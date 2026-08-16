package com.jotbill.app.importing

import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.PushbackReader
import java.nio.charset.Charset

/**
 * 阶段六 T3：轻量 CSV 表格读取器。
 *
 * 只覆盖官方账单所需的 CSV 规则：逗号分隔、双引号包裹字段、引号内可含
 * 逗号/换行、双引号转义为两个双引号；不引入第三方 CSV 依赖。
 * 流式逐行读取，不在内存中整表展开。
 */
internal object CsvTableReader {

    /**
     * 流式读取 CSV 行。
     *
     * @param onRow 每读到一个完整逻辑行调用一次；返回 false 提前终止
     * @return 成功读取的逻辑行总数（提前终止时返回已读行数）
     */
    fun readRows(
        file: File,
        encoding: String?,
        onRow: (rowNumber: Int, columns: List<String>) -> Boolean
    ): Int {
        val charset = runCatching { Charset.forName(encoding ?: ImportFilePreparer.ENCODING_UTF8) }
            .getOrElse { Charsets.UTF_8 }
        val inputReader = InputStreamReader(FileInputStream(file), charset)
        val pushback = PushbackReader(inputReader, 1)
        return pushback.use { input ->
            val row = mutableListOf<String>()
            val field = StringBuilder()
            var inQuotes = false
            var rowNumber = 0
            var next: Int
            var previousWasCr = false

            fun finishField() {
                row += field.toString()
                field.setLength(0)
            }

            fun finishRow(): Boolean {
                finishField()
                rowNumber += 1
                val columns = row.toList()
                row.clear()
                return onRow(rowNumber, columns)
            }

            while (true) {
                next = input.read()
                if (next < 0) break
                val char = next.toChar()
                when {
                    inQuotes -> {
                        if (char == '"') {
                            val lookahead = input.read()
                            if (lookahead == '"'.code) {
                                field.append('"')
                            } else {
                                if (lookahead >= 0) input.unread(lookahead)
                                inQuotes = false
                            }
                        } else {
                            field.append(char)
                        }
                    }
                    char == '"' -> inQuotes = true
                    char == ',' -> finishField()
                    char == '\r' -> {
                        if (!finishRow()) return rowNumber
                        previousWasCr = true
                    }
                    char == '\n' -> {
                        if (previousWasCr) {
                            previousWasCr = false
                        } else if (!finishRow()) {
                            return rowNumber
                        }
                    }
                    else -> {
                        previousWasCr = false
                        field.append(char)
                    }
                }
            }
            if (field.isNotEmpty() || row.isNotEmpty()) {
                finishRow()
            }
            rowNumber
        }
    }
}

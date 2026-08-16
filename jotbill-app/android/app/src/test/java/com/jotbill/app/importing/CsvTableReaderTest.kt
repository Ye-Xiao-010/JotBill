package com.jotbill.app.importing

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class CsvTableReaderTest {
    @Test
    fun readsQuotedCommasQuotesAndEmbeddedNewlines() {
        val file = File.createTempFile("csv-reader", ".csv")
        file.writeText("a,\"b,1\",\"line1\nline2\",\"say \"\"hi\"\"\"\r\n2,,x,y\n")
        val rows = mutableListOf<List<String>>()
        CsvTableReader.readRows(file, "UTF-8") { _, columns -> rows += columns; true }
        assertEquals(2, rows.size)
        assertEquals("b,1", rows[0][1])
        assertEquals("line1\nline2", rows[0][2])
        assertEquals("say \"hi\"", rows[0][3])
        assertEquals(listOf("2", "", "x", "y"), rows[1])
    }
}

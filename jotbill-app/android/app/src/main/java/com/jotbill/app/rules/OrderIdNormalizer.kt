package com.jotbill.app.rules

import java.util.Locale

/**
 * 订单号格式归一器（M5）：**只做格式归一，不做跨语义映射**。
 *
 * 归一范围：首尾空白剔除、全角英数转半角、英文统一大写（Locale.ROOT）。
 * 订单号是「同一笔交易」跨源归并（P1/L1）的唯一客观键，而不同采集通道
 * （页面文本/通知/规则/导入/手动）对同一单号的大小写与全半角写法可能不一致，
 * 归一前会因格式差异漏并、产出双卡。
 *
 * 明确不做：不同来源单号的语义等同（如「页面订单号」与「通知商户单号」是否
 * 同值）—— 代码推不出该映射，真机取证前不得强行等同（M5 边界拍板）。
 */
object OrderIdNormalizer {

    /**
     * 归一化订单号；空白输入返回 null（与"无订单号"语义对齐）。
     *
     * @param value 原始订单号文本
     * @return 归一化后的订单号（trim + 全角转半角 + 大写）；输入为 null/空白返回 null
     */
    fun normalize(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val halfWidth = buildString(trimmed.length) {
            for (ch in trimmed) {
                append(
                    when (ch) {
                        in '０'..'９' -> '0' + (ch - '０')
                        in 'Ａ'..'Ｚ' -> 'A' + (ch - 'Ａ')
                        in 'ａ'..'ｚ' -> 'a' + (ch - 'ａ')
                        else -> ch
                    }
                )
            }
        }
        return halfWidth.uppercase(Locale.ROOT)
    }
}

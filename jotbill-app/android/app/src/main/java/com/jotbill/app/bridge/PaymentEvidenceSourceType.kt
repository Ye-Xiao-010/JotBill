package com.jotbill.app.bridge

import java.util.Locale

/**
 * 支付证据 `sourceType` 的归一化（桥接层专用）。
 *
 * ⚠️ 原先是 `recognition/CheckoutContract.kt` 里的同名函数。精简版移除识别
 * 链路时该文件被删，但 `BridgeDtoMapper.evidence()` 仍在用 —— 已落库的
 * `payment_evidence` 行仍带这个字段（Room schema 18 张表按约束不可动），
 * 待确认中心的证据区块要把它映射给 RN。
 *
 * 归一化规则原样保留，不做"顺手简化"：
 * - 大小写不敏感，`NOTIFICATION` / `notification` 同义；
 * - `accessibility_result` 是历史别名，与 `accessibility` 同义；
 * - 无法识别的值返回 null，由调用方抛 INVALID_DATA —— **不静默兜底**成某个
 *   合法值，否则库里一条脏数据会在 UI 上显示成错误的来源，且无从察觉。
 */
internal fun normalizePaymentEvidenceSourceType(value: String?): String? =
    when (value?.trim()?.lowercase(Locale.ROOT)) {
        "notification" -> "notification"
        "accessibility", "accessibility_result" -> "accessibility"
        "manual" -> "manual"
        else -> null
    }
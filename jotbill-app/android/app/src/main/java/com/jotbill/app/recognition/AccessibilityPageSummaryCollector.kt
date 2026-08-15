package com.jotbill.app.recognition

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest
import java.util.Locale

/**
 * 从无障碍节点树生成结算页观察摘要。
 *
 * 该收集器只生成内存中的布尔门禁和哈希，不保留原始文本，也不执行截图、点击或网络操作。
 */
internal object AccessibilityPageSummaryCollector {
    private const val MAX_NODE_COUNT = 320
    private const val MAX_DEPTH = 24
    private const val MAX_TOKEN_LENGTH = 240
    private const val MAX_NORMALIZED_TEXT_LENGTH = 8_000
    private const val FINGERPRINT_VERSION = "checkout-page-v1"
    private const val HEX = "0123456789abcdef"

    private val whitespacePattern = Regex("\\s+")
    private val countdownPattern = Regex(
        """(?i)(还剩|剩余|倒计时|countdown)?\s*\d{1,2}\s*[:：]\s*\d{2}(?:\s*[:：]\s*\d{2})?"""
    )
    private val clockPattern = Regex(
        """\b(?:[01]?\d|2[0-3])\s*[:：]\s*[0-5]\d(?:\s*[:：]\s*[0-5]\d)?\b"""
    )
    private val datePattern = Regex(
        """\b\d{4}\s*[-/.]\s*\d{1,2}\s*[-/.]\s*\d{1,2}\b"""
    )
    private val chineseDatePattern = Regex(
        """\d{4}\s*年\s*\d{1,2}\s*月\s*\d{1,2}\s*日?"""
    )
    private val chineseTimePattern = Regex(
        """\d{1,2}\s*时\s*\d{1,2}\s*分(?:\s*\d{1,2}\s*秒)?"""
    )
    private val relativeTimePattern = Regex(
        """(?i)(刚刚|今天|昨天|前天|\d+\s*(秒|分钟|小时|天)前)"""
    )
    private val uuidPattern = Regex(
        """(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b"""
    )
    private val longNumberPattern = Regex("\\b\\d{12,24}\\b")
    private val currencyPattern = Regex("""[¥￥$＄€£]""")
    private val groupingSeparatorPattern = Regex("""(?<=\d)[,，](?=\d)""")
    private val noisePattern = Regex(
        """(?i)(广告|推荐商品|猜你喜欢|loading|加载中|骨架屏|占位符|动画中|轮播|刷新中|请稍候|carousel|placeholder)"""
    )

    private val finalAmountTerms = listOf(
        "应付",
        "实付",
        "订单金额",
        "订单总额",
        "优惠后合计",
        "优惠后金额",
        "优惠后应付",
        "待支付",
        "待付款",
        "需付款",
        "付款金额"
    )
    private val checkoutActionTerms = listOf(
        "提交订单",
        "去支付",
        "确认订单",
        "立即支付",
        "确认支付",
        "同意并支付",
        "去付款"
    )
    private val cartTerms = listOf("购物车", "购物袋", "去结算", "全选")
    private val productTerms = listOf("商品详情", "加入购物车", "立即购买", "选择规格")
    private val browsingTerms = listOf("搜索", "推荐", "首页", "分类", "猜你喜欢")
    private val sensitiveTerms = listOf(
        "密码",
        "验证码",
        "校验码",
        "二维码",
        "付款码",
        "人脸识别",
        "指纹",
        "银行卡",
        "安全验证",
        "身份验证"
    )
    private val sensitiveAsciiPattern = Regex("""(?i)\b(password|pin)\b""")
    private val paymentHandoffTerms = listOf(
        "支付密码",
        "付款码",
        "二维码",
        "支付授权",
        "请输入密码",
        "人脸识别"
    )

    /** 支付结果页关键词：支付成功/失败/已完成/已付款等，用于判定 PAYMENT_RESULT。 */
    private val paymentResultTerms = listOf(
        "支付成功",
        "支付失败",
        "交易完成",
        "交易关闭",
        "已付款",
        "支付完成",
        "付款成功",
        "订单已支付",
        "订单已提交"
    )

    fun collect(
        shoppingPackage: String,
        windowClass: String?,
        root: AccessibilityNodeInfo,
        observedAt: Long
    ): CheckoutPageObservation {
        val accumulator = NodeSummaryAccumulator()
        collectNode(root, accumulator, depth = 0)

        val normalizedText = normalizeTokens(accumulator.tokens)
        val lowerText = normalizedText.lowercase(Locale.ROOT)
        val hasFinalAmountSignal = hasFinalAmountSignal(lowerText)
        val hasCheckoutActionSignal = containsAny(lowerText, checkoutActionTerms)
        val isSensitivePage = accumulator.sensitive || containsSensitiveSignal(lowerText)
        val pageRole = classifyPageRole(
            lowerText = lowerText,
            hasFinalAmountSignal = hasFinalAmountSignal,
            hasCheckoutActionSignal = hasCheckoutActionSignal,
            isSensitivePage = isSensitivePage
        )
        val normalizedWindowClass = normalizeWindowClass(windowClass)
        val normalizedTextHash = sha256(normalizedText)
        val pageFingerprint = sha256(
            listOf(
                FINGERPRINT_VERSION,
                shoppingPackage.trim(),
                normalizedWindowClass,
                pageRole.storageValue,
                normalizedTextHash
            ).joinToString("|")
        )

        return CheckoutPageObservation(
            shoppingPackage = shoppingPackage.trim(),
            pageRole = pageRole,
            pageFingerprint = pageFingerprint,
            hasFinalAmountSignal = hasFinalAmountSignal,
            hasCheckoutActionSignal = hasCheckoutActionSignal,
            isSensitivePage = isSensitivePage,
            windowClass = normalizedWindowClass,
            normalizedTextHash = normalizedTextHash,
            observedAt = observedAt
        )
    }

    private fun collectNode(
        node: AccessibilityNodeInfo,
        accumulator: NodeSummaryAccumulator,
        depth: Int
    ) {
        if (depth > MAX_DEPTH || accumulator.visitedNodes >= MAX_NODE_COUNT) {
            return
        }

        accumulator.visitedNodes += 1
        val visible = depth == 0 || node.isVisibleToUser
        if (node.isPassword) {
            accumulator.sensitive = true
        }
        if (visible) {
            node.text?.toString()?.let(accumulator::addText)
            node.contentDescription?.toString()?.let(accumulator::addText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.let(accumulator::addText)
                node.tooltipText?.toString()?.let(accumulator::addText)
            }

            node.className?.toString()?.let { className ->
                accumulator.addSemantic("node:${className.substringAfterLast('.')}")
                if (className.contains("password", ignoreCase = true)) {
                    accumulator.sensitive = true
                }
            }
            node.viewIdResourceName?.let { viewId ->
                accumulator.addSemantic("view:${viewId.substringAfterLast('/')}")
                if (viewId.contains("password", ignoreCase = true) ||
                    viewId.contains("verification", ignoreCase = true)
                ) {
                    accumulator.sensitive = true
                }
            }
        }

        for (index in 0 until node.childCount) {
            if (accumulator.visitedNodes >= MAX_NODE_COUNT) {
                break
            }
            val child = try {
                node.getChild(index)
            } catch (_: RuntimeException) {
                null
            }
            if (child != null) {
                try {
                    collectNode(child, accumulator, depth + 1)
                } finally {
                    child.recycle()
                }
            }
        }
    }

    private fun normalizeTokens(tokens: List<String>): String {
        return tokens
            .asSequence()
            .mapNotNull(::normalizeToken)
            .joinToString(" ")
            .take(MAX_NORMALIZED_TEXT_LENGTH)
    }

    private fun normalizeToken(rawToken: String): String? {
        var token = rawToken
            .replace('\u00a0', ' ')
            .trim()
        if (token.isBlank() || noisePattern.containsMatchIn(token)) {
            return null
        }

        token = countdownPattern.replace(token, " ")
        token = clockPattern.replace(token, " ")
        token = datePattern.replace(token, " ")
        token = chineseDatePattern.replace(token, " ")
        token = chineseTimePattern.replace(token, " ")
        token = relativeTimePattern.replace(token, " ")
        token = uuidPattern.replace(token) { match ->
            " id_${sha256(match.value).take(16)} "
        }
        token = longNumberPattern.replace(token) { match ->
            " id_${sha256(match.value).take(16)} "
        }
        token = currencyPattern.replace(token, " currency ")
        token = token.replace("元", " currency ")
        token = groupingSeparatorPattern.replace(token, "")
        token = whitespacePattern.replace(token, " ")
            .lowercase(Locale.ROOT)
            .trim()

        return token.take(MAX_TOKEN_LENGTH).takeIf { it.isNotBlank() }
    }

    private fun hasFinalAmountSignal(text: String): Boolean {
        if (containsAny(text, finalAmountTerms)) {
            return true
        }
        return (text.contains("合计") || text.contains("总计")) &&
            (text.contains("订单") || text.contains("收货") || text.contains("优惠后"))
    }

    private fun classifyPageRole(
        lowerText: String,
        hasFinalAmountSignal: Boolean,
        hasCheckoutActionSignal: Boolean,
        isSensitivePage: Boolean
    ): CheckoutPageRole {
        if (containsAny(lowerText, paymentHandoffTerms)) {
            return CheckoutPageRole.PAYMENT_HANDOFF
        }
        // 支付结果页判定：必须在支付应用包名上下文中命中结果页关键词
        // 不基于单一张图或单一关键词，且敏感页（密码/付款码）已被 paymentHandoffTerms 拦截
        if (containsAny(lowerText, paymentResultTerms)) {
            return CheckoutPageRole.PAYMENT_RESULT
        }
        if (!isSensitivePage && hasFinalAmountSignal && hasCheckoutActionSignal) {
            return CheckoutPageRole.CHECKOUT
        }
        if (containsAny(lowerText, cartTerms)) {
            return CheckoutPageRole.CART
        }
        if (containsAny(lowerText, productTerms)) {
            return CheckoutPageRole.PRODUCT_DETAIL
        }
        if (containsAny(lowerText, browsingTerms)) {
            return CheckoutPageRole.SHOPPING_HOME
        }
        return CheckoutPageRole.UNKNOWN
    }

    private fun containsAny(text: String, terms: List<String>): Boolean = terms.any(text::contains)

    private fun containsSensitiveSignal(text: String): Boolean =
        containsAny(text, sensitiveTerms) || sensitiveAsciiPattern.containsMatchIn(text)

    private fun normalizeWindowClass(windowClass: String?): String =
        whitespacePattern.replace(windowClass.orEmpty().trim(), " ").lowercase(Locale.ROOT)

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0f])
            }
        }
    }

    private class NodeSummaryAccumulator {
        val tokens = ArrayList<String>()
        var visitedNodes: Int = 0
        var sensitive: Boolean = false

        fun addText(value: String) {
            if (sensitiveTerms.any { value.contains(it, ignoreCase = true) } ||
                sensitiveAsciiPattern.containsMatchIn(value)
            ) {
                sensitive = true
            }
            if (value.isNotBlank()) {
                if (tokens.size < MAX_NODE_COUNT) {
                    tokens += value.take(MAX_TOKEN_LENGTH)
                }
            }
        }

        fun addSemantic(value: String) {
            if (tokens.size < MAX_NODE_COUNT) {
                tokens += value.take(MAX_TOKEN_LENGTH)
            }
        }
    }
}

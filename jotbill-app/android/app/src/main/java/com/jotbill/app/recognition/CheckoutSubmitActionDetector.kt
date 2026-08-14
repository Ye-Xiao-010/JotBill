package com.jotbill.app.recognition

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * 从无障碍点击事件中保守识别提交订单动作。
 *
 * 只接受明确的点击事件和可验证的节点动作，不执行节点点击，也不把普通文本变化当成购买意图。
 */
internal object CheckoutSubmitActionDetector {
    private const val MAX_ANCESTOR_DEPTH = 4

    private val submitLabels = setOf(
        "提交订单",
        "提交订单按钮",
        "去支付",
        "去支付按钮",
        "确认订单",
        "确认订单按钮",
        "立即支付",
        "立即支付按钮",
        "确认支付",
        "确认支付按钮",
        "同意并支付",
        "同意并支付按钮",
        "去付款",
        "去付款按钮"
    )

    private val submitResourceMarkers = setOf(
        "submitorder",
        "gopay",
        "confirmorder",
        "paynow",
        "confirmpayment",
        "agreeandpay",
        "gopayment"
    )

    fun isSubmitActionEvent(
        event: AccessibilityEvent,
        source: AccessibilityNodeInfo?,
        shoppingPackage: String
    ): Boolean {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED || source == null) {
            return false
        }
        if (shoppingPackage.isBlank() || source.packageName?.toString()?.trim() != shoppingPackage) {
            return false
        }

        val ownedAncestors = ArrayList<AccessibilityNodeInfo>(MAX_ANCESTOR_DEPTH)
        var current = source
        var depth = 0
        return try {
            while (depth <= MAX_ANCESTOR_DEPTH) {
                // current 可能为空，用安全调用避免类型不匹配；为空时视为非提交节点。
                if (current?.let { isSubmitNode(it) } == true) {
                    return true
                }

                val parent = try {
                    // current 可能为空，用安全调用避免 nullable receiver 不安全调用。
                    current?.parent
                } catch (_: RuntimeException) {
                    null
                } ?: break

                if (
                    parent === current ||
                    parent === source ||
                    ownedAncestors.any { ancestor -> ancestor === parent } ||
                    parent.packageName?.toString()?.trim() != shoppingPackage
                ) {
                    break
                }
                ownedAncestors += parent
                current = parent
                depth += 1
            }
            false
        } finally {
            ownedAncestors.asReversed().forEach { node ->
                try {
                    node.recycle()
                } catch (_: RuntimeException) {
                    // 系统节点已回收时无需再次处理。
                }
            }
        }
    }

    private fun isSubmitNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser || !node.isClickable || !hasClickAction(node)) {
            return false
        }

        return isSubmitLabel(node.text) ||
            isSubmitLabel(node.contentDescription) ||
            isSubmitResourceId(node.viewIdResourceName)
    }

    private fun hasClickAction(node: AccessibilityNodeInfo): Boolean =
        node.actionList.any { action ->
            action.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id
        }

    private fun isSubmitLabel(value: CharSequence?): Boolean {
        val normalized = value
            ?.toString()
            ?.replace(Regex("\\s+"), "")
            ?.lowercase(Locale.ROOT)
            ?.trim()
            ?: return false
        return normalized in submitLabels
    }

    private fun isSubmitResourceId(value: String?): Boolean {
        val normalized = value
            ?.substringAfterLast('/')
            ?.lowercase(Locale.ROOT)
            ?.replace(Regex("[^a-z0-9]"), "")
            ?.takeIf { it.isNotBlank() }
            ?: return false
        return submitResourceMarkers.any { marker ->
            normalized == marker || normalized.endsWith(marker)
        }
    }
}

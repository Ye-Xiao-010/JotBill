package com.jotbill.app.bookkeeping

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍节点树的只读投影。
 *
 * 规则引擎（dump / 定位 / 抽取）只依赖本接口，纯 JVM 单测可以手工构造页面树，
 * 不需要 Robolectric。生产实现 [AccessibilityPageNode] 包裹 AccessibilityNodeInfo，
 * 与现有识别管线各自持有节点引用、互不 recycle。
 */
interface PageNode {
    /** 节点 text；空语义与 mipay 一致：null 与空串都不进入 dump。 */
    val text: String?

    /** 节点 contentDescription。 */
    val contentDescription: String?

    /** 子节点数量。 */
    val childCount: Int

    /** 取第 index 个子节点；系统节点不可读时返回 null。 */
    fun childAt(index: Int): PageNode?
}

/** [PageNode] 的生产实现：包裹 AccessibilityNodeInfo，不持有任何可变状态。 */
class AccessibilityPageNode(private val node: AccessibilityNodeInfo) : PageNode {
    override val text: String?
        get() = try {
            node.text?.toString()
        } catch (_: RuntimeException) {
            null
        }

    override val contentDescription: String?
        get() = try {
            node.contentDescription?.toString()
        } catch (_: RuntimeException) {
            null
        }

    override val childCount: Int
        get() = try {
            node.childCount
        } catch (_: RuntimeException) {
            0
        }

    override fun childAt(index: Int): PageNode? = try {
        node.getChild(index)?.let(::AccessibilityPageNode)
    } catch (_: RuntimeException) {
        null
    }
}

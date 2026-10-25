package com.jotbill.app.bookkeeping

/** 测试用页面节点：直接给定 text/contentDescription/children。 */
class FakePageNode(
    override val text: String?,
    override val contentDescription: String?,
    val children: List<FakePageNode> = emptyList()
) : PageNode {
    override val childCount: Int get() = children.size
    override fun childAt(index: Int): PageNode? = children.getOrNull(index)
}

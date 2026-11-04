package com.jotbill.app.recognition

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.jotbill.app.utils.JotBillLog
import java.security.MessageDigest
import java.util.Locale

/**
 * 容量配额的只读视图，供单测校验配额之间的数量关系。
 *
 * 节点树无法在 JVM 单测中构造，但"文本配额必须显著大于语义配额"是页面角色
 * 判定的关键不变量（京东结算页故障即源于二者共用同一上限），必须可验证。
 */
internal object AccessibilityPageSummaryCollectorBudget {
    /** 节点遍历数量上限。 */
    val maxNodeCount: Int get() = AccessibilityPageSummaryCollector.maxNodeCountForTest

    /** 语义标记容量配额。 */
    val semanticBudget: Int get() = AccessibilityPageSummaryCollector.semanticBudgetForTest

    /** tokens 总容量上限。 */
    val totalTokenCapacity: Int
        get() = AccessibilityPageSummaryCollector.totalTokenCapacityForTest
}

/**
 * 最终金额信号命中的分支。
 *
 * 存在意义是**可取证**：[AccessibilityPageSummaryCollector.hasFinalAmountSignal]
 * 只吐一个布尔，出问题时无法知道是哪条兜底放行的，而
 * [PageAmountAccumulator.resolveFinalAmountMinor] 是否能提取出数值
 * 恰恰与走哪条分支强相关（详见 [PageAmountDiagnostics] 文件头）。
 *
 * ⚠️ 2026-09-29 新增，**判据本身未变** —— `hasFinalAmountSignal` 已改为委托
 * [AccessibilityPageSummaryCollector.finalAmountSignalEvidence]，三条分支与顺序
 * 逐字照搬原实现，行为等价。**声明为顶层**以便 [PageAmountDiagnostics] 引用。
 */
internal enum class FinalAmountSignalEvidence {
    /** 词表标签直接命中（`实付` / `订单金额` …）。 */
    LABEL_TERM,

    /** 「结算动作词 + 页面存在货币金额」—— 拼多多无标签结算页靠这条。 */
    ACTION_WITH_CURRENCY,

    /** 「合计/总计 + 订单/收货/优惠后」。 */
    SUBTOTAL_WITH_ORDER,

    /** 三条分支均不成立。 */
    NONE
}

/**
 * 从无障碍节点树生成结算页观察摘要。
 *
 * 该收集器只生成内存中的布尔门禁和哈希，不保留原始文本，也不执行截图、点击或网络操作。
 */
internal object AccessibilityPageSummaryCollector {
    private const val TAG = "PageSummaryCollector"
    private const val MAX_NODE_COUNT = 320
    private const val MAX_DEPTH = 24
    private const val MAX_TOKEN_LENGTH = 240
    private const val MAX_NORMALIZED_TEXT_LENGTH = 8_000
    /**
     * 指纹版本。
     *
     * v2：文本与语义标记拆分独立容量配额后，token 集合与顺序均发生变化，
     * 同一页面的指纹与 v1 不同。升版本号使新旧指纹显式隔离，避免升级后
     * 残留的历史会话指纹与新指纹意外匹配。
     */
    private const val FINGERPRINT_VERSION = "checkout-page-v2"
    private const val HEX = "0123456789abcdef"

    /**
     * 语义标记（node:类名 / view:资源id）的容量配额。
     *
     * 语义标记只服务于指纹的结构感知，不参与页面角色判定，数量却是文本的数倍
     * （每个节点最多贡献 2 个）。限制在此配额内，为文本留出充足槽位。
     */
    private const val MAX_SEMANTIC_TOKEN_BUDGET = 320

    /**
     * tokens 总容量上限，文本可用到该上限。
     *
     * 京东结算页 154 个节点中仅 34 个带文本，900 的容量对文本而言绰绰有余；
     * 该上限的作用是防御异常巨大的节点树，而非日常裁剪。
     */
    private const val MAX_TOTAL_TOKEN_COUNT = 900

    internal val maxNodeCountForTest: Int get() = MAX_NODE_COUNT
    internal val semanticBudgetForTest: Int get() = MAX_SEMANTIC_TOKEN_BUDGET
    internal val totalTokenCapacityForTest: Int get() = MAX_TOTAL_TOKEN_COUNT

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
    /** 页面指纹不得保留支付码、卡号、验证码等连续数字的原值或哈希。 */
    private val sensitiveNumberPattern = Regex("""(?<!\d)(?:\d[\s-]?){3,24}(?!\d)""")
    private val currencyPattern = Regex("""[¥￥$＄€£]""")
    private val groupingSeparatorPattern = Regex("""(?<=\d)[,，](?=\d)""")
    private val noisePattern = Regex(
        """(?i)(广告|推荐商品|猜你喜欢|loading|加载中|骨架屏|占位符|动画中|轮播|刷新中|请稍候|carousel|placeholder)"""
    )

    /**
     * 轮播/翻页类控件的简单类名。
     *
     * 拼多多结算页用 ViewSwitcher/ViewFlipper 滚动展示"N 人已拼""还剩 N 件"等
     * 动态文案。这类文本不含噪声关键词，会原样进入指纹并每隔数秒生成新哈希，
     * 被下游误判为新订单边界。因此整棵子树的文本都不纳入指纹，只保留控件语义标记。
     */
    private val rotatingWidgetClassNames = setOf(
        "ViewSwitcher",
        "ViewFlipper",
        "TextSwitcher",
        "AdapterViewFlipper",
        "Marquee",
        "MarqueeTextView"
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

    /**
     * 商品规格选择层的数量调节控件文案（成对出现才构成证据，Y3 2026-09-10）。
     *
     * 真机取证（2026-09-10 .tmp-ui1.xml）：拼多多商品规格弹层以普通 TextView
     * 定时轮播价格/折扣/榜单文案，隐藏节点携带支付动作词（actionSignal=true
     * 而 33 条可见文本无任何动作词），页面被误判 CHECKOUT 后每次轮播换字都
     * 触发新会话自动截图。结算页数量为只读展示，增减控件成对出现只存在于
     * 规格选择/购物车编辑场景，是商品页的强结构特征。
     */
    private val quantityMinusTerms = listOf("减少数量")
    private val quantityPlusTerms = listOf("增加数量")

    /** 规格选择引导词；带冒号形式比裸"已选"更特异，避免误伤统计类文案。 */
    private val specGuideTerms = listOf("已选：", "已选:")
    private val cartTerms = listOf("购物车", "购物袋", "去结算", "全选")
    private val productTerms = listOf("商品详情", "加入购物车", "立即购买", "选择规格")
    private val browsingTerms = listOf("搜索", "推荐", "首页", "分类", "猜你喜欢")

    /**
     * 结算/下单流程的窗口类名特征词（小写）。
     *
     * 词条只取真机实测类名中稳定出现的模块段，不做推测：
     * - settlement / fillorder：京东 PopupNewFillOrderActivity 所在结算模块；
     * - checkout / confirmorder / placeorder：主流电商结算页通用命名。
     * 不收录 order 单词本身——订单列表 OrderListActivityTaro、订单详情
     * OrderDetailActivityTaro 都会命中，会把已完成订单页误判成结算页。
     */
    private val checkoutClassTerms = listOf(
        "settlement",
        "fillorder",
        "checkout",
        "confirmorder",
        "placeorder"
    )
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
    private val sensitiveAsciiPattern = Regex("""(?i)\b(password|pin|otp)\b""")
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
        "支付取消",
        "付款失败",
        "订单取消",
        "订单关闭",
        "支付未完成"
    )

    /**
     * 判断控件类名是否属于轮播/翻页容器。
     *
     * 提取为独立方法便于单测覆盖：真机节点树无法在 JVM 单测中构造，
     * 但类名判定是指纹稳定性的关键分支，必须可验证。
     *
     * @param className 完整类名（含包名）或简单类名。
     * @return true 表示该控件及其子树的文本不应纳入指纹。
     */
    internal fun isRotatingWidgetClass(className: String?): Boolean {
        val simpleName = className?.trim()?.substringAfterLast('.').orEmpty()
        return simpleName.isNotBlank() && simpleName in rotatingWidgetClassNames
    }

    /**
     * 判断窗口类名是否具备结算页语义。
     *
     * 提取为独立方法便于单测覆盖。京东结算页
     * （com.jd.lib.settlement.fillorder.activity.PopupNewFillOrderActivity）
     * 含「猜你喜欢」推荐位，会命中 browsingTerms 兜底而被判为 SHOPPING_HOME。
     * 类名中的 settlement/fillorder 是比推荐位文案强得多的结算证据，
     * 应优先于文本词表兜底。
     *
     * @param normalizedWindowClass 已小写归一化的窗口类名。
     * @return true 表示类名指向结算或下单流程页面。
     */
    internal fun hasCheckoutClassSemantic(normalizedWindowClass: String): Boolean =
        normalizedWindowClass.isNotBlank() &&
            checkoutClassTerms.any { normalizedWindowClass.contains(it) }

    /**
     * 清空 L3 跨帧易变追踪的全部观察状态。
     *
     * 服务中断/销毁时由无障碍服务调用：页面观察不再连续，陈旧的跨帧观察
     * 会污染重新连接后的易变判定（如中断前观察到轮播、恢复后页面早已
     * 离开）。观察窗口有 TTL（默认 15s），不清空也会自然过期，但显式
     * 清空使边界语义更明确。
     */
    fun resetVolatileTracker() {
        NodeSummaryAccumulator.volatileTracker.clear()
    }

    /**
     * 判定页面是否为商品规格选择层（Y3，2026-09-10；同日回归修正）。
     *
     * 真机回归取证（14:08:02.248）：拼多多"确认订单"结算层与商品规格层共用
     * 同一窗口（wd2.f0 弹层），结算层同样带"已选：/数量对"文案——原判据
     * 把真结算页误杀成 PRODUCT_DETAIL，整条自动截图链路失效（零 ACCEPTED、
     * 支付落孤立证据）。修正：规格层证据与**提交订单动作**并存时，结算层
     * 证据优先——checkoutActionTerms 是结算层的结构性文案，规格选择完成
     * 进入确认态时页面必然携带"提交订单/确认订单"按钮。
     *
     * @param lowerText 已小写归一化的页面文本。
     * @param hasCheckoutActionSignal 页面是否含结算动作词（由调用方判定后传入）。
     * @return true 表示商品规格选择层（无提交动作），角色判定时不得返回 CHECKOUT。
     */
    internal fun isProductSpecSelectionLayer(
        lowerText: String,
        hasCheckoutActionSignal: Boolean
    ): Boolean {
        if (hasCheckoutActionSignal) {
            return false
        }
        val hasQuantityPair =
            containsAny(lowerText, quantityMinusTerms) && containsAny(lowerText, quantityPlusTerms)
        val hasSpecGuide = containsAny(lowerText, specGuideTerms)
        return hasQuantityPair || hasSpecGuide
    }

    /**
     * 采集页面观察摘要。
     *
     * @param includeHiddenText 是否把 isVisibleToUser=false 但带文本的节点纳入
     * 语义判定。弹窗式结算页与微信支付结果页的关键节点都会间歇上报不可见，
     * 关闭该开关会导致动作信号丢失、页面角色抖动，因此默认开启。
     */
    fun collect(
        shoppingPackage: String,
        windowClass: String?,
        root: AccessibilityNodeInfo,
        observedAt: Long,
        includeHiddenText: Boolean = true
    ): CheckoutPageObservation {
        val accumulator = NodeSummaryAccumulator()
        // L3 易变观察的作用域：包名+窗口类名。跨窗口/跨应用隔离，同一窗口
        // 的轮播位置才能在时间轴上被连续观察；支付结果页扫描等独立调用点
        // 天然以不同 scopeKey 隔离，互不污染。
        accumulator.scopeKey = "${shoppingPackage.trim()}|${normalizeWindowClass(windowClass)}"
        collectNode(
            node = root,
            accumulator = accumulator,
            depth = 0,
            includeHiddenText = includeHiddenText
        )

        // 信号文本：角色/金额/动作/敏感判定的输入，不受易变剔除影响——
        // 轮播位置上的"券后 ¥9.9"仍参与 hasFinalAmountSignal 与
        // isProductSpecSelectionLayer 判定，语义门禁不因指纹稳定化而削弱。
        val normalizedText = normalizeTokens(accumulator.signalTokens)
        val lowerText = normalizedText.lowercase(Locale.ROOT)
        // P2-8：兜底分支不能再看【已脱敏】文本里的数字（3 位以上已被抹成
        // " number "，¥128.00 → currency number .00 ⇒ 正则不匹配 ⇒ 整链失效）。
        // 改用同一次遍历从【原始】文本得到的"页面是否存在货币金额"。
        val hasCurrencyAmount = accumulator.amountAccumulator.hasCurrencyAmount
        val amountSignalEvidence = finalAmountSignalEvidence(lowerText, hasCurrencyAmount)
        val hasFinalAmountSignal = amountSignalEvidence != FinalAmountSignalEvidence.NONE
        val hasCheckoutActionSignal = containsAny(lowerText, checkoutActionTerms)
        if (accumulator.textDropped) {
            JotBillLog.w(
                TAG,
                "页面文本因容量上限被截断: pkg=$shoppingPackage, " +
                    "nodes=${accumulator.visitedNodes}, tokens=${accumulator.signalTokens.size}, " +
                    "amountSignal=$hasFinalAmountSignal, actionSignal=$hasCheckoutActionSignal"
            )
        }
        val isSensitivePage = accumulator.sensitive || containsSensitiveSignal(lowerText)
        val normalizedWindowClass = normalizeWindowClass(windowClass)
        val checkoutClassSemantic = hasCheckoutClassSemantic(normalizedWindowClass)
        val pageRole = classifyPageRole(
            lowerText = lowerText,
            hasFinalAmountSignal = hasFinalAmountSignal,
            hasCheckoutActionSignal = hasCheckoutActionSignal,
            isSensitivePage = isSensitivePage,
            hasCheckoutClassSemantic = checkoutClassSemantic
        )
        // 订单总额（分）；null = 无法判定（无金额/多金额歧义）⇒ 调用方保守沿用旧会话。
        // 提取与诊断共用同一次收口结果，**不得**在别处再调一次以免读数漂移。
        val finalAmountMinor = accumulator.amountAccumulator.resolveFinalAmountMinor()
        // 金额取证（2026-09-29）：finalAmountMinor 恒为 null ⇒ 归属判定恒 0 命中
        // ⇒ 同一笔支付产生多条待确认。中间态（带标签池 / 无标签兜底 / 信号分支）
        // 是判定成因的唯一依据，故落盘。**纯诊断，不参与任何判定**（见 PageAmountDiagnostics）。
        logAmountDiagnostics(
            shoppingPackage = shoppingPackage,
            pageRole = pageRole,
            windowClass = windowClass,
            accumulator = accumulator,
            lowerText = lowerText,
            hasCurrencyAmount = hasCurrencyAmount,
            hasCheckoutActionSignal = hasCheckoutActionSignal,
            finalAmountMinor = finalAmountMinor,
            evidence = amountSignalEvidence
        )
        // 指纹文本：只含稳定 token。轮播位置（观察窗口内出现 ≥2 个不同文本）
        // 的 token 已在采集时剔除（见 collectNode 的 volatileTracker 分支），
        // 换商品时非轮播 token（标题/规格/店铺/推荐位）大量同变，指纹仍必变。
        val stableText = normalizeTokens(accumulator.fingerprintTokens)
        val normalizedTextHash = sha256(normalizedText)
        val pageFingerprint = sha256(
            listOf(
                FINGERPRINT_VERSION,
                shoppingPackage.trim(),
                normalizedWindowClass,
                pageRole.storageValue,
                sha256(stableText)
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
            observedAt = observedAt,
            hasCheckoutClassSemantic = checkoutClassSemantic,
            // 订单总额（分）；null = 无法判定（无金额/多金额歧义）⇒ 调用方保守沿用旧会话。
            finalAmountMinor = finalAmountMinor
        )
    }

    /**
     * 金额取证日志（2026-09-29 诊断设施，**只读不改行为**）。
     *
     * [collect] 由**逐内容变更事件**驱动（`CheckoutAccessibilityService:853`），
     * 一次结算页停留可能产生数十次采集，直接打日志会淹没 diag 文件，故两道收敛：
     * ① 只对「结算页 / 有最终金额信号」的页面打 —— 归属判定问题只出在这两类页；
     * ② 同一页面状态下**只打一行**（元组未变则跳过），金额或标签一变即补打新行。
     *
     * ⚠️ [lastAmountDiagKey] 是本文件**唯一的可变状态**，仅用于诊断去重；
     * 它不参与任何判定，即使失准也只影响日志条数。
     */
    private fun logAmountDiagnostics(
        shoppingPackage: String,
        pageRole: CheckoutPageRole,
        windowClass: String?,
        accumulator: NodeSummaryAccumulator,
        lowerText: String,
        hasCurrencyAmount: Boolean,
        hasCheckoutActionSignal: Boolean,
        finalAmountMinor: Long?,
        evidence: FinalAmountSignalEvidence
    ) {
        if (pageRole != CheckoutPageRole.CHECKOUT && evidence == FinalAmountSignalEvidence.NONE) {
            return
        }
        val labeledTerms = FINAL_AMOUNT_TERMS.filter { lowerText.contains(it) }
        val labeledAmounts = accumulator.amountAccumulator.labeledAmountsForDiagnostics()
        val unlabeledAmounts = accumulator.amountAccumulator.unlabeledAmountsForDiagnostics()
        val dedupKey = PageAmountDiagnostics.dedupKey(
            shoppingPackage = shoppingPackage,
            windowClass = windowClass,
            pageRole = pageRole,
            textDropped = accumulator.textDropped,
            labeledTerms = labeledTerms,
            labeledAmounts = labeledAmounts,
            unlabeledAmounts = unlabeledAmounts,
            finalAmountMinor = finalAmountMinor,
            evidence = evidence
        )
        if (!markAmountDiagIfChanged(dedupKey)) {
            return
        }
        JotBillLog.w(
            TAG,
            PageAmountDiagnostics.format(
                shoppingPackage = shoppingPackage,
                pageRole = pageRole,
                windowClass = windowClass,
                visitedNodes = accumulator.visitedNodes,
                tokenCount = accumulator.signalTokens.size,
                textDropped = accumulator.textDropped,
                labeledTerms = labeledTerms,
                labeledAmounts = labeledAmounts,
                unlabeledAmounts = unlabeledAmounts,
                finalAmountMinor = finalAmountMinor,
                evidence = evidence,
                termHit = evidence == FinalAmountSignalEvidence.LABEL_TERM,
                // 复用 collect 已算好的值，不重算 —— 保证日志与角色判定读到的是同一份
                actionHit = hasCheckoutActionSignal,
                currencyHit = hasCurrencyAmount,
                subtotalHit = evidence == FinalAmountSignalEvidence.SUBTOTAL_WITH_ORDER
            )
        )
    }

    /**
     * 诊断去重键；`@Synchronized` 保证并发采集下同一状态也只打一行。
     *
     * [lastAmountDiagKey] 是本文件**唯一的可变状态**，仅供诊断去重，
     * 不参与任何判定；即使失准，影响也只限于日志条数。
     */
    @Synchronized
    private fun markAmountDiagIfChanged(key: String): Boolean {
        if (lastAmountDiagKey == key) {
            return false
        }
        lastAmountDiagKey = key
        return true
    }

    @Volatile
    private var lastAmountDiagKey: String? = null

    private fun collectNode(
        node: AccessibilityNodeInfo,
        accumulator: NodeSummaryAccumulator,
        depth: Int,
        sensitiveAncestor: Boolean = false,
        includeHiddenText: Boolean = false,
        rotatingAncestor: Boolean = false
    ) {
        if (depth > MAX_DEPTH || accumulator.visitedNodes >= MAX_NODE_COUNT) {
            return
        }

        accumulator.visitedNodes += 1
        val hasContentDescription = !node.contentDescription.isNullOrBlank()
        val hasAnyText = !node.text.isNullOrBlank() || hasContentDescription
        // isVisibleToUser 在弹窗式结算页上不可靠：京东 PopupNewFillOrderActivity
        // 的底部"立即支付"按钮会间歇上报 false，导致该节点整体被跳过、
        // actionSignal 时真时假、页面角色在 CHECKOUT 与 SHOPPING_HOME 之间抖动。
        // 因此允许把带文本的不可见节点也纳入语义判定（includeHiddenText），
        // 微信支付成功页的隐藏 contentDescription 同样依赖该开关。
        val visible = depth == 0 || node.isVisibleToUser ||
            (includeHiddenText && hasAnyText)
        val className = node.className?.toString().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        val nodeSensitive = sensitiveAncestor ||
            node.isPassword ||
            className.contains("password", ignoreCase = true) ||
            className.contains("verification", ignoreCase = true) ||
            className.contains("secure", ignoreCase = true) ||
            viewId.contains("password", ignoreCase = true) ||
            viewId.contains("verification", ignoreCase = true) ||
            viewId.contains("secure", ignoreCase = true)
        if (nodeSensitive) {
            accumulator.sensitive = true
        }
        // 轮播容器自身及其整棵子树的文本都不纳入指纹，避免滚动文案造成指纹漂移；
        // 容器的类名语义标记仍然保留，页面结构变化依旧能被指纹感知。
        val nodeRotating = rotatingAncestor || isRotatingWidgetClass(className)
        if (visible && !nodeSensitive) {
            if (!nodeRotating) {
                // 位置键：以节点语义标记（类名+资源id）标识"同一位置"。
                // L3 易变检测按位置观察跨帧文本：窗口内出现 ≥2 个不同文本
                // 即判定轮播，该位置文本只进信号通道、不进指纹。
                val positionKey = buildString {
                    append(className.substringAfterLast('.'))
                    if (viewId.isNotBlank()) {
                        append('@')
                        append(viewId.substringAfterLast('/'))
                    }
                }
                node.text?.toString()?.let {
                    accumulator.addText(it, positionKey)
                }
                node.contentDescription?.toString()?.let {
                    accumulator.addText(it, positionKey)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    node.hintText?.toString()?.let {
                        accumulator.addText(it, positionKey)
                    }
                    node.tooltipText?.toString()?.let {
                        accumulator.addText(it, positionKey)
                    }
                }
            }

            className.takeIf { it.isNotBlank() }?.let { className ->
                accumulator.addSemantic("node:${className.substringAfterLast('.')}")
            }
            viewId.takeIf { it.isNotBlank() }?.let { viewId ->
                accumulator.addSemantic("view:${viewId.substringAfterLast('/')}")
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
                    collectNode(
                    child,
                    accumulator,
                    depth + 1,
                    nodeSensitive,
                    includeHiddenText,
                    nodeRotating
                )
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
        token = uuidPattern.replace(token, " id ")
        token = sensitiveNumberPattern.replace(token, " number ")
        token = currencyPattern.replace(token, " currency ")
        token = token.replace("元", " currency ")
        token = groupingSeparatorPattern.replace(token, "")
        token = whitespacePattern.replace(token, " ")
            .lowercase(Locale.ROOT)
            .trim()

        return token.take(MAX_TOKEN_LENGTH).takeIf { it.isNotBlank() }
    }

    /**
     * 最终金额信号 —— [hasFinalAmountSignal] 的判定出处，返回命中的分支。
     *
     * 三条分支与顺序为原实现原样搬运；判定逻辑**只此一处**，
     * 诊断日志不得另抄一份（抄了就会在分支变更后说谎）。
     *
     * @param text 已归一化（含脱敏）的信号文本。
     * @param hasCurrencyAmount 页面是否出现过货币金额。**必须来自原始文本提取**
     *   （[PageAmountExtractor]）——脱敏把 ≥3 位连续数字抹成 ` number `，若仍从
     *   [text] 里找数字，`¥128.00` 这类百元订单会系统性漏判（P2-8：PDD 无标签
     *   结算页 ≥¥100 订单整链失效）。
     */
    internal fun finalAmountSignalEvidence(
        text: String,
        hasCurrencyAmount: Boolean
    ): FinalAmountSignalEvidence {
        if (containsAny(text, FINAL_AMOUNT_TERMS)) {
            return FinalAmountSignalEvidence.LABEL_TERM
        }
        // 拼多多确认订单页可能只出现“提交订单 + ¥55.70”，没有“实付/合计”等标签；
        // 这种情况下把“提交/支付动作 + 货币金额”作为最终金额信号。
        // ⚠️ 用**非严格**的 hasCurrencyAmount（任一候选存在），不用提取出的单值：
        // 提取在“多金额歧义”时返回 null，若用它会让“动作词 + 两个小额金额”的
        // 页面由 true 变 false —— 那才是新引入的漏判。
        if (containsAny(text, checkoutActionTerms) && hasCurrencyAmount) {
            return FinalAmountSignalEvidence.ACTION_WITH_CURRENCY
        }
        if ((text.contains("合计") || text.contains("总计")) &&
            (text.contains("订单") || text.contains("收货") || text.contains("优惠后"))
        ) {
            return FinalAmountSignalEvidence.SUBTOTAL_WITH_ORDER
        }
        return FinalAmountSignalEvidence.NONE
    }

    /**
     * 最终金额信号。
     *
     * @param text 已归一化（含脱敏）的信号文本。
     * @param hasCurrencyAmount 见 [finalAmountSignalEvidence]。
     */
    internal fun hasFinalAmountSignal(text: String, hasCurrencyAmount: Boolean): Boolean =
        finalAmountSignalEvidence(text, hasCurrencyAmount) != FinalAmountSignalEvidence.NONE

    /**
     * 判定页面角色。
     *
     * 判定次序体现证据强弱：支付交接/结果页关键词最强（涉及敏感页与状态终态），
     * 其次是「金额 + 动作」双信号，最后才是购物车/详情/浏览类文本兜底。
     *
     * @param hasCheckoutClassSemantic 窗口类名是否指向结算流程。京东弹窗结算页
     * （PopupNewFillOrderActivity）的「立即支付」按钮会间歇上报不可见，
     * actionSignal 因此时真时假；页面又含「猜你喜欢」推荐位，一旦动作信号缺失
     * 就被 browsingTerms 兜底判成 SHOPPING_HOME，触发
     * INVALIDATED_NON_CHECKOUT_PAGE 清空刚存入的快照。
     * 注意：改判 UNKNOWN 无法解决该问题——CheckoutPageDetector 把一切非
     * CHECKOUT 角色统一拒为 NOT_CHECKOUT_PAGE，UNKNOWN 与 SHOPPING_HOME
     * 在失效链路上完全等价。因此这里用「类名结算语义 + 金额信号」替代
     * 缺失的动作信号，直接判定 CHECKOUT，让角色在抖动期间保持稳定。
     * 金额信号是必要条件：结算类名下若连应付金额都读不到，说明页面尚未渲染
     * 完成，此时不应进入结算态触发采集。
     */
    private fun classifyPageRole(
        lowerText: String,
        hasFinalAmountSignal: Boolean,
        hasCheckoutActionSignal: Boolean,
        isSensitivePage: Boolean,
        hasCheckoutClassSemantic: Boolean
    ): CheckoutPageRole {
        if (containsAny(lowerText, paymentHandoffTerms)) {
            return CheckoutPageRole.PAYMENT_HANDOFF
        }
        // 支付结果页判定：必须在支付应用包名上下文中命中结果页关键词
        // 不基于单一张图或单一关键词，且敏感页（密码/付款码）已被 paymentHandoffTerms 拦截
        if (containsAny(lowerText, paymentResultTerms)) {
            return CheckoutPageRole.PAYMENT_RESULT
        }
        // Y3（2026-09-10）：商品规格选择层前置排除 CHECKOUT。真机实证规格弹层
        // 的隐藏节点携带支付动作词（actionSignal=true 而可见文本无动作词），
        // 加上价格轮播使指纹持续漂移，每次换字都触发新会话自动截图——截到的
        // 全是商品页图。数量增减成对/规格引导词是商品层强结构证据，优先级
        // 高于"金额+动作"双信号判定，但低于支付交接/结果页判定。
        // 同日回归修正：拼多多结算确认层与规格层共用 wd2.f0 窗口且同样带
        // "已选："文案——动作信号并存时不得排除（结算层证据优先），否则
        // 真结算页被误杀、自动链路全断（14:08 回归取证）。
        if (isProductSpecSelectionLayer(
                lowerText = lowerText,
                hasCheckoutActionSignal = hasCheckoutActionSignal
            )
        ) {
            return CheckoutPageRole.PRODUCT_DETAIL
        }
        if (!isSensitivePage && hasFinalAmountSignal &&
            (hasCheckoutActionSignal || hasCheckoutClassSemantic)
        ) {
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

    /**
     * 敏感信号判定（密码/验证码/付款码/银行卡等）。
     *
     * internal 供 JVM 单测钉住「敏感页零采集」不变式的词表口径
     * （`PaymentResultInAppGuardTest`）；行为与 private 时期完全一致。
     */
    internal fun containsSensitiveSignal(text: String): Boolean =
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
        /** 信号通道：页面角色/金额/动作/敏感判定的文本输入（全部文本）。 */
        val signalTokens = ArrayList<String>()

        /** 指纹通道：只含稳定文本（易变位置剔除后）与语义标记。 */
        val fingerprintTokens = ArrayList<String>()

        /** 页面金额（分）候选累积器；由 [addText] 逐节点喂**原始**文本。 */
        val amountAccumulator = PageAmountAccumulator()
        var visitedNodes: Int = 0
        var sensitive: Boolean = false

        /**
         * 文本 token 是否曾因容量上限被丢弃。
         *
         * 文本是页面角色判定的唯一依据，被静默丢弃会导致 amountSignal /
         * actionSignal 无声失效、页面判定漂移。保留该标记与配套 WARN 日志，
         * 使这类容量问题在真机上可被立即发现。
         */
        var textDropped: Boolean = false

        /**
         * 写入节点文本（信号通道必进；指纹通道经 L3 易变检测分流）。
         *
         * 文本承载页面语义（金额、动作按钮），优先级高于类名/资源 id 语义标记，
         * 因此使用独立的更大容量上限，不与语义标记争抢槽位。
         *
         * @param value 节点的 text/contentDescription/hintText/tooltipText 之一。
         * @param positionKey 节点位置键（类名[@资源id]），供 L3 跨帧观察。
         */
        fun addText(value: String, positionKey: String = "") {
            if (sensitiveTerms.any { value.contains(it, ignoreCase = true) } ||
                sensitiveAsciiPattern.containsMatchIn(value)
            ) {
                sensitive = true
                return
            }
            if (value.isBlank()) {
                return
            }
            if (signalTokens.size >= MAX_TOTAL_TOKEN_COUNT) {
                textDropped = true
                return
            }
            val normalized = sensitiveNumberPattern.replace(value, " number ")
                .take(MAX_TOKEN_LENGTH)
            signalTokens += normalized
            // 页面金额（分）提取：必须在**脱敏之前**喂原始文本 —— 脱敏会把
            // 3 位以上连续数字抹成 " number "（P2-8 的根因），脱敏之后已无从取数。
            amountAccumulator.observe(value)

            // L3 易变检测（护栏前置）：命中"最终金额"语义的 token
            // 永不进入易变判定——结算页应付金额是静态文本不会轮播，轮播的
            // 是商品页到手价/折扣；金额 token 留在指纹中保证换商品必触发
            // 新订单边界。轮播容器子树（rotating）已在上游跳过，不会到这。
            // 判据必须与金额提取同源（PageAmountExtractor.isFinalAmountToken），
            // 否则两通道判据分叉即 P8"信号通道认、指纹通道不认"。
            val hitsFinalAmount = PageAmountExtractor.isFinalAmountToken(value)
            val volatile = !hitsFinalAmount &&
                volatileTracker.observeAndCheck(scopeKey, positionKey, normalized)
            if (!volatile) {
                fingerprintTokens += normalized
            }
        }

        /**
         * 写入类名或资源 id 的语义标记（仅指纹通道）。
         *
         * 语义标记只服务于指纹的结构感知，数量远多于文本且不参与角色判定，
         * 因此限制在较小的配额内，避免挤占文本槽位。
         *
         * @param value 形如 `node:类名` 或 `view:资源id` 的语义标记。
         */
        fun addSemantic(value: String) {
            if (fingerprintTokens.size < MAX_SEMANTIC_TOKEN_BUDGET) {
                fingerprintTokens += value.take(MAX_TOKEN_LENGTH)
            }
        }

        /** 本页观察的作用域键与易变追踪器，由 collect() 每次设置。 */
        lateinit var scopeKey: String

        companion object {
            /** L3 跨帧易变追踪器：按 scope（包名+窗口类名）隔离观察窗口。 */
            val volatileTracker = VolatileTokenTracker()
        }
    }
}

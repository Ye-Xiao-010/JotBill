package com.jotbill.app.bookkeeping

import com.jotbill.app.utils.JotBillLog
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 规则式自动记账引擎 —— 小米钱包 `ce.b`（AutoBookKeeping）状态机的忠实移植。
 *
 * 事件流三板斧（设计方案 §2.2）：
 * 1. 节流：同一窗口内容变化事件 200ms 内只处理一次（[CONTENT_CHANGE_INTERVAL_MILLIS]）；
 * 2. 窗口去重：窗口状态事件建立跟踪（mWindowId），已上报窗口（mReportedWindowId）
 *    不再处理；内容变化事件只作用于「当前跟踪中的窗口」；
 * 3. 三类事件分流：通知（64）/ 页面（32）/ 内容变化（2048）各自维护状态。
 *
 * 二次确认（§2.5）：带 locateConfirm 的规则（收银台预提取类）抽取成功后不立即
 * 交付，挂入 [pendingConfirms] 等待 5 分钟内的确认通知 / 确认页，超时丢弃。
 *
 * 本类为**纯 JVM 单线程**实现：只依赖 [EventSnapshot] 与 [PageNode] 投影，
 * 由 [com.jotbill.app.recognition.CheckoutAccessibilityService] 在主线程无障碍
 * 回调里驱动；入账等耗时动作经 [Sink] 回调交给外部协程。
 *
 * 与小米差异（有意为之）：
 * - 不做 FLAG_INCLUDE_NOT_IMPORTANT_VIEWS 的运行时开关舞蹈（小米 l0()）——
 *   本服务该 flag 常开；
 * - 新增 [Claim] 认领注册表：规则抽取成功后登记金额指纹，供既有弹窗管线
 *   查询并让路（弹窗兜底与自动入账互斥，拍板项①）。
 */
class AutoBookkeepingEngine(
    private val registryRef: AtomicReference<PageRuleRegistry?>,
    private val channels: ChannelGate,
    private val sink: Sink,
    private val rescanScheduler: RescanScheduler,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /**
     * 重扫调度器：生产实现基于主线程 Handler；单测用手动实现驱动「虚拟时间」。
     * lean 弹窗管线的 P2-5 教训：结果页渲染完即静止、事件流停摆 —— 规则引擎
     * 若只靠下一个内容变化事件重试，最后一次事件先于金额渲染到达时这笔就
     * 永远错过。跟踪窗口分析失败后由调度器补扫（[RESCAN_INTERVAL_MILLIS] ×
     * [RESCAN_MAX_COUNT] 预算内），对齐既有管线的自愈语义。
     */
    interface RescanScheduler {
        fun postDelayed(delayMillis: Long, action: () -> Unit)

        fun cancelAll()
    }
    /** 事件快照：由服务适配层从 AccessibilityEvent 提取，引擎不触碰框架类。 */
    class EventSnapshot(
        val eventType: Int,
        val packageName: String?,
        val className: String?,
        val windowId: Int,
        val eventTime: Long,
        val notificationText: String?,
        val notificationContentDesc: String?
    )

    /** 抽取成功的记录（对齐小米 de.b 上报体中引擎能产出的部分）。 */
    class ExtractedRecord(
        val fields: Map<String, String>,
        val ruleId: Int,
        val ruleNote: String?,
        val fromApp: Int,
        val jotbillType: String?,
        val sourcePackage: String,
        val extractTime: Long
    )

    /** 交付回调：入账方同步判定「是否认领」（金额可解析且>0 才认领），异步落账。 */
    fun interface Sink {
        fun onRecordReady(record: ExtractedRecord): Boolean
    }

    /** 通道闸门抽象，生产实现为 [BookkeepingChannels]，单测可注入假实现。 */
    fun interface ChannelGate {
        fun shouldProcess(packageName: String): Boolean
    }

    private class PendingConfirm(
        val record: ExtractedRecord,
        val rule: PageRule,
        val expireAt: Long
    )

    /**
     * 已认领的抽取指纹：同包名+同金额在 [CLAIM_WINDOW_MILLIS] 内视为同一笔，
     * 既有弹窗管线据此让路。窗口取 PaymentTiming.SAME_TXN_WINDOW_MILLIS 同值
     * （15s）：与既有「同额合并」裁定同一时间尺度。
     *
     * P4 护栏：[orderId] 参与判重 —— 两侧订单号都非空且**不同**时，订单号客观
     * 证明是两笔真单（如连付两笔同额），不得按同额指纹吞并第二条（与
     * RecentPaymentCooldown 的 orderId 护栏同口径）；任一侧缺失时维持原语义。
     */
    class Claim(
        val packageName: String,
        val amountMinor: Long,
        val ruleId: Int,
        val at: Long,
        val orderId: String? = null
    )

    private val claims = mutableListOf<Claim>()

    private var lastContentChangeAt = 0L

    private var trackedWindowId = 0

    private var trackedActivityName: String? = null

    private var trackedRuleKey: String? = null

    private var reportedWindowId = 0

    /**
     * 二次确认挂起队列：`包名 → 待确认记录队列`。
     *
     * ⚠️ **按包名排队而非单值覆盖**（与 mipay 的 `Map<包名, 单条>` 不同）：
     * 二次确认的锚点只到「包名/类名」粒度，无法分辨锚点属于哪一笔，但**确认页
     * 会出现两次**（用户连付两笔就看到两次）。单值覆盖会：
     * ① 静默丢弃第一笔；② 让第一次确认页交付**第二笔**的记录（金额/商户是
     * 第二笔的，确认的却是第一笔）—— 比丢数据更糟。
     * 改为队列后按序消费，与「一次确认页确认一笔」一一对应。
     */
    private val pendingConfirms = mutableMapOf<String, ArrayDeque<PendingConfirm>>()

    // ── 静止页重扫兜底（仅主线程访问）─────────────────────────────────────
    private var rescanCount = 0

    private var rescanPackageName: String? = null

    private var rescanRootProvider: (() -> PageNode?)? = null

    /** 失败诊断日志节流：键 = 包名|规则key。 */
    private val lastAnalyzeDiagAt = mutableMapOf<String, Long>()

    /** 主线程入口。引擎内部吞掉一切异常，绝不影响宿主服务。 */
    fun onEvent(snapshot: EventSnapshot, rootProvider: () -> PageNode?) {
        try {
            handleEvent(snapshot, rootProvider)
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "自动记账引擎处理事件失败，已吞掉", error)
        }
    }

    private fun handleEvent(snapshot: EventSnapshot, rootProvider: () -> PageNode?) {
        val registry = registryRef.get()
        if (registry == null || registry.ruleCount == 0) {
            return
        }
        val packageName = snapshot.packageName ?: return
        if (packageName == SYSTEM_UI_PACKAGE || packageName == OWN_PACKAGE) {
            return
        }
        if (!channels.shouldProcess(packageName)) {
            return
        }
        when (snapshot.eventType) {
            EVENT_NOTIFICATION_STATE_CHANGED -> onNotificationEvent(snapshot)
            EVENT_WINDOW_STATE_CHANGED -> onWindowStateEvent(snapshot, rootProvider)
            EVENT_WINDOW_CONTENT_CHANGED -> onWindowContentChangedEvent(snapshot, rootProvider)
        }
    }

    // ── 通知事件（64）：通知类规则 + 二次确认的通知锚点 ────────────────────

    private fun onNotificationEvent(snapshot: EventSnapshot) {
        val packageName = snapshot.packageName ?: return
        val notificationKey = "$packageName/${snapshot.className.orEmpty()}"
        val rules = registryRef.get()?.rulesFor(notificationKey)
        if (rules != null) {
            for (rule in rules) {
                val locateValue = when (rule.locateAttribute) {
                    0 -> snapshot.notificationText
                    1 -> snapshot.notificationContentDesc
                    else -> null
                }
                if (locateValue != null && rule.isLocatedBy(locateValue)) {
                    val fields = rule.extractFrom(locateValue)
                    if (fields != null) {
                        deliver(
                            ExtractedRecord(
                                fields = fields,
                                ruleId = rule.ruleId,
                                ruleNote = rule.note,
                                fromApp = rule.fromApp,
                                jotbillType = rule.jotbillType,
                                sourcePackage = packageName,
                                extractTime = clock()
                            )
                        )
                        return
                    }
                }
            }
            // 规则在但未命中 ≠ 结束：mipay 语义 `!g0 && W` —— 仍要检查
            // 二次确认的通知锚点（落空直接返回）。
        }
        if (isConfirmNotification(snapshot)) {
            tryPendingConfirmByNotification(snapshot)
        }
    }

    // ── 窗口状态事件（32）：建立跟踪 + 页面分析 / 确认页锚点 ────────────────

    private fun onWindowStateEvent(snapshot: EventSnapshot, rootProvider: () -> PageNode?) {
        val packageName = snapshot.packageName ?: return
        if (snapshot.windowId == reportedWindowId) {
            return
        }
        val className = snapshot.className
        val matchedKey = registryRef.get()?.matchKey(packageName, className)
        if (matchedKey != null) {
            // 换窗即重置重扫预算：上一窗口（可能是 MMWebViewUI 这类宽 key 匹配的
            // 普通文章页）烧掉的预算不能连累本窗口 —— 静止页迟到渲染恰是最需要
            // 重扫兜底的场景（审查 P2-1）。
            if (trackedRuleKey != matchedKey || trackedWindowId != snapshot.windowId) {
                rescanScheduler.cancelAll()
                rescanCount = 0
            }
            trackedWindowId = snapshot.windowId
            trackedActivityName = className
            trackedRuleKey = matchedKey
            if (analyzeTrackedPage(packageName, rootProvider)) {
                onAnalyzeSucceeded()
                return
            }
            scheduleRescan(packageName, rootProvider)
        }
        if (isConfirmActivity(packageName, className)) {
            trackedWindowId = snapshot.windowId
            trackedActivityName = className
            trackedRuleKey = matchedKey
            tryPendingConfirmByPage(packageName, rootProvider)
        }
    }

    // ── 窗口内容变化事件（2048）：节流 + 只作用于跟踪窗口 ──────────────────

    private fun onWindowContentChangedEvent(snapshot: EventSnapshot, rootProvider: () -> PageNode?) {
        if (snapshot.eventTime - lastContentChangeAt < CONTENT_CHANGE_INTERVAL_MILLIS) {
            return
        }
        lastContentChangeAt = snapshot.eventTime
        if (snapshot.windowId == reportedWindowId || trackedWindowId != snapshot.windowId) {
            return
        }
        val packageName = snapshot.packageName ?: return
        if (analyzeTrackedPage(packageName, rootProvider)) {
            onAnalyzeSucceeded()
            return
        }
        if (isConfirmActivity(packageName, trackedActivityName)) {
            tryPendingConfirmByPage(packageName, rootProvider)
            return
        }
        scheduleRescan(packageName, rootProvider)
    }

    // ── 页面分析（h0/Z/Q）：dump → 定位 → 抽取 → 交付或挂二次确认 ──────────

    /** 分析成功：取消挂起的重扫并重置预算（窗口跟踪由分析内部按规则语义复位）。 */
    private fun onAnalyzeSucceeded() {
        rescanScheduler.cancelAll()
        rescanCount = 0
    }

    /**
     * 静止页重扫兜底（对齐既有管线 P2-5 的自愈语义）：跟踪窗口分析失败时补一拍。
     * 真实事件先到则重扫被取消/覆盖；预算 [RESCAN_MAX_COUNT] 用尽后静默停止。
     */
    private fun scheduleRescan(packageName: String, rootProvider: () -> PageNode?) {
        if (rescanCount >= RESCAN_MAX_COUNT) {
            return
        }
        rescanCount += 1
        rescanPackageName = packageName
        rescanRootProvider = rootProvider
        rescanScheduler.postDelayed(RESCAN_INTERVAL_MILLIS) { runRescan() }
    }

    private fun runRescan() {
        val packageName = rescanPackageName ?: return
        val rootProvider = rescanRootProvider ?: return
        // 跟踪已被成功分析清除（或换窗）→ 无事可做。换窗时 32 事件路径会重新
        // 建立跟踪并重置预算，这里的旧残跑不会误配。
        if (trackedWindowId == 0) {
            return
        }
        JotBillLog.d(
            TAG,
            "静止页重扫兜底: pkg=$packageName, count=$rescanCount/$RESCAN_MAX_COUNT, " +
                "key=$trackedRuleKey"
        )
        if (analyzeTrackedPage(packageName, rootProvider)) {
            onAnalyzeSucceeded()
            return
        }
        if (rescanCount < RESCAN_MAX_COUNT) {
            scheduleRescan(packageName, rootProvider)
        }
    }

    private fun analyzeTrackedPage(
        sourcePackage: String,
        rootProvider: () -> PageNode?
    ): Boolean {
        val key = trackedRuleKey ?: return false
        val rules = registryRef.get()?.rulesFor(key) ?: return false
        val root = rootProvider() ?: return false
        // 诊断留样（不足#1）：真机规则打不中时必须能看到页面 dump 长什么样，
        // 否则正则调优无从下手。节流输出，内容只进内存日志不落盘。
        var locatedAny = false
        var sampleDump = ""
        for (rule in rules) {
            val dump = PageNodeDumper.dump(root, rule)
            if (dump.elapsedMs > SLOW_DUMP_WARN_MILLIS) {
                JotBillLog.w(
                    TAG,
                    "页面 dump 耗时异常: ${dump.elapsedMs}ms, nodes=${dump.nodeCount}, " +
                        "ruleId=${rule.ruleId}, pkg=$sourcePackage"
                )
            }
            sampleDump = dump.dump
            if (!dump.located) {
                continue
            }
            locatedAny = true
            val fields = rule.extractFrom(dump.dump)
            if (fields == null) {
                logAnalyzeDiag(
                    packageName = sourcePackage,
                    ruleKey = key,
                    headline = "锚点已命中但抽取正则未匹配: ruleId=${rule.ruleId}(${rule.note})"
                        .let { "$it, pattern 差在字段抽取段" },
                    dump = dump.dump
                )
                continue
            }
            val record = ExtractedRecord(
                fields = fields,
                ruleId = rule.ruleId,
                ruleNote = rule.note,
                fromApp = rule.fromApp,
                jotbillType = rule.jotbillType,
                sourcePackage = sourcePackage,
                extractTime = clock()
            )
            if (rule.locateConfirm == null || rule.matchMode != MATCH_MODE_KEEP_TRACKING) {
                reportedWindowId = trackedWindowId
                trackedWindowId = 0
                trackedActivityName = null
                trackedRuleKey = null
            }
            if (rule.locateConfirm != null) {
                val queue = pendingConfirms.getOrPut(sourcePackage) { ArrayDeque() }
                if (queue.isNotEmpty()) {
                    // 同包连付：排在队首之后，不再覆盖。队首会由它自己的确认页按序消费。
                    JotBillLog.i(
                        TAG,
                        "同包已有挂起确认，本笔排队: pkg=$sourcePackage, 队列=${queue.size + 1}, " +
                            "队首ruleId=${queue.first().rule.ruleId}, 新ruleId=${rule.ruleId}"
                    )
                }
                queue.addLast(
                    PendingConfirm(
                        record = record,
                        rule = rule,
                        expireAt = clock() + CONFIRM_WINDOW_MILLIS
                    )
                )
                JotBillLog.i(
                    TAG,
                    "规则抽取成功，挂入二次确认: ruleId=${rule.ruleId}, note=${rule.note}, " +
                        "fields=${record.fields.keys}, pkg=$sourcePackage"
                )
            } else {
                deliver(record)
            }
            return true
        }
        logAnalyzeDiag(
            packageName = sourcePackage,
            ruleKey = key,
            headline = "规则未命中: locatedAny=$locatedAny, rules=${rules.size}, " +
                "ruleIds=${rules.joinToString(",") { it.ruleId.toString() }}",
            dump = sampleDump
        )
        return false
    }

    /**
     * 诊断日志（3s 节流/键）。
     * ⚠️ 落盘政策：JotBillLog 自 2026-10-25 起所有构建一律落盘（真机排查需要），
     * dump 样本因此按构建类型门控 —— debug 全量样本，release 只保留判定结论，
     * 不在用户手机上持久化支付页面文本片段。
     */
    private fun logAnalyzeDiag(packageName: String, ruleKey: String, headline: String, dump: String) {
        val throttleKey = "$packageName|$ruleKey"
        val now = clock()
        if (now - (lastAnalyzeDiagAt[throttleKey] ?: 0L) < DIAG_LOG_INTERVAL_MILLIS) {
            return
        }
        lastAnalyzeDiagAt[throttleKey] = now
        val sample = if (com.jotbill.app.BuildConfig.DEBUG) {
            dump.replace('\n', '│').take(DIAG_DUMP_LIMIT)
        } else {
            "(release 构建隐藏 dump 样本)"
        }
        JotBillLog.i(TAG, "[ABK诊断] $headline, pkg=$packageName, key=$ruleKey, dump样本=$sample")
    }

    // ── 二次确认（W/X/j0/k0）───────────────────────────────────────────────

    /**
     * 取该包名**队首**的待确认记录（不消费）。队首过期则顺次丢弃，直到拿到未过期的或队列空。
     */
    private fun pendingFor(packageName: String): PendingConfirm? {
        val queue = pendingConfirms[packageName] ?: return null
        while (queue.isNotEmpty() && clock() > queue.first().expireAt) {
            val dropped = queue.removeFirst()
            JotBillLog.i(TAG, "二次确认超时丢弃: ruleId=${dropped.rule.ruleId}")
        }
        if (queue.isEmpty()) {
            pendingConfirms.remove(packageName)
            return null
        }
        return queue.first()
    }

    /** 消费（取出并移除）该包名的队首待确认记录；确认锚点命中时调用。 */
    private fun consumePending(packageName: String): PendingConfirm? {
        val queue = pendingConfirms[packageName] ?: return null
        val head = queue.removeFirstOrNull()
        if (queue.isEmpty()) pendingConfirms.remove(packageName)
        return head
    }

    private fun isConfirmNotification(snapshot: EventSnapshot): Boolean {
        val pending = pendingFor(snapshot.packageName ?: return false) ?: return false
        val notification = pending.rule.locateConfirm?.notification ?: return false
        val key = "${snapshot.packageName}/${snapshot.className.orEmpty()}"
        return notification == key
    }

    private fun isConfirmActivity(packageName: String, className: String?): Boolean {
        val pending = pendingFor(packageName) ?: return false
        val activities = pending.rule.locateConfirm?.activity ?: return false
        if (activities.isEmpty()) return false
        if (className.isNullOrEmpty()) return false
        if ("$packageName/$className" in activities) return true
        if ("$packageName/*" in activities) return true
        if (className in activities) return true
        return false
    }

    private fun tryPendingConfirmByNotification(snapshot: EventSnapshot) {
        val packageName = snapshot.packageName ?: return
        val pending = pendingFor(packageName) ?: return
        val anchor = pending.rule.locateConfirm ?: return
        val locateValue = when (anchor.locateAttribute) {
            0 -> snapshot.notificationText
            1 -> snapshot.notificationContentDesc
            else -> null
        }
        val located = locateValue != null &&
            (
                anchor.locateToken?.let { locateValue.contains(it) } == true ||
                    anchor.locatePattern?.matcher(locateValue)?.find() == true
                )
        if (located) {
            // 消费队首（而非整表清空）：同包连付时后续记录仍在队列中，由它自己的确认页消费
            consumePending(packageName)
            JotBillLog.i(TAG, "二次确认(通知锚点)命中: ruleId=${pending.rule.ruleId}")
            deliver(pending.record)
        }
    }

    private fun tryPendingConfirmByPage(packageName: String, rootProvider: () -> PageNode?) {
        val pending = pendingFor(packageName) ?: return
        val anchor = pending.rule.locateConfirm ?: return
        val root = rootProvider() ?: return
        val result = PageNodeDumper.dumpForConfirm(root, anchor)
        if (result.located) {
            reportedWindowId = trackedWindowId
            trackedWindowId = 0
            trackedActivityName = null
            trackedRuleKey = null
            // 消费队首（而非整表清空）：同包连付时后续记录仍在队列中
            consumePending(packageName)
            JotBillLog.i(TAG, "二次确认(页面锚点)命中: ruleId=${pending.rule.ruleId}")
            deliver(pending.record)
        }
    }

    // ── 交付与认领（JotBill 扩展：与弹窗管线的互斥协议）────────────────────

    private fun deliver(record: ExtractedRecord) {
        val amountMinor = parseAmountMinor(record.fields["amount"])
        if (amountMinor != null && amountMinor > 0) {
            pruneClaims()
            // 引擎级指纹去重（对齐小米服务端 status==2 的本地形态）：
            // 同包名+同金额在认领窗内的第二条记录（如「结果页规则入账后几秒
            // 支付宝又发支出通知命中通知规则」）直接丢弃 —— 同额 15s 合并
            // 与用户既有裁定同一口径。
            // P4 护栏：两侧订单号都非空且不同 ⇒ 客观两笔，放行第二条。
            val recordOrderId = record.fields["orderNo"]?.trim()?.takeIf { it.isNotEmpty() }
            val duplicated = claims.any {
                it.packageName == record.sourcePackage && it.amountMinor == amountMinor &&
                    (it.orderId.isNullOrBlank() || recordOrderId.isNullOrBlank() ||
                        it.orderId == recordOrderId)
            }
            if (duplicated) {
                JotBillLog.i(
                    TAG,
                    "同包名同金额认领窗内重复，按指纹丢弃: ruleId=${record.ruleId}, " +
                        "amount=$amountMinor, pkg=${record.sourcePackage}"
                )
                return
            }
        }
        val claimed = sink.onRecordReady(record)
        if (claimed && amountMinor != null && amountMinor > 0) {
            claims += Claim(
                packageName = record.sourcePackage,
                amountMinor = amountMinor,
                ruleId = record.ruleId,
                at = clock(),
                orderId = record.fields["orderNo"]?.trim()?.takeIf { it.isNotEmpty() }
            )
            JotBillLog.i(
                TAG,
                "规则记录已交付: ruleId=${record.ruleId}, note=${record.ruleNote}, " +
                    "amount=$amountMinor"
            )
        } else {
            JotBillLog.i(
                TAG,
                "规则记录未被认领(金额不可解析): ruleId=${record.ruleId}, " +
                    "note=${record.ruleNote}"
            )
        }
    }

    private fun pruneClaims() {
        val now = clock()
        claims.removeAll { now - it.at > CLAIM_WINDOW_MILLIS }
    }

    /**
     * 服务解绑/重绑后的状态复位：窗口 id 与页面跟踪是易变的会话态，跨绑定周期
     * 保留只会让新周期的事件错配旧跟踪；认领指纹带时间窗可安全保留。
     * 由宿主服务在 onServiceConnected（引擎已存在的重连场景）调用。
     *
     * P11 修复：[pendingConfirms] **不再随复位清空** —— 已抽取待二次确认的记录
     * 自带 5 分钟过期（[CONFIRM_WINDOW_MILLIS]），重绑即清空会让"抽取成功 →
     * 服务重绑 → 确认页到达"之间的记录静默丢失（漏记方向）。保留后由
     * [pendingFor] 的过期淘汰兜底，锚点命中仍按包名队列正常消费。
     */
    fun resetTracking() {
        trackedWindowId = 0
        trackedActivityName = null
        trackedRuleKey = null
        reportedWindowId = 0
        lastContentChangeAt = 0L
        rescanScheduler.cancelAll()
        rescanCount = 0
        rescanPackageName = null
        rescanRootProvider = null
    }

    /**
     * 既有弹窗管线在证据解析成功后调用：同包名+同金额在认领窗内 → 弹窗让路。
     */
    fun shouldSuppressPopup(packageName: String, amountMinor: Long): Boolean {
        pruneClaims()
        return claims.any {
            it.packageName == packageName && it.amountMinor == amountMinor &&
                clock() - it.at <= CLAIM_WINDOW_MILLIS
        }
    }

    companion object {
        private const val TAG = "AutoBookkeepingEngine"

        /** 对齐小米 asMinContentChangeInterval=200。 */
        const val CONTENT_CHANGE_INTERVAL_MILLIS = 200L

        /** 对齐小米 pending_confirm 5 分钟窗口。 */
        const val CONFIRM_WINDOW_MILLIS = 300_000L

        /** 静止页重扫：间隔与单窗预算（对齐既有管线 P2-5 参数）。 */
        const val RESCAN_INTERVAL_MILLIS = 1_000L

        const val RESCAN_MAX_COUNT = 8

        /** 诊断日志节流与 dump 样本上限（防刷屏；样本内容按构建类型门控）。 */
        const val DIAG_LOG_INTERVAL_MILLIS = 3_000L

        const val DIAG_DUMP_LIMIT = 800

        /** 单次 dump 超此耗时输出告警（慢页面/脏树的可观测出口）。 */
        const val SLOW_DUMP_WARN_MILLIS = 50L

        /** 与 PaymentTiming.SAME_TXN_WINDOW_MILLIS 同值（15s，同额合并裁定）。 */
        const val CLAIM_WINDOW_MILLIS = 15_000L

        /** 小米 matchMode=1：带 locateConfirm 的规则抽取后保留窗口跟踪。 */
        private const val MATCH_MODE_KEEP_TRACKING = 1

        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        private const val OWN_PACKAGE = "com.jotbill.app"

        const val EVENT_NOTIFICATION_STATE_CHANGED = 64

        const val EVENT_WINDOW_STATE_CHANGED = 32

        const val EVENT_WINDOW_CONTENT_CHANGED = 2048

        /** "12.00"/"1,234.50"/"¥9.9" → 分；解析失败返回 null。 */
        fun parseAmountMinor(raw: String?): Long? {
            if (raw.isNullOrBlank()) return null
            val cleaned = raw.replace("¥", "").replace("￥", "")
                .replace(",", "").replace("，", "").trim()
            if (cleaned.isEmpty()) return null
            return try {
                val value = java.math.BigDecimal(cleaned)
                value.multiply(java.math.BigDecimal(100))
                    .setScale(0, java.math.RoundingMode.HALF_UP)
                    .longValueExact()
            } catch (_: NumberFormatException) {
                null
            } catch (_: ArithmeticException) {
                null
            }
        }
    }
}

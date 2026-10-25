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
    private val clock: () -> Long = System::currentTimeMillis
) {
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
     */
    class Claim(val packageName: String, val amountMinor: Long, val ruleId: Int, val at: Long)

    private val claims = mutableListOf<Claim>()

    private var lastContentChangeAt = 0L

    private var trackedWindowId = 0

    private var trackedActivityName: String? = null

    private var trackedRuleKey: String? = null

    private var reportedWindowId = 0

    private val pendingConfirms = mutableMapOf<String, PendingConfirm>()

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
            trackedWindowId = snapshot.windowId
            trackedActivityName = className
            trackedRuleKey = matchedKey
            if (analyzeTrackedPage(packageName, rootProvider)) {
                return
            }
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
            return
        }
        if (isConfirmActivity(packageName, trackedActivityName)) {
            tryPendingConfirmByPage(packageName, rootProvider)
        }
    }

    // ── 页面分析（h0/Z/Q）：dump → 定位 → 抽取 → 交付或挂二次确认 ──────────

    private fun analyzeTrackedPage(
        sourcePackage: String,
        rootProvider: () -> PageNode?
    ): Boolean {
        val key = trackedRuleKey ?: return false
        val rules = registryRef.get()?.rulesFor(key) ?: return false
        val root = rootProvider() ?: return false
        for (rule in rules) {
            val dump = PageNodeDumper.dump(root, rule)
            if (!dump.located) {
                continue
            }
            val fields = rule.extractFrom(dump.dump) ?: continue
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
                pendingConfirms[sourcePackage] = PendingConfirm(
                    record = record,
                    rule = rule,
                    expireAt = clock() + CONFIRM_WINDOW_MILLIS
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
        return false
    }

    // ── 二次确认（W/X/j0/k0）───────────────────────────────────────────────

    private fun pendingFor(packageName: String): PendingConfirm? {
        val pending = pendingConfirms[packageName] ?: return null
        if (clock() > pending.expireAt) {
            pendingConfirms.remove(packageName)
            JotBillLog.i(TAG, "二次确认超时丢弃: ruleId=${pending.rule.ruleId}")
            return null
        }
        return pending
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
            pendingConfirms.remove(packageName)
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
            pendingConfirms.remove(packageName)
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
            val duplicated = claims.any {
                it.packageName == record.sourcePackage && it.amountMinor == amountMinor
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
            claims += Claim(record.sourcePackage, amountMinor, record.ruleId, clock())
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
     */
    fun resetTracking() {
        trackedWindowId = 0
        trackedActivityName = null
        trackedRuleKey = null
        reportedWindowId = 0
        lastContentChangeAt = 0L
        pendingConfirms.clear()
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

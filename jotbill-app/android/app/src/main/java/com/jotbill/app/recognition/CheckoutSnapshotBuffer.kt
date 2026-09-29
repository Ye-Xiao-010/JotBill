package com.jotbill.app.recognition

import android.os.Handler
import android.os.Looper
import com.jotbill.app.utils.JotBillLog
import java.util.UUID

/**
 * 结算页槽位存活时长（会话上限，5 分钟）。
 *
 * 结算页稳定截图后用户还需经过收银台、选支付方式、输密码等环节，且可能在结算页
 * 比价、等优惠券、犹豫 —— 该槽位"等的是用户决定"，停留数分钟是常态。
 * TTL 只作内存兜底，"当前结算页最新状态"主要靠新截图覆盖（见 `store()`）。
 *
 * ⚠️ 与 [PAYMENT_PROCESS_TTL_MILLIS] **同值域不同义、不得联动修改**：
 *
 * | 常量 | 起算点 | 超时后果 |
 * |---|---|---|
 * | 本常量（结算页槽位） | `store()` 时刻 | 结算页快照过期 ⇒ 支付交接时**无图可搬** |
 * | [PAYMENT_PROCESS_TTL_MILLIS]（支付过程槽位） | **搬入时刻** | 支付成功时**无图可送** ⇒ 落待确认 |
 *
 * 若把两者统一为 2 分钟，会出现"用户在结算页停留 >2 分钟后才提交订单 ⇒ 交接时结算页
 * 槽位已空 ⇒ 支付成功时无图 ⇒ 落待确认"的回归 —— TTL 分离要修的正是这个形态。
 */
internal const val SESSION_TTL_MILLIS = 5 * 60 * 1000L

/**
 * 支付过程槽位存活时长（2 分钟，自**搬入时刻**起算）。
 *
 * 语义是"事件驱动 + 兜底超时"：主终点是支付结果页到达（消费即释放），本 TTL 只兜底
 * 慢支付（换卡 / 验证码延迟）。超时后果 = 图过期 ⇒ 支付成功时无图 ⇒ 落待确认（安全侧）。
 */
internal const val PAYMENT_PROCESS_TTL_MILLIS = 120_000L
internal const val CHECKOUT_SNAPSHOT_SLOT_PREFIX = "shopping-package:"

/** 支付过程槽位前缀：key = `payment:<sessionId>`（一单一图，见 [CheckoutSnapshotBuffer.moveToPaymentProcess]）。 */
internal const val PAYMENT_PROCESS_SLOT_PREFIX = "payment:"

/** 结算截图和购买意图必须共用同一购物包槽位命名。 */
internal fun checkoutSnapshotSlotId(shoppingPackage: String): String =
    CHECKOUT_SNAPSHOT_SLOT_PREFIX + shoppingPackage

/** 支付过程槽位命名：按会话键控，与结算页槽位（按购物包）不同名，二者不会互相覆盖。 */
internal fun paymentProcessSlotId(sessionId: String): String =
    PAYMENT_PROCESS_SLOT_PREFIX + sessionId

/**
 * 仅存在于内存中的结算页截图。
 *
 * imageBytes 不进入 Room、文件、日志或 RN；消费或覆盖时会先清零内部数组。
 * 对外返回的副本由后续 AI 调度器负责在请求结束后释放。
 */
data class CheckoutSnapshot internal constructor(
    val snapshotId: String,
    val sessionId: String,
    val slotId: String,
    val shoppingPackage: String,
    val pageRole: CheckoutPageRole,
    val pageFingerprint: String,
    val capturedAt: Long,
    val expiresAt: Long,
    val candidateRevision: Int,
    val sourceReason: SnapshotSourceReason,
    internal val imageBytes: ByteArray
) {
    internal fun copyForConsumer(): CheckoutSnapshot = copy(imageBytes = imageBytes.copyOf())

    /**
     * 释放 Ready.snapshot 的图片副本。
     *
     * consumeForAi() 返回的副本归 AI 消费方所有；消费方完成读取后必须主动调用本方法。
     */
    fun release() {
        imageBytes.fill(0)
    }
}

/** 供持久化协调器使用的快照元数据，不携带图片字节。 */
internal data class CheckoutSnapshotReference(
    val snapshotId: String,
    val sessionId: String,
    val slotId: String,
    val shoppingPackage: String,
    val pageRole: CheckoutPageRole,
    val pageFingerprint: String,
    val capturedAt: Long,
    val expiresAt: Long,
    val candidateRevision: Int,
    val sourceReason: SnapshotSourceReason
)

/** 截图工具或手动悬浮按钮向缓冲区提交的输入，不包含任何网络行为。 */
internal data class SnapshotCaptureRequest(
    val slotId: String,
    val sessionId: String,
    val shoppingPackage: String,
    val pageRole: CheckoutPageRole,
    val pageFingerprint: String,
    val sourceReason: SnapshotSourceReason,
    val imageBytes: ByteArray,
    /** 必须由敏感页面检查提供，手动截图不能用默认路径绕过该校验。 */
    val isSensitivePage: Boolean,
    val captureTarget: SnapshotCaptureTarget = SnapshotCaptureTarget.CHECKOUT
)

enum class SnapshotStoreRejection(val wireValue: String) {
    INVALID_ARGUMENT("INVALID_ARGUMENT"),
    NOT_CHECKOUT_PAGE("NOT_CHECKOUT_PAGE"),
    EMPTY_IMAGE("EMPTY_IMAGE"),
    SENSITIVE_PAGE("SENSITIVE_PAGE"),
    JOURNEY_ALREADY_TRIGGERED("JOURNEY_ALREADY_TRIGGERED")
}

sealed class SnapshotStoreResult {
    /** requiresDisambiguation 为 true 时，后续协调器必须选择指纹或进入待确认。 */
    data class Stored(
        val snapshot: CheckoutSnapshot,
        val replacedSnapshotId: String?,
        val candidateRevision: Int,
        val requiresDisambiguation: Boolean
    ) : SnapshotStoreResult()

    data class Unchanged(val snapshot: CheckoutSnapshot) : SnapshotStoreResult()

    data class Rejected(val reason: SnapshotStoreRejection) : SnapshotStoreResult()
}

/** 支付过程槽位搬运（改造 B-1）的失败原因；成功路径见 [PaymentProcessMoveResult.Moved]。 */
enum class PaymentProcessMoveRejection(val wireValue: String) {
    INVALID_ARGUMENT("INVALID_ARGUMENT"),
    NO_SOURCE_SLOT("NO_SOURCE_SLOT"),
    SESSION_MISMATCH("SESSION_MISMATCH"),
    NO_MOVABLE_CANDIDATE("NO_MOVABLE_CANDIDATE"),
    CANDIDATE_EXPIRED("CANDIDATE_EXPIRED"),
    ALREADY_CONSUMED("ALREADY_CONSUMED")
}

/**
 * 结算页槽位 → 支付过程槽位的搬运结果。
 *
 * 只暴露元数据、**不携带图片字节**：`CheckoutBufferGateway` 是 public 接口而
 * [CheckoutSnapshotReference] 是 internal 类型，故此处不能直接返回引用；消费侧本就按
 * `intent` + 槽位定位，无需引用。
 */
sealed class PaymentProcessMoveResult {
    /** 搬运成功；`expiresAt` 已按"搬入时刻 + 支付过程 TTL"显式重置（改造 B-4）。 */
    data class Moved(
        val sessionId: String,
        val targetSlotId: String,
        val pageFingerprint: String,
        val sourceReason: SnapshotSourceReason,
        val expiresAt: Long
    ) : PaymentProcessMoveResult()

    /**
     * 目标槽位已有未消费候选：一单一图不变式生效，本次**不搬第二张**（幂等成功）。
     *
     * 该分支真实存在：同一 sessionId 可收到多次支付交接事件（服务侧补发、多个构造点），
     * 且源槽位结构上可并存手动 + 自动两张候选 —— 首次只摘一张，第二次交接会再搬一次。
     */
    data class AlreadyOccupied(
        val sessionId: String,
        val targetSlotId: String
    ) : PaymentProcessMoveResult()

    data class Rejected(val reason: PaymentProcessMoveRejection) : PaymentProcessMoveResult()

    /** true 表示支付过程槽位当前持有本会话的图（含幂等跳过的情形）。 */
    val occupied: Boolean
        get() = this is Moved || this is AlreadyOccupied
}

enum class SnapshotConsumeRejection(val wireValue: String) {
    NO_BUFFERED_SNAPSHOT("NO_BUFFERED_SNAPSHOT"),
    SNAPSHOT_EXPIRED("SNAPSHOT_EXPIRED"),
    AMBIGUOUS_CANDIDATES("AMBIGUOUS_CANDIDATES"),
    NO_MATCHING_PAGE_FINGERPRINT("NO_MATCHING_PAGE_FINGERPRINT")
}

sealed class CheckoutAiTriggerResult {
    data class Ready(
        val requestKey: CheckoutRequestKey,
        val intent: PurchaseIntent,
        val snapshot: CheckoutSnapshot,
        /** 通知回退附带的安全元数据；普通购买意图为 null，暂不执行支付匹配。 */
        val paymentNotification: PaymentNotificationFallbackSignal? = null
    ) : CheckoutAiTriggerResult()

    data class AlreadyTriggered(val requestKey: CheckoutRequestKey) : CheckoutAiTriggerResult()

    data class Rejected(val reason: SnapshotConsumeRejection) : CheckoutAiTriggerResult()
}

/**
 * 解析支付交接栅栏应当采用的页面指纹。
 *
 * 调用方传入的是结算会话建立时的观察指纹，而槽位候选的指纹是截图那一刻写入的。
 * 京东与拼多多结算页的指纹持续漂移，两者几乎必然不同。若栅栏直接采用调用方指纹，
 * 消费端按候选指纹请求时就会与栅栏对不上，快照被自己的栅栏永久锁死，
 * 表现为支付证据一直停在 WAITING_ENRICHMENT、无法自动入账。
 *
 * @param requestedFingerprint 调用方传入的会话观察指纹。
 * @param candidateFingerprints 槽位内现存候选的指纹列表，按写入顺序排列。
 * @return 栅栏应采用的指纹：候选中存在同名则沿用，否则取最新候选；无候选时保持原值。
 */
internal fun resolveHandoffFenceFingerprint(
    requestedFingerprint: String,
    candidateFingerprints: List<String>
): String {
    if (candidateFingerprints.isEmpty()) {
        return requestedFingerprint
    }
    if (candidateFingerprints.contains(requestedFingerprint)) {
        return requestedFingerprint
    }
    return candidateFingerprints.last()
}

/**
 * 缓冲区日志出口。
 *
 * 生产默认走 [AndroidSnapshotBufferLogSink];JVM 单测注入捕获实现,
 * 使缓冲区决策逻辑可以在无 Android 框架的环境下被特征测试钉死。
 * 消息文本与触发时机保持与原 Log 调用逐字一致。
 */
internal interface SnapshotBufferLogSink {
    fun i(tag: String, message: String)

    fun w(tag: String, message: String)
}

internal object AndroidSnapshotBufferLogSink : SnapshotBufferLogSink {
    override fun i(tag: String, message: String) {
        JotBillLog.i(tag, message)
    }

    override fun w(tag: String, message: String) {
        JotBillLog.w(tag, message)
    }
}

/**
 * 快照过期回调调度。
 *
 * 生产默认主线程 Handler;JVM 单测注入可检视的假调度器,
 * 验证"取消旧回调-按最近过期时间重排"的调度契约。
 */
internal interface SnapshotExpirationScheduler {
    fun postDelayed(runnable: Runnable, delayMillis: Long)

    fun cancel(runnable: Runnable)
}

internal class MainLooperSnapshotExpirationScheduler : SnapshotExpirationScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun postDelayed(runnable: Runnable, delayMillis: Long) {
        handler.postDelayed(runnable, delayMillis)
    }

    override fun cancel(runnable: Runnable) {
        handler.removeCallbacks(runnable)
    }
}

/**
 * 按槽位保存当前结算页快照的短生命周期缓冲区。
 *
 * 每个槽位最多保留一个手动保护候选和一个最新自动候选；30 秒后自动失效并清零图片。
 * 同一会话、同一指纹的自动候选不能覆盖手动候选；不同指纹的自动候选递增版本并与
 * 手动候选并存，消费前必须明确指纹，否则进入歧义回退。只有 consumeForAi 收到购买
 * 意图时才会产生请求键并消费快照，普通观察、通知重放和 RN 重渲染无法触发上传。
 */
class CheckoutSnapshotBuffer internal constructor(
    private val snapshotTtlMillis: Long = SESSION_TTL_MILLIS,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val snapshotIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val expirationScheduler: SnapshotExpirationScheduler = MainLooperSnapshotExpirationScheduler(),
    private val logSink: SnapshotBufferLogSink = AndroidSnapshotBufferLogSink
) : CheckoutBufferGateway {
    private companion object {
        private const val TAG = "CheckoutSnapshotBuffer"

        /** "槽位丢弃(空槽位)"按 reason 的日志采样间隔。 */
        private const val EMPTY_DISCARD_LOG_INTERVAL_MILLIS = 5_000L
    }

    private data class SlotState(
        val sessionId: String,
        var manualCandidate: CheckoutSnapshot? = null,
        var automaticCandidate: CheckoutSnapshot? = null,
        var nextCandidateRevision: Int = 0,
        var consumedRequestKey: CheckoutRequestKey? = null,
        val expiredPageFingerprints: MutableSet<String> = mutableSetOf(),
        var handoffPageFingerprint: String? = null
    )

    private val slots = mutableMapOf<String, SlotState>()

    /** 各 reason 最近一次输出"槽位丢弃(空槽位)"的时间，用于日志采样。 */
    private val lastEmptyDiscardLogAt = mutableMapOf<String, Long>()

    /** 采样间隔内被抑制的空槽位丢弃次数，随下次输出一并汇报，避免静默丢信息。 */
    private val suppressedEmptyDiscardCount = mutableMapOf<String, Int>()
    private var closed = false
    private val expirationRunnable = Runnable {
        synchronized(this) {
            if (!closed) {
                purgeExpired(nowMillis())
            }
        }
    }

    init {
        require(snapshotTtlMillis > 0) { "snapshotTtlMillis 必须大于 0" }
    }

    @Synchronized
    // SnapshotCaptureRequest 为 internal 类型，将 store 降为 internal 避免公开暴露内部参数类型。
    internal fun store(request: SnapshotCaptureRequest): SnapshotStoreResult {
        if (closed) {
            return SnapshotStoreResult.Rejected(SnapshotStoreRejection.INVALID_ARGUMENT)
        }
        val now = nowMillis()
        purgeExpired(now)

        if (
            request.slotId.isBlank() ||
            request.sessionId.isBlank() ||
            request.shoppingPackage.isBlank() ||
            request.pageFingerprint.isBlank()
        ) {
            return SnapshotStoreResult.Rejected(SnapshotStoreRejection.INVALID_ARGUMENT)
        }
        val targetAllowed = when (request.captureTarget) {
            SnapshotCaptureTarget.CHECKOUT ->
                request.sourceReason == SnapshotSourceReason.CHECKOUT_STABLE &&
                    request.pageRole.canBufferSnapshot()

            SnapshotCaptureTarget.USER_RETRY ->
                request.sourceReason == SnapshotSourceReason.MANUAL_CAPTURE &&
                    request.pageRole.canBufferUserRetrySnapshot()
        }
        if (!targetAllowed) {
            return SnapshotStoreResult.Rejected(SnapshotStoreRejection.NOT_CHECKOUT_PAGE)
        }
        if (request.isSensitivePage) {
            return SnapshotStoreResult.Rejected(SnapshotStoreRejection.SENSITIVE_PAGE)
        }
        if (request.imageBytes.isEmpty()) {
            return SnapshotStoreResult.Rejected(SnapshotStoreRejection.EMPTY_IMAGE)
        }

        val existing = slots[request.slotId]
        val isUserRetryCapture =
            request.captureTarget == SnapshotCaptureTarget.USER_RETRY &&
                request.sourceReason == SnapshotSourceReason.MANUAL_CAPTURE
        if (
            existing?.sessionId == request.sessionId &&
            existing.consumedRequestKey != null &&
            !isUserRetryCapture
        ) {
            return SnapshotStoreResult.Rejected(SnapshotStoreRejection.JOURNEY_ALREADY_TRIGGERED)
        }

        val state = if (existing == null || existing.sessionId != request.sessionId) {
            existing?.let { releaseCandidates(it) }
            SlotState(request.sessionId).also { slots[request.slotId] = it }
        } else {
            existing
        }

        // D-16 = B（自动优先）：同指纹即"自动也认得出这一页"，按职责边界原则
        // （自动负责认得出的页面、手动负责认不出的页面）应由自动接管。
        // 旧实现在此处拒绝同指纹的自动截图（MANUAL_CANDIDATE_PROTECTED），
        // 使自动被手动反向干扰，已取消 ⇒ 自动候选覆盖手动候选。
        // 注意：协调器侧还有两处"同指纹手动候选 ⇒ 前置拒绝自动"的守卫（实时 + 排队），
        // 三者必须同改，只改此处不生效。
        val sameFingerprint = findCandidate(state, request.pageFingerprint)
        if (sameFingerprint != null) {
            if (sameFingerprint.sourceReason == request.sourceReason) {
                return SnapshotStoreResult.Unchanged(sameFingerprint.copyForConsumer())
            }

            removeCandidate(state, sameFingerprint)?.release()
        }

        if (isUserRetryCapture) {
            // 用户主动重试代表一次新的明确消费，解除原自动请求的去重墓碑。
            state.consumedRequestKey = null
        }

        var replacedSnapshotId: String? = sameFingerprint?.snapshotId
        when (request.sourceReason) {
            SnapshotSourceReason.MANUAL_CAPTURE -> {
                val previousManual = state.manualCandidate
                if (previousManual != null) {
                    state.manualCandidate = null
                    previousManual.release()
                    replacedSnapshotId = previousManual.snapshotId
                }
            }
            SnapshotSourceReason.CHECKOUT_STABLE -> {
                val previousAutomatic = state.automaticCandidate
                if (previousAutomatic != null) {
                    state.automaticCandidate = null
                    previousAutomatic.release()
                    replacedSnapshotId = previousAutomatic.snapshotId
                }
            }
        }

        val candidateRevision = state.nextCandidateRevision + 1
        state.nextCandidateRevision = candidateRevision
        val snapshot = CheckoutSnapshot(
            snapshotId = snapshotIdFactory(),
            sessionId = request.sessionId,
            slotId = request.slotId,
            shoppingPackage = request.shoppingPackage,
            pageRole = request.pageRole,
            pageFingerprint = request.pageFingerprint,
            capturedAt = now,
            expiresAt = now + snapshotTtlMillis,
            candidateRevision = candidateRevision,
            sourceReason = request.sourceReason,
            imageBytes = request.imageBytes.copyOf()
        )
        when (request.sourceReason) {
            SnapshotSourceReason.MANUAL_CAPTURE -> state.manualCandidate = snapshot
            SnapshotSourceReason.CHECKOUT_STABLE -> state.automaticCandidate = snapshot
        }
        state.expiredPageFingerprints.remove(request.pageFingerprint)
        logSink.i(
            TAG,
            "候选已写入: slot=${request.slotId}, session=${request.sessionId.take(8)}, " +
                "fingerprint=${request.pageFingerprint.take(12)}, " +
                "source=${request.sourceReason.wireValue}, revision=$candidateRevision, " +
                "replaced=${replacedSnapshotId?.take(8) ?: "none"}"
        )
        return SnapshotStoreResult.Stored(
            snapshot = snapshot.copyForConsumer(),
            replacedSnapshotId = replacedSnapshotId,
            candidateRevision = candidateRevision,
            requiresDisambiguation = state.manualCandidate != null && state.automaticCandidate != null
        ).also {
            scheduleNextExpirationLocked(now)
        }
    }

    @Synchronized
    fun current(slotId: String): CheckoutSnapshot? {
        if (closed) {
            return null
        }
        val now = nowMillis()
        purgeExpired(now)
        val candidates = slots[slotId]?.let(::candidateList) ?: return null
        return candidates.singleOrNull()?.copyForConsumer()
    }

    /** 返回当前未过期候选的副本；多个候选时交给协调器或待确认界面选择。 */
    @Synchronized
    override fun currentCandidates(slotId: String): List<CheckoutSnapshot> {
        if (closed) {
            return emptyList()
        }
        val now = nowMillis()
        purgeExpired(now)
        return slots[slotId]
            ?.let(::candidateList)
            ?.sortedByDescending { it.candidateRevision }
            ?.map { it.copyForConsumer() }
            ?: emptyList()
    }

    /** 不复制图片地检查候选，供截图入口做自动/手动去重和保护判断。 */
    @Synchronized
    fun hasCandidate(
        slotId: String,
        sessionId: String,
        pageFingerprint: String,
        sourceReason: SnapshotSourceReason? = null
    ): Boolean {
        if (closed) {
            return false
        }
        purgeExpired(nowMillis())
        val state = slots[slotId] ?: return false
        if (state.sessionId != sessionId) {
            return false
        }
        return candidateList(state).any { candidate ->
            candidate.pageFingerprint == pageFingerprint &&
                (sourceReason == null || candidate.sourceReason == sourceReason)
        }
    }

    /** 仅识别非结算页的手动候选，供离开结算页后的生命周期保护使用。 */
    @Synchronized
    fun hasNonCheckoutManualCandidate(
        slotId: String,
        sessionId: String,
        pageFingerprint: String
    ): Boolean {
        if (closed) {
            return false
        }
        purgeExpired(nowMillis())
        val state = slots[slotId] ?: return false
        if (state.sessionId != sessionId) {
            return false
        }
        val candidate = state.manualCandidate ?: return false
        return candidate.pageRole.canBufferUserRetrySnapshot() &&
            candidate.pageFingerprint == pageFingerprint
    }

    /**
     * 判断槽位是否已为指定会话设置支付交接栅栏。
     *
     * 与 hasCandidate 的区别是本方法不关心图片是否已落盘：栅栏一旦设置，
     * 说明该会话正处于"等待切支付应用消费"的交接态，即使截图仍在飞行中，
     * 槽位也不允许被手动重试上下文抢占。
     *
     * 京东弹窗结算页在稳定后数百毫秒即跳中间页，截图常常来不及落盘；
     * 此时槽位候选为空，若仅凭"有无候选"判断就会放行抢占，
     * 交接栅栏被 discard 后 handoffCheckout 仍指向该会话，形成永久残留会话，
     * 真机表现为"交接栅栏设置失败(槽位不存在)"反复出现、自动截图长期不触发。
     *
     * 有意不调用 purgeExpired：栅栏的职责就是保护尚未落盘的会话，
     * 若因过期清理而返回 false，保护就失去了意义。过期回收仍由
     * consumeForAi / currentCandidates 等正常路径负责。
     *
     * @param slotId 购物包槽位标识。
     * @param sessionId 期望持有栅栏的会话标识。
     * @return true 表示该会话已设置交接栅栏，槽位不得被抢占。
     */
    @Synchronized
    fun hasPaymentHandoffFence(slotId: String, sessionId: String): Boolean {
        if (closed || slotId.isBlank() || sessionId.isBlank()) {
            return false
        }
        val state = slots[slotId] ?: return false
        return state.sessionId == sessionId && state.handoffPageFingerprint != null
    }

    /** 支付交接期间只允许原页面指纹消费，避免无指纹请求误取旧会话快照。 */
    @Synchronized
    fun retainForPaymentHandoff(
        slotId: String,
        sessionId: String,
        pageFingerprint: String
    ) {
        if (closed || slotId.isBlank() || sessionId.isBlank() || pageFingerprint.isBlank()) {
            return
        }
        val state = slots[slotId]
        if (state == null) {
            // 槽位已被清理：交接元数据仍在但图片候选已丢失，后续必然 NO_BUFFERED_SNAPSHOT。
logSink.w(
                TAG,
                "交接栅栏设置失败(槽位不存在): slot=$slotId, " +
                    "session=${sessionId.take(8)}, fingerprint=${pageFingerprint.take(12)}"
            )
            return
        }
        if (state.sessionId == sessionId) {
            // 栅栏必须落在候选实际持有的指纹上。调用方传入的是会话建立时的
            // 观察指纹，而候选是截图那一刻写入的；京东/拼多多结算页指纹持续
            // 漂移，两者几乎必然不同。若直接采用调用方指纹，消费端
            // （requested=候选指纹）与栅栏（barrier=观察指纹）永远对不上，
            // 快照被自己的栅栏永久锁死。真机 .tmp-device-capture-20260824-142754：
            //   交接栅栏已设置: fingerprint=0b64b3afe9ce, candidates=...@1286a1e87671
            //   快照消费被交接栅栏拒绝: requested=1286a1e87671, barrier=0b64b3afe9ce
            // 后果是所有支付停在 WAITING_ENRICHMENT，无一笔自动入账。
            val candidates = candidateList(state)
            val effectiveFingerprint = resolveHandoffFenceFingerprint(
                requestedFingerprint = pageFingerprint,
                candidateFingerprints = candidates.map { it.pageFingerprint }
            )
            state.handoffPageFingerprint = effectiveFingerprint
logSink.i(
                TAG,
                "交接栅栏已设置: slot=$slotId, session=${sessionId.take(8)}, " +
                    "fingerprint=${effectiveFingerprint.take(12)}, " +
                    (if (effectiveFingerprint != pageFingerprint) {
                        "requested=${pageFingerprint.take(12)}(已按候选校正), "
                    } else {
                        ""
                    }) +
                    "candidates=${describeCandidates(state)}"
            )
        } else {
logSink.w(
                TAG,
                "交接栅栏设置失败(会话不匹配): slot=$slotId, " +
                    "slotSession=${state.sessionId.take(8)}, " +
                    "requestSession=${sessionId.take(8)}, " +
                    "candidates=${describeCandidates(state)}"
            )
        }
    }

    /** 原结算页恢复后解除交接栅栏，保留同一会话已有候选和消费标记。 */
    @Synchronized
    fun restoreFromPaymentHandoff(
        slotId: String,
        sessionId: String,
        pageFingerprint: String
    ) {
        if (closed) {
            return
        }
        val state = slots[slotId] ?: return
        if (
            state.sessionId == sessionId &&
            state.handoffPageFingerprint == pageFingerprint
        ) {
            state.handoffPageFingerprint = null
        }
    }

    /**
     * 把结算页槽位中"本会话的候选图"搬进支付过程专用槽位（改造 B-1）。
     *
     * - 目的：让图在"用户进入支付应用 → 完成支付"整段期间**不被后续截图覆盖**，
     *   也不被同包新会话的 [SlotState] 替换连带释放。
     * - 语义：**所有权转移**（不复制字节）——源槽位中该候选经 [detachCandidate] 摘除
     *   但**不 release**；若误走会 release 的路径，图会在搬入前被抹零，
     *   支付成功时无图可送（改造 B 最容易出错的一点）。
     * - 目标槽位：[paymentProcessSlotId]，即 `payment:<sessionId>`，**一单一图**；
     *   搬入时**显式重置** `expiresAt` = 搬入时刻 + [paymentProcessTtlMillis]
     *   （不得沿用源候选"store 时刻 + 结算页 TTL"的旧值，见改造 B-4）。
     *
     * 候选选择（与 [consumeForAi] 的无指纹路径同源）：
     * 1. 指纹命中优先（要求 CHECKOUT 角色）；
     * 2. 未命中/未传指纹 ⇒ 自动候选优先，其次手动候选 —— 结算页指纹高频漂移
     *    （京东/拼多多）会让交接指纹与槽内候选**几乎必然不同**，严格按指纹搬运会
     *    静默漏搬；这是"指纹优先 + 槽内选择"而非"强制指纹匹配"的原因。
     * 3. 一律要求 CHECKOUT 角色：支付结果意图的消费受"非结算角色不可消费"守卫
     *    （[consumeForAi] 角色守卫），搬一张注定消费不掉的图只会白占槽位。
     *
     * @return 见 [PaymentProcessMoveResult]；源无候选 / 已过期 / 已消费时为 Rejected。
     */
    override fun moveToPaymentProcess(
        sourceSlotId: String,
        sessionId: String,
        pageFingerprint: String?
    ): PaymentProcessMoveResult =
        moveToPaymentProcessLocked(
            sourceSlotId = sourceSlotId,
            sessionId = sessionId,
            pageFingerprint = pageFingerprint,
            paymentProcessTtlMillis = PAYMENT_PROCESS_TTL_MILLIS
        )

    /**
     * [moveToPaymentProcess] 的完整实现。
     *
     * ⚠️ 网关方法为 public 而 [CheckoutSnapshotReference] 是 internal 类型，且 override
     * 不得增加参数 ⇒ TTL 以私有参数形式保留在此实现上（规格 B-1 签名的落点），
     * 网关侧固定使用 [PAYMENT_PROCESS_TTL_MILLIS]。
     */
    @Synchronized
    private fun moveToPaymentProcessLocked(
        sourceSlotId: String,
        sessionId: String,
        pageFingerprint: String?,
        paymentProcessTtlMillis: Long
    ): PaymentProcessMoveResult {
        if (
            closed ||
            sourceSlotId.isBlank() ||
            sessionId.isBlank() ||
            paymentProcessTtlMillis <= 0L
        ) {
            return PaymentProcessMoveResult.Rejected(PaymentProcessMoveRejection.INVALID_ARGUMENT)
        }
        val now = nowMillis()
        purgeExpired(now)
        val targetSlotId = paymentProcessSlotId(sessionId)

        // 一单一图不变式（§2.0 方案丙）：目标槽位已消费 ⇒ 重复搬运只会得到 AlreadyTriggered，
        // 无意义；已有未消费候选 ⇒ 本次不搬第二张（幂等），防止同一会话的两张候选
        // 在支付槽位里形成新的歧义。
        slots[targetSlotId]?.let { target ->
            if (target.consumedRequestKey != null) {
                logSink.i(
                    TAG,
                    "支付过程搬运跳过(槽位已消费): target=$targetSlotId, " +
                        "session=${sessionId.take(8)}, tombstone=${target.consumedRequestKey?.value?.take(24)}"
                )
                return PaymentProcessMoveResult.Rejected(PaymentProcessMoveRejection.ALREADY_CONSUMED)
            }
            if (candidateList(target).isNotEmpty()) {
                logSink.i(
                    TAG,
                    "支付过程槽位已占用，跳过重复搬运: target=$targetSlotId, " +
                        "session=${sessionId.take(8)}, existing=${describeCandidates(target)}"
                )
                return PaymentProcessMoveResult.AlreadyOccupied(sessionId, targetSlotId)
            }
        }

        val state = slots[sourceSlotId] ?: run {
            logSink.w(
                TAG,
                "支付过程搬运失败(源槽位不存在): source=$sourceSlotId, " +
                    "session=${sessionId.take(8)}, liveSlots=" +
                    slots.keys.joinToString(",").ifBlank { "none" }
            )
            return PaymentProcessMoveResult.Rejected(PaymentProcessMoveRejection.NO_SOURCE_SLOT)
        }
        if (state.sessionId != sessionId) {
            logSink.w(
                TAG,
                "支付过程搬运失败(会话不匹配): source=$sourceSlotId, " +
                    "slotSession=${state.sessionId.take(8)}, requestSession=${sessionId.take(8)}"
            )
            return PaymentProcessMoveResult.Rejected(PaymentProcessMoveRejection.SESSION_MISMATCH)
        }
        if (state.consumedRequestKey != null) {
            logSink.i(
                TAG,
                "支付过程搬运跳过(源会话已消费): source=$sourceSlotId, " +
                    "session=${sessionId.take(8)}"
            )
            return PaymentProcessMoveResult.Rejected(PaymentProcessMoveRejection.ALREADY_CONSUMED)
        }

        val candidate = resolveMovableCandidate(state, pageFingerprint)
        if (candidate == null) {
            logSink.w(
                TAG,
                "支付过程搬运失败(无可搬候选): source=$sourceSlotId, " +
                    "session=${sessionId.take(8)}, requested=${pageFingerprint?.take(12) ?: "null"}, " +
                    "candidates=${describeCandidates(state)}"
            )
            return PaymentProcessMoveResult.Rejected(PaymentProcessMoveRejection.NO_MOVABLE_CANDIDATE)
        }
        if (now >= candidate.expiresAt) {
            // 过期候选按消费路径同样处理：摘除 + 抹零 + 记入过期墓碑。
            removeCandidate(state, candidate)?.release()
            state.expiredPageFingerprints += candidate.pageFingerprint
            scheduleNextExpirationLocked(now)
            logSink.i(
                TAG,
                "支付过程搬运失败(候选已过期): source=$sourceSlotId, " +
                    "session=${sessionId.take(8)}, fingerprint=${candidate.pageFingerprint.take(12)}"
            )
            return PaymentProcessMoveResult.Rejected(PaymentProcessMoveRejection.CANDIDATE_EXPIRED)
        }

        val detached = detachCandidate(state, candidate)
            ?: return PaymentProcessMoveResult.Rejected(PaymentProcessMoveRejection.NO_MOVABLE_CANDIDATE)
        val moved = detached.copy(
            slotId = targetSlotId,
            // 显式重置过期时刻（B-4）：起算点 = 搬入时刻，而非沿用源候选的 store 时刻。
            expiresAt = now + paymentProcessTtlMillis
        )
        val targetState = SlotState(sessionId).also { slots[targetSlotId] = it }
        when (moved.sourceReason) {
            SnapshotSourceReason.MANUAL_CAPTURE -> targetState.manualCandidate = moved
            SnapshotSourceReason.CHECKOUT_STABLE -> targetState.automaticCandidate = moved
        }
        targetState.nextCandidateRevision = moved.candidateRevision
        scheduleNextExpirationLocked(now)
        logSink.i(
            TAG,
            "支付过程槽位已搬入: target=$targetSlotId, session=${sessionId.take(8)}, " +
                "fingerprint=${moved.pageFingerprint.take(12)}, source=${moved.sourceReason.wireValue}, " +
                "bytes=${moved.imageBytes.size}, expiresAt=+$paymentProcessTtlMillis" +
                "ms, 源槽位剩余=${describeCandidates(state)}"
        )
        return PaymentProcessMoveResult.Moved(
            sessionId = sessionId,
            targetSlotId = targetSlotId,
            pageFingerprint = moved.pageFingerprint,
            sourceReason = moved.sourceReason,
            expiresAt = moved.expiresAt
        )
    }

    /**
     * 搬运的候选选择：指纹命中优先，否则按 D-16 自动优先、再手动候选。
     * 见 [moveToPaymentProcess] 的选择规则说明。
     */
    private fun resolveMovableCandidate(
        state: SlotState,
        pageFingerprint: String?
    ): CheckoutSnapshot? {
        // 批 V-4b：宽进后自动候选的角色可能是 PRODUCT_DETAIL / CART（详情页直达支付）
        // ⇒ 选择判据改用可截角色集合（单一出处 CheckoutContract）。若仍硬编 CHECKOUT，
        // B-1 搬运会搬不到图 ⇒ 恒落 PAYMENT_PROCESS_SNAPSHOT_MISSING。
        // 安全前提不变：目标槽位 `payment:<sessionId>` 是一单一图，槽内不会出现他单候选。
        if (pageFingerprint != null) {
            findCandidate(state, pageFingerprint)
                ?.takeIf { it.pageRole in AUTO_DISPATCHABLE_PAGE_ROLES }
                ?.let { return it }
        }
        return state.automaticCandidate?.takeIf { it.pageRole in AUTO_DISPATCHABLE_PAGE_ROLES }
            ?: state.manualCandidate?.takeIf { it.pageRole in AUTO_DISPATCHABLE_PAGE_ROLES }
    }

    /** 只读取与页面键对应的引用，避免为支付匹配复制或暴露图片。 */
    @Synchronized
    // CheckoutSnapshotReference 为 internal 类型，将 findReference 降为 internal 避免公开暴露内部返回类型。
    internal fun findReference(
        slotId: String,
        pageFingerprint: String? = null,
        snapshotId: String? = null
    ): CheckoutSnapshotReference? {
        if (closed) {
            return null
        }
        purgeExpired(nowMillis())
        val state = slots[slotId] ?: return null
        val candidate = candidateList(state).firstOrNull { snapshot ->
            (pageFingerprint == null || snapshot.pageFingerprint == pageFingerprint) &&
                (snapshotId == null || snapshot.snapshotId == snapshotId)
        } ?: return null
        return CheckoutSnapshotReference(
            snapshotId = candidate.snapshotId,
            sessionId = candidate.sessionId,
            slotId = candidate.slotId,
            shoppingPackage = candidate.shoppingPackage,
            pageRole = candidate.pageRole,
            pageFingerprint = candidate.pageFingerprint,
            capturedAt = candidate.capturedAt,
            expiresAt = candidate.expiresAt,
            candidateRevision = candidate.candidateRevision,
            sourceReason = candidate.sourceReason
        )
    }

    /**
     * 消费当前快照并生成唯一请求键。该方法只接受明确的购买意图，消费后立即清空内部图片。
     */
    @Synchronized
    override fun consumeForAi(
        slotId: String,
        intent: PurchaseIntent,
        pageFingerprint: String?
    ): CheckoutAiTriggerResult {
        if (closed) {
            return CheckoutAiTriggerResult.Rejected(SnapshotConsumeRejection.NO_BUFFERED_SNAPSHOT)
        }
        val now = nowMillis()
        purgeExpired(now)
        val state = slots[slotId]
            ?: run {
                // 槽位整体不存在：图片候选连同槽位一起被 discard/clear 移除。
                // 与"槽内无候选"区分，用于判定是会话切换清理还是失效清理。
    logSink.w(
                    TAG,
                    "快照消费失败(槽位不存在): slot=$slotId, intent=${intent.name}, " +
                        "requested=${pageFingerprint?.take(12) ?: "null"}, " +
                        "liveSlots=${slots.keys.joinToString(",").ifBlank { "none" }}"
                )
                return CheckoutAiTriggerResult.Rejected(SnapshotConsumeRejection.NO_BUFFERED_SNAPSHOT)
            }

        state.consumedRequestKey?.let {
            return CheckoutAiTriggerResult.AlreadyTriggered(it)
        }

        val candidates = candidateList(state)

        // 支付交接栅栏只保护仍存在的候选：挂起快照过期被清理后栅栏失去保护对象，
        // 不能继续拦截消费（否则槽残留会让后续请求永远得到 NO_MATCHING_PAGE_FINGERPRINT，
        // 而真实语义是快照已不可用）。
        state.handoffPageFingerprint?.let { retainedFingerprint ->
            if (
                pageFingerprint != retainedFingerprint &&
                candidates.isNotEmpty()
            ) {
                logSink.i(
                    TAG,
                    "快照消费被交接栅栏拒绝: requested=${pageFingerprint?.take(12)}, " +
                        "barrier=${retainedFingerprint.take(12)}, " +
                        "candidates=${candidates.joinToString(",") { it.pageFingerprint.take(12) }}"
                )
                return CheckoutAiTriggerResult.Rejected(
                    if (pageFingerprint == null) {
                        SnapshotConsumeRejection.AMBIGUOUS_CANDIDATES
                    } else {
                        SnapshotConsumeRejection.NO_MATCHING_PAGE_FINGERPRINT
                    }
                )
            }
        }
        if (candidates.isEmpty()) {
            if (
                state.expiredPageFingerprints.isNotEmpty() &&
                (pageFingerprint == null ||
                    state.expiredPageFingerprints.contains(pageFingerprint))
            ) {
                state.expiredPageFingerprints.clear()
                slots.remove(slotId)
    logSink.i(
                    TAG,
                    "快照消费失败(已过期): slot=$slotId, session=${state.sessionId.take(8)}, " +
                        "requested=${pageFingerprint?.take(12) ?: "null"}"
                )
                return CheckoutAiTriggerResult.Rejected(SnapshotConsumeRejection.SNAPSHOT_EXPIRED)
            }
            // 槽位存在但候选为空：说明图片已被 discard/clear 释放，而非 TTL 过期。
logSink.w(
                TAG,
                "快照消费失败(槽内无候选): slot=$slotId, session=${state.sessionId.take(8)}, " +
                    "requested=${pageFingerprint?.take(12) ?: "null"}, " +
                    "barrier=${state.handoffPageFingerprint?.take(12) ?: "null"}"
            )
            return CheckoutAiTriggerResult.Rejected(SnapshotConsumeRejection.NO_BUFFERED_SNAPSHOT)
        }

        val snapshot = if (pageFingerprint != null) {
            findCandidate(state, pageFingerprint)
                ?: if (state.expiredPageFingerprints.contains(pageFingerprint)) {
                    state.expiredPageFingerprints.remove(pageFingerprint)
                    return CheckoutAiTriggerResult.Rejected(SnapshotConsumeRejection.SNAPSHOT_EXPIRED)
                } else {
                    logSink.i(
                        TAG,
                        "快照指纹不匹配: requested=${pageFingerprint.take(12)}, " +
                            "barrier=${state.handoffPageFingerprint?.take(12) ?: "null"}, " +
                            "candidates=${candidates.joinToString(",") { it.pageFingerprint.take(12) }}, " +
                            "expired=${state.expiredPageFingerprints.joinToString(",") { it.take(12) }}"
                    )
                    return CheckoutAiTriggerResult.Rejected(
                        SnapshotConsumeRejection.NO_MATCHING_PAGE_FINGERPRINT
                    )
                }
        } else {
            // 无指纹消费：D-16 = B ⇒ 自动优先 + 自动不可消费才回退手动。
            // 判据与下方统一角色守卫同源（批 V-4b 起两者同为 AUTO_DISPATCHABLE_PAGE_ROLES：
            // 自动候选的角色由 store 的 target 门禁保证落在该集合内；手动候选可为更宽的
            // 页面，如断言通道的首页/未知页）。
            // 不满足角色判据的候选不得被选中——否则会先选中再被角色守卫拒绝，
            // 丢失本应保留的歧义语义；此时回退 singleOrNull：槽内只剩自动候选时
            // 唯一即无歧义。
            // ⚠️ 无指纹的 USER_RETRY 组合生产不可达（其公开入口恒带合法 64 位 hex 指纹，
            // 见 UserRetryCaptureContext.isComplete()），故此处不再为它设例外。
            // 此前一律 AMBIGUOUS_CANDIDATES 拒绝，导致手动补截后支付交接反而消费不到
            // 任何快照；purgeExpired 已在上方执行，此处候选必然未过期。
            val automatic = state.automaticCandidate
                ?.takeIf { it.pageRole in AUTO_DISPATCHABLE_PAGE_ROLES }
            val manual = state.manualCandidate
                ?.takeIf { it.pageRole in AUTO_DISPATCHABLE_PAGE_ROLES }
            automatic
                ?: manual
                ?: candidates.singleOrNull()
                ?: return CheckoutAiTriggerResult.Rejected(
                    SnapshotConsumeRejection.AMBIGUOUS_CANDIDATES
                )
        }
        // 页面角色守卫：只有 USER_RETRY（手动截图重试）允许消费任意非敏感页快照；
        // 自动意图必须落在可截角色集合内（批 V-4b 起为 AUTO_DISPATCHABLE_PAGE_ROLES，
        // 含 PRODUCT_DETAIL / CART / CHECKOUT —— 支撑"详情页直达收银台"）。
        // 此前误用 canBufferUserRetrySnapshot()（含 CHECKOUT），导致结算页快照对
        // 自动购买意图一律被拒，AI 永远无法派发。
        // ⚠️ USER_RETRY 的豁免分支**必须保留**：断言通道是"用户显式点击绕过页面分类"
        // 的独立旁路（Service.performUserAssertedCapture），不受宽进改动影响。
        if (snapshot.pageRole !in AUTO_DISPATCHABLE_PAGE_ROLES && intent != PurchaseIntent.USER_RETRY) {
            logSink.i(
                TAG,
                "快照页面角色不可消费: role=${snapshot.pageRole}, intent=${intent.wireValue}"
            )
            return CheckoutAiTriggerResult.Rejected(
                SnapshotConsumeRejection.NO_MATCHING_PAGE_FINGERPRINT
            )
        }
        if (now >= snapshot.expiresAt) {
            removeCandidate(state, snapshot)?.release()
            state.expiredPageFingerprints += snapshot.pageFingerprint
            scheduleNextExpirationLocked(now)
            return CheckoutAiTriggerResult.Rejected(SnapshotConsumeRejection.SNAPSHOT_EXPIRED)
        }

        val requestKey = CheckoutRequestKey.from(
            sessionId = snapshot.sessionId,
            shoppingPackage = snapshot.shoppingPackage,
            pageFingerprint = snapshot.pageFingerprint
        )
        val consumerSnapshot = snapshot.copyForConsumer()
        releaseConsumedCandidate(state, snapshot)
        state.consumedRequestKey = requestKey
        scheduleNextExpirationLocked(now)
        return CheckoutAiTriggerResult.Ready(
            requestKey = requestKey,
            intent = intent,
            snapshot = consumerSnapshot
        )
    }

    /**
     * 页面回到非结算角色或会话取消时调用，确保旧图片不能被后续事件消费。
     *
     * @param slotId 目标槽位。
     * @param reason 清理原因，仅用于诊断日志；调用方须传入可区分分支的稳定字符串。
     */
    @Synchronized
    fun discard(slotId: String, reason: String = "UNSPECIFIED") {
        val removed = slots.remove(slotId)
        if (removed != null) {
logSink.i(
                TAG,
                "槽位丢弃: reason=$reason, slot=$slotId, " +
                    "session=${removed.sessionId.take(8)}, " +
                    "candidates=${describeCandidates(removed)}, " +
                    "handoffFingerprint=${removed.handoffPageFingerprint?.take(12) ?: "null"}, " +
                    "consumed=${removed.consumedRequestKey != null}"
            )
            releaseCandidates(removed)
        } else {
            // 空槽位丢弃没有实际影响，但真机上手动重试上下文每秒可触发数次
            // （USER_RETRY_CONTEXT_CHANGED / USER_RETRY_NEW_PAGE 成对出现），
            // 曾在 40 秒内刷出数百行、严重淹没真正的候选写入与栅栏日志。
            // 按 reason 分别采样：既保留"该分支仍在发生"的可见性，
            // 又不会让某个高频原因掩盖其他原因。真实丢弃始终逐次打印。
            val now = nowMillis()
            val lastAt = lastEmptyDiscardLogAt[reason]
            if (lastAt == null || now - lastAt >= EMPTY_DISCARD_LOG_INTERVAL_MILLIS) {
                lastEmptyDiscardLogAt[reason] = now
                val suppressed = suppressedEmptyDiscardCount.remove(reason) ?: 0
    logSink.i(
                    TAG,
                    "槽位丢弃(空槽位): reason=$reason, slot=$slotId" +
                        if (suppressed > 0) ", 期间已抑制${suppressed}次" else ""
                )
            } else {
                suppressedEmptyDiscardCount[reason] =
                    (suppressedEmptyDiscardCount[reason] ?: 0) + 1
            }
        }
        scheduleNextExpirationLocked(nowMillis())
    }

    /**
     * 服务中断或销毁时清理所有槽位，确保没有遗漏的图片字节继续留在内存中。
     *
     * @param reason 清理原因，仅用于诊断日志；调用方须传入可区分分支的稳定字符串。
     */
    @Synchronized
    fun clear(reason: String = "UNSPECIFIED") {
        if (slots.isEmpty()) {
logSink.i(TAG, "缓冲清空(无槽位): reason=$reason")
        } else {
            slots.forEach { (slotId, state) ->
    logSink.i(
                    TAG,
                    "缓冲清空: reason=$reason, slot=$slotId, " +
                        "session=${state.sessionId.take(8)}, " +
                        "candidates=${describeCandidates(state)}, " +
                        "handoffFingerprint=${state.handoffPageFingerprint?.take(12) ?: "null"}, " +
                        "consumed=${state.consumedRequestKey != null}"
                )
            }
        }
        slots.values.forEach(::releaseCandidates)
        slots.clear()
        // 清空是一次明确的状态边界：重置采样，避免清空后的首次空槽位丢弃被误抑制。
        lastEmptyDiscardLogAt.clear()
        suppressedEmptyDiscardCount.clear()
        expirationScheduler.cancel(expirationRunnable)
    }

    /** 服务销毁时停止主动过期回调并释放所有槽位图片。 */
    @Synchronized
    fun close() {
        if (closed) {
            return
        }
        closed = true
        expirationScheduler.cancel(expirationRunnable)
        slots.values.forEach(::releaseCandidates)
        slots.clear()
    }

    @Synchronized
    private fun purgeExpired(now: Long) {
        if (closed) {
            return
        }
        slots.values.forEach { state ->
            state.manualCandidate?.let { candidate ->
                if (candidate.expiresAt <= now) {
                    state.manualCandidate = null
                    candidate.release()
                    state.expiredPageFingerprints += candidate.pageFingerprint
                }
            }
            state.automaticCandidate?.let { candidate ->
                if (candidate.expiresAt <= now) {
                    state.automaticCandidate = null
                    candidate.release()
                    state.expiredPageFingerprints += candidate.pageFingerprint
                }
            }
        }
        scheduleNextExpirationLocked(now)
    }

    private fun scheduleNextExpirationLocked(now: Long) {
        expirationScheduler.cancel(expirationRunnable)
        if (closed) {
            return
        }

        var nextExpiration: Long? = null
        slots.values.forEach { state ->
            listOfNotNull(state.manualCandidate, state.automaticCandidate).forEach { candidate ->
                if (nextExpiration == null || candidate.expiresAt < nextExpiration!!) {
                    nextExpiration = candidate.expiresAt
                }
            }
        }
        nextExpiration?.let { expiresAt ->
            expirationScheduler.postDelayed(
                expirationRunnable,
                (expiresAt - now).coerceAtLeast(0L)
            )
        }
    }

    /** 汇总槽内候选的来源与指纹，供生命周期日志定位丢失的具体快照。 */
    private fun describeCandidates(state: SlotState): String {
        val candidates = candidateList(state)
        if (candidates.isEmpty()) {
            return "none"
        }
        return candidates.joinToString(",") { candidate ->
            "${candidate.sourceReason.wireValue}@${candidate.pageFingerprint.take(12)}"
        }
    }

    /**
     * 只释放**被消费的那一张**候选（D-16 = B 的直接推论，见 `IMPL_PLAN_VERSION_B` §8.6）。
     *
     * 未被消费的候选必须保留图片字节：它正是"自动图被下游判无效时回退手动"的保底。
     * 旧实现无条件释放两张，会在消费自动图时连带抹掉手动图，使回退无图可用。
     * 交接栅栏与过期墓碑仍随消费清除：栅栏是一次交接的临时保护，消费完成后失去保护
     * 对象，若不清除会反过来拦住保留下来的候选。
     *
     * 槽位整体消失的路径（`discard` / `clear` / `close` / 换会话）仍走 [releaseCandidates]。
     */
    private fun releaseConsumedCandidate(state: SlotState, consumed: CheckoutSnapshot) {
        removeCandidate(state, consumed)?.release()
        state.expiredPageFingerprints.clear()
        state.handoffPageFingerprint = null
    }

    private fun releaseCandidates(state: SlotState) {
        state.manualCandidate?.release()
        state.automaticCandidate?.release()
        state.manualCandidate = null
        state.automaticCandidate = null
        state.expiredPageFingerprints.clear()
        state.handoffPageFingerprint = null
    }

    private fun candidateList(state: SlotState): List<CheckoutSnapshot> = listOfNotNull(
        state.manualCandidate,
        state.automaticCandidate
    )

    private fun findCandidate(state: SlotState, pageFingerprint: String): CheckoutSnapshot? =
        candidateList(state).firstOrNull { it.pageFingerprint == pageFingerprint }

    private fun removeCandidate(state: SlotState, snapshot: CheckoutSnapshot): CheckoutSnapshot? {
        return when {
            state.manualCandidate?.snapshotId == snapshot.snapshotId -> {
                state.manualCandidate = null
                snapshot
            }
            state.automaticCandidate?.snapshotId == snapshot.snapshotId -> {
                state.automaticCandidate = null
                snapshot
            }
            else -> null
        }
    }

    /**
     * 摘除候选的引用但**不释放图片字节**（改造 B-1 红线路径）。
     *
     * 语义 = [removeCandidate]（本身只置空字段），差别只在**约定**：
     * - [removeCandidate] 的调用方**负责**随后 `release()`（消费 / 覆盖路径，字节已用完）；
     * - 本方法只被 [moveToPaymentProcess] 使用，摘除后字节**必须继续存活**，
     *   由支付过程槽位接管（过期回收 / clear / close 统一释放）。
     *
     * ⚠️ 若在搬移路径误加 `release()`，图会在搬入前被抹零 ⇒ 支付成功时无图可送，
     * 且单测"搬移后字节逐字节等于原始内容"会红 —— 不要删那条断言。
     */
    private fun detachCandidate(state: SlotState, snapshot: CheckoutSnapshot): CheckoutSnapshot? =
        removeCandidate(state, snapshot)
}

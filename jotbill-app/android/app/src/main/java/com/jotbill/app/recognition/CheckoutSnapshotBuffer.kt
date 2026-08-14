package com.jotbill.app.recognition

import android.os.Handler
import android.os.Looper
import java.util.UUID

private const val DEFAULT_SNAPSHOT_TTL_MILLIS = 30_000L
internal const val CHECKOUT_SNAPSHOT_SLOT_PREFIX = "shopping-package:"

/** 结算截图和购买意图必须共用同一购物包槽位命名。 */
internal fun checkoutSnapshotSlotId(shoppingPackage: String): String =
    CHECKOUT_SNAPSHOT_SLOT_PREFIX + shoppingPackage

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
    JOURNEY_ALREADY_TRIGGERED("JOURNEY_ALREADY_TRIGGERED"),
    MANUAL_CANDIDATE_PROTECTED("MANUAL_CANDIDATE_PROTECTED")
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
 * 按槽位保存当前结算页快照的短生命周期缓冲区。
 *
 * 每个槽位最多保留一个手动保护候选和一个最新自动候选；30 秒后自动失效并清零图片。
 * 同一会话、同一指纹的自动候选不能覆盖手动候选；不同指纹的自动候选递增版本并与
 * 手动候选并存，消费前必须明确指纹，否则进入歧义回退。只有 consumeForAi 收到购买
 * 意图时才会产生请求键并消费快照，普通观察、通知重放和 RN 重渲染无法触发上传。
 */
class CheckoutSnapshotBuffer(
    private val snapshotTtlMillis: Long = DEFAULT_SNAPSHOT_TTL_MILLIS,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val snapshotIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val expirationHandler: Handler = Handler(Looper.getMainLooper())
) {
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
    fun store(request: SnapshotCaptureRequest): SnapshotStoreResult {
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
        if (existing?.sessionId == request.sessionId && existing.consumedRequestKey != null) {
            return SnapshotStoreResult.Rejected(SnapshotStoreRejection.JOURNEY_ALREADY_TRIGGERED)
        }

        val state = if (existing == null || existing.sessionId != request.sessionId) {
            existing?.let { releaseCandidates(it) }
            SlotState(request.sessionId).also { slots[request.slotId] = it }
        } else {
            existing
        }

        val sameFingerprint = findCandidate(state, request.pageFingerprint)
        if (sameFingerprint != null) {
            if (
                sameFingerprint.sourceReason == SnapshotSourceReason.MANUAL_CAPTURE &&
                request.sourceReason == SnapshotSourceReason.CHECKOUT_STABLE
            ) {
                return SnapshotStoreResult.Rejected(SnapshotStoreRejection.MANUAL_CANDIDATE_PROTECTED)
            }
            if (sameFingerprint.sourceReason == request.sourceReason) {
                return SnapshotStoreResult.Unchanged(sameFingerprint.copyForConsumer())
            }

            removeCandidate(state, sameFingerprint)?.release()
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
    fun currentCandidates(slotId: String): List<CheckoutSnapshot> {
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
        val state = slots[slotId] ?: return
        if (state.sessionId == sessionId) {
            state.handoffPageFingerprint = pageFingerprint
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

    /** 只读取与页面键对应的引用，避免为支付匹配复制或暴露图片。 */
    @Synchronized
    fun findReference(
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
    fun consumeForAi(
        slotId: String,
        intent: PurchaseIntent,
        pageFingerprint: String? = null
    ): CheckoutAiTriggerResult {
        if (closed) {
            return CheckoutAiTriggerResult.Rejected(SnapshotConsumeRejection.NO_BUFFERED_SNAPSHOT)
        }
        val now = nowMillis()
        purgeExpired(now)
        val state = slots[slotId]
            ?: return CheckoutAiTriggerResult.Rejected(SnapshotConsumeRejection.NO_BUFFERED_SNAPSHOT)

        state.handoffPageFingerprint?.let { retainedFingerprint ->
            if (pageFingerprint != retainedFingerprint) {
                return CheckoutAiTriggerResult.Rejected(
                    if (pageFingerprint == null) {
                        SnapshotConsumeRejection.AMBIGUOUS_CANDIDATES
                    } else {
                        SnapshotConsumeRejection.NO_MATCHING_PAGE_FINGERPRINT
                    }
                )
            }
        }

        state.consumedRequestKey?.let {
            return CheckoutAiTriggerResult.AlreadyTriggered(it)
        }

        val candidates = candidateList(state)
        if (candidates.isEmpty()) {
            if (
                state.expiredPageFingerprints.isNotEmpty() &&
                (pageFingerprint == null ||
                    state.expiredPageFingerprints.contains(pageFingerprint))
            ) {
                state.expiredPageFingerprints.clear()
                slots.remove(slotId)
                return CheckoutAiTriggerResult.Rejected(SnapshotConsumeRejection.SNAPSHOT_EXPIRED)
            }
            return CheckoutAiTriggerResult.Rejected(SnapshotConsumeRejection.NO_BUFFERED_SNAPSHOT)
        }

        val snapshot = if (pageFingerprint != null) {
            findCandidate(state, pageFingerprint)
                ?: if (state.expiredPageFingerprints.contains(pageFingerprint)) {
                    state.expiredPageFingerprints.remove(pageFingerprint)
                    return CheckoutAiTriggerResult.Rejected(SnapshotConsumeRejection.SNAPSHOT_EXPIRED)
                } else {
                    return CheckoutAiTriggerResult.Rejected(
                        SnapshotConsumeRejection.NO_MATCHING_PAGE_FINGERPRINT
                    )
                }
        } else {
            candidates.singleOrNull()
                ?: return CheckoutAiTriggerResult.Rejected(
                    SnapshotConsumeRejection.AMBIGUOUS_CANDIDATES
                )
        }
        if (snapshot.pageRole.canBufferUserRetrySnapshot() && intent != PurchaseIntent.USER_RETRY) {
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
        releaseCandidates(state)
        state.consumedRequestKey = requestKey
        scheduleNextExpirationLocked(now)
        return CheckoutAiTriggerResult.Ready(
            requestKey = requestKey,
            intent = intent,
            snapshot = consumerSnapshot
        )
    }

    /** 页面回到非结算角色或会话取消时调用，确保旧图片不能被后续事件消费。 */
    @Synchronized
    fun discard(slotId: String) {
        slots.remove(slotId)?.let { releaseCandidates(it) }
        scheduleNextExpirationLocked(nowMillis())
    }

    /** 服务中断或销毁时清理所有槽位，确保没有遗漏的图片字节继续留在内存中。 */
    @Synchronized
    fun clear() {
        slots.values.forEach(::releaseCandidates)
        slots.clear()
        expirationHandler.removeCallbacks(expirationRunnable)
    }

    /** 服务销毁时停止主动过期回调并释放所有槽位图片。 */
    @Synchronized
    fun close() {
        if (closed) {
            return
        }
        closed = true
        expirationHandler.removeCallbacks(expirationRunnable)
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
        expirationHandler.removeCallbacks(expirationRunnable)
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
            expirationHandler.postDelayed(
                expirationRunnable,
                (expiresAt - now).coerceAtLeast(0L)
            )
        }
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
}

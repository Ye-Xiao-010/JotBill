package com.jotbill.app.recognition

import java.util.UUID

private const val DEFAULT_SNAPSHOT_TTL_MILLIS = 30_000L

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
    val sourceReason: SnapshotSourceReason,
    internal val imageBytes: ByteArray
) {
    internal fun copyForConsumer(): CheckoutSnapshot = copy(imageBytes = imageBytes.copyOf())

    internal fun release() {
        imageBytes.fill(0)
    }
}

/** 截图工具或手动悬浮按钮向缓冲区提交的输入，不包含任何网络行为。 */
data class SnapshotCaptureRequest(
    val slotId: String,
    val sessionId: String,
    val shoppingPackage: String,
    val pageRole: CheckoutPageRole,
    val pageFingerprint: String,
    val sourceReason: SnapshotSourceReason,
    val imageBytes: ByteArray
)

enum class SnapshotStoreRejection(val wireValue: String) {
    INVALID_ARGUMENT("INVALID_ARGUMENT"),
    NOT_CHECKOUT_PAGE("NOT_CHECKOUT_PAGE"),
    EMPTY_IMAGE("EMPTY_IMAGE"),
    JOURNEY_ALREADY_TRIGGERED("JOURNEY_ALREADY_TRIGGERED")
}

sealed class SnapshotStoreResult {
    data class Stored(
        val snapshot: CheckoutSnapshot,
        val replacedSnapshotId: String?
    ) : SnapshotStoreResult()

    data class Unchanged(val snapshot: CheckoutSnapshot) : SnapshotStoreResult()

    data class Rejected(val reason: SnapshotStoreRejection) : SnapshotStoreResult()
}

enum class SnapshotConsumeRejection(val wireValue: String) {
    NO_BUFFERED_SNAPSHOT("NO_BUFFERED_SNAPSHOT"),
    SNAPSHOT_EXPIRED("SNAPSHOT_EXPIRED")
}

sealed class CheckoutAiTriggerResult {
    data class Ready(
        val requestKey: CheckoutRequestKey,
        val intent: PurchaseIntent,
        val snapshot: CheckoutSnapshot
    ) : CheckoutAiTriggerResult()

    data class AlreadyTriggered(val requestKey: CheckoutRequestKey) : CheckoutAiTriggerResult()

    data class Rejected(val reason: SnapshotConsumeRejection) : CheckoutAiTriggerResult()
}

/**
 * 按槽位保存当前结算页快照的短生命周期缓冲区。
 *
 * 默认每个槽位只保留一张图，30 秒后自动失效。新会话或页面实质变化会覆盖旧图；
 * 同一会话的相同指纹只更新时间窗口，不重复截图。只有 consumeForAi 收到购买意图
 * 时才会产生请求键并消费快照，普通观察、通知重放和 RN 重渲染无法触发上传。
 */
class CheckoutSnapshotBuffer(
    private val snapshotTtlMillis: Long = DEFAULT_SNAPSHOT_TTL_MILLIS,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val snapshotIdFactory: () -> String = { UUID.randomUUID().toString() }
) {
    private data class SlotState(
        val sessionId: String,
        var snapshot: CheckoutSnapshot?,
        var consumedRequestKey: CheckoutRequestKey?
    )

    private val slots = mutableMapOf<String, SlotState>()

    init {
        require(snapshotTtlMillis > 0) { "snapshotTtlMillis 必须大于 0" }
    }

    @Synchronized
    fun store(request: SnapshotCaptureRequest): SnapshotStoreResult {
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
        if (!request.pageRole.canBufferSnapshot()) {
            return SnapshotStoreResult.Rejected(SnapshotStoreRejection.NOT_CHECKOUT_PAGE)
        }
        if (request.imageBytes.isEmpty()) {
            return SnapshotStoreResult.Rejected(SnapshotStoreRejection.EMPTY_IMAGE)
        }

        val existing = slots[request.slotId]
        if (existing?.sessionId == request.sessionId && existing.consumedRequestKey != null) {
            return SnapshotStoreResult.Rejected(SnapshotStoreRejection.JOURNEY_ALREADY_TRIGGERED)
        }

        val current = existing?.snapshot
        if (
            current != null &&
            current.sessionId == request.sessionId &&
            current.pageFingerprint == request.pageFingerprint
        ) {
            return SnapshotStoreResult.Unchanged(current.copyForConsumer())
        }

        current?.release()
        val snapshot = CheckoutSnapshot(
            snapshotId = snapshotIdFactory(),
            sessionId = request.sessionId,
            slotId = request.slotId,
            shoppingPackage = request.shoppingPackage,
            pageRole = request.pageRole,
            pageFingerprint = request.pageFingerprint,
            capturedAt = now,
            expiresAt = now + snapshotTtlMillis,
            sourceReason = request.sourceReason,
            imageBytes = request.imageBytes.copyOf()
        )
        slots[request.slotId] = SlotState(
            sessionId = request.sessionId,
            snapshot = snapshot,
            consumedRequestKey = null
        )
        return SnapshotStoreResult.Stored(
            snapshot = snapshot.copyForConsumer(),
            replacedSnapshotId = current?.snapshotId
        )
    }

    @Synchronized
    fun current(slotId: String): CheckoutSnapshot? {
        val now = nowMillis()
        purgeExpired(now)
        return slots[slotId]?.snapshot?.copyForConsumer()
    }

    /**
     * 消费当前快照并生成唯一请求键。该方法只接受明确的购买意图，消费后立即清空内部图片。
     */
    @Synchronized
    fun consumeForAi(
        slotId: String,
        intent: PurchaseIntent
    ): CheckoutAiTriggerResult {
        val now = nowMillis()
        val state = slots[slotId]
            ?: return CheckoutAiTriggerResult.Rejected(SnapshotConsumeRejection.NO_BUFFERED_SNAPSHOT)

        state.consumedRequestKey?.let {
            return CheckoutAiTriggerResult.AlreadyTriggered(it)
        }

        val snapshot = state.snapshot
            ?: return CheckoutAiTriggerResult.Rejected(SnapshotConsumeRejection.NO_BUFFERED_SNAPSHOT)
        if (now >= snapshot.expiresAt) {
            snapshot.release()
            slots.remove(slotId)
            return CheckoutAiTriggerResult.Rejected(SnapshotConsumeRejection.SNAPSHOT_EXPIRED)
        }

        val requestKey = CheckoutRequestKey.from(
            sessionId = snapshot.sessionId,
            shoppingPackage = snapshot.shoppingPackage,
            pageFingerprint = snapshot.pageFingerprint
        )
        val consumerSnapshot = snapshot.copyForConsumer()
        snapshot.release()
        state.snapshot = null
        state.consumedRequestKey = requestKey
        return CheckoutAiTriggerResult.Ready(
            requestKey = requestKey,
            intent = intent,
            snapshot = consumerSnapshot
        )
    }

    /** 页面回到非结算角色或会话取消时调用，确保旧图片不能被后续事件消费。 */
    @Synchronized
    fun discard(slotId: String) {
        slots.remove(slotId)?.snapshot?.release()
    }

    @Synchronized
    private fun purgeExpired(now: Long) {
        val expiredSlots = slots.filterValues { state ->
            state.snapshot?.expiresAt?.let { it <= now } == true
        }.keys
        expiredSlots.forEach { slotId ->
            slots.remove(slotId)?.snapshot?.release()
        }
    }

}

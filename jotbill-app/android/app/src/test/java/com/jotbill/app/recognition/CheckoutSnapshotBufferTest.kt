package com.jotbill.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CheckoutSnapshotBuffer 特征测试:钉死"当前实际行为"而非理想行为,
 * 作为批 4 journey 重构的回归安全网。每条断言都对应源码中的一个决策分支,
 * 重构后必须逐条保持,有意的行为变化须在本文件中显式改写并注明原因。
 *
 * 环境约定:注入可变时钟 / 可检视调度器 / 捕获日志 sink,不依赖 Android 框架。
 */
class CheckoutSnapshotBufferTest {

    private companion object {
        const val SLOT = "shopping-package:com.test.app"
        const val SESSION = "session-1"
        const val SESSION_2 = "session-2"
        val FP_A = "a".repeat(64)
        val FP_B = "b".repeat(64)
        const val TTL = 120_000L
        const val START_NOW = 1_000_000L
    }

    private class MutableClock(var now: Long = START_NOW)

    private class RecordingScheduler : SnapshotExpirationScheduler {
        val scheduled = mutableListOf<Pair<Runnable, Long>>()
        override fun postDelayed(runnable: Runnable, delayMillis: Long) {
            scheduled.add(runnable to delayMillis)
        }
        override fun cancel(runnable: Runnable) {
            scheduled.removeAll { it.first === runnable }
        }
    }

    private class RecordingLogSink : SnapshotBufferLogSink {
        val lines = mutableListOf<String>()
        override fun i(tag: String, message: String) {
            lines.add("I: $message")
        }
        override fun w(tag: String, message: String) {
            lines.add("W: $message")
        }
    }

    private class Fixture(val ttl: Long = TTL) {
        val clock = MutableClock()
        val scheduler = RecordingScheduler()
        val logSink = RecordingLogSink()
        var snapshotCounter = 0
        val buffer = CheckoutSnapshotBuffer(
            snapshotTtlMillis = ttl,
            nowMillis = { clock.now },
            snapshotIdFactory = { "snap-${++snapshotCounter}" },
            expirationScheduler = scheduler,
            logSink = logSink
        )

        fun advance(by: Long) {
            clock.now += by
        }
    }

    private fun captureRequest(
        slotId: String = SLOT,
        sessionId: String = SESSION,
        fingerprint: String = FP_A,
        sourceReason: SnapshotSourceReason = SnapshotSourceReason.CHECKOUT_STABLE,
        pageRole: CheckoutPageRole = CheckoutPageRole.CHECKOUT,
        sensitive: Boolean = false,
        bytes: ByteArray = ByteArray(16) { 1 },
        target: SnapshotCaptureTarget = SnapshotCaptureTarget.CHECKOUT
    ) = SnapshotCaptureRequest(
        slotId = slotId,
        sessionId = sessionId,
        shoppingPackage = "com.test.app",
        pageRole = pageRole,
        pageFingerprint = fingerprint,
        sourceReason = sourceReason,
        imageBytes = bytes,
        isSensitivePage = sensitive,
        captureTarget = target
    )

    // ---------- 存储与消费主链路 ----------

    @Test
    fun storeThenConsumeReturnsReadyWithRequestKeyAndClearsCandidates() {
        val f = Fixture()
        val stored = f.buffer.store(captureRequest())
        assertTrue(stored is SnapshotStoreResult.Stored)
        stored as SnapshotStoreResult.Stored
        assertEquals(1, stored.candidateRevision)
        assertFalse(stored.requiresDisambiguation)

        val result = f.buffer.consumeForAi(SLOT, PurchaseIntent.SUBMIT_ACTION, FP_A)
        assertTrue(result is CheckoutAiTriggerResult.Ready)
        result as CheckoutAiTriggerResult.Ready
        assertEquals(
            CheckoutRequestKey.from(SESSION, "com.test.app", FP_A),
            result.requestKey
        )
        assertEquals(PurchaseIntent.SUBMIT_ACTION, result.intent)
        assertTrue(result.snapshot.imageBytes.contentEquals(ByteArray(16) { 1 }))
        assertNull(f.buffer.current(SLOT))

        val again = f.buffer.consumeForAi(SLOT, PurchaseIntent.SUBMIT_ACTION, FP_A)
        assertTrue(again is CheckoutAiTriggerResult.AlreadyTriggered)
        assertEquals(result.requestKey, (again as CheckoutAiTriggerResult.AlreadyTriggered).requestKey)
    }

    @Test
    fun storeCopiesRequestBytesAndReturnedSnapshotIsIndependentCopy() {
        val f = Fixture()
        val original = ByteArray(8) { 7 }
        val stored = f.buffer.store(captureRequest(bytes = original)) as SnapshotStoreResult.Stored
        original[0] = 9
        // 请求字节数组在 store 内被复制,外部修改不影响内部候选
        val consumed = f.buffer.consumeForAi(SLOT, PurchaseIntent.SUBMIT_ACTION, FP_A)
            as CheckoutAiTriggerResult.Ready
        assertTrue(consumed.snapshot.imageBytes[0] == 7.toByte())
        stored.snapshot.release()
    }

    @Test
    fun consumeReleasesInternalCandidateSoReturnedCopyIsIndependent() {
        val f = Fixture()
        f.buffer.store(captureRequest())
        val ready = f.buffer.consumeForAi(SLOT, PurchaseIntent.SUBMIT_ACTION, FP_A)
            as CheckoutAiTriggerResult.Ready
        ready.snapshot.release()
        assertTrue(ready.snapshot.imageBytes.all { it == 0.toByte() })
    }

    // ---------- store 门禁 ----------

    @Test
    fun storeRejectsSensitivePageEmptyImageAndBlankFields() {
        val f = Fixture()
        assertEquals(
            SnapshotStoreRejection.SENSITIVE_PAGE,
            (f.buffer.store(captureRequest(sensitive = true)) as SnapshotStoreResult.Rejected).reason
        )
        assertEquals(
            SnapshotStoreRejection.EMPTY_IMAGE,
            (f.buffer.store(captureRequest(bytes = ByteArray(0))) as SnapshotStoreResult.Rejected).reason
        )
        assertEquals(
            SnapshotStoreRejection.INVALID_ARGUMENT,
            (f.buffer.store(captureRequest(fingerprint = "")) as SnapshotStoreResult.Rejected).reason
        )
    }

    @Test
    fun storeRejectsTargetReasonRoleCombinations() {
        val f = Fixture()
        // CHECKOUT 目标只接受 CHECKOUT_STABLE 来源 + 结算页角色
        assertEquals(
            SnapshotStoreRejection.NOT_CHECKOUT_PAGE,
            (f.buffer.store(
                captureRequest(sourceReason = SnapshotSourceReason.MANUAL_CAPTURE)
            ) as SnapshotStoreResult.Rejected).reason
        )
        assertEquals(
            SnapshotStoreRejection.NOT_CHECKOUT_PAGE,
            (f.buffer.store(
                captureRequest(pageRole = CheckoutPageRole.UNKNOWN)
            ) as SnapshotStoreResult.Rejected).reason
        )
        // USER_RETRY 目标只接受 MANUAL_CAPTURE 来源
        assertEquals(
            SnapshotStoreRejection.NOT_CHECKOUT_PAGE,
            (f.buffer.store(
                captureRequest(
                    sourceReason = SnapshotSourceReason.CHECKOUT_STABLE,
                    target = SnapshotCaptureTarget.USER_RETRY
                )
            ) as SnapshotStoreResult.Rejected).reason
        )
    }

    @Test
    fun closedBufferRejectsEverything() {
        val f = Fixture()
        f.buffer.store(captureRequest())
        f.buffer.close()
        assertEquals(
            SnapshotStoreRejection.INVALID_ARGUMENT,
            (f.buffer.store(captureRequest()) as SnapshotStoreResult.Rejected).reason
        )
        assertEquals(
            SnapshotConsumeRejection.NO_BUFFERED_SNAPSHOT,
            (f.buffer.consumeForAi(SLOT, PurchaseIntent.SUBMIT_ACTION, FP_A)
                as CheckoutAiTriggerResult.Rejected).reason
        )
        assertNull(f.buffer.current(SLOT))
    }

    // ---------- 会话与候选语义 ----------

    @Test
    fun newSessionReplacesOldSlotStateAndReleasesOldCandidates() {
        val f = Fixture()
        f.buffer.store(captureRequest(sessionId = SESSION))
        val stored = f.buffer.store(captureRequest(sessionId = SESSION_2))
        assertTrue(stored is SnapshotStoreResult.Stored)
        assertTrue(f.buffer.hasCandidate(SLOT, SESSION_2, FP_A))
        assertFalse(f.buffer.hasCandidate(SLOT, SESSION, FP_A))
    }

    @Test
    fun autoSameFingerprintReturnsUnchangedWithoutNewRevision() {
        val f = Fixture()
        val first = f.buffer.store(captureRequest()) as SnapshotStoreResult.Stored
        val second = f.buffer.store(captureRequest()) as SnapshotStoreResult.Unchanged
        // Unchanged 仅回传已有候选,不携带新版本号(类型上也没有该字段)
        assertEquals(first.snapshot.snapshotId, second.snapshot.snapshotId)
        assertEquals(1, f.buffer.currentCandidates(SLOT).single().candidateRevision)
    }

    @Test
    fun manualCandidateProtectedFromAutoOverwriteOnSameFingerprint() {
        val f = Fixture()
        val manual = f.buffer.store(
            captureRequest(
                sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                target = SnapshotCaptureTarget.USER_RETRY
            )
        )
        assertTrue(manual is SnapshotStoreResult.Stored)
        assertEquals(
            SnapshotStoreRejection.MANUAL_CANDIDATE_PROTECTED,
            (f.buffer.store(captureRequest()) as SnapshotStoreResult.Rejected).reason
        )
    }

    @Test
    fun autoDifferentFingerprintReplacesPreviousAutomaticWithNewRevision() {
        val f = Fixture()
        val first = f.buffer.store(captureRequest(fingerprint = FP_A)) as SnapshotStoreResult.Stored
        val second = f.buffer.store(captureRequest(fingerprint = FP_B)) as SnapshotStoreResult.Stored
        assertEquals(2, second.candidateRevision)
        assertEquals(first.snapshot.snapshotId, second.replacedSnapshotId)
        // 【钉死真实行为】槽内自动候选只有一个:新自动候选无条件替换旧候选,
        // 不因指纹不同而并存(类 KDoc 写的"不同指纹并存"与实现不符,以实现为准)。
        // 自动候选的歧义消费因此只出现在 手动+自动 并存场景。
        val candidates = f.buffer.currentCandidates(SLOT)
        assertEquals(1, candidates.size)
        assertEquals(FP_B, candidates[0].pageFingerprint)
        assertTrue(
            f.buffer.consumeForAi(SLOT, PurchaseIntent.SUBMIT_ACTION)
                is CheckoutAiTriggerResult.Ready
        )
    }

    @Test
    fun manualPlusAutoRequiresDisambiguation() {
        val f = Fixture()
        f.buffer.store(
            captureRequest(
                fingerprint = FP_A,
                sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                target = SnapshotCaptureTarget.USER_RETRY
            )
        )
        val auto = f.buffer.store(captureRequest(fingerprint = FP_B))
        assertTrue(auto is SnapshotStoreResult.Stored)
        assertTrue((auto as SnapshotStoreResult.Stored).requiresDisambiguation)
        assertEquals(2, f.buffer.currentCandidates(SLOT).size)
    }

    @Test
    fun userRetryStoreClearsConsumedTombstoneAndCanConsumeAgain() {
        val f = Fixture()
        f.buffer.store(captureRequest())
        val first = f.buffer.consumeForAi(SLOT, PurchaseIntent.SUBMIT_ACTION, FP_A)
        assertTrue(first is CheckoutAiTriggerResult.Ready)
        // 会话未变时再次自动存储被消费墓碑拦截
        assertEquals(
            SnapshotStoreRejection.JOURNEY_ALREADY_TRIGGERED,
            (f.buffer.store(captureRequest()) as SnapshotStoreResult.Rejected).reason
        )
        // 手动重试代表新的明确消费:解除墓碑并入库
        val retry = f.buffer.store(
            captureRequest(
                sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                target = SnapshotCaptureTarget.USER_RETRY
            )
        )
        assertTrue(retry is SnapshotStoreResult.Stored)
        val second = f.buffer.consumeForAi(SLOT, PurchaseIntent.USER_RETRY, FP_A)
        assertTrue(second is CheckoutAiTriggerResult.Ready)
    }

    // ---------- 消费门禁 ----------

    @Test
    fun nonCheckoutRoleOnlyConsumableByUserRetry() {
        val f = Fixture()
        f.buffer.store(
            captureRequest(
                pageRole = CheckoutPageRole.UNKNOWN,
                sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                target = SnapshotCaptureTarget.USER_RETRY
            )
        )
        assertEquals(
            SnapshotConsumeRejection.NO_MATCHING_PAGE_FINGERPRINT,
            (f.buffer.consumeForAi(SLOT, PurchaseIntent.SUBMIT_ACTION, FP_A)
                as CheckoutAiTriggerResult.Rejected).reason
        )
        assertTrue(
            f.buffer.consumeForAi(SLOT, PurchaseIntent.USER_RETRY, FP_A)
                is CheckoutAiTriggerResult.Ready
        )
    }

    @Test
    fun consumeWithUnknownFingerprintRejectedAndTombstonedFingerprintReportsExpired() {
        val f = Fixture()
        f.buffer.store(captureRequest(fingerprint = FP_A))
        assertEquals(
            SnapshotConsumeRejection.NO_MATCHING_PAGE_FINGERPRINT,
            (f.buffer.consumeForAi(SLOT, PurchaseIntent.SUBMIT_ACTION, FP_B)
                as CheckoutAiTriggerResult.Rejected).reason
        )
    }

    // ---------- TTL 过期 ----------

    @Test
    fun candidatesSurviveUntilTtlBoundary() {
        val f = Fixture()
        f.buffer.store(captureRequest())
        f.advance(TTL - 1)
        assertTrue(
            f.logSink.lines.isNotEmpty() // 候选已写入日志存在
        )
        assertEquals(
            "snap-1",
            (f.buffer.current(SLOT) as CheckoutSnapshot).snapshotId
        )
    }

    @Test
    fun expiredCandidateReportsSnapshotExpiredOnceThenSlotIsGone() {
        val f = Fixture()
        f.buffer.store(captureRequest())
        f.advance(TTL + 1)
        assertNull(f.buffer.current(SLOT))
        val expired = f.buffer.consumeForAi(SLOT, PurchaseIntent.SUBMIT_ACTION, FP_A)
        assertEquals(
            SnapshotConsumeRejection.SNAPSHOT_EXPIRED,
            (expired as CheckoutAiTriggerResult.Rejected).reason
        )
        // 首次过期消费移除整个槽位,第二次是普通无缓冲
        assertEquals(
            SnapshotConsumeRejection.NO_BUFFERED_SNAPSHOT,
            (f.buffer.consumeForAi(SLOT, PurchaseIntent.SUBMIT_ACTION, FP_A)
                as CheckoutAiTriggerResult.Rejected).reason
        )
    }

    // ---------- 支付交接栅栏 ----------

    @Test
    fun handoffFenceCorrectsToCandidateFingerprintAndBlocksMismatchedConsume() {
        val f = Fixture()
        f.buffer.store(captureRequest(fingerprint = FP_A))
        f.buffer.retainForPaymentHandoff(SLOT, SESSION, FP_B)
        assertTrue(f.buffer.hasPaymentHandoffFence(SLOT, SESSION))
        // 调用方传入观察指纹 FP_B,栅栏按候选校正为 FP_A:FP_B 被拒,FP_A 通过
        assertTrue(
            f.logSink.lines.any { it.contains("已按候选校正") }
        )
        assertEquals(
            SnapshotConsumeRejection.NO_MATCHING_PAGE_FINGERPRINT,
            (f.buffer.consumeForAi(SLOT, PurchaseIntent.PAYMENT_APP_HANDOFF, FP_B)
                as CheckoutAiTriggerResult.Rejected).reason
        )
        assertTrue(
            f.buffer.consumeForAi(SLOT, PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
                is CheckoutAiTriggerResult.Ready
        )
        // 消费释放候选时一并清除栅栏
        assertFalse(f.buffer.hasPaymentHandoffFence(SLOT, SESSION))
    }

    @Test
    fun handoffFenceTurnsFingerprintlessConsumeIntoAmbiguous() {
        val f = Fixture()
        f.buffer.store(captureRequest(fingerprint = FP_A))
        f.buffer.retainForPaymentHandoff(SLOT, SESSION, FP_B)
        assertEquals(
            SnapshotConsumeRejection.AMBIGUOUS_CANDIDATES,
            (f.buffer.consumeForAi(SLOT, PurchaseIntent.PAYMENT_APP_HANDOFF)
                as CheckoutAiTriggerResult.Rejected).reason
        )
    }

    @Test
    fun restoreBySessionClearsConsumedTombstoneAllowingFreshStore() {
        val f = Fixture()
        f.buffer.store(captureRequest())
        val consumed = f.buffer.consumeForAi(SLOT, PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
        assertTrue(consumed is CheckoutAiTriggerResult.Ready)
        // 旅程恢复:同会话清除栅栏与消费墓碑(0.0.76 真机:不清墓碑则补截被拒)
        f.buffer.restoreFromPaymentHandoffBySession(SLOT, SESSION)
        assertFalse(f.buffer.hasPaymentHandoffFence(SLOT, SESSION))
        val restored = f.buffer.store(captureRequest(fingerprint = FP_B))
        assertTrue("恢复后同会话补截应被允许", restored is SnapshotStoreResult.Stored)
    }

    @Test
    fun restoreFromPaymentHandoffRequiresExactSessionAndFingerprint() {
        val f = Fixture()
        f.buffer.store(captureRequest(fingerprint = FP_A))
        f.buffer.retainForPaymentHandoff(SLOT, SESSION, FP_A)
        // 指纹不匹配的恢复不解除栅栏
        f.buffer.restoreFromPaymentHandoff(SLOT, SESSION, FP_B)
        assertTrue(f.buffer.hasPaymentHandoffFence(SLOT, SESSION))
        f.buffer.restoreFromPaymentHandoff(SLOT, SESSION_2, FP_A)
        assertTrue(f.buffer.hasPaymentHandoffFence(SLOT, SESSION))
        f.buffer.restoreFromPaymentHandoff(SLOT, SESSION, FP_A)
        assertFalse(f.buffer.hasPaymentHandoffFence(SLOT, SESSION))
    }

    @Test
    fun retainOnMissingSlotOnlyLogsAndSetsNoFence() {
        val f = Fixture()
        f.buffer.retainForPaymentHandoff(SLOT, SESSION, FP_A)
        assertFalse(f.buffer.hasPaymentHandoffFence(SLOT, SESSION))
        assertTrue(
            f.logSink.lines.any { it.contains("交接栅栏设置失败(槽位不存在)") }
        )
    }

    @Test
    fun fenceOnExpiredCandidateAnchorsToStaleCandidateFingerprint() {
        val f = Fixture()
        f.buffer.store(captureRequest(fingerprint = FP_A))
        f.advance(TTL + 1)
        // retain 不做过期清理:候选对象仍在槽状态里,栅栏按其指纹校正(而非透传传入指纹)
        f.buffer.retainForPaymentHandoff(SLOT, SESSION, FP_B)
        assertTrue(f.buffer.hasPaymentHandoffFence(SLOT, SESSION))
        // 消费先做过期清理 → 候选清空 → 栅栏失去保护对象;
        // 过期墓碑属于 FP_A 而请求是 FP_B,故报告无缓冲而非过期
        assertEquals(
            SnapshotConsumeRejection.NO_BUFFERED_SNAPSHOT,
            (f.buffer.consumeForAi(SLOT, PurchaseIntent.PAYMENT_APP_HANDOFF, FP_B)
                as CheckoutAiTriggerResult.Rejected).reason
        )
        // 请求过期墓碑指纹 FP_A 时正确报告过期并移除整个槽位(栅栏随之消失)
        assertEquals(
            SnapshotConsumeRejection.SNAPSHOT_EXPIRED,
            (f.buffer.consumeForAi(SLOT, PurchaseIntent.PAYMENT_APP_HANDOFF, FP_A)
                as CheckoutAiTriggerResult.Rejected).reason
        )
        assertFalse(f.buffer.hasPaymentHandoffFence(SLOT, SESSION))
    }

    // ---------- 引用与生命周期 ----------

    @Test
    fun findReferenceReturnsMetadataWithoutConsuming() {
        val f = Fixture()
        f.buffer.store(captureRequest())
        val reference = f.buffer.findReference(SLOT, FP_A)
        assertTrue(reference != null)
        reference as CheckoutSnapshotReference
        assertEquals(SESSION, reference.sessionId)
        assertEquals(FP_A, reference.pageFingerprint)
        // 读引用不消费:后续仍可正常消费
        assertTrue(
            f.buffer.consumeForAi(SLOT, PurchaseIntent.SUBMIT_ACTION, FP_A)
                is CheckoutAiTriggerResult.Ready
        )
        assertNull(f.buffer.findReference(SLOT, FP_A))
    }

    @Test
    fun realDiscardAlwaysLoggedAndRemovesSlot() {
        val f = Fixture()
        f.buffer.store(captureRequest())
        f.buffer.discard(SLOT, "TEST_REASON")
        assertNull(f.buffer.current(SLOT))
        assertTrue(f.logSink.lines.any { it.contains("槽位丢弃: reason=TEST_REASON") })
    }

    @Test
    fun emptySlotDiscardLogIsSampledPerReason() {
        val f = Fixture()
        f.buffer.discard("no-such-slot", "TEST_REASON")
        val firstCount = f.logSink.lines.count { it.contains("槽位丢弃(空槽位)") }
        assertEquals(1, firstCount)
        // 采样间隔内抑制
        f.buffer.discard("no-such-slot", "TEST_REASON")
        f.buffer.discard("no-such-slot", "TEST_REASON")
        assertEquals(1, f.logSink.lines.count { it.contains("槽位丢弃(空槽位)") })
        // 推进超过采样间隔后输出并汇报抑制次数
        f.advance(5_000L)
        f.buffer.discard("no-such-slot", "TEST_REASON")
        val sampled = f.logSink.lines.filter { it.contains("槽位丢弃(空槽位)") }
        assertEquals(2, sampled.size)
        assertTrue(sampled.last().contains("期间已抑制2次"))
        // 不同 reason 独立采样
        f.buffer.discard("no-such-slot", "OTHER_REASON")
        assertTrue(f.logSink.lines.any { it.contains("reason=OTHER_REASON") })
    }

    @Test
    fun clearReleasesAllSlotsAndResetsLogSampling() {
        val f = Fixture()
        f.buffer.discard("no-such-slot", "TEST_REASON")
        f.buffer.store(captureRequest())
        f.buffer.clear("TEST_CLEAR")
        assertNull(f.buffer.current(SLOT))
        assertTrue(f.logSink.lines.any { it.contains("缓冲清空: reason=TEST_CLEAR") })
        // 清空重置采样:间隔内的下一次空槽位丢弃仍输出
        f.buffer.discard("no-such-slot", "TEST_REASON")
        val sampled = f.logSink.lines.filter { it.contains("槽位丢弃(空槽位)") }
        assertEquals(2, sampled.size)
    }

    @Test
    fun expirationSchedulerIsCancelledAndRescheduledPerStateChange() {
        val f = Fixture()
        f.buffer.store(captureRequest())
        assertEquals(1, f.scheduler.scheduled.size)
        assertEquals(TTL, f.scheduler.scheduled.single().second)
        // 消费后无候选,只剩取消
        f.buffer.consumeForAi(SLOT, PurchaseIntent.SUBMIT_ACTION, FP_A)
        assertTrue(f.scheduler.scheduled.isEmpty())
    }
}

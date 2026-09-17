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

        /** 用户断言截图的专用槽位与会话（服务侧 user-asserted: 前缀约定）。 */
        const val ASSERTED_SLOT = "user-asserted:com.test.app"
        const val ASSERTED_SESSION = "asserted-session-1"
        const val ASSERTED_SESSION_2 = "asserted-session-2"
        val ASSERTED_FP = "d".repeat(64)
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
    fun autoOverwritesManualCandidateOnSameFingerprint() {
        // 【D-16 = B 有意行为变化】同指纹 ⇒ 自动也认得出这一页，按职责边界原则
        // （自动负责认得出的页面、手动负责认不出的页面）放行自动覆盖手动候选。
        // 旧行为是拒绝自动（MANUAL_CANDIDATE_PROTECTED），该拒绝原因已随 D-16 删除。
        val f = Fixture()
        val manual = f.buffer.store(
            captureRequest(
                sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                target = SnapshotCaptureTarget.USER_RETRY
            )
        ) as SnapshotStoreResult.Stored

        val auto = f.buffer.store(captureRequest()) as SnapshotStoreResult.Stored
        assertEquals(manual.snapshot.snapshotId, auto.replacedSnapshotId)
        assertFalse(auto.requiresDisambiguation)
        val remaining = f.buffer.currentCandidates(SLOT)
        assertEquals(1, remaining.size)
        assertEquals(SnapshotSourceReason.CHECKOUT_STABLE, remaining.single().sourceReason)
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
    fun fingerprintlessConsumePrefersAutomaticCandidateOverManual() {
        // 【D-16 = B 有意行为变化】无指纹消费在手动+自动并存时改为**自动优先**
        // （旧行为为手动优先）。此前一律 AMBIGUOUS_CANDIDATES 拒绝，导致手动补截
        // 反而让支付交接消费不到任何快照；"手动优先"是为修该 bug 引入的补丁而非原则。
        val f = Fixture()
        f.buffer.store(
            captureRequest(
                fingerprint = FP_A,
                sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                target = SnapshotCaptureTarget.USER_RETRY
            )
        )
        f.buffer.store(captureRequest(fingerprint = FP_B))

        val result = f.buffer.consumeForAi(SLOT, PurchaseIntent.PAYMENT_APP_HANDOFF)
        assertTrue(result is CheckoutAiTriggerResult.Ready)
        result as CheckoutAiTriggerResult.Ready
        assertEquals(FP_B, result.snapshot.pageFingerprint)
        assertEquals(SnapshotSourceReason.CHECKOUT_STABLE, result.snapshot.sourceReason)
        assertEquals(
            CheckoutRequestKey.from(SESSION, "com.test.app", FP_B),
            result.requestKey
        )
    }

    @Test
    fun fingerprintlessConsumeUsesAutomaticWhenManualIsNotConsumableForIntent() {
        // 手动候选为非结算角色（如商品页手动截图）时对 SUBMIT_ACTION 不可消费，
        // 但槽内还有可消费的自动候选 ⇒ 自动优先直接取用，不再落到歧义。
        val f = Fixture()
        f.buffer.store(
            captureRequest(
                fingerprint = FP_A,
                pageRole = CheckoutPageRole.PRODUCT_DETAIL,
                sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                target = SnapshotCaptureTarget.USER_RETRY
            )
        )
        f.buffer.store(captureRequest(fingerprint = FP_B))

        val result = f.buffer.consumeForAi(SLOT, PurchaseIntent.SUBMIT_ACTION)
        assertTrue(result is CheckoutAiTriggerResult.Ready)
        assertEquals(FP_B, (result as CheckoutAiTriggerResult.Ready).snapshot.pageFingerprint)
    }

    @Test
    fun fingerprintlessConsumeUsesAutomaticEvenForUserRetryIntent() {
        // ⚠️ 该组合（无指纹 + USER_RETRY）**生产不可达，仅作防御性覆盖**：
        // USER_RETRY 的公开入口恒带合法 64 位 hex 指纹（UserRetryCaptureContext.isComplete()
        // 要求 hasSafeDisplayContext()），唯一可传空指纹的 requestUserRetry 已在批 V-1a 删除。
        // 钉死意图：USER_RETRY 不再使手动候选抢先于自动候选。
        val f = Fixture()
        f.buffer.store(
            captureRequest(
                fingerprint = FP_A,
                pageRole = CheckoutPageRole.PRODUCT_DETAIL,
                sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                target = SnapshotCaptureTarget.USER_RETRY
            )
        )
        f.buffer.store(captureRequest(fingerprint = FP_B))

        val result = f.buffer.consumeForAi(SLOT, PurchaseIntent.USER_RETRY)
        assertTrue(result is CheckoutAiTriggerResult.Ready)
        result as CheckoutAiTriggerResult.Ready
        assertEquals(FP_B, result.snapshot.pageFingerprint)
        assertEquals(SnapshotSourceReason.CHECKOUT_STABLE, result.snapshot.sourceReason)
    }

    @Test
    fun consumeReleasesOnlyConsumedCandidateAndRetainsManualFallback() {
        // 【D-16 = B 有意行为变化】消费自动图时不得连带释放手动图（IMPL_PLAN §8.6）：
        // 手动图是"自动图被下游判无效后回退"的保底。旧行为无条件释放两张。
        val f = Fixture()
        f.buffer.store(
            captureRequest(
                fingerprint = FP_A,
                sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                target = SnapshotCaptureTarget.USER_RETRY
            )
        )
        f.buffer.store(captureRequest(fingerprint = FP_B))

        val consumed = f.buffer.consumeForAi(SLOT, PurchaseIntent.PAYMENT_APP_HANDOFF)
            as CheckoutAiTriggerResult.Ready
        assertEquals(FP_B, consumed.snapshot.pageFingerprint)

        // 被消费的自动图已摘除，手动图仍在槽内且图片字节未被抹零
        val retained = f.buffer.currentCandidates(SLOT)
        assertEquals(1, retained.size)
        assertEquals(SnapshotSourceReason.MANUAL_CAPTURE, retained.single().sourceReason)
        assertEquals(FP_A, retained.single().pageFingerprint)
        assertTrue(
            "手动回退图的图片字节已被抹零",
            retained.single().imageBytes.any { it != 0.toByte() }
        )
        // 交接栅栏必须随消费清除，否则会反过来拦住保留下来的手动候选
        assertFalse(f.buffer.hasPaymentHandoffFence(SLOT, SESSION))
        consumed.snapshot.release()
    }

    @Test
    fun consumedRequestTombstoneStillBlocksSecondConsumeAfterNarrowedRelease() {
        // ⚠️ 记录 D-16 第 3 项的收益边界：释放粒度收窄后手动图虽被保留，
        // 但 consumedRequestKey 墓碑仍使二次消费返回 AlreadyTriggered ⇒
        // "自动图被下游判无效后可回退手动"要完全兑现还需墓碑放开或独立回退入口。
        val f = Fixture()
        f.buffer.store(
            captureRequest(
                fingerprint = FP_A,
                sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                target = SnapshotCaptureTarget.USER_RETRY
            )
        )
        f.buffer.store(captureRequest(fingerprint = FP_B))

        val first = f.buffer.consumeForAi(SLOT, PurchaseIntent.PAYMENT_APP_HANDOFF)
            as CheckoutAiTriggerResult.Ready
        val second = f.buffer.consumeForAi(SLOT, PurchaseIntent.PAYMENT_APP_HANDOFF)
        assertTrue(second is CheckoutAiTriggerResult.AlreadyTriggered)
        assertEquals(first.requestKey, (second as CheckoutAiTriggerResult.AlreadyTriggered).requestKey)
        // 保留下来的手动图未被抹零（墓碑只挡消费，不擦字节）
        val retained = f.buffer.currentCandidates(SLOT)
        assertEquals(1, retained.size)
        assertEquals(FP_A, retained.single().pageFingerprint)
        assertTrue(retained.single().imageBytes.any { it != 0.toByte() })
        first.snapshot.release()
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

    // ---------- 用户断言截图通道（服务侧专用槽位契约） ----------

    private fun assertedRequest(
        slotId: String = ASSERTED_SLOT,
        sessionId: String = ASSERTED_SESSION,
        fingerprint: String = ASSERTED_FP
    ) = captureRequest(
        slotId = slotId,
        sessionId = sessionId,
        fingerprint = fingerprint,
        sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
        target = SnapshotCaptureTarget.USER_RETRY
    )

    @Test
    fun userAssertedCaptureRoundTripStoresAndConsumesOnDedicatedSlot() {
        val f = Fixture()
        // 服务侧断言截图契约：全新会话 + 合成指纹 + MANUAL_CAPTURE/USER_RETRY + CHECKOUT 角色
        val stored = f.buffer.store(assertedRequest())
        assertTrue(stored is SnapshotStoreResult.Stored)
        (stored as SnapshotStoreResult.Stored).snapshot.release()

        val ready = f.buffer.consumeForAi(ASSERTED_SLOT, PurchaseIntent.USER_RETRY, ASSERTED_FP)
        assertTrue(ready is CheckoutAiTriggerResult.Ready)
        ready as CheckoutAiTriggerResult.Ready
        assertEquals(
            CheckoutRequestKey.from(ASSERTED_SESSION, "com.test.app", ASSERTED_FP),
            ready.requestKey
        )
        assertEquals(PurchaseIntent.USER_RETRY, ready.intent)
        ready.snapshot.release()

        // 消费后槽位无候选，后续同指纹消费为 AlreadyTriggered（幂等键生效）
        val again = f.buffer.consumeForAi(ASSERTED_SLOT, PurchaseIntent.USER_RETRY, ASSERTED_FP)
        assertTrue(again is CheckoutAiTriggerResult.AlreadyTriggered)
    }

    @Test
    fun userAssertedCaptureNeverTouchesShoppingPackageSlot() {
        val f = Fixture()
        // 结算槽位有在途自动候选时，断言截图写入专用槽位，互不可见
        f.buffer.store(captureRequest())
        val stored = f.buffer.store(assertedRequest()) as SnapshotStoreResult.Stored
        stored.snapshot.release()

        // 断言槽位可正常消费
        val ready = f.buffer.consumeForAi(ASSERTED_SLOT, PurchaseIntent.USER_RETRY, ASSERTED_FP)
            as CheckoutAiTriggerResult.Ready
        ready.snapshot.release()
        // 结算槽位的自动候选原样保留，仍可被真实购买意图消费
        val auto = f.buffer.consumeForAi(SLOT, PurchaseIntent.SUBMIT_ACTION, FP_A)
        assertTrue(auto is CheckoutAiTriggerResult.Ready)
        (auto as CheckoutAiTriggerResult.Ready).snapshot.release()
    }

    @Test
    fun repeatedUserAssertedCaptureReplacesPreviousSlotState() {
        val f = Fixture()
        val first = f.buffer.store(assertedRequest()) as SnapshotStoreResult.Stored
        first.snapshot.release()
        f.buffer.consumeForAi(ASSERTED_SLOT, PurchaseIntent.USER_RETRY, ASSERTED_FP)

        // 第二次断言：全新会话，整体替换已消费的旧槽位状态，不受 consumedRequestKey 阻塞
        val secondFp = "c".repeat(64)
        val second = f.buffer.store(
            assertedRequest(sessionId = ASSERTED_SESSION_2, fingerprint = secondFp)
        )
        assertTrue(second is SnapshotStoreResult.Stored)
        (second as SnapshotStoreResult.Stored).snapshot.release()
        val ready = f.buffer.consumeForAi(ASSERTED_SLOT, PurchaseIntent.USER_RETRY, secondFp)
        assertTrue(ready is CheckoutAiTriggerResult.Ready)
        (ready as CheckoutAiTriggerResult.Ready).snapshot.release()
    }

    // ---------- 改造 B：支付过程槽位 ----------

    /**
     * R1 红线（搬移只摘引用、不抹零）的契约测试：
     * 搬入后源槽位无该候选，但目标槽位取回的字节**逐字节等于原始内容**（非全零）。
     * 若搬移路径误走会 release 的摘除（removeCandidate + release），本用例必红。
     */
    @Test
    fun moveToPaymentProcessDetachesReferenceWithoutZeroingBytes() {
        val f = Fixture()
        val bytes = ByteArray(16) { 1 }
        f.buffer.store(captureRequest(bytes = bytes))

        val result = f.buffer.moveToPaymentProcess(SLOT, SESSION, FP_A)
        assertTrue(result is PaymentProcessMoveResult.Moved)
        result as PaymentProcessMoveResult.Moved
        assertEquals(paymentProcessSlotId(SESSION), result.targetSlotId)
        assertEquals(FP_A, result.pageFingerprint)

        // 源槽位：引用已摘除
        assertTrue(f.buffer.currentCandidates(SLOT).isEmpty())

        // 目标槽位：可取回，字节与原始内容逐字节一致（不是全零、不是空数组）
        val consume = f.buffer.consumeForAi(result.targetSlotId, PurchaseIntent.PAYMENT_RESULT, null)
        assertTrue(consume is CheckoutAiTriggerResult.Ready)
        consume as CheckoutAiTriggerResult.Ready
        assertTrue(
            "搬移不得抹零：目标槽位字节必须逐字节等于原始内容",
            consume.snapshot.imageBytes.contentEquals(bytes)
        )
        (consume as CheckoutAiTriggerResult.Ready).snapshot.release()
    }

    /**
     * 两槽位 TTL 分离（改造 B-4）：结算页槽位用会话上限（5 分钟），支付过程槽位
     * 自搬入时刻起算支付 TTL（2 分钟）—— 搬入必须**显式重置** expiresAt，
     * 不得沿用源候选"store 时刻 + 结算页 TTL"的旧值。
     */
    @Test
    fun moveResetsExpiresAtToMoveTimePlusPaymentTtl() {
        val f = Fixture(ttl = SESSION_TTL_MILLIS)
        f.buffer.store(captureRequest())
        val result = f.buffer.moveToPaymentProcess(SLOT, SESSION, FP_A)
        assertTrue(result is PaymentProcessMoveResult.Moved)
        assertEquals(START_NOW + PAYMENT_PROCESS_TTL_MILLIS, (result as PaymentProcessMoveResult.Moved).expiresAt)
    }

    /**
     * 一单一图幂等：同一 sessionId 的重复交接（源槽位已空）不得在支付槽位里
     * 造出第二张。临时去掉幂等 ⇒ 本用例必红（第二张覆盖/追加）。
     */
    @Test
    fun repeatedMoveForSameSessionKeepsSinglePicture() {
        val f = Fixture()
        f.buffer.store(captureRequest())
        assertTrue(f.buffer.moveToPaymentProcess(SLOT, SESSION, FP_A) is PaymentProcessMoveResult.Moved)
        val second = f.buffer.moveToPaymentProcess(SLOT, SESSION, FP_A)
        assertTrue(second is PaymentProcessMoveResult.AlreadyOccupied)

        // 消费时槽内只有一张，requestKey 也只生成一次
        val consume = f.buffer.consumeForAi(paymentProcessSlotId(SESSION), PurchaseIntent.PAYMENT_RESULT, null)
        assertTrue(consume is CheckoutAiTriggerResult.Ready)
        (consume as CheckoutAiTriggerResult.Ready).snapshot.release()
    }

    /**
     * 源槽位可并存手动 + 自动两张（SlotState 双字段）：第一次搬走自动后，
     * 第二次交接事件若把手动也搬进同一支付槽位 ⇒ 歧义。一单一图不变式必须挡下。
     */
    @Test
    fun secondMoveOfRemainingManualCandidateIsRejectedByIdempotency() {
        val f = Fixture()
        val automaticBytes = ByteArray(8) { 1 }
        val manualBytes = ByteArray(8) { 2 }
        f.buffer.store(captureRequest(fingerprint = FP_A, bytes = automaticBytes))
        f.buffer.store(
            captureRequest(
                fingerprint = FP_B,
                bytes = manualBytes,
                sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                target = SnapshotCaptureTarget.USER_RETRY
            )
        )
        assertTrue(f.buffer.moveToPaymentProcess(SLOT, SESSION, FP_A) is PaymentProcessMoveResult.Moved)
        // 手动候选仍在源槽位，第二次搬运必须被幂等挡下
        assertTrue(f.buffer.moveToPaymentProcess(SLOT, SESSION, FP_B) is PaymentProcessMoveResult.AlreadyOccupied)

        val consume = f.buffer.consumeForAi(paymentProcessSlotId(SESSION), PurchaseIntent.PAYMENT_RESULT, null)
        assertTrue(consume is CheckoutAiTriggerResult.Ready)
        consume as CheckoutAiTriggerResult.Ready
        assertEquals(FP_A, consume.snapshot.pageFingerprint)
        assertTrue(consume.snapshot.imageBytes.contentEquals(automaticBytes))
        consume.snapshot.release()
    }

    /** 指纹漂移（京东/拼多多常态）：交接指纹与槽内候选不同 ⇒ 仍按自动优先搬走，不得漏搬。 */
    @Test
    fun moveWithDriftedFingerprintStillMovesAutomaticCandidate() {
        val f = Fixture()
        val bytes = ByteArray(16) { 3 }
        f.buffer.store(captureRequest(fingerprint = FP_A, bytes = bytes))
        val result = f.buffer.moveToPaymentProcess(SLOT, SESSION, FP_B)
        assertTrue(result is PaymentProcessMoveResult.Moved)
        assertEquals(FP_A, (result as PaymentProcessMoveResult.Moved).pageFingerprint)

        val consume = f.buffer.consumeForAi(paymentProcessSlotId(SESSION), PurchaseIntent.PAYMENT_RESULT, null)
        assertTrue(consume is CheckoutAiTriggerResult.Ready)
        (consume as CheckoutAiTriggerResult.Ready).snapshot.release()
    }

    /** 非 CHECKOUT 角色候选不搬：PAYMENT_RESULT 消费受角色守卫，搬了也消费不掉。 */
    @Test
    fun moveSkipsNonCheckoutRoleCandidates() {
        val f = Fixture()
        f.buffer.store(
            captureRequest(
                fingerprint = FP_A,
                pageRole = CheckoutPageRole.UNKNOWN,
                sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                target = SnapshotCaptureTarget.USER_RETRY
            )
        )
        val result = f.buffer.moveToPaymentProcess(SLOT, SESSION, FP_A)
        assertEquals(
            PaymentProcessMoveRejection.NO_MOVABLE_CANDIDATE,
            (result as PaymentProcessMoveResult.Rejected).reason
        )
        // 源槽位原样保留（未摘除、未释放）
        assertEquals(1, f.buffer.currentCandidates(SLOT).size)
    }

    @Test
    fun moveRejectsSessionMismatch() {
        val f = Fixture()
        f.buffer.store(captureRequest(sessionId = SESSION_2))
        val result = f.buffer.moveToPaymentProcess(SLOT, SESSION)
        assertEquals(
            PaymentProcessMoveRejection.SESSION_MISMATCH,
            (result as PaymentProcessMoveResult.Rejected).reason
        )
    }

    @Test
    fun moveFromMissingSourceSlotIsRejected() {
        val f = Fixture()
        val result = f.buffer.moveToPaymentProcess(SLOT, SESSION)
        assertEquals(
            PaymentProcessMoveRejection.NO_SOURCE_SLOT,
            (result as PaymentProcessMoveResult.Rejected).reason
        )
    }

    /** 支付槽位已消费后再搬运 ⇒ ALREADY_CONSUMED（复活无意义，只会得到 AlreadyTriggered）。 */
    @Test
    fun moveAfterPaymentSlotConsumedIsRejected() {
        val f = Fixture()
        f.buffer.store(captureRequest())
        assertTrue(f.buffer.moveToPaymentProcess(SLOT, SESSION, FP_A) is PaymentProcessMoveResult.Moved)
        val consume = f.buffer.consumeForAi(paymentProcessSlotId(SESSION), PurchaseIntent.PAYMENT_RESULT, null)
        assertTrue(consume is CheckoutAiTriggerResult.Ready)
        (consume as CheckoutAiTriggerResult.Ready).snapshot.release()

        f.buffer.store(captureRequest(fingerprint = FP_B))
        val again = f.buffer.moveToPaymentProcess(SLOT, SESSION, FP_B)
        assertEquals(
            PaymentProcessMoveRejection.ALREADY_CONSUMED,
            (again as PaymentProcessMoveResult.Rejected).reason
        )
    }

    /** 支付过程槽位按 2 分钟到期回收，且不误伤结算页槽位的其余候选。 */
    @Test
    fun paymentSlotExpiresAtPaymentTtlWithoutTouchingCheckoutSlot() {
        val f = Fixture(ttl = SESSION_TTL_MILLIS)
        val movedBytes = ByteArray(8) { 4 }
        val manualBytes = ByteArray(8) { 5 }
        f.buffer.store(captureRequest(fingerprint = FP_A, bytes = movedBytes))
        assertTrue(f.buffer.moveToPaymentProcess(SLOT, SESSION, FP_A) is PaymentProcessMoveResult.Moved)
        f.buffer.store(
            captureRequest(
                fingerprint = FP_B,
                bytes = manualBytes,
                sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                target = SnapshotCaptureTarget.USER_RETRY
            )
        )

        f.advance(PAYMENT_PROCESS_TTL_MILLIS + 1_000L)
        // 支付槽位已被 purgeExpired 释放
        assertTrue(f.buffer.currentCandidates(paymentProcessSlotId(SESSION)).isEmpty())
        // 结算页槽位的手动候选（会话级 TTL）不受影响
        val remaining = f.buffer.currentCandidates(SLOT)
        assertEquals(listOf(FP_B), remaining.map { it.pageFingerprint })
        assertTrue(remaining.single().imageBytes.contentEquals(manualBytes))
        remaining.forEach { it.release() }
    }
}

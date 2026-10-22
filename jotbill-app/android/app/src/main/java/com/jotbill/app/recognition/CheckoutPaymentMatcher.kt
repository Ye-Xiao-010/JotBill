package com.jotbill.app.recognition

import androidx.room.withTransaction
import com.jotbill.app.pending.PendingChangeHub
import com.jotbill.app.pending.PendingChangeInfo
import com.jotbill.app.pending.RecentPaymentCooldown
import com.jotbill.app.data.CreateBillOutcome
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.LedgerRepository
import com.jotbill.app.data.entity.AuditEntryEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.CheckoutSessionEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import com.jotbill.app.data.entity.TransactionCandidateEntity
import com.jotbill.app.data.entity.PaymentEvidenceEntity
import com.jotbill.app.rules.DuplicateGuard
import com.jotbill.app.rules.RuleEngine
import com.jotbill.app.utils.JotBillLog
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.abs
import org.json.JSONObject

private const val PAYMENT_MATCH_WINDOW_MILLIS = 5 * 60 * 1000L
internal const val CHECKOUT_PAYMENT_MATCH_WINDOW_MILLIS = PAYMENT_MATCH_WINDOW_MILLIS

/**
 * D-19 金额容差绝对下限（分，即 ¥1.00）：保证小额单有基本余量（运费、包装等固定型差异）。
 *
 * ⚠️ 容差**只在本文件定义一次**：`evaluate()` 的判定与会话解析的 DAO 检索带
 * （`TransactionCandidateDao.findUnoccupiedByAmountCovering`）共用
 * [paymentAmountToleranceMinor]，后者以**绑定参数**接收计算结果。
 * **不得在 SQL 内联同一公式**——两处实现必导致日后调参漏改一处（本项目已三次踩同类问题）。
 */
private const val AMOUNT_TOLERANCE_MIN_MINOR = 100L

/** D-19 金额容差比例项（%）：自适应大额单（优惠、手续费等比例型差异）。 */
private const val AMOUNT_TOLERANCE_PERCENT = 5L

/**
 * D-19 双向有界容差（分）：`max(¥1.00, 支付金额 × 5%)`，两者取大。
 *
 * 小额时下限兜底、大额时比例放宽。以**支付金额**为基准——支付金额是事实，
 * AI 读的是页面。可调常量，待真机数据校准（与 D-4 槽位 TTL、D-7 窗口同一处理方式）。
 */
internal fun paymentAmountToleranceMinor(paymentAmountMinor: Long): Long = maxOf(
    AMOUNT_TOLERANCE_MIN_MINOR,
    paymentAmountMinor * AMOUNT_TOLERANCE_PERCENT / 100
)

/** 候选金额相对支付金额的容差归属（D-19）。 */
internal enum class PaymentAmountMatch {
    /** 落在容差带内：视为同一笔，以支付金额入账。 */
    WITHIN_TOLERANCE,

    /** AI 金额低于支付金额且超出容差带（常见成因：少读运费/优惠前金额）。 */
    AI_LOWER,

    /** AI 金额高于支付金额且超出容差带（误读放大，如 100 读成 1000）。 */
    AI_HIGHER
}

/**
 * 判定候选金额落在容差带的哪一侧（D-19 三态）。
 *
 * 带内不区分方向；带外**保留方向**，供冲突原因与用户文案区分"实付更高"与
 * "AI 读高"——后者是本次新增的拦截方向，此前一律放行会静默入账。
 */
internal fun classifyPaymentAmount(
    candidateAmountMinor: Long,
    paymentAmountMinor: Long
): PaymentAmountMatch {
    val delta = candidateAmountMinor - paymentAmountMinor
    return when {
        abs(delta) <= paymentAmountToleranceMinor(paymentAmountMinor) ->
            PaymentAmountMatch.WITHIN_TOLERANCE
        delta < 0L -> PaymentAmountMatch.AI_LOWER
        else -> PaymentAmountMatch.AI_HIGHER
    }
}

/**
 * 同会话跨来源证据收敛窗口。
 *
 * ⚠️ 刻意**宽于**结果页事件去重桶（D-6 已把事件桶收窄为 30 秒）：事件键跨桶后不再命中，
 * 同一结果页的跨桶重复读（同包同金额，读取间隔 > 30 秒）靠本窗口收敛为一条证据。
 * ⇒ **不得随事件桶一并收窄**，否则跨桶重复读会落成两条证据（D-6 裁定：本常量值不动）。
 */
private const val PAYMENT_EVIDENCE_MERGE_WINDOW_MILLIS = 2 * 60 * 1000L

/**
 * 孤儿待确认去重窗口（D-25，2026-09-29 定案 / 2026-09-30 修订）。
 *
 * ⚠️ **为什么必须存在**：`sourceEventKey` 幂等键挡不住重复观测 —— 结果页事件键含
 * 30 秒时间桶（`PaymentResultParser`，D-6 为防"同额两单撞键"而定的窄桶），
 * 跨桶即换键。`PAYMENT_EVIDENCE_MERGE_WINDOW_MILLIS` 的 2 分钟收敛只作用于**证据行**，
 * 收敛不到本函数独立新建的**孤儿卡**。
 *
 * ⚠️ **2026-09-30 由 60 秒调至 90 秒**（真机实证，定案值当天的实测就漏了）：
 * 真机（小米 25128PNA1C / 拼多多 build 0.0.98）同一笔 ¥2.68 的结果页在
 * 10:05:45 与 10:06:46 被各观测一次，`textLength` 均为 20（同一页面），
 * `occurredAt=1790733945898` 与 `1790734006291` 相差 **60393 ms** ——
 * 比原 60 秒窗多 **393 毫秒**，两条都建了卡。
 * 且 `CheckoutAccessibilityService.PAYMENT_RESULT_EVENT_MEMORY_MILLIS`（第一道防线）
 * 同为 60 秒，**三道判据的阈值全部挤在 30~60 秒窄带内**，于是在同一刻集体失效。
 *
 * 90 秒的依据：覆盖实测的 60.393 秒；**仍远小于 D-6 关心的 2 分钟**
 * （"同包同金额、2 分钟内两单不得撞键"），不把那个风险重新引进来。
 * ⚠️ 须与 `PAYMENT_RESULT_EVENT_MEMORY_MILLIS` **保持同值** —— 阈值不一致时，
 * 先失效的那道防线形同虚设。
 *
 * ⚠️ **这不是根治**：本判据是纯时间维度，必然随用户停留时长失效
 * （用户在结果页停留超 90 秒仍会重复）。根治方向是把"同一笔"锚到**结算上下文**
 * 而非时间，但孤儿路径拿不到 `sessionId`（0 命中时实参即 `null`/`emptyList`，
 * 且 `orphanSink` 是非 suspend 的 fire-and-forget）。已记为待办。
 */
private const val ORPHAN_DEDUP_WINDOW_MILLIS = 90_000L

private const val PAYMENT_CONFIRMED_STATUS = "PAYMENT_CONFIRMED"
private const val AI_PENDING_STATUS = "AI_PENDING"
private const val WAITING_PAYMENT_STATUS = "WAITING_PAYMENT"
private const val WAITING_ENRICHMENT_STATUS = "WAITING_ENRICHMENT"
private const val NEEDS_CONFIRMATION_STATUS = "NEEDS_CONFIRMATION"
private const val AUTO_COMMITTED_STATUS = "AUTO_COMMITTED"
private const val COMPLETED_STATUS = "COMPLETED"
private const val AUTO_COMMIT_OPERATOR = "auto_commit"
private const val AI_RECOGNITION_FAILED_REASON = "AI_RECOGNITION_FAILED"
private const val EVIDENCE_ONLY_DECISION = "EVIDENCE_ONLY"

/** 用户断言截图（手动点击强制记账）的冲突原因，供前端提示用户核对。 */
internal const val USER_ASSERTED_REASON = "USER_ASSERTED"

/** 用户断言截图但 AI 未识别到金额时的冲突原因；落 0 金额占位由用户补填。 */
internal const val USER_ASSERTED_AMOUNT_MISSING_REASON = "USER_ASSERTED_AMOUNT_MISSING"

/**
 * 启动期兜底落卡的冲突原因（批 V-5，`IMPL_PLAN` §2.4 D-3 兜底 2）。
 *
 * 语义：会话已有支付成功证据，但 AI 定论**永远不会到来**（进程被杀后在飞请求随作用域
 * 消失），启动期扫描必须把该笔落待确认，避免"证据在、用户看不到"的静默窗口。
 */
internal const val AI_RESULT_MISSING_ON_STARTUP = "AI_RESULT_MISSING_ON_STARTUP"

/**
 * 仅由截图 AI 视觉推断出的支付结果状态。
 *
 * 与 "SUCCESS" 的区别：截图 AI 只看到一张图，无法证明支付真的完成
 *（历史账单详情、聊天转发凭证、未提交的收银台都可能被读出金额）。
 * 该状态的证据仍参与匹配与待确认展示，但一律不得触发自动入账。
 */
internal const val PAYMENT_STATUS_AI_UNVERIFIED = "SUCCESS_AI_UNVERIFIED"

/** 截图 AI 未经证实的支付结果转待确认时的冲突原因，供前端提示用户核对。 */
internal const val AI_UNVERIFIED_PAYMENT_REASON = "AI_UNVERIFIED_PAYMENT"

/**
 * 该证据是否代表"支付已发生"。
 *
 * 包含已证实的 SUCCESS 与截图 AI 推断的 [PAYMENT_STATUS_AI_UNVERIFIED]：
 * 两者都应参与候选关联与待确认展示，差异只体现在能否自动入账。
 */
internal fun PaymentEvidenceEntity.indicatesPaidOutcome(): Boolean =
    paymentStatus == "SUCCESS" || paymentStatus == PAYMENT_STATUS_AI_UNVERIFIED

/**
 * 在原有冲突判定之上叠加"截图 AI 未证实"守卫。
 *
 * 截图 AI 只凭一张图推断支付完成，可能把历史账单、聊天凭证或未提交的
 * 收银台读成本次支付。即使金额/订单号/时间三项校验全部通过，也不允许
 * 自动写入账本，必须转待确认由用户确认。
 *
 * @param conflictReason 原有评估得出的冲突原因，非空时直接沿用。
 * @param paymentStatus 参与本次匹配的支付证据状态。
 * @return 应生效的冲突原因；null 表示允许自动入账。
 */
internal fun resolveConflictWithAiUnverifiedGuard(
    conflictReason: String?,
    paymentStatus: String?
): String? = when {
    conflictReason != null -> conflictReason
    paymentStatus == PAYMENT_STATUS_AI_UNVERIFIED -> AI_UNVERIFIED_PAYMENT_REASON
    else -> null
}
/**
 * 购物应用包名到平台展示名的权威映射。
 *
 * 包名由系统无障碍事件提供，可靠性远高于 AI 视觉识别：真机上 AI 曾把
 * platform 读成"手机应用"这类无意义文字，导致账单来源无法体现购物平台。
 * 因此 platform 一律由该映射推导，不采用 AI 返回值。
 *
 * @param shoppingPackage 结算会话记录的购物应用包名。
 * @return 平台展示名；未收录的包名返回 null。
 */
internal fun shoppingPlatformName(shoppingPackage: String?): String? =
    when (shoppingPackage?.trim()) {
        "com.xunmeng.pinduoduo" -> "拼多多"
        "com.taobao.taobao" -> "淘宝"
        "com.tmall.wireless" -> "天猫"
        "com.jingdong.app.mall" -> "京东"
        "com.sankuai.meituan" -> "美团"
        "com.sdu.didi.psnger" -> "滴滴"
        "com.ss.android.ugc.aweme" -> "抖音"
        "com.xingin.xhs" -> "小红书"
        else -> null
    }

private val TERMINAL_SESSION_STATUSES = setOf(
    "COMPLETED",
    "CANCELLED",
    "FAILED",
    "EXPIRED"
)

/** 与 DefaultDataInitializer 种子账户 ID 保持一致的支付平台资金账户映射。 */
private const val WECHAT_PAY_ACCOUNT_ID = "default-account-wechat"
private const val ALIPAY_ACCOUNT_ID = "default-account-alipay"

internal sealed class CheckoutPaymentMatchResult {
    data class Accepted(
        val sessionId: String,
        val status: String,
        val candidateId: String? = null,
        val evidenceId: String? = null,
        val matched: Boolean = false,
        val billId: String? = null
    ) : CheckoutPaymentMatchResult()

    data class Rejected(
        val sessionId: String?,
        val reason: String
    ) : CheckoutPaymentMatchResult()
}

/**
 * 启动期证据兜底扫描的产出计数（批 V-5，`IMPL_PLAN` §2.4 D-3 兜底 2）。
 *
 * 两个计数分开上报，是为了让"补齐候选"与"兜底落卡"各自可量化 ——
 * 后者对应"AI 定论不可得"的真实发生率（进程被杀 / 派发失败未补），是需要被观测的指标。
 */
internal data class EvidenceBackfillOutcome(
    /** 为"无候选"的历史会话补出的 EVIDENCE_ONLY 占位候选数。 */
    val candidatesBackfilled: Int,
    /** 因"会话仍在等 AI 定论、但定论不会到来"而落待确认（或确认已有卡）的会话数。 */
    val pendingsPromoted: Int
)

/**
 * 阶段四 T4：支付证据归属会话的解析决策。
 *
 * 并发会话无法区分时输出 Ambiguous，不强行合并；
 * 由待确认中心（T6）创建并发候选，由用户选择归属。
 */
internal sealed class PaymentSessionResolution {
    /** 订单号或金额+时间窗口唯一匹配的会话。byOrderId 表示是否通过订单号直接命中。 */
    data class Matched(
        val reference: CheckoutSnapshotReference,
        val byOrderId: Boolean
    ) : PaymentSessionResolution()

    /** 多个并发会话无法区分，进入待确认由用户选择。 */
    data class Ambiguous(val sessionIds: List<String>) : PaymentSessionResolution()

    /** 无匹配会话。 */
    object NoMatch : PaymentSessionResolution()
}

/**
 * 将支付证据和视觉候选限制在同一个 sessionId 内关联，并在匹配成功时自动入账。
 *
 * 阶段四 T5 起：matched 且 conflictReason == null 的候选通过 [LedgerRepository]
 * 在同一事务内写 Bill、联动更新账户余额、把候选标记为已入账，会话进入
 * AUTO_COMMITTED（公开 SessionStatus 为 COMPLETED）；冲突候选仍只写会话、
 * 证据和候选，由 T6 待确认中心处理，不静默入账。
 */
internal class CheckoutPaymentMatcher(
    private val database: JotBillDatabase,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val ledger = LedgerRepository(database)
    private val ruleEngine = RuleEngine(database)

    /** AI 请求开始前先建立会话，支付通知可以在 AI 返回前安全落到同一 sessionId。 */
    suspend fun ensureSession(
        reference: CheckoutSnapshotReference,
        requestKey: CheckoutRequestKey,
        aiRequestId: String? = null
    ): CheckoutSessionEntity = database.withTransaction {
        upsertSession(
            reference = reference,
            initialStatus = "AI_PENDING",
            requestKey = requestKey,
            aiRequestId = aiRequestId,
            now = nowMillis()
        )
    }

    /** AI 已消费内存快照后，从未过期的唯一会话恢复原始 sessionId。 */
    suspend fun findReferenceForPayment(
        shoppingPackage: String,
        pageFingerprint: String,
        at: Long = nowMillis()
    ): CheckoutSnapshotReference? {
        val sessions = database.checkoutSessionDao().findActiveByPage(
            shoppingPackage = shoppingPackage,
            pageFingerprint = pageFingerprint,
            at = at
        )
        val session = sessions.singleOrNull() ?: return null
        // 批 V-4 遗留修正：判据与 CheckoutPurchaseIntentCoordinator:765 /
        // CheckoutSnapshotBuffer:798 / CheckoutPageDetector:145 同源，统一用
        // AUTO_DISPATCHABLE_PAGE_ROLES。此处此前硬判 == CHECKOUT，而该集合含
        // PRODUCT_DETAIL / CART ⇒ 详情页直达支付时会话角色为 PRODUCT_DETAIL，
        // 证据落库路径判 null，"宽进真生效"被打折（同批改造已在其余三处放宽）。
        val pageRole = CheckoutPageRole.fromStorageValue(session.pageRole)
        if (pageRole !in AUTO_DISPATCHABLE_PAGE_ROLES) {
            return null
        }
        return CheckoutSnapshotReference(
            snapshotId = session.snapshotId.orEmpty(),
            sessionId = session.id,
            slotId = checkoutSnapshotSlotId(shoppingPackage),
            shoppingPackage = shoppingPackage,
            pageRole = pageRole,
            pageFingerprint = pageFingerprint,
            capturedAt = session.createdAt,
            expiresAt = session.expiresAt,
            candidateRevision = 0,
            sourceReason = SnapshotSourceReason.CHECKOUT_STABLE
        )
    }

    // D-24 / R4（2026-09-29 实施）：原 findRecentCheckoutReferenceForPayment
    // （"最近活跃结算会话"兜底）已随三处调用点一并摘除并删除。
    // 理由：归属判定 0 命中时退到"最近活跃会话"＝**猜测**，可能把证据配到错误的会话；
    // 映射化把同一情形变为"可检测的歧义"⇒ 落孤儿待确认（安全侧），严格更安全。
    // 原有 KDoc（供考古）："AI 已派发、ensureSession 已落库（AI_PENDING），但内存交接引用与
    // buffer 快照都已失效（如收银台停留超过截图 TTL），支付证据晚到时仍应关联该会话生成
    // EVIDENCE_ONLY 候选，而不是落 NO_ACTIVE_SESSION 孤儿待确认。"

    /**
     * 阶段四 T4：按订单号优先、金额+时间窗口+会话未占用综合解析支付证据应归属的会话。
     *
     * 匹配规则（文档 3.5 节）：
     * - 订单号一致时优先级最高，直接匹配；
     * - 没有订单号时按金额+时间窗口+会话未被占用综合匹配；
     * - 仅凭"同金额 + 五分钟内"不能自动合并两笔连续支付；
     * - 并发会话无法区分时输出 Ambiguous，不强行合并，由待确认中心让用户选择。
     */
    suspend fun resolveSessionForPayment(
        paymentPackage: String,
        amountMinor: Long,
        occurredAt: Long,
        orderId: String? = null,
        at: Long = nowMillis()
    ): PaymentSessionResolution {
        if (paymentPackage !in DEFAULT_SUPPORTED_PAYMENT_PACKAGES || amountMinor <= 0L) {
            return PaymentSessionResolution.NoMatch
        }

        // 第一优先级：订单号一致直接匹配
        if (!orderId.isNullOrBlank()) {
            val byOrderId = database.transactionCandidateDao()
                .findActiveByOrderId(orderId, at)
            val sessionIds = byOrderId.map { it.sessionId }.distinct()
            return when {
                sessionIds.size == 1 -> {
                    val session = database.checkoutSessionDao().getById(sessionIds.single())
                    if (session != null && !session.isClosedOrExpired(at)) {
                        PaymentSessionResolution.Matched(
                            reference = referenceForSession(session),
                            byOrderId = true
                        )
                    } else {
                        PaymentSessionResolution.NoMatch
                    }
                }
                sessionIds.size > 1 -> PaymentSessionResolution.Ambiguous(sessionIds)
                else -> PaymentSessionResolution.NoMatch
            }
        }

        // 第二优先级：金额覆盖 + 时间窗口 + 会话未被占用
        // D-19：检索带与 evaluate() 共用同一容差公式（双向带）。**必须同改**——
        // 只改 evaluate() 时，AI 金额更低的候选会在会话解析阶段就被 SQL 滤掉，
        // 证据落孤儿待确认、根本走不到 evaluate()，新容差在下行方向完全失效。
        val candidates = database.transactionCandidateDao().findUnoccupiedByAmountCovering(
            amountMinor = amountMinor,
            toleranceMinor = paymentAmountToleranceMinor(amountMinor),
            at = at
        )
        val withinWindow = candidates.filter { candidate ->
            val candidateTime = candidate.aiOccurredAt
                ?: database.checkoutSessionDao().getById(candidate.sessionId)?.createdAt
            candidateTime != null &&
                abs(occurredAt - candidateTime) <= PAYMENT_MATCH_WINDOW_MILLIS
        }
        val sessionIds = withinWindow.map { it.sessionId }.distinct()
        return when {
            sessionIds.size == 1 -> {
                val session = database.checkoutSessionDao().getById(sessionIds.single())
                if (session != null && !session.isClosedOrExpired(at)) {
                    PaymentSessionResolution.Matched(
                        reference = referenceForSession(session),
                        byOrderId = false
                    )
                } else {
                    PaymentSessionResolution.NoMatch
                }
            }
            sessionIds.size > 1 -> PaymentSessionResolution.Ambiguous(sessionIds)
            else -> {
                // 第三优先级：结算页截图 AI 失败后支付证据晚到。若时间窗口内只有
                // 一个尚未被证据补齐的 AI_FAILED 占位候选，则回填到原会话，
                // 而不是生成一条无法关联结算页的纯通知待确认。
                val failedCandidates = database.transactionCandidateDao()
                    .findAiFailedUnmatchedCandidates(
                        since = occurredAt - PAYMENT_MATCH_WINDOW_MILLIS,
                        until = occurredAt + PAYMENT_MATCH_WINDOW_MILLIS
                    )
                val failedSessionIds = failedCandidates.map { it.sessionId }.distinct()
                when {
                    failedSessionIds.size == 1 -> {
                        val session = database.checkoutSessionDao()
                            .getById(failedSessionIds.single())
                        if (session != null && session.expiresAt > at) {
                            // AI 失败占位来自普通页手动截图（pageRole 常为 UNKNOWN），
                            // 支付证据晚到时把引用角色提升为 CHECKOUT，允许通过支付证据校验。
                            PaymentSessionResolution.Matched(
                                reference = referenceForSession(session).copy(
                                    pageRole = CheckoutPageRole.CHECKOUT
                                ),
                                byOrderId = false
                            )
                        } else {
                            PaymentSessionResolution.NoMatch
                        }
                    }
                    failedSessionIds.size > 1 -> PaymentSessionResolution.Ambiguous(failedSessionIds)
                    else -> PaymentSessionResolution.NoMatch
                }
            }
        }
    }

    /** 从会话实体构建快照引用，供证据落库复用。 */
    private fun referenceForSession(session: CheckoutSessionEntity): CheckoutSnapshotReference {
        val shoppingPackage = session.shoppingPackage.orEmpty()
        val pageRole = CheckoutPageRole.fromStorageValue(session.pageRole)
        return CheckoutSnapshotReference(
            snapshotId = session.snapshotId.orEmpty(),
            sessionId = session.id,
            slotId = checkoutSnapshotSlotId(shoppingPackage),
            shoppingPackage = shoppingPackage,
            pageRole = pageRole,
            pageFingerprint = session.pageFingerprint.orEmpty(),
            capturedAt = session.createdAt,
            expiresAt = session.expiresAt,
            candidateRevision = 0,
            sourceReason = SnapshotSourceReason.CHECKOUT_STABLE
        )
    }

    /**
     * 记录个人转账（微信/支付宝向好友转账）为待确认转账。
     *
     * 收款人是个人而非商户，不作为消费证据参与会话匹配；文本解析与截图
     * AI 两条路径识别到同一笔转账时，由确定性 ID（包名+2 分钟桶+金额+收款人）
     * 幂等收敛为一条，避免"同一笔转账出现两个金额"。
     *
     * ⚠️ 2026-10-12 修：此前**只插库、从不 [PendingChangeHub.emit]** ——
     * 待确认中心里能看到这张卡，但付款确认弹窗永远不弹。真机验收要求
     * "识别到支付成功页就弹出来"，转账同属支付，不能例外。
     *
     * ⚠️ 同批修：`sourceType` 原写死 `"accessibility_transfer"`，但
     * [normalizePaymentEvidenceSourceType] 只接受 `notification` /
     * `accessibility` / `manual`，该值不在 Native.d.ts 的契约内 ——
     * ① 弹窗来源文案退到 `else` 分支显示通用句；② 待确认中心
     * `ReviewScreen.tsx:1161` 的 `SOURCE_LABELS` 无此键，会把原始英文串
     * 直接显示给用户。转账的业务语义由 `type = "transfer"` 承载，
     * `sourceType` 描述的是**证据从哪来**，此处本就该是 `accessibility`。
     */
    suspend fun recordPersonalTransfer(
        amountMinor: Long,
        paymentPackage: String,
        recipient: String?,
        occurredAt: Long?
    ) {
        if (amountMinor <= 0L) return
        val now = nowMillis()
        val effectiveOccurredAt = occurredAt ?: now
        val pendingId = stableId(
            "personal-transfer",
            paymentPackage,
            amountMinor.toString(),
            (effectiveOccurredAt / 120_000L).toString(),
            recipient.orEmpty()
        )
        val dao = database.pendingTransactionDao()
        if (dao.getById(pendingId) != null) {
            return
        }
        val pending = PendingTransactionEntity(
            id = pendingId,
            candidateId = null,
            sessionId = null,
            amountMinor = amountMinor,
            type = "transfer",
            occurredAt = effectiveOccurredAt,
            platform = paymentPackage,
            merchant = recipient,
            productDescription = null,
            orderId = null,
            accountId = null,
            categoryId = null,
            conflictReason = null,
            sourceType = "accessibility",
            createdAt = now,
            version = 1
        )
        database.withTransaction {
            // emit 与插入放在同一个事务内：弹窗侧按 pendingId 回查该行，
            // 事务提交前发出等于让弹窗去读一条还不存在的记录。
            if (dao.insertIfAbsent(pending) == -1L) {
                return@withTransaction
            }
            PendingChangeHub.emit(
                PendingChangeInfo(
                    pendingId = pendingId,
                    type = pending.type,
                    amountMinor = pending.amountMinor,
                    sourceType = pending.sourceType,
                    conflictReason = pending.conflictReason,
                    occurredAt = pending.occurredAt,
                    isNew = true
                )
            )
        }
        JotBillLog.i(
            "CheckoutPaymentMatcher",
            "个人转账已转待确认: amount=$amountMinor, recipient=${recipient ?: "未知"}, " +
                "package=$paymentPackage, pendingId=${pendingId.take(12)}"
        )
    }

    /**
     * 阶段四审查修复：记录无会话可关联的孤儿支付信号。
     *
     * 支付先到但没有任何结算会话可匹配时（无障碍服务未连接、无活跃结算页、
     * 会话解析 NoMatch），不丢弃证据：直接落一条纯通知待确认交易
     * （sessionId 为空、candidateId 为空），由用户在待确认中心处理。
     *
     * 幂等：两道。
     * 1. **同 sourceEventKey** —— 待确认 ID 由 sourceEventKey 确定性生成，重复信号不产生第二条；
     * 2. **D-25 跨桶重复观测** —— 同支付包 + 同金额 + [ORPHAN_DEDUP_WINDOW_MILLIS]
     *    窗内已有 pending 时不新建第二张卡，只把 `conflictReason` 合并进既有记录。
     *    （第 1 道挡不住：结果页事件键含 30 秒时间桶，跨桶即换键。）
     */
    suspend fun recordOrphanPaymentSignal(
        signal: PaymentNotificationFallbackSignal,
        sourceType: String = "NOTIFICATION",
        conflictReason: String = "NO_ACTIVE_SESSION",
        sessionIds: List<String> = emptyList()
    ) {
        val now = nowMillis()
        database.withTransaction {
            if (!signal.isValid()) {
                JotBillLog.w(
                    "CheckoutPaymentMatcher",
                    "孤立支付证据未落待确认(信号非法): pkg=${signal.paymentPackage}, " +
                        "amount=${signal.amountMinor}, reason=$conflictReason"
                )
                return@withTransaction
            }
            val normalizedSourceType = normalizePaymentEvidenceSourceType(sourceType)
                ?: run {
                    JotBillLog.w(
                        "CheckoutPaymentMatcher",
                        "孤立支付证据未落待确认(来源类型非法): sourceType=$sourceType, " +
                            "amount=${signal.amountMinor}"
                    )
                    return@withTransaction
                }
            // D-3 / G3.1：v1 通知链路停用 —— 通知来源的孤儿待确认同样不落。
            // 与 recordPaymentEvidence 的出口开关成对，否则通知仍会经孤儿路径
            // 产生纯通知待确认（这正是 D-3 要消除的形态）。
            if (isNotificationSourceDisabledInV1(normalizedSourceType)) {
                JotBillLog.i(
                    "CheckoutPaymentMatcher",
                    "孤立支付证据来源在 v1 已停用(D-3)，未落待确认: pkg=${signal.paymentPackage}, " +
                        "amount=${signal.amountMinor}, reason=$conflictReason"
                )
                return@withTransaction
            }
            // 同额支付刚被处理过（确认入账/忽略）⇒ 用户多半还停在同一个结果页，
            // 本帧是同一笔的重复观测。不建卡、不 emit（见 [RecentPaymentCooldown]
            // 的完整失效分析）。必须早于下面三道既有去重：它们分别依赖
            // sourceEventKey 不跨桶、内存事件记忆、以及**仍然存活的** pending 行，
            // 而消费之后这三者都不成立。
            val cooldownRemaining = RecentPaymentCooldown.cooldownRemaining(
                paymentPackage = signal.paymentPackage,
                amountMinor = signal.amountMinor,
                now = now
            )
            if (cooldownRemaining != null) {
                JotBillLog.i(
                    "CheckoutPaymentMatcher",
                    "支付结果页重复观测已抑制(该笔刚被处理): pkg=${signal.paymentPackage}, " +
                        "amount=${signal.amountMinor}, 剩余冷却=${cooldownRemaining}ms"
                )
                return@withTransaction
            }
            val sourceEventKey = sourceEventKey(signal)
            val pendingId = stableId("pending-orphan", sourceEventKey)
            val dao = database.pendingTransactionDao()
            val existingOrphan = dao.getById(pendingId)
            if (existingOrphan != null) {
                // 批 V-4 处置：同一笔支付可能被**两条路径**各落一次孤儿（服务层证据落库
                // 路径 + 协调器归属出口路径）。幂等键保证不产生第二条待确认，但后到
                // 路径的 conflictReason 不得被静默丢弃 —— `PAYMENT_PROCESS_SNAPSHOT_MISSING`
                // 是量化"搬运漏检率"的锚点（R2 目标）。
                // ⇒ 合并而非丢弃；口径与 RN 侧 translateConflictReason 的多码拆分一致
                //   （逗号分隔、既有码在前，保住 D-21 的 startsWith 前缀语义）。
                val mergedReason = mergeOrphanConflictReason(
                    existing = existingOrphan.conflictReason,
                    incoming = conflictReason
                )
                if (mergedReason != existingOrphan.conflictReason) {
                    dao.updateConflictReason(pendingId, mergedReason)
                    JotBillLog.i(
                        "CheckoutPaymentMatcher",
                        "孤立支付证据冲突原因已合并: pending=${pendingId.take(16)}, " +
                            "from=${existingOrphan.conflictReason}, to=$mergedReason"
                    )
                } else {
                    JotBillLog.i(
                        "CheckoutPaymentMatcher",
                        "孤立支付证据已存在待确认(幂等跳过): pending=${pendingId.take(16)}, " +
                            "amount=${signal.amountMinor}, reason=$conflictReason"
                    )
                }
                return@withTransaction
            }
            // ---- D-25：跨桶重复观测去重（孤儿待确认专用）----
            // 上面的幂等键按 sourceEventKey 判重，而结果页事件键含 30 秒时间桶
            // （D-6 为防"同额两单撞键"而定的窄桶），跨桶即换键 ⇒ 挡不住重复观测。
            // 证据层有 mergeSessionEvidence 的 2 分钟窗口兜底，孤儿卡没有 ⇒ 补这一道。
            // 口径：同支付包 + 同金额 + 60 秒窗内已有 pending ⇒ 判为同一笔的重复观测，
            // 合并 conflictReason 而不新建第二张卡（证据不丢弃，冲突码照旧保全）。
            if (signal.occurredAt > 0L) {
                val duplicate = dao.findRecentPendingByPlatformAndAmount(
                    platform = signal.paymentPackage,
                    amountMinor = signal.amountMinor,
                    windowStart = signal.occurredAt - ORPHAN_DEDUP_WINDOW_MILLIS,
                    windowEnd = signal.occurredAt + ORPHAN_DEDUP_WINDOW_MILLIS
                )
                if (duplicate != null) {
                    val mergedReason = mergeOrphanConflictReason(
                        duplicate.conflictReason,
                        conflictReason
                    )
                    if (mergedReason != duplicate.conflictReason) {
                        dao.updateConflictReason(duplicate.id, mergedReason)
                    }
                    JotBillLog.i(
                        "CheckoutPaymentMatcher",
                        "孤立支付证据判为重复观测(同包同额" +
                            "${ORPHAN_DEDUP_WINDOW_MILLIS / 1000}秒内)，未新建待确认: " +
                            "pending=${duplicate.id.take(16)}, incoming=${pendingId.take(16)}, " +
                            "amount=${signal.amountMinor}, reason=$conflictReason"
                    )
                    return@withTransaction
                }
            }
            // 阶段五 T1 时机一：纯通知待确认创建前执行规则；
            // set_platform 不应用于 pending.platform（该列承担支付包名语义），
            // set_merchant/set_account/set_category 可安全应用
            val ruleDecision = ruleEngine.evaluateAndRecord(
                RuleEngine.Subject(
                    amountMinor = signal.amountMinor,
                    platform = signal.paymentPackage,
                    merchant = signal.merchant,
                    description = null,
                    sourceType = normalizedSourceType.lowercase()
                )
            )
            if (ruleDecision is RuleEngine.Decision.Ignored) {
                recordRuleDecisionAudit(pendingId, "ignore", ruleDecision.hits)
                JotBillLog.i(
                    "CheckoutPaymentMatcher",
                    "孤立支付证据被规则忽略: pending=${pendingId.take(16)}, " +
                        "amount=${signal.amountMinor}, hits=${ruleDecision.hits.size}"
                )
                return@withTransaction
            }
            val rulePatch = ruleEngine.sanitizePatch(
                (ruleDecision as? RuleEngine.Decision.Continue)?.patch
                    ?: RuleEngine.FieldPatch()
            )

            // ---- 阶段五 T6 去重第 4 级：纯通知待确认创建前疑似重复提示（只提示不合并）----
            // 查询异常保守处理：疑似重复信息缺失不阻塞待确认创建（证据不丢弃），
            // 保持原 conflictReason；命中时覆盖为 SUSPECTED_DUPLICATE 并在
            // appliedRules 记录重复账单与因素，原原因保留在 baseConflictReason
            val resolvedMerchant = rulePatch.merchant ?: signal.merchant
            val resolvedAccountId = rulePatch.accountId ?: defaultAccountIdForPayment(signal.paymentPackage)
            var effectiveConflictReason = conflictReason
            var dedupExtras: Pair<String, String>? = null
            try {
                val guard = DuplicateGuard(database)
                val similarHits = guard.findSimilar(
                    type = "expense",
                    amountMinor = signal.amountMinor,
                    occurredAt = signal.occurredAt,
                    accountId = resolvedAccountId,
                    merchant = resolvedMerchant
                )
                if (similarHits.isNotEmpty()) {
                    effectiveConflictReason = DuplicateGuard.CONFLICT_SUSPECTED_DUPLICATE
                    dedupExtras = "suspectedDuplicate" to guard.suspectedDuplicateHint(similarHits)
                }
            } catch (_: RuntimeException) {
                // 相似检查失败：不阻塞待确认创建，保守不标记
            }

            val pending = PendingTransactionEntity(
                id = pendingId,
                candidateId = null,
                sessionId = null,
                amountMinor = signal.amountMinor,
                type = "expense",
                occurredAt = signal.occurredAt,
                platform = signal.paymentPackage,
                merchant = resolvedMerchant,
                productDescription = null,
                orderId = signal.orderId,
                accountId = resolvedAccountId,
                categoryId = rulePatch.categoryId,
                conflictReason = effectiveConflictReason,
                sourceType = normalizedSourceType,
                appliedRules = dedupExtras?.let { extras ->
                    ruleEngine.mergeAppliedRules(
                        mergeRulesJson("{}", "baseConflictReason" to conflictReason, extras),
                        ruleDecision.hits
                    )
                } ?: ruleEngine.mergeAppliedRules("{}", ruleDecision.hits),
                createdAt = now,
                version = 1
            )

            // ⚠️ 2026-10-12 修两处：
            //  ① emit 用的 `pendingId` 是 `stableId("pending-orphan", …)`，
            //     而真正插入的行主键是 `stableId("pending-ambiguous", sessionId, …)`
            //     —— 弹窗侧按 ID 回查必然落空，指向一条不存在的记录。
            //  ② 兜底分支 `dao.insertIfAbsent(pending)` 插了行，却因为
            //     `insertedAmbiguousPending == false` 跳过 emit 直接 return
            //     —— 落库了、不弹窗。
            // 现在记录**真实插入的 ID**，只 emit 一条（弹窗同刻只可能有一张，
            // 其余 Ambiguous 行留给待确认中心），且保证 ID 一定存在。
            if (sessionIds.isNotEmpty()) {
                val sessionDao = database.checkoutSessionDao()
                var insertedAmbiguousPending = false
                var firstInsertedId: String? = null
                sessionIds
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .forEach { sessionId ->
                        val session = sessionDao.getById(sessionId) ?: return@forEach
                        if (session.isClosedOrExpired(now)) return@forEach
                        val ambiguousId = stableId("pending-ambiguous", sessionId, sourceEventKey)
                        val insertResult = dao.insertIfAbsent(
                            pending.copy(id = ambiguousId, sessionId = sessionId)
                        )
                        if (insertResult != -1L) {
                            insertedAmbiguousPending = true
                            if (firstInsertedId == null) firstInsertedId = ambiguousId
                        }
                    }
                if (!insertedAmbiguousPending) {
                    if (dao.insertIfAbsent(pending) != -1L) firstInsertedId = pendingId
                }
                    JotBillLog.i(
                        "CheckoutPaymentMatcher",
                        "孤立支付证据已落待确认(并发会话): pending=${firstInsertedId?.take(16)}, " +
                            "amount=${signal.amountMinor}, sessions=${sessionIds.size}, " +
                            "ambiguous=$insertedAmbiguousPending, reason=$effectiveConflictReason"
                    )
                    if (firstInsertedId != null) {
                        RecentPaymentCooldown.rememberCard(
                            pendingId = firstInsertedId,
                            paymentPackage = signal.paymentPackage,
                            amountMinor = signal.amountMinor
                        )
                        PendingChangeHub.emit(
                            PendingChangeInfo(
                                pendingId = firstInsertedId,
                                type = pending.type,
                                amountMinor = pending.amountMinor,
                                sourceType = pending.sourceType,
                                conflictReason = effectiveConflictReason,
                                occurredAt = pending.occurredAt,
                                isNew = true
                            )
                        )
                    }
                    return@withTransaction
                }
            val inserted = dao.insertIfAbsent(pending)
            JotBillLog.i(
                "CheckoutPaymentMatcher",
                "孤立支付证据已落待确认: pending=${pendingId.take(16)}, " +
                    "amount=${signal.amountMinor}, merchant=${resolvedMerchant ?: "null"}, " +
                    "account=${resolvedAccountId ?: "null"}, reason=$effectiveConflictReason, " +
                    "inserted=${inserted != -1L}"
            )
            if (inserted != -1L) {
                // 必须在 emit 之前登记：emit 是同步的，监听方可能在同一栈上立刻
                // 走到"确认入账"，那时若还没登记就查不到冷却键（见 [RecentPaymentCooldown]）。
                RecentPaymentCooldown.rememberCard(
                    pendingId = pendingId,
                    paymentPackage = signal.paymentPackage,
                    amountMinor = signal.amountMinor
                )
                PendingChangeHub.emit(
                    PendingChangeInfo(
                        pendingId = pendingId,
                        type = pending.type,
                        amountMinor = pending.amountMinor,
                        sourceType = pending.sourceType,
                        conflictReason = effectiveConflictReason,
                        occurredAt = pending.occurredAt,
                        isNew = true
                    )
                )
            }
        }
    }

    /**
     * 记录支付证据；重复来源事件只保留一条证据。
     *
     * @param sourceType 证据来源：NOTIFICATION（通知监听）或 ACCESSIBILITY（支付结果页），
     * 供前端区分展示，不再把结果页证据误记为通知。
     * @param aiUnverified true 表示该证据仅由截图 AI 视觉推断得出，未经页面文本
     * 语义或支付通知证实。此类证据落库为 [PAYMENT_STATUS_AI_UNVERIFIED]，
     * 不参与自动入账，只能转待确认由用户核对。
     */
    suspend fun recordPaymentEvidence(
        reference: CheckoutSnapshotReference,
        signal: PaymentNotificationFallbackSignal,
        sourceType: String = "NOTIFICATION",
        aiUnverified: Boolean = false
    ): CheckoutPaymentMatchResult {
        val validationError = validatePayment(reference, signal)
        if (validationError != null) {
            return CheckoutPaymentMatchResult.Rejected(reference.sessionId, validationError)
        }
        val normalizedSourceType = normalizePaymentEvidenceSourceType(sourceType)
            ?: return CheckoutPaymentMatchResult.Rejected(
                reference.sessionId,
                "PAYMENT_SOURCE_TYPE_INVALID"
            )
        // D-3 / G3.1：v1 通知链路停用 —— 通知证据不参与匹配与入账。
        // 采集与解析代码保留，新版本删除此闸即恢复。
        if (isNotificationSourceDisabledInV1(normalizedSourceType)) {
            JotBillLog.i(
                "CheckoutPaymentMatcher",
                "支付证据来源在 v1 已停用(D-3)，本次通知证据不参与匹配: pkg=${signal.paymentPackage}, " +
                    "amount=${signal.amountMinor}"
            )
            return CheckoutPaymentMatchResult.Rejected(
                reference.sessionId,
                "PAYMENT_NOTIFICATION_DISABLED_V1"
            )
        }

        return database.withTransaction {
            val now = nowMillis()
            val sourceEventKey = sourceEventKey(signal)
            val evidenceDao = database.paymentEvidenceDao()
            val existingEvidence = evidenceDao.getBySourceEventKey(sourceEventKey)
            if (existingEvidence != null) {
                if (existingEvidence.sessionId != reference.sessionId) {
                    return@withTransaction CheckoutPaymentMatchResult.Rejected(
                        reference.sessionId,
                        "PAYMENT_EVIDENCE_ALREADY_BOUND"
                    )
                }
                val existingSession = database.checkoutSessionDao().getById(reference.sessionId)
                    ?: return@withTransaction CheckoutPaymentMatchResult.Rejected(
                        reference.sessionId,
                        "PAYMENT_SESSION_MISSING"
                    )
                val existingCandidate = database.transactionCandidateDao()
                    .getBySession(reference.sessionId)
                    .singleOrNull()
                return@withTransaction CheckoutPaymentMatchResult.Accepted(
                    sessionId = reference.sessionId,
                    status = existingSession.status,
                    candidateId = existingSession.candidateId ?: existingCandidate?.id,
                    evidenceId = existingEvidence.id,
                    matched = existingCandidate?.matched == true
                )
            }
            val existingSession = database.checkoutSessionDao().getById(reference.sessionId)
            val existingCandidateBeforeReopen = database.transactionCandidateDao()
                .getBySession(reference.sessionId)
                .singleOrNull()
            val failedAiPlaceholder = existingSession != null &&
                existingSession.status == "FAILED" &&
                existingCandidateBeforeReopen?.conflictReason == AI_RECOGNITION_FAILED_REASON &&
                existingCandidateBeforeReopen.amountMinor == null &&
                existingCandidateBeforeReopen.evidenceAmountMinor == null
            if (existingSession != null && existingSession.isClosedOrExpired(now) && !failedAiPlaceholder) {
                return@withTransaction CheckoutPaymentMatchResult.Rejected(
                    reference.sessionId,
                    "PAYMENT_SESSION_CLOSED"
                )
            }
            var reopenedFailedSession: CheckoutSessionEntity? = null
            if (failedAiPlaceholder && existingSession != null) {
                // AI 失败占位候选获得支付证据：用支付金额补齐候选，并把会话从
                // FAILED 重开为 PAYMENT_CONFIRMED，后续仍转待确认，不自动入账。
                val candidate = checkNotNull(existingCandidateBeforeReopen)
                database.transactionCandidateDao().update(
                    candidate.copy(
                        amountMinor = signal.amountMinor,
                        // merchant 只保留真实商户名（支付通知提供），不再用购物平台兜底：
                        // 平台已由 platform 字段独立承载。
                        merchant = signal.merchant ?: candidate.merchant,
                        evidenceAmountMinor = signal.amountMinor,
                        evidenceMerchant = signal.merchant,
                        evidenceOrderId = signal.orderId,
                        aiOccurredAt = signal.occurredAt,
                        version = candidate.version + 1
                    )
                )
                reopenedFailedSession = existingSession.copy(
                    status = CheckoutJourneyState.PAYMENT_CONFIRMED.toPublicSessionStatus().wireValue,
                    journeyState = PAYMENT_CONFIRMED_STATUS,
                    updatedAt = now
                )
                database.checkoutSessionDao().update(reopenedFailedSession)
            }
            var session = reopenedFailedSession ?: upsertSession(
                reference = reference,
                initialStatus = PAYMENT_CONFIRMED_STATUS,
                requestKey = null,
                aiRequestId = null,
                now = now
            )
            session = advancePaymentConfirmed(session, now)
            val evidenceId = stableId(
                "payment",
                sourceEventKey
            )
            val evidence = PaymentEvidenceEntity(
                id = existingEvidence?.id ?: evidenceId,
                sessionId = reference.sessionId,
                sourceType = normalizedSourceType,
                amountMinor = signal.amountMinor,
                merchant = signal.merchant,
                platform = signal.paymentPackage,
                orderId = signal.orderId,
                paymentMethod = null,
                paymentStatus = if (aiUnverified) PAYMENT_STATUS_AI_UNVERIFIED else "SUCCESS",
                occurredAt = signal.occurredAt,
                rawTextHash = signal.rawTextHash ?: stableId(
                    "payment-source",
                    signal.paymentPackage,
                    signal.amountMinor.toString(),
                    signal.occurredAt.toString(),
                    signal.merchant,
                    signal.orderId,
                    sourceEventKey
                ),
                sourceEventKey = sourceEventKey,
                capturedAt = now
            )
            val storedEvidence = if (existingEvidence == null) {
                mergeSessionEvidence(reference.sessionId, evidence, now)
            } else {
                if (evidence != existingEvidence) {
                    // DAO 未提供更新方法，在同一事务内按原主键替换证据。
                    evidenceDao.delete(existingEvidence)
                    evidenceDao.insert(evidence)
                }
                evidence
            }

            val candidate = database.transactionCandidateDao()
                .getBySession(reference.sessionId)
                .singleOrNull()
            if (candidate == null) {
                // AI 尚未返回时支付证据先到：先用证据建立 EVIDENCE_ONLY 临时候选，
                // 让待确认中心立刻显示关联到本会话的金额/商户/时间；AI 成功返回后仍可
                // 覆盖该占位候选并补全商品描述，AI 失败时也会回填失败原因和重试入口。
                val provisionalCandidate = TransactionCandidateEntity(
                    id = candidateIdFor(reference.sessionId),
                    sessionId = reference.sessionId,
                    amountMinor = storedEvidence.amountMinor,
                    // platform 固定为购物平台；证据里的 platform 是支付包名，不能混用。
                    platform = shoppingPlatformName(session.shoppingPackage),
                    merchant = storedEvidence.merchant,
                    productDescription = null,
                    evidenceAmountMinor = storedEvidence.amountMinor,
                    evidenceMerchant = storedEvidence.merchant,
                    evidenceOrderId = storedEvidence.orderId,
                    aiOrderId = null,
                    aiOccurredAt = null,
                    matched = false,
                    conflictReason = null,
                    appliedRules = rulesJson(
                        "sessionId" to reference.sessionId,
                        "decision" to EVIDENCE_ONLY_DECISION
                    ),
                    createdAt = now,
                    version = 1
                )
                database.transactionCandidateDao().insertIfAbsent(provisionalCandidate)
                val persistedProvisional = database.transactionCandidateDao()
                    .getById(provisionalCandidate.id)
                    ?: provisionalCandidate
                session = session.copy(
                    candidateId = persistedProvisional.id,
                    updatedAt = now
                )
                database.checkoutSessionDao().update(session)
                session = advanceStatus(session, WAITING_ENRICHMENT_STATUS, now)
                // 批 V-5（改造 D-2 ①）：**不再**在此处落待确认 —— 旧实现"证据一到就落卡"
                // 会让弹窗早于 AI 定论出现（AI 未返回 ⇒ 字段为空），AI 返回无冲突后又会
                // 自动入账并清掉该卡（弹窗自消失），即 P5。
                // `WAITING_ENRICHMENT` 本身就是"等待 AI 定论"的显式中间态且写在 Room 里
                // （进程被杀可恢复）⇒ 落卡时机推迟到：
                //   · AI 定论为需 review / 失败超时（本文件后续分支）；
                //   · 定论**不可得**时 —— 服务层 [promoteEvidenceOnlySessionToPending]
                //     （派发失败立即补）与 [backfillEvidenceOnlyCandidates]（启动期兜底）。
                return@withTransaction CheckoutPaymentMatchResult.Accepted(
                    sessionId = reference.sessionId,
                    status = session.status,
                    candidateId = persistedProvisional.id,
                    evidenceId = storedEvidence.id,
                    matched = false
                )
            }

            if (isEvidenceOnlyProvisionalCandidate(candidate)) {
                // 候选仍是"证据先到"占位（AI 未返回，aiOccurredAt 必为空）：
                // 不能走 AI 候选评估，否则必然误报 TIME_MISSING 并把正常待确认
                // 覆盖成"缺少时间信息"冲突。这里只做字段补齐与金额一致性校验。
                val amountConsistent = storedEvidence.amountMinor == null ||
                    candidate.amountMinor == null ||
                    storedEvidence.amountMinor == candidate.amountMinor
                if (!amountConsistent) {
                    // 同会话两笔不同金额（真正的多证据冲突）：转待确认由用户裁决。
                    val conflicted = candidate.copy(
                        evidenceAmountMinor = storedEvidence.amountMinor,
                        evidenceMerchant = storedEvidence.merchant,
                        evidenceOrderId = storedEvidence.orderId,
                        conflictReason = "EVIDENCE_AMOUNT_CONFLICT",
                        matched = false,
                        version = candidate.version + 1
                    )
                    database.transactionCandidateDao().update(conflicted)
                    session = advanceStatus(session, NEEDS_CONFIRMATION_STATUS, now)
                    pendingFromConflict(
                        session = session,
                        candidate = conflicted,
                        evidence = storedEvidence,
                        conflictReason = "EVIDENCE_AMOUNT_CONFLICT",
                        now = now
                    )?.let { upsertPendingForSession(it) }
                    return@withTransaction CheckoutPaymentMatchResult.Accepted(
                        sessionId = reference.sessionId,
                        status = session.status,
                        candidateId = conflicted.id,
                        evidenceId = storedEvidence.id,
                        matched = false
                    )
                }
                val enriched = candidate.copy(
                    amountMinor = storedEvidence.amountMinor ?: candidate.amountMinor,
                    // merchant 只保留真实商户名，不再用购物平台兜底。
                    merchant = candidate.merchant ?: storedEvidence.merchant,
                    evidenceAmountMinor = storedEvidence.amountMinor ?: candidate.evidenceAmountMinor,
                    evidenceMerchant = storedEvidence.merchant ?: candidate.evidenceMerchant,
                    evidenceOrderId = storedEvidence.orderId ?: candidate.evidenceOrderId,
                    version = candidate.version + 1
                )
                if (enriched != candidate) {
                    database.transactionCandidateDao().update(enriched)
                }
                session = session.copy(candidateId = enriched.id, updatedAt = now)
                database.checkoutSessionDao().update(session)
                // 批 V-5（改造 D-2 ①）：与上一个分支同源 —— 候选仍是证据占位即"AI 未返回"，
                // 第二笔证据同样不得抢在 AI 定论前落卡（否则又会得到一次过早弹窗）。
                return@withTransaction CheckoutPaymentMatchResult.Accepted(
                    sessionId = reference.sessionId,
                    status = session.status,
                    candidateId = enriched.id,
                    evidenceId = storedEvidence.id,
                    matched = false
                )
            }

            // AI 先到但未识别到时间（结算页常无时间字段）：证据后到时用证据时间
            // 回填候选 aiOccurredAt，避免 evaluate 双侧时间校验误报 TIME_MISSING。
            val evalCandidate = if (candidate.aiOccurredAt == null && storedEvidence.occurredAt != null) {
                val backfilled = candidate.copy(
                    aiOccurredAt = storedEvidence.occurredAt,
                    version = candidate.version + 1
                )
                database.transactionCandidateDao().update(backfilled)
                JotBillLog.i(
                    "CheckoutPaymentMatcher",
                    "AI 未识别时间，证据后到回填: session=${reference.sessionId.take(8)}, " +
                        "occurredAt=${storedEvidence.occurredAt}"
                )
                backfilled
            } else {
                candidate
            }
            val evaluation = evaluate(evalCandidate, storedEvidence)
            val updatedCandidate = candidateAfterPayment(evalCandidate, storedEvidence, evaluation)
            if (updatedCandidate != candidate) {
                database.transactionCandidateDao().update(updatedCandidate)
            }
            session = session.copy(
                candidateId = updatedCandidate.id,
                updatedAt = now
            )
            database.checkoutSessionDao().update(session)
            val effectiveConflictReason = resolveConflictWithAiUnverifiedGuard(
                evaluation.conflictReason,
                storedEvidence.paymentStatus
            )
            val outcome = if (effectiveConflictReason == null) {
                // 阶段四 T5：匹配成功且无冲突，自动入账（写 Bill + 余额更新 + 会话 AUTO_COMMITTED）
                autoCommitMatchedCandidate(session, updatedCandidate, storedEvidence, now)
            } else {
                // 阶段四 T6：冲突候选转待确认，conflictReason 必须填充供前端展示
                pendingFromConflict(
                    session = session,
                    candidate = updatedCandidate,
                    evidence = storedEvidence,
                    conflictReason = effectiveConflictReason,
                    now = now
                )?.let { upsertPendingForSession(it) }
                AutoCommitOutcome(advanceStatus(session, NEEDS_CONFIRMATION_STATUS, now), null)
            }
            CheckoutPaymentMatchResult.Accepted(
                sessionId = reference.sessionId,
                status = outcome.session.status,
                candidateId = updatedCandidate.id,
                evidenceId = storedEvidence.id,
                matched = updatedCandidate.matched,
                billId = outcome.billId
            )
        }
    }

    /**
     * 启动期证据兜底扫描（批 V-5 起同时承担两件事）。
     *
     * ## ① 历史会话补齐候选（原职责）
     * 为"证据先到、AI 未返回且**尚无候选**"的会话补 EVIDENCE_ONLY 占位候选关联，
     * 每笔证据只补一次，不改变用户已忽略或已确认的状态。
     *
     * ## ② 「AI 定论不可得」兜底落待确认（批 V-5 新增，`IMPL_PLAN` §2.4 D-3 兜底 2）
     * 改造 D 把落待确认推迟到 AI 定论之后 ⇒ 进程被杀时在飞 AI 请求随作用域一起消失，
     * 定论永不返回，会话会停在 `WAITING_ENRICHMENT` + EVIDENCE_ONLY 占位候选上
     * ⇒ 用户永远看不到该笔。
     *
     * ⚠️ **必须在本方法内扩条件，而不是另起一套扫描**（批 V-5 实施前核查发现）：
     * 旧判据是"**无候选**"，而改造 D 之后目标会话**必然已有** EVIDENCE_ONLY 占位候选
     * ⇒ 旧判据永远命中不到它们；另写一套扫描则会出现两套扫描互相打架（同一会话被
     * 两条路径各处理一次）。故本方法用同一循环覆盖两种形态：
     * - 无候选 + 有支付成功证据 ⇒ 先补占位候选，再落待确认；
     * - 有 EVIDENCE_ONLY 占位候选 + 会话仍在 `WAITING_ENRICHMENT` ⇒ 说明无 AI 结果
     *   （`candidateFromAi` 在 AI 返回时会重建 `appliedRules.decision`，占位标记随即消失
     *   —— 见 [isEvidenceOnlyProvisionalCandidate]）⇒ 直接落待确认。
     *
     * 落卡统一走 [promoteEvidenceOnlySessionToPending]：主键与会话一对一（幂等）、
     * 已有孤儿卡时跳过（防同一笔两张卡）、AI 已定论时跳过（不抢跑）。
     * 兼容已过期的历史会话：证据已落库的会话即使过了匹配窗口也应让用户看到。
     */
    suspend fun backfillEvidenceOnlyCandidates(): EvidenceBackfillOutcome {
        var candidatesBackfilled = 0
        var pendingsPromoted = 0
        database.withTransaction {
            val now = nowMillis()
            val sessions = database.checkoutSessionDao().getByStatuses(
                listOf("WAITING_ENRICHMENT", "PAYMENT_CONFIRMED", "NEEDS_CONFIRMATION")
            )
            for (session in sessions) {
                val existingCandidate = database.transactionCandidateDao()
                    .getBySession(session.id)
                    .singleOrNull()
                if (existingCandidate != null) {
                    // ② 兜底 2：占位候选 + 仍在 WAITING_ENRICHMENT ⇒ 无 AI 结果。
                    // 会话已被推进到其它旅程状态（如已落卡的 NEEDS_CONFIRMATION）时不介入。
                    if (
                        (session.journeyState ?: session.status) != WAITING_ENRICHMENT_STATUS ||
                        !isEvidenceOnlyProvisionalCandidate(existingCandidate)
                    ) {
                        continue
                    }
                    if (
                        promoteEvidenceOnlySessionToPending(session.id, AI_RESULT_MISSING_ON_STARTUP)
                    ) {
                        pendingsPromoted += 1
                    }
                    continue
                }
                val evidence = database.paymentEvidenceDao()
                    .getBySession(session.id)
                    .firstOrNull { it.indicatesPaidOutcome() && (it.amountMinor ?: 0L) > 0L }
                    ?: continue
                val provisionalCandidate = TransactionCandidateEntity(
                    id = candidateIdFor(session.id),
                    sessionId = session.id,
                    amountMinor = evidence.amountMinor,
                    // platform 固定为购物平台，不写入支付包名。
                    platform = shoppingPlatformName(session.shoppingPackage),
                    merchant = evidence.merchant,
                    productDescription = null,
                    evidenceAmountMinor = evidence.amountMinor,
                    evidenceMerchant = evidence.merchant,
                    evidenceOrderId = evidence.orderId,
                    aiOrderId = null,
                    aiOccurredAt = null,
                    matched = false,
                    conflictReason = null,
                    appliedRules = rulesJson(
                        "sessionId" to session.id,
                        "decision" to EVIDENCE_ONLY_DECISION
                    ),
                    createdAt = now,
                    version = 1
                )
                database.transactionCandidateDao().insertIfAbsent(provisionalCandidate)
                val persisted = database.transactionCandidateDao()
                    .getById(provisionalCandidate.id)
                    ?: provisionalCandidate
                val updatedSession = session.copy(
                    candidateId = persisted.id,
                    updatedAt = now
                )
                database.checkoutSessionDao().update(updatedSession)
                advanceStatus(updatedSession, WAITING_ENRICHMENT_STATUS, now)
                // ① 的历史形态同样"没有 AI 定论会到来" ⇒ 落卡出口与 ② 完全一致。
                if (promoteEvidenceOnlySessionToPending(session.id, AI_RESULT_MISSING_ON_STARTUP)) {
                    pendingsPromoted += 1
                }
                candidatesBackfilled += 1
            }
        }
        return EvidenceBackfillOutcome(candidatesBackfilled, pendingsPromoted)
    }


    /**
     * 批 V-5（改造 D-2 ② / D-3 兜底）：把"证据已落库、但**不会有 AI 定论**"的会话落待确认。
     *
     * ## 为什么需要它
     *
     * 改造 D 把"创建待确认 + 弹窗"从"证据落库时"推迟到"AI 定论时" ⇒ 必须有一个出口
     * 覆盖"定论永远不会来"的形态，否则证据会停在中间态、用户永远看不到（静默丢失）。
     * 两个调用面：
     * 1. **服务层**：`trigger(PAYMENT_RESULT)` 判定为未派发/派发失败（AI 未配置、
     *    预算耗尽、门禁拒绝）时立即调用；截图兜底路径同样调用 —— 它直调视觉客户端
     *    （`recognizePaymentResult`），**不经** matcher 的 AI 定论链路，本就不会有定论；
     * 2. **启动期**：[backfillEvidenceOnlyCandidates] 对"仍是 EVIDENCE_ONLY 占位"的历史
     *    会话调用（进程被杀 ⇒ 在飞请求随作用域消失，定论永不返回）。
     *
     * ## 幂等与防重复（关键）
     *
     * 待确认主键 = `stableId("pending", sessionId)`，与会话一对一 ⇒ 任何一条兜底触发
     * 都只产生**一张**卡；且具备**自愈**能力：若 AI 定论随后到达，无冲突时
     * [autoCommitMatchedCandidate] 按 sessionId 删除本卡，需 review 时 [pendingFromConflict]
     * 覆盖同一主键 ⇒ 不会出现第二张。
     *
     * @param reason 写入 `conflictReason`（同时是日志锚点，前端按码分档展示）。
     * @return true 表示该会话当前**有待确认卡**（本次新建或本就存在）；
     *   false 表示未落卡（会话不存在 / 已入账 / 已有孤儿卡覆盖 / AI 已定论 / 证据金额无效）。
     */
    suspend fun promoteEvidenceOnlySessionToPending(sessionId: String, reason: String): Boolean =
        database.withTransaction {
            val session = database.checkoutSessionDao().getById(sessionId)
                ?: return@withTransaction false
            // 已入账（自动入账或用户手动确认）：用户已经/正在看到该笔，不再落卡
            if (
                database.billDao().getById(stableId("bill", session.id)) != null ||
                database.billDao().findByOriginalTransactionId(session.id) != null
            ) {
                return@withTransaction false
            }
            val candidate = database.transactionCandidateDao()
                .getBySession(session.id)
                .singleOrNull()
            // 候选不再是"证据占位" ⇒ AI 已定论 ⇒ 交回正常链路（入账 / 冲突待确认 / 显式终态），
            // 本方法不得抢跑（否则会与 AI 定论竞争出第二张卡）。
            if (!isEvidenceOnlyProvisionalCandidate(candidate)) {
                return@withTransaction false
            }
            val now = nowMillis()
            // 会话已有待确认（含 Ambiguous 形态，其行带 sessionId）⇒ 幂等，无需重复写
            if (database.pendingTransactionDao().findPendingBySessionId(session.id) != null) {
                return@withTransaction true
            }
            val evidence = database.paymentEvidenceDao()
                .getBySession(session.id)
                .firstOrNull { it.indicatesPaidOutcome() && (it.amountMinor ?: 0L) > 0L }
                ?: return@withTransaction false
            // 该证据此前已由**孤儿出口**（协调器 B-3/E-2 兜底：槽位缺图 / 归属 0 命中 /
            // Ambiguous）落过卡。孤儿卡 sessionId 为空，靠会话主键去重**抓不到**它 ⇒
            // 此处必须按 `sourceEventKey` 显式判重，否则同一笔支付会出现两张卡。
            if (orphanPendingExists(evidence.sourceEventKey)) {
                return@withTransaction false
            }
            val pending = pendingFromEvidenceOnly(
                session = session,
                evidence = evidence,
                now = now,
                candidateId = candidate?.id,
                conflictReason = reason
            ) ?: return@withTransaction false
            upsertPendingForSession(pending)
            // 推进到 NEEDS_CONFIRMATION：既是语义上的"等用户确认"，也让启动扫描的
            // "仍在 WAITING_ENRICHMENT 且无 AI 结果"条件不再命中 ⇒ 二次扫描天然幂等。
            advanceStatus(session, NEEDS_CONFIRMATION_STATUS, now)
            true
        }

    /**
     * 该支付事件是否已由孤儿出口落过待确认。
     *
     * 主键口径与 [recordOrphanPaymentSignal] 完全一致（`pending-orphan` + sourceEventKey），
     * 而 `PaymentEvidenceEntity.sourceEventKey` 与该函数内 `sourceEventKey(signal)` 同值
     * （落库时即由同一函数算出）⇒ 可直接查表，无需重算。
     */
    private suspend fun orphanPendingExists(sourceEventKey: String?): Boolean {
        val key = sourceEventKey?.takeIf { it.isNotBlank() } ?: return false
        return database.pendingTransactionDao()
            .getById(stableId("pending-orphan", key)) != null
    }

    private suspend fun upsertSession(
        reference: CheckoutSnapshotReference,
        initialStatus: String,
        requestKey: CheckoutRequestKey?,
        aiRequestId: String?,
        now: Long
    ): CheckoutSessionEntity {
        val dao = database.checkoutSessionDao()
        val existing = dao.getById(reference.sessionId)
        if (existing == null) {
            val journeyState = CheckoutJourneyState.fromStorageValue(initialStatus)
            val created = CheckoutSessionEntity(
                id = reference.sessionId,
                status = journeyState.toPublicSessionStatus().wireValue,
                journeyState = journeyState.wireValue,
                shoppingPackage = reference.shoppingPackage,
                pageRole = reference.pageRole.storageValue,
                pageFingerprint = reference.pageFingerprint,
                snapshotId = reference.snapshotId,
                checkoutRequestKey = requestKey?.value,
                aiRequestId = aiRequestId,
                candidateId = null,
                createdAt = reference.capturedAt,
                updatedAt = now,
                expiresAt = sessionExpiresAt(reference)
            )
            if (dao.insertIfAbsent(created) != -1L) {
                return created
            }
            val raced = dao.getById(reference.sessionId)
                ?: throw IllegalStateException("结算会话插入后无法读取")
            return mergeSession(raced, reference, requestKey, aiRequestId, now)
        }

        return mergeSession(existing, reference, requestKey, aiRequestId, now)
    }

    private suspend fun mergeSession(
        existing: CheckoutSessionEntity,
        reference: CheckoutSnapshotReference,
        requestKey: CheckoutRequestKey?,
        aiRequestId: String?,
        now: Long
    ): CheckoutSessionEntity {
        val updated = existing.copy(
            shoppingPackage = existing.shoppingPackage ?: reference.shoppingPackage,
            pageRole = existing.pageRole ?: reference.pageRole.storageValue,
            pageFingerprint = existing.pageFingerprint ?: reference.pageFingerprint,
            snapshotId = existing.snapshotId ?: reference.snapshotId,
            checkoutRequestKey = existing.checkoutRequestKey ?: requestKey?.value,
            aiRequestId = aiRequestId ?: existing.aiRequestId,
            updatedAt = now,
            expiresAt = maxOf(existing.expiresAt, sessionExpiresAt(reference))
        )
        if (updated != existing) {
            database.checkoutSessionDao().update(updated)
        }
        return updated
    }

    private suspend fun advanceStatus(
        session: CheckoutSessionEntity,
        target: String,
        now: Long
    ): CheckoutSessionEntity {
        val journeyValue = session.journeyState ?: session.status
        if (journeyValue == target) {
            return session
        }
        val currentState = CheckoutJourneyState.fromStorageValue(journeyValue)
        val targetState = CheckoutJourneyState.fromStorageValue(target)
        if (!currentState.canTransitionTo(targetState)) {
            return session
        }
        val updated = session.copy(
            status = targetState.toPublicSessionStatus().wireValue,
            journeyState = targetState.wireValue,
            updatedAt = now
        )
        database.checkoutSessionDao().update(updated)
        return updated
    }

    private suspend fun advancePaymentConfirmed(
        session: CheckoutSessionEntity,
        now: Long
    ): CheckoutSessionEntity {
        val journeyValue = session.journeyState ?: session.status
        val state = CheckoutJourneyState.fromStorageValue(journeyValue)
        return if (
            state == CheckoutJourneyState.PAYMENT_CONFIRMED ||
            state == CheckoutJourneyState.WAITING_ENRICHMENT ||
            state == CheckoutJourneyState.NEEDS_CONFIRMATION ||
            state == CheckoutJourneyState.AUTO_COMMITTED
        ) {
            session
        } else {
            advanceStatus(session, PAYMENT_CONFIRMED_STATUS, now)
        }
    }

    /**
     * 阶段四 T5：把 matched 且无冲突的候选自动入账。
     *
     * 必须在外层 database.withTransaction 内调用（Room 嵌套事务合并到外层），
     * 同一事务内依次完成：幂等检查 → 写 Bill → 联动更新账户余额 → 候选标记已入账 →
     * 会话进入 AUTO_COMMITTED（公开 SessionStatus 为 COMPLETED）。
     * 任一步失败整体回滚，会话不进入 COMPLETED。
     *
     * 幂等保证：
     * 1. 会话旅程已是 AUTO_COMMITTED/COMPLETED 时直接返回，不重复入账；
     * 2. Bill 主键由 sessionId 确定性生成，预查存在时只推进会话状态；
     * 3. BillDao.insert 为 ABORT 策略，并发竞态下重复插入抛异常回滚整个事务。
     */
    private suspend fun autoCommitMatchedCandidate(
        session: CheckoutSessionEntity,
        candidate: TransactionCandidateEntity,
        evidence: PaymentEvidenceEntity,
        now: Long
    ): AutoCommitOutcome {
        val journeyValue = session.journeyState ?: session.status
        if (journeyValue == AUTO_COMMITTED_STATUS || journeyValue == COMPLETED_STATUS) {
            return AutoCommitOutcome(session, null)
        }
        val billId = stableId("bill", session.id)
        if (database.billDao().getById(billId) != null) {
            // 该会话历史上已产生过账单（重复通知/重复决策），只补齐会话状态，不写第二笔 Bill
            return AutoCommitOutcome(
                advanceStatus(session, AUTO_COMMITTED_STATUS, now),
                billId
            )
        }
        // 阶段四 T6：用户已手动确认过该会话的待确认交易（Bill.originalTransactionId = sessionId）
        // 视为该会话已入账，跳过自动写 Bill，只推进会话终态，避免同一支付重复入账
        val manuallyCommitted = database.billDao().findByOriginalTransactionId(session.id)
        if (manuallyCommitted != null) {
            return AutoCommitOutcome(advanceSessionCompleted(session, now), manuallyCommitted.id)
        }
        val amountMinor = requireNotNull(candidate.amountMinor) {
            "自动入账候选金额缺失：sessionId=${session.id}"
        }
        // 阶段五 T1：候选生成时规则 ignore 已标记的交易不再入账（规则先标、证据后到的时序）。
        // 批 V-5（§8.4 定案）：命中 ignore 时给会话**显式终态**，不再悬置等过期（P18）。
        if (ruleEngine.decisionOf(candidate.appliedRules) == RuleEngine.DECISION_IGNORED) {
            return AutoCommitOutcome(closeIgnoredSession(session, now), null)
        }
        // 阶段五 T1 时机二：自动入账前最终执行规则（规则可能在候选生成后被用户修改/删除）；
        // ignore 跳过记账，send_to_review 强制转待确认，set_* 按优先级补全账单字段
        val ruleDecision = ruleEngine.evaluateAndRecord(
            RuleEngine.Subject(
                amountMinor = amountMinor,
                platform = candidate.platform,
                merchant = candidate.merchant,
                description = candidate.productDescription,
                sourceType = "ai_vision"
            )
        )
        when (ruleDecision) {
            is RuleEngine.Decision.Ignored -> {
                recordRuleDecisionAudit(candidate.id, "ignore", ruleDecision.hits)
                database.transactionCandidateDao().update(
                    candidate.copy(
                        appliedRules = ruleEngine.mergeAppliedRules(
                            candidate.appliedRules,
                            ruleDecision.hits,
                            decisionOverride = RuleEngine.DECISION_IGNORED
                        ),
                        version = candidate.version + 1
                    )
                )
                // 不写 Bill、不进待确认、不弹窗；但**必须给会话显式终态** ——
                // 旧实现的"保持 WAITING_ENRICHMENT 等待过期回收"会让会话悬置到过期
                // （P18 静默态；改造 D 之后更是没人会再来推进它）。批 V-5（§8.4 定案）。
                return AutoCommitOutcome(closeIgnoredSession(session, now), null)
            }
            is RuleEngine.Decision.ToReview -> {
                database.transactionCandidateDao().update(
                    candidate.copy(
                        matched = false,
                        conflictReason = RuleEngine.CONFLICT_RULE_FORCED_REVIEW,
                        appliedRules = ruleEngine.mergeAppliedRules(
                            candidate.appliedRules,
                            ruleDecision.hits,
                            decisionOverride = RuleEngine.DECISION_FORCED_REVIEW
                        ),
                        version = candidate.version + 1
                    )
                )
                val reviewPending = pendingFromConflict(
                    session = session,
                    candidate = candidate,
                    evidence = evidence,
                    conflictReason = RuleEngine.CONFLICT_RULE_FORCED_REVIEW,
                    now = now
                )
                if (reviewPending != null) {
                    upsertPendingForSession(reviewPending)
                }
                return AutoCommitOutcome(
                    advanceStatus(session, NEEDS_CONFIRMATION_STATUS, now),
                    null
                )
            }
            else -> Unit
        }
        val rulePatch = ruleEngine.sanitizePatch(
            (ruleDecision as? RuleEngine.Decision.Continue)?.patch
                ?: RuleEngine.FieldPatch()
        )
        // 审查修复：支付账户缺失/停用时不自动入账——无账户账单虽不更新余额，
        // 但会话进入 COMPLETED 会让用户误以为已正确入账；转待确认由用户补选账户；
        // 阶段五 T1：规则 set_account 指定的账户优先于包名默认映射
        val accountId = rulePatch.accountId ?: defaultAccountIdForPayment(evidence.platform)
        if (accountId == null) {
            val pending = pendingFromConflict(
                session = session,
                candidate = candidate,
                evidence = evidence,
                conflictReason = "ACCOUNT_UNAVAILABLE",
                now = now
            )
            if (pending != null) {
                upsertPendingForSession(pending)
            }
            return AutoCommitOutcome(
                advanceStatus(session, NEEDS_CONFIRMATION_STATUS, now),
                null
            )
        }
        val bill = BillEntity(
            id = billId,
            amountMinor = amountMinor,
            type = "expense",
            occurredAt = evidence.occurredAt ?: candidate.aiOccurredAt ?: now,
            description = candidate.productDescription,
            // 阶段五 T1：规则 set_category/set_account/set_platform/set_merchant 补全账单字段
            categoryId = rulePatch.categoryId,
            accountId = accountId,
            targetAccountId = null,
            // platform 语义固定为"购物平台"，且只信任系统提供的包名：
            // AI 视觉识别曾把它读成"手机应用"这类无意义文字。规则显式设置的
            // platform 优先级最高（用户意图），其余一律按包名映射推导。
            platform = rulePatch.platform ?: shoppingPlatformName(session.shoppingPackage),
            merchant = rulePatch.merchant
                ?: candidate.merchant
                ?: evidence.merchant,
            productDescription = candidate.productDescription,
            sourceType = "ai_vision",
            originalTransactionId = session.id,
            orderId = evidence.orderId ?: candidate.aiOrderId,
            createdAt = now,
            updatedAt = now,
            version = 1
        )

        // ---- 阶段五 T6：自动入账前去重检查（第 1/4 级）----
        // 第 4 级（相似度）命中或检查异常：不自动入账，转待确认由用户判断，
        // 宁可进待确认也不冒重复入账风险；第 1 级（orderId 精确命中）由
        // createBillWithDedup 内部处理——命中时合并进原账单不产生第二笔
        try {
            val guard = DuplicateGuard(database)
            val similarHits = guard.findSimilar(
                type = bill.type,
                amountMinor = bill.amountMinor,
                occurredAt = bill.occurredAt,
                accountId = bill.accountId,
                merchant = bill.merchant,
                excludeId = bill.id
            )
            if (similarHits.isNotEmpty()) {
                val reviewPending = pendingFromConflict(
                    session = session,
                    candidate = candidate,
                    evidence = evidence,
                    conflictReason = DuplicateGuard.CONFLICT_SUSPECTED_DUPLICATE,
                    now = now,
                    appliedRulesExtras = arrayOf(
                        "suspectedDuplicate" to guard.suspectedDuplicateHint(similarHits)
                    )
                )
                if (reviewPending != null) {
                    upsertPendingForSession(reviewPending)
                }
                return AutoCommitOutcome(
                    advanceStatus(session, NEEDS_CONFIRMATION_STATUS, now),
                    null
                )
            }
        } catch (error: RuntimeException) {
            // 去重检查异常：证据不丢弃，转待确认
            val fallbackPending = pendingFromConflict(
                session = session,
                candidate = candidate,
                evidence = evidence,
                conflictReason = DuplicateGuard.CONFLICT_DEDUP_CHECK_FAILED,
                now = now
            )
            if (fallbackPending != null) {
                upsertPendingForSession(fallbackPending)
            }
            return AutoCommitOutcome(
                advanceStatus(session, NEEDS_CONFIRMATION_STATUS, now),
                null
            )
        }

        // 写 Bill + 按类型联动余额 + 审计日志；嵌套 withTransaction 合并到外层事务；
        // 阶段五 T6：内部执行第 1 级查重——(sourceType, orderId) 命中时合并进
        // 既有账单（不产生第二笔），settledBillId 为实际落库账单
        val createOutcome = ledger.createBillWithDedup(bill, operator = AUTO_COMMIT_OPERATOR)
        val settledBillId = when (createOutcome) {
            is CreateBillOutcome.Committed -> createOutcome.bill.id
            is CreateBillOutcome.MergedInto -> createOutcome.bill.id
        }

        // 候选标记为已入账，记录 billId 供审计与前端追溯；保留规则命中键（rule:*）
        database.transactionCandidateDao().update(
            candidate.copy(
                appliedRules = mergeRulesJson(
                    candidate.appliedRules,
                    "sessionId" to session.id,
                    "decision" to AUTO_COMMITTED_STATUS,
                    "billId" to settledBillId,
                    "evidenceId" to evidence.id
                ),
                version = candidate.version + 1
            )
        )
        // 阶段四 T6：会话已自动入账，清理该会话仍在等待的待确认交易（忽略记录保留审计）
        database.pendingTransactionDao().findPendingBySessionId(session.id)?.let {
            database.pendingTransactionDao().delete(it)
            // 广播终态事件,让提醒消费方消除该待确认的系统通知
            PendingChangeHub.emit(
                PendingChangeInfo(
                    pendingId = it.id,
                    type = it.type,
                    amountMinor = it.amountMinor,
                    sourceType = it.sourceType,
                    conflictReason = null,
                    occurredAt = it.occurredAt,
                    isNew = false,
                    removed = true
                )
            )
        }
        return AutoCommitOutcome(advanceStatus(session, AUTO_COMMITTED_STATUS, now), settledBillId)
    }

    /**
     * 批 V-5（`IMPL_PLAN` §8.4 定案）：规则 `ignore` 命中后的**显式终态**。
     *
     * ## 语义
     * `ignore` 是规则引擎（用户可配置）的**正常结论**，不是失败路径 ⇒ 不写 Bill、
     * 不进待确认、不弹窗；但会话必须离开"等待回收"的悬置态（P18）。
     *
     * ## 为什么是 CANCELLED / DISMISSED，而不是 COMPLETED
     * - **不得写 `COMPLETED`/`AUTO_COMMITTED`**：那会伪造"已入账"语义，账单表里却没有
     *   对应记录 ⇒ 自动入账率、账单一致性都会失真（用户看到"已完成"却查不到账）。
     * - `CANCELLED` / `DISMISSED` 与"用户忽略待确认"完全同码同路径
     *   （`LedgerRepository` 的忽略分支），前端已有该码的处置；规则命中证据仍保留在候选的
     *   `appliedRules.decision=IGNORED` 与 `rule-ignore:<candidateId>` 审计行中，
     *   可与"用户忽略"区分。
     *
     * ## 实现要点
     * 走 `updateTerminalStateIfActive`（SQL 内自守"仅活动会话可写终态"）⇒ **不需要**
     * 给 `CheckoutJourneyState` 增加转换边，避免改动跨层契约（`CheckoutContract`）。
     * 并发下会话若已进入其它终态，则以库内状态为准，不覆盖别人的结论。
     */
    private suspend fun closeIgnoredSession(
        session: CheckoutSessionEntity,
        now: Long
    ): CheckoutSessionEntity {
        val terminal = session.copy(
            status = SessionStatus.CANCELLED.wireValue,
            journeyState = CheckoutJourneyState.DISMISSED.wireValue,
            updatedAt = now
        )
        val rows = database.checkoutSessionDao().updateTerminalStateIfActive(
            id = session.id,
            status = terminal.status,
            journeyState = CheckoutJourneyState.DISMISSED.wireValue,
            updatedAt = now
        )
        return if (rows == 1) {
            terminal
        } else {
            database.checkoutSessionDao().getById(session.id) ?: session
        }
    }

    /**
     * 阶段四 T6：把会话推进到旅程终态 COMPLETED（公开 SessionStatus 亦为 COMPLETED）。
     *
     * NEEDS_CONFIRMATION 只允许直达 COMPLETED（手动确认入账后的路径）；
     * 其余状态经 AUTO_COMMITTED 两步推进，任一步非法时 advanceStatus 自动跳过。
     */
    private suspend fun advanceSessionCompleted(
        session: CheckoutSessionEntity,
        now: Long
    ): CheckoutSessionEntity {
        val stepped = advanceStatus(session, AUTO_COMMITTED_STATUS, now)
        return advanceStatus(stepped, COMPLETED_STATUS, now)
    }

    /**
     * 支付包名到种子资金账户的固定映射：微信支付对应"微信零钱"，支付宝对应"支付宝"。
     * 账户被删除或停用时返回 null，账单不指定账户也不更新余额，
     * 由用户在后续编辑中补充（T6 待确认路径不受影响）。
     */
    private suspend fun defaultAccountIdForPayment(paymentPackage: String?): String? {
        val accountId = when (paymentPackage) {
            "com.tencent.mm" -> WECHAT_PAY_ACCOUNT_ID
            "com.eg.android.AlipayGphone" -> ALIPAY_ACCOUNT_ID
            else -> return null
        }
        val account = database.accountDao().getById(accountId) ?: return null
        return if (account.isActive) accountId else null
    }

    /**
     * 阶段四 T6：从支付证据构建待确认交易。
     *
     * AI 尚未返回时仍传入 EVIDENCE_ONLY 候选 ID，保证候选、会话、待确认三者关联；
     * 商户缺失时按会话购物包名兜底。sourceType 继承证据来源；
     * 证据金额无效时返回 null（不生成待确认）。
     *
     * @param conflictReason 批 V-5 新增：落卡原因（如
     *   `PAYMENT_RESULT_AI_DISPATCH_UNAVAILABLE` / `AI_RESULT_MISSING_ON_STARTUP`）。
     *   旧调用面（启动补齐历史会话）保持 null —— 前端按码分档展示提示文案。
     */
    private suspend fun pendingFromEvidenceOnly(
        session: CheckoutSessionEntity,
        evidence: PaymentEvidenceEntity,
        now: Long,
        candidateId: String? = null,
        conflictReason: String? = null
    ): PendingTransactionEntity? {
        val amountMinor = evidence.amountMinor?.takeIf { it > 0L } ?: return null
        return PendingTransactionEntity(
            id = stableId("pending", session.id),
            candidateId = candidateId ?: session.candidateId,
            sessionId = session.id,
            amountMinor = amountMinor,
            type = "expense",
            occurredAt = evidence.occurredAt ?: now,
            // platform 展示购物平台；账户判定仍用支付包名（evidence.platform）。
            platform = shoppingPlatformName(session.shoppingPackage),
            merchant = evidence.merchant,
            productDescription = null,
            orderId = evidence.orderId,
            accountId = defaultAccountIdForPayment(evidence.platform),
            categoryId = null,
            conflictReason = conflictReason,
            sourceType = normalizePaymentEvidenceSourceType(evidence.sourceType) ?: "notification",
            createdAt = now,
            version = 1
        )
    }

    /**
     * 阶段四 T6：从冲突候选构建待确认交易。
     *
     * 金额/时间/订单号优先取支付证据，证据缺失时用候选字段补充；
     * conflictReason 必须填充供前端展示；sourceType 为 ai_vision（存在 AI 候选）。
     * 候选金额缺失且证据金额缺失时返回 null（无法构成合法待确认）。
     *
     * @param appliedRulesExtras 额外写入 appliedRules 的键值对
     *   （阶段五 T6 疑似重复提示等元数据），键值均为 String
     */
    private suspend fun pendingFromConflict(
        session: CheckoutSessionEntity,
        candidate: TransactionCandidateEntity,
        evidence: PaymentEvidenceEntity?,
        conflictReason: String?,
        now: Long,
        vararg appliedRulesExtras: Pair<String, String>
    ): PendingTransactionEntity? {
        val amountMinor = evidence?.amountMinor?.takeIf { it > 0L }
            ?: candidate.amountMinor?.takeIf { it > 0L }
            ?: return null
        val appliedRules = if (appliedRulesExtras.isEmpty()) {
            // 未显式传附加元数据时维持既有行为（空对象），不继承候选规则记录
            "{}"
        } else {
            mergeRulesJson("{}", *appliedRulesExtras)
        }
        return PendingTransactionEntity(
            id = stableId("pending", session.id),
            candidateId = candidate.id,
            sessionId = session.id,
            amountMinor = amountMinor,
            type = "expense",
            occurredAt = evidence?.occurredAt ?: candidate.aiOccurredAt ?: now,
            // platform 展示购物平台；账户判定仍用支付包名（evidence.platform）。
            platform = candidate.platform ?: shoppingPlatformName(session.shoppingPackage),
            merchant = candidate.merchant ?: evidence?.merchant,
            productDescription = candidate.productDescription,
            orderId = evidence?.orderId ?: candidate.aiOrderId,
            accountId = defaultAccountIdForPayment(evidence?.platform),
            categoryId = null,
            conflictReason = conflictReason ?: candidate.conflictReason,
            sourceType = "ai_vision",
            appliedRules = appliedRules,
            createdAt = now,
            version = 1
        )
    }

    /**
     * 阶段四 T6：按会话幂等落待确认交易。
     *
     * - 首次出现：插入（ID 由 sessionId 确定性生成，IGNORE 兜底并发）；
     * - 已存在且仍为 pending：内容有实质变化时更新并递增版本（如纯通知待确认被 AI 冲突结果增强）；
     * - 已被忽略：保留用户决策，不做任何改动。
     */
    private suspend fun upsertPendingForSession(pending: PendingTransactionEntity) {
        val dao = database.pendingTransactionDao()
        val existing = dao.getById(pending.id) ?: run {
            val inserted = dao.insertIfAbsent(pending)
            if (inserted != -1L) {
                PendingChangeHub.emit(pending.toChangeInfo(isNew = true))
            }
            return
        }
        if (existing.status != PendingTransactionEntity.STATUS_PENDING) {
            return
        }
        val normalizedExisting = existing.copy(version = 1, createdAt = 0L)
        val normalizedPending = pending.copy(version = 1, createdAt = 0L)
        if (normalizedExisting != normalizedPending) {
            dao.update(pending.copy(version = existing.version + 1))
            PendingChangeHub.emit(pending.toChangeInfo(isNew = false))
        }
    }

    /** 待确认实体转 hub 事件负载(不携带商户/订单号等敏感字段)。 */
    private fun PendingTransactionEntity.toChangeInfo(isNew: Boolean) = PendingChangeInfo(
        pendingId = id,
        type = type,
        amountMinor = amountMinor,
        sourceType = sourceType,
        conflictReason = conflictReason,
        occurredAt = occurredAt,
        isNew = isNew
    )

    /** 自动入账结果：推进后的会话与生成的账单 ID（幂等跳过时为 null 或已存在的 ID）。 */
    /** 成功重试后只删除 AI 失败占位，保留用户已经产生的其他待确认交易。 */
    private suspend fun removeAiFailurePending(sessionId: String) {
        val dao = database.pendingTransactionDao()
        val pending = dao.findPendingBySessionId(sessionId)
        if (pending?.conflictReason == AI_RECOGNITION_FAILED_REASON) {
            dao.delete(pending)
        }
    }

    private fun isRetryableAiFailureCandidate(candidate: TransactionCandidateEntity?): Boolean {
        if (candidate == null) {
            return false
        }
        if (candidate.conflictReason == AI_RECOGNITION_FAILED_REASON) {
            return true
        }
        return runCatching {
            JSONObject(candidate.appliedRules).optString("decision") == "AI_FAILED"
        }.getOrDefault(false)
    }

    /** 支付证据先于 AI 返回时创建的临时占位候选；后续 AI 结果必须覆盖而非按重复跳过。 */
    private fun isEvidenceOnlyProvisionalCandidate(candidate: TransactionCandidateEntity?): Boolean {
        if (candidate == null) {
            return false
        }
        return runCatching {
            JSONObject(candidate.appliedRules).optString("decision") == EVIDENCE_ONLY_DECISION
        }.getOrDefault(false)
    }

    private data class AutoCommitOutcome(
        val session: CheckoutSessionEntity,
        val billId: String?
    )

    private fun candidateAfterPayment(
        candidate: TransactionCandidateEntity,
        evidence: PaymentEvidenceEntity,
        evaluation: PaymentEvaluation
    ): TransactionCandidateEntity = candidate.copy(
        amountMinor = if (evaluation.conflictReason == null) {
            evidence.amountMinor
        } else {
            candidate.amountMinor
        },
        evidenceAmountMinor = evidence.amountMinor,
        evidenceMerchant = evidence.merchant,
        evidenceOrderId = evidence.orderId,
        matched = evaluation.conflictReason == null,
        conflictReason = evaluation.conflictReason,
        appliedRules = mergeRulesJson(
            candidate.appliedRules,
            "decision" to if (evaluation.conflictReason == null) "MATCHED" else "NEEDS_CONFIRMATION",
            "amountRule" to evaluation.amountRule,
            "orderRule" to evaluation.orderRule,
            "timeRule" to evaluation.timeRule,
            "paymentPackage" to evidence.platform
        ),
        version = candidate.version + 1
    )

    private fun evaluate(
        candidate: TransactionCandidateEntity,
        evidence: PaymentEvidenceEntity,
        aiOccurredAt: Long? = candidate.aiOccurredAt
    ): PaymentEvaluation {
        val conflicts = mutableListOf<String>()
        val amountRule = when {
            evidence.amountMinor == null || evidence.amountMinor <= 0L -> {
                conflicts += "PAYMENT_AMOUNT_MISSING"
                "INVALID"
            }
            candidate.amountMinor == null || candidate.amountMinor <= 0L -> {
                conflicts += "AI_AMOUNT_MISSING"
                "INVALID"
            }
            // D-19：双向有界容差 max(¥1.00, 支付金额 × 5%)。带内视为同一笔、以支付金额
            // 入账（见 candidateAfterPayment）；带外保留冲突原因 ⇒ 转待确认。
            // 此前为单边不等式（AI 金额更低才冲突、更高一律放行）⇒ AI 误读放大会静默入账。
            else -> when (classifyPaymentAmount(candidate.amountMinor, evidence.amountMinor)) {
                // ⚠️ 本字面值于 D-19（2026-09-16）由 AI_AMOUNT_COVERS_PAYMENT 改名而来：
                // 旧名只描述"AI 更高"单一方向，容差带内两方向都放行故失真。
                // 该值只进 appliedRules 诊断 JSON（无程序消费方）⇒ 历史记录中两者会混存，
                // 排查旧数据时需同时识别。
                PaymentAmountMatch.WITHIN_TOLERANCE -> "AI_AMOUNT_WITHIN_TOLERANCE"
                PaymentAmountMatch.AI_LOWER -> {
                    conflicts += "AI_AMOUNT_LOWER_THAN_PAYMENT"
                    "PAYMENT_EXCEEDS_AI"
                }
                PaymentAmountMatch.AI_HIGHER -> {
                    conflicts += "AI_AMOUNT_HIGHER_THAN_PAYMENT"
                    "AI_EXCEEDS_PAYMENT"
                }
            }
        }
        val orderRule = when {
            candidate.aiOrderId.isNullOrBlank() || evidence.orderId.isNullOrBlank() -> "ORDER_ID_PARTIAL"
            candidate.aiOrderId == evidence.orderId -> "ORDER_ID_EXACT"
            else -> {
                conflicts += "ORDER_ID_MISMATCH"
                "ORDER_ID_CONFLICT"
            }
        }
        val timeRule = when {
            // 审查修复：任一侧时间缺失时不能自动匹配——时间窗口是防误配的关键约束，
            // 缺时间走待确认由用户判断，不再输出 TIME_PARTIAL 放行
            evidence.occurredAt == null || aiOccurredAt == null -> {
                conflicts += "TIME_MISSING"
                "TIME_PARTIAL"
            }
            abs(evidence.occurredAt - aiOccurredAt) <= PAYMENT_MATCH_WINDOW_MILLIS -> "TIME_WITHIN_WINDOW"
            else -> {
                conflicts += "TIME_WINDOW_MISMATCH"
                "TIME_OUTSIDE_WINDOW"
            }
        }
        if (evidence.platform !in DEFAULT_SUPPORTED_PAYMENT_PACKAGES) {
            conflicts += "PAYMENT_PACKAGE_UNSUPPORTED"
        }
        return PaymentEvaluation(
            conflictReason = conflicts.takeIf { it.isNotEmpty() }?.joinToString(","),
            amountRule = amountRule,
            orderRule = orderRule,
            timeRule = timeRule
        )
    }

    private fun selectEvidence(
        aiOrderId: String?,
        evidences: List<PaymentEvidenceEntity>
    ): EvidenceSelection {
        if (evidences.isEmpty()) {
            return EvidenceSelection(null, "PAYMENT_EVIDENCE_MISSING")
        }
        if (!aiOrderId.isNullOrBlank()) {
            val exact = evidences.filter { it.orderId == aiOrderId }
            if (exact.isNotEmpty()) {
                return deduplicateEvidence(exact)
            }
            if (exact.size > 1) {
                // 不可达保留分支：同订单多证据的歧义判定已统一收敛到 deduplicateEvidence
            }
        }
        return deduplicateEvidence(evidences) ?: if (evidences.size == 1) {
            EvidenceSelection(evidences.single(), null)
        } else {
            EvidenceSelection(null, "PAYMENT_EVIDENCE_AMBIGUOUS")
        }
    }

    /**
     * 跨来源证据收敛：同一笔支付常同时产生"截图 AI 兜底"与"无障碍结果页"两条证据，
     * 两条路径的 sourceEventKey 生成规则不同，按主键查重挡不住，会在同一会话留下
     * 两条同额证据，被前端展示成"两个金额"。
     *
     * 规则：会话内已存在"同金额 +（订单号一致或均空）+ 时间窗内"的证据时，
     * 不再插入新行，而是把更全的字段（商户/订单号）合并回既有证据并提升
     * capturedAt；金额或订单号真正冲突时不收敛，照常插入，由候选评估或用户裁决。
     */
    private suspend fun mergeSessionEvidence(
        sessionId: String,
        incoming: PaymentEvidenceEntity,
        now: Long
    ): PaymentEvidenceEntity {
        val dao = database.paymentEvidenceDao()
        val similar = dao.getBySession(sessionId).firstOrNull { existing ->
            existing.amountMinor != null &&
                existing.amountMinor == incoming.amountMinor &&
                (existing.orderId.isNullOrBlank() && incoming.orderId.isNullOrBlank() ||
                    existing.orderId == incoming.orderId) &&
                existing.occurredAt != null &&
                incoming.occurredAt != null &&
                abs(incoming.occurredAt - existing.occurredAt) <=
                    PAYMENT_EVIDENCE_MERGE_WINDOW_MILLIS
        } ?: run {
            dao.insertIfAbsent(incoming)
            return dao.getById(incoming.id) ?: incoming
        }
        val merged = similar.copy(
            merchant = similar.merchant ?: incoming.merchant,
            orderId = similar.orderId ?: incoming.orderId,
            sourceType = incoming.sourceType,
            capturedAt = now
        )
        if (merged != similar) {
            dao.delete(similar)
            dao.insert(merged)
        }
        JotBillLog.i(
            "CheckoutPaymentMatcher",
            "支付证据跨来源收敛: session=${sessionId.take(8)}, amount=${merged.amountMinor}, " +
                "sources=${similar.sourceType}+${incoming.sourceType}, evidenceId=${merged.id.take(12)}"
        )
        return merged
    }

    /**
     * 收敛同一笔支付可能产生的多条证据（通知监听 + 支付结果页）。
     *
     * 一致证据只选 capturedAt 最新的一条参与匹配，避免把“同一次支付的两个来源”
     * 误判为 PAYMENT_EVIDENCE_AMBIGUOUS；只有金额/订单号/平台真正冲突时才保留歧义。
     * 无订单号时仅允许 60 秒内的同金额证据收敛，避免把连续两笔支付自动合并。
     */
    private fun deduplicateEvidence(
        evidences: List<PaymentEvidenceEntity>
    ): EvidenceSelection {
        if (evidences.isEmpty()) {
            return EvidenceSelection(null, "PAYMENT_EVIDENCE_MISSING")
        }
        if (evidences.size == 1) {
            return EvidenceSelection(evidences.single(), null)
        }
        val representative = evidences.maxByOrNull { it.capturedAt }
            ?: return EvidenceSelection(null, "PAYMENT_EVIDENCE_AMBIGUOUS")
        val representativeOrderId = representative.orderId
        val representativeAmount = representative.amountMinor
        val representativeOccurredAt = representative.occurredAt
        val compatible = evidences.all { evidence ->
            val samePlatform = evidence.platform == representative.platform
            val sameAmount = evidence.amountMinor != null &&
                evidence.amountMinor == representativeAmount
            val samePayment = if (!representativeOrderId.isNullOrBlank()) {
                evidence.orderId == representativeOrderId
            } else {
                evidence.orderId.isNullOrBlank() &&
                    representativeOccurredAt != null &&
                    evidence.occurredAt != null &&
                    abs((evidence.occurredAt ?: 0L) - (representativeOccurredAt ?: 0L)) <= 60_000L
            }
            samePlatform && sameAmount && samePayment
        }
        return if (compatible) {
            EvidenceSelection(representative, null)
        } else {
            EvidenceSelection(null, "PAYMENT_EVIDENCE_AMBIGUOUS")
        }
    }


    private fun validatePayment(
        reference: CheckoutSnapshotReference,
        signal: PaymentNotificationFallbackSignal
    ): String? {
        if (
            reference.sessionId.isBlank() ||
            reference.shoppingPackage.isBlank() ||
            reference.pageFingerprint.isBlank() ||
            reference.pageRole != CheckoutPageRole.CHECKOUT
        ) {
            return "CHECKOUT_REFERENCE_INVALID"
        }
        if (!signal.isValid() || signal.paymentPackage !in DEFAULT_SUPPORTED_PAYMENT_PACKAGES) {
            return "PAYMENT_SIGNAL_INVALID"
        }
        if (reference.capturedAt <= 0L ||
            abs(signal.occurredAt - reference.capturedAt) > PAYMENT_MATCH_WINDOW_MILLIS
        ) {
            return "PAYMENT_TIME_OUTSIDE_WINDOW"
        }
        return null
    }

    private fun sourceEventKey(signal: PaymentNotificationFallbackSignal): String =
        signal.sourceEventKey?.takeIf { it.isNotBlank() }
            ?: stableId(
                "payment-source-event",
                signal.paymentPackage,
                signal.amountMinor.toString(),
                signal.occurredAt.toString(),
                signal.merchant,
                signal.orderId
            )

    private fun sessionExpiresAt(reference: CheckoutSnapshotReference): Long = maxOf(
        reference.expiresAt,
        reference.capturedAt + PAYMENT_MATCH_WINDOW_MILLIS
    )

    private fun candidateIdFor(sessionId: String): String = stableId("candidate", sessionId)

    private fun parseTimestamp(value: String): Long? = runCatching {
        Instant.parse(value).toEpochMilli()
    }.getOrNull()

    private fun stableId(prefix: String, vararg values: String?): String {
        val payload = values.joinToString("|") { it.orEmpty() }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$prefix|$payload".toByteArray(StandardCharsets.UTF_8))
        return prefix + "-" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun rulesJson(vararg values: Pair<String, Any?>): String = JSONObject().apply {
        values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }.toString()

    /**
     * 阶段五 T1 时机一：AI 候选生成时执行规则引擎。
     *
     * - Continue：set_platform/set_merchant 覆盖候选展示字段（用户确认的规则优先级
     *   高于 AI 视觉识别），set_category/set_account 记录命中，待入账/待确认时应用；
     * - Ignored：候选标记 decision=IGNORED，后续证据到达时不再入账或转待确认，审计留痕；
     * - ToReview：候选标记 decision=FORCED_REVIEW，证据匹配后由时机二强制转待确认。
     *
     * 命中摘要合并进 appliedRules（保留既有元数据键），命中计数由引擎在事务内累加。
     */
    private suspend fun applyRulesAtCandidate(
        candidate: TransactionCandidateEntity
    ): TransactionCandidateEntity {
        val decision = ruleEngine.evaluateAndRecord(
            RuleEngine.Subject(
                amountMinor = candidate.amountMinor,
                platform = candidate.platform,
                merchant = candidate.merchant,
                description = candidate.productDescription,
                sourceType = "ai_vision"
            )
        )
        return when (decision) {
            is RuleEngine.Decision.Continue -> {
                if (decision.hits.isEmpty()) {
                    candidate
                } else {
                    candidate.copy(
                        platform = decision.patch.platform ?: candidate.platform,
                        merchant = decision.patch.merchant ?: candidate.merchant,
                        appliedRules = ruleEngine.mergeAppliedRules(
                            candidate.appliedRules,
                            decision.hits
                        )
                    )
                }
            }
            is RuleEngine.Decision.Ignored -> {
                recordRuleDecisionAudit(candidate.id, "ignore", decision.hits)
                candidate.copy(
                    appliedRules = ruleEngine.mergeAppliedRules(
                        candidate.appliedRules,
                        decision.hits,
                        decisionOverride = RuleEngine.DECISION_IGNORED
                    )
                )
            }
            is RuleEngine.Decision.ToReview -> {
                candidate.copy(
                    appliedRules = ruleEngine.mergeAppliedRules(
                        candidate.appliedRules,
                        decision.hits,
                        decisionOverride = RuleEngine.DECISION_FORCED_REVIEW
                    )
                )
            }
        }
    }

    /**
     * 规则 ignore 决策审计：确定性 ID 幂等插入，保留命中规则清单供追溯，
     * 不写 Bill、不更新余额、不进待确认。
     */
    private suspend fun recordRuleDecisionAudit(
        entityId: String,
        action: String,
        hits: List<RuleEngine.RuleHit>
    ) {
        database.auditEntryDao().insertIfAbsent(
            AuditEntryEntity(
                id = "rule-$action:$entityId",
                entityType = "rule_decision",
                entityId = entityId,
                action = action,
                fieldName = null,
                oldValue = null,
                newValue = org.json.JSONArray().apply {
                    hits.forEach { hit ->
                        put("${hit.ruleId}:${hit.action}:${hit.actionValue ?: ""}")
                    }
                }.toString(),
                operator = "rule_engine",
                createdAt = nowMillis()
            )
        )
    }

    /** 在既有 appliedRules JSON 对象上合并决策元数据键，保留规则命中键（rule:*）。 */
    private fun mergeRulesJson(base: String, vararg values: Pair<String, Any?>): String {
        val merged = runCatching { JSONObject(base) }.getOrDefault(JSONObject())
        values.forEach { (key, value) -> merged.put(key, value ?: JSONObject.NULL) }
        return merged.toString()
    }

    private data class PaymentEvaluation(
        val conflictReason: String?,
        val amountRule: String,
        val orderRule: String,
        val timeRule: String
    )

    private data class EvidenceSelection(
        val evidence: PaymentEvidenceEntity?,
        val conflictReason: String?
    )
}

private fun CheckoutSessionEntity.isClosedOrExpired(now: Long): Boolean =
    expiresAt <= now ||
        (
            status in TERMINAL_SESSION_STATUSES &&
                journeyState != CheckoutJourneyState.AI_FAILED.wireValue
        )

/** 孤儿冲突原因合并后允许的最大段数（防异常路径反复追加）。 */
internal const val MAX_MERGED_ORPHAN_REASONS = 3

/**
 * 同一笔支付的孤儿冲突原因合并（批 V-4 处置）。
 *
 * **背景**：改造 A-3 接线后，同一笔支付可能经两条路径各落一次孤儿 ——
 * 服务层的证据落库路径（`NO_ACTIVE_SESSION` 等）与协调器的归属出口路径
 * （`PAYMENT_RESULT_NO_MATCHING_SESSION` / `PAYMENT_PROCESS_SNAPSHOT_MISSING`）。
 * 两者共用 `stableId("pending-orphan", sourceEventKey)` ⇒ 第二条被幂等跳过。
 * **直接丢弃后到原因会让 `PAYMENT_PROCESS_SNAPSHOT_MISSING`（"搬运漏检率"的
 * 量化锚点，R2 目标）消失**，故改为合并。
 *
 * **口径**：
 * - 逗号分隔多码 —— 与 RN 侧 `translateConflictReason` 的 `split(',')` 展示逻辑一致；
 * - **既有码在前**、新码追加在后 —— D-21 的 `isUserAssertedConflictReason` 依赖
 *   `startsWith` 前缀语义，顺序颠倒会把手动断言的冲突误判为普通冲突；
 * - 最多 [MAX_MERGED_ORPHAN_REASONS] 段；
 * - 已含同一码（按段精确匹配）时保持原值 ⇒ 幂等。
 */
internal fun mergeOrphanConflictReason(existing: String?, incoming: String?): String {
    val current = existing?.trim().orEmpty()
    val next = incoming?.trim().orEmpty()
    if (next.isEmpty()) return current
    if (current.isEmpty()) return next
    if (current == next) return current
    val codes = current.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (next in codes) return current
    if (codes.size >= MAX_MERGED_ORPHAN_REASONS) return current
    return (codes + next).joinToString(",")
}

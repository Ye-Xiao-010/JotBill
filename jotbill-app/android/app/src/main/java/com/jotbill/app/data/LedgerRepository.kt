package com.jotbill.app.data

import androidx.room.withTransaction
import com.jotbill.app.data.entity.AuditEntryEntity
import com.jotbill.app.data.entity.BalanceCalibrationEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.CheckoutSessionEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import com.jotbill.app.data.entity.TransactionCandidateEntity
import com.jotbill.app.pending.PendingChangeHub
import com.jotbill.app.pending.PendingChangeInfo
import com.jotbill.app.rules.DuplicateGuard
import com.jotbill.app.rules.FieldPriorityResolver
import org.json.JSONObject
import java.util.UUID

internal const val MAX_SAFE_MINOR_UNIT = 9_007_199_254_740_991L

/**
 * 阶段五 T6：带去重的账单创建结果。
 *
 * - [Committed]：未命中去重，已插入新账单并联动余额；
 * - [MergedInto]：命中去重第 1 级（同 (sourceType, orderId) 已入账），
 *   新证据可变字段已合并进既有账单，未产生第二笔；bill 为合并后的最新账单。
 */
sealed class CreateBillOutcome {
    data class Committed(val bill: BillEntity) : CreateBillOutcome()
    data class MergedInto(val bill: BillEntity) : CreateBillOutcome()
}

/**
 * 阶段五 T6：待确认确认入账结果。
 *
 * @param success 是否成功（版本/状态校验失败为 false，事务无写入）
 * @param committed 入账的新账单（正常确认路径）；合并路径为 null
 * @param mergedInto 第 1 级命中的合并目标账单（合并确认路径）；正常路径为 null
 */
data class ConfirmResult(
    val success: Boolean,
    val committed: BillEntity?,
    val mergedInto: BillEntity?
)

/**
 * 阶段五 T2/T3：待确认交易被用户确认入账后的审计墓碑类型。
 * RefundLinker/TransferClassifier 以 (该类型, pendingId) 查询，识别已确认的事件，
 * 防止待确认被物理删除后重复通知再次挂账（所有类型的待确认确认均写入）。
 */
const val PENDING_CONFIRM_AUDIT_TYPE = "pending_confirm"

internal class LedgerValidationException(
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)

private val TERMINAL_SESSION_STATUSES = setOf("COMPLETED", "CANCELLED", "FAILED", "EXPIRED")

/**
 * 负责账单、账户余额及余额校准的本地事务。
 */
class LedgerRepository(private val database: JotBillDatabase) {
    /**
     * 创建账单并按交易类型联动更新账户余额。
     *
     * 阶段五 T6 起内部委托 [createBillWithDedup]：写入前执行去重第 1 级查重，
     * 命中时合并进既有账单（不产生第二笔）。保持 Unit 签名兼容既有调用方；
     * 需要去重结果的调用方（自动入账/桥接层）请直接使用 [createBillWithDedup]。
     *
     * @param bill 待写入的账单实体
     * @param operator 审计操作者标记：手动路径为 "system"，阶段四 T5 自动入账为 "auto_commit"
     */
    suspend fun createBillWithBalanceUpdate(
        bill: BillEntity,
        operator: String = "system"
    ): CreateBillOutcome = createBillWithDedup(bill, operator)

    /**
     * 阶段五 T6：带去重的账单创建。
     *
     * 写入前执行去重第 1 级（(sourceType, orderId) 精确查重，退款豁免）：
     * - 未命中：插入新账单并联动余额（与既有行为一致），返回 [CreateBillOutcome.Committed]；
     * - 命中：不产生第二笔，新证据字段按 EVIDENCE 级经 T5 合并语义补进原账单
     *   （只补 null 字段、锁定字段保护），返回 [CreateBillOutcome.MergedInto]。
     *
     * @param bill 待写入的账单实体
     * @param operator 审计操作者标记
     */
    suspend fun createBillWithDedup(
        bill: BillEntity,
        operator: String = "system"
    ): CreateBillOutcome {
        validateSafeAmount(bill.amountMinor)
        // 阶段五 T5：锁定字段名必须在合并器可锁字段清单内，防止脏数据落库
        val invalidLocks = FieldPriorityResolver.invalidLockFields(
            FieldPriorityResolver.parseLocks(bill.fieldLocks)
        )
        require(invalidLocks.isEmpty()) {
            "Unknown lockable fields: ${invalidLocks.joinToString()}"
        }
        val now = System.currentTimeMillis()
        return database.withTransaction {
            // adjustment 是**校准**账单：`BalanceCalculator.delta` 对它返回 0，
            // 即写入后余额分毫不动。若允许它经本路径落库，调用方却会拿到
            // Committed 并按"已入账"上报（转账/退款自动入账链路即如此），
            // 用户看到"保存成功"却找不到任何余额变化。
            // update / delete 路径已有 requireMutableBill，本路径此前是缺口 ——
            // 漏了它就等于"能创建、不能改、不能删"的幽灵账单。口径统一在此。
            requireMutableBill(bill)
            // ---- 去重第 1 级：(sourceType, orderId) 精确查重（退款豁免）----
            val duplicate = DuplicateGuard(database).checkLevel1(
                sourceType = bill.sourceType,
                orderId = bill.orderId,
                type = bill.type,
                excludeId = bill.id
            )
            if (duplicate != null) {
                return@withTransaction mergeIntoExistingBill(duplicate, bill, now)
            }

            validateBillAccounts(bill, requireActiveAccounts = true)
            database.billDao().insert(bill)
            applyBalanceDelta(
                bill,
                multiplier = 1L,
                updatedAt = now,
                requireActiveAccounts = true
            )

            database.auditEntryDao().insert(
                AuditEntryEntity(
                    id = bill.id + ":create",
                    entityType = "bill",
                    entityId = bill.id,
                    action = "create",
                    fieldName = null,
                    oldValue = null,
                    newValue = billAuditValue(bill),
                    operator = operator,
                    createdAt = now
                )
            )
            CreateBillOutcome.Committed(bill)
        }
    }

    /**
     * 第 1 级命中后的合并写入：新证据可变字段只补原账单空缺（同 EVIDENCE 级
     * 不覆盖非空值，符合 T5 合并器语义），金额/时间/账户/类型不改动；
     * 锁定字段保护由 updateBillWithBalanceUpdate 内部执行。
     * 原账单余额已入账，合并只做字段补全（金额不变，余额重算净变化为零）。
     */
    private suspend fun mergeIntoExistingBill(
        existing: BillEntity,
        incoming: BillEntity,
        now: Long
    ): CreateBillOutcome {
        val merged = existing.copy(
            description = existing.description ?: incoming.description,
            platform = existing.platform ?: incoming.platform,
            merchant = existing.merchant ?: incoming.merchant,
            productDescription = existing.productDescription ?: incoming.productDescription,
            updatedAt = now,
            version = existing.version + 1
        )
        val updated = updateBillWithBalanceUpdate(
            existing,
            merged,
            fieldUpdateSource = FieldPriorityResolver.Source.EVIDENCE
        )
        if (!updated) {
            // 版本并发变化：抛出让外层事务回滚，由调用方决定转待确认或重试
            throw LedgerValidationException("Duplicate bill has changed during merge")
        }
        database.auditEntryDao().insertIfAbsent(
            AuditEntryEntity(
                id = "${existing.id}:dedup-merge:${merged.version}",
                entityType = "dedup_merge",
                entityId = existing.id,
                action = "merge",
                fieldName = null,
                oldValue = billAuditValue(existing),
                newValue = billAuditValue(merged),
                operator = "duplicate_guard",
                createdAt = now
            )
        )
        return CreateBillOutcome.MergedInto(merged)
    }

    /**
     * 更新账单并联动修正账户余额（版本化乐观锁）。
     *
     * 阶段五 T5：入账前经字段优先级合并器校验——非用户来源（规则/证据/AI/会话/默认）
     * 不得改动用户锁定字段（fieldLocks 命中），被拒字段回退为既有值并写入
     * field_lock_conflict 审计提示，不静默覆写；用户来源（默认）不受限制。
     *
     * @param existing 调用方读取的既有账单（版本基准）
     * @param updated 拟写入的更新账单（version = existing.version + 1）
     * @param fieldUpdateSource 本次字段更新来源级别；默认用户编辑（可改锁定字段本身）
     */
    suspend fun updateBillWithBalanceUpdate(
        existing: BillEntity,
        updated: BillEntity,
        fieldUpdateSource: FieldPriorityResolver.Source = FieldPriorityResolver.Source.USER_LOCK
    ): Boolean {
        require(existing.id == updated.id) {
            "Bill ids must match for update"
        }
        requireMutableBill(existing)
        requireMutableBill(updated)
        validateSafeAmount(existing.amountMinor)
        validateSafeAmount(updated.amountMinor)
        require(updated.version == existing.version + 1) {
            "Bill version must increase by one"
        }
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val current = database.billDao().getById(existing.id)
                ?: return@withTransaction false
            requireMutableBill(current)
            if (current.version != existing.version) {
                return@withTransaction false
            }
            // 阶段五 T5：锁定字段保护（以事务内 current 为基准，防止并发期间锁定变化）
            val (protected, lockConflicts) = FieldPriorityResolver.protectLockedUpdate(
                current, updated, fieldUpdateSource
            )
            validateBillAccounts(protected, requireActiveAccounts = true)
            val updatedRows = database.billDao().updateIfVersion(
                id = protected.id,
                expectedVersion = current.version,
                amountMinor = protected.amountMinor,
                type = protected.type,
                occurredAt = protected.occurredAt,
                description = protected.description,
                categoryId = protected.categoryId,
                accountId = protected.accountId,
                targetAccountId = protected.targetAccountId,
                platform = protected.platform,
                merchant = protected.merchant,
                productDescription = protected.productDescription,
                sourceType = protected.sourceType,
                originalTransactionId = protected.originalTransactionId,
                orderId = protected.orderId,
                fieldLocks = protected.fieldLocks,
                updatedAt = protected.updatedAt,
                newVersion = protected.version
            )
            if (updatedRows != 1) {
                return@withTransaction false
            }
            applyBalanceDelta(current, multiplier = -1L, updatedAt = now)
            applyBalanceDelta(protected, multiplier = 1L, updatedAt = now)
            database.auditEntryDao().insert(
                AuditEntryEntity(
                    id = "${protected.id}:update:${protected.version}",
                    entityType = "bill",
                    entityId = protected.id,
                    action = "update",
                    fieldName = null,
                    oldValue = billAuditValue(current),
                    newValue = billAuditValue(protected),
                    operator = "system",
                    createdAt = now
                )
            )
            // 锁定冲突提示：逐字段留痕（供待确认展示与审计追溯），不阻塞本次更新
            lockConflicts.forEach { conflict ->
                database.auditEntryDao().insert(
                    AuditEntryEntity(
                        id = "${protected.id}:lock-conflict:${protected.version}:${conflict.field}",
                        entityType = "field_lock_conflict",
                        entityId = protected.id,
                        action = "reject",
                        fieldName = conflict.field,
                        oldValue = conflict.rejectedValue?.toString(),
                        newValue = conflict.keptValue?.toString(),
                        operator = "field_priority_resolver",
                        createdAt = now
                    )
                )
            }
            true
        }
    }

    suspend fun deleteBillWithBalanceUpdate(bill: BillEntity): Boolean {
        requireMutableBill(bill)
        validateSafeAmount(bill.amountMinor)
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val current = database.billDao().getById(bill.id)
                ?: return@withTransaction false
            requireMutableBill(current)
            if (database.billDao().deleteById(current.id) != 1) {
                return@withTransaction false
            }
            applyBalanceDelta(current, multiplier = -1L, updatedAt = now)
            database.auditEntryDao().insert(
                AuditEntryEntity(
                    id = "${current.id}:delete",
                    entityType = "bill",
                    entityId = current.id,
                    action = "delete",
                    fieldName = null,
                    oldValue = billAuditValue(current),
                    newValue = null,
                    operator = "system",
                    createdAt = now
                )
            )
            true
        }
    }

    /**
     * 在同一 Room 事务内完成待确认交易的确认、入账和全部关联终态更新。
     *
     * 事务内重新读取 pending 并校验版本、账户和关联关系；任何一步失败都会整体回滚。
     *
     * 阶段五 T6：
     * - 确认入账前执行去重第 1 级（(sourceType, orderId)，退款豁免）——命中时
     *   不产生第二笔，新账单可变字段合并进既有账单，其余流转（候选收口、
     *   会话终态、pending 删除、确认墓碑）照常完成；
     * - 确认入账前执行第 4 级疑似重复检查——命中只写审计提示
     *   （suspected_duplicate），不阻塞确认（用户可让两笔并存）。
     */
    suspend fun confirmPendingTransaction(
        pending: PendingTransactionEntity,
        bill: BillEntity
    ): ConfirmResult {
        val now = System.currentTimeMillis()
        val result = database.withTransaction {
            val currentPending = database.pendingTransactionDao().getById(pending.id)
                ?: return@withTransaction ConfirmResult(false, null, null)
            if (
                currentPending.status != PendingTransactionEntity.STATUS_PENDING ||
                currentPending.version != pending.version
            ) {
                return@withTransaction ConfirmResult(false, null, null)
            }

            // 阶段五 T2：退款/手续费待确认确认入账时回填原交易关联（Bill.originalTransactionId），
            // 保证多次部分退款的累计口径（sumRefundsByOriginalId）覆盖用户确认路径；
            // 手续费同理（FeeExtractor 写入的 originalBillId 指向主交易）。
            // 用户从编辑面板手动选择原交易时，桥接层已透传 originalTransactionId 优先，
            // 此处仅对"未手动选择"的回填自动关联的 originalBillId。
            // 退款判定以用户提交的账单类型为准：支出型待确认被用户改为退款并手动
            // 关联原交易时（按 pending 原类型判定会误抛 immutable），同样走原交易绑定路径。
            val isRefund = bill.type == "refund"
            val backfillsOriginal = (isRefund || bill.type == "fee") &&
                currentPending.originalBillId != null && bill.originalTransactionId == null
            val effectiveBill = if (backfillsOriginal) {
                bill.copy(originalTransactionId = currentPending.originalBillId)
            } else {
                bill
            }

            // 来源绑定不可改写：会话型待确认绑结算会话（幂等边界）；退款/手续费绑原交易
            // （自动关联 originalBillId 或用户手动选择 originalTransactionId 均可，
            // 用户手动选择优先级最高，见 DECISIONS 用户操作优先原则）。
            val bindsOriginal = isRefund || bill.type == "fee"
            if (!bindsOriginal && effectiveBill.originalTransactionId != currentPending.sessionId) {
                throw LedgerValidationException("Pending transaction source binding is immutable")
            }

            // 累计退款不得超过原交易金额（与 RefundLinker 自动入账路径同口径）：
            // 只在自动入账路径校验会漏掉确认路径——如两笔同原交易退款，首笔尚在
            // 待确认时第二笔已自动入账（sum=0），再确认首笔即累计 2×原金额。
            if (isRefund) {
                val originalId = effectiveBill.originalTransactionId
                if (originalId != null) {
                    val original = database.billDao().getById(originalId)
                    val refundedBefore = original
                        ?.let { database.billDao().sumRefundsByOriginalId(it.id) }
                        ?: 0L
                    if (original != null &&
                        refundedBefore + effectiveBill.amountMinor > original.amountMinor
                    ) {
                        throw LedgerValidationException("Refund exceeds original transaction amount")
                    }
                }
            }
            validateConfirmationBill(effectiveBill)
            // 与 createBillWithDedup 同口径：adjustment 不允许经确认路径落库。
            // 否则余额不动（delta=0）+ 待确认被物理删除 = 用户点"确认入账"
            // 却凭空丢了一笔记录，且该 adjustment 账单被 requireMutableBill
            // 锁死、无法编辑或删除补救。
            requireMutableBill(effectiveBill)
            val relations = loadPendingRelations(currentPending)
            if (relations.session?.let { it.status in TERMINAL_SESSION_STATUSES } == true) {
                throw LedgerValidationException("Checkout session is already terminal")
            }
            if (currentPending.sessionId != null &&
                database.billDao().findByOriginalTransactionId(currentPending.sessionId) != null
            ) {
                throw LedgerValidationException("Checkout session has already been committed")
            }

            // ---- 阶段五 T6 去重第 1 级：确认入账前查重（退款豁免）----
            val guard = DuplicateGuard(database)
            val duplicate = guard.checkLevel1(
                sourceType = effectiveBill.sourceType,
                orderId = effectiveBill.orderId,
                type = effectiveBill.type,
                excludeId = effectiveBill.id
            )
            val outcome: CreateBillOutcome = if (duplicate != null) {
                mergeIntoExistingBill(duplicate, effectiveBill, now)
            } else {
                database.billDao().insert(effectiveBill)
                applyBalanceDelta(
                    effectiveBill,
                    multiplier = 1L,
                    updatedAt = now,
                    requireActiveAccounts = true
                )
                CreateBillOutcome.Committed(effectiveBill)
            }
            // 落库账单（新建或合并目标）作为候选收口/审计的关联对象
            val settledBill = when (outcome) {
                is CreateBillOutcome.Committed -> outcome.bill
                is CreateBillOutcome.MergedInto -> outcome.bill
            }

            // ---- 阶段五 T6 去重第 4 级：确认入账前疑似重复提示（只提示不阻塞）----
            val similarHits = guard.findSimilar(
                type = effectiveBill.type,
                amountMinor = effectiveBill.amountMinor,
                occurredAt = effectiveBill.occurredAt,
                accountId = effectiveBill.accountId,
                merchant = effectiveBill.merchant,
                excludeId = settledBill.id
            )
            if (similarHits.isNotEmpty()) {
                database.auditEntryDao().insertIfAbsent(
                    AuditEntryEntity(
                        id = "suspected-duplicate:${currentPending.id}",
                        entityType = DuplicateGuard.AUDIT_SUSPECTED_DUPLICATE,
                        entityId = currentPending.id,
                        action = "hint",
                        fieldName = null,
                        oldValue = null,
                        newValue = guard.suspectedDuplicateHint(similarHits),
                        operator = "duplicate_guard",
                        createdAt = now
                    )
                )
            }

            relations.candidate?.let { candidate ->
                val resolved = database.transactionCandidateDao().resolveIfVersion(
                    id = candidate.id,
                    sessionId = candidate.sessionId,
                    expectedVersion = candidate.version,
                    matched = true,
                    conflictReason = null,
                    appliedRules = decisionRules(candidate.appliedRules, "USER_CONFIRMED", settledBill.id)
                )
                if (resolved != 1) {
                    throw LedgerValidationException("Transaction candidate has changed")
                }
            }
            relations.session?.let { session ->
                if (database.checkoutSessionDao().updateTerminalStateIfActive(
                        id = session.id,
                        status = "COMPLETED",
                        journeyState = "COMPLETED",
                        updatedAt = now
                    ) != 1
                ) {
                    throw LedgerValidationException("Checkout session has changed")
                }
            }
            if (database.pendingTransactionDao()
                    .deleteByIdIfVersion(currentPending.id, currentPending.version) != 1
            ) {
                throw LedgerValidationException("Pending transaction has changed")
            }
            // 阶段五 T2/T3：待确认确认后写审计墓碑（所有类型），防止同一事件的重复通知
            // 在待确认被物理删除后再次挂账（识别链路按 (类型, pendingId) 查询）
            database.auditEntryDao().insertIfAbsent(
                AuditEntryEntity(
                    id = "pending-confirm:${currentPending.id}",
                    entityType = PENDING_CONFIRM_AUDIT_TYPE,
                    entityId = currentPending.id,
                    action = "confirm",
                    fieldName = null,
                    oldValue = null,
                    newValue = billAuditValue(settledBill),
                    operator = "system",
                    createdAt = now
                )
            )
            if (outcome is CreateBillOutcome.Committed) {
                database.auditEntryDao().insert(
                    AuditEntryEntity(
                        id = "${settledBill.id}:create",
                        entityType = "bill",
                        entityId = settledBill.id,
                        action = "create",
                        fieldName = null,
                        oldValue = null,
                        newValue = billAuditValue(settledBill),
                        operator = "system",
                        createdAt = now
                    )
                )
                ConfirmResult(true, committed = settledBill, mergedInto = null)
            } else {
                ConfirmResult(true, committed = null, mergedInto = settledBill)
            }
        }
        // 事务提交后广播终态事件：提醒消费方消除该待确认的系统通知
        // (pending 物理删除,用入参的 pending 携带通知所需的字段)
        PendingChangeHub.emit(
            PendingChangeInfo(
                pendingId = pending.id,
                type = pending.type,
                amountMinor = pending.amountMinor,
                sourceType = pending.sourceType,
                conflictReason = null,
                occurredAt = pending.occurredAt,
                isNew = false,
                removed = true
            )
        )
        return result
    }

    /** 在同一事务内标记忽略、收口候选并关闭其会话。 */
    suspend fun ignorePendingTransaction(
        pending: PendingTransactionEntity,
        expectedVersion: Int
    ): Boolean {
        val now = System.currentTimeMillis()
        val result = database.withTransaction {
            val currentPending = database.pendingTransactionDao().getById(pending.id)
                ?: return@withTransaction false
            if (
                currentPending.status != PendingTransactionEntity.STATUS_PENDING ||
                currentPending.version != expectedVersion
            ) {
                return@withTransaction false
            }
            val relations = loadPendingRelations(currentPending)
            if (database.pendingTransactionDao()
                    .markIgnoredIfVersion(currentPending.id, currentPending.version) != 1
            ) {
                return@withTransaction false
            }
            relations.candidate?.let { candidate ->
                val resolved = database.transactionCandidateDao().resolveIfVersion(
                    id = candidate.id,
                    sessionId = candidate.sessionId,
                    expectedVersion = candidate.version,
                    matched = false,
                    conflictReason = "USER_IGNORED",
                    appliedRules = decisionRules(candidate.appliedRules, "USER_IGNORED")
                )
                if (resolved != 1) {
                    throw LedgerValidationException("Transaction candidate has changed")
                }
            }
            relations.session?.let { session ->
                if (session.status !in TERMINAL_SESSION_STATUSES &&
                    database.checkoutSessionDao().updateTerminalStateIfActive(
                        id = session.id,
                        status = "CANCELLED",
                        journeyState = "DISMISSED",
                        updatedAt = now
                    ) != 1
                ) {
                    throw LedgerValidationException("Checkout session has changed")
                }
            }
            true
        }
        if (result) {
            // 忽略同样是终态：让提醒消费方消除该待确认的系统通知
            PendingChangeHub.emit(
                PendingChangeInfo(
                    pendingId = pending.id,
                    type = pending.type,
                    amountMinor = pending.amountMinor,
                    sourceType = pending.sourceType,
                    conflictReason = null,
                    occurredAt = pending.occurredAt,
                    isNew = false,
                    removed = true
                )
            )
        }
        return result
    }

    suspend fun calibrateAccountBalance(
        accountId: String,
        actualBalanceMinor: Long,
        note: String?
    ): BalanceCalibrationEntity {
        if (actualBalanceMinor !in -MAX_SAFE_MINOR_UNIT..MAX_SAFE_MINOR_UNIT) {
            throw LedgerValidationException("Balance must be a safe integer minor unit")
        }
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val account = requireNotNull(database.accountDao().getById(accountId)) {
                "Account $accountId does not exist"
            }
            val diffMinor = try {
                Math.subtractExact(actualBalanceMinor, account.currentBalanceMinor)
            } catch (error: ArithmeticException) {
                throw LedgerValidationException("Balance calibration difference is out of range", error)
            }
            val calibration = BalanceCalibrationEntity(
                id = UUID.randomUUID().toString(),
                accountId = account.id,
                beforeBalanceMinor = account.currentBalanceMinor,
                afterBalanceMinor = actualBalanceMinor,
                diffMinor = diffMinor,
                note = note?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = now
            )
            val adjustmentAmountMinor = try {
                if (diffMinor < 0L) Math.negateExact(diffMinor) else diffMinor
            } catch (error: ArithmeticException) {
                throw LedgerValidationException("Balance adjustment is out of range", error)
            }
            validateSafeAmount(adjustmentAmountMinor)
            val adjustmentBill = BillEntity(
                id = calibration.id,
                amountMinor = adjustmentAmountMinor,
                type = "adjustment",
                occurredAt = now,
                description = calibration.note ?: "余额校准",
                categoryId = null,
                accountId = account.id,
                targetAccountId = null,
                platform = null,
                merchant = null,
                productDescription = null,
                sourceType = "manual",
                originalTransactionId = null,
                orderId = null,
                createdAt = now,
                updatedAt = now,
                version = 1
            )

            database.billDao().insert(adjustmentBill)
            database.accountDao().update(
                account.copy(
                    currentBalanceMinor = actualBalanceMinor,
                    updatedAt = now
                )
            )
            database.balanceCalibrationDao().insert(calibration)
            database.auditEntryDao().insert(
                AuditEntryEntity(
                    id = "${calibration.id}:adjustment",
                    entityType = "balance_calibration",
                    entityId = calibration.id,
                    action = "adjustment",
                    fieldName = "current_balance_minor",
                    oldValue = account.currentBalanceMinor.toString(),
                    newValue = JSONObject().apply {
                        put("accountId", account.id)
                        put("beforeBalanceMinor", calibration.beforeBalanceMinor)
                        put("afterBalanceMinor", calibration.afterBalanceMinor)
                        put("diffMinor", calibration.diffMinor)
                        put("adjustmentBillId", adjustmentBill.id)
                    }.toString(),
                    operator = "system",
                    createdAt = now
                )
            )
            database.auditEntryDao().insert(
                AuditEntryEntity(
                    id = "${adjustmentBill.id}:create",
                    entityType = "bill",
                    entityId = adjustmentBill.id,
                    action = "create",
                    fieldName = null,
                    oldValue = null,
                    newValue = billAuditValue(adjustmentBill),
                    operator = "system",
                    createdAt = now
                )
            )
            calibration
        }
    }

    private data class PendingRelations(
        val session: CheckoutSessionEntity?,
        val candidate: TransactionCandidateEntity?
    )

    private suspend fun loadPendingRelations(
        pending: PendingTransactionEntity
    ): PendingRelations {
        if (pending.candidateId != null && pending.sessionId == null) {
            throw LedgerValidationException("Pending candidate is missing its session binding")
        }
        val session = pending.sessionId?.let { sessionId ->
            database.checkoutSessionDao().getById(sessionId)
                ?: throw LedgerValidationException("Checkout session $sessionId does not exist")
        }
        val candidateId = pending.candidateId ?: session?.candidateId
        val candidate = candidateId?.let { id ->
            database.transactionCandidateDao().getById(id)
                ?: throw LedgerValidationException("Transaction candidate $id does not exist")
        }
        if (candidate != null && session?.id != candidate.sessionId) {
            throw LedgerValidationException("Pending candidate and session are not bound")
        }
        if (pending.candidateId != null &&
            session?.candidateId != null &&
            session.candidateId != pending.candidateId
        ) {
            throw LedgerValidationException("Pending candidate does not match its session")
        }
        return PendingRelations(session = session, candidate = candidate)
    }

    private suspend fun validateConfirmationBill(bill: BillEntity) {
        validateSafeAmount(bill.amountMinor, requirePositive = true)
        validateBillAccounts(
            bill,
            requireActiveAccounts = true,
            missingAccountMessage = "Confirmation requires an account"
        )
    }

    private suspend fun validateBillAccounts(
        bill: BillEntity,
        requireActiveAccounts: Boolean,
        missingAccountMessage: String = "Transaction requires an account"
    ) {
        val accountId = bill.accountId?.takeIf { it.isNotBlank() }
            ?: throw LedgerValidationException(missingAccountMessage)
        val account = database.accountDao().getById(accountId)
            ?: throw LedgerValidationException("Account $accountId does not exist")
        if (requireActiveAccounts && !account.isActive) {
            throw LedgerValidationException("Account $accountId is inactive")
        }

        if (bill.type == "transfer" || bill.type == "repayment") {
            val targetAccountId = bill.targetAccountId?.takeIf { it.isNotBlank() }
                ?: throw LedgerValidationException("${bill.type} requires a target account")
            if (targetAccountId == accountId) {
                throw LedgerValidationException("Source and target accounts must differ")
            }
            val target = database.accountDao().getById(targetAccountId)
                ?: throw LedgerValidationException("Target account $targetAccountId does not exist")
            if (!target.isActive) {
                throw LedgerValidationException("Target account $targetAccountId is inactive")
            }
        }
    }

    private fun validateSafeAmount(amountMinor: Long, requirePositive: Boolean = false) {
        val lowerBound = if (requirePositive) 1L else 0L
        if (amountMinor < lowerBound || amountMinor > MAX_SAFE_MINOR_UNIT) {
            throw LedgerValidationException(
                if (requirePositive) {
                    "Amount must be a positive safe integer minor unit"
                } else {
                    "Amount must be a safe integer minor unit"
                }
            )
        }
    }

    private fun checkedMultiply(left: Long, right: Long, message: String): Long = try {
        Math.multiplyExact(left, right)
    } catch (error: ArithmeticException) {
        throw LedgerValidationException(message, error)
    }

    private fun checkedAdd(left: Long, right: Long, message: String): Long = try {
        Math.addExact(left, right)
    } catch (error: ArithmeticException) {
        throw LedgerValidationException(message, error)
    }

    private fun decisionRules(existing: String, decision: String, billId: String? = null): String {
        val rules = runCatching { JSONObject(existing) }.getOrElse { JSONObject() }
        rules.put("decision", decision)
        billId?.let { rules.put("billId", it) }
        return rules.toString()
    }

    private suspend fun applyBalanceDelta(
        bill: BillEntity,
        multiplier: Long,
        updatedAt: Long,
        requireActiveAccounts: Boolean = false
    ) {
        validateSafeAmount(bill.amountMinor)
        val sourceDelta = checkedMultiply(
            BalanceCalculator.delta(bill.amountMinor, bill.type),
            multiplier,
            "Account balance delta is out of range"
        )
        val accountId = bill.accountId?.takeIf { it.isNotBlank() }
        if (accountId == null &&
            (requireActiveAccounts || bill.type == "transfer" || bill.type == "repayment")
        ) {
            throw LedgerValidationException("Transaction requires an account")
        }
        accountId?.let { sourceAccountId ->
            val account = database.accountDao().getById(sourceAccountId)
                ?: throw LedgerValidationException("Account $sourceAccountId does not exist")
            if (requireActiveAccounts && !account.isActive) {
                throw LedgerValidationException("Account $sourceAccountId is inactive")
            }
            database.accountDao().updateBalance(
                id = account.id,
                newBalance = checkedAdd(
                    account.currentBalanceMinor,
                    sourceDelta,
                    "Account balance is out of range"
                ),
                updatedAt = updatedAt
            )
        }

        if (bill.type == "transfer" || bill.type == "repayment") {
            val targetAccountId = bill.targetAccountId?.takeIf { it.isNotBlank() }
                ?: throw LedgerValidationException("${bill.type} requires a target account")
            if (targetAccountId == accountId) {
                throw LedgerValidationException("Source and target accounts must differ")
            }
            val target = database.accountDao().getById(targetAccountId)
                ?: throw LedgerValidationException("Target account $targetAccountId does not exist")
            if (requireActiveAccounts && !target.isActive) {
                throw LedgerValidationException("Target account $targetAccountId is inactive")
            }
            // 存储口径：负债账户"负数=欠款"（normalizeBalance 取负入库、getNetWorth 取 ABS）。
            // 目标账户恒 +amount：资产入金加余额；负债转入即还款，余额向 0 靠近（欠款减少）。
            val targetDelta = checkedMultiply(
                bill.amountMinor,
                multiplier,
                "Target account balance delta is out of range"
            )
            database.accountDao().updateBalance(
                id = target.id,
                newBalance = checkedAdd(
                    target.currentBalanceMinor,
                    targetDelta,
                    "Target account balance is out of range"
                ),
                updatedAt = updatedAt
            )
        }
    }

    private fun requireMutableBill(bill: BillEntity) {
        require(bill.type != "adjustment") {
            "Adjustment bills are immutable"
        }
    }

    private fun billAuditValue(bill: BillEntity): String = JSONObject().apply {
        put("id", bill.id)
        put("amountMinor", bill.amountMinor)
        put("type", bill.type)
        put("occurredAt", bill.occurredAt)
        putNullable("description", bill.description)
        putNullable("categoryId", bill.categoryId)
        putNullable("accountId", bill.accountId)
        putNullable("targetAccountId", bill.targetAccountId)
        putNullable("platform", bill.platform)
        putNullable("merchant", bill.merchant)
        putNullable("productDescription", bill.productDescription)
        put("sourceType", bill.sourceType)
        putNullable("originalTransactionId", bill.originalTransactionId)
        putNullable("orderId", bill.orderId)
        put("createdAt", bill.createdAt)
        put("updatedAt", bill.updatedAt)
        put("version", bill.version)
        put("fieldLocks", bill.fieldLocks)
    }.toString()

    private fun JSONObject.putNullable(key: String, value: String?) {
        put(key, value ?: JSONObject.NULL)
    }
}

object BalanceCalculator {
    fun calculate(currentBalance: Long, amount: Long, type: String): Long =
        Math.addExact(currentBalance, delta(amount, type))

    fun delta(amount: Long, type: String): Long = when (type) {
        "income", "refund" -> amount
        "expense", "fee" -> Math.negateExact(amount)
        "transfer", "repayment" -> Math.negateExact(amount)
        "adjustment" -> 0L
        else -> error("Unsupported transaction type: $type")
    }
}

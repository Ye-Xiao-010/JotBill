package com.jotbill.app.bookkeeping

import android.content.Context
import androidx.room.withTransaction
import com.jotbill.app.data.CreateBillOutcome
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.LedgerRepository
import com.jotbill.app.data.MAX_SAFE_MINOR_UNIT
import com.jotbill.app.data.OrderConflictException
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.AuditEntryEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import com.jotbill.app.pending.EditCardMarker
import com.jotbill.app.pending.EditCardFactory
import com.jotbill.app.rules.FieldPriorityResolver
import com.jotbill.app.rules.RuleEngine
import com.jotbill.app.utils.JotBillLog
import java.util.UUID

/**
 * 统一信号入账器（2026-11-16 记账模型调整）。
 *
 * 规则：
 * 1. **首笔信号直接生成成功账单**（支付 → expense，退款 → refund），不再落待确认；
 * 2. 后续信号在 [MERGE_WINDOW_MILLIS]（用户拍板 60 秒）内、同类型同金额、订单号
 *    不冲突、既有账单来自自动信号通道 ⇒ 视为同一笔，只做信息补充：商户、订单号、
 *    分类、**账户升级**（现金兜底 → 信号中提取到的银行/渠道账户，余额随账户迁移）；
 * 3. 账户解析优先级：用户规则 > 信号提示（银行名/退款渠道）> 原单账户 > 包名映射 > 现金；
 * 4. 支付账单创建后生成一张「已入账编辑卡」供弹窗调整账户/分类（确认即更新账单）。
 *
 * 并发与幂等（2026-11-17 审查修复）：
 * - 判重读取与账单写入在**同一个 Room 事务**内完成，Room 事务串行化保证多通道并发
 *   到达时第二笔能看到第一笔的提交，不会双账；
 * - 信号携带 `sourceEventKey` 时在事务内写确定性审计键（`insertIfAbsent`），重复投递 /
 *   进程重启后的补扫只处理一次；
 * - 处理异常整体回滚（含幂等键）并返回 [Outcome.Failed]，由调用方落待确认兜底，绝不
 *   静默伪装成 Ignored/Duplicate。
 */
class SignalBillWriter(
    private val appContext: Context?,
    private val database: JotBillDatabase
) {
    private val ledger by lazy { LedgerRepository(database) }

    private val notifier by lazy { appContext?.let { AutoBookkeepingNotifier(it) } }

    /** 支付信号（无障碍结果页/支付通知/短信等统一形态）。 */
    data class PaymentSignal(
        val amountMinor: Long,
        val occurredAt: Long,
        val merchant: String?,
        val orderId: String?,
        /** 账单 platform 字段（展示用渠道名；包名仅作来源标识）。 */
        val platform: String?,
        /** 通知/账单文案里的渠道名（支付宝/微信支付/拼多多…）。 */
        val channelLabel: String?,
        val sourcePackage: String,
        val sourceType: String,
        /** 账单类型：expense / income（默认支出）。 */
        val type: String = "expense",
        /** 账户提示：银行名、卡尾号、渠道（微信/支付宝/银行卡…）。 */
        val accountHints: List<String?> = emptyList(),
        /** 幂等键：同一条来源事件（通知/结果页/短信）只处理一次。 */
        val sourceEventKey: String? = null,
        val createEditCard: Boolean = true
    )

    /** 退款信号。 */
    data class RefundSignal(
        val amountMinor: Long,
        val occurredAt: Long,
        val merchant: String?,
        val orderId: String?,
        val platform: String?,
        val sourcePackage: String,
        val sourceType: String,
        /** 退款去向提示：银行名/微信支付/支付宝/银行卡… */
        val receivingHints: List<String?> = emptyList(),
        val originalBillId: String? = null,
        /** 用户 set_account 规则命中的账户（最高优先级）。 */
        val ruleAccountId: String? = null,
        val categoryId: String? = null,
        /** 幂等键：同一条来源事件（通知/结果页/短信）只处理一次。 */
        val sourceEventKey: String? = null
    )

    sealed class Outcome {
        data class Created(val billId: String) : Outcome()
        data class Enriched(val billId: String) : Outcome()
        /** 同一来源事件重复投递（幂等键已存在）；billId 为可定位的既有账单。 */
        data class Duplicate(val billId: String?) : Outcome()
        /** 规则要求人工复核（send_to_review）：调用方须落待确认，不得静默入账。 */
        data class NeedsReview(val reason: String) : Outcome()
        /** 处理失败（事务已回滚）：调用方须落待确认或重试，不得当作已处理。 */
        data class Failed(val reason: String) : Outcome()
        data class Ignored(val reason: String) : Outcome()
    }

    /** 事务内结果：副作用（通知/编辑卡/待确认）一律在事务提交后执行。 */
    private sealed class Tx {
        data class Created(val bill: BillEntity) : Tx()
        data class Enriched(val billId: String) : Tx()
        data class Duplicate(val billId: String?) : Tx()
        data class NeedsReview(val reason: String) : Tx()
        data class Ignored(val reason: String) : Tx()
    }

    suspend fun recordPayment(signal: PaymentSignal): Outcome {
        if (signal.amountMinor !in 1L..MAX_SAFE_MINOR_UNIT) {
            return Outcome.Ignored("INVALID_AMOUNT")
        }
        val now = System.currentTimeMillis()
        val occurredAt = signal.occurredAt.takeIf { it > 0L } ?: now
        val tx = try {
            commitPayment(signal, occurredAt, now)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (error is OrderConflictException) {
                JotBillLog.w(TAG, "同单号撞号，转人工复核: orderId=${signal.orderId?.take(24)}", error)
                return Outcome.NeedsReview(CONFLICT_ORDER_MISMATCH)
            }
            JotBillLog.w(
                TAG,
                "信号入账失败: amount=${signal.amountMinor}, from=${signal.sourcePackage}",
                error
            )
            return Outcome.Failed("BOOK_FAILED")
        }
        return when (tx) {
            is Tx.Created -> {
                // post-commit 副作用不得让"已入账"变成异常（2026-11-17 复审）：
                // 通知失败只记日志，调用方仍按成功处理，避免再落一张同额待确认。
                runCatching {
                    notifier?.notifyAutoBooked(
                        billId = tx.bill.id,
                        amountMinor = tx.bill.amountMinor,
                        type = tx.bill.type,
                        channelLabel = signal.channelLabel ?: signal.platform ?: "通知",
                        merchant = tx.bill.merchant
                    )
                }.onFailure { JotBillLog.w(TAG, "自动入账通知发送失败", it) }
                if (signal.createEditCard) createEditCard(tx.bill)
                if (tx.bill.type == "expense" || tx.bill.type == "refund") {
                    absorbSameAmountPendingCards(tx.bill, tx.bill.type)
                }
                JotBillLog.i(
                    TAG,
                    "首笔信号已入账: bill=${tx.bill.id.take(8)}, amount=${tx.bill.amountMinor}, " +
                        "account=${tx.bill.accountId}, from=${signal.sourcePackage}"
                )
                Outcome.Created(tx.bill.id)
            }
            is Tx.Enriched -> Outcome.Enriched(tx.billId)
            is Tx.Duplicate -> Outcome.Duplicate(tx.billId)
            is Tx.NeedsReview -> Outcome.NeedsReview(tx.reason)
            is Tx.Ignored -> Outcome.Ignored(tx.reason)
        }
    }

    suspend fun recordRefund(signal: RefundSignal): Outcome {
        if (signal.amountMinor !in 1L..MAX_SAFE_MINOR_UNIT) {
            return Outcome.Ignored("INVALID_AMOUNT")
        }
        val now = System.currentTimeMillis()
        val occurredAt = signal.occurredAt.takeIf { it > 0L } ?: now
        val tx = try {
            commitRefund(signal, occurredAt, now)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (error is OrderConflictException) {
                JotBillLog.w(TAG, "退款撞号，转人工复核: orderId=${signal.orderId?.take(24)}", error)
                return Outcome.NeedsReview(CONFLICT_ORDER_MISMATCH)
            }
            JotBillLog.w(
                TAG,
                "退款入账失败: amount=${signal.amountMinor}, from=${signal.sourcePackage}",
                error
            )
            return Outcome.Failed("REFUND_BOOK_FAILED")
        }
        return when (tx) {
            is Tx.Created -> {
                JotBillLog.i(
                    TAG,
                    "退款首笔已入账: bill=${tx.bill.id.take(8)}, amount=${tx.bill.amountMinor}, " +
                        "account=${tx.bill.accountId}, original=${signal.originalBillId?.take(8)}, " +
                        "from=${signal.sourcePackage}"
                )
                // 同笔退款可能已由另一通道落了待确认卡：账单落地后吸收，避免双记。
                absorbSameAmountPendingCards(tx.bill, "refund")
                Outcome.Created(tx.bill.id)
            }
            is Tx.Enriched -> Outcome.Enriched(tx.billId)
            is Tx.Duplicate -> Outcome.Duplicate(tx.billId)
            is Tx.NeedsReview -> Outcome.NeedsReview(tx.reason)
            is Tx.Ignored -> Outcome.Ignored(tx.reason)
        }
    }

    // ── 事务内提交 ───────────────────────────────────────────────────────

    private suspend fun commitPayment(
        signal: PaymentSignal,
        occurredAt: Long,
        now: Long
    ): Tx = database.withTransaction {
        // 先做只读幂等预检（2026-11-17 复审）：已处理过的事件直接判重，不再重跑规则。
        // 否则用户事后新增 send_to_review 规则时，同一事件的重投会给已入账账单
        // 再生成一张复核卡。轻量查审计键，不写任何东西。
        if (eventProcessed("payment", signal.sourceEventKey)) {
            return@withTransaction Tx.Duplicate(null)
        }
        // 规则优先：Ignored / ToReview 不写事件认领键 —— 待确认卡由调用方在
        // 事务外创建，若先认领、落卡失败，信号会被永久判重而丢证据。
        // 落卡幂等由 orphan 卡的确定性 ID / 页面证据窗口承担。
        val rule = evaluateRules(signal)
        if (rule.decision is RuleEngine.Decision.Ignored) {
            return@withTransaction Tx.Ignored("RULE_IGNORED")
        }
        if (rule.decision is RuleEngine.Decision.ToReview) {
            return@withTransaction Tx.NeedsReview(rule.decision.reason)
        }
        claimEvent("payment", signal.sourceEventKey, now)
            ?: return@withTransaction Tx.Duplicate(null)
        val patch = rule.patch

        val accounts = activeAccounts()
        val accountId = resolvePaymentAccount(signal, patch, accounts)
        val merchant = patch.merchant ?: signal.merchant
        val orderId = signal.orderId?.takeIf { it.isNotBlank() }

        val recent = findMergeCandidate(
            type = signal.type,
            amountMinor = signal.amountMinor,
            incomingOrderId = orderId,
            incomingMerchant = merchant,
            incomingSourceType = signal.sourceType,
            now = now
        )
        if (recent != null) {
            enrichBill(
                existing = recent,
                merchant = merchant,
                orderId = orderId,
                categoryId = patch.categoryId,
                accountId = accountId
            )
            JotBillLog.i(
                TAG,
                "首笔已入账，后续信号补全: bill=${recent.id.take(8)}, " +
                    "amount=${signal.amountMinor}, from=${signal.sourcePackage}"
            )
            return@withTransaction Tx.Enriched(recent.id)
        }

        val categoryId = patch.categoryId?.takeIf { database.categoryDao().getById(it) != null }
            ?: BookkeepingCategoryHints.guess(
                merchant = merchant,
                platform = signal.channelLabel ?: signal.platform,
                ruleNote = null,
                type = signal.type
            )?.takeIf { database.categoryDao().getById(it) != null }
        val bill = BillEntity(
            id = UUID.randomUUID().toString(),
            amountMinor = signal.amountMinor,
            type = signal.type,
            occurredAt = occurredAt,
            description = merchant ?: signal.channelLabel,
            categoryId = categoryId,
            accountId = accountId,
            targetAccountId = null,
            platform = patch.platform ?: signal.platform,
            merchant = merchant,
            productDescription = null,
            sourceType = signal.sourceType,
            originalTransactionId = null,
            orderId = orderId,
            createdAt = now,
            updatedAt = now,
            version = 1
        )
        when (val outcome = ledger.createBillWithDedup(bill, operator = "signal_auto_book")) {
            is CreateBillOutcome.Committed -> Tx.Created(outcome.bill)
            is CreateBillOutcome.MergedInto -> {
                // level-1（订单号）命中既有账单：合并字段后按补全返回，绝不返回未落库 ID。
                enrichBill(
                    existing = outcome.bill,
                    merchant = merchant,
                    orderId = orderId,
                    categoryId = patch.categoryId,
                    accountId = accountId
                )
                JotBillLog.i(
                    TAG,
                    "信号命中既有账单(level-1)，不重复入账: bill=${outcome.bill.id.take(8)}, " +
                        "eventKey=${signal.sourceEventKey?.take(12)}"
                )
                Tx.Enriched(outcome.bill.id)
            }
        }
    }

    private suspend fun commitRefund(
        signal: RefundSignal,
        occurredAt: Long,
        now: Long
    ): Tx = database.withTransaction {
        claimEvent("refund", signal.sourceEventKey, now)
            ?: return@withTransaction Tx.Duplicate(null)

        val accounts = activeAccounts()
        val originalAccountId = signal.originalBillId?.let { id ->
            database.billDao().getById(id)?.accountId?.takeIf { acc -> accounts.any { it.id == acc } }
        }
        val accountId = resolveRefundAccount(signal, accounts, originalAccountId)
        val orderId = signal.orderId?.takeIf { it.isNotBlank() }

        val recent = findMergeCandidate(
            type = "refund",
            amountMinor = signal.amountMinor,
            incomingOrderId = orderId,
            incomingMerchant = signal.merchant,
            incomingSourceType = signal.sourceType,
            now = now
        )
        if (recent != null) {
            enrichBill(
                existing = recent,
                merchant = signal.merchant,
                orderId = orderId,
                categoryId = null,
                accountId = accountId,
                originalBillId = signal.originalBillId
            )
            JotBillLog.i(
                TAG,
                "退款首笔已入账，后续信号补全: bill=${recent.id.take(8)}, " +
                    "amount=${signal.amountMinor}, from=${signal.sourcePackage}"
            )
            return@withTransaction Tx.Enriched(recent.id)
        }

        val bill = BillEntity(
            id = UUID.randomUUID().toString(),
            amountMinor = signal.amountMinor,
            type = "refund",
            occurredAt = occurredAt,
            description = signal.merchant ?: "退款",
            categoryId = signal.categoryId?.takeIf { database.categoryDao().getById(it) != null }
                ?: database.categoryDao().getById(DEFAULT_REFUND_CATEGORY_ID)?.id,
            accountId = accountId,
            targetAccountId = null,
            platform = signal.platform,
            merchant = signal.merchant,
            productDescription = null,
            sourceType = signal.sourceType,
            originalTransactionId = signal.originalBillId,
            orderId = orderId,
            createdAt = now,
            updatedAt = now,
            version = 1
        )
        when (val outcome = ledger.createBillWithDedup(bill, operator = "refund_signal")) {
            is CreateBillOutcome.Committed -> Tx.Created(outcome.bill)
            is CreateBillOutcome.MergedInto -> {
                enrichBill(
                    existing = outcome.bill,
                    merchant = signal.merchant,
                    orderId = orderId,
                    categoryId = signal.categoryId,
                    accountId = accountId,
                    originalBillId = signal.originalBillId
                )
                Tx.Enriched(outcome.bill.id)
            }
        }
    }

    // ── 幂等 ─────────────────────────────────────────────────────────────

    /** 只读幂等预检：已认领的事件直接判重，避免重跑规则/重复命中计数。 */
    private suspend fun eventProcessed(kind: String, sourceEventKey: String?): Boolean {
        val key = sourceEventKey?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return database.auditEntryDao()
            .getByEntity(SIGNAL_EVENT_ENTITY_TYPE, "$kind:$key")
            .isNotEmpty()
    }

    /**
     * 事务内认领信号事件：确定性审计键 `insertIfAbsent`，命中冲突（-1）即重复投递。
     * 处理异常时事务回滚、键随之一并消失，允许重试。
     *
     * @return null 表示重复；否则返回（无用）新行标识，仅供日志。
     */
    private suspend fun claimEvent(kind: String, sourceEventKey: String?, now: Long): Long? {
        val key = sourceEventKey?.trim()?.takeIf { it.isNotEmpty() } ?: return 0L
        val inserted = database.auditEntryDao().insertIfAbsent(
            AuditEntryEntity(
                id = "signal-event:$kind:$key",
                entityType = SIGNAL_EVENT_ENTITY_TYPE,
                entityId = "$kind:$key",
                action = "processed",
                fieldName = null,
                oldValue = null,
                newValue = null,
                operator = "signal_bill_writer",
                createdAt = now
            )
        )
        return inserted.takeIf { it != -1L }
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    private suspend fun activeAccounts() = database.accountDao().getAll().filter { it.isActive }

    /**
     * 合并候选：同类型 + 同金额 + 60 秒窗 + **自动信号来源白名单**（SQL 端过滤）
     * + 订单号不冲突 + 商户不冲突。
     *
     * 白名单排除 manual/import/ai_chat：用户手工/导入/对话写入的账单即使同额同秒
     * 也不得被自动信号"升级账户"或吸收。商户互斥（两侧都非空且互不包含）时
     * 视为两笔真单，不合并。
     */
    private suspend fun findMergeCandidate(
        type: String,
        amountMinor: Long,
        incomingOrderId: String?,
        incomingMerchant: String?,
        incomingSourceType: String,
        now: Long
    ): BillEntity? {
        val recent = database.billDao().findRecentByAmountAndTypeAndSources(
            type = type,
            amountMinor = amountMinor,
            windowStart = now - MERGE_WINDOW_MILLIS,
            windowEnd = now,
            sourceTypes = MERGEABLE_SOURCE_TYPES.toList()
        ) ?: return null
        if (!orderCompatible(recent.orderId, incomingOrderId)) return null
        // 商户护栏只用于**同渠道**判重；跨渠道的商户字段口径不同
        // （银行短信常是「财付通」等通道名，页面/通知是店铺名），若也校验
        // 会把同一笔支付拆成两笔（2026-11-17 复审）。
        if (recent.sourceType == incomingSourceType &&
            !merchantCompatible(recent.merchant, incomingMerchant)
        ) {
            return null
        }
        return recent
    }

    /** 商户护栏：两侧都非空且互不包含 ⇒ 两笔真单，不合并。 */
    private fun merchantCompatible(existing: String?, incoming: String?): Boolean {
        val left = existing?.trim().orEmpty()
        val right = incoming?.trim().orEmpty()
        if (left.isEmpty() || right.isEmpty()) return true
        return left.contains(right, ignoreCase = true) ||
            right.contains(left, ignoreCase = true)
    }

    private class RuleOutcome(
        val decision: RuleEngine.Decision,
        val patch: RuleEngine.FieldPatch
    )

    /**
     * 规则执行。平台条件同时接受包名与展示渠道名（兼容历史中文规则）。
     * 规则引擎自身异常不阻断信号入账，按无规则处理。
     */
    private suspend fun evaluateRules(signal: PaymentSignal): RuleOutcome {
        val decision = try {
            RuleEngine(database).evaluateAndRecord(
                RuleEngine.Subject(
                    amountMinor = signal.amountMinor,
                    platform = signal.platform,
                    platformAliases = listOfNotNull(signal.channelLabel),
                    merchant = signal.merchant,
                    description = signal.merchant,
                    sourceType = signal.sourceType
                )
            )
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "信号规则执行失败，按无规则处理", error)
            RuleEngine.Decision.Continue(
                RuleEngine.FieldPatch(),
                emptyList(),
                emptyList()
            )
        }
        val patch = try {
            RuleEngine(database).sanitizePatch(
                (decision as? RuleEngine.Decision.Continue)?.patch ?: RuleEngine.FieldPatch()
            )
        } catch (error: RuntimeException) {
            RuleEngine.FieldPatch()
        }
        return RuleOutcome(decision, patch)
    }

    private fun resolvePaymentAccount(
        signal: PaymentSignal,
        patch: RuleEngine.FieldPatch,
        accounts: List<AccountEntity>
    ): String? {
        patch.accountId?.let { candidate ->
            if (accounts.any { it.id == candidate }) return candidate
        }
        return (AccountInference.resolveByHints(signal.sourcePackage, signal.accountHints, accounts)
            as? AccountResolution.Resolved)?.accountId
    }

    private fun resolveRefundAccount(
        signal: RefundSignal,
        accounts: List<AccountEntity>,
        originalAccountId: String?
    ): String? {
        signal.ruleAccountId?.let { id ->
            if (accounts.any { it.id == id }) return id
        }
        val byHints = AccountInference.resolveByHints(
            signal.sourcePackage,
            signal.receivingHints,
            accounts
        )
        val hintAccount = (byHints as? AccountResolution.Resolved)?.accountId
        // 信号未给出可匹配账户（落现金）时，原单账户优先于现金
        val cashId = AccountInference.CASH_FALLBACK_ACCOUNT_ID
        if (hintAccount != null && hintAccount != cashId) return hintAccount
        return originalAccountId ?: hintAccount ?: cashId
    }

    /**
     * 后续信号补全：只补空缺字段；账户允许从现金兜底**升级**为具体账户（余额随之迁移）。
     */
    private suspend fun enrichBill(
        existing: BillEntity,
        merchant: String?,
        orderId: String?,
        categoryId: String?,
        accountId: String?,
        originalBillId: String? = null
    ) {
        val current = database.billDao().getById(existing.id) ?: return
        val merchantFill = current.merchant ?: merchant
        val orderFill = current.orderId ?: orderId
        val categoryFill = current.categoryId
            ?: categoryId?.takeIf { database.categoryDao().getById(it) != null }
        val accountUpgrade = current.accountId == AccountInference.CASH_FALLBACK_ACCOUNT_ID &&
            accountId != null &&
            accountId != current.accountId &&
            accountId != AccountInference.CASH_FALLBACK_ACCOUNT_ID
        val accountFill = if (accountUpgrade) accountId else current.accountId
        val originalFill = current.originalTransactionId ?: originalBillId
        val descriptionFill = current.description ?: merchant
        val changed = merchantFill != current.merchant ||
            orderFill != current.orderId ||
            categoryFill != current.categoryId ||
            accountFill != current.accountId ||
            originalFill != current.originalTransactionId ||
            descriptionFill != current.description
        if (!changed) return
        val updated = current.copy(
            merchant = merchantFill,
            orderId = orderFill,
            categoryId = categoryFill,
            accountId = accountFill,
            originalTransactionId = originalFill,
            description = descriptionFill,
            version = current.version + 1,
            updatedAt = System.currentTimeMillis()
        )
        val applied = try {
            ledger.updateBillWithBalanceUpdate(
                current,
                updated,
                FieldPriorityResolver.Source.EVIDENCE
            )
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            JotBillLog.w(TAG, "信号补全失败: bill=${current.id.take(8)}", error)
            false
        }
        if (applied) {
            // 以事务内最终落库值为准同步卡片（字段锁定/校验可能拒绝部分改动）。
            val persisted = database.billDao().getById(current.id) ?: updated
            updateEditCardFields(persisted)
        }
        JotBillLog.i(
            TAG,
            "信号补全${if (applied) "成功" else "跳过"}: bill=${current.id.take(8)}, " +
                "merchant=${merchantFill != current.merchant}, order=${orderFill != current.orderId}, " +
                "category=${categoryFill != current.categoryId}, accountUpgrade=$accountUpgrade"
        )
    }

    /**
     * 跨通道吸收（2026-11-17 复审上移）：本笔自动入账成功后，把其它通道此前
     * 落在 60 秒窗内的同额支出待确认卡标记忽略 —— 否则卡与账单并存，用户确认
     * 卡即双记。编辑卡不参与（账单已存在，卡片只驱动弹窗）。
     * 失败只记日志：吸收是清理动作，不得影响已完成的入账。
     */
    private suspend fun absorbSameAmountPendingCards(bill: BillEntity, type: String) {
        val cards = try {
            database.pendingTransactionDao().findPendingByAmountWindow(
                type = type,
                amountMinor = bill.amountMinor,
                windowStart = bill.createdAt - MERGE_WINDOW_MILLIS,
                windowEnd = bill.createdAt + MERGE_WINDOW_MILLIS
            )
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "跨通道待确认卡查询失败，跳过吸收", error)
            return
        }
        if (cards.isEmpty()) return
        for (card in cards) {
            if (card.id == editCardId(bill.id)) continue
            // 只吸收自动信号卡：导入/用户断言/手动卡不参与跨通道吸收。
            if (card.sourceType !in MERGEABLE_SOURCE_TYPES) continue
            if (!orderCompatible(card.orderId, bill.orderId)) continue
            val ignored = try {
                ledger.ignorePendingTransaction(card, card.version)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                JotBillLog.w(TAG, "跨通道吸收待确认卡失败: pending=${card.id.take(16)}", error)
                false
            }
            if (ignored) {
                JotBillLog.i(
                    TAG,
                    "跨通道吸收同额待确认卡: pending=${card.id.take(16)}, " +
                        "amount=${bill.amountMinor}, platform=${card.platform}"
                )
            }
        }
    }

    /** 编辑卡仍挂在待确认管线里：补全后同步卡片字段，避免弹窗显示旧账户。 */
    private suspend fun updateEditCardFields(bill: BillEntity) {
        val cardId = editCardId(bill.id)
        val card = database.pendingTransactionDao().getById(cardId) ?: return
        if (card.status != PendingTransactionEntity.STATUS_PENDING) return
        if (!EditCardMarker.isEditCard(card.appliedRules)) return
        try {
            database.pendingTransactionDao().update(
                card.copy(
                    accountId = bill.accountId,
                    categoryId = bill.categoryId,
                    merchant = bill.merchant,
                    orderId = bill.orderId,
                    version = card.version + 1
                )
            )
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "编辑卡字段同步失败: card=${card.id.take(16)}", error)
        }
    }

    private suspend fun createEditCard(bill: BillEntity) {
        val context = appContext ?: return
        EditCardFactory.create(context, database, bill)
    }

    companion object {
        private const val TAG = "SignalBillWriter"

        /** 首笔与补全信号的时间窗（用户拍板 2026-11-16：支付/退款各 60 秒）。 */
        const val MERGE_WINDOW_MILLIS = 60_000L

        /** 退款种子分类（与 DefaultDataInitializer / RefundLinker 保持一致）。 */
        const val DEFAULT_REFUND_CATEGORY_ID = "default-category-income-refund"

        /** 订单号撞号（同单号不同金额/类型）转人工复核的冲突码。 */
        const val CONFLICT_ORDER_MISMATCH = "ORDER_CONFLICT"

        /** 信号幂等键的审计实体类型。 */
        internal const val SIGNAL_EVENT_ENTITY_TYPE = "signal_event"

        /** 允许参与 60 秒补全合并的自动信号来源；manual/import/ai_chat 绝不参与。 */
        private val MERGEABLE_SOURCE_TYPES = setOf(
            "notification",
            "accessibility",
            "sms",
            "ai_vision"
        )

        fun editCardId(billId: String): String = "pending-edit-$billId"

        /** 订单号护栏：两侧都非空且不同 ⇒ 两张真单，不允许补全合并。 */
        fun orderCompatible(existingOrderId: String?, incomingOrderId: String?): Boolean {
            if (existingOrderId.isNullOrBlank() || incomingOrderId.isNullOrBlank()) return true
            return existingOrderId == incomingOrderId
        }
    }
}

package com.jotbill.app.bookkeeping

import com.jotbill.app.data.CreateBillOutcome
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.JotBillDatabaseProvider
import com.jotbill.app.data.LedgerRepository
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.recognition.CheckoutPaymentMatcher
import com.jotbill.app.recognition.PaymentNotificationFallbackSignal
import com.jotbill.app.rules.DuplicateGuard
import com.jotbill.app.rules.OrderIdNormalizer
import com.jotbill.app.rules.RuleEngine
import com.jotbill.app.utils.JotBillLog
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 规则引擎的交付侧：抽取记录 → 自动入账（对齐小米「上报→确认→通知」的本地形态）。
 *
 * 职责链：
 * 1. [onRecordReady]（主线程）：同步解析金额判定「认领」，让弹窗管线让路；
 * 2. 异步落账：字段归一化（类型/时间/商户）→ 用户分类规则补全（复用 [RuleEngine]，
 *    对应「手机端不做分类」的小米分工）→ 默认支付账户 → 相似度去重 → 入账；
 * 3. 任一环节不适合自动入账（无默认账户 / 疑似重复 / 落库异常）→ 转待确认兜底
 *    （复用 [CheckoutPaymentMatcher.recordOrphanPaymentSignal]，含同额合并语义）；
 * 4. 入账成功 → 系统通知「已自动记一笔」，带撤销动作（设计方案 §2.7）。
 *
 * ⚠️ B3 拍板（2026-11-04）：银行短信（[SMS_SOURCE_PACKAGE]）默认走现金兜底**自动
 * 入账**（口径乙）；但若账户字段命中**用户自建 set_account 规则**（[RuleEngine]
 * patch.accountId 非空，即 FieldPriorityResolver 的 RULE 级来源），属于猜测性人工
 * 干预 ⇒ **强制转待确认**（[CONFLICT_RULE_ACCOUNT_NEEDS_REVIEW]），由用户核对。
 * 该强制仅对短信渠道生效，通知/结果页等其他渠道不适用。
 */
class AutoBookkeeper(
    appContext: android.content.Context,
    private val scope: CoroutineScope,
    /**
     * 数据库注入点（B3 测试用）：生产不传（走 [JotBillDatabaseProvider]），
     * JVM 单测注入 Room 内存库以覆盖落库语义；默认值保证生产行为零变化。
     */
    private val databaseOverride: JotBillDatabase? = null
) : AutoBookkeepingEngine.Sink {

    private val context = appContext.applicationContext

    private val database by lazy {
        databaseOverride ?: JotBillDatabaseProvider.get(context)
    }

    private val ledger by lazy { LedgerRepository(database) }

    private val matcher by lazy { CheckoutPaymentMatcher(database) }

    private val notifier by lazy { AutoBookkeepingNotifier(context) }

    // ── Sink：主线程回调，只做轻量同步判定 ────────────────────────────────

    override fun onRecordReady(record: AutoBookkeepingEngine.ExtractedRecord): Boolean {
        val rawAmount = record.fields["amount"]
        val amountMinor = AutoBookkeepingEngine.parseAmountMinor(rawAmount)
        if (amountMinor == null || amountMinor <= 0L) {
            // P7：规则抽到了金额字段但不可入账（解析失败/非正数，如短信里的
            // "-100.00" 负号金额）—— 此前在此静默返回，全链无任何痕迹，规则资产
            // 一旦抽出负号金额就是系统性漏记。落诊断日志供真机回溯；不猜符号、
            // 不取绝对值（无法判断是支出还是退款冲正，猜错方向比丢更糟）。
            if (!rawAmount.isNullOrBlank()) {
                JotBillLog.w(
                    TAG,
                    "规则金额字段不可入账，记录已丢弃(诊断): rule=${record.ruleId}" +
                        "(${record.ruleNote}), raw=${rawAmount.take(30)}, " +
                        "parsed=$amountMinor, pkg=${record.sourcePackage}"
                )
            }
            return false
        }
        scope.launch {
            book(record, amountMinor)
        }
        return true
    }

    private suspend fun book(
        record: AutoBookkeepingEngine.ExtractedRecord,
        amountMinor: Long
    ) {
        val type = resolveType(record)
        val merchant = (record.fields["counterparty"] ?: record.fields["merchant"])
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_TEXT_LENGTH)
        val orderId = OrderIdNormalizer.normalize(record.fields["orderNo"])
            ?.take(MAX_TEXT_LENGTH)
        val occurredAt = parseBusinessTimeMillis(
            record.fields["businessTime"],
            fallback = record.extractTime
        )

        // 用户分类规则补全（金额/平台/商户/描述 → set_category/set_account 等）。
        // 决策三分支对齐 RuleEngine 契约与 CheckoutPaymentMatcher 三处先例：
        // - Ignored（如「星巴克不自动记账」）= 丢弃：不写 Bill、不进待确认；
        // - ToReview = 转待确认由用户处理（CONFLICT_RULE_FORCED_REVIEW）；
        // - Continue = 取 patch 继续自动入账。
        val decision = try {
            RuleEngine(database).evaluateAndRecord(
                RuleEngine.Subject(
                    amountMinor = amountMinor,
                    platform = channelLabel(record.sourcePackage),
                    merchant = merchant,
                    // 用户规则按「描述包含 X」匹配 —— 传商户这种真实交易语义，
                    // 不传规则备注等工程字样（会误触发渠道名规则）。
                    description = merchant,
                    sourceType = BILL_SOURCE_TYPE
                )
            )
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "分类规则执行失败，按无规则补全处理", error)
            null
        }
        when (decision) {
            is RuleEngine.Decision.Ignored -> {
                JotBillLog.i(
                    TAG,
                    "规则决策=忽略，丢弃自动记账记录: rule=${record.ruleId}, " +
                        "amount=$amountMinor, hits=${decision.hits.size}"
                )
                return
            }
            is RuleEngine.Decision.ToReview -> {
                fallbackToPending(
                    record, amountMinor, merchant, orderId, occurredAt,
                    RuleEngine.CONFLICT_RULE_FORCED_REVIEW
                )
                return
            }
            else -> Unit
        }
        val patch = try {
            RuleEngine(database).sanitizePatch(
                (decision as? RuleEngine.Decision.Continue)?.patch ?: RuleEngine.FieldPatch()
            )
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "分类规则补全净化失败，按无规则补全处理", error)
            RuleEngine.FieldPatch()
        }

        // ---- B3 拍板（2026-11-04）：短信渠道 + 用户 set_account 规则命中 ⇒ 强制待确认 ----
        // 判据 = 净化后的 patch.accountId 非空（RuleEngine 的 patch 只来自用户自建规则，
        // set_account 是唯一填充 accountId 的动作；sanitizePatch 已滤掉失效/停用账户，
        // 引用失效等同未配置 ⇒ 走默认现金兜底自动入账）。语义：默认兜底走自动化，
        // 但用户人为指定账户属于猜测性干预，必须让用户核对。
        // ⚠️ 仅短信渠道（com.android.mms）生效，不得扩散到通知/结果页等其他渠道。
        if (record.sourcePackage == SMS_SOURCE_PACKAGE && patch.accountId != null) {
            fallbackToPending(
                record, amountMinor, merchant, orderId, occurredAt,
                CONFLICT_RULE_ACCOUNT_NEEDS_REVIEW
            )
            return
        }

        val accountResolution = resolveAccountId(
            record.sourcePackage,
            patch,
            bankHint = record.fields["bank"]
        )
        val accountId = when (accountResolution) {
            is AccountResolution.Resolved -> accountResolution.accountId
            // 短信银行名多账户命中：转待确认（复用 RULE_ACCOUNT_NEEDS_REVIEW，不猜测）
            AccountResolution.NeedsReview -> {
                fallbackToPending(
                    record, amountMinor, merchant, orderId, occurredAt,
                    CONFLICT_RULE_ACCOUNT_NEEDS_REVIEW
                )
                return
            }
            AccountResolution.Unresolved -> {
                fallbackToPending(record, amountMinor, merchant, orderId, occurredAt, "NO_DEFAULT_ACCOUNT")
                return
            }
        }

        // ---- P1：现金兜底可识别标记（不改乙口径，只提可观测性/可纠正性）----
        // 账户来自推断链**链尾**（无用户 set_account 规则、银行名/包名映射均未命中，
        // 落入现金兜底）时，该笔的账户归属是"系统猜的"。入账后写一条审计标记，
        // 供 UI 提示「该笔账户为推断」、用户核对纠正；账单数据本身不变。
        val accountInferred = patch.accountId == null &&
            accountId == CASH_FALLBACK_ACCOUNT_ID
        val categoryId = patch.categoryId?.takeIf { database.categoryDao().getById(it) != null }
            ?: BookkeepingCategoryHints.guess(
                merchant = merchant,
                platform = patch.platform ?: channelLabel(record.sourcePackage),
                ruleNote = record.ruleNote,
                type = type
            )?.takeIf { database.categoryDao().getById(it) != null }

        val now = System.currentTimeMillis()
        val bill = BillEntity(
            id = UUID.randomUUID().toString(),
            amountMinor = amountMinor,
            type = type,
            occurredAt = occurredAt,
            // 无商户的支付（支付宝主结果页只抽得到金额）给出渠道级描述，
            // 账单列表不至于出现光秃秃的金额；后续账单详情页二次抽取补上
            // 商户时以商户为准展示。
            description = merchant ?: "${channelLabel(record.sourcePackage)}" +
                (if (type == "income") "收入" else "支出"),
            categoryId = categoryId,
            accountId = accountId,
            targetAccountId = null,
            platform = patch.platform ?: channelLabel(record.sourcePackage),
            merchant = patch.merchant ?: merchant,
            productDescription = null,
            sourceType = BILL_SOURCE_TYPE,
            originalTransactionId = null,
            orderId = orderId,
            createdAt = now,
            updatedAt = now,
            version = 1
        )

        // 相似度去重（对齐既有自动入账路径的第 4 级检查）：疑似重复不冒二次
        // 入账风险，转待确认由用户判断。
        val similar = try {
            DuplicateGuard(database).findSimilar(
                type = bill.type,
                amountMinor = bill.amountMinor,
                occurredAt = bill.occurredAt,
                accountId = bill.accountId,
                merchant = bill.merchant,
                excludeId = bill.id
            )
        } catch (error: RuntimeException) {
            // 协程取消不是落库失败：不得在已取消的作用域里再做兜底落库
            if (error is kotlinx.coroutines.CancellationException) throw error
            JotBillLog.w(TAG, "相似度去重检查失败，转待确认", error)
            null
        }
        if (similar != null && similar.isNotEmpty()) {
            // 跨通道互认（不再只认同 sourceType 的账单）：同一笔被页面规则 / 通知监听 /
            // 账单导入任一通道先入账后，本通道的二次抽取同样应归并进既有账单，
            // 而不是落一张 SUSPECTED_DUPLICATE 待确认。
            //
            // 吸收判据按通道分两档：
            // - **同通道**：沿用 [isSameTransaction]，任一方缺订单号仍可吸收 ——
            //   这是「主结果页先只有金额、账单详情页二次抽出商户/分类」的补全场景；
            // - **跨通道**：必须双方都有订单号且相同。订单号是唯一能客观证明同一笔的键，
            //   任一方缺失时只提示不吸收，否则同额同商户的两笔真实支付会被误合并。
            val absorbable = similar.filter { hit ->
                if (hit.bill.sourceType == BILL_SOURCE_TYPE) {
                    isSameTransaction(hit.bill.orderId, orderId)
                } else {
                    !hit.bill.orderId.isNullOrBlank() && !orderId.isNullOrBlank() &&
                        hit.bill.orderId == orderId
                }
            }
            if (absorbable.isNotEmpty()) {
                // 二次抽取命中既有账单：只补既有账单的空缺字段，金额/账户/类型绝不改动。
                // 对齐小米服务端「指纹合并+字段补全」的本地形态（合并与补全在小米是
                // 服务端能力，客户端只上报；本地版由 [FieldPriorityResolver] 承担裁决）。
                enrichExistingBill(
                    existing = absorbable.maxByOrNull { it.bill.createdAt }!!.bill,
                    merchant = merchant,
                    orderId = orderId,
                    categoryId = categoryId
                )
                return
            }
            // 有相似命中但不可吸收（订单号不一致 / 跨通道缺订单号）：
            // 疑似真实第二笔，转待确认由用户判断，不得擅自合并
            fallbackToPending(
                record, amountMinor, merchant, orderId, occurredAt,
                DuplicateGuard.CONFLICT_SUSPECTED_DUPLICATE
            )
            return
        }

        try {
            val outcome = ledger.createBillWithDedup(bill, operator = AUDIT_OPERATOR)
            val settled = (outcome as? CreateBillOutcome.Committed)?.bill
            if (settled != null) {
                if (accountInferred) {
                    // P1：审计标记失败不得拖垮已完成的入账（兜底静默）。
                    runCatching {
                        database.auditEntryDao().insertIfAbsent(
                            com.jotbill.app.data.entity.AuditEntryEntity(
                                id = "${settled.id}:account-inferred",
                                entityType = "bill",
                                entityId = settled.id,
                                action = AUDIT_ACTION_ACCOUNT_INFERRED,
                                fieldName = "accountId",
                                oldValue = null,
                                newValue = settled.accountId,
                                operator = AUDIT_OPERATOR,
                                createdAt = now
                            )
                        )
                    }
                }
                notifier.notifyAutoBooked(
                    billId = settled.id,
                    amountMinor = settled.amountMinor,
                    type = settled.type,
                    channelLabel = settled.platform ?: channelLabel(record.sourcePackage),
                    merchant = settled.merchant
                )
                JotBillLog.i(
                    TAG,
                    "自动入账完成: billId=${settled.id.take(8)}, amount=$amountMinor, " +
                        "type=$type, rule=${record.ruleId}(${record.ruleNote}), " +
                        "account=$accountId, category=$categoryId"
                )
            } else {
                JotBillLog.i(
                    TAG,
                    "规则记录命中既有账单(level-1 合并)，不重复入账: rule=${record.ruleId}"
                )
            }
        } catch (error: Exception) {
            // 协程取消（服务销毁等）静默退出，不做兜底（兜底也必然再被取消）
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (error is com.jotbill.app.data.OrderConflictException) {
                // M1：同单号撞号（金额/类型不一致）—— 新证据转待确认并带可识别
                // 冲突标记，绝不被静默吞进旧账单。
                JotBillLog.w(TAG, "同单号撞号，转待确认: orderId=$orderId", error)
                fallbackToPending(
                    record, amountMinor, merchant, orderId, occurredAt,
                    DuplicateGuard.CONFLICT_DEDUP_CHECK_FAILED
                )
                return
            }
            JotBillLog.w(TAG, "自动入账失败，转待确认", error)
            fallbackToPending(record, amountMinor, merchant, orderId, occurredAt, "AUTO_BOOK_FAILED")
        }
    }

    // ── 字段归一化 ─────────────────────────────────────────────────────────

    private fun resolveType(record: AutoBookkeepingEngine.ExtractedRecord): String {
        // detailType 已被 extractFieldsMapper 归一化为 "1"收入/"2"支出（小米约定）。
        return when (record.fields["detailType"]) {
            "1" -> "income"
            "2" -> "expense"
            else -> if (record.jotbillType == "income") "income" else "expense"
        }
    }

    /**
     * 账户解析（读法一）：规则 → 信号推断 → 包名映射 → 现金兜底。
     *
     * 信号层与包名/兜底层共用 [AccountInference.inferSignalThenFallback]（与
     * CheckoutPaymentMatcher.defaultAccountIdForPayment 同一实现）；规则（set_account）
     * 命中在此**之前**已由调用方处理（短信渠道命中即强制待确认）。
     *
     * @param bankHint 短信解析的银行名（其它信号恒 null）；多账户命中返回
     *   [AccountResolution.NeedsReview] 由调用方转待确认，不猜测。
     */
    private suspend fun resolveAccountId(
        sourcePackage: String,
        patch: RuleEngine.FieldPatch,
        bankHint: String?
    ): AccountResolution {
        patch.accountId?.let { candidate ->
            val account = database.accountDao().getById(candidate)
            if (account != null && account.isActive) return AccountResolution.Resolved(candidate)
        }
        val activeAccounts = database.accountDao().getAll().filter { it.isActive }
        return AccountInference.inferSignalThenFallback(sourcePackage, bankHint, activeAccounts)
    }

        /**
     * 二次抽取（账单详情页/通知）对既有账单的字段回填：只补空缺，绝不覆盖。
     * 走 [FieldPriorityResolver.Source.EVIDENCE] 级（4 级）—— 低于用户编辑与
     * 用户规则，锁定字段自动受保护；金额/账户/类型不参与回填，余额不动。
     */
    private suspend fun enrichExistingBill(
        existing: BillEntity,
        merchant: String?,
        orderId: String?,
        categoryId: String?
    ) {
        val fills = computeFieldFills(existing, merchant, orderId, categoryId)
        if (fills.merchant == null && fills.orderId == null && fills.categoryId == null) {
            JotBillLog.i(
                TAG,
                "二次抽取无字段可补（既有账单已完整），丢弃: bill=${existing.id.take(8)}"
            )
            return
        }
        val categoryFill = fills.categoryId
            ?.takeIf { database.categoryDao().getById(it) != null }
        val updated = existing.copy(
            merchant = fills.merchant ?: existing.merchant,
            orderId = fills.orderId ?: existing.orderId,
            categoryId = categoryFill ?: existing.categoryId,
            version = existing.version + 1
        )
        val applied = try {
            ledger.updateBillWithBalanceUpdate(
                existing,
                updated,
                com.jotbill.app.rules.FieldPriorityResolver.Source.EVIDENCE
            )
        } catch (error: Exception) {
            JotBillLog.w(TAG, "二次抽取字段回填失败", error)
            false
        }
        JotBillLog.i(
            TAG,
            "二次抽取字段回填${if (applied) "成功" else "跳过(版本竞争/校验未过)"}: " +
                "bill=${existing.id.take(8)}, merchant=${fills.merchant != null}, " +
                "orderId=${fills.orderId != null}, category=${categoryFill != null}"
        )
    }
    // ── 兜底：转待确认（弹窗管线已被认领让路时，证据不能丢）──────────────

    private suspend fun fallbackToPending(
        record: AutoBookkeepingEngine.ExtractedRecord,
        amountMinor: Long,
        merchant: String?,
        orderId: String?,
        occurredAt: Long,
        reason: String
    ) {
        try {
            matcher.recordOrphanPaymentSignal(
                PaymentNotificationFallbackSignal(
                    paymentPackage = record.sourcePackage,
                    amountMinor = amountMinor,
                    occurredAt = occurredAt,
                    merchant = merchant,
                    orderId = orderId,
                    sourceEventKey = "abk:${record.ruleId}:${record.extractTime}",
                    rawTextHash = null
                ),
                sourceType = "ACCESSIBILITY",
                conflictReason = reason
            )
            JotBillLog.i(
                TAG,
                "规则记录转待确认: reason=$reason, rule=${record.ruleId}, amount=$amountMinor"
            )
        } catch (error: Exception) {
            JotBillLog.w(TAG, "规则记录转待确认失败，证据丢弃", error)
        }
    }

    companion object {
        private const val TAG = "AutoBookkeeper"

        const val BILL_SOURCE_TYPE = "accessibility"

        const val AUDIT_OPERATOR = "auto_bookkeeping"

        /**
         * P1：现金兜底自动入账的审计动作标记 —— 账户归属来自推断链链尾（系统猜测）
         * 的账单会带一条该动作的审计记录，供 UI 提示「该笔账户为推断」并引导纠正。
         */
        const val AUDIT_ACTION_ACCOUNT_INFERRED = "account_inferred_cash_fallback"

        private const val MAX_TEXT_LENGTH = 120

        private const val CROSS_DAY_TOLERANCE_MINUTES = 60L

        /** 与 DefaultDataInitializer 种子账户一致（CheckoutPaymentMatcher 同源约定）；唯一出处见 [AccountInference]。 */
        private const val WECHAT_PAY_ACCOUNT_ID = AccountInference.WECHAT_PAY_ACCOUNT_ID

        private const val ALIPAY_ACCOUNT_ID = AccountInference.ALIPAY_ACCOUNT_ID

        /** 非微信/支付宝渠道的入账兜底账户（现金，推断链链尾）。 */
        private const val CASH_FALLBACK_ACCOUNT_ID = AccountInference.CASH_FALLBACK_ACCOUNT_ID

        /** 银行短信来源包（BookkeepingChannels 的 mms 渠道同款包名）。 */
        private const val SMS_SOURCE_PACKAGE = "com.android.mms"

        /**
         * B3 拍板（2026-11-04）：短信渠道账户字段命中用户 set_account 规则时的
         * 冲突码 —— 强制转待确认（猜测性人工干预须用户核对），不自动入账。
         * 落 `pending.conflictReason`，与 `NO_DEFAULT_ACCOUNT` 同消费路径。
         */
        const val CONFLICT_RULE_ACCOUNT_NEEDS_REVIEW = "RULE_ACCOUNT_NEEDS_REVIEW"

        private val FULL_TIME_PATTERN = Pattern.compile(
            "(\\d{4})年(\\d{1,2})月(\\d{1,2})日\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?"
        )

        private val MONTH_DAY_TIME = Pattern.compile(
            "(\\d{1,2})月(\\d{1,2})日\\s*(\\d{1,2}):(\\d{2})"
        )

        private val DAY_DASH_TIME = Pattern.compile(
            "(\\d{1,2})-(\\d{1,2})\\s*(\\d{1,2}):(\\d{2})"
        )

        private val HOUR_MINUTE = Pattern.compile(
            "^(\\d{1,2}):(\\d{2})$"
        )

        // ── 时间解析（纯函数，单测直接驱动）───────────────────────────────

    /**
         * businessTime 文本 → epoch 毫秒。覆盖内置规则产出的全部格式：
         * `2026年10月25日 14:30:25`、`10月25日 14:30`、`10-25 14:30`、`14:30`。
         * 只有时刻（HH:mm）时按当天解释；算出的时间超过参照时刻 60 分钟以上按
         * 前一天/前一年解释（跨零点支付、跨年短信）。全部失败回退抽取时刻。
         */
        internal fun parseBusinessTimeMillis(raw: String?, fallback: Long): Long {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return fallback
            val zone = ZoneId.systemDefault()
            val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(fallback), zone)

            FULL_TIME_PATTERN.matcher(text).takeIf { it.find() }?.let { matcher ->
                val time = LocalDateTime.of(
                    matcher.group(1)!!.toInt(),
                    matcher.group(2)!!.toInt(),
                    matcher.group(3)!!.toInt(),
                    matcher.group(4)!!.toInt(),
                    matcher.group(5)!!.toInt(),
                    matcher.group(6)?.toInt() ?: 0
                )
                return time.atZone(zone).toInstant().toEpochMilli()
            }

            monthDayTime(text, now, zone)?.let { return it }
            dayTimeDash(text, now, zone)?.let { return it }
            hourMinuteOnly(text, now, zone)?.let { return it }
            return fallback
        }

        internal fun monthDayTime(text: String, now: LocalDateTime, zone: ZoneId): Long? {
            val matcher = MONTH_DAY_TIME.matcher(text).takeIf { it.find() } ?: return null
            val month = matcher.group(1)!!.toInt()
            val day = matcher.group(2)!!.toInt()
            val hour = matcher.group(3)!!.toInt()
            val minute = matcher.group(4)!!.toInt()
            if (!isValidMonthDay(now.year, month, day)) return null
            var time = LocalDate.of(now.year, month, day).atTime(hour, minute)
            if (time.isAfter(now.plusMinutes(CROSS_DAY_TOLERANCE_MINUTES))) {
                time = LocalDate.of(now.year - 1, month, day).atTime(hour, minute)
            }
            return time.atZone(zone).toInstant().toEpochMilli()
        }

        internal fun dayTimeDash(text: String, now: LocalDateTime, zone: ZoneId): Long? {
            val matcher = DAY_DASH_TIME.matcher(text).takeIf { it.find() } ?: return null
            val month = matcher.group(1)!!.toInt()
            val day = matcher.group(2)!!.toInt()
            val hour = matcher.group(3)!!.toInt()
            val minute = matcher.group(4)!!.toInt()
            if (!isValidMonthDay(now.year, month, day)) return null
            var time = LocalDate.of(now.year, month, day).atTime(hour, minute)
            // 与 monthDayTime 同款跨年容差：1 月初收到的 12-28 短信按去年解释，
            // 否则会记出一笔「未来 11 个月」的账单
            if (time.isAfter(now.plusMinutes(CROSS_DAY_TOLERANCE_MINUTES))) {
                time = LocalDate.of(now.year - 1, month, day).atTime(hour, minute)
            }
            return time.atZone(zone).toInstant().toEpochMilli()
        }

        internal fun hourMinuteOnly(text: String, now: LocalDateTime, zone: ZoneId): Long? {
            val matcher = HOUR_MINUTE.matcher(text).takeIf { it.matches() } ?: return null
            val hour = matcher.group(1)!!.toInt()
            val minute = matcher.group(2)!!.toInt()
            if (hour > 23 || minute > 59) return null
            var time = now.toLocalDate().atTime(hour, minute)
            if (time.isAfter(now.plusMinutes(CROSS_DAY_TOLERANCE_MINUTES))) {
                time = time.minusDays(1)
            }
            return time.atZone(zone).toInstant().toEpochMilli()
        }

        internal fun isValidMonthDay(year: Int, month: Int, day: Int): Boolean = try {
            LocalDate.of(year, month, day)
            true
        } catch (_: java.time.DateTimeException) {
            false
        }

        /** 支付包名 → 展示用渠道名（通知文案与账单 platform 字段共用）。 */
        fun channelLabel(sourcePackage: String?): String = when (sourcePackage) {
            "com.eg.android.AlipayGphone" -> "支付宝"
            "com.tencent.mm" -> "微信支付"
            "com.unionpay" -> "云闪付"
            "com.jingdong.app.mall" -> "京东"
            "com.sankuai.meituan" -> "美团"
            "com.xunmeng.pinduoduo" -> "拼多多"
            "com.ss.android.ugc.aweme" -> "抖音"
            "com.android.mms" -> "银行短信"
            else -> "自动记账"
        }

        /**
         * 纯函数：既有账单的空缺字段与二次抽取值的合并决策（只补空，不覆盖）。
         * 独立成函数以便单测钉死「绝不覆盖非空字段」不变式。
         */
        class FieldFills(val merchant: String?, val orderId: String?, val categoryId: String?)

        fun computeFieldFills(
            existing: BillEntity,
            merchant: String?,
            orderId: String?,
            categoryId: String?
        ): FieldFills = FieldFills(
            merchant = merchant?.takeIf { existing.merchant.isNullOrBlank() },
            orderId = orderId?.takeIf { existing.orderId.isNullOrBlank() },
            categoryId = categoryId?.takeIf { existing.categoryId.isNullOrBlank() }
        )

        /**
         * 订单号判别：任一方缺订单号视为「无法排除同笔」（保守吸收）；
         * 双方都有且不同 ⇒ 明确是两笔真实交易，不得吸收。
         */
        internal fun isSameTransaction(existingOrderId: String?, newOrderId: String?): Boolean {
            if (existingOrderId.isNullOrBlank() || newOrderId.isNullOrBlank()) return true
            return existingOrderId == newOrderId
        }
    }
}

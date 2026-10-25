package com.jotbill.app.bookkeeping

import com.jotbill.app.data.CreateBillOutcome
import com.jotbill.app.data.JotBillDatabaseProvider
import com.jotbill.app.data.LedgerRepository
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.recognition.CheckoutPaymentMatcher
import com.jotbill.app.recognition.PaymentNotificationFallbackSignal
import com.jotbill.app.rules.DuplicateGuard
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
 */
class AutoBookkeeper(
    appContext: android.content.Context,
    private val scope: CoroutineScope
) : AutoBookkeepingEngine.Sink {

    private val context = appContext.applicationContext

    private val database by lazy {
        JotBillDatabaseProvider.get(context)
    }

    private val ledger by lazy { LedgerRepository(database) }

    private val matcher by lazy { CheckoutPaymentMatcher(database) }

    private val notifier by lazy { AutoBookkeepingNotifier(context) }

    // ── Sink：主线程回调，只做轻量同步判定 ────────────────────────────────

    override fun onRecordReady(record: AutoBookkeepingEngine.ExtractedRecord): Boolean {
        val amountMinor = AutoBookkeepingEngine.parseAmountMinor(record.fields["amount"])
        if (amountMinor == null || amountMinor <= 0L) {
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
        val orderId = record.fields["orderNo"]?.trim()?.takeIf { it.isNotEmpty() }
            ?.take(MAX_TEXT_LENGTH)
        val occurredAt = parseBusinessTimeMillis(
            record.fields["businessTime"],
            fallback = record.extractTime
        )

        // 用户分类规则补全（金额/平台/商户/描述 → set_category/set_account 等）。
        // ignore/强转待确认类决策在自动路径的落点是「不自动入账」：证据转待确认，
        // 与用户在待确认中心的显式处理衔接（RuleEngine 契约：ignore 只挡自动路径）。
        val patch = try {
            val decision = RuleEngine(database).evaluateAndRecord(
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
            RuleEngine(database).sanitizePatch(
                (decision as? RuleEngine.Decision.Continue)?.patch ?: RuleEngine.FieldPatch()
            )
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "分类规则执行失败，按无规则补全处理", error)
            RuleEngine.FieldPatch()
        }

        val accountId = resolveAccountId(record.sourcePackage, patch)
        if (accountId == null) {
            fallbackToPending(record, amountMinor, merchant, orderId, occurredAt, "NO_DEFAULT_ACCOUNT")
            return
        }
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
            JotBillLog.w(TAG, "相似度去重检查失败，转待确认", error)
            null
        }
        if (similar != null && similar.isNotEmpty()) {
            val selfHits = similar.filter { it.bill.sourceType == BILL_SOURCE_TYPE }
            if (selfHits.isNotEmpty()) {
                // 本管线二次抽取命中自己刚入账的账单（典型：支付宝/微信主结果页
                // 先只有金额，用户随后打开账单详情页，2000107/2000108 抽出商户/
                // 收支类型）—— 对齐小米服务端「指纹合并+字段补全」的本地形态：
                // 只补既有账单的空缺字段，金额/账户/类型绝不改动。
                enrichExistingBill(
                    existing = selfHits.maxByOrNull { it.bill.createdAt }!!.bill,
                    merchant = merchant,
                    orderId = orderId,
                    categoryId = categoryId
                )
                return
            }
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
     * 用户规则指定的账户优先（引用失效则弃用），否则按支付包名取种子账户：
     * 微信支付→微信零钱、支付宝→支付宝；其余渠道（云闪付/京东/美团/拼多多/
     * 抖音）的无障碍事件**读不到实际扣款账户**，兜底落「现金」保证自动入账
     * 闭环 —— 用户可用分类规则 set_account 精确覆盖，或在账单里改。
     * 兜底账户也不存在/停用时返回 null 转待确认。
     */
    private suspend fun resolveAccountId(
        sourcePackage: String,
        patch: RuleEngine.FieldPatch
    ): String? {
        patch.accountId?.let { candidate ->
            val account = database.accountDao().getById(candidate)
            if (account != null && account.isActive) return candidate
        }
        val accountId = when (sourcePackage) {
            "com.tencent.mm" -> WECHAT_PAY_ACCOUNT_ID
            "com.eg.android.AlipayGphone" -> ALIPAY_ACCOUNT_ID
            else -> CASH_FALLBACK_ACCOUNT_ID
        }
        val account = database.accountDao().getById(accountId) ?: return null
        return if (account.isActive) accountId else null
    }

    /**
     * businessTime 文本 → epoch 毫秒。覆盖内置规则产出的全部格式：
     * `2026年10月25日 14:30:25`、`10月25日 14:30`、`10-25 14:30`、`14:30`。
     * 只有时刻（HH:mm）时按当天解释；算出的时间超过参照时刻 60 分钟以上按
     * 前一天/前一年解释（跨零点支付、跨年短信）。全部失败回退抽取时刻。
     */
    private fun parseBusinessTimeMillis(raw: String?, fallback: Long): Long {
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

    private fun monthDayTime(text: String, now: LocalDateTime, zone: ZoneId): Long? {
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

    private fun dayTimeDash(text: String, now: LocalDateTime, zone: ZoneId): Long? {
        val matcher = DAY_DASH_TIME.matcher(text).takeIf { it.find() } ?: return null
        val month = matcher.group(1)!!.toInt()
        val day = matcher.group(2)!!.toInt()
        val hour = matcher.group(3)!!.toInt()
        val minute = matcher.group(4)!!.toInt()
        if (!isValidMonthDay(now.year, month, day)) return null
        val time = LocalDate.of(now.year, month, day).atTime(hour, minute)
        return time.atZone(zone).toInstant().toEpochMilli()
    }

    private fun hourMinuteOnly(text: String, now: LocalDateTime, zone: ZoneId): Long? {
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

    private fun isValidMonthDay(year: Int, month: Int, day: Int): Boolean = try {
        LocalDate.of(year, month, day)
        true
    } catch (_: java.time.DateTimeException) {
        false
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

        private const val MAX_TEXT_LENGTH = 120

        private const val CROSS_DAY_TOLERANCE_MINUTES = 60L

        /** 与 DefaultDataInitializer 种子账户一致（CheckoutPaymentMatcher 同源约定）。 */
        private const val WECHAT_PAY_ACCOUNT_ID = "default-account-wechat"

        private const val ALIPAY_ACCOUNT_ID = "default-account-alipay"

        /** 非微信/支付宝渠道的入账兜底账户（现金）。 */
        private const val CASH_FALLBACK_ACCOUNT_ID = "default-account-cash"

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
    }
}

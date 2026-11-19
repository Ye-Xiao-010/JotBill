package com.jotbill.app.bookkeeping

import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.JotBillDatabaseProvider
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.notification.PaymentEvidenceInput
import com.jotbill.app.notification.PaymentResultNormalizer
import com.jotbill.app.recognition.CheckoutPaymentMatcher
import com.jotbill.app.recognition.PaymentNotificationFallbackSignal
import com.jotbill.app.recognition.RefundLinker
import com.jotbill.app.rules.DuplicateGuard
import com.jotbill.app.rules.OrderIdNormalizer
import com.jotbill.app.utils.JotBillLog
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
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

    private val matcher by lazy { CheckoutPaymentMatcher(database) }

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
        // 页面退款记录（jotbillType="refund"）：走退款链路，不按收支入账。
        if (record.jotbillType == "refund") {
            processRefundRecord(record, amountMinor)
            return
        }
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

        // 统一入账（2026-11-16 模型调整 / 2026-11-17 审查收敛）：
        // 页面规则与银行短信都作为「信号」交给 SignalBillWriter —— 首笔直接成账，
        // 后续同额信号 60 秒内补全账户/商户；规则 send_to_review 转待确认。
        val channel = channelLabel(record.sourcePackage)
        val bankHint = record.fields["bank"]
            ?: record.fields["orgName"]?.takeIf { it.contains("银行") }
        val accountHints = if (record.sourcePackage == SMS_SOURCE_PACKAGE) {
            listOfNotNull(
                bankHint,
                record.fields["card"]?.takeIf { it.isNotBlank() }
            )
        } else {
            emptyList()
        }
        val sourceType = if (record.sourcePackage == SMS_SOURCE_PACKAGE) {
            "sms"
        } else {
            BILL_SOURCE_TYPE
        }
        val sourceEventKey = record.sourceEventKey
            ?: "abk:${record.ruleId}:${record.extractTime}:$amountMinor"
        val outcome = try {
            SignalBillWriter(context, database).recordPayment(
                SignalBillWriter.PaymentSignal(
                    amountMinor = amountMinor,
                    occurredAt = occurredAt,
                    merchant = merchant,
                    orderId = orderId,
                    platform = record.sourcePackage,
                    channelLabel = channel,
                    sourcePackage = record.sourcePackage,
                    sourceType = sourceType,
                    type = type,
                    accountHints = accountHints,
                    sourceEventKey = sourceEventKey,
                    bankCard = record.bankCard
                )
            )
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            JotBillLog.w(TAG, "信号入账异常，转待确认", error)
            fallbackToPending(
                record, amountMinor, merchant, orderId, occurredAt, "AUTO_BOOK_FAILED"
            )
            return
        }
        when (outcome) {
            is SignalBillWriter.Outcome.Created -> {
                // P1：现金兜底可识别标记（账户归属来自推断链链尾，供 UI 提示纠正）。
                val settled = try {
                    database.billDao().getById(outcome.billId)
                } catch (error: RuntimeException) {
                    JotBillLog.w(TAG, "入账后读取账单失败(仅审计标记跳过)", error)
                    null
                }
                if (settled?.accountId == CASH_FALLBACK_ACCOUNT_ID) {
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
                                createdAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
                JotBillLog.i(
                    TAG,
                    "自动入账完成: billId=${settled?.id?.take(8)}, amount=$amountMinor, " +
                        "type=$type, rule=${record.ruleId}(${record.ruleNote}), " +
                        "account=${settled?.accountId}"
                )
            }

            is SignalBillWriter.Outcome.Enriched -> {
                JotBillLog.i(
                    TAG,
                    "后续信号补全既有账单: bill=${outcome.billId.take(8)}, " +
                        "amount=$amountMinor, rule=${record.ruleId}"
                )
            }

            is SignalBillWriter.Outcome.Duplicate -> {
                JotBillLog.i(
                    TAG,
                    "重复信号已处理过，跳过: amount=$amountMinor, rule=${record.ruleId}"
                )
            }

            is SignalBillWriter.Outcome.Ignored -> {
                JotBillLog.i(
                    TAG,
                    "规则决策=忽略，丢弃自动记账记录: rule=${record.ruleId}, " +
                        "amount=$amountMinor, reason=${outcome.reason}"
                )
            }

            is SignalBillWriter.Outcome.NeedsReview -> {
                fallbackToPending(
                    record, amountMinor, merchant, orderId, occurredAt, outcome.reason
                )
            }

            is SignalBillWriter.Outcome.Failed -> {
                fallbackToPending(
                    record, amountMinor, merchant, orderId, occurredAt, outcome.reason
                )
            }
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

        // ── 兜底：转待确认（弹窗管线已被认领让路时，证据不能丢）──────────────

    /**
     * 页面退款记录（拼多多退款详情页等）→ 退款链路。
     *
     * 规则资产以 `jotbillType="refund"` 标注；退款详情页没有订单号，证据交
     * [RefundLinker]：原单关联不上时落待确认卡（ORIGINAL_MISSING），不静默丢。
     * 页面身份按「包名+规则+金额+渠道」记忆 7 天，重复扫描/回访不重复建卡
     * （见 [RefundPageMemory]）。
     */
    private suspend fun processRefundRecord(
        record: AutoBookkeepingEngine.ExtractedRecord,
        amountMinor: Long
    ) {
        val channel = record.fields["refundChannel"]?.trim()?.takeIf { it.isNotEmpty() }
        val memoryKey =
            "page-refund|${record.sourcePackage}|${record.ruleId}|$amountMinor|${channel.orEmpty()}"
        if (RefundPageMemory.isSeen(context, memoryKey)) {
            JotBillLog.i(
                TAG,
                "页面退款页已处理过(7天内)，跳过: amount=$amountMinor, channel=$channel"
            )
            return
        }
        val evidence = PaymentEvidenceInput(
            sourcePackage = record.sourcePackage,
            amountMinor = amountMinor,
            occurredAt = record.extractTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
            merchant = channelLabel(record.sourcePackage),
            orderId = null,
            paymentStatus = PaymentResultNormalizer.PAYMENT_STATUS_REFUND,
            sourceEventKey = sha256Text(memoryKey),
            rawTextHash = sha256Text("page-refund-text|${record.ruleId}|$amountMinor"),
            receivingAccountHint = channel
        )
        val outcome = try {
            RefundLinker(database, appContext = context).processRefund(evidence)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            JotBillLog.w(TAG, "页面退款证据处理失败", error)
            return
        }
        // 只在终态标记记忆：入账失败/转待确认时保留重试机会，不锁死 7 天。
        if (
            outcome is RefundLinker.RefundOutcome.AutoCommitted ||
            outcome is RefundLinker.RefundOutcome.Enriched ||
            outcome is RefundLinker.RefundOutcome.Duplicate ||
            outcome is RefundLinker.RefundOutcome.Ignored
        ) {
            RefundPageMemory.mark(context, memoryKey)
        }
        JotBillLog.i(
            TAG,
            "页面退款证据处理完成: amount=$amountMinor, channel=$channel, " +
                "rule=${record.ruleId}, outcome=${outcome.javaClass.simpleName}"
        )
    }

    private fun sha256Text(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }


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
                sourceType = if (record.sourcePackage == SMS_SOURCE_PACKAGE) {
                    "SMS"
                } else {
                    "ACCESSIBILITY"
                },
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

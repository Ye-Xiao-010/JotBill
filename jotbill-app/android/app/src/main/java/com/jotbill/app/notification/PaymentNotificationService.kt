package com.jotbill.app.notification

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jotbill.app.data.JotBillDatabaseProvider
import com.jotbill.app.recognition.CheckoutAccessibilityService
import com.jotbill.app.recognition.CheckoutPaymentMatcher
import com.jotbill.app.recognition.NOTIFICATION_FALLBACK_GRACE_MILLIS
import com.jotbill.app.recognition.FeeExtractor
import com.jotbill.app.recognition.PaymentNotificationFallbackSignal
import com.jotbill.app.recognition.RefundLinker
import com.jotbill.app.recognition.TransferClassifier
import com.jotbill.app.recognition.PurchaseIntentCoordinationResult
import com.jotbill.app.recognition.PurchaseIntentResultCallback
import com.jotbill.app.utils.JotBillLog
import com.jotbill.app.utils.SentryLogger
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 支付通知监听服务。
 *
 * 阶段四重写：从"收到通知立即弹窗"改为"解析→生成证据→落库→交匹配决策"。
 *
 * 工作流程：
 * 1. 用户授权通知读取权限后，系统自动启动此服务
 * 2. 收到通知时，用 PaymentResultNormalizer 解析为结构化 PaymentEvidenceInput
 * 3. 排除收款、到账、红包等非消费语义
 * 4. 有活跃结算会话时，通过 CheckoutPaymentMatcher 落库 PaymentEvidence 并触发匹配
 * 5. 无活跃会话时，通过 PaymentNotificationFallbackSignal 触发回退路径
 * 6. 不直接弹窗、不直接写 Bill、不直接更新账户余额
 */
class PaymentNotificationService : NotificationListenerService() {

    /** 协程作用域，用于异步落库证据和触发匹配 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** N-0b：未命中语义的通知的限流记录（key = 包名|标题），见 [logUnmatchedNotification]。 */
    private val unmatchedLogAt = mutableMapOf<String, Long>()

    /**
     * 服务创建时调用。
     */
    override fun onCreate() {
        super.onCreate()
        SentryLogger.i(TAG, "支付通知服务启动（阶段四重写）")
        SentryLogger.addBreadcrumb("PaymentNotificationService 启动", category = "service_lifecycle")
        JotBillLog.i(TAG, "支付通知服务已创建（阶段四：解析→证据→决策）")
    }

    /**
     * 服务销毁时调用。
     */
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        SentryLogger.i(TAG, "支付通知服务销毁")
        JotBillLog.i(TAG, "支付通知服务已销毁")
    }

    /**
     * 服务连接时调用。
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        JotBillLog.i(TAG, "通知监听服务已连接")
        SentryLogger.addBreadcrumb("通知监听服务已连接", category = "service_lifecycle")
    }

    /**
     * 服务断开时调用。
     *
     * 国产 ROM 杀后台后系统可能解除绑定且不自动重连，此后支付/退款/转账/手续费
     * 四类通知证据全部停止采集且无任何痕迹。按官方推荐在此请求重绑；
     * requestRebind 为 API 24+（minSdk 24，无需版本守卫）。
     */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        JotBillLog.w(TAG, "通知监听服务已断开，请求重绑")
        SentryLogger.addBreadcrumb("通知监听服务已断开，请求重绑", category = "service_lifecycle")
        runCatching {
            requestRebind(ComponentName(this, PaymentNotificationService::class.java))
        }.onFailure { error ->
            JotBillLog.w(TAG, "通知监听服务重绑请求失败", error)
            SentryLogger.addBreadcrumb("通知监听服务重绑请求失败: ${error.message}", category = "service_lifecycle")
        }
    }

    /**
     * 收到新通知时调用。
     *
     * @param sbn 状态栏通知对象
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            processNotification(sbn)
        } catch (error: Exception) {
            JotBillLog.e(TAG, "处理通知时发生错误", error)
            SentryLogger.e(TAG, "处理通知时发生错误", error)
        }
    }

    /**
     * 通知被移除时调用。阶段四暂不处理移除事件。
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // 阶段四不处理通知移除
    }

    /**
     * 处理通知：解析→排除非消费→生成证据→落库或回退。
     *
     * @param sbn 状态栏通知对象
     */
    private fun processNotification(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        // N-0c：先过内置解析器白名单，再过用户配置（设置页「监听应用」）。
        // 只做收窄：配置里没有解析规则的包永远不会放行，添加即无效需给用户提示。
        if (packageName !in PaymentResultNormalizer.SUPPORTED_PACKAGES) {
            if (PaymentNotificationConfig.isUnsupportedButConfigured(this, packageName)) {
                JotBillLog.w(
                    TAG,
                    "该应用已在设置页开启监听，但暂无解析规则，不会产生记录: pkg=$packageName"
                )
            }
            return
        }
        if (!PaymentNotificationConfig.isAppMonitored(this, packageName)) {
            JotBillLog.i(TAG, "该应用已在设置页关闭监听，跳过: pkg=$packageName")
            return
        }

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // 提取通知各字段
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val titleBig = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val tickerText = notification.tickerText?.toString() ?: ""

        // 2026-10-29 观测补强（纯诊断）：支付宝通知"到没到、长什么样"此前完全不可见
        // —— 解析失败只写 Sentry、不落 diag，导致 0.0.103 的"支付宝零痕迹"无法
        // 区分"没通知/解析失败/黑窗内"。只记长度与 title 摘要，不记正文。
        val isAlipay = packageName == PaymentResultNormalizer.ALIPAY_PACKAGE
        if (isAlipay) {
            JotBillLog.i(
                TAG,
                "支付宝通知到达: title=${title.take(30)}, textLen=${text.length}, " +
                    "bigTextLen=${bigText.length}, subTextLen=${subText.length}, " +
                    "summaryLen=${summaryText.length}, tickerLen=${tickerText.length}"
            )
        }

        // W-3：微信身份闸在分发入口统一过一次，覆盖退款/手续费/转账/支付全部四条路径。
        // 聊天/群/公众号消息与支付通知共用同一个包名，正文里出现「退款」「转账」等词
        // 会走错分支；`normalize` 内另有同源判据作为解析层不变式。
        val fullContent = listOf(title, titleBig, text, bigText, subText, summaryText, tickerText)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (!PaymentResultNormalizer.wechatIdentityOk(packageName, fullContent)) {
            JotBillLog.d(
                TAG,
                "微信通知缺少「微信支付」身份标识，不参与账务解析: title=${title.take(30)}"
            )
            return
        }

        // 阶段五 T2：退款语义优先识别（此前退款被非消费排除丢弃，现路由到退款链路）
        val refundInput = PaymentResultNormalizer.normalizeRefund(
            sourcePackage = packageName,
            title = title,
            titleBig = titleBig,
            text = text,
            bigText = bigText,
            subText = subText,
            summaryText = summaryText,
            tickerText = tickerText,
            postTime = sbn.postTime,
            notificationKey = sbn.key,
            notificationId = sbn.id
        )
        if (refundInput != null) {
            JotBillLog.i(TAG, "解析到退款证据: 包名=${refundInput.sourcePackage}")
            scope.launch {
                handleRefundEvidence(refundInput)
            }
            return
        }

        // 阶段五 T4：手续费语义识别（含主交易拆分）：手续费独立成 fee 待确认，
        // 同通知携带的提现/转账/还款主交易继续走 T3 归属决策路径，两笔互不重复
        val feeParse = PaymentResultNormalizer.normalizeFee(
            sourcePackage = packageName,
            title = title,
            titleBig = titleBig,
            text = text,
            bigText = bigText,
            subText = subText,
            summaryText = summaryText,
            tickerText = tickerText,
            postTime = sbn.postTime,
            notificationKey = sbn.key,
            notificationId = sbn.id
        )
        if (feeParse != null) {
            JotBillLog.i(
                TAG,
                "解析到手续费证据: 包名=${feeParse.feePart.sourcePackage}, " +
                    "含主交易=${feeParse.mainPart != null}"
            )
            scope.launch {
                handleFeeEvidence(feeParse)
            }
            return
        }

        // 阶段五 T3：转账/还款语义识别（此前被非消费排除丢弃或误入消费路径，现路由到归属决策）
        val transferInput = PaymentResultNormalizer.normalizeTransfer(
            sourcePackage = packageName,
            title = title,
            titleBig = titleBig,
            text = text,
            bigText = bigText,
            subText = subText,
            summaryText = summaryText,
            tickerText = tickerText,
            postTime = sbn.postTime,
            notificationKey = sbn.key,
            notificationId = sbn.id
        )
        if (transferInput != null) {
            JotBillLog.i(TAG, "解析到转账/还款证据: 包名=${transferInput.sourcePackage}")
            scope.launch {
                handleTransferEvidence(transferInput)
            }
            return
        }

        // 解析为结构化证据输入
        // N-0c：支付关键词取用户配置，未配置/配置为空时回退内置表（不放大匹配面）。
        val configuredKeywords = PaymentNotificationConfig.filterKeywords(this)
        val paymentKeywords = if (configuredKeywords.isNotEmpty()) {
            configuredKeywords
        } else {
            PaymentResultNormalizer.paymentKeywords()
        }
        val evidenceInput = PaymentResultNormalizer.normalize(
            sourcePackage = packageName,
            title = title,
            titleBig = titleBig,
            text = text,
            bigText = bigText,
            subText = subText,
            summaryText = summaryText,
            tickerText = tickerText,
            postTime = sbn.postTime,
            notificationKey = sbn.key,
            notificationId = sbn.id,
            paymentKeywords = paymentKeywords
        ) ?: run {
            // 非消费通知或金额无效：Sentry 之外落一条 diag（支付宝尤为关键 ——
            // 此前失败零痕迹，无法与"通知没到"区分）。
            if (isAlipay) {
                JotBillLog.i(
                    TAG,
                    "支付宝通知未通过解析(非消费或金额无效): title=${title.take(30)}, " +
                        "textLen=${text.length}, bigTextLen=${bigText.length}"
                )
            }
            SentryLogger.addBreadcrumb(
                "通知未通过解析（非消费或金额无效）",
                data = mapOf(
                    "package_name" to packageName,
                    "filter_reason" to "unsupported_or_invalid_payment",
                    "title_digest" to title.take(30),
                    "title_big_digest" to (titleBig ?: "").take(30)
                ),
                category = "notification_filter"
            )
            logUnmatchedNotification(packageName, title, text, bigText)
            return
        }

        // N-2 证据留样：解析成功也要记标题与结构化字段摘要，否则真机上只看得到
        // 「解析到支付证据: 包名=…」，永远拿不到标题样本 —— 标题身份闸因此无法落地。
        // 隐私：只记标题截断与字段有无/摘要，不记通知正文原文。
        JotBillLog.i(
            TAG,
            "解析到支付证据: 包名=${evidenceInput.sourcePackage}, " +
                "title=${title.take(30)}, " +
                "orderId=${evidenceInput.orderId?.take(16) ?: "(无)"}, " +
                "merchant=${evidenceInput.merchant?.take(20) ?: "(无)"}, " +
                "amount=${evidenceInput.amountMinor}, " +
                "occurredAt=${evidenceInput.occurredAt}"
        )
        if (isAlipay) {
            JotBillLog.i(
                TAG,
                "支付宝通知解析成功: amount=${evidenceInput.amountMinor}分, title=${title.take(30)}"
            )
        }

        // 异步落库证据并触发匹配
        scope.launch {
            handlePaymentEvidence(evidenceInput)
        }
    }

    /**
     * N-0b：未命中任何语义路径的通知留痕，打开「通知到了但没规则」这个盲区。
     *
     * 此前这里只有 Sentry 面包屑，JotBillLog 里完全没有痕迹 —— 真机上微信支付通知
     * 到底到没到、文本长什么样，全靠推测。现在补一条可检索的日志。
     *
     * 隐私（沿用 [com.jotbill.app.bookkeeping.AutoBookkeepingEngine.logAnalyzeDiag]
     * 的既有约定）：日志正文含金额/订单号，故文本样本**只在 debug 构建**落盘，
     * release 只留判定结论；两种构建都记文本哈希前缀，便于关联重复通知。
     *
     * 限流：同 (包名, 标题) 30 秒内只记一条，避免微信聊天等高频通知刷屏。
     */
    private fun logUnmatchedNotification(
        packageName: String,
        title: String,
        text: String,
        bigText: String
    ) {
        val body = if (bigText.isNotBlank()) bigText else text
        val throttleKey = "$packageName|${title.take(30)}"
        val now = System.currentTimeMillis()
        val last = unmatchedLogAt[throttleKey] ?: 0L
        if (now - last < UNMATCHED_LOG_INTERVAL_MILLIS) return
        unmatchedLogAt[throttleKey] = now

        val sample = if (com.jotbill.app.BuildConfig.DEBUG) {
            body.replace('\n', '│').take(UNMATCHED_LOG_SAMPLE_LIMIT)
        } else {
            "(release 构建隐藏通知文本样本)"
        }
        val bodyHash = Integer.toHexString(body.hashCode())
        JotBillLog.i(
            TAG,
            "通知未命中任何语义路径: pkg=$packageName, title=${title.take(30)}, " +
                "len=${body.length}, hash=$bodyHash, 样本=$sample"
        )
        if (unmatchedLogAt.size > UNMATCHED_LOG_KEY_LIMIT) {
            // 有界：长期运行不会无限增长（标题种类有限，超限后清空即可）
            unmatchedLogAt.clear()
        }
    }

    /**
     * 处理解析后的支付证据：尝试关联活跃会话并落库，无会话时走回退路径。
     *
     * 回退不可用（无障碍服务未连接/超时/被拒）时，直接落纯通知待确认，
     * 确保支付先到但无法关联会话的证据不丢失。
     *
     * @param input 解析后的支付证据输入
     */
    private suspend fun handlePaymentEvidence(input: PaymentEvidenceInput) {
        // 构建回退信号，用于触发无障碍服务的购买意图回退
        val signal = PaymentNotificationFallbackSignal(
            paymentPackage = input.sourcePackage,
            amountMinor = input.amountMinor,
            occurredAt = input.occurredAt,
            merchant = input.merchant,
            orderId = input.orderId,
            rawTextHash = input.rawTextHash,
            sourceEventKey = input.sourceEventKey
        )

        // 通过无障碍服务触发回退，尝试关联活跃结算会话
        val result = tryFallbackSignal(signal)
        val acceptedByAccessibility = result is PurchaseIntentCoordinationResult.Accepted ||
            result is PurchaseIntentCoordinationResult.AiDispatched

            // 回退未被接受（服务不可用/超时/门禁拒绝）：证据不丢弃，落纯通知待确认
            if (!acceptedByAccessibility) {
                try {
                    val database = JotBillDatabaseProvider.get(applicationContext)
                    if (result == null) {
                        // 超时可能是无障碍链路仍在异步落库；短暂等待后按 sourceEventKey 复查，
                        // 避免同一次支付既落会话证据又落孤儿待确认。
                        delay(EVIDENCE_GRACE_MILLIS)
                    }
                    val evidenceAlreadyStored = database.paymentEvidenceDao()
                        .getBySourceEventKey(input.sourceEventKey) != null
                    if (evidenceAlreadyStored) {
                        JotBillLog.i(TAG, "支付证据已由无障碍链路落库，跳过纯通知兜底")
                        return
                    }
                    // N-0a：按真实落库结果记录。此前此处无条件打「已转纯通知待确认」，
                    // 而 D-3 闸门在 matcher 内部静默丢弃 ⇒ 日志与实际不符，真机排查会
                    // 照着它去找一条根本不存在的待确认。
                    val outcome = CheckoutPaymentMatcher(database).recordOrphanPaymentSignal(
                        signal, sourceType = "NOTIFICATION"
                    )
                    val summary = "通知证据落库结果=${outcome.kind}" +
                        (outcome.pendingId?.let { ", id=${it.take(16)}" } ?: "")
                    if (outcome.isAutoCommitted) {
                        JotBillLog.i(TAG, "支付通知回退不可用($result)，$summary（已自动入账）")
                    } else if (outcome.isStored) {
                        JotBillLog.i(TAG, "支付通知回退不可用($result)，$summary")
                    } else {
                        JotBillLog.w(TAG, "支付通知回退不可用($result)，$summary（未产生新待确认）")
                    }
                } catch (error: RuntimeException) {
                    JotBillLog.w(TAG, "纯通知待确认写入失败", error)
                }
            }

        // 记录处理结果
        val resultInfo = mapOf(
            "package" to input.sourcePackage,
            "fallbackResult" to (result?.javaClass?.simpleName ?: "TIMEOUT_OR_UNAVAILABLE")
        )
        SentryLogger.addBreadcrumb(
            "支付证据处理完成",
            data = resultInfo,
            category = "payment_evidence"
        )
        JotBillLog.i(TAG, "支付证据处理完成: $resultInfo")
    }

    /**
     * 阶段五 T2：处理退款证据。
     *
     * 退款与结算会话无关（通常在购买后异步到达），不走无障碍回退和会话匹配，
     * 直接交给 RefundLinker 做原交易关联决策：
     * 原交易号唯一命中且累计未超原实付时自动入账（type=refund 冲减原支出），
     * 其余场景进入待确认中心，不静默丢弃也不静默入账。
     *
     * @param input 解析后的退款证据输入
     */
    private suspend fun handleRefundEvidence(input: PaymentEvidenceInput) {
        try {
            val database = JotBillDatabaseProvider.get(applicationContext)
            val outcome = RefundLinker(database).processRefund(input)
            val outcomeInfo = mapOf(
                "package" to input.sourcePackage,
                "outcome" to outcome.javaClass.simpleName
            )
            SentryLogger.addBreadcrumb(
                "退款证据处理完成",
                data = outcomeInfo,
                category = "refund_evidence"
            )
            JotBillLog.i(TAG, "退款证据处理完成: $outcomeInfo")
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "退款证据处理失败", error)
            SentryLogger.addBreadcrumb(
                "退款证据处理失败",
                data = mapOf("package" to input.sourcePackage),
                category = "refund_evidence"
            )
        }
    }

    /**
     * 阶段五 T3：处理转账/还款证据。
     *
     * 转账/还款与结算会话无关（无购物上下文），不走无障碍回退和会话匹配，
     * 直接交给 TransferClassifier 做方向与账户归属决策：
     * - 转账：源与目标均精确匹配启用账户且源≠目标 → 自动入账（双账户调整，不计收支）；
     * - 还款：目标为负债账户 → 自动入账（源扣减、欠款减少，不计支出）；
     * - 他人转账、账户无法解析、约束不满足 → 待确认（ACCOUNT_UNRESOLVED），
     *   不自动判定为自有账户转账，不静默入账。
     *
     * @param input 解析后的转账/还款证据输入
     */
    private suspend fun handleTransferEvidence(input: PaymentEvidenceInput) {
        try {
            val database = JotBillDatabaseProvider.get(applicationContext)
            val outcome = TransferClassifier(database).processTransfer(input)
            val outcomeInfo = mapOf(
                "package" to input.sourcePackage,
                "paymentStatus" to input.paymentStatus,
                "outcome" to outcome.javaClass.simpleName
            )
            SentryLogger.addBreadcrumb(
                "转账/还款证据处理完成",
                data = outcomeInfo,
                category = "transfer_evidence"
            )
            JotBillLog.i(TAG, "转账/还款证据处理完成: $outcomeInfo")
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "转账/还款证据处理失败", error)
            SentryLogger.addBreadcrumb(
                "转账/还款证据处理失败",
                data = mapOf("package" to input.sourcePackage),
                category = "transfer_evidence"
            )
        }
    }

    /**
     * 阶段五 T4：处理手续费证据（含同通知主交易拆分）。
     *
     * 手续费金额小、特征弱，一律进入待确认，不自动入账：
     * - 手续费独立成 type=fee 待确认（计入支出，确认后扣减源账户余额）；
     * - 伴随的提现/转账/还款主交易交回 TransferClassifier 走既有归属决策，
     *   主账单已存在时由 FeeExtractor 关联（originalBillId），未入账时记提示，不阻塞确认；
     * - 金额解析失败整条进待确认并标记 FEE_AMOUNT_UNPARSED，不强行入账。
     *
     * @param parse 手续费通知拆分结果（feePart 必有，mainPart 可空）
     */
    private suspend fun handleFeeEvidence(parse: FeeNotificationParse) {
        try {
            val database = JotBillDatabaseProvider.get(applicationContext)
            val outcome = FeeExtractor(database).processFee(parse.feePart, parse.mainPart)
            val outcomeInfo = mapOf(
                "package" to parse.feePart.sourcePackage,
                "outcome" to outcome.javaClass.simpleName
            )
            SentryLogger.addBreadcrumb(
                "手续费证据处理完成",
                data = outcomeInfo,
                category = "fee_evidence"
            )
            JotBillLog.i(TAG, "手续费证据处理完成: $outcomeInfo")
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "手续费证据处理失败", error)
            SentryLogger.addBreadcrumb(
                "手续费证据处理失败",
                data = mapOf("package" to parse.feePart.sourcePackage),
                category = "fee_evidence"
            )
        }

        // 主交易与手续费是两笔独立语义：主交易继续走 T3 转账/还款归属决策，
        // 两者的 sourceEventKey 已通过语义后缀（fee/main）互相区分，不会重复入账
        parse.mainPart?.let { mainPart ->
            try {
                handleTransferEvidence(mainPart)
            } catch (error: RuntimeException) {
                JotBillLog.w(TAG, "手续费伴随主交易处理失败", error)
            }
        }
    }

    /**
     * 通过无障碍服务触发支付通知回退信号。
     *
     * 回退是尽力路由：无障碍服务未连接、没有稳定结算页或快照门禁失败时安全返回。
     *
     * @param signal 支付通知回退信号
     * @return 回退结果描述
     */
    private suspend fun tryFallbackSignal(
        signal: PaymentNotificationFallbackSignal
    ): PurchaseIntentCoordinationResult? {
        return try {
            withTimeoutOrNull(FALLBACK_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine { cont: CancellableContinuation<PurchaseIntentCoordinationResult> ->
                    CheckoutAccessibilityService.requestPaymentNotificationFallbackSignal(
                        signal,
                        PurchaseIntentResultCallback { result ->
                            if (cont.isActive) {
                                cont.resume(result)
                            }
                        }
                    )
                }
            }
        } catch (_: RuntimeException) {
            JotBillLog.w(TAG, "支付通知回退入口不可用")
            null
        }
    }

    companion object {
        private const val TAG = "PaymentNotificationService"
        private const val FALLBACK_TIMEOUT_MILLIS = 5_000L

        /**
         * 证据落库复查等待：从 1s 提到 [NOTIFICATION_FALLBACK_GRACE_MILLIS](8s)
         * （2026-10-28 修订 D-3）。HyperOS 2 支付期无障碍死区可达 155s，通知
         * 通常比无障碍证据早到 —— 这里等 8s 给主动扫描/漏点事件一个先落库的
         * 机会；超窗仍查无该 sourceEventKey ⇒ 判定无障碍通道本次失守，
         * 以 NOTIFICATION_FALLBACK 标记兜底落卡（闸侧豁免，见 CheckoutContract）。
         */
        private val EVIDENCE_GRACE_MILLIS = NOTIFICATION_FALLBACK_GRACE_MILLIS

        /** N-0b：同 (包名, 标题) 的未命中通知的最小日志间隔。 */
        private const val UNMATCHED_LOG_INTERVAL_MILLIS = 30_000L

        /** N-0b：debug 构建落盘的通知文本样本长度上限。 */
        private const val UNMATCHED_LOG_SAMPLE_LIMIT = 300

        /** N-0b：限流表容量上限，超出后整表清空（标题种类有限，无需 LRU）。 */
        private const val UNMATCHED_LOG_KEY_LIMIT = 200

        /** Accepted 或 AiDispatched 表示无障碍链路已接收证据，其余情况才进入纯通知待确认。 */

        /** 广播 Action：检测到支付通知（保留兼容，供旧模块监听） */
        const val ACTION_PAYMENT_DETECTED = "com.jotbill.app.PAYMENT_DETECTED"

        /** 广播 Extra Key：支付数据 JSON */
        const val EXTRA_PAYMENT_DATA = "payment_data"
    }
}

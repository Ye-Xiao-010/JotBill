package com.jotbill.app.notification

import android.app.Notification
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jotbill.app.bookkeeping.AutoBookkeeper
import com.jotbill.app.bookkeeping.BankSmsNotificationParser
import com.jotbill.app.bookkeeping.BookkeepingChannels
import com.jotbill.app.bookkeeping.PageRuleRegistry
import com.jotbill.app.bookkeeping.SignalBillWriter
import com.jotbill.app.data.JotBillDatabaseProvider
import com.jotbill.app.recognition.CheckoutAccessibilityService
import com.jotbill.app.recognition.CheckoutPaymentMatcher
import com.jotbill.app.recognition.NOTIFICATION_FALLBACK_GRACE_MILLIS
import com.jotbill.app.recognition.FeeExtractor
import com.jotbill.app.recognition.PaymentNotificationFallbackSignal
import com.jotbill.app.recognition.RecognitionPackages
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

    /** 银行短信的规则解析投递口（通知监听链路；与无障碍引擎写入同源、同幂等口径）。 */
    private val smsBookkeeper by lazy { AutoBookkeeper(applicationContext, scope) }

    /** 同一条短信通知可能被系统重复投递（更新/重建），按 key+正文摘要去重。 */
    private val recentSmsSignatures = LinkedHashSet<String>()

    /** 拼多多通知样本去重签名（同 key+内容只记一次；营销推送高频，靠去重防刷屏）。 */
    private val recentPddSignatures = LinkedHashSet<String>()

    /**
     * 活跃通知周期补扫（真机 2026-11-16）：系统偶发「监听已连接但不投递回调」
     * （安装/省电策略后常见），仅靠连接时补扫会漏掉之后到达的短信。
     * 连接期间每 60 秒扫一次活跃通知，幂等键防止重复入账。
     */
    private val catchUpHandler = Handler(Looper.getMainLooper())

    private val catchUpRunnable = object : Runnable {
        override fun run() {
            try {
                catchUpActiveNotifications()
            } catch (error: RuntimeException) {
                JotBillLog.w(TAG, "周期补扫活跃通知失败", error)
            }
            catchUpHandler.postDelayed(this, ACTIVE_NOTIFICATION_CATCHUP_INTERVAL_MILLIS)
        }
    }

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
        isListenerConnected = false
        catchUpHandler.removeCallbacks(catchUpRunnable)
        scope.cancel()
        SentryLogger.i(TAG, "支付通知服务销毁")
        JotBillLog.i(TAG, "支付通知服务已销毁")
    }

    /**
     * 服务连接时调用。
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        isListenerConnected = true
        JotBillLog.i(TAG, "通知监听服务已连接")
        SentryLogger.addBreadcrumb("通知监听服务已连接", category = "service_lifecycle")
        // 跨通道收口（2026-11-16）：通知监听通常比无障碍更早连上，把「卡与同额
        // 账单并存」的历史遗留卡在此收口，避免用户确认后双记。
        scope.launch {
            try {
                val matcher = CheckoutPaymentMatcher(JotBillDatabaseProvider.get(applicationContext))
                val reconciled = matcher.reconcilePendingAgainstRecentBills()
                if (reconciled > 0) {
                    JotBillLog.i(TAG, "启动期跨通道收口: 忽略同额待确认卡=$reconciled")
                }
            } catch (error: RuntimeException) {
                JotBillLog.w(TAG, "启动期跨通道收口失败", error)
            }
            // 补扫活跃短信通知（真机 2026-11-16）：系统在后台可能不投递通知回调，
            // 连接时把近 10 分钟仍活跃的短信通知补处理一次 —— 退款证据按
            // 「包名+时间桶+金额」幂等，支出重复投递走相似度吸收，不会双记。
            try {
                catchUpActiveNotifications()
            } catch (error: RuntimeException) {
                JotBillLog.w(TAG, "监听连接补扫短信通知失败", error)
            }
        }
        // 周期补扫：覆盖「已连接但不投递回调」的僵尸态（60 秒一次，幂等防重）。
        catchUpHandler.removeCallbacks(catchUpRunnable)
        catchUpHandler.postDelayed(catchUpRunnable, ACTIVE_NOTIFICATION_CATCHUP_INTERVAL_MILLIS)
    }

    /** 扫描当前活跃通知并补处理记账相关包（mms/拼多多/京东/美团，10 分钟内）。 */
    private fun catchUpActiveNotifications() {
        if (!BookkeepingChannels.isMasterEnabled(this)) return
        val active = try {
            activeNotifications
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "读取活跃通知失败", error)
            return
        }
        val cutoff = System.currentTimeMillis() - SMS_ACTIVE_CATCHUP_WINDOW_MILLIS
        var processed = 0
        for (sbn in active.orEmpty()) {
            val catchUp = sbn.packageName in RecognitionPackages.BOOKKEEPING_NOTIFICATION_PACKAGES ||
                sbn.packageName == PDD_PACKAGE ||
                sbn.packageName == JdNotificationParser.PACKAGE ||
                sbn.packageName == MeituanNotificationParser.PACKAGE
            if (!catchUp) continue
            if (sbn.postTime < cutoff) continue
            when (sbn.packageName) {
                PDD_PACKAGE -> processPddNotification(sbn)
                JdNotificationParser.PACKAGE -> processJdNotification(sbn)
                MeituanNotificationParser.PACKAGE -> processMeituanNotification(sbn)
                else -> processBankSmsNotification(sbn)
            }
            processed += 1
        }
        if (processed > 0) {
            JotBillLog.i(TAG, "活跃通知补扫: count=$processed")
        }
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
        isListenerConnected = false
        catchUpHandler.removeCallbacks(catchUpRunnable)
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
        if (!shouldProcessSource(this, packageName)) return
        // 银行短信：真机（Android 17 / HyperOS）短信通知为静默通知，无障碍不投递
        // 通知事件 ⇒ 改由本监听器投递规则解析（见 BankSmsNotificationParser）。
        if (packageName in RecognitionPackages.BOOKKEEPING_NOTIFICATION_PACKAGES) {
            processBankSmsNotification(sbn)
            return
        }
        // 拼多多：支付环节无通知（已实证），只处理退款 —— 微信零钱/多多钱包退款
        // 没有银行短信时，这是唯一的退款信号。
        if (packageName == PDD_PACKAGE) {
            processPddNotification(sbn)
            return
        }
        // 京东：退款通知覆盖「退到微信/京东钱包」等无银行短信的场景；支付通知不入账。
        if (packageName == JdNotificationParser.PACKAGE) {
            processJdNotification(sbn)
            return
        }
        // 美团：退款通知（「退款通知 / 退款状态提醒」）覆盖退到微信/美团余额的场景。
        if (packageName == MeituanNotificationParser.PACKAGE) {
            processMeituanNotification(sbn)
            return
        }
        // 只认内置解析器白名单：设置页的「监听应用/过滤关键词」配置 UI 已下线
        // （e577c6b），配置的写入方不复存在，继续读取只会让存量旧值静默收窄
        // 监听范围且用户无从修改。包名边界唯一事实源 = PaymentResultNormalizer。
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

        // 解析为结构化证据输入：支付关键词固定用内置表（用户配置 UI 已下线，
        // 见 processNotification 处说明）。默认值即 PAYMENT_KEYWORDS，显式传入仅为
        // 保持调用点可读性。
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
            paymentKeywords = PaymentResultNormalizer.paymentKeywords()
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
                    "title_len" to title.length,
                    "title_big_len" to titleBig.length
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
     * 银行短信渠道：通知监听链路解析（配置门 + 去重 + 退款优先 + 规则抽取）。
     *
     * 隐私：正文只在 debug 构建以**数字脱敏**样本落日志（卡号/金额/余额不落盘），
     * release 只记判定结论与长度。
     */
    private fun processBankSmsNotification(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        if (!BookkeepingChannels.shouldProcess(this, packageName)) {
            JotBillLog.i(
                TAG,
                "[ABK诊断] 短信通知被渠道闸丢弃: pkg=$packageName（总开关或银行短信渠道未开启）"
            )
            return
        }
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val titleBig = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString() ?: ""
        val tickerText = notification.tickerText?.toString() ?: ""
        val content = listOf(title, titleBig, text, bigText, subText, summaryText, tickerText)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (content.isBlank()) return
        if (!rememberSmsNotification(sbn.key, content)) {
            JotBillLog.d(TAG, "短信通知重复投递，跳过: key=${sbn.key}")
            return
        }
        val sample = if (com.jotbill.app.BuildConfig.DEBUG) {
            "样本(数字脱敏)=" + content.replace(Regex("\\d"), "#").take(100)
        } else {
            ""
        }
        JotBillLog.i(
            TAG,
            "[ABK诊断] 短信通知到达: title=${title.take(20)}, 正文长度=${content.length}, $sample"
        )

        val refund = BankSmsNotificationParser.parseRefund(content, sbn.postTime)
        if (refund != null) {
            JotBillLog.i(
                TAG,
                "[ABK诊断] 短信判定为退款到账: amount=${refund.amountMinor}分, 转 RefundLinker"
            )
            scope.launch { handleRefundEvidence(refund) }
            return
        }

        val record = BankSmsNotificationParser.parseExpense(
            registry = PageRuleRegistry.get(this),
            content = content,
            extractTime = sbn.postTime
        )
        if (record == null) {
            JotBillLog.i(
                TAG,
                "[ABK诊断] 短信未命中规则: title=${title.take(20)}, 正文长度=${content.length}, $sample"
            )
            return
        }
        JotBillLog.i(
            TAG,
            "[ABK诊断] 短信命中规则: ruleId=${record.ruleId}(${record.ruleNote}), " +
                "bankCardEvidence=${record.bankCard != null}, cardType=${record.bankCard?.subtype}, 转自动入账"
        )
        smsBookkeeper.onRecordReady(record)
    }

    /** 同一通知被系统重复投递（更新/重建）时只解析一次；有界集合防长期增长。 */
    private fun rememberSmsNotification(key: String, content: String): Boolean {
        val signature = "$key|${content.hashCode()}"
        synchronized(recentSmsSignatures) {
            if (!recentSmsSignatures.add(signature)) return false
            if (recentSmsSignatures.size > SMS_SIGNATURE_CAPACITY) {
                val iterator = recentSmsSignatures.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
        return true
    }

    /**
     * 拼多多通知：支付环节无通知（真机实证），只解析退款；其余保持 debug 采样。
     */
    private fun processPddNotification(sbn: StatusBarNotification) {
        if (!BookkeepingChannels.shouldProcess(this, PddNotificationParser.PACKAGE)) {
            JotBillLog.i(TAG, "[ABK诊断] 拼多多通知被渠道闸丢弃（pdd 渠道或总开关关闭）")
            return
        }
        val content = notificationContentOf(sbn) ?: return
        val refund = PddNotificationParser.parseRefund(content, sbn.postTime)
        if (refund != null) {
            deliverRefundNotification(refund, "拼多多")
            return
        }
        logPddNotificationSample(sbn)
    }

    /**
     * 京东通知：只解析退款（支付通知不入账）；非退款通知 debug 采样。
     */
    private fun processJdNotification(sbn: StatusBarNotification) {
        if (!BookkeepingChannels.shouldProcess(this, JdNotificationParser.PACKAGE)) {
            JotBillLog.i(TAG, "[ABK诊断] 京东通知被渠道闸丢弃（jd 渠道或总开关关闭）")
            return
        }
        val content = notificationContentOf(sbn) ?: return
        val refund = JdNotificationParser.parseRefund(content, sbn.postTime)
        if (refund != null) {
            deliverRefundNotification(refund, "京东")
            return
        }
        if (com.jotbill.app.BuildConfig.DEBUG) {
            val sample = content.replace(Regex("\\d"), "#").take(140)
            JotBillLog.i(
                TAG,
                "[ABK诊断] 京东通知样本(数字脱敏): 长度=${content.length}, 样本=$sample"
            )
        }
    }

    /**
     * 美团通知：只解析退款（「退款通知 / 退款状态提醒」）；同一退款的两条通知
     * 由 [RefundNotificationMemory] 按「包名+金额」30 分钟去重。
     */
    private fun processMeituanNotification(sbn: StatusBarNotification) {
        if (!BookkeepingChannels.shouldProcess(this, MeituanNotificationParser.PACKAGE)) {
            JotBillLog.i(TAG, "[ABK诊断] 美团通知被渠道闸丢弃（meituan 渠道或总开关关闭）")
            return
        }
        val content = notificationContentOf(sbn) ?: return
        val refund = MeituanNotificationParser.parseRefund(content, sbn.postTime)
        if (refund != null) {
            deliverRefundNotification(refund, "美团")
            return
        }
        if (com.jotbill.app.BuildConfig.DEBUG) {
            val sample = content.replace(Regex("\\d"), "#").take(140)
            JotBillLog.i(
                TAG,
                "[ABK诊断] 美团通知样本(数字脱敏): 长度=${content.length}, 样本=$sample"
            )
        }
    }

    /** 通知退款统一交付：30 分钟内同包同额只建一张卡（处理成功后再写入去重记忆，避免异常丢单）。 */
    private fun deliverRefundNotification(evidence: PaymentEvidenceInput, sourceLabel: String) {
        val key = "${evidence.sourcePackage}|${evidence.amountMinor}"
        if (RefundNotificationMemory.isSeen(this, key)) {
            JotBillLog.i(
                TAG,
                "[ABK诊断] 同额退款通知重复(30分钟内)，跳过: $sourceLabel, amount=${evidence.amountMinor}"
            )
            return
        }
        JotBillLog.i(
            TAG,
            "[ABK诊断] $sourceLabel 退款通知命中: amount=${evidence.amountMinor}分, " +
                "channel=${evidence.receivingAccountHint}, 转 RefundLinker"
        )
        scope.launch {
            if (handleRefundEvidence(evidence)) {
                RefundNotificationMemory.mark(this@PaymentNotificationService, key)
            }
        }
    }

    /** 通知标题 + 正文 + bigText 等拼接全文（不含原始数字脱敏，日志侧自行处理）。 */
    private fun notificationContentOf(sbn: StatusBarNotification): String? {
        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val titleBig = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString() ?: ""
        val ticker = notification.tickerText?.toString() ?: ""
        return listOf(title, titleBig, text, bigText, subText, summaryText, ticker)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
    }

    /**
     * 诊断（debug 构建）：拼多多通知样本 —— 确认支付/退款通知的渠道、标题与文本形态，
     * 为后续是否接入解析提供真机依据。数字脱敏，release 构建不输出；按 key+内容去重。
     */
    private fun logPddNotificationSample(sbn: StatusBarNotification) {
        if (!com.jotbill.app.BuildConfig.DEBUG) return
        val content = notificationContentOf(sbn) ?: return
        val signature = "${sbn.key}|${content.hashCode()}"
        synchronized(recentPddSignatures) {
            if (!recentPddSignatures.add(signature)) return
            if (recentPddSignatures.size > PDD_SIGNATURE_CAPACITY) {
                val iterator = recentPddSignatures.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
        val sample = content.replace(Regex("\\d"), "#").take(140)
        JotBillLog.i(
            TAG,
            "[ABK诊断] 拼多多通知样本: channel=${sbn.notification?.channelId}, key=${sbn.key}, " +
                "长度=${content.length}, 样本(数字脱敏)=$sample"
        )
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
        if (!shouldProcessSource(this, input.sourcePackage)) return
        // 模型调整（2026-11-16）：支付通知作为首笔信号直接入账；同额后续信号
        // 在 SignalBillWriter 的 60 秒窗内补全，不再走会话匹配与待确认兜底。
        val channelLabel = AutoBookkeeper.channelLabel(input.sourcePackage)
        try {
            val database = JotBillDatabaseProvider.get(applicationContext)
            val outcome = SignalBillWriter(applicationContext, database).recordPayment(
                SignalBillWriter.PaymentSignal(
                    amountMinor = input.amountMinor,
                    occurredAt = input.occurredAt,
                    merchant = input.merchant,
                    orderId = input.orderId,
                    platform = input.sourcePackage,
                    channelLabel = channelLabel,
                    sourcePackage = input.sourcePackage,
                    sourceType = "notification",
                    accountHints = listOf(channelLabel),
                    sourceEventKey = input.sourceEventKey
                )
            )
            // 规则强制复核 / 入账失败：证据不得丢弃，落纯通知待确认由用户核对。
            if (outcome is SignalBillWriter.Outcome.NeedsReview ||
                outcome is SignalBillWriter.Outcome.Failed
            ) {
                val reason = when (outcome) {
                    is SignalBillWriter.Outcome.NeedsReview -> outcome.reason
                    is SignalBillWriter.Outcome.Failed -> outcome.reason
                    else -> ""
                }
                fallbackToPending(input, reason)
            }
            JotBillLog.i(
                TAG,
                "支付通知入账: outcome=${outcome.javaClass.simpleName}, " +
                    "amount=${input.amountMinor}, pkg=${input.sourcePackage}"
            )
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "支付通知入账失败", error)
            fallbackToPending(input, "NOTIFICATION_BOOK_FAILED")
        }
    }

    /** 信号无法自动入账时的待确认兜底（幂等由 orphan 卡的确定性 ID 承担）。 */
    private suspend fun fallbackToPending(input: PaymentEvidenceInput, reason: String) {
        try {
            CheckoutPaymentMatcher(JotBillDatabaseProvider.get(applicationContext))
                .recordOrphanPaymentSignal(
                    PaymentNotificationFallbackSignal(
                        paymentPackage = input.sourcePackage,
                        amountMinor = input.amountMinor,
                        occurredAt = input.occurredAt,
                        merchant = input.merchant,
                        orderId = input.orderId,
                        sourceEventKey = input.sourceEventKey,
                        rawTextHash = input.rawTextHash
                    ),
                    sourceType = "NOTIFICATION",
                    conflictReason = reason,
                    // 本次兜底的前提就是"自动入账不可行"（规则复核/失败）：
                    // 禁止 N-2 强证据路径再次尝试入账，否则 send_to_review 会被击穿。
                    suppressStrongEvidence = true
                )
            JotBillLog.i(
                TAG,
                "支付通知转待确认: reason=$reason, amount=${input.amountMinor}"
            )
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            JotBillLog.w(TAG, "支付通知转待确认失败，证据丢弃", error)
        }
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
    private suspend fun handleRefundEvidence(input: PaymentEvidenceInput): Boolean {
        if (!shouldProcessSource(this, input.sourcePackage)) return false
        return try {
            val database = JotBillDatabaseProvider.get(applicationContext)
            val outcome = RefundLinker(database, appContext = applicationContext).processRefund(input)
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
            true
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "退款证据处理失败", error)
            SentryLogger.addBreadcrumb(
                "退款证据处理失败",
                data = mapOf("package" to input.sourcePackage),
                category = "refund_evidence"
            )
            false
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
        if (!shouldProcessSource(this, input.sourcePackage)) return
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
        if (!shouldProcessSource(this, parse.feePart.sourcePackage)) return
        // 先处理伴随的提现/转账/还款主交易，确保主账单/待确认先行落库，
        // 随后 FeeExtractor 才能稳定关联 originalBillId。
        parse.mainPart?.let { mainPart ->
            try {
                handleTransferEvidence(mainPart)
            } catch (error: RuntimeException) {
                JotBillLog.w(TAG, "手续费伴随主交易处理失败", error)
            }
        }
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

        /** 银行短信通知去重签名容量（同通知重复投递按 key+正文摘要跳过）。 */
        private const val SMS_SIGNATURE_CAPACITY = 32

        /** 拼多多包名（支付/退款通知样本诊断，见 logPddNotificationSample）。 */
        private const val PDD_PACKAGE = "com.xunmeng.pinduoduo"

        internal val SUPPORTED_SOURCE_PACKAGES = PaymentResultNormalizer.SUPPORTED_PACKAGES +
            RecognitionPackages.BOOKKEEPING_NOTIFICATION_PACKAGES +
            setOf(PDD_PACKAGE, JdNotificationParser.PACKAGE, MeituanNotificationParser.PACKAGE)

        internal fun shouldProcessSource(context: android.content.Context, packageName: String): Boolean =
            packageName in SUPPORTED_SOURCE_PACKAGES && BookkeepingChannels.shouldProcess(context, packageName)

        @Volatile
        internal var isListenerConnected: Boolean = false
            private set

        /** 拼多多通知样本去重容量（同 key+内容只记一次）。 */
        private const val PDD_SIGNATURE_CAPACITY = 32

        /** 监听连接补扫活跃短信通知的时间窗（覆盖后台漏回调的常见时差）。 */
        private const val SMS_ACTIVE_CATCHUP_WINDOW_MILLIS = 10 * 60 * 1000L

        /** 活跃通知周期补扫间隔（覆盖「已连接但不投递回调」的僵尸态）。 */
        private const val ACTIVE_NOTIFICATION_CATCHUP_INTERVAL_MILLIS = 60_000L

        /** Accepted 或 AiDispatched 表示无障碍链路已接收证据，其余情况才进入纯通知待确认。 */

        /** 广播 Action：检测到支付通知（保留兼容，供旧模块监听） */
        const val ACTION_PAYMENT_DETECTED = "com.jotbill.app.PAYMENT_DETECTED"

        /** 广播 Extra Key：支付数据 JSON */
        const val EXTRA_PAYMENT_DATA = "payment_data"
    }
}

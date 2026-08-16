package com.jotbill.app.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.jotbill.app.data.JotBillDatabaseProvider
import com.jotbill.app.recognition.CheckoutAccessibilityService
import com.jotbill.app.recognition.CheckoutPaymentMatcher
import com.jotbill.app.recognition.PaymentNotificationFallbackSignal
import com.jotbill.app.recognition.RefundLinker
import com.jotbill.app.recognition.TransferClassifier
import com.jotbill.app.recognition.PurchaseIntentCoordinationResult
import com.jotbill.app.recognition.PurchaseIntentResultCallback
import com.jotbill.app.utils.SentryLogger
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

    /**
     * 服务创建时调用。
     */
    override fun onCreate() {
        super.onCreate()
        SentryLogger.i(TAG, "支付通知服务启动（阶段四重写）")
        SentryLogger.addBreadcrumb("PaymentNotificationService 启动", category = "service_lifecycle")
        Log.i(TAG, "支付通知服务已创建（阶段四：解析→证据→决策）")
    }

    /**
     * 服务销毁时调用。
     */
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        SentryLogger.i(TAG, "支付通知服务销毁")
        Log.i(TAG, "支付通知服务已销毁")
    }

    /**
     * 服务连接时调用。
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "通知监听服务已连接")
        SentryLogger.addBreadcrumb("通知监听服务已连接", category = "service_lifecycle")
    }

    /**
     * 服务断开时调用。
     */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "通知监听服务已断开")
        SentryLogger.addBreadcrumb("通知监听服务已断开", category = "service_lifecycle")
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
            Log.e(TAG, "处理通知时发生错误", error)
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
        if (packageName !in PaymentResultNormalizer.SUPPORTED_PACKAGES) return

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
            Log.i(TAG, "解析到退款证据: 包名=${refundInput.sourcePackage}")
            scope.launch {
                handleRefundEvidence(refundInput)
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
            Log.i(TAG, "解析到转账/还款证据: 包名=${transferInput.sourcePackage}")
            scope.launch {
                handleTransferEvidence(transferInput)
            }
            return
        }

        // 解析为结构化证据输入
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
            notificationId = sbn.id
        ) ?: run {
            // 非消费通知或金额无效，只记录非敏感的过滤结果。
            SentryLogger.addBreadcrumb(
                "通知未通过解析（非消费或金额无效）",
                data = mapOf(
                    "package_name" to packageName,
                    "filter_reason" to "unsupported_or_invalid_payment"
                ),
                category = "notification_filter"
            )
            return
        }

        Log.i(TAG, "解析到支付证据: 包名=${evidenceInput.sourcePackage}")

        // 异步落库证据并触发匹配
        scope.launch {
            handlePaymentEvidence(evidenceInput)
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
                CheckoutPaymentMatcher(database).recordOrphanPaymentSignal(
                    signal, sourceType = "NOTIFICATION"
                )
                Log.i(TAG, "支付通知回退不可用($result)，已转纯通知待确认")
            } catch (error: RuntimeException) {
                Log.w(TAG, "纯通知待确认写入失败", error)
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
        Log.i(TAG, "支付证据处理完成: $resultInfo")
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
            Log.i(TAG, "退款证据处理完成: $outcomeInfo")
        } catch (error: RuntimeException) {
            Log.w(TAG, "退款证据处理失败", error)
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
            Log.i(TAG, "转账/还款证据处理完成: $outcomeInfo")
        } catch (error: RuntimeException) {
            Log.w(TAG, "转账/还款证据处理失败", error)
            SentryLogger.addBreadcrumb(
                "转账/还款证据处理失败",
                data = mapOf("package" to input.sourcePackage),
                category = "transfer_evidence"
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
            Log.w(TAG, "支付通知回退入口不可用")
            null
        }
    }

    companion object {
        private const val TAG = "PaymentNotificationService"
        private const val FALLBACK_TIMEOUT_MILLIS = 5_000L

        /** Accepted 或 AiDispatched 表示无障碍链路已接收证据，其余情况才进入纯通知待确认。 */

        /** 广播 Action：检测到支付通知（保留兼容，供旧模块监听） */
        const val ACTION_PAYMENT_DETECTED = "com.jotbill.app.PAYMENT_DETECTED"

        /** 广播 Extra Key：支付数据 JSON */
        const val EXTRA_PAYMENT_DATA = "payment_data"
    }
}

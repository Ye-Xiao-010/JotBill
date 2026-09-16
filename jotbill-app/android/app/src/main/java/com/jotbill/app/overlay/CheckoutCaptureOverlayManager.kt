package com.jotbill.app.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import com.jotbill.app.recognition.CheckoutInvalidationReason
import com.jotbill.app.recognition.CheckoutObservationEvent
import com.jotbill.app.recognition.CheckoutPageRole
import com.jotbill.app.recognition.CheckoutPageDetector
import com.jotbill.app.recognition.CheckoutPageObservation
import com.jotbill.app.recognition.SnapshotCaptureRejection
import com.jotbill.app.recognition.SnapshotCaptureResult
import com.jotbill.app.recognition.SnapshotCaptureStatus
import com.jotbill.app.recognition.isSha256PageFingerprint
import kotlin.math.abs

/** 手动截图按钮的显示模式；当前页面仍必须通过结算页和敏感页门禁。 */
internal enum class CheckoutOverlayMode(val wireValue: String) {
    ALWAYS_ON("always_on"),
    CHECKOUT_ONLY("checkout_only"),
    OFF("off");

    companion object {
        fun parse(value: String?): CheckoutOverlayMode =
            entries.firstOrNull { it.wireValue == value } ?: CHECKOUT_ONLY
    }
}

/**
 * 判定"已捕获"状态是否处于保持窗口内，应忽略同包非结算页的状态回退。
 *
 * 京东结算页在 STORED 之后数十毫秒即抛出 NON_CHECKOUT_PAGE
 * （真机 .tmp-device-capture-20260823-163623 中 5 次 STORED 全部如此），
 * 若立刻把状态改回 READY，用户根本看不到"已捕获"反馈。
 *
 * @param isCapturedState 当前状态是否为 CAPTURED_WAITING_PAYMENT。
 * @param capturedAt 进入"已捕获"状态的时间戳；0 表示从未捕获。
 * @param now 当前时间戳。
 * @param holdMillis 保持窗口时长。
 * @return true 表示应保持"已捕获"，不执行状态回退。
 */
internal fun shouldHoldCapturedState(
    isCapturedState: Boolean,
    capturedAt: Long,
    now: Long,
    holdMillis: Long
): Boolean = isCapturedState && capturedAt > 0L && now - capturedAt < holdMillis

/**
 * 判定一次截图结果是否应更新悬浮按钮状态。
 *
 * 成功类结果（STORED / DUPLICATE）代表快照确已写入缓冲，
 * 只需确认购物包一致即可采纳：自动截图用的是稳定确认时刻的指纹，
 * 而页面指纹在结算页上持续漂移，若要求指纹相等则必然被丢弃。
 * 真机 .tmp-device-capture-20260823-154942 中自动截图 6 次 STORED
 * 却无一次更新按钮，用户因此反馈"明明已捕获但显示没有变化"。
 *
 * 失败类结果仍需指纹与会话严格匹配，否则旧页面的拒绝原因会覆盖
 * 当前页面的可用状态，把本可点击的按钮显示成不可用。
 *
 * @param isSuccessResult 是否为成功类结果。
 * @param currentFingerprint 悬浮层当前记录的页面指纹。
 * @param resultFingerprint 结果携带的页面指纹。
 * @param currentPackage 悬浮层当前记录的购物包名。
 * @param resultPackage 结果携带的购物包名。
 * @param currentSessionId 悬浮层当前记录的会话标识。
 * @param resultSessionId 结果携带的会话标识。
 * @return true 表示应采纳该结果并更新按钮状态。
 */
internal fun shouldAcceptOverlayCaptureResult(
    isSuccessResult: Boolean,
    currentFingerprint: String?,
    resultFingerprint: String?,
    currentPackage: String?,
    resultPackage: String?,
    currentSessionId: String?,
    resultSessionId: String?
): Boolean {
    // 跨购物包的结果一律不采纳：槽位按购物包划分，互不代表。
    if (currentPackage != null && resultPackage != null && currentPackage != resultPackage) {
        return false
    }
    if (isSuccessResult) {
        return true
    }
    if (currentFingerprint == null && resultFingerprint != null) {
        return false
    }
    if (
        currentFingerprint != null &&
        resultFingerprint != null &&
        currentFingerprint != resultFingerprint
    ) {
        return false
    }
    return !(
        currentSessionId != null &&
            resultSessionId != null &&
            currentSessionId != resultSessionId
        )
}

/** 悬浮按钮对 RN 暴露的无图片状态。 */
internal enum class CheckoutOverlayStatus(val wireValue: String) {
    HIDDEN("HIDDEN"),
    READY("READY"),
    CAPTURING("CAPTURING"),
    CAPTURED_WAITING_PAYMENT("CAPTURED_WAITING_PAYMENT"),
    EXPIRED_RETRY("EXPIRED_RETRY"),
    SENSITIVE("SENSITIVE"),
    UNAVAILABLE("UNAVAILABLE"),
    SERVICE_NOT_CONNECTED("SERVICE_NOT_CONNECTED")
}

/** 仅包含状态和安全元数据，不包含页面原文、Bitmap、ByteArray 或 Base64。 */
internal data class CheckoutOverlayState(
    val status: CheckoutOverlayStatus,
    val visible: Boolean,
    val enabled: Boolean,
    val mode: CheckoutOverlayMode,
    val reasonCode: String? = null,
    val shoppingPackage: String? = null,
    val pageFingerprint: String? = null,
    val sessionId: String? = null,
    val snapshotId: String? = null,
    val candidateRevision: Int? = null,
    val requiresDisambiguation: Boolean = false
)

/** 连接悬浮窗管理器和 RN 事件模块的进程内状态通道。 */
internal object CheckoutCaptureOverlayStateHub {
    @Volatile
    private var listener: ((CheckoutOverlayState) -> Unit)? = null

    @Synchronized
    fun setListener(listener: ((CheckoutOverlayState) -> Unit)?) {
        this.listener = listener
    }

    @Synchronized
    fun clearListener(listener: (CheckoutOverlayState) -> Unit) {
        if (this.listener === listener) {
            this.listener = null
        }
    }

    fun emit(state: CheckoutOverlayState) {
        val current = listener ?: return
        try {
            current(state)
        } catch (_: RuntimeException) {
            // RN 生命周期变化时允许事件消费者暂时不可用。
        }
    }
}

/**
 * 独立的结算手动截图悬浮按钮管理器。
 *
 * 它不复用支付通知的全屏浮窗，也不拥有截图字节；点击只通过回调请求已有截图协调器。
 */
internal class CheckoutCaptureOverlayManager(context: Context) {
    companion object {
        private const val TAG = "CheckoutCaptureOverlay"
        private const val PREFERENCES_NAME = "checkout_capture_overlay"
        private const val KEY_ENABLED = "manual_capture_enabled"
        private const val KEY_MODE = "manual_capture_mode"
        /**
         * 默认 always_on：识别失败的页面恰恰是手动断言截图最有价值的页面，
         * checkout_only 会让按钮在那些页面消失，断言通道无从触达。
         * 用户显式设置过的模式不受影响（仅未写入偏好时使用该默认）。
         */
        private const val DEFAULT_MODE = "always_on"

        /**
         * 手动截图点击的最小间隔。
         *
         * 截图结果最长可延迟约 2 秒返回，空窗期内用户看不到反馈会反复点击
         * （真机曾 2 秒内堆积 6 次请求）。取 800ms 既能挡住连点，
         * 也不会让用户觉得按钮迟钝。
         */
        private const val MANUAL_CAPTURE_MIN_INTERVAL_MILLIS = 800L

        /**
         * "已捕获"状态的最短保持时间。
         *
         * 京东结算页在 STORED 之后数十毫秒即抛出 NON_CHECKOUT_PAGE，
         * 若立刻把状态改回 READY，用户根本看不到"已捕获"反馈。
         * 保持窗口内忽略同包非结算页的状态回退，窗口结束后自然恢复。
         */
        private const val CAPTURED_STATE_HOLD_MILLIS = 3_000L

        /**
         * 结算页内容变化会短暂把页面判成普通页/节点不可读，立即移除按钮会造成
         * 高频 addView/removeView 闪烁。隐藏前保留一段时间，若期间页面重新稳定为
         * 可展示状态，则取消隐藏并继续复用同一个系统窗口。
         */
        private const val HIDE_GRACE_MILLIS = 1_500L

        @Volatile
        private var activeManager: CheckoutCaptureOverlayManager? = null

        fun hasOverlayPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(context.applicationContext)

        fun requestOverlayPermission(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return
            }
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.applicationContext.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.applicationContext.startActivity(intent)
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
            activeManager?.setEnabledOnMain(enabled)
        }

        fun setMode(context: Context, mode: CheckoutOverlayMode) {
            preferences(context).edit().putString(KEY_MODE, mode.wireValue).apply()
            activeManager?.setModeOnMain(mode)
        }

        fun getMode(context: Context): CheckoutOverlayMode =
            CheckoutOverlayMode.parse(preferences(context).getString(KEY_MODE, DEFAULT_MODE))

        fun getEnabled(context: Context): Boolean =
            preferences(context).getBoolean(KEY_ENABLED, true)

        fun currentState(context: Context): CheckoutOverlayState =
            activeManager?.state ?: CheckoutOverlayState(
                status = CheckoutOverlayStatus.SERVICE_NOT_CONNECTED,
                visible = false,
                enabled = getEnabled(context),
                mode = getMode(context),
                reasonCode = CheckoutOverlayStatus.SERVICE_NOT_CONNECTED.wireValue
            )

        private fun preferences(context: Context) =
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(appContext).scaledTouchSlop
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val pageDetector = CheckoutPageDetector()

    private var windowManager: WindowManager? = null
    private var overlayView: TextView? = null
    private var manualCaptureRequestHandler: (() -> Unit)? = null
    private var configuredEnabled = preferences.getBoolean(KEY_ENABLED, true)
    private var mode = CheckoutOverlayMode.parse(preferences.getString(KEY_MODE, DEFAULT_MODE))
    private var currentPackage: String? = null
    private var currentFingerprint: String? = null
    private var currentSupportedShoppingApp = false
    private var currentSensitivePage = false
    private var currentCheckout = false
    private var currentUserRetryContext = false
    private var closed = false
    private var hideScheduled = false

    /** 最近一次受理手动截图点击的时间，用于节流重复提交。 */
    private var lastManualCaptureRequestAt = 0L

    /** 最近一次进入"已捕获"状态的时间，用于保持窗口判定。 */
    private var currentCapturedAt = 0L

    /** 时间源，便于单测替换。 */
    private val systemClock: () -> Long = { System.currentTimeMillis() }

    private val pendingHideRunnable = Runnable {
        hideScheduled = false
        removeView()
    }

    @Volatile
    private var state = CheckoutOverlayState(
        status = CheckoutOverlayStatus.HIDDEN,
        visible = false,
        enabled = configuredEnabled && mode != CheckoutOverlayMode.OFF,
        mode = mode,
        reasonCode = "NO_ACTIVE_CHECKOUT"
    )

    private var downRawX = 0f
    private var downRawY = 0f
    private var downWindowX = 0
    private var downWindowY = 0
    private var moved = false

    init {
        activeManager = this
    }

    fun setManualCaptureRequestHandler(handler: (() -> Unit)?) {
        runOnMain {
            if (!closed) {
                manualCaptureRequestHandler = handler
            }
        }
    }

    fun onObservationEvent(event: CheckoutObservationEvent) {
        runOnMain {
            if (closed) {
                return@runOnMain
            }
            when (event) {
                is CheckoutObservationEvent.Stable -> handleStable(event.page.observation)
                is CheckoutObservationEvent.Invalidated -> handleInvalidated(event.detail)
            }
        }
    }

    /**
     * 门禁通过但尚未稳定确认的"候选观察"通知。
     *
     * 悬浮层此前只在稳定确认后才有反馈：候选期（门禁 ACCEPTED → 稳定窗口完成，
     * 指纹持续漂移时可达数十秒）内零事件，按钮停留在旧状态——若上一状态为
     * HIDDEN/已移除，用户看到的是"识别在工作但按钮不出现"（真机 22:17 连续
     * ACCEPTED 27 秒后按钮才出现）。门禁通过即进入 CAPTURING，让用户在候选
     * 期就看到"处理中"反馈；后续 Stable/Invalidated/截图结果会自然覆盖。
     * 状态判据沿用 canShowOnCurrentPage（含敏感页/模式/权限约束）。
     */
    fun onCheckoutCandidate(observation: CheckoutPageObservation) {
        runOnMain {
            if (closed) {
                return@runOnMain
            }
            if (!pageDetector.supportsShoppingPackage(observation.shoppingPackage)) {
                return@runOnMain
            }
            currentPackage = observation.shoppingPackage
            currentFingerprint = observation.pageFingerprint
            currentSupportedShoppingApp = true
            currentSensitivePage = observation.isSensitivePage
            currentCheckout = true
            currentUserRetryContext = true
            updateState(
                status = CheckoutOverlayStatus.CAPTURING,
                visible = canShowOnCurrentPage(),
                reasonCode = CheckoutOverlayStatus.CAPTURING.wireValue
            )
        }
    }

    /** 支持购物应用的非结算页只用于 always_on 展示，点击仍受结算页门禁保护。 */
    fun onSupportedShoppingNonCheckout(
        shoppingPackage: String,
        pageFingerprint: String?,
        userRetryCaptureAvailable: Boolean
    ) {
        runOnMain {
            if (closed || !pageDetector.supportsShoppingPackage(shoppingPackage)) {
                return@runOnMain
            }
            currentPackage = shoppingPackage
            currentFingerprint = pageFingerprint
            currentSupportedShoppingApp = true
            currentSensitivePage = false
            currentCheckout = false
            currentUserRetryContext = userRetryCaptureAvailable
            // 非结算页只有在手动重试上下文真正就绪时才可截图（服务侧要求
            // activeCheckout 已建立）。此前无条件置 READY，导致按钮显示"截图"
            // 但点击必然被 NO_ACTIVE_CHECKOUT 拒绝，用户看到的是无响应。
            //
            // 与 handleInvalidated(NON_CHECKOUT_PAGE) 同一保持窗口语义：
            // 拼多多结算页的提交按钮可见性信号会间歇丢失，门禁在
            // ACCEPTED 与翻转之间抖动；自动截图 STORED 结果刚把按钮置为
            // "已捕获"，数十毫秒后的门禁翻转若立即回退 READY，用户根本
            // 看不到已捕获反馈（真机 22:04:58：STORED 后 40ms 被覆盖）。
            if (shouldHoldCapturedState(
                    isCapturedState =
                        state.status == CheckoutOverlayStatus.CAPTURED_WAITING_PAYMENT,
                    capturedAt = currentCapturedAt,
                    now = systemClock(),
                    holdMillis = CAPTURED_STATE_HOLD_MILLIS
                )
            ) {
                refreshVisibility()
                return@runOnMain
            }
            updateState(
                status = if (userRetryCaptureAvailable) {
                    CheckoutOverlayStatus.READY
                } else {
                    CheckoutOverlayStatus.UNAVAILABLE
                },
                visible = canShowOnCurrentPage(),
                reasonCode = CheckoutInvalidationReason.NON_CHECKOUT_PAGE.wireValue,
                clearSnapshotMetadata = true
            )
        }
    }

    fun onCaptureResult(result: SnapshotCaptureResult) {
        runOnMain {
            if (closed) {
                return@runOnMain
            }
            // 成功类结果（STORED / DUPLICATE）证明快照确实已写入缓冲，
            // 不能因指纹或会话已推进而丢弃——否则按钮永远停在"未捕获"。
            // 真机 .tmp-device-capture-20260823-154942：自动截图 6 次 STORED
            // 却无一次更新按钮状态，用户反馈"明明已捕获但显示没有变化"。
            // 原因是自动截图用的是稳定确认时的指纹，而页面指纹在持续漂移，
            // currentFingerprint 早已是新值，相等过滤必然失败。
            // 失败类结果仍需严格匹配，避免旧页面的拒绝原因覆盖当前页面状态。
            val isSuccessResult = result.status == SnapshotCaptureStatus.STORED ||
                result.status == SnapshotCaptureStatus.DUPLICATE
            if (!shouldAcceptOverlayCaptureResult(
                    isSuccessResult = isSuccessResult,
                    currentFingerprint = currentFingerprint,
                    resultFingerprint = result.pageFingerprint,
                    currentPackage = currentPackage,
                    resultPackage = result.shoppingPackage,
                    currentSessionId = state.sessionId,
                    resultSessionId = result.sessionId
                )
            ) {
                return@runOnMain
            }

            when (result.status) {
                SnapshotCaptureStatus.STORED,
                SnapshotCaptureStatus.DUPLICATE -> {
                    // "已捕获"是既成事实：快照已在缓冲中等待支付交接消费。
                    // 不能用 canShowOnCurrentPage() 决定可见性——该判据依赖
                    // currentCheckout，而结算页在抖动中会立刻被 NON_CHECKOUT_PAGE
                    // 覆盖成 false。真机 .tmp-device-capture-20260823-163623 中
                    // 5 次 STORED 全部没能把按钮更新为"已捕获"，
                    // 按钮全程停在 checkout=false 的 READY 上下文。
                    // 只要仍在受支持的购物应用内且非敏感页，就应把结果告知用户。
                    currentCapturedAt = systemClock()
                    updateState(
                        status = CheckoutOverlayStatus.CAPTURED_WAITING_PAYMENT,
                        visible = canShowCapturedState(),
                        reasonCode = result.reasonCode,
                        result = result
                    )
                }

                SnapshotCaptureStatus.EXPIRED -> updateState(
                    status = CheckoutOverlayStatus.EXPIRED_RETRY,
                    visible = canShowOnCurrentPage(),
                    reasonCode = result.reasonCode,
                    result = result
                )

                SnapshotCaptureStatus.CANCELLED -> handleCancelled(result)
                SnapshotCaptureStatus.REJECTED -> handleRejected(result)
                SnapshotCaptureStatus.UNAVAILABLE,
                SnapshotCaptureStatus.FAILED -> handleUnavailable(result)
            }
        }
    }

    /**
     * AI 已接管快照所有权后收起按钮，避免对同一结算旅程重复派发。
     *
     * 只清理快照元数据与"本页已可截图"标记，不清理页面上下文
     * （currentPackage / currentSupportedShoppingApp / currentSensitivePage）。
     * 此前把页面上下文一并清空，导致用户仍停在结算页时按钮直接消失、
     * 且要等下一个无障碍事件重建上下文才会回来——真机上表现为
     * "点一下显示处理中，然后按钮就没了"：
     *   14:42:38.154 截图状态 STORED, 来源=MANUAL_CAPTURE
     *   14:42:38.156 悬浮按钮进入隐藏宽限: status=HIDDEN, reason=AI_DISPATCHED
     *   14:42:39.160 悬浮按钮按下: status=READY（1 秒后才恢复）
     * 保留页面上下文后，按钮可在同一页面的后续判定中正常恢复可用状态。
     */
    fun onAiDispatched() {
        runOnMain {
            if (closed) {
                return@runOnMain
            }
            // 快照已交给 AI，本次结算引用不再可用；页面上下文保持不变。
            currentCheckout = false
            currentUserRetryContext = false
            updateState(
                status = CheckoutOverlayStatus.HIDDEN,
                visible = false,
                reasonCode = "AI_DISPATCHED",
                clearSnapshotMetadata = true
            )
        }
    }

    fun onManualCaptureRequested() {
        runOnMain {
            if (closed) {
                return@runOnMain
            }
            if (!canManualCapture()) {
                android.util.Log.d(
                    TAG,
                    "手动截图点击被拒绝: status=${state.status}, reason=${state.reasonCode}, " +
                        "checkout=$currentCheckout, supported=$currentSupportedShoppingApp, " +
                        "retry=$currentUserRetryContext"
                )
                return@runOnMain
            }
            // 截图结果最长可延迟约 2 秒返回（真机 15:50:25.235 点击 →
            // 15:50:27.383 才收到 NO_ACTIVE_CHECKOUT）。这段空窗内用户看不到
            // 反馈会反复点击，日志中曾 2 秒内堆积 6 次请求，
            // 后续结果连续回灌成多条 NO_ACTIVE_CHECKOUT / SCREENSHOT_BUSY。
            // 节流只挡住重复提交，不改变 CAPTURING 期间允许点击的既有语义
            // （该语义保证自动截图进行时用户仍能手动触发）。
            val now = System.currentTimeMillis()
            if (now - lastManualCaptureRequestAt < MANUAL_CAPTURE_MIN_INTERVAL_MILLIS) {
                android.util.Log.d(
                    TAG,
                    "手动截图点击被节流: 距上次${now - lastManualCaptureRequestAt}ms, " +
                        "status=${state.status}"
                )
                return@runOnMain
            }
            lastManualCaptureRequestAt = now
            android.util.Log.d(TAG, "手动截图点击已受理: status=${state.status}, reason=${state.reasonCode}")
            updateState(
                status = CheckoutOverlayStatus.CAPTURING,
                visible = true,
                reasonCode = CheckoutOverlayStatus.CAPTURING.wireValue
            )
            val handler = manualCaptureRequestHandler
            if (handler == null) {
                updateState(
                    status = CheckoutOverlayStatus.SERVICE_NOT_CONNECTED,
                    visible = false,
                    reasonCode = CheckoutOverlayStatus.SERVICE_NOT_CONNECTED.wireValue,
                    clearSnapshotMetadata = true
                )
            } else {
                try {
                    handler()
                } catch (error: RuntimeException) {
                    android.util.Log.w(TAG, "调用手动截图入口失败", error)
                    updateState(
                        status = CheckoutOverlayStatus.UNAVAILABLE,
                        visible = canShowOnCurrentPage(),
                        reasonCode = "MANUAL_CAPTURE_REQUEST_FAILED"
                    )
                }
            }
        }
    }

    fun onServiceInterrupted() {
        runOnMain {
            if (!closed) {
                currentPackage = null
                currentFingerprint = null
                currentSupportedShoppingApp = false
                currentSensitivePage = false
                currentCheckout = false
                currentUserRetryContext = false
                updateState(
                    status = CheckoutOverlayStatus.SERVICE_NOT_CONNECTED,
                    visible = false,
                    reasonCode = CheckoutOverlayStatus.SERVICE_NOT_CONNECTED.wireValue,
                    clearSnapshotMetadata = true
                )
                removeViewImmediately()
            }
        }
    }

    fun close() {
        runOnMain {
            if (closed) {
                return@runOnMain
            }
            closed = true
            manualCaptureRequestHandler = null
            currentPackage = null
            currentFingerprint = null
            currentSupportedShoppingApp = false
            currentSensitivePage = false
            currentCheckout = false
            currentUserRetryContext = false
            removeViewImmediately()
            state = state.copy(
                status = CheckoutOverlayStatus.SERVICE_NOT_CONNECTED,
                visible = false,
                enabled = false,
                reasonCode = CheckoutOverlayStatus.SERVICE_NOT_CONNECTED.wireValue,
                shoppingPackage = null,
                pageFingerprint = null,
                sessionId = null,
                snapshotId = null,
                candidateRevision = null,
                requiresDisambiguation = false
            )
            CheckoutCaptureOverlayStateHub.emit(state)
            if (activeManager === this) {
                activeManager = null
            }
        }
    }

    private fun handleStable(observation: com.jotbill.app.recognition.CheckoutPageObservation) {
        currentPackage = observation.shoppingPackage
        currentFingerprint = observation.pageFingerprint
        currentSupportedShoppingApp = pageDetector.supportsShoppingPackage(observation.shoppingPackage)
        currentSensitivePage = observation.isSensitivePage
        currentCheckout = observation.pageRole == CheckoutPageRole.CHECKOUT &&
            !observation.isSensitivePage
        currentUserRetryContext = false

        if (!currentCheckout) {
            val status = if (observation.isSensitivePage) {
                CheckoutOverlayStatus.SENSITIVE
            } else {
                CheckoutOverlayStatus.HIDDEN
            }
            updateState(status, false, status.wireValue)
            return
        }

        val sameCapturedPage = state.pageFingerprint == observation.pageFingerprint &&
            state.status == CheckoutOverlayStatus.CAPTURED_WAITING_PAYMENT
        updateState(
            status = if (sameCapturedPage) {
                CheckoutOverlayStatus.CAPTURED_WAITING_PAYMENT
            } else {
                CheckoutOverlayStatus.CAPTURING
            },
            visible = canShowOnCurrentPage(),
            reasonCode = if (sameCapturedPage) {
                state.reasonCode
            } else {
                CheckoutOverlayStatus.CAPTURING.wireValue
            },
            clearSnapshotMetadata = !sameCapturedPage
        )
    }

    private fun handleInvalidated(detail: com.jotbill.app.recognition.CheckoutObservationInvalidation) {
        if (
            detail.reason == CheckoutInvalidationReason.NON_CHECKOUT_PAGE &&
            detail.shoppingPackage?.let(pageDetector::supportsShoppingPackage) == true
        ) {
            currentPackage = detail.shoppingPackage
            currentFingerprint = detail.pageFingerprint
            currentSupportedShoppingApp = true
            currentSensitivePage = false
            currentCheckout = false
            currentUserRetryContext = false
            // 刚捕获成功后的短暂窗口内不把状态改回 READY：京东结算页在
            // STORED 之后数十毫秒即抛出 NON_CHECKOUT_PAGE，若立刻覆盖，
            // 用户根本看不到"已捕获"。保持窗口结束后自然回到 READY。
            if (shouldHoldCapturedState(
                    isCapturedState =
                        state.status == CheckoutOverlayStatus.CAPTURED_WAITING_PAYMENT,
                    capturedAt = currentCapturedAt,
                    now = systemClock(),
                    holdMillis = CAPTURED_STATE_HOLD_MILLIS
                )
            ) {
                refreshVisibility()
                return
            }
            updateState(
                status = CheckoutOverlayStatus.READY,
                visible = canShowOnCurrentPage(),
                reasonCode = detail.reason.wireValue,
                clearSnapshotMetadata = true
            )
            return
        }

        currentPackage = null
        currentFingerprint = null
        currentSupportedShoppingApp = false
        currentSensitivePage = detail.reason == CheckoutInvalidationReason.SENSITIVE_PAGE
        currentCheckout = false
        currentUserRetryContext = false
        val status = when (detail.reason) {
            CheckoutInvalidationReason.SENSITIVE_PAGE -> CheckoutOverlayStatus.SENSITIVE
            CheckoutInvalidationReason.SERVICE_INTERRUPTED,
            CheckoutInvalidationReason.SERVICE_DESTROYED -> CheckoutOverlayStatus.SERVICE_NOT_CONNECTED
            else -> CheckoutOverlayStatus.HIDDEN
        }
        updateState(
            status = status,
            visible = false,
            reasonCode = detail.reason.wireValue,
            clearSnapshotMetadata = true
        )
    }

    private fun handleCancelled(result: SnapshotCaptureResult) {
        when (result.reasonCode) {
            CheckoutInvalidationReason.SENSITIVE_PAGE.wireValue ->
                updateState(CheckoutOverlayStatus.SENSITIVE, false, result.reasonCode, result)
            CheckoutInvalidationReason.SERVICE_INTERRUPTED.wireValue,
            CheckoutInvalidationReason.SERVICE_DESTROYED.wireValue ->
                updateState(CheckoutOverlayStatus.SERVICE_NOT_CONNECTED, false, result.reasonCode, result)
            else -> updateState(CheckoutOverlayStatus.HIDDEN, false, result.reasonCode, result)
        }
    }

    private fun handleRejected(result: SnapshotCaptureResult) {
        if (result.reasonCode == SnapshotCaptureRejection.SENSITIVE_PAGE.wireValue) {
            updateState(CheckoutOverlayStatus.SENSITIVE, false, result.reasonCode, result)
        } else if (result.reasonCode == SnapshotCaptureRejection.CAPTURE_IN_FLIGHT.wireValue) {
            updateState(CheckoutOverlayStatus.CAPTURING, canShowOnCurrentPage(), result.reasonCode, result)
        } else {
            updateState(CheckoutOverlayStatus.UNAVAILABLE, false, result.reasonCode, result)
        }
    }

    private fun handleUnavailable(result: SnapshotCaptureResult) {
        if (result.reasonCode == "SERVICE_NOT_CONNECTED") {
            updateState(CheckoutOverlayStatus.SERVICE_NOT_CONNECTED, false, result.reasonCode, result)
        } else {
            updateState(CheckoutOverlayStatus.UNAVAILABLE, canShowOnCurrentPage(), result.reasonCode, result)
        }
    }

    private fun setEnabledOnMain(enabled: Boolean) {
        runOnMain {
            configuredEnabled = enabled
            if (!enabled) {
                updateState(CheckoutOverlayStatus.HIDDEN, false, "DISABLED")
                removeViewImmediately()
            } else {
                refreshVisibility()
            }
        }
    }

    private fun setModeOnMain(nextMode: CheckoutOverlayMode) {
        runOnMain {
            mode = nextMode
            if (nextMode == CheckoutOverlayMode.OFF) {
                updateState(CheckoutOverlayStatus.HIDDEN, false, nextMode.wireValue)
                removeViewImmediately()
            } else {
                refreshVisibility()
            }
        }
    }

    private fun canShowOnCurrentPage(): Boolean =
        configuredEnabled &&
            mode != CheckoutOverlayMode.OFF &&
            !currentSensitivePage &&
            (
                currentCheckout ||
                    (mode == CheckoutOverlayMode.ALWAYS_ON && currentSupportedShoppingApp)
                ) &&
            hasOverlayPermission(appContext)

    /**
     * "已捕获"状态是否可展示。
     *
     * 与 canShowOnCurrentPage() 的关键区别是不依赖 currentCheckout：
     * 快照已写入缓冲是既成事实，而结算页在抖动中会立刻被 NON_CHECKOUT_PAGE
     * 覆盖成 checkout=false，若沿用同一判据则"已捕获"永远无法显示给用户。
     * 仍然保留敏感页、总开关、模式与悬浮窗权限这几道约束。
     */
    private fun canShowCapturedState(): Boolean =
        configuredEnabled &&
            mode != CheckoutOverlayMode.OFF &&
            !currentSensitivePage &&
            currentSupportedShoppingApp &&
            hasOverlayPermission(appContext)

    private fun canManualCapture(): Boolean =
        canShowOnCurrentPage() &&
            !currentSensitivePage &&
            (
                currentCheckout ||
                    (
                        mode == CheckoutOverlayMode.ALWAYS_ON &&
                            currentSupportedShoppingApp &&
                            currentFingerprint.isSha256PageFingerprint()
                    )
                ) &&
            state.status in setOf(
                CheckoutOverlayStatus.READY,
                // 结算页稳定后自动截图立即开始，悬浮按钮先进入 CAPTURING；
                // 此时仍允许用户点击，由截图协调器用 CAPTURE_IN_FLIGHT 拒绝并发请求，
                // 不能在自动截图开始前就把按钮禁用到无法手动触发。
                CheckoutOverlayStatus.CAPTURING,
                // "已捕获待支付"也必须可点击：自动截图落盘后用户可能想手动重截一张
                // 更好的（漂移后金额已变、首帧不完整等）。会话绑定路径会把手动截图
                // 落入手动候选槽（USER_RETRY target），消费端手动优先，不会因已有
                // 自动候选而冲突；反复点击仍受节流与 CAPTURE_IN_FLIGHT 约束。
                CheckoutOverlayStatus.CAPTURED_WAITING_PAYMENT,
                CheckoutOverlayStatus.EXPIRED_RETRY,
                CheckoutOverlayStatus.UNAVAILABLE
            )

    private fun updateState(
        status: CheckoutOverlayStatus,
        visible: Boolean,
        reasonCode: String?,
        result: SnapshotCaptureResult? = null,
        clearSnapshotMetadata: Boolean = false
    ) {
        val nextState = state.copy(
            status = status,
            visible = visible,
            enabled = configuredEnabled && mode != CheckoutOverlayMode.OFF,
            mode = mode,
            reasonCode = reasonCode,
            shoppingPackage = result?.shoppingPackage ?: currentPackage,
            pageFingerprint = result?.pageFingerprint ?: currentFingerprint,
            sessionId = when {
                result != null -> result.sessionId
                clearSnapshotMetadata -> null
                else -> state.sessionId
            },
            snapshotId = when {
                result != null -> result.snapshotId
                clearSnapshotMetadata -> null
                else -> state.snapshotId
            },
            candidateRevision = when {
                result != null -> result.candidateRevision
                clearSnapshotMetadata -> null
                else -> state.candidateRevision
            },
            requiresDisambiguation = if (clearSnapshotMetadata) {
                false
            } else {
                result?.requiresDisambiguation ?: state.requiresDisambiguation
            }
        )
        if (nextState == state) {
            return
        }
        state = nextState
        refreshVisibility()
        CheckoutCaptureOverlayStateHub.emit(state)
    }

    private fun refreshVisibility() {
        // "已捕获"用独立判据：它不依赖 currentCheckout，否则结算页抖动会
        // 立刻把刚显示出来的"已捕获"重新隐藏，用户根本看不到反馈。
        val shouldShow = state.visible &&
            if (state.status == CheckoutOverlayStatus.CAPTURED_WAITING_PAYMENT) {
                canShowCapturedState()
            } else {
                canShowOnCurrentPage()
            }
        if (shouldShow) {
            cancelPendingHide()
            ensureView()
            updateViewAppearance()
            return
        }
        scheduleDelayedHide()
    }

    /** 短暂失效不立即移除窗口；保留期间禁用按钮，页面恢复后复用原窗口。 */
    private fun scheduleDelayedHide() {
        val view = overlayView ?: return
        if (hideScheduled) {
            // 已处于隐藏宽限期内，不重置计时，避免持续无关事件让按钮永远不消失。
            view.isEnabled = false
            view.alpha = 0.55f
            return
        }
        hideScheduled = true
        view.isEnabled = false
        view.alpha = 0.55f
        android.util.Log.d(
            TAG,
            "悬浮按钮进入隐藏宽限: status=${state.status}, reason=${state.reasonCode}"
        )
        mainHandler.postDelayed(pendingHideRunnable, HIDE_GRACE_MILLIS)
    }

    private fun cancelPendingHide() {
        if (!hideScheduled && overlayView == null) {
            return
        }
        hideScheduled = false
        mainHandler.removeCallbacks(pendingHideRunnable)
    }

    private fun removeViewImmediately() {
        cancelPendingHide()
        removeView()
    }

    private fun ensureView() {
        // 只允许存在一个系统窗口。addView 后到首次 attach 之间 overlayView 已经非空，
        // 此前用 isAttachedToWindow 判断会在连续刷新时重复 addView，导致关闭后残留旧窗口。
        if (overlayView != null) {
            return
        }
        val manager = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (manager == null) {
            state = state.copy(
                status = CheckoutOverlayStatus.UNAVAILABLE,
                visible = false,
                reasonCode = "WINDOW_SERVICE_UNAVAILABLE"
            )
            CheckoutCaptureOverlayStateHub.emit(state)
            return
        }
        val view = createOverlayView()
        try {
            manager.addView(view, createLayoutParams())
            windowManager = manager
            overlayView = view
            android.util.Log.d(
                TAG,
                "悬浮按钮已添加: status=${state.status}, reason=${state.reasonCode}, " +
                    "mode=$mode, checkout=$currentCheckout"
            )
        } catch (error: RuntimeException) {
            windowManager = null
            overlayView = null
            state = state.copy(
                status = CheckoutOverlayStatus.UNAVAILABLE,
                visible = false,
                reasonCode = "OVERLAY_ADD_FAILED"
            )
            CheckoutCaptureOverlayStateHub.emit(state)
            android.util.Log.w(TAG, "结算截图悬浮按钮添加失败", error)
        }
    }

    private fun removeView() {
        val view = overlayView ?: return
        try {
            windowManager?.removeView(view)
            android.util.Log.d(TAG, "悬浮按钮已移除: status=${state.status}, reason=${state.reasonCode}")
        } catch (error: RuntimeException) {
            android.util.Log.w(TAG, "结算截图悬浮按钮移除失败", error)
        } finally {
            overlayView = null
            windowManager = null
        }
    }

    private fun createOverlayView(): TextView = TextView(appContext).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        isClickable = true
        isFocusable = false
        contentDescription = "手动截图"
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    android.util.Log.d(
                        TAG,
                        "悬浮按钮按下: enabled=${view.isEnabled}, status=${state.status}, reason=${state.reasonCode}"
                    )
                    downRawX = event.rawX
                    downRawY = event.rawY
                    val params = view.layoutParams as? WindowManager.LayoutParams
                    downWindowX = params?.x ?: 0
                    downWindowY = params?.y ?: 0
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        moved = true
                    }
                    if (moved) {
                        val params = view.layoutParams as? WindowManager.LayoutParams
                        if (params != null) {
                            params.x = downWindowX + dx.toInt()
                            params.y = downWindowY + dy.toInt()
                            try {
                                windowManager?.updateViewLayout(view, params)
                            } catch (error: RuntimeException) {
                                android.util.Log.w(TAG, "结算截图悬浮按钮移动失败", error)
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    android.util.Log.d(TAG, "悬浮按钮抬起: moved=$moved, enabled=${view.isEnabled}")
                    if (!moved) {
                        onManualCaptureRequested()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun updateViewAppearance() {
        val view = overlayView ?: return
        val canClick = canManualCapture()
        view.text = when (state.status) {
            CheckoutOverlayStatus.READY -> "截图"
            CheckoutOverlayStatus.CAPTURING -> "处理中"
            CheckoutOverlayStatus.CAPTURED_WAITING_PAYMENT -> "已捕获"
            CheckoutOverlayStatus.EXPIRED_RETRY -> "重试"
            CheckoutOverlayStatus.UNAVAILABLE -> "不可用"
            CheckoutOverlayStatus.SENSITIVE -> "敏感"
            CheckoutOverlayStatus.SERVICE_NOT_CONNECTED -> "离线"
            CheckoutOverlayStatus.HIDDEN -> ""
        }
        view.isEnabled = canClick
        view.alpha = if (canClick) 1f else 0.72f
        // 在 GradientDrawable.apply 块内，Drawable.getState() 会遮蔽外层 state 字段，
        // 因此先取出 status 到局部变量，避免 state.status 解析到 IntArray 上报错。
        val overlayStatus = state.status
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                when (overlayStatus) {
                    CheckoutOverlayStatus.READY,
                    CheckoutOverlayStatus.EXPIRED_RETRY -> Color.rgb(22, 101, 52)
                    CheckoutOverlayStatus.CAPTURING -> Color.rgb(71, 85, 105)
                    CheckoutOverlayStatus.CAPTURED_WAITING_PAYMENT -> Color.rgb(37, 99, 235)
                    CheckoutOverlayStatus.UNAVAILABLE -> Color.rgb(107, 114, 128)
                    else -> Color.rgb(127, 29, 29)
                }
            )
        }
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            dp(56),
            dp(56),
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(220)
        }
    }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}

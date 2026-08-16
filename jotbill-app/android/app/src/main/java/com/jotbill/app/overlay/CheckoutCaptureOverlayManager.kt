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
        private const val DEFAULT_MODE = "checkout_only"

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
            updateState(
                status = CheckoutOverlayStatus.READY,
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
            if (currentFingerprint == null && result.pageFingerprint != null) {
                return@runOnMain
            }
            if (
                currentFingerprint != null &&
                result.pageFingerprint != null &&
                currentFingerprint != result.pageFingerprint
            ) {
                return@runOnMain
            }
            if (
                currentPackage != null &&
                result.shoppingPackage != null &&
                currentPackage != result.shoppingPackage
            ) {
                return@runOnMain
            }
            if (
                state.sessionId != null &&
                result.sessionId != null &&
                state.sessionId != result.sessionId
            ) {
                return@runOnMain
            }

            when (result.status) {
                SnapshotCaptureStatus.STORED,
                SnapshotCaptureStatus.PROTECTED,
                SnapshotCaptureStatus.DUPLICATE -> updateState(
                    status = CheckoutOverlayStatus.CAPTURED_WAITING_PAYMENT,
                    visible = canShowOnCurrentPage(),
                    reasonCode = result.reasonCode,
                    result = result
                )

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

    /** AI 已接管快照所有权后隐藏按钮，避免继续操作同一结算旅程。 */
    fun onAiDispatched() {
        runOnMain {
            if (closed) {
                return@runOnMain
            }
            currentPackage = null
            currentFingerprint = null
            currentSupportedShoppingApp = false
            currentSensitivePage = false
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
                return@runOnMain
            }
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
            removeView()
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

    private fun canManualCapture(): Boolean =
        canShowOnCurrentPage() &&
            (
                currentCheckout ||
                    (
                        mode == CheckoutOverlayMode.ALWAYS_ON &&
                            currentSupportedShoppingApp &&
                            !currentSensitivePage &&
                            currentUserRetryContext &&
                            currentFingerprint.isSha256PageFingerprint()
                    )
                ) &&
            state.status in setOf(
                CheckoutOverlayStatus.READY,
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
        state = state.copy(
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
        refreshVisibility()
        CheckoutCaptureOverlayStateHub.emit(state)
    }

    private fun refreshVisibility() {
        val shouldShow = state.visible && canShowOnCurrentPage()
        if (!shouldShow) {
            removeView()
            return
        }
        ensureView()
        updateViewAppearance()
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

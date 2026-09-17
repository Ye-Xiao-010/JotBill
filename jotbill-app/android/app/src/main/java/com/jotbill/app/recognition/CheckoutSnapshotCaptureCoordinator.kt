package com.jotbill.app.recognition

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Display
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** 本地截图入口的最终状态；不会代表已经发起 AI 网络请求。 */
enum class SnapshotCaptureStatus(val wireValue: String) {
    STORED("STORED"),
    EXPIRED("EXPIRED"),
    REJECTED("REJECTED"),
    UNAVAILABLE("UNAVAILABLE"),
    DUPLICATE("DUPLICATE"),
    CANCELLED("CANCELLED"),
    FAILED("FAILED")
}

/** 截图入口自身的安全和页面门禁拒绝原因。 */
enum class SnapshotCaptureRejection(val wireValue: String) {
    NO_ACTIVE_CHECKOUT("NO_ACTIVE_CHECKOUT"),
    NOT_CHECKOUT_PAGE("NOT_CHECKOUT_PAGE"),
    SENSITIVE_PAGE("SENSITIVE_PAGE"),
    CAPTURE_IN_FLIGHT("CAPTURE_IN_FLIGHT"),
    PAGE_CHANGED("PAGE_CHANGED")
}

/** Android 截图能力不可用或系统拒绝截图的原因。 */
enum class SnapshotCaptureUnavailableReason(val wireValue: String) {
    SERVICE_NOT_CONNECTED("SERVICE_NOT_CONNECTED"),
    API_UNSUPPORTED("API_UNSUPPORTED"),
    CAPABILITY_UNAVAILABLE("CAPABILITY_UNAVAILABLE"),
    SCREENSHOT_SECURE("SCREENSHOT_SECURE"),
    SCREENSHOT_BUSY("SCREENSHOT_BUSY"),
    INVALID_DISPLAY("INVALID_DISPLAY"),
    SYSTEM_REJECTED("SYSTEM_REJECTED"),
    ENCODE_FAILED("ENCODE_FAILED")
}

/** 不携带图片数据的截图结果，供购买意图协调器和 RN 显示状态使用。 */
data class SnapshotCaptureResult(
    val status: SnapshotCaptureStatus,
    val sourceReason: SnapshotSourceReason,
    val reasonCode: String,
    val sessionId: String? = null,
    val slotId: String? = null,
    val shoppingPackage: String? = null,
    val pageFingerprint: String? = null,
    val snapshotId: String? = null,
    val candidateRevision: Int? = null,
    val requiresDisambiguation: Boolean = false
)

/** 手动截图异步回调；回调只收到状态和元数据，不收到图片字节。 */
fun interface SnapshotCaptureCallback {
    fun onResult(result: SnapshotCaptureResult)
}

/**
 * 事件分发或处理失败的类别，用于区分暂时性故障与不可恢复故障。
 */
internal enum class DispatchFailureKind {
    /** 主线程在超时窗口内未处理完事件（卡顿），任务仍会稍后执行。 */
    MAIN_THREAD_TIMEOUT,

    /** 单个事件处理抛出 RuntimeException，属该事件自身的问题。 */
    EVENT_RUNTIME_ERROR,

    /** 主线程 Handler 无法投递，协调器已无法维持状态机。 */
    MAIN_HANDLER_UNAVAILABLE,

    /** OutOfMemoryError 等 Error，运行时已不可信。 */
    FATAL_ERROR,

    /** 服务销毁，需正常收口释放截图字节。 */
    SERVICE_DESTROYED
}

/**
 * 判定某类失败是否应当熔断协调器。
 *
 * 熔断不可逆（screenshotThread 已 quitSafely），代价是截图能力永久失效，
 * 因此只有真正无法继续维持状态机的故障才应触发。
 * 真机 .tmp-device-capture-20260823-123024 显示，此前把主线程卡顿也判为熔断，
 * 导致一次 5 秒卡顿就让 12 次手动截图全部 SERVICE_NOT_CONNECTED、
 * 自动截图归零，而门禁其实已正确识别到结算页。
 *
 * @param kind 失败类别。
 * @return true 表示必须熔断收口；false 表示放弃本次事件、保留协调器。
 */
internal fun shouldFailClosedForDispatchFailure(kind: DispatchFailureKind): Boolean = when (kind) {
    DispatchFailureKind.MAIN_THREAD_TIMEOUT -> false
    DispatchFailureKind.EVENT_RUNTIME_ERROR -> false
    DispatchFailureKind.MAIN_HANDLER_UNAVAILABLE -> true
    DispatchFailureKind.FATAL_ERROR -> true
    DispatchFailureKind.SERVICE_DESTROYED -> true
}

/**
 * 判定协调器是否需要重建。
 *
 * 三个关闭标志由不同路径设置，必须全部覆盖：
 * closed 由主线程收口置真、failClosedRequested 由跨线程请求先行置真、
 * emergencyClosed 由主线程不可用的兜底路径设置。
 * 漏掉任一者都会遗留"已不可用但判定为健康"的中间态。
 *
 * @param closed 主线程收口标志。
 * @param failClosedRequested 跨线程熔断请求标志。
 * @param emergencyClosed 紧急关闭标志。
 * @return true 表示该实例已不可用，应当重建。
 */
internal fun shouldRecoverCoordinator(
    closed: Boolean,
    failClosedRequested: Boolean,
    emergencyClosed: Boolean
): Boolean = closed || failClosedRequested || emergencyClosed

/**
 * 生成观察事件的诊断名称，用于熔断与分发失败日志。
 *
 * 只输出事件类型、购物包名与截断指纹：页面文本、金额、订单号一律不进日志，
 * 与"页面指纹不得保留敏感原值"的既有约束保持一致。
 *
 * @return 形如 `Stable(pkg=com.jingdong.app.mall,fp=4dc69b730966)` 的短标识。
 */
private fun CheckoutObservationEvent.diagnosticName(): String = when (this) {
    is CheckoutObservationEvent.Stable ->
        "Stable(pkg=${page.observation.shoppingPackage}," +
            "fp=${page.observation.pageFingerprint.take(12)})"
    is CheckoutObservationEvent.Invalidated ->
        "Invalidated(reason=${detail.reason.wireValue}," +
            "pkg=${detail.shoppingPackage ?: "null"}," +
            "fp=${detail.pageFingerprint?.take(12) ?: "null"})"
}

/**
 * 解析"某一稳定帧对应哪个会话"（§8.3 sessionId 传递链路的第③环）。
 *
 * 会话由截图协调器在 `handleStable` 内建立或沿用（新建 / 漂移沿用 / 支付返回恢复），
 * 因此**只能在它处理完本帧之后**解析；服务层在 `onStable` 时刻读取只会拿到上一帧的会话。
 *
 * 三条判据缺一不可（包名 + 指纹 + 会话非空）：
 * - 包名不符 ⇒ 本帧已被别的购物包的会话接管；
 * - 指纹不符 ⇒ 本帧没有建立/沿用任何会话（交接过渡窗内被有意忽略、
 *   `attemptedAutomaticCaptures` 复用等早退分支都会走到这里）；
 * - 会话为空 ⇒ 协调器尚未建立过会话。
 *
 * 提取为纯函数以便单测覆盖：返回错的会话会把"订单2 的帧"标成"订单1 的 sessionId"，
 * 正是改造 E 要消除的错误归属，且真机只能靠事后日志追查。
 *
 * @return 本帧对应会话的 sessionId；无对应会话时返回 null（调用方**不得**回退猜测）。
 */
internal fun stableFrameSessionId(
    activeSessionId: String?,
    activeShoppingPackage: String?,
    activePageFingerprint: String?,
    observedShoppingPackage: String,
    observedPageFingerprint: String
): String? {
    if (activeSessionId.isNullOrBlank()) {
        return null
    }
    if (activeShoppingPackage != observedShoppingPackage) {
        return null
    }
    if (activePageFingerprint != observedPageFingerprint) {
        return null
    }
    return activeSessionId
}

/**
 * 判断一次页面失效是否属于“同购物包内的支付中间页”。
 *
 * 中间页（拼多多提交订单后的收银台、京东提交后的加载页）不是结算页，却是
 * 结算 → 支付旅程的必经环节：应按预交接挂起会话与快照，而非清空槽位。
 *
 * 提取为纯函数以便单测覆盖——该分支判错会静默销毁唯一的结算截图，
 * 只能靠真机日志事后追查。
 *
 * 判据取“当前会话或已挂起会话”的包名：预交接已把 activeCheckout 置空，
 * 挂起期间同包内的每个非结算页事件都会让当前会话为空。若只看当前会话，
 * 这些后续事件会被误判为普通离开并清空槽位。
 *
 * @param reason 本次失效原因；只有 NON_CHECKOUT_PAGE 才可能是中间页。
 * @param invalidatedPackage 触发失效的页面所属购物包名。
 * @param handoffSourcePackage 当前会话或已挂起会话的购物包名；两者皆无时为 null。
 * @return true 表示应按预交接挂起处理，保留槽位与快照。
 */
internal fun isSamePackagePaymentInterstitial(
    reason: CheckoutInvalidationReason,
    invalidatedPackage: String?,
    handoffSourcePackage: String?
): Boolean {
    if (reason != CheckoutInvalidationReason.NON_CHECKOUT_PAGE) {
        return false
    }
    val invalidated = invalidatedPackage?.trim().orEmpty()
    val source = handoffSourcePackage?.trim().orEmpty()
    return invalidated.isNotBlank() && invalidated == source
}

/**
 * 判定是否应当用当前会话替换已挂起的交接会话。
 *
 * 旧实现无条件沿用已挂起会话，导致挂起会话一旦失去快照与栅栏就永久滞留：
 * 真机京东日志中 8941f58a / c02b4e5b 反复出现在 retained 位置，
 * 其间 current 已换成 170c28f7、c221b97d、2b103eca 等有效新会话却全被丢弃，
 * "交接栅栏设置失败(槽位不存在)"随之重复出现，自动截图长期无法完成交接。
 *
 * 替换条件要求旧会话确实已无价值（既无快照也无栅栏），且存在可接替的当前会话。
 * 只要旧会话仍持有其一，就必须继续保留——它的快照正等待支付交接消费，
 * 替换会让已截到的图与支付证据失去关联。
 *
 * @param hasCurrentCheckout 是否存在可接替的当前活跃会话。
 * @param retainedHasSnapshot 已挂起会话在缓冲中是否仍有候选。
 * @param retainedHasFence 已挂起会话是否仍持有交接栅栏。
 * @return true 表示应改用当前会话作为新的挂起会话。
 */
internal fun shouldReplaceHandoffCheckout(
    hasCurrentCheckout: Boolean,
    retainedHasSnapshot: Boolean,
    retainedHasFence: Boolean
): Boolean = hasCurrentCheckout && !retainedHasSnapshot && !retainedHasFence

/** 挂起交接期间稳定事件的处置动作。 */
internal enum class HandoffStableEventAction {
    /** 同包过渡期事件：保留挂起会话，忽略本次稳定事件。 */
    KEEP_RETAINED,
    /** 返回原结算页：恢复挂起会话。 */
    RESTORE,
    /** 返回不同结算页：丢弃挂起会话，允许新订单建立会话。 */
    DISCARD_RETAINED
}

/** 交接挂起后的过渡窗长度：同购物包的迟到稳定事件在此窗口内不视为新订单。 */
internal const val HANDOFF_TRANSITION_WINDOW_MILLIS = 10_000L

/**
 * 判定挂起交接期间到达的稳定事件应如何处置挂起会话。
 *
 * X1 真机故障时序（2026-09-10 17:09 第三次复现，与 09-09 20:55 / 22:10 同构）：
 * - 17:09:17.149 交接栅栏已设置（指纹已按候选校正），购买意图 Accepted；
 * - 17:09:17.165 支付交接会话预写入，结算截图 AI 已派发；
 * - 17:09:17.345 同包迟到稳定事件（转场 UI 指纹漂移，距结算页稳定 6.4s）
 *   超出旧漂移尾波窗口（锚定页面 stableAt + 2.5s），被判"返回其他结算页"丢弃；
 * - 17:09:18.105 支付上下文失去协调器引用，fallback 生成新会话 23eafff1；
 * - 17:09:21.837 ¥55 支付证据落错会话 matched=false，待确认而非自动入账。
 *
 * 边界（本函数钉死）：
 * 1. 过渡窗必须锚定**挂起时刻**而非结算页稳定时刻——用户从页面稳定到提交订单
 *    天然间隔数秒（E 场景 6.4s），页面锚点会把挂起初期的转场事件全部误判；
 * 2. 只放行同购物包事件：转场 UI 重发来自同一购物 App；跨包稳定事件立即丢弃；
 * 3. 同指纹（返回原结算页）在任何时刻都走恢复，与过渡窗无关；
 * 4. 窗口外/跨包的不同指纹结算页按新订单丢弃——用户真正回来逛新订单不受影响，
 *    代价仅是窗口内（10s）立刻开新订单的自动截图延迟一个稳定事件周期。
 */
internal fun handoffStableEventAction(
    observedPackage: String,
    observedFingerprint: String,
    retainedPackage: String,
    retainedFingerprint: String,
    handoffRetainedAtMillis: Long,
    observedAtMillis: Long,
    transitionWindowMillis: Long = HANDOFF_TRANSITION_WINDOW_MILLIS
): HandoffStableEventAction {
    val samePackage = observedPackage == retainedPackage
    val sameCheckout = samePackage && observedFingerprint == retainedFingerprint
    return when {
        sameCheckout -> HandoffStableEventAction.RESTORE
        samePackage &&
            observedAtMillis - handoffRetainedAtMillis < transitionWindowMillis ->
            HandoffStableEventAction.KEEP_RETAINED
        else -> HandoffStableEventAction.DISCARD_RETAINED
    }
}

/**
 * 判定手动重试上下文能否建立 USER_RETRY 会话。
 *
 * 结算页角色（CHECKOUT）的上下文只作兜底记录，绝不可建立 USER_RETRY 会话，
 * 否则会把结算会话降级：自动截图再也找不到 CHECKOUT 会话，
 * 且指纹变化时把刚存的快照自毁。真机 .tmp-device-capture-20260823-131138：
 *   13:13:17.164 候选已写入 source=MANUAL_CAPTURE
 *   13:13:17.165 槽位丢弃 USER_RETRY_CONTEXT_CHANGED, consumed=false（1ms 后自毁）
 *   13:13:17.797 截图状态 REJECTED, 来源=CHECKOUT_STABLE, 原因=NOT_CHECKOUT_PAGE
 * 该时段门禁 7 次 ACCEPTED，自动截图候选却为 0，最终落孤儿待确认。
 *
 * 结算页的自动截图链路由 handleStable 负责建立 CHECKOUT 会话；
 * 手动补截在结算态挂起（activeCheckout=null）后仍可正常建立。
 *
 * @param contextRole 手动重试上下文的页面角色；null 表示无上下文。
 * @param contextUsable 上下文是否完整且具备安全展示条件。
 * @param activeCaptureTarget 当前活跃会话的截图目标；null 表示无活跃会话。
 * @return true 表示允许建立 USER_RETRY 会话。
 */
internal fun canEstablishUserRetrySession(
    contextRole: CheckoutPageRole?,
    contextUsable: Boolean,
    activeCaptureTarget: SnapshotCaptureTarget?
): Boolean = when {
    !contextUsable -> false
    contextRole == null -> false
    contextRole == CheckoutPageRole.CHECKOUT -> false
    activeCaptureTarget == null -> true
    activeCaptureTarget == SnapshotCaptureTarget.USER_RETRY -> true
    else -> false
}

/**
 * 判定普通页手动重试上下文能否接管截图槽位。
 *
 * 提取为纯函数以便单测覆盖。四类回归都由该判据统一收口：
 * 1. 交接挂起期间 `activeCheckout` 被置空，若只看"无人占用"就接管，会 discard
 *    掉正在交接的结算截图（拼多多回归）；
 * 2. 交接会话本身没有快照、也没有交接栅栏时（真空壳），若仍一律拒绝接管，
 *    空壳交接会长期霸占槽位，用户手动截图全部被 NO_ACTIVE_CHECKOUT
 *    拒绝（京东回归）；
 * 3. 交接栅栏已设置但截图仍在飞行中：槽位候选此刻为空，仅凭"有无快照"判断
 *    会误判为真空壳并放行抢占。栅栏被 discard 后 handoffCheckout 仍指向该会话，
 *    形成永久残留，真机表现为"交接栅栏设置失败(槽位不存在)"反复出现、
 *    自动截图长期不触发（京东弹窗结算页回归）；
 * 4. 保护必须限定在同一购物包内：截图槽位按购物包划分，京东的挂起会话
 *    与拼多多的槽位互不相干。真机日志中京东会话 e3b01a29 曾拦下
 *    contextPkg=com.xunmeng.pinduoduo 的手动截图，导致用户在拼多多页面
 *    连续点击全部失败——跨包拦截纯属越权，没有任何快照因此被保护。
 *
 * 判据因此是"同一购物包内的交接会话是否有快照或栅栏需要保护"。
 * 拼多多结算页停留较久、截图能在挂起前落盘，靠 handoffHasSnapshot 即可拦住；
 * 京东弹窗页数百毫秒即跳转，必须靠 handoffHasFence 覆盖落盘空窗。
 *
 * @param handoffHasSnapshot 挂起的交接会话在缓冲中是否仍有可消费候选；
 * 无挂起会话时传 false。
 * @param activeCaptureTarget 当前活跃会话的截图目标；null 表示无活跃会话。
 * @param handoffHasFence 挂起的交接会话是否已设置支付交接栅栏；
 * 无挂起会话时传 false。栅栏存在即代表该会话正等待支付交接消费，
 * 无论截图是否已落盘都不得被抢占。
 * @param handoffSharesPackage 挂起会话与本次手动重试上下文是否属于同一购物包。
 * 为 false 时（含无挂起会话、上下文包名缺失）交接保护一律不生效。
 * @return true 表示允许手动重试上下文接管槽位。
 */
internal fun canUserRetryTakeOverSlot(
    handoffHasSnapshot: Boolean,
    activeCaptureTarget: SnapshotCaptureTarget?,
    handoffHasFence: Boolean = false,
    handoffSharesPackage: Boolean = true
): Boolean = when {
    handoffSharesPackage && handoffHasSnapshot -> false
    handoffSharesPackage && handoffHasFence -> false
    activeCaptureTarget == SnapshotCaptureTarget.CHECKOUT -> false
    else -> true
}

/** 供后续协调器观察自动和手动截图状态的回调。 */
fun interface SnapshotCaptureResultListener {
    fun onResult(result: SnapshotCaptureResult)
}

/**
 * 把稳定结算页观察连接到内存截图缓冲区。
 *
 * 该类不执行点击、支付确认、AI 请求或记账；它只负责截图能力门禁、截图生命周期和缓冲写入。
 */
internal class CheckoutSnapshotCaptureCoordinator(
    private val accessibilityService: AccessibilityService,
    private val buffer: CheckoutSnapshotBuffer = CheckoutSnapshotBuffer(),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() }
) : CheckoutObservationListener {
    companion object {
        private const val TAG = "CheckoutSnapshotCapture"
        private const val SCREENSHOT_ERROR_PREFIX = "SCREENSHOT_ERROR_"
        private const val MAX_SCREENSHOT_LONG_EDGE = 1080
        private const val JPEG_QUALITY = 85
        private const val MAX_ENCODED_SCREENSHOT_BYTES = 5 * 1024 * 1024
        private const val EVENT_DISPATCH_TIMEOUT_MILLIS = 5_000L
        /** 熔断后"跳过观察事件"日志的采样间隔，兼顾可观测与防刷屏。 */
        private const val CLOSED_EVENT_SKIP_LOG_INTERVAL_MILLIS = 5_000L

        /**
         * 会话沿用上限（改造 C / Y4 判据 2）。// 可调常量
         *
         * 初值 2 分钟：覆盖"正常结算 → 支付"全流程，且短于会话上限 5 分钟。
         * 超龄 ⇒ 旧会话**不被沿用** ⇒ 新建会话。
         *
         * 🔴 **不得与 `PAYMENT_PROCESS_TTL_MILLIS` 联动**（`IMPL_PLAN` §2.3 C-2）——
         * 两者**初值同为 2 分钟，但语义与起算点完全不同**：
         *
         * | 常量 | 语义 | 起算点 | 超时后果 |
         * |---|---|---|---|
         * | `MAX_SESSION_REUSE_MILLIS`（本常量） | **会话沿用上限** | `ActiveCheckout.sessionCreatedAt`（会话建立） | 旧会话不被沿用 ⇒ **新建会话** |
         * | `PAYMENT_PROCESS_TTL_MILLIS`（V-3 引入，§2.2 B-4） | **支付过程槽位存活时长** | **搬入时刻**（前台切换） | 图**过期释放** ⇒ 支付成功时无图 ⇒ 落待确认 |
         *
         * ⇒ "用户在结算页停留很久"时两者会**同时**参与判定，代价方向却相反
         * （前者拆会话、后者丢图）⇒ **改一个不要顺手改另一个**。
         * ⇒ 本常量属"过渡期启发式"，journey 落地后本判据整体退役（金额提取保留），
         *   故**不再追加真机测量投入**（`IMPL_PLAN` §2.3 C-2 末段）。
         */
        internal const val MAX_SESSION_REUSE_MILLIS = 2 * 60 * 1000L
        private const val MAIN_HANDLER_UNAVAILABLE = "MAIN_HANDLER_UNAVAILABLE"
        private const val EVENT_PROCESSING_FAILED = "EVENT_PROCESSING_FAILED"
        private const val SCREENSHOT_HANDLER_UNAVAILABLE = "SCREENSHOT_HANDLER_UNAVAILABLE"
        /** 飞行中的自动截图被手动截图抢占时的取消原因；手动优先级见不变式第 5 条。 */
        private const val PREEMPTED_BY_MANUAL_CAPTURE = "PREEMPTED_BY_MANUAL_CAPTURE"

        /**
         * 截图回调超时看门狗。MIUI 等系统可能静默丢弃 takeScreenshot 回调
         * （真机上出现在 adb 授权恢复后），无超时则 pendingCapture 永久占位、
         * 同页所有手动重试恒 CAPTURE_IN_FLIGHT、自动截图排队永不消费。
         * 正常回调耗时百毫秒级，3 秒足以覆盖重度卡顿；超时按 CANCELLED 收口，
         * 迟到的系统回调会因 requestId 不匹配走字节抹零路径，不会二次发布。
         */
        private const val CAPTURE_CALLBACK_TIMEOUT_MILLIS = 3_000L
        private const val CAPTURE_CALLBACK_TIMEOUT = "CAPTURE_CALLBACK_TIMEOUT"
    }

    private data class CaptureKey(
        val sessionId: String,
        val pageFingerprint: String
    )

    private data class ActiveCheckout(
        val sessionId: String,
        val slotId: String,
        val page: StableCheckoutPage,
        val captureTarget: SnapshotCaptureTarget,
        /**
         * 会话建立时刻（改造 C / Y4 判据 2 的**起算点**）。
         *
         * 沿用旧会话（`copy(page = page)`）时**不刷新** —— 它衡量的是"这个会话
         * 已被沿用了多久"，而不是"页面最近何时稳定"。
         */
        val sessionCreatedAt: Long
    ) {
        val key: CaptureKey
            get() = CaptureKey(sessionId, page.observation.pageFingerprint)
    }

    private data class PendingCapture(
        val requestId: String,
        val activeCheckout: ActiveCheckout,
        val sourceReason: SnapshotSourceReason,
        val callback: SnapshotCaptureCallback?,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val completionPublished: AtomicBoolean = AtomicBoolean(false),
        val finishStarted: AtomicBoolean = AtomicBoolean(false),
        val imageBytes: AtomicReference<ByteArray?> = AtomicReference(null)
    ) {
        fun offerImage(bytes: ByteArray?): Boolean {
            if (cancelled.get()) {
                bytes?.fill(0)
                return false
            }
            imageBytes.set(bytes)
            if (cancelled.get()) {
                imageBytes.getAndSet(null)?.fill(0)
                return false
            }
            return true
        }

        fun takeImage(): ByteArray? = imageBytes.getAndSet(null)

        fun releaseForEmergency() {
            cancelled.set(true)
            imageBytes.getAndSet(null)?.fill(0)
        }
    }

    private data class HandoffCheckout(
        val activeCheckout: ActiveCheckout,
        val attemptedAutomaticCaptures: Set<CaptureKey>,
        /** 挂起时刻；交接过渡窗锚点（见 handoffStableEventAction）。 */
        val retainedAtMillis: Long
    )

    private data class QueuedManualCapture(
        val key: CaptureKey,
        val callback: SnapshotCaptureCallback,
        val completionPublished: AtomicBoolean = AtomicBoolean(false)
    )

    private class MainThreadDispatch {
        val completed = CountDownLatch(1)
        @Volatile var failure: Throwable? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotThread = HandlerThread("CheckoutScreenshot").apply { start() }
    private val screenshotHandler = Handler(screenshotThread.looper)
    private val callbackExecutor = Executor { runnable ->
        val posted = try {
            screenshotHandler.post(runnable)
        } catch (_: RuntimeException) {
            false
        }
        if (!posted) {
            requestFailClosedFromAnyThread(reason = SCREENSHOT_HANDLER_UNAVAILABLE)
            throw RejectedExecutionException(SCREENSHOT_HANDLER_UNAVAILABLE)
        }
    }
    private val pageDetector = CheckoutPageDetector()
    private val attemptedAutomaticCaptures = mutableSetOf<CaptureKey>()
    /** 只作为主线程不可用时的消费阻断标记，不承载协调器状态转换。 */
    private val failClosedRequested = AtomicBoolean(false)
    private val emergencyClosed = AtomicBoolean(false)
    private var activeCheckout: ActiveCheckout? = null
    private var pendingCapture: PendingCapture? = null
    private var queuedAutomatic: ActiveCheckout? = null
    private var queuedManual: QueuedManualCapture? = null
    private var handoffCheckout: HandoffCheckout? = null
    private var userRetryContext: UserRetryCaptureContext? = null
    /** "交接挂起期间跳过手动重试接管"日志的最近打印时间，按秒采样防止刷屏。 */
    private var lastHandoffRetrySkipLogAt = 0L

    /**
     * "协调器已关闭跳过事件"日志的最近打印时间。
     *
     * 熔断后每个无障碍事件都会命中该分支（真机每秒可达数十次），
     * 必须采样；但完全不打印会让"自动截图 0 次"变成无据可查的黑盒。
     */
    private var lastClosedEventSkipLogAt = 0L

    /** "结算页上下文仅作兜底"日志的最近打印时间，按秒采样防止刷屏。 */
    private var lastCheckoutContextFallbackLogAt = 0L
    private var resultListener: SnapshotCaptureResultListener? = null
    /** fallback 可能在截图线程设置，主线程路径需要可靠观察关闭状态。 */
    @Volatile
    private var closed = false
    private var eventGeneration = 0L

    override fun onEvent(event: CheckoutObservationEvent) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runEventOnMainThread(event)?.let { error ->
                throw eventDispatchFailure(EVENT_PROCESSING_FAILED, error)
            }
            return
        }

        val dispatch = MainThreadDispatch()
        val postedAt = nowMillis()
        val posted = try {
            mainHandler.post {
                try {
                    dispatch.failure = runEventOnMainThread(event)
                } catch (error: Throwable) {
                    // 即使异常来自事件包装层，也必须把失败写入共享结果后再释放等待者。
                    dispatch.failure = error
                } finally {
                    dispatch.completed.countDown()
                }
            }
        } catch (error: RuntimeException) {
            requestFailClosedFromAnyThread(reason = MAIN_HANDLER_UNAVAILABLE)
            throw eventDispatchFailure(MAIN_HANDLER_UNAVAILABLE, error)
        }
        if (!posted) {
            requestFailClosedFromAnyThread(reason = MAIN_HANDLER_UNAVAILABLE)
            throw eventDispatchFailure(MAIN_HANDLER_UNAVAILABLE)
        }

        val completed = try {
            dispatch.completed.await(EVENT_DISPATCH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Log.w(
                TAG,
                "结算观察事件分发被中断: event=${event.diagnosticName()}, " +
                    "waited=${nowMillis() - postedAt}ms, thread=${Thread.currentThread().name}"
            )
            requestFailClosedFromAnyThread(reason = EVENT_PROCESSING_FAILED)
            Thread.currentThread().interrupt()
            throw eventDispatchFailure(EVENT_PROCESSING_FAILED)
        }
        if (!completed) {
            // 主线程在超时窗口内未能处理该事件：这在真机上多为暂时性卡顿
            // （伴随 Choreographer Skipped frames / Davey!），而非协调器损坏。
            // 此前这里直接熔断，代价极不对称：一次 5 秒卡顿就让截图能力永久失效，
            // 手动截图恒返回 SERVICE_NOT_CONNECTED、自动截图静默归零。
            // 改为放弃本次事件、保留协调器：投递到主线程的任务仍会稍后执行，
            // 后续事件可继续正常处理。真正不可恢复的故障
            // （handler 不可用、截图线程异常）仍走熔断路径。
            Log.w(
                TAG,
                "结算观察事件分发超时，放弃本次事件但保留协调器: " +
                    "event=${event.diagnosticName()}, " +
                    "waited=${nowMillis() - postedAt}ms, " +
                    "timeout=${EVENT_DISPATCH_TIMEOUT_MILLIS}ms, " +
                    "thread=${Thread.currentThread().name}"
            )
            throw eventDispatchFailure(EVENT_PROCESSING_FAILED)
        }

        dispatch.failure?.let { error ->
            // 单个事件处理失败不再熔断整个协调器：具体的关闭决策已在
            // runEventOnMainThread 内按异常类型区分（RuntimeException 保留协调器，
            // Error 才熔断），此处只需把失败上抛给调用方。
            Log.w(
                TAG,
                "结算观察事件在主线程处理失败: event=${event.diagnosticName()}, " +
                    "waited=${nowMillis() - postedAt}ms",
                error
            )
            throw eventDispatchFailure(EVENT_PROCESSING_FAILED, error)
        }
    }

    private fun runEventOnMainThread(event: CheckoutObservationEvent): Throwable? = try {
        handleEventOnMainThread(event)
        null
    } catch (error: RuntimeException) {
        // 单个事件的业务异常（节点树读取失败、状态不一致等）是可恢复的：
        // 熔断会让截图能力永久失效，代价远大于放弃这一个事件。
        // 保留协调器，后续事件继续正常处理。
        Log.w(TAG, "结算观察事件处理失败，已放弃该事件但保留协调器", error)
        error
    } catch (error: Throwable) {
        // Error（OutOfMemoryError 等）表明运行时已不可信，必须熔断收口，
        // 避免继续持有截图字节与缓冲状态。
        Log.w(TAG, "结算观察事件处理发生严重错误，已关闭截图协调器", error)
        failClosedOnMainThread(reason = EVENT_PROCESSING_FAILED)
        error
    }

    private fun eventDispatchFailure(reason: String, cause: Throwable? = null): IllegalStateException =
        IllegalStateException(reason, cause)

    private fun handleEventOnMainThread(event: CheckoutObservationEvent) {
        if (isClosedOnMainThread()) {
            // 协调器熔断后自动链路在此静默 return，真机表现为"自动截图 0 次"。
            // 无障碍事件每秒可达数十次，因此按时间采样打印，避免刷屏。
            val now = nowMillis()
            if (now - lastClosedEventSkipLogAt >= CLOSED_EVENT_SKIP_LOG_INTERVAL_MILLIS) {
                lastClosedEventSkipLogAt = now
                Log.w(
                    TAG,
                    "协调器已关闭，跳过结算观察事件: event=${event.diagnosticName()}, " +
                        "closed=$closed, failClosedRequested=${failClosedRequested.get()}, " +
                        "emergencyClosed=${emergencyClosed.get()}"
                )
            }
            return
        }
        eventGeneration += 1
        when (event) {
            is CheckoutObservationEvent.Stable -> handleStable(event.page)
            is CheckoutObservationEvent.Invalidated -> handleInvalidated(event.detail)
        }
    }

    private fun isClosedOnMainThread(): Boolean =
        closed || failClosedRequested.get() || emergencyClosed.get()

    /**
     * 协调器是否已进入不可逆的关闭状态。
     *
     * 熔断后 screenshotThread 已 quitSafely()，手动截图恒返回
     * SERVICE_NOT_CONNECTED、自动链路在事件入口静默 return，只有重建实例才能恢复。
     * 服务侧据此在事件入口自愈重建，避免"截图功能静默失效直到用户重启无障碍服务"。
     *
     * @return true 表示该实例已不可用，调用方应当重建协调器。
     */
    fun isPermanentlyClosed(): Boolean = shouldRecoverCoordinator(
        closed = closed,
        failClosedRequested = failClosedRequested.get(),
        emergencyClosed = emergencyClosed.get()
    )

    /** 仅在主线程摘除状态、关闭缓冲并发布取消结果。 */
    private fun failClosedOnMainThread(
        pending: PendingCapture? = null,
        imageBytes: ByteArray? = null,
        reason: String
    ) {
        imageBytes?.fill(0)
        pending?.releaseForEmergency()
        if (closed) {
            return
        }

        failClosedRequested.set(true)
        // 与主线程不可用 fallback 竞争关闭所有权，避免双方交叉清理协调器状态。
        if (!emergencyClosed.compareAndSet(false, true)) {
            Log.w(
                TAG,
                "协调器熔断请求被忽略(关闭所有权已被占用): reason=$reason, " +
                    "thread=${Thread.currentThread().name}"
            )
            return
        }
        closed = true
        // 熔断不可逆：下面会 quitSafely() 退出截图线程，此后所有手动截图返回
        // SERVICE_NOT_CONNECTED、自动链路在 handleEventOnMainThread 入口静默 return，
        // 只有服务重建才能恢复。必须留下原因与现场，否则真机上只能看到
        // "按钮点了没反应"而查不到任何关闭记录。
        Log.w(
            TAG,
            "协调器已熔断(不可逆，截图功能将持续不可用): reason=$reason, " +
                "thread=${Thread.currentThread().name}, " +
                "pendingCapture=${pendingCapture?.sourceReason?.wireValue ?: "none"}, " +
                "activeSession=${activeCheckout?.sessionId?.take(8) ?: "null"}, " +
                "handoffSession=${handoffCheckout?.activeCheckout?.sessionId?.take(8) ?: "null"}, " +
                "eventGeneration=$eventGeneration",
            Throwable("熔断调用栈")
        )
        try {
            buffer.close()
        } catch (error: RuntimeException) {
            Log.w(TAG, "关闭截图缓冲失败，继续释放协调器状态", error)
        }

        // 先完成协调器状态清理，再通知旧回调；回调重入只能看到已关闭的状态。
        try {
            screenshotThread.quitSafely()
        } catch (error: RuntimeException) {
            Log.w(TAG, "退出截图线程失败", error)
        }

        val pendingToNotify = mutableListOf<PendingCapture>()
        val queuedToNotify = mutableListOf<QueuedManualCapture>()
        pendingCapture?.let { current ->
            pendingToNotify += current
        }
        if (pending != null && pendingToNotify.none { it.requestId == pending.requestId }) {
            pendingToNotify += pending
        }
        pendingCapture = null
        pendingToNotify.forEach { it.releaseForEmergency() }

        queuedManual?.let { queuedToNotify += it }
        queuedManual = null
        queuedAutomatic = null
        activeCheckout = null
        handoffCheckout = null
        userRetryContext = null
        attemptedAutomaticCaptures.clear()
        resultListener = null

        pendingToNotify.forEach { cancelled ->
            publishPendingResult(
                pending = cancelled,
                result = cancelledResult(
                    current = cancelled.activeCheckout,
                    sourceReason = cancelled.sourceReason,
                    reason = reason
                )
            )
        }
        queuedToNotify.forEach { queued ->
            publishQueuedResult(
                queued = queued,
                result = cancelledResult(
                    current = null,
                    sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                    reason = reason,
                    key = queued.key
                )
            )
        }
    }

    /**
     * 主线程不可用时建立关闭语义并摘除全部协调器状态。
     * fallback 不发布回调；无法安全通知的回调会被记录，迟到路径只能释放资源后返回。
     */
    private fun emergencyCloseWithoutMainThread(
        pending: PendingCapture? = null,
        imageBytes: ByteArray? = null
    ) {
        imageBytes?.fill(0)
        pending?.releaseForEmergency()
        failClosedRequested.set(true)
        // 只有取得关闭所有权的一方可以清理普通协调器字段，避免覆盖主线程正常收口。
        if (!emergencyClosed.compareAndSet(false, true)) {
            Log.w(
                TAG,
                "主线程不可用兜底关闭被忽略(关闭所有权已被占用): " +
                    "thread=${Thread.currentThread().name}"
            )
            return
        }
        closed = true
        // 该路径不发布任何回调，调用方拿不到结果只会一直等待或静默失败，
        // 因此这里是唯一的现场记录点。
        Log.w(
            TAG,
            "协调器已紧急关闭(主线程不可用，不发布回调): " +
                "thread=${Thread.currentThread().name}, " +
                "pendingCapture=${pendingCapture?.sourceReason?.wireValue ?: "none"}, " +
                "queuedManual=${queuedManual != null}, " +
                "eventGeneration=$eventGeneration",
            Throwable("紧急关闭调用栈")
        )

        val pendingToRelease = pendingCapture
        val queuedToDrop = queuedManual
        pendingCapture = null
        queuedManual = null
        queuedAutomatic = null
        activeCheckout = null
        handoffCheckout = null
        userRetryContext = null
        attemptedAutomaticCaptures.clear()
        resultListener = null
        pendingToRelease?.let {
            it.releaseForEmergency()
            it.completionPublished.compareAndSet(false, true)
        }
        pending?.completionPublished?.compareAndSet(false, true)
        queuedToDrop?.completionPublished?.compareAndSet(false, true)
        if (pendingToRelease != null || pending != null || queuedToDrop != null) {
            Log.w(
                TAG,
                "主线程不可用，已丢弃未通知的截图回调: " +
                    "pending=" + (pendingToRelease?.requestId ?: pending?.requestId) +
                    ", queued=" + (queuedToDrop != null)
            )
        }
        try {
            buffer.close()
        } catch (error: RuntimeException) {
            Log.w(TAG, "主线程不可用时关闭截图缓冲失败", error)
        }
        try {
            screenshotThread.quitSafely()
        } catch (error: RuntimeException) {
            Log.w(TAG, "主线程不可用时退出截图线程失败", error)
        }
    }

    /** 请求主线程执行完整 fail-closed；只有投递或等待失败才走最小原子 fallback。 */
    private fun requestFailClosedFromAnyThread(
        pending: PendingCapture? = null,
        imageBytes: ByteArray? = null,
        reason: String
    ): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            failClosedOnMainThread(pending, imageBytes, reason)
            return true
        }

        failClosedRequested.set(true)
        // 该标记先于主线程投递生效：isClosedOnMainThread() 立即返回 true，
        // 因此从此刻起手动截图就会返回 SERVICE_NOT_CONNECTED，
        // 而真正的 failClosedOnMainThread 可能还排在阻塞的主线程队列里。
        // 真机故障（主线程卡顿 4.8 秒后事件分发超时）正是走这条路径，
        // 此处不记录就无法解释"点击受理后数秒才批量失败"。
        Log.w(
            TAG,
            "已请求协调器熔断(跨线程，判定即刻生效): reason=$reason, " +
                "thread=${Thread.currentThread().name}, " +
                "pending=${pending?.sourceReason?.wireValue ?: "none"}",
            Throwable("熔断请求调用栈")
        )
        val dispatch = MainThreadDispatch()
        val posted = try {
            mainHandler.post {
                try {
                    failClosedOnMainThread(pending, imageBytes, reason)
                } catch (error: Throwable) {
                    dispatch.failure = error
                } finally {
                    dispatch.completed.countDown()
                }
            }
        } catch (_: RuntimeException) {
            false
        }
        if (!posted) {
            emergencyCloseWithoutMainThread(pending, imageBytes)
            return false
        }

        val completed = try {
            dispatch.completed.await(EVENT_DISPATCH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            emergencyCloseWithoutMainThread(pending, imageBytes)
            Thread.currentThread().interrupt()
            return false
        }
        if (!completed || dispatch.failure != null) {
            emergencyCloseWithoutMainThread(pending, imageBytes)
            return false
        }
        return emergencyClosed.get()
    }

    /** 设置自动截图结果监听器；监听器不拥有图片引用。 */
    fun setResultListener(listener: SnapshotCaptureResultListener?) {
        val posted = try {
            mainHandler.post {
                resultListener = listener
            }
        } catch (_: RuntimeException) {
            false
        }
        if (!posted) {
            requestFailClosedFromAnyThread(reason = MAIN_HANDLER_UNAVAILABLE)
        }
    }

    /**
     * 挂起的交接会话在缓冲中是否仍有可消费候选。
     *
     * 用于区分两种交接状态：
     * - 有快照：槽位承载着等待支付交接消费的结算截图，必须保护；
     * - 无快照：结算截图在飞行中被中间页取消，或已过期被清理，此时交接只剩
     *   元数据外壳，继续独占槽位会让手动截图一直被 NO_ACTIVE_CHECKOUT 拒绝。
     *
     * @return true 表示存在挂起会话且其槽位内仍有该会话的候选。
     */
    private fun handoffCheckoutHasSnapshot(): Boolean {
        val retained = handoffCheckout?.activeCheckout ?: return false
        return try {
            buffer.currentCandidates(retained.slotId).any { candidate ->
                candidate.sessionId == retained.sessionId
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    /**
     * 按秒采样记录"结算页上下文仅作兜底、不建立会话"。
     *
     * 该分支是本轮回归的修复点：结算页上下文若建立 USER_RETRY 会话，
     * 会把结算会话降级并使自动截图失效。留痕便于复测确认修复生效。
     *
     * @param shoppingPackage 上下文所属购物包，仅用于诊断。
     */
    private fun logCheckoutContextKeptAsFallback(shoppingPackage: String?) {
        val now = nowMillis()
        if (now - lastCheckoutContextFallbackLogAt < 5_000L) {
            return
        }
        lastCheckoutContextFallbackLogAt = now
        Log.i(
            TAG,
            "结算页手动上下文仅作兜底，不建立手动会话: pkg=${shoppingPackage ?: "null"}, " +
                "activeTarget=${activeCheckout?.captureTarget?.name ?: "null"}"
        )
    }

    /**
     * 指定槽位内是否存在自动截图（CHECKOUT_STABLE）候选。
     *
     * 自动候选是结算页快照，正等待支付交接消费，任何手动重试路径都不得清理。
     * 与 handoffCheckoutHasSnapshot 的区别是本方法不要求存在挂起会话：
     * 结算态尚未挂起时自动候选同样需要保护。
     *
     * @param slotId 购物包槽位标识。
     * @return true 表示槽位内已有自动截图候选。
     */
    private fun hasAutomaticCandidate(slotId: String): Boolean = try {
        buffer.currentCandidates(slotId).any { candidate ->
            candidate.sourceReason == SnapshotSourceReason.CHECKOUT_STABLE
        }
    } catch (_: RuntimeException) {
        false
    }

    /**
     * 挂起的交接会话是否已设置支付交接栅栏。
     *
     * 与 handoffCheckoutHasSnapshot 互补：栅栏在"结算页稳定 → 跳中间页"时即已
     * 设置，而截图可能还在飞行中尚未落盘。京东弹窗结算页数百毫秒即跳转，
     * 这段落盘空窗内槽位候选为空，只看快照会把正在交接的会话误判为空壳并放行
     * 抢占，栅栏随即被 discard，handoffCheckout 却仍指向该会话形成永久残留。
     *
     * @return true 表示存在挂起会话且其槽位仍持有该会话的交接栅栏。
     */
    private fun handoffCheckoutHasFence(): Boolean {
        val retained = handoffCheckout?.activeCheckout ?: return false
        return try {
            buffer.hasPaymentHandoffFence(retained.slotId, retained.sessionId)
        } catch (_: RuntimeException) {
            false
        }
    }

    /**
     * 按秒采样输出"交接挂起期间跳过手动重试接管"，避免中间页高频事件刷屏。
     *
     * 同时标明保护来源与包名：真机上该日志曾连续出现数十次，
     * 但无法区分"同包内保护真实快照"（正确）与"跨包越权拦截"（缺陷），
     * 排查时只能靠人工比对会话归属。
     *
     * @param contextPackage 被跳过的手动重试上下文所属购物包，仅用于诊断。
     * @param bySnapshot 是否因挂起会话仍有候选而拦截。
     * @param byFence 是否因挂起会话仍持有交接栅栏而拦截。
     */
    private fun logHandoffRetrySkipSampled(
        contextPackage: String?,
        bySnapshot: Boolean,
        byFence: Boolean
    ) {
        val now = nowMillis()
        if (now - lastHandoffRetrySkipLogAt < 1_000L) {
            return
        }
        lastHandoffRetrySkipLogAt = now
        Log.i(
            TAG,
            "交接挂起期间跳过手动重试接管: " +
                "handoffSession=${handoffCheckout?.activeCheckout?.sessionId?.take(8) ?: "null"}, " +
                "handoffPkg=${
                    handoffCheckout?.activeCheckout?.page?.observation?.shoppingPackage ?: "null"
                }, " +
                "contextPkg=${contextPackage ?: "null"}, " +
                "bySnapshot=$bySnapshot, byFence=$byFence"
        )
    }

    /** 设置服务最近一次确认的手动截图上下文；节点树不可读时只保留脱敏元数据并禁止捕获。 */
    fun setUserRetryContext(context: UserRetryCaptureContext?) {
        val posted = try {
            mainHandler.post {
                if (closed) {
                    return@post
                }
                val current = activeCheckout
                val activeUserRetry = current?.takeIf {
                    it.captureTarget == SnapshotCaptureTarget.USER_RETRY
                }
                val resolvedContext = context?.let { next ->
                    if (
                        activeUserRetry != null &&
                        next.isComplete() &&
                        next.sessionId.isNullOrBlank() &&
                        next.shoppingPackage == activeUserRetry.page.observation.shoppingPackage &&
                        next.pageFingerprint == activeUserRetry.page.observation.pageFingerprint &&
                        next.pageRole == activeUserRetry.page.observation.pageRole &&
                        next.isSensitivePage == activeUserRetry.page.observation.isSensitivePage
                    ) {
                        next.withSessionId(activeUserRetry.sessionId)
                    } else {
                        next
                    }
                }
                val previous = userRetryContext
                userRetryContext = resolvedContext
                // 支付交接挂起期间槽位属于挂起会话，其快照正等待切支付应用后被消费，
                // 此时 activeCheckout 已被置空，不能让手动重试上下文接管并 discard。
                // 但若挂起会话既无快照也无交接栅栏（真空壳），继续拒绝只会让空壳交接
                // 长期霸占槽位、手动截图一直失败，此时应允许接管。
                // 栅栏必须单独判断：京东弹窗结算页在截图落盘前就跳中间页，
                // 那段空窗里候选为空但栅栏已设，只看快照会误放行并毁掉交接。
                // 保护还必须限定同购物包：槽位按购物包划分，京东挂起会话无权
                // 拦截拼多多页面的手动截图（真机曾因此让拼多多连续点击全部失败）。
                val handoffPackage = handoffCheckout?.activeCheckout
                    ?.page?.observation?.shoppingPackage?.trim()
                val contextPackage = resolvedContext?.shoppingPackage?.trim()
                val handoffSharesPackage = !handoffPackage.isNullOrBlank() &&
                    handoffPackage == contextPackage
                val handoffHasSnapshot = handoffSharesPackage && handoffCheckoutHasSnapshot()
                val handoffHasFence = handoffSharesPackage && handoffCheckoutHasFence()
                if (!canUserRetryTakeOverSlot(
                        handoffHasSnapshot = handoffHasSnapshot,
                        activeCaptureTarget = current?.captureTarget,
                        handoffHasFence = handoffHasFence,
                        handoffSharesPackage = handoffSharesPackage
                    )
                ) {
                    if (handoffHasSnapshot || handoffHasFence) {
                        logHandoffRetrySkipSampled(
                            contextPackage = contextPackage,
                            bySnapshot = handoffHasSnapshot,
                            byFence = handoffHasFence
                        )
                    }
                    return@post
                }
                if (current?.captureTarget == SnapshotCaptureTarget.USER_RETRY) {
                    val sameContext = resolvedContext?.let {
                        it.sessionId == current.sessionId &&
                            it.shoppingPackage == current.page.observation.shoppingPackage &&
                            it.pageFingerprint == current.page.observation.pageFingerprint &&
                            it.pageRole == current.page.observation.pageRole &&
                            it.isSensitivePage == current.page.observation.isSensitivePage
                    } == true
                    if (!sameContext || resolvedContext?.isComplete() != true) {
                        activeCheckout = null
                        // 同样不得清掉自动截图候选：真机日志中该 discard 曾在手动截图
                        // 成功后 1ms 就把 candidates=MANUAL_CAPTURE@... 连同槽位一起
                        // 丢弃（consumed=false），使支付交接无图可用。
                        if (!hasAutomaticCandidate(current.slotId)) {
                            buffer.discard(current.slotId, reason = "USER_RETRY_CONTEXT_CHANGED")
                        }
                    } else if (resolvedContext != null) {
                        activeCheckout = current.copy(
                            page = StableCheckoutPage(
                                observation = resolvedContext.observation,
                                stableAt = nowMillis()
                            )
                        )
                    }
                }

                // 只有"当前无会话"或"已是手动重试会话"时才可建立 USER_RETRY 会话。
                //
                // 关键约束：结算页角色（CHECKOUT）的上下文只作兜底记录，绝不可在此
                // 篡夺会话。59a41b9 让结算页也建立手动上下文以支持停留期间补截，
                // 但当时未排除 CHECKOUT 角色，导致真机上出现连锁故障：
                //   13:13:17.164 候选已写入 source=MANUAL_CAPTURE
                //   13:13:17.165 槽位丢弃 USER_RETRY_CONTEXT_CHANGED, consumed=false
                //   13:13:17.797 截图状态 REJECTED, 来源=CHECKOUT_STABLE,
                //                原因=NOT_CHECKOUT_PAGE
                // 结算会话被降级为 USER_RETRY 后，自动截图再也找不到 CHECKOUT 会话，
                // 且指纹一变就把刚存的快照自毁，最终 CHECKOUT_REFERENCE_INVALID
                // 落孤儿待确认。
                // 结算页的自动截图链路自有 handleStable 负责建立 CHECKOUT 会话；
                // 手动补截在结算态挂起（activeCheckout=null）后仍可正常建立。
                val contextIsCheckoutRole =
                    resolvedContext?.pageRole == CheckoutPageRole.CHECKOUT
                if (contextIsCheckoutRole) {
                    // 结算页上下文只作兜底记录，不建立会话；日志留痕便于复测确认。
                    logCheckoutContextKeptAsFallback(resolvedContext?.shoppingPackage)
                }
                if (canEstablishUserRetrySession(
                        contextRole = resolvedContext?.pageRole,
                        contextUsable = resolvedContext?.hasSafeDisplayContext() == true &&
                            resolvedContext.isComplete(),
                        activeCaptureTarget = activeCheckout?.captureTarget
                    ) && resolvedContext != null
                ) {
                    val sessionId = resolvedContext.sessionId ?: sessionIdFactory()
                    val previousKey = previous?.let {
                        it.shoppingPackage to it.pageFingerprint
                    }
                    val nextKey = resolvedContext.shoppingPackage to resolvedContext.pageFingerprint
                    // 换页时清理旧的手动候选，避免把上一页的图误配给新页面。
                    // 但绝不能清掉自动截图（CHECKOUT_STABLE）候选：那是结算页快照，
                    // 正等待支付交接消费。真机上该 discard 每秒可触发数次
                    // （日志"期间已抑制 14 次"），一旦误清就只能落孤儿待确认。
                    val slotId = checkoutSnapshotSlotId(resolvedContext.shoppingPackage)
                    if (previousKey != nextKey && !hasAutomaticCandidate(slotId)) {
                        buffer.discard(slotId, reason = "USER_RETRY_NEW_PAGE")
                    }
                    activeCheckout = ActiveCheckout(
                        sessionId = sessionId,
                        slotId = slotId,
                        page = StableCheckoutPage(
                            observation = resolvedContext.observation,
                            stableAt = nowMillis()
                        ),
                        captureTarget = SnapshotCaptureTarget.USER_RETRY,
                        sessionCreatedAt = nowMillis()
                    )
                    userRetryContext = resolvedContext.copy(sessionId = sessionId)
                }
            }
        } catch (_: RuntimeException) {
            false
        }
        if (!posted) {
            requestFailClosedFromAnyThread(reason = MAIN_HANDLER_UNAVAILABLE)
        }
    }

    /** 用户点击入口；手动截图不等待新的稳定窗口，但仍必须经过结算页门禁。 */
    fun requestManualCapture(callback: SnapshotCaptureCallback) {
        val posted = try {
            mainHandler.post {
                if (closed) {
                    // 悬浮按钮此时仍显示 READY 并受理点击，用户只会看到"点了没反应"。
                    // 打印三个关闭标志位以区分：正常 close(SERVICE_DESTROYED)、
                    // 跨线程熔断请求、以及主线程不可用的紧急关闭。
                    Log.w(
                        TAG,
                        "手动截图被拒(协调器已关闭): closed=$closed, " +
                            "failClosedRequested=${failClosedRequested.get()}, " +
                            "emergencyClosed=${emergencyClosed.get()}"
                    )
                    publish(
                        unavailableResult(
                            sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                            reasonCode = SnapshotCaptureUnavailableReason.SERVICE_NOT_CONNECTED.wireValue
                        ),
                        callback
                    )
                    return@post
                }

                val current = activeCheckout
                if (current == null) {
                    publish(
                        rejectedResult(
                            sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                            reason = SnapshotCaptureRejection.NO_ACTIVE_CHECKOUT
                        ),
                        callback
                    )
                    return@post
                }

                if (current.captureTarget == SnapshotCaptureTarget.CHECKOUT) {
                    when (pageDetector.detect(current.page.observation)) {
                        is CheckoutPageDetection.Accepted -> Unit
                        is CheckoutPageDetection.Rejected -> {
                            publish(
                                rejectedResult(
                                    current = current,
                                    sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                                    reason = if (current.page.observation.isSensitivePage) {
                                        SnapshotCaptureRejection.SENSITIVE_PAGE
                                    } else {
                                        SnapshotCaptureRejection.NOT_CHECKOUT_PAGE
                                    }
                                ),
                                callback
                            )
                            return@post
                        }
                    }
                } else if (userRetryContext?.isComplete() != true ||
                    userRetryContext?.sessionId != current.sessionId ||
                    userRetryContext?.pageFingerprint != current.page.observation.pageFingerprint
                ) {
                    publish(
                        rejectedResult(
                            current = current,
                            sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                            reason = SnapshotCaptureRejection.NOT_CHECKOUT_PAGE
                        ),
                        callback
                    )
                    return@post
                }

                val observation = current.page.observation
                if (observation.isSensitivePage) {
                    publish(
                        rejectedResult(
                            current = current,
                            sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                            reason = SnapshotCaptureRejection.SENSITIVE_PAGE
                        ),
                        callback
                    )
                    return@post
                }

                if (buffer.hasCandidate(
                        slotId = current.slotId,
                        sessionId = current.sessionId,
                        pageFingerprint = observation.pageFingerprint,
                        sourceReason = SnapshotSourceReason.MANUAL_CAPTURE
                    )
                ) {
                    publish(
                        duplicateResult(current, SnapshotSourceReason.MANUAL_CAPTURE),
                        callback
                    )
                    return@post
                }

                val inFlight = pendingCapture
                if (inFlight != null) {
                    if (inFlight.sourceReason == SnapshotSourceReason.MANUAL_CAPTURE || queuedManual != null) {
                        publish(
                            rejectedResult(
                                current = current,
                                sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                                reason = SnapshotCaptureRejection.CAPTURE_IN_FLIGHT
                            ),
                            callback
                        )
                        return@post
                    }
                    // 自动截图在飞时手动请求不再排队等待：排队期间页面失效会让手动请求
                    // 被静默取消，且 1~2 秒的等待内用户得不到任何反馈。按不变式第 5 条
                    // （用户手动操作优先级最高），取消飞行中的自动截图并立即执行手动。
                    // 自动 key 从已尝试集合移除：手动若失败，后续稳定事件仍可自动补截兜底。
                    val preempted = detachPendingCapture()
                    if (preempted != null) {
                        attemptedAutomaticCaptures.remove(preempted.activeCheckout.key)
                        publishPendingResult(
                            pending = preempted,
                            result = cancelledResult(
                                current = preempted.activeCheckout,
                                sourceReason = preempted.sourceReason,
                                reason = PREEMPTED_BY_MANUAL_CAPTURE
                            )
                        )
                    }
                }

                beginCapture(current, SnapshotSourceReason.MANUAL_CAPTURE, callback)
            }
        } catch (_: RuntimeException) {
            false
        }
        if (!posted) {
            requestFailClosedFromAnyThread(reason = MAIN_HANDLER_UNAVAILABLE)
            Log.w(TAG, "主线程不可用时手动截图未能发布结果")
        }
    }

    /**
     * 返回某个稳定帧所对应会话的 sessionId（§8.3 sessionId 传递链路的第③环）。
     *
     * 只回答会话标识，不暴露槽位、图片或页面文本；判据见 [stableFrameSessionId]。
     * **仅可在主线程调用**：`activeCheckout` 由主线程独占读写，服务层在
     * `dispatchEvent` 内（稳定协调器的回调，主线程）紧接 `onEvent` 之后调用它。
     *
     * @return 会话标识；本帧未建立/沿用任何会话时返回 null —— 调用方不得回退猜测。
     */
    fun currentCheckoutSessionId(
        shoppingPackage: String,
        pageFingerprint: String
    ): String? {
        if (closed || shoppingPackage.isBlank() || pageFingerprint.isBlank()) {
            return null
        }
        val current = activeCheckout ?: return null
        return stableFrameSessionId(
            activeSessionId = current.sessionId,
            activeShoppingPackage = current.page.observation.shoppingPackage,
            activePageFingerprint = current.page.observation.pageFingerprint,
            observedShoppingPackage = shoppingPackage,
            observedPageFingerprint = pageFingerprint
        )
    }

    /**
     * 给支付结果页交接兜底：即使服务层 currentStableCheckout 因瞬时事件被清空，
     * 截图协调器仍持有 activeCheckout/handoffCheckout 时，优先返回 buffer 中
     * 的真实引用；buffer 已被清空时用页面元数据构造引用，供支付证据建立会话。
     */
    fun currentPaymentHandoffReference(): CheckoutSnapshotReference? {
        if (closed) return null
        val source = handoffCheckout?.activeCheckout ?: activeCheckout ?: return null
        val observation = source.page.observation
        val buffered = buffer.findReference(source.slotId, observation.pageFingerprint)
        return buffered ?: CheckoutSnapshotReference(
            snapshotId = "",
            sessionId = source.sessionId,
            slotId = source.slotId,
            shoppingPackage = observation.shoppingPackage,
            pageRole = observation.pageRole,
            pageFingerprint = observation.pageFingerprint,
            capturedAt = observation.observedAt,
            expiresAt = observation.observedAt + 30_000L,
            candidateRevision = 0,
            sourceReason = SnapshotSourceReason.CHECKOUT_STABLE
        )
    }

    /** 服务中断时立即取消回调并清理当前购物包槽位。 */
    fun onServiceInterrupted() {
        val posted = try {
            mainHandler.post {
                try {
                    if (!closed) {
                        handleInvalidated(
                            CheckoutObservationInvalidation(
                                reason = CheckoutInvalidationReason.SERVICE_INTERRUPTED,
                                shoppingPackage = activeCheckout?.page?.observation?.shoppingPackage,
                                pageFingerprint = activeCheckout?.page?.observation?.pageFingerprint,
                                invalidatedAt = nowMillis()
                            )
                        )
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "服务中断清理失败，已关闭截图协调器", error)
                    failClosedOnMainThread(reason = EVENT_PROCESSING_FAILED)
                }
            }
        } catch (_: RuntimeException) {
            false
        }
        if (!posted) {
            requestFailClosedFromAnyThread(reason = MAIN_HANDLER_UNAVAILABLE)
        }
    }

    /** 服务销毁时关闭协调器并释放所有正在等待的图片。 */
    fun close() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            closeOnMainThread()
            return
        }

        val closeDispatch = MainThreadDispatch()
        val posted = try {
            mainHandler.post {
                try {
                    closeOnMainThread()
                } catch (error: Throwable) {
                    closeDispatch.failure = error
                } finally {
                    closeDispatch.completed.countDown()
                }
            }
        } catch (_: RuntimeException) {
            false
        }
        if (!posted) {
            emergencyCloseWithoutMainThread()
            throw eventDispatchFailure(MAIN_HANDLER_UNAVAILABLE)
        }

        val completed = try {
            closeDispatch.completed.await(EVENT_DISPATCH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            emergencyCloseWithoutMainThread()
            Thread.currentThread().interrupt()
            throw eventDispatchFailure(EVENT_PROCESSING_FAILED)
        }
        if (!completed) {
            emergencyCloseWithoutMainThread()
            throw eventDispatchFailure(EVENT_PROCESSING_FAILED)
        }
        closeDispatch.failure?.let { error ->
            emergencyCloseWithoutMainThread()
            throw eventDispatchFailure(EVENT_PROCESSING_FAILED, error)
        }
    }

    private fun closeOnMainThread() {
        if (closed) {
            return
        }
        // 关闭先建立 buffer 消费栅栏，再释放旧回调，避免回调重入消费旧快照。
        failClosedOnMainThread(reason = CheckoutInvalidationReason.SERVICE_DESTROYED.wireValue)
    }

    private fun publishPendingResult(
        pending: PendingCapture,
        result: SnapshotCaptureResult
    ) {
        if (pending.completionPublished.compareAndSet(false, true)) {
            publish(result, pending.callback)
        }
    }

    private fun publishQueuedResult(
        queued: QueuedManualCapture,
        result: SnapshotCaptureResult
    ) {
        if (queued.completionPublished.compareAndSet(false, true)) {
            publish(result, queued.callback)
        }
    }

    /**
     * 判断旧结算会话的截图是否值得跨指纹漂移保留。
     *
     * 只有同一购物包内的指纹变化才可能是轮播/倒计时造成的漂移；切换购物应用
     * 一定是新订单，旧图必须释放，否则会把上一单的截图关联到本单支付证据。
     *
     * 另外要求旧槽位确实存在未过期、未消费的候选，避免为空槽位保留消费墓碑
     * 而阻塞新会话截图。
     *
     * @param previous 漂移前的结算会话。
     * @param observation 新的稳定页面观察结果。
     * @return true 表示旧槽位应保留，不执行 discard。
     */
    private fun hasRetainableCandidate(
        previous: ActiveCheckout,
        observation: CheckoutPageObservation
    ): Boolean {
        if (previous.page.observation.shoppingPackage != observation.shoppingPackage) {
            return false
        }
        if (previous.captureTarget != SnapshotCaptureTarget.CHECKOUT) {
            return false
        }
        // 改造 C / Y4 判据 1（金额）：两侧都能判定且不相等 ⇒ 真的是另一笔订单 ⇒ 不沿用。
        // 任一侧为 null（页面无货币金额，或存在多金额歧义）⇒ 视为"无法判定" ⇒
        // **保守沿用**（§2.3 R3：宁可少新建，不可误新建）。
        val previousAmount = previous.page.observation.finalAmountMinor
        val currentAmount = observation.finalAmountMinor
        if (previousAmount != null && currentAmount != null && previousAmount != currentAmount) {
            return false
        }
        // 改造 C / Y4 判据 2（时间上限）：会话已沿用超龄 ⇒ 不再沿用 ⇒ 新建会话。
        // 起算点 = sessionCreatedAt（沿用期间不刷新）。⚠️ 与 PAYMENT_PROCESS_TTL_MILLIS
        // 同值不同义、不得联动，见 MAX_SESSION_REUSE_MILLIS 的 KDoc。
        if (nowMillis() - previous.sessionCreatedAt > MAX_SESSION_REUSE_MILLIS) {
            return false
        }
        return try {
            buffer.currentCandidates(previous.slotId).any { candidate ->
                candidate.sessionId == previous.sessionId
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun handleStable(page: StableCheckoutPage) {
        val transitionGeneration = eventGeneration
        val observation = page.observation
        // 真实结算事件优先于普通页手动重试上下文，避免两个目标共享一次点击。
        userRetryContext = null
        if (pageDetector.detect(observation) !is CheckoutPageDetection.Accepted) {
            handleInvalidated(
                CheckoutObservationInvalidation(
                    reason = if (observation.isSensitivePage) {
                        CheckoutInvalidationReason.SENSITIVE_PAGE
                    } else {
                        CheckoutInvalidationReason.NON_CHECKOUT_PAGE
                    },
                    shoppingPackage = observation.shoppingPackage,
                    pageFingerprint = observation.pageFingerprint,
                    invalidatedAt = nowMillis()
                )
            )
            return
        }

        val retainedHandoff = handoffCheckout
        if (retainedHandoff != null) {
            if (closed) {
                return
            }
            val retained = retainedHandoff.activeCheckout
            when (
                handoffStableEventAction(
                    observedPackage = observation.shoppingPackage,
                    observedFingerprint = observation.pageFingerprint,
                    retainedPackage = retained.page.observation.shoppingPackage,
                    retainedFingerprint = retained.page.observation.pageFingerprint,
                    handoffRetainedAtMillis = retainedHandoff.retainedAtMillis,
                    observedAtMillis = observation.observedAt
                )
            ) {
                HandoffStableEventAction.KEEP_RETAINED -> {
                    // 交接过渡期（挂起后 10s 内）同包迟到稳定事件：转场 UI 重发或
                    // 倒计时漂移，不代表新订单。保留挂起会话等支付证据消费，
                    // 丢弃会让证据落错会话（X1，2026-09-10 17:09 实证）。
                    Log.i(
                        TAG,
                        "交接过渡期忽略迟到稳定事件: slot=${retained.slotId}, " +
                            "session=${retained.sessionId.take(8)}, " +
                            "sinceRetained=${observation.observedAt - retainedHandoff.retainedAtMillis}ms, " +
                            "pkg=${observation.shoppingPackage}"
                    )
                    return
                }
                HandoffStableEventAction.RESTORE -> {
                    handoffCheckout = null
                    if (closed) {
                        return
                    }
                    // 支付应用返回原结算页时恢复原会话和尝试状态，不重新截图或覆盖原候选。
                    buffer.restoreFromPaymentHandoff(
                        slotId = retained.slotId,
                        sessionId = retained.sessionId,
                        pageFingerprint = observation.pageFingerprint
                    )
                    activeCheckout = retained.copy(page = page)
                    attemptedAutomaticCaptures.clear()
                    attemptedAutomaticCaptures += retainedHandoff.attemptedAutomaticCaptures
                    if (closed) {
                        // 关闭可能与恢复并发发生；不得让旧交接状态在失败收口后复活。
                        activeCheckout = null
                        handoffCheckout = null
                        attemptedAutomaticCaptures.clear()
                    }
                    return
                }
                HandoffStableEventAction.DISCARD_RETAINED -> {
                    // 返回到不同页面时，旧交接会话和其槽位不能被新订单复用。
                    handoffCheckout = null
                    buffer.discard(retained.slotId, reason = "HANDOFF_RETURNED_TO_OTHER_CHECKOUT")
                    attemptedAutomaticCaptures.clear()
                }
            }
        }

        val previous = activeCheckout
        // 同一购物包内的指纹变化可能只是轮播/倒计时造成的漂移，而非新订单。
        // 若旧会话已有未消费且未过期的截图，沿用旧会话与槽位：既避免把"已截到的图"
        // 在提交订单前清零（真机上曾导致支付交接只剩 NO_BUFFERED_SNAPSHOT），
        // 也让后续截图落在同一会话内由 buffer 按指纹去重。
        //
        // ⚠️ Y4 判据 1/2 落在 hasRetainableCandidate 内 ⇒ 只在指纹变化时才参与。
        // 有人会怀疑"指纹不变但金额变 ⇒ 仍沿用"是漏洞 —— **该组合不可达，不是缺陷**：
        // 指纹与金额同源于同一份页面文本，文本变则指纹必变；"指纹不变却金额变"
        // 要求同一份文本同时含两个不同金额。⇒ 勿为此"顺手修"（用户 2026-09-16 评估）。
        val driftWithinSameCheckout = previous != null &&
            previous.page.observation.shoppingPackage == observation.shoppingPackage &&
            previous.page.observation.pageFingerprint != observation.pageFingerprint &&
            hasRetainableCandidate(previous, observation)
        val startsNewSession = previous == null ||
            previous.page.observation.shoppingPackage != observation.shoppingPackage ||
            (previous.page.observation.pageFingerprint != observation.pageFingerprint &&
                !driftWithinSameCheckout)
        var pendingToNotify: PendingCapture? = null
        var queuedManualToNotify: QueuedManualCapture? = null
        val current = if (startsNewSession) {
            // 先摘除旧请求和排队项；回调必须等新会话及旧槽位清理完成后才能重入。
            pendingToNotify = detachPendingCapture()
            queuedManualToNotify = queuedManual
            queuedManual = null
            queuedAutomatic = null
            previous?.let { oldCheckout ->
                // 新指纹代表新的订单边界；先释放旧槽位，避免旧图片或消费墓碑阻塞新会话。
                buffer.discard(oldCheckout.slotId, reason = "NEW_CHECKOUT_SESSION")
                attemptedAutomaticCaptures.removeAll { it.sessionId == oldCheckout.sessionId }
            }
            ActiveCheckout(
                sessionId = sessionIdFactory(),
                slotId = slotIdFor(observation.shoppingPackage),
                page = page,
                captureTarget = SnapshotCaptureTarget.CHECKOUT,
                sessionCreatedAt = nowMillis()
            )
        } else {
            if (driftWithinSameCheckout) {
                Log.i(
                    TAG,
                    "结算页指纹漂移沿用原会话: slot=${previous!!.slotId}, " +
                        "session=${previous.sessionId.take(8)}"
                )
            }
            previous!!.copy(page = page)
        }
        activeCheckout = current

        pendingToNotify?.let { pending ->
            publishPendingResult(
                pending = pending,
                result = cancelledResult(
                    current = pending.activeCheckout,
                    sourceReason = pending.sourceReason,
                    reason = SnapshotCaptureRejection.PAGE_CHANGED.wireValue
                )
            )
        }
        queuedManualToNotify?.let { queued ->
            publishQueuedResult(
                queued = queued,
                result = cancelledResult(
                    current = previous,
                    sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                    reason = SnapshotCaptureRejection.PAGE_CHANGED.wireValue,
                    key = queued.key
                )
            )
        }

        // 取消回调允许同步重入；若重入已经推进事件序列，旧事件不得继续写入或排队。
        if (closed || eventGeneration != transitionGeneration || activeCheckout?.key != current.key) {
            if (closed) {
                activeCheckout = null
                handoffCheckout = null
                queuedAutomatic = null
            }
            return
        }

        val key = current.key
        if (key in attemptedAutomaticCaptures) {
            return
        }

        // 指纹漂移沿用原会话时，槽内已有本会话的可用截图：结算页内容并未真正改变，
        // 不必为每一次漂移重复截图（真机上曾在 8 秒内连截 6 张并互相覆盖）。
        // 后续支付交接会按槽内真实指纹消费这张已有截图。
        if (driftWithinSameCheckout) {
            attemptedAutomaticCaptures += key
            publish(duplicateResult(current, SnapshotSourceReason.CHECKOUT_STABLE))
            return
        }

        // D-16 = B（自动优先）：此处原有"同指纹已有手动候选 ⇒ 前置拒绝自动截图"守卫，
        // 与职责边界原则冲突（同指纹说明自动也认得出这一页），已删除 ⇒ 自动截图继续，
        // 由 buffer 覆盖同指纹的手动候选。删除后本方法不再有与手动候选相关的前置分支。
        if (buffer.hasCandidate(
                slotId = current.slotId,
                sessionId = current.sessionId,
                pageFingerprint = observation.pageFingerprint,
                sourceReason = SnapshotSourceReason.CHECKOUT_STABLE
            )
        ) {
            attemptedAutomaticCaptures += key
            publish(duplicateResult(current, SnapshotSourceReason.CHECKOUT_STABLE))
            return
        }

        if (pendingCapture != null) {
            queuedAutomatic = current
            return
        }

        attemptedAutomaticCaptures += key
        beginCapture(current, SnapshotSourceReason.CHECKOUT_STABLE, callback = null)
    }

    private fun handleInvalidated(detail: CheckoutObservationInvalidation) {
        val current = activeCheckout
        val retained = handoffCheckout
        // 同购物包内的非结算中间页（拼多多提交订单后的收银台）是支付旅程的一部分：
        // 视作“预交接”挂起当前结算会话与快照，等待切支付应用或回到结算页恢复，
        // 而不是立即清空槽位导致已 STORED 的截图再也关联不上支付证据。
        //
        // 判定必须同时接受已挂起会话（retained）：预交接已把 activeCheckout 置空，
        // 此后同包内的每一个非结算页事件都会让 current==null。若只看 current，
        // 挂起期间的后续事件就会被当成"普通离开"而清空槽位——真机上京东在
        // 10:54:27 正确设置交接栅栏后，10:54:57 的同包事件即以
        // current=null,retained=3c91b93b,consumed=false 把唯一快照清空，
        // 随后支付证据只能落 NO_BUFFERED_SNAPSHOT / CHECKOUT_REFERENCE_INVALID。
        val handoffSource = current ?: retained?.activeCheckout
        val isPaymentInterstitial = isSamePackagePaymentInterstitial(
            reason = detail.reason,
            invalidatedPackage = detail.shoppingPackage,
            handoffSourcePackage = handoffSource?.page?.observation?.shoppingPackage
        )
        val retainForHandoff =
            (detail.reason == CheckoutInvalidationReason.PAYMENT_APP_HANDOFF || isPaymentInterstitial) &&
                handoffSource != null
        // 已挂起会话失去快照与栅栏后不能继续独占交接位，否则新会话永远无法交接。
        val replaceRetainedHandoff = retained != null && shouldReplaceHandoffCheckout(
            hasCurrentCheckout = current != null,
            retainedHasSnapshot = handoffCheckoutHasSnapshot(),
            retainedHasFence = handoffCheckoutHasFence()
        )
        val nextHandoff = if (!retainForHandoff) {
            null
        } else if (retained != null && !replaceRetainedHandoff) {
            retained
        } else {
            val activeForHandoff = checkNotNull(handoffSource)
            if (replaceRetainedHandoff) {
                Log.i(
                    TAG,
                    "挂起交接会话已无快照与栅栏，改用当前会话接替: " +
                        "旧=${retained?.activeCheckout?.sessionId?.take(8) ?: "null"}, " +
                        "新=${activeForHandoff.sessionId.take(8)}"
                )
            }
            HandoffCheckout(
                activeCheckout = activeForHandoff,
                attemptedAutomaticCaptures = attemptedAutomaticCaptures
                    .filter { it.sessionId == activeForHandoff.sessionId }
                    .toSet(),
                retainedAtMillis = nowMillis()
            )
        }

        // 失效决策链路诊断：retainForHandoff=false 即代表本次会清空全部图片候选。
        // current/retained 的组合可区分"首次进入中间页"与"中间页后续事件"；
        // nextHandoff 则暴露挂起会话是否被当前会话接替，用于识别残留会话。
        Log.i(
            TAG,
            "观察失效: reason=${detail.reason.wireValue}, " +
                "current=${current?.sessionId?.take(8) ?: "null"}, " +
                "retained=${retained?.activeCheckout?.sessionId?.take(8) ?: "null"}, " +
                "interstitial=$isPaymentInterstitial, retainForHandoff=$retainForHandoff, " +
                "nextHandoff=${nextHandoff?.activeCheckout?.sessionId?.take(8) ?: "null"}, " +
                "replacedHandoff=$replaceRetainedHandoff, " +
                "detailPkg=${detail.shoppingPackage ?: "null"}, " +
                "detailFingerprint=${detail.pageFingerprint?.take(12) ?: "null"}"
        )

        // 先摘除请求和排队项，再建立交接栅栏并清空旧状态，最后才允许旧回调重入。
        // 例外：支付交接（含同包中间页预交接）且截图仍在飞行中时不摘除请求。
        // 京东这类"结算页稳定后数百毫秒即跳中间页"的时序下，摘除等于把唯一一次
        // 结算截图取消，槽位永远为空，支付证据只能落 CHECKOUT_REFERENCE_INVALID。
        // 保留请求可让它落盘到挂起会话的槽位，交接时正常被消费。
        val keepPendingForHandoff = retainForHandoff &&
            pendingCapture != null &&
            pendingCapture?.sourceReason == SnapshotSourceReason.CHECKOUT_STABLE &&
            pendingCapture?.activeCheckout?.sessionId == nextHandoff?.activeCheckout?.sessionId
        val pendingToNotify = if (keepPendingForHandoff) {
            Log.i(
                TAG,
                "交接期间保留飞行中的结算截图: " +
                    "session=${pendingCapture?.activeCheckout?.sessionId?.take(8)}, " +
                    "reason=${detail.reason.wireValue}"
            )
            null
        } else {
            detachPendingCapture()
        }
        val queuedManualToNotify = queuedManual
        queuedManual = null
        queuedAutomatic = null
        userRetryContext = null
        // 只有明确的支付交接保留快照；普通离开和其他失效原因都清理槽位，避免旧图误用。
        if (!retainForHandoff) {
            buffer.clear(
                reason = "INVALIDATED_${detail.reason.wireValue}" +
                    "(current=${current != null},retained=${retained != null}," +
                    "interstitial=$isPaymentInterstitial," +
                    "pkg=${detail.shoppingPackage ?: "null"})"
            )
        } else {
            // 栅栏必须落在真正被挂起的那个会话上（nextHandoff），而不是 handoffSource。
            // 二次交接时 handoffSource 取的是当前会话，若它并非挂起会话，会用错误的
            // sessionId/指纹设置栅栏，真机上表现为"交接栅栏设置失败(槽位不存在)"。
            nextHandoff?.activeCheckout?.let { retainedCheckout ->
                buffer.retainForPaymentHandoff(
                    slotId = retainedCheckout.slotId,
                    sessionId = retainedCheckout.sessionId,
                    pageFingerprint = retainedCheckout.page.observation.pageFingerprint
                )
            }
        }
        handoffCheckout = nextHandoff
        activeCheckout = null
        attemptedAutomaticCaptures.clear()
        if (closed) {
            handoffCheckout = null
            activeCheckout = null
            queuedAutomatic = null
            return
        }

        pendingToNotify?.let { pending ->
            publishPendingResult(
                pending = pending,
                result = cancelledResult(
                    current = pending.activeCheckout,
                    sourceReason = pending.sourceReason,
                    reason = detail.reason.wireValue
                )
            )
        }
        queuedManualToNotify?.let { queued ->
            publishQueuedResult(
                queued = queued,
                result = cancelledResult(
                    current = current,
                    sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                    reason = detail.reason.wireValue,
                    key = queued.key
                )
            )
        }
    }

    private fun detachPendingCapture(): PendingCapture? {
        val pending = pendingCapture ?: return null
        // 先解除当前请求，晚到的系统回调只能走释放路径。
        pendingCapture = null
        pending.releaseForEmergency()
        return pending
    }

    private fun beginCapture(
        current: ActiveCheckout,
        sourceReason: SnapshotSourceReason,
        callback: SnapshotCaptureCallback?,
        completionPublished: AtomicBoolean = AtomicBoolean(false),
        queued: QueuedManualCapture? = null
    ) {
        if (closed) {
            val result = cancelledResult(
                current = current,
                sourceReason = sourceReason,
                reason = MAIN_HANDLER_UNAVAILABLE,
                key = queued?.key
            )
            if (queued != null) {
                publishQueuedResult(queued, result)
            } else {
                publish(result, callback)
            }
            return
        }
        val unavailableReason = screenshotUnavailableReason()
        if (unavailableReason != null) {
            val result = unavailableResult(
                current = current,
                sourceReason = sourceReason,
                reasonCode = unavailableReason.wireValue
            )
            if (queued != null) {
                publishQueuedResult(queued, result)
            } else {
                publish(result, callback)
            }
            drainQueuedCapture()
            return
        }

        val pending = PendingCapture(
            requestId = UUID.randomUUID().toString(),
            activeCheckout = current,
            sourceReason = sourceReason,
            callback = callback,
            completionPublished = completionPublished
        )
        pendingCapture = pending

        if (closed) {
            pendingCapture = null
            pending.releaseForEmergency()
            publishPendingResult(
                pending = pending,
                result = cancelledResult(
                    current = current,
                    sourceReason = sourceReason,
                    reason = MAIN_HANDLER_UNAVAILABLE,
                    key = queued?.key
                )
            )
            return
        }

        scheduleCaptureCallbackWatchdog(pending)

        try {
            @Suppress("NewApi")
            accessibilityService.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                callbackExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val encoded = encodeScreenshot(screenshot)
                        finishCapture(pending, encoded.bytes, encoded.reasonCode)
                    }

                    override fun onFailure(errorCode: Int) {
                        finishCapture(
                            pending = pending,
                            imageBytes = null,
                            failureReason = screenshotFailureReason(errorCode)
                        )
                    }
                }
            )
        } catch (_: SecurityException) {
            finishCapture(
                pending = pending,
                imageBytes = null,
                failureReason = SnapshotCaptureUnavailableReason.CAPABILITY_UNAVAILABLE.wireValue
            )
        } catch (_: IllegalStateException) {
            finishCapture(
                pending = pending,
                imageBytes = null,
                failureReason = SnapshotCaptureUnavailableReason.SYSTEM_REJECTED.wireValue
            )
        } catch (error: RuntimeException) {
            Log.w(TAG, "调用系统截图失败", error)
            finishCapture(
                pending = pending,
                imageBytes = null,
                failureReason = SnapshotCaptureUnavailableReason.SYSTEM_REJECTED.wireValue
            )
        }
    }

    /**
     * 给在飞截图挂回调超时看门狗。
     *
     * 只做一件事：超时后若该请求仍在 pendingCapture 位上，按 CANCELLED 收口并
     * 排空排队项。正常完成（finishCaptureOnMainThread 已摘除）或已被失效路径
     * 摘除时，requestId 不匹配使本次触发成为无操作，无需显式取消。
     * 看门狗在主线程执行，与协调器其余状态变更同线程串行，无额外同步要求。
     */
    private fun scheduleCaptureCallbackWatchdog(pending: PendingCapture) {
        val posted = try {
            mainHandler.postDelayed({
                if (pendingCapture?.requestId != pending.requestId) {
                    return@postDelayed
                }
                Log.w(
                    TAG,
                    "截图回调看门狗超时，按 CANCELLED 收口: request=${pending.requestId.take(8)}, " +
                        "source=${pending.sourceReason.wireValue}, " +
                        "session=${pending.activeCheckout.sessionId.take(8)}"
                )
                val timedOut = detachPendingCapture()
                if (timedOut != null) {
                    publishPendingResult(
                        pending = timedOut,
                        result = cancelledResult(
                            current = timedOut.activeCheckout,
                            sourceReason = timedOut.sourceReason,
                            reason = CAPTURE_CALLBACK_TIMEOUT
                        )
                    )
                    drainQueuedCapture()
                }
            }, CAPTURE_CALLBACK_TIMEOUT_MILLIS)
        } catch (_: RuntimeException) {
            false
        }
        if (!posted) {
            // 主线程不可用时走既有熔断路径；看门狗本身不承载关闭语义。
            requestFailClosedFromAnyThread(reason = MAIN_HANDLER_UNAVAILABLE)
        }
    }

    private fun finishCapture(
        pending: PendingCapture,
        imageBytes: ByteArray?,
        failureReason: String?
    ) {
        if (!pending.finishStarted.compareAndSet(false, true)) {
            imageBytes?.fill(0)
            return
        }
        if (!pending.offerImage(imageBytes)) {
            return
        }

        if (Looper.myLooper() != Looper.getMainLooper()) {
            val dispatch = MainThreadDispatch()
            val posted = try {
                mainHandler.post {
                    try {
                        finishCaptureOnMainThread(pending, failureReason)
                    } catch (error: Throwable) {
                        dispatch.failure = error
                        pending.takeImage()?.fill(0)
                        // Y5（2026-09-10 16:50 真机实证）：单次收口异常不熔断。
                        // 旧实现直接 failClosedOnMainThread，单事件异常即令协调器
                        // 永久关闭，配合服务重建形成"截图→异常→熔断→重建→再截图"
                        // 的"处理中"闪断循环。收口点在 pendingCapture 摘除之前均为
                        // 字段读取，抛异常概率极低；真异常由看门狗按 requestId
                        // 兜底收口。此处记录完整栈，修复旧实现只记 reason 不记因的
                        // 归因盲区（E 场景三次失败无法区分超时与异常）。
                        Log.w(
                            TAG,
                            "截图结果主线程收口异常(不熔断，看门狗按 requestId 兜底): " +
                                "pending=${pending.sourceReason.wireValue}, " +
                                "session=${pending.activeCheckout.sessionId.take(8)}",
                            error
                        )
                    } finally {
                        dispatch.completed.countDown()
                    }
                }
            } catch (_: RuntimeException) {
                false
            }
            if (!posted) {
                requestFailClosedFromAnyThread(
                    pending = pending,
                    reason = MAIN_HANDLER_UNAVAILABLE
                )
                return
            }

            val completed = try {
                dispatch.completed.await(EVENT_DISPATCH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                requestFailClosedFromAnyThread(
                    pending = pending,
                    reason = EVENT_PROCESSING_FAILED
                )
                Thread.currentThread().interrupt()
                return
            }
            if (!completed || dispatch.failure != null) {
                // Y5（2026-09-10 16:50 真机实证）：主线程卡顿（RN 调试包加载 +
                // 无障碍事件洪流，Choreographer 跳帧 350≈5.8s 冻结）时截图结果
                // 分发超时。旧实现直接熔断，代价与事件分发路径的历史教训完全相同
                // （见 shouldFailClosedForDispatchFailure：一次 5 秒卡顿令截图能力
                // 永久失效）。对齐该裁定：超时不熔断——投递已入队，主线程恢复后
                // 照常收口，看门狗与 requestId 比对保证迟到处理无副作用。
                Log.w(
                    TAG,
                    "截图结果主线程分发超时(已入队，不熔断): " +
                        "pending=${pending.sourceReason.wireValue}, " +
                        "completed=${completed}, " +
                        "failure=${dispatch.failure != null}, " +
                        "timeoutMs=$EVENT_DISPATCH_TIMEOUT_MILLIS, " +
                        "thread=${Thread.currentThread().name}"
                )
            }
            return
        }

        finishCaptureOnMainThread(pending, failureReason)
    }

    private fun finishCaptureOnMainThread(
        pending: PendingCapture,
        failureReason: String?
    ) {
        if (closed || emergencyClosed.get()) {
            pending.takeImage()?.fill(0)
            return
        }
        val imageBytes = pending.takeImage()
        if (pendingCapture?.requestId != pending.requestId) {
            imageBytes?.fill(0)
            return
        }
        pendingCapture = null

        // 交接挂起期间 activeCheckout 已被置空，但该请求属于挂起会话时仍应落盘：
        // 京东"结算页稳定后即跳中间页"的时序下，这是唯一一次结算截图。
        val belongsToHandoff = handoffCheckout?.activeCheckout?.key == pending.activeCheckout.key
        val keyMatches = activeCheckout?.key == pending.activeCheckout.key || belongsToHandoff
        if (pending.cancelled.get() || closed || !keyMatches) {
            imageBytes?.fill(0)
            pending.cancelled.set(true)
            publishPendingResult(
                pending = pending,
                result = cancelledResult(
                    current = pending.activeCheckout,
                    sourceReason = pending.sourceReason,
                    reason = SnapshotCaptureRejection.PAGE_CHANGED.wireValue
                )
            )
            drainQueuedCapture()
            return
        }

        val capturedBytes = imageBytes
        if (failureReason != null || capturedBytes == null || capturedBytes.isEmpty()) {
            capturedBytes?.fill(0)
            val result = if (failureReason == SnapshotCaptureUnavailableReason.ENCODE_FAILED.wireValue) {
                failedResult(
                    pending.activeCheckout,
                    pending.sourceReason,
                    failureReason ?: SnapshotCaptureUnavailableReason.ENCODE_FAILED.wireValue
                )
            } else {
                unavailableResult(
                    current = pending.activeCheckout,
                    sourceReason = pending.sourceReason,
                    reasonCode = failureReason ?: SnapshotCaptureUnavailableReason.ENCODE_FAILED.wireValue
                )
            }
            publishPendingResult(pending, result)
            drainQueuedCapture()
            return
        }

        val request = SnapshotCaptureRequest(
            slotId = pending.activeCheckout.slotId,
            sessionId = pending.activeCheckout.sessionId,
            shoppingPackage = pending.activeCheckout.page.observation.shoppingPackage,
            pageRole = pending.activeCheckout.page.observation.pageRole,
            pageFingerprint = pending.activeCheckout.page.observation.pageFingerprint,
            sourceReason = pending.sourceReason,
            imageBytes = capturedBytes,
            isSensitivePage = pending.activeCheckout.page.observation.isSensitivePage,
            // 手动截图统一改挂 USER_RETRY target：CHECKOUT target 在 buffer 门禁处只接受
            // CHECKOUT_STABLE 来源（CheckoutSnapshotBuffer.store 的 targetAllowed 判定），
            // 若沿用会话的 CHECKOUT target，手动截图会以 NOT_CHECKOUT_PAGE 被整张丢弃，
            // "自动已截 → 手动重截一张更好的"永远没有落盘通道。
            // canBufferUserRetrySnapshot() 已包含 CHECKOUT 角色（CheckoutContract.kt），
            // 且 USER_RETRY 落盘按既有语义解除消费墓碑，手动重截即一次新的明确消费机会。
            captureTarget = if (pending.sourceReason == SnapshotSourceReason.MANUAL_CAPTURE) {
                SnapshotCaptureTarget.USER_RETRY
            } else {
                pending.activeCheckout.captureTarget
            }
        )

        val result = try {
            try {
                storeResult(pending.activeCheckout, pending.sourceReason, buffer.store(request))
            } catch (error: RuntimeException) {
                Log.w(TAG, "写入截图缓冲失败", error)
                failedResult(pending.activeCheckout, pending.sourceReason, "BUFFER_STORE_FAILED")
            }
        } finally {
            // 缓冲区在成功时已复制字节；失败、拒绝和取消都在这里清零入口数组。
            capturedBytes.fill(0)
        }
        // 交接挂起期间落盘的结算截图，其栅栏在 handleInvalidated 时因槽位仍为空而
        // 设置失败，必须在此补设，否则支付交接消费时仍会 NO_BUFFERED_SNAPSHOT。
        if (belongsToHandoff && result.status == SnapshotCaptureStatus.STORED) {
            try {
                buffer.retainForPaymentHandoff(
                    slotId = pending.activeCheckout.slotId,
                    sessionId = pending.activeCheckout.sessionId,
                    pageFingerprint = pending.activeCheckout.page.observation.pageFingerprint
                )
                Log.i(
                    TAG,
                    "交接后落盘已补设栅栏: " +
                        "session=${pending.activeCheckout.sessionId.take(8)}, " +
                        "fingerprint=${pending.activeCheckout.page.observation.pageFingerprint.take(12)}"
                )
            } catch (error: RuntimeException) {
                Log.w(TAG, "交接后补设栅栏失败", error)
            }
        }
        publishPendingResult(pending, result)
        drainQueuedCapture()
    }

    private fun storeResult(
        current: ActiveCheckout,
        sourceReason: SnapshotSourceReason,
        storeResult: SnapshotStoreResult
    ): SnapshotCaptureResult {
        return when (storeResult) {
            is SnapshotStoreResult.Stored -> {
                val snapshot = storeResult.snapshot
                val result = SnapshotCaptureResult(
                    status = SnapshotCaptureStatus.STORED,
                    sourceReason = sourceReason,
                    reasonCode = SnapshotCaptureStatus.STORED.wireValue,
                    sessionId = snapshot.sessionId,
                    slotId = snapshot.slotId,
                    shoppingPackage = snapshot.shoppingPackage,
                    pageFingerprint = snapshot.pageFingerprint,
                    snapshotId = snapshot.snapshotId,
                    candidateRevision = storeResult.candidateRevision,
                    requiresDisambiguation = storeResult.requiresDisambiguation
                )
                snapshot.release()
                result
            }

            is SnapshotStoreResult.Unchanged -> {
                val snapshot = storeResult.snapshot
                val result = SnapshotCaptureResult(
                    status = SnapshotCaptureStatus.DUPLICATE,
                    sourceReason = sourceReason,
                    reasonCode = SnapshotCaptureStatus.DUPLICATE.wireValue,
                    sessionId = snapshot.sessionId,
                    slotId = snapshot.slotId,
                    shoppingPackage = snapshot.shoppingPackage,
                    pageFingerprint = snapshot.pageFingerprint,
                    snapshotId = snapshot.snapshotId,
                    candidateRevision = snapshot.candidateRevision
                )
                snapshot.release()
                result
            }

            is SnapshotStoreResult.Rejected -> SnapshotCaptureResult(
                status = SnapshotCaptureStatus.REJECTED,
                sourceReason = sourceReason,
                reasonCode = storeResult.reason.wireValue,
                sessionId = current.sessionId,
                slotId = current.slotId,
                shoppingPackage = current.page.observation.shoppingPackage,
                pageFingerprint = current.page.observation.pageFingerprint
            )
        }
    }

    private fun drainQueuedCapture() {
        if (closed || pendingCapture != null) {
            return
        }

        queuedManual?.let { queued ->
            queuedManual = null
            val current = activeCheckout
            if (current == null || current.key != queued.key) {
                publishQueuedResult(
                    queued = queued,
                    result = cancelledResult(
                        current = current,
                        sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                        reason = SnapshotCaptureRejection.PAGE_CHANGED.wireValue,
                        key = queued.key
                    )
                )
            } else {
                beginCapture(
                    current = current,
                    sourceReason = SnapshotSourceReason.MANUAL_CAPTURE,
                    callback = queued.callback,
                    completionPublished = queued.completionPublished,
                    queued = queued
                )
            }
            return
        }

        val queued = queuedAutomatic ?: return
        queuedAutomatic = null
        val current = activeCheckout
        if (current == null || current.key != queued.key || queued.key in attemptedAutomaticCaptures) {
            return
        }
        // D-16 = B（自动优先）：此处原有排队路径的"同指纹已有手动候选 ⇒ 前置拒绝"守卫，
        // 与 handleStable 的实时路径同源同改，已删除 ⇒ 排队自动截图照常发起。
        attemptedAutomaticCaptures += current.key
        beginCapture(current, SnapshotSourceReason.CHECKOUT_STABLE, callback = null)
    }

    private fun screenshotUnavailableReason(): SnapshotCaptureUnavailableReason? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return SnapshotCaptureUnavailableReason.API_UNSUPPORTED
        }
        val capabilities = try {
            accessibilityService.serviceInfo?.capabilities ?: 0
        } catch (_: RuntimeException) {
            0
        }
        return if (capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT == 0) {
            SnapshotCaptureUnavailableReason.CAPABILITY_UNAVAILABLE
        } else {
            null
        }
    }

    private data class EncodedScreenshot(
        val bytes: ByteArray?,
        val reasonCode: String?
    )

    /** 把硬件截图缩放并压缩到受限 JPEG 后，立即释放系统资源。 */
    private fun encodeScreenshot(screenshot: ScreenshotResult): EncodedScreenshot {
        var hardwareBuffer: HardwareBuffer? = null
        var hardwareBitmap: Bitmap? = null
        var softwareBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null
        var compressedBytes: ByteArray? = null
        var transferred = false
        val output = BoundedByteArrayOutputStream(MAX_ENCODED_SCREENSHOT_BYTES)

        try {
            val capturedBuffer = screenshot.hardwareBuffer
                ?: return EncodedScreenshot(null, SnapshotCaptureUnavailableReason.ENCODE_FAILED.wireValue)
            hardwareBuffer = capturedBuffer
            val capturedHardwareBitmap = Bitmap.wrapHardwareBuffer(capturedBuffer, screenshot.colorSpace)
                ?: return EncodedScreenshot(null, SnapshotCaptureUnavailableReason.ENCODE_FAILED.wireValue)
            hardwareBitmap = capturedHardwareBitmap
            val capturedSoftwareBitmap = capturedHardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: return EncodedScreenshot(null, SnapshotCaptureUnavailableReason.ENCODE_FAILED.wireValue)
            softwareBitmap = capturedSoftwareBitmap
            val longestEdge = maxOf(capturedSoftwareBitmap.width, capturedSoftwareBitmap.height)
            val bitmapToEncode = if (longestEdge > MAX_SCREENSHOT_LONG_EDGE) {
                val scale = MAX_SCREENSHOT_LONG_EDGE.toFloat() / longestEdge.toFloat()
                val targetWidth = (capturedSoftwareBitmap.width * scale)
                    .roundToInt()
                    .coerceAtLeast(1)
                val targetHeight = (capturedSoftwareBitmap.height * scale)
                    .roundToInt()
                    .coerceAtLeast(1)
                Bitmap.createScaledBitmap(
                    capturedSoftwareBitmap,
                    targetWidth,
                    targetHeight,
                    true
                ).also { scaledBitmap = it }
            } else {
                capturedSoftwareBitmap
            }
            if (!bitmapToEncode.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                return EncodedScreenshot(null, SnapshotCaptureUnavailableReason.ENCODE_FAILED.wireValue)
            }
            val encodedBytes = output.toByteArray()
            compressedBytes = encodedBytes
            if (encodedBytes.isEmpty() || encodedBytes.size > MAX_ENCODED_SCREENSHOT_BYTES) {
                return EncodedScreenshot(null, SnapshotCaptureUnavailableReason.ENCODE_FAILED.wireValue)
            }
            transferred = true
            return EncodedScreenshot(encodedBytes, null)
        } catch (_: RuntimeException) {
            return EncodedScreenshot(null, SnapshotCaptureUnavailableReason.ENCODE_FAILED.wireValue)
        } finally {
            if (!transferred) {
                compressedBytes?.fill(0)
            }
            output.clear()
            try {
                scaledBitmap?.recycle()
            } finally {
                try {
                    softwareBitmap?.recycle()
                } finally {
                    try {
                        hardwareBitmap?.recycle()
                    } finally {
                        hardwareBuffer?.close()
                    }
                }
            }
        }
    }

    /** 限制压缩输出不超过 5MB，避免超限图片在内存中完整暂存。 */
    private class BoundedByteArrayOutputStream(
        private val maxBytes: Int
    ) : ByteArrayOutputStream(32 * 1024) {
        fun clear() {
            buf.fill(0)
            reset()
        }

        override fun write(b: Int) {
            if (size() >= maxBytes) {
                throw ScreenshotTooLargeException()
            }
            super.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len > maxBytes - size()) {
                throw ScreenshotTooLargeException()
            }
            super.write(b, off, len)
        }
    }

    private class ScreenshotTooLargeException : RuntimeException()

    private fun screenshotFailureReason(errorCode: Int): String = when (errorCode) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
            SnapshotCaptureUnavailableReason.SCREENSHOT_SECURE.wireValue
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            SnapshotCaptureUnavailableReason.SCREENSHOT_BUSY.wireValue
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY,
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW ->
            SnapshotCaptureUnavailableReason.INVALID_DISPLAY.wireValue
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            SnapshotCaptureUnavailableReason.CAPABILITY_UNAVAILABLE.wireValue
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR ->
            SnapshotCaptureUnavailableReason.SYSTEM_REJECTED.wireValue
        else -> SCREENSHOT_ERROR_PREFIX + errorCode
    }

    private fun slotIdFor(shoppingPackage: String): String = checkoutSnapshotSlotId(shoppingPackage)

    private fun duplicateResult(
        current: ActiveCheckout,
        sourceReason: SnapshotSourceReason
    ): SnapshotCaptureResult = SnapshotCaptureResult(
        status = SnapshotCaptureStatus.DUPLICATE,
        sourceReason = sourceReason,
        reasonCode = SnapshotCaptureStatus.DUPLICATE.wireValue,
        sessionId = current.sessionId,
        slotId = current.slotId,
        shoppingPackage = current.page.observation.shoppingPackage,
        pageFingerprint = current.page.observation.pageFingerprint
    )

    private fun rejectedResult(
        current: ActiveCheckout? = null,
        sourceReason: SnapshotSourceReason,
        reason: SnapshotCaptureRejection
    ): SnapshotCaptureResult = SnapshotCaptureResult(
        status = SnapshotCaptureStatus.REJECTED,
        sourceReason = sourceReason,
        reasonCode = reason.wireValue,
        sessionId = current?.sessionId,
        slotId = current?.slotId,
        shoppingPackage = current?.page?.observation?.shoppingPackage,
        pageFingerprint = current?.page?.observation?.pageFingerprint
    )

    private fun unavailableResult(
        current: ActiveCheckout? = null,
        sourceReason: SnapshotSourceReason,
        reasonCode: String
    ): SnapshotCaptureResult = SnapshotCaptureResult(
        status = SnapshotCaptureStatus.UNAVAILABLE,
        sourceReason = sourceReason,
        reasonCode = reasonCode,
        sessionId = current?.sessionId,
        slotId = current?.slotId,
        shoppingPackage = current?.page?.observation?.shoppingPackage,
        pageFingerprint = current?.page?.observation?.pageFingerprint
    )

    private fun failedResult(
        current: ActiveCheckout,
        sourceReason: SnapshotSourceReason,
        reason: String
    ): SnapshotCaptureResult = SnapshotCaptureResult(
        status = SnapshotCaptureStatus.FAILED,
        sourceReason = sourceReason,
        reasonCode = reason,
        sessionId = current.sessionId,
        slotId = current.slotId,
        shoppingPackage = current.page.observation.shoppingPackage,
        pageFingerprint = current.page.observation.pageFingerprint
    )

    private fun cancelledResult(
        current: ActiveCheckout?,
        sourceReason: SnapshotSourceReason,
        reason: String,
        key: CaptureKey? = null
    ): SnapshotCaptureResult = SnapshotCaptureResult(
        status = SnapshotCaptureStatus.CANCELLED,
        sourceReason = sourceReason,
        reasonCode = reason,
        sessionId = current?.sessionId ?: key?.sessionId,
        slotId = current?.slotId,
        shoppingPackage = current?.page?.observation?.shoppingPackage,
        pageFingerprint = current?.page?.observation?.pageFingerprint ?: key?.pageFingerprint
    )

    private fun publish(
        result: SnapshotCaptureResult,
        callback: SnapshotCaptureCallback? = null
    ) {
        Log.i(TAG, "截图状态: ${result.status.wireValue}, 来源=${result.sourceReason.wireValue}, 原因=${result.reasonCode}")
        try {
            resultListener?.onResult(result)
        } catch (error: RuntimeException) {
            Log.w(TAG, "截图结果监听器处理失败", error)
        }
        if (callback != null) {
            try {
                callback.onResult(result)
            } catch (error: RuntimeException) {
                Log.w(TAG, "截图回调处理失败", error)
            }
        }
    }
}

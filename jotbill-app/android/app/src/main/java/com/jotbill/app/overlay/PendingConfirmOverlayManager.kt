package com.jotbill.app.overlay

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.jotbill.app.R
import com.jotbill.app.bridge.BridgeDtoMapper
import com.jotbill.app.bridge.sharedJsEventEmitter
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.JotBillDatabaseProvider
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.CategoryEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import com.jotbill.app.pending.PendingChangeHub
import com.jotbill.app.pending.PendingChangeInfo
import com.jotbill.app.pending.PendingConfirmReadiness
import com.jotbill.app.pending.PendingConfirmService
import com.jotbill.app.pending.PendingPromptPolicy
import com.jotbill.app.pending.PendingReminderNotifier
import com.jotbill.app.utils.JotBillLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 付款确认弹窗（移植自 LiteNote PaymentOverlayManager 的 UI 骨架）。
 *
 * 待确认创建事件到达时，在当前应用上方弹出确认卡片：预填金额/商户/账户，
 * 用户选分类、可改金额，一键经共享确认入口
 * [PendingConfirmService] 入账。触发门禁由 [PendingPromptPolicy] 决定；
 * 敏感页采用帧级门禁——只在最近一帧确认非敏感时显示，绝不遮挡密码键盘
 * （这与截图的 60 秒锁存不同：弹窗不采集屏幕内容，风险是遮挡而非外发）。
 *
 * 无障碍未运行或观察状态过期时一律不弹，系统通知兜底照常工作。
 *
 * ⚠️ **2026-10-12 用户裁定**：去重与频控两条规则废弃（见 [PendingPromptPolicy]）；
 * 弹窗未确认时 30s 自动关闭并补发"这笔还没记上"通知。
 * ⇒ **每一笔被识别出的支付都会弹窗**，用户注意力窗口 30s，
 * 超时只是转入待确认中心，不会丢账。
 */
internal object PendingConfirmOverlayManager {
    private const val TAG = "PendingConfirmOverlay"
    private const val PREFS_NAME = "pending_confirm_overlay"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_CATEGORY_ID = "last_category_id"

    /**
     * 悬浮窗自动消失时间：用户不操作则主动关闭，该笔转入待确认中心。
     *
     * ⚠️ 2026-10-12 由 60s 改为 30s（用户裁定）。语义是"给用户 30s 决定"，
     * **不是"30s 后丢弃"** —— pending 行始终在库里，关闭只清 UI 状态。
     * 自动关闭时会发一条系统通知（见 [dismissInternal] 的 AUTO_DISMISS 分支），
     * 避免用户误以为已入账。
     */
    private const val AUTO_DISMISS_DELAY = 30_000L
    private const val SUCCESS_DISPLAY_DELAY = 1_500L

    /**
     * "该待确认已在其他入口处理"错误页的自动关闭延时（2026-10-24 复审补）。
     *
     * 错误页会把 `content_main` 整体置 GONE，X/稍后按钮随之消失，只剩一个"重试"；
     * 而确认入口已经取消了 `autoDismissRunnable`。不补这个短超时，用户不点重试
     * 就会永久停在全屏遮罩上（只能杀进程）。3 秒足够读完提示。
     */
    private const val ALREADY_HANDLED_DISMISS_DELAY = 3_000L

    /**
     * 槽位忙时弹窗候选的排队上限（2026-10-24 审查 P1-1）。
     *
     * 槽位 = 在屏弹窗 / 挂起候选 / 在途上屏三者占其一。MIUI 还权挂起最长实测 57s，
     * 期间到达的支付不能丢：入队待补播。上限 3 笔覆盖 L1 连续支付场景，
     * 超出即丢弃并留痕（QUEUE_FULL），不无界堆积。
     */
    private const val PROMPT_QUEUE_CAPACITY = 3

    /** 观察状态的新鲜度：超龄的"非敏感"判定不可信，宁可弹不出来也不冒险。 */
    private const val OBSERVATION_FRESH_MILLIS = 30_000L

    /** 预读待确认的重试次数与间隔：覆盖落库事务提交前的可见性延迟（见 [readPendingRow]）。 */
    private const val PENDING_ROW_READ_ATTEMPTS = 3
    private const val PENDING_ROW_READ_RETRY_DELAY_MILLIS = 40L

    /** 分类网格每行数量与最大可见行数（超出纵向滚动）。 */
    private const val CATEGORIES_PER_ROW = 3
    private const val MAX_VISIBLE_ROWS = 3

    /** addView 之后的上屏自检延迟（见 [scheduleVisibilityCheck]）。 */
    private const val VISIBILITY_CHECK_DELAY = 500L

    @Volatile
    private var appContext: Context? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var database: JotBillDatabase? = null

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var autoDismissRunnable: Runnable? = null

    /** 上屏自检任务；关闭弹窗时取消，避免悬挂引用（见 [scheduleVisibilityCheck]）。 */
    private var visibilityCheckRunnable: Runnable? = null

    /** 最近一次观察帧是否敏感；初始按敏感对待（无知识时不弹，通知兜底）。 */
    @Volatile
    private var lastObservationSensitive = true

    @Volatile
    private var lastObservationAt = 0L

    @Volatile
    private var lastForegroundPackage: String? = null

    /**
     * 等待非敏感帧的弹窗候选；null 表示无挂起候选。
     *
     * **仅主线程读写**。曾经被 [onPendingChange] 的 IO 协程间接写入，导致
     * 主线程守卫看不见 IO 线程的状态变更 → 双弹窗 + 悬浮窗永久泄漏；
     * 现已改为决策后 `mainHandler.post` 回主线程执行。
     */
    private var waitingCandidate: PromptCandidate? = null

    /** @see waitingCandidate */
    private var candidateExpiryRunnable: Runnable? = null

    /**
     * 已过策略门禁、正在读行 / 准备上屏的候选。**仅主线程读写**。
     *
     * **为什么必须有它（2026-10-12 修）**：[waitingCandidate] 一个字段被同时当作
     * "挂起等待非敏感帧的存储"和"防重入的守卫"用，而它会在 [showCandidate] 之前
     * 就被清空；[showCandidate] 内部又要跨一次协程（读库 + `mainHandler.post`），
     * 期间 `overlayView` 也还是 null（它在 [displayOverlay] 里 `addView` 之后才赋值）。
     *
     * 于是窗口期内**两道守卫同时为空**：
     * ```
     *   主线程 M1: maybeShowCandidate(#1) → 守卫过 → showCandidate(#1) → 进 IO
     *   主线程 M2: maybeShowCandidate(#2) → 守卫**仍然**过（两者都是 null）→ 进 IO
     *   主线程 D1: displayOverlay(#1) → addView → overlayView = view1
     *   主线程 D2: displayOverlay(#2) → addView → overlayView = view2   ← view1 成了孤儿
     * ```
     * [dismissInternal] 只移除 `overlayView` 指向的那一个 ⇒ view1 作为
     * `MATCH_PARENT` + `FLAG_LAYOUT_IN_SCREEN` 的全屏半透明遮罩**永久残留**，
     * 用户点不到任何东西，只能杀进程。
     *
     * 真实触发条件较窄（需要两条 merchant|amount 不同的 pending 在毫秒级内到达），
     * 但故障后果是不可恢复的，属于不能带上线的缺陷。
     * 本字段在 [maybeShowCandidate] / [maybeShowPendingCandidate] 里**占位**，
     * 在 [showCandidate] 回到主线程时释放。
     */
    private var inFlightCandidate: PromptCandidate? = null

    /**
     * 槽位忙时的候选等待队列（2026-10-24 审查 P1-1）。
     *
     * 此前 [onPendingChange] / [maybeShowCandidate] 在槽位忙时直接 return ——
     * 第二笔支付的弹窗事件被静默丢弃：pending 行照常落库、系统通知照常发送，
     * 但弹窗永久不出现，日志里只有一条门禁判定后便无下文。改为入队，
     * 槽位释放时经 [drainQueuedPrompts] FIFO 补播。
     * **仅主线程读写**（与上方三个槽位状态同一约定）。
     */
    private val promptQueue = PromptQueue(
        capacity = PROMPT_QUEUE_CAPACITY,
        ttlMillis = PendingPromptPolicy.CANDIDATE_TTL_MILLIS
    )

    private lateinit var prefs: SharedPreferences

    // ⚠️ 2026-10-12：`recentDedupKeys` / `recentPromptTimes` / `snapshotPolicyState()`
    // 已随去重与频控两条规则的废弃整体删除（用户裁定）。
    //
    // 这三个成员曾是一处 ConcurrentModificationException 崩进程的根因：
    // `onPendingChange` 的决策协程写 `.toMap()` / `.toList()` 并把它当"原子快照"，
    // 而这两个方法是**遍历**不是快照，并发写会让它们抛 CME；异常在无 handler 的
    // SupervisorJob 协程里会冒泡到默认处理器**直接杀掉进程**。当时的绕法是
    // "回主线程取快照"，现在根因本身不存在了 —— [PendingPromptPolicy.evaluate]
    // 已改为纯函数，不再接收任何历史状态。

    /** 当前弹窗的选中状态（仅主线程读写）。 */
    private var selectedCategory: CategoryEntity? = null
    private var selectedAccount: AccountEntity? = null
    private val categoryViews = mutableListOf<Pair<TextView, CategoryEntity>>()
    private val accountViews = mutableListOf<Pair<TextView, AccountEntity>>()
    private var currentConfirmButton: Button? = null

    /** 当前弹窗的金额输入框。金额参与就绪判据（D-21），输入变化时重算按钮状态。 */
    private var currentAmountInput: EditText? = null
    private var displayedPendingId: String? = null

    /**
     * 确认入账进行中（2026-10-24）。
     *
     * 为什么需要：[onConfirmClicked] 会把按钮置灰并写"入账中..."，但
     * [updateConfirmEnabled] 会被金额输入、分类 chip、账户 chip 三处回调触发。
     * 用户在写库期间碰一下金额或点一下分类，按钮就会被重新点亮、文案却仍是
     * "入账中..." —— 再点一次会走第二次 `PendingConfirmService.confirm`，
     * 返回 `AlreadyHandled` 并把成功页覆盖成"已在其他入口处理"，用户以为没记上。
     */
    private var isConfirming = false

    /**
     * 当前在屏弹窗对应的待确认行关键字段，仅 [AUTO_DISMISS] 自动关闭时用来补发通知。
     *
     * 为什么要单独存而不是回查数据库：[dismissInternal] 在关窗后要把所有状态清空，
     * 而补发通知需要的是"关窗前那一行的金额/类型/来源"。回查会多一次 IO，
     * 且在用户已确认入账的行上会查不到（行已删）。
     */
    private var displayedAutoDismissInfo: AutoDismissInfo? = null

    private data class AutoDismissInfo(
        val amountMinor: Long,
        val type: String,
        val sourceType: String
    )

    fun init(context: Context) {
        if (appContext != null) {
            return
        }
        val app = context.applicationContext
        appContext = app
        prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        database = try {
            JotBillDatabaseProvider.get(app)
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "弹窗数据库初始化失败,弹窗停用(通知兜底)", error)
            null
        }
        PendingChangeHub.addListener(::onPendingChangeFromHub)
        JotBillLog.i(TAG, "付款确认弹窗已初始化: enabled=${isEnabled(app)}")
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) {
            mainHandler.post {
                // 停用即清队列：功能关掉后不得再补播任何历史候选。
                val dropped = promptQueue.clear()
                if (dropped > 0) {
                    JotBillLog.i(TAG, "弹窗候选队列已清空(功能停用): dropped=$dropped")
                }
                dismissInternal("DISABLED")
            }
        }
    }

    /**
     * 无障碍服务喂入最新观察帧状态；弹窗的帧级敏感门禁依据。
     * 只在白名单购物/支付应用的观察帧上调用。
     */
    fun onObservationState(sensitive: Boolean, foregroundPackage: String?) {
        lastObservationSensitive = sensitive
        lastObservationAt = System.currentTimeMillis()
        if (!foregroundPackage.isNullOrBlank()) {
            lastForegroundPackage = foregroundPackage
        }
        mainHandler.post { maybeShowPendingCandidate() }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2026-10-12 回退：原 onForegroundChanged() 已删除。
    //
    // 它是为修复"前台是本应用时不弹"这条死守卫而加的（守卫见 [evaluateGate]）：
    // 该守卫读 lastForegroundPackage，而后者此前只由 [onObservationState] 喂入，
    // 喂入方被白名单锁死，本应用包名永远进不去。
    //
    // **真机实测证明这个修法是错的**（2026-10-12，见 docs/qa/device/2026-10-12/）：
    //   10:16:39.331  前台变更: foreground=com.android.systemui, isSelf=false
    //   10:16:40.329  前台变更: foreground=com.jotbill.app,     isSelf=true
    //   10:16:43.340  前台变更: foreground=com.android.systemui, isSelf=false  ← 击穿
    //   10:16:43.913  前台变更: foreground=com.jotbill.app,     isSelf=true
    //   （此后在 10:53:43 / 10:54:03 的两笔支付中重复出现同一模式）
    //
    // 原因：入口只接受"离开白名单购物/支付应用"的事件，而状态栏、输入法、
    // 桌面、通知下拉全都会产生这类事件。resolveEffectiveShoppingPackage 对它们
    // 一律回落到 eventPackage，于是每次点屏幕都会把 lastForegroundPackage 从
    // com.jotbill.app 覆盖成 com.android.systemui 之类的值，守卫随之失效。
    // 真机 logcat 里这类事件在用户操作期间持续高频出现（MIUI 状态栏噪声）。
    //
    // **为什么整条回退而不是加白名单过滤**：
    //   ① SYSTEM_UI_PACKAGES 本身就漏（缺 GMS 输入法 com.google.android.inputmethod.latin、
    //      MIUI 桌面 com.miui.home、视频通话悬浮窗等），靠"列举不是自己"必然继续漏；
    //   ② 真正的正解是读真实前台包名，而两条可行路径都更贵
    //      （UsageStatsManager 需特殊权限；ActivityManager.getRunningAppProcesses
    //        自 5.1 起对第三方无效），rootInActiveWindow 同样会被 systemui 事件污染；
    //   ③ 而这条守卫本身**不是当前症状的成因** —— 实测两笔支付的弹窗门禁分别是
    //      PASS(微信) 与 NO_OVERLAY_PERMISSION(支付宝)，都与"前台是不是本应用"无关。
    //
    // 该守卫连同其数据来源一起保持现状（仍是死代码），留待与"弹窗绑定到支付成功页"
    // 这个更大的问题一并重做。
    // ────────────────────────────────────────────────────────────────────────

    private fun onPendingChangeFromHub(info: PendingChangeInfo) {
        mainHandler.post { onPendingChange(info) }
    }

    /**
     * 读待确认行；读不到时做有界重试，规避"落库事务尚未提交"的可见性竞态。
     *
     * **竞态是真的**：`CheckoutPaymentMatcher.recordOrphanPaymentSignal` /
     * `upsertPendingForSession` 都在 `database.withTransaction { ... }` **内部**同步
     * 调用 `PendingChangeHub.emit`。emit → 主线程 post → 本协程在 Dispatchers.IO 上
     * 另开一条数据库连接读同一行 —— 而此时事务可能尚未提交。SQLite 的读写隔离下，
     * 另一条连接看不到未提交数据 ⇒ `getById` 返回 null。
     *
     * 此前两处预读都是 `?: return@launch`，一次读空就**静默放弃本次弹窗**。
     * 数据没丢（事务会提交、待确认中心可见、系统通知兜底照常），但弹窗不出现 ——
     * 而弹窗是精简版唯一的自动记账入口，漏一次就是验收 L1 的一次 miss。
     * 主线程越忙（支付瞬间正是最忙的时候）post 越快，反而越容易命中这个窗口。
     *
     * 只对"读为空"重试；读抛异常是真实故障，立即上抛由上层按失败处理，不重试掩盖。
     * 上限 3 次 × 40ms：足以覆盖事务提交耗时，又不会让一个真的不存在的 pendingId
     * 把 IO 线程拖住。
     */
    private suspend fun readPendingRow(
        db: JotBillDatabase,
        pendingId: String
    ): PendingTransactionEntity? {
        repeat(PENDING_ROW_READ_ATTEMPTS) { attempt ->
            val row = try {
                db.pendingTransactionDao().getById(pendingId)
            } catch (error: RuntimeException) {
                JotBillLog.w(TAG, "弹窗预读待确认失败", error)
                return null
            }
            if (row != null) {
                if (attempt > 0) {
                    JotBillLog.i(TAG, "弹窗预读在第${attempt + 1}次命中(事务提交可见性延迟): id=${pendingId.take(16)}")
                }
                return row
            }
            if (attempt < PENDING_ROW_READ_ATTEMPTS - 1) {
                delay(PENDING_ROW_READ_RETRY_DELAY_MILLIS)
            }
        }
        JotBillLog.w(
            TAG,
            "弹窗预读待确认为空(事务可能未提交或行已被处理): id=${pendingId.take(16)}, " +
                "attempts=$PENDING_ROW_READ_ATTEMPTS"
        )
        return null
    }

    /** 仅主线程调用。 */
    private fun onPendingChange(info: PendingChangeInfo) {
        if (overlayView != null || waitingCandidate != null || inFlightCandidate != null) {
            // 已有弹窗 / 挂起候选 / 在途上屏：不叠加
            // （去重由 policy 的窗口键兜底跨笔场景）。
            // 2026-10-24 审查 P1-1：不再静默丢弃 —— 那会让 MIUI 还权挂起期间
            // 到达的第二笔支付永久不弹（落库与通知不受影响，但日志无痕）。
            // 入队待槽位释放后补播。
            enqueuePrompt(info)
            return
        }
        processPendingChange(info)
    }

    /** 读行 + 策略评估 + 尝试上屏的完整管线；主线程调用，槽位必须空闲。 */
    private fun processPendingChange(info: PendingChangeInfo) {
        val context = appContext ?: return
        val db = database ?: return
        scope.launch {
            val pendingRow = readPendingRow(db, info.pendingId) ?: return@launch
            // evaluate 是纯函数（2026-10-12 起不依赖任何历史状态），主线程调用无代价。
            // 保留 mainHandler.post 是因为 [maybeShowCandidate] 的下游
            // （waitingCandidate / inFlightCandidate / overlayView）均为主线程状态。
            mainHandler.post {
                val decision = PendingPromptPolicy.evaluate(
                    info = info,
                    merchant = pendingRow.merchant,
                    enabled = isEnabled(context)
                )
                when (decision) {
                    is PendingPromptPolicy.Decision.Skip -> {
                        JotBillLog.i(
                            TAG,
                            "弹窗跳过: ${decision.reason.wireValue}, id=${info.pendingId.take(16)}"
                        )
                    }

                    is PendingPromptPolicy.Decision.Prompt -> {
                        val candidate = PromptCandidate(
                            pendingId = info.pendingId,
                            dedupKey = decision.dedupKey,
                            createdAt = System.currentTimeMillis()
                        )
                        maybeShowCandidate(candidate, info)
                    }
                }
            }
        }
    }

    /** 主线程：尝试立即显示候选；槽位忙时入队，观察状态不可信时挂起等待非敏感帧。 */
    private fun maybeShowCandidate(candidate: PromptCandidate, info: PendingChangeInfo) {
        if (overlayView != null || waitingCandidate != null || inFlightCandidate != null) {
            // 2026-10-24 审查 P1-1：onPendingChange 放行之后、本方法执行之前隔着
            // 读行协程与一次主线程投递，期间槽位可能已被并发事件占住 ——
            // 同样入队，不留静默丢弃。
            enqueuePrompt(info)
            return
        }
        val gate = evaluateGate()
        // 无论放行与否都留痕：放行时这一行是"弹窗本该出现"的基准线，
        // 与随后的"付款确认弹窗已显示"配对即可判定 addView 环节（2026-10-10）。
        JotBillLog.i(
            TAG,
            "弹窗门禁判定: id=${candidate.pendingId.take(16)}, allowed=${gate.allowed}, " +
                "reason=${gate.reason}"
        )
        if (gate.allowed) {
            // 先占位再走异步：见 [inFlightCandidate] 的注释。
            inFlightCandidate = candidate
            showCandidate(candidate)
            return
        }
        JotBillLog.i(
            TAG,
            "弹窗候选挂起等待非敏感帧: id=${candidate.pendingId.take(16)}, " +
                "sensitive=$lastObservationSensitive, foreground=$lastForegroundPackage, " +
                "blockedBy=${gate.reason}"
        )
        waitingCandidate = candidate
        candidateExpiryRunnable?.let(mainHandler::removeCallbacks)
        candidateExpiryRunnable = Runnable {
            if (waitingCandidate === candidate) {
                waitingCandidate = null
                JotBillLog.i(TAG, "弹窗候选超时放弃(敏感页未结束), 通知兜底: id=${candidate.pendingId.take(16)}")
            }
        }
        mainHandler.postDelayed(candidateExpiryRunnable!!, PendingPromptPolicy.CANDIDATE_TTL_MILLIS)
    }

    /** 观察帧更新后的重评；仅主线程调用。 */
    private fun maybeShowPendingCandidate() {
        val candidate = waitingCandidate ?: return
        if (System.currentTimeMillis() - candidate.createdAt > PendingPromptPolicy.CANDIDATE_TTL_MILLIS) {
            waitingCandidate = null
            candidateExpiryRunnable?.let(mainHandler::removeCallbacks)
            candidateExpiryRunnable = null
            // 挂起候选超时放弃，槽位随之空闲：队列里的候选获得补播机会。
            drainQueuedPrompts()
            return
        }
        val gate = evaluateGate()
        if (gate.allowed) {
            waitingCandidate = null
            candidateExpiryRunnable?.let(mainHandler::removeCallbacks)
            candidateExpiryRunnable = null
            // 挂起后放行是"等到了非敏感帧"的正向证据，与首次判定区分开记。
            JotBillLog.i(
                TAG,
                "弹窗候选挂起后放行: id=${candidate.pendingId.take(16)}, waitedMs=" +
                    "${System.currentTimeMillis() - candidate.createdAt}, reason=${gate.reason}"
            )
            inFlightCandidate = candidate
            showCandidate(candidate)
        }
    }

    /**
     * 槽位忙时的入队决策；仅主线程调用。
     * 三种结果都留痕，保证任何一笔未弹出的支付都能从日志归因（审查 P1-1）。
     */
    private fun enqueuePrompt(info: PendingChangeInfo) {
        when (promptQueue.offer(info)) {
            PromptQueue.OfferResult.Enqueued -> JotBillLog.i(
                TAG,
                "弹窗候选入队: reason=SLOT_BUSY, id=${info.pendingId.take(16)}, " +
                    "queueSize=${promptQueue.size}"
            )
            PromptQueue.OfferResult.Duplicate -> JotBillLog.i(
                TAG,
                "弹窗候选入队去重(队列已有该笔): id=${info.pendingId.take(16)}, " +
                    "queueSize=${promptQueue.size}"
            )
            PromptQueue.OfferResult.Full -> JotBillLog.w(
                TAG,
                "弹窗候选丢弃: reason=QUEUE_FULL, id=${info.pendingId.take(16)}, " +
                    "capacity=$PROMPT_QUEUE_CAPACITY, queueSize=${promptQueue.size}"
            )
        }
    }

    /**
     * 槽位释放后的队列补播；仅主线程调用，可安全重复触发。
     *
     * 每次最多补播一笔：出队 → 走完整 [processPendingChange] 管线 —— 重读行、
     * 重过策略与门禁。排队期间该笔可能已被其他入口处理（RN 待确认中心）、
     * 或页面状态已变化，直接重放旧的上屏决策是不安全的；管线内的各项校验
     * （行存在、status=PENDING、策略、门禁）天然覆盖这些情形。
     */
    private fun drainQueuedPrompts() {
        if (overlayView != null || waitingCandidate != null || inFlightCandidate != null) {
            return
        }
        if (appContext == null || database == null) {
            // 管线起点的两个前置不满足时不得出队，否则条目会被无痕吞掉。
            return
        }
        while (true) {
            when (val result = promptQueue.poll()) {
                PromptQueue.PollResult.Empty -> return
                is PromptQueue.PollResult.Expired -> JotBillLog.i(
                    TAG,
                    "弹窗候选出队后超时丢弃: id=${result.pendingId.take(16)}, ageMs=${result.ageMs}"
                )
                is PromptQueue.PollResult.Ready -> {
                    JotBillLog.i(
                        TAG,
                        "弹窗候选出队补播: id=${result.info.pendingId.take(16)}, " +
                            "waitedMs=${result.waitedMs}"
                    )
                    processPendingChange(result.info)
                    return
                }
            }
        }
    }

    /**
     * 帧级门禁的判定结果。
     *
     * @param allowed 是否放行。
     * @param reason 放行原因，或**被挡住的门禁代号 + 现场值**（诊断日志锚点）。
     */
    private data class GateVerdict(val allowed: Boolean, val reason: String)

    /**
     * 帧级门禁：最近观察帧非敏感且新鲜，且前台不是本应用，且有悬浮窗权限。
     *
     * **返回判定理由而不是裸 Boolean**（2026-10-10）：真机日志里出现过一次
     * `弹窗候选挂起等待非敏感帧: sensitive=false, foreground=com.eg.android.AlipayGphone`
     * —— 前两道门禁的现场值都显示"应该放行"，但弹窗就是没出。旧实现只打印
     * `sensitive` 与 `foreground`，把**观察帧新鲜度**和**悬浮窗权限**两道吞掉了，
     * 导致无法区分"门禁挡住"与"其它原因"，只能靠推测。
     * 本方法让每个被挡住的情形都带一个可 grep 的代号。
     */
    private fun evaluateGate(): GateVerdict {
        val context = appContext
            ?: return GateVerdict(false, "APP_CONTEXT_NULL")
        val observationAge = System.currentTimeMillis() - lastObservationAt
        if (observationAge > OBSERVATION_FRESH_MILLIS) {
            return GateVerdict(
                false,
                "OBSERVATION_STALE(age=${observationAge}ms > ${OBSERVATION_FRESH_MILLIS}ms)"
            )
        }
        if (lastObservationSensitive) {
            return GateVerdict(
                false,
                "SENSITIVE_FRAME(age=${observationAge}ms, foreground=$lastForegroundPackage)"
            )
        }
        // 前台是本应用时不弹：应用内待确认中心已覆盖该场景
        if (lastForegroundPackage == context.packageName) {
            return GateVerdict(
                false,
                "FOREGROUND_IS_SELF(foreground=$lastForegroundPackage, age=${observationAge}ms)"
            )
        }
        if (!hasOverlayPermission(context)) {
            return GateVerdict(
                false,
                "NO_OVERLAY_PERMISSION(foreground=$lastForegroundPackage, " +
                    "age=${observationAge}ms, device=${Build.MANUFACTURER} ${Build.MODEL} " +
                    "sdk=${Build.VERSION.SDK_INT})"
            )
        }
        return GateVerdict(
            true,
            "PASS(age=${observationAge}ms, foreground=$lastForegroundPackage)"
        )
    }

    private fun hasOverlayPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(context)

    /**
     * 读取待确认行并上屏；**每个提前返回都留日志**。
     *
     * 此前三处 `return` / `return@launch` 全是静默的：门禁已放行却什么都没弹，
     * 日志里只剩一行"付款确认弹窗已显示"从未出现，无从区分"读不到行""行已被处理"
     * 与"数据库不可用"。三者是完全不同的故障，必须能分开 grep。
     */
    private fun showCandidate(candidate: PromptCandidate) {
        val context = appContext
        if (context == null) {
            releaseInFlight(candidate)
            JotBillLog.w(
                TAG,
                "弹窗放弃: 应用上下文未初始化, id=${candidate.pendingId.take(16)}"
            )
            return
        }
        val db = database
        if (db == null) {
            releaseInFlight(candidate)
            JotBillLog.w(
                TAG,
                "弹窗放弃: 数据库不可用, id=${candidate.pendingId.take(16)}"
            )
            return
        }
        scope.launch {
            val pendingRow = readPendingRow(db, candidate.pendingId)
            // 行内容复核与占位释放在**同一个主线程任务**内完成，中间不留窗口：
            // 先做 async 读、再 post 释放，会让 inFlightCandidate 在读行期间失效，
            // 防重入守卫重新出现空洞。
            mainHandler.post {
                if (inFlightCandidate !== candidate) {
                    // 已被 dismissInternal / setEnabled(false) 作废。读行期间用户
                    // 关掉了弹窗或停用了功能，此时不得再上屏。
                    return@post
                }
                inFlightCandidate = null
                if (pendingRow == null) {
                    JotBillLog.w(
                        TAG,
                        "弹窗放弃: 预读待确认为空(事务未提交或行已被处理), " +
                            "id=${candidate.pendingId.take(16)}"
                    )
                    drainQueuedPrompts()
                    return@post
                }
                if (pendingRow.status != PendingTransactionEntity.STATUS_PENDING) {
                    JotBillLog.i(
                        TAG,
                        "弹窗放弃: 待确认已被其他入口处理, id=${candidate.pendingId.take(16)}, " +
                            "status=${pendingRow.status}"
                    )
                    drainQueuedPrompts()
                    return@post
                }
                displayOverlay(pendingRow)
            }
        }
    }

    /** 释放在途占位；仅主线程调用。释放后槽位空闲，尝试补播队列。 */
    private fun releaseInFlight(candidate: PromptCandidate) {
        if (inFlightCandidate === candidate) {
            inFlightCandidate = null
            drainQueuedPrompts()
        }
    }

    private fun displayOverlay(pendingRow: PendingTransactionEntity) {
        val context = appContext ?: return
        if (!hasOverlayPermission(context)) {
            JotBillLog.i(TAG, "无悬浮窗权限,弹窗放弃(通知兜底)")
            return
        }
        // 提交点复核：[displayOverlay] 是唯一给 overlayView 赋值的地方，也是 addView
        // 的实际发起处。走到这里之前经过了读库协程与一次主线程投递，
        // 期间可能已有另一张卡上屏（见 [inFlightCandidate] 的注释）。
        // 这里再挡一次：宁可这一笔少弹一次，也不能产生一个关不掉的全屏遮罩。
        if (overlayView != null) {
            JotBillLog.w(
                TAG,
                "弹窗放弃: 已有弹窗在屏,拒绝重复上屏: id=${pendingRow.id.take(16)}, " +
                    "待显示=${pendingRow.id.take(8)}, 在屏=${displayedPendingId?.take(8)}"
            )
            return
        }
        try {
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.pending_confirm_overlay, null)
            val sourceText = view.findViewById<TextView>(R.id.tv_source)
            // D-21：0 金额占位（AI 失败/金额缺失）允许弹窗，但用户必须看懂
            // "为什么是 0、下一步做什么" ⇒ 金额缺失文案优先于来源文案
            // （否则会显示"截图识别到一笔支付"，与空金额自相矛盾）。
            sourceText.text = if (pendingRow.amountMinor <= 0L) {
                "未识别到金额，请补充金额后确认"
            } else {
                when (pendingRow.sourceType) {
                    "notification" -> "检测到支付通知，待确认入账"
                    "accessibility" -> "检测到支付结果页，待确认入账"
                    "ai_vision" -> "截图识别到一笔支付，待确认"
                    else -> "检测到一笔待确认支付"
                }
            }
            val merchantText = view.findViewById<TextView>(R.id.tv_merchant)
            // 精简版：结算页截图 AI 已停用，商户只剩"支付结果页文本解析"一个来源，
            // 解析不出就是解析不出。此前固定显示"商户：未知"，读起来像"读到了但没看懂"，
            // 实际是"压根没有" —— 而这一行用户又不能改（弹窗只让改金额/分类/账户），
            // 是一处死路。解析不出时整行隐藏，把纵向空间留给可编辑字段。
            val merchant = pendingRow.merchant?.trim()?.takeIf { it.isNotEmpty() }
            if (merchant == null) {
                merchantText.visibility = View.GONE
            } else {
                merchantText.visibility = View.VISIBLE
                merchantText.text = "商户：${merchant.take(24)}"
            }
            // 商品描述不再由识别链路填充（阶段二），但 FeeExtractor / 导入等生产者
            // 仍可能写入；此时预填进备注框，避免同一份信息在弹窗里凭空消失。
            // 留空则由 hint 引导用户自己补一句。
            view.findViewById<EditText>(R.id.et_remark).apply {
                setText(pendingRow.productDescription?.trim().orEmpty())
                setSelection(text?.length ?: 0)
            }
            val amountInput = view.findViewById<EditText>(R.id.et_amount)
            amountInput.setText(formatYuan(pendingRow.amountMinor))
            currentAmountInput = amountInput
            // 金额参与就绪判据（D-21）⇒ 必须监听输入变化重算按钮，否则 0 金额
            // 条目打开时按钮置灰后再无时机变亮（功能死锁）。顺序：先 setText 再挂
            // 监听，避免预填触发一次无意义的重算。
            amountInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(s: Editable?) {
                    updateConfirmEnabled(currentConfirmButton)
                }
            })
            displayedPendingId = pendingRow.id
            displayedAutoDismissInfo = AutoDismissInfo(
                amountMinor = pendingRow.amountMinor,
                type = pendingRow.type,
                sourceType = pendingRow.sourceType
            )
            selectedCategory = null
            // 账户不造占位实体：待确认携带的账户 ID 由 loadAccounts 在列表中匹配预选，
            // 匹配不到就要求用户手选，避免把不存在的账户写进账单。
            selectedAccount = null
            categoryViews.clear()
            accountViews.clear()

            val confirmButton = view.findViewById<Button>(R.id.btn_confirm)
            currentConfirmButton = confirmButton
            updateConfirmEnabled(confirmButton)

            view.findViewById<ImageButton>(R.id.btn_close_x).setOnClickListener {
                dismissInternal("CLOSE_X")
            }
            view.findViewById<Button>(R.id.btn_close).setOnClickListener {
                dismissInternal("LATER")
            }
            confirmButton.setOnClickListener {
                onConfirmClicked(view, pendingRow, confirmButton)
            }

            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.addView(view, createLayoutParams())
            this.windowManager = windowManager
            overlayView = view

            scheduleAutoDismiss()
            scheduleVisibilityCheck(view)
            loadCategories(view, pendingRow)
            loadAccounts(view, pendingRow.accountId)
            JotBillLog.i(
                TAG,
                "付款确认弹窗已显示: id=${pendingRow.id.take(16)}, amount=${pendingRow.amountMinor}, " +
                    "source=${pendingRow.sourceType}"
            )
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "弹窗显示失败(通知兜底)", error)
            // 上屏自检可能已排上（scheduleVisibilityCheck 之后的 findViewById 仍可能抛），
            // 一并撤销，保持"没有弹窗 ⇒ 没有待执行的上屏自检"这条不变式。
            visibilityCheckRunnable?.let(mainHandler::removeCallbacks)
            visibilityCheckRunnable = null
            overlayView?.let { view ->
                try {
                    windowManager?.removeView(view)
                } catch (_: RuntimeException) {
                }
            }
            overlayView = null
        }
    }

    /**
     * 上屏自检：`addView` 不抛异常 **≠** 窗口真的贴在屏幕上。
     *
     * 部分 ROM 会在 `addView` 返回后静默移除系统悬浮窗（典型触发：悬浮窗权限被
     * 系统策略回收、后台启动限制、厂商省电策略）。此时日志里 `付款确认弹窗已显示`
     * 一行一切正常，用户侧却表现为"什么都没弹" —— 两者矛盾且**没有任何日志可判**。
     * 本项目 2026-10-10 的"弹窗不出现"问题正是卡在这个盲区上：只能靠肉眼比对截图，
     * 无法从日志区分"门禁挡住"与"弹了但没上屏"。
     *
     * 上游 LiteNote 的 `PaymentOverlayManager.showPaymentOverlay()` 里写了这个自检
     * （`postDelayed(500)` 查 `isAttachedToWindow`，成功打 INFO、失败打 WARN），
     * 本方法即移植自那里。
     *
     * 与上游的差别：多一道**身份校验**。500ms 内弹窗可能已被关闭（CLOSE_X /
     * 成功反馈 / `setEnabled(false)`），上游写法只看 `overlayView != null`，
     * 会把"我们自己关掉的"误报成"被系统移除"。这里比对 `overlayView === view`，
     * 不是当前弹窗则静默跳过。
     *
     * 仅主线程调用（由 [displayOverlay] 在主线程调用）。
     */
    private fun scheduleVisibilityCheck(view: View) {
        visibilityCheckRunnable?.let(mainHandler::removeCallbacks)
        val runnable = Runnable {
            visibilityCheckRunnable = null
            if (overlayView !== view) {
                // 500ms 内已被关闭（dismissInternal 会取消本任务，此处是兜底）。
                return@Runnable
            }
            if (view.isAttachedToWindow) {
                JotBillLog.i(
                    TAG,
                    "付款确认弹窗已上屏确认: id=${displayedPendingId?.take(16)}, attached=true"
                )
                return@Runnable
            }
            // 失败时把能拿到的现场一并打出来：区分"被摘除"与"布局成 0 尺寸/不可见"。
            JotBillLog.w(
                TAG,
                "付款确认弹窗 addView 成功但未上屏(疑被系统/ROM 移除): " +
                    "id=${displayedPendingId?.take(16)}, attached=false, shown=${view.isShown}, " +
                    "visibility=${view.visibility}, size=${view.width}x${view.height}, " +
                    "windowToken=${view.windowToken != null}"
            )
            // 必须清理：否则 overlayView 仍非空，30 秒内所有新候选都会被
            // maybeShowCandidate 的 `overlayView != null` 挡掉，表现为连续漏弹。
            // 走 dismissInternal 保证窗口/计时器/状态一起收口；reason 不是
            // AUTO_DISMISS，不会补发"这笔还没记上"的通知。
            dismissInternal("NOT_ATTACHED")
        }
        visibilityCheckRunnable = runnable
        mainHandler.postDelayed(runnable, VISIBILITY_CHECK_DELAY)
    }

    private fun onConfirmClicked(view: View, pendingRow: PendingTransactionEntity, confirmButton: Button) {
        // 防重入：写库期间用户改金额/点分类会经 updateConfirmEnabled 重新点亮按钮，
        // 没有这道闸就会二次进入 confirm（见 isConfirming 的 KDoc）。
        if (isConfirming) {
            return
        }
        val category = selectedCategory
        val account = selectedAccount
        if (category == null || account == null) {
            return
        }
        val amountMinor = parseYuan(view.findViewById<EditText>(R.id.et_amount).text?.toString())
        if (amountMinor == null) {
            showErrorFeedback(view, "金额格式不正确，请输入不超过两位小数的金额")
            return
        }
        val remark = view.findViewById<EditText>(R.id.et_remark).text?.toString()?.trim()?.takeIf { it.isNotBlank() }
        isConfirming = true
        // 确认开始即取消自动关闭：否则 30 秒后 AUTO_DISMISS 会给一笔已经入账、
        // pending 行已删的条目补发"这笔还没记上"的假通知，且之后没有 removed
        // 事件来 cancel，通知会永久挂在通知栏（见 notifyAutoDismissIfNeeded）。
        autoDismissRunnable?.let(mainHandler::removeCallbacks)
        autoDismissRunnable = null
        confirmButton.isEnabled = false
        confirmButton.text = "入账中..."
        scope.launch {
            val outcome = try {
                PendingConfirmService(requireDatabase()).confirm(
                    pendingId = pendingRow.id,
                    overrides = PendingConfirmService.Overrides(
                        amountMinor = amountMinor,
                        merchant = pendingRow.merchant,
                        categoryId = category.id,
                        accountId = account.id,
                        description = remark
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: RuntimeException) {
                JotBillLog.w(TAG, "弹窗确认异常", error)
                null
            }
            mainHandler.post {
                // 卡片身份（2026-10-24 复审）：写库期间用户可能已点 X/稍后关掉这张卡，
                // 并且下一张已经上屏。此时：
                // - UI 操作必须丢弃（否则成功页 1.5s dismiss 会关掉新卡；
                //   AlreadyHandled 残留 isConfirming=true 会让新卡按钮永久灰；
                //   Invalid/null 会抹掉新卡正在进行的确认状态）；
                // - 但**写库已成功的全局副作用不能丢**（缓存失效 / 最近分类），
                //   否则 RN 侧会一直显示旧账单，直到用户下次回前台。
                val isCurrentCard = overlayView === view
                when (outcome) {
                    is PendingConfirmService.Outcome.Confirmed -> {
                        // 全局副作用：无条件执行。
                        prefs.edit().putString(KEY_LAST_CATEGORY_ID, category.id).apply()
                        emitJsEvents(outcome.bill, pendingRow.id)
                        if (isCurrentCard) {
                            showSuccessFeedback(view, outcome.bill.amountMinor, category.name)
                        } else {
                            JotBillLog.i(
                                TAG,
                                "确认成功但卡片已更换：仅刷新缓存，不展示成功页 " +
                                    "id=${pendingRow.id.take(16)}"
                            )
                        }
                    }

                    is PendingConfirmService.Outcome.AlreadyHandled -> {
                        if (!isCurrentCard) {
                            JotBillLog.i(
                                TAG,
                                "确认结果已过期(卡片已更换)，丢弃: id=${pendingRow.id.take(16)}"
                            )
                            return@post
                        }
                        // 该笔已被其他入口处理：保持 isConfirming=true，不允许再点。
                        showErrorFeedback(view, "该待确认已在其他入口处理", dismissAfter = true)
                        // 错误页隐藏了 X/稍后，而 autoDismiss 已在确认入口取消 ⇒
                        // 必须补一个短超时，否则不点"重试"就永久卡住。
                        // expect=view：3 秒内若已换卡，不得关掉新卡。
                        mainHandler.postDelayed(
                            { dismissInternal("ALREADY_HANDLED", expect = view) },
                            ALREADY_HANDLED_DISMISS_DELAY
                        )
                    }

                    is PendingConfirmService.Outcome.Invalid -> {
                        if (!isCurrentCard) {
                            JotBillLog.i(
                                TAG,
                                "确认校验结果已过期(卡片已更换)，丢弃: id=${pendingRow.id.take(16)}"
                            )
                            return@post
                        }
                        // 校验失败（金额/类型/来源非法）：这笔还没入账，允许修正后重试。
                        isConfirming = false
                        showErrorFeedback(view, outcome.reason)
                        // 重新排队自动关闭：确认入口已取消原计时器，不重排就失去
                        // "30 秒兜底"（错误页隐藏了 X/稍后，用户只能点重试）。
                        scheduleAutoDismiss()
                        confirmButton.text = "确认入账"
                        updateConfirmEnabled(confirmButton)
                    }

                    null -> {
                        if (!isCurrentCard) {
                            JotBillLog.i(
                                TAG,
                                "确认异常结果已过期(卡片已更换)，丢弃: id=${pendingRow.id.take(16)}"
                            )
                            return@post
                        }
                        // 写库异常：同上，允许重试。
                        isConfirming = false
                        showErrorFeedback(view, "入账失败，请稍后在待确认中心重试")
                        scheduleAutoDismiss()
                        confirmButton.text = "确认入账"
                        updateConfirmEnabled(confirmButton)
                    }
                }
            }
        }
    }

    /** 弹窗确认与 App 内确认等价：广播同一组 JS 事件刷新账单/待确认缓存。 */
    private fun emitJsEvents(bill: com.jotbill.app.data.entity.BillEntity, pendingId: String) {
        val emitter = sharedJsEventEmitter ?: return
        try {
            emitter.emit(
                "onPendingChanged",
                JSONObject().put("pendingId", pendingId).put("deleted", true)
            )
            emitter.emit("onBillCreated", BridgeDtoMapper.bill(bill))
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "弹窗确认后 JS 事件推送失败(RN 可能未就绪)", error)
        }
    }

    private fun loadCategories(view: View, pendingRow: PendingTransactionEntity) {
        val db = database ?: return
        val app = appContext ?: return
        val loadingContainer = view.findViewById<LinearLayout>(R.id.loading_container)
        val categoriesContainer = view.findViewById<LinearLayout>(R.id.container_categories)
        val scrollView = view.findViewById<View>(R.id.scroll_categories)
        val confirmButton = view.findViewById<Button>(R.id.btn_confirm)
        scope.launch {
            val categories = try {
                db.categoryDao().getByType("expense")
            } catch (error: RuntimeException) {
                JotBillLog.w(TAG, "弹窗加载分类失败", error)
                emptyList()
            }
            val defaultCategoryId = resolveDefaultCategoryId(db, pendingRow)
            mainHandler.post {
                // 身份校验：查询返回前弹窗可能已被关闭、并已上屏下一张。
                // 旧回调若继续执行，会往**全局** categoryViews 追加已 detach 的视图、
                // 并 selectCategory 改写新卡的选中态与按钮状态。
                if (overlayView !== view) {
                    return@post
                }
                categoriesContainer.removeAllViews()
                loadingContainer.visibility = View.GONE
                if (categories.isEmpty()) {
                    val errorText = TextView(app).apply {
                        text = "暂无支出分类，请先在应用内创建"
                        setTextColor(0xFF737373.toInt())
                        textSize = 13f
                    }
                    categoriesContainer.addView(errorText)
                    scrollView.visibility = View.VISIBLE
                    return@post
                }
                val density = app.resources.displayMetrics.density
                val rowHeight = (40 * density).toInt()
                val rowGap = (8 * density).toInt()
                val chunked = categories.chunked(CATEGORIES_PER_ROW)
                chunked.forEachIndexed { rowIndex, rowCategories ->
                    val rowLayout = LinearLayout(app).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            if (rowIndex < chunked.size - 1) {
                                bottomMargin = rowGap
                            }
                        }
                    }
                    rowCategories.forEach { category ->
                        val categoryView = createCategoryView(app, category, confirmButton)
                        rowLayout.addView(categoryView)
                        categoryViews.add(categoryView to category)
                        if (category.id == defaultCategoryId) {
                            selectCategory(categoryView, category)
                        }
                    }
                    val remaining = CATEGORIES_PER_ROW - rowCategories.size
                    repeat(remaining) {
                        val spacer = View(app)
                        spacer.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                        rowLayout.addView(spacer)
                    }
                    categoriesContainer.addView(rowLayout)
                }
                if (chunked.size > MAX_VISIBLE_ROWS) {
                    scrollView.layoutParams = scrollView.layoutParams.apply {
                        height = MAX_VISIBLE_ROWS * rowHeight + (MAX_VISIBLE_ROWS - 1) * rowGap
                    }
                }
                scrollView.visibility = View.VISIBLE
            }
        }
    }

    /** 分类预选优先级：待确认既有 > 上次同商户 > 上次弹窗选择 > 最近一次支出分类。 */
    private suspend fun resolveDefaultCategoryId(
        db: JotBillDatabase,
        pendingRow: PendingTransactionEntity
    ): String? {
        pendingRow.categoryId?.let { return it }
        pendingRow.merchant?.takeIf { it.isNotBlank() }?.let { merchant ->
            try {
                db.billDao().findLastExpenseCategoryIdByMerchant(merchant)?.let { return it }
            } catch (_: RuntimeException) {
            }
        }
        prefs.getString(KEY_LAST_CATEGORY_ID, null)?.let { return it }
        return try {
            db.billDao().findLastExpenseCategoryId()
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun loadAccounts(view: View, preferredAccountId: String?) {
        val db = database ?: return
        val app = appContext ?: return
        val container = view.findViewById<LinearLayout>(R.id.container_accounts)
        scope.launch {
            val accounts = try {
                db.accountDao().getAll().filter { it.isActive }
            } catch (error: RuntimeException) {
                JotBillLog.w(TAG, "弹窗加载账户失败", error)
                emptyList()
            }
            mainHandler.post {
                // 身份校验：理由同 loadCategories —— 旧卡的回调不得改写新卡的账户选中态。
                if (overlayView !== view) {
                    return@post
                }
                container.removeAllViews()
                accountViews.clear()
                val density = app.resources.displayMetrics.density
                accounts.forEach { account ->
                    val chip = TextView(app).apply {
                        text = "${account.icon ?: "💳"} ${account.name}"
                        textSize = 14f
                        setTextColor(0xFF1A1A1A.toInt())
                        setBackgroundResource(R.drawable.btn_category_unselected)
                        setTypeface(typeface, Typeface.BOLD)
                        val horizontalPadding = (12 * density).toInt()
                        val verticalPadding = (8 * density).toInt()
                        setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            (40 * density).toInt()
                        ).apply {
                            marginEnd = (8 * density).toInt()
                        }
                        gravity = Gravity.CENTER
                        setOnClickListener {
                            selectAccount(this, account)
                        }
                    }
                    container.addView(chip)
                    accountViews.add(chip to account)
                    // 待确认携带的账户（证据路径的支付平台默认账户）自动预选
                    if (account.id == preferredAccountId) {
                        selectAccount(chip, account)
                    }
                }
            }
        }
    }

    private fun createCategoryView(
        context: Context,
        category: CategoryEntity,
        confirmButton: Button
    ): TextView {
        return TextView(context).apply {
            text = "${category.icon ?: "📦"} ${category.name}"
            textSize = 14f
            setTextColor(0xFF1A1A1A.toInt())
            setBackgroundResource(R.drawable.btn_category_unselected)
            setTypeface(typeface, Typeface.BOLD)
            val density = context.resources.displayMetrics.density
            val horizontalPadding = (12 * density).toInt()
            val verticalPadding = (8 * density).toInt()
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            layoutParams = LinearLayout.LayoutParams(
                0,
                (40 * density).toInt(),
                1f
            ).apply {
                marginStart = (4 * density).toInt()
                marginEnd = (4 * density).toInt()
            }
            gravity = Gravity.CENTER
            setOnClickListener {
                selectCategory(this, category)
            }
        }
    }

    private fun selectCategory(selectedView: TextView, category: CategoryEntity) {
        selectedCategory = category
        categoryViews.forEach { (view, cat) ->
            if (cat.id == category.id) {
                view.setBackgroundResource(R.drawable.btn_category_selected)
                view.setTextColor(0xFF1D4ED8.toInt())
            } else {
                view.setBackgroundResource(R.drawable.btn_category_unselected)
                view.setTextColor(0xFF1A1A1A.toInt())
            }
        }
        updateConfirmEnabled(currentConfirmButton)
    }

    private fun selectAccount(selectedView: TextView, account: AccountEntity) {
        selectedAccount = account
        accountViews.forEach { (view, acc) ->
            if (acc.id == account.id) {
                view.setBackgroundResource(R.drawable.btn_category_selected)
                view.setTextColor(0xFF1D4ED8.toInt())
            } else {
                view.setBackgroundResource(R.drawable.btn_category_unselected)
                view.setTextColor(0xFF1A1A1A.toInt())
            }
        }
        updateConfirmEnabled(currentConfirmButton)
    }

    /**
     * 分类、账户与金额都就绪才允许入账：弹窗是显式确认入口，不允许无分类/
     * 无账户/无金额写入。D-21：0 金额占位允许弹窗，但确认前强制补金额 ⇒
     * 判据由"分类 + 账户"扩展为"分类 + 账户 + 金额 > 0"。
     *
     * 金额口径走 [PendingConfirmReadiness]（与服务端 [PendingConfirmService]
     * 同一出处）；本方法由金额输入监听回调（见 displayOverlay），分类/账户
     * 选择回调三处触发 —— 缺任一处都会让按钮状态卡死。
     */
    private fun updateConfirmEnabled(confirmButton: Button?) {
        if (isConfirming) {
            // 写库期间金额输入 / 分类 / 账户三处回调都可能走到这里，
            // 必须挡住，否则按钮被重新点亮、文案仍是"入账中..."（见 isConfirming）。
            confirmButton?.isEnabled = false
            return
        }
        val ready = PendingConfirmReadiness.isReady(
            hasCategory = selectedCategory != null,
            hasAccount = selectedAccount != null,
            amountMinor = parseYuan(currentAmountInput?.text?.toString())
        )
        confirmButton?.isEnabled = ready
        confirmButton?.alpha = if (ready) 1.0f else 0.5f
    }

    private fun showSuccessFeedback(view: View, amountMinor: Long, categoryName: String) {
        view.findViewById<LinearLayout>(R.id.content_main)?.visibility = View.GONE
        val successContent = view.findViewById<LinearLayout>(R.id.content_success)
        successContent?.visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.tv_success_detail)?.text =
            "¥${formatYuan(amountMinor)} - $categoryName"
        // 必须带 expect：成功页停留 1.5s 期间用户可能已被新卡顶替，
        // 无身份校验的 dismiss 会关掉新卡（2026-10-24 复审 P0）。
        mainHandler.postDelayed({ dismissInternal("SUCCESS", expect = view) }, SUCCESS_DISPLAY_DELAY)
    }

    private fun showErrorFeedback(view: View, message: String, dismissAfter: Boolean = false) {
        view.findViewById<LinearLayout>(R.id.content_main)?.visibility = View.GONE
        val errorContent = view.findViewById<LinearLayout>(R.id.content_error)
        errorContent?.visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.tv_error_message)?.text = message
        val retryButton = view.findViewById<Button>(R.id.btn_retry)
        retryButton?.setOnClickListener {
            errorContent?.visibility = View.GONE
            view.findViewById<LinearLayout>(R.id.content_main)?.visibility = View.VISIBLE
            if (dismissAfter) {
                dismissInternal("ALREADY_HANDLED")
            }
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
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    }

    private fun scheduleAutoDismiss() {
        autoDismissRunnable?.let(mainHandler::removeCallbacks)
        autoDismissRunnable = Runnable { dismissInternal("AUTO_DISMISS") }
        mainHandler.postDelayed(autoDismissRunnable!!, AUTO_DISMISS_DELAY)
    }

    /**
     * 关闭弹窗；主线程调用。
     *
     * @param expect 期望关闭的卡片。延迟回调（成功反馈、已处理超时）必须传入发起时的
     *   `view` —— 否则回调执行时若已换卡，`dismissInternal` 会关掉**新卡**。
     *   用户主动关闭（X / 稍后）不传，作用于当前卡。
     */
    private fun dismissInternal(reason: String, expect: View? = null) {
        if (expect != null && overlayView !== expect) {
            JotBillLog.i(TAG, "弹窗关闭已跳过(目标卡片已更换): reason=$reason")
            return
        }
        try {
            autoDismissRunnable?.let(mainHandler::removeCallbacks)
            autoDismissRunnable = null
            visibilityCheckRunnable?.let(mainHandler::removeCallbacks)
            visibilityCheckRunnable = null
            overlayView?.let { view ->
                windowManager?.removeView(view)
            }
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "弹窗关闭异常", error)
        } finally {
            // ⚠️ 必须在下面清空 displayedPendingId / displayedAutoDismissInfo **之前**调用：
            // 它读的就是这两个字段。放在清空之后会永远拿到 null ⇒ 通知永不发出。
            notifyAutoDismissIfNeeded(reason)
            overlayView = null
            selectedCategory = null
            selectedAccount = null
            categoryViews.clear()
            accountViews.clear()
            currentConfirmButton = null
            currentAmountInput = null
            displayedPendingId = null
            displayedAutoDismissInfo = null
            isConfirming = false
            waitingCandidate = null
            candidateExpiryRunnable?.let(mainHandler::removeCallbacks)
            candidateExpiryRunnable = null
            // 在途候选一并作废：否则读行协程回来后仍会通过 [showCandidate] 的
            // `inFlightCandidate !== candidate` 检查并补弹一张 —— 用户刚点的"关闭"
            // 会表现为"关了又冒出来"（见 [inFlightCandidate]）。
            inFlightCandidate = null
            JotBillLog.i(TAG, "付款确认弹窗已关闭: reason=$reason")
            // 槽位已释放：补播排队中的候选（2026-10-24 审查 P1-1）。
            drainQueuedPrompts()
        }
    }

    /**
     * 仅 [AUTO_DISMISS] 路径补发"这笔还没记上"的系统通知（2026-10-12）。
     *
     * **只覆盖自动关闭**：用户主动点关闭（[CLOSE_X] / [LATER]）是"我知道有这回事、
     * 我选择先放着"，补发通知是噪音；确认成功时那笔已经入账、pending 行已删，
     * 更不能再发。
     */
    private fun notifyAutoDismissIfNeeded(reason: String) {
        if (reason != "AUTO_DISMISS") return
        val context = appContext ?: return
        val pendingId = displayedPendingId ?: return
        val info = displayedAutoDismissInfo ?: return
        PendingReminderNotifier.notifyAutoDismissed(
            context = context,
            pendingId = pendingId,
            amountMinor = info.amountMinor,
            type = info.type,
            sourceType = info.sourceType
        )
    }

    private fun requireDatabase(): JotBillDatabase =
        database ?: throw IllegalStateException("PendingConfirmOverlay database unavailable")

    /** 分 → "35.80"（输入框显示）。 */
    private fun formatYuan(amountMinor: Long): String {
        val negative = amountMinor < 0
        val abs = kotlin.math.abs(amountMinor)
        return (if (negative) "-" else "") + (abs / 100) + "." + (abs % 100).toString().padStart(2, '0')
    }

    /** "35.80" → 分；超过两位小数或非法输入返回 null。 */
    private fun parseYuan(text: String?): Long? {
        val trimmed = text?.trim().orEmpty()
        if (!Regex("""^\d{1,9}(\.\d{1,2})?$""").matches(trimmed)) {
            return null
        }
        val whole = trimmed.substringBefore('.').toLong()
        val fraction = trimmed.substringAfter('.', "").padEnd(2, '0').ifEmpty { "00" }
        val minor = whole * 100 + fraction.toLong()
        return minor.takeIf { it in 1L..9_007_199_254_740_991L }
    }

    /** 弹窗候选：已通过策略、等待非敏感帧的待确认。 */
    private data class PromptCandidate(
        val pendingId: String,
        val dedupKey: String,
        val createdAt: Long
    )
}

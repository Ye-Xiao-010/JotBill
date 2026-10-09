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
 */
internal object PendingConfirmOverlayManager {
    private const val TAG = "PendingConfirmOverlay"
    private const val PREFS_NAME = "pending_confirm_overlay"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_CATEGORY_ID = "last_category_id"

    /** 悬浮窗自动消失时间。 */
    private const val AUTO_DISMISS_DELAY = 60_000L
    private const val SUCCESS_DISPLAY_DELAY = 1_500L

    /** 观察状态的新鲜度：超龄的"非敏感"判定不可信，宁可弹不出来也不冒险。 */
    private const val OBSERVATION_FRESH_MILLIS = 30_000L

    /** 预读待确认的重试次数与间隔：覆盖落库事务提交前的可见性延迟（见 [readPendingRow]）。 */
    private const val PENDING_ROW_READ_ATTEMPTS = 3
    private const val PENDING_ROW_READ_RETRY_DELAY_MILLIS = 40L

    /** 分类网格每行数量与最大可见行数（超出纵向滚动）。 */
    private const val CATEGORIES_PER_ROW = 3
    private const val MAX_VISIBLE_ROWS = 3

    @Volatile
    private var appContext: Context? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var database: JotBillDatabase? = null

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var autoDismissRunnable: Runnable? = null

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

    private lateinit var prefs: SharedPreferences

    /**
 * 策略运行状态：去重键 → 最近弹窗时间；滑动窗口内的弹窗时间戳。
     *
     * **仅主线程读写** —— HashMap 非线程安全，并发写可损坏内部结构导致去重永久失效
     * （弹窗无限轰炸）。[onPendingChange] 的决策 IO 协程只读副本（`.toMap()`），
     * 写入一律 `mainHandler.post` 回主线程。
     */
    private val recentDedupKeys = HashMap<String, Long>()
    private val recentPromptTimes = mutableListOf<Long>()

    /** 当前弹窗的选中状态（仅主线程读写）。 */
    private var selectedCategory: CategoryEntity? = null
    private var selectedAccount: AccountEntity? = null
    private val categoryViews = mutableListOf<Pair<TextView, CategoryEntity>>()
    private val accountViews = mutableListOf<Pair<TextView, AccountEntity>>()
    private var currentConfirmButton: Button? = null

    /** 当前弹窗的金额输入框。金额参与就绪判据（D-21），输入变化时重算按钮状态。 */
    private var currentAmountInput: EditText? = null
    private var displayedPendingId: String? = null

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
        val context = appContext ?: return
        val db = database ?: return
        if (overlayView != null || waitingCandidate != null) {
            // 已有弹窗或挂起候选：不叠加（去重由 policy 的窗口键兜底跨笔场景）。
            return
        }
        scope.launch {
            val pendingRow = readPendingRow(db, info.pendingId) ?: return@launch
            val decision = PendingPromptPolicy.evaluate(
                info = info,
                merchant = pendingRow.merchant,
                enabled = isEnabled(context),
                now = System.currentTimeMillis(),
                recentDedupKeys = recentDedupKeys.toMap(),
                recentPromptTimes = recentPromptTimes.toList()
            )
            when (decision) {
                is PendingPromptPolicy.Decision.Skip -> {
                    JotBillLog.i(TAG, "弹窗跳过: ${decision.reason.wireValue}, id=${info.pendingId.take(16)}")
                }

                is PendingPromptPolicy.Decision.Prompt -> {
                    // ⚠️ 必须回主线程：recordPrompted / maybeShowCandidate 读写的是
                    // 一组"仅主线程"字段（overlayView、waitingCandidate、
                    // candidateExpiryRunnable、recentDedupKeys、recentPromptTimes），
                    // 而本协程跑在 Dispatchers.IO 上。直接在 IO 线程调用会造成：
                    // ① 两条并发 pending 各自通过 :218 与 :260 的双重守卫
                    //    （守卫之间的状态变更在 IO 线程，主线程看不见），
                    //    两次 displayOverlay → 两次 windowManager.addView，
                    //    而 dismissInternal 只移除 overlayView 指向的最后一个视图
                    //    ⇒ 第一个系统窗口永久残留、无法关闭；
                    // ② waitingCandidate 非 volatile ⇒ 主线程读不到 IO 线程的写，
                    //    候选丢失（弹窗不出现）或留下永不触发的僵尸超时 runnable。
                    // recordPrompted 同样必须与守卫在同一线程，否则去重表
                    // 会在 IO 线程被并发修改（HashMap 非线程安全，可损坏内部结构）。
                    val candidate = PromptCandidate(
                        pendingId = info.pendingId,
                        dedupKey = decision.dedupKey,
                        createdAt = System.currentTimeMillis()
                    )
                    mainHandler.post {
                        recordPrompted(decision.dedupKey, candidate.createdAt)
                        maybeShowCandidate(candidate)
                    }
                }
            }
        }
    }

    /** 记录已弹窗的去重键与时间戳。**仅主线程调用**（见 [onPendingChange] 内的 post）。 */
    private fun recordPrompted(dedupKey: String, now: Long) {
        recentDedupKeys[dedupKey] = now
        recentPromptTimes += now
        // 清理过期项，防止长期运行下无限增长
        recentDedupKeys.entries.removeAll { now - it.value > PendingPromptPolicy.DEDUP_WINDOW_MILLIS * 2 }
        recentPromptTimes.removeAll { now - it > 2 * 60 * 60 * 1000L }
    }

    /** 主线程：尝试立即显示候选；观察状态不可信时挂起等待非敏感帧。 */
    private fun maybeShowCandidate(candidate: PromptCandidate) {
        if (overlayView != null || waitingCandidate != null) {
            return
        }
        if (canShowNow()) {
            showCandidate(candidate)
            return
        }
        JotBillLog.i(
            TAG,
            "弹窗候选挂起等待非敏感帧: id=${candidate.pendingId.take(16)}, " +
                "sensitive=$lastObservationSensitive, foreground=$lastForegroundPackage"
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
            return
        }
        if (canShowNow()) {
            waitingCandidate = null
            candidateExpiryRunnable?.let(mainHandler::removeCallbacks)
            candidateExpiryRunnable = null
            showCandidate(candidate)
        }
    }

    /** 帧级门禁：最近观察帧非敏感且新鲜，且前台不是本应用。 */
    private fun canShowNow(): Boolean {
        val context = appContext ?: return false
        if (System.currentTimeMillis() - lastObservationAt > OBSERVATION_FRESH_MILLIS) {
            return false
        }
        if (lastObservationSensitive) {
            return false
        }
        // 前台是本应用时不弹：应用内待确认中心已覆盖该场景
        if (lastForegroundPackage == context.packageName) {
            return false
        }
        return hasOverlayPermission(context)
    }

    private fun hasOverlayPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(context)

    private fun showCandidate(candidate: PromptCandidate) {
        val context = appContext ?: return
        val db = database ?: return
        scope.launch {
            val pendingRow = readPendingRow(db, candidate.pendingId) ?: return@launch
            // 行内容复核：事件到达后可能已被其他入口确认/忽略
            if (pendingRow.status != PendingTransactionEntity.STATUS_PENDING) {
                return@launch
            }
            mainHandler.post { displayOverlay(pendingRow) }
        }
    }

    private fun displayOverlay(pendingRow: PendingTransactionEntity) {
        val context = appContext ?: return
        if (!hasOverlayPermission(context)) {
            JotBillLog.i(TAG, "无悬浮窗权限,弹窗放弃(通知兜底)")
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
            loadCategories(view, pendingRow)
            loadAccounts(view, pendingRow.accountId)
            JotBillLog.i(
                TAG,
                "付款确认弹窗已显示: id=${pendingRow.id.take(16)}, amount=${pendingRow.amountMinor}, " +
                    "source=${pendingRow.sourceType}"
            )
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "弹窗显示失败(通知兜底)", error)
            overlayView?.let { view ->
                try {
                    windowManager?.removeView(view)
                } catch (_: RuntimeException) {
                }
            }
            overlayView = null
        }
    }

    private fun onConfirmClicked(view: View, pendingRow: PendingTransactionEntity, confirmButton: Button) {
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
                when (outcome) {
                    is PendingConfirmService.Outcome.Confirmed -> {
                        prefs.edit().putString(KEY_LAST_CATEGORY_ID, category.id).apply()
                        emitJsEvents(outcome.bill)
                        showSuccessFeedback(view, outcome.bill.amountMinor, category.name)
                    }

                    is PendingConfirmService.Outcome.AlreadyHandled -> {
                        showErrorFeedback(view, "该待确认已在其他入口处理", dismissAfter = true)
                    }

                    is PendingConfirmService.Outcome.Invalid -> {
                        showErrorFeedback(view, outcome.reason)
                        confirmButton.isEnabled = true
                        confirmButton.text = "确认入账"
                    }

                    null -> {
                        showErrorFeedback(view, "入账失败，请稍后在待确认中心重试")
                        confirmButton.isEnabled = true
                        confirmButton.text = "确认入账"
                    }
                }
            }
        }
    }

    /** 弹窗确认与 App 内确认等价：广播同一组 JS 事件刷新账单/待确认缓存。 */
    private fun emitJsEvents(bill: com.jotbill.app.data.entity.BillEntity) {
        val emitter = sharedJsEventEmitter ?: return
        try {
            emitter.emit(
                "onPendingChanged",
                JSONObject().put("pendingId", displayedPendingId).put("deleted", true)
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
        mainHandler.postDelayed({ dismissInternal("SUCCESS") }, SUCCESS_DISPLAY_DELAY)
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

    /** 关闭弹窗；主线程调用。 */
    private fun dismissInternal(reason: String) {
        try {
            autoDismissRunnable?.let(mainHandler::removeCallbacks)
            autoDismissRunnable = null
            overlayView?.let { view ->
                windowManager?.removeView(view)
            }
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "弹窗关闭异常", error)
        } finally {
            overlayView = null
            selectedCategory = null
            selectedAccount = null
            categoryViews.clear()
            accountViews.clear()
            currentConfirmButton = null
            currentAmountInput = null
            displayedPendingId = null
            waitingCandidate = null
            candidateExpiryRunnable?.let(mainHandler::removeCallbacks)
            candidateExpiryRunnable = null
            JotBillLog.i(TAG, "付款确认弹窗已关闭: reason=$reason")
        }
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

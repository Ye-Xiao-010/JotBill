package com.jotbill.app.bookkeeping

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.RuleEntity
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B3 银行短信渠道测试：
 *
 * - 抽取层负向：验证码 / 理财收益 / 还款提醒 / 账单汇总短信（都含金额）不得产出
 *   `ExtractedRecord` —— 一条不采，落库层自然无从误记；
 * - 抽取层正向：招行快捷支付扣款样例产出 1 条记录，金额/时间/商户字段按资产规则抽出；
 * - 落库语义（B3 拍板 2026-11-04）：
 *   ① **乙口径**：短信记录默认自动入账到现金兜底账户（`resolveAccountId` 不改）；
 *   ② 短信 + 用户自建 `set_account` 规则命中账户字段 ⇒ **强制转待确认**
 *      （`CONFLICT_RULE_ACCOUNT_NEEDS_REVIEW`），不自动入账。
 *
 * 驱动方式：抽取层用真实内置规则资产（`bookkeeping_builtin_rules.json`）+ 引擎
 * 通知路径（`com.android.mms/android.app.Notification` key）；落库层用
 * Robolectric + Room 内存库直驱 [AutoBookkeeper.onRecordReady]（注入点
 * `databaseOverride`，生产不传参行为不变）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BankSmsNegativeTest {

    private class RecordingSink : AutoBookkeepingEngine.Sink {
        val records = mutableListOf<AutoBookkeepingEngine.ExtractedRecord>()

        override fun onRecordReady(record: AutoBookkeepingEngine.ExtractedRecord): Boolean {
            records += record
            val amount = AutoBookkeepingEngine.parseAmountMinor(record.fields["amount"])
            return amount != null && amount > 0
        }
    }

    /** 构造走真实资产与通知路径的引擎（通道闸恒放行，测试不关心 SP 开关）。 */
    private fun harness(): Pair<AutoBookkeepingEngine, RecordingSink> {
        val asset = File("src/main/assets/bookkeeping/bookkeeping_builtin_rules.json")
        assertTrue("内置规则资产不存在: ${asset.absolutePath}", asset.isFile)
        val registry = PageRuleRegistry.fromJsonText(asset.readText())
        val sink = RecordingSink()
        val engine = AutoBookkeepingEngine(
            registryRef = AtomicReference(registry),
            channels = { true },
            sink = sink,
            rescanScheduler = object : AutoBookkeepingEngine.RescanScheduler {
                override fun postDelayed(delayMillis: Long, action: () -> Unit) = Unit
                override fun cancelAll() = Unit
            },
            clock = { 1_000_000L }
        )
        return engine to sink
    }

    /** 投递一条银行短信通知事件（真机形态：短信 App 的通知，className=android.app.Notification）。 */
    private fun deliverSms(engine: AutoBookkeepingEngine, text: String) {
        engine.onEvent(
            AutoBookkeepingEngine.EventSnapshot(
                eventType = AutoBookkeepingEngine.EVENT_NOTIFICATION_STATE_CHANGED,
                packageName = "com.android.mms",
                className = "android.app.Notification",
                windowId = 0,
                eventTime = 1_000_000L,
                notificationText = text,
                notificationContentDesc = null
            ),
            rootProvider = { null }
        )
    }

    @Test
    fun 招行快捷支付扣款短信产出一条记录() {
        val (engine, sink) = harness()
        deliverSms(
            engine,
            "【招商银行】您账户3456于10月25日14:30在【拼多多】发生快捷支付扣款，人民币29.90元"
        )

        assertEquals("扣款短信应产出且仅产出一条记录", 1, sink.records.size)
        val record = sink.records.single()
        assertEquals(1000033L, record.ruleId.toLong())
        assertEquals(2990L, AutoBookkeepingEngine.parseAmountMinor(record.fields["amount"]))
        assertEquals("10月25日14:30", record.fields["businessTime"])
        assertEquals("拼多多", record.fields["method"])
    }

    @Test
    fun 验证码短信零记录() {
        val (engine, sink) = harness()
        deliverSms(
            engine,
            "【招商银行】验证码123456，您正在快捷支付，请勿泄露给他人。"
        )
        assertEquals("验证码短信含数字但非交易，不得产出记录", 0, sink.records.size)
    }

    @Test
    fun 理财收益短信零记录() {
        val (engine, sink) = harness()
        deliverSms(
            engine,
            "【招商银行】您理财收益88.00元已入账，请登录手机银行查看。"
        )
        assertEquals("理财收益短信不是消费/入账交易语义，不得产出记录", 0, sink.records.size)
    }

    @Test
    fun 还款提醒短信零记录() {
        val (engine, sink) = harness()
        deliverSms(
            engine,
            "【招商银行】您的信用卡本期应还1234.56元，请于11月10日前还款。"
        )
        assertEquals("还款提醒是待办语义，不是已发生的交易，不得产出记录", 0, sink.records.size)
    }

    @Test
    fun 账单汇总短信零记录() {
        val (engine, sink) = harness()
        deliverSms(
            engine,
            "【招商银行】您本期账单共3笔交易，合计人民币1234.56元，请及时查收。"
        )
        assertEquals("账单汇总一条多笔有歧义，不得产出记录", 0, sink.records.size)
    }

    @Test
    fun 其他来源包不落短信规则() {
        // 对照：同文案经非 mms 包到达（如浏览器通知）不得命中 mms 规则 key。
        val asset = File("src/main/assets/bookkeeping/bookkeeping_builtin_rules.json")
        val registry = PageRuleRegistry.fromJsonText(asset.readText())
        assertEquals(
            "mms 规则 key 只挂在短信通知形态下",
            null,
            registry.matchKey("com.android.browser", "android.app.Notification")
        )
    }

    // ── B3 拍板（2026-11-04）落库语义两例 ──────────────────────────────

    /**
     * 建 Room 内存库并插入现金兜底账户（种子数据等价物），
     * 以 [runBlocking] 的协程作用域交付给用例 —— [AutoBookkeeper.onRecordReady]
     * 的异步落账 launch 在该作用域上，runBlocking 退出前必然完成。
     */
    private fun withDatabase(block: suspend CoroutineScope.(JotBillDatabase) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(context, JotBillDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            database.accountDao().insert(
                AccountEntity(
                    id = "default-account-cash", name = "现金", subtype = "cash",
                    kind = "asset", initialBalanceMinor = 0L, currentBalanceMinor = 100_000L,
                    currency = "CNY", icon = null, color = null, sortOrder = 0,
                    isActive = true, createdAt = 1L, updatedAt = 1L
                )
            )
            block(this, database)
        } finally {
            database.close()
        }
    }

    /** 构造一条银行短信抽取记录（真机形态：招行扣款经 mms 通知抽出的字段）。 */
    private fun smsRecord(
        amountText: String,
        orderId: String,
        counterparty: String = "拼多多"
    ) = AutoBookkeepingEngine.ExtractedRecord(
        fields = mapOf(
            "amount" to amountText,
            "orderNo" to orderId,
            "counterparty" to counterparty,
            "businessTime" to "10月25日14:30",
            "detailType" to "2"
        ),
        ruleId = 1000033,
        ruleNote = "招商银行-支出",
        fromApp = 4,
        jotbillType = null,
        sourcePackage = "com.android.mms",
        extractTime = System.currentTimeMillis()
    )

    /**
     * 投递抽取记录并**等待异步落账完成**后再返回。
     *
     * [AutoBookkeeper.onRecordReady] 的落账是 `scope.launch`；runBlocking 单线程
     * event loop 下 launch 任务在当前 block 同步段让出前不会执行 ⇒ 必须 join
     * 全部子协程，否则断言跑在落账之前（假红）。
     */
    private suspend fun CoroutineScope.deliverRecord(
        database: JotBillDatabase,
        record: AutoBookkeepingEngine.ExtractedRecord
    ) {
        AutoBookkeeper(ApplicationProvider.getApplicationContext(), this, database)
            .onRecordReady(record)
        coroutineContext.job.children.forEach { it.join() }
    }

    /**
     * B3 拍板 ②：短信 + 用户 set_account 规则命中账户字段 ⇒ 强制转待确认
     * （猜测性人工干预须用户核对），不得自动入账。
     */
    @Test
    fun 短信用户账户规则命中强制待确认() = withDatabase { database ->
        val ruleAccountId = "acc-bank-card"
        database.accountDao().insert(
            AccountEntity(
                id = ruleAccountId, name = "招商银行卡", subtype = "debit",
                kind = "asset", initialBalanceMinor = 0L, currentBalanceMinor = 50_000L,
                currency = "CNY", icon = null, color = null, sortOrder = 1,
                isActive = true, createdAt = 1L, updatedAt = 1L
            )
        )
        database.ruleDao().insert(
            RuleEntity(
                id = "rule-sms-account", name = "短信入招商卡",
                condition = "description_contains", conditionValue = "拼多多",
                action = "set_account", actionValue = ruleAccountId,
                priority = 100, isActive = true, hitCount = 0, source = "manual",
                createdAt = 1L, updatedAt = 1L
            )
        )

        deliverRecord(database, smsRecord(amountText = "34.50", orderId = "ORD-SMS-RULE-1"))

        assertEquals("规则命中不得自动入账", 0, database.billDao().count())
        val pending = database.pendingTransactionDao().findRecentPendingByPlatformAndAmount(
            platform = "com.android.mms",
            amountMinor = 3450L,
            windowStart = 0L,
            windowEnd = Long.MAX_VALUE
        )
        assertTrue("应落待确认卡", pending != null)
        assertEquals(
            AutoBookkeeper.CONFLICT_RULE_ACCOUNT_NEEDS_REVIEW,
            pending!!.conflictReason
        )
    }

    /**
     * B3 拍板 ①（乙口径）：短信 + 无用户账户规则 ⇒ 默认自动入账到现金兜底账户。
     */
    @Test
    fun 短信无规则自动入账到现金账户() = withDatabase { database ->
        deliverRecord(database, smsRecord(amountText = "29.90", orderId = "ORD-SMS-CASH-1"))

        val bill = database.billDao().findByOrderAndAmount("ORD-SMS-CASH-1", 2990L)
        assertTrue("短信记录应自动入账", bill != null)
        assertEquals("默认入现金兜底账户", "default-account-cash", bill!!.accountId)
        val pending = database.pendingTransactionDao().findRecentPendingByPlatformAndAmount(
            platform = "com.android.mms",
            amountMinor = 2990L,
            windowStart = 0L,
            windowEnd = Long.MAX_VALUE
        )
        assertEquals("自动入账后不得再落待确认", null, pending)
    }
}

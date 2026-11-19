package com.jotbill.app.bookkeeping

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.pending.EditCardMarker
import com.jotbill.app.pending.PendingConfirmService
import com.jotbill.app.recognition.RefundLinker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 记账模型调整（2026-11-16）核心测试：
 * 1. 首笔信号直接生成账单（不再落待确认）；
 * 2. 60 秒内同额后续信号只补全账户/商户，并允许现金 → 银行卡账户升级（余额迁移）；
 * 3. 退款首笔直接成账，账户按提示优先匹配，无法匹配落现金；
 * 4. 支付账单生成「已入账编辑卡」，确认只更新账单（账户/分类），不新建。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SignalBillWriterTest {

    private lateinit var database: JotBillDatabase
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, JotBillDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            database.accountDao().insert(
                AccountEntity(
                    id = "default-account-cash", name = "现金", subtype = "cash", kind = "asset",
                    initialBalanceMinor = 100_000L, currentBalanceMinor = 100_000L,
                    currency = "CNY", icon = null, color = null, sortOrder = 0,
                    isActive = true, createdAt = 1L, updatedAt = 1L
                )
            )
            database.accountDao().insert(
                AccountEntity(
                    id = "acc-rcc", name = "四川农商银行", subtype = "bank_debit", kind = "asset",
                    initialBalanceMinor = 0L, currentBalanceMinor = 0L,
                    currency = "CNY", icon = null, color = null, sortOrder = 1,
                    isActive = true, createdAt = 1L, updatedAt = 1L, cardTail = "2510"
                )
            )
            database.accountDao().insert(
                AccountEntity(
                    id = "default-account-wechat", name = "微信零钱", subtype = "e_wallet",
                    kind = "asset", initialBalanceMinor = 0L, currentBalanceMinor = 0L,
                    currency = "CNY", icon = null, color = null, sortOrder = 2,
                    isActive = true, createdAt = 1L, updatedAt = 1L
                )
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun payment(
        amount: Long,
        hints: List<String?> = emptyList(),
        source: String = "com.xunmeng.pinduoduo",
        orderId: String? = null,
        sourceType: String = "accessibility",
        sourceEventKey: String? = null,
        merchant: String = "拼多多"
    ) =
        SignalBillWriter.PaymentSignal(
            amountMinor = amount,
            occurredAt = System.currentTimeMillis(),
            merchant = merchant,
            orderId = orderId,
            platform = source,
            channelLabel = "拼多多",
            sourcePackage = source,
            sourceType = sourceType,
            accountHints = hints,
            sourceEventKey = sourceEventKey
        )

    private fun refund(
        amount: Long,
        hints: List<String?> = emptyList(),
        source: String = "com.xunmeng.pinduoduo",
        sourceEventKey: String? = null
    ) =
        SignalBillWriter.RefundSignal(
            amountMinor = amount,
            occurredAt = System.currentTimeMillis(),
            merchant = "拼多多",
            orderId = null,
            platform = source,
            sourcePackage = source,
            sourceType = "notification",
            receivingHints = hints,
            sourceEventKey = sourceEventKey
        )

    @Test
    fun 首笔支付信号直接生成账单() = runBlocking {
        val writer = SignalBillWriter(context, database)
        val outcome = writer.recordPayment(payment(1234L))

        assertTrue("首笔信号应直接入账: $outcome", outcome is SignalBillWriter.Outcome.Created)
        val bills = database.billDao().getAllForExport()
        assertEquals(1, bills.size)
        assertEquals(1234L, bills.first().amountMinor)
        assertEquals("expense", bills.first().type)
        assertEquals("default-account-cash", bills.first().accountId)
        assertEquals("不产生待确认业务卡（仅编辑卡）", 1, database.pendingTransactionDao().count())
        assertEquals(
            "编辑卡不得被同额吸收/自动关闭吞掉",
            1,
            database.pendingTransactionDao().findPendingEditCards().size
        )
    }

    @Test
    fun 后续信号补全并升级账户余额迁移() = runBlocking {
        val writer = SignalBillWriter(context, database)
        writer.recordPayment(payment(1000L))
        val cashAfterPay = database.accountDao().getById("default-account-cash")!!.currentBalanceMinor
        assertEquals(99_000L, cashAfterPay)

        val enriched = writer.recordPayment(
            payment(1000L, hints = listOf("四川农商银行"), source = "com.android.mms")
        )

        assertTrue("后续同额信号应补全: $enriched", enriched is SignalBillWriter.Outcome.Enriched)
        val bill = database.billDao().getAllForExport().single()
        assertEquals("账户升级到银行账户", "acc-rcc", bill.accountId)
        assertEquals("原现金账户余额回补", 100_000L, database.accountDao().getById("default-account-cash")!!.currentBalanceMinor)
        assertEquals("银行账户承接支出(-1000)", -1000L, database.accountDao().getById("acc-rcc")!!.currentBalanceMinor)
        assertEquals(1, database.billDao().count())
    }

    @Test
    fun 退款首笔直接成账且账户提示优先() = runBlocking {
        val writer = SignalBillWriter(context, database)
        val outcome = writer.recordRefund(refund(2000L, hints = listOf("微信支付"), source = "com.android.mms"))

        assertTrue("退款首笔应直接入账: $outcome", outcome is SignalBillWriter.Outcome.Created)
        val bill = database.billDao().getAllForExport().single()
        assertEquals("refund", bill.type)
        assertEquals("微信提示命中零钱账户", "default-account-wechat", bill.accountId)
        assertEquals(2000L, database.accountDao().getById("default-account-wechat")!!.currentBalanceMinor)
        assertEquals(0, database.pendingTransactionDao().count())
    }

    @Test
    fun 退款无法匹配账户时落现金() = runBlocking {
        val writer = SignalBillWriter(context, database)
        writer.recordRefund(refund(3000L, source = "com.xunmeng.pinduoduo"))
        val bill = database.billDao().getAllForExport().single()
        assertEquals("default-account-cash", bill.accountId)
        assertEquals(103_000L, database.accountDao().getById("default-account-cash")!!.currentBalanceMinor)
    }

    @Test
    fun 已入账编辑卡确认只更新账单不重复记账() = runBlocking {
        val writer = SignalBillWriter(context, database)
        writer.recordPayment(payment(1500L))
        val bill = database.billDao().getAllForExport().single()
        val card = database.pendingTransactionDao().findPendingEditCards().singleOrNull()
        assertNotNull("应生成已入账编辑卡", card)
        assertEquals(bill.id, EditCardMarker.billId(card!!.appliedRules))

        val outcome = PendingConfirmService(database).confirm(
            pendingId = card.id,
            overrides = PendingConfirmService.Overrides(
                accountId = "acc-rcc",
                accountTouched = true
            )
        )

        assertTrue("编辑卡确认应成功: $outcome", outcome is PendingConfirmService.Outcome.Confirmed)
        assertEquals("账单仍只有一笔", 1, database.billDao().count())
        val updated = database.billDao().getById(bill.id)!!
        assertEquals("账户已更新", "acc-rcc", updated.accountId)
        assertEquals("余额迁移到银行账户(-1500)", -1500L, database.accountDao().getById("acc-rcc")!!.currentBalanceMinor)
    }

    @Test
    fun 同一来源事件重复投递只入账一次() = runBlocking {
        val writer = SignalBillWriter(context, database)
        val first = writer.recordPayment(payment(1888L, sourceEventKey = "notif-1"))
        val second = writer.recordPayment(payment(1888L, sourceEventKey = "notif-1"))

        assertTrue("首笔应入账: $first", first is SignalBillWriter.Outcome.Created)
        assertTrue("重复事件应判重: $second", second is SignalBillWriter.Outcome.Duplicate)
        assertEquals(1, database.billDao().count())
    }

    @Test
    fun 手工同额账单不被自动信号合并() = runBlocking {
        val now = System.currentTimeMillis()
        database.billDao().insert(
            com.jotbill.app.data.entity.BillEntity(
                id = "manual-bill",
                amountMinor = 1200L,
                type = "expense",
                occurredAt = now,
                description = "手工记账",
                categoryId = null,
                accountId = "default-account-cash",
                targetAccountId = null,
                platform = "现金",
                merchant = "手工商户",
                productDescription = null,
                sourceType = "manual",
                originalTransactionId = null,
                orderId = null,
                createdAt = now,
                updatedAt = now,
                version = 1
            )
        )
        val outcome = SignalBillWriter(context, database)
            .recordPayment(payment(1200L, source = "com.android.mms", sourceType = "sms"))

        assertTrue("手工账单不参与自动合并: $outcome", outcome is SignalBillWriter.Outcome.Created)
        assertEquals(2, database.billDao().count())
        assertEquals(
            "手工账单账户不变",
            "default-account-cash",
            database.billDao().getById("manual-bill")!!.accountId
        )
    }

    @Test
    fun 订单号不同的同额信号各自成账() = runBlocking {
        val writer = SignalBillWriter(context, database)
        writer.recordPayment(payment(1990L, orderId = "ORDER-A", sourceEventKey = "e-a"))
        val second = writer.recordPayment(payment(1990L, orderId = "ORDER-B", sourceEventKey = "e-b"))

        assertTrue("订单号不同是两笔真单: $second", second is SignalBillWriter.Outcome.Created)
        assertEquals(2, database.billDao().count())
    }

    @Test
    fun 并发同额信号只成一笔() = runBlocking {
        val writer = SignalBillWriter(context, database)
        val results = (1..6).map { index ->
            async(Dispatchers.IO) {
                writer.recordPayment(payment(2500L, sourceEventKey = "concurrent-$index"))
            }
        }.awaitAll()

        assertEquals(
            "同额并发信号只允许一张账单: $results",
            1,
            database.billDao().count()
        )
    }

    @Test
    fun 规则send_to_review不自动入账() = runBlocking {
        database.ruleDao().insert(
            com.jotbill.app.data.entity.RuleEntity(
                id = "rule-review",
                name = "拼多多需复核",
                condition = com.jotbill.app.rules.RuleEngine.CONDITION_MERCHANT_CONTAINS,
                conditionValue = "拼多多",
                action = com.jotbill.app.rules.RuleEngine.ACTION_SEND_TO_REVIEW,
                actionValue = null,
                priority = 1,
                isActive = true,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        val writer = SignalBillWriter(context, database)
        val first = writer.recordPayment(payment(2600L, sourceEventKey = "review-1"))
        // NeedsReview 不写事件认领键：重投必须仍可处理（落卡失败可重试），不能判重丢证据。
        val second = writer.recordPayment(payment(2600L, sourceEventKey = "review-1"))

        assertTrue("规则复核应返回 NeedsReview: $first", first is SignalBillWriter.Outcome.NeedsReview)
        assertTrue("复核信号不得被认领键判重: $second", second is SignalBillWriter.Outcome.NeedsReview)
        assertEquals("不得静默入账", 0, database.billDao().count())
    }

    @Test
    fun 同来源退款重复投递只入账一次() = runBlocking {
        val writer = SignalBillWriter(context, database)
        val first = writer.recordRefund(refund(3200L, sourceEventKey = "refund-dup-1"))
        val second = writer.recordRefund(refund(3200L, sourceEventKey = "refund-dup-1"))

        assertTrue("首笔退款应入账: $first", first is SignalBillWriter.Outcome.Created)
        assertTrue("重复退款应判重: $second", second is SignalBillWriter.Outcome.Duplicate)
        assertEquals(1, database.billDao().getAllForExport().count { it.type == "refund" })
    }

    @Test
    fun 跨渠道同额不同商户仍合并() = runBlocking {
        val writer = SignalBillWriter(context, database)
        writer.recordPayment(
            payment(1880L, merchant = "三只松鼠旗舰店", sourceEventKey = "page-1")
        )
        // 银行短信商户常是支付通道名（财付通），与页面店铺名互不包含，
        // 但跨渠道同额 60 秒内是同一笔 —— 不得被商户护栏拆成两笔。
        val second = writer.recordPayment(
            payment(
                1880L,
                source = "com.android.mms",
                sourceType = "sms",
                merchant = "财付通",
                sourceEventKey = "sms-1"
            )
        )

        assertTrue("跨渠道应补全而非新建: $second", second is SignalBillWriter.Outcome.Enriched)
        assertEquals(1, database.billDao().count())
    }

    @Test
    fun 同渠道同额不同商户各自成账() = runBlocking {
        val writer = SignalBillWriter(context, database)
        writer.recordPayment(payment(2100L, merchant = "店铺甲", sourceEventKey = "page-a"))
        val second = writer.recordPayment(
            payment(2100L, merchant = "店铺乙", sourceEventKey = "page-b")
        )

        assertTrue("同渠道不同商户是两笔真单: $second", second is SignalBillWriter.Outcome.Created)
        assertEquals(2, database.billDao().count())
    }

    @Test
    fun 跨通道吸收不吞导入或断言卡() = runBlocking {
        val now = System.currentTimeMillis()
        database.pendingTransactionDao().insertIfAbsent(
            com.jotbill.app.data.entity.PendingTransactionEntity(
                id = "import-card-1",
                candidateId = null,
                sessionId = null,
                amountMinor = 3300L,
                type = "expense",
                occurredAt = now,
                platform = "com.xunmeng.pinduoduo",
                merchant = "拼多多",
                productDescription = null,
                orderId = null,
                accountId = null,
                categoryId = null,
                conflictReason = null,
                sourceType = "import",
                appliedRules = "{}",
                createdAt = now,
                version = 1
            )
        )

        SignalBillWriter(context, database).recordPayment(payment(3300L, sourceEventKey = "sms-2"))

        val card = database.pendingTransactionDao().getById("import-card-1")
        assertNotNull("导入卡不得被自动信号吸收", card)
        assertEquals(
            "导入卡应保持待确认状态",
            com.jotbill.app.data.entity.PendingTransactionEntity.STATUS_PENDING,
            card!!.status
        )
    }

    @Test
    fun 钱包账单被短信卡尾号升级为银行卡() = runBlocking {
        val writer = SignalBillWriter(context, database)
        writer.recordPayment(
            payment(
                1888L,
                hints = listOf("微信支付"),
                source = "com.tencent.mm",
                sourceEventKey = "wechat-page-1"
            )
        )
        assertEquals(
            "首笔仅能从结果页映射到微信零钱",
            "default-account-wechat",
            database.billDao().getAllForExport().single().accountId
        )

        // 银行短信带卡尾号 2510：短信账户来源优先级最高，应升级到银行卡。
        val second = writer.recordPayment(
            payment(
                1888L,
                hints = listOf("四川农商银行", "6222020200112510"),
                source = "com.android.mms",
                sourceType = "sms",
                sourceEventKey = "sms-card-1"
            )
        )

        assertTrue("短信应补全既有账单: $second", second is SignalBillWriter.Outcome.Enriched)
        val bill = database.billDao().getAllForExport().single()
        assertEquals("升级到卡尾号命中的银行账户", "acc-rcc", bill.accountId)
        assertEquals(
            "微信零钱余额回补",
            0L,
            database.accountDao().getById("default-account-wechat")!!.currentBalanceMinor
        )
        assertEquals(
            "银行账户承接支出",
            -1888L,
            database.accountDao().getById("acc-rcc")!!.currentBalanceMinor
        )
    }

    @Test
    fun 银行账户不被钱包信号降级() = runBlocking {
        val writer = SignalBillWriter(context, database)
        writer.recordPayment(
            payment(
                1666L,
                hints = listOf("四川农商银行", "6222020200112510"),
                source = "com.android.mms",
                sourceType = "sms",
                sourceEventKey = "sms-first-1"
            )
        )
        assertEquals(
            "短信卡尾号直接落银行账户",
            "acc-rcc",
            database.billDao().getAllForExport().single().accountId
        )

        val second = writer.recordPayment(
            payment(
                1666L,
                hints = listOf("微信支付"),
                source = "com.tencent.mm",
                sourceEventKey = "wechat-late-1"
            )
        )

        assertTrue("同额信号仍应补全", second is SignalBillWriter.Outcome.Enriched)
        assertEquals(
            "钱包信号不得把银行账户降级",
            "acc-rcc",
            database.billDao().getAllForExport().single().accountId
        )
    }

    @Test
    fun 卡尾号优先于同名银行账户() = runBlocking {
        // 同名的第二张卡（没填尾号）：只按银行名会多候选，按尾号必须唯一命中。
        database.accountDao().insert(
            AccountEntity(
                id = "acc-rcc-2", name = "四川农商银行", subtype = "bank_debit", kind = "asset",
                initialBalanceMinor = 0L, currentBalanceMinor = 0L,
                currency = "CNY", icon = null, color = null, sortOrder = 2,
                isActive = true, createdAt = 1L, updatedAt = 1L
            )
        )

        val resolution = AccountInference.resolveByHints(
            sourcePackage = "com.android.mms",
            hints = listOf("四川农商银行 尾号2510"),
            accounts = database.accountDao().getAll().filter { it.isActive }
        )

        assertTrue("尾号应唯一命中: $resolution", resolution is AccountResolution.Resolved)
        assertEquals(
            "命中填了尾号的账户",
            "acc-rcc",
            (resolution as AccountResolution.Resolved).accountId
        )
        assertEquals("短信账户等级最高", AccountInference.Rank.BANK, resolution.rank)
    }

    private fun postalSms(amount: String, timestamp: Long = System.currentTimeMillis()) =
        checkNotNull(BankSmsNotificationParser.parseExpense(
            PageRuleRegistry.get(context),
            "【邮储银行】您尾号0176账户快捷支付-支付宝，支出金额${amount}元，余额10.00元",
            timestamp
        ))

    @Test
    fun 支付宝先入账邮储短信补全并复用新卡() = runBlocking {
        val alipay = AccountInference.ALIPAY_ACCOUNT_ID
        database.accountDao().insert(database.accountDao().getById("default-account-wechat")!!.copy(
            id = alipay,
            name = "支付宝"
        ))
        val writer = SignalBillWriter(context, database)
        val first = writer.recordPayment(payment(600, source = AccountInference.ALIPAY_PACKAGE)) as SignalBillWriter.Outcome.Created
        assertEquals(alipay, database.billDao().getById(first.billId)?.accountId)
        val sms = postalSms("6.00")
        val bookkeeper = AutoBookkeeper(context, this, database)
        repeat(2) {
            assertTrue(bookkeeper.onRecordReady(sms))
            coroutineContext[Job]!!.children.toList().joinAll()
        }
        val bank = database.accountDao().getAll().single { it.cardTail == "0176" }
        assertEquals("bank:psbc", bank.icon)
        assertEquals("bank_debit", bank.subtype)
        assertEquals(-600L, bank.currentBalanceMinor)
        assertEquals(0L, database.accountDao().getById(alipay)?.currentBalanceMinor)
        val onlyBill = database.billDao().getAllForExport().single()
        assertEquals(first.billId, onlyBill.id)
        assertEquals(AccountInference.ALIPAY_PACKAGE, onlyBill.platform)
        assertEquals(bank.id, onlyBill.accountId)
        assertEquals(bank.id, database.pendingTransactionDao().getById(SignalBillWriter.editCardId(onlyBill.id))?.accountId)

        assertTrue(bookkeeper.onRecordReady(postalSms("7.00")))
        coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals(1, database.accountDao().getAll().count { it.cardTail == "0176" })
        assertEquals(-1300L, database.accountDao().getById(bank.id)?.currentBalanceMinor)
    }

    @Test
    fun 兼容已存在的邮储别名同尾号账户且不重复建卡() = runBlocking {
        val existing = database.accountDao().getById("acc-rcc")!!.copy(
            id = "my-postal-card",
            name = "我的工资卡",
            icon = "bank:psbc",
            cardTail = "0176"
        )
        database.accountDao().insert(existing)
        assertTrue(AutoBookkeeper(context, this, database).onRecordReady(postalSms("6.00")))
        coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals(existing.id, database.billDao().getAllForExport().single().accountId)
        assertEquals(1, database.accountDao().getAll().count { it.cardTail == "0176" })
    }

    @Test
    fun 邮储退款短信补全已有退款并创建到账卡() = runBlocking {
        val writer = SignalBillWriter(context, database)
        val first = writer.recordRefund(refund(600)) as SignalBillWriter.Outcome.Created
        val sms = checkNotNull(BankSmsNotificationParser.parseRefund(
            "【邮储银行】您尾号0176账户退货，收入金额6.00元，余额10.00元。",
            System.currentTimeMillis()
        ))
        val outcome = RefundLinker(database, appContext = context).processRefund(sms)
        assertEquals(RefundLinker.RefundOutcome.Enriched(first.billId), outcome)
        val saved = database.billDao().getAllForExport().single()
        val bank = database.accountDao().getAll().single { it.cardTail == "0176" }
        assertEquals(bank.id, saved.accountId)
        assertEquals(600L, bank.currentBalanceMinor)
        assertEquals(100_000L, database.accountDao().getById("default-account-cash")?.currentBalanceMinor)
    }

    @Test
    fun 同一卡两笔短信并发只创建一个账户() = runBlocking {
        val bookkeeper = AutoBookkeeper(context, this, database)
        assertTrue(bookkeeper.onRecordReady(postalSms("6.00")))
        assertTrue(bookkeeper.onRecordReady(postalSms("7.00")))
        coroutineContext[Job]!!.children.toList().joinAll()
        val bank = database.accountDao().getAll().single { it.cardTail == "0176" }
        val bills = database.billDao().getAllForExport()
        assertEquals(2, bills.size)
        assertTrue(bills.all { it.accountId == bank.id })
        assertEquals(-1300L, bank.currentBalanceMinor)
    }

    @Test
    fun 超过补全窗口的同订单短信不会创建无账单账户() = runBlocking {
        val writer = SignalBillWriter(context, database)
        val first = writer.recordPayment(payment(600, orderId = "POSTAL-OLD-ORDER")) as SignalBillWriter.Outcome.Created
        val bill = database.billDao().getById(first.billId)!!
        database.billDao().delete(bill)
        database.billDao().insert(bill.copy(createdAt = System.currentTimeMillis() - 61_000))
        val result = writer.recordPayment(payment(
            600, orderId = bill.orderId, source = "com.android.mms", sourceType = "sms"
        ).copy(bankCard = BankCardEvidence("邮储银行", "0176", "bank_debit")))
        assertEquals(SignalBillWriter.Outcome.Enriched(first.billId), result)
        assertEquals(bill.accountId, database.billDao().getAllForExport().single().accountId)
        assertTrue(database.accountDao().getAll().none { it.cardTail == "0176" })
    }

    @Test
    fun 未知类型与锁定账单不会产生新账户() = runBlocking {
        val writer = SignalBillWriter(context, database)
        val result = writer.recordPayment(payment(500).copy(
            bankCard = BankCardEvidence("邮储银行", "0176", null)
        ))
        assertTrue(result is SignalBillWriter.Outcome.Created)
        assertEquals(3, database.accountDao().getAll().size)
        val bill = database.billDao().getAllForExport().single()
        database.billDao().insert(bill.copy(id = "locked", amountMinor = 600, fieldLocks = "[\"accountId\"]"))
        assertTrue(AutoBookkeeper(context, this, database).onRecordReady(postalSms("6.00")))
        coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals("default-account-cash", database.billDao().getById("locked")?.accountId)
        assertEquals(3, database.accountDao().getAll().size)
    }

    @Test
    fun 退款泛化渠道提示不猜账户且保留原单账户() = runBlocking {
        // 另一张银行账户：若把「银行卡」当账户名包含匹配，会错误命中它。
        database.accountDao().insert(
            AccountEntity(
                id = "acc-cmb-card", name = "招商银行卡", subtype = "bank_debit", kind = "asset",
                initialBalanceMinor = 0L, currentBalanceMinor = 0L,
                currency = "CNY", icon = null, color = null, sortOrder = 3,
                isActive = true, createdAt = 1L, updatedAt = 1L
            )
        )
        val writer = SignalBillWriter(context, database)
        writer.recordPayment(
            payment(
                4200L,
                hints = listOf("四川农商银行", "6222020200112510"),
                source = "com.android.mms",
                sourceType = "sms",
                sourceEventKey = "expense-origin-1"
            )
        )
        val original = database.billDao().getAllForExport().single { it.type == "expense" }
        assertEquals("原单应落卡尾号账户", "acc-rcc", original.accountId)

        val outcome = writer.recordRefund(
            SignalBillWriter.RefundSignal(
                amountMinor = 4200L,
                occurredAt = System.currentTimeMillis(),
                merchant = "京东",
                orderId = null,
                platform = "com.jingdong.app.mall",
                sourcePackage = "com.jingdong.app.mall",
                sourceType = "notification",
                receivingHints = listOf("银行卡"),
                originalBillId = original.id,
                sourceEventKey = "refund-generic-1"
            )
        )

        assertTrue("退款首笔应成账: $outcome", outcome is SignalBillWriter.Outcome.Created)
        val refund = database.billDao().getAllForExport().first { it.type == "refund" }
        assertEquals(
            "泛化「银行卡」提示不得猜账户，应沿用原单账户",
            "acc-rcc",
            refund.accountId
        )
    }
}

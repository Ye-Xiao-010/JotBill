package com.jotbill.app.recognition

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.bookkeeping.AccountInference
import com.jotbill.app.bookkeeping.AccountResolution
import com.jotbill.app.bookkeeping.AutoBookkeeper
import com.jotbill.app.bookkeeping.PageNode
import com.jotbill.app.bookkeeping.PageNodeDumper
import com.jotbill.app.bookkeeping.PageRuleRegistry
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.CategoryEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import com.jotbill.app.notification.PaymentEvidenceInput
import com.jotbill.app.notification.PaymentResultNormalizer
import com.jotbill.app.pending.PendingConfirmService
import com.jotbill.app.utils.JotBillLog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Batch3RecognitionOverlayFixesTest {

    private lateinit var database: JotBillDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, JotBillDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun editCardConfirm_persistsRemarkAndPreselectedCategoryWhenBillUnclassified() = runBlocking {
        val now = 1_700_000_000_000L
        database.accountDao().insert(
            AccountEntity("acc-1", "微信零钱", "e_wallet", "asset", 10_000L, 10_000L, "CNY", null, null, 0, true, now, now)
        )
        database.categoryDao().insert(
            CategoryEntity("cat-food", "餐饮", "expense", null, null, true, 1, now, now, null)
        )
        database.billDao().insert(
            BillEntity("bill-1", 2_500L, "expense", now, null, null, "acc-1", null, "com.tencent.mm", "肯德基", null, "accessibility", null, null, now, now, 1)
        )
        database.pendingTransactionDao().insert(
            PendingTransactionEntity(
                id = "edit-card-1",
                sessionId = null,
                candidateId = null,
                amountMinor = 2_500L,
                type = "expense",
                occurredAt = now,
                platform = "com.tencent.mm",
                merchant = "肯德基",
                productDescription = null,
                categoryId = null,
                accountId = "acc-1",
                orderId = null,
                sourceType = "accessibility",
                status = PendingTransactionEntity.STATUS_PENDING,
                conflictReason = null,
                appliedRules = """{"editBillId":"bill-1"}""",
                createdAt = now,
                version = 1
            )
        )

        val outcome = PendingConfirmService(database).confirm(
            pendingId = "edit-card-1",
            overrides = PendingConfirmService.Overrides(
                categoryId = "cat-food",
                accountId = "acc-1",
                description = "工作午餐",
                accountTouched = false,
                categoryTouched = false
            )
        )

        assertTrue(outcome is PendingConfirmService.Outcome.Confirmed)
        val saved = database.billDao().getById("bill-1")!!
        assertEquals("编辑卡应保存用户备注", "工作午餐", saved.description)
        assertEquals("原账单无分类时应保存弹窗自动预选分类", "cat-food", saved.categoryId)
    }

    @Test
    fun crossPlatformPendingQuery_excludesEditCards() = runBlocking {
        val now = 1_700_000_000_000L
        database.pendingTransactionDao().insert(
            PendingTransactionEntity(
                id = "edit-card-2",
                sessionId = null,
                candidateId = null,
                amountMinor = 1_800L,
                type = "expense",
                occurredAt = now,
                platform = "com.tencent.mm",
                merchant = "商户",
                productDescription = null,
                categoryId = null,
                accountId = null,
                orderId = null,
                sourceType = "accessibility",
                status = PendingTransactionEntity.STATUS_PENDING,
                conflictReason = null,
                appliedRules = """{"editBillId":"bill-edit-2"}""",
                createdAt = now,
                version = 1
            )
        )
        val found = database.pendingTransactionDao().findRecentPendingByAmountCrossPlatform(
            platform = "com.xunmeng.pinduoduo",
            excludedPlatform = "com.android.mms",
            amountMinor = 1_800L,
            windowStart = now - 60_000L,
            windowEnd = now + 60_000L
        )
        assertEquals(null, found)
    }

    @Test
    fun paymentResultParser_classifiesWechatTransferPageAsPersonalTransfer() {
        val now = 1_700_000_000_000L
        val parsed = PaymentResultParser.parseResult(
            pageText = "支付成功 微信转账 ¥50.00 已转账",
            sourcePackage = "com.tencent.mm",
            pageFingerprint = "fp-1",
            observedAt = now
        )
        assertTrue(
            "微信转账页不应把“微信转账”误抓为商户而落成消费支出",
            parsed is PaymentResultParseResult.PersonalTransfer
        )
    }

    @Test
    fun builtinRules_matchLargeAmountsForAlipayTransferAndRedPacketAndClassifyTransfer() {
        val asset = File("src/main/assets/bookkeeping/bookkeeping_builtin_rules.json")
        val registry = PageRuleRegistry.fromJsonText(asset.readText())

        val alipayTransferRule = registry.rulesFor("com.alipay.android.msp.ui.views.MspContainerActivity")!!
            .first { it.ruleId == 1000032 }
        assertEquals("transfer", alipayTransferRule.jotbillType)
        val transferFields = alipayTransferRule.extractFrom(
            "<View contentDesc=转账成功¥125.50 />\n<View text=收款方 />\n<View text=张三 />"
        )
        assertNotNull("支付宝 >= ¥10 转账正则应匹配", transferFields)
        assertEquals("125.50", transferFields!!["amount"])

        val alipayRedPacketIncome = registry.rulesFor("com.alipay.mobile.nebulax.xriver.activity.XRiverActivity")!!
            .first { it.ruleId == 1000029 && it.jotbillType == "income" }
        val redPacketFields = alipayRedPacketIncome.extractFrom(
            "<View text=头像 />\n<View text=李四 />\n<View text=发的红包 />\n<View text=88.88元 />\n<View text=领取成功 />"
        )
        assertNotNull("支付宝 >= ¥10 红包正则应匹配", redPacketFields)
        assertEquals("88.88", redPacketFields!!["amount"])
    }

    @Test
    fun autoBookkeeper_parseBusinessTimeHandlesMalformedAndLeapYearSafely() {
        val fallback = LocalDateTime.of(2024, 1, 15, 10, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(fallback, AutoBookkeeper.parseBusinessTimeMillis("02月29日 12:00", fallback))
        assertEquals(fallback, AutoBookkeeper.parseBusinessTimeMillis("2026年13月45日 99:99", fallback))
    }

    @Test
    fun refundLinker_deduplicatesCrossChannelRefundAfter60sWindow() = runBlocking {
        val now = 1_700_000_000_000L
        database.accountDao().insert(
            AccountEntity("acc-1", "零钱", "cash", "asset", 0L, 0L, "CNY", null, null, 0, true, now, now)
        )
        database.billDao().insert(
            BillEntity("bill-ref-1", 1_990L, "refund", now - 90_000L, "退款", null, "acc-1", null, "com.xunmeng.pinduoduo", "拼多多", null, "notification", null, null, now - 90_000L, now - 90_000L, 1)
        )
        val outcome = RefundLinker(database, nowMillis = { now }).processRefund(
            PaymentEvidenceInput(
                sourcePackage = "com.tencent.mm",
                amountMinor = 1_990L,
                occurredAt = now,
                merchant = "微信退款",
                orderId = null,
                paymentStatus = "REFUND",
                sourceEventKey = "wx-refund-90s-later",
                rawTextHash = "hash-90s"
            )
        )
        assertTrue("90 秒后第二通道同额退款不应重复入账", outcome is RefundLinker.RefundOutcome.Duplicate)
    }

    @Test
    fun refundNormalizer_doesNotParseCountAsAmount() {
        val parsed = PaymentResultNormalizer.normalizeRefund(
            sourcePackage = "com.tencent.mm",
            title = "微信支付退款通知",
            text = "退款1笔，退款金额¥19.90已退回至零钱",
            bigText = "",
            subText = "",
            summaryText = "",
            tickerText = "",
            postTime = 1_700_000_000_000L,
            notificationKey = "wx-ref-key",
            notificationId = 1
        )
        assertNotNull(parsed)
        assertEquals(1_990L, parsed!!.amountMinor)
    }

    @Test
    fun pageNodeDumper_omitsSensitivePasswordNodeText() {
        val sensitiveNode = object : PageNode {
            override val text: String = "123456"
            override val contentDescription: String = "支付密码"
            override val childCount: Int = 0
            override val isSensitive: Boolean = true
            override fun childAt(index: Int): PageNode? = null
        }
        val root = object : PageNode {
            override val text: String? = null
            override val contentDescription: String? = null
            override val childCount: Int = 2
            override fun childAt(index: Int): PageNode = if (index == 0) sensitiveNode else object : PageNode {
                override val text: String = "支付金额10.00元"
                override val contentDescription: String? = null
                override val childCount: Int = 0
                override fun childAt(index: Int): PageNode? = null
            }
        }
        val asset = File("src/main/assets/bookkeeping/bookkeeping_builtin_rules.json")
        val rule = PageRuleRegistry.fromJsonText(asset.readText())
            .rulesFor("com.alipay.android.msp.ui.views.MspContainerActivity")!!.first()
        val dump = PageNodeDumper.dump(root, rule)
        assertFalse("敏感密码节点文本不得进入 dump", dump.dump.contains("123456"))
        assertTrue(dump.dump.contains("支付金额10.00元"))
    }

    @Test
    fun jotBillLog_redactsPiiInReleaseModeWhileKeepingDiagnostics() {
        val raw = "解析到支付证据: 包名=com.tencent.mm, title=张三, orderId=4200001234567890, merchant=星巴克, amount=3500, 手机号13812345678"
        val redacted = JotBillLog.redactForRelease(raw)
        assertFalse(redacted.contains("张三"))
        assertFalse(redacted.contains("4200001234567890"))
        assertFalse(redacted.contains("星巴克"))
        assertFalse(redacted.contains("13812345678"))
        assertTrue("诊断结构与包名/金额应保留", redacted.contains("com.tencent.mm") && redacted.contains("amount=3500"))
    }

    @Test
    fun accountInference_resolveByHintsReturnsNeedsReviewForAmbiguousMultiCardBank() {
        val accounts = listOf(
            AccountEntity("cmb-debit", "招商银行储蓄卡", "bank_debit", "asset", 0L, 0L, "CNY", null, null, 0, true, 0L, 0L),
            AccountEntity("cmb-credit", "招商银行信用卡", "credit_card", "liability", 0L, 0L, "CNY", null, null, 1, true, 0L, 0L),
            AccountEntity("default-account-cash", "现金", "cash", "asset", 0L, 0L, "CNY", null, null, 2, true, 0L, 0L)
        )
        val result = AccountInference.resolveByHints("com.android.mms", listOf("招商银行"), accounts)
        assertEquals(AccountResolution.NeedsReview, result)
    }
}

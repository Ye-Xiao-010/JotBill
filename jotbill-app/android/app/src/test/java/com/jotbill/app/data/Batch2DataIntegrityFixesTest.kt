package com.jotbill.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.backup.BackupManager
import com.jotbill.app.budget.BudgetProgressCalculator
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.BudgetEntity
import com.jotbill.app.data.entity.CategoryEntity
import com.jotbill.app.data.entity.ImportBatchEntity
import com.jotbill.app.data.entity.RuleEntity
import com.jotbill.app.importing.AlipayBillAdapter
import com.jotbill.app.importing.WechatBillAdapter
import com.jotbill.app.rules.RuleEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Batch2DataIntegrityFixesTest {

    private lateinit var database: JotBillDatabase
    private lateinit var repository: LedgerRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, JotBillDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LedgerRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun backupRoundTrip_preservesCategoryParentIdRuleMetadataAndClearsImportBatches() = runBlocking {
        val now = 1_700_000_000_000L
        database.categoryDao().insert(
            CategoryEntity(
                id = "cat-parent", name = "餐饮", type = "expense",
                icon = null, color = null, isDefault = true, sortOrder = 1,
                createdAt = now, updatedAt = now, parentId = null
            )
        )
        database.categoryDao().insert(
            CategoryEntity(
                id = "cat-child", name = "外卖", type = "expense",
                icon = null, color = null, isDefault = true, sortOrder = 2,
                createdAt = now, updatedAt = now, parentId = "cat-parent"
            )
        )
        database.ruleDao().insert(
            RuleEntity(
                id = "rule-1", name = "外卖归类",
                condition = RuleEngine.CONDITION_MERCHANT_CONTAINS,
                conditionValue = "美团",
                action = RuleEngine.ACTION_SET_CATEGORY,
                actionValue = "cat-child",
                priority = 10, isActive = true,
                hitCount = 7, source = RuleEntity.SOURCE_FROM_CORRECTION,
                createdAt = now, updatedAt = now
            )
        )
        val manager = BackupManager(database)
        val backup = manager.createBackup(encrypt = false)

        // 备份之后发生了一次导入，随后恢复旧备份：import_batches 必须同步清空
        database.importBatchDao().insert(
            ImportBatchEntity(
                id = "batch-after-backup", fileHash = "sha256-after",
                source = "alipay", format = "csv", formatVersion = "v2",
                importerVersion = "1", status = ImportBatchEntity.STATUS_COMPLETED,
                createdAt = now, updatedAt = now
            )
        )

        val parsed = manager.parse(backup.bytes, "backup.jotbill")
        manager.executeRestore(parsed, "overwrite")

        val restoredChild = database.categoryDao().getById("cat-child")
        assertEquals("cat-parent", restoredChild?.parentId)

        val restoredRule = database.ruleDao().getById("rule-1")
        assertEquals(7, restoredRule?.hitCount)
        assertEquals(RuleEntity.SOURCE_FROM_CORRECTION, restoredRule?.source)

        assertNull(
            "覆盖恢复应清理 import_batches，避免残留 file_hash 阻断重导",
            database.importBatchDao().getById("batch-after-backup")
        )
    }

    @Test
    fun netWorth_treatsLiabilityOverpaymentAsAssetNotDebt() = runBlocking {
        val now = 1_700_000_000_000L
        database.accountDao().insert(
            AccountEntity(
                id = "acc-cash", name = "现金", subtype = "cash", kind = "asset",
                initialBalanceMinor = 100_000L, currentBalanceMinor = 100_000L,
                currency = "CNY", icon = null, color = null, sortOrder = 0,
                isActive = true, createdAt = now, updatedAt = now
            )
        )
        database.accountDao().insert(
            AccountEntity(
                id = "acc-cc-overpaid", name = "招行信用卡", subtype = "credit_card", kind = "liability",
                initialBalanceMinor = 0L, currentBalanceMinor = 10_000L, // 溢缴款 +100元
                currency = "CNY", icon = null, color = null, sortOrder = 1,
                isActive = true, createdAt = now, updatedAt = now
            )
        )
        database.accountDao().insert(
            AccountEntity(
                id = "acc-cc-debt", name = "建行信用卡", subtype = "credit_card", kind = "liability",
                initialBalanceMinor = -30_000L, currentBalanceMinor = -30_000L, // 欠款 300元
                currency = "CNY", icon = null, color = null, sortOrder = 2,
                isActive = true, createdAt = now, updatedAt = now
            )
        )

        val row = database.accountDao().getNetWorth()
        assertEquals(110_000L, row.totalAssets)
        assertEquals(30_000L, row.totalLiabilities)
        assertEquals(80_000L, row.totalAssets - row.totalLiabilities)
    }

    @Test
    fun categoryBudget_excludesCrossCategoryRefundsButIncludesOwnAndOriginalSubcategoryRefunds() = runBlocking {
        val now = 1_732_600_000_000L // 2024-11-26
        database.accountDao().insert(
            AccountEntity(
                id = "acc-1", name = "零钱", subtype = "cash", kind = "asset",
                initialBalanceMinor = 500_000L, currentBalanceMinor = 500_000L,
                currency = "CNY", icon = null, color = null, sortOrder = 0,
                isActive = true, createdAt = now, updatedAt = now
            )
        )
        database.categoryDao().insert(
            CategoryEntity("cat-food", "餐饮", "expense", null, null, true, 1, now, now, null)
        )
        database.categoryDao().insert(
            CategoryEntity("cat-takeout", "外卖", "expense", null, null, true, 2, now, now, "cat-food")
        )
        database.categoryDao().insert(
            CategoryEntity("cat-shop", "购物", "expense", null, null, true, 3, now, now, null)
        )
        database.categoryDao().insert(
            CategoryEntity("cat-refund-income", "退款", "income", null, null, true, 4, now, now, null)
        )

        // 餐饮(外卖子分类)支出 200 元 + 购物支出 500 元
        database.billDao().insert(
            BillEntity("b-food", 20_000L, "expense", now, null, "cat-takeout", "acc-1", null, null, "美团", null, "manual", null, null, now, now, 1)
        )
        database.billDao().insert(
            BillEntity("b-shop", 50_000L, "expense", now, null, "cat-shop", "acc-1", null, null, "淘宝", null, "manual", null, null, now, now, 1)
        )
        // 购物退款 500 元（挂在收入分类退款，关联原单 b-shop）
        database.billDao().insert(
            BillEntity("b-shop-refund", 50_000L, "refund", now, null, "cat-refund-income", "acc-1", null, null, "淘宝", null, "manual", "b-shop", null, now, now, 1)
        )
        // 外卖部分退款 30 元（挂在收入分类退款，关联原单 b-food）
        database.billDao().insert(
            BillEntity("b-food-refund", 3_000L, "refund", now, null, "cat-refund-income", "acc-1", null, null, "美团", null, "manual", "b-food", null, now, now, 1)
        )

        val foodBudget = BudgetEntity(
            id = "bg-food", name = "餐饮预算", amountMinor = 50_000L, period = "monthly",
            categoryId = "cat-food", alertAt = 80, isActive = true, createdAt = now, updatedAt = now
        )
        val totalBudget = BudgetEntity(
            id = "bg-total", name = "总预算", amountMinor = 100_000L, period = "monthly",
            categoryId = null, alertAt = 80, isActive = true, createdAt = now, updatedAt = now
        )

        val foodSnap = BudgetProgressCalculator.compute(database, foodBudget, now)
        assertEquals("餐饮预算毛支出应含子分类外卖", 20_000L, foodSnap.grossSpentMinor)
        assertEquals("餐饮预算应只扣减外卖退款，排除购物退款", 3_000L, foodSnap.refundMinor)
        assertEquals(17_000L, foodSnap.spentMinor)

        val totalSnap = BudgetProgressCalculator.compute(database, totalBudget, now)
        assertEquals(70_000L, totalSnap.grossSpentMinor)
        assertEquals("总预算仍扣减全部退款", 53_000L, totalSnap.refundMinor)
        assertEquals(17_000L, totalSnap.spentMinor)
    }

    @Test
    fun ruleEngine_preservesPatchesOnSendToReviewAndRejectsMalformedAmountRange() {
        val engine = RuleEngine(database)
        val rules = listOf(
            RuleEntity("r-review", "大额审核", RuleEngine.CONDITION_AMOUNT_RANGE, "1000-", RuleEngine.ACTION_SEND_TO_REVIEW, null, 1, true, 0, "manual", 1L, 1L),
            RuleEntity("r-cat", "星巴克分类", RuleEngine.CONDITION_MERCHANT_CONTAINS, "星巴克", RuleEngine.ACTION_SET_CATEGORY, "cat-coffee", 2, true, 0, "manual", 2L, 2L),
            RuleEntity("r-bad", "非法金额区间", RuleEngine.CONDITION_AMOUNT_RANGE, "10.00-50.00", RuleEngine.ACTION_IGNORE, null, 0, true, 0, "manual", 0L, 0L)
        )
        val decision = engine.evaluate(
            RuleEngine.Subject(amountMinor = 3_500L, merchant = "星巴克咖啡"),
            rules
        )
        assertTrue("非法浮点金额区间不得误触发 ignore，应命中 send_to_review", decision is RuleEngine.Decision.ToReview)
        assertEquals("send_to_review 时不得丢弃 set_category 补丁", "cat-coffee", decision.patch.categoryId)
        assertNull(RuleEngine.parseAmountRange("10.00-50.00"))
        assertNull(RuleEngine.parseAmountRange("abc-def"))
        assertNull(RuleEngine.parseAmountRange("-"))
        assertNull(RuleEngine.parseAmountRange("5000-1000"))
        assertNotNull(RuleEngine.parseAmountRange("1000-5000"))
    }

    @Test
    fun ledger_allowsEditingInactiveAccountBillAndEnforcesRefundCapOnCreate() = runBlocking {
        val now = 1_700_000_000_000L
        database.accountDao().insert(
            AccountEntity("acc-old", "已销卡", "credit_card", "liability", 0L, 0L, "CNY", null, null, 0, true, now, now)
        )
        val orig = BillEntity("b-orig", 10_000L, "expense", now, null, null, "acc-old", null, null, "商户A", null, "manual", null, "ORD-REF", now, now, 1)
        repository.createBillWithDedup(orig)

        // 手工创建超额退款必须被 RefundCapGuard 拦截
        var capBlocked = false
        try {
            repository.createBillWithDedup(
                BillEntity("b-over-refund", 12_000L, "refund", now + 1, null, null, "acc-old", null, null, "商户A", null, "manual", "b-orig", "ORD-REF", now + 1, now + 1, 1)
            )
        } catch (_: LedgerValidationException) {
            capBlocked = true
        }
        assertTrue("createBillWithDedup 必须校验 RefundCapGuard", capBlocked)

        // 停用旧账户后，修改该账户历史账单的备注/分类应允许（未更换账户）
        val oldAcc = database.accountDao().getById("acc-old")!!
        database.accountDao().update(oldAcc.copy(isActive = false))
        val updated = repository.updateBillWithBalanceUpdate(
            orig,
            orig.copy(description = "补充备注", version = orig.version + 1, updatedAt = now + 2)
        )
        assertTrue("停用账户的历史账单应允许修改非账户字段", updated)

        // 自动去重查询不得命中退款账单
        database.billDao().insert(
            BillEntity("b-refund-only", 5_000L, "refund", now + 3, null, null, "acc-old", null, null, "商户B", null, "notification", null, "ORD-ONLY-REFUND", now + 3, now + 3, 1)
        )
        assertNull(database.billDao().findByOrderIdForAutoMerge("ORD-ONLY-REFUND"))
    }

    @Test
    fun importAdapters_neverProduceImmutableAdjustmentBillsAndParseAlipayV2Columns() {
        val alipayFile = File.createTempFile("alipay-neutral", ".csv")
        alipayFile.writeText(
            "交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注\n" +
                "2026-08-29 17:42:23,投资理财,某基金公司,fund@a.com,基金定投,不计收支,500.00,招商银行储蓄卡(1234),交易成功,TD20260829999,MO999,\n"
        )
        val alipayRes = AlipayBillAdapter.parse(alipayFile, "UTF-8")
        assertEquals(1, alipayRes.rows.size)
        assertFalse("支付宝未识别中性流水不得落为 adjustment", alipayRes.rows[0].type == "adjustment")
        assertEquals("招商银行储蓄卡(1234)", alipayRes.rows[0].paymentMethod)

        val wechatFile = File.createTempFile("wechat-neutral", ".csv")
        wechatFile.writeText(
            "交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注\n" +
                "2026-08-29 18:00:00,亲属卡交易,家人,代付,/,120.00,零钱,支付成功,WX20260829999,,\n"
        )
        val wechatRes = WechatBillAdapter.parse(wechatFile, "UTF-8")
        assertEquals(1, wechatRes.rows.size)
        assertFalse("微信未识别中性流水不得落为 adjustment", wechatRes.rows[0].type == "adjustment")
    }
}

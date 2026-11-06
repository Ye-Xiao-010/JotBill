package com.jotbill.app.importing

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B1/B3 导入退款行守卫的回归测试（Robolectric + Room 内存库）。
 *
 * 锁定行为（复核修订后为**计数差值**口径）：
 * - B1 跨源判重：同 (原单, 金额) 键下，批次内第 1..baseline 行 SKIPPED，
 *   第 baseline+1 行起放行 —— 同额第二笔**真实**分次退款不丢（防吞单方向）；
 * - B3 累计上限：未判重但累计退款将超原实付时判 PENDING（转待确认，不丢行）；
 * - 跳过留痕：SKIPPED 带 skipReason，writeSkipAudit 落批次审计（行号/判据/脱敏样本）；
 * - 无原单（merchantOrderId 缺失或查无支出）时不拦截（维持既有阈值逻辑）；
 * - 非退款行不拦截。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImportRefundGuardTest {

    private lateinit var database: JotBillDatabase
    private lateinit var context: Application
    private lateinit var engine: ImportEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, JotBillDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        engine = ImportEngine(context, database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun account(id: String, name: String, balanceMinor: Long = 0L) = AccountEntity(
        id = id, name = name, subtype = "e_wallet", kind = "asset",
        initialBalanceMinor = balanceMinor, currentBalanceMinor = balanceMinor, currency = "CNY",
        icon = null, color = null, sortOrder = 0, isActive = true,
        createdAt = 1L, updatedAt = 1L
    )

    /** 原交易支出账单（orderId = 商户单号，供 findRefundOriginal 命中）。 */
    private fun originalExpense(id: String = "orig-1", orderId: String = "M-1", amountMinor: Long = 1_000L) =
        BillEntity(
            id = id, amountMinor = amountMinor, type = "expense",
            occurredAt = 1_000_000L, description = null, categoryId = null,
            accountId = null, targetAccountId = null, platform = "wechat",
            merchant = "拼多多", productDescription = null, sourceType = "manual",
            originalTransactionId = null, orderId = orderId,
            createdAt = 1L, updatedAt = 1L, version = 1
        )

    /** 已入账退款账单（模拟通知链路自动入账：originalTransactionId 锚定原单）。 */
    private fun refundBill(id: String, originalId: String = "orig-1", amountMinor: Long) =
        BillEntity(
            id = id, amountMinor = amountMinor, type = "refund",
            occurredAt = 1_100_000L, description = "退款", categoryId = null,
            accountId = null, targetAccountId = null, platform = "wechat",
            merchant = "拼多多", productDescription = null, sourceType = "notification",
            originalTransactionId = originalId, orderId = "PAY-1",
            createdAt = 1L, updatedAt = 1L, version = 1
        )

    private fun refundPending(id: String, originalId: String = "orig-1", amountMinor: Long) =
        PendingTransactionEntity(
            id = id, candidateId = null, sessionId = null,
            amountMinor = amountMinor, type = "refund",
            occurredAt = 1_100_000L, platform = "wechat", merchant = "拼多多",
            productDescription = null, orderId = "PAY-1", accountId = null,
            categoryId = null, conflictReason = "ORIGINAL_MISSING",
            sourceType = "notification", appliedRules = "{}",
            originalBillId = originalId, createdAt = 1L, version = 1
        )

    private fun refundRow(
        amountMinor: Long,
        merchantOrderId: String? = "M-1",
        transactionId: String = "tx-1",
        lineNumber: Int = 1
    ) = ParsedImportRow(
        occurredAt = 1_100_000L, amountMinor = amountMinor, type = "refund",
        merchant = "拼多多", productDescription = null, paymentMethod = "零钱",
        transactionId = transactionId, merchantOrderId = merchantOrderId,
        remark = null, rawStatus = "退款成功", sourceLineNumber = lineNumber
    )

    @Test
    fun duplicateRefundBillAlreadyCommittedIsSkipped() = runBlocking {
        database.billDao().insert(originalExpense())
        database.billDao().insert(refundBill("refund-1", amountMinor = 400L))
        val counter = ImportEngine.RefundSkipCounter()

        val decision = engine.checkRefundRowGuard(refundRow(amountMinor = 400L), counter)

        assertEquals("同原单同额退款已入账，批次第一行必须跳过", "skipped", decision?.kind)
        assertEquals(
            "跳过必须带留痕原因",
            "REFUND_DUPLICATE_COUNTED",
            decision?.skipReason
        )
    }

    @Test
    fun duplicateRefundPendingCardIsSkipped() = runBlocking {
        database.billDao().insert(originalExpense())
        database.pendingTransactionDao().insert(refundPending("pending-refund-1", amountMinor = 400L))
        val counter = ImportEngine.RefundSkipCounter()

        val decision = engine.checkRefundRowGuard(refundRow(amountMinor = 400L), counter)

        assertEquals("同原单同额退款已在待确认，导入行必须跳过", "skipped", decision?.kind)
    }

    /**
     * 计数差值核心场景①：同原单同额已有 1 笔退款，批次含 2 行同额 ——
     * 第一行 SKIPPED（抵掉既有那笔），第二行放行并真实落库（第二件商品的真退款不丢）。
     */
    @Test
    fun secondSameAmountRefundInBatchIsImported() = runBlocking {
        database.accountDao().insert(account("acc-wx", "零钱"))
        database.billDao().insert(originalExpense())
        database.billDao().insert(refundBill("refund-1", amountMinor = 400L))
        val counter = ImportEngine.RefundSkipCounter()
        val row1 = refundRow(amountMinor = 400L, transactionId = "tx-a", lineNumber = 10)
        val row2 = refundRow(amountMinor = 400L, transactionId = "tx-b", lineNumber = 11)

        val first = engine.checkRefundRowGuard(row1, counter)
        val second = engine.checkRefundRowGuard(row2, counter)

        assertEquals("第一行抵掉既有退款，判重跳过", "skipped", first?.kind)
        assertNull("第二行是同额的真实分次退款，必须放行", second)

        // 放行行走真实写账路径：账单数 +1、账户余额 +400
        engine.writeImported(row2, WechatBillAdapter, database.accountDao().getAll(), "batch-test")
        assertEquals("原单 + 旧退款 + 新退款 = 3 条账单", 3, database.billDao().count())
        val imported = database.billDao().getById("import:tx-b")
        assertEquals("放行行按 refund 落库", "refund", imported!!.type)
        assertEquals(400L, imported.amountMinor)
        assertEquals(
            "放行行加回账户余额",
            400L,
            database.accountDao().getById("acc-wx")!!.currentBalanceMinor
        )
    }

    /** 计数差值核心场景②：批次只含 1 行同额 → SKIPPED，不落库。 */
    @Test
    fun singleDuplicateRowInBatchIsSkippedOnly() = runBlocking {
        database.billDao().insert(originalExpense())
        database.billDao().insert(refundBill("refund-1", amountMinor = 400L))
        val counter = ImportEngine.RefundSkipCounter()

        val decision = engine.checkRefundRowGuard(refundRow(amountMinor = 400L), counter)

        assertEquals("skipped", decision?.kind)
        assertEquals("判重行不落库", 2, database.billDao().count())
    }

    @Test
    fun differentAmountRefundIsNotDuplicate() = runBlocking {
        database.billDao().insert(originalExpense())
        database.billDao().insert(refundBill("refund-1", amountMinor = 400L))
        val counter = ImportEngine.RefundSkipCounter()

        // 部分退款第二笔（不同金额）：不判重，且 400 + 300 <= 1000 不超额
        val decision = engine.checkRefundRowGuard(refundRow(amountMinor = 300L), counter)

        assertNull("不同金额的部分退款不是重复，应放行", decision)
    }

    @Test
    fun cumulativeRefundExceedingOriginalGoesPending() = runBlocking {
        database.billDao().insert(originalExpense())
        database.billDao().insert(refundBill("refund-1", amountMinor = 600L))
        val counter = ImportEngine.RefundSkipCounter()

        // 600 + 500 > 1000：未判重（金额不同）但累计超额
        val decision = engine.checkRefundRowGuard(refundRow(amountMinor = 500L), counter)

        assertEquals("累计退款超原实付必须转待确认", "pending", decision?.kind)
    }

    @Test
    fun refundRowWithoutOriginalIsNotIntercepted() = runBlocking {
        val counter = ImportEngine.RefundSkipCounter()
        // 无 merchantOrderId：无法锚定原单，守卫不拦截（既有阈值逻辑接管）
        assertNull(engine.checkRefundRowGuard(refundRow(amountMinor = 400L, merchantOrderId = null), counter))

        database.billDao().insert(originalExpense(orderId = "M-9"))
        // merchantOrderId 查无对应支出：同样不拦截
        assertNull(engine.checkRefundRowGuard(refundRow(amountMinor = 400L, merchantOrderId = "M-404"), counter))
    }

    @Test
    fun nonRefundRowIsNotIntercepted() = runBlocking {
        val row = refundRow(amountMinor = 400L).copy(type = "expense")
        assertNull(engine.checkRefundRowGuard(row, ImportEngine.RefundSkipCounter()))
    }

    @Test
    fun skippedRowLeavesAuditTrace() = runBlocking {
        val row = refundRow(amountMinor = 400L, transactionId = "tx-skip", lineNumber = 42)

        engine.writeSkipAudit(row, "batch-1", "REFUND_DUPLICATE_COUNTED")
        // insertIfAbsent 幂等：同一行重复写不产生第二条
        engine.writeSkipAudit(row, "batch-1", "REFUND_DUPLICATE_COUNTED")

        val audits = database.auditEntryDao().getByEntity("import_skip", "tx-skip")
        assertEquals("同一跳过行审计幂等", 1, audits.size)
        val payload = JSONObject(audits.single().newValue!!)
        assertEquals("REFUND_DUPLICATE_COUNTED", payload.getString("reason"))
        assertEquals(42, payload.getInt("lineNumber"))
        assertEquals(400L, payload.getLong("amountMinor"))
        assertEquals("M-1", payload.getString("merchantOrderId"))
        assertTrue("样本字段必须存在（maskSample 对纯中文不脱码）", payload.has("sample"))
        assertEquals("审计归属批次", "batch-1", audits.single().operator)
    }
}

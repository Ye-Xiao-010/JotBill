package com.jotbill.app.backup

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.BillEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 备份往返保留字段锁与导入记录标识的回归测试。
 *
 * 审查发现缺陷：BackupManager.readSnapshot 的 bills 快照未导出 field_locks 与
 * import_record_id，insertBills 也未恢复——恢复后用户字段锁静默丢失（低优先级
 * 来源可覆盖用户保护），导入去重键丢失（checkLevel3 失效，可能重复导入）。
 *
 * 本测试锁定修复后的行为：createBackup → parse → executeRestore 往返后，
 * 账单的 fieldLocks 与 importRecordId 被完整保留。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupRoundTripTest {

    private lateinit var database: JotBillDatabase
    private lateinit var manager: BackupManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, JotBillDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        manager = BackupManager(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun insertAccountAndBill() = runBlocking {
        database.accountDao().insert(
            AccountEntity(
                id = "acc-1", name = "现金", subtype = "cash", kind = "asset",
                initialBalanceMinor = 0L, currentBalanceMinor = 1000L, currency = "CNY",
                icon = null, color = null, sortOrder = 0, isActive = true,
                createdAt = 1L, updatedAt = 1L
            )
        )
        database.billDao().insert(
            BillEntity(
                id = "bill-1", amountMinor = 500L, type = "expense",
                occurredAt = 1_000_000L, description = null, categoryId = null,
                accountId = "acc-1", targetAccountId = null, platform = "微信支付",
                merchant = "KFC", productDescription = null, sourceType = "import",
                originalTransactionId = null, orderId = "order-1",
                createdAt = 1L, updatedAt = 1L, version = 1,
                fieldLocks = """["platform","merchant"]""",
                importRecordId = "import-record-1"
            )
        )
    }

    @Test
    fun roundTripPreservesFieldLocksAndImportRecordId() = runBlocking {
        insertAccountAndBill()

        // 1. 创建备份
        val backup = manager.createBackup(encrypt = false)

        // 2. 解析备份（校验通过）
        val parsed = manager.parse(backup.bytes, "test.jotbill")
        assertEquals("backup_created_at 校验", backup.createdAt, parsed.createdAt)

        // 3. 覆盖恢复
        manager.executeRestore(parsed, "overwrite")

        // 4. 断言字段保留
        val restored = database.billDao().getById("bill-1")
        assertNotNull("账单应恢复", restored)
        assertEquals("field_locks 应保留", """["platform","merchant"]""", restored!!.fieldLocks)
        assertEquals("import_record_id 应保留", "import-record-1", restored.importRecordId)
    }
}

package com.jotbill.app.data

import com.jotbill.app.data.entity.BillEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段七 T1：本地 JVM 不再依赖 Room 注解反射。
 * Room 注解、外键和表结构验证移交 T2 的 room-testing/androidTest。
 */
class BillEntityContractTest {
    private fun sampleBill() = BillEntity(
        id = "bill-1",
        amountMinor = 100L,
        type = "expense",
        occurredAt = 1L,
        description = null,
        categoryId = null,
        accountId = null,
        targetAccountId = null,
        platform = null,
        merchant = null,
        productDescription = null,
        sourceType = "manual",
        originalTransactionId = null,
        orderId = null,
        createdAt = 1L,
        updatedAt = 1L,
        version = 1
    )

    @Test
    fun phase5FieldLocksDefaultToEmptyArray() {
        assertEquals("[]", sampleBill().fieldLocks)
    }

    @Test
    fun phase6ImportRecordIdDefaultsToNullAndIsPreserved() {
        assertNull(sampleBill().importRecordId)
        assertEquals("import-record-1", sampleBill().copy(importRecordId = "import-record-1").importRecordId)
    }

    @Test
    fun databaseMigrationChainCoversPhase6Step() {
        assertEquals(14, JOT_BILL_DATABASE_VERSION)
        assertTrue(DatabaseMigrations.all.any { it.startVersion == 9 && it.endVersion == 10 })
        assertTrue(DatabaseMigrations.all.any { it.startVersion == 10 && it.endVersion == 11 })
        // 批 6 子分类:categories.parent_id 轻量迁移
        assertTrue(DatabaseMigrations.all.any { it.startVersion == 11 && it.endVersion == 12 })
        // 2026-11-17:accounts.card_tail 卡尾号匹配
        assertTrue(DatabaseMigrations.all.any { it.startVersion == 12 && it.endVersion == 13 })
        // 2026-11-21:budgets 自定义周期字段
        assertTrue(DatabaseMigrations.all.any { it.startVersion == 13 && it.endVersion == 14 })
    }
}

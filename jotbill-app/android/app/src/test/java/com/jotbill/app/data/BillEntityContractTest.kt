package com.jotbill.app.data

import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.CategoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BillEntityContractTest {
    @Test
    fun billForeignKeysUseRestrictForAllAccountAndCategoryReferences() {
        val entity = BillEntity::class.java.getAnnotation(Entity::class.java)
        val foreignKeys = entity.foreignKeys.toList()

        assertEquals(3, foreignKeys.size)
        assertTrue(foreignKeys.all { it.onDelete == ForeignKey.RESTRICT })
        assertEquals(
            setOf("category_id", "account_id", "target_account_id"),
            foreignKeys.flatMap { it.childColumns.toList() }.toSet()
        )
        assertEquals(
            CategoryEntity::class,
            foreignKeys.single { it.childColumns.contentEquals(arrayOf("category_id")) }.entity
        )
        assertEquals(
            setOf(AccountEntity::class),
            foreignKeys
                .filter { it.childColumns.single() != "category_id" }
                .map { it.entity }
                .toSet()
        )
    }

    @Test
    fun foreignKeyChangeHasDatabaseMigration() {
        val database = JotBillDatabase::class.java.getAnnotation(Database::class.java)

        assertEquals(4, database.version)
        assertTrue(DatabaseMigrations.all.any { it.startVersion == 3 && it.endVersion == 4 })
    }
}

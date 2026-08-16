package com.jotbill.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jotbill.app.data.entity.ImportBatchEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportBatchDaoTest {
    private lateinit var database: JotBillDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, JotBillDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun fileHashUniqueIndexPreventsDuplicateBatch() = runBlocking {
        val batch = ImportBatchEntity(
            id = "batch-1",
            fileHash = "hash-1",
            source = "wechat",
            format = "csv",
            formatVersion = "wechat-csv-v1",
            importerVersion = ImportBatchEntity.IMPORTER_VERSION,
            status = ImportBatchEntity.STATUS_COMPLETED,
            importedCount = 1,
            createdAt = 1L,
            updatedAt = 1L,
            completedAt = 2L
        )
        database.importBatchDao().insert(batch)
        val duplicate = batch.copy(id = "batch-2")
        var duplicateRejected = false
        try {
            database.importBatchDao().insert(duplicate)
        } catch (_: Exception) {
            duplicateRejected = true
        }
        assertTrue(duplicateRejected)
        assertNotNull(database.importBatchDao().findByFileHash("hash-1"))
        assertEquals("batch-1", database.importBatchDao().getLatestCompleted()?.id)
    }

    @Test
    fun finishMovesRunningBatchToTerminalCounts() = runBlocking {
        val batch = ImportBatchEntity(
            id = "batch-2",
            fileHash = "hash-2",
            source = "alipay",
            format = "xlsx",
            formatVersion = "alipay-bill-v1",
            importerVersion = ImportBatchEntity.IMPORTER_VERSION,
            status = ImportBatchEntity.STATUS_RUNNING,
            createdAt = 1L,
            updatedAt = 1L
        )
        database.importBatchDao().insert(batch)
        val updated = database.importBatchDao().finish(
            id = "batch-2",
            status = ImportBatchEntity.STATUS_COMPLETED,
            importedCount = 5,
            mergedCount = 1,
            skippedCount = 2,
            pendingCount = 0,
            failedCount = 0,
            suspiciousCount = 0,
            accountUnmatchedCount = 1,
            updatedAt = 3L,
            completedAt = 3L
        )
        assertEquals(1, updated)
        assertEquals(5, database.importBatchDao().getById("batch-2")?.importedCount)
    }
}

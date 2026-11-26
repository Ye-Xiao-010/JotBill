package com.jotbill.app.stability

import android.content.pm.PackageManager
import android.os.Build
import com.jotbill.app.data.JotBillDatabaseProvider
import com.jotbill.app.recognition.PaymentResultDedupMemory
import com.jotbill.app.utils.InstallApkModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class Batch1StabilityFixesTest {

    @Test
    fun installApkSigningFlags_usesSigningCertificatesOnApi28Plus() {
        assertEquals(
            PackageManager.GET_SIGNING_CERTIFICATES,
            InstallApkModule.signingFlags(Build.VERSION_CODES.P)
        )
        assertEquals(
            PackageManager.GET_SIGNING_CERTIFICATES,
            InstallApkModule.signingFlags(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        )
        @Suppress("DEPRECATION")
        assertEquals(
            PackageManager.GET_SIGNATURES,
            InstallApkModule.signingFlags(Build.VERSION_CODES.O)
        )
    }

    @Test
    fun paymentResultDedupMemory_shortTtlDoesNotEvictLongPageObservationTtl() {
        val memory = PaymentResultDedupMemory()
        val t0 = 1_000_000L
        assertTrue(memory.mark("page-observation|wx|1990|hash1", t0, 300_000L))
        // 30 秒后来了一条 15 秒 TTL 的普通事件：不能误删 5 分钟页面重观测记录
        assertTrue(memory.mark("event-key-1", t0 + 30_000L, 15_000L))
        // 65 秒后再次观测同一结果页：仍在 5 分钟 TTL 内，应被去重跳过
        assertFalse(memory.mark("page-observation|wx|1990|hash1", t0 + 65_000L, 300_000L))
        // 301 秒后过期：允许重新记录
        assertTrue(memory.mark("page-observation|wx|1990|hash1", t0 + 300_001L, 300_000L))
    }

    @Test
    fun paymentResultDedupMemory_isThreadSafeUnderConcurrentAccess() {
        val memory = PaymentResultDedupMemory()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 8).map { worker ->
                Callable {
                    var accepted = 0
                    for (i in 0 until 500) {
                        val ttl = if (worker % 2 == 0) 15_000L else 300_000L
                        if (memory.mark("k-${worker % 2}-$i", 1_000_000L + i * 50L, ttl)) {
                            accepted++
                        }
                    }
                    accepted
                }
            }
            val results = executor.invokeAll(tasks).map { it.get() }
            assertTrue(results.sum() > 0)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun databaseProvider_detectsSqlCipherKeyMismatchErrors() {
        assertTrue(
            JotBillDatabaseProvider.isCipherOpenFailure(
                RuntimeException("wrapper", IllegalStateException("file is not a database: , while compiling: select count(*) from sqlite_master;"))
            )
        )
        assertTrue(
            JotBillDatabaseProvider.isCipherOpenFailure(
                IllegalStateException("HMAC check failed for page 1")
            )
        )
        assertFalse(
            JotBillDatabaseProvider.isCipherOpenFailure(
                IllegalStateException("Migration didn't properly handle bills")
            )
        )
    }
}

package com.jotbill.app.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldPriorityResolverTest {
    @Test
    fun importRankIsHigherThanEvidenceRank() {
        val current = mapOf(
            "platform" to FieldPriorityResolver.Resolved("旧平台", FieldPriorityResolver.Source.EVIDENCE),
            "merchant" to FieldPriorityResolver.Resolved(null, null)
        )
        val result = FieldPriorityResolver.resolve(
            current = current,
            candidates = listOf(
                FieldPriorityResolver.Assignment("platform", "微信支付", FieldPriorityResolver.Source.IMPORT),
                FieldPriorityResolver.Assignment("merchant", "测试商户", FieldPriorityResolver.Source.IMPORT)
            ),
            locks = emptySet()
        )
        assertEquals("微信支付", result.values["platform"])
        assertEquals("测试商户", result.values["merchant"])
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun userLockRejectsImportCandidate() {
        val current = mapOf(
            "platform" to FieldPriorityResolver.Resolved("用户锁定平台", FieldPriorityResolver.Source.USER_LOCK)
        )
        val result = FieldPriorityResolver.resolve(
            current = current,
            candidates = listOf(
                FieldPriorityResolver.Assignment("platform", "微信支付", FieldPriorityResolver.Source.IMPORT)
            ),
            locks = setOf("platform")
        )
        assertEquals("用户锁定平台", result.values["platform"])
        assertEquals(1, result.conflicts.size)
        assertTrue(result.conflicts.single().locked)
    }

    @Test
    fun parseLocksIgnoresInvalidJsonAndUnknownFieldsAreReported() {
        assertTrue(FieldPriorityResolver.parseLocks(null).isEmpty())
        assertTrue(FieldPriorityResolver.parseLocks("not-json").isEmpty())
        assertEquals(setOf("unknown"), FieldPriorityResolver.invalidLockFields(listOf("platform", "unknown")))
        assertFalse(FieldPriorityResolver.invalidLockFields(listOf("platform", "merchant")).isNotEmpty())
    }
}

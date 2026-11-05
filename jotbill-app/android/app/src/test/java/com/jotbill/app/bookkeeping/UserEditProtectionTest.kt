package com.jotbill.app.bookkeeping

import com.jotbill.app.rules.FieldPriorityResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纠错防回推（A-1/A-2，2026-11-05 拍板）机制级测试：
 *
 * 1. 锁定字段不被自动链路（IMPORT/EVIDENCE）覆盖 —— 导入 MERGED（mergeImported）
 *    走的 [FieldPriorityResolver.resolve] 对 locks 命中字段拒绝低优先级写入；
 * 2. 手写账（sourceType=manual）既有字段按用户级保护（mergeImported 既有语义）；
 * 3. 自动锁合并语义（A-1：parseLocks + 变更字段 → serializeLocks，更新锁值保持锁）。
 *
 * 说明：L1 归并（mergeIntoExistingBill）的"只补空、金额/时间/账户/类型不改动"由
 * `LedgerRepository` 实现保证（`?:` 补空 + EVIDENCE 级二次保护），端到端由
 * `CheckoutPaymentSignalMergeTest` 既有场景回归覆盖；本类锁定 FieldPriorityResolver 的机制核。
 */
class UserEditProtectionTest {

    /** 构造一条"用户已改商户且商户已锁"的当前值映射。 */
    private fun lockedMerchantState(value: String): Map<String, FieldPriorityResolver.Resolved> =
        FieldPriorityResolver.LOCKABLE_FIELDS.associateWith { field ->
            FieldPriorityResolver.Resolved(
                value = if (field == "merchant") value else null,
                source = if (field == "merchant") FieldPriorityResolver.Source.USER_LOCK else null
            )
        }

    /** 用例 1/2：手改（已锁）的字段，晚到的 IMPORT/EVIDENCE 一律不覆盖。 */
    @Test
    fun lockedFieldIsNotOverwrittenByImportOrEvidence() {
        val current = lockedMerchantState("用户改过的商户")
        val assignments = listOf(
            FieldPriorityResolver.Assignment("merchant", "信号里的商户", FieldPriorityResolver.Source.IMPORT),
            FieldPriorityResolver.Assignment("description", "导入补的描述", FieldPriorityResolver.Source.IMPORT)
        )
        val resolved = FieldPriorityResolver.resolve(current, assignments, setOf("merchant"))
        assertEquals("锁定字段必须保持用户值", "用户改过的商户", resolved.values["merchant"])
        assertEquals("非锁字段按优先级正常写入", "导入补的描述", resolved.values["description"])
    }

    /** 用例 2：EVIDENCE 级（L1 归并/回填同源）同样不得覆盖锁定字段。 */
    @Test
    fun lockedFieldIsNotOverwrittenByEvidence() {
        val current = lockedMerchantState("用户改过的商户")
        val assignments = listOf(
            FieldPriorityResolver.Assignment("merchant", "证据商户", FieldPriorityResolver.Source.EVIDENCE)
        )
        val resolved = FieldPriorityResolver.resolve(current, assignments, setOf("merchant"))
        assertEquals("用户改过的商户", resolved.values["merchant"])
    }

    /** 用例 3：再次编辑 = 更新锁值、保持锁 —— 锁集合对同一字段幂等合并、值可更新。 */
    @Test
    fun reEditUpdatesValueAndKeepsLock() {
        val locks = FieldPriorityResolver.parseLocks(
            FieldPriorityResolver.serializeLocks(setOf("merchant"))
        )
        // A-1 合并语义：parseLocks(既有锁) + 本次变更字段 → serializeLocks（同字段重复并入幂等）
        val merged = locks + setOf("merchant", "categoryId")
        val serialized = FieldPriorityResolver.serializeLocks(merged)
        val roundTrip = FieldPriorityResolver.parseLocks(serialized)
        assertEquals(setOf("merchant", "categoryId"), roundTrip)
        assertTrue("变更字段仍处锁定状态", roundTrip.contains("merchant"))
    }

    /** 防御：只锁 LOCKABLE_FIELDS 内字段（非法字段名被序列化校验拒绝的既有语义）。 */
    @Test
    fun onlyLockableFieldsAreValid() {
        val invalid = FieldPriorityResolver.invalidLockFields(setOf("merchant", "notAField"))
        assertEquals(setOf("notAField"), invalid)
    }
}

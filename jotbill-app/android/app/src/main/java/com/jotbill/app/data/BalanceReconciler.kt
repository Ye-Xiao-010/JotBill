package com.jotbill.app.data

import com.jotbill.app.data.entity.AuditEntryEntity
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * B4：余额一致性抽检（**只读判定，绝不改数**）。
 *
 * 不变式口径：current_balance = initial_balance + Σ(账单 delta) + Σ(校准 diff)。
 *
 * 关键设计（拍板口径）：**校准差额必须纳入口径**，而不是"排除校准过的账户"。
 * `LedgerRepository.calibrateAccountBalance` 直接设余额并写一笔 delta=0 的
 * adjustment 账单 —— 若口径不含校准差额，校准过的账户每次启动都误报，
 * 用户会习惯性无视提示（等于没做）；若直接排除校准账户，校准后发生的真实漂移
 * 又彻底无人看。纳入 Σdiff_minor 后：校准本身不误报，校准后的新漂移仍会报。
 *
 * Σ(账单 delta) 的方向与 `BalanceCalculator.delta` 逐字对齐（见
 * `BillDao.sumNetDeltaByAccount` 的 CASE 分支），转账/还款的目标侧恒 +amount
 * 由 `BillDao.sumTransferTargetDeltaByAccount` 补齐。
 *
 * 产出两个通道：
 * - [auditMismatches]：启动后台调用，命中写审计（按日幂等，不刷屏），不打扰用户；
 * - [check]：桥接层 `DataModule.getBalanceReconcileWarnings` 实时重算，
 *   RN 账户页据此展示提示并指向现成的「校准余额」入口。
 */
internal class BalanceReconciler(private val database: JotBillDatabase) {

    /**
     * 一个账户的不一致快照。
     *
     * @param expectedMinor 按不变式算出的应有余额
     * @param actualMinor 账户当前存储余额
     * @param diffMinor actual - expected（正=账面多于应有，负=少于）
     */
    data class Mismatch(
        val accountId: String,
        val expectedMinor: Long,
        val actualMinor: Long,
        val diffMinor: Long
    )

    /** 全量账户（含已停用）逐一对账，返回不一致清单；一致时为空列表。 */
    suspend fun check(): List<Mismatch> {
        val sourceDeltas = database.billDao().sumNetDeltaByAccount()
            .associate { it.accountId to it.delta }
        val targetDeltas = database.billDao().sumTransferTargetDeltaByAccount()
            .associate { it.accountId to it.delta }
        val calibrationDiffs = database.balanceCalibrationDao().sumDiffByAccount()
            .associate { it.accountId to it.delta }
        return database.accountDao().getAll().mapNotNull { account ->
            val expected = account.initialBalanceMinor +
                (sourceDeltas[account.id] ?: 0L) +
                (targetDeltas[account.id] ?: 0L) +
                (calibrationDiffs[account.id] ?: 0L)
            if (expected == account.currentBalanceMinor) {
                null
            } else {
                Mismatch(
                    accountId = account.id,
                    expectedMinor = expected,
                    actualMinor = account.currentBalanceMinor,
                    diffMinor = account.currentBalanceMinor - expected
                )
            }
        }
    }

    /**
     * 启动抽检：不一致逐账户写审计（entityType=balance_reconcile），按"日"幂等 ——
     * 同一天重复启动只留一条，防止审计表被启动刷屏。返回不一致清单供日志使用。
     */
    suspend fun auditMismatches(): List<Mismatch> {
        val mismatches = check()
        if (mismatches.isEmpty()) return mismatches
        val now = System.currentTimeMillis()
        val dayBucket = LocalDate.ofInstant(
            java.time.Instant.ofEpochMilli(now),
            ZoneId.systemDefault()
        ).toString()
        mismatches.forEach { mismatch ->
            database.auditEntryDao().insertIfAbsent(
                AuditEntryEntity(
                    id = "balance-reconcile:${mismatch.accountId}:$dayBucket",
                    entityType = "balance_reconcile",
                    entityId = mismatch.accountId,
                    action = "mismatch",
                    fieldName = "current_balance_minor",
                    oldValue = mismatch.expectedMinor.toString(),
                    newValue = JSONObject().apply {
                        put("accountId", mismatch.accountId)
                        put("expectedMinor", mismatch.expectedMinor)
                        put("actualMinor", mismatch.actualMinor)
                        put("diffMinor", mismatch.diffMinor)
                    }.toString(),
                    operator = "balance_reconciler",
                    createdAt = now
                )
            )
        }
        return mismatches
    }
}

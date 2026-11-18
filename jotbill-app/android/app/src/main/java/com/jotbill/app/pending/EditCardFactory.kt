package com.jotbill.app.pending

import android.content.Context
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.BillEntity
import com.jotbill.app.data.entity.PendingTransactionEntity
import com.jotbill.app.overlay.PendingConfirmOverlayManager
import com.jotbill.app.utils.JotBillLog
import org.json.JSONObject

/**
 * 「已入账编辑卡」工厂（2026-11-16 记账模型调整）。
 *
 * 任何通道（无障碍结果页/支付通知/短信/规则）首笔入账后都调用本工厂挂一张
 * 编辑卡，驱动既有付款确认弹窗调整账户/分类；确认走 [PendingConfirmService]
 * 的编辑分支（更新账单，不新建）。提醒器与待确认中心均忽略编辑卡。
 */
internal object EditCardFactory {

    private const val TAG = "EditCardFactory"

    suspend fun create(context: Context, database: JotBillDatabase, bill: BillEntity) {
        if (bill.type != "expense") return
        if (!PendingConfirmOverlayManager.isEnabled(context)) return
        val card = PendingTransactionEntity(
            id = "pending-edit-${bill.id}",
            candidateId = null,
            sessionId = null,
            amountMinor = bill.amountMinor,
            type = bill.type,
            occurredAt = bill.occurredAt,
            platform = bill.platform,
            merchant = bill.merchant,
            productDescription = null,
            orderId = bill.orderId,
            accountId = bill.accountId,
            categoryId = bill.categoryId,
            conflictReason = null,
            sourceType = bill.sourceType,
            appliedRules = JSONObject().apply {
                put(EditCardMarker.BILL_KEY, bill.id)
                put("decision", EditCardMarker.DECISION)
            }.toString(),
            createdAt = System.currentTimeMillis(),
            version = 1
        )
        val inserted = try {
            database.pendingTransactionDao().insertIfAbsent(card)
        } catch (error: RuntimeException) {
            JotBillLog.w(TAG, "编辑卡创建失败: bill=${bill.id.take(8)}", error)
            return
        }
        if (inserted == -1L) return
        PendingChangeHub.emit(
            PendingChangeInfo(
                pendingId = card.id,
                type = card.type,
                amountMinor = card.amountMinor,
                sourceType = card.sourceType,
                conflictReason = null,
                occurredAt = card.occurredAt,
                isNew = true,
                isEditCard = true
            )
        )
        JotBillLog.i(TAG, "已入账编辑卡已创建: bill=${bill.id.take(8)}")
    }
}

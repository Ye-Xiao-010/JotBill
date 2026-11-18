package com.jotbill.app.pending

import org.json.JSONObject

/**
 * 「已入账编辑卡」标记（2026-11-16 记账模型调整）。
 *
 * 首笔信号直接生成账单后，弹窗语义从「待确认入账」变为「已入账，可修改账户/分类」。
 * 编辑卡仍复用 pending 卡片的弹窗管线（悬浮窗 UI、账号/分类选择、成功反馈），
 * 但以 `appliedRules.editBillId` 关联真实账单：
 * - 确认 = 更新账单的账户/分类（余额随账户迁移），不新建账单；
 * - 提醒与待确认中心列表必须忽略编辑卡（账单已在账本里，不是"待确认"）；
 * - 启动期清理残留编辑卡（弹窗未展示/超时后遗留）。
 */
internal object EditCardMarker {

    const val BILL_KEY = "editBillId"

    const val DECISION = "EDIT_BILL"

    fun billId(appliedRules: String?): String? {
        if (appliedRules.isNullOrBlank()) return null
        return try {
            JSONObject(appliedRules)
                .optString(BILL_KEY, "")
                .takeIf { it.isNotBlank() }
        } catch (_: RuntimeException) {
            null
        }
    }

    fun isEditCard(appliedRules: String?): Boolean = billId(appliedRules) != null
}

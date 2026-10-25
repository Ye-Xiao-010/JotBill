package com.jotbill.app.bookkeeping

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jotbill.app.data.JotBillDatabaseProvider
import com.jotbill.app.data.LedgerRepository
import com.jotbill.app.utils.JotBillLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 通知栏「撤销」动作：删除该笔自动账单并回滚余额联动。
 * goAsync + 独立 IO 作用域，保证广播返回后落库仍能完成。
 */
class AutoBookkeepingUndoReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_UNDO) {
            return
        }
        val billId = intent.getStringExtra(EXTRA_BILL_ID)
        if (billId.isNullOrBlank()) {
            JotBillLog.w(TAG, "撤销请求缺少账单 id，忽略")
            return
        }
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val database = JotBillDatabaseProvider.get(appContext)
                val bill = database.billDao().getById(billId)
                if (bill == null) {
                    JotBillLog.i(TAG, "撤销目标账单不存在(可能已删除): ${billId.take(8)}")
                } else {
                    val deleted = LedgerRepository(database)
                        .deleteBillWithBalanceUpdate(bill)
                    JotBillLog.i(
                        TAG,
                        "自动记账撤销${if (deleted) "成功" else "失败"}: billId=${billId.take(8)}"
                    )
                }
                (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                    ?.cancel(billId.hashCode())
            } catch (error: Exception) {
                JotBillLog.w(TAG, "自动记账撤销异常", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AutoBookkeepingUndoReceiver"

        const val ACTION_UNDO = "com.jotbill.app.bookkeeping.action.UNDO_AUTO_BOOK"

        const val EXTRA_BILL_ID = "billId"
    }
}

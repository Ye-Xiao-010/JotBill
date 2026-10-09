/** 本地能力服务统一导出。 */
// paymentNotification 已随精简版移除：支付通知自动记账是第 4 条记账入口，
// 依赖已删除的 PaymentNotificationService / PaymentResultNormalizer。
// 待确认提醒与预算提醒改走 nativePendingReminder / nativeBudgetReminder，
// 与通知监听无关，保留。
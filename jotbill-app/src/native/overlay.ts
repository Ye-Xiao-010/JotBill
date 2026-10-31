import { NativeModules } from 'react-native';

interface RawOverlayModule {
  setPendingConfirmPopupEnabled(enabled: boolean): Promise<void>;
  getPendingConfirmPopupEnabled(): Promise<boolean>;
}

function getRawOverlayModule(): RawOverlayModule {
  const module = NativeModules.Overlay as RawOverlayModule | undefined;
  if (!module) {
    throw new Error('Native Overlay module is unavailable on this platform');
  }
  return module;
}

/**
 * 2026-10-12 精简：截图识别链路已整链删除，本文件随之只剩"付款确认弹窗开关"一个出口。
 *
 * 删除的（连同原生侧实现，见 PendingConfirmOverlayManager 同分支提交）：
 *   · `getOverlayPermissionStatus` / `requestOverlayPermission` —— 手动截图悬浮按钮的
 *     权限读写。**注意别与 PaymentNotificationModule 的同名方法混淆**：后者服务的是
 *     通知悬浮窗，2026-10-31 起由权限与提醒页（GeneralSettingsScreen）经
 *     `paymentNotificationService` 调用，保留未动。
 *   · `setManualCaptureButtonEnabled` / `setManualCaptureButtonMode` /
 *     `getManualCaptureButtonMode` / `getManualCaptureButtonState` —— 悬浮按钮三件套。
 *   · `requestManualCapture` —— 手动触发结算页截图。
 *   · `setCheckoutCaptureEnabled` / `getCheckoutCaptureEnabled` —— 识别链路开关本身。
 *     原设置页那张「🛒 结算页识别」卡片已整块移除，开关不再有入口。
 *   · `addCheckoutCaptureOverlayStateListener` + `CHECKOUT_CAPTURE_OVERLAY_STATE_EVENT`
 *     —— 状态事件。本文件曾是本模块唯一的 `NativeEventEmitter` 使用点，删掉后
 *     原生侧的 `addListener` / `removeListeners` 也不再需要，一并移除。
 */
export const nativeOverlay = {
  async setPendingConfirmPopupEnabled(enabled: boolean): Promise<void> {
    await getRawOverlayModule().setPendingConfirmPopupEnabled(enabled);
  },

  async getPendingConfirmPopupEnabled(): Promise<boolean> {
    return getRawOverlayModule().getPendingConfirmPopupEnabled();
  },
};

export type NativeOverlay = typeof nativeOverlay;
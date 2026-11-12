import { NativeModules } from 'react-native';

export interface RuntimeHealth {
  databaseVersion: number;
  billCount: number;
  categoryCount: number;
  accountCount: number;
  lastLaunchAt: string;
  checkedAt: string;
  aiProviderConfigured: boolean;
  accessibilityEnabled: boolean;
  notificationEnabled: boolean;
  overlayPermissionGranted: boolean;
  batteryOptimizationIgnored: boolean;
  keepAliveEnabled: boolean;
  keepAliveRunning: boolean;
  lastMigrationRecoveredAt?: string | null;
  orphanImportBatches: number;
}

export type RuntimeStatus = RuntimeHealth;

interface RawRuntimeModule {
  getRuntimeHealth(): Promise<string>;
  openNotificationListenerSettings(): Promise<null>;
  openBatteryOptimizationSettings(): Promise<null>;
  exportDiagLog(): Promise<string>;
}

function getRawRuntimeModule(): RawRuntimeModule {
  const module = NativeModules.Runtime as RawRuntimeModule | undefined;
  if (!module) {
    throw new Error('Native Runtime module is unavailable on this platform');
  }
  return module;
}

export const nativeRuntime = {
  async getRuntimeHealth(): Promise<RuntimeHealth> {
    return JSON.parse(await getRawRuntimeModule().getRuntimeHealth()) as RuntimeHealth;
  },

  /**
   * 打开系统"通知访问"设置页（最小通知权限契约变更）。
   * 通知监听权限与无障碍权限不在同一设置页，需专用跳转。
   */
  async openNotificationListenerSettings(): Promise<void> {
    await getRawRuntimeModule().openNotificationListenerSettings();
  },

  /** 打开系统电池优化列表，由用户自行决定是否放行后台。 */
  async openBatteryOptimizationSettings(): Promise<void> {
    await getRawRuntimeModule().openBatteryOptimizationSettings();
  },

  /**
   * 打包诊断日志并拉起系统分享面板（用户在微信/QQ 直接转发给开发者）。
   * @returns zip 文件名，供成功 toast 展示。
   * @throws DIAG_EXPORT_EMPTY 没有可导出的日志；DIAG_EXPORT_NO_ACTIVITY 无前台界面；
   *   DIAG_EXPORT_SHARE_ERROR / DIAG_EXPORT_ERROR 打包或分享失败。
   */
  async exportDiagLog(): Promise<string> {
    return await getRawRuntimeModule().exportDiagLog();
  },
};

export type NativeRuntime = typeof nativeRuntime;

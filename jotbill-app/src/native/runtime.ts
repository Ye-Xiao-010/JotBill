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
  lastMigrationRecoveredAt?: string | null;
  orphanImportBatches: number;
}

export type RuntimeStatus = RuntimeHealth;

interface RawRuntimeModule {
  getRuntimeHealth(): Promise<string>;
  openNotificationListenerSettings(): Promise<null>;
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
};

export type NativeRuntime = typeof nativeRuntime;

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
}

export type RuntimeStatus = RuntimeHealth;

interface RawRuntimeModule {
  getRuntimeHealth(): Promise<string>;
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
};

export type NativeRuntime = typeof nativeRuntime;

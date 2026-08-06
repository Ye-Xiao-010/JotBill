import { NativeModules } from 'react-native';

export interface RuntimeStatus {
  databaseVersion: number;
  billCount: number;
  categoryCount: number;
  accountCount: number;
  lastLaunchAt: string;
  checkedAt: string;
}

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
  async getRuntimeHealth(): Promise<RuntimeStatus> {
    return JSON.parse(await getRawRuntimeModule().getRuntimeHealth()) as RuntimeStatus;
  },
};

export type NativeRuntime = typeof nativeRuntime;

import { NativeModules } from 'react-native';
import type { ApkVerification, InstallApkModule } from '../../../docs/types/Native';

interface RawInstallApkModule {
  install?: (filePath: string) => Promise<null>;
  verifyApk?: (filePath: string, expectedSha256?: string | null) => Promise<string>;
}

function getRawInstallApk(): RawInstallApkModule {
  const module = NativeModules.InstallApk as RawInstallApkModule | undefined;
  if (!module) {
    throw new Error('Native InstallApk module is unavailable on this platform');
  }
  return module;
}

export const nativeInstallApk: InstallApkModule = {
  async install(filePath: string): Promise<void> {
    const raw = getRawInstallApk();
    if (typeof raw.install !== 'function') {
      throw new Error('Native InstallApk.install is unavailable');
    }
    await raw.install(filePath);
  },

  async verifyApk(filePath: string, expectedSha256?: string): Promise<ApkVerification> {
    const raw = getRawInstallApk();
    if (typeof raw.verifyApk !== 'function') {
      throw new Error('Native InstallApk.verifyApk is unavailable');
    }
    return JSON.parse(await raw.verifyApk(filePath, expectedSha256 || null)) as ApkVerification;
  },
};

export type NativeInstallApk = typeof nativeInstallApk;

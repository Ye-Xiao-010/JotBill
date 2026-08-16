/**
 * 应用更新检查 Hook
 */
import { useEffect, useState, useCallback } from 'react';
import { Platform, NativeModules } from 'react-native';
import RNFS from 'react-native-fs';
import { nativeInstallApk } from '../native/installApk';
import { appVersionApi, UpdateManifestLatest as AppVersionInfo } from '../services/update/appVersion';
import { downloadUpdateFile, getSafeUpdateUrl } from '../services/update/client';
import { useAlert } from '../providers';
import packageJson from '../../package.json';

interface UpdateState {
  checking: boolean;
  hasUpdate: boolean;
  latestVersion: AppVersionInfo | null;
  showModal: boolean;
  downloading: boolean;
  progress: number;
}

interface UseAppUpdateOptions {
  /** 是否在 hook 挂载时自动检查更新，默认关闭，避免页面挂载触发联网。 */
  autoCheck?: boolean;
}

// 检查、下载和安装共用模块级队列，跨 Hook 实例也不会操作同一个更新文件。
let appUpdateQueue: Promise<void> = Promise.resolve();
let appCheckInFlight: Promise<void> | null = null;
let appDownloadInFlight: Promise<void> | null = null;
let apkSequence = 0;

function enqueueAppUpdate<T>(operation: () => Promise<T>): Promise<T> {
  const queued = appUpdateQueue.then(operation, operation);
  appUpdateQueue = queued.then(() => undefined, () => undefined);
  return queued;
}

function createApkPath(version: string): string {
  apkSequence = (apkSequence + 1) % 1000000;
  const safeVersion = version.replace(/[^a-zA-Z0-9._-]/g, '_').slice(0, 64) || 'latest';
  return `${RNFS.CachesDirectoryPath}/app-v${safeVersion}-${Date.now()}-${apkSequence}.apk`;
}

export function useAppUpdate(options: UseAppUpdateOptions = {}) {
  const { autoCheck = false } = options;
  const { alert } = useAlert();
  const [state, setState] = useState<UpdateState>({
    checking: false,
    hasUpdate: false,
    latestVersion: null,
    showModal: false,
    downloading: false,
    progress: 0,
  });
  const currentVersion = packageJson.version;

  const checkUpdate = useCallback((showNoUpdateAlert = false): Promise<void> => {
    if (Platform.OS !== 'android') return Promise.resolve();
    if (appCheckInFlight) return appCheckInFlight;

    const operation = enqueueAppUpdate(async () => {
      console.log('[useAppUpdate] 开始检查更新...');
      setState(prev => ({ ...prev, checking: true }));

      try {
        const result = await appVersionApi.checkUpdate(currentVersion);
        console.log('[useAppUpdate] 更新检查完成:', result.hasUpdate);
        if (result.latestVersion) {
          getSafeUpdateUrl(result.latestVersion.downloadUrl);
        }

        setState(prev => ({
          ...prev,
          checking: false,
          hasUpdate: result.hasUpdate,
          latestVersion: result.latestVersion || null,
          showModal: result.hasUpdate && !!result.latestVersion,
        }));

        if (!result.hasUpdate && showNoUpdateAlert) {
          alert('检查更新', '当前已是最新版本');
        }
      } catch (error: unknown) {
        const message = error instanceof Error ? error.message : '更新服务暂时不可用';
        console.log('[useAppUpdate] 检查更新失败:', message);
        setState(prev => ({ ...prev, checking: false }));
        if (showNoUpdateAlert) {
          alert('检查更新', '检查更新失败，请稍后重试');
        }
      } finally {
        setState(prev => ({ ...prev, checking: false }));
      }
    });
    let tracked: Promise<void>;
    tracked = operation.finally(() => {
      if (appCheckInFlight === tracked) {
        appCheckInFlight = null;
      }
    });
    appCheckInFlight = tracked;
    return tracked;
  }, [currentVersion, alert]);

  const hideModal = useCallback(() => {
    setState(prev => ({ ...prev, showModal: false }));
  }, []);

  const installApk = useCallback(async (filePath: string) => {
    try {
      // 使用 Android Intent 安装 APK
      const { InstallApk } = NativeModules;
      if (InstallApk) {
        await Promise.resolve(InstallApk.install(filePath));
      } else {
        // 备用方案：使用 react-native-fs 的 Android 特定方法
        await RNFS.scanFile(filePath);
        alert('下载完成', '请在通知栏或文件管理器中点击安装包进行安装');
      }
    } catch (error: unknown) {
      const message = error instanceof Error ? error.message : '安装失败';
      console.log('[useAppUpdate] 安装失败:', message);
      alert('安装失败', '请手动安装下载的 APK 文件');
    }
  }, [alert]);

  const downloadAndInstall = useCallback((): Promise<void> => {
    if (!state.latestVersion) return Promise.resolve();
    if (appDownloadInFlight) return appDownloadInFlight;

    const latestVersion = state.latestVersion;
    if (!latestVersion) return Promise.resolve();
    const { downloadUrl, version } = latestVersion;
    const filePath = createApkPath(version);
    const operation = enqueueAppUpdate(async () => {
      setState(prev => ({ ...prev, downloading: true, progress: 0 }));

      try {
        const safeDownloadUrl = getSafeUpdateUrl(downloadUrl);
        await downloadUpdateFile(
          safeDownloadUrl,
          filePath,
          (progress) => setState(prev => ({ ...prev, progress })),
        );
        setState(prev => ({ ...prev, showModal: false }));
        // 阶段七 T5：安装前校验 SHA-256、包名、版本码与签名一致性。
        const verification = await nativeInstallApk.verifyApk(filePath, latestVersion.sha256);
        if (!verification.sha256Matched) {
          throw new Error('更新包 SHA-256 校验失败，已取消安装');
        }
        if (!verification.signatureConsistent) {
          throw new Error('更新包签名与当前应用不一致，已取消安装');
        }
        if (verification.versionCode !== latestVersion.versionCode) {
          throw new Error('更新包版本码与更新清单不一致，已取消安装');
        }
        // 安装 APK
        await installApk(filePath);
      } catch (error: unknown) {
        const message = error instanceof Error ? error.message : '下载更新包失败，请稍后重试';
        console.log('[useAppUpdate] 下载失败');
        alert('下载失败', message);
      } finally {
        setState(prev => ({ ...prev, downloading: false }));
      }
    });
    let tracked: Promise<void>;
    tracked = operation.finally(() => {
      if (appDownloadInFlight === tracked) {
        appDownloadInFlight = null;
      }
    });
    appDownloadInFlight = tracked;
    return tracked;
  }, [state.latestVersion, alert, installApk]);

  // 只有明确传入 autoCheck: true 时才允许页面挂载触发更新网络请求。
  useEffect(() => {
    if (autoCheck) {
      checkUpdate(false);
    }
  }, [autoCheck, checkUpdate]);

  return {
    ...state,
    currentVersion,
    checkUpdate: () => checkUpdate(true),
    hideModal,
    downloadAndInstall,
  };
}

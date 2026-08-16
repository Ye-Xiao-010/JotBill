/**
 * 热更新核心服务
 * 负责下载、校验、解压、应用热更新 bundle
 */
import { NativeModules, Platform } from 'react-native';
import RNFS from 'react-native-fs';
import { unzip } from 'react-native-zip-archive';
import { hotUpdateApi, HotUpdateBundleInfo } from '../update/hotUpdate';
import { downloadUpdateFile, getSafeUpdateUrl } from '../update/client';
import bundleMeta from '../../../bundle-version.json';
import packageJson from '../../../package.json';

const HotUpdate = NativeModules.HotUpdate as {
  applyPreparedBundle?: () => Promise<boolean>;
  clearHotUpdate?: () => Promise<boolean>;
  markBundleApplied?: () => void;
  reload?: () => void;
} | undefined;

// 临时目录只用于下载和解压，正式目录由原生模块一次性替换。
const TEMP_DIR = `${RNFS.DocumentDirectoryPath}/hot_update_temp`;
const STAGED_BUNDLE_FILE = `${TEMP_DIR}/index.android.bundle`;

// 所有会触碰临时目录或原生交换目录的操作共用一个服务层队列。
let exclusiveTail: Promise<void> = Promise.resolve();
let updateInFlight: Promise<boolean> | null = null;
let clearInFlight: Promise<boolean> | null = null;

function enqueueExclusive<T>(operation: () => Promise<T>): Promise<T> {
  const queued = exclusiveTail.then(operation, operation);
  exclusiveTail = queued.then(() => undefined, () => undefined);
  return queued;
}

export type UpdateStatus =
  | 'idle'
  | 'checking'
  | 'downloading'
  | 'applying'
  | 'done'
  | 'error';

export interface HotUpdateState {
  status: UpdateStatus;
  hasUpdate: boolean;
  bundleInfo: HotUpdateBundleInfo | null;
  progress: number;
  error: string | null;
  patchMode: 'full' | 'diff' | null;
}

/**
 * 获取当前 bundle 版本号（编译时嵌入）
 */
export function getCurrentBundleVersion(): number {
  return bundleMeta.bundleVersion;
}

/**
 * 获取当前原生版本号
 */
export function getNativeVersion(): string {
  return packageJson.version;
}

/**
 * 计算文件 SHA256
 */
async function computeSHA256(filePath: string): Promise<string> {
  return await RNFS.hash(filePath, 'sha256');
}

/**
 * 确保目录存在
 */
async function ensureDir(dir: string): Promise<void> {
  const exists = await RNFS.exists(dir);
  if (!exists) {
    await RNFS.mkdir(dir);
  }
}

/** 由原生模块在同一文件系统内替换目录，失败时保留旧 bundle。 */
async function applyPreparedBundle(): Promise<void> {
  const apply = HotUpdate?.applyPreparedBundle;
  if (typeof apply !== 'function') {
    throw new Error('当前版本不支持热更新原子替换');
  }
  const applied = await apply();
  if (applied !== true) {
    throw new Error('热更新 bundle 替换失败');
  }
}

/**
 * 清理目录
 */
async function cleanDir(dir: string): Promise<void> {
  const exists = await RNFS.exists(dir);
  if (exists) {
    await RNFS.unlink(dir);
  }
}

/** 清理失败只记录警告，不改变已经完成的原生应用结果。 */
async function bestEffortCleanDir(dir: string): Promise<void> {
  try {
    await cleanDir(dir);
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message : '未知错误';
    console.warn('[HotUpdate] 临时目录清理失败，保留已完成结果:', message);
  }
}

/**
 * 检查热更新
 */
export async function checkForUpdate(): Promise<{
  hasUpdate: boolean;
  bundle?: HotUpdateBundleInfo;
}> {
  if (Platform.OS !== 'android') {
    return { hasUpdate: false };
  }

  const nativeVersion = getNativeVersion();
  const bundleVersion = getCurrentBundleVersion();

  const result = await hotUpdateApi.checkUpdate(nativeVersion, bundleVersion);
  if (result.bundle) {
    getSafeUpdateUrl(result.bundle.downloadUrl);
    if (result.bundle.patchAvailable && result.bundle.patchUrl) {
      getSafeUpdateUrl(result.bundle.patchUrl);
    }
  }
  return result;
}

/**
 * 下载并应用热更新
 */
export function downloadAndApplyUpdate(
  bundle: HotUpdateBundleInfo,
  onProgress?: (progress: number) => void,
): Promise<boolean> {
  if (updateInFlight) {
    return updateInFlight;
  }

  const operation = enqueueExclusive(() => runDownloadAndApplyUpdate(bundle, onProgress));
  let tracked: Promise<boolean>;
  tracked = operation.finally(() => {
    if (updateInFlight === tracked) {
      updateInFlight = null;
    }
  });
  updateInFlight = tracked;
  return tracked;
}

/** 清理入口也必须排在同一队列中，不能与下载、解压或原生交换并发。 */
export function clearHotUpdate(): Promise<boolean> {
  if (clearInFlight) {
    return clearInFlight;
  }

  const operation = enqueueExclusive(async () => {
    const clear = HotUpdate?.clearHotUpdate;
    if (typeof clear !== 'function') {
      throw new Error('当前版本不支持清理热更新');
    }
    return (await clear()) === true;
  });
  let tracked: Promise<boolean>;
  tracked = operation.finally(() => {
    if (clearInFlight === tracked) {
      clearInFlight = null;
    }
  });
  clearInFlight = tracked;
  return tracked;
}

async function runDownloadAndApplyUpdate(
  bundle: HotUpdateBundleInfo,
  onProgress?: (progress: number) => void,
): Promise<boolean> {
  try {
    await cleanDir(TEMP_DIR);
    await ensureDir(TEMP_DIR);

    let success = false;

    // 尝试 diff 补丁下载（迭代二）
    if (bundle.patchAvailable && bundle.patchUrl) {
      console.log('[HotUpdate] 尝试 diff 补丁更新...');
      success = await downloadAndApplyPatch(bundle, onProgress);
    }

    // diff 失败或不可用，下载完整包
    if (!success) {
      console.log('[HotUpdate] 下载完整 bundle zip...');
      success = await downloadFullBundle(bundle, onProgress);
    }

    if (success) {
      // 标记 bundle 已成功应用
      if (HotUpdate?.markBundleApplied) {
        HotUpdate.markBundleApplied();
      }

      // 强制更新则立即 reload（先取出方法引用，避免闭包内可选调用报错）
      const reload = HotUpdate?.reload;
      if (bundle.forceUpdate && reload) {
        console.log('[HotUpdate] 强制更新，重新加载...');
        // 延迟一点确保文件写入完成
        setTimeout(() => {
          reload();
        }, 500);
      }
    }

    return success;
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message : '未知错误';
    console.error('[HotUpdate] 更新失败:', message);
    return false;
  } finally {
    await bestEffortCleanDir(TEMP_DIR);
  }
}

/**
 * 下载完整 bundle zip 并解压
 */
async function downloadFullBundle(
  bundle: HotUpdateBundleInfo,
  onProgress?: (progress: number) => void,
): Promise<boolean> {
  const tempZipPath = `${TEMP_DIR}/bundle.zip`;

  try {
    const safeDownloadUrl = getSafeUpdateUrl(bundle.downloadUrl);

    // 1. 通过拒绝重定向的更新客户端下载 zip。
    await downloadUpdateFile(safeDownloadUrl, tempZipPath, onProgress);

    // 2. 校验 SHA256
    const hash = await computeSHA256(tempZipPath);
    if (hash !== bundle.fileHash) {
      throw new Error(`SHA256 校验失败: 期望 ${bundle.fileHash}, 实际 ${hash}`);
    }
    console.log('[HotUpdate] SHA256 校验通过');

    // 3. 解压到临时目录，不影响当前正在使用的 bundle。
    //    zip 内结构: index.android.bundle + drawable-*/xxx.png
    await unzip(tempZipPath, TEMP_DIR);
    await cleanDir(tempZipPath);
    console.log('[HotUpdate] 临时解压完成');

    // 4. 先验证临时 bundle，再让原生层替换正式目录。
    const bundleExists = await RNFS.exists(STAGED_BUNDLE_FILE);
    if (!bundleExists) {
      throw new Error('解压后未找到 index.android.bundle');
    }

    const stat = await RNFS.stat(STAGED_BUNDLE_FILE);
    console.log(`[HotUpdate] Bundle 已保存: ${(Number(stat.size) / 1024).toFixed(1)} KB`);
    await applyPreparedBundle();

    return true;
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message : '未知错误';
    console.error('[HotUpdate] 完整包下载失败:', message);
    return false;
  }
}

/**
 * 下载并应用 diff 补丁（迭代二实现）
 */
async function downloadAndApplyPatch(
  bundle: HotUpdateBundleInfo,
  _onProgress?: (progress: number) => void,
): Promise<boolean> {
  if (bundle.patchUrl) {
    getSafeUpdateUrl(bundle.patchUrl);
  }
  // 迭代二实现：bspatch 合成
  console.log('[HotUpdate] diff 补丁暂未实现，回退完整包');
  return false;
}

/**
 * 启动时标记 bundle 加载成功
 */
export function markBundleLoaded(): void {
  if (Platform.OS === 'android' && HotUpdate?.markBundleApplied) {
    HotUpdate.markBundleApplied();
    console.log('[HotUpdate] bundle 加载成功，已重置崩溃计数');
  }
}

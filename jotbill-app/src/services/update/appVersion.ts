/**
 * 阶段七 T5：自发布 APK 更新清单服务。
 *
 * 不再依赖旧后端 /app-version/check，改为读取只读 HTTPS JSON 清单：
 * {
 *   "latest": {
 *     "version": "0.0.29",
 *     "versionCode": 29,
 *     "downloadUrl": "https://.../jotbill-0.0.29.apk",
 *     "sha256": "...",
 *     "updateLog": "...",
 *     "forceUpdate": false
 *   }
 * }
 */
import env from '../../config/env';
import { getSafeUpdateUrl } from './client';

export interface UpdateManifestLatest {
  version: string;
  versionCode: number;
  downloadUrl: string;
  sha256: string;
  updateLog: string;
  forceUpdate: boolean;
}

export interface UpdateManifest {
  latest: UpdateManifestLatest;
}

export type AppVersionInfo = UpdateManifestLatest;

export interface CheckUpdateResponse {
  hasUpdate: boolean;
  latestVersion?: UpdateManifestLatest;
}

function isSafeHexSha256(value: string): boolean {
  return /^[a-fA-F0-9]{64}$/.test(value);
}

async function fetchManifest(): Promise<UpdateManifest> {
  const rawUrl = env.getUpdateManifestUrl();
  if (!rawUrl.trim()) {
    throw new Error('未配置更新清单地址');
  }
  const manifestUrl = getSafeUpdateUrl(rawUrl);
  const response = await fetch(manifestUrl, { redirect: 'error' });
  if (!response.ok) {
    throw new Error(`更新清单请求失败: HTTP ${response.status}`);
  }
  const manifest = (await response.json()) as UpdateManifest;
  const latest = manifest?.latest;
  if (!latest || typeof latest.versionCode !== 'number') {
    throw new Error('更新清单格式无效');
  }
  if (!isSafeHexSha256(latest.sha256 || '')) {
    throw new Error('更新清单缺少有效 SHA-256');
  }
  if (typeof latest.downloadUrl !== 'string' || !latest.downloadUrl.trim()) {
    throw new Error('更新清单缺少下载地址');
  }
  return manifest;
}

export const appVersionApi = {
  async checkUpdate(_currentVersion: string): Promise<CheckUpdateResponse> {
    const currentVersionCode = Number.parseInt(env.get('BUILD_NUMBER') || '0', 10);
    const manifest = await fetchManifest();
    const latestVersion = manifest.latest;
    const hasUpdate = latestVersion.versionCode > currentVersionCode;
    return { hasUpdate, latestVersion: hasUpdate ? latestVersion : undefined };
  },
};

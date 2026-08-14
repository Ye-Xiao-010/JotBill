/**
 * 应用版本 API 服务
 */
import { getUpdateJson } from './client';

export interface AppVersionInfo {
  id: number;
  version: string;
  versionCode: number;
  downloadUrl: string;
  updateLog: string;
  forceUpdate: boolean;
  platform: string;
  createdAt: string;
}

export interface CheckUpdateResponse {
  hasUpdate: boolean;
  latestVersion?: AppVersionInfo;
}

/**
 * 应用版本服务
 */
export const appVersionApi = {
  /**
   * 检查更新
   */
  async checkUpdate(currentVersion: string): Promise<CheckUpdateResponse> {
    console.log('[AppVersion] 检查更新, 当前版本:', currentVersion);
    const result = await getUpdateJson<CheckUpdateResponse>('/app-version/check', {
      currentVersion,
      platform: 'android',
    });
    console.log('[AppVersion] 检查更新完成:', result.hasUpdate);
    return result;
  },

  /**
   * 获取最新版本
   */
  async getLatest(): Promise<AppVersionInfo | null> {
    try {
      return await getUpdateJson<AppVersionInfo>('/app-version/latest', {
        platform: 'android',
      });
    } catch {
      return null;
    }
  },
};

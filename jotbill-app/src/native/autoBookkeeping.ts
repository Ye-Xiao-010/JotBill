import { NativeModules } from 'react-native';

/** 渠道元信息由 native 单一事实源下发，RN 不再硬编码渠道清单。 */
export interface AutoBookkeepingChannelMeta {
  key: string;
  label: string;
  description: string;
  defaultEnabled: boolean;
}

export interface AutoBookkeepingSettings {
  masterEnabled: boolean;
  keepAliveEnabled: boolean;
  keepAlivePrompted: boolean;
  keepAliveRunning: boolean;
  channels: Record<string, boolean>;
  channelMeta: AutoBookkeepingChannelMeta[];
}

export interface AutoBookkeepingRuleStats {
  keys: number;
  rules: number;
  /** 资产加载失败原因；非空表示规则集为空、引擎空转。 */
  error?: string | null;
}

interface RawAutoBookkeepingModule {
  getSettings(): Promise<string>;
  setMasterEnabled(enabled: boolean): Promise<null>;
  setChannelEnabled(channel: string, enabled: boolean): Promise<null>;
  setKeepAliveEnabled(enabled: boolean): Promise<null>;
  markKeepAlivePrompted(): Promise<null>;
  getRuleStats(): Promise<string>;
  getServiceRunning(): Promise<boolean>;
}

function getRawModule(): RawAutoBookkeepingModule {
  const module = NativeModules.AutoBookkeeping as RawAutoBookkeepingModule | undefined;
  if (!module) {
    throw new Error('Native AutoBookkeeping module is unavailable on this platform');
  }
  return module;
}

export const nativeAutoBookkeeping = {
  async getSettings(): Promise<AutoBookkeepingSettings> {
    return JSON.parse(await getRawModule().getSettings()) as AutoBookkeepingSettings;
  },

  async setMasterEnabled(enabled: boolean): Promise<void> {
    await getRawModule().setMasterEnabled(enabled);
  },

  async setChannelEnabled(channel: string, enabled: boolean): Promise<void> {
    await getRawModule().setChannelEnabled(channel, enabled);
  },

  async setKeepAliveEnabled(enabled: boolean): Promise<void> {
    await getRawModule().setKeepAliveEnabled(enabled);
  },

  async markKeepAlivePrompted(): Promise<void> {
    await getRawModule().markKeepAlivePrompted();
  },

  async getRuleStats(): Promise<AutoBookkeepingRuleStats> {
    return JSON.parse(await getRawModule().getRuleStats()) as AutoBookkeepingRuleStats;
  },

  /** 无障碍服务当前是否真实存活（进程内静态标记，MIUI 设置读取不可信时以它为准）。 */
  async getServiceRunning(): Promise<boolean> {
    return await getRawModule().getServiceRunning();
  },
};

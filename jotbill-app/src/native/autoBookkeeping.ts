import { NativeModules } from 'react-native';

export interface AutoBookkeepingSettings {
  masterEnabled: boolean;
  channels: {
    alipay: boolean;
    wechat: boolean;
    unionpay: boolean;
    mms: boolean;
  };
}

export interface AutoBookkeepingRuleStats {
  keys: number;
  rules: number;
}

interface RawAutoBookkeepingModule {
  getSettings(): Promise<string>;
  setMasterEnabled(enabled: boolean): Promise<null>;
  setChannelEnabled(channel: string, enabled: boolean): Promise<null>;
  getRuleStats(): Promise<string>;
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

  async getRuleStats(): Promise<AutoBookkeepingRuleStats> {
    return JSON.parse(await getRawModule().getRuleStats()) as AutoBookkeepingRuleStats;
  },
};

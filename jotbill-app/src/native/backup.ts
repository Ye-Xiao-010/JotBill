import { NativeModules } from 'react-native';

export type ExportFormat = 'csv';

export interface ExportResult {
  filePath: string;
}

interface RawBackupModule {
  exportData(format: ExportFormat): Promise<string>;
  clearData(): Promise<null>;
}

function getRawBackupModule(): RawBackupModule {
  const module = NativeModules.Backup as RawBackupModule | undefined;
  if (!module) {
    throw new Error('Native Backup module is unavailable on this platform');
  }
  return module;
}

export const nativeBackup = {
  async exportData(format: ExportFormat = 'csv'): Promise<ExportResult> {
    return JSON.parse(await getRawBackupModule().exportData(format)) as ExportResult;
  },

  async clearData(): Promise<void> {
    await getRawBackupModule().clearData();
  },
};

export type NativeBackup = typeof nativeBackup;

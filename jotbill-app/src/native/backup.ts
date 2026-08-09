import { NativeModules } from 'react-native';
import type {
  BackupModule,
  BackupSummary,
  CreateBackupOptions,
  ExportResult as NativeExportResult,
  ImportResult,
  RestorePreview,
  RestoreResult,
  RestoreStrategy,
} from '../../../docs/types/Native';

export type ExportFormat = 'csv';
export type ExportResult = NativeExportResult;

interface RawBackupModule {
  createBackup(optionsJson: string): Promise<string>;
  restoreBackup(filePath: string): Promise<string>;
  executeRestore(filePath: string, strategy: RestoreStrategy): Promise<string>;
  exportData(format: ExportFormat): Promise<string>;
  importData(filePath: string): Promise<string>;
  clearData(): Promise<null>;
}

function getRawBackupModule(): RawBackupModule {
  const module = NativeModules.Backup as RawBackupModule | undefined;
  if (!module) {
    throw new Error('Native Backup module is unavailable on this platform');
  }
  return module;
}

function parseJson<T>(value: string, operation: string): T {
  try {
    return JSON.parse(value) as T;
  } catch {
    throw new Error(`Native Backup.${operation} returned invalid JSON`);
  }
}

export const nativeBackup: BackupModule = {
  async createBackup(options: CreateBackupOptions = {}): Promise<BackupSummary> {
    return parseJson(
      await getRawBackupModule().createBackup(JSON.stringify(options)),
      'createBackup',
    );
  },

  async restoreBackup(filePath: string): Promise<RestorePreview> {
    return parseJson(
      await getRawBackupModule().restoreBackup(filePath),
      'restoreBackup',
    );
  },

  async executeRestore(
    filePath: string,
    strategy: RestoreStrategy,
  ): Promise<RestoreResult> {
    return parseJson(
      await getRawBackupModule().executeRestore(filePath, strategy),
      'executeRestore',
    );
  },

  async exportData(format: ExportFormat = 'csv'): Promise<ExportResult> {
    return parseJson(
      await getRawBackupModule().exportData(format),
      'exportData',
    );
  },

  async importData(filePath: string): Promise<ImportResult> {
    return parseJson(
      await getRawBackupModule().importData(filePath),
      'importData',
    );
  },

  async clearData(): Promise<void> {
    await getRawBackupModule().clearData();
  },
};

export type NativeBackup = typeof nativeBackup;

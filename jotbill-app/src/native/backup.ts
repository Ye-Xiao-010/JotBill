import { NativeModules } from 'react-native';
import type {
  BackupError,
  BackupModule,
  BackupSummary,
  CreateBackupOptions,
  ExportResult as NativeExportResult,
  ImportResult,
  RestorePreview,
  RestoreStrategy,
} from '../../../docs/types/Native';

export type ExportFormat = 'csv';
export type ExportResult = NativeExportResult;

interface RawBackupModule {
  createBackup?: (optionsJson: string) => Promise<string>;
  restoreBackup?: (filePath: string) => Promise<string>;
  executeRestore?: (filePath: string, strategy: RestoreStrategy) => Promise<string>;
  exportData?: (format: ExportFormat) => Promise<string>;
  importData?: (filePath: string) => Promise<string>;
  clearData?: () => Promise<null>;
}

export type NativeBackupError = Error & BackupError;

function getRawBackupModule(): RawBackupModule {
  const module = NativeModules.Backup as RawBackupModule | undefined;
  if (!module) {
    throw createNativeBackupError(
      'NOT_IMPLEMENTED',
      'Native Backup module is unavailable on this platform',
    );
  }
  return module;
}

function createNativeBackupError(
  code: BackupError['code'],
  message: string,
): NativeBackupError {
  const error = new Error(message) as NativeBackupError;
  error.name = 'NativeBackupError';
  error.code = code;
  error.retryable = false;
  return error;
}

function requireCreateBackup(module: RawBackupModule): NonNullable<RawBackupModule['createBackup']> {
  if (typeof module.createBackup !== 'function') {
    throw createNativeBackupError(
      'NOT_IMPLEMENTED',
      'Native Backup.createBackup is not implemented on this platform',
    );
  }
  return module.createBackup.bind(module);
}

function requireRestoreBackup(module: RawBackupModule): NonNullable<RawBackupModule['restoreBackup']> {
  if (typeof module.restoreBackup !== 'function') {
    throw createNativeBackupError(
      'NOT_IMPLEMENTED',
      'Native Backup.restoreBackup is not implemented on this platform',
    );
  }
  return module.restoreBackup.bind(module);
}

function requireExecuteRestore(module: RawBackupModule): NonNullable<RawBackupModule['executeRestore']> {
  if (typeof module.executeRestore !== 'function') {
    throw createNativeBackupError(
      'NOT_IMPLEMENTED',
      'Native Backup.executeRestore is not implemented on this platform',
    );
  }
  return module.executeRestore.bind(module);
}

function requireExportData(module: RawBackupModule): NonNullable<RawBackupModule['exportData']> {
  if (typeof module.exportData !== 'function') {
    throw createNativeBackupError(
      'NOT_IMPLEMENTED',
      'Native Backup.exportData is not implemented on this platform',
    );
  }
  return module.exportData.bind(module);
}

function requireImportData(module: RawBackupModule): NonNullable<RawBackupModule['importData']> {
  if (typeof module.importData !== 'function') {
    throw createNativeBackupError(
      'NOT_IMPLEMENTED',
      'Native Backup.importData is not implemented on this platform',
    );
  }
  return module.importData.bind(module);
}

function requireClearData(module: RawBackupModule): NonNullable<RawBackupModule['clearData']> {
  if (typeof module.clearData !== 'function') {
    throw createNativeBackupError(
      'NOT_IMPLEMENTED',
      'Native Backup.clearData is not implemented on this platform',
    );
  }
  return module.clearData.bind(module);
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
    const module = getRawBackupModule();
    return parseJson(
      await requireCreateBackup(module)(JSON.stringify(options)),
      'createBackup',
    );
  },

  async restoreBackup(filePath: string): Promise<RestorePreview> {
    const module = getRawBackupModule();
    return parseJson(
      await requireRestoreBackup(module)(filePath),
      'restoreBackup',
    );
  },

  async executeRestore(
    filePath: string,
    strategy: RestoreStrategy,
  ): Promise<ImportResult> {
    const module = getRawBackupModule();
    return parseJson(
      await requireExecuteRestore(module)(filePath, strategy),
      'executeRestore',
    );
  },

  async exportData(format: ExportFormat = 'csv'): Promise<ExportResult> {
    const module = getRawBackupModule();
    return parseJson(
      await requireExportData(module)(format),
      'exportData',
    );
  },

  async importData(filePath: string): Promise<ImportResult> {
    const module = getRawBackupModule();
    return parseJson(
      await requireImportData(module)(filePath),
      'importData',
    );
  },

  async clearData(): Promise<void> {
    const module = getRawBackupModule();
    await requireClearData(module)();
  },
};

export type NativeBackup = typeof nativeBackup;

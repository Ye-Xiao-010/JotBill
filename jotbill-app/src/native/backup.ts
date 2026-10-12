import { NativeModules } from 'react-native';
import type {
  BackupError,
  BackupErrorCode,
  BackupFailureStatus,
  BackupModule,
  BackupOperation,
  BackupSummary,
  CreateBackupOptions,
  ExportResult as NativeExportResult,
  RestorePreview,
  RestoreImportResult,
  RestoreStrategy,
} from '../../../docs/types/Native';

export type ExportFormat = 'csv';
export type ExportResult = NativeExportResult;

interface RawBackupModule {
  createBackup?: (optionsJson: string) => Promise<string>;
  restoreBackup?: (filePath: string) => Promise<string>;
  executeRestore?: (filePath: string, strategy: RestoreStrategy) => Promise<string>;
  exportData?: (format: ExportFormat) => Promise<string>;
  clearData?: () => Promise<null>;
}

const DEFAULT_ERROR_CODES: Record<BackupOperation, BackupErrorCode> = {
  createBackup: 'BACKUP_FAILED',
  restoreBackup: 'RESTORE_PREVIEW_FAILED',
  executeRestore: 'RESTORE_FAILED',
  exportData: 'EXPORT_FAILED',
  importData: 'IMPORT_FAILED',
  inspectImport: 'IMPORT_INSPECT_FAILED',
  executeImport: 'IMPORT_FAILED',
  cancelImport: 'IMPORT_FAILED',
  undoImport: 'IMPORT_UNDO_FAILED',
  getImportBatches: 'IMPORT_FAILED',
  clearData: 'CLEAR_DATA_ERROR',
};

const BACKUP_ERROR_CODES: readonly BackupErrorCode[] = [
  'INVALID_ARGUMENT',
  'NOT_FOUND',
  'DATA_CONFLICT',
  'DB_ERROR',
  'NOT_IMPLEMENTED',
  'UNSUPPORTED_FORMAT',
  'EXPORT_IN_PROGRESS',
  'EXPORT_CANCELLED',
  'EXPORT_ERROR',
  'CLEAR_DATA_ERROR',
  'BACKUP_FAILED',
  'RESTORE_PREVIEW_FAILED',
  'RESTORE_FAILED',
  'EXPORT_FAILED',
  'IMPORT_FAILED',
  'BACKUP_UNSUPPORTED_FORMAT',
  'BACKUP_VALIDATION_FAILED',
  'BACKUP_CANCELLED',
  'BACKUP_TRANSACTION_FAILED',
  'BACKUP_IO_ERROR',
  'BACKUP_ENCRYPTION_ERROR',
  'PERMISSION_DENIED',
  'UNKNOWN',
];

/** 统一封装原生拒绝、能力缺失和返回内容解析错误。 */
export class NativeBackupError extends Error implements BackupError {
  readonly name = 'NativeBackupError' as const;

  constructor(
    readonly operation: BackupOperation,
    readonly code: BackupErrorCode,
    readonly status: BackupFailureStatus,
    readonly retryable: boolean,
    message: string,
  ) {
    super(message);
    Object.setPrototypeOf(this, NativeBackupError.prototype);
  }
}

function getRawBackupModule(operation: BackupOperation): RawBackupModule {
  const module = NativeModules.Backup as RawBackupModule | undefined;
  if (!module) {
    throw new NativeBackupError(
      operation,
      'NOT_IMPLEMENTED',
      'not_implemented',
      false,
      'Native Backup module is unavailable on this platform',
    );
  }
  return module;
}

function missingCapability(operation: BackupOperation): NativeBackupError {
  return new NativeBackupError(
    operation,
    'NOT_IMPLEMENTED',
    'not_implemented',
    false,
    `Native Backup.${operation} is not implemented on this platform`,
  );
}

function requireCreateBackup(module: RawBackupModule): NonNullable<RawBackupModule['createBackup']> {
  if (typeof module.createBackup !== 'function') {
    throw missingCapability('createBackup');
  }
  return module.createBackup.bind(module);
}

function requireRestoreBackup(module: RawBackupModule): NonNullable<RawBackupModule['restoreBackup']> {
  if (typeof module.restoreBackup !== 'function') {
    throw missingCapability('restoreBackup');
  }
  return module.restoreBackup.bind(module);
}

function requireExecuteRestore(module: RawBackupModule): NonNullable<RawBackupModule['executeRestore']> {
  if (typeof module.executeRestore !== 'function') {
    throw missingCapability('executeRestore');
  }
  return module.executeRestore.bind(module);
}

function requireExportData(module: RawBackupModule): NonNullable<RawBackupModule['exportData']> {
  if (typeof module.exportData !== 'function') {
    throw missingCapability('exportData');
  }
  return module.exportData.bind(module);
}

function requireClearData(module: RawBackupModule): NonNullable<RawBackupModule['clearData']> {
  if (typeof module.clearData !== 'function') {
    throw missingCapability('clearData');
  }
  return module.clearData.bind(module);
}

function parseJson<T>(value: string, operation: BackupOperation): T {
  try {
    return JSON.parse(value) as T;
  } catch {
    throw new NativeBackupError(
      operation,
      'BACKUP_VALIDATION_FAILED',
      'validation_failed',
      false,
      `Native Backup.${operation} returned invalid JSON`,
    );
  }
}

function getErrorField(error: unknown, field: 'code' | 'message'): unknown {
  if (typeof error !== 'object' || error === null) {
    return undefined;
  }
  return (error as Record<string, unknown>)[field];
}

function isBackupErrorCode(value: unknown): value is BackupErrorCode {
  return typeof value === 'string' && BACKUP_ERROR_CODES.some((code) => code === value);
}

function getFailureStatus(code: BackupErrorCode): BackupFailureStatus {
  switch (code) {
    case 'INVALID_ARGUMENT':
      return 'invalid_argument';
    case 'NOT_FOUND':
      return 'not_found';
    case 'DATA_CONFLICT':
      return 'data_conflict';
    case 'DB_ERROR':
    case 'CLEAR_DATA_ERROR':
      return 'database_error';
    case 'NOT_IMPLEMENTED':
      return 'not_implemented';
    case 'UNSUPPORTED_FORMAT':
    case 'BACKUP_UNSUPPORTED_FORMAT':
      return 'unsupported_format';
    case 'EXPORT_IN_PROGRESS':
      return 'in_progress';
    case 'EXPORT_CANCELLED':
    case 'BACKUP_CANCELLED':
      return 'cancelled';
    case 'BACKUP_VALIDATION_FAILED':
      return 'validation_failed';
    case 'BACKUP_TRANSACTION_FAILED':
      return 'transaction_failed';
    case 'BACKUP_IO_ERROR':
      return 'io_error';
    case 'BACKUP_ENCRYPTION_ERROR':
      return 'encryption_error';
    case 'PERMISSION_DENIED':
      return 'permission_denied';
    case 'BACKUP_FAILED':
      return 'backup_failed';
    case 'RESTORE_PREVIEW_FAILED':
      return 'restore_preview_failed';
    case 'RESTORE_FAILED':
      return 'restore_failed';
    case 'EXPORT_ERROR':
    case 'EXPORT_FAILED':
      return 'export_failed';
    case 'IMPORT_FAILED':
      return 'import_failed';
    case 'UNKNOWN':
      return 'unknown';
  }

  return 'unknown';
}

function isRetryable(code: BackupErrorCode): boolean {
  return code === 'EXPORT_IN_PROGRESS' || code === 'BACKUP_IO_ERROR';
}

function normalizeNativeError(operation: BackupOperation, error: unknown): NativeBackupError {
  if (error instanceof NativeBackupError) {
    return error;
  }

  const rawCode = getErrorField(error, 'code');
  const code = isBackupErrorCode(rawCode) ? rawCode : DEFAULT_ERROR_CODES[operation];
  const rawMessage = getErrorField(error, 'message');
  const message = typeof rawMessage === 'string' && rawMessage.length > 0
    ? rawMessage
    : error instanceof Error
      ? error.message
      : `Native Backup.${operation} failed`;

  return new NativeBackupError(
    operation,
    code,
    getFailureStatus(code),
    isRetryable(code),
    message,
  );
}

async function invokeJson<T>(
  operation: BackupOperation,
  call: () => Promise<string>,
): Promise<T> {
  try {
    return parseJson<T>(await call(), operation);
  } catch (error) {
    throw normalizeNativeError(operation, error);
  }
}

async function invokeVoid(
  operation: BackupOperation,
  call: () => Promise<null>,
): Promise<void> {
  try {
    await call();
  } catch (error) {
    throw normalizeNativeError(operation, error);
  }
}

type LegacyBackupModule = Omit<
  BackupModule,
  'inspectImport' | 'executeImport' | 'cancelImport' | 'undoImport' | 'getImportBatches'
>;

export const nativeBackup: LegacyBackupModule = {
  async createBackup(options: CreateBackupOptions = {}): Promise<BackupSummary> {
    return invokeJson<BackupSummary>(
      'createBackup',
      () => requireCreateBackup(getRawBackupModule('createBackup'))(JSON.stringify(options)),
    );
  },

  async restoreBackup(filePath: string): Promise<RestorePreview> {
    return invokeJson<RestorePreview>(
      'restoreBackup',
      () => requireRestoreBackup(getRawBackupModule('restoreBackup'))(filePath),
    );
  },

  async executeRestore(
    filePath: string,
    strategy: RestoreStrategy,
  ): Promise<RestoreImportResult> {
    return invokeJson<RestoreImportResult>(
      'executeRestore',
      () => requireExecuteRestore(getRawBackupModule('executeRestore'))(filePath, strategy),
    );
  },

  async exportData(format: ExportFormat = 'csv'): Promise<ExportResult> {
    return invokeJson<ExportResult>(
      'exportData',
      () => requireExportData(getRawBackupModule('exportData'))(format),
    );
  },

  // ⚠️ 2026-10-12 删除 `importData()`：原生 `BackupModule.importData` 与本 wrapper
// 一并删除（两端都是零调用）。导入走 `src/native/import.ts` 的
// `nativeImport.inspectImport` / `executeImport`（T5 新引擎），
// 见 `ImportScreen.tsx`。

  async clearData(): Promise<void> {
    await invokeVoid(
      'clearData',
      () => requireClearData(getRawBackupModule('clearData'))(),
    );
  },
};

export type NativeBackup = typeof nativeBackup;

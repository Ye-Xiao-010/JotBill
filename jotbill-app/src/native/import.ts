import { NativeModules } from 'react-native';
import type {
  BackupErrorCode,
  BackupFailureStatus,
  BackupModule,
  BackupOperation,
  ImportBatch,
  ImportExecutionResult,
  ImportInspection,
  UndoImportResult,
} from '../../../docs/types/Native';
import { NativeBackupError } from './backup';

export type NativeImport = Pick<
  BackupModule,
  'inspectImport' | 'executeImport' | 'cancelImport' | 'undoImport' | 'getImportBatches'
>;

interface RawBackupModule {
  inspectImport?: (filePath: string, password?: string | null, candidateFileName?: string | null) => Promise<string>;
  executeImport?: (filePath: string, password?: string | null, candidateFileName?: string | null) => Promise<string>;
  cancelImport?: () => Promise<null>;
  undoImport?: (batchId: string) => Promise<string>;
  getImportBatches?: () => Promise<string>;
}

type ImportOperation = Extract<
  BackupOperation,
  'inspectImport' | 'executeImport' | 'cancelImport' | 'undoImport' | 'getImportBatches'
>;

const DEFAULT_ERROR_CODES: Record<ImportOperation, BackupErrorCode> = {
  inspectImport: 'IMPORT_INSPECT_FAILED',
  executeImport: 'IMPORT_FAILED',
  cancelImport: 'IMPORT_FAILED',
  undoImport: 'IMPORT_UNDO_FAILED',
  getImportBatches: 'IMPORT_FAILED',
};

const IMPORT_ERROR_CODES: readonly BackupErrorCode[] = [
  'INVALID_ARGUMENT',
  'NOT_FOUND',
  'DATA_CONFLICT',
  'DB_ERROR',
  'NOT_IMPLEMENTED',
  'UNSUPPORTED_FORMAT',
  'IMPORT_FAILED',
  'IMPORT_IN_PROGRESS',
  'IMPORT_CANCELLED',
  'IMPORT_INSPECT_FAILED',
  'IMPORT_PASSWORD_REQUIRED',
  'IMPORT_PASSWORD_INVALID',
  'IMPORT_CANDIDATE_REQUIRED',
  'IMPORT_ZIP_INVALID',
  'IMPORT_UNDO_FAILED',
  'BACKUP_IO_ERROR',
  'BACKUP_VALIDATION_FAILED',
  'PERMISSION_DENIED',
  'UNKNOWN',
];

function getRawBackupModule(operation: ImportOperation): RawBackupModule {
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

function requireMethod<K extends keyof RawBackupModule>(
  module: RawBackupModule,
  key: K,
  operation: ImportOperation,
): NonNullable<RawBackupModule[K]> {
  const method = module[key];
  if (typeof method !== 'function') {
    throw new NativeBackupError(
      operation,
      'NOT_IMPLEMENTED',
      'not_implemented',
      false,
      `Native Backup.${operation} is not implemented on this platform`,
    );
  }
  return method as NonNullable<RawBackupModule[K]>;
}

function isImportErrorCode(value: unknown): value is BackupErrorCode {
  return typeof value === 'string' && IMPORT_ERROR_CODES.some((code) => code === value);
}

function failureStatus(code: BackupErrorCode): BackupFailureStatus {
  switch (code) {
    case 'INVALID_ARGUMENT':
      return 'invalid_argument';
    case 'NOT_FOUND':
      return 'not_found';
    case 'DB_ERROR':
      return 'database_error';
    case 'NOT_IMPLEMENTED':
      return 'not_implemented';
    case 'UNSUPPORTED_FORMAT':
      return 'unsupported_format';
    case 'IMPORT_IN_PROGRESS':
      return 'import_in_progress';
    case 'IMPORT_CANCELLED':
      return 'import_cancelled';
    case 'IMPORT_INSPECT_FAILED':
      return 'import_inspect_failed';
    case 'IMPORT_PASSWORD_REQUIRED':
    case 'IMPORT_PASSWORD_INVALID':
      return 'import_encryption_error';
    case 'IMPORT_CANDIDATE_REQUIRED':
      return 'import_candidate_required';
    case 'IMPORT_UNDO_FAILED':
      return 'import_undo_failed';
    case 'BACKUP_IO_ERROR':
      return 'io_error';
    case 'BACKUP_VALIDATION_FAILED':
      return 'validation_failed';
    case 'PERMISSION_DENIED':
      return 'permission_denied';
    case 'IMPORT_FAILED':
      return 'import_failed';
    default:
      return 'unknown';
  }
}

function isRetryable(code: BackupErrorCode): boolean {
  return code === 'IMPORT_IN_PROGRESS';
}

function normalizeError(operation: ImportOperation, error: unknown): NativeBackupError {
  if (error instanceof NativeBackupError) return error;
  const raw = error as { code?: unknown; message?: unknown } | null | undefined;
  const code = isImportErrorCode(raw?.code) ? raw.code : DEFAULT_ERROR_CODES[operation];
  const message = typeof raw?.message === 'string' && raw.message.length > 0
    ? raw.message
    : error instanceof Error
      ? error.message
      : `Native Backup.${operation} failed`;
  return new NativeBackupError(operation, code, failureStatus(code), isRetryable(code), message);
}

function parseJson<T>(value: string, operation: ImportOperation): T {
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

async function invokeJson<T>(
  operation: ImportOperation,
  call: () => Promise<string>,
): Promise<T> {
  try {
    return parseJson<T>(await call(), operation);
  } catch (error) {
    throw normalizeError(operation, error);
  }
}

async function invokeVoid(
  operation: ImportOperation,
  call: () => Promise<null>,
): Promise<void> {
  try {
    await call();
  } catch (error) {
    throw normalizeError(operation, error);
  }
}

function emptyToNull(value?: string): string | null {
  return value && value.length > 0 ? value : null;
}

export const nativeImport: NativeImport = {
  async inspectImport(
    filePath: string,
    password?: string,
    candidateFileName?: string,
  ): Promise<ImportInspection> {
    return invokeJson<ImportInspection>(
      'inspectImport',
      () => requireMethod(getRawBackupModule('inspectImport'), 'inspectImport', 'inspectImport')(
        filePath,
        emptyToNull(password),
        emptyToNull(candidateFileName),
      ),
    );
  },

  async executeImport(
    filePath: string,
    password?: string,
    candidateFileName?: string,
  ): Promise<ImportExecutionResult> {
    return invokeJson<ImportExecutionResult>(
      'executeImport',
      () => requireMethod(getRawBackupModule('executeImport'), 'executeImport', 'executeImport')(
        filePath,
        emptyToNull(password),
        emptyToNull(candidateFileName),
      ),
    );
  },

  async cancelImport(): Promise<void> {
    await invokeVoid(
      'cancelImport',
      () => requireMethod(getRawBackupModule('cancelImport'), 'cancelImport', 'cancelImport')(),
    );
  },

  async undoImport(batchId: string): Promise<UndoImportResult> {
    return invokeJson<UndoImportResult>(
      'undoImport',
      () => requireMethod(getRawBackupModule('undoImport'), 'undoImport', 'undoImport')(batchId),
    );
  },

  async getImportBatches(): Promise<ImportBatch[]> {
    return invokeJson<ImportBatch[]>(
      'getImportBatches',
      () => requireMethod(getRawBackupModule('getImportBatches'), 'getImportBatches', 'getImportBatches')(),
    );
  },
};

export type NativeImportApi = typeof nativeImport;

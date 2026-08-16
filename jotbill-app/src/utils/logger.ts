/**
 * 日志工具类
 */
import env from '../config/env';

const SENSITIVE_KEY_PATTERN =
  /password|passwd|pwd|api[-_]?key|apikey|authorization|access[-_]?token|secret|signature/i;

const SENSITIVE_VALUE_PATTERN =
  /(password|passwd|pwd|api[-_]?key|apikey|authorization|access[-_]?token|secret|signature)\s*[:=]\s*([^\s,;]+)|Bearer\s+[A-Za-z0-9._~+/-]+=*|\b[0-9]{13,19}\b|data:image\/[^;]+;base64,[A-Za-z0-9+/=]+/gi;

function redactString(value: string): string {
  return value.replace(SENSITIVE_VALUE_PATTERN, '***');
}

function redactKey(key: string): string {
  return SENSITIVE_KEY_PATTERN.test(key) ? '***' : key;
}

/**
 * 阶段七 T7：递归脱敏日志对象。密码、API Key、Token、疑似卡号与 Base64 图片整值不落日志。
 */
export function redactSensitive(value: unknown): unknown {
  if (typeof value === 'string') return redactString(value);
  if (Array.isArray(value)) return value.map(redactSensitive);
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>).map(([key, entryValue]) => {
        if (SENSITIVE_KEY_PATTERN.test(key)) return [redactKey(key), '***'];
        return [redactKey(key), redactSensitive(entryValue)];
      }),
    );
  }
  return value;
}

export enum LogLevel {
  DEBUG = 0,
  INFO = 1,
  WARN = 2,
  ERROR = 3,
}

class Logger {
  private static instance: Logger;
  private logLevel: LogLevel;
  private enableLogs: boolean;

  private constructor() {
    this.enableLogs = env.shouldEnableLogs();
    this.logLevel = env.isDevelopment() ? LogLevel.DEBUG : LogLevel.INFO;
  }

  static getInstance(): Logger {
    if (!Logger.instance) {
      Logger.instance = new Logger();
    }
    return Logger.instance;
  }

  private shouldLog(level: LogLevel): boolean {
    return this.enableLogs && level >= this.logLevel;
  }

  private formatMessage(level: string, tag: string, message: string, extra?: any): string {
    const timestamp = new Date().toISOString();
    const baseMessage = `[${timestamp}] [${level}] [${tag}] ${redactString(message)}`;

    if (extra) {
      return `${baseMessage}\n${JSON.stringify(redactSensitive(extra), null, 2)}`;
    }

    return baseMessage;
  }

  debug(tag: string, message: string, extra?: any): void {
    if (this.shouldLog(LogLevel.DEBUG)) {
      console.log(this.formatMessage('DEBUG', tag, message, extra));
    }
  }

  info(tag: string, message: string, extra?: any): void {
    if (this.shouldLog(LogLevel.INFO)) {
      console.info(this.formatMessage('INFO', tag, message, extra));
    }
  }

  warn(tag: string, message: string, extra?: any): void {
    if (this.shouldLog(LogLevel.WARN)) {
      console.warn(this.formatMessage('WARN', tag, message, extra));
    }
  }

  error(tag: string, message: string, error?: Error | any): void {
    if (this.shouldLog(LogLevel.ERROR)) {
      const errorInfo = error instanceof Error
        ? { message: redactString(error.message), stack: redactString(error.stack || '') }
        : redactSensitive(error);

      console.error(this.formatMessage('ERROR', tag, message, errorInfo));
    }
  }

  // 网络请求日志
  logRequest(method: string, url: string, data?: any): void {
    this.debug('HTTP', `${method} ${url}`, redactSensitive(data));
  }

  logResponse(method: string, url: string, status: number, data?: any): void {
    this.debug('HTTP', `${method} ${url} - ${status}`, redactSensitive(data));
  }

  logError(method: string, url: string, error: any): void {
    this.error('HTTP', `${method} ${url} - Request failed`, redactSensitive(error));
  }
}

export const logger = Logger.getInstance();
export default logger;

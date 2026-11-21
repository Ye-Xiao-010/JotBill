import { NativeModules, NativeEventEmitter, PermissionsAndroid, Platform } from 'react-native';

/** 与原生 DataModule.PENDING_CREATED_EVENT 对应的事件名。 */
export const PENDING_CREATED_EVENT = 'onPendingCreated';

/** 原生推送的待确认变更事件负载(不包含商户/订单号等敏感字段)。 */
export interface PendingCreatedEvent {
  pendingId: string;
  type: string;
  /** 金额(分);0 表示占位条目(如 AI 识别失败重试位)。 */
  amountMinor: number;
  sourceType: string;
  conflictReason: string | null;
  isNew: boolean;
}

interface RawDataModule {
  isPendingReminderEnabled(): Promise<boolean>;
  setPendingReminderEnabled(enabled: boolean): Promise<void>;
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

function getRawDataModule(): RawDataModule {
  const module = NativeModules.Data as RawDataModule | undefined;
  if (!module) {
    throw new Error('Native Data module is unavailable on this platform');
  }
  return module;
}

export const nativePendingReminder = {
  async isReminderEnabled(): Promise<boolean> {
    return getRawDataModule().isPendingReminderEnabled();
  },

  async setReminderEnabled(enabled: boolean): Promise<void> {
    await getRawDataModule().setPendingReminderEnabled(enabled);
  },

  /** API 33+ 通知运行时授权;已授权或无需授权时返回 true,拒绝返回 false。 */
  async requestNotificationPermissionIfNeeded(): Promise<boolean> {
    if (Platform.OS !== 'android' || Number(Platform.Version) < 33) {
      return true;
    }
    const permission = (PermissionsAndroid.PERMISSIONS as Record<string, string>).POST_NOTIFICATIONS;
    if (!permission) {
      return true;
    }
    const result = await PermissionsAndroid.request(
      permission as unknown as Parameters<typeof PermissionsAndroid.request>[0],
      {
        title: '待确认提醒',
        message: '允许猫猫招财在有新的待确认交易时发送系统通知',
        buttonPositive: '允许',
        buttonNegative: '拒绝',
      },
    );
    return result === PermissionsAndroid.RESULTS.GRANTED;
  },
};

/** 订阅待确认变更事件;返回的句柄用于卸载时 remove。 */
export function addPendingCreatedListener(
  listener: (event: PendingCreatedEvent) => void,
): { remove: () => void } {
  const emitter = new NativeEventEmitter(getRawDataModule() as never);
  return emitter.addListener(PENDING_CREATED_EVENT, listener);
}

/** 与原生 DataModule 发射的 onPendingChanged 事件对应(确认/忽略后的终态)。 */
export const PENDING_CHANGED_EVENT = 'onPendingChanged';

export type PendingChangedEvent =
  | { pendingId: string; ignored?: boolean; deleted?: boolean }
  | { ignoredCount: number };

/** 订阅待确认确认/忽略后的终态事件;返回的句柄用于卸载时 remove。 */
export function addPendingChangedListener(
  listener: (event: PendingChangedEvent) => void,
): { remove: () => void } {
  const emitter = new NativeEventEmitter(getRawDataModule() as never);
  return emitter.addListener(PENDING_CHANGED_EVENT, listener);
}

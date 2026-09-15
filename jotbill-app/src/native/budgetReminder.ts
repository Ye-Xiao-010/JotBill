import { NativeModules, NativeEventEmitter } from 'react-native';

/** 与原生 DataModule 发射的 onBudgetAlert 事件对应的事件名。 */
export const BUDGET_ALERT_EVENT = 'onBudgetAlert';

/** 原生推送的预算超支提醒事件负载。 */
export interface BudgetAlertEvent {
  budgetId: string;
  budgetName: string;
  /** 进度(0-1,与 getBudgetProgress 的 progress 同口径)。 */
  progress: number;
  isOverBudget: boolean;
}

interface RawDataModule {
  isBudgetReminderEnabled(): Promise<boolean>;
  setBudgetReminderEnabled(enabled: boolean): Promise<void>;
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

export const nativeBudgetReminder = {
  async isReminderEnabled(): Promise<boolean> {
    return getRawDataModule().isBudgetReminderEnabled();
  },

  async setReminderEnabled(enabled: boolean): Promise<void> {
    await getRawDataModule().setBudgetReminderEnabled(enabled);
  },
};

/** 订阅预算超支提醒事件;返回的句柄用于卸载时 remove。 */
export function addBudgetAlertListener(
  listener: (event: BudgetAlertEvent) => void,
): { remove: () => void } {
  const emitter = new NativeEventEmitter(getRawDataModule() as never);
  return emitter.addListener(BUDGET_ALERT_EVENT, listener);
}

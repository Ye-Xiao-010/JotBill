/**
 * Toast Hook —— 全局单例。
 *
 * ⚠️ 此前这里是**局部 useState**，没有 Provider、没有 Context。这导致：
 * 每个 `useToast()` 调用点都拿到各自独立的状态，而 `<Toast>` 组件必须由
 * 使用方自己渲染。全仓 grep `<Toast` 只有 `CreateBillScreen:501` 一处渲染，
 * 意味着 `CategoryManageScreen` / `AllBillsScreen` / `AddCategoryModal` /
 * `StatisticsScreen` 的 `showError(...)` 全部**指向一个不存在的 Toast** ——
 * 用户点删除分类失败、点添加分类名称为空，看到的是"点了没反应"。
 *
 * 这类"静默失败"最坏：界面上一切正常，用户以为成功了。
 *
 * 修法：提升为 Context 单例 —— 根部渲染唯一 `<Toast />`，任何位置的
 * `useToast()` 都往同一个状态里写。**对外 API 完全不变**，现有 20+ 调用点
 * 零改动。
 */
import { useState, useCallback, createContext, useContext } from 'react';
import type { ToastType } from '../components/ui/Toast';

interface ToastState {
  visible: boolean;
  message: string;
  type: ToastType;
  duration: number;
  position: 'top' | 'center' | 'bottom';
}

interface ShowToastOptions {
  type?: ToastType;
  duration?: number;
  position?: 'top' | 'center' | 'bottom';
}

export interface ToastApi {
  toastState: ToastState;
  showToast: (message: string, options?: ShowToastOptions) => void;
  hideToast: () => void;
  showSuccess: (message: string, duration?: number) => void;
  showError: (message: string, duration?: number) => void;
  showWarning: (message: string, duration?: number) => void;
  showInfo: (message: string, duration?: number) => void;
}

const initialState: ToastState = {
  visible: false,
  message: '',
  type: 'info',
  duration: 3000,
  position: 'center',
};

/**
 * 兜底实现：`useToast` 被用在 Provider 之外时（如单元测试、Storybook、
 * Provider 尚未挂载的早期渲染）不至于抛"Cannot read property of null"。
 * 那种情况下状态照样自洽，只是提示不会显示 —— 好过整棵树崩溃。
 */
const fallbackState: ToastApi = (() => {
  let state = initialState;
  const set = (next: ToastState) => {
    state = next;
  };
  const showToast = (message: string, options?: ShowToastOptions) =>
    set({
      visible: true,
      message,
      type: options?.type || 'info',
      duration: options?.duration || 3000,
      position: options?.position || 'center',
    });
  return {
    get toastState() {
      return state;
    },
    showToast,
    hideToast: () => set({ ...state, visible: false }),
    showSuccess: (m: string, d?: number) => showToast(m, { type: 'success', duration: d }),
    showError: (m: string, d?: number) => showToast(m, { type: 'error', duration: d }),
    showWarning: (m: string, d?: number) => showToast(m, { type: 'warning', duration: d }),
    showInfo: (m: string, d?: number) => showToast(m, { type: 'info', duration: d }),
  };
})();

export const ToastContext = createContext<ToastApi>(fallbackState);

/**
 * 由 `ToastProvider` 内部使用：持有唯一状态并下发便捷方法。
 * 不导出给业务代码 —— 业务只用 {@link useToast}。
 */
export const useToastProviderState = (): ToastApi => {
  const [toastState, setToastState] = useState<ToastState>(initialState);

  const showToast = useCallback((message: string, options?: ShowToastOptions) => {
    setToastState({
      visible: true,
      message,
      type: options?.type || 'info',
      duration: options?.duration || 3000,
      position: options?.position || 'center',
    });
  }, []);

  const hideToast = useCallback(() => {
    setToastState(prev => ({ ...prev, visible: false }));
  }, []);

  const showSuccess = useCallback(
    (message: string, duration?: number) => showToast(message, { type: 'success', duration }),
    [showToast],
  );
  const showError = useCallback(
    (message: string, duration?: number) => showToast(message, { type: 'error', duration }),
    [showToast],
  );
  const showWarning = useCallback(
    (message: string, duration?: number) => showToast(message, { type: 'warning', duration }),
    [showToast],
  );
  const showInfo = useCallback(
    (message: string, duration?: number) => showToast(message, { type: 'info', duration }),
    [showToast],
  );

  return {
    toastState,
    showToast,
    hideToast,
    showSuccess,
    showError,
    showWarning,
    showInfo,
  };
};

/** 取全局 Toast。**API 与此前完全一致**，现有调用点无需改动。 */
export const useToast = (): ToastApi => useContext(ToastContext);
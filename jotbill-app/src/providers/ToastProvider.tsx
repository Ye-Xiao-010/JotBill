/**
 * Toast 上下文提供者
 *
 * 把 Toast 从"每个调用点各自持有 state"提升为**全局单例**，根部渲染唯一
 * `<Toast />`。此前 `useToast` 是局部 useState 且没有 Provider，导致
 * CategoryManageScreen / AllBillsScreen / AddCategoryModal / StatisticsScreen
 * 的所有 `showError` 都写进了一个从未被渲染的组件 —— 用户看到的是
 * "点了没反应"。详见 `hooks/useToast.ts` 的说明。
 *
 * 对外 API 与原 `useToast` 完全一致，现有调用点零改动。
 */
import React, { ReactNode } from 'react';
import Toast from '../components/ui/Toast';
import { ToastContext, useToastProviderState } from '../hooks/useToast';

export const ToastProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  const api = useToastProviderState();

  return (
    <ToastContext.Provider value={api}>
      {children}
      <Toast
        visible={api.toastState.visible}
        message={api.toastState.message}
        type={api.toastState.type}
        duration={api.toastState.duration}
        position={api.toastState.position}
        onHide={api.hideToast}
      />
    </ToastContext.Provider>
  );
};
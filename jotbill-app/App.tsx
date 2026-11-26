import React, { useEffect } from 'react';
import { NativeModules, NativeEventEmitter, StatusBar } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { QueryClientProvider } from '@tanstack/react-query';
import { queryClient, invalidateCache } from './src/lib/queryClient';
import {
  addPendingChangedListener,
  addPendingCreatedListener,
  nativePendingReminder,
} from './src/native/pendingReminder';
import { addBudgetAlertListener } from './src/native/budgetReminder';
import { markBundleLoaded } from './src/services/hotUpdate';
import { useAppStateManager } from './src/hooks';
import { ThemeProvider, useTheme, AlertProvider, ToastProvider, DisplayPreferencesProvider } from './src/providers';
import AppNavigator from './src/navigation/AppNavigator';

function AppContent() {
  // 管理应用状态，处理后台缓存清理
  useAppStateManager();

  // 获取主题
  const { isDark, isSoft, isHogwarts, colors } = useTheme();

  // 热更新崩溃计数复位：JS bundle 能执行到这里即证明热包健康，必须立即复位
  // 原生侧 crash_count（原生每次启动 +1，达到 2 会静默删除热包回退内置 bundle）。
  // 此前唯一复位点挂在无人使用的 useHotUpdate 里，热包最多存活 2 次启动即被回滚。
  useEffect(() => {
    markBundleLoaded();
  }, []);

  // 自动补全可以同时更换账单账户并迁移余额，详情和账户缓存都要失效。
  useEffect(() => {
    const rawDataModule = NativeModules.Data;
    if (!rawDataModule) {
      console.warn('[App] Data module 不可用，账单创建事件无法订阅');
      return;
    }
    const emitter = new NativeEventEmitter(rawDataModule);
    const subscriptions = ['onBillCreated', 'onBillUpdated'].map(event =>
      emitter.addListener(event, () => invalidateCache.bills()),
    );
    return () => subscriptions.forEach(subscription => subscription.remove());
  }, []);

  // 待确认创建/更新事件:失效识别链路缓存,保证待确认中心及时刷新
  // (数据落库在原生侧完成,本监听只负责 UI 同步;事件负载不含敏感字段)
  useEffect(() => {
    const subscription = addPendingCreatedListener(() => {
      invalidateCache.recognition();
    });
    nativePendingReminder.requestNotificationPermissionIfNeeded().catch(() => undefined);
    return () => subscription.remove();
  }, []);

  // 待确认确认/忽略后的终态事件：确认会写账单+删待确认+更新余额，忽略会删待确认，
  // 两侧缓存都要失效（此前只刷 recognition，确认入账后账单页仍是旧数据）。
  useEffect(() => {
    const subscription = addPendingChangedListener(() => {
      invalidateCache.recognition();
      invalidateCache.bills();
    });
    return () => subscription.remove();
  }, []);

  // 预算超支告警事件:刷新预算进度缓存(系统通知之外的 JS 侧 UI 同步通道)
  useEffect(() => {
    const subscription = addBudgetAlertListener(() => {
      invalidateCache.budgets();
    });
    return () => subscription.remove();
  }, []);

  return (
    <>
      <StatusBar
        barStyle={isDark ? 'light-content' : 'dark-content'}
        backgroundColor={isSoft || isHogwarts ? colors.background : colors.surface}
      />
      <AppNavigator />
    </>
  );
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <SafeAreaProvider>
        <ThemeProvider>
          <DisplayPreferencesProvider>
            <AlertProvider>
              {/* ToastProvider 必须包在 AppContent 之外：它持有全局唯一的 Toast 状态
                  并渲染那唯一的一个 <Toast />。此前没有 Provider，多个页面的
                  showError 写进了各自独立的、未被渲染的 state，用户看不到任何提示。 */}
              <ToastProvider>
                <AppContent />
              </ToastProvider>
            </AlertProvider>
          </DisplayPreferencesProvider>
        </ThemeProvider>
      </SafeAreaProvider>
    </QueryClientProvider>
  );
}

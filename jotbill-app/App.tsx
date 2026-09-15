import React, { useEffect } from 'react';
import { NativeModules, NativeEventEmitter, StatusBar } from 'react-native';
import { Provider as PaperProvider, MD3DarkTheme, MD3LightTheme } from 'react-native-paper';
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
import { ThemeProvider, useTheme, AlertProvider } from './src/providers';
import AppNavigator from './src/navigation/AppNavigator';

const { PaymentNotificationModule } = NativeModules;

function AppContent() {
  // 管理应用状态，处理后台缓存清理
  useAppStateManager();

  // 获取主题
  const { isDark, colors } = useTheme();

  // 热更新崩溃计数复位：JS bundle 能执行到这里即证明热包健康，必须立即复位
  // 原生侧 crash_count（原生每次启动 +1，达到 2 会静默删除热包回退内置 bundle）。
  // 此前唯一复位点挂在无人使用的 useHotUpdate 里，热包最多存活 2 次启动即被回滚。
  useEffect(() => {
    markBundleLoaded();
  }, []);

  // 监听账单创建成功事件
  useEffect(() => {
    console.log('[App] 开始注册账单创建事件监听器...');

    if (!PaymentNotificationModule) {
      console.warn('[App] PaymentNotificationModule 不可用');
      return;
    }

    console.log('[App] PaymentNotificationModule 可用，创建事件监听器');
    const eventEmitter = new NativeEventEmitter(PaymentNotificationModule);

    const subscription = eventEmitter.addListener('onBillCreated', () => {
      console.log('========================================');
      console.log('[App] ✓ 收到账单创建成功事件！');
      console.log('[App] 开始刷新数据...');
      console.log('========================================');

      // 使账单相关的所有缓存失效
      invalidateCache.bills();

      console.log('[App] 缓存失效完成，等待重新获取数据');
    });

    console.log('[App] ✓ 事件监听器注册成功');

    return () => {
      console.log('[App] 注销事件监听器');
      subscription.remove();
    };
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

  // 待确认确认/忽略后的终态事件:刷新识别链路缓存,核销页与待确认入口及时同步
  useEffect(() => {
    const subscription = addPendingChangedListener(() => {
      invalidateCache.recognition();
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

  // 配置 Paper 主题
  const paperTheme = isDark
    ? { ...MD3DarkTheme, colors: { ...MD3DarkTheme.colors, primary: colors.primary } }
    : { ...MD3LightTheme, colors: { ...MD3LightTheme.colors, primary: colors.primary } };

  return (
    <PaperProvider theme={paperTheme}>
      <StatusBar barStyle="dark-content" backgroundColor={colors.surface} />
      <AppNavigator />
    </PaperProvider>
  );
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <SafeAreaProvider>
        <ThemeProvider>
          <AlertProvider>
            <AppContent />
          </AlertProvider>
        </ThemeProvider>
      </SafeAreaProvider>
    </QueryClientProvider>
  );
}

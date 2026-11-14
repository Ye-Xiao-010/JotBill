/**
 * 展示偏好 Provider
 *
 * 管理首页可选的展示模块（当前仅「预算与目标」卡片）。
 * 默认关闭：首页保持简洁，用户可在「设置 → 显示」中开启；
 * 开关状态持久化到 AsyncStorage，重启后保留。
 */
import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
} from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';

export const DASHBOARD_BUDGET_STORAGE_KEY = '@app_dashboard_show_budget';

interface DisplayPreferencesContextType {
  /** 首页是否显示预算与目标卡片（默认 false） */
  showDashboardBudget: boolean;
  /** 设置该开关；立即生效并异步持久化 */
  setShowDashboardBudget: (value: boolean) => void;
  /** 本地偏好是否已读取完成（用于避免开关闪烁 / 请求提前触发） */
  isLoaded: boolean;
}

const DisplayPreferencesContext = createContext<
  DisplayPreferencesContextType | undefined
>(undefined);

export function DisplayPreferencesProvider({
  children,
}: {
  children: React.ReactNode;
}) {
  const [showDashboardBudget, setShowDashboardBudgetState] = useState(false);
  const [isLoaded, setIsLoaded] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const saved = await AsyncStorage.getItem(DASHBOARD_BUDGET_STORAGE_KEY);
        if (!cancelled && saved !== null) {
          setShowDashboardBudgetState(saved === 'true');
        }
      } catch (error) {
        console.log('[DisplayPreferencesProvider] 加载展示偏好失败:', error);
      } finally {
        if (!cancelled) {
          setIsLoaded(true);
        }
      }
    };
    load();
    return () => {
      cancelled = true;
    };
  }, []);

  const setShowDashboardBudget = useCallback((value: boolean) => {
    setShowDashboardBudgetState(value);
    AsyncStorage.setItem(
      DASHBOARD_BUDGET_STORAGE_KEY,
      value ? 'true' : 'false',
    ).catch(error => {
      console.log('[DisplayPreferencesProvider] 保存展示偏好失败:', error);
    });
  }, []);

  return (
    <DisplayPreferencesContext.Provider
      value={{ showDashboardBudget, setShowDashboardBudget, isLoaded }}
    >
      {children}
    </DisplayPreferencesContext.Provider>
  );
}

export function useDisplayPreferences(): DisplayPreferencesContextType {
  const context = useContext(DisplayPreferencesContext);
  if (context === undefined) {
    throw new Error(
      'useDisplayPreferences must be used within a DisplayPreferencesProvider',
    );
  }
  return context;
}

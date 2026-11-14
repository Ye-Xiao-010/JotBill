/**
 * 展示偏好（首页预算与目标开关）测试
 * 覆盖：默认关闭、开启即写入、重启读回、非法值兜底。
 */
import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import AsyncStorage from '@react-native-async-storage/async-storage';
import {
  DASHBOARD_BUDGET_STORAGE_KEY,
  DisplayPreferencesProvider,
  useDisplayPreferences,
} from '../src/providers/DisplayPreferencesProvider';

let captured: ReturnType<typeof useDisplayPreferences> | null = null;

function Probe() {
  captured = useDisplayPreferences();
  return null;
}

async function mountProvider() {
  await ReactTestRenderer.act(async () => {
    ReactTestRenderer.create(
      <DisplayPreferencesProvider>
        <Probe />
      </DisplayPreferencesProvider>,
    );
  });
}

describe('DisplayPreferencesProvider', () => {
  beforeEach(async () => {
    captured = null;
    await AsyncStorage.clear();
    jest.restoreAllMocks();
  });

  it('首次启动无存储：首页预算默认关闭', async () => {
    await mountProvider();
    expect(captured).not.toBeNull();
    expect(captured!.showDashboardBudget).toBe(false);
    expect(captured!.isLoaded).toBe(true);
  });

  it('开启开关立即生效并写入持久化键', async () => {
    await mountProvider();
    const spy = jest.spyOn(AsyncStorage, 'setItem');

    await ReactTestRenderer.act(async () => {
      captured!.setShowDashboardBudget(true);
    });

    expect(captured!.showDashboardBudget).toBe(true);
    expect(spy).toHaveBeenCalledWith(DASHBOARD_BUDGET_STORAGE_KEY, 'true');
  });

  it('读回已保存的 true', async () => {
    await AsyncStorage.setItem(DASHBOARD_BUDGET_STORAGE_KEY, 'true');
    await mountProvider();
    expect(captured!.showDashboardBudget).toBe(true);
  });

  it('非法存储值按关闭处理，不崩溃', async () => {
    await AsyncStorage.setItem(DASHBOARD_BUDGET_STORAGE_KEY, 'yes');
    await mountProvider();
    expect(captured!.showDashboardBudget).toBe(false);
  });
});

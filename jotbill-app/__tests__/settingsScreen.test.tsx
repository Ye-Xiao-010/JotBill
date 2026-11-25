/**
 * 设置页（方案 C · Bento 双卡控制台）渲染与交互测试
 *
 * 覆盖：
 * 1. Bento 双卡职责独立：左卡专注自动记账（不含待确认），右卡专注待确认中心，彻底不展示净资产；
 * 2. 右上角唯一浅/深色切换按钮生效，底部「外观与显示」无重复深色模式开关；
 * 3. 4 格财务快捷（账户/分类/预算/目标）与「自动化与数据」5 行完整跳转，无重复待确认中心；
 * 4. 「首页预算与目标」位于「主题风格」上方且可切换；
 * 5. 「主题风格」默认折叠 → 点击展开 3 张主题主卡（魔法羊皮纸排首位 → 柔光 → 经典积木四色聚合）；
 *    点击魔法羊皮纸展开 5 枚院徽+格言、魔法音效试听与桌面图标开关；点击经典积木展开 4 色并切换；
 * 6. soft / blocks / hogwarts × 浅/深色模式均可正常渲染。
 */
import React from 'react';
import { Platform, Switch, Text, TouchableOpacity } from 'react-native';
import ReactTestRenderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { DisplayPreferencesProvider, ThemeProvider } from '../src/providers';
import SettingsScreen from '../src/screens/SettingsScreen';
import { nativeTheme } from '../src/native/theme';

jest.mock('@react-navigation/native', () => ({
  useFocusEffect: jest.fn(),
}));

const mockGetBookkeepingSettings = jest.fn();
const mockGetRecognitionStatus = jest.fn();
const mockGetRecognitionPermissions = jest.fn();
const mockGetAccounts = jest.fn();
const mockGetCategories = jest.fn();
const mockGetRules = jest.fn();
const mockGetAiProviders = jest.fn();

jest.mock('../src/native', () => ({
  nativeAutoBookkeeping: {
    getSettings: (...args: unknown[]) => mockGetBookkeepingSettings(...args),
  },
  nativeRecognition: {
    getRecognitionStatus: (...args: unknown[]) => mockGetRecognitionStatus(...args),
  },
  nativeRuntime: {
    getRecognitionPermissions: (...args: unknown[]) =>
      mockGetRecognitionPermissions(...args),
  },
  nativeData: {
    getAccounts: (...args: unknown[]) => mockGetAccounts(...args),
    getCategories: (...args: unknown[]) => mockGetCategories(...args),
    getRules: (...args: unknown[]) => mockGetRules(...args),
  },
  nativeAi: {
    getAiProviders: (...args: unknown[]) => mockGetAiProviders(...args),
  },
}));

function collectTexts(node: ReactTestRenderer.ReactTestInstance): string[] {
  return node.findAllByType(Text).map(t => {
    const children = t.props.children;
    if (Array.isArray(children)) {
      return children.map(c => (c == null ? '' : String(c))).join('');
    }
    return children == null ? '' : String(children);
  });
}

async function flushMicrotasks() {
  for (let i = 0; i < 4; i += 1) {
    await ReactTestRenderer.act(async () => {
      await new Promise<void>(resolve => setTimeout(resolve, 0));
    });
  }
}

async function renderSettings(
  themeName: 'soft' | 'blocks' | 'hogwarts' = 'soft',
  mode: 'light' | 'dark' = 'light',
) {
  await AsyncStorage.clear();
  await AsyncStorage.setItem('@app_theme_name', themeName);
  await AsyncStorage.setItem('@app_theme_mode', mode);
  const navigate = jest.fn();
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    renderer = ReactTestRenderer.create(
      <QueryClientProvider client={client}>
        <ThemeProvider>
          <DisplayPreferencesProvider>
            <SettingsScreen navigation={{ navigate }} />
          </DisplayPreferencesProvider>
        </ThemeProvider>
      </QueryClientProvider>,
    );
  });
  await flushMicrotasks();
  const unmount = async () => {
    await ReactTestRenderer.act(async () => {
      renderer.unmount();
    });
    client.clear();
  };
  return { renderer, navigate, unmount };
}

describe('SettingsScreen 方案 C 重构回归测试', () => {
  const originalPlatformOs = Platform.OS;

  beforeEach(() => {
    jest.clearAllMocks();
    Object.defineProperty(Platform, 'OS', {
      configurable: true,
      value: 'android',
    });
    mockGetBookkeepingSettings.mockResolvedValue({
      masterEnabled: true,
      silentBookkeepingEnabled: true,
      autoBookedNotificationEnabled: true,
      keepAliveEnabled: false,
      keepAlivePrompted: true,
      keepAliveRunning: false,
      channels: { wechat: true, alipay: true, unionpay: true, sms: true },
      channelMeta: [
        { key: 'wechat', label: '微信', description: '', defaultEnabled: true },
        { key: 'alipay', label: '支付宝', description: '', defaultEnabled: true },
        { key: 'unionpay', label: '云闪付', description: '', defaultEnabled: true },
        { key: 'sms', label: '银行短信', description: '', defaultEnabled: true },
      ],
    });
    mockGetRecognitionStatus.mockResolvedValue({ pendingCount: 2 });
    mockGetRecognitionPermissions.mockResolvedValue({
      accessibility: 'ready',
      notificationAccess: 'ready',
      overlay: 'ready',
      appNotifications: 'ready',
    });
    mockGetAccounts.mockResolvedValue([
      { id: 'a1', name: '微信零钱', isActive: true },
      { id: 'a2', name: '支付宝', isActive: true },
      { id: 'a3', name: '旧卡', isActive: false },
    ]);
    mockGetCategories.mockResolvedValue([
      { id: 'c1', name: '餐饮' },
      { id: 'c2', name: '交通' },
      { id: 'c3', name: '购物' },
    ]);
    mockGetRules.mockResolvedValue([{ id: 'r1' }, { id: 'r2' }]);
    mockGetAiProviders.mockResolvedValue([
      { id: 'p1', name: '通义千问', provider: 'qwen', isDefault: true },
    ]);
  });

  afterAll(() => {
    Object.defineProperty(Platform, 'OS', {
      configurable: true,
      value: originalPlatformOs,
    });
  });

  it('Bento 双卡职责独立（自动记账不含待确认、不展示净资产），且 4 格财务与 5 行自动化均可跳转', async () => {
    const { renderer, navigate, unmount } = await renderSettings('soft', 'light');
    const allTexts = collectTexts(renderer.root);
    const joined = allTexts.join(' ');

    // 彻底不展示净资产
    expect(joined).not.toContain('净资产');

    // 自动记账卡：不含“待确认”字样
    const buttons = renderer.root.findAllByType(TouchableOpacity);
    const autoCard = buttons.find(b =>
      String(b.props.accessibilityLabel || '').startsWith('自动记账'),
    );
    const reviewCard = buttons.find(b =>
      String(b.props.accessibilityLabel || '').startsWith('待确认中心'),
    );
    expect(autoCard).toBeTruthy();
    expect(reviewCard).toBeTruthy();
    expect(collectTexts(autoCard!).join(' ')).not.toContain('待确认');
    expect(collectTexts(autoCard!).join(' ')).toContain('已开启');
    expect(collectTexts(autoCard!).join(' ')).toContain('4/4 渠道');

    // 待确认中心只在 Bento 右卡出现一次，下方列表不重复
    const reviewMatches = buttons.filter(b =>
      String(b.props.accessibilityLabel || '').startsWith('待确认中心'),
    );
    expect(reviewMatches).toHaveLength(1);
    expect(collectTexts(reviewCard!).join(' ')).toContain('2 笔待核');

    // 财务 4 格计数与状态汇总
    expect(allTexts).toContain('2 个'); // 活跃账户数（过滤掉 isActive=false）
    expect(allTexts).toContain('3 个'); // 分类数
    expect(allTexts).toContain('2 条'); // 规则数
    expect(allTexts).toContain('4/4 就绪');
    expect(allTexts).toContain('通义千问');

    // 验证全部核心路由跳转
    const pressByPrefix = async (prefix: string) => {
      const btn = renderer.root
        .findAllByType(TouchableOpacity)
        .find(b => String(b.props.accessibilityLabel || '').startsWith(prefix));
      expect(btn).toBeTruthy();
      await ReactTestRenderer.act(async () => {
        btn!.props.onPress();
      });
    };

    await pressByPrefix('自动记账');
    await pressByPrefix('待确认中心');
    await pressByPrefix('账户管理');
    await pressByPrefix('分类管理');
    await pressByPrefix('预算管理');
    await pressByPrefix('目标管理');
    await pressByPrefix('识别与分类规则');
    await pressByPrefix('权限与提醒');
    await pressByPrefix('运行状态');
    await pressByPrefix('AI 服务商');
    await pressByPrefix('数据管理');

    expect(navigate.mock.calls.map(c => c[0])).toEqual([
      'AutoBookkeeping',
      'Review',
      'Accounts',
      'Categories',
      'Budgets',
      'FinancialGoals',
      'Rules',
      'GeneralSettings',
      'RuntimeStatus',
      'AiProviders',
      'DataManagement',
    ]);

    await unmount();
  });

  it('深色模式仅在右上角切换、底部无重复深色开关，且首页预算开关位于主题风格上方', async () => {
    const { renderer, unmount } = await renderSettings('soft', 'light');
    const allTexts = collectTexts(renderer.root);

    // 底部只有 1 个 Switch（首页预算与目标），没有重复的“深色模式”行
    const switches = renderer.root.findAllByType(Switch);
    expect(switches).toHaveLength(1);
    expect(switches[0].props.accessibilityLabel).toBe('首页预算与目标');
    expect(allTexts).not.toContain('深色模式');

    // 首页预算与目标位于主题风格之前
    expect(allTexts.indexOf('首页预算与目标')).toBeGreaterThan(-1);
    expect(allTexts.indexOf('主题风格')).toBeGreaterThan(
      allTexts.indexOf('首页预算与目标'),
    );

    // 点击右上角深色按钮可即时切换浅/深色
    const darkBtn = renderer.root
      .findAllByType(TouchableOpacity)
      .find(b => b.props.accessibilityLabel === '切换到深色模式');
    expect(darkBtn).toBeTruthy();
    await ReactTestRenderer.act(async () => {
      darkBtn!.props.onPress();
    });
    const lightBtn = renderer.root
      .findAllByType(TouchableOpacity)
      .find(b => b.props.accessibilityLabel === '切换到浅色模式');
    expect(lightBtn).toBeTruthy();

    await unmount();
  });

  it('主题风格默认折叠，点击展开三大主题卡（魔法首位含四学院下拉 + 柔光 + 经典积木四色聚合）', async () => {
    const soundSpy = jest.spyOn(nativeTheme, 'playSound');
    const { renderer, unmount } = await renderSettings('soft', 'light');

    // 默认折叠：未渲染 3 张主题主卡
    expect(collectTexts(renderer.root)).not.toContain('HOGWARTS EDITION');
    expect(collectTexts(renderer.root)).not.toContain('经典积木');

    // 1) 点击「主题风格」展开三大主题主卡
    const themeTrigger = renderer.root
      .findAllByType(TouchableOpacity)
      .find(b => String(b.props.accessibilityLabel || '').startsWith('主题风格'));
    expect(themeTrigger).toBeTruthy();
    await ReactTestRenderer.act(async () => {
      themeTrigger!.props.onPress();
    });

    const themeCards = renderer.root
      .findAllByType(TouchableOpacity)
      .filter(b => String(b.props.accessibilityLabel || '').startsWith('主题 '));
    expect(themeCards.map(b => b.props.accessibilityLabel)).toEqual([
      '主题 魔法羊皮纸',
      '主题 柔光，当前使用中',
      '主题 经典积木，当前 积木蓝',
    ]);

    // 2) 点击排第一位的「魔法羊皮纸」→ 切换主题并展开 5 枚院徽与介绍 + 音效试听 + 桌面图标
    const hpBtn = renderer.root
      .findAllByType(TouchableOpacity)
      .find(b => String(b.props.accessibilityLabel || '').startsWith('主题 魔法羊皮纸'));
    expect(hpBtn).toBeTruthy();
    await ReactTestRenderer.act(async () => {
      hpBtn!.props.onPress();
    });

    const hpTexts = collectTexts(renderer.root);
    expect(hpTexts).toContain('古灵阁设置');
    expect(hpTexts).toContain('霍格沃茨古金 ✓');
    expect(hpTexts).toContain('格兰芬多');
    expect(hpTexts).toContain('绯红鎏金 · 勇气与火');
    expect(hpTexts).toContain('斯莱特林');
    expect(hpTexts).toContain('拉文克劳');
    expect(hpTexts).toContain('赫奇帕奇');
    expect(hpTexts).toContain('魔法交互音效');
    expect(hpTexts).toContain('同步切换桌面图标');

    // 试听魔法音效
    const auditionBtn = renderer.root
      .findAllByType(TouchableOpacity)
      .find(b => b.props.accessibilityLabel === '试听魔法音效');
    expect(auditionBtn).toBeTruthy();
    await ReactTestRenderer.act(async () => {
      auditionBtn!.props.onPress();
    });
    expect(soundSpy).toHaveBeenCalled();

    // 3) 点击「经典积木」→ 自动收起学院下拉，并展开 4 种积木配色
    const brutalBtn = renderer.root
      .findAllByType(TouchableOpacity)
      .find(b => String(b.props.accessibilityLabel || '').startsWith('主题 经典积木'));
    expect(brutalBtn).toBeTruthy();
    await ReactTestRenderer.act(async () => {
      brutalBtn!.props.onPress();
    });

    const brutalTexts = collectTexts(renderer.root);
    expect(brutalTexts).not.toContain('格兰芬多');
    expect(brutalTexts).toContain('积木蓝');
    expect(brutalTexts).toContain('薄荷账本');
    expect(brutalTexts).toContain('落日橙');
    expect(brutalTexts).toContain('墨黑');

    // 选择「薄荷账本」
    const mintBtn = renderer.root
      .findAllByType(TouchableOpacity)
      .find(b => String(b.props.accessibilityLabel || '').startsWith('主题 薄荷账本'));
    expect(mintBtn).toBeTruthy();
    await ReactTestRenderer.act(async () => {
      mintBtn!.props.onPress();
    });
    expect(collectTexts(renderer.root)).toContain('经典积木 · 薄荷账本');

    soundSpy.mockRestore();
    await unmount();
  });

  it.each([
    ['soft', 'light'],
    ['soft', 'dark'],
    ['blocks', 'light'],
    ['blocks', 'dark'],
    ['hogwarts', 'light'],
    ['hogwarts', 'dark'],
  ] as const)('全主题渲染不报错且权限异常/暂停状态正常反映：%s / %s', async (theme, mode) => {
    mockGetBookkeepingSettings.mockResolvedValueOnce({
      masterEnabled: false,
      silentBookkeepingEnabled: false,
      autoBookedNotificationEnabled: true,
      keepAliveEnabled: false,
      keepAlivePrompted: false,
      keepAliveRunning: false,
      channels: {},
      channelMeta: [],
    });
    mockGetRecognitionPermissions.mockResolvedValueOnce({
      accessibility: 'missing',
      notificationAccess: 'ready',
      overlay: 'ready',
      appNotifications: 'ready',
    });
    mockGetRecognitionStatus.mockResolvedValueOnce({ pendingCount: 0 });

    const { renderer, unmount } = await renderSettings(theme, mode);
    const texts = collectTexts(renderer.root);
    expect(texts).toContain('已暂停');
    expect(texts).toContain('已清空');
    expect(texts).toContain('1 项异常');
    await unmount();
  });
});

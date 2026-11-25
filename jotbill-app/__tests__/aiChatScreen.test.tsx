import React from 'react';
import { Platform, Text } from 'react-native';
import ReactTestRenderer from 'react-test-renderer';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { AlertProvider, ThemeProvider } from '../src/providers';
import AIChatScreen, { matchAccountIdByName } from '../src/screens/ai/AIChatScreen';
import type { Account, Category } from '../src/native';
import type { AIModelConfig, StreamCallbacks } from '../src/types/ai';

const mockNavigate = jest.fn();
const mockGoBack = jest.fn();

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: mockNavigate, goBack: mockGoBack }),
  useRoute: () => ({ params: {} }),
  useFocusEffect: (cb: () => void) => {
    const ReactLib = require('react');
    ReactLib.useEffect(() => cb(), [cb]);
  },
}));

jest.mock('react-native-image-picker', () => ({
  launchCamera: jest.fn(),
  launchImageLibrary: jest.fn(),
}));

jest.mock('react-native-image-viewing', () => () => null);

jest.mock('../src/services/audio/audioRecorderService', () => ({
  audioRecorderService: {
    startRecording: jest.fn(),
    stopRecording: jest.fn(),
    cancelRecording: jest.fn(),
    deleteRecordingFile: jest.fn(),
  },
}));

jest.mock('../src/hooks/useChatSessions', () => ({
  useChatSessions: () => ({
    sessions: [],
    setSessions: jest.fn(),
    isLoading: false,
    isLoadingMore: false,
    error: null,
    hasMore: false,
    loadMore: jest.fn(),
    reload: jest.fn(),
  }),
}));

const mockGetCategories = jest.fn();
const mockGetAccounts = jest.fn();
const mockAddBill = jest.fn();

jest.mock('../src/native', () => ({
  nativeData: {
    getCategories: (...args: unknown[]) => mockGetCategories(...args),
    getAccounts: (...args: unknown[]) => mockGetAccounts(...args),
    addBill: (...args: unknown[]) => mockAddBill(...args),
  },
}));

const mockGetConfigs = jest.fn();
const mockStreamMessage = jest.fn();
const mockGetChatSession = jest.fn();

jest.mock('../src/services/ai', () => ({
  aiService: {
    getConfigs: (...args: unknown[]) => mockGetConfigs(...args),
    streamMessage: (...args: unknown[]) => mockStreamMessage(...args),
    getChatSession: (...args: unknown[]) => mockGetChatSession(...args),
    transcribeAudio: jest.fn(),
    renameChatSession: jest.fn(),
    togglePinChatSession: jest.fn(),
    deleteChatSession: jest.fn(),
  },
}));

const mockAccounts: Account[] = [
  {
    id: 'acc-wx',
    name: '微信',
    subtype: 'e_wallet',
    kind: 'asset',
    initialBalanceMinor: 50000,
    currentBalanceMinor: 50000,
    currency: 'CNY',
    sortOrder: 0,
    isActive: true,
    createdAt: '2026-11-01T00:00:00Z',
    updatedAt: '2026-11-01T00:00:00Z',
  },
  {
    id: 'acc-cmb',
    name: '招商银行',
    subtype: 'bank_debit',
    kind: 'asset',
    initialBalanceMinor: 200000,
    currentBalanceMinor: 200000,
    currency: 'CNY',
    cardTail: '8848',
    sortOrder: 1,
    isActive: true,
    createdAt: '2026-11-01T00:00:00Z',
    updatedAt: '2026-11-01T00:00:00Z',
  },
];

const mockCategories: Category[] = [
  {
    id: 'cat-food',
    name: '餐饮',
    type: 'expense',
    icon: 'utensils',
    isDefault: true,
    sortOrder: 0,
    createdAt: '2026-11-01T00:00:00Z',
    updatedAt: '2026-11-01T00:00:00Z',
  },
];

const mockConfigs: AIModelConfig[] = [
  {
    id: 'cfg-qwen',
    name: '千问 Plus',
    provider: 'qwen',
    model: 'qwen-plus',
    isDefault: true,
    supportsVision: true,
    createdAt: '2026-11-01T00:00:00Z',
    updatedAt: '2026-11-01T00:00:00Z',
  },
  {
    id: 'cfg-ds',
    name: 'DeepSeek',
    provider: 'deepseek',
    model: 'deepseek-chat',
    isDefault: false,
    supportsVision: false,
    createdAt: '2026-11-01T00:00:00Z',
    updatedAt: '2026-11-01T00:00:00Z',
  },
];

function collectTexts(node: ReactTestRenderer.ReactTestInstance): string[] {
  return node.findAllByType(Text).map((t) => {
    const children = t.props.children;
    if (Array.isArray(children)) {
      return children.map((c) => (c == null ? '' : String(c))).join('');
    }
    return children == null ? '' : String(children);
  });
}

async function flushMicrotasks() {
  for (let i = 0; i < 6; i += 1) {
    await ReactTestRenderer.act(async () => {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    });
  }
}

async function renderChat(
  themeName: 'soft' | 'blocks' | 'hogwarts' = 'soft',
  mode: 'light' | 'dark' = 'light',
) {
  await AsyncStorage.clear();
  await AsyncStorage.setItem('@app_theme_name', themeName);
  await AsyncStorage.setItem('@app_theme_mode', mode);
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    renderer = ReactTestRenderer.create(
      <SafeAreaProvider
        initialMetrics={{
          frame: { x: 0, y: 0, width: 390, height: 844 },
          insets: { top: 0, left: 0, right: 0, bottom: 0 },
        }}
      >
        <ThemeProvider>
          <AlertProvider>
            <AIChatScreen />
          </AlertProvider>
        </ThemeProvider>
      </SafeAreaProvider>,
    );
  });
  await flushMicrotasks();
  const unmount = async () => {
    await ReactTestRenderer.act(async () => {
      renderer.unmount();
    });
  };
  return { renderer, unmount };
}

describe('AIChatScreen 升级回归测试', () => {
  const originalPlatformOs = Platform.OS;

  beforeEach(() => {
    jest.clearAllMocks();
    Object.defineProperty(Platform, 'OS', {
      configurable: true,
      value: 'android',
    });
    mockGetCategories.mockResolvedValue(mockCategories);
    mockGetAccounts.mockResolvedValue(mockAccounts);
    mockGetConfigs.mockResolvedValue({ success: true, data: mockConfigs });
    mockStreamMessage.mockImplementation(() => ({ close: jest.fn() }));
  });

  afterAll(() => {
    Object.defineProperty(Platform, 'OS', {
      configurable: true,
      value: originalPlatformOs,
    });
  });

  test('matchAccountIdByName 支持精确名称、尾号与唯一模糊匹配', () => {
    expect(matchAccountIdByName('微信', mockAccounts)).toBe('acc-wx');
    expect(matchAccountIdByName('招行尾号8848', mockAccounts)).toBe('acc-cmb');
    expect(matchAccountIdByName('招商', mockAccounts)).toBe('acc-cmb');
    expect(matchAccountIdByName('不存在银行', mockAccounts)).toBeUndefined();
  });

  test('未配置 AI 服务商时展示引导横幅并可一键跳转 AiProviders', async () => {
    mockGetConfigs.mockResolvedValue({ success: true, data: [] });
    const { renderer, unmount } = await renderChat('soft', 'light');
    try {
      expect(renderer.root.findByProps({ testID: 'ai-unconfigured-banner' })).toBeTruthy();
      const ctaBtn = renderer.root.findByProps({ testID: 'ai-setup-cta-btn' });
      await ReactTestRenderer.act(async () => {
        ctaBtn.props.onPress();
      });
      expect(mockNavigate).toHaveBeenCalledWith('AiProviders');
    } finally {
      await unmount();
    }
  });

  test('顶部模型切换器可切换 DeepSeek 并向 streamMessage 传入 configId，同时正确渲染账单/账户/预算/分类占比卡片', async () => {
    let capturedCallbacks: StreamCallbacks | undefined;
    mockStreamMessage.mockImplementation((_req: unknown, callbacks: StreamCallbacks) => {
      capturedCallbacks = callbacks;
      return { close: jest.fn() };
    });

    const { renderer, unmount } = await renderChat('soft', 'dark');
    try {
      // 1. 切换模型到 DeepSeek
      const switcherBtn = renderer.root.findByProps({ testID: 'ai-header-model-switcher' });
      await ReactTestRenderer.act(async () => {
        switcherBtn.props.onPress();
      });
      const dsItem = renderer.root.findByProps({ testID: 'ai-switcher-item-cfg-ds' });
      await ReactTestRenderer.act(async () => {
        dsItem.props.onPress();
      });

      // 2. 点击快捷建议发起对话
      const quickPromptBtn = renderer.root.findByProps({ testID: 'ai-suggestion-s2' });
      await ReactTestRenderer.act(async () => {
        quickPromptBtn.props.onPress();
      });

      expect(mockStreamMessage).toHaveBeenCalledWith(
        expect.objectContaining({
          configId: 'cfg-ds',
          content: '帮我记一笔：微信付午饭 28 元',
        }),
        expect.any(Object),
      );
      expect(capturedCallbacks).toBeDefined();

      // 3. 模拟全部 4 类富卡片工具结果（create_bills / get_statistics / get_accounts / get_budgets）
      await ReactTestRenderer.act(async () => {
        capturedCallbacks!.onToolResult?.({
          toolCallId: 'tc-1',
          toolName: 'create_bills',
          result: {
            success: true,
            message: '已整理 1 条待确认账单',
            data: {
              bills: [
                {
                  amount: 28,
                  type: 'expense',
                  description: '午饭',
                  categoryName: '餐饮',
                  accountName: '招商银行',
                  date: '2026-11-25',
                },
              ],
            },
          },
        });
        capturedCallbacks!.onToolResult?.({
          toolCallId: 'tc-2',
          toolName: 'get_statistics',
          result: {
            success: true,
            message: '统计完成',
            data: {
              totalIncome: 5000,
              totalExpense: 100,
              balance: 4900,
              expenseByCategory: [{ categoryName: '餐饮', amount: 60, percentage: 60, count: 2 }],
              platformBreakdown: [{ platform: '美团', amount: 60, percentage: 60, count: 2 }],
              monthlyTrends: [
                { month: '2026-10', income: 4800, expense: 120, balance: 4680 },
                { month: '2026-11', income: 5000, expense: 100, balance: 4900 },
              ],
            },
          },
        });
        capturedCallbacks!.onToolResult?.({
          toolCallId: 'tc-3',
          toolName: 'get_accounts',
          result: {
            success: true,
            message: '共 2 个启用账户，净资产 2500.00 元',
            data: {
              totalAssets: 2500,
              totalLiabilities: 0,
              netWorth: 2500,
              accounts: [
                { id: 'acc-cmb', name: '招商银行', subtype: 'bank_debit', kind: 'asset', balance: 2000, cardTail: '8848' },
              ],
            },
          },
        });
        capturedCallbacks!.onToolResult?.({
          toolCallId: 'tc-4',
          toolName: 'get_budgets',
          result: {
            success: true,
            message: '共 1 项启用预算',
            data: {
              budgets: [
                {
                  id: 'bud-1',
                  name: '本月总预算',
                  periodLabel: '11/01–11/30',
                  limit: 1000,
                  spent: 350,
                  remaining: 650,
                  progressPct: 35,
                  isOverBudget: false,
                  alertAt: 80,
                },
              ],
            },
          },
        });
        capturedCallbacks!.onToolResult?.({
          toolCallId: 'tc-5',
          toolName: 'get_goals',
          result: {
            success: true,
            message: '共 1 项财务目标',
            data: {
              goals: [
                {
                  id: 'goal-1',
                  name: '应急备用金',
                  target: 10000,
                  current: 4000,
                  remaining: 6000,
                  progressPct: 40,
                  isCompleted: false,
                  targetDate: '2026-12-25',
                  daysLeft: 30,
                },
              ],
            },
          },
        });
        capturedCallbacks!.onDone?.({ sessionId: 'sess-1', thinkingTimeMs: 420 });
      });

      const allTexts = collectTexts(renderer.root);
      const joined = allTexts.join(' ');
      // CategoryIcon 渲染图标而非直接打印原始 lucide 文本名
      expect(allTexts).not.toContain('utensils');
      // BillCard 自动匹配账户并渲染 AccountBadge
      expect(joined).toContain('招商银行');
      expect(joined).toContain('8848');
      // 分类占比、平台分布、月度趋势、账户净资产、预算与目标卡片均正常渲染
      expect(joined).toContain('主要支出分类');
      expect(joined).toContain('餐饮 (60%)');
      expect(joined).toContain('消费平台分布');
      expect(joined).toContain('美团 (60%)');
      expect(joined).toContain('按月收支趋势');
      expect(joined).toContain('2026-10');
      expect(joined).toContain('净资产');
      expect(joined).toContain('本月总预算');
      expect(joined).toContain('35%');
      expect(joined).toContain('应急备用金');
      expect(joined).toContain('40%');

      // 4. 在 EditBillModal 中切换账单账户为微信并保存
      const editBtn = renderer.root.find(
        (node) => typeof node.props.testID === 'string' && node.props.testID.startsWith('ai-bill-edit-'),
      );
      await ReactTestRenderer.act(async () => {
        editBtn.props.onPress();
      });
      const wxChip = renderer.root.findByProps({ testID: 'edit-bill-account-acc-wx' });
      await ReactTestRenderer.act(async () => {
        wxChip.props.onPress();
      });
      await ReactTestRenderer.act(async () => {
        renderer.root.findByProps({ testID: 'edit-bill-save-btn' }).props.onPress();
      });

      const updatedTexts = collectTexts(renderer.root).join(' ');
      expect(updatedTexts).toContain('微信');
    } finally {
      await unmount();
    }
  });

  test.each([
    ['soft', 'light'],
    ['blocks', 'dark'],
    ['hogwarts', 'light'],
    ['hogwarts', 'dark'],
  ] as const)('在 %s (%s) 主题下正常渲染顶部模型切换器', async (themeName, mode) => {
    const { renderer, unmount } = await renderChat(themeName, mode);
    try {
      expect(renderer.root.findByProps({ testID: 'ai-header-model-switcher' })).toBeTruthy();
    } finally {
      await unmount();
    }
  });
});

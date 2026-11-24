/**
 * 首页与统计页账单账户彩色微标渲染测试
 *
 * 覆盖：
 * 1. DashboardScreen 在三类主题（soft / blocks / hogwarts）× 浅/深模式下的账户微标渲染、
 *    真实 `<AccountIcon />` 复用、信用卡虚线框、新增地方银行自动配色、历史无账户账单兜底，
 *    以及同钱包平台文案去重 vs 跨渠道保留；
 * 2. 统计页 DailyView（brutal / hogwarts）选中日明细通过 `<BillItem>` 展示账户微标；
 * 3. 统计页 SoftIncomeExpenseView（soft）选中日明细与分类下钻明细均展示账户微标。
 */
import React from 'react';
import { StyleSheet, Text, TouchableOpacity } from 'react-native';
import ReactTestRenderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import AsyncStorage from '@react-native-async-storage/async-storage';
import {
  AlertProvider,
  DisplayPreferencesProvider,
  ThemeProvider,
  ToastProvider,
} from '../src/providers';
import DashboardScreen from '../src/screens/dashboard/DashboardScreen';
import AllBillsScreen from '../src/screens/finance/AllBillsScreen';
import DailyView from '../src/screens/reports/DailyView';
import SoftIncomeExpenseView from '../src/screens/reports/SoftIncomeExpenseView';
import AccountBadge from '../src/components/business/AccountBadge';
import AccountIcon from '../src/components/business/AccountIcon';
import { HOGWARTS_FONTS } from '../src/constants/hogwartsAssets';
import type { Account, Bill, Category } from '../src/native';

const mockNavigate = jest.fn();
const mockUseFocusEffect = jest.fn();

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: mockNavigate, goBack: jest.fn() }),
  useRoute: () => ({ params: {} }),
  useFocusEffect: (...args: unknown[]) => mockUseFocusEffect(...args),
  useIsFocused: () => false,
}));

const mockGetBills = jest.fn();
const mockGetBillStatistics = jest.fn();
const mockGetCategories = jest.fn();
const mockGetAccounts = jest.fn();
const mockGetRecognitionStatus = jest.fn();
const mockGetBudgetProgress = jest.fn();
const mockGetFinancialGoals = jest.fn();

jest.mock('../src/native', () => ({
  nativeData: {
    getBills: (...args: unknown[]) => mockGetBills(...args),
    getBillStatistics: (...args: unknown[]) => mockGetBillStatistics(...args),
    getCategories: (...args: unknown[]) => mockGetCategories(...args),
    getAccounts: (...args: unknown[]) => mockGetAccounts(...args),
    getBudgetProgress: (...args: unknown[]) => mockGetBudgetProgress(...args),
    getFinancialGoals: (...args: unknown[]) => mockGetFinancialGoals(...args),
  },
  nativeRecognition: {
    getRecognitionStatus: (...args: unknown[]) => mockGetRecognitionStatus(...args),
  },
  nativeRuntime: {
    getRecognitionPermissions: jest.fn().mockResolvedValue(null),
    openRecognitionPermissionSettings: jest.fn(),
  },
}));

const SAMPLE_CATEGORIES: Category[] = [
  {
    id: 'cat-food',
    name: '餐饮',
    type: 'expense',
    icon: 'utensils',
    color: '#FF6B6B',
    isDefault: true,
    sortOrder: 0,
    createdAt: '2026-11-01T00:00:00.000Z',
    updatedAt: '2026-11-01T00:00:00.000Z',
  },
  {
    id: 'cat-shop',
    name: '购物',
    type: 'expense',
    icon: 'shopping-bag',
    color: '#45B7D1',
    isDefault: true,
    sortOrder: 1,
    createdAt: '2026-11-01T00:00:00.000Z',
    updatedAt: '2026-11-01T00:00:00.000Z',
  },
];

const SAMPLE_ACCOUNTS: Account[] = [
  {
    id: 'acc-wechat',
    name: '微信零钱',
    subtype: 'e_wallet',
    kind: 'asset',
    initialBalanceMinor: 0,
    currentBalanceMinor: 82000,
    currency: 'CNY',
    icon: 'brand:wechat',
    color: '#00A67E',
    sortOrder: 0,
    isActive: true,
    createdAt: '2026-11-01T00:00:00.000Z',
    updatedAt: '2026-11-01T00:00:00.000Z',
  },
  {
    id: 'acc-psbc',
    name: '中国邮政储蓄银行',
    subtype: 'bank_debit',
    kind: 'asset',
    initialBalanceMinor: 0,
    currentBalanceMinor: 500000,
    currency: 'CNY',
    icon: 'bank:psbc',
    cardTail: '0176',
    sortOrder: 1,
    isActive: true,
    createdAt: '2026-11-01T00:00:00.000Z',
    updatedAt: '2026-11-01T00:00:00.000Z',
  },
  {
    id: 'acc-cmb-cc',
    name: '招商银行信用卡',
    subtype: 'credit_card',
    kind: 'liability',
    initialBalanceMinor: 0,
    currentBalanceMinor: -128000,
    currency: 'CNY',
    icon: 'bank:cmbchina',
    cardTail: '8821',
    sortOrder: 2,
    isActive: true,
    createdAt: '2026-11-01T00:00:00.000Z',
    updatedAt: '2026-11-01T00:00:00.000Z',
  },
  {
    id: 'acc-scrcu',
    name: '四川农商银行',
    subtype: 'bank_debit',
    kind: 'asset',
    initialBalanceMinor: 0,
    currentBalanceMinor: 310000,
    currency: 'CNY',
    cardTail: '2510',
    sortOrder: 3,
    isActive: true,
    createdAt: '2026-11-01T00:00:00.000Z',
    updatedAt: '2026-11-01T00:00:00.000Z',
  },
];

const TODAY_ISO = '2026-11-24T04:32:00.000Z';

const SAMPLE_BILLS: Bill[] = [
  {
    id: 'bill-wechat',
    amountMinor: 2850,
    type: 'expense',
    categoryId: 'cat-food',
    accountId: 'acc-wechat',
    description: '瑞幸咖啡',
    platform: 'com.tencent.mm',
    sourceType: 'accessibility',
    occurredAt: TODAY_ISO,
    version: 1,
    createdAt: TODAY_ISO,
    updatedAt: TODAY_ISO,
  },
  {
    id: 'bill-psbc-via-wx',
    amountMinor: 16800,
    type: 'expense',
    categoryId: 'cat-shop',
    accountId: 'acc-psbc',
    description: '盒马鲜生',
    platform: 'com.tencent.mm',
    sourceType: 'accessibility',
    occurredAt: TODAY_ISO,
    version: 1,
    createdAt: TODAY_ISO,
    updatedAt: TODAY_ISO,
  },
  {
    id: 'bill-cmb-cc',
    amountMinor: 49900,
    type: 'expense',
    categoryId: 'cat-shop',
    accountId: 'acc-cmb-cc',
    description: '优衣库外套',
    platform: 'com.eg.android.AlipayGphone',
    sourceType: 'accessibility',
    occurredAt: TODAY_ISO,
    version: 1,
    createdAt: TODAY_ISO,
    updatedAt: TODAY_ISO,
  },
  {
    id: 'bill-scrcu',
    amountMinor: 4500,
    type: 'expense',
    categoryId: 'cat-food',
    accountId: 'acc-scrcu',
    description: '牛肉面',
    platform: 'com.android.mms',
    sourceType: 'notification',
    occurredAt: TODAY_ISO,
    version: 1,
    createdAt: TODAY_ISO,
    updatedAt: TODAY_ISO,
  },
  {
    id: 'bill-unbound',
    amountMinor: 1200,
    type: 'expense',
    categoryId: 'cat-food',
    description: '路边煎饼',
    sourceType: 'manual',
    occurredAt: TODAY_ISO,
    version: 1,
    createdAt: TODAY_ISO,
    updatedAt: TODAY_ISO,
  },
];

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

async function renderWithTheme(
  node: React.ReactElement,
  themeName: 'soft' | 'blocks' | 'hogwarts',
  mode: 'light' | 'dark',
) {
  await AsyncStorage.clear();
  await AsyncStorage.setItem('@app_theme_name', themeName);
  await AsyncStorage.setItem('@app_theme_mode', mode);
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    renderer = ReactTestRenderer.create(
      <QueryClientProvider client={client}>
        <ThemeProvider>
          <DisplayPreferencesProvider>
            <ToastProvider>
              <AlertProvider>{node}</AlertProvider>
            </ToastProvider>
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
  return { renderer, unmount };
}

describe('首页与统计页账户彩色微标渲染测试', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockGetCategories.mockResolvedValue(SAMPLE_CATEGORIES);
    mockGetAccounts.mockResolvedValue(SAMPLE_ACCOUNTS);
    mockGetBills.mockResolvedValue({
      items: SAMPLE_BILLS,
      total: SAMPLE_BILLS.length,
      hasMore: false,
    });
    mockGetBillStatistics.mockResolvedValue({
      totalIncome: 0,
      totalExpense: 75250,
      balance: -75250,
      incomeCount: 0,
      expenseCount: SAMPLE_BILLS.length,
      expenseCategoryStats: [
        {
          categoryId: 'cat-shop',
          categoryName: '购物',
          categoryIcon: 'shopping-bag',
          amountMinor: 66700,
          percentage: 88.6,
          count: 2,
        },
        {
          categoryId: 'cat-food',
          categoryName: '餐饮',
          categoryIcon: 'utensils',
          amountMinor: 8550,
          percentage: 11.4,
          count: 3,
        },
      ],
      incomeCategoryStats: [],
      dailyTrends: [
        { date: '2026-11-24', income: 0, expense: 75250 },
      ],
      monthlyTrends: [],
    });
    mockGetRecognitionStatus.mockResolvedValue({ pendingCount: 0 });
    mockGetBudgetProgress.mockResolvedValue([]);
    mockGetFinancialGoals.mockResolvedValue([]);
  });

  const themeCases: Array<{
    theme: 'soft' | 'blocks' | 'hogwarts';
    mode: 'light' | 'dark';
    expectedRadius: number;
  }> = [
    { theme: 'soft', mode: 'light', expectedRadius: 999 },
    { theme: 'soft', mode: 'dark', expectedRadius: 999 },
    { theme: 'blocks', mode: 'light', expectedRadius: 8 },
    { theme: 'blocks', mode: 'dark', expectedRadius: 8 },
    { theme: 'hogwarts', mode: 'light', expectedRadius: 8 },
    { theme: 'hogwarts', mode: 'dark', expectedRadius: 8 },
  ];

  it.each(themeCases)(
    'DashboardScreen 在 $theme / $mode 下正确展示账户微标、真实图标、虚线信用卡、新增银行与平台去重',
    async ({ theme, mode, expectedRadius }) => {
      const { renderer, unmount } = await renderWithTheme(
        <DashboardScreen />,
        theme,
        mode,
      );

      // 5 笔账单中有 4 笔绑定了账户，第 5 笔历史无账户不显示微标
      const badges = renderer.root.findAllByType(AccountBadge);
      expect(badges).toHaveLength(4);

      const icons = renderer.root.findAllByType(AccountIcon);
      expect(icons).toHaveLength(4);

      const allTexts = collectTexts(renderer.root);
      expect(allTexts).toContain('微信零钱');
      expect(allTexts).toContain('邮储银行');
      expect(allTexts).toContain('· 0176');
      expect(allTexts).toContain('招商银行信用卡');
      expect(allTexts).toContain('· 8821');
      expect(allTexts).toContain('四川农商银行');
      expect(allTexts).toContain('· 2510');
      expect(allTexts).toContain('路边煎饼');

      // 校验不同账户颜色不同、信用卡虚线、储蓄卡实线与主题圆角
      const badgeContainers = badges.map(b =>
        StyleSheet.flatten(b.children[0] && typeof b.children[0] !== 'string' ? b.children[0].props.style : {}),
      );
      expect(badgeContainers.every(s => s.borderRadius === expectedRadius)).toBe(true);

      const psbcStyle = badgeContainers[1];
      const cmbStyle = badgeContainers[2];
      const scrcuStyle = badgeContainers[3];
      expect(psbcStyle.borderStyle).toBe('solid');
      expect(cmbStyle.borderStyle).toBe('dashed');
      expect(scrcuStyle.borderStyle).toBe('solid');
      expect(
        new Set(badgeContainers.map(s => s.backgroundColor)).size,
      ).toBeGreaterThanOrEqual(3);

      // 魔法羊皮纸主题字体校验
      if (theme === 'hogwarts') {
        const nameText = badges[0].findAllByType(Text)[0];
        expect(StyleSheet.flatten(nameText.props.style).fontFamily).toBe(
          HOGWARTS_FONTS.body,
        );
      }

      // 元信息去重：瑞幸咖啡（微信零钱 + 微信支付）不重复显示「微信支付」，
      // 盒马鲜生（邮储银行 + 微信支付）保留「微信支付」
      const buttons = renderer.root.findAllByType(TouchableOpacity);
      const luckinRow = buttons.find(b =>
        String(b.props.accessibilityLabel || '').startsWith('瑞幸咖啡'),
      );
      const hemaRow = buttons.find(b =>
        String(b.props.accessibilityLabel || '').startsWith('盒马鲜生'),
      );
      expect(luckinRow).toBeTruthy();
      expect(hemaRow).toBeTruthy();
      expect(collectTexts(luckinRow!).join(' ')).not.toContain('微信支付');
      expect(collectTexts(hemaRow!).join(' ')).toContain('微信支付');

      await unmount();
    },
  );

  it('统计页 DailyView（积木/魔法羊皮纸）选中日明细展示对应账户彩色微标', async () => {
    for (const theme of ['blocks', 'hogwarts'] as const) {
      const { renderer, unmount } = await renderWithTheme(
        <DailyView
          initialYear={2026}
          initialMonth={10}
          initialSelectedDay="2026-11-24"
        />,
        theme,
        'light',
      );

      const badges = renderer.root.findAllByType(AccountBadge);
      expect(badges).toHaveLength(4);
      const texts = collectTexts(renderer.root);
      expect(texts).toContain('邮储银行');
      expect(texts).toContain('· 0176');
      expect(texts).toContain('四川农商银行');
      expect(texts).toContain('· 2510');
      await unmount();
    }
  });

  it('统计页 SoftIncomeExpenseView（柔光浅/深）当日明细与分类下钻均展示账户微标', async () => {
    const { renderer, unmount } = await renderWithTheme(
      <SoftIncomeExpenseView />,
      'soft',
      'dark',
    );

    // 1) 当日明细（今日 2026-11-24，4 笔带账户账单）
    expect(renderer.root.findAllByType(AccountBadge)).toHaveLength(4);

    // 2) 点击「购物」分类行下钻展开明细
    const shopRow = renderer.root
      .findAllByType(TouchableOpacity)
      .find(b => String(b.props.accessibilityLabel || '').startsWith('购物'));
    expect(shopRow).toBeTruthy();

    mockGetBills.mockResolvedValueOnce({
      items: [SAMPLE_BILLS[1], SAMPLE_BILLS[2]], // 盒马(邮储) + 优衣库(招行信用卡)
      total: 2,
      hasMore: false,
    });

    await ReactTestRenderer.act(async () => {
      shopRow!.props.onPress();
    });
    await flushMicrotasks();

    // 当日明细 4 个 + 分类下钻 2 个 = 6 个 AccountBadge（下钻的为 compact）
    const allBadges = renderer.root.findAllByType(AccountBadge);
    expect(allBadges).toHaveLength(6);
    expect(allBadges.filter(b => b.props.compact === true)).toHaveLength(2);
    const texts = collectTexts(renderer.root);
    expect(texts).toContain('优衣库外套');
    expect(texts).toContain('招商银行信用卡');

    await unmount();
  });

  it('全部账单页 AllBillsScreen 在浅/深主题下展示扣款账户微标与尾号', async () => {
    mockUseFocusEffect.mockImplementation((effect: () => void | (() => void)) => {
      React.useEffect(() => effect(), [effect]);
    });

    for (const [theme, mode] of [
      ['soft', 'light'],
      ['blocks', 'dark'],
      ['hogwarts', 'light'],
    ] as const) {
      const { renderer, unmount } = await renderWithTheme(
        <AllBillsScreen />,
        theme,
        mode,
      );

      const badges = renderer.root.findAllByType(AccountBadge);
      expect(badges).toHaveLength(4);
      const texts = collectTexts(renderer.root);
      expect(texts).toContain('微信零钱');
      expect(texts).toContain('邮储银行');
      expect(texts).toContain('· 0176');
      expect(texts).toContain('招商银行信用卡');
      expect(texts).toContain('· 8821');
      expect(texts).toContain('四川农商银行');
      expect(texts).toContain('· 2510');
      await unmount();
    }

    mockUseFocusEffect.mockReset();
  });
});

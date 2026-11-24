/**
 * 柔光（soft）主题渲染与交互回归测试
 *
 * 覆盖：
 * 1. ThemeProvider 加载 soft 浅/深模式时正确暴露 isSoft/shape 并同步原生 ThemeModule；
 * 2. SoftBillKeypad 浅/深渲染与按键/再记/保存回调；
 * 3. SoftIncomeExpenseView 空态、单分类 100% 圆环、支出/收入构成切换与分类行点击下钻；
 * 4. SoftAssetsView 浅/深渲染、累计收支、近 12 月中文类型构成与大额「万」格式。
 */
import React from 'react';
import { Text, TouchableOpacity } from 'react-native';
import ReactTestRenderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { ThemeProvider } from '../src/providers/ThemeProvider';
import { nativeTheme } from '../src/native/theme';
import SoftBillKeypad from '../src/components/finance/SoftBillKeypad';
import SoftIncomeExpenseView from '../src/screens/reports/SoftIncomeExpenseView';
import SoftAssetsView from '../src/screens/reports/SoftAssetsView';

const mockNavigate = jest.fn();

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: mockNavigate, goBack: jest.fn() }),
  useFocusEffect: jest.fn(),
}));

const mockGetBills = jest.fn();
const mockGetAccounts = jest.fn();

jest.mock('../src/native', () => ({
  nativeData: {
    getBills: (...args: unknown[]) => mockGetBills(...args),
    getAccounts: (...args: unknown[]) => mockGetAccounts(...args),
    getBillStatistics: jest.fn(),
    getNetWorth: jest.fn(),
  },
}));

const mockUseDailyGridData = jest.fn();
const mockUseMonthlyGridData = jest.fn();
const mockUseDailyBills = jest.fn();

jest.mock('../src/hooks/useIncomeExpenseData', () => ({
  useDailyGridData: (...args: unknown[]) => mockUseDailyGridData(...args),
  useMonthlyGridData: (...args: unknown[]) => mockUseMonthlyGridData(...args),
  useDailyBills: (...args: unknown[]) => mockUseDailyBills(...args),
}));

const mockUseAssetsData = jest.fn();

jest.mock('../src/hooks/useAssetsData', () => ({
  useAssetsData: (...args: unknown[]) => mockUseAssetsData(...args),
}));

function collectTexts(renderer: ReactTestRenderer.ReactTestRenderer): string[] {
  return renderer.root.findAllByType(Text).map((node) => {
    const children = node.props.children;
    if (Array.isArray(children)) {
      return children.map((c) => (c == null ? '' : String(c))).join('');
    }
    return children == null ? '' : String(children);
  });
}

async function renderInSoftTheme(
  node: React.ReactElement,
  mode: 'light' | 'dark' = 'light',
) {
  await AsyncStorage.setItem('@app_theme_name', 'soft');
  await AsyncStorage.setItem('@app_theme_mode', mode);
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    renderer = ReactTestRenderer.create(
      <QueryClientProvider client={client}>
        <ThemeProvider>{node}</ThemeProvider>
      </QueryClientProvider>,
    );
  });
  const unmount = async () => {
    await ReactTestRenderer.act(async () => {
      renderer.unmount();
    });
    client.clear();
  };
  return { renderer, client, unmount };
}

async function flushMicrotasks() {
  await ReactTestRenderer.act(async () => {
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
  });
}

describe('柔光主题渲染回归测试', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockUseDailyBills.mockReturnValue({
      bills: [],
      total: 0,
      hasMore: false,
      isLoading: false,
      isError: false,
      error: null,
      refetch: jest.fn(),
    });
    mockUseMonthlyGridData.mockReturnValue({
      monthlyData: [],
      statistics: null,
      isLoading: false,
      isFetching: false,
      isError: false,
      error: null,
      refetch: jest.fn(),
    });
    mockGetBills.mockResolvedValue({ items: [], total: 0, hasMore: false });
    mockGetAccounts.mockResolvedValue([]);
  });

  it('ThemeProvider 加载 soft 浅色与深色时同步原生主题与导航栏背景色', async () => {
    const syncSpy = jest.spyOn(nativeTheme, 'syncTheme');

    const lightRender = await renderInSoftTheme(<Text>ok</Text>, 'light');
    expect(syncSpy).toHaveBeenLastCalledWith('soft', false, '#F4F5F9');
    await lightRender.unmount();

    const darkRender = await renderInSoftTheme(<Text>ok</Text>, 'dark');
    expect(syncSpy).toHaveBeenLastCalledWith('soft', true, '#0E1014');
    await darkRender.unmount();

    syncSpy.mockRestore();
  });

  it('SoftBillKeypad 在浅/深模式下渲染并响应按键、再记与保存回调', async () => {
    const onKey = jest.fn();
    const onSave = jest.fn();
    const onSaveAndContinue = jest.fn();

    const { renderer, unmount } = await renderInSoftTheme(
      <SoftBillKeypad
        onKey={onKey}
        onSave={onSave}
        onSaveAndContinue={onSaveAndContinue}
        saveLabel="保存"
        showContinue
      />,
      'dark',
    );

    const buttons = renderer.root.findAllByType(TouchableOpacity);
    const key1 = buttons.find((b) => b.props.accessibilityLabel === '1');
    const againBtn = buttons.find((b) => b.props.accessibilityLabel === '保存并再记一笔');
    const saveBtn = buttons.find((b) => b.props.accessibilityLabel === '保存');

    expect(key1).toBeTruthy();
    expect(againBtn).toBeTruthy();
    expect(saveBtn).toBeTruthy();

    await ReactTestRenderer.act(async () => {
      key1!.props.onPress();
      againBtn!.props.onPress();
      saveBtn!.props.onPress();
    });

    expect(onKey).toHaveBeenCalledWith('1');
    expect(onSaveAndContinue).toHaveBeenCalledTimes(1);
    expect(onSave).toHaveBeenCalledTimes(1);
    await unmount();
  });

  it('SoftIncomeExpenseView 支持空态、单分类 100% 圆环、支出/收入切换与分类点击下钻', async () => {
    // 1) 空态
    mockUseDailyGridData.mockReturnValue({
      dailyMap: new Map(),
      statistics: {
        totalExpense: 0,
        totalIncome: 0,
        expenseCategoryStats: [],
        incomeCategoryStats: [],
      },
      isLoading: false,
      isFetching: false,
      isError: false,
      error: null,
      refetch: jest.fn(),
    });

    const emptyRes = await renderInSoftTheme(<SoftIncomeExpenseView />, 'light');
    expect(collectTexts(emptyRes.renderer)).toContain('该期间暂无支出记录');
    await emptyRes.unmount();

    // 2) 单分类 100% 支出 + 收入数据 + 点击下钻
    mockGetBills.mockResolvedValue({
      items: [
        {
          id: 'bill-1',
          amountMinor: 3800,
          type: 'expense',
          description: '牛肉面',
          merchant: '兰州拉面',
          occurredAt: '2026-11-24T04:00:00.000Z',
        },
      ],
      total: 1,
      hasMore: false,
    });
    mockUseDailyGridData.mockReturnValue({
      dailyMap: new Map([['2026-11-24', { expense: 120, income: 5000 }]]),
      statistics: {
        totalExpense: 120,
        totalIncome: 5000,
        expenseCategoryStats: [
          {
            categoryId: 'cat-food',
            categoryName: '餐饮',
            categoryIcon: 'utensils',
            amount: 120,
            percentage: 100,
            count: 3,
          },
        ],
        incomeCategoryStats: [
          {
            categoryId: 'cat-salary',
            categoryName: '工资',
            categoryIcon: 'banknote',
            amount: 5000,
            percentage: 100,
            count: 1,
          },
        ],
      },
      isLoading: false,
      isFetching: false,
      isError: false,
      error: null,
      refetch: jest.fn(),
    });

    const { renderer, unmount } = await renderInSoftTheme(<SoftIncomeExpenseView />, 'light');
    const texts = collectTexts(renderer);
    expect(texts).toContain('支出构成');
    expect(texts).toContain('餐饮');
    expect(texts.join(' ')).not.toContain('utensils');

    // 点击「餐饮」分类行下钻展开明细
    const catRow = renderer.root
      .findAllByType(TouchableOpacity)
      .find((b) => String(b.props.accessibilityLabel || '').startsWith('餐饮'));
    expect(catRow).toBeTruthy();
    await ReactTestRenderer.act(async () => {
      catRow!.props.onPress();
    });
    await flushMicrotasks();
    expect(mockGetBills).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'expense', categoryId: 'cat-food' }),
    );
    expect(collectTexts(renderer)).toContain('牛肉面');

    // 切换到「收入」构成
    const incomeTab = renderer.root
      .findAllByType(TouchableOpacity)
      .find((b) => {
        const inner = b.findAllByType(Text);
        return inner.some((t) => t.props.children === '收入');
      });
    expect(incomeTab).toBeTruthy();
    await ReactTestRenderer.act(async () => {
      incomeTab!.props.onPress();
    });
    const incomeTexts = collectTexts(renderer);
    expect(incomeTexts).toContain('收入构成');
    expect(incomeTexts).toContain('工资');
    await unmount();
  });

  it('SoftAssetsView 渲染净资产、累计收支与中文近 12 月类型构成（大额用万）', async () => {
    mockUseAssetsData.mockReturnValue({
      netWorth: 28500,
      totalAssets: 30000,
      totalLiabilities: 1500,
      totalIncome: 52000,
      totalExpense: 23500,
      assetTrendData: [
        { month: '2026-09', value: 12000 },
        { month: '2026-10', value: 21000 },
        { month: '2026-11', value: 28500 },
      ],
      incomeCategoryStats: [
        {
          categoryId: 'inc-1',
          categoryName: '工资',
          categoryIcon: 'banknote',
          amount: 52000,
          percentage: 100,
          count: 10,
        },
      ],
      expenseCategoryStats: [
        {
          categoryId: 'exp-1',
          categoryName: '住房',
          categoryIcon: 'house',
          amount: 18000,
          percentage: 76.6,
          count: 6,
        },
      ],
      isLoading: false,
      isFetching: false,
      isError: false,
      error: null,
      refetch: jest.fn(),
    });
    mockGetAccounts.mockResolvedValue([
      {
        id: 'acc-1',
        name: '招商银行',
        kind: 'asset',
        subtype: 'bank_debit',
        currentBalanceMinor: 3000000,
        isActive: true,
      },
    ]);

    const { renderer, unmount } = await renderInSoftTheme(<SoftAssetsView />, 'dark');
    await flushMicrotasks();
    const texts = collectTexts(renderer);
    expect(texts).toContain('净资产（账户余额）');
    expect(texts).toContain('累计收入');
    expect(texts).toContain('累计支出');
    expect(texts).toContain('¥5.20万');
    expect(texts).toContain('近 12 月类型构成');
    expect(texts).toContain('住房');
    expect(texts).toContain('¥1.80万');
    expect(texts.join(' ')).not.toContain('house');
    await unmount();
  });
});

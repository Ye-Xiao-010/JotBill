import React from 'react';
import { Text } from 'react-native';
import ReactTestRenderer, { act } from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { AlertProvider, ThemeProvider } from '../src/providers';
import AllBillsScreen from '../src/screens/finance/AllBillsScreen';
import type { Account, Bill, Category } from '../src/native';

const mockNavigate = jest.fn();

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: mockNavigate, goBack: jest.fn() }),
  useRoute: () => ({ params: {} }),
  useFocusEffect: (cb: () => void) => {
    const ReactLib = require('react');
    ReactLib.useEffect(() => cb(), [cb]);
  },
}));

const mockGetBills = jest.fn();
const mockGetCategories = jest.fn();
const mockGetAccounts = jest.fn();

jest.mock('../src/native', () => ({
  nativeData: {
    getBills: (...args: unknown[]) => mockGetBills(...args),
    getCategories: (...args: unknown[]) => mockGetCategories(...args),
    getAccounts: (...args: unknown[]) => mockGetAccounts(...args),
  },
}));

const CATEGORIES: Category[] = [
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
  {
    id: 'cat-coffee',
    name: '咖啡饮品',
    type: 'expense',
    icon: 'coffee',
    parentId: 'cat-food',
    isDefault: false,
    sortOrder: 1,
    createdAt: '2026-11-01T00:00:00Z',
    updatedAt: '2026-11-01T00:00:00Z',
  },
  {
    id: 'cat-transport',
    name: '交通',
    type: 'expense',
    icon: 'car',
    isDefault: true,
    sortOrder: 2,
    createdAt: '2026-11-01T00:00:00Z',
    updatedAt: '2026-11-01T00:00:00Z',
  },
  {
    id: 'cat-salary',
    name: '工资',
    type: 'income',
    icon: 'wallet',
    isDefault: true,
    sortOrder: 3,
    createdAt: '2026-11-01T00:00:00Z',
    updatedAt: '2026-11-01T00:00:00Z',
  },
];

const ACCOUNTS: Account[] = [
  {
    id: 'acc-wx',
    name: '微信零钱',
    subtype: 'e_wallet',
    kind: 'asset',
    initialBalanceMinor: 50000,
    currentBalanceMinor: 50000,
    currency: 'CNY',
    icon: 'brand:wechat',
    sortOrder: 0,
    isActive: true,
    createdAt: '2026-11-01T00:00:00Z',
    updatedAt: '2026-11-01T00:00:00Z',
  },
  {
    id: 'acc-cmb',
    name: '招行卡',
    subtype: 'bank_debit',
    kind: 'asset',
    initialBalanceMinor: 200000,
    currentBalanceMinor: 200000,
    currency: 'CNY',
    icon: 'bank:cmbchina',
    cardTail: '8848',
    sortOrder: 1,
    isActive: true,
    createdAt: '2026-11-01T00:00:00Z',
    updatedAt: '2026-11-01T00:00:00Z',
  },
];

const SAMPLE_BILLS: Bill[] = [
  {
    id: 'bill-coffee',
    amountMinor: 3500,
    type: 'expense',
    occurredAt: '2026-11-25T10:00:00Z',
    description: '瑞幸冰美式',
    categoryId: 'cat-coffee',
    accountId: 'acc-wx',
    merchant: '瑞幸咖啡',
    sourceType: 'manual',
    createdAt: '2026-11-25T10:00:00Z',
    updatedAt: '2026-11-25T10:00:00Z',
    version: 1,
  },
  {
    id: 'bill-taxi',
    amountMinor: 12000,
    type: 'expense',
    occurredAt: '2026-11-20T09:00:00Z',
    description: '滴滴快车去机场',
    categoryId: 'cat-transport',
    accountId: 'acc-cmb',
    merchant: '滴滴出行',
    sourceType: 'manual',
    createdAt: '2026-11-20T09:00:00Z',
    updatedAt: '2026-11-20T09:00:00Z',
    version: 1,
  },
];

function collectTexts(root: ReactTestRenderer.ReactTestInstance): string {
  return root
    .findAllByType(Text)
    .map(node => {
      const c = node.props.children;
      if (Array.isArray(c)) {
        return c.map(item => (item == null ? '' : String(item))).join('');
      }
      return c == null ? '' : String(c);
    })
    .join(' ');
}

async function flush(ms = 0) {
  for (let i = 0; i < 4; i += 1) {
    await act(async () => {
      await new Promise<void>(resolve => setTimeout(resolve, ms));
    });
  }
}

async function renderAllBills(
  theme: 'soft' | 'blocks' | 'hogwarts' = 'soft',
  mode: 'light' | 'dark' = 'light',
) {
  await AsyncStorage.clear();
  await AsyncStorage.setItem('@app_theme_name', theme);
  await AsyncStorage.setItem('@app_theme_mode', mode);

  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });

  let renderer!: ReactTestRenderer.ReactTestRenderer;
  await act(async () => {
    renderer = ReactTestRenderer.create(
      <SafeAreaProvider
        initialMetrics={{
          frame: { x: 0, y: 0, width: 390, height: 844 },
          insets: { top: 0, left: 0, right: 0, bottom: 0 },
        }}
      >
        <QueryClientProvider client={queryClient}>
          <ThemeProvider>
            <AlertProvider>
              <AllBillsScreen />
            </AlertProvider>
          </ThemeProvider>
        </QueryClientProvider>
      </SafeAreaProvider>,
    );
  });
  await flush();

  const cleanup = async () => {
    await act(async () => {
      renderer.unmount();
    });
    queryClient.clear();
  };

  return { renderer, cleanup };
}

describe('AllBillsScreen 搜索与多维筛选测试', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockGetCategories.mockResolvedValue(CATEGORIES);
    mockGetAccounts.mockResolvedValue(ACCOUNTS);
    mockGetBills.mockResolvedValue({
      items: SAMPLE_BILLS,
      total: SAMPLE_BILLS.length,
      hasMore: false,
    });
  });

  test('支持防抖关键词搜索、账户弹窗筛选、父分类自动包含子分类、按金额降序排序与一键重置', async () => {
    const { renderer, cleanup } = await renderAllBills('soft', 'light');
    try {
      // 1. 关键词搜索（防抖 250ms 后传递 keyword）
      const searchInput = renderer.root.findByProps({ testID: 'all-bills-search-input' });
      await act(async () => {
        searchInput.props.onChangeText('瑞幸');
      });
      await act(async () => {
        await new Promise<void>(resolve => setTimeout(resolve, 300));
      });
      await flush();

      expect(mockGetBills).toHaveBeenLastCalledWith(
        expect.objectContaining({
          keyword: '瑞幸',
          orderBy: 'date',
          orderDirection: 'desc',
        }),
      );
      expect(renderer.root.findByProps({ testID: 'all-bills-filter-summary' })).toBeTruthy();

      // 2. 选择账户筛选（招行卡）
      await act(async () => {
        renderer.root.findByProps({ testID: 'filter-account-btn' }).props.onPress();
      });
      await act(async () => {
        renderer.root.findByProps({ testID: 'filter-account-option-acc-cmb' }).props.onPress();
      });
      await flush();

      expect(mockGetBills).toHaveBeenLastCalledWith(
        expect.objectContaining({
          accountId: 'acc-cmb',
        }),
      );

      // 3. 选择父分类「餐饮」自动包含其子分类「咖啡饮品」，过滤掉「交通」账单
      await act(async () => {
        renderer.root.findByProps({ testID: 'filter-category-btn' }).props.onPress();
      });
      await act(async () => {
        renderer.root.findByProps({ testID: 'filter-category-option-cat-food' }).props.onPress();
      });
      await flush();

      const afterParentFilterText = collectTexts(renderer.root);
      expect(afterParentFilterText).toContain('瑞幸咖啡');
      expect(afterParentFilterText).not.toContain('滴滴出行');

      // 4. 切换为金额降序排序，展示单分组金额排序标题
      await act(async () => {
        renderer.root.findByProps({ testID: 'filter-sort-toggle' }).props.onPress();
      });
      await flush();

      expect(mockGetBills).toHaveBeenLastCalledWith(
        expect.objectContaining({
          orderBy: 'amount',
          orderDirection: 'desc',
        }),
      );
      expect(collectTexts(renderer.root)).toContain('金额由高到低');

      // 5. 一键重置清空所有筛选条件
      await act(async () => {
        renderer.root.findByProps({ testID: 'all-bills-reset-filters' }).props.onPress();
      });
      await flush();

      expect(renderer.root.findAllByProps({ testID: 'all-bills-filter-summary' })).toHaveLength(0);
      expect(mockGetBills).toHaveBeenLastCalledWith(
        expect.objectContaining({
          orderBy: 'date',
          orderDirection: 'desc',
        }),
      );
    } finally {
      await cleanup();
    }
  });

  test.each([
    ['soft', 'light'],
    ['blocks', 'dark'],
    ['hogwarts', 'light'],
    ['hogwarts', 'dark'],
  ] as const)('在 %s (%s) 主题下正常渲染搜索栏与多维筛选条', async (theme, mode) => {
    const { renderer, cleanup } = await renderAllBills(theme, mode);
    try {
      expect(renderer.root.findByProps({ testID: 'all-bills-search-input' })).toBeTruthy();
      expect(renderer.root.findByProps({ testID: 'filter-account-btn' })).toBeTruthy();
      expect(renderer.root.findByProps({ testID: 'filter-category-btn' })).toBeTruthy();
      expect(renderer.root.findByProps({ testID: 'filter-sort-toggle' })).toBeTruthy();
    } finally {
      await cleanup();
    }
  });
});

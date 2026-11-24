/**
 * AccountsScreen 与账户抽屉组件综合测试
 *
 * 覆盖：
 * 1. accountUiHelpers 纯函数（资产/负债/停用分组、占比条、金额遮罩、校准符号归一化、银行搜索）；
 * 2. AccountsScreen 在魔法羊皮纸 / 柔光 / 积木蓝三套主题下的渲染、分组展示、隐私遮罩、
 *    点击行展开快捷操作条、折叠已停用账户一键启用、空状态；
 * 3. AccountFormSheet 底部抽屉交互（资产/负债切换、热门银行直选、23 家银行目录搜索、
 *    新建负债自动记负、编辑态锁定账户性质并保留未改动图标）；
 * 4. BalanceCalibrationSheet 底部抽屉交互（对账横幅一键直达、实时差额计算、负债校准符号）。
 */
import React from 'react';
import { Text, TextInput, TouchableOpacity } from 'react-native';
import ReactTestRenderer, { act } from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { ThemeProvider } from '../src/providers';
import AccountsScreen from '../src/screens/settings/AccountsScreen';
import {
  computeAssetLiabilityRatio,
  filterBanksByQuery,
  formatAccountMoney,
  normalizeCalibrationMinor,
  partitionAccounts,
  splitHeroAmountParts,
} from '../src/screens/settings/accounts/accountUiHelpers';
import type { Account } from '../src/native';

const mockGoBack = jest.fn();
const mockUseFocusEffect = jest.fn();

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ goBack: mockGoBack, navigate: jest.fn() }),
  useFocusEffect: (...args: unknown[]) => mockUseFocusEffect(...args),
}));

const mockGetAccounts = jest.fn();
const mockGetNetWorth = jest.fn();
const mockGetBalanceReconcileWarnings = jest.fn();
const mockAddAccount = jest.fn();
const mockUpdateAccount = jest.fn();
const mockCalibrateAccountBalance = jest.fn();

jest.mock('../src/native', () => ({
  nativeData: {
    getAccounts: (...args: unknown[]) => mockGetAccounts(...args),
    getNetWorth: (...args: unknown[]) => mockGetNetWorth(...args),
    getBalanceReconcileWarnings: (...args: unknown[]) =>
      mockGetBalanceReconcileWarnings(...args),
    addAccount: (...args: unknown[]) => mockAddAccount(...args),
    updateAccount: (...args: unknown[]) => mockUpdateAccount(...args),
    calibrateAccountBalance: (...args: unknown[]) =>
      mockCalibrateAccountBalance(...args),
  },
}));

const SAMPLE_ACCOUNTS: Account[] = [
  {
    id: 'acc-cmb-debit',
    name: '招行工资卡',
    subtype: 'bank_debit',
    kind: 'asset',
    initialBalanceMinor: 4000000,
    currentBalanceMinor: 4286000,
    currency: 'CNY',
    icon: 'bank:cmbchina',
    cardTail: '2510',
    sortOrder: 0,
    isActive: true,
    createdAt: '2026-11-01T00:00:00.000Z',
    updatedAt: '2026-11-24T00:00:00.000Z',
  },
  {
    id: 'acc-wechat',
    name: '微信零钱',
    subtype: 'e_wallet',
    kind: 'asset',
    initialBalanceMinor: 500000,
    currentBalanceMinor: 642850,
    currency: 'CNY',
    icon: 'brand:wechat',
    cardTail: null,
    sortOrder: 1,
    isActive: true,
    createdAt: '2026-11-01T00:00:00.000Z',
    updatedAt: '2026-11-24T00:00:00.000Z',
  },
  {
    id: 'acc-cmb-credit',
    name: '招行信用卡',
    subtype: 'credit_card',
    kind: 'liability',
    initialBalanceMinor: 0,
    currentBalanceMinor: -462000,
    currency: 'CNY',
    icon: 'bank:cmbchina',
    cardTail: '8842',
    sortOrder: 2,
    isActive: true,
    createdAt: '2026-11-01T00:00:00.000Z',
    updatedAt: '2026-11-24T00:00:00.000Z',
  },
  {
    id: 'acc-old-icbc',
    name: '工行旧卡',
    subtype: 'bank_debit',
    kind: 'asset',
    initialBalanceMinor: 0,
    currentBalanceMinor: 0,
    currency: 'CNY',
    icon: 'bank:icbc',
    cardTail: '0916',
    sortOrder: 3,
    isActive: false,
    createdAt: '2026-10-01T00:00:00.000Z',
    updatedAt: '2026-10-10T00:00:00.000Z',
  },
];

function collectText(renderer: ReactTestRenderer.ReactTestRenderer): string {
  return renderer.root
    .findAllByType(Text)
    .map(node => {
      const children = node.props.children;
      if (Array.isArray(children)) {
        return children.filter(c => typeof c === 'string' || typeof c === 'number').join('');
      }
      return typeof children === 'string' || typeof children === 'number'
        ? String(children)
        : '';
    })
    .join(' ');
}

function findTouchableByText(
  renderer: ReactTestRenderer.ReactTestRenderer,
  label: string,
) {
  const buttons = renderer.root.findAllByType(TouchableOpacity);
  return buttons.find(btn => {
    const texts = btn.findAllByType(Text);
    return texts.some(t => {
      const c = t.props.children;
      const joined = Array.isArray(c) ? c.join('') : String(c ?? '');
      return joined.includes(label);
    });
  });
}

async function flushQueries() {
  for (let i = 0; i < 4; i += 1) {
    await act(async () => {
      await new Promise<void>(resolve => setTimeout(resolve, 0));
    });
  }
}

async function renderAccountsScreen(options?: {
  theme?: 'hogwarts' | 'soft' | 'blocks';
  mode?: 'light' | 'dark';
  accounts?: Account[];
  mismatches?: Array<{
    accountId: string;
    expectedBalanceMinor: number;
    currentBalanceMinor: number;
    diffMinor: number;
  }>;
}) {
  const theme = options?.theme ?? 'hogwarts';
  const mode = options?.mode ?? 'light';
  const accounts = options?.accounts ?? SAMPLE_ACCOUNTS;
  const mismatches = options?.mismatches ?? [];

  await AsyncStorage.setItem('@app_theme_name', theme);
  await AsyncStorage.setItem('@app_theme_mode', mode);
  await AsyncStorage.setItem('@app_hogwarts_house', 'classic');

  mockGetAccounts.mockResolvedValue(accounts);
  const assets = accounts
    .filter(a => a.isActive && a.kind === 'asset')
    .reduce((s, a) => s + a.currentBalanceMinor, 0);
  const liabilities = accounts
    .filter(a => a.isActive && a.kind === 'liability')
    .reduce((s, a) => s + Math.abs(a.currentBalanceMinor), 0);
  mockGetNetWorth.mockResolvedValue({
    netWorthMinor: assets - liabilities,
    totalAssetsMinor: assets,
    totalLiabilitiesMinor: liabilities,
  });
  mockGetBalanceReconcileWarnings.mockResolvedValue({
    checkedAt: '2026-11-24T12:00:00.000Z',
    mismatches,
  });

  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });

  let renderer!: ReactTestRenderer.ReactTestRenderer;
  await act(async () => {
    renderer = ReactTestRenderer.create(
      <QueryClientProvider client={queryClient}>
        <ThemeProvider>
          <AccountsScreen />
        </ThemeProvider>
      </QueryClientProvider>,
    );
  });
  await flushQueries();
  return { renderer, queryClient };
}

describe('accountUiHelpers', () => {
  test('partitions active assets, liabilities and inactive accounts with subtotals', () => {
    const result = partitionAccounts(SAMPLE_ACCOUNTS);
    expect(result.activeAssets).toHaveLength(2);
    expect(result.activeLiabilities).toHaveLength(1);
    expect(result.inactiveAccounts).toHaveLength(1);
    expect(result.activeCount).toBe(3);
    expect(result.assetsSubtotalMinor).toBe(4928850);
    expect(result.liabilitiesSubtotalMinor).toBe(462000);
  });

  test('computes asset vs liability ratios safely including edge cases', () => {
    expect(computeAssetLiabilityRatio(90000, 10000)).toEqual({
      assetPct: 90,
      debtPct: 10,
    });
    expect(computeAssetLiabilityRatio(0, 0)).toEqual({
      assetPct: 100,
      debtPct: 0,
    });
  });

  test('formats money and hero parts with privacy masking', () => {
    expect(formatAccountMoney(4286000, 'asset')).toBe('¥42,860.00');
    expect(formatAccountMoney(-462000, 'liability')).toBe('-¥4,620.00');
    expect(formatAccountMoney(4286000, 'asset', true)).toBe('••••••');
    expect(splitHeroAmountParts(-12345, false)).toEqual({
      sign: '-',
      currency: '¥',
      intPart: '123',
      decPart: '.45',
    });
    expect(splitHeroAmountParts(12345, true)).toEqual({
      sign: '',
      currency: '',
      intPart: '••••••',
      decPart: '',
    });
  });

  test('normalizes calibration minor units for asset and liability', () => {
    expect(normalizeCalibrationMinor(50000, 'asset')).toBe(50000);
    expect(normalizeCalibrationMinor(50000, 'liability')).toBe(-50000);
    expect(normalizeCalibrationMinor(0, 'liability')).toBe(0);
  });

  test('filters 23 banks by Chinese full name, short name, and aliases', () => {
    expect(filterBanksByQuery('').length).toBe(23);
    expect(filterBanksByQuery('招行').map(b => b.code)).toContain('cmbchina');
    expect(filterBanksByQuery('CMB').map(b => b.code)).toContain('cmbchina');
    expect(filterBanksByQuery('浦发').map(b => b.code)).toContain('spdb');
  });
});

describe('AccountsScreen UI redesign', () => {
  beforeEach(async () => {
    jest.clearAllMocks();
    await AsyncStorage.clear();
  });

  test.each([
    ['hogwarts', 'light'],
    ['hogwarts', 'dark'],
    ['soft', 'light'],
    ['soft', 'dark'],
    ['blocks', 'light'],
  ] as const)(
    'renders grouped accounts, card tails and net worth cleanly in %s (%s)',
    async (theme, mode) => {
      const { renderer, queryClient } = await renderAccountsScreen({ theme, mode });
      const text = collectText(renderer);

      expect(text).toContain('账户管理');
      expect(text).toContain('资产账户');
      expect(text).toContain('负债账户');
      expect(text).toContain('招行工资卡');
      expect(text).toContain('•••• 2510');
      expect(text).toContain('招行信用卡');
      expect(text).toContain('•••• 8842');
      expect(text).toContain('已停用账户 (1)');
      // 停用账户默认折叠，不干扰活跃列表
      expect(text).not.toContain('工行旧卡');

      await act(async () => {
        renderer.unmount();
      });
      queryClient.clear();
    },
  );

  test('masks balances on eye toggle and expands inline quick actions on row press', async () => {
    const { renderer, queryClient } = await renderAccountsScreen({ theme: 'soft' });

    // 默认未展开快捷操作条
    expect(
      renderer.root.findAllByProps({ testID: 'account-actions-acc-cmb-debit' }),
    ).toHaveLength(0);

    // 点击账户行展开快捷操作条
    const row = renderer.root.findByProps({ testID: 'account-row-acc-cmb-debit' });
    await act(async () => {
      row.props.onPress();
    });
    expect(
      renderer.root.findAllByProps({ testID: 'account-actions-acc-cmb-debit' }).length,
    ).toBeGreaterThan(0);

    // 点击眼睛按钮遮罩金额
    const eyeBtn = renderer.root.findByProps({ testID: 'toggle-hide-amounts' });
    await act(async () => {
      eyeBtn.props.onPress();
    });
    expect(collectText(renderer)).toContain('••••••');

    // 展开已停用账户并点击重新启用
    mockUpdateAccount.mockResolvedValue({ ...SAMPLE_ACCOUNTS[3], isActive: true });
    const inactiveToggle = renderer.root.findByProps({
      testID: 'toggle-inactive-accounts',
    });
    await act(async () => {
      inactiveToggle.props.onPress();
    });
    expect(collectText(renderer)).toContain('工行旧卡');

    const reactivateBtn = findTouchableByText(renderer, '重新启用');
    expect(reactivateBtn).toBeDefined();
    await act(async () => {
      reactivateBtn!.props.onPress();
    });
    expect(mockUpdateAccount).toHaveBeenCalledWith('acc-old-icbc', {
      isActive: true,
    });

    await act(async () => {
      renderer.unmount();
    });
    queryClient.clear();
  });

  test('creates a liability credit card via bottom sheet with bank chip, tail and auto-negated balance', async () => {
    mockAddAccount.mockResolvedValue({
      ...SAMPLE_ACCOUNTS[2],
      id: 'acc-new-cc',
    });
    const { renderer, queryClient } = await renderAccountsScreen({
      theme: 'hogwarts',
    });

    // 打开添加账户抽屉
    await act(async () => {
      renderer.root.findByProps({ testID: 'add-account-btn' }).props.onPress();
    });

    // 切到负债账户 -> 信用卡
    const liabilityTab = findTouchableByText(renderer, '负债账户');
    expect(liabilityTab).toBeDefined();
    await act(async () => {
      liabilityTab!.props.onPress();
    });

    // 选择热门银行「招商」
    const cmbChip = renderer.root.findByProps({ accessibilityLabel: '招商银行' });
    await act(async () => {
      cmbChip.props.onPress();
    });

    // 输入卡尾号与欠款金额
    const inputs = renderer.root.findAllByType(TextInput);
    const tailInput = inputs.find(i => i.props.placeholder === '2510');
    const amountInput = inputs.find(i => i.props.placeholder === '0.00');
    expect(tailInput).toBeDefined();
    expect(amountInput).toBeDefined();

    await act(async () => {
      tailInput!.props.onChangeText('6688');
      amountInput!.props.onChangeText('3200');
    });

    // 提交创建
    const submitBtn = renderer.root.findByProps({
      accessibilityLabel: '创建账户',
    });
    await act(async () => {
      submitBtn.props.onPress();
    });
    await flushQueries();

    expect(mockAddAccount).toHaveBeenCalledWith({
      name: '招商银行',
      subtype: 'credit_card',
      kind: 'liability',
      initialBalanceMinor: -320000,
      currency: 'CNY',
      icon: 'bank:cmbchina',
      cardTail: '6688',
    });

    await act(async () => {
      renderer.unmount();
    });
    queryClient.clear();
  });

  test('jumps from reconcile warning banner directly into calibration sheet and computes live diff', async () => {
    mockCalibrateAccountBalance.mockResolvedValue({ id: 'cal-1' });
    const { renderer, queryClient } = await renderAccountsScreen({
      theme: 'hogwarts',
      mismatches: [
        {
          accountId: 'acc-cmb-debit',
          expectedBalanceMinor: 4298850,
          currentBalanceMinor: 4286000,
          diffMinor: -12850,
        },
      ],
    });

    expect(
      renderer.root.findAllByProps({ testID: 'reconcile-warning-banner' }).length,
    ).toBeGreaterThan(0);

    // 点击「去校准 →」直达校准抽屉
    await act(async () => {
      renderer.root.findByProps({ testID: 'reconcile-jump-btn' }).props.onPress();
    });

    // 修改实际余额触发实时差额计算
    const actualInput = renderer.root.findByProps({
      testID: 'calibration-actual-input',
    });
    await act(async () => {
      actualInput.props.onChangeText('42988.50');
    });

    expect(collectText(renderer)).toContain('将自动补记余额调增');
    expect(collectText(renderer)).toContain('+¥128.50');

    // 提交校准
    const saveCalBtn = renderer.root.findByProps({
      accessibilityLabel: '确认保存校准',
    });
    await act(async () => {
      saveCalBtn.props.onPress();
    });
    await flushQueries();

    expect(mockCalibrateAccountBalance).toHaveBeenCalledWith(
      'acc-cmb-debit',
      4298850,
      '对账校准差额',
    );

    await act(async () => {
      renderer.unmount();
    });
    queryClient.clear();
  });

  test('locks account kind during edit and searches full 23-bank directory inside sheet', async () => {
    mockUpdateAccount.mockResolvedValue({
      ...SAMPLE_ACCOUNTS[0],
      name: '兴业工资卡',
      icon: 'bank:cib',
    });
    const { renderer, queryClient } = await renderAccountsScreen({
      theme: 'soft',
    });

    // 展开招行工资卡并点击编辑
    await act(async () => {
      renderer.root.findByProps({ testID: 'account-row-acc-cmb-debit' }).props.onPress();
    });
    const editBtn = renderer.root.findByProps({ accessibilityLabel: '编辑招行工资卡' });
    await act(async () => {
      editBtn.props.onPress();
    });

    // 编辑态锁定账户性质
    expect(collectText(renderer)).toContain('资产账户 · 创建后不可变更性质');

    // 打开全部 23 家银行目录并搜索「兴业」
    const moreBanksBtn = renderer.root.findByProps({
      accessibilityLabel: '查看全部 23 家银行',
    });
    await act(async () => {
      moreBanksBtn.props.onPress();
    });

    const searchInput = renderer.root
      .findAllByType(TextInput)
      .find(i => i.props.placeholder?.includes('搜索银行'));
    expect(searchInput).toBeDefined();
    await act(async () => {
      searchInput!.props.onChangeText('兴业');
    });
    expect(collectText(renderer)).toContain('兴业银行');

    const pickCibBtn = renderer.root.findByProps({ accessibilityLabel: '选择兴业银行' });
    await act(async () => {
      pickCibBtn.props.onPress();
    });

    // 保存编辑
    const saveBtn = renderer.root.findByProps({ accessibilityLabel: '保存修改' });
    await act(async () => {
      saveBtn.props.onPress();
    });
    await flushQueries();

    expect(mockUpdateAccount).toHaveBeenCalledWith('acc-cmb-debit', {
      name: '招行工资卡',
      subtype: 'bank_debit',
      icon: 'bank:cib',
      cardTail: '2510',
    });

    await act(async () => {
      renderer.unmount();
    });
    queryClient.clear();
  });

  test('renders themed empty state when user has no accounts', async () => {
    const { renderer, queryClient } = await renderAccountsScreen({
      theme: 'hogwarts',
      accounts: [],
    });

    expect(
      renderer.root.findAllByProps({ testID: 'accounts-empty-state' }).length,
    ).toBeGreaterThan(0);
    expect(collectText(renderer)).toContain('金库尚未开启');

    await act(async () => {
      renderer.unmount();
    });
    queryClient.clear();
  });
});

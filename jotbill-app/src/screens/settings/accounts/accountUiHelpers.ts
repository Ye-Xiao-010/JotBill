/**
 * 账户管理页共享元数据与纯函数工具
 *
 * 供 AccountsScreen、AccountFormSheet、BalanceCalibrationSheet 及单元测试共用，
 * 保持金额格式化、资产负债分组、占比计算与银行搜索口径完全一致。
 */
import type { Account, AccountSubtype } from '../../../native';
import { BANKS } from '../../../constants/accountIcons';

export interface AccountOption {
  label: string;
  shortLabel: string;
  hint: string;
  subtype: AccountSubtype;
  kind: Account['kind'];
}

export const ACCOUNT_OPTIONS: readonly AccountOption[] = [
  {
    label: '银行借记卡',
    shortLabel: '储蓄卡',
    hint: '工资 / 储蓄卡',
    subtype: 'bank_debit',
    kind: 'asset',
  },
  {
    label: '电子钱包',
    shortLabel: '电子钱包',
    hint: '微信 / 支付宝',
    subtype: 'e_wallet',
    kind: 'asset',
  },
  {
    label: '现金',
    shortLabel: '现金',
    hint: '零钱 / 钱包',
    subtype: 'cash',
    kind: 'asset',
  },
  {
    label: '其他账户',
    shortLabel: '其他账户',
    hint: '理财 / 公积金',
    subtype: 'other',
    kind: 'asset',
  },
  {
    label: '信用卡',
    shortLabel: '信用卡',
    hint: '支持短信尾号',
    subtype: 'credit_card',
    kind: 'liability',
  },
  {
    label: '消费信贷',
    shortLabel: '消费信贷',
    hint: '花呗 / 白条',
    subtype: 'consumer_loan',
    kind: 'liability',
  },
];

/** 热门 8 大银行（横向直选栏，顺序贴近国内常用覆盖率） */
export const HOT_BANK_CODES: readonly string[] = [
  'cmbchina',
  'icbc',
  'ccb',
  'abchina',
  'boc',
  'bankcomm',
  'psbc',
  'pingan',
];

export const HOT_BANKS = HOT_BANK_CODES
  .map(code => BANKS.find(bank => bank.code === code))
  .filter((bank): bank is (typeof BANKS)[number] => Boolean(bank));

/** 余额校准常用备注快捷标签 */
export const CALIBRATION_NOTE_PRESETS: readonly string[] = [
  '月末对账',
  '遗漏零星支出',
  '利息/返现补记',
  '期初余额修正',
];

export function isBankCardSubtype(subtype: AccountSubtype): boolean {
  return subtype === 'bank_debit' || subtype === 'credit_card';
}

export function getAccountOption(subtype: AccountSubtype): AccountOption {
  return (
    ACCOUNT_OPTIONS.find(option => option.subtype === subtype) ??
    ACCOUNT_OPTIONS[0]
  );
}

export function getOptionsForKind(kind: Account['kind']): AccountOption[] {
  return ACCOUNT_OPTIONS.filter(option => option.kind === kind);
}

export function formatAccountMoney(
  amountMinor: number,
  kind: Account['kind'],
  masked = false,
): string {
  if (masked) return '••••••';
  const amount = Math.abs(amountMinor / 100).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  if (amountMinor === 0) return '¥0.00';
  return amountMinor < 0 ? `-¥${amount}` : `¥${amount}`;
}

export interface HeroAmountParts {
  sign: string;
  currency: string;
  intPart: string;
  decPart: string;
}

export function splitHeroAmountParts(
  amountMinor: number,
  masked = false,
): HeroAmountParts {
  if (masked) {
    return { sign: '', currency: '', intPart: '••••••', decPart: '' };
  }
  const sign = amountMinor < 0 ? '-' : '';
  const formatted = Math.abs(amountMinor / 100).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  const [intPart, decPart] = formatted.split('.');
  return {
    sign,
    currency: '¥',
    intPart,
    decPart: `.${decPart ?? '00'}`,
  };
}

export function parseMinorInput(value: string): number | null {
  const normalized = value.trim();
  if (!normalized) return null;
  const amount = Number(normalized);
  if (!Number.isFinite(amount)) return null;
  const minor = Math.round(amount * 100);
  return Number.isSafeInteger(minor) ? minor : null;
}

/**
 * 归一化校准余额输入：
 * 负债账户输入框统一以「-¥」前缀收集正数欠款（规避 Android decimal-pad 无法输负号），
 * 统一规范为非正整数；资产账户原样保留解析值。
 */
export function normalizeCalibrationMinor(
  parsedMinor: number,
  kind: Account['kind'],
): number {
  if (kind === 'liability') {
    return parsedMinor === 0 ? 0 : -Math.abs(parsedMinor);
  }
  return parsedMinor;
}

export interface AssetLiabilityRatio {
  assetPct: number;
  debtPct: number;
}

export function computeAssetLiabilityRatio(
  totalAssetsMinor: number,
  totalLiabilitiesMinor: number,
): AssetLiabilityRatio {
  const assets = Math.max(0, totalAssetsMinor);
  const debts = Math.abs(totalLiabilitiesMinor);
  const sum = assets + debts;
  if (sum <= 0) {
    return { assetPct: 100, debtPct: 0 };
  }
  const assetPct = Math.round((assets / sum) * 100);
  return {
    assetPct,
    debtPct: Math.max(0, 100 - assetPct),
  };
}

export interface PartitionedAccounts {
  activeAssets: Account[];
  activeLiabilities: Account[];
  inactiveAccounts: Account[];
  activeCount: number;
  assetsSubtotalMinor: number;
  liabilitiesSubtotalMinor: number;
}

export function partitionAccounts(accounts: readonly Account[]): PartitionedAccounts {
  const activeAssets: Account[] = [];
  const activeLiabilities: Account[] = [];
  const inactiveAccounts: Account[] = [];

  for (const account of accounts) {
    if (!account.isActive) {
      inactiveAccounts.push(account);
      continue;
    }
    if (account.kind === 'liability') {
      activeLiabilities.push(account);
    } else {
      activeAssets.push(account);
    }
  }

  const assetsSubtotalMinor = activeAssets.reduce(
    (sum, account) => sum + account.currentBalanceMinor,
    0,
  );
  const liabilitiesSubtotalMinor = activeLiabilities.reduce(
    (sum, account) =>
      sum + (account.currentBalanceMinor < 0 ? -account.currentBalanceMinor : 0),
    0,
  );

  return {
    activeAssets,
    activeLiabilities,
    inactiveAccounts,
    activeCount: activeAssets.length + activeLiabilities.length,
    assetsSubtotalMinor,
    liabilitiesSubtotalMinor,
  };
}

export function filterBanksByQuery(query: string) {
  const q = query.trim().toLowerCase();
  if (!q) return BANKS;
  return BANKS.filter(
    bank =>
      bank.name.toLowerCase().includes(q) ||
      bank.shortName.toLowerCase().includes(q) ||
      bank.code.toLowerCase().includes(q) ||
      bank.aliases.some(alias => alias.toLowerCase().includes(q)),
  );
}

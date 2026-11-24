import { BANKS } from '../src/constants/accountIcons';
import {
  BANK_BADGE_TONES,
  BRAND_BADGE_TONES,
  CUSTOM_ACCOUNT_TONES,
  formatAccountBadgeParts,
  resolveAccountBadgeStyle,
  shouldHideRedundantPlatform,
  type AccountBadgeInput,
} from '../src/constants/accountStyles';
import type { ThemeStyleVariant } from '../src/theme/colors';

function parseHexRgb(hex: string): [number, number, number] {
  const h = hex.replace('#', '');
  return [
    parseInt(h.slice(0, 2), 16),
    parseInt(h.slice(2, 4), 16),
    parseInt(h.slice(4, 6), 16),
  ];
}

function parseRgba(rgba: string): [number, number, number, number] {
  const match = rgba.match(/rgba\(\s*(\d+),\s*(\d+),\s*(\d+),\s*([\d.]+)\s*\)/);
  if (!match) {
    throw new Error(`Invalid rgba color: ${rgba}`);
  }
  return [
    Number(match[1]),
    Number(match[2]),
    Number(match[3]),
    Number(match[4]),
  ];
}

/** 将半透明底色合成到页面表面上，用于计算真实视觉对比度 */
function compositeRgbaOverHex(rgba: string, surfaceHex: string): [number, number, number] {
  const [r, g, b, a] = parseRgba(rgba);
  const [sr, sg, sb] = parseHexRgb(surfaceHex);
  return [
    r * a + sr * (1 - a),
    g * a + sg * (1 - a),
    b * a + sb * (1 - a),
  ];
}

function rgbLuminance([r, g, b]: [number, number, number]): number {
  const [rl, gl, bl] = [r, g, b].map(v => {
    const n = v / 255;
    return n <= 0.03928 ? n / 12.92 : Math.pow((n + 0.055) / 1.055, 2.4);
  });
  return 0.2126 * rl + 0.7152 * gl + 0.0722 * bl;
}

function contrastRatio(fgHex: string, bgRgb: [number, number, number]): number {
  const l1 = rgbLuminance(parseHexRgb(fgHex));
  const l2 = rgbLuminance(bgRgb);
  return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
}

const THEME_SURFACES: Array<{
  variant: ThemeStyleVariant;
  isDark: boolean;
  surfaceHex: string;
}> = [
  { variant: 'soft', isDark: false, surfaceHex: '#FFFFFF' },
  { variant: 'soft', isDark: false, surfaceHex: '#EEF0F6' }, // drillDrawer 底色
  { variant: 'soft', isDark: true, surfaceHex: '#171A21' },
  { variant: 'soft', isDark: true, surfaceHex: '#1E222B' },
  { variant: 'brutal', isDark: false, surfaceHex: '#FFFFFF' },
  { variant: 'brutal', isDark: true, surfaceHex: '#27272A' },
  { variant: 'hogwarts', isDark: false, surfaceHex: '#FCF7EA' },
  { variant: 'hogwarts', isDark: true, surfaceHex: '#1C1730' },
];

describe('accountStyles 账户微标样式与动态适配', () => {
  it('覆盖 banks.json 中全部 23 家银行及微信/支付宝品牌色', () => {
    expect(Object.keys(BRAND_BADGE_TONES).sort()).toEqual(['alipay', 'wechat']);
    for (const bank of BANKS) {
      expect(BANK_BADGE_TONES[bank.code]).toBeDefined();
      const style = resolveAccountBadgeStyle(
        {
          id: `acc-${bank.code}`,
          name: bank.name,
          subtype: 'bank_debit',
          kind: 'asset',
          icon: `bank:${bank.code}`,
        },
        { variant: 'soft', isDark: false },
      );
      expect(style.toneKey).toBe(`bank:${bank.code}`);
      expect(style.hasBitmapIcon).toBe(true);
      expect(style.borderStyle).toBe('solid');
    }
  });

  it('所有内置银行、品牌、现金、10 套新账户色板与自定义 hex 在全主题浅/深模式下均满足 WCAG AA (>= 4.5:1)', () => {
    const sampleAccounts: AccountBadgeInput[] = [
      { id: 'brand-wechat', name: '微信零钱', subtype: 'e_wallet', kind: 'asset', icon: 'brand:wechat' },
      { id: 'brand-alipay', name: '支付宝', subtype: 'e_wallet', kind: 'asset', icon: 'brand:alipay' },
      { id: 'default-cash', name: '现金', subtype: 'cash', kind: 'asset', color: '#4CAF50' },
      ...BANKS.map(bank => ({
        id: `bank-${bank.code}`,
        name: bank.shortName,
        subtype: 'bank_debit' as const,
        kind: 'asset' as const,
        icon: `bank:${bank.code}`,
      })),
      // 覆盖 10 套哈希调色板
      ...Array.from({ length: 24 }, (_, idx) => ({
        id: `custom-account-${idx}`,
        name: `地方银行${idx}`,
        subtype: 'bank_debit' as const,
        kind: 'asset' as const,
      })),
      // 覆盖常见自定义 hex（含默认种子信用卡色 #FF7043、亮黄、青蓝、草绿、深蓝）
      ...['#FF7043', '#FACC15', '#00BCD4', '#22C55E', '#1E3A8A', '#EC4899'].map((hex, i) => ({
        id: `explicit-${i}`,
        name: `自定义卡${i}`,
        subtype: 'other' as const,
        kind: 'asset' as const,
        color: hex,
      })),
    ];

    for (const ctx of THEME_SURFACES) {
      for (const account of sampleAccounts) {
        const style = resolveAccountBadgeStyle(account, {
          variant: ctx.variant,
          isDark: ctx.isDark,
        });
        const bgRgb = compositeRgbaOverHex(style.backgroundColor, ctx.surfaceHex);
        const ratio = contrastRatio(style.textColor, bgRgb);
        expect(ratio).toBeGreaterThanOrEqual(4.5);
      }
    }
  });

  it('现金账户使用暖琥珀色，不与微信绿撞色', () => {
    const cash = resolveAccountBadgeStyle(
      { id: 'default-account-cash', name: '现金', subtype: 'cash', kind: 'asset', color: '#4CAF50' },
      { variant: 'soft', isDark: false },
    );
    const wechat = resolveAccountBadgeStyle(
      { id: 'default-account-wechat', name: '微信零钱', subtype: 'e_wallet', kind: 'asset', color: '#00A67E' },
      { variant: 'soft', isDark: false },
    );
    expect(cash.toneKey).toBe('subtype:cash');
    expect(wechat.toneKey).toBe('brand:wechat');
    expect(cash.textColor).not.toBe(wechat.textColor);
  });

  it('任意新增自定义账户具备稳定哈希配色，且不同账户分布到多种颜色', () => {
    const newAccounts: AccountBadgeInput[] = [
      { id: 'acc-scrcu', name: '四川农商银行', subtype: 'bank_debit', kind: 'asset', cardTail: '2510' },
      { id: 'acc-huabei', name: '花呗', subtype: 'consumer_loan', kind: 'liability' },
      { id: 'acc-baitiao', name: '京东白条', subtype: 'consumer_loan', kind: 'liability' },
      { id: 'acc-campus', name: '校园饭卡', subtype: 'other', kind: 'asset' },
      { id: 'acc-fund', name: '公积金账户', subtype: 'other', kind: 'asset' },
      { id: 'acc-hsbc', name: '汇丰银行', subtype: 'bank_debit', kind: 'asset', cardTail: '9081' },
    ];

    const firstPass = newAccounts.map(acc =>
      resolveAccountBadgeStyle(acc, { variant: 'soft', isDark: false }),
    );
    const secondPass = newAccounts.map(acc =>
      resolveAccountBadgeStyle(acc, { variant: 'soft', isDark: false }),
    );

    // 稳定性：同一账户多次解析完全一致
    expect(firstPass).toEqual(secondPass);
    expect(CUSTOM_ACCOUNT_TONES.length).toBeGreaterThanOrEqual(10);
    // 多样性：6 个新增账户不会全部撞成同一种颜色
    expect(new Set(firstPass.map(s => s.toneKey)).size).toBeGreaterThanOrEqual(3);
  });

  it('负债类账户（信用卡/消费信贷）使用虚线框，资产类账户使用实线框', () => {
    const creditCard = resolveAccountBadgeStyle(
      { id: 'cc-1', name: '招商银行信用卡', subtype: 'credit_card', kind: 'liability', cardTail: '8821' },
      { variant: 'soft', isDark: false },
    );
    const loan = resolveAccountBadgeStyle(
      { id: 'loan-1', name: '花呗', subtype: 'consumer_loan', kind: 'liability' },
      { variant: 'brutal', isDark: false },
    );
    const debit = resolveAccountBadgeStyle(
      { id: 'db-1', name: '招商银行储蓄卡', subtype: 'bank_debit', kind: 'asset', cardTail: '1234' },
      { variant: 'soft', isDark: false },
    );

    expect(creditCard.borderStyle).toBe('dashed');
    expect(loan.borderStyle).toBe('dashed');
    expect(debit.borderStyle).toBe('solid');
  });

  it('仅在深色模式且命中位图图标时提供浅底衬垫', () => {
    const bankAcc: AccountBadgeInput = {
      id: 'ccb-1',
      name: '建设银行',
      subtype: 'bank_debit',
      kind: 'asset',
      icon: 'bank:ccb',
    };
    const localAcc: AccountBadgeInput = {
      id: 'scrcu-1',
      name: '四川农商银行',
      subtype: 'bank_debit',
      kind: 'asset',
    };

    expect(resolveAccountBadgeStyle(bankAcc, { variant: 'soft', isDark: true }).bitmapBackdropColor).toBeTruthy();
    expect(resolveAccountBadgeStyle(bankAcc, { variant: 'hogwarts', isDark: true }).bitmapBackdropColor).toBeTruthy();
    expect(resolveAccountBadgeStyle(bankAcc, { variant: 'soft', isDark: false }).bitmapBackdropColor).toBeUndefined();
    expect(resolveAccountBadgeStyle(localAcc, { variant: 'soft', isDark: true }).bitmapBackdropColor).toBeUndefined();
  });

  it('formatAccountBadgeParts：银行全称缩简称、自定义名保留、尾号不重复拼接', () => {
    expect(
      formatAccountBadgeParts({ name: '中国邮政储蓄银行', icon: 'bank:psbc', cardTail: '0176' }),
    ).toEqual({
      name: '邮储银行',
      tail: '0176',
      fullLabel: '邮储银行 · 0176',
    });

    expect(
      formatAccountBadgeParts({ name: '招行工资卡', icon: null, cardTail: '8821' }),
    ).toEqual({
      name: '招行工资卡',
      tail: '8821',
      fullLabel: '招行工资卡 · 8821',
    });

    expect(
      formatAccountBadgeParts({ name: '邮储0176', icon: 'bank:psbc', cardTail: '0176' }),
    ).toEqual({
      name: '邮储0176',
      tail: undefined,
      fullLabel: '邮储0176',
    });
  });

  it('shouldHideRedundantPlatform：同账户钱包去重，跨渠道银行卡保留', () => {
    const wechat: AccountBadgeInput = { id: 'w', name: '微信零钱', subtype: 'e_wallet', icon: 'brand:wechat' };
    const alipay: AccountBadgeInput = { id: 'a', name: '支付宝', subtype: 'e_wallet', icon: 'brand:alipay' };
    const psbc: AccountBadgeInput = { id: 'p', name: '邮储银行', subtype: 'bank_debit', icon: 'bank:psbc' };

    expect(shouldHideRedundantPlatform('微信支付', wechat)).toBe(true);
    expect(shouldHideRedundantPlatform('支付宝', alipay)).toBe(true);
    expect(shouldHideRedundantPlatform('微信支付', psbc)).toBe(false);
    expect(shouldHideRedundantPlatform('支付宝', psbc)).toBe(false);
    expect(shouldHideRedundantPlatform('银行短信', psbc)).toBe(false);
    expect(shouldHideRedundantPlatform('微信支付', undefined)).toBe(false);
  });
});

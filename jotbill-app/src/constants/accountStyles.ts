/**
 * 账户微标样式与展示辅助 —— 供首页与统计页账单行共用。
 *
 * 设计原则：
 * 1. 动态适配任意新增账户：23 家银行 + 微信/支付宝使用精调品牌色；现金使用暖琥珀色
 *    （避免与微信/农行/邮储绿撞色）；其余自定义/地方银行账户优先读取合法自定义
 *    `account.color`，否则按 `account.id`（缺省按名称）稳定哈希映射到 10 套高区分度色板。
 * 2. 资产 vs 负债正交区分：信用卡/消费信贷（liability）使用虚线边框，资产账户用实线边框。
 * 3. 全主题（soft / brutal / hogwarts）× 浅/深模式自适应，深色模式命中真实位图时提供轻衬垫。
 */
import type { AccountSubtype } from '../native';
import type { ThemeStyleVariant } from '../theme/colors';
import {
  accountDisplayName,
  resolveAccountIcon,
  type AccountIconMatch,
} from './accountIcons';

export interface AccountBadgeInput {
  id: string;
  name: string;
  subtype: AccountSubtype;
  kind?: 'asset' | 'liability';
  icon?: string | null;
  color?: string | null;
  cardTail?: string | null;
}

export interface AccountBadgeThemeContext {
  variant: ThemeStyleVariant;
  isDark: boolean;
}

export interface AccountBadgeStyle {
  /** 微标文字色与矢量兜底图标色 */
  textColor: string;
  /** 微标背景色（rgba） */
  backgroundColor: string;
  /** 微标边框色（rgba） */
  borderColor: string;
  /** 资产实线 / 负债虚线 */
  borderStyle: 'solid' | 'dashed';
  /** 深色模式下真实 PNG 图标的极轻浅底衬垫，防止深色银行 Logo 发暗 */
  bitmapBackdropColor?: string;
  /** 是否命中位图图标 */
  hasBitmapIcon: boolean;
  /** 命中的色组键（便于测试与诊断） */
  toneKey: string;
}

export interface AccountBadgeParts {
  /** 展示名称（银行全称自动缩为简称，用户自定义名保留） */
  name: string;
  /** 4 位尾号（账户名中已包含时不重复显示） */
  tail?: string;
  /** 完整可读文本（用于无障碍标签） */
  fullLabel: string;
}

interface ToneSpec {
  lightText: string;
  darkText: string;
  tintHex: string;
}

const HEX_COLOR_RE = /^#[0-9a-fA-F]{6}$/;

/** 品牌账户（微信 / 支付宝）精调双模式色组 */
export const BRAND_BADGE_TONES: Record<string, ToneSpec> = {
  wechat: { lightText: '#065F46', darkText: '#34D399', tintHex: '#07C160' },
  alipay: { lightText: '#075985', darkText: '#38BDF8', tintHex: '#1677FF' },
};

/** 23 家内置银行精调双模式色组（键与 banks.json 完全对齐） */
export const BANK_BADGE_TONES: Record<string, ToneSpec> = {
  icbc: { lightText: '#B91C1C', darkText: '#F87171', tintHex: '#DC2626' },
  ccb: { lightText: '#1D4ED8', darkText: '#60A5FA', tintHex: '#1D4ED8' },
  abchina: { lightText: '#115E59', darkText: '#2DD4BF', tintHex: '#0D9488' },
  boc: { lightText: '#9F1239', darkText: '#FB7185', tintHex: '#BE123C' },
  bankcomm: { lightText: '#1E40AF', darkText: '#93C5FD', tintHex: '#2563EB' },
  psbc: { lightText: '#166534', darkText: '#4ADE80', tintHex: '#16A34A' },
  cmbchina: { lightText: '#B91C3C', darkText: '#FB7185', tintHex: '#E11D48' },
  citicbank: { lightText: '#B91C1C', darkText: '#FCA5A5', tintHex: '#DC2626' },
  spdb: { lightText: '#1D4ED8', darkText: '#60A5FA', tintHex: '#2563EB' },
  cmbc: { lightText: '#155E75', darkText: '#22D3EE', tintHex: '#0891B2' },
  cib: { lightText: '#1E40AF', darkText: '#60A5FA', tintHex: '#1D4ED8' },
  cebbank: { lightText: '#6D28D9', darkText: '#C4B5FD', tintHex: '#7C3AED' },
  hxb: { lightText: '#B91C1C', darkText: '#F87171', tintHex: '#DC2626' },
  cgbchina: { lightText: '#BE123C', darkText: '#FB7185', tintHex: '#E11D48' },
  pingan: { lightText: '#9A3412', darkText: '#FB923C', tintHex: '#EA580C' },
  cbhb: { lightText: '#3730A3', darkText: '#A5B4FC', tintHex: '#4338CA' },
  hfbank: { lightText: '#92400E', darkText: '#FDBA74', tintHex: '#D97706' },
  czbank: { lightText: '#9A3412', darkText: '#FB923C', tintHex: '#EA580C' },
  bankofbeijing: { lightText: '#B91C1C', darkText: '#F87171', tintHex: '#DC2626' },
  bosc: { lightText: '#1E3A8A', darkText: '#93C5FD', tintHex: '#2563EB' },
  jsbchina: { lightText: '#075985', darkText: '#38BDF8', tintHex: '#0284C7' },
  nbcb: { lightText: '#92400E', darkText: '#FBBF24', tintHex: '#D97706' },
  njcb: { lightText: '#9F1239', darkText: '#FDA4AF', tintHex: '#E11D48' },
};

/** 现金默认色：暖琥珀，避免与微信/邮储/农行绿撞色 */
export const CASH_BADGE_TONE: ToneSpec = {
  lightText: '#92400E',
  darkText: '#FBBF24',
  tintHex: '#D97706',
};

/** 其余任意新增账户（地方银行、消费信贷、自定义钱包等）的 10 套高区分度哈希调色板 */
export const CUSTOM_ACCOUNT_TONES: readonly ToneSpec[] = [
  { lightText: '#4338CA', darkText: '#A5B4FC', tintHex: '#4F46E5' }, // 靛蓝
  { lightText: '#115E59', darkText: '#2DD4BF', tintHex: '#0D9488' }, // 青碧
  { lightText: '#92400E', darkText: '#FBBF24', tintHex: '#D97706' }, // 暖琥珀
  { lightText: '#BE123C', darkText: '#FB7185', tintHex: '#E11D48' }, // 玫红
  { lightText: '#6D28D9', darkText: '#C4B5FD', tintHex: '#7C3AED' }, // 紫罗兰
  { lightText: '#075985', darkText: '#38BDF8', tintHex: '#0284C7' }, // 湖蓝
  { lightText: '#9A3412', darkText: '#FB923C', tintHex: '#EA580C' }, // 珊瑚橙
  { lightText: '#166534', darkText: '#4ADE80', tintHex: '#16A34A' }, // 松林绿
  { lightText: '#86198F', darkText: '#E879F9', tintHex: '#C026D3' }, // 梅紫
  { lightText: '#334155', darkText: '#CBD5E1', tintHex: '#475569' }, // 石板蓝
];

function parseHexRgb(hex: string): [number, number, number] {
  const clean = hex.slice(1);
  return [
    parseInt(clean.slice(0, 2), 16),
    parseInt(clean.slice(2, 4), 16),
    parseInt(clean.slice(4, 6), 16),
  ];
}

function rgbToHex(r: number, g: number, b: number): string {
  const toByte = (v: number) =>
    Math.max(0, Math.min(255, Math.round(v)))
      .toString(16)
      .padStart(2, '0');
  return `#${toByte(r)}${toByte(g)}${toByte(b)}`.toUpperCase();
}

function rgbToHsl(r: number, g: number, b: number): [number, number, number] {
  const rn = r / 255;
  const gn = g / 255;
  const bn = b / 255;
  const max = Math.max(rn, gn, bn);
  const min = Math.min(rn, gn, bn);
  const l = (max + min) / 2;
  if (max === min) return [0, 0, l];
  const d = max - min;
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
  let h = 0;
  if (max === rn) h = (gn - bn) / d + (gn < bn ? 6 : 0);
  else if (max === gn) h = (bn - rn) / d + 2;
  else h = (rn - gn) / d + 4;
  return [h * 60, s, l];
}

function hslToRgb(h: number, s: number, l: number): [number, number, number] {
  const c = (1 - Math.abs(2 * l - 1)) * s;
  const hp = ((h % 360) + 360) % 360 / 60;
  const x = c * (1 - Math.abs((hp % 2) - 1));
  let r1 = 0;
  let g1 = 0;
  let b1 = 0;
  if (hp < 1) [r1, g1, b1] = [c, x, 0];
  else if (hp < 2) [r1, g1, b1] = [x, c, 0];
  else if (hp < 3) [r1, g1, b1] = [0, c, x];
  else if (hp < 4) [r1, g1, b1] = [0, x, c];
  else if (hp < 5) [r1, g1, b1] = [x, 0, c];
  else [r1, g1, b1] = [c, 0, x];
  const m = l - c / 2;
  return [(r1 + m) * 255, (g1 + m) * 255, (b1 + m) * 255];
}

function rgbLuminance([r, g, b]: [number, number, number]): number {
  const toLinear = (v: number) => {
    const n = v / 255;
    return n <= 0.03928 ? n / 12.92 : Math.pow((n + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * toLinear(r) + 0.7152 * toLinear(g) + 0.0722 * toLinear(b);
}

function contrastOnLuminance(fg: [number, number, number], bgLum: number): number {
  const fgLum = rgbLuminance(fg);
  return (Math.max(fgLum, bgLum) + 0.05) / (Math.min(fgLum, bgLum) + 0.05);
}

/**
 * 将任意自定义 6 位 hex 派生为浅/深双模式高对比前景色（在全部主题底色上保证 ≥ 4.5:1）。
 * 通过真实相对亮度逐步收敛明度，覆盖黄/绿/青/蓝全色相。
 */
function deriveToneFromHex(hex: string): ToneSpec {
  const [r, g, b] = parseHexRgb(hex);
  const [h, s] = rgbToHsl(r, g, b);
  const satLight = Math.min(0.82, Math.max(0.32, s));
  const satDark = Math.min(0.86, Math.max(0.45, s));

  // 最保守浅底（soft surfaceMuted #EEF0F6 叠 12% 任意 tint 后下界亮度约 0.65）
  const conservativeLightBgLum = 0.62;
  let lightRgb = hslToRgb(h, satLight, 0.34);
  for (let l = 0.34; l >= 0.14; l -= 0.02) {
    lightRgb = hslToRgb(h, satLight, l);
    if (contrastOnLuminance(lightRgb, conservativeLightBgLum) >= 5.0) {
      break;
    }
  }

  // 最保守深底（brutal dark #27272A 叠 20% 亮 tint 后上界亮度约 0.10）
  const conservativeDarkBgLum = 0.11;
  let darkRgb = hslToRgb(h, satDark, 0.68);
  for (let l = 0.68; l <= 0.88; l += 0.02) {
    darkRgb = hslToRgb(h, satDark, l);
    if (contrastOnLuminance(darkRgb, conservativeDarkBgLum) >= 5.2) {
      break;
    }
  }

  return {
    lightText: rgbToHex(lightRgb[0], lightRgb[1], lightRgb[2]),
    darkText: rgbToHex(darkRgb[0], darkRgb[1], darkRgb[2]),
    tintHex: hex.toUpperCase(),
  };
}

/** 多项式稳定哈希（纯算术实现，无位运算），确保同一新账户每次配色固定且均匀分散 */
function hashString(input: string): number {
  let hash = 5381;
  for (let i = 0; i < input.length; i += 1) {
    hash = (hash * 33 + input.charCodeAt(i)) % 2147483647;
  }
  return hash;
}

function rgbaFromHex(hex: string, alpha: number): string {
  const [r, g, b] = parseHexRgb(hex);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

/** 负债账户（信用卡 / 消费信贷）用虚线框与储蓄/电子钱包形成形态区分 */
export function isLiabilityAccount(account: Pick<AccountBadgeInput, 'kind' | 'subtype'>): boolean {
  return (
    account.kind === 'liability' ||
    account.subtype === 'credit_card' ||
    account.subtype === 'consumer_loan'
  );
}

function resolveTone(
  account: AccountBadgeInput,
  iconMatch: AccountIconMatch | null,
): { tone: ToneSpec; toneKey: string } {
  if (iconMatch?.kind === 'brand' && BRAND_BADGE_TONES[iconMatch.code]) {
    return {
      tone: BRAND_BADGE_TONES[iconMatch.code],
      toneKey: `brand:${iconMatch.code}`,
    };
  }
  if (iconMatch?.kind === 'bank' && BANK_BADGE_TONES[iconMatch.code]) {
    return {
      tone: BANK_BADGE_TONES[iconMatch.code],
      toneKey: `bank:${iconMatch.code}`,
    };
  }
  if (account.subtype === 'cash') {
    return { tone: CASH_BADGE_TONE, toneKey: 'subtype:cash' };
  }
  const explicitColor = account.color?.trim();
  if (explicitColor && HEX_COLOR_RE.test(explicitColor)) {
    return {
      tone: deriveToneFromHex(explicitColor),
      toneKey: `explicit:${explicitColor.toUpperCase()}`,
    };
  }
  const seed = account.id || account.name || account.subtype;
  const idx = hashString(seed) % CUSTOM_ACCOUNT_TONES.length;
  return {
    tone: CUSTOM_ACCOUNT_TONES[idx],
    toneKey: `custom:${idx}`,
  };
}

/**
 * 计算账户微标的主题化配色与边框形态。
 */
export function resolveAccountBadgeStyle(
  account: AccountBadgeInput,
  { variant, isDark }: AccountBadgeThemeContext,
): AccountBadgeStyle {
  const iconMatch = resolveAccountIcon(account.icon, account.name);
  const hasBitmapIcon = iconMatch !== null;
  const { tone, toneKey } = resolveTone(account, iconMatch);
  const borderStyle = isLiabilityAccount(account) ? 'dashed' : 'solid';

  // 按主题风格微调底色与边框浓度：
  // - soft：清透柔和
  // - brutal：轮廓更利落
  // - hogwarts：羊皮纸复古墨色融底
  const bgAlpha = isDark
    ? variant === 'brutal'
      ? 0.2
      : 0.17
    : variant === 'brutal'
      ? 0.12
      : 0.095;
  const borderAlpha = isDark
    ? variant === 'brutal'
      ? 0.48
      : variant === 'hogwarts'
        ? 0.4
        : 0.34
    : variant === 'brutal'
      ? 0.46
      : variant === 'hogwarts'
        ? 0.34
        : 0.26;

  return {
    textColor: isDark ? tone.darkText : tone.lightText,
    backgroundColor: rgbaFromHex(tone.tintHex, bgAlpha),
    borderColor: rgbaFromHex(tone.tintHex, borderAlpha),
    borderStyle,
    bitmapBackdropColor:
      isDark && hasBitmapIcon
        ? variant === 'hogwarts'
          ? 'rgba(252, 247, 234, 0.90)'
          : 'rgba(255, 255, 255, 0.90)'
        : undefined,
    hasBitmapIcon,
    toneKey,
  };
}

/**
 * 将账户名拆分为「可省略名称 + 不可截断 4 位尾号」。
 */
export function formatAccountBadgeParts(
  account: Pick<AccountBadgeInput, 'name' | 'icon' | 'cardTail'>,
): AccountBadgeParts {
  const match = resolveAccountIcon(account.icon, account.name);
  const rawName = account.name.trim();
  const name = rawName ? accountDisplayName(rawName, match) : '未命名账户';
  const rawTail = account.cardTail?.trim() || '';
  const tail = rawTail && !name.includes(rawTail) ? rawTail : undefined;
  return {
    name,
    tail,
    fullLabel: tail ? `${name} · ${tail}` : name,
  };
}

/**
 * 判断账单左侧元信息里的渠道文案是否与扣款账户重复：
 * - 仅当平台为「微信支付」且账户为微信账户，或平台为「支付宝」且账户为支付宝账户时去重；
 * - 跨渠道扣款（如微信支付扣银行卡）必须保留平台文案。
 */
export function shouldHideRedundantPlatform(
  platformLabel: string,
  account?: Pick<AccountBadgeInput, 'name' | 'icon'> | null,
): boolean {
  if (!platformLabel || !account) return false;
  const match = resolveAccountIcon(account.icon, account.name);
  if (platformLabel === '微信支付') {
    return (
      (match?.kind === 'brand' && match.code === 'wechat') ||
      account.name.includes('微信')
    );
  }
  if (platformLabel === '支付宝') {
    return (
      (match?.kind === 'brand' && match.code === 'alipay') ||
      account.name.includes('支付宝')
    );
  }
  return false;
}

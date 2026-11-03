/**
 * 主题色板注册表 - 轻量积木风
 *
 * 每套主题自带 light/dark 两块色板：主题 = 风格（主色/背景/强调色），
 * 明暗 = 模式，两者正交（4 主题 × 2 模式）。
 *
 * 硬约束：
 * - ThemePalette 为显式接口 + satisfies，任何一套缺 token 编译期即报错，
 *   不允许静默 fallback。
 * - 金额语义全主题固定：income=绿 / expense=红（只做明度彩度适配）。
 * - 关键文字组合满足 WCAG AA（实测值见各主题注释）。
 */

/** 单块色板的完整 token 清单（新增主题必须全部给出） */
export interface ThemePalette {
  // 主色调
  primary: string;
  primaryLight: string;
  primaryDark: string;

  // 辅助色 - 糖果色块
  secondary: string;
  accent: string;
  success: string;
  successLight: string;
  warning: string;
  error: string;

  // Neo-Brutalism 特色色
  pink: string;
  lime: string;
  cyan: string;
  orange: string;

  // 描边色
  stroke: string;
  strokeLight: string;

  // 中性色系统
  textPrimary: string;
  textSecondary: string;
  textTertiary: string;
  textQuaternary: string;
  divider: string;
  background: string;
  surface: string;
  surfaceMuted: string;

  // 功能色 - 收支（收入=绿 / 支出=红，全主题单一口径）
  income: string;
  expense: string;
  // 收支行/徽章的浅底与深字变体
  incomeLight: string;
  expenseLight: string;
  incomeStrong: string;
  expenseStrong: string;

  // 主色块之上的文字/分隔线
  onPrimary: string;
  onPrimaryMuted: string;
  onPrimarySubtle: string;
  onPrimaryDivider: string;

  // 卡片和容器
  cardBackground: string;
  cardBorder: string;

  // 透明度变体
  shadow: string;
  shadowDark: string;
  overlay: string;
  overlayLight: string;

  // 渐变色
  gradientPrimaryStart: string;
  gradientPrimaryEnd: string;
}

/** 各主题共用的中性文字阶梯（浅色模式） */
const sharedLightText = {
  textPrimary: '#1A1A1A',
  textSecondary: '#404040',
  textTertiary: '#737373',
  textQuaternary: '#A3A3A3',
} as const;

/** 各主题共用的中性文字阶梯（深色模式） */
const sharedDarkText = {
  textPrimary: '#FAFAFA',
  textSecondary: '#D4D4D4',
  textTertiary: '#A3A3A3',
  textQuaternary: '#737373',
} as const;

/** 收支语义色（浅色模式，AA 修正后：income 5.02:1 / expense 4.83:1 于白表面） */
const sharedLightAmount = {
  income: '#15803D',
  expense: '#DC2626',
  incomeLight: '#DCFCE7',
  expenseLight: '#FEE2E2',
  incomeStrong: '#15803D',
  expenseStrong: '#DC2626',
} as const;

/** 收支语义色（深色模式） */
const sharedDarkAmount = {
  income: '#4ADE80',
  expense: '#F87171',
  incomeLight: '#173226',
  expenseLight: '#3F1D24',
  incomeStrong: '#4ADE80',
  expenseStrong: '#F87171',
} as const;

/** 深色模式共用的表面/描边体系 */
const sharedDarkSurface = {
  surface: '#27272A',
  cardBackground: '#27272A',
  stroke: '#E5E5E5',
  strokeLight: '#A3A3A3',
  divider: '#404040',
  cardBorder: '#E5E5E5',
  shadow: '#000000',
  shadowDark: '#000000',
  overlay: 'rgba(0, 0, 0, 0.8)',
  overlayLight: 'rgba(0, 0, 0, 0.5)',
} as const;

/* ================================================================
 * blocks 积木蓝（默认，原 Neo-Brutalism 色板的 AA 修正版）
 * 修正点：primary #3B82F6→#2563EB（白字 5.17:1 过 AA）；
 *         income/expense 加深；dark 模式 onPrimary 白→#1A1A1A
 *         （原 白/#60A5FA = 2.54:1 不达 AA）。
 * ============================================================== */
const blocksLight: ThemePalette = {
  primary: '#2563EB',
  primaryLight: '#DBEAFE',
  primaryDark: '#1D4ED8',

  secondary: '#A855F7',
  accent: '#FACC15',
  success: '#22C55E',
  successLight: '#DEF7EC',
  warning: '#FB923C',
  error: '#DC2626',

  pink: '#EC4899',
  lime: '#84CC16',
  cyan: '#06B6D4',
  orange: '#F97316',

  stroke: '#1A1A1A',
  strokeLight: '#404040',

  ...sharedLightText,
  divider: '#E5E5E5',
  background: '#FEF9EF',
  surface: '#FFFFFF',
  surfaceMuted: '#F7F3EA',

  ...sharedLightAmount,

  onPrimary: '#FFFFFF',
  onPrimaryMuted: 'rgba(255, 255, 255, 0.82)',
  onPrimarySubtle: 'rgba(255, 255, 255, 0.72)',
  onPrimaryDivider: 'rgba(255, 255, 255, 0.24)',

  cardBackground: '#FFFFFF',
  cardBorder: '#1A1A1A',

  shadow: '#1A1A1A',
  shadowDark: '#000000',
  overlay: 'rgba(0, 0, 0, 0.6)',
  overlayLight: 'rgba(0, 0, 0, 0.3)',

  gradientPrimaryStart: '#2563EB',
  gradientPrimaryEnd: '#3B82F6',
};

const blocksDark: ThemePalette = {
  primary: '#60A5FA',
  primaryLight: '#1E3A5F',
  primaryDark: '#3B82F6',

  secondary: '#C084FC',
  accent: '#FDE047',
  success: '#4ADE80',
  successLight: '#1A3328',
  warning: '#FDBA74',
  error: '#F87171',

  pink: '#F472B6',
  lime: '#A3E635',
  cyan: '#22D3EE',
  orange: '#FB923C',

  ...sharedDarkText,
  divider: sharedDarkSurface.divider,
  background: '#18181B',
  surface: sharedDarkSurface.surface,
  surfaceMuted: '#202024',

  ...sharedDarkAmount,

  onPrimary: '#1A1A1A',
  onPrimaryMuted: 'rgba(0, 0, 0, 0.75)',
  onPrimarySubtle: 'rgba(0, 0, 0, 0.6)',
  onPrimaryDivider: 'rgba(0, 0, 0, 0.24)',

  cardBackground: sharedDarkSurface.cardBackground,
  cardBorder: sharedDarkSurface.cardBorder,
  stroke: sharedDarkSurface.stroke,
  strokeLight: sharedDarkSurface.strokeLight,

  shadow: sharedDarkSurface.shadow,
  shadowDark: sharedDarkSurface.shadowDark,
  overlay: sharedDarkSurface.overlay,
  overlayLight: sharedDarkSurface.overlayLight,

  gradientPrimaryStart: '#60A5FA',
  gradientPrimaryEnd: '#93C5FD',
};

/* ================================================================
 * mint 薄荷账本（深绿主色，财务心智；白字/#047857 = 5.48:1 AA）
 * ============================================================== */
const mintLight: ThemePalette = {
  primary: '#047857',
  primaryLight: '#D1FAE5',
  primaryDark: '#065F46',

  secondary: '#A855F7',
  accent: '#FBBF24',
  success: '#22C55E',
  successLight: '#DEF7EC',
  warning: '#FB923C',
  error: '#DC2626',

  pink: '#EC4899',
  lime: '#84CC16',
  cyan: '#06B6D4',
  orange: '#F97316',

  stroke: '#1A1A1A',
  strokeLight: '#404040',

  ...sharedLightText,
  divider: '#E5E5E5',
  background: '#F4F7F5',
  surface: '#FFFFFF',
  surfaceMuted: '#E8EEEA',

  ...sharedLightAmount,

  onPrimary: '#FFFFFF',
  onPrimaryMuted: 'rgba(255, 255, 255, 0.82)',
  onPrimarySubtle: 'rgba(255, 255, 255, 0.72)',
  onPrimaryDivider: 'rgba(255, 255, 255, 0.24)',

  cardBackground: '#FFFFFF',
  cardBorder: '#1A1A1A',

  shadow: '#1A1A1A',
  shadowDark: '#000000',
  overlay: 'rgba(0, 0, 0, 0.6)',
  overlayLight: 'rgba(0, 0, 0, 0.3)',

  gradientPrimaryStart: '#047857',
  gradientPrimaryEnd: '#059669',
};

const mintDark: ThemePalette = {
  primary: '#34D399',
  primaryLight: '#064E3B',
  primaryDark: '#059669',

  secondary: '#C084FC',
  accent: '#FBBF24',
  success: '#4ADE80',
  successLight: '#1A3328',
  warning: '#FDBA74',
  error: '#F87171',

  pink: '#F472B6',
  lime: '#A3E635',
  cyan: '#22D3EE',
  orange: '#FB923C',

  ...sharedDarkText,
  divider: sharedDarkSurface.divider,
  background: '#18181B',
  surface: sharedDarkSurface.surface,
  surfaceMuted: '#202024',

  ...sharedDarkAmount,

  onPrimary: '#1A1A1A',
  onPrimaryMuted: 'rgba(0, 0, 0, 0.75)',
  onPrimarySubtle: 'rgba(0, 0, 0, 0.6)',
  onPrimaryDivider: 'rgba(0, 0, 0, 0.24)',

  cardBackground: sharedDarkSurface.cardBackground,
  cardBorder: sharedDarkSurface.cardBorder,
  stroke: sharedDarkSurface.stroke,
  strokeLight: sharedDarkSurface.strokeLight,

  shadow: sharedDarkSurface.shadow,
  shadowDark: sharedDarkSurface.shadowDark,
  overlay: sharedDarkSurface.overlay,
  overlayLight: sharedDarkSurface.overlayLight,

  gradientPrimaryStart: '#34D399',
  gradientPrimaryEnd: '#6EE7B7',
};

/** 主题注册表：satisfies 保证每套色板字段完整，缺 token 编译期报错 */
export const themes = {
  blocks: { light: blocksLight, dark: blocksDark },
  mint: { light: mintLight, dark: mintDark },
} satisfies Record<string, { light: ThemePalette; dark: ThemePalette }>;

export type ThemeName = keyof typeof themes;

/** 主题展示名（设置页选择器等 UI 使用） */
export const THEME_LABELS: Record<ThemeName, string> = {
  blocks: '积木蓝',
  mint: '薄荷账本',
};

// 兼容旧导入：默认 blocks 浅色
export const colors = themes.blocks.light;
export const lightColors = themes.blocks.light;
export const darkColors = themes.blocks.dark;

export type ThemeColors = ThemePalette;
export type ColorKey = keyof ThemePalette;

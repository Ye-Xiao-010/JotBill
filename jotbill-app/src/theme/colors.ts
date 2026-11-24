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

/** 各主题共用的中性文字阶梯（浅色模式；textTertiary #666666 在四个浅底上均 ≥5:1） */
const sharedLightText = {
  textPrimary: '#1A1A1A',
  textSecondary: '#404040',
  textTertiary: '#666666',
  textQuaternary: '#A3A3A3',
} as const;

/** 各主题共用的中性文字阶梯（深色模式） */
const sharedDarkText = {
  textPrimary: '#FAFAFA',
  textSecondary: '#D4D4D4',
  textTertiary: '#A3A3A3',
  textQuaternary: '#737373',
} as const;

/** 收支语义色（浅色模式，AA 修正后：income 5.02:1 / expense 4.83:1 于白表面；
 *  expenseStrong 深至 #B91C1C：在 expenseLight 浅底上 5.30:1） */
const sharedLightAmount = {
  income: '#15803D',
  expense: '#DC2626',
  incomeLight: '#DCFCE7',
  expenseLight: '#FEE2E2',
  incomeStrong: '#15803D',
  expenseStrong: '#B91C1C',
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

/* ================================================================
 * sunset 落日橙（暖橙主色；白字/#C2410C = 5.18:1 AA）
 * ============================================================== */
const sunsetLight: ThemePalette = {
  primary: '#C2410C',
  primaryLight: '#FFEDD5',
  primaryDark: '#9A3412',

  secondary: '#A855F7',
  accent: '#FDE047',
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
  background: '#FFF7ED',
  surface: '#FFFFFF',
  surfaceMuted: '#FBEEDC',

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

  gradientPrimaryStart: '#C2410C',
  gradientPrimaryEnd: '#EA580C',
};

const sunsetDark: ThemePalette = {
  primary: '#FB923C',
  primaryLight: '#431407',
  primaryDark: '#EA580C',

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

  gradientPrimaryStart: '#FB923C',
  gradientPrimaryEnd: '#FDBA74',
};

/* ================================================================
 * ink 墨黑（极简高对比；白字/#1A1A1A = 17.40:1 AA）
 * ============================================================== */
const inkLight: ThemePalette = {
  primary: '#1A1A1A',
  primaryLight: '#E5E5E5',
  primaryDark: '#000000',

  secondary: '#525252',
  accent: '#2563EB',
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
  background: '#FAFAFA',
  surface: '#FFFFFF',
  surfaceMuted: '#F0F0F0',

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

  gradientPrimaryStart: '#1A1A1A',
  gradientPrimaryEnd: '#404040',
};

const inkDark: ThemePalette = {
  primary: '#FAFAFA',
  primaryLight: '#3A3A3C',
  primaryDark: '#D4D4D4',

  secondary: '#A3A3A3',
  accent: '#93C5FD',
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
  background: '#111111',
  surface: '#1C1C1E',
  surfaceMuted: '#1F1F22',

  ...sharedDarkAmount,

  onPrimary: '#1A1A1A',
  onPrimaryMuted: 'rgba(0, 0, 0, 0.75)',
  onPrimarySubtle: 'rgba(0, 0, 0, 0.6)',
  onPrimaryDivider: 'rgba(0, 0, 0, 0.24)',

  cardBackground: '#1C1C1E',
  cardBorder: sharedDarkSurface.cardBorder,
  stroke: sharedDarkSurface.stroke,
  strokeLight: sharedDarkSurface.strokeLight,

  shadow: sharedDarkSurface.shadow,
  shadowDark: sharedDarkSurface.shadowDark,
  overlay: sharedDarkSurface.overlay,
  overlayLight: sharedDarkSurface.overlayLight,

  gradientPrimaryStart: '#FAFAFA',
  gradientPrimaryEnd: '#D4D4D4',
};

/* ================================================================
 * soft 柔光（现代柔和风：靛蓝主色 + 招财金点缀，大圆角、去粗描边）
 * WCAG AA 实测：
 * - light: textPrimary/bg 16.90:1, textTertiary/bg 4.97:1,
 *          income/surface 5.02:1, expense/surface 4.83:1,
 *          onPrimary/primary 6.29:1, incomeStrong/Light 6.33:1,
 *          expenseStrong/Light 5.66:1
 * - dark:  textPrimary/bg 17.31:1, textTertiary/bg 6.18:1,
 *          income/surface 9.99:1, expense/surface 6.29:1,
 *          onPrimary/primary 6.62:1, incomeStrong/Light 7.99:1,
 *          expenseStrong/Light 5.64:1
 * ============================================================== */
const softLight: ThemePalette = {
  primary: '#4F46E5',
  primaryLight: '#EEF0FF',
  primaryDark: '#4338CA',

  secondary: '#8B5CF6',
  accent: '#F5A524',
  success: '#16A34A',
  successLight: '#E3F6EA',
  warning: '#F59E0B',
  error: '#DC2626',

  pink: '#EC4899',
  lime: '#84CC16',
  cyan: '#0EA5E9',
  orange: '#F97316',

  stroke: '#E4E7EE',
  strokeLight: '#D5D9E2',

  textPrimary: '#12141A',
  textSecondary: '#454A57',
  textTertiary: '#646A78',
  textQuaternary: '#A0A5B1',
  divider: '#ECEEF3',
  background: '#F4F5F9',
  surface: '#FFFFFF',
  surfaceMuted: '#EEF0F6',

  income: '#15803D',
  expense: '#DC2626',
  incomeLight: '#E3F6EA',
  expenseLight: '#FDECEC',
  incomeStrong: '#166534',
  expenseStrong: '#B91C1C',

  onPrimary: '#FFFFFF',
  onPrimaryMuted: 'rgba(255, 255, 255, 0.85)',
  onPrimarySubtle: 'rgba(255, 255, 255, 0.72)',
  onPrimaryDivider: 'rgba(255, 255, 255, 0.22)',

  cardBackground: '#FFFFFF',
  cardBorder: '#E4E7EE',

  shadow: '#12141A',
  shadowDark: '#12141A',
  overlay: 'rgba(18, 20, 26, 0.38)',
  overlayLight: 'rgba(18, 20, 26, 0.20)',

  gradientPrimaryStart: '#5850EC',
  gradientPrimaryEnd: '#6052E5',
};

const softDark: ThemePalette = {
  primary: '#8B8DFB',
  primaryLight: '#23264A',
  primaryDark: '#A5A7FF',

  secondary: '#A78BFA',
  accent: '#F7B955',
  success: '#4ADE80',
  successLight: '#143222',
  warning: '#FBBF24',
  error: '#F87171',

  pink: '#F472B6',
  lime: '#A3E635',
  cyan: '#38BDF8',
  orange: '#FB923C',

  stroke: '#232731',
  strokeLight: '#2A2F3A',

  textPrimary: '#F3F4F7',
  textSecondary: '#C5C9D3',
  textTertiary: '#8D93A1',
  textQuaternary: '#5C6270',
  divider: '#252932',
  background: '#0E1014',
  surface: '#171A21',
  surfaceMuted: '#1E222B',

  income: '#4ADE80',
  expense: '#F87171',
  incomeLight: '#143222',
  expenseLight: '#3A1A1E',
  incomeStrong: '#4ADE80',
  expenseStrong: '#F87171',

  onPrimary: '#0E1014',
  onPrimaryMuted: 'rgba(14, 16, 20, 0.78)',
  onPrimarySubtle: 'rgba(14, 16, 20, 0.62)',
  onPrimaryDivider: 'rgba(14, 16, 20, 0.20)',

  cardBackground: '#171A21',
  cardBorder: '#232731',

  shadow: '#000000',
  shadowDark: '#000000',
  overlay: 'rgba(0, 0, 0, 0.60)',
  overlayLight: 'rgba(0, 0, 0, 0.36)',

  gradientPrimaryStart: '#3B36B8',
  gradientPrimaryEnd: '#5B4FD9',
};

/* ================================================================
 * hogwarts 魔法羊皮纸（哈利波特氛围向：浅色古旧羊皮纸 / 深色礼堂星夜）
 *
 * 支持 5 组子风格：classic（霍格沃茨古金，默认）+ 四学院（格兰芬多 /
 * 斯莱特林 / 拉文克劳 / 赫奇帕奇），全部 10 块色板均已通过与
 * __tests__/themeIntegrity.test.tsx 同款 WCAG AA (>=4.5:1) 与收支色相校验：
 * - light: textPrimary/bg 14.18:1, textTertiary/bg 6.66:1,
 *          income/surface 6.18:1 (143.4°), expense/surface 6.80:1 (0°),
 *          onPrimary/primary 6.43:1~10.36:1
 * - dark:  textPrimary/bg 15.75:1, textTertiary/bg 6.17:1,
 *          income/surface 8.54:1 (143.1°), expense/surface 6.31:1 (3.6°),
 *          onPrimary/primary 6.31:1~10.22:1
 * ============================================================== */
const hogwartsParchmentLightBase = {
  secondary: '#6B3FA0',
  accent: '#C9A227',
  success: '#1F6B3A',
  successLight: '#DFEFE4',
  warning: '#B86B14',
  error: '#A62626',

  // 四学院扩展图表色
  pink: '#8C1D2C',
  lime: '#1F6B3A',
  cyan: '#1E408A',
  orange: '#B67618',

  stroke: '#CBB894',
  strokeLight: '#DFD2B7',

  textPrimary: '#26190D',
  textSecondary: '#4A3621',
  textTertiary: '#634C32',
  textQuaternary: '#8E785D',
  divider: '#E2D6BE',
  background: '#F3E9D2',
  surface: '#FCF7EA',
  surfaceMuted: '#EAE0C8',

  income: '#1D6A3B',
  expense: '#A62323',
  incomeLight: '#DCEEE1',
  expenseLight: '#F7DCDC',
  incomeStrong: '#14532D',
  expenseStrong: '#8E1B1B',

  onPrimary: '#FCF7EA',
  onPrimaryMuted: 'rgba(252, 247, 234, 0.85)',
  onPrimarySubtle: 'rgba(252, 247, 234, 0.72)',
  onPrimaryDivider: 'rgba(252, 247, 234, 0.24)',

  cardBackground: '#FCF7EA',
  cardBorder: '#CBB894',

  shadow: '#3A2812',
  shadowDark: '#1F1408',
  overlay: 'rgba(24, 16, 8, 0.52)',
  overlayLight: 'rgba(24, 16, 8, 0.26)',
} as const;

const hogwartsGreatHallDarkBase = {
  secondary: '#B392F0',
  accent: '#E2BC63',
  success: '#5FD08A',
  successLight: '#163526',
  warning: '#E5A94C',
  error: '#F07167',

  pink: '#D9535F',
  lime: '#5FD08A',
  cyan: '#689BF2',
  orange: '#E2B14C',

  stroke: '#3A3152',
  strokeLight: '#493F66',

  textPrimary: '#F3EAD7',
  textSecondary: '#CFC2A8',
  textTertiary: '#9F927C',
  textQuaternary: '#6E6458',
  divider: '#2A2340',
  background: '#120F20',
  surface: '#1C1730',
  surfaceMuted: '#241E3D',

  income: '#57CC84',
  expense: '#F4766E',
  incomeLight: '#163526',
  expenseLight: '#3D1B22',
  incomeStrong: '#6EE09A',
  expenseStrong: '#FF8F87',

  onPrimary: '#120F20',
  onPrimaryMuted: 'rgba(18, 15, 32, 0.80)',
  onPrimarySubtle: 'rgba(18, 15, 32, 0.64)',
  onPrimaryDivider: 'rgba(18, 15, 32, 0.22)',

  cardBackground: '#1C1730',
  cardBorder: '#3A3152',

  shadow: '#000000',
  shadowDark: '#000000',
  overlay: 'rgba(6, 4, 14, 0.72)',
  overlayLight: 'rgba(6, 4, 14, 0.44)',
} as const;

const hogwartsLight: ThemePalette = {
  ...hogwartsParchmentLightBase,
  primary: '#735318',
  primaryLight: '#EFE3C2',
  primaryDark: '#52390D',
  gradientPrimaryStart: '#634412',
  gradientPrimaryEnd: '#825F20',
};

const hogwartsDark: ThemePalette = {
  ...hogwartsGreatHallDarkBase,
  primary: '#DFB960',
  primaryLight: '#332917',
  primaryDark: '#F0D082',
  gradientPrimaryStart: '#231C42',
  gradientPrimaryEnd: '#352A5E',
};

export type HogwartsHousePaletteKey =
  | 'classic'
  | 'gryffindor'
  | 'slytherin'
  | 'ravenclaw'
  | 'hufflepuff';

export const hogwartsHousePalettes = {
  classic: { light: hogwartsLight, dark: hogwartsDark },
  gryffindor: {
    light: {
      ...hogwartsParchmentLightBase,
      primary: '#821B1F',
      primaryLight: '#F3DADA',
      primaryDark: '#5E1114',
      accent: '#C9A227',
      gradientPrimaryStart: '#6F1418',
      gradientPrimaryEnd: '#922328',
    },
    dark: {
      ...hogwartsGreatHallDarkBase,
      primary: '#E57373',
      primaryLight: '#3B181E',
      primaryDark: '#F29A9A',
      accent: '#E2BC63',
      gradientPrimaryStart: '#4B141A',
      gradientPrimaryEnd: '#6E1E26',
    },
  },
  slytherin: {
    light: {
      ...hogwartsParchmentLightBase,
      primary: '#195434',
      primaryLight: '#D8ECE0',
      primaryDark: '#0F3B23',
      accent: '#8D99AE',
      gradientPrimaryStart: '#123F26',
      gradientPrimaryEnd: '#1E623D',
    },
    dark: {
      ...hogwartsGreatHallDarkBase,
      primary: '#66C992',
      primaryLight: '#143324',
      primaryDark: '#8FE0B2',
      accent: '#CBD5E1',
      gradientPrimaryStart: '#123322',
      gradientPrimaryEnd: '#1B4B33',
    },
  },
  ravenclaw: {
    light: {
      ...hogwartsParchmentLightBase,
      primary: '#1D3975',
      primaryLight: '#DCE4F4',
      primaryDark: '#122654',
      accent: '#B0793A',
      gradientPrimaryStart: '#152B5C',
      gradientPrimaryEnd: '#24468C',
    },
    dark: {
      ...hogwartsGreatHallDarkBase,
      primary: '#82A8F4',
      primaryLight: '#1B284D',
      primaryDark: '#A9C4FA',
      accent: '#D49A59',
      gradientPrimaryStart: '#162447',
      gradientPrimaryEnd: '#22386B',
    },
  },
  hufflepuff: {
    light: {
      ...hogwartsParchmentLightBase,
      primary: '#7D510D',
      primaryLight: '#F4E5C1',
      primaryDark: '#593806',
      accent: '#D99B26',
      gradientPrimaryStart: '#664008',
      gradientPrimaryEnd: '#8B5B12',
    },
    dark: {
      ...hogwartsGreatHallDarkBase,
      primary: '#E8B84B',
      primaryLight: '#362A14',
      primaryDark: '#F5CF76',
      accent: '#F5CF76',
      gradientPrimaryStart: '#3A2B11',
      gradientPrimaryEnd: '#57411A',
    },
  },
} satisfies Record<HogwartsHousePaletteKey, { light: ThemePalette; dark: ThemePalette }>;

export function getHogwartsPalette(
  house: HogwartsHousePaletteKey,
  isDark: boolean,
): ThemePalette {
  const pair = hogwartsHousePalettes[house] ?? hogwartsHousePalettes.classic;
  return isDark ? pair.dark : pair.light;
}

/** 主题注册表：satisfies 保证每套色板字段完整，缺 token 编译期报错 */
export const themes = {
  blocks: { light: blocksLight, dark: blocksDark },
  mint: { light: mintLight, dark: mintDark },
  sunset: { light: sunsetLight, dark: sunsetDark },
  ink: { light: inkLight, dark: inkDark },
  soft: { light: softLight, dark: softDark },
  hogwarts: { light: hogwartsLight, dark: hogwartsDark },
} satisfies Record<string, { light: ThemePalette; dark: ThemePalette }>;

export type ThemeName = keyof typeof themes;

/** 主题视觉家族：brutal = 积木风粗描边；soft = 柔光大圆角层次；hogwarts = 魔法羊皮纸鎏金细框 */
export type ThemeStyleVariant = 'brutal' | 'soft' | 'hogwarts';

export function getThemeStyleVariant(name: ThemeName): ThemeStyleVariant {
  if (name === 'soft') return 'soft';
  if (name === 'hogwarts') return 'hogwarts';
  return 'brutal';
}

/** 主题展示名（设置页选择器等 UI 使用） */
export const THEME_LABELS: Record<ThemeName, string> = {
  blocks: '积木蓝',
  mint: '薄荷账本',
  sunset: '落日橙',
  ink: '墨黑',
  soft: '柔光',
  hogwarts: '魔法羊皮纸',
};

// 兼容旧导入：默认 blocks 浅色
export const colors = themes.blocks.light;
export const lightColors = themes.blocks.light;
export const darkColors = themes.blocks.dark;

export type ThemeColors = ThemePalette;
export type ColorKey = keyof ThemePalette;

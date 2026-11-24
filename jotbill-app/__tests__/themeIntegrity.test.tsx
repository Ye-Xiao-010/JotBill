/**
 * 主题注册表完整性 + ThemeProvider 持久化 + 魔法羊皮纸（含四学院）测试
 *
 * 覆盖：
 * 1. 5 套主题 × 2 模式 + 魔法羊皮纸 5 组学院子色板 × 2 模式 token 齐全、键集合严格一致、无空值；
 * 2. 金额语义不变：income 为绿系（100~170°）、expense 为红系（≤15° 或 ≥340°）；
 * 3. 关键组合 WCAG AA 对比度（正文/三级文/金额/主色上文字 ≥ 4.5:1）；
 * 4. 全部 25 个默认分类图标键均有对应魔法羊皮纸 SVG 路径；
 * 5. ThemeProvider 持久化：默认值、切换 hogwarts + 学院 + 音效、读回生效、非法值兜底。
 */
import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { ThemeProvider, useTheme } from '../src/providers/ThemeProvider';
import {
  themes,
  hogwartsHousePalettes,
  THEME_LABELS,
  type ThemePalette,
} from '../src/theme/colors';
import { CATEGORY_LUCIDE_ICONS } from '../src/components/business/CategoryIcon';
import { HOGWARTS_ICON_PATHS } from '../src/constants/hogwartsIcons';
import { HOGWARTS_FONTS } from '../src/constants/hogwartsAssets';

/* ---------------- 工具函数（WCAG 相对亮度 / 对比度 / 色相） ---------------- */

/** 计算 hex 颜色的 WCAG 相对亮度 */
function luminance(hex: string): number {
  const h = hex.replace('#', '');
  const [r, g, b] = [0, 2, 4].map(i => {
    const v = parseInt(h.substr(i, 2), 16) / 255;
    return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** 两色对比度（1~21） */
function contrast(fg: string, bg: string): number {
  const [l1, l2] = [luminance(fg), luminance(bg)];
  return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
}

/** hex → 色相角（0~360）；绿系约 120~160，红系接近 0/360 */
function hue(hex: string): number {
  const h = hex.replace('#', '');
  const r = parseInt(h.substr(0, 2), 16) / 255;
  const g = parseInt(h.substr(2, 2), 16) / 255;
  const b = parseInt(h.substr(4, 2), 16) / 255;
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  if (max === min) return 0;
  const d = max - min;
  let deg: number;
  if (max === r) deg = 60 * (((g - b) / d) % 6);
  else if (max === g) deg = 60 * ((b - r) / d + 2);
  else deg = 60 * ((r - g) / d + 4);
  return (deg + 360) % 360;
}

// 汇总全部待检色板（5 主题 + 4 学院衍生）
const ALL_PALETTE_ENTRIES: Array<[string, ThemePalette]> = [
  ...Object.entries(themes).flatMap(([name, set]) => [
    [`${name}/light`, set.light] as [string, ThemePalette],
    [`${name}/dark`, set.dark] as [string, ThemePalette],
  ]),
  ...Object.entries(hogwartsHousePalettes)
    .filter(([house]) => house !== 'classic')
    .flatMap(([house, set]) => [
      [`hogwarts:${house}/light`, set.light] as [string, ThemePalette],
      [`hogwarts:${house}/dark`, set.dark] as [string, ThemePalette],
    ]),
];

/* ---------------- 1. 色板与图标完整性 ---------------- */

describe('主题注册表完整性', () => {
  it('5 套主题 × 2 模式 = 10 块主色板，且含 5 组魔法学院色板', () => {
    expect(Object.keys(themes)).toHaveLength(5);
    expect(Object.keys(hogwartsHousePalettes)).toHaveLength(5);
    for (const set of Object.values(themes)) {
      expect(set.light).toBeDefined();
      expect(set.dark).toBeDefined();
    }
  });

  it('全部 18 块色板键集合严格一致且无空值', () => {
    const referenceKeys = Object.keys(themes.blocks.light).sort();
    for (const [, palette] of ALL_PALETTE_ENTRIES) {
      expect(Object.keys(palette).sort()).toEqual(referenceKeys);
      for (const value of Object.values(palette)) {
        expect(typeof value).toBe('string');
        expect(value.length).toBeGreaterThan(0);
        expect(['undefined', 'null', '']).not.toContain(value);
      }
    }
  });

  it('THEME_LABELS 与注册表键集合一致', () => {
    expect(Object.keys(THEME_LABELS).sort()).toEqual(Object.keys(themes).sort());
  });

  it('全部 25 个默认分类键均有对应魔法羊皮纸矢量图标路径', () => {
    for (const lucideKey of Object.keys(CATEGORY_LUCIDE_ICONS)) {
      expect(HOGWARTS_ICON_PATHS[lucideKey]).toBeDefined();
      expect(HOGWARTS_ICON_PATHS[lucideKey].length).toBeGreaterThan(0);
    }
  });
});

/* ---------------- 2. 金额语义不变（色相判定，不看变量名） ---------------- */

describe('金额语义色', () => {
  for (const [label, palette] of ALL_PALETTE_ENTRIES) {
    it(`${label}: income 为绿系（色相 100~170°）`, () => {
      const h = hue(palette.income);
      expect(h).toBeGreaterThanOrEqual(100);
      expect(h).toBeLessThanOrEqual(170);
    });
    it(`${label}: expense 为红系（≤15° 或 ≥340°）`, () => {
      const h = hue(palette.expense);
      expect(h <= 15 || h >= 340).toBe(true);
    });
  }
});

/* ---------------- 3. WCAG AA 对比度 ---------------- */

describe('关键组合对比度 ≥ 4.5:1', () => {
  for (const [label, palette] of ALL_PALETTE_ENTRIES) {
    it(`${label}: 正文/背景`, () => {
      expect(contrast(palette.textPrimary, palette.background)).toBeGreaterThanOrEqual(4.5);
    });
    it(`${label}: 三级文/背景`, () => {
      expect(contrast(palette.textTertiary, palette.background)).toBeGreaterThanOrEqual(4.5);
    });
    it(`${label}: 收入金额/表面`, () => {
      expect(contrast(palette.income, palette.surface)).toBeGreaterThanOrEqual(4.5);
    });
    it(`${label}: 支出金额/表面`, () => {
      expect(contrast(palette.expense, palette.surface)).toBeGreaterThanOrEqual(4.5);
    });
    it(`${label}: 主色块上文字 onPrimary/primary`, () => {
      expect(contrast(palette.onPrimary, palette.primary)).toBeGreaterThanOrEqual(4.5);
    });
    it(`${label}: 徽章深字/浅底 incomeStrong/incomeLight`, () => {
      expect(contrast(palette.incomeStrong, palette.incomeLight)).toBeGreaterThanOrEqual(4.5);
    });
    it(`${label}: 徽章深字/浅底 expenseStrong/expenseLight`, () => {
      expect(contrast(palette.expenseStrong, palette.expenseLight)).toBeGreaterThanOrEqual(4.5);
    });
  }
});

/* ---------------- 4. ThemeProvider 持久化 ---------------- */

let captured: ReturnType<typeof useTheme> | null = null;

/** 探测组件：把 useTheme() 的上下文值暴露给测试 */
function Probe() {
  captured = useTheme();
  return null;
}

async function mountProvider() {
  await ReactTestRenderer.act(async () => {
    ReactTestRenderer.create(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    );
  });
}

describe('ThemeProvider 持久化与默认值', () => {
  beforeEach(async () => {
    captured = null;
    await AsyncStorage.clear();
    jest.restoreAllMocks();
  });

  it('首次启动无键：默认 blocks + 浅色 + brutal 形状', async () => {
    await mountProvider();
    expect(captured!.themeName).toBe('blocks');
    expect(captured!.themeMode).toBe('light');
    expect(captured!.isDark).toBe(false);
    expect(captured!.isHogwarts).toBe(false);
    expect(captured!.styleVariant).toBe('brutal');
    expect(captured!.shape.borderWidth.card).toBe(2);
    expect(captured!.colors.primary).toBe(themes.blocks.light.primary);
  });

  it('setThemeName 写入 @app_theme_name 且 colors 引用切换到目标主题', async () => {
    await mountProvider();
    const spy = jest.spyOn(AsyncStorage, 'setItem');
    await ReactTestRenderer.act(async () => {
      captured!.setThemeName('mint');
    });
    expect(spy).toHaveBeenCalledWith('@app_theme_name', 'mint');
    expect(captured!.themeName).toBe('mint');
    expect(captured!.colors.primary).toBe(themes.mint.light.primary);
  });

  it('读回：预存 ink + dark 后启动即为 ink 深色色板', async () => {
    await AsyncStorage.setItem('@app_theme_name', 'ink');
    await AsyncStorage.setItem('@app_theme_mode', 'dark');
    await mountProvider();
    expect(captured!.themeName).toBe('ink');
    expect(captured!.isDark).toBe(true);
    expect(captured!.colors.primary).toBe(themes.ink.dark.primary);
  });

  it('非法存储值兜底：neon / 非法学院不存在时回落默认值，不崩溃', async () => {
    await AsyncStorage.setItem('@app_theme_name', 'neon');
    await AsyncStorage.setItem('@app_hogwarts_house', 'durmstrang');
    await mountProvider();
    expect(captured!.themeName).toBe('blocks');
    expect(captured!.hogwartsHouse).toBe('classic');
    expect(captured!.isHogwarts).toBe(false);
    expect(captured!.colors.primary).toBe(themes.blocks.light.primary);
  });

  it('切换至 hogwarts 主题与四学院：shape / fonts / 学院色板与持久化正确生效', async () => {
    await mountProvider();
    const spy = jest.spyOn(AsyncStorage, 'setItem');

    await ReactTestRenderer.act(async () => {
      captured!.setThemeName('hogwarts');
    });
    expect(captured!.themeName).toBe('hogwarts');
    expect(captured!.isHogwarts).toBe(true);
    expect(captured!.styleVariant).toBe('hogwarts');
    expect(captured!.shape.borderWidth.card).toBe(1);
    expect(captured!.shape.radius.card).toBe(18);
    expect(captured!.fonts.display).toBe(HOGWARTS_FONTS.display);
    expect(captured!.fonts.body).toBe(HOGWARTS_FONTS.body);
    expect(captured!.fonts.numeric).toBe(HOGWARTS_FONTS.numeric);
    expect(captured!.colors.primary).toBe(themes.hogwarts.light.primary);

    await ReactTestRenderer.act(async () => {
      captured!.setHogwartsHouse('gryffindor');
    });
    expect(spy).toHaveBeenCalledWith('@app_hogwarts_house', 'gryffindor');
    expect(captured!.hogwartsHouse).toBe('gryffindor');
    expect(captured!.colors.primary).toBe(hogwartsHousePalettes.gryffindor.light.primary);

    await ReactTestRenderer.act(async () => {
      captured!.setThemeMode('dark');
    });
    expect(captured!.isDark).toBe(true);
    expect(captured!.colors.primary).toBe(hogwartsHousePalettes.gryffindor.dark.primary);
  });

  it('读回：预存 hogwarts + slytherin + dark + sfx=false + dynamicIcon=false 启动即生效', async () => {
    await AsyncStorage.setItem('@app_theme_name', 'hogwarts');
    await AsyncStorage.setItem('@app_hogwarts_house', 'slytherin');
    await AsyncStorage.setItem('@app_theme_mode', 'dark');
    await AsyncStorage.setItem('@app_hogwarts_sfx', 'false');
    await AsyncStorage.setItem('@app_hogwarts_dynamic_icon', 'false');
    await mountProvider();
    expect(captured!.isHogwarts).toBe(true);
    expect(captured!.hogwartsHouse).toBe('slytherin');
    expect(captured!.hogwartsSfxEnabled).toBe(false);
    expect(captured!.hogwartsDynamicIconEnabled).toBe(false);
    expect(captured!.colors.primary).toBe(hogwartsHousePalettes.slytherin.dark.primary);

    const spy = jest.spyOn(AsyncStorage, 'setItem');
    await ReactTestRenderer.act(async () => {
      captured!.setHogwartsDynamicIconEnabled(true);
    });
    expect(spy).toHaveBeenCalledWith('@app_hogwarts_dynamic_icon', 'true');
    expect(captured!.hogwartsDynamicIconEnabled).toBe(true);
  });
});

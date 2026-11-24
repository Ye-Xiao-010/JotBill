/**
 * 主题形状、层级与字体系统（ThemeShape / ThemeFonts）
 *
 * - brutal：数值与既有 spacing.ts 100% 一致，确保旧四套积木风零回归；
 * - hogwarts：古典羊皮纸精装书风格——柔和圆角（14–24dp）、1px 鎏金细描边、
 *   暖褐轻阴影与双语古籍字体（霞鹜文楷 + 站酷小薇 + Cinzel）。
 */
import { StyleSheet, type TextStyle, type ViewStyle } from 'react-native';
import type { ThemeStyleVariant } from './colors';
import {
  borderRadius as brutalRadius,
  borderWidth as brutalBorderWidth,
  shadow as brutalShadow,
} from './spacing';
import { HOGWARTS_FONTS } from '../constants/hogwartsAssets';

export interface ThemeElevation {
  none: ViewStyle;
  card: ViewStyle;
  floating: ViewStyle;
  hero: ViewStyle;
}

export interface ThemeFonts {
  display?: string;
  body?: string;
  numeric?: string;
  ornate?: string;
}

export interface ThemeShape {
  variant: ThemeStyleVariant;
  isHogwarts: boolean;
  radius: {
    tag: number;
    small: number;
    medium: number;
    button: number;
    input: number;
    card: number;
    modal: number;
    hero: number;
    sheet: number;
    pill: number;
  };
  borderWidth: {
    hairline: number;
    card: number;
    control: number;
    strong: number;
    navTop: number;
  };
  elevation: ThemeElevation;
  fontWeight: {
    regular: TextStyle['fontWeight'];
    medium: TextStyle['fontWeight'];
    semibold: TextStyle['fontWeight'];
    bold: TextStyle['fontWeight'];
    heavy: TextStyle['fontWeight'];
  };
  iconStrokeWidth: {
    regular: number;
    active: number;
  };
  pressScale: number;
}

export const brutalShape: ThemeShape = {
  variant: 'brutal',
  isHogwarts: false,
  radius: {
    tag: brutalRadius.small,
    small: brutalRadius.small,
    medium: brutalRadius.medium,
    button: brutalRadius.button,
    input: brutalRadius.input,
    card: brutalRadius.card,
    modal: brutalRadius.card,
    hero: brutalRadius.card,
    sheet: brutalRadius.xlarge,
    pill: brutalRadius.round,
  },
  borderWidth: {
    hairline: brutalBorderWidth.hairline,
    card: brutalBorderWidth.thin,
    control: brutalBorderWidth.medium,
    strong: brutalBorderWidth.thick,
    navTop: brutalBorderWidth.thick,
  },
  elevation: {
    none: { shadowOpacity: 0, elevation: 0 },
    card: brutalShadow.small,
    floating: brutalShadow.medium,
    hero: brutalShadow.soft,
  },
  fontWeight: {
    regular: '400',
    medium: '600',
    semibold: '700',
    bold: '800',
    heavy: '900',
  },
  iconStrokeWidth: {
    regular: 2.5,
    active: 2.5,
  },
  pressScale: 1,
};

const hogwartsLightShape: ThemeShape = {
  variant: 'hogwarts',
  isHogwarts: true,
  radius: {
    tag: 8,
    small: 10,
    medium: 14,
    button: 14,
    input: 12,
    card: 18,
    modal: 22,
    hero: 24,
    sheet: 26,
    pill: 999,
  },
  borderWidth: {
    hairline: StyleSheet.hairlineWidth,
    card: 1,
    control: 1,
    strong: 1.5,
    navTop: 1,
  },
  elevation: {
    none: { shadowOpacity: 0, elevation: 0 },
    card: {
      shadowColor: '#3A2812',
      shadowOffset: { width: 0, height: 3 },
      shadowOpacity: 0.1,
      shadowRadius: 10,
      elevation: 2,
    },
    floating: {
      shadowColor: '#3A2812',
      shadowOffset: { width: 0, height: 6 },
      shadowOpacity: 0.16,
      shadowRadius: 16,
      elevation: 5,
    },
    hero: {
      shadowColor: '#3A2812',
      shadowOffset: { width: 0, height: 8 },
      shadowOpacity: 0.18,
      shadowRadius: 18,
      elevation: 6,
    },
  },
  fontWeight: {
    regular: '400',
    medium: '500',
    semibold: '600',
    bold: '700',
    heavy: '700',
  },
  iconStrokeWidth: {
    regular: 2.0,
    active: 2.2,
  },
  pressScale: 0.97,
};

const hogwartsDarkShape: ThemeShape = {
  ...hogwartsLightShape,
  elevation: {
    none: { shadowOpacity: 0, elevation: 0 },
    card: {
      shadowColor: '#000000',
      shadowOffset: { width: 0, height: 2 },
      shadowOpacity: 0.28,
      shadowRadius: 8,
      elevation: 2,
    },
    floating: {
      shadowColor: '#000000',
      shadowOffset: { width: 0, height: 8 },
      shadowOpacity: 0.42,
      shadowRadius: 18,
      elevation: 6,
    },
    hero: {
      shadowColor: '#000000',
      shadowOffset: { width: 0, height: 10 },
      shadowOpacity: 0.48,
      shadowRadius: 20,
      elevation: 8,
    },
  },
};

export function getThemeShape(variant: ThemeStyleVariant, isDark: boolean): ThemeShape {
  if (variant === 'hogwarts') {
    return isDark ? hogwartsDarkShape : hogwartsLightShape;
  }
  return brutalShape;
}

const emptyFonts: ThemeFonts = {};
const hogwartsFontsObj: ThemeFonts = {
  display: HOGWARTS_FONTS.display,
  body: HOGWARTS_FONTS.body,
  numeric: HOGWARTS_FONTS.numeric,
  ornate: HOGWARTS_FONTS.ornate,
};

export function getThemeFonts(isHogwarts: boolean): ThemeFonts {
  return isHogwarts ? hogwartsFontsObj : emptyFonts;
}

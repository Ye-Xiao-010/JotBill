/**
 * 主题形状与层级系统（ThemeShape）
 *
 * 将圆角、描边、阴影高度、字重和按压反馈从静态常量提升为主题态 token：
 * - brutal：与既有 spacing.ts 数值保持 100% 一致，确保旧四套积木风零回归；
 * - soft：大圆角、浅色去粗描边 + 轻投影、深色 1px 低对比发丝线、缩放按压反馈。
 */
import { StyleSheet, type TextStyle, type ViewStyle } from 'react-native';
import type { ThemeStyleVariant } from './colors';
import { borderRadius as brutalRadius, borderWidth as brutalBorderWidth, shadow as brutalShadow } from './spacing';

export interface ThemeElevation {
  none: ViewStyle;
  card: ViewStyle;
  floating: ViewStyle;
  hero: ViewStyle;
}

export interface ThemeShape {
  variant: ThemeStyleVariant;
  isSoft: boolean;
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
  isSoft: false,
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

const softLightShape: ThemeShape = {
  variant: 'soft',
  isSoft: true,
  radius: {
    tag: 8,
    small: 12,
    medium: 14,
    button: 14,
    input: 14,
    card: 20,
    modal: 24,
    hero: 28,
    sheet: 30,
    pill: 999,
  },
  borderWidth: {
    hairline: StyleSheet.hairlineWidth,
    card: 0,
    control: 0,
    strong: 0,
    navTop: 0,
  },
  elevation: {
    none: { shadowOpacity: 0, elevation: 0 },
    card: {
      shadowColor: '#12141A',
      shadowOffset: { width: 0, height: 2 },
      shadowOpacity: 0.06,
      shadowRadius: 8,
      elevation: 2,
    },
    floating: {
      shadowColor: '#12141A',
      shadowOffset: { width: 0, height: 8 },
      shadowOpacity: 0.14,
      shadowRadius: 20,
      elevation: 6,
    },
    hero: {
      shadowColor: '#4F46E5',
      shadowOffset: { width: 0, height: 12 },
      shadowOpacity: 0.30,
      shadowRadius: 24,
      elevation: 8,
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
    regular: 2,
    active: 2.3,
  },
  pressScale: 0.97,
};

const softDarkShape: ThemeShape = {
  ...softLightShape,
  borderWidth: {
    hairline: StyleSheet.hairlineWidth,
    card: 1,
    control: 1,
    strong: 1,
    navTop: 0,
  },
  elevation: {
    none: { shadowOpacity: 0, elevation: 0 },
    card: { shadowOpacity: 0, elevation: 0 },
    floating: {
      shadowColor: '#000000',
      shadowOffset: { width: 0, height: 10 },
      shadowOpacity: 0.45,
      shadowRadius: 20,
      elevation: 8,
    },
    hero: {
      shadowColor: '#4F46E5',
      shadowOffset: { width: 0, height: 12 },
      shadowOpacity: 0.36,
      shadowRadius: 24,
      elevation: 8,
    },
  },
};

export function getThemeShape(variant: ThemeStyleVariant, isDark: boolean): ThemeShape {
  if (variant === 'soft') {
    return isDark ? softDarkShape : softLightShape;
  }
  return brutalShape;
}

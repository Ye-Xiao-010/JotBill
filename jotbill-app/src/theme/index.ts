/**
 * 主题系统统一导出
 * 提供完整的设计系统配置
 */

import { colors, type ColorKey, type ThemeStyleVariant, getThemeStyleVariant } from './colors';
import { typography, type FontSizeKey, type FontWeightKey } from './typography';
import {
  spacing,
  borderRadius,
  borderWidth,
  shadow,
  type SpacingKey,
  type BorderRadiusKey,
} from './spacing';
import {
  brutalShape,
  getThemeShape,
  getThemeFonts,
  type ThemeShape,
  type ThemeElevation,
  type ThemeFonts,
} from './shape';

// 重新导出所有类型和常量
export { colors, type ColorKey, type ThemeStyleVariant, getThemeStyleVariant };
export { typography, type FontSizeKey, type FontWeightKey };
export { spacing, borderRadius, borderWidth, shadow, type SpacingKey, type BorderRadiusKey };
export {
  brutalShape,
  getThemeShape,
  getThemeFonts,
  type ThemeShape,
  type ThemeElevation,
  type ThemeFonts,
};

// 主题配置对象
export const theme = {
  colors,
  typography,
  spacing,
  borderRadius,
  borderWidth,
  shadow,
} as const;

export type Theme = typeof theme;

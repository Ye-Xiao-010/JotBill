/**
 * useStyles Hook
 * 简化动态主题样式的创建
 */
import { useMemo } from 'react';
import { StyleSheet } from 'react-native';
import { useTheme } from '../providers';
import { ThemeColors } from '../theme/colors';
import { ThemeShape } from '../theme/shape';

type StyleCreator<T> = (colors: ThemeColors, shape: ThemeShape, isDark: boolean) => T;

/**
 * 创建响应主题变化的动态样式
 * @param styleCreator 样式创建函数，接收 colors（及可选 shape / isDark）参数
 * @returns 动态样式对象
 */
export function useStyles<T>(styleCreator: StyleCreator<T>): T {
  const { colors, shape, isDark } = useTheme();
  return useMemo(() => styleCreator(colors, shape, isDark), [colors, shape, isDark, styleCreator]);
}

/**
 * 创建带有静态样式和动态样式的组合
 * @param staticStyles 静态样式（不依赖主题）
 * @param dynamicStyleCreator 动态样式创建函数
 * @returns { static, dynamic } 样式对象
 */
export function useThemedStyles<S, D>(
  staticStyles: S,
  dynamicStyleCreator: StyleCreator<D>
): { static: S; dynamic: D } {
  const { colors, shape, isDark } = useTheme();
  const dynamic = useMemo(
    () => dynamicStyleCreator(colors, shape, isDark),
    [colors, shape, isDark, dynamicStyleCreator],
  );
  return { static: staticStyles, dynamic };
}

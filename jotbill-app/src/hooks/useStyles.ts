/**
 * useStyles Hook
 * 简化动态主题样式的创建，向后兼容只接收 (colors) 的旧调用点，
 * 同时为新页面按需提供 (colors, shape, isDark)。
 */
import { useMemo } from 'react';
import { useTheme } from '../providers';
import type { ThemeColors } from '../theme/colors';
import type { ThemeShape } from '../theme/shape';

type StyleCreator<T> = (colors: ThemeColors, shape: ThemeShape, isDark: boolean) => T;

/**
 * 创建响应主题变化的动态样式
 */
export function useStyles<T>(styleCreator: StyleCreator<T>): T {
  const { colors, shape, isDark } = useTheme();
  return useMemo(() => styleCreator(colors, shape, isDark), [colors, shape, isDark, styleCreator]);
}

/**
 * 创建带有静态样式和动态样式的组合（保留原 V2 导出名 useThemedStyles）
 */
export function useThemedStyles<S, D>(
  staticStyles: S,
  dynamicStyleCreator: StyleCreator<D>,
): { static: S; dynamic: D } {
  const { colors, shape, isDark } = useTheme();
  const dynamic = useMemo(
    () => dynamicStyleCreator(colors, shape, isDark),
    [colors, shape, isDark, dynamicStyleCreator],
  );
  return { static: staticStyles, dynamic };
}

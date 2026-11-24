/**
 * 统一 RefreshControl 主题配色
 *
 * 确保深色模式下 Android 下拉刷新圈不再露出刺眼白底，指示器色与当前主题 primary 一致。
 */
import { useMemo } from 'react';
import { useTheme } from '../providers';

export function useRefreshControlProps() {
  const { colors } = useTheme();
  return useMemo(
    () => ({
      colors: [colors.primary],
      progressBackgroundColor: colors.surface,
      tintColor: colors.primary,
    }),
    [colors.primary, colors.surface],
  );
}

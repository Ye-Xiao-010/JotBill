/**
 * 原生主题与触感桥接（ThemeModule）
 *
 * - syncTheme：将当前主题名、明暗状态和底栏背景色同步给原生 Android
 *   （供付款确认悬浮窗皮肤联动 + 系统底部 NavigationBar 配色）
 * - performHaptic：通过 View.performHapticFeedback 触发系统线性马达触感（零权限）
 *
 * 在 Jest / 未注册原生模块的环境下安全降级为空操作，不抛异常。
 */
import { NativeModules, Platform } from 'react-native';

export type SoftHapticKind = 'light' | 'medium' | 'success';

interface NativeThemeModuleShape {
  syncTheme?: (themeName: string, isDark: boolean, navigationBarColor: string) => void;
  performHaptic?: (kind: SoftHapticKind) => void;
}

const getModule = (): NativeThemeModuleShape | undefined =>
  Platform.OS === 'android'
    ? (NativeModules.ThemeModule as NativeThemeModuleShape | undefined)
    : undefined;

export const nativeTheme = {
  syncTheme(themeName: string, isDark: boolean, navigationBarColor: string): void {
    try {
      getModule()?.syncTheme?.(themeName, isDark, navigationBarColor);
    } catch {
      // 原生桥接异常不影响 JS 渲染
    }
  },
  performHaptic(kind: SoftHapticKind = 'light'): void {
    try {
      getModule()?.performHaptic?.(kind);
    } catch {
      // 触感失败静默忽略
    }
  },
};

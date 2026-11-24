/**
 * 原生主题、触感、魔法音效与动态桌面图标桥接（Android ThemeModule）
 *
 * - syncTheme：将当前主题名、明暗状态和底栏背景色同步给原生 Android
 *   （供付款确认悬浮窗皮肤联动 + 系统底部 NavigationBar 配色）
 * - performHaptic：通过 View.performHapticFeedback 触发系统线性马达触感（零权限）
 * - setThemePreference：同步魔法羊皮纸主题下的学院、音效开关与动态桌面图标开关
 * - playSound / applyLauncherIconNow：触发 SoundPool 魔法音效与动态桌面图标切换
 *
 * 在 Jest / 非 Android / 未注册原生模块的环境下安全降级为空操作，不抛异常。
 */
import { NativeModules, Platform } from 'react-native';

export type SoftHapticKind = 'light' | 'medium' | 'success';
export type MagicSoundKey = 'coin_drop' | 'coin_clink' | 'quill' | 'chime';

interface RawThemeModule {
  syncTheme?: (themeName: string, isDark: boolean, navigationBarColor: string) => void;
  performHaptic?: (kind: SoftHapticKind) => void;
  setThemePreference?: (
    themeName: string,
    house: string,
    sfxEnabled: boolean,
    dynamicIconEnabled: boolean,
  ) => Promise<void>;
  playSound?: (soundKey: MagicSoundKey) => Promise<boolean>;
  applyLauncherIconNow?: () => Promise<boolean>;
}

const getModule = (): RawThemeModule | undefined =>
  Platform.OS === 'android'
    ? (NativeModules.ThemeModule as RawThemeModule | undefined)
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

  async setThemePreference(
    themeName: string,
    house: string,
    sfxEnabled: boolean,
    dynamicIconEnabled: boolean = true,
  ): Promise<void> {
    const mod = getModule();
    if (!mod?.setThemePreference) return;
    try {
      await mod.setThemePreference(themeName, house, sfxEnabled, dynamicIconEnabled);
    } catch {
      // 原生同步失败不阻断 RN 侧主题切换
    }
  },

  async playSound(soundKey: MagicSoundKey): Promise<boolean> {
    const mod = getModule();
    if (!mod?.playSound) return false;
    try {
      return await mod.playSound(soundKey);
    } catch {
      return false;
    }
  },

  async applyLauncherIconNow(): Promise<boolean> {
    const mod = getModule();
    if (!mod?.applyLauncherIconNow) return false;
    try {
      return await mod.applyLauncherIconNow();
    } catch {
      return false;
    }
  },
};

export type NativeTheme = typeof nativeTheme;

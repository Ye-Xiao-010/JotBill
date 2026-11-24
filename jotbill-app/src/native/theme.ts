/**
 * 原生主题、魔法音效与动态桌面图标桥接（Android ThemeModule）
 *
 * - 将当前 RN 主题名 / 学院 / 音效开关 / 动态桌面图标开关同步到原生 SharedPreferences，
 *   供 Android 悬浮付款确认弹窗（PopupIconAssets）、SoundPool 音效模块与桌面图标切换器读取；
 * - 桌面图标切换在 MainActivity.onStop()（退回桌面）时自动无感执行，避免切主题时闪退。
 * - 非 Android 或单测环境下静默降级为 no-op，绝不抛出阻塞 UI 的异常。
 */
import { NativeModules } from 'react-native';

export type MagicSoundKey = 'coin_drop' | 'coin_clink' | 'quill' | 'chime';

interface RawThemeModule {
  setThemePreference(
    themeName: string,
    house: string,
    sfxEnabled: boolean,
    dynamicIconEnabled: boolean,
  ): Promise<void>;
  playSound(soundKey: MagicSoundKey): Promise<boolean>;
  applyLauncherIconNow?(): Promise<boolean>;
}

function getRawThemeModule(): RawThemeModule | undefined {
  return NativeModules.ThemeModule as RawThemeModule | undefined;
}

export const nativeTheme = {
  async setThemePreference(
    themeName: string,
    house: string,
    sfxEnabled: boolean,
    dynamicIconEnabled: boolean = true,
  ): Promise<void> {
    const mod = getRawThemeModule();
    if (!mod?.setThemePreference) return;
    try {
      await mod.setThemePreference(themeName, house, sfxEnabled, dynamicIconEnabled);
    } catch {
      // 原生同步失败不阻断 RN 侧主题切换
    }
  },

  async playSound(soundKey: MagicSoundKey): Promise<boolean> {
    const mod = getRawThemeModule();
    if (!mod?.playSound) return false;
    try {
      return await mod.playSound(soundKey);
    } catch {
      return false;
    }
  },

  async applyLauncherIconNow(): Promise<boolean> {
    const mod = getRawThemeModule();
    if (!mod?.applyLauncherIconNow) return false;
    try {
      return await mod.applyLauncherIconNow();
    } catch {
      return false;
    }
  },
};

export type NativeTheme = typeof nativeTheme;

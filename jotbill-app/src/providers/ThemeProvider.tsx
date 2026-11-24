/**
 * 主题 Provider
 * 管理主题风格（themeName）、明暗模式（themeMode）、以及「魔法羊皮纸」专属的
 * 学院子风格（hogwartsHouse）与魔法交互音效开关（hogwartsSfxEnabled）。
 * 所有偏好分别持久化，并同步写入原生 Android SharedPreferences 供悬浮弹窗与 SoundPool 使用。
 */
import React, { createContext, useContext, useState, useEffect, useCallback, useMemo } from 'react';
import { useColorScheme } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import {
  themes,
  hogwartsHousePalettes,
  getHogwartsPalette,
  getThemeStyleVariant,
  type ThemeColors,
  type ThemeName,
  type ThemeStyleVariant,
  type HogwartsHousePaletteKey,
} from '../theme/colors';
import {
  getThemeShape,
  getThemeFonts,
  type ThemeShape,
  type ThemeFonts,
} from '../theme/shape';
import { nativeTheme, type MagicSoundKey } from '../native/theme';

type ThemeMode = 'light' | 'dark' | 'system';

interface ThemeContextType {
  /** 当前主题模式设置 */
  themeMode: ThemeMode;
  /** 实际使用的主题（light 或 dark） */
  isDark: boolean;
  /** 当前主题风格（blocks / mint / sunset / ink / hogwarts） */
  themeName: ThemeName;
  /** 当前主题视觉家族（brutal / hogwarts） */
  styleVariant: ThemeStyleVariant;
  /** 便捷判断：当前是否为哈利波特「魔法羊皮纸」主题 */
  isHogwarts: boolean;
  /** 魔法羊皮纸主题下的学院子风格（classic / gryffindor / slytherin / ravenclaw / hufflepuff） */
  hogwartsHouse: HogwartsHousePaletteKey;
  /** 魔法交互音效开关（仅 hogwarts 主题下生效） */
  hogwartsSfxEnabled: boolean;
  /** 同步切换手机桌面软件图标开关（hogwarts 主题切招财巫师猫/四学院，切回旧主题自动还原） */
  hogwartsDynamicIconEnabled: boolean;
  /** 主题形状与层级 token */
  shape: ThemeShape;
  /** 主题字体 token（非 hogwarts 为空对象，不改变原主题字体） */
  fonts: ThemeFonts;
  /** 当前主题颜色 */
  colors: ThemeColors;
  /** 设置主题模式 */
  setThemeMode: (mode: ThemeMode) => void;
  /** 切换深色/浅色模式 */
  toggleTheme: () => void;
  /** 设置主题风格 */
  setThemeName: (name: ThemeName) => void;
  /** 设置魔法羊皮纸学院子风格 */
  setHogwartsHouse: (house: HogwartsHousePaletteKey) => void;
  /** 设置魔法交互音效开关 */
  setHogwartsSfxEnabled: (enabled: boolean) => void;
  /** 设置是否随主题同步切换桌面图标 */
  setHogwartsDynamicIconEnabled: (enabled: boolean) => void;
  /** 播放魔法交互音效（仅 hogwarts + 音效开启时触发，支持 force 试听） */
  playMagicSound: (key: MagicSoundKey, options?: { force?: boolean }) => void;
}

const THEME_STORAGE_KEY = '@app_theme_mode';
const THEME_NAME_STORAGE_KEY = '@app_theme_name';
const HOGWARTS_HOUSE_STORAGE_KEY = '@app_hogwarts_house';
const HOGWARTS_SFX_STORAGE_KEY = '@app_hogwarts_sfx';
const HOGWARTS_DYNAMIC_ICON_STORAGE_KEY = '@app_hogwarts_dynamic_icon';

const ThemeContext = createContext<ThemeContextType | undefined>(undefined);

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const systemColorScheme = useColorScheme();
  const [themeMode, setThemeModeState] = useState<ThemeMode>('light');
  const [themeName, setThemeNameState] = useState<ThemeName>('blocks');
  const [hogwartsHouse, setHogwartsHouseState] = useState<HogwartsHousePaletteKey>('classic');
  const [hogwartsSfxEnabled, setHogwartsSfxEnabledState] = useState<boolean>(true);
  const [hogwartsDynamicIconEnabled, setHogwartsDynamicIconEnabledState] = useState<boolean>(true);
  const [isLoaded, setIsLoaded] = useState(false);

  const isDark = themeMode === 'system'
    ? systemColorScheme === 'dark'
    : themeMode === 'dark';

  const styleVariant = getThemeStyleVariant(themeName);
  const isHogwarts = styleVariant === 'hogwarts';

  const colors: ThemeColors = useMemo(() => {
    if (isHogwarts) {
      return getHogwartsPalette(hogwartsHouse, isDark);
    }
    const paletteSet = themes[themeName] ?? themes.blocks;
    return isDark ? paletteSet.dark : paletteSet.light;
  }, [isHogwarts, hogwartsHouse, themeName, isDark]);

  const shape = useMemo(() => getThemeShape(styleVariant, isDark), [styleVariant, isDark]);
  const fonts = useMemo(() => getThemeFonts(isHogwarts), [isHogwarts]);

  // 加载保存的主题设置（模式 + 风格 + 学院 + 音效 + 桌面图标）
  useEffect(() => {
    const loadTheme = async () => {
      let resolvedName: ThemeName = 'blocks';
      let resolvedHouse: HogwartsHousePaletteKey = 'classic';
      let resolvedSfx = true;
      let resolvedDynamicIcon = true;
      try {
        const [savedMode, savedName, savedHouse, savedSfx, savedDynamicIcon] = await Promise.all([
          AsyncStorage.getItem(THEME_STORAGE_KEY),
          AsyncStorage.getItem(THEME_NAME_STORAGE_KEY),
          AsyncStorage.getItem(HOGWARTS_HOUSE_STORAGE_KEY),
          AsyncStorage.getItem(HOGWARTS_SFX_STORAGE_KEY),
          AsyncStorage.getItem(HOGWARTS_DYNAMIC_ICON_STORAGE_KEY),
        ]);
        if (savedMode && ['light', 'dark', 'system'].includes(savedMode)) {
          setThemeModeState(savedMode as ThemeMode);
        }
        if (savedName && savedName in themes) {
          resolvedName = savedName as ThemeName;
          setThemeNameState(resolvedName);
        }
        if (savedHouse && savedHouse in hogwartsHousePalettes) {
          resolvedHouse = savedHouse as HogwartsHousePaletteKey;
          setHogwartsHouseState(resolvedHouse);
        }
        if (savedSfx === 'false') {
          resolvedSfx = false;
          setHogwartsSfxEnabledState(false);
        } else if (savedSfx === 'true') {
          resolvedSfx = true;
          setHogwartsSfxEnabledState(true);
        }
        if (savedDynamicIcon === 'false') {
          resolvedDynamicIcon = false;
          setHogwartsDynamicIconEnabledState(false);
        } else if (savedDynamicIcon === 'true') {
          resolvedDynamicIcon = true;
          setHogwartsDynamicIconEnabledState(true);
        }
      } catch (error) {
        console.log('[ThemeProvider] 加载主题设置失败:', error);
      } finally {
        void nativeTheme.setThemePreference(
          resolvedName,
          resolvedHouse,
          resolvedSfx,
          resolvedDynamicIcon,
        );
        setIsLoaded(true);
      }
    };
    loadTheme();
  }, []);

  const setThemeMode = useCallback(async (mode: ThemeMode) => {
    setThemeModeState(mode);
    try {
      await AsyncStorage.setItem(THEME_STORAGE_KEY, mode);
    } catch (error) {
      console.log('[ThemeProvider] 保存主题设置失败:', error);
    }
  }, []);

  const setThemeName = useCallback(async (name: ThemeName) => {
    setThemeNameState(name);
    void nativeTheme.setThemePreference(
      name,
      hogwartsHouse,
      hogwartsSfxEnabled,
      hogwartsDynamicIconEnabled,
    );
    try {
      await AsyncStorage.setItem(THEME_NAME_STORAGE_KEY, name);
    } catch (error) {
      console.log('[ThemeProvider] 保存主题风格失败:', error);
    }
  }, [hogwartsHouse, hogwartsSfxEnabled, hogwartsDynamicIconEnabled]);

  const setHogwartsHouse = useCallback(async (house: HogwartsHousePaletteKey) => {
    setHogwartsHouseState(house);
    void nativeTheme.setThemePreference(
      themeName,
      house,
      hogwartsSfxEnabled,
      hogwartsDynamicIconEnabled,
    );
    try {
      await AsyncStorage.setItem(HOGWARTS_HOUSE_STORAGE_KEY, house);
    } catch (error) {
      console.log('[ThemeProvider] 保存学院风格失败:', error);
    }
  }, [themeName, hogwartsSfxEnabled, hogwartsDynamicIconEnabled]);

  const setHogwartsSfxEnabled = useCallback(async (enabled: boolean) => {
    setHogwartsSfxEnabledState(enabled);
    void nativeTheme.setThemePreference(
      themeName,
      hogwartsHouse,
      enabled,
      hogwartsDynamicIconEnabled,
    );
    try {
      await AsyncStorage.setItem(HOGWARTS_SFX_STORAGE_KEY, enabled ? 'true' : 'false');
    } catch (error) {
      console.log('[ThemeProvider] 保存魔法音效设置失败:', error);
    }
  }, [themeName, hogwartsHouse, hogwartsDynamicIconEnabled]);

  const setHogwartsDynamicIconEnabled = useCallback(async (enabled: boolean) => {
    setHogwartsDynamicIconEnabledState(enabled);
    void nativeTheme.setThemePreference(
      themeName,
      hogwartsHouse,
      hogwartsSfxEnabled,
      enabled,
    );
    try {
      await AsyncStorage.setItem(HOGWARTS_DYNAMIC_ICON_STORAGE_KEY, enabled ? 'true' : 'false');
    } catch (error) {
      console.log('[ThemeProvider] 保存桌面图标设置失败:', error);
    }
  }, [themeName, hogwartsHouse, hogwartsSfxEnabled]);

  const toggleTheme = useCallback(() => {
    const newMode = isDark ? 'light' : 'dark';
    setThemeMode(newMode);
  }, [isDark, setThemeMode]);

  const playMagicSound = useCallback(
    (key: MagicSoundKey, options?: { force?: boolean }) => {
      if (options?.force || (isHogwarts && hogwartsSfxEnabled)) {
        void nativeTheme.playSound(key);
      }
    },
    [isHogwarts, hogwartsSfxEnabled],
  );

  if (!isLoaded) {
    return null;
  }

  return (
    <ThemeContext.Provider
      value={{
        themeMode,
        isDark,
        themeName,
        styleVariant,
        isHogwarts,
        hogwartsHouse,
        hogwartsSfxEnabled,
        hogwartsDynamicIconEnabled,
        shape,
        fonts,
        colors,
        setThemeMode,
        toggleTheme,
        setThemeName,
        setHogwartsHouse,
        setHogwartsSfxEnabled,
        setHogwartsDynamicIconEnabled,
        playMagicSound,
      }}
    >
      {children}
    </ThemeContext.Provider>
  );
}

export function useTheme() {
  const context = useContext(ThemeContext);
  if (context === undefined) {
    throw new Error('useTheme must be used within a ThemeProvider');
  }
  return context;
}

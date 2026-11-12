/**
 * 主题 Provider
 * 管理主题风格（themeName）与明暗模式（themeMode）两个正交维度：
 * colors = themes[themeName][isDark ? 'dark' : 'light']。
 * 两个维度分别持久化，重启后保留。
 */
import React, { createContext, useContext, useState, useEffect, useCallback } from 'react';
import { useColorScheme } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { themes, ThemeColors, ThemeName } from '../theme/colors';

type ThemeMode = 'light' | 'dark' | 'system';

interface ThemeContextType {
  /** 当前主题模式设置 */
  themeMode: ThemeMode;
  /** 实际使用的主题（light 或 dark） */
  isDark: boolean;
  /** 当前主题风格（blocks / mint / sunset / ink） */
  themeName: ThemeName;
  /** 当前主题颜色 */
  colors: ThemeColors;
  /** 设置主题模式 */
  setThemeMode: (mode: ThemeMode) => void;
  /** 切换深色/浅色模式 */
  toggleTheme: () => void;
  /** 设置主题风格 */
  setThemeName: (name: ThemeName) => void;
}

const THEME_STORAGE_KEY = '@app_theme_mode';
const THEME_NAME_STORAGE_KEY = '@app_theme_name';

const ThemeContext = createContext<ThemeContextType | undefined>(undefined);

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const systemColorScheme = useColorScheme();
  const [themeMode, setThemeModeState] = useState<ThemeMode>('light');
  const [themeName, setThemeNameState] = useState<ThemeName>('blocks');
  const [isLoaded, setIsLoaded] = useState(false);

  // 计算实际使用的主题
  const isDark = themeMode === 'system'
    ? systemColorScheme === 'dark'
    : themeMode === 'dark';

  const paletteSet = themes[themeName] ?? themes.blocks;
  const colors: ThemeColors = isDark ? paletteSet.dark : paletteSet.light;

  // 加载保存的主题设置（模式 + 风格）
  useEffect(() => {
    const loadTheme = async () => {
      try {
        const [savedMode, savedName] = await Promise.all([
          AsyncStorage.getItem(THEME_STORAGE_KEY),
          AsyncStorage.getItem(THEME_NAME_STORAGE_KEY),
        ]);
        if (savedMode && ['light', 'dark', 'system'].includes(savedMode)) {
          setThemeModeState(savedMode as ThemeMode);
        }
        if (savedName && savedName in themes) {
          setThemeNameState(savedName as ThemeName);
        }
      } catch (error) {
        console.log('[ThemeProvider] 加载主题设置失败:', error);
      } finally {
        setIsLoaded(true);
      }
    };
    loadTheme();
  }, []);

  // 设置主题模式
  const setThemeMode = useCallback(async (mode: ThemeMode) => {
    setThemeModeState(mode);
    try {
      await AsyncStorage.setItem(THEME_STORAGE_KEY, mode);
    } catch (error) {
      console.log('[ThemeProvider] 保存主题设置失败:', error);
    }
  }, []);

  // 设置主题风格
  const setThemeName = useCallback(async (name: ThemeName) => {
    setThemeNameState(name);
    try {
      await AsyncStorage.setItem(THEME_NAME_STORAGE_KEY, name);
    } catch (error) {
      console.log('[ThemeProvider] 保存主题风格失败:', error);
    }
  }, []);

  // 切换主题
  const toggleTheme = useCallback(() => {
    const newMode = isDark ? 'light' : 'dark';
    setThemeMode(newMode);
  }, [isDark, setThemeMode]);

  // 等待主题加载完成
  if (!isLoaded) {
    return null;
  }

  return (
    <ThemeContext.Provider value={{ themeMode, isDark, themeName, colors, setThemeMode, toggleTheme, setThemeName }}>
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

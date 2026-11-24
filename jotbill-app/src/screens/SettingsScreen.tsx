/**
 * 设置屏幕 - Neo-Brutalism 风格
 * 描边用户卡片 + 糖果色图标块 + 描边设置分组
 * 仅保留入口标题；权限相关统一收进「权限与提醒」页。
 */
import React, { useState } from 'react';
import {
  Image,
  View,
  Text,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  Switch,
} from 'react-native';
import {
  Activity,
  Bell,
  Bot,
  CreditCard,
  Database,
  Wallet,
  Flag,
  ListFilter,
  Moon,
  ShieldCheck,
  Tags,
  ChevronRight,
  Zap,
  Volume2,
} from 'lucide-react-native';
import {
  ThemeColors,
  themes,
  THEME_LABELS,
  type ThemeName,
} from '../theme/colors';
import { spacing, borderRadius, borderWidth, shadow, typography, type ThemeShape } from '../theme';
import { useStyles } from '../hooks';
import { useDisplayPreferences, useTheme } from '../providers';
import {
  HOGWARTS_FONTS,
  HOGWARTS_HOUSES,
  HOGWARTS_HOUSE_ORDER,
} from '../constants/hogwartsAssets';
import type { MagicSoundKey } from '../native/theme';


interface SettingsScreenProps {
  navigation?: any;
}

// 设置图标键：与下面的 iconMap / ICON_CANDY_COLORS 强绑定，
// 新增设置项时漏配图标会直接编译报错（此前「自动记账」就因键名漂移变成空白方块）。
type SettingsIconKey =
  | 'accounts'
  | 'category'
  | 'wallet'
  | 'flag'
  | 'netWorth'
  | 'review'
  | 'autoBookkeeping'
  | 'permissions'
  | 'ai'
  | 'runtime'
  | 'data'
  | 'rules'
  | 'moon';

// 设置项类型定义
interface BaseSettingItem {
  id: string;
  label: string;
  icon: SettingsIconKey;
  /** 可选副标题：解释开关/入口的作用，帮助用户判断是否要改 */
  description?: string;
}

interface PressableSettingItem extends BaseSettingItem {
  isSwitch?: false;
  onPress: () => void;
}

interface SwitchSettingItem extends BaseSettingItem {
  isSwitch: true;
  value: boolean;
  onValueChange: (value: boolean) => void;
}

type SettingItem = PressableSettingItem | SwitchSettingItem;

interface SettingGroup {
  title: string;
  items: SettingItem[];
}

// 糖果色图标背景映射
const ICON_CANDY_COLORS: Record<SettingsIconKey, string> = {
  accounts: '#A5D8FF',
  category: '#FFE8CC',
  wallet: '#FFD93D',
  flag: '#FF90B3',
  netWorth: '#96F2D7',
  review: '#FFC9DE',
  autoBookkeeping: '#99E9F2',
  permissions: '#B5E8DC',
  ai: '#D0BFFF',
  runtime: '#B2F2BB',
  data: '#FFD8A8',
  rules: '#D3F9D8',
  moon: '#C5A3FF',
};

export default function SettingsScreen({ navigation }: SettingsScreenProps) {
  const {
    isDark,
    toggleTheme,
    colors,
    themeName,
    setThemeName,
    isHogwarts,
    hogwartsHouse,
    setHogwartsHouse,
    hogwartsSfxEnabled,
    setHogwartsSfxEnabled,
    hogwartsDynamicIconEnabled,
    setHogwartsDynamicIconEnabled,
    playMagicSound,
  } = useTheme();
  const [auditionIndex, setAuditionIndex] = useState(0);
  const {
    showDashboardBudget,
    setShowDashboardBudget,
  } = useDisplayPreferences();
  const styles = useStyles(createStyles);

  const activeHouseMeta = HOGWARTS_HOUSES[hogwartsHouse] ?? HOGWARTS_HOUSES.classic;

  const handleAuditionSound = () => {
    const order: MagicSoundKey[] = ['coin_drop', 'quill', 'chime', 'coin_clink'];
    const key = order[auditionIndex % order.length];
    setAuditionIndex(prev => prev + 1);
    playMagicSound(key, { force: true });
  };

  const iconMap: Record<
    SettingsIconKey,
    React.FC<{ size: number; color: string }>
  > = {
    accounts: CreditCard,
    category: Tags,
    wallet: Wallet,
    flag: Flag,
    netWorth: Wallet,
    review: Bell,
    autoBookkeeping: Zap,
    permissions: ShieldCheck,
    ai: Bot,
    runtime: Activity,
    data: Database,
    rules: ListFilter,
    moon: Moon,
  };

  const settingGroups: SettingGroup[] = [
    {
      title: '💰 财务管理',
      items: [
        { id: 'accounts', label: '账户管理', icon: 'accounts', onPress: () => navigation?.navigate('Accounts') },
        { id: 'category-manage', label: '分类管理', icon: 'category', onPress: () => navigation?.navigate('Categories') },
        { id: 'budget-manage', label: '预算管理', icon: 'wallet', onPress: () => navigation?.navigate('Budgets') },
        { id: 'financial-goals', label: '财务目标', icon: 'flag', onPress: () => navigation?.navigate('FinancialGoals') },
        { id: 'net-worth', label: '净资产', icon: 'netWorth', onPress: () => navigation?.navigate('Reports') },
      ],
    },
    {
      title: '🧩 识别与自动化',
      items: [
        { id: 'review', label: '待确认中心', icon: 'review', onPress: () => navigation?.navigate('Review') },
        { id: 'auto-bookkeeping', label: '自动记账', icon: 'autoBookkeeping', onPress: () => navigation?.navigate('AutoBookkeeping') },
        { id: 'runtime-status', label: '运行状态', icon: 'runtime', onPress: () => navigation?.navigate('RuntimeStatus') },
      ],
    },
    {
      title: '🔔 权限与提醒',
      items: [
        {
          id: 'general-settings',
          label: '权限与提醒',
          icon: 'permissions',
          onPress: () => navigation?.navigate('GeneralSettings'),
        },
      ],
    },
    {
      title: '🤖 AI 与数据',
      items: [
        { id: 'ai-providers', label: 'AI 服务商', icon: 'ai', onPress: () => navigation?.navigate('AiProviders') },
        { id: 'data-management', label: '数据管理', icon: 'data', onPress: () => navigation?.navigate('DataManagement') },
        { id: 'rules', label: '规则', icon: 'rules', onPress: () => navigation?.navigate('Rules') },
      ],
    },
    {
      title: '🎨 显示',
      items: [
        {
          id: 'dark-mode',
          label: '深色模式',
          icon: 'moon',
          isSwitch: true,
          value: isDark,
          onValueChange: toggleTheme,
        },
        {
          id: 'dashboard-budget',
          label: '首页预算与目标',
          description: '显示预算进度和财务目标卡片',
          icon: 'wallet',
          isSwitch: true,
          value: showDashboardBudget,
          onValueChange: setShowDashboardBudget,
        },
      ],
    },
  ];

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <View style={styles.userInfo}>
          <View style={styles.avatarContainer}>
            {isHogwarts ? (
              <Image
                source={activeHouseMeta.crest}
                style={styles.avatar}
                resizeMode="cover"
              />
            ) : (
              <View style={styles.avatarPlaceholder}>
                <Wallet size={32} color={colors.primary} />
              </View>
            )}
          </View>
          <View style={styles.userDetails}>
            <Text style={styles.userName}>
              {isHogwarts ? '古灵阁本地账卷' : '本地账本'}
            </Text>
            <Text style={styles.userType}>
              {isHogwarts
                ? `${activeHouseMeta.label} · 数据保存在此设备`
                : '数据保存在此设备'}
            </Text>
          </View>
        </View>
      </View>

      <ScrollView style={styles.scrollView} showsVerticalScrollIndicator={false}>
        {settingGroups.map((group, groupIndex) => (
          <View key={groupIndex} style={styles.settingGroup}>
            <Text style={styles.groupTitle}>{group.title}</Text>
            <View style={styles.groupItems}>
              {group.items.map((item) => {
                const IconComponent = iconMap[item.icon];
                const candyColor = ICON_CANDY_COLORS[item.icon] || colors.accent;
                return (
                <View key={item.id}>
                  {item.isSwitch ? (
                    <View style={styles.settingItem}>
                      <View style={styles.settingLeft}>
                        <View style={[styles.iconContainer, { backgroundColor: candyColor }]}>
                          {IconComponent && <IconComponent size={20} color={colors.stroke} />}
                        </View>
                        <View style={styles.settingTextBlock}>
                          <Text style={styles.settingLabel}>{item.label}</Text>
                          {item.description ? (
                            <Text style={styles.settingDescription}>{item.description}</Text>
                          ) : null}
                        </View>
                      </View>
                      <Switch
                        value={item.value}
                        onValueChange={item.onValueChange}
                        trackColor={{ false: colors.divider, true: colors.primary }}
                        thumbColor="#FFFFFF"
                        accessibilityLabel={item.label}
                      />
                    </View>
                  ) : (
                    <TouchableOpacity
                      style={styles.settingItem}
                      onPress={item.onPress}
                      activeOpacity={0.8}
                    >
                      <View style={styles.settingLeft}>
                        <View style={[styles.iconContainer, { backgroundColor: candyColor }]}>
                          {IconComponent && <IconComponent size={20} color={colors.stroke} />}
                        </View>
                        <View style={styles.settingTextBlock}>
                          <Text style={styles.settingLabel}>{item.label}</Text>
                          {item.description ? (
                            <Text style={styles.settingDescription}>{item.description}</Text>
                          ) : null}
                        </View>
                      </View>
                      <ChevronRight size={20} color={colors.textTertiary} strokeWidth={2.5} />
                    </TouchableOpacity>
                  )}
                </View>
              )})}
            </View>
          </View>
        ))}

        {/* 主题选择：色块预览 + 名称，切换即时生效并持久化 */}
        <View style={styles.settingGroup}>
          <Text style={styles.groupTitle}>🎨 主题风格</Text>
          <View style={styles.themeGrid}>
            {(Object.keys(themes) as ThemeName[]).map(name => {
              const preview = themes[name].light;
              const active = name === themeName;
              return (
                <TouchableOpacity
                  key={name}
                  style={[styles.themeCard, active && styles.themeCardActive]}
                  onPress={() => setThemeName(name)}
                  activeOpacity={0.8}
                  accessibilityLabel={`主题 ${THEME_LABELS[name]}${active ? '，当前使用中' : ''}`}
                  accessibilityRole="button"
                  accessibilityState={{ selected: active }}
                >
                  <View style={styles.themeSwatches}>
                    <View style={[styles.themeSwatch, { backgroundColor: preview.primary }]} />
                    <View style={[styles.themeSwatch, { backgroundColor: preview.accent }]} />
                    <View
                      style={[
                        styles.themeSwatch,
                        { backgroundColor: preview.background },
                        styles.themeSwatchBordered,
                      ]}
                    />
                  </View>
                  <Text style={styles.themeName}>
                    {THEME_LABELS[name]}{active ? ' ✓' : ''}
                  </Text>
                </TouchableOpacity>
              );
            })}
          </View>
        </View>

        {/* 魔法羊皮纸专属：四学院子风格纹章切换 + 魔法交互音效 */}
        {isHogwarts ? (
          <View style={styles.settingGroup}>
            <Text style={styles.groupTitle}>🏰 学院纹章与魔法音效</Text>
            <View style={styles.houseGrid}>
              {HOGWARTS_HOUSE_ORDER.map(houseId => {
                const house = HOGWARTS_HOUSES[houseId];
                const active = houseId === hogwartsHouse;
                return (
                  <TouchableOpacity
                    key={houseId}
                    style={[styles.houseCard, active && styles.houseCardActive]}
                    onPress={() => {
                      setHogwartsHouse(houseId);
                      playMagicSound('chime');
                    }}
                    activeOpacity={0.8}
                    accessibilityLabel={`学院风格 ${house.label}${active ? '，当前使用中' : ''}`}
                    accessibilityRole="button"
                    accessibilityState={{ selected: active }}
                  >
                    <Image
                      source={house.crest}
                      style={styles.houseCrest}
                      resizeMode="cover"
                    />
                    <View style={styles.houseTextBlock}>
                      <Text style={styles.houseLabel} numberOfLines={1}>
                        {house.label}{active ? ' ✓' : ''}
                      </Text>
                      <Text style={styles.houseMotto} numberOfLines={1}>
                        {house.motto}
                      </Text>
                    </View>
                  </TouchableOpacity>
                );
              })}
            </View>

            <View style={[styles.settingItem, styles.sfxCard]}>
              <View style={styles.settingLeft}>
                <View style={[styles.iconContainer, styles.sfxIconContainer]}>
                  <Volume2 size={20} color={colors.primary} />
                </View>
                <View style={styles.settingTextBlock}>
                  <Text style={styles.settingLabel}>魔法交互音效</Text>
                  <Text style={styles.settingDescription}>
                    记账成功金币落袋、待确认钟铃与魔杖轻响
                  </Text>
                </View>
              </View>
              <View style={styles.sfxControls}>
                <TouchableOpacity
                  style={styles.auditionButton}
                  onPress={handleAuditionSound}
                  activeOpacity={0.8}
                  accessibilityLabel="试听魔法音效"
                  accessibilityRole="button"
                >
                  <Text style={styles.auditionButtonText}>试听</Text>
                </TouchableOpacity>
                <Switch
                  value={hogwartsSfxEnabled}
                  onValueChange={setHogwartsSfxEnabled}
                  trackColor={{ false: colors.divider, true: colors.primary }}
                  thumbColor="#FFFFFF"
                  accessibilityLabel="魔法交互音效"
                />
              </View>
            </View>

            <View style={[styles.settingItem, styles.sfxCard]}>
              <View style={styles.settingLeft}>
                <Image
                  source={activeHouseMeta.crest}
                  style={styles.desktopIconPreview}
                  resizeMode="cover"
                />
                <View style={styles.settingTextBlock}>
                  <Text style={styles.settingLabel}>同步切换桌面图标</Text>
                  <Text style={styles.settingDescription}>
                    随学院切换招财巫师猫 / 院徽图标，返回桌面 1–3 秒生效
                  </Text>
                </View>
              </View>
              <Switch
                value={hogwartsDynamicIconEnabled}
                onValueChange={setHogwartsDynamicIconEnabled}
                trackColor={{ false: colors.divider, true: colors.primary }}
                thumbColor="#FFFFFF"
                accessibilityLabel="同步切换桌面图标"
              />
            </View>
          </View>
        ) : null}

        <View style={styles.versionInfo}>
          <Text style={styles.versionText}>招财猫猫</Text>
          <Text style={styles.copyrightText}>© 2026 招财猫猫</Text>
        </View>
      </ScrollView>
    </View>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: colors.background,
    },
    header: {
      backgroundColor: colors.surface,
      marginHorizontal: spacing.lg,
      marginTop: spacing.lg,
      borderRadius: shape.radius.card,
      borderWidth: shape.borderWidth.card,
      borderColor: colors.cardBorder,
      padding: spacing.lg,
      ...shape.elevation.card,
    },
    userInfo: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.md,
    },
    avatarContainer: {
      width: 64,
      height: 64,
      borderRadius: shape.isHogwarts ? 32 : borderRadius.medium,
      borderWidth: shape.isHogwarts ? 2 : borderWidth.medium,
      borderColor: shape.isHogwarts ? colors.accent : colors.stroke,
      backgroundColor: colors.accent,
      overflow: 'hidden',
    },
    avatar: {
      width: '100%',
      height: '100%',
    },
    avatarPlaceholder: {
      width: '100%',
      height: '100%',
      alignItems: 'center',
      justifyContent: 'center',
    },
    userDetails: {
      flex: 1,
    },
    userName: {
      fontSize: 18,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
      marginBottom: 2,
    },
    userType: {
      fontSize: 13,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    scrollView: {
      flex: 1,
    },
    settingGroup: {
      marginTop: spacing.lg,
      paddingHorizontal: spacing.lg,
    },
    groupTitle: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textSecondary,
      marginBottom: spacing.sm,
      paddingHorizontal: spacing.xs,
    },
    groupItems: {
      gap: spacing.sm,
    },
    themeGrid: {
      flexDirection: 'row',
      flexWrap: 'wrap',
      gap: spacing.sm,
    },
    themeCard: {
      flexBasis: '48%',
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.borderWidth.card,
      borderColor: colors.cardBorder,
      padding: spacing.md,
      minHeight: 44,
    },
    themeCardActive: {
      borderWidth: shape.isHogwarts ? 2 : borderWidth.medium,
      borderColor: colors.primary,
      ...shape.elevation.card,
    },
    themeSwatches: {
      flexDirection: 'row',
      gap: spacing.xs,
      marginBottom: spacing.sm,
    },
    themeSwatch: {
      width: 24,
      height: 24,
      borderRadius: borderRadius.small,
    },
    themeSwatchBordered: {
      borderWidth: borderWidth.hairline,
      borderColor: colors.divider,
    },
    themeName: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    houseGrid: {
      gap: spacing.sm,
      marginBottom: spacing.sm,
    },
    houseCard: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.md,
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: 1,
      borderColor: colors.cardBorder,
      padding: spacing.sm + 2,
    },
    houseCardActive: {
      borderWidth: 2,
      borderColor: colors.primary,
      backgroundColor: colors.primaryLight,
      ...shape.elevation.card,
    },
    houseCrest: {
      width: 44,
      height: 44,
      borderRadius: 22,
      borderWidth: 1.5,
      borderColor: colors.accent,
    },
    houseTextBlock: {
      flex: 1,
    },
    houseLabel: {
      fontSize: 15,
      fontFamily: HOGWARTS_FONTS.display,
      color: colors.textPrimary,
    },
    houseMotto: {
      fontSize: 12,
      fontFamily: HOGWARTS_FONTS.body,
      color: colors.textSecondary,
      marginTop: 2,
    },
    sfxCard: {
      marginTop: spacing.xs,
    },
    sfxIconContainer: {
      backgroundColor: colors.primaryLight,
      borderColor: colors.accent,
    },
    desktopIconPreview: {
      width: 40,
      height: 40,
      borderRadius: 12,
      borderWidth: 1.5,
      borderColor: colors.accent,
    },
    sfxControls: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
    },
    auditionButton: {
      paddingHorizontal: spacing.sm + 2,
      paddingVertical: spacing.xs + 1,
      borderRadius: shape.radius.pill,
      backgroundColor: colors.primaryLight,
      borderWidth: 1,
      borderColor: colors.primary,
    },
    auditionButtonText: {
      fontSize: 12,
      fontFamily: HOGWARTS_FONTS.body,
      color: colors.primaryDark,
    },
    settingItem: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingVertical: spacing.md,
      paddingHorizontal: spacing.md,
      minHeight: 56,
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.borderWidth.card,
      borderColor: colors.cardBorder,
    },
    settingLeft: {
      flexDirection: 'row',
      alignItems: 'center',
      flex: 1,
      gap: spacing.md,
    },
    iconContainer: {
      width: 40,
      height: 40,
      borderRadius: shape.isHogwarts ? 12 : borderRadius.small,
      borderWidth: shape.borderWidth.card,
      borderColor: colors.cardBorder,
      alignItems: 'center',
      justifyContent: 'center',
    },
    settingLabel: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    settingTextBlock: {
      flex: 1,
      justifyContent: 'center',
      gap: 2,
    },
    settingDescription: {
      fontSize: 12,
      fontWeight: '500',
      color: colors.textTertiary,
      lineHeight: 16,
    },
    versionInfo: {
      alignItems: 'center',
      paddingTop: spacing.lg,
      paddingBottom: spacing.xl,
    },
    versionText: {
      fontSize: 12,
      fontWeight: '600',
      fontFamily: typography.fontFamily.mono,
      color: colors.textTertiary,
    },
    copyrightText: {
      fontSize: 12,
      fontWeight: '600',
      fontFamily: typography.fontFamily.mono,
      color: colors.textTertiary,
      marginTop: 4,
    },
  }),
});

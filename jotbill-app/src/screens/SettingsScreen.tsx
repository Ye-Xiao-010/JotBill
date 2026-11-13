/**
 * 设置屏幕 - Neo-Brutalism 风格
 * 描边用户卡片 + 糖果色图标块 + 描边设置分组
 */
import React from 'react';
import {
  View,
  Text,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  Switch,
} from 'react-native';
import {
  Activity,
  Bot,
  CreditCard,
  Database,
  Wallet,
  Flag,
  ListFilter,
  Settings,
  Moon,
  Info,
  ChevronRight,
  Bell,
  BellRing,
  ShieldCheck,
  Tags,
  Zap,
} from 'lucide-react-native';
import { ThemeColors, themes, THEME_LABELS, type ThemeName } from '../theme/colors';
import { spacing, borderRadius, borderWidth, shadow, typography } from '../theme';
import { useStyles } from '../hooks';
import { useDisplayPreferences, useTheme } from '../providers';


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
  | 'accessibility'
  | 'notification'
  | 'ai'
  | 'runtime'
  | 'data'
  | 'rules'
  | 'settings'
  | 'moon'
  | 'info';

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
  ai: '#D0BFFF',
  runtime: '#B2F2BB',
  data: '#FFD8A8',
  netWorth: '#96F2D7',
  rules: '#D3F9D8',
  wallet: '#FFD93D',
  flag: '#FF90B3',
  settings: '#7EB6FF',
  moon: '#C5A3FF',
  info: '#7DCEA0',
  review: '#FFC9DE',
  autoBookkeeping: '#99E9F2',
  accessibility: '#B5E8DC',
  notification: '#FFD6A5',
  category: '#FFE8CC',
};

export default function SettingsScreen({ navigation }: SettingsScreenProps) {
  const { isDark, toggleTheme, colors, themeName, setThemeName } = useTheme();
  const {
    showDashboardBudget,
    setShowDashboardBudget,
  } = useDisplayPreferences();
  const styles = useStyles(createStyles);

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
    accessibility: ShieldCheck,
    notification: BellRing,
    ai: Bot,
    runtime: Activity,
    data: Database,
    rules: ListFilter,
    settings: Settings,
    moon: Moon,
    info: Info,
  };

  const settingGroups: SettingGroup[] = [
    {
      title: '💰 财务',
      items: [
        { id: 'accounts', label: '账户管理', icon: 'accounts', onPress: () => navigation?.navigate('Accounts') },
        { id: 'category-manage', label: '分类管理', icon: 'category', onPress: () => navigation?.navigate('Categories') },
        { id: 'budget-manage', label: '预算管理', icon: 'wallet', onPress: () => navigation?.navigate('Budgets') },
        { id: 'financial-goals', label: '财务目标', icon: 'flag', onPress: () => navigation?.navigate('FinancialGoals') },
        { id: 'net-worth', label: '净资产', icon: 'netWorth', onPress: () => navigation?.navigate('Reports') },
      ],
    },
    {
      title: '🧩 本地能力',
      items: [
        { id: 'review', label: '待确认中心', icon: 'review', onPress: () => navigation?.navigate('Review') },
        { id: 'auto-bookkeeping', label: '自动记账', icon: 'autoBookkeeping', onPress: () => navigation?.navigate('AutoBookkeeping') },
        { id: 'accessibility', label: '无障碍权限', icon: 'accessibility', onPress: () => navigation?.navigate('AccessibilityPermission') },
        { id: 'notification', label: '通知权限', icon: 'notification', onPress: () => navigation?.navigate('NotificationPermission') },
        { id: 'ai-providers', label: 'AI 服务商', icon: 'ai', onPress: () => navigation?.navigate('AiProviders') },
        { id: 'runtime-status', label: '运行状态', icon: 'runtime', onPress: () => navigation?.navigate('RuntimeStatus') },
        { id: 'data-management', label: '数据管理', icon: 'data', onPress: () => navigation?.navigate('DataManagement') },
        { id: 'rules', label: '规则', icon: 'rules', onPress: () => navigation?.navigate('Rules') },
      ],
    },
    {
      title: '⚙️ 应用',
      items: [
        {
          id: 'general-settings',
          label: '通用配置',
          icon: 'settings',
          onPress: () => navigation?.navigate('GeneralSettings'),
        },
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
    {
      title: 'ℹ️ 关于',
      items: [
        { id: 'about', label: '关于应用', icon: 'info', onPress: () => {} },
      ],
    },
  ];

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <View style={styles.userInfo}>
          <View style={styles.avatarContainer}>
            <View style={styles.avatarPlaceholder}>
              <Wallet size={32} color={colors.primary} />
            </View>
          </View>
          <View style={styles.userDetails}>
            <Text style={styles.userName}>本地账本</Text>
            <Text style={styles.userType}>数据保存在此设备</Text>
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

        <View style={styles.versionInfo}>
          <Text style={styles.versionText}>猫猫招财</Text>
          <Text style={styles.copyrightText}>© 2026 猫猫招财</Text>
        </View>
      </ScrollView>
    </View>
  );
}

const createStyles = (colors: ThemeColors) => ({
  ...StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: colors.background,
    },
    header: {
      backgroundColor: colors.surface,
      marginHorizontal: spacing.lg,
      marginTop: spacing.lg,
      borderRadius: borderRadius.card,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      padding: spacing.lg,
      ...shadow.small,
    },
    userInfo: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.md,
    },
    avatarContainer: {
      width: 64,
      height: 64,
      borderRadius: borderRadius.medium,
      borderWidth: borderWidth.medium,
      borderColor: colors.stroke,
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
      fontWeight: '800',
      color: colors.textPrimary,
      marginBottom: 2,
    },
    userType: {
      fontSize: 13,
      fontWeight: '600',
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
      fontWeight: '800',
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
      borderRadius: borderRadius.card,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      padding: spacing.md,
      minHeight: 44,
    },
    themeCardActive: {
      borderWidth: borderWidth.medium,
      borderColor: colors.primary,
      ...shadow.small,
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
      fontWeight: '700',
      color: colors.textPrimary,
    },
    settingItem: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingVertical: spacing.md,
      paddingHorizontal: spacing.md,
      minHeight: 56,
      backgroundColor: colors.surface,
      borderRadius: borderRadius.card,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
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
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      alignItems: 'center',
      justifyContent: 'center',
    },
    settingLabel: {
      fontSize: 15,
      fontWeight: '700',
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

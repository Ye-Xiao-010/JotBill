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
} from 'lucide-react-native';
import { ThemeColors } from '../theme/colors';
import { spacing, borderRadius, borderWidth, shadow } from '../theme';
import { useStyles } from '../hooks';
import { useTheme } from '../providers';

interface SettingsScreenProps {
  navigation?: any;
}

// 设置项类型定义
interface BaseSettingItem {
  id: string;
  label: string;
  icon: string;
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
const ICON_CANDY_COLORS: Record<string, string> = {
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
};

export default function SettingsScreen({ navigation }: SettingsScreenProps) {
  const { isDark, toggleTheme } = useTheme();
  const styles = useStyles(createStyles);

  const iconMap: Record<string, React.FC<{ size: number; color: string }>> = {
    accounts: CreditCard,
    ai: Bot,
    runtime: Activity,
    data: Database,
    netWorth: Wallet,
    rules: ListFilter,
    wallet: Wallet,
    flag: Flag,
    settings: Settings,
    moon: Moon,
    info: Info,
  };

  const settingGroups: SettingGroup[] = [
    {
      title: '💰 财务',
      items: [
        { id: 'accounts', label: '账户管理', icon: 'accounts', onPress: () => navigation?.navigate('Accounts') },
        { id: 'budget-manage', label: '预算管理', icon: 'wallet', onPress: () => navigation?.navigate('Budgets') },
        { id: 'financial-goals', label: '财务目标', icon: 'flag', onPress: () => navigation?.navigate('FinancialGoals') },
        { id: 'net-worth', label: '净资产', icon: 'netWorth', onPress: () => navigation?.navigate('Reports') },
      ],
    },
    {
      title: '🧩 本地能力',
      items: [
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
              <Wallet size={32} color={styles._colors.primary} />
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
                const candyColor = ICON_CANDY_COLORS[item.icon] || styles._colors.accent;
                return (
                <View key={item.id}>
                  {item.isSwitch ? (
                    <View style={styles.settingItem}>
                      <View style={styles.settingLeft}>
                        <View style={[styles.iconContainer, { backgroundColor: candyColor }]}>
                          {IconComponent && <IconComponent size={20} color={styles._colors.stroke} />}
                        </View>
                        <Text style={styles.settingLabel}>{item.label}</Text>
                      </View>
                      <Switch
                        value={item.value}
                        onValueChange={item.onValueChange}
                        trackColor={{ false: styles._colors.divider, true: styles._colors.primary }}
                        thumbColor="#FFFFFF"
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
                          {IconComponent && <IconComponent size={20} color={styles._colors.stroke} />}
                        </View>
                        <Text style={styles.settingLabel}>{item.label}</Text>
                      </View>
                      <ChevronRight size={20} color={styles._colors.textTertiary} strokeWidth={2.5} />
                    </TouchableOpacity>
                  )}
                </View>
              )})}
            </View>
          </View>
        ))}

        <View style={styles.versionInfo}>
          <Text style={styles.versionText}>JotBill</Text>
          <Text style={styles.copyrightText}>© 2026 JotBill</Text>
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
    versionInfo: {
      alignItems: 'center',
      paddingTop: spacing.lg,
      paddingBottom: spacing.xl,
    },
    versionText: {
      fontSize: 12,
      fontWeight: '600',
      fontFamily: 'Courier',
      color: colors.textTertiary,
    },
    copyrightText: {
      fontSize: 12,
      fontWeight: '600',
      fontFamily: 'Courier',
      color: colors.textTertiary,
      marginTop: 4,
    },
  }),
  _colors: colors,
});

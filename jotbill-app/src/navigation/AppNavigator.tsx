/**
 * 应用导航器 - Neo-Brutalism 风格
 * 粗描边 Tab Bar + 糖果色活跃态 + 方圆角"+"按钮 + 实心阴影
 */
import React, { useState, useMemo, useEffect } from 'react';
import { Keyboard, View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import { NavigationContainer, DefaultTheme, DarkTheme } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import * as Sentry from '@sentry/react-native';
import { Home, BarChart3, MessageCircle, Sparkles, User, Plus } from 'lucide-react-native';

// 导入屏幕组件
import SettingsScreen from '../screens/SettingsScreen';
import GeneralSettingsScreen from '../screens/GeneralSettingsScreen';
import {
  CreateBillScreen,
  AllBillsScreen,
  CategoryManageScreen,
  BillDetailScreen,
} from '../screens/finance';
import { DashboardScreen } from '../screens/dashboard';
import { ReportsScreen } from '../screens/reports';
import { AIChatScreen, AIChatSessionsScreen } from '../screens/ai';
import FinancialGoalScreen from '../screens/FinancialGoalScreen';
import BudgetScreen from '../screens/BudgetScreen';
import AccountsScreen from '../screens/settings/AccountsScreen';
import AiProviderScreen from '../screens/settings/AiProviderScreen';
import RuntimeStatusScreen from '../screens/settings/RuntimeStatusScreen';
import DataManagementScreen from '../screens/settings/DataManagementScreen';
import ImportScreen from '../screens/settings/ImportScreen';
import RulesScreen from '../screens/settings/RulesScreen';
import AccessibilityPermissionScreen from '../screens/settings/AccessibilityPermissionScreen';
import NotificationPermissionScreen from '../screens/settings/NotificationPermissionScreen';
import AutoBookkeepingScreen from '../screens/settings/AutoBookkeepingScreen';
import ReviewScreen from '../screens/review/ReviewScreen';

import { useTheme } from '../providers';
import { SoftGradient } from '../components/ui';
import { spacing, borderRadius, borderWidth } from '../theme/spacing';
import { ThemeColors } from '../theme/colors';
import { ThemeShape } from '../theme/shape';
import '../types/navigation';

const Stack = createNativeStackNavigator();

// 使用 lucide-react-native 图标 - Neo-Brutalism 加粗描边
const HomeIcon = ({ color, size = 24 }: { color: string; size?: number }) => (
  <Home size={size} color={color} strokeWidth={2.5} />
);

const ChartIcon = ({ color, size = 24 }: { color: string; size?: number }) => (
  <BarChart3 size={size} color={color} strokeWidth={2.5} />
);

const ChatIcon = ({ color, size = 24 }: { color: string; size?: number }) => (
  <MessageCircle size={size} color={color} strokeWidth={2.5} />
);

const UserIcon = ({ color, size = 24 }: { color: string; size?: number }) => (
  <User size={size} color={color} strokeWidth={2.5} />
);

// 底部导航标签配置
interface TabConfig {
  key: string;
  label: string;
  IconComponent: React.FC<{ color: string; size?: number }>;
}

const tabs: TabConfig[] = [
  { key: 'dashboard', label: '首页', IconComponent: HomeIcon },
  { key: 'reports', label: '统计', IconComponent: ChartIcon },
  { key: 'add', label: '记账', IconComponent: () => null },
  { key: 'ai', label: 'AI助手', IconComponent: ChatIcon },
  { key: 'settings', label: '我的', IconComponent: UserIcon },
];

// Neo-Brutalism 底部导航栏组件
interface BottomTabBarProps {
  activeTab: string;
  onTabPress: (key: string) => void;
  colors: ThemeColors;
}

function BottomTabBar({ activeTab, onTabPress, colors }: BottomTabBarProps) {
  const dynamicStyles = useMemo(() => StyleSheet.create({
    container: {
      flexDirection: 'row',
      backgroundColor: colors.surface,
      borderTopWidth: borderWidth.thick,
      borderTopColor: colors.stroke,
      paddingBottom: spacing.sm,
      paddingTop: spacing.sm,
    },
    tabLabel: {
      fontSize: 10,
      color: colors.textTertiary,
      fontWeight: '600',
      marginTop: 3,
    },
    tabLabelActive: {
      color: colors.textPrimary,
      fontWeight: '700',
    },
    // 活跃 tab 的背景块 — 始终保留 border 占位，避免切换时 borderRadius 表现不一致
    tabIconBlock: {
      width: 36,
      height: 36,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: 'transparent',
      alignItems: 'center',
      justifyContent: 'center',
    },
    tabIconBlockActive: {
      backgroundColor: colors.primaryLight,
      borderColor: colors.stroke,
    },
    // 中央"+"按钮 - Neo-Brutalism 方圆角 + 实心阴影
    addButtonOuter: {
      marginTop: -22,
    },
    addButtonShadow: {
      position: 'absolute',
      width: 52,
      height: 52,
      borderRadius: borderRadius.medium,
      backgroundColor: colors.stroke,
      top: 3,
      left: 3,
    },
    addButton: {
      width: 52,
      height: 52,
      borderRadius: borderRadius.medium,
      backgroundColor: colors.accent,
      alignItems: 'center',
      justifyContent: 'center',
      borderWidth: borderWidth.thick,
      borderColor: colors.stroke,
    },
    addButtonLabel: {
      fontSize: 10,
      color: colors.textTertiary,
      fontWeight: '600',
      marginTop: 6,
    },
  }), [colors]);

  return (
    <View style={dynamicStyles.container}>
      {tabs.map((tab) => {
        const isActive = activeTab === tab.key;
        const isAddButton = tab.key === 'add';

        if (isAddButton) {
          return (
            <TouchableOpacity
              key={tab.key}
              onPress={() => onTabPress(tab.key)}
              style={tabStyles.addButtonContainer}
              activeOpacity={0.8}
            >
              <View style={dynamicStyles.addButtonOuter}>
                <View style={dynamicStyles.addButtonShadow} />
                <View style={dynamicStyles.addButton}>
                  <Text style={tabStyles.addButtonIcon}>+</Text>
                </View>
              </View>
              <Text style={dynamicStyles.addButtonLabel}>{tab.label}</Text>
            </TouchableOpacity>
          );
        }

        const IconComponent = tab.IconComponent;
        const iconColor = isActive ? colors.primary : colors.textTertiary;

        return (
          <TouchableOpacity
            key={tab.key}
            onPress={() => onTabPress(tab.key)}
            style={tabStyles.tab}
            activeOpacity={0.7}
          >
            <View style={[
              dynamicStyles.tabIconBlock,
              isActive && dynamicStyles.tabIconBlockActive,
            ]}>
              <IconComponent color={iconColor} size={22} />
            </View>
            <Text style={[dynamicStyles.tabLabel, isActive && dynamicStyles.tabLabelActive]}>
              {tab.label}
            </Text>
          </TouchableOpacity>
        );
      })}
    </View>
  );
}

const tabStyles = StyleSheet.create({
  tab: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: spacing.xs,
  },
  addButtonContainer: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'flex-start',
  },
  addButtonIcon: {
    fontSize: 28,
    fontWeight: '800',
    color: '#1A1A1A',
    marginTop: -2,
  },
});

// 柔光主题悬浮胶囊底栏：大圆角半透明胶囊 + 浅底药丸活跃态 + 圆形渐变 FAB
function SoftBottomTabBar({
  activeTab,
  onTabPress,
  colors,
  shape,
  isDark,
}: {
  activeTab: string;
  onTabPress: (key: string) => void;
  colors: ThemeColors;
  shape: ThemeShape;
  isDark: boolean;
}) {
  const insets = useSafeAreaInsets();
  const [keyboardVisible, setKeyboardVisible] = useState(false);

  useEffect(() => {
    const showSub = Keyboard.addListener('keyboardDidShow', () => setKeyboardVisible(true));
    const hideSub = Keyboard.addListener('keyboardDidHide', () => setKeyboardVisible(false));
    return () => {
      showSub.remove();
      hideSub.remove();
    };
  }, []);

  const softStyles = useMemo(() => StyleSheet.create({
    wrapper: {
      position: 'absolute',
      left: 12,
      right: 12,
      bottom: Math.max(insets.bottom, 12),
      height: 68,
      borderRadius: 26,
      backgroundColor: isDark ? 'rgba(23, 26, 33, 0.94)' : 'rgba(255, 255, 255, 0.94)',
      borderWidth: isDark ? 1 : StyleSheet.hairlineWidth,
      borderColor: isDark ? colors.cardBorder : 'rgba(18, 20, 26, 0.06)',
      flexDirection: 'row',
      alignItems: 'center',
      ...shape.elevation.floating,
    },
    tab: {
      flex: 1,
      alignItems: 'center',
      justifyContent: 'center',
      gap: 3,
      height: '100%',
    },
    tabPill: {
      width: 52,
      height: 30,
      borderRadius: 999,
      alignItems: 'center',
      justifyContent: 'center',
    },
    tabPillActive: {
      backgroundColor: colors.primaryLight,
    },
    tabLabel: {
      fontSize: 11,
      fontWeight: '500',
      color: colors.textTertiary,
    },
    tabLabelActive: {
      color: colors.primary,
      fontWeight: '600',
    },
    fabSlot: {
      flex: 1,
      alignItems: 'center',
      justifyContent: 'center',
    },
    fabRing: {
      marginTop: -28,
      width: 60,
      height: 60,
      borderRadius: 30,
      backgroundColor: colors.background,
      alignItems: 'center',
      justifyContent: 'center',
      ...shape.elevation.hero,
    },
    fabGradient: {
      width: 52,
      height: 52,
      borderRadius: 26,
      alignItems: 'center',
      justifyContent: 'center',
    },
  }), [colors, shape, isDark, insets.bottom]);

  if (keyboardVisible) {
    return null;
  }

  const softTabs = [
    { key: 'dashboard', label: '首页', Icon: Home },
    { key: 'reports', label: '统计', Icon: BarChart3 },
    { key: 'add', label: '记账', Icon: Plus },
    { key: 'ai', label: 'AI 助手', Icon: Sparkles },
    { key: 'settings', label: '我的', Icon: User },
  ];

  return (
    <View style={softStyles.wrapper}>
      {softTabs.map((tab) => {
        if (tab.key === 'add') {
          return (
            <TouchableOpacity
              key={tab.key}
              onPress={() => onTabPress('add')}
              style={softStyles.fabSlot}
              activeOpacity={0.85}
              accessibilityLabel="记一笔"
              accessibilityRole="button"
            >
              <View style={softStyles.fabRing}>
                <SoftGradient
                  colors={[colors.gradientPrimaryStart, colors.primary, colors.gradientPrimaryEnd]}
                  style={softStyles.fabGradient}
                >
                  <Plus size={26} color="#FFFFFF" strokeWidth={2.5} />
                </SoftGradient>
              </View>
            </TouchableOpacity>
          );
        }

        const isActive = activeTab === tab.key;
        const Icon = tab.Icon;
        const iconColor = isActive ? colors.primary : colors.textTertiary;

        return (
          <TouchableOpacity
            key={tab.key}
            onPress={() => onTabPress(tab.key)}
            style={softStyles.tab}
            activeOpacity={0.75}
            accessibilityRole="tab"
            accessibilityState={{ selected: isActive }}
          >
            <View style={[softStyles.tabPill, isActive && softStyles.tabPillActive]}>
              <Icon
                size={21}
                color={iconColor}
                strokeWidth={isActive ? shape.iconStrokeWidth.active : shape.iconStrokeWidth.regular}
              />
            </View>
            <Text style={[softStyles.tabLabel, isActive && softStyles.tabLabelActive]}>
              {tab.label}
            </Text>
          </TouchableOpacity>
        );
      })}
    </View>
  );
}

// 主导航容器组件
function MainNavigator({ navigation }: { navigation: any }) {
  const [activeTab, setActiveTab] = useState('dashboard');
  const { colors, shape, isSoft, isDark } = useTheme();

  const dynamicStyles = useMemo(() => StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: colors.background,
    },
  }), [colors]);

  const handleTabPress = (key: string) => {
    if (key === 'add') {
      Sentry.captureMessage('用户点击记账按钮', {
        level: 'info',
        tags: {
          action: 'navigation',
          screen: 'create_bill',
        },
      });
      navigation.navigate('CreateBill');
    } else {
      setActiveTab(key);
    }
  };

  const renderScreen = () => {
    switch (activeTab) {
      case 'dashboard':
        return <DashboardScreen onSelectTab={setActiveTab} />;
      case 'reports':
        return <ReportsScreen />;
      case 'settings':
        return <SettingsScreen navigation={navigation} />;
      case 'ai':
        return <AIChatScreen />;
      default:
        return <DashboardScreen onSelectTab={setActiveTab} />;
    }
  };

  return (
    <SafeAreaView
      style={dynamicStyles.container}
      edges={isSoft ? ['top'] : ['top', 'bottom']}
    >
      <View style={styles.content}>
        {renderScreen()}
      </View>
      {isSoft ? (
        <SoftBottomTabBar
          activeTab={activeTab}
          onTabPress={handleTabPress}
          colors={colors}
          shape={shape}
          isDark={isDark}
        />
      ) : (
        <BottomTabBar activeTab={activeTab} onTabPress={handleTabPress} colors={colors} />
      )}
    </SafeAreaView>
  );
}

// 根导航器
export default function AppNavigator() {
  const { isDark, isSoft, colors } = useTheme();

  // 导航主题
  const navigationTheme = useMemo(() => ({
    ...(isDark ? DarkTheme : DefaultTheme),
    colors: {
      ...(isDark ? DarkTheme.colors : DefaultTheme.colors),
      primary: colors.primary,
      background: colors.background,
      card: isSoft ? colors.background : colors.surface,
      text: colors.textPrimary,
      border: isSoft ? colors.divider : colors.stroke,
    },
  }), [isDark, isSoft, colors]);

  // Stack Header 样式：柔光去粗黑底边，积木风保持 3px 描边
  const stackScreenOptions = useMemo(() => ({
    headerShown: false,
    headerStyle: isSoft
      ? ({
          backgroundColor: colors.background,
        } as any)
      : ({
          backgroundColor: colors.surface,
          borderBottomWidth: borderWidth.medium,
          borderBottomColor: colors.stroke,
        } as any),
    headerTitleStyle: {
      fontSize: 18,
      fontWeight: (isSoft ? '700' : '800') as '700' | '800',
      color: colors.textPrimary,
    },
    headerTintColor: colors.textPrimary,
    headerShadowVisible: false,
  }), [colors, isSoft]);

  const billModalOptions = useMemo(
    () => (title: string) =>
      isSoft
        ? {
            headerShown: false,
            presentation: 'transparentModal' as const,
            animation: 'slide_from_bottom' as const,
            contentStyle: { backgroundColor: 'transparent' },
          }
        : {
            headerShown: true,
            title,
          },
    [isSoft],
  );

  return (
    <NavigationContainer theme={navigationTheme}>
      <Stack.Navigator screenOptions={stackScreenOptions}>
        <Stack.Screen name="Main" component={MainNavigator} />
        <Stack.Screen name="Settings" component={SettingsScreen} />
        <Stack.Screen
          name="Reports"
          component={ReportsScreen}
          options={{ headerShown: true, title: '净资产与报表' }}
        />
        <Stack.Screen
          name="GeneralSettings"
          component={GeneralSettingsScreen}
          options={{ headerShown: false }}
        />
        <Stack.Screen
          name="CreateBill"
          component={CreateBillScreen}
          options={billModalOptions('创建账单')}
        />
        <Stack.Screen
          name="EditBill"
          component={CreateBillScreen}
          options={billModalOptions('编辑账单')}
        />
        <Stack.Screen
          name="AllBills"
          component={AllBillsScreen}
          options={{
            headerShown: true,
            title: '全部账单',
          }}
        />
        <Stack.Screen
          name="Categories"
          component={CategoryManageScreen}
          options={{
            headerShown: true,
            title: '分类管理',
          }}
        />
        <Stack.Screen
          name="BillDetail"
          component={BillDetailScreen}
          options={{
            headerShown: true,
            title: '交易详情',
          }}
        />
        <Stack.Screen
          name="FinancialGoals"
          component={FinancialGoalScreen}
          options={{ headerShown: false }}
        />
        <Stack.Screen
          name="Budgets"
          component={BudgetScreen}
          options={{ headerShown: false }}
        />
        <Stack.Screen
          name="Accounts"
          component={AccountsScreen}
          options={{ headerShown: false }}
        />
        <Stack.Screen
          name="AiProviders"
          component={AiProviderScreen}
          options={{ headerShown: false }}
        />
        <Stack.Screen
          name="RuntimeStatus"
          component={RuntimeStatusScreen}
          options={{ headerShown: false }}
        />
        <Stack.Screen
          name="DataManagement"
          component={DataManagementScreen}
          options={{ headerShown: false }}
        />
        <Stack.Screen
          name="Import"
          component={ImportScreen}
          options={{ headerShown: false }}
        />
        <Stack.Screen
          name="Rules"
          component={RulesScreen}
          options={{ headerShown: false }}
        />
        <Stack.Screen
          name="Review"
          component={ReviewScreen}
          options={{ headerShown: false }}
        />
        <Stack.Screen
          name="AccessibilityPermission"
          component={AccessibilityPermissionScreen}
          options={{ headerShown: false }}
        />
        <Stack.Screen
          name="NotificationPermission"
          component={NotificationPermissionScreen}
          options={{ headerShown: false }}
        />
        <Stack.Screen
          name="AutoBookkeeping"
          component={AutoBookkeepingScreen}
          options={{ headerShown: false }}
        />
        <Stack.Screen
          name="AIChatSessions"
          component={AIChatSessionsScreen}
          options={{ headerShown: false }}
        />
        <Stack.Screen
          name="AIChat"
          component={AIChatScreen}
          options={{ headerShown: false }}
        />
      </Stack.Navigator>
    </NavigationContainer>
  );
}

const styles = StyleSheet.create({
  content: {
    flex: 1,
  },
});

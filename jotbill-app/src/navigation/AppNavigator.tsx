/**
 * 应用导航器 - Neo-Brutalism 风格
 * 粗描边 Tab Bar + 糖果色活跃态 + 方圆角"+"按钮 + 实心阴影
 */
import React, { useState, useMemo } from 'react';
import { View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { NavigationContainer, DefaultTheme, DarkTheme } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import * as Sentry from '@sentry/react-native';
import { Home, BarChart3, MessageCircle, User } from 'lucide-react-native';

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
import ReviewScreen from '../screens/review/ReviewScreen';

import { useTheme } from '../providers';
import { spacing, borderRadius, borderWidth } from '../theme/spacing';
import { ThemeColors } from '../theme/colors';
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

// 主导航容器组件
function MainNavigator({ navigation }: { navigation: any }) {
  const [activeTab, setActiveTab] = useState('dashboard');
  const { colors } = useTheme();

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
        return <DashboardScreen />;
      case 'reports':
        return <ReportsScreen />;
      case 'settings':
        return <SettingsScreen navigation={navigation} />;
      case 'ai':
        return <AIChatScreen />;
      default:
        return <DashboardScreen />;
    }
  };

  return (
    <SafeAreaView style={dynamicStyles.container} edges={['top', 'bottom']}>
      <View style={styles.content}>
        {renderScreen()}
      </View>
      <BottomTabBar activeTab={activeTab} onTabPress={handleTabPress} colors={colors} />
    </SafeAreaView>
  );
}

// 根导航器
export default function AppNavigator() {
  const { isDark, colors } = useTheme();

  // Neo-Brutalism 导航主题
  const navigationTheme = useMemo(() => ({
    ...(isDark ? DarkTheme : DefaultTheme),
    colors: {
      ...(isDark ? DarkTheme.colors : DefaultTheme.colors),
      primary: colors.primary,
      background: colors.background,
      card: colors.surface,
      text: colors.textPrimary,
      border: colors.stroke,
    },
  }), [isDark, colors]);

  // Neo-Brutalism Stack Header 样式
  const stackScreenOptions = useMemo(() => ({
    headerShown: false,
    headerStyle: {
      backgroundColor: colors.surface,
      borderBottomWidth: borderWidth.medium,
      borderBottomColor: colors.stroke,
    } as any,
    headerTitleStyle: {
      fontSize: 18,
      fontWeight: '800' as const,
      color: colors.textPrimary,
    },
    headerTintColor: colors.textPrimary,
    headerShadowVisible: false,
  }), [colors]);

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
          options={{
            headerShown: true,
            title: '创建账单',
          }}
        />
        <Stack.Screen
          name="EditBill"
          component={CreateBillScreen}
          options={{
            headerShown: true,
            title: '编辑账单',
          }}
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

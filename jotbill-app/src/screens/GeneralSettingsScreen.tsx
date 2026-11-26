/**
 * 权限与提醒设置页 - Neo-Brutalism 风格
 * 一页收拢 5 项系统权限（无障碍/悬浮窗/通知监听/通知提醒/电池优化）与 2 个提醒开关。
 *
 * 设计约束（2026-10-31 设置整改批次）：
 *   · 权限状态统一读取 `Runtime.getRuntimeHealth()`（通知提醒一项用 PermissionsAndroid.check），
 *     不再混用 PaymentNotificationModule 的第二套状态 API；
 *   · 跳转每个权限一个入口：无障碍 → Recognition.openAccessibilitySettings、
 *     通知监听 → Runtime.openNotificationListenerSettings、悬浮窗 → PaymentNotificationModule
 *     的 requestOverlayPermission（overlay.ts 的同名方法已随截图链路删除）、
 *     电池优化无专用跳转走 Linking.openSettings；
 *   · 通知监听仅辅助识别退款/手续费，不参与支付自动记账（依赖无障碍读取结算页）；
 *     页面仅保留标题与状态，详细说明收进无障碍/通知监听两个引导页。
 */
import React, { useCallback, useEffect, useState } from 'react';
import {
  AppState,
  Linking,
  PermissionsAndroid,
  Platform,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TouchableOpacity,
  View,
  type AppStateStatus,
} from 'react-native';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Battery,
  Bell,
  BellRing,
  ChevronLeft,
  ChevronRight,
  Layers3,
  ShieldCheck,
} from 'lucide-react-native';
import { nativeBudgetReminder } from '../native/budgetReminder';
import { nativePendingReminder } from '../native/pendingReminder';
import { nativeRecognition, nativeRuntime } from '../native';
import { paymentNotificationService } from '../services/paymentNotification';
import { useAlert } from '../providers';
import { useStyles, useRefreshControlProps } from '../hooks';
import { HOGWARTS_FONTS } from '../constants/hogwartsAssets';
import { borderRadius, borderWidth, shadow, spacing } from '../theme';
import { ThemeColors } from '../theme/colors';
import { ThemeShape } from '../theme/shape';
import { SafeAreaView } from 'react-native-safe-area-context';

/** 与 RuntimeStatusScreen 同键同源（getRuntimeHealth），两页共享缓存、互相刷新。 */
const HEALTH_QUERY_KEY = ['runtime-status'] as const;

function StatusBadge({
  enabled,
  colors,
  isSoft,
  isDark,
}: {
  enabled: boolean | undefined;
  colors: ThemeColors;
  isSoft: boolean;
  isDark: boolean;
}) {
  if (enabled === undefined) {
    return (
      <View
        style={[
          badgeStyles.base,
          isSoft && badgeStyles.softBase,
          { backgroundColor: isSoft ? colors.surfaceMuted : colors.divider },
        ]}
      >
        <Text style={[badgeStyles.text, isSoft && { color: colors.textTertiary }]}>读取中…</Text>
      </View>
    );
  }
  return (
    <View
      style={[
        badgeStyles.base,
        isSoft && badgeStyles.softBase,
        {
          backgroundColor: isSoft
            ? (enabled ? colors.incomeLight : colors.warning + '22')
            : (enabled ? colors.success : colors.warning),
        },
      ]}
    >
      <Text
        style={[
          badgeStyles.text,
          isSoft && {
            color: enabled
              ? colors.incomeStrong
              : (isDark ? colors.warning : '#B45309'),
          },
        ]}
      >
        {enabled ? '已开启' : '未开启'}
      </Text>
    </View>
  );
}

const badgeStyles = StyleSheet.create({
  base: {
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xs,
    borderRadius: borderRadius.small,
    borderWidth: borderWidth.thin,
    borderColor: '#FFFFFF',
  },
  softBase: {
    borderRadius: 999,
    borderWidth: 0,
    paddingHorizontal: 10,
    paddingVertical: 3,
  },
  text: {
    fontSize: 12,
    fontWeight: '700',
    color: '#FFFFFF',
  },
});

interface PermissionRow {
  id: string;
  icon: React.FC<{ size: number; color: string }>;
  label: string;
  enabled: boolean | undefined;
  onOpen: () => Promise<unknown> | void;
  /** 有分步引导页的权限，附「开启方法」入口。 */
  guideRoute?: string;
}

export default function GeneralSettingsScreen() {
  const navigation = useNavigation();
  const queryClient = useQueryClient();
  const styles = useStyles(createStyles);
  const refreshControlProps = useRefreshControlProps();
  const { alert } = useAlert();

  const [openingId, setOpeningId] = useState<string | null>(null);
  // 通知提醒（POST_NOTIFICATIONS）运行时授权状态；Android 13 以下视为已授权
  const [postNotificationGranted, setPostNotificationGranted] = useState<boolean | undefined>();

  // 待确认提醒开关(仅控制系统通知;数据落库与 JS 事件不受影响,默认开启)
  const [pendingReminderEnabled, setPendingReminderEnabled] = useState(true);
  // 预算超支提醒开关(仅控制系统通知;进度计算与 UI 变色不受影响,默认开启)
  const [budgetReminderEnabled, setBudgetReminderEnabled] = useState(true);

  const healthQuery = useQuery({
    queryKey: HEALTH_QUERY_KEY,
    queryFn: () => nativeRuntime.getRuntimeHealth(),
  });

  const checkPostNotification = useCallback(async () => {
    if (Platform.OS !== 'android' || Number(Platform.Version) < 33) {
      setPostNotificationGranted(true);
      return;
    }
    const permission = (PermissionsAndroid.PERMISSIONS as Record<string, string>)
      .POST_NOTIFICATIONS;
    if (!permission) {
      setPostNotificationGranted(true);
      return;
    }
    try {
      const granted = await PermissionsAndroid.check(
        permission as unknown as Parameters<typeof PermissionsAndroid.check>[0],
      );
      setPostNotificationGranted(granted);
    } catch {
      setPostNotificationGranted(undefined);
    }
  }, []);

  const refresh = useCallback(async () => {
    await Promise.all([healthQuery.refetch(), checkPostNotification()]);
  }, [healthQuery, checkPostNotification]);

  // 从系统设置返回时自动刷新权限状态
  useFocusEffect(
    useCallback(() => {
      void checkPostNotification();
      const subscription = AppState.addEventListener(
        'change',
        (nextState: AppStateStatus) => {
          if (nextState === 'active') {
            void queryClient.invalidateQueries({ queryKey: HEALTH_QUERY_KEY });
            void checkPostNotification();
          }
        },
      );
      return () => {
        subscription.remove();
      };
    }, [queryClient, checkPostNotification]),
  );

  // 加载提醒开关初始状态
  useEffect(() => {
    nativePendingReminder
      .isReminderEnabled()
      .then(setPendingReminderEnabled)
      .catch(() => undefined);
    nativeBudgetReminder
      .isReminderEnabled()
      .then(setBudgetReminderEnabled)
      .catch(() => undefined);
  }, []);

  const handleTogglePendingReminder = async (enabled: boolean) => {
    setPendingReminderEnabled(enabled);
    try {
      if (enabled) {
        await nativePendingReminder.requestNotificationPermissionIfNeeded();
      }
      await nativePendingReminder.setReminderEnabled(enabled);
    } catch (error) {
      console.error('切换待确认提醒失败:', error);
    }
  };

  const handleToggleBudgetReminder = async (enabled: boolean) => {
    setBudgetReminderEnabled(enabled);
    try {
      await nativeBudgetReminder.setReminderEnabled(enabled);
    } catch (error) {
      console.error('切换预算提醒失败:', error);
    }
  };

  const runOpen = useCallback(
    async (id: string, action: () => Promise<unknown> | void) => {
      setOpeningId(id);
      try {
        await action();
      } catch (error) {
        const message = error instanceof Error ? error.message : '无法打开系统设置';
        alert('打开设置失败', message);
      } finally {
        setOpeningId(null);
      }
    },
    [alert],
  );

  const health = healthQuery.data;

  const permissionRows: PermissionRow[] = [
    {
      id: 'accessibility',
      icon: ShieldCheck,
      label: '无障碍权限',
      enabled: health?.accessibilityEnabled,
      onOpen: () => nativeRecognition.openAccessibilitySettings(),
      guideRoute: 'AccessibilityPermission',
    },
    {
      id: 'overlay',
      icon: Layers3,
      label: '悬浮窗权限',
      enabled: health?.overlayPermissionGranted,
      onOpen: () => paymentNotificationService.requestOverlayPermission(),
    },
    {
      id: 'notification-listener',
      icon: BellRing,
      label: '通知监听权限',
      enabled: health?.notificationEnabled,
      onOpen: () => nativeRuntime.openNotificationListenerSettings(),
      guideRoute: 'NotificationPermission',
    },
    {
      id: 'post-notification',
      icon: Bell,
      label: '通知提醒',
      enabled: postNotificationGranted,
      onOpen: async () => {
        const granted = await nativePendingReminder.requestNotificationPermissionIfNeeded();
        setPostNotificationGranted(granted);
        if (!granted) {
          alert('未授权', '系统通知权限未授予，待确认/预算提醒将无法送达，可稍后在系统设置中开启。');
        }
      },
    },
    {
      id: 'battery',
      icon: Battery,
      label: '电池优化白名单',
      enabled: health?.batteryOptimizationIgnored,
      onOpen: () => Linking.openSettings(),
    },
  ];

  return (
    <SafeAreaView style={styles.container} edges={['top', 'bottom']}>
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} style={styles.iconButton}>
          <ChevronLeft size={24} color={styles._colors.textPrimary} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>权限与提醒</Text>
        <ShieldCheck size={21} color={styles._colors.primary} />
      </View>

      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.content}
        refreshControl={
          <RefreshControl {...refreshControlProps} refreshing={healthQuery.isFetching} onRefresh={refresh} />
        }
      >
        <View style={styles.sectionCard}>
          <Text style={styles.sectionTitle}>🔑 权限</Text>
          {permissionRows.map((row) => {
            const IconComponent = row.icon;
            return (
              <View key={row.id} style={styles.permRow}>
                <View style={styles.iconBlock}>
                  <IconComponent size={20} color={styles._isSoft ? styles._colors.primary : styles._colors.stroke} />
                </View>
                <View style={styles.permTexts}>
                  <View style={styles.permTitleRow}>
                    <Text style={styles.permLabel}>{row.label}</Text>
                    <StatusBadge enabled={row.enabled} colors={styles._colors} isSoft={styles._isSoft} isDark={styles._isDark} />
                  </View>
                  <View style={styles.permActions}>
                    {row.enabled !== true && (
                      <TouchableOpacity
                        style={styles.openButton}
                        onPress={() => void runOpen(row.id, row.onOpen)}
                        disabled={openingId === row.id}
                      >
                        <Text style={styles.openButtonText}>
                          {openingId === row.id ? '打开中…' : '去开启'}
                        </Text>
                      </TouchableOpacity>
                    )}
                    {row.guideRoute ? (
                      <TouchableOpacity
                        style={styles.guideLink}
                        onPress={() => navigation.navigate(row.guideRoute as never)}
                      >
                        <Text style={styles.guideLinkText}>开启方法</Text>
                        <ChevronRight size={14} color={styles._colors.primary} />
                      </TouchableOpacity>
                    ) : null}
                  </View>
                </View>
              </View>
            );
          })}
        </View>

        <View style={styles.sectionCard}>
          <Text style={styles.sectionTitle}>🔔 系统提醒</Text>
          <View style={styles.switchRow}>
            <Text style={styles.switchLabel}>有新待确认交易时通知</Text>
            <Switch
              value={pendingReminderEnabled}
              onValueChange={handleTogglePendingReminder}
              trackColor={{ false: styles._colors.divider, true: styles._colors.primary }}
              thumbColor="#FFFFFF"
            />
          </View>
          <View style={styles.switchRow}>
            <Text style={styles.switchLabel}>预算达到预警阈值时通知</Text>
            <Switch
              value={budgetReminderEnabled}
              onValueChange={handleToggleBudgetReminder}
              trackColor={{ false: styles._colors.divider, true: styles._colors.primary }}
              thumbColor="#FFFFFF"
            />
          </View>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape, isDark: boolean) => ({
  ...StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: colors.background,
    },
    header: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingHorizontal: spacing.md,
      paddingTop: spacing.xl,
      paddingBottom: spacing.md,
      backgroundColor: shape.isSoft || shape.isHogwarts ? colors.background : colors.surface,
      borderBottomWidth: shape.isSoft ? 0 : shape.isHogwarts ? shape.borderWidth.card : borderWidth.medium,
      borderBottomColor: shape.isHogwarts ? colors.divider : colors.stroke,
    },
    iconButton: {
      width: 40,
      height: 40,
      alignItems: 'center',
      justifyContent: 'center',
    },
    headerTitle: {
      fontSize: shape.isSoft || shape.isHogwarts ? 18 : 20,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
    },
    scrollView: { flex: 1 },
    content: { padding: spacing.lg, gap: spacing.md },
    sectionCard: {
      padding: spacing.lg,
      backgroundColor: colors.surface,
      borderWidth: shape.isSoft || shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.cardBorder : colors.stroke,
      borderRadius: shape.radius.card,
      ...(shape.isSoft || shape.isHogwarts ? shape.elevation.card : {}),
    },
    sectionTitle: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
      marginBottom: spacing.sm,
    },
    permRow: {
      flexDirection: 'row',
      alignItems: 'flex-start',
      gap: spacing.md,
      paddingVertical: spacing.md,
      borderTopWidth: shape.isSoft || shape.isHogwarts ? StyleSheet.hairlineWidth : borderWidth.thin,
      borderTopColor: shape.isSoft || shape.isHogwarts ? colors.divider : colors.stroke,
    },
    iconBlock: {
      width: 40,
      height: 40,
      borderRadius: shape.isSoft || shape.isHogwarts ? 12 : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      backgroundColor: shape.isSoft || shape.isHogwarts ? colors.primaryLight : colors.accent,
      alignItems: 'center',
      justifyContent: 'center',
    },
    permTexts: { flex: 1 },
    permTitleRow: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      gap: spacing.sm,
    },
    permLabel: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    permActions: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.md,
      marginTop: spacing.sm,
    },
    openButton: {
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.xs + 2,
      borderRadius: shape.isSoft || shape.isHogwarts ? shape.radius.pill : borderRadius.button,
      borderWidth: shape.isSoft ? 0 : shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isHogwarts ? colors.accent : colors.stroke,
      backgroundColor: colors.primary,
      ...(shape.isSoft || shape.isHogwarts ? shape.elevation.none : shadow.small),
    },
    openButtonText: {
      fontSize: 13,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: shape.isSoft || shape.isHogwarts ? colors.onPrimary : '#FFFFFF',
    },
    guideLink: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 2,
    },
    guideLinkText: {
      fontSize: 13,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.primary,
    },
    switchRow: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      paddingVertical: spacing.sm,
    },
    switchLabel: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
      flex: 1,
      marginRight: spacing.md,
    },
  }),
  _colors: colors,
  _isSoft: shape.isSoft,
  _isDark: isDark,
});

/**
 * 通知权限引导页 - Neo-Brutalism 风格
 * 引导用户开启支付通知监听服务，提供状态检查和跳转系统设置入口
 * 权限状态从 Runtime.getRuntimeHealth().notificationEnabled 读取；
 * 跳转使用 Runtime 的通知监听设置方法。
 */
import React, { useCallback, useState } from 'react';
import {
  AppState,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
  type AppStateStatus,
} from 'react-native';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AlertCircle,
  BellRing,
  CheckCircle2,
  ChevronLeft,
  ExternalLink,
  RefreshCw,
  ShieldCheck,
} from 'lucide-react-native';
import { nativeRuntime } from '../../native';
import { QUERY_KEYS } from '../../lib/queryClient';
import { useAlert } from '../../providers';
import { borderRadius, borderWidth, spacing } from '../../theme/spacing';
import { ThemeColors } from '../../theme/colors';
import { useStyles } from '../../hooks';
import { SafeAreaView } from 'react-native-safe-area-context';

const NOTIFICATION_QUERY_KEY = QUERY_KEYS.recognition.notification;

/** 通知权限引导页组件，对应阶段四任务T7前端桥接。 */
export default function NotificationPermissionScreen() {
  const navigation = useNavigation();
  const queryClient = useQueryClient();
  const styles = useStyles(createStyles);
  const { alert } = useAlert();
  const [opening, setOpening] = useState(false);

  // 通知监听权限状态来自 Runtime.getRuntimeHealth()，不新增独立查询方法
  const query = useQuery({
    queryKey: NOTIFICATION_QUERY_KEY,
    queryFn: async () => {
      const health = await nativeRuntime.getRuntimeHealth();
      return health.notificationEnabled === true;
    },
  });

  const refresh = useCallback(async () => {
    await query.refetch();
  }, [query]);

  // 焦点期间监听 AppState，从系统设置返回时自动刷新权限状态。
  useFocusEffect(
    useCallback(() => {
      const subscription = AppState.addEventListener(
        'change',
        (nextState: AppStateStatus) => {
          if (nextState === 'active') {
            void queryClient.invalidateQueries({
              queryKey: NOTIFICATION_QUERY_KEY,
            });
          }
        },
      );
      return () => {
        subscription.remove();
      };
    }, [queryClient]),
  );

  // 审查修复：跳转系统"通知访问"专用设置页，此前复用无障碍设置跳转会打开错误页面
  const handleOpenSettings = useCallback(async () => {
    setOpening(true);
    try {
      await nativeRuntime.openNotificationListenerSettings();
    } catch (error) {
      const message =
        error instanceof Error ? error.message : '无法打开系统设置';
      alert('打开设置失败', message);
    } finally {
      setOpening(false);
    }
  }, [alert]);

  const enabled = query.data === true;
  const isLoading = query.isFetching && query.data === undefined;

  return (
    <SafeAreaView style={styles.container} edges={['top', 'bottom']}>
      <View style={styles.header}>
        <TouchableOpacity
          onPress={() => navigation.goBack()}
          style={styles.iconButton}
        >
          <ChevronLeft size={24} color={styles._colors.textPrimary} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>通知权限</Text>
        <BellRing size={21} color={styles._colors.primary} />
      </View>
      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.content}
        refreshControl={
          <RefreshControl refreshing={query.isFetching} onRefresh={refresh} />
        }
      >
        <View style={styles.statusCard}>
          <View style={styles.statusIconRow}>
            {query.isError ? (
              <AlertCircle size={32} color={styles._colors.error} />
            ) : enabled ? (
              <CheckCircle2 size={32} color={styles._colors.success} />
            ) : (
              <AlertCircle size={32} color={styles._colors.warning} />
            )}
            <View style={styles.statusMain}>
              <Text style={styles.statusTitle}>
                {query.isError
                  ? '无法读取通知权限状态'
                  : isLoading
                    ? '正在读取通知权限状态'
                    : enabled
                      ? '支付通知监听已开启'
                      : '支付通知监听未开启'}
              </Text>
              <Text style={styles.statusMeta}>
                {query.isError
                  ? '读取失败，请刷新状态后重试'
                  : isLoading
                  ? '正在读取状态…'
                  : enabled
                    ? '已授权通知访问权限，当前用于识别退款/手续费类通知（不参与支付自动记账）'
                    : '未开启不影响支付自动记账（依赖无障碍读取支付结果页）；开启后可识别退款/手续费类通知'}
              </Text>
            </View>
          </View>
        </View>

        <View style={styles.sectionCard}>
          <Text style={styles.sectionTitle}>这个开关管什么？</Text>
          <Text style={styles.sectionText}>
            精简版中，支付成功通知不再用于支付自动记账——自动记账依赖
            无障碍读取支付结果页，支付后由确认弹窗/待确认中心处理。
            开启通知监听后，应用只用通知识别退款、手续费等辅助信息。
            不开启也不影响正常记账。
          </Text>
        </View>

        <View style={styles.sectionCard}>
          <Text style={styles.sectionTitle}>如何开启？</Text>
          <View style={styles.stepRow}>
            <View style={styles.stepBadge}>
              <Text style={styles.stepBadgeText}>1</Text>
            </View>
            <Text style={styles.stepText}>
              点击下方按钮，进入系统设置的应用服务列表
            </Text>
          </View>
          <View style={styles.stepRow}>
            <View style={styles.stepBadge}>
              <Text style={styles.stepBadgeText}>2</Text>
            </View>
            <Text style={styles.stepText}>
              找到猫猫招财的"支付通知监听"，打开开关
            </Text>
          </View>
          <View style={styles.stepRow}>
            <View style={styles.stepBadge}>
              <Text style={styles.stepBadgeText}>3</Text>
            </View>
            <Text style={styles.stepText}>
              返回应用下拉刷新，确认状态已变为"已开启"
            </Text>
          </View>
        </View>

        <View style={styles.sectionCard}>
          <View style={styles.privacyRow}>
            <ShieldCheck size={20} color={styles._colors.primary} />
            <Text style={styles.privacyText}>
              本地优先：通知只在设备上解析为金额、商户、订单号等结构化证据，
              不会保存通知原文，也不会上传到云端。
            </Text>
          </View>
        </View>

        <View style={styles.actionRow}>
          <TouchableOpacity
            style={[
              styles.primaryButton,
              (opening || isLoading) && styles.primaryButtonDisabled,
            ]}
            onPress={handleOpenSettings}
            disabled={opening || isLoading}
            activeOpacity={0.85}
          >
            <ExternalLink size={18} color="#FFFFFF" />
            <Text style={styles.primaryButtonText}>
              {opening ? '正在打开…' : enabled ? '重新打开设置' : '去系统设置开启'}
            </Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={styles.secondaryButton}
            onPress={refresh}
            disabled={query.isFetching}
            activeOpacity={0.85}
          >
            <RefreshCw size={18} color={styles._colors.textPrimary} />
            <Text style={styles.secondaryButtonText}>刷新状态</Text>
          </TouchableOpacity>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const createStyles = (colors: ThemeColors) => ({
  ...StyleSheet.create({
    container: { flex: 1, backgroundColor: colors.background },
    header: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingHorizontal: spacing.md,
      paddingTop: spacing.xl,
      paddingBottom: spacing.md,
      backgroundColor: colors.surface,
      borderBottomWidth: borderWidth.medium,
      borderBottomColor: colors.stroke,
    },
    iconButton: {
      width: 40,
      height: 40,
      alignItems: 'center',
      justifyContent: 'center',
    },
    headerTitle: {
      fontSize: 20,
      fontWeight: '800',
      color: colors.textPrimary,
    },
    scrollView: { flex: 1 },
    content: { padding: spacing.lg, gap: spacing.md },
    statusCard: {
      flexDirection: 'row',
      alignItems: 'center',
      padding: spacing.lg,
      backgroundColor: colors.surface,
      borderWidth: borderWidth.medium,
      borderColor: colors.stroke,
      borderRadius: borderRadius.card,
    },
    statusIconRow: {
      flexDirection: 'row',
      alignItems: 'flex-start',
      gap: spacing.md,
      flex: 1,
    },
    statusMain: { flex: 1 },
    statusTitle: {
      fontSize: 16,
      fontWeight: '800',
      color: colors.textPrimary,
      marginBottom: 4,
    },
    statusMeta: {
      fontSize: 13,
      fontWeight: '500',
      color: colors.textSecondary,
      lineHeight: 20,
    },
    sectionCard: {
      padding: spacing.lg,
      backgroundColor: colors.surface,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      borderRadius: borderRadius.card,
    },
    sectionTitle: {
      fontSize: 15,
      fontWeight: '800',
      color: colors.textPrimary,
      marginBottom: spacing.sm,
    },
    sectionText: {
      fontSize: 13,
      fontWeight: '500',
      color: colors.textSecondary,
      lineHeight: 20,
    },
    stepRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
      paddingVertical: spacing.xs,
    },
    stepBadge: {
      width: 24,
      height: 24,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: colors.primaryLight,
      alignItems: 'center',
      justifyContent: 'center',
    },
    stepBadgeText: {
      fontSize: 13,
      fontWeight: '800',
      color: colors.textPrimary,
    },
    stepText: {
      flex: 1,
      fontSize: 13,
      fontWeight: '500',
      color: colors.textPrimary,
      lineHeight: 20,
    },
    privacyRow: {
      flexDirection: 'row',
      alignItems: 'flex-start',
      gap: spacing.sm,
    },
    privacyText: {
      flex: 1,
      fontSize: 12,
      fontWeight: '600',
      color: colors.textSecondary,
      lineHeight: 18,
    },
    actionRow: {
      gap: spacing.sm,
      paddingTop: spacing.sm,
    },
    primaryButton: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'center',
      gap: spacing.sm,
      paddingVertical: spacing.md,
      borderRadius: borderRadius.medium,
      borderWidth: borderWidth.medium,
      borderColor: colors.stroke,
      backgroundColor: colors.primary,
    },
    primaryButtonDisabled: {
      opacity: 0.6,
    },
    primaryButtonText: {
      fontSize: 15,
      fontWeight: '800',
      color: '#FFFFFF',
    },
    secondaryButton: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'center',
      gap: spacing.sm,
      paddingVertical: spacing.md,
      borderRadius: borderRadius.medium,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: colors.surface,
    },
    secondaryButtonText: {
      fontSize: 14,
      fontWeight: '700',
      color: colors.textPrimary,
    },
  }),
  _colors: colors,
});

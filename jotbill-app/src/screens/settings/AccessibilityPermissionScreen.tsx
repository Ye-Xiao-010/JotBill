/**
 * 无障碍权限引导页 - Neo-Brutalism 风格
 * 引导用户开启结算页观察服务，提供状态检查和跳转系统设置入口
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
  Accessibility as AccessibilityIcon,
  AlertCircle,
  CheckCircle2,
  ChevronLeft,
  ExternalLink,
  RefreshCw,
  ShieldCheck,
} from 'lucide-react-native';
import { nativeRecognition } from '../../native';
import { QUERY_KEYS } from '../../lib/queryClient';
import { useAlert } from '../../providers';
import { borderRadius, borderWidth, spacing } from '../../theme/spacing';
import { ThemeColors } from '../../theme/colors';
import { useStyles } from '../../hooks';
import { SafeAreaView } from 'react-native-safe-area-context';

const ACCESSIBILITY_QUERY_KEY = QUERY_KEYS.recognition.accessibility;

/** 无障碍权限引导页组件，对应阶段三任务9前端桥接。 */
export default function AccessibilityPermissionScreen() {
  const navigation = useNavigation();
  const queryClient = useQueryClient();
  const styles = useStyles(createStyles);
  const { alert } = useAlert();
  const [opening, setOpening] = useState(false);

  const query = useQuery({
    queryKey: ACCESSIBILITY_QUERY_KEY,
    queryFn: () => nativeRecognition.isAccessibilityEnabled(),
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
            void queryClient.invalidateQueries({ queryKey: ACCESSIBILITY_QUERY_KEY });
          }
        },
      );
      return () => {
        subscription.remove();
      };
    }, [queryClient]),
  );

  const handleOpenSettings = useCallback(async () => {
    setOpening(true);
    try {
      await nativeRecognition.openAccessibilitySettings();
    } catch (error) {
      const message = error instanceof Error ? error.message : '无法打开系统设置';
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
        <Text style={styles.headerTitle}>无障碍权限</Text>
        <AccessibilityIcon size={21} color={styles._colors.primary} />
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
            {enabled ? (
              <CheckCircle2 size={32} color={styles._colors.success} />
            ) : (
              <AlertCircle size={32} color={styles._colors.warning} />
            )}
            <View style={styles.statusMain}>
              <Text style={styles.statusTitle}>
                {enabled ? '结算页观察服务已开启' : '结算页观察服务未开启'}
              </Text>
              <Text style={styles.statusMeta}>
                {isLoading
                  ? '正在读取状态…'
                  : enabled
                    ? '已授权无障碍权限，可自动检测购物结算页'
                    : '请前往系统设置开启服务，开启后才能自动识别结算页'}
              </Text>
            </View>
          </View>
        </View>

        <View style={styles.sectionCard}>
          <Text style={styles.sectionTitle}>为什么要开启？</Text>
          <Text style={styles.sectionText}>
            结算页观察服务会读取购物应用的窗口内容，仅在判定为结算页时才保存内存截图，
            用于 AI 识别平台、商户、金额和订单号。服务不会点击、输入或处理支付，
            也不会把页面原文或图片长期留在设备上。
          </Text>
        </View>

        <View style={styles.sectionCard}>
          <Text style={styles.sectionTitle}>如何开启？</Text>
          <View style={styles.stepRow}>
            <View style={styles.stepBadge}>
              <Text style={styles.stepBadgeText}>1</Text>
            </View>
            <Text style={styles.stepText}>
              点击下方按钮，进入系统"已安装的服务"列表
            </Text>
          </View>
          <View style={styles.stepRow}>
            <View style={styles.stepBadge}>
              <Text style={styles.stepBadgeText}>2</Text>
            </View>
            <Text style={styles.stepText}>
              找到 JotBill 的"结算页观察服务"，打开开关
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
              本地优先：识别链路产生的会话、候选、证据全部保存在设备本地，不会上传到云端。
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

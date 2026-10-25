/**
 * 自动记账设置页 - Neo-Brutalism 风格
 *
 * 规则式自动记账（mipay 逆向移植）的开关中心：
 * - 总开关：一键暂停全部自动记账（合规红线的显式退出口）；
 * - 渠道开关：支付宝 / 微信支付 / 云闪付 / 银行短信（M6 预留）；
 * - 内置规则规模展示 + 工作原理 + 隐私说明。
 */
import React from 'react';
import {
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AlertCircle,
  ChevronLeft,
  Info,
  ShieldCheck,
  Smartphone,
  Zap,
} from 'lucide-react-native';
import { nativeAutoBookkeeping } from '../../native';
import { QUERY_KEYS } from '../../lib/queryClient';
import { borderRadius, borderWidth, spacing } from '../../theme/spacing';
import { ThemeColors } from '../../theme/colors';
import { useStyles } from '../../hooks';
import { SafeAreaView } from 'react-native-safe-area-context';

const SETTINGS_QUERY_KEY = QUERY_KEYS.bookkeeping.settings;

interface ChannelRow {
  key:
    | 'alipay'
    | 'wechat'
    | 'unionpay'
    | 'jd'
    | 'meituan'
    | 'pdd'
    | 'douyin'
    | 'mms';
  label: string;
  description: string;
}

const CHANNELS: ChannelRow[] = [
  { key: 'alipay', label: '支付宝', description: '收银台 / 支付结果页 / 账单详情 / 通知' },
  { key: 'wechat', label: '微信支付', description: '扫码 / 转账 / 红包 / 账单详情' },
  { key: 'unionpay', label: '云闪付', description: '支付结果页 / 银联收银台' },
  { key: 'jd', label: '京东', description: '支付结果页 / 支付结果弹框' },
  { key: 'meituan', label: '美团', description: '支付结果页' },
  { key: 'pdd', label: '拼多多', description: '多多支付收银台（确认后入账）' },
  { key: 'douyin', label: '抖音', description: '商城 / 团购下单页（确认后入账）' },
  { key: 'mms', label: '银行短信', description: '扣款 / 入账短信（经系统通知，无需短信权限）' },
];

/** 自动记账设置页组件。 */
export default function AutoBookkeepingScreen() {
  const navigation = useNavigation();
  const queryClient = useQueryClient();
  const styles = useStyles(createStyles);

  const settingsQuery = useQuery({
    queryKey: SETTINGS_QUERY_KEY,
    queryFn: nativeAutoBookkeeping.getSettings,
  });
  const statsQuery = useQuery({
    queryKey: QUERY_KEYS.bookkeeping.stats,
    queryFn: nativeAutoBookkeeping.getRuleStats,
  });
  const serviceQuery = useQuery({
    queryKey: QUERY_KEYS.bookkeeping.service,
    queryFn: nativeAutoBookkeeping.getServiceRunning,
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: SETTINGS_QUERY_KEY });
  };

  const masterMutation = useMutation({
    mutationFn: nativeAutoBookkeeping.setMasterEnabled,
    onSuccess: invalidate,
  });
  const channelMutation = useMutation({
    mutationFn: ({
      channel,
      enabled,
    }: {
      channel: ChannelRow['key'];
      enabled: boolean;
    }) => nativeAutoBookkeeping.setChannelEnabled(channel, enabled),
    onSuccess: invalidate,
  });

  const settings = settingsQuery.data;
  const masterEnabled = settings?.masterEnabled ?? false;
  const serviceRunning = serviceQuery.data ?? false;
  const broken = masterEnabled && !serviceRunning;

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <View style={styles.header}>
        <TouchableOpacity
          style={styles.backButton}
          onPress={() => navigation.goBack()}
        >
          <ChevronLeft size={24} color={styles._colors.stroke} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>自动记账</Text>
        <View style={styles.backButton} />
      </View>

      <ScrollView
        style={styles.scrollView}
        showsVerticalScrollIndicator={false}
        contentContainerStyle={styles.content}
      >
        {broken ? (
          <View style={styles.warningCard}>
            <AlertCircle size={20} color={styles._colors.error} />
            <View style={styles.masterText}>
              <Text style={styles.warningTitle}>自动记账已失效</Text>
              <Text style={styles.warningText}>
                无障碍服务未运行（系统更新后可能被静默关闭）。不处理则不会自动记任何账。
              </Text>
              <TouchableOpacity
                style={styles.warningAction}
                onPress={() => navigation.navigate('AccessibilityPermission')}
              >
                <Text style={styles.warningActionText}>去开启无障碍服务</Text>
              </TouchableOpacity>
            </View>
          </View>
        ) : null}
        <View style={styles.sectionCard}>
          <View style={styles.masterRow}>
            <View style={styles.iconContainer}>
              <Zap size={20} color={styles._colors.stroke} />
            </View>
            <View style={styles.masterText}>
              <Text style={styles.sectionTitle}>付完钱自动记账</Text>
              <Text style={styles.sectionText}>
                在支付应用里完成支付后，自动识别金额与商户并入账，
                通知栏可一键撤销；未识别的场景仍走待确认兜底。
              </Text>
            </View>
            <Switch
              value={masterEnabled}
              onValueChange={(enabled) => masterMutation.mutate(enabled)}
              trackColor={{ false: styles._colors.divider, true: styles._colors.primary }}
              thumbColor="#FFFFFF"
            />
          </View>
        </View>

        <View style={styles.sectionCard}>
          <Text style={styles.sectionTitle}>支付渠道</Text>
          {CHANNELS.map((channel) => (
            <View key={channel.key} style={styles.channelRow}>
              <View style={styles.masterText}>
                <Text style={styles.channelLabel}>{channel.label}</Text>
                <Text style={styles.sectionText}>{channel.description}</Text>
              </View>
              <Switch
                value={settings?.channels[channel.key] ?? false}
                onValueChange={(enabled) =>
                  channelMutation.mutate({ channel: channel.key, enabled })
                }
                trackColor={{ false: styles._colors.divider, true: styles._colors.primary }}
                thumbColor="#FFFFFF"
              />
            </View>
          ))}
        </View>

        <View style={styles.sectionCard}>
          <View style={styles.privacyRow}>
            <Info size={16} color={styles._colors.textSecondary} />
            <Text style={styles.privacyText}>
              内置 {statsQuery.data?.rules ?? '—'} 条页面规则
              （{statsQuery.data?.keys ?? '—'} 类页面）。只读取白名单应用的
              支付结果页文字，不截图、不读短信内容；识别在本地完成。
            </Text>
          </View>
          <View style={styles.privacyRow}>
            <ShieldCheck size={16} color={styles._colors.textSecondary} />
            <Text style={styles.privacyText}>
              关闭总开关立即停止一切自动识别；已自动入账的记录可在账单列表删除，
              或在弹出通知上直接撤销。
            </Text>
          </View>
          <View style={styles.privacyRow}>
            <Smartphone size={16} color={styles._colors.textSecondary} />
            <Text style={styles.privacyText}>
              需要系统「无障碍服务」保持开启（见设置 → 无障碍权限）。
            </Text>
          </View>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const createStyles = (colors: ThemeColors) => ({
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
      paddingVertical: spacing.sm,
    },
    backButton: {
      width: 40,
      height: 40,
      alignItems: 'center',
      justifyContent: 'center',
    },
    headerTitle: {
      fontSize: 17,
      fontWeight: '900',
      color: colors.textPrimary,
    },
    scrollView: {
      flex: 1,
    },
    content: {
      padding: spacing.md,
      gap: spacing.md,
    },
    sectionCard: {
      padding: spacing.lg,
      backgroundColor: colors.surface,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      borderRadius: borderRadius.card,
      gap: spacing.sm,
    },
    masterRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
    },
    iconContainer: {
      width: 40,
      height: 40,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: colors.primaryLight,
      alignItems: 'center',
      justifyContent: 'center',
    },
    masterText: {
      flex: 1,
      gap: 2,
    },
    sectionTitle: {
      fontSize: 15,
      fontWeight: '800',
      color: colors.textPrimary,
    },
    sectionText: {
      fontSize: 12,
      fontWeight: '500',
      color: colors.textSecondary,
      lineHeight: 18,
    },
    channelRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
      paddingVertical: spacing.xs,
    },
    channelLabel: {
      fontSize: 14,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    warningCard: {
      flexDirection: 'row',
      alignItems: 'flex-start',
      gap: spacing.sm,
      padding: spacing.lg,
      backgroundColor: colors.surface,
      borderWidth: borderWidth.thick,
      borderColor: colors.error,
      borderRadius: borderRadius.card,
    },
    warningTitle: {
      fontSize: 14,
      fontWeight: '800',
      color: colors.error,
    },
    warningText: {
      fontSize: 12,
      fontWeight: '600',
      color: colors.textSecondary,
      lineHeight: 18,
      marginTop: 2,
    },
    warningAction: {
      marginTop: spacing.sm,
      alignSelf: 'flex-start',
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.xs,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: colors.primaryLight,
    },
    warningActionText: {
      fontSize: 12,
      fontWeight: '800',
      color: colors.textPrimary,
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
  }),
  _colors: colors,
});

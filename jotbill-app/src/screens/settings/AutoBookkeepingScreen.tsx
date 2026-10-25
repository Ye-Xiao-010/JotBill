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
    mutationFn: ({ channel, enabled }: { channel: string; enabled: boolean }) =>
      nativeAutoBookkeeping.setChannelEnabled(channel, enabled),
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
          {(settings?.channelMeta ?? []).map((channel) => (
            <View key={channel.key} style={styles.channelRow}>
              <View style={styles.masterText}>
                <Text style={styles.channelLabel}>{channel.label}</Text>
                <Text style={styles.sectionText}>{channel.description}</Text>
              </View>
              <Switch
                value={settings?.channels[channel.key] ?? channel.defaultEnabled}
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
            {statsQuery.data && statsQuery.data.rules === 0 ? (
              <Text style={[styles.privacyText, { color: styles._colors.error }]}>
                内置规则未加载{statsQuery.data.error ? `（${statsQuery.data.error}）` : ''}，
                自动记账当前不会工作，请更新应用或反馈日志。
              </Text>
            ) : (
              <Text style={styles.privacyText}>
                内置 {statsQuery.data?.rules ?? '—'} 条页面规则
                （{statsQuery.data?.keys ?? '—'} 类页面）。只读取白名单应用
                的支付相关页面文字（银行短信通道只读系统通知里的银行短信），
                不上传原文，识别在本地完成。
              </Text>
            )}
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

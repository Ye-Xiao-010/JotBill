/**
 * 自动记账设置页 - Neo-Brutalism 风格
 *
 * 规则式自动记账（mipay 逆向移植）的开关中心：
 * - 总开关：一键暂停全部自动记账（合规红线的显式退出口）；
 * - 渠道开关：支付宝 / 微信支付 / 云闪付 / 银行短信（M6 预留）；
 * - 内置规则规模展示 + 工作原理 + 隐私说明。
 */
import React, { useCallback, useEffect, useRef } from 'react';
import {
  Alert,
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
import { ThemeShape } from '../../theme/shape';
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

  const invalidate = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: SETTINGS_QUERY_KEY });
  }, [queryClient]);

  const keepAlivePromptShown = useRef(false);

  const requestKeepAlive = useCallback(() => {
    keepAlivePromptShown.current = true;
    Alert.alert(
      '开启后台守护？',
      '开启后 JotBill 会显示一条低重要性常驻通知，降低最近任务被清理后漏记的概率。\n\n它不会轮询屏幕，也不会定时唤醒；自动记账仍然只在支付事件发生时工作。',
      [
        {
          text: '暂不',
          style: 'cancel',
          onPress: () => {
            void (async () => {
              await nativeAutoBookkeeping.markKeepAlivePrompted();
              invalidate();
            })();
          },
        },
        {
          text: '开启',
          onPress: () => {
            void (async () => {
              await nativeAutoBookkeeping.markKeepAlivePrompted();
              await nativeAutoBookkeeping.setKeepAliveEnabled(true);
              invalidate();
            })();
          },
        },
      ],
      { cancelable: false },
    );
  }, [invalidate]);

  useEffect(() => {
    if (keepAlivePromptShown.current) return;
    const current = settingsQuery.data;
    if (current?.masterEnabled && !current.keepAlivePrompted) {
      requestKeepAlive();
    }
  }, [requestKeepAlive, settingsQuery.data]);

  const masterMutation = useMutation({
    mutationFn: nativeAutoBookkeeping.setMasterEnabled,
    onSuccess: invalidate,
  });
  const silentBookkeepingMutation = useMutation({
    mutationFn: nativeAutoBookkeeping.setSilentBookkeepingEnabled,
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: SETTINGS_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: QUERY_KEYS.recognition.permissions }),
      ]);
    },
    onError: () => Alert.alert('设置失败', '无法保存静默记账设置，请重试。'),
  });
  const autoBookedNotificationMutation = useMutation({
    mutationFn: nativeAutoBookkeeping.setAutoBookedNotificationEnabled,
    onSuccess: invalidate,
    onError: () => Alert.alert('设置失败', '无法保存通知设置，请重试。'),
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
          <ChevronLeft size={24} color={styles._isSoft ? styles._colors.textPrimary : styles._colors.stroke} />
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
              <Text style={styles.warningTitle}>无障碍服务未连接</Text>
              <Text style={styles.warningText}>
                支付结果页和页面规则无法识别；已开启的通知和短信识别仍可独立工作。
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
              <Zap size={20} color={styles._isSoft ? styles._colors.primary : styles._colors.stroke} />
            </View>
            <View style={styles.masterText}>
              <Text style={styles.sectionTitle}>付完钱自动记账</Text>
              <Text style={styles.sectionText}>
                在支付应用里完成支付后，自动识别金额与商户并入账；
                未识别的场景仍走待确认兜底。
              </Text>
            </View>
            <Switch
              value={masterEnabled}
              onValueChange={(enabled) => {
                if (!enabled) {
                  masterMutation.mutate(false);
                  return;
                }
                masterMutation.mutate(true, {
                  onSuccess: () => {
                    if (!settings?.keepAlivePrompted) {
                      requestKeepAlive();
                    }
                  },
                });
              }}
              trackColor={{ false: styles._colors.divider, true: styles._colors.primary }}
              thumbColor="#FFFFFF"
            />
          </View>
        </View>

        <View style={styles.sectionCard}>
          <View style={styles.masterRow}>
            <View style={styles.masterText}>
              <Text style={styles.sectionTitle}>全局静默记账</Text>
              <Text style={styles.sectionText}>
                已启用渠道的交易照常自动入账，不弹出记账窗口。需核对的交易仍留在待确认中心，通知栏提醒不受影响。
              </Text>
            </View>
            <Switch
              accessibilityLabel="全局静默记账"
              value={settings?.silentBookkeepingEnabled ?? false}
              disabled={!masterEnabled || silentBookkeepingMutation.isPending}
              onValueChange={enabled => silentBookkeepingMutation.mutate(enabled)}
              trackColor={{ false: styles._colors.divider, true: styles._colors.primary }}
              thumbColor="#FFFFFF"
            />
          </View>
        </View>

        <View style={styles.sectionCard}>
          <View style={styles.masterRow}>
            <View style={styles.masterText}>
              <Text style={styles.sectionTitle}>自动记账通知</Text>
              <Text style={styles.sectionText}>
                自动入账成功后，在通知栏提示金额、渠道和商户，并可一键撤销。关闭后照常入账，只是不再发这条通知，也就无法从通知栏撤销。
              </Text>
            </View>
            <Switch
              accessibilityLabel="自动记账通知"
              value={settings?.autoBookedNotificationEnabled ?? true}
              disabled={!masterEnabled || autoBookedNotificationMutation.isPending}
              onValueChange={enabled => autoBookedNotificationMutation.mutate(enabled)}
              trackColor={{ false: styles._colors.divider, true: styles._colors.primary }}
              thumbColor="#FFFFFF"
            />
          </View>
        </View>

        {masterEnabled ? (
          <View style={styles.sectionCard}>
            <View style={styles.masterRow}>
              <View style={styles.masterText}>
                <Text style={styles.sectionTitle}>后台守护</Text>
                <Text style={styles.sectionText}>
                  显示一条低重要性常驻通知，降低最近任务被清理后漏记的概率。不轮询、不定时唤醒。
                </Text>
                <Text style={styles.sectionText}>
                  另有 1×1 不可见窗口随无障碍服务启用而常驻，用于避免系统冻结进程导致漏记；
                  它不受本开关控制，关闭需在系统设置中停用无障碍服务。
                </Text>
                {settings?.keepAliveEnabled && !settings.keepAliveRunning ? (
                  <Text style={[styles.sectionText, { color: styles._colors.error }]}>
                    守护未运行，请检查通知权限或电池优化设置。
                  </Text>
                ) : null}
              </View>
              <Switch
                value={settings?.keepAliveEnabled ?? false}
                onValueChange={(enabled) => {
                  void (async () => {
                    await nativeAutoBookkeeping.markKeepAlivePrompted();
                    await nativeAutoBookkeeping.setKeepAliveEnabled(enabled);
                    invalidate();
                  })();
                }}
                trackColor={{ false: styles._colors.divider, true: styles._colors.primary }}
                thumbColor="#FFFFFF"
              />
            </View>
          </View>
        ) : null}

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
              或在本页「自动记账通知」开启时，从通知栏直接撤销。
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

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
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
      fontSize: shape.isSoft ? 18 : 17,
      fontWeight: shape.isSoft ? '700' : '900',
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
      borderWidth: shape.isSoft ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isSoft ? colors.cardBorder : colors.stroke,
      borderRadius: shape.radius.card,
      gap: spacing.sm,
      ...(shape.isSoft ? shape.elevation.card : {}),
    },
    masterRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
    },
    iconContainer: {
      width: 40,
      height: 40,
      borderRadius: shape.isSoft ? 12 : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
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
      fontWeight: shape.isSoft ? '700' : '800',
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
      fontWeight: shape.isSoft ? '600' : '700',
      color: colors.textPrimary,
    },
    warningCard: {
      flexDirection: 'row',
      alignItems: 'flex-start',
      gap: spacing.sm,
      padding: spacing.lg,
      backgroundColor: shape.isSoft ? colors.expenseLight : colors.surface,
      borderWidth: shape.isSoft ? 0 : borderWidth.thick,
      borderColor: colors.error,
      borderRadius: shape.radius.card,
    },
    warningTitle: {
      fontSize: 14,
      fontWeight: shape.isSoft ? '700' : '800',
      color: shape.isSoft ? colors.expenseStrong : colors.error,
    },
    warningText: {
      fontSize: 12,
      fontWeight: shape.isSoft ? '500' : '600',
      color: colors.textSecondary,
      lineHeight: 18,
      marginTop: 2,
    },
    warningAction: {
      marginTop: spacing.sm,
      alignSelf: 'flex-start',
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.xs + 2,
      borderRadius: shape.isSoft ? shape.radius.pill : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: shape.isSoft ? colors.surface : colors.primaryLight,
    },
    warningActionText: {
      fontSize: 12,
      fontWeight: shape.isSoft ? '700' : '800',
      color: shape.isSoft ? colors.expenseStrong : colors.textPrimary,
    },
    privacyRow: {
      flexDirection: 'row',
      alignItems: 'flex-start',
      gap: spacing.sm,
    },
    privacyText: {
      flex: 1,
      fontSize: 12,
      fontWeight: shape.isSoft ? '500' : '600',
      color: colors.textSecondary,
      lineHeight: 18,
    },
  }),
  _colors: colors,
  _isSoft: shape.isSoft,
});

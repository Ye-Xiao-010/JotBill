import React, { useCallback, useState } from 'react';
import { Alert, RefreshControl, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { useQuery } from '@tanstack/react-query';
import { Activity, CheckCircle2, ChevronLeft, Database, FileText, Layers3, WalletCards } from 'lucide-react-native';
import { nativeRuntime } from '../../native';
import { borderRadius, borderWidth, spacing } from '../../theme/spacing';
import { ThemeColors } from '../../theme/colors';
import { useStyles } from '../../hooks';
import { SafeAreaView } from 'react-native-safe-area-context';
import { typography } from '../../theme';

const STATUS_QUERY_KEY = ['runtime-status'] as const;

function booleanText(value?: boolean): string {
  return value ? '已授权/已开启' : '未授权/未开启';
}

function formatTime(value?: string): string {
  if (!value) return '--';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('zh-CN', { hour12: false });
}

export default function RuntimeStatusScreen() {
  const navigation = useNavigation();
  const styles = useStyles(createStyles);
  const [exporting, setExporting] = useState(false);
  const query = useQuery({
    queryKey: STATUS_QUERY_KEY,
    queryFn: () => nativeRuntime.getRuntimeHealth(),
  });

  const onRefresh = useCallback(async () => {
    await query.refetch();
  }, [query]);

  /**
   * 导出诊断日志（打包 zip → 系统分享面板，用户可直接在微信/QQ 转发）。
   * 弹窗不出现类问题（如门禁挂起/事件断流）靠此日志归因，见 DiagLogExporter。
   */
  const onExportDiagLog = useCallback(() => {
    Alert.alert(
      '导出诊断日志',
      '将把运行日志打包并通过系统分享面板发送。日志包含金额等记账数据，不含密码（敏感页零提取）。',
      [
        { text: '取消', style: 'cancel' },
        {
          text: '导出',
          onPress: async () => {
            if (exporting) return;
            setExporting(true);
            try {
              const fileName = await nativeRuntime.exportDiagLog();
              Alert.alert('导出完成', `已生成 ${fileName}，请在分享面板选择发送方式。`);
            } catch (error) {
              // reject(code, message) 在 JS 侧 Error 上 code 与 message 是两个独立
              // 属性（RN PromiseImpl 把 WritableMap Object.assign 到 error 上），
              // message 只有中文文案 —— 判 code，不判 message 内容。
              const code = (error as { code?: string })?.code ?? '';
              const message = error instanceof Error ? error.message : String(error);
              const friendly = code === 'DIAG_EXPORT_EMPTY'
                ? '暂无诊断日志可导出，请先使用一段时间后再试。'
                : code === 'DIAG_EXPORT_NO_ACTIVITY'
                  ? '请在应用内重试（分享面板需要前台界面）。'
                  : `导出失败：${message ?? '未知错误'}`;
              Alert.alert(code === 'DIAG_EXPORT_EMPTY' ? '暂无可导出' : '导出失败', friendly);
            } finally {
              setExporting(false);
            }
          },
        },
      ],
    );
  }, [exporting]);

  const status = query.data;
  const rows = [
    { label: '数据库版本', value: status ? String(status.databaseVersion) : '--', Icon: Database },
    { label: '账单数量', value: status ? String(status.billCount) : '--', Icon: FileText },
    { label: '分类数量', value: status ? String(status.categoryCount) : '--', Icon: Layers3 },
    { label: '账户数量', value: status ? String(status.accountCount) : '--', Icon: WalletCards },
  ];

  return (
    <SafeAreaView style={styles.container} edges={['top', 'bottom']}>
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} style={styles.iconButton}>
          <ChevronLeft size={24} color={styles._colors.textPrimary} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>运行状态</Text>
        <Activity size={21} color={styles._colors.primary} />
      </View>
      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.content}
        refreshControl={<RefreshControl refreshing={query.isFetching} onRefresh={onRefresh} />}
      >
        <View style={styles.healthCard}>
          <View style={styles.healthIcon}><CheckCircle2 size={24} color={styles._colors.success} /></View>
          <View style={styles.healthMain}>
            <Text style={styles.healthTitle}>本地数据模块正常</Text>
            <Text style={styles.healthMeta}>最近检查：{formatTime(status?.checkedAt)}</Text>
          </View>
        </View>
        <View style={styles.grid}>
          {rows.map(({ label, value, Icon }) => (
            <View key={label} style={styles.statCard}>
              <Icon size={20} color={styles._colors.primary} />
              <Text style={styles.statValue}>{value}</Text>
              <Text style={styles.statLabel}>{label}</Text>
            </View>
          ))}
        </View>
        <View style={styles.detailCard}>
          <Text style={styles.detailTitle}>启动信息</Text>
          <View style={styles.detailRow}>
            <Text style={styles.detailLabel}>最近启动</Text>
            <Text style={styles.detailValue}>{formatTime(status?.lastLaunchAt)}</Text>
          </View>
          <View style={styles.detailRow}>
            <Text style={styles.detailLabel}>数据存储</Text>
            <Text style={styles.detailValue}>本机 Room / SQLCipher</Text>
          </View>
          <View style={styles.detailRow}>
            <Text style={styles.detailLabel}>无障碍服务</Text>
            <Text style={styles.detailValue}>{booleanText(status?.accessibilityEnabled)}</Text>
          </View>
          <View style={styles.detailRow}>
            <Text style={styles.detailLabel}>通知监听</Text>
            <Text style={styles.detailValue}>{booleanText(status?.notificationEnabled)}</Text>
          </View>
          <View style={styles.detailRow}>
            <Text style={styles.detailLabel}>悬浮窗权限</Text>
            <Text style={styles.detailValue}>{booleanText(status?.overlayPermissionGranted)}</Text>
          </View>
          <View style={styles.detailRow}>
            <Text style={styles.detailLabel}>电池优化白名单</Text>
            <Text style={styles.detailValue}>{booleanText(status?.batteryOptimizationIgnored)}</Text>
          </View>
          <View style={styles.detailRow}>
            <Text style={styles.detailLabel}>后台守护</Text>
            <Text style={styles.detailValue}>
              {status?.keepAliveRunning
                ? '运行中'
                : status?.keepAliveEnabled
                  ? '未运行'
                  : '未开启'}
            </Text>
          </View>
          <View style={styles.detailRow}>
            <Text style={styles.detailLabel}>孤儿导入批次</Text>
            <Text style={styles.detailValue}>{status?.orphanImportBatches ?? '--'}</Text>
          </View>
          {status?.lastMigrationRecoveredAt ? (
            <View style={styles.detailRow}>
              <Text style={styles.detailLabel}>迁移回滚时间</Text>
              <Text style={styles.detailValue}>{formatTime(status.lastMigrationRecoveredAt)}</Text>
            </View>
          ) : null}
        </View>
        <View style={styles.detailCard}>
          <Text style={styles.detailTitle}>诊断</Text>
          <View style={styles.detailRow}>
            <Text style={styles.detailLabel}>诊断日志（排障用）</Text>
            <TouchableOpacity onPress={onExportDiagLog} disabled={exporting}>
              <Text style={[styles.linkText, exporting && styles.linkTextDisabled]}>
                {exporting ? '导出中…' : '导出'}
              </Text>
            </TouchableOpacity>
          </View>
          <View style={styles.detailRow}>
            <Text style={styles.detailLabel}>电池优化列表</Text>
            <TouchableOpacity onPress={() => { void nativeRuntime.openBatteryOptimizationSettings(); }}>
              <Text style={styles.linkText}>打开</Text>
            </TouchableOpacity>
          </View>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const createStyles = (colors: ThemeColors) => ({
  ...StyleSheet.create({
    container: { flex: 1, backgroundColor: colors.background },
    header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: spacing.md, paddingTop: spacing.xl, paddingBottom: spacing.md, backgroundColor: colors.surface, borderBottomWidth: borderWidth.medium, borderBottomColor: colors.stroke },
    iconButton: { width: 40, height: 40, alignItems: 'center', justifyContent: 'center' },
    headerTitle: { fontSize: 20, fontWeight: '800', color: colors.textPrimary },
    scrollView: { flex: 1 },
    content: { padding: spacing.lg, gap: spacing.md },
    healthCard: { flexDirection: 'row', alignItems: 'center', gap: spacing.md, padding: spacing.lg, backgroundColor: colors.surface, borderWidth: borderWidth.medium, borderColor: colors.stroke, borderRadius: borderRadius.card },
    healthIcon: { width: 44, height: 44, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.primaryLight, borderRadius: borderRadius.small },
    healthMain: { flex: 1 },
    healthTitle: { fontSize: 16, fontWeight: '800', color: colors.textPrimary },
    healthMeta: { marginTop: 4, fontSize: 12, color: colors.textSecondary },
    grid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.md },
    statCard: { width: '47%', minHeight: 112, padding: spacing.md, justifyContent: 'space-between', backgroundColor: colors.surface, borderWidth: borderWidth.thin, borderColor: colors.stroke, borderRadius: borderRadius.card },
    statValue: { fontSize: 26, fontWeight: '900', color: colors.textPrimary, fontFamily: typography.fontFamily.mono },
    statLabel: { fontSize: 12, fontWeight: '700', color: colors.textSecondary },
    detailCard: { padding: spacing.lg, backgroundColor: colors.surface, borderWidth: borderWidth.thin, borderColor: colors.stroke, borderRadius: borderRadius.card },
    detailTitle: { marginBottom: spacing.md, fontSize: 16, fontWeight: '800', color: colors.textPrimary },
    detailRow: { flexDirection: 'row', justifyContent: 'space-between', gap: spacing.md, paddingVertical: spacing.sm, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: colors.divider },
    detailLabel: { fontSize: 13, color: colors.textSecondary },
    detailValue: { flexShrink: 1, fontSize: 13, fontWeight: '700', color: colors.textPrimary, textAlign: 'right' },
    linkText: { fontSize: 13, fontWeight: '800', color: colors.primary },
    linkTextDisabled: { color: colors.textSecondary },
  }),
  _colors: colors,
});

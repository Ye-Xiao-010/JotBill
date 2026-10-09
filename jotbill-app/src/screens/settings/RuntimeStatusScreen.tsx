import React, { useCallback } from 'react';
import { RefreshControl, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { Linking } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { Activity, AlertTriangle, Battery, CheckCircle2, ChevronLeft, Database, FileText, Layers3, WalletCards } from 'lucide-react-native';
import { nativeRuntime } from '../../native';
import { borderRadius, borderWidth, spacing } from '../../theme/spacing';
import { ThemeColors } from '../../theme/colors';
import { useStyles } from '../../hooks';
import { SafeAreaView } from 'react-native-safe-area-context';

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
  const query = useQuery({
    queryKey: STATUS_QUERY_KEY,
    queryFn: () => nativeRuntime.getRuntimeHealth(),
  });

  const onRefresh = useCallback(async () => {
    await query.refetch();
  }, [query]);

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
          <Text style={styles.detailTitle}>重新授权</Text>
          <View style={styles.detailRow}>
            <Text style={styles.detailLabel}>通知监听设置</Text>
            <TouchableOpacity onPress={() => { void nativeRuntime.openNotificationListenerSettings(); }}>
              <Text style={styles.linkText}>打开</Text>
            </TouchableOpacity>
          </View>
          <View style={styles.detailRow}>
            <Text style={styles.detailLabel}>悬浮窗/电池优化</Text>
            <TouchableOpacity onPress={() => { void Linking.openSettings(); }}>
              <Text style={styles.linkText}>打开系统设置</Text>
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
    statValue: { fontSize: 26, fontWeight: '900', color: colors.textPrimary, fontFamily: 'Courier' },
    statLabel: { fontSize: 12, fontWeight: '700', color: colors.textSecondary },
    detailCard: { padding: spacing.lg, backgroundColor: colors.surface, borderWidth: borderWidth.thin, borderColor: colors.stroke, borderRadius: borderRadius.card },
    detailTitle: { marginBottom: spacing.md, fontSize: 16, fontWeight: '800', color: colors.textPrimary },
    detailRow: { flexDirection: 'row', justifyContent: 'space-between', gap: spacing.md, paddingVertical: spacing.sm, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: colors.divider },
    detailLabel: { fontSize: 13, color: colors.textSecondary },
    detailValue: { flexShrink: 1, fontSize: 13, fontWeight: '700', color: colors.textPrimary, textAlign: 'right' },
    linkText: { fontSize: 13, fontWeight: '800', color: colors.primary },
  }),
  _colors: colors,
});

import React, { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Alert, BackHandler, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useNavigation, usePreventRemove } from '@react-navigation/native';
import { useQueryClient } from '@tanstack/react-query';
import { Archive, CheckCircle2, ChevronLeft, Download, FileText, FileUp, Trash2, X } from 'lucide-react-native';
import { nativeBackup } from '../../native';
import type { BackupSummary, RestorePreview } from '../../../../docs/types/Native';
import { invalidateCache } from '../../lib/queryClient';
import { borderRadius, borderWidth, spacing } from '../../theme/spacing';
import { ThemeColors } from '../../theme/colors';
import { useStyles } from '../../hooks';
import { SafeAreaView } from 'react-native-safe-area-context';

type StatusKind = 'success' | 'error' | 'info';

interface ScreenStatus {
  kind: StatusKind;
  title: string;
  message: string;
}

const isErrorWithCode = (error: unknown, code: string): boolean =>
  typeof error === 'object' &&
  error !== null &&
  'code' in error &&
  (error as { code?: unknown }).code === code;

const getErrorMessage = (error: unknown): string => {
  if (typeof error === 'object' && error !== null) {
    const value = error as { message?: unknown; code?: unknown };
    if (typeof value.message === 'string' && value.message.length > 0) {
      return typeof value.code === 'string' ? `${value.message}（${value.code}）` : value.message;
    }
  }
  return error instanceof Error ? error.message : '备份操作失败';
};

const countText = (value: number): string => value.toLocaleString('zh-CN');

export default function DataManagementScreen() {
  const navigation = useNavigation();
  const queryClient = useQueryClient();
  const styles = useStyles(createStyles);
  const [exporting, setExporting] = useState(false);
  const [creatingBackup, setCreatingBackup] = useState(false);
  const [restoring, setRestoring] = useState(false);
  const [clearing, setClearing] = useState(false);
  const [preview, setPreview] = useState<RestorePreview | null>(null);
  const [backup, setBackup] = useState<BackupSummary | null>(null);
  const [status, setStatus] = useState<ScreenStatus | null>(null);
  const exportingRef = useRef(false);
  const creatingBackupRef = useRef(false);
  const restoringRef = useRef(false);
  const clearingRef = useRef(false);

  const busy = exporting || creatingBackup || restoring || clearing;

  usePreventRemove(restoring || restoringRef.current, () => {
    // 恢复事务期间不派发离开动作，恢复结束后由 React Navigation 自动解除拦截。
  });

  useEffect(() => {
    const unsubscribe = navigation.addListener('beforeRemove', (event) => {
      if (restoringRef.current) event.preventDefault();
    });
    return unsubscribe;
  }, [navigation]);

  useEffect(() => {
    const subscription = BackHandler.addEventListener(
      'hardwareBackPress',
      () => restoringRef.current,
    );
    return () => subscription.remove();
  }, []);

  const exportCsv = async () => {
    exportingRef.current = true;
    setExporting(true);
    setStatus(null);
    try {
      const result = await nativeBackup.exportData('csv');
      Alert.alert('导出完成', 'CSV 文件已保存到你选择的位置。', [{ text: '知道了' }]);
      return result;
    } catch (error) {
      if (isErrorWithCode(error, 'EXPORT_CANCELLED')) return;
      setStatus({ kind: 'error', title: 'CSV 导出失败', message: getErrorMessage(error) });
    } finally {
      exportingRef.current = false;
      setExporting(false);
    }
  };

  const createBackup = async () => {
    creatingBackupRef.current = true;
    setCreatingBackup(true);
    setStatus(null);
    try {
      const result = await nativeBackup.createBackup({ encrypt: false });
      setBackup(result);
      setStatus({
        kind: 'success',
        title: '备份已保存',
        message: `文件已保存。包含 ${countText(result.recordSummary.totalCount)} 条可恢复非敏感记录；API Key 等凭据未写入备份。`,
      });
    } catch (error) {
      if (isErrorWithCode(error, 'BACKUP_CANCELLED')) return;
      setStatus({ kind: 'error', title: '备份失败', message: getErrorMessage(error) });
    } finally {
      creatingBackupRef.current = false;
      setCreatingBackup(false);
    }
  };

  const chooseBackup = async () => {
    if (
      busy ||
      exportingRef.current ||
      creatingBackupRef.current ||
      restoringRef.current ||
      clearingRef.current
    ) return;
    restoringRef.current = true;
    setRestoring(true);
    setStatus(null);
    try {
      const result = await nativeBackup.restoreBackup('');
      setPreview(result);
      setStatus({ kind: 'info', title: '备份已通过预览校验', message: '请确认后执行覆盖恢复。当前页面不会执行合并恢复。' });
    } catch (error) {
      if (isErrorWithCode(error, 'BACKUP_CANCELLED')) return;
      setStatus({ kind: 'error', title: '备份预览失败', message: getErrorMessage(error) });
    } finally {
      restoringRef.current = false;
      setRestoring(false);
    }
  };

  const executeOverwriteRestore = async () => {
    if (
      !preview ||
      busy ||
      exportingRef.current ||
      creatingBackupRef.current ||
      restoringRef.current ||
      clearingRef.current
    ) return;
    restoringRef.current = true;
    setRestoring(true);
    setStatus(null);
    try {
      const result = await nativeBackup.executeRestore(preview.filePath, 'overwrite');
      invalidateCache.all();
      queryClient.clear();
      setPreview(null);
      setStatus({
        kind: 'success',
        title: '覆盖恢复完成',
        message: `已恢复 ${countText(result.imported)} 条非敏感记录，跳过 ${countText(result.skipped)} 条未包含记录，替换 ${countText(result.replaced)} 条重复记录；API Key 等凭据未恢复。`,
      });
    } catch (error) {
      setStatus({ kind: 'error', title: '恢复失败', message: getErrorMessage(error) });
    } finally {
      restoringRef.current = false;
      setRestoring(false);
    }
  };

  const confirmOverwriteRestore = () => {
    if (
      !preview ||
      busy ||
      exportingRef.current ||
      creatingBackupRef.current ||
      restoringRef.current ||
      clearingRef.current
    ) return;
    Alert.alert(
      '确认覆盖恢复',
      `将替换备份范围内的账单、账户、分类等数据。重复记录按替换处理，恢复失败会回滚。\n\n备份记录：${countText(preview.recordSummary.totalCount)}`,
      [
        { text: '取消', style: 'cancel' },
        { text: '确认覆盖', style: 'destructive', onPress: () => { void executeOverwriteRestore(); } },
      ],
    );
  };

  const cancelPreview = () => {
    if (restoring || restoringRef.current) return;
    setPreview(null);
    setStatus({ kind: 'info', title: '已取消恢复', message: '备份文件未写入数据库。' });
  };

  const clearData = () => {
    if (busy || restoringRef.current) return;
    Alert.alert('清除本地数据', '这会删除本机账单、账户、分类、预算和目标，且不能撤销。请先导出 CSV。', [
      { text: '取消', style: 'cancel' },
      {
        text: '继续清除',
        style: 'destructive',
        onPress: async () => {
          if (
            exportingRef.current ||
            creatingBackupRef.current ||
            restoringRef.current ||
            clearingRef.current
          ) return;
          clearingRef.current = true;
          setClearing(true);
          try {
            await nativeBackup.clearData();
            invalidateCache.all();
            queryClient.clear();
            Alert.alert('已清除', '本地数据已删除。');
          } catch (error) {
            setStatus({ kind: 'error', title: '清除失败', message: getErrorMessage(error) });
          } finally {
            clearingRef.current = false;
            setClearing(false);
          }
        },
      },
    ]);
  };

  const handleBack = () => {
    if (
      busy ||
      exportingRef.current ||
      creatingBackupRef.current ||
      restoringRef.current ||
      clearingRef.current
    ) return;
    navigation.goBack();
  };

  return (
    <SafeAreaView style={styles.container} edges={['top', 'bottom']}>
      <View style={styles.header}>
        <TouchableOpacity onPress={handleBack} style={[styles.iconButton, busy && styles.disabledButton]} disabled={busy} accessibilityLabel="返回">
          <ChevronLeft size={24} color={styles._colors.textPrimary} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>数据管理</Text>
        <Archive size={21} color={styles._colors.primary} />
      </View>
      <ScrollView style={styles.scrollView} contentContainerStyle={styles.content}>
        <View style={styles.infoCard}>
          <Archive size={24} color={styles._colors.primary} />
          <View style={styles.infoMain}>
            <Text style={styles.infoTitle}>本地数据</Text>
            <Text style={styles.infoText}>备份文件只保存在你选择的位置，不会上传到服务器。</Text>
          </View>
        </View>

        {status ? (
          <View style={[styles.statusCard, status.kind === 'success' ? styles.successStatus : status.kind === 'error' ? styles.errorStatus : styles.infoStatus]}>
            {status.kind === 'success' ? (
              <CheckCircle2 size={22} color={styles._colors.success} />
            ) : status.kind === 'error' ? (
              <X size={22} color={styles._colors.error} />
            ) : (
              <FileText size={22} color={styles._colors.primary} />
            )}
            <View style={styles.statusMain}>
              <Text style={styles.statusTitle}>{status.title}</Text>
              <Text style={styles.statusText}>{status.message}</Text>
            </View>
          </View>
        ) : null}

        <TouchableOpacity style={styles.actionCard} onPress={createBackup} disabled={busy}>
          {creatingBackup ? <ActivityIndicator color={styles._colors.primary} /> : <Archive size={24} color={styles._colors.primary} />}
          <View style={styles.actionMain}>
            <Text style={styles.actionTitle}>创建本地备份</Text>
            <Text style={styles.actionText}>生成带版本、来源和摘要校验的 JotBill JSON 备份。</Text>
          </View>
        </TouchableOpacity>

        {backup ? (
          <View style={styles.summaryPanel}>
            <Text style={styles.panelTitle}>最近一次备份</Text>
            <Text style={styles.panelText} numberOfLines={2} ellipsizeMode="middle">{backup.filePath}</Text>
            <Text style={styles.panelMeta}>共 {countText(backup.recordSummary.totalCount)} 条摘要记录 · SHA-256 {backup.checksum.slice(0, 12)}…</Text>
          </View>
        ) : null}

        <TouchableOpacity style={styles.actionCard} onPress={chooseBackup} disabled={busy}>
          {restoring ? <ActivityIndicator color={styles._colors.primary} /> : <FileText size={24} color={styles._colors.primary} />}
          <View style={styles.actionMain}>
            <Text style={styles.actionTitle}>选择备份并预览</Text>
            <Text style={styles.actionText}>先解析版本、摘要、字段和重复记录，再决定是否覆盖恢复。</Text>
          </View>
        </TouchableOpacity>

        {preview ? (
          <View style={styles.previewPanel}>
            <View style={styles.previewHeader}>
              <View style={styles.previewHeading}>
                <Text style={styles.panelTitle}>恢复预览</Text>
                <Text style={styles.panelText} numberOfLines={2} ellipsizeMode="middle">{preview.filePath}</Text>
              </View>
              <TouchableOpacity onPress={cancelPreview} style={[styles.smallIconButton, restoring && styles.disabledButton]} disabled={restoring} accessibilityLabel="取消恢复预览">
                <X size={20} color={styles._colors.textPrimary} />
              </TouchableOpacity>
            </View>
            <View style={styles.metricsGrid}>
              <View style={styles.metric}><Text style={styles.metricValue}>{countText(preview.recordSummary.billCount)}</Text><Text style={styles.metricLabel}>账单</Text></View>
              <View style={styles.metric}><Text style={styles.metricValue}>{countText(preview.recordSummary.accountCount)}</Text><Text style={styles.metricLabel}>账户</Text></View>
              <View style={styles.metric}><Text style={styles.metricValue}>{countText(preview.recordSummary.categoryCount)}</Text><Text style={styles.metricLabel}>分类</Text></View>
              <View style={styles.metric}><Text style={styles.metricValue}>{countText(preview.duplicateCount)}</Text><Text style={styles.metricLabel}>重复替换</Text></View>
            </View>
            <Text style={styles.panelMeta}>格式 {preview.format} v{preview.formatVersion} · 校验摘要 {preview.checksum.slice(0, 12)}…</Text>
            <Text style={styles.panelMeta}>AI Provider 非敏感字段和成本记录会恢复；API Key、密钥和凭据不会写入备份。当前策略：覆盖，重复记录替换。</Text>
            <TouchableOpacity style={styles.restoreButton} onPress={confirmOverwriteRestore} disabled={restoring}>
              {restoring ? <ActivityIndicator color={styles._colors.surface} /> : <Archive size={19} color={styles._colors.surface} />}
              <Text style={styles.restoreButtonText}>确认覆盖恢复</Text>
            </TouchableOpacity>
            <TouchableOpacity style={styles.cancelButton} onPress={cancelPreview} disabled={restoring}>
              <X size={18} color={styles._colors.textPrimary} />
              <Text style={styles.cancelButtonText}>取消</Text>
            </TouchableOpacity>
          </View>
        ) : null}

        <TouchableOpacity style={styles.actionCard} onPress={() => { if (!busy) navigation.navigate('Import'); }} disabled={busy}>
          <FileUp size={24} color={styles._colors.primary} />
          <View style={styles.actionMain}>
            <Text style={styles.actionTitle}>账单导入</Text>
            <Text style={styles.actionText}>导入微信/支付宝官方 CSV、XLSX 或加密压缩包账单。</Text>
          </View>
        </TouchableOpacity>

        <TouchableOpacity style={styles.actionCard} onPress={exportCsv} disabled={busy}>
          {exporting ? <ActivityIndicator color={styles._colors.primary} /> : <Download size={24} color={styles._colors.primary} />}
          <View style={styles.actionMain}>
            <Text style={styles.actionTitle}>导出 CSV</Text>
            <Text style={styles.actionText}>导出账单明细，保留现有阶段二导出行为。</Text>
          </View>
        </TouchableOpacity>

        <TouchableOpacity style={[styles.actionCard, styles.dangerCard]} onPress={clearData} disabled={busy}>
          {clearing ? <ActivityIndicator color={styles._colors.error} /> : <Trash2 size={24} color={styles._colors.error} />}
          <View style={styles.actionMain}>
            <Text style={styles.dangerTitle}>清除本地数据</Text>
            <Text style={styles.actionText}>删除本机业务数据，操作前请先导出或创建备份。</Text>
          </View>
        </TouchableOpacity>
      </ScrollView>
    </SafeAreaView>
  );
}

const createStyles = (colors: ThemeColors) => ({
  ...StyleSheet.create({
    container: { flex: 1, backgroundColor: colors.background },
    header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: spacing.md, paddingTop: spacing.xl, paddingBottom: spacing.md, backgroundColor: colors.surface, borderBottomWidth: borderWidth.medium, borderBottomColor: colors.stroke },
    iconButton: { width: 40, height: 40, alignItems: 'center', justifyContent: 'center' },
    smallIconButton: { width: 36, height: 36, alignItems: 'center', justifyContent: 'center' },
    disabledButton: { opacity: 0.45 },
    headerTitle: { fontSize: 20, fontWeight: '800', color: colors.textPrimary },
    scrollView: { flex: 1 },
    content: { padding: spacing.lg, gap: spacing.md },
    infoCard: { flexDirection: 'row', alignItems: 'center', gap: spacing.md, padding: spacing.lg, backgroundColor: colors.primaryLight, borderWidth: borderWidth.thin, borderColor: colors.stroke, borderRadius: borderRadius.card },
    infoMain: { flex: 1 },
    infoTitle: { fontSize: 16, fontWeight: '800', color: colors.textPrimary },
    infoText: { marginTop: 4, fontSize: 13, lineHeight: 19, color: colors.textSecondary },
    statusCard: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing.md, padding: spacing.md, borderWidth: borderWidth.thin, borderRadius: borderRadius.card },
    successStatus: { backgroundColor: colors.successLight, borderColor: colors.success },
    errorStatus: { backgroundColor: '#FEE2E2', borderColor: colors.error },
    infoStatus: { backgroundColor: colors.primaryLight, borderColor: colors.primary },
    statusMain: { flex: 1 },
    statusTitle: { fontSize: 15, fontWeight: '800', color: colors.textPrimary },
    statusText: { marginTop: 4, fontSize: 13, lineHeight: 19, color: colors.textSecondary },
    actionCard: { flexDirection: 'row', alignItems: 'center', gap: spacing.md, padding: spacing.lg, backgroundColor: colors.surface, borderWidth: borderWidth.thin, borderColor: colors.stroke, borderRadius: borderRadius.card },
    dangerCard: { borderColor: colors.error },
    actionMain: { flex: 1 },
    actionTitle: { fontSize: 16, fontWeight: '800', color: colors.textPrimary },
    dangerTitle: { fontSize: 16, fontWeight: '800', color: colors.error },
    actionText: { marginTop: 4, fontSize: 13, lineHeight: 19, color: colors.textSecondary },
    summaryPanel: { padding: spacing.lg, backgroundColor: colors.surface, borderWidth: borderWidth.thin, borderColor: colors.strokeLight, borderRadius: borderRadius.small },
    previewPanel: { padding: spacing.lg, backgroundColor: colors.surface, borderWidth: borderWidth.medium, borderColor: colors.primary, borderRadius: borderRadius.small },
    previewHeader: { flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between', gap: spacing.sm },
    previewHeading: { flex: 1 },
    panelTitle: { fontSize: 16, fontWeight: '800', color: colors.textPrimary },
    panelText: { marginTop: spacing.sm, fontSize: 12, lineHeight: 17, color: colors.textSecondary },
    panelMeta: { marginTop: spacing.sm, fontSize: 12, lineHeight: 17, color: colors.textTertiary },
    metricsGrid: { flexDirection: 'row', gap: spacing.sm, marginTop: spacing.md },
    metric: { flex: 1, minWidth: 0, padding: spacing.sm, backgroundColor: colors.background, borderWidth: borderWidth.thin, borderColor: colors.strokeLight, borderRadius: borderRadius.small },
    metricValue: { fontSize: 17, fontWeight: '800', color: colors.textPrimary },
    metricLabel: { marginTop: 2, fontSize: 11, color: colors.textSecondary },
    restoreButton: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: spacing.sm, minHeight: 46, marginTop: spacing.lg, paddingHorizontal: spacing.md, backgroundColor: colors.primary, borderWidth: borderWidth.thin, borderColor: colors.stroke, borderRadius: borderRadius.button },
    restoreButtonText: { color: colors.surface, fontSize: 15, fontWeight: '800' },
    cancelButton: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: spacing.sm, minHeight: 42, marginTop: spacing.sm, paddingHorizontal: spacing.md, borderWidth: borderWidth.thin, borderColor: colors.strokeLight, borderRadius: borderRadius.button },
    cancelButtonText: { color: colors.textPrimary, fontSize: 14, fontWeight: '700' },
  }),
  _colors: colors,
});

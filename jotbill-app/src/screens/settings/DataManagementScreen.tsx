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

  /**
   * 本机现状与"覆盖后增减"。
   *
   * `preview.existingRecordSummary` 由原生返回（BackupManager:222），但此前 UI
   * 完全没用它 —— 用户只看到"备份里有 N 笔"，看不到"我本机有 M 笔会被删"。
   * 覆盖恢复是 DELETE FROM bills 全表删后重插，这两个数字必须并排出现。
   *
   * 容错：老版本备份或桥接缺该字段时按 undefined 处理，不让预览崩掉。
   */
  const existing = preview?.existingRecordSummary;
  const diffBills =
    existing && preview ? preview.recordSummary.billCount - existing.billCount : 0;

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
        message:
          `文件已保存。包含 ${countText(result.recordSummary.totalCount)} 条可恢复非敏感记录；` +
          `API Key 等凭据未写入备份。\n\n注意：备份不包含待确认交易、AI 对话历史与导入批次` +
          `（清除数据后将无法再撤销导入）。`,
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
      // ⚠️ result.errors 是原生精心构造的降级事实清单（BackupManager:252-267）：
      // "备份未包含 AI 成本记录""3 个不安全地址字段已省略""5 个仍被引用的
      // Provider 配置已保留"…… 这些是用户**必须知道**的部分状态，
      // 此前一次都没被读取，只以绿色"覆盖恢复完成"呈现 —— 等于把
      // "有些数据没恢复"说成了"全部恢复成功"。
      const warnings = result.errors ?? [];
      setStatus({
        kind: warnings.length > 0 ? 'info' : 'success',
        title: warnings.length > 0 ? `覆盖恢复完成（含 ${warnings.length} 条提示）` : '覆盖恢复完成',
        message:
          `已恢复 ${countText(result.imported)} 条非敏感记录，跳过 ${countText(result.skipped)} 条未包含记录，` +
          `替换 ${countText(result.replaced)} 条重复记录；API Key 等凭据未恢复。` +
          (warnings.length > 0 ? '\n\n以下内容未被完整恢复：\n· ' + warnings.join('\n· ') : ''),
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
    // ⚠️ 原生执行的是 database.clearAllTables() —— 清空**全部 18 张表**，
    // 而原确认文案只承诺"账单、账户、分类、预算和目标"5 类。
    // 被顺带清掉的包括：AI 对话历史（chat_sessions/messages）、
    // 待确认交易（pending_transactions）、**导入批次（import_batches）** ——
    // 后者没了就再也无法撤销那次导入。
    // 且不可逆：BackupManager 只备份 11 张表，"先备份再清除"救不回这几类。
    // 文案必须与实际范围一致，否则用户会以为"只是清账本"。
    Alert.alert(
      '清除本地数据',
      '这会清空本机全部数据，且无法撤销：\n\n' +
        '· 账单、账户、分类、预算、财务目标、规则、余额校准\n' +
        '· AI 对话历史\n' +
        '· 待确认交易（尚未入账的支付记录）\n' +
        '· 导入批次（清除后无法再撤销导入）\n\n' +
        '请先导出 CSV 或创建本地备份。注意备份本身不包含待确认交易与 AI 对话历史。',
      [
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
            Alert.alert(
              '已清除',
              '本地全部数据已删除，包括 AI 对话历史、待确认交易与导入批次。',
            );
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
            {/* ⚠️ 覆盖恢复是 DELETE FROM bills 全表删后重插（BackupManager:988-1002）。
                原生在 preview 里已经返回 existingRecordSummary（本机现状），但 UI 只渲染
                备份文件的数字 —— 用户在点确认时**看不到自己会丢多少**。
                一个装了半年账本的用户点错一次，5000 笔本地数据就没了，
                而 UI 事前事后都没让他看见这个数字。必须把两组数字并排展示。 */}
            <View style={styles.sectionLabel}>
              <Text style={styles.panelTitle}>备份文件内（将被恢复）</Text>
            </View>
            <View style={styles.metricsGrid}>
              <View style={styles.metric}><Text style={styles.metricValue}>{countText(preview.recordSummary.billCount)}</Text><Text style={styles.metricLabel}>账单</Text></View>
              <View style={styles.metric}><Text style={styles.metricValue}>{countText(preview.recordSummary.accountCount)}</Text><Text style={styles.metricLabel}>账户</Text></View>
              <View style={styles.metric}><Text style={styles.metricValue}>{countText(preview.recordSummary.categoryCount)}</Text><Text style={styles.metricLabel}>分类</Text></View>
              <View style={styles.metric}><Text style={styles.metricValue}>{countText(preview.duplicateCount)}</Text><Text style={styles.metricLabel}>重复替换</Text></View>
            </View>
            {existing ? (
              <>
                <View style={styles.sectionLabel}>
                  <Text style={styles.panelTitle}>本机现有（将被替换）</Text>
                </View>
                <View style={styles.metricsGrid}>
                  <View style={[styles.metric, styles.metricWarning]}>
                    <Text style={styles.metricValue}>{countText(existing.billCount)}</Text>
                    <Text style={styles.metricLabel}>本机账单</Text>
                  </View>
                  <View style={styles.metric}>
                    <Text style={styles.metricValue}>{countText(existing.accountCount)}</Text>
                    <Text style={styles.metricLabel}>本机账户</Text>
                  </View>
                  <View style={styles.metric}>
                    <Text style={styles.metricValue}>{countText(existing.categoryCount)}</Text>
                    <Text style={styles.metricLabel}>本机分类</Text>
                  </View>
                  <View style={[styles.metric, styles.metricWarning]}>
                    <Text style={styles.metricValue}>{countText(diffBills)}</Text>
                    <Text style={styles.metricLabel}>账单增减</Text>
                  </View>
                </View>
                <Text style={styles.warningText}>
                  {diffBills === 0
                    ? '账单数量不变，但本机已有账单会被备份中的记录替换（按 orderId 匹配）。'
                    : diffBills > 0
                      ? `覆盖后账单将减少 ${countText(diffBills)} 条（备份里没有的记录会被删除，且无法撤销）。`
                      : `覆盖后账单将增加 ${countText(-diffBills)} 条。`}
                </Text>
              </>
            ) : null}
            <Text style={styles.panelMeta}>格式 {preview.format} v{preview.formatVersion} · 校验摘要 {preview.checksum.slice(0, 12)}…</Text>
            <Text style={styles.panelMeta}>
              备份不包含：待确认交易、AI 对话历史、导入批次（清除后无法再撤销导入）、结算会话与证据。
              因此"先备份再清除"并不能救回这几类数据。
            </Text>
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
    sectionLabel: { marginTop: spacing.lg, marginBottom: spacing.xs },
    metricWarning: { borderColor: colors.error, borderWidth: borderWidth.medium },
    warningText: { marginTop: spacing.md, fontSize: 13, lineHeight: 19, fontWeight: '700', color: colors.error },
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

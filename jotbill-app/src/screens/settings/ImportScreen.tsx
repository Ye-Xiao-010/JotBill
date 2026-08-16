import React, { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { useQueryClient } from '@tanstack/react-query';
import {
  AlertTriangle,
  CheckCircle2,
  ChevronLeft,
  FileUp,
  KeyRound,
  Play,
  RotateCcw,
  X,
} from 'lucide-react-native';
import { nativeImport } from '../../native';
import type {
  ImportBatch,
  ImportExecutionResult,
  ImportInspection,
  TransactionType,
} from '../../../../docs/types/Native';
import { invalidateCache } from '../../lib/queryClient';
import { borderRadius, borderWidth, spacing } from '../../theme/spacing';
import { ThemeColors } from '../../theme/colors';
import { useStyles } from '../../hooks';

type Phase = 'entry' | 'inspecting' | 'candidate' | 'password' | 'preview' | 'executing' | 'result';

const TYPE_LABELS: Record<TransactionType, string> = {
  income: '收入',
  expense: '支出',
  transfer: '转账',
  refund: '退款',
  repayment: '还款',
  fee: '手续费',
  adjustment: '不计收支',
};

const BATCH_STATUS_LABELS: Record<ImportBatch['status'], string> = {
  RUNNING: '进行中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  FAILED: '失败',
  UNDONE: '已撤销',
  UNDO_FAILED: '部分撤销',
};

const SOURCE_LABELS: Record<string, string> = {
  wechat: '微信账单',
  alipay: '支付宝账单',
};

const errorCode = (error: unknown): string | undefined =>
  typeof error === 'object' && error !== null && 'code' in error
    ? String((error as { code?: unknown }).code)
    : undefined;

const errorMessage = (error: unknown): string => {
  if (typeof error === 'object' && error !== null) {
    const value = error as { message?: unknown; code?: unknown };
    if (typeof value.message === 'string' && value.message.length > 0) {
      return typeof value.code === 'string' ? `${value.message}（${value.code}）` : value.message;
    }
  }
  return error instanceof Error ? error.message : '账单导入失败';
};

const countText = (value: number): string => value.toLocaleString('zh-CN');

const timeText = (value?: number | null): string =>
  value == null ? '—' : new Date(value).toLocaleString('zh-CN');

export default function ImportScreen() {
  const navigation = useNavigation();
  const queryClient = useQueryClient();
  const styles = useStyles(createStyles);
  const [phase, setPhase] = useState<Phase>('entry');
  const [busy, setBusy] = useState(false);
  const [inspection, setInspection] = useState<ImportInspection | null>(null);
  const [execution, setExecution] = useState<ImportExecutionResult | null>(null);
  const [batches, setBatches] = useState<ImportBatch[]>([]);
  const [filePath, setFilePath] = useState('');
  const [selectedCandidate, setSelectedCandidate] = useState<string | null>(null);
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [cancelling, setCancelling] = useState(false);

  useEffect(() => {
    void loadBatches();
    return () => setPassword('');
  }, []);

  const loadBatches = async () => {
    try {
      setBatches(await nativeImport.getImportBatches());
    } catch (loadError) {
      // 批次列表失败不阻塞导入主流程，仅在结果区展示原生返回错误。
      setError(errorMessage(loadError));
    }
  };

  const inspectFile = async (nextFilePath?: string, nextPassword?: string, candidate?: string) => {
    setBusy(true);
    setError('');
    try {
      const result = await nativeImport.inspectImport(nextFilePath ?? '', nextPassword, candidate);
      setInspection(result);
      setFilePath(result.filePath || nextFilePath || '');
      if (result.passwordRequired) {
        setPhase('password');
      } else if (result.needsCandidateSelection) {
        setPhase('candidate');
      } else {
        setPhase('preview');
      }
    } catch (inspectError) {
      if (errorCode(inspectError) === 'IMPORT_CANCELLED') return;
      setError(errorMessage(inspectError));
      setPhase('entry');
    } finally {
      setBusy(false);
    }
  };

  const chooseCandidate = (candidateName: string) => {
    setSelectedCandidate(candidateName);
    void inspectFile(filePath, password, candidateName);
  };

  const confirmPassword = () => {
    if (!password.trim()) {
      setError('请输入解压密码');
      return;
    }
    void inspectFile(filePath, password, selectedCandidate ?? undefined);
  };

  const resetImport = () => {
    setInspection(null);
    setExecution(null);
    setFilePath('');
    setSelectedCandidate(null);
    setPassword('');
    setError('');
    setPhase('entry');
  };

  const confirmExecution = () => {
    if (!inspection) return;
    if (inspection.duplicateFile) {
      Alert.alert(
        '重复文件',
        '该文件内容哈希已经导入过。默认阻止执行；如确认是改名后的同一文件仍要导入，请点击“仍要导入”。',
        [
          { text: '取消', style: 'cancel' },
          { text: '仍要导入', style: 'destructive', onPress: () => { void executeImport(); } },
        ],
      );
      return;
    }
    void executeImport();
  };

  const executeImport = async () => {
    if (!filePath || !inspection) return;
    setBusy(true);
    setError('');
    setPhase('executing');
    try {
      const result = await nativeImport.executeImport(
        filePath,
        password || undefined,
        selectedCandidate ?? undefined,
      );
      setExecution(result);
      invalidateCache.bills();
      void queryClient.refetchQueries({ queryKey: ['accounts'] });
      await loadBatches();
      setPhase('result');
    } catch (executeError) {
      if (errorCode(executeError) === 'IMPORT_CANCELLED') {
        setError('导入已取消，已写入部分已清理。');
        setPhase('preview');
      } else {
        setError(errorMessage(executeError));
        setPhase('preview');
      }
    } finally {
      setBusy(false);
    }
  };

  const cancelImport = async () => {
    if (cancelling) return;
    setCancelling(true);
    try {
      await nativeImport.cancelImport();
    } catch (cancelError) {
      setError(errorMessage(cancelError));
    } finally {
      setCancelling(false);
    }
  };

  const confirmUndo = (batch: ImportBatch) => {
    Alert.alert(
      '撤销导入',
      `将撤销最近批次 ${batch.id.slice(0, 12)}… 新建的账单与合并更新；已被你编辑过的记录会保留并列入清单。`,
      [
        { text: '取消', style: 'cancel' },
        { text: '整批撤销', style: 'destructive', onPress: () => { void undoBatch(batch); } },
      ],
    );
  };

  const undoBatch = async (batch: ImportBatch) => {
    setBusy(true);
    setError('');
    try {
      const result = await nativeImport.undoImport(batch.id);
      invalidateCache.bills();
      await loadBatches();
      const protectedText = result.protectedRecords.length > 0
        ? `；${countText(result.protectedRecords.length)} 条受保护记录未自动处理`
        : '';
      Alert.alert(
        result.status === 'UNDONE' ? '撤销完成' : '部分撤销',
        `已删除 ${countText(result.deletedCount)} 条、回滚 ${countText(result.rollbackCount)} 条、清理待确认 ${countText(result.pendingDeletedCount)} 条${protectedText}。`,
      );
    } catch (undoError) {
      setError(errorMessage(undoError));
    } finally {
      setBusy(false);
    }
  };

  const handleBack = () => {
    if (busy) return;
    navigation.goBack();
  };

  const renderHeader = () => (
    <View style={styles.header}>
      <TouchableOpacity onPress={handleBack} style={[styles.iconButton, busy && styles.disabledButton]} disabled={busy} accessibilityLabel="返回">
        <ChevronLeft size={24} color={styles._colors.textPrimary} />
      </TouchableOpacity>
      <Text style={styles.headerTitle}>账单导入</Text>
      <FileUp size={21} color={styles._colors.primary} />
    </View>
  );

  const renderError = () => (error ? (
    <View style={[styles.statusCard, styles.errorStatus]}>
      <AlertTriangle size={20} color={styles._colors.error} />
      <Text style={styles.statusText}>{error}</Text>
    </View>
  ) : null);

  const renderEntry = () => (
    <ScrollView style={styles.scrollView} contentContainerStyle={styles.content}>
      <View style={styles.infoCard}>
        <FileUp size={24} color={styles._colors.primary} />
        <View style={styles.infoMain}>
          <Text style={styles.infoTitle}>微信/支付宝官方账单</Text>
          <Text style={styles.infoText}>支持 CSV、XLSX 与加密压缩包；密码只用于本次解压，不会保存。</Text>
        </View>
      </View>
      {renderError()}
      <TouchableOpacity style={styles.actionCard} onPress={() => { void inspectFile(); }} disabled={busy}>
        {busy ? <ActivityIndicator color={styles._colors.primary} /> : <FileUp size={24} color={styles._colors.primary} />}
        <View style={styles.actionMain}>
          <Text style={styles.actionTitle}>选择账单文件</Text>
          <Text style={styles.actionText}>先解析并预览，确认后才会写入账单。</Text>
        </View>
      </TouchableOpacity>
      <View style={styles.section}>
        <Text style={styles.sectionTitle}>最近导入批次</Text>
        {batches.length === 0 ? (
          <Text style={styles.emptyText}>暂无导入批次</Text>
        ) : batches.slice(0, 5).map((batch) => (
          <View key={batch.id} style={styles.batchCard}>
            <View style={styles.batchMain}>
              <Text style={styles.batchTitle}>
                {SOURCE_LABELS[batch.source] ?? batch.source} · {batch.format.toUpperCase()} · {BATCH_STATUS_LABELS[batch.status]}
              </Text>
              <Text style={styles.batchMeta}>
                新增 {countText(batch.importedCount)} · 合并 {countText(batch.mergedCount)} · 跳过 {countText(batch.skippedCount)}
              </Text>
              <Text style={styles.batchMeta} numberOfLines={1}>{batch.id}</Text>
            </View>
            {batch.status === 'COMPLETED' ? (
              <TouchableOpacity style={styles.undoButton} onPress={() => confirmUndo(batch)} disabled={busy}>
                <RotateCcw size={16} color={styles._colors.surface} />
                <Text style={styles.undoButtonText}>撤销</Text>
              </TouchableOpacity>
            ) : null}
          </View>
        ))}
      </View>
    </ScrollView>
  );

  const renderInspecting = () => (
    <View style={styles.centerBox}>
      <ActivityIndicator size="large" color={styles._colors.primary} />
      <Text style={styles.centerText}>正在解析账单文件…</Text>
    </View>
  );

  const renderCandidate = () => (
    <ScrollView style={styles.scrollView} contentContainerStyle={styles.content}>
      {renderError()}
      <Text style={styles.panelTitle}>压缩包内有多个候选文件</Text>
      <Text style={styles.panelText}>请选择要导入的账单文件，不自动猜测。</Text>
      {(inspection?.candidates ?? []).map((candidate) => (
        <TouchableOpacity
          key={candidate.fileName}
          style={styles.actionCard}
          onPress={() => chooseCandidate(candidate.fileName)}
          disabled={busy}
        >
          <FileUp size={22} color={styles._colors.primary} />
          <View style={styles.actionMain}>
            <Text style={styles.actionTitle}>{candidate.fileName}</Text>
            <Text style={styles.actionText}>{candidate.format.toUpperCase()} · {candidate.encoding ?? '未知编码'}</Text>
          </View>
        </TouchableOpacity>
      ))}
    </ScrollView>
  );

  const renderPassword = () => (
    <ScrollView style={styles.scrollView} contentContainerStyle={styles.content}>
      <View style={styles.infoCard}>
        <KeyRound size={24} color={styles._colors.primary} />
        <View style={styles.infoMain}>
          <Text style={styles.infoTitle}>加密压缩包</Text>
          <Text style={styles.infoText}>密码只在当前导入调用内使用，不写入数据库、日志或剪贴板。</Text>
        </View>
      </View>
      {renderError()}
      <TextInput
        style={styles.passwordInput}
        value={password}
        onChangeText={setPassword}
        placeholder="请输入账单压缩包解压密码"
        placeholderTextColor={styles._colors.textTertiary}
        secureTextEntry
        autoCapitalize="none"
        autoCorrect={false}
      />
      <TouchableOpacity style={styles.primaryButton} onPress={confirmPassword} disabled={busy}>
        {busy ? <ActivityIndicator color={styles._colors.surface} /> : <KeyRound size={18} color={styles._colors.surface} />}
        <Text style={styles.primaryButtonText}>继续预览</Text>
      </TouchableOpacity>
      <TouchableOpacity style={styles.secondaryButton} onPress={resetImport} disabled={busy}>
        <X size={18} color={styles._colors.textPrimary} />
        <Text style={styles.secondaryButtonText}>重新选择文件</Text>
      </TouchableOpacity>
    </ScrollView>
  );

  const renderPreview = () => {
    const planned = inspection?.planned;
    const typeDistribution = inspection?.typeDistribution ?? {};
    return (
      <ScrollView style={styles.scrollView} contentContainerStyle={styles.content}>
        {renderError()}
        <View style={styles.previewPanel}>
          <View style={styles.previewHeading}>
            <Text style={styles.panelTitle}>
              {inspection?.source ? SOURCE_LABELS[inspection.source] ?? inspection.source : '待确认账单'}预览
            </Text>
            <Text style={styles.panelMeta}>{inspection?.format.toUpperCase()} · {inspection?.formatVersion ?? '版本未知'}</Text>
          </View>
          {inspection?.duplicateFile ? (
            <View style={[styles.statusCard, styles.warningStatus]}>
              <AlertTriangle size={20} color={styles._colors.warning} />
              <Text style={styles.statusText}>文件内容 SHA-256 已存在于历史批次，默认阻止重复导入。</Text>
            </View>
          ) : null}
          <Text style={styles.panelText}>时间范围：{timeText(inspection?.timeRangeStart)} ~ {timeText(inspection?.timeRangeEnd)}</Text>
          <Text style={styles.panelText}>总记录 {countText(inspection?.totalRecords ?? 0)} · 有效记录 {countText(inspection?.validRecords ?? 0)} · 来源跳过 {countText(inspection?.skippedBySource ?? 0)}</Text>
          <Text style={styles.panelMeta}>文件哈希 {inspection?.fileHash.slice(0, 16)}…</Text>
          <View style={styles.metricsGrid}>
            <View style={styles.metric}><Text style={styles.metricValue}>{countText(planned?.imported ?? 0)}</Text><Text style={styles.metricLabel}>预计新增</Text></View>
            <View style={styles.metric}><Text style={styles.metricValue}>{countText(planned?.merged ?? 0)}</Text><Text style={styles.metricLabel}>合并</Text></View>
            <View style={styles.metric}><Text style={styles.metricValue}>{countText(planned?.skipped ?? 0)}</Text><Text style={styles.metricLabel}>跳过</Text></View>
            <View style={styles.metric}><Text style={styles.metricValue}>{countText(planned?.pending ?? 0)}</Text><Text style={styles.metricLabel}>待确认</Text></View>
            <View style={styles.metric}><Text style={styles.metricValue}>{countText(planned?.failed ?? 0)}</Text><Text style={styles.metricLabel}>失败</Text></View>
          </View>
          <Text style={styles.panelText}>疑似重复 {countText(inspection?.suspiciousCount ?? 0)} · 账户未匹配 {countText(inspection?.accountUnmatchedCount ?? 0)}</Text>
          <View style={styles.typeBox}>
            {(Object.keys(typeDistribution) as TransactionType[]).map((type) => (
              <View key={type} style={styles.typeRow}>
                <Text style={styles.typeLabel}>{TYPE_LABELS[type]}</Text>
                <Text style={styles.typeValue}>{countText(typeDistribution[type] ?? 0)}</Text>
              </View>
            ))}

          </View>
          {(inspection?.sampleErrors?.length ?? 0) > 0 ? (
            <View style={styles.errorBox}>
              <Text style={styles.panelTitle}>解析提示</Text>
              {inspection?.sampleErrors.map((sample, index) => (
                <Text key={`${index}-${sample}`} style={styles.panelMeta}>{sample}</Text>
              ))}
            </View>
          ) : null}
          <TouchableOpacity style={styles.primaryButton} onPress={confirmExecution} disabled={busy}>
            {busy ? <ActivityIndicator color={styles._colors.surface} /> : <Play size={18} color={styles._colors.surface} />}
            <Text style={styles.primaryButtonText}>确认执行导入</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.secondaryButton} onPress={resetImport} disabled={busy}>
            <X size={18} color={styles._colors.textPrimary} />
            <Text style={styles.secondaryButtonText}>重新选择文件</Text>
          </TouchableOpacity>
        </View>
      </ScrollView>
    );
  };

  const renderExecuting = () => (
    <View style={styles.centerBox}>
      <ActivityIndicator size="large" color={styles._colors.primary} />
      <Text style={styles.centerTitle}>正在分批写入账单</Text>
      <Text style={styles.centerText}>请勿离开页面；可在下方安全取消，已写入部分会清理。</Text>
      {renderError()}
      <TouchableOpacity style={styles.dangerButton} onPress={() => { void cancelImport(); }} disabled={cancelling}>
        {cancelling ? <ActivityIndicator color={styles._colors.surface} /> : <X size={18} color={styles._colors.surface} />}
        <Text style={styles.dangerButtonText}>取消导入</Text>
      </TouchableOpacity>
    </View>
  );

  const renderResult = () => (
    <ScrollView style={styles.scrollView} contentContainerStyle={styles.content}>
      <View style={styles.successPanel}>
        <CheckCircle2 size={28} color={styles._colors.success} />
        <View style={styles.infoMain}>
          <Text style={styles.panelTitle}>导入完成</Text>
          <Text style={styles.panelText}>
            新增 {countText(execution?.imported ?? 0)} · 合并 {countText(execution?.merged ?? 0)} · 跳过 {countText(execution?.skipped ?? 0)} · 待确认 {countText(execution?.pending ?? 0)} · 失败 {countText(execution?.failed ?? 0)}
          </Text>
          <Text style={styles.panelMeta}>批次号：{execution?.batchId}</Text>
        </View>
      </View>
      {renderError()}
      <View style={styles.section}>
        <Text style={styles.sectionTitle}>撤销本次导入</Text>
        {execution && batches.some((batch) => batch.id === execution.batchId && batch.status === 'COMPLETED') ? (
          <TouchableOpacity style={styles.dangerButton} onPress={() => confirmUndo(batches.find((batch) => batch.id === execution.batchId) as ImportBatch)} disabled={busy}>
            <RotateCcw size={18} color={styles._colors.surface} />
            <Text style={styles.dangerButtonText}>整批撤销</Text>
          </TouchableOpacity>
        ) : (
          <Text style={styles.panelText}>当前没有可撤销的最近批次。</Text>
        )}
      </View>
      <TouchableOpacity style={styles.primaryButton} onPress={resetImport} disabled={busy}>
        <FileUp size={18} color={styles._colors.surface} />
        <Text style={styles.primaryButtonText}>继续导入</Text>
      </TouchableOpacity>
    </ScrollView>
  );

  const renderBody = () => {
    switch (phase) {
      case 'inspecting': return renderInspecting();
      case 'candidate': return renderCandidate();
      case 'password': return renderPassword();
      case 'preview': return renderPreview();
      case 'executing': return renderExecuting();
      case 'result': return renderResult();
      default: return renderEntry();
    }
  };

  return (
    <SafeAreaView style={styles.container} edges={['top', 'bottom']}>
      {renderHeader()}
      {renderBody()}
    </SafeAreaView>
  );
}

const createStyles = (colors: ThemeColors) => ({
  ...StyleSheet.create({
    container: { flex: 1, backgroundColor: colors.background },
    header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: spacing.md, paddingTop: spacing.xl, paddingBottom: spacing.md, backgroundColor: colors.surface, borderBottomWidth: borderWidth.medium, borderBottomColor: colors.stroke },
    iconButton: { width: 40, height: 40, alignItems: 'center', justifyContent: 'center' },
    disabledButton: { opacity: 0.45 },
    headerTitle: { fontSize: 20, fontWeight: '800', color: colors.textPrimary },
    scrollView: { flex: 1 },
    content: { padding: spacing.lg, gap: spacing.md },
    infoCard: { flexDirection: 'row', alignItems: 'center', gap: spacing.md, padding: spacing.lg, backgroundColor: colors.primaryLight, borderWidth: borderWidth.thin, borderColor: colors.stroke, borderRadius: borderRadius.card },
    infoMain: { flex: 1 },
    infoTitle: { fontSize: 16, fontWeight: '800', color: colors.textPrimary },
    infoText: { marginTop: 4, fontSize: 13, lineHeight: 19, color: colors.textSecondary },
    statusCard: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing.sm, padding: spacing.md, borderWidth: borderWidth.thin, borderRadius: borderRadius.card },
    errorStatus: { backgroundColor: '#FEE2E2', borderColor: colors.error },
    warningStatus: { backgroundColor: colors.primaryLight, borderColor: colors.warning },
    statusText: { flex: 1, fontSize: 13, lineHeight: 19, color: colors.textSecondary },
    actionCard: { flexDirection: 'row', alignItems: 'center', gap: spacing.md, padding: spacing.lg, backgroundColor: colors.surface, borderWidth: borderWidth.thin, borderColor: colors.stroke, borderRadius: borderRadius.card },
    actionMain: { flex: 1 },
    actionTitle: { fontSize: 16, fontWeight: '800', color: colors.textPrimary },
    actionText: { marginTop: 4, fontSize: 13, lineHeight: 19, color: colors.textSecondary },
    centerBox: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: spacing.xxl, gap: spacing.md },
    centerTitle: { fontSize: 18, fontWeight: '800', color: colors.textPrimary },
    centerText: { fontSize: 14, lineHeight: 20, color: colors.textSecondary, textAlign: 'center' },
    previewPanel: { padding: spacing.lg, backgroundColor: colors.surface, borderWidth: borderWidth.medium, borderColor: colors.primary, borderRadius: borderRadius.small },
    previewHeading: { flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between', gap: spacing.sm },
    panelTitle: { fontSize: 16, fontWeight: '800', color: colors.textPrimary },
    panelText: { marginTop: spacing.sm, fontSize: 13, lineHeight: 19, color: colors.textSecondary },
    panelMeta: { marginTop: spacing.sm, fontSize: 12, lineHeight: 17, color: colors.textTertiary },
    metricsGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm, marginTop: spacing.md },
    metric: { flexGrow: 1, minWidth: 64, padding: spacing.sm, backgroundColor: colors.background, borderWidth: borderWidth.thin, borderColor: colors.strokeLight, borderRadius: borderRadius.small },
    metricValue: { fontSize: 17, fontWeight: '800', color: colors.textPrimary },
    metricLabel: { marginTop: 2, fontSize: 11, color: colors.textSecondary },
    typeBox: { marginTop: spacing.md, gap: spacing.xs },
    typeRow: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: spacing.xs, borderBottomWidth: 1, borderBottomColor: colors.divider },
    typeLabel: { fontSize: 13, color: colors.textSecondary },
    typeValue: { fontSize: 13, fontWeight: '800', color: colors.textPrimary },
    errorBox: { marginTop: spacing.md, padding: spacing.md, backgroundColor: colors.background, borderWidth: borderWidth.thin, borderColor: colors.strokeLight, borderRadius: borderRadius.small },
    passwordInput: { minHeight: 48, paddingHorizontal: spacing.md, fontSize: 15, fontWeight: '600', color: colors.textPrimary, backgroundColor: colors.surface, borderWidth: borderWidth.medium, borderColor: colors.stroke, borderRadius: borderRadius.input },
    primaryButton: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: spacing.sm, minHeight: 46, marginTop: spacing.md, paddingHorizontal: spacing.md, backgroundColor: colors.primary, borderWidth: borderWidth.thin, borderColor: colors.stroke, borderRadius: borderRadius.button },
    primaryButtonText: { color: colors.surface, fontSize: 15, fontWeight: '800' },
    secondaryButton: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: spacing.sm, minHeight: 42, marginTop: spacing.sm, paddingHorizontal: spacing.md, borderWidth: borderWidth.thin, borderColor: colors.strokeLight, borderRadius: borderRadius.button },
    secondaryButtonText: { color: colors.textPrimary, fontSize: 14, fontWeight: '700' },
    dangerButton: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: spacing.sm, minHeight: 44, marginTop: spacing.md, paddingHorizontal: spacing.md, backgroundColor: colors.error, borderWidth: borderWidth.thin, borderColor: colors.stroke, borderRadius: borderRadius.button },
    dangerButtonText: { color: colors.surface, fontSize: 14, fontWeight: '800' },
    successPanel: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing.md, padding: spacing.lg, backgroundColor: colors.successLight, borderWidth: borderWidth.thin, borderColor: colors.success, borderRadius: borderRadius.card },
    section: { padding: spacing.lg, backgroundColor: colors.surface, borderWidth: borderWidth.thin, borderColor: colors.stroke, borderRadius: borderRadius.small },
    sectionTitle: { fontSize: 15, fontWeight: '800', color: colors.textPrimary },
    emptyText: { marginTop: spacing.md, fontSize: 13, color: colors.textTertiary },
    batchCard: { flexDirection: 'row', alignItems: 'center', gap: spacing.md, marginTop: spacing.md, padding: spacing.md, backgroundColor: colors.background, borderWidth: borderWidth.thin, borderColor: colors.strokeLight, borderRadius: borderRadius.small },
    batchMain: { flex: 1 },
    batchTitle: { fontSize: 14, fontWeight: '800', color: colors.textPrimary },
    batchMeta: { marginTop: 3, fontSize: 12, lineHeight: 16, color: colors.textTertiary },
    undoButton: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs, paddingHorizontal: spacing.md, minHeight: 36, backgroundColor: colors.primary, borderWidth: borderWidth.thin, borderColor: colors.stroke, borderRadius: borderRadius.small },
    undoButtonText: { color: colors.surface, fontSize: 12, fontWeight: '800' },
  }),
  _colors: colors,
});

/**
 * 待确认中心 - Neo-Brutalism 风格
 * 展示 AI 识别产出的待确认交易，支持确认入账、忽略和重试识别
 */
import React, { useCallback, useMemo, useState } from 'react';
import {
  ActivityIndicator,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AlertCircle,
  Bell,
  Check,
  ChevronLeft,
  Clock3,
  Inbox,
  Loader2,
  RefreshCw,
  Sparkles,
  X,
} from 'lucide-react-native';
import {
  nativeData,
  nativeRecognition,
  type PendingTransaction,
  type PaymentEvidence,
  type RecognitionStatus,
} from '../../native';
import { invalidateCache, QUERY_KEYS } from '../../lib/queryClient';
import { useAlert } from '../../providers';
import { borderRadius, borderWidth, spacing } from '../../theme/spacing';
import { ThemeColors } from '../../theme/colors';
import { useStyles } from '../../hooks';
import { SafeAreaView } from 'react-native-safe-area-context';

const PENDING_QUERY_KEY = QUERY_KEYS.recognition.pending;
const STATUS_QUERY_KEY = QUERY_KEYS.recognition.status;
const EVIDENCE_QUERY_KEY = QUERY_KEYS.recognition.evidence;

/** 待确认中心屏幕组件，对应阶段三任务9前端桥接。 */
export default function ReviewScreen() {
  const navigation = useNavigation();
  const queryClient = useQueryClient();
  const styles = useStyles(createStyles);
  const { alert } = useAlert();
  const [retrySubmittedVersions, setRetrySubmittedVersions] = useState<
    Record<string, number>
  >({});

  const pendingQuery = useQuery({
    queryKey: PENDING_QUERY_KEY,
    queryFn: () => nativeData.getPendingTransactions({ page: 1, limit: 50 }),
  });
  const statusQuery = useQuery({
    queryKey: STATUS_QUERY_KEY,
    queryFn: () => nativeRecognition.getRecognitionStatus(),
  });

  const invalidateAll = useCallback(() => {
    invalidateCache.recognition();
    invalidateCache.bills();
  }, []);

  const confirmMutation = useMutation({
    mutationFn: ({ pending }: { pending: PendingTransaction }) =>
      nativeData.confirmPendingTransaction(pending.pendingId, {}, pending.version),
    onSuccess: () => {
      invalidateAll();
    },
    onError: (error, { pending }) => {
      const message =
        error instanceof Error ? error.message : '确认入账失败，请稍后重试';
      alert('确认失败', `${pending.pendingId}：${message}`);
    },
  });

  const ignoreMutation = useMutation({
    mutationFn: ({ pending }: { pending: PendingTransaction }) =>
      nativeData.ignorePendingTransaction(pending.pendingId, pending.version),
    onSuccess: () => {
      invalidateAll();
    },
    onError: (error, { pending }) => {
      const message =
        error instanceof Error ? error.message : '忽略失败，请稍后重试';
      alert('忽略失败', `${pending.pendingId}：${message}`);
    },
  });

  const retryMutation = useMutation({
    mutationFn: ({ candidateId }: { candidateId: string }) =>
      nativeRecognition.retryAiRecognition(candidateId),
    onSuccess: () => {
      invalidateAll();
      alert('重试已触发', 'AI 识别已重新触发，结果会更新到待确认列表。');
    },
    onError: (error, { candidateId }) => {
      setRetrySubmittedVersions((current) => {
        if (!(candidateId in current)) return current;
        const next = { ...current };
        delete next[candidateId];
        return next;
      });
      const message =
        error instanceof Error ? error.message : '重试识别失败，请稍后重试';
      alert('重试失败', message);
    },
  });

  const refresh = useCallback(async () => {
    await Promise.all([
      pendingQuery.refetch(),
      statusQuery.refetch(),
      queryClient.refetchQueries({
        queryKey: EVIDENCE_QUERY_KEY,
        type: 'active',
      }),
    ]);
  }, [pendingQuery, queryClient, statusQuery]);

  // 焦点期间自动刷新，方便从无障碍服务返回时立即看到最新候选。
  useFocusEffect(
    useCallback(() => {
      invalidateAll();
    }, [invalidateAll]),
  );

  const status = statusQuery.data;
  const pendingItems = pendingQuery.data?.items ?? [];

  const handleConfirm = useCallback(
    (pending: PendingTransaction) => {
      confirmMutation.mutate({ pending });
    },
    [confirmMutation],
  );

  const handleIgnore = useCallback(
    (pending: PendingTransaction) => {
      ignoreMutation.mutate({ pending });
    },
    [ignoreMutation],
  );

  const handleRetry = useCallback(
    (pending: PendingTransaction) => {
      if (!pending.candidateId) {
        alert('无法重试', '该待确认交易没有关联的候选，不能重试识别。');
        return;
      }
      if (retrySubmittedVersions[pending.candidateId] === pending.version) {
        return;
      }
      setRetrySubmittedVersions((current) => ({
        ...current,
        [pending.candidateId as string]: pending.version,
      }));
      retryMutation.mutate({ candidateId: pending.candidateId });
    },
    [alert, retryMutation, retrySubmittedVersions],
  );

  return (
    <SafeAreaView style={styles.container} edges={['top', 'bottom']}>
      <View style={styles.header}>
        <TouchableOpacity
          onPress={() => navigation.goBack()}
          style={styles.iconButton}
        >
          <ChevronLeft size={24} color={styles._colors.textPrimary} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>待确认中心</Text>
        <Inbox size={21} color={styles._colors.primary} />
      </View>
      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.content}
        refreshControl={
          <RefreshControl
            refreshing={pendingQuery.isFetching || statusQuery.isFetching}
            onRefresh={refresh}
          />
        }
      >
        {statusQuery.isError ? (
          <QueryErrorState
            title="识别状态读取失败"
            onRetry={() => statusQuery.refetch()}
            colors={styles._colors}
          />
        ) : (
          <RecognitionStatusCard status={status} colors={styles._colors} />
        )}
        {pendingQuery.isError ? (
          <QueryErrorState
            title="待确认交易读取失败"
            onRetry={() => pendingQuery.refetch()}
            colors={styles._colors}
          />
        ) : pendingQuery.isLoading ? (
          <LoadingState colors={styles._colors} />
        ) : pendingItems.length === 0 ? (
          <EmptyState colors={styles._colors} />
        ) : (
          <View style={styles.list}>
            {pendingItems.map((pending) => (
              <PendingCard
                key={pending.pendingId}
                pending={pending}
                onConfirm={handleConfirm}
                onIgnore={handleIgnore}
                onRetry={handleRetry}
                isConfirming={
                  confirmMutation.isPending &&
                  confirmMutation.variables?.pending.pendingId === pending.pendingId
                }
                isIgnoring={
                  ignoreMutation.isPending &&
                  ignoreMutation.variables?.pending.pendingId === pending.pendingId
                }
                isRetrying={
                  retryMutation.isPending &&
                  retryMutation.variables?.candidateId === pending.candidateId
                }
                retryBlocked={
                  Boolean(
                    pending.candidateId &&
                      retrySubmittedVersions[pending.candidateId] === pending.version,
                  )
                }
                colors={styles._colors}
              />
            ))}
          </View>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

/** 识别链路状态卡片，展示活跃会话、待确认数和 AI 是否在识别中。 */
function RecognitionStatusCard({
  status,
  colors,
}: {
  status?: RecognitionStatus;
  colors: ThemeColors;
}) {
  const styles = useStyles(createStyles);
  const aiActive = status?.aiRequesting === true;
  const activeSessions = status?.activeSessions ?? 0;
  const pendingCount = status?.pendingCount ?? 0;

  return (
    <View style={styles.statusCard}>
      <View style={styles.statusHeader}>
        <Sparkles size={18} color={colors.primary} />
        <Text style={styles.statusTitle}>识别链路状态</Text>
      </View>
      <View style={styles.statusMetrics}>
        <View style={styles.metricItem}>
          <Text style={styles.metricValue}>{activeSessions}</Text>
          <Text style={styles.metricLabel}>活跃会话</Text>
        </View>
        <View style={styles.metricDivider} />
        <View style={styles.metricItem}>
          <Text style={styles.metricValue}>{pendingCount}</Text>
          <Text style={styles.metricLabel}>待确认</Text>
        </View>
        <View style={styles.metricDivider} />
        <View style={styles.metricItem}>
          <View style={styles.metricIconRow}>
            {aiActive ? (
              <Loader2 size={16} color={colors.primary} />
            ) : (
              <Clock3 size={16} color={colors.textTertiary} />
            )}
            <Text
              style={[
                styles.metricValueSmall,
                { color: aiActive ? colors.primary : colors.textPrimary },
              ]}
            >
              {aiActive ? '识别中' : '空闲'}
            </Text>
          </View>
          <Text style={styles.metricLabel}>AI 状态</Text>
        </View>
      </View>
    </View>
  );
}

/** 空状态提示，避免空白页面给用户造成困惑。 */
function QueryErrorState({
  title,
  onRetry,
  colors,
}: {
  title: string;
  onRetry: () => Promise<unknown>;
  colors: ThemeColors;
}) {
  const styles = useStyles(createStyles);
  return (
    <View style={styles.emptyCard}>
      <AlertCircle size={32} color={colors.error} />
      <Text style={styles.emptyTitle}>{title}</Text>
      <Text style={styles.emptyText}>请刷新后重试，当前没有可用的查询结果。</Text>
      <TouchableOpacity
        style={styles.errorButton}
        onPress={() => void onRetry()}
        activeOpacity={0.85}
      >
        <RefreshCw size={16} color={colors.textPrimary} />
        <Text style={styles.errorButtonText}>重试</Text>
      </TouchableOpacity>
    </View>
  );
}

function EmptyState({ colors }: { colors: ThemeColors }) {
  const styles = useStyles(createStyles);
  return (
    <View style={styles.emptyCard}>
      <Bell size={32} color={colors.textTertiary} />
      <Text style={styles.emptyTitle}>暂无待确认交易</Text>
      <Text style={styles.emptyText}>
        AI 视觉识别或支付通知产生待确认交易时，会在此处显示。
        确认入账后会写入本地账本并更新账户余额。
      </Text>
    </View>
  );
}

/** 初次查询期间单独展示加载态，避免把尚未返回的数据误报为空。 */
function LoadingState({ colors }: { colors: ThemeColors }) {
  const styles = useStyles(createStyles);
  return (
    <View style={styles.emptyCard}>
      <ActivityIndicator size="small" color={colors.primary} />
      <Text style={styles.emptyTitle}>正在读取待确认交易</Text>
      <Text style={styles.emptyText}>请稍候…</Text>
    </View>
  );
}

/** 单条待确认交易卡片，展示候选字段并触发确认/忽略/重试。 */
interface PendingCardProps {
  pending: PendingTransaction;
  onConfirm: (pending: PendingTransaction) => void;
  onIgnore: (pending: PendingTransaction) => void;
  onRetry: (pending: PendingTransaction) => void;
  isConfirming: boolean;
  isIgnoring: boolean;
  isRetrying: boolean;
  retryBlocked: boolean;
  colors: ThemeColors;
}

function PendingCard({
  pending,
  onConfirm,
  onIgnore,
  onRetry,
  isConfirming,
  isIgnoring,
  isRetrying,
  retryBlocked,
  colors,
}: PendingCardProps) {
  const styles = useStyles(createStyles);
  // 阶段四 T7：按会话查询支付证据摘要；无会话的纯手动记录不查询
  const evidenceQuery = useQuery({
    queryKey: [...EVIDENCE_QUERY_KEY, pending.sessionId ?? 'none'],
    queryFn: () => nativeRecognition.getPaymentEvidence(pending.sessionId!),
    enabled: Boolean(pending.sessionId),
    staleTime: 30_000,
  });
  const evidences = useMemo(
    () =>
      (evidenceQuery.data ?? []).filter(
        (item) => item.paymentStatus === 'SUCCESS' || item.paymentStatus == null,
      ),
    [evidenceQuery.data],
  );
  const amountLabel = useMemo(
    () => formatAmount(pending.amountMinor, pending.type),
    [pending.amountMinor, pending.type],
  );
  const sourceLabel = useMemo(
    () => SOURCE_LABELS[pending.sourceType] ?? pending.sourceType,
    [pending.sourceType],
  );
  const timeLabel = useMemo(() => formatTime(pending.occurredAt), [pending.occurredAt]);
  const conflictLabel = useMemo(
    () => translateConflictReason(pending.conflictReason),
    [pending.conflictReason],
  );
  const hasConflict = Boolean(pending.conflictReason);
  // 审查修复配套：AI 失败占位条目金额未知（0），禁止确认入账，只能忽略或重试识别
  const isAiFailedPlaceholder =
    pending.conflictReason === 'AI_RECOGNITION_FAILED' && pending.amountMinor === 0;

  return (
    <View style={styles.pendingCard}>
      <View style={styles.pendingHeader}>
        <View style={styles.pendingAmountRow}>
          <Text style={styles.pendingAmount}>
            {isAiFailedPlaceholder ? '金额未知' : amountLabel}
          </Text>
          <View
            style={[
              styles.typeBadge,
              {
                backgroundColor:
                  pending.type === 'income' ? colors.primaryLight : colors.warning,
              },
            ]}
          >
            <Text style={styles.typeBadgeText}>
              {TYPE_LABELS[pending.type] ?? pending.type}
            </Text>
          </View>
        </View>
        <Text style={styles.pendingTime}>{timeLabel}</Text>
      </View>

      <View style={styles.pendingMeta}>
        <MetaRow label="来源" value={sourceLabel} colors={colors} />
        {pending.platform ? (
          <MetaRow label="平台" value={pending.platform} colors={colors} />
        ) : null}
        {pending.merchant ? (
          <MetaRow label="商户" value={pending.merchant} colors={colors} />
        ) : null}
        {pending.productDescription ? (
          <MetaRow
            label="商品"
            value={pending.productDescription}
            colors={colors}
          />
        ) : null}
        {pending.orderId ? (
          <MetaRow label="订单号" value={pending.orderId} colors={colors} />
        ) : null}
        {hasConflict ? (
          <MetaRow
            label="冲突"
            value={conflictLabel}
            colors={colors}
            highlight
          />
        ) : null}
      </View>

      {pending.sessionId ? (
        <EvidenceSection
          evidences={evidences}
          loading={evidenceQuery.isLoading}
          error={evidenceQuery.isError}
          onRetry={() => evidenceQuery.refetch()}
          colors={colors}
        />
      ) : (
        <NoSessionEvidenceState />
      )}

      <View style={styles.actionRow}>
        <TouchableOpacity
          style={[
            styles.confirmButton,
            (isConfirming || isAiFailedPlaceholder) && styles.actionButtonDisabled,
          ]}
          onPress={() => onConfirm(pending)}
          disabled={isConfirming || isIgnoring || isAiFailedPlaceholder}
          activeOpacity={0.85}
        >
          <Check size={16} color="#FFFFFF" />
          <Text style={styles.confirmButtonText}>
            {isAiFailedPlaceholder
              ? '识别失败，请重试'
              : isConfirming
                ? '确认中…'
                : '确认入账'}
          </Text>
        </TouchableOpacity>
        {pending.candidateId ? (
          <TouchableOpacity
            style={[
              styles.retryButton,
              (isRetrying || retryBlocked) && styles.actionButtonDisabled,
            ]}
            onPress={() => onRetry(pending)}
            disabled={isRetrying || isConfirming || isIgnoring || retryBlocked}
            activeOpacity={0.85}
          >
            <RefreshCw size={16} color={colors.textPrimary} />
            <Text style={styles.retryButtonText}>
              {isRetrying ? '重试中…' : retryBlocked ? '重试已提交' : '重试识别'}
            </Text>
          </TouchableOpacity>
        ) : null}
        <TouchableOpacity
          style={[
            styles.ignoreButton,
            isIgnoring && styles.actionButtonDisabled,
          ]}
          onPress={() => onIgnore(pending)}
          disabled={isIgnoring || isConfirming}
          activeOpacity={0.85}
        >
          <X size={16} color={colors.textSecondary} />
          <Text style={styles.ignoreButtonText}>
            {isIgnoring ? '忽略中…' : '忽略'}
          </Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

/** 单行元数据展示，避免内联样式重复。 */
function MetaRow({
  label,
  value,
  colors,
  highlight = false,
}: {
  label: string;
  value: string;
  colors: ThemeColors;
  highlight?: boolean;
}) {
  const styles = useStyles(createStyles);
  return (
    <View style={styles.metaRow}>
      <Text style={styles.metaLabel}>{label}</Text>
      <Text
        style={[
          styles.metaValue,
          highlight && { color: colors.error },
        ]}
        numberOfLines={2}
      >
        {value}
      </Text>
    </View>
  );
}

/**
 * 支付证据摘要区块（阶段四 T7）。
 * 只展示结构化摘要（来源、金额、订单号、状态），不展示通知原文。
 */
function NoSessionEvidenceState() {
  const styles = useStyles(createStyles);
  return (
    <View style={styles.evidenceSection}>
      <Text style={styles.evidenceTitle}>支付证据</Text>
      <Text style={styles.evidenceEmpty}>无关联结算会话，未查询支付证据。</Text>
    </View>
  );
}

function EvidenceSection({
  evidences,
  loading,
  error,
  onRetry,
  colors,
}: {
  evidences: PaymentEvidence[];
  loading: boolean;
  error: boolean;
  onRetry: () => Promise<unknown>;
  colors: ThemeColors;
}) {
  const styles = useStyles(createStyles);
  if (loading) {
    return (
      <View style={styles.evidenceSection}>
        <Text style={styles.evidenceTitle}>支付证据</Text>
        <Text style={styles.evidenceEmpty}>正在读取…</Text>
      </View>
    );
  }
  if (error) {
    return (
      <View style={styles.evidenceSection}>
        <Text style={styles.evidenceTitle}>支付证据</Text>
        <Text style={styles.evidenceEmpty}>支付证据读取失败。</Text>
        <TouchableOpacity
          style={styles.evidenceRetryButton}
          onPress={() => void onRetry()}
          activeOpacity={0.85}
        >
          <RefreshCw size={14} color={colors.textPrimary} />
          <Text style={styles.retryButtonText}>重试</Text>
        </TouchableOpacity>
      </View>
    );
  }
  if (evidences.length === 0) {
    return (
      <View style={styles.evidenceSection}>
        <Text style={styles.evidenceTitle}>支付证据</Text>
        <Text style={styles.evidenceEmpty}>
          暂无支付成功证据，等待支付通知或支付结果页确认。
        </Text>
      </View>
    );
  }
  return (
    <View style={styles.evidenceSection}>
      <View style={styles.evidenceHeader}>
        <Bell size={13} color={colors.primary} />
        <Text style={styles.evidenceTitle}>
          支付证据（{evidences.length} 条）
        </Text>
      </View>
      {evidences.map((evidence) => (
        <View key={evidence.evidenceId} style={styles.evidenceRow}>
          <View style={styles.evidenceBadge}>
            <Text style={styles.evidenceBadgeText}>
              {EVIDENCE_SOURCE_LABELS[evidence.sourceType] ??
                String(evidence.sourceType)}
            </Text>
          </View>
          <Text style={styles.evidenceAmount} numberOfLines={1}>
            {formatAmount(evidence.amountMinor ?? 0, 'expense')}
          </Text>
          {evidence.orderId ? (
            <Text style={styles.evidenceOrder} numberOfLines={1}>
              {shortenOrderId(evidence.orderId)}
            </Text>
          ) : (
            <Text style={styles.evidenceOrder}>无订单号</Text>
          )}
        </View>
      ))}
    </View>
  );
}

/** 冲突原因代码到中文说明的映射；未知代码原样展示供排查。 */
const CONFLICT_LABELS: Record<string, string> = {
  PAYMENT_AMOUNT_MISSING: '支付通知缺少金额，无法核对',
  AI_AMOUNT_MISSING: 'AI 识别缺少金额，无法核对',
  AI_AMOUNT_LOWER_THAN_PAYMENT: '实际支付金额高于 AI 识别金额',
  ORDER_ID_MISMATCH: 'AI 订单号与支付订单号不一致',
  TIME_WINDOW_MISMATCH: '支付时间与结算页时间差距过大',
  TIME_MISSING: '缺少时间信息，无法自动核对',
  PAYMENT_PACKAGE_UNSUPPORTED: '支付平台暂不支持',
  PAYMENT_EVIDENCE_AMBIGUOUS: '匹配到多条支付证据，需要人工选择',
  PAYMENT_EVIDENCE_MISSING: '缺少支付成功证据',
  ACCOUNT_UNAVAILABLE: '支付账户不存在或已停用，请手动选择账户',
  NO_ACTIVE_SESSION: '未关联结算页，请核对后确认',
  AI_RECOGNITION_FAILED: 'AI 识别失败，金额未知，请重试识别',
};

/** 冲突原因可能为逗号拼接的多个代码，逐个翻译后合并展示。 */
function translateConflictReason(reason?: string): string {
  if (!reason) return '';
  return reason
    .split(',')
    .map((code) => CONFLICT_LABELS[code.trim()] ?? code.trim())
    .filter(Boolean)
    .join('；');
}

/** 订单号只展示尾部 6 位，避免整串占满卡片。 */
function shortenOrderId(orderId: string): string {
  if (orderId.length <= 6) return orderId;
  return `…${orderId.slice(-6)}`;
}

const EVIDENCE_SOURCE_LABELS: Record<string, string> = {
  notification: '通知',
  accessibility: '结果页',
  manual: '手动',
};

const TYPE_LABELS: Record<string, string> = {
  income: '收入',
  expense: '支出',
  transfer: '转账',
  refund: '退款',
  repayment: '还款',
  fee: '手续费',
  adjustment: '不计收支',
};

const SOURCE_LABELS: Record<string, string> = {
  ai_vision: 'AI 视觉识别',
  notification: '支付通知',
  accessibility: '无障碍节点',
  sms: '银行短信',
  import: '账单导入',
  manual: '手动记账',
  ai_chat: 'AI 对话',
};

function formatAmount(amountMinor: number, type: string): string {
  const amount = Math.abs(amountMinor / 100).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  if (type === 'income') return `+¥${amount}`;
  if (type === 'refund') return `+¥${amount}`;
  return `-¥${amount}`;
}

function formatTime(value: string): string {
  if (!value) return '--';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('zh-CN', { hour12: false });
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
      padding: spacing.lg,
      backgroundColor: colors.surface,
      borderWidth: borderWidth.medium,
      borderColor: colors.stroke,
      borderRadius: borderRadius.card,
    },
    statusHeader: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
      marginBottom: spacing.md,
    },
    statusTitle: {
      fontSize: 15,
      fontWeight: '800',
      color: colors.textPrimary,
    },
    statusMetrics: {
      flexDirection: 'row',
      alignItems: 'center',
    },
    metricItem: {
      flex: 1,
      alignItems: 'center',
    },
    metricDivider: {
      width: borderWidth.thin,
      height: 32,
      backgroundColor: colors.divider,
    },
    metricValue: {
      fontSize: 22,
      fontWeight: '900',
      color: colors.textPrimary,
      fontFamily: 'Courier',
    },
    metricValueSmall: {
      fontSize: 15,
      fontWeight: '800',
    },
    metricIconRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 4,
    },
    metricLabel: {
      marginTop: 4,
      fontSize: 12,
      fontWeight: '600',
      color: colors.textSecondary,
    },
    emptyCard: {
      padding: spacing.xl,
      alignItems: 'center',
      backgroundColor: colors.surface,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      borderRadius: borderRadius.card,
    },
    emptyTitle: {
      marginTop: spacing.md,
      fontSize: 16,
      fontWeight: '800',
      color: colors.textPrimary,
    },
    emptyText: {
      marginTop: spacing.xs,
      fontSize: 13,
      fontWeight: '500',
      color: colors.textSecondary,
      textAlign: 'center',
      lineHeight: 20,
    },
    list: { gap: spacing.md },
    pendingCard: {
      padding: spacing.lg,
      backgroundColor: colors.surface,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      borderRadius: borderRadius.card,
    },
    pendingHeader: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      marginBottom: spacing.sm,
    },
    pendingAmountRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
    },
    pendingAmount: {
      fontSize: 22,
      fontWeight: '900',
      color: colors.textPrimary,
      fontFamily: 'Courier',
    },
    pendingTime: {
      fontSize: 12,
      fontWeight: '600',
      color: colors.textSecondary,
    },
    typeBadge: {
      paddingHorizontal: spacing.xs,
      paddingVertical: 2,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
    },
    typeBadgeText: {
      fontSize: 11,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    pendingMeta: {
      paddingVertical: spacing.sm,
      borderTopWidth: StyleSheet.hairlineWidth,
      borderTopColor: colors.divider,
      borderBottomWidth: StyleSheet.hairlineWidth,
      borderBottomColor: colors.divider,
      gap: spacing.xs,
    },
    evidenceSection: {
      marginTop: spacing.sm,
      padding: spacing.sm,
      backgroundColor: colors.background,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      borderRadius: borderRadius.medium,
      gap: spacing.xs,
    },
    evidenceHeader: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 4,
    },
    evidenceTitle: {
      fontSize: 12,
      fontWeight: '800',
      color: colors.textSecondary,
    },
    evidenceEmpty: {
      fontSize: 12,
      fontWeight: '500',
      color: colors.textTertiary,
      lineHeight: 18,
    },
    errorButton: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'center',
      gap: 4,
      marginTop: spacing.sm,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.sm,
      borderRadius: borderRadius.medium,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: colors.surface,
    },
    errorButtonText: {
      fontSize: 13,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    evidenceRetryButton: {
      alignSelf: 'flex-start',
      flexDirection: 'row',
      alignItems: 'center',
      gap: 4,
      paddingHorizontal: spacing.sm,
      paddingVertical: spacing.xs,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: colors.surface,
    },
    evidenceRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
    },
    evidenceBadge: {
      paddingHorizontal: 6,
      paddingVertical: 2,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: colors.primaryLight,
    },
    evidenceBadgeText: {
      fontSize: 10,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    evidenceAmount: {
      fontSize: 12,
      fontWeight: '800',
      color: colors.textPrimary,
      fontFamily: 'Courier',
    },
    evidenceOrder: {
      flex: 1,
      fontSize: 11,
      fontWeight: '500',
      color: colors.textSecondary,
      textAlign: 'right',
    },
    metaRow: {
      flexDirection: 'row',
      alignItems: 'flex-start',
      gap: spacing.sm,
    },
    metaLabel: {
      width: 56,
      fontSize: 12,
      fontWeight: '600',
      color: colors.textSecondary,
    },
    metaValue: {
      flex: 1,
      fontSize: 13,
      fontWeight: '600',
      color: colors.textPrimary,
    },
    actionRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
      marginTop: spacing.md,
    },
    confirmButton: {
      flex: 1,
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'center',
      gap: 4,
      paddingVertical: spacing.sm,
      borderRadius: borderRadius.medium,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: colors.primary,
    },
    confirmButtonText: {
      fontSize: 13,
      fontWeight: '800',
      color: '#FFFFFF',
    },
    retryButton: {
      flex: 1,
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'center',
      gap: 4,
      paddingVertical: spacing.sm,
      borderRadius: borderRadius.medium,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: colors.surface,
    },
    retryButtonText: {
      fontSize: 13,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    ignoreButton: {
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.sm,
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'center',
      gap: 4,
      borderRadius: borderRadius.medium,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: colors.surface,
    },
    ignoreButtonText: {
      fontSize: 13,
      fontWeight: '700',
      color: colors.textSecondary,
    },
    actionButtonDisabled: {
      opacity: 0.5,
    },
  }),
  _colors: colors,
});

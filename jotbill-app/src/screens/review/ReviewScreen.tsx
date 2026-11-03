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
  TextInput,
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
  Copy,
  Inbox,
  Loader2,
  Pencil,
  RefreshCw,
  Sparkles,
  X,
} from 'lucide-react-native';
import {
  nativeData,
  nativeRecognition,
  type Account,
  type Bill,
  type PendingConfirmationInput,
  type PendingTransaction,
  type PaymentEvidence,
  type RecognitionStatus,
  type TransactionType,
} from '../../native';
import { invalidateCache, QUERY_KEYS } from '../../lib/queryClient';
import { formatPlatform } from '../../utils/platform';
import { useAlert } from '../../providers';
import { borderRadius, borderWidth, spacing } from '../../theme/spacing';
import { ThemeColors } from '../../theme/colors';
import { useStyles } from '../../hooks';
import { SafeAreaView } from 'react-native-safe-area-context';
import { typography } from '../../theme';

const PENDING_QUERY_KEY = QUERY_KEYS.recognition.pending;
const STATUS_QUERY_KEY = QUERY_KEYS.recognition.status;
const EVIDENCE_QUERY_KEY = QUERY_KEYS.recognition.evidence;
/** 与 `PendingEditPanel` 共用同一份缓存，避免每张卡片各发一次桥接调用。 */
const ACCOUNTS_QUERY_KEY = ['accounts', 'list'] as const;

/**
 * "重试识别"按钮是否可用（单一出处，UI 渲染与点击回调共用）。
 *
 * 判据：只有"结算页 AI 识别失败"的占位候选才可能重试 —— 其 `conflictReason`
 * 固定为 `AI_RECOGNITION_FAILED` 且金额为 0（见 `CheckoutPaymentMatcher`
 * 的 AI 失败占位分支）。其余候选（孤儿待确认、正常证据待确认）要么没有候选，
 * 要么重试也没有可用的结算页上下文。
 *
 * 精简模式停用结算页截图识别链路后，`retryAiRecognition` 的 `USER_RETRY` 通道
 * 拿不到手动补截上下文，必然失败 ⇒ 按钮隐藏而非置灰。
 */
function isRetryRecognitionAvailable(pending: PendingTransaction): boolean {
  return (
    pending.conflictReason === 'AI_RECOGNITION_FAILED' &&
    pending.amountMinor === 0 &&
    Boolean(pending.candidateId)
  );
}

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
    mutationFn: ({
      pending,
      input,
    }: {
      pending: PendingTransaction;
      input: PendingConfirmationInput;
    }) => nativeData.confirmPendingTransaction(pending.pendingId, input, pending.version),
    onSuccess: (bill) => {
      invalidateAll();
      // P1-5（2026-10-24 场景审查）：合并路径（同来源同订单号已有账单）会丢弃
      // 用户本次的修改，原生在响应里带 mergedIntoExistingBill 标志 —— 必须如实
      // 提示，不能让用户以为"按我改的记上了"。
      const merged = (bill as { mergedIntoExistingBill?: boolean }).mergedIntoExistingBill;
      if (merged) {
        alert(
          '已并入既有账单',
          `该笔支付此前已记过账（¥${((bill.amountMinor ?? 0) / 100).toFixed(2)}），本次修改未生效。如需调整，请到账单列表修改那条账单。`,
        );
      }
    },
    onError: (error) => {
      // 不再回显裸 pendingId：那是内部 UUID，对用户零信息量，
      // 反而把真正的原因挤到后面。改为可照做的中文指引。
      alert('确认失败', describeConfirmError(error));
      // 版本冲突等情况下列表中的 version 已过期:重新拉取,避免用户在旧版本上反复提交
      invalidateAll();
    },
  });

  const ignoreMutation = useMutation({
    mutationFn: ({ pending }: { pending: PendingTransaction }) =>
      nativeData.ignorePendingTransaction(pending.pendingId, pending.version),
    onSuccess: () => {
      invalidateAll();
    },
    onError: (error) => {
      alert('忽略失败', describeConfirmError(error));
    },
  });

  const retryMutation = useMutation({
    mutationFn: ({ candidateId }: { candidateId: string }) =>
      nativeRecognition.retryAiRecognition(candidateId),
    onSuccess: () => {
      invalidateAll();
      setRetrySubmittedVersions({});
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
      // 转账卡缺源/目标账户时快捷确认必然失败（LedgerRepository.validateBillAccounts），
      // 与其让用户点了再看报错，不如直接引导到编辑面板（2026-10-24 场景审查）。
      if (pending.type === 'transfer') {
        alert('转账需选择账户', '请点「编辑」选择付款账户与转入账户后再确认。');
        return;
      }
      confirmMutation.mutate({ pending, input: {} });
    },
    [alert, confirmMutation],
  );

  /** 编辑后确认：类型/金额/账户/目标账户/原交易经 input 通道提交，不改方法签名。 */
  const handleConfirmWithInput = useCallback(
    (pending: PendingTransaction, input: PendingConfirmationInput) => {
      confirmMutation.mutate({ pending, input });
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
      // 精简模式：结算页截图识别链路停用后，重试通道必然失败（见 PendingCard 的
      // canRetryRecognition 注释）。按钮已隐藏，这里是纵深防御 —— 避免将来有别处
      // 复用本回调时又暴露出一个点不动的入口。
      if (!isRetryRecognitionAvailable(pending)) {
        alert(
          '无法重试',
          '结算页截图识别已停用，无法重新识别。请直接修改金额或分类后确认入账。',
        );
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
                onConfirmWithInput={handleConfirmWithInput}
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
            {/* 分页截断提示:接口一次最多取 50 条,超出部分此前被静默丢弃 */}
            {(pendingQuery.data?.total ?? 0) > pendingItems.length && (
              <Text style={styles.truncatedHint}>
                仅显示前 {pendingItems.length} 条，共 {pendingQuery.data?.total} 条待确认；
                处理当前条目后其余会陆续显示。
              </Text>
            )}
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

/** 单条待确认交易卡片，展示候选字段并触发确认/忽略/重试/编辑。 */
interface PendingCardProps {
  pending: PendingTransaction;
  onConfirm: (pending: PendingTransaction) => void;
  onConfirmWithInput: (
    pending: PendingTransaction,
    input: PendingConfirmationInput,
  ) => void;
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
  onConfirmWithInput,
  onIgnore,
  onRetry,
  isConfirming,
  isIgnoring,
  isRetrying,
  retryBlocked,
  colors,
}: PendingCardProps) {
  const [editing, setEditing] = useState(false);
  const styles = useStyles(createStyles);

  /**
   * 账户名解析：把 `pending.accountId` 翻成人看得懂的名字。
   *
   * 与 `PendingEditPanel` 用同一个 query key（`['accounts','list']`），
   * 50 张卡片共享一份缓存 —— 不会因为卡片多而发出 N 次桥接调用。
   * `PendingEditPanel` 内部已经存在同名 key，这里只是复用它的缓存。
   */
  const accountsQuery = useQuery({
    queryKey: ACCOUNTS_QUERY_KEY,
    queryFn: () => nativeData.getAccounts(),
  });
  const accountName = useMemo(() => {
    if (!pending.accountId) return null;
    const accounts = (accountsQuery.data ?? []) as Account[];
    const hit = accounts.find((account: Account) => account.id === pending.accountId);
    // 查不到 = 账户被删或停用；与"从未指定"是两种情况，UI 要分开说
    return hit ? `${hit.icon ?? '💳'} ${hit.name}` : null;
  }, [accountsQuery.data, pending.accountId]);
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

  /**
   * "重试识别"是否可用。
   *
   * 该按钮走 `retryAiRecognition` → 无障碍 `USER_RETRY` 通道，而那条通道要求
   * 先在**购物 App 的结算页**建立手动补截上下文（`userRetryCaptureContext`）。
   * 精简模式已停用结算页截图识别链路，上下文不再建立 ⇒ 按钮点了必然失败
   * （`NO_CHECKOUT_CONTEXT` / `SERVICE_NOT_CONNECTED`），是一个功能性死按钮。
   *
   * 判据只用既有 `conflictReason` 字段，不新增桥接方法：
   * 能重试的只可能是"结算页 AI 识别失败"的占位候选，其 conflictReason 前缀固定为
   * `AI_RECOGNITION_FAILED`（见 `CheckoutPaymentMatcher` 的 AI 失败占位分支）；
   * 孤儿待确认（`NO_ACTIVE_SESSION` / `PAYMENT_RESULT_NO_MATCHING_SESSION` 等）
   * 压根没有候选，按钮本就不渲染。
   *
   * 隐藏而非置灰：置灰仍要解释"为什么不能点"，对用户是噪音；精简版下这个按钮
   * 不存在比"存在但点不动"更干净。
   */
  const canRetryRecognition = isRetryRecognitionAvailable(pending);

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
        {/* ⚠️ 必须显示会记到哪个账户。
            确认入账会立刻扣/加该账户余额，而此前卡片**只字不提账户**，
            用户点"确认入账"时并不知道钱从哪出、余额会变多少。
            `pending.accountId` 为空时更要点明：这条现在直接确认会失败
            （LedgerRepository.validateBillAccounts 硬性要求 accountId），
            必须先点「编辑」选账户 —— 让用户在点之前就知道，而不是点完吃一个
            "Transaction requires an account"。 */}
        {accountName ? (
          <MetaRow label="账户" value={accountName} colors={colors} />
        ) : pending.accountId ? (
          <MetaRow label="账户" value="已停用或已删除" colors={colors} highlight />
        ) : (
          <MetaRow
            label="账户"
            value="未指定 · 需点「编辑」选择后才能入账"
            colors={colors}
            highlight
          />
        )}
        {pending.platform ? (
          <MetaRow
            label="平台"
            value={formatPlatform(pending.platform)}
            colors={colors}
          />
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

      {editing ? (
        <PendingEditPanel
          pending={pending}
          submitting={isConfirming}
          onCancel={() => setEditing(false)}
          onSubmit={(input) => onConfirmWithInput(pending, input)}
        />
      ) : null}

      <View style={styles.actionRow}>
        <TouchableOpacity
          style={[styles.retryButton, editing && styles.actionButtonDisabled]}
          onPress={() => setEditing(current => !current)}
          disabled={isConfirming || isIgnoring}
          activeOpacity={0.85}
        >
          <Pencil size={16} color={colors.textPrimary} />
          <Text style={styles.retryButtonText}>
            {editing ? '收起编辑' : '编辑'}
          </Text>
        </TouchableOpacity>
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
        {pending.candidateId && canRetryRecognition ? (
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

/** 待确认编辑面板支持修改的类型（adjustment 不开放手动选择）。 */
const EDITABLE_TYPE_LABELS: Array<{ value: TransactionType; label: string }> = [
  { value: 'expense', label: '支出' },
  { value: 'income', label: '收入' },
  { value: 'transfer', label: '转账' },
  { value: 'repayment', label: '还款' },
  { value: 'refund', label: '退款' },
  { value: 'fee', label: '手续费' },
];

/**
 * 待确认编辑面板（阶段五 T7）。
 *
 * - 类型/金额/账户/目标账户/退款原交易的修正经 confirmPendingTransaction
 *   的 input 通道提交，不改方法签名；
 * - 转账/还款校验目标账户必填且源≠目标，还款目标限定负债账户；
 * - 退款原交易选择复用 getBills() 服务端查询（关键词过滤），不做本地全文搜索；
 * - "保存为规则"（FR-051）只把账户修正映射为单条规则草稿，
 *   用户确认后才 addRule 落库，不静默创建。
 */
interface PendingEditPanelProps {
  pending: PendingTransaction;
  submitting: boolean;
  onCancel: () => void;
  onSubmit: (input: PendingConfirmationInput) => void;
}

function PendingEditPanel({ pending, submitting, onCancel, onSubmit }: PendingEditPanelProps) {
  const styles = useStyles(createStyles);
  const { alert, confirm: confirmDialog } = useAlert();
  const queryClient = useQueryClient();
  const [type, setType] = useState<TransactionType>(pending.type);
  const [amountText, setAmountText] = useState(
    pending.amountMinor > 0 ? (pending.amountMinor / 100).toFixed(2) : '',
  );
  const [accountId, setAccountId] = useState<string | undefined>(pending.accountId);
  const [targetAccountId, setTargetAccountId] = useState<string | undefined>(undefined);
  const [originalBillId, setOriginalBillId] = useState<string | undefined>(undefined);
  const [refundKeyword, setRefundKeyword] = useState('');

  const accountsQuery = useQuery({
    queryKey: ACCOUNTS_QUERY_KEY,
    queryFn: () => nativeData.getAccounts(),
  });
  const activeAccounts: Account[] = useMemo(
    () => (accountsQuery.data ?? []).filter((account: Account) => account.isActive),
    [accountsQuery.data],
  );

  /** 退款原交易候选：复用 getBills 服务端关键词查询，不做本地全文搜索。 */
  const refundBillsQuery = useQuery({
    queryKey: ['bills', 'refund-origin', refundKeyword],
    queryFn: () =>
      nativeData.getBills({
        type: 'expense',
        keyword: refundKeyword.trim() || undefined,
        limit: 20,
        orderBy: 'date',
        orderDirection: 'desc',
      }),
    enabled: type === 'refund',
  });

  const isTransferLike = type === 'transfer' || type === 'repayment';
  const targetAccounts = useMemo(
    () =>
      isTransferLike
        ? activeAccounts.filter((account: Account) =>
            type === 'repayment' ? account.kind === 'liability' : true,
          )
        : [],
    [activeAccounts, isTransferLike, type],
  );

  /** 账户修正相对原值的差异，作为"保存为规则"草稿的依据。 */
  const accountChanged =
    Boolean(accountId) && accountId !== pending.accountId;
  const selectedAccount = activeAccounts.find((account: Account) => account.id === accountId);
  const originalBill = (refundBillsQuery.data?.items ?? []).find(
    (bill: Bill) => bill.id === originalBillId,
  );

  /** FR-051：把账户修正映射为规则草稿，用户确认后落库（不静默创建）。 */
  const handleCreateRule = () => {
    if (!accountId) return;
    const useMerchant = Boolean(pending.merchant?.trim());
    const ruleCondition = useMerchant ? 'merchant_contains' : 'platform_equals';
    const ruleConditionValue = useMerchant
      ? (pending.merchant as string)
      : (pending.platform ?? '');
    if (!ruleConditionValue) {
      alert('无法创建规则', '该待确认缺少商户与平台信息，无法生成规则条件。');
      return;
    }
    const ruleName = `${useMerchant ? pending.merchant : pending.platform} → ${
      selectedAccount?.name ?? accountId
    }`;
    confirmDialog(
      '保存为规则',
      `将创建规则：当${useMerchant ? '商户包含' : '平台等于'}“${ruleConditionValue}”时，账户设为“${
        selectedAccount?.name ?? accountId
      }”。规则会对未来交易生效，是否创建？`,
      async () => {
        try {
          await nativeData.addRule({
            name: ruleName,
            condition: ruleCondition,
            conditionValue: ruleConditionValue,
            action: 'set_account',
            actionValue: accountId,
            priority: 100,
          });
          queryClient.invalidateQueries({ queryKey: ['rules'] });
          alert('规则已创建', '后续匹配交易将自动应用该账户。');
        } catch (error) {
          const message = error instanceof Error ? error.message : '创建规则失败';
          alert('创建规则失败', message);
        }
      },
    );
  };

  /** 提交前校验：金额、双账户约束、还款负债约束。 */
  const handleSubmit = () => {
    const amountMinor = Math.round((parseFloat(amountText) || 0) * 100);
    if (!amountText.trim() || amountMinor <= 0) {
      alert('表单不完整', '请填写有效金额。');
      return;
    }
    if (isTransferLike) {
      if (!accountId) {
        alert('表单不完整', '请选择源账户。');
        return;
      }
      if (!targetAccountId) {
        alert('表单不完整', '请选择目标账户。');
        return;
      }
      if (accountId === targetAccountId) {
        alert('表单有误', '源账户与目标账户不能相同。');
        return;
      }
      if (type === 'repayment') {
        const target = activeAccounts.find((account: Account) => account.id === targetAccountId);
        if (target && target.kind !== 'liability') {
          alert('表单有误', '还款的目标账户必须是负债账户（信用卡/花呗等）。');
          return;
        }
      }
    }
    onSubmit({
      type,
      amountMinor,
      ...(accountId ? { accountId } : {}),
      ...(isTransferLike && targetAccountId ? { targetAccountId } : {}),
      ...(type === 'refund' && originalBillId
        ? {
            originalTransactionId: originalBillId,
            description: originalBill
              ? `退款原交易：${originalBill.description ?? originalBill.merchant ?? originalBillId}`
              : undefined,
          }
        : {}),
    });
  };

  return (
    <View style={styles.editPanel}>
      <Text style={styles.editTitle}>修正交易信息</Text>

      <Text style={styles.editLabel}>类型</Text>
      <View style={styles.chipGrid}>
        {EDITABLE_TYPE_LABELS.map(option => (
          <TouchableOpacity
            key={option.value}
            style={[styles.chip, type === option.value && styles.chipSelected]}
            onPress={() => {
              setType(option.value);
              setTargetAccountId(undefined);
            }}
          >
            <Text style={styles.chipText}>{option.label}</Text>
          </TouchableOpacity>
        ))}
      </View>

      <Text style={styles.editLabel}>金额（元）</Text>
      <TextInput
        style={styles.editInput}
        value={amountText}
        onChangeText={setAmountText}
        keyboardType="numeric"
        placeholder="0.00"
        placeholderTextColor={styles._colors.textTertiary}
      />

      {activeAccounts.length > 0 ? (
        <>
          <Text style={styles.editLabel}>{isTransferLike ? '源账户' : '账户'}</Text>
          <View style={styles.chipGrid}>
            {activeAccounts.map((account: Account) => (
              <TouchableOpacity
                key={account.id}
                style={[styles.chip, accountId === account.id && styles.chipSelected]}
                onPress={() => setAccountId(account.id)}
              >
                <Text style={styles.chipText}>{account.name}</Text>
              </TouchableOpacity>
            ))}
          </View>
        </>
      ) : null}

      {isTransferLike && targetAccounts.length > 0 ? (
        <>
          <Text style={styles.editLabel}>
            目标账户{type === 'repayment' ? '（限负债账户）' : ''}
          </Text>
          <View style={styles.chipGrid}>
            {targetAccounts.map((account: Account) => (
              <TouchableOpacity
                key={account.id}
                style={[styles.chip, targetAccountId === account.id && styles.chipSelected]}
                onPress={() => setTargetAccountId(account.id)}
              >
                <Text style={styles.chipText}>{account.name}</Text>
              </TouchableOpacity>
            ))}
          </View>
        </>
      ) : null}

      {type === 'refund' ? (
        <>
          <Text style={styles.editLabel}>原交易（可选，输入关键词查询）</Text>
          <TextInput
            style={styles.editInput}
            value={refundKeyword}
            onChangeText={setRefundKeyword}
            placeholder="商户 / 备注 / 商品关键词"
            placeholderTextColor={styles._colors.textTertiary}
          />
          {(refundBillsQuery.data?.items ?? []).map((bill: Bill) => (
            <TouchableOpacity
              key={bill.id}
              style={[
                styles.refundBillRow,
                originalBillId === bill.id && styles.chipSelected,
              ]}
              onPress={() => setOriginalBillId(bill.id)}
            >
              <Text style={styles.refundBillAmount}>
                ¥{(bill.amountMinor / 100).toFixed(2)}
              </Text>
              <Text style={styles.refundBillText} numberOfLines={1}>
                {bill.description ?? bill.merchant ?? bill.orderId ?? bill.id}
              </Text>
              <Text style={styles.refundBillTime}>{formatTime(bill.occurredAt)}</Text>
            </TouchableOpacity>
          ))}
          {refundBillsQuery.isLoading ? (
            <Text style={styles.editHint}>正在查询历史账单…</Text>
          ) : (refundBillsQuery.data?.items ?? []).length === 0 ? (
            <Text style={styles.editHint}>未找到匹配的历史支出账单。</Text>
          ) : null}
        </>
      ) : null}

      {accountChanged ? (
        <TouchableOpacity
          style={styles.createRuleButton}
          onPress={handleCreateRule}
          activeOpacity={0.85}
        >
          <Copy size={14} color={styles._colors.primary} />
          <Text style={styles.createRuleText}>
            保存为规则：{selectedAccount?.name ?? '该账户'}
          </Text>
        </TouchableOpacity>
      ) : null}

      <View style={styles.editActions}>
        <TouchableOpacity style={styles.editCancelButton} onPress={onCancel} activeOpacity={0.85}>
          <Text style={styles.editCancelButtonText}>取消</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.editConfirmButton, submitting && styles.actionButtonDisabled]}
          onPress={handleSubmit}
          disabled={submitting}
          activeOpacity={0.85}
        >
          <Text style={styles.editConfirmButtonText}>
            {submitting ? '确认中…' : '按修正确认入账'}
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
  // D-19（2026-09-16）新增方向：AI 金额高于支付金额且超出容差带 max(¥1.00, 5%)。
  AI_AMOUNT_HIGHER_THAN_PAYMENT: 'AI 识别金额高于实际支付金额，请核对是否识别有误',
  ORDER_ID_MISMATCH: 'AI 订单号与支付订单号不一致',
  TIME_WINDOW_MISMATCH: '支付时间与结算页时间差距过大',
  TIME_MISSING: '缺少时间信息，无法自动核对',
  PAYMENT_PACKAGE_UNSUPPORTED: '支付平台暂不支持',
  PAYMENT_EVIDENCE_AMBIGUOUS: '匹配到多条支付证据，需要人工选择',
  PAYMENT_EVIDENCE_MISSING: '缺少支付成功证据',
  ACCOUNT_UNAVAILABLE: '支付账户不存在或已停用，请手动选择账户',
  NO_ACTIVE_SESSION: '未关联结算页，请核对后确认',
  // 批 V-4：改造 A-3 接线后，协调器归属出口也会落孤儿待确认。这三条与上面的
  // 服务层码可能按逗号合并展示（见 translateConflictReason）。
  PAYMENT_RESULT_NO_MATCHING_SESSION: '未找到对应的结算页会话，请核对后确认',
  PAYMENT_PROCESS_SNAPSHOT_MISSING: '未找到结算页快照，无法自动记账，请核对后确认',
  AI_RECOGNITION_FAILED: 'AI 识别失败，金额未知，请重试识别',
  // 阶段五 T2/T3/T4/T6 新增冲突码
  ACCOUNT_UNRESOLVED: '账户无法自动判定，请手动选择账户与目标账户',
  RULE_FORCED_REVIEW: '命中转待确认规则，需人工确认',
  SUSPECTED_DUPLICATE: '疑似重复交易（同金额/时间/账户/商户相近），请核对后再确认',
  DEDUP_CHECK_FAILED: '去重检查异常，请人工核对是否重复',
  FEE_AMOUNT_UNPARSED: '手续费金额解析失败，请补填金额',
  AI_UNVERIFIED_PAYMENT: '支付结果由截图识别推断，未获得支付凭证，请确认是否已实际支付',
  USER_ASSERTED: '手动截图识别，请核对金额与商户后确认',
  USER_ASSERTED_AMOUNT_MISSING: '手动截图未识别到金额，请补填后确认',
  // N-1：通知来源的证据已解析成功，但这一版不替用户自动入账（自动入账等 N-2 放开）。
  AUTO_COMMIT_DEFERRED: '支付通知已识别，请核对后确认入账',
};

/** 冲突原因可能为逗号拼接的多个代码，逐个翻译后合并展示。 */
function translateConflictReason(reason?: string): string {
  if (!reason) return '';
  return reason
    .split(',')
    .map((code) => code.trim().startsWith('AMBIGUOUS_SESSION_IDS')
      ? '同时命中多个结算会话，请选择要入账的记录'
      : CONFLICT_LABELS[code.trim()] ?? code.trim())
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

/**
 * 把原生的校验错误翻成用户能照做的中文。
 *
 * ⚠️ 此前 `alert('确认失败', \`${pending.pendingId}：${message}\`)` 直接把
 * 原生英文异常和**裸 pendingId（UUID）**甩给用户，实际长这样：
 *
 *     确认失败
 *     3f8a1c92-7d4e-4b6a-9e21-0c5f8a2b7d13：Transaction requires an account
 *
 * 用户既看不懂英文，也完全不知道那个 UUID 是什么，更不知道下一步该做什么。
 * 而这条路径是**主功能**（弹窗点"稍后"后的兜底入口）。
 *
 * 覆盖的都是 `PendingConfirmService` / `LedgerRepository` / `BridgeDtoMapper`
 * 里真实会抛出的串。匹配不到就原样返回（不吞信息），但至少去掉 UUID 前缀。
 */
function describeConfirmError(error: unknown): string {
  const raw = error instanceof Error ? error.message : String(error ?? '');
  const rules: Array<[RegExp, string]> = [
    [
      /Transaction requires an account/i,
      '这条待确认没有账户，无法入账。点卡片上的「编辑」选一个账户，或先到「通用配置 → 账户管理」启用账户。',
    ],
    [/Account .* does not exist/i, '这条待确认记录的账户已不存在，请点「编辑」重新选择账户。'],
    [/Account .* is inactive/i, '这条待确认记录的账户已停用，请点「编辑」改选一个启用中的账户。'],
    [
      /Duplicate bill has changed during merge/i,
      '这条账单刚刚被其他入口处理过，请下拉刷新查看最新列表。',
    ],
    [
      /amountMinor must be a positive safe integer minor unit/i,
      '金额无效，请点「编辑」重新填写一个大于 0 的金额。',
    ],
    [/Bill version/i, '这条待确认已被更新，请下拉刷新后重试。'],
    [/INVALID_TYPE|invalid bill type/i, '账单类型无效，请点「编辑」重新选择。'],
    [/INVALID_SOURCE_TYPE/i, '来源类型无效，请下拉刷新后重试。'],
    // NOT_FOUND 原文含内部 UUID（"Pending transaction <uuid> was not found"），
    // 直出零信息量：通常是这条卡已被其他入口（弹窗/另一台设备）处理掉。
    [/was not found/i, '这条待确认已被处理或已不存在，请下拉刷新。'],
    // 服务端对悬空分类的拦截（PendingConfirmService）。编辑面板没有分类选择器，
    // 提示必须指向真实可行的出路，不能让用户去找不存在的控件。
    [
      /分类已被删除/i,
      '这条待确认的分类已被删除：付款确认弹窗中可直接重选分类；在待确认中心可忽略此卡，或到「规则」页为对应平台设置默认分类。',
    ],
  ];
  for (const [pattern, message] of rules) {
    if (pattern.test(raw)) return message;
  }
  // 兜底：非空就透传（可能含具体 ID 信息），空则给通用文案
  const cleaned = raw.trim();
  return cleaned ? cleaned : '操作失败，请稍后重试';
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
    truncatedHint: {
      fontSize: 12,
      color: colors.textSecondary,
      textAlign: 'center',
      paddingVertical: spacing.sm,
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
      fontFamily: typography.fontFamily.mono,
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
      fontFamily: typography.fontFamily.mono,
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
      fontFamily: typography.fontFamily.mono,
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
    // ---- 阶段五 T7：待确认编辑面板样式 ----
    editPanel: {
      marginTop: spacing.sm,
      padding: spacing.md,
      backgroundColor: colors.background,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      borderRadius: borderRadius.medium,
      gap: spacing.xs,
    },
    editTitle: {
      fontSize: 13,
      fontWeight: '800',
      color: colors.textPrimary,
      marginBottom: spacing.xs,
    },
    editLabel: {
      fontSize: 12,
      fontWeight: '700',
      color: colors.textSecondary,
      marginTop: spacing.xs,
    },
    chipGrid: {
      flexDirection: 'row',
      flexWrap: 'wrap',
      gap: spacing.sm,
    },
    chip: {
      paddingHorizontal: spacing.sm,
      paddingVertical: spacing.xs,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: colors.surface,
    },
    chipSelected: {
      backgroundColor: colors.accent,
      borderWidth: borderWidth.medium,
    },
    chipText: {
      fontSize: 12,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    editInput: {
      backgroundColor: colors.surface,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      borderRadius: borderRadius.medium,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.sm,
      fontSize: 14,
      fontWeight: '600',
      color: colors.textPrimary,
    },
    editHint: {
      fontSize: 12,
      fontWeight: '500',
      color: colors.textTertiary,
    },
    refundBillRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
      padding: spacing.sm,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      borderRadius: borderRadius.small,
      backgroundColor: colors.surface,
    },
    refundBillAmount: {
      fontSize: 12,
      fontWeight: '800',
      color: colors.textPrimary,
      fontFamily: typography.fontFamily.mono,
    },
    refundBillText: {
      flex: 1,
      fontSize: 12,
      fontWeight: '600',
      color: colors.textPrimary,
    },
    refundBillTime: {
      fontSize: 10,
      fontWeight: '500',
      color: colors.textTertiary,
    },
    createRuleButton: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 4,
      marginTop: spacing.xs,
      paddingHorizontal: spacing.sm,
      paddingVertical: spacing.xs,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.primary,
      backgroundColor: colors.surface,
      alignSelf: 'flex-start',
    },
    createRuleText: {
      fontSize: 12,
      fontWeight: '700',
      color: colors.primary,
    },
    editActions: {
      flexDirection: 'row',
      gap: spacing.sm,
      marginTop: spacing.sm,
    },
    editCancelButton: {
      flex: 1,
      alignItems: 'center',
      justifyContent: 'center',
      paddingVertical: spacing.sm,
      borderRadius: borderRadius.medium,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: colors.surface,
    },
    editCancelButtonText: {
      fontSize: 13,
      fontWeight: '700',
      color: colors.textSecondary,
    },
    editConfirmButton: {
      flex: 1.5,
      alignItems: 'center',
      justifyContent: 'center',
      paddingVertical: spacing.sm,
      borderRadius: borderRadius.medium,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: colors.primary,
    },
    editConfirmButtonText: {
      fontSize: 13,
      fontWeight: '800',
      color: '#FFFFFF',
    },
  }),
  _colors: colors,
});

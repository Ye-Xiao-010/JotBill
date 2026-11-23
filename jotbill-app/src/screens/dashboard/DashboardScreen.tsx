/**
 * Dashboard 首页 - 轻量化 Neo-Brutalism 风格
 * 保留暖白、积木色块与偏移阴影，集中强调本月支出和近期交易。
 * 近期交易按天分组展示；预算与目标卡片默认隐藏，由「设置 → 显示」开关控制。
 */
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  AppState,
  Platform,
  View,
  Text,
  ScrollView,
  RefreshControl,
  StyleSheet,
  TouchableOpacity,
  Dimensions,
} from 'react-native';
import { useFocusEffect, useIsFocused, useNavigation } from '@react-navigation/native';
import { useQuery } from '@tanstack/react-query';
import {
  Bell,
  ChevronRight,
  Download,
  Eye,
  EyeOff,
  Inbox,
  PieChart as PieChartIcon,
  Search,
  Sparkles,
  TriangleAlert,
  X,
  Zap,
} from 'lucide-react-native';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import {
  borderRadius,
  borderWidth,
  spacing,
  shadow,
} from '../../theme/spacing';
import { ProgressBar, QueryErrorBanner, SoftGradient } from '../../components/ui';
import { DashboardSkeleton } from '../../components/skeleton';
import CategoryIcon from '../../components/business/CategoryIcon';
import { useDashboard, useStyles } from '../../hooks';
import { useAlert, useDisplayPreferences, useTheme } from '../../providers';
import { nativeData, nativeRecognition, nativeRuntime } from '../../native';
import type { RecognitionPermission } from '../../native/runtime';
import { formatPlatform } from '../../utils/platform';
import { formatCurrency, formatSignedCurrency, formatTime } from '../../utils/format';
import { getCategoryIcon, getCategoryColor, getBudgetStatusColor } from '../../constants/app';
import { groupBillsByDay, limitBillDayGroups, type BillDayGroup } from '../../utils/billGroups';
import { CACHE_TIME, GC_TIME, QUERY_KEYS } from '../../lib/queryClient';
import type {
  Bill,
  BudgetProgress,
  Category,
  FinancialGoal,
} from '../../native';

const { width: SCREEN_WIDTH } = Dimensions.get('window');
const PROGRESS_CARD_WIDTH = SCREEN_WIDTH - spacing.lg * 2;

interface ProgressCardItem {
  id: string;
  type: 'budget' | 'goal';
  title: string;
  icon: string;
  progress: number;
  currentAmount: number;
  totalAmount: number;
  color: string;
  labelLeft: string;
  labelRight: string;
  percent?: number;
  periodLabel?: string;
}

interface GoalProgress extends FinancialGoal {
  progress: number;
  isCompleted: boolean;
}

const WEEKDAY_LABELS = [
  '周日',
  '周一',
  '周二',
  '周三',
  '周四',
  '周五',
  '周六',
] as const;

interface DayLabel {
  badge: string;
  dateText: string;
  isToday: boolean;
  isYesterday: boolean;
}

function startOfLocalDayTimestamp(date: Date): number {
  return new Date(
    date.getFullYear(),
    date.getMonth(),
    date.getDate(),
  ).getTime();
}

/** 今天 / 昨天 / 周几；跨年时补出年份，避免旧账被误读成今年 */
function getDayLabel(date: Date | null): DayLabel {
  if (!date) {
    return {
      badge: '—',
      dateText: '日期未知',
      isToday: false,
      isYesterday: false,
    };
  }

  const now = new Date();
  const dayDiff = Math.round(
    (startOfLocalDayTimestamp(now) - startOfLocalDayTimestamp(date)) / 86400000,
  );
  const dateText =
    date.getFullYear() === now.getFullYear()
      ? `${date.getMonth() + 1}月${date.getDate()}日`
      : `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日`;

  if (dayDiff === 0) {
    return { badge: '今天', dateText, isToday: true, isYesterday: false };
  }
  if (dayDiff === 1) {
    return { badge: '昨天', dateText, isToday: false, isYesterday: true };
  }
  return {
    badge: WEEKDAY_LABELS[date.getDay()],
    dateText,
    isToday: false,
    isYesterday: false,
  };
}

/** 单个日组：日期标头（含当日收支小计）+ 当天交易卡片 */
function DayTradeGroup({
  group,
  categoryById,
  styles,
  isSoft,
  isDark,
  colors,
  onPressBill,
}: {
  group: BillDayGroup;
  categoryById: Map<string, Category>;
  styles: any;
  isSoft: boolean;
  isDark: boolean;
  colors: ThemeColors;
  onPressBill: (bill: Bill) => void;
}) {
  const label = getDayLabel(group.date);
  const summaryLabel = [
    group.date
      ? label.isToday
        ? '今天'
        : label.isYesterday
          ? '昨天'
          : label.badge
      : '日期未知',
    group.date ? label.dateText : '',
    group.expenseMinor > 0
      ? `支出 ${formatCurrency(group.expenseMinor / 100)}`
      : '',
    group.incomeMinor > 0
      ? `收入 ${formatCurrency(group.incomeMinor / 100)}`
      : '',
  ]
    .filter(Boolean)
    .join('，');

  return (
    <View style={styles.dayGroup}>
      <View
        style={styles.dayHeader}
        accessible
        accessibilityRole="header"
        accessibilityLabel={summaryLabel}
      >
        <View style={styles.dayHeaderLeft}>
          <View
            style={[
              styles.dayBadge,
              label.isToday && styles.dayBadgeToday,
              label.isYesterday && styles.dayBadgeYesterday,
            ]}
          >
            <Text
              style={[
                styles.dayBadgeText,
                isSoft && label.isToday && styles.dayBadgeTodayText,
              ]}
            >
              {label.badge}
            </Text>
          </View>
          <Text style={styles.dayDate} numberOfLines={1}>
            {label.dateText}
          </Text>
        </View>
        <View style={styles.dayTotals}>
          {group.expenseMinor > 0 ? (
            <Text style={styles.dayExpenseTotal} numberOfLines={1}>
              支 {formatCurrency(group.expenseMinor / 100)}
            </Text>
          ) : null}
          {group.incomeMinor > 0 ? (
            <Text style={styles.dayIncomeTotal} numberOfLines={1}>
              收 {formatCurrency(group.incomeMinor / 100)}
            </Text>
          ) : null}
        </View>
      </View>

      <View style={styles.dayCard}>
        {group.bills.map((bill, index) => {
          const category = bill.categoryId
            ? categoryById.get(bill.categoryId)
            : undefined;
          const categoryName =
            category?.name ||
            bill.merchant ||
            bill.productDescription ||
            formatPlatform(bill.platform) ||
            '未分类';
          const categoryIcon =
            category?.icon || getCategoryIcon(category?.name);
          const isIncome =
            bill.type === 'income' || bill.type === 'refund';
          const description =
            bill.description ||
            bill.merchant ||
            bill.productDescription ||
            categoryName;
          // 时间已在日组内，元信息只保留「时间 · 平台」；
          // 平台缺失且分类与标题不同时补分类，避免出现「正餐 / 03:23 · 正餐」这类重复。
          const time = formatTime(bill.occurredAt);
          const timeLabel = time && time !== '00:00' ? time : '';
          const platformLabel = formatPlatform(bill.platform);
          const metaParts = [timeLabel, platformLabel];
          if (!platformLabel && categoryName !== description) {
            metaParts.push(categoryName);
          }
          const meta = metaParts.filter(Boolean).join(' · ');

          const isAuto = bill.sourceType !== 'manual';
          const rawCategoryColor = category?.color || getCategoryColor(category?.name, '#64748B');
          const softTileBg =
            /^#[0-9a-fA-F]{6}$/.test(rawCategoryColor)
              ? `${rawCategoryColor}${isDark ? '33' : '22'}`
              : colors.surfaceMuted;

          return (
            <TouchableOpacity
              key={bill.id}
              style={[
                styles.transactionItem,
                index > 0 && styles.transactionItemBorder,
              ]}
              onPress={() => onPressBill(bill)}
              activeOpacity={0.72}
              accessibilityLabel={`${description} ${formatSignedCurrency(
                bill.amountMinor / 100,
                isIncome,
              )}`}
              accessibilityRole="button"
            >
              <View style={styles.transactionLeft}>
                <View
                  style={[
                    styles.transactionIcon,
                    {
                      backgroundColor: isSoft ? softTileBg : getCategoryColor(category?.name),
                    },
                  ]}
                >
                  <CategoryIcon
                    icon={categoryIcon}
                    size={21}
                    emojiStyle={styles.transactionIconText}
                  />
                </View>
                <View style={styles.transactionInfo}>
                  <Text style={styles.transactionTitle} numberOfLines={1}>
                    {description}
                  </Text>
                  {(meta || (isSoft && isAuto)) ? (
                    <View style={styles.transactionMetaRow}>
                      {meta ? (
                        <Text style={styles.transactionMeta} numberOfLines={1}>
                          {meta}
                        </Text>
                      ) : null}
                      {isSoft && isAuto ? (
                        <View style={styles.autoTag}>
                          <Zap size={10} color={colors.primary} strokeWidth={2.4} />
                          <Text style={styles.autoTagText}>自动</Text>
                        </View>
                      ) : null}
                    </View>
                  ) : null}
                </View>
              </View>
              <Text
                style={[
                  styles.transactionAmount,
                  isIncome ? styles.incomeAmount : styles.expenseAmount,
                ]}
              >
                {formatSignedCurrency(bill.amountMinor / 100, isIncome)}
              </Text>
            </TouchableOpacity>
          );
        })}
      </View>
    </View>
  );
}

/** 横向分页展示预算与目标，页码和圆点明确提示还有更多卡片。 */
function ProgressCardCarousel({
  cards,
  styles,
  colors,
}: {
  cards: ProgressCardItem[];
  styles: any;
  colors: ThemeColors;
}) {
  const [activeIndex, setActiveIndex] = useState(0);

  if (cards.length === 0) {
    return null;
  }

  return (
    <View style={styles.progressSection}>
      <View style={styles.progressHeading}>
        <Text style={styles.progressSectionTitle}>预算与目标</Text>
        {cards.length > 1 ? (
          <Text style={styles.progressCount}>
            {activeIndex + 1} / {cards.length}
          </Text>
        ) : null}
      </View>
      <ScrollView
        horizontal
        pagingEnabled
        decelerationRate="fast"
        showsHorizontalScrollIndicator={false}
        onMomentumScrollEnd={event => {
          const nextIndex = Math.round(
            event.nativeEvent.contentOffset.x / PROGRESS_CARD_WIDTH,
          );
          setActiveIndex(Math.max(0, Math.min(nextIndex, cards.length - 1)));
        }}
      >
        {cards.map(card => (
          <View
            key={card.id}
            style={[styles.progressCard, { width: PROGRESS_CARD_WIDTH }]}
          >
            <View style={styles.progressCardHeader}>
              <View style={styles.budgetTitleRow}>
                <Text style={styles.sectionSticker}>{card.icon}</Text>
                <View>
                  <Text style={styles.progressCardTitle}>{card.title}</Text>
                  <Text style={styles.progressCardType}>
                    {card.type === 'budget'
                      ? `预算${card.periodLabel ? ` · ${card.periodLabel}` : ''}`
                      : '目标'}
                  </Text>
                </View>
              </View>
              <View
                style={[
                  styles.budgetPercentBadge,
                  card.progress >= 100 && card.type === 'goal'
                    ? { backgroundColor: colors.success }
                    : undefined,
                ]}
              >
                <Text
                  style={[
                    styles.budgetPercentText,
                    card.progress >= 100 && card.type === 'goal'
                      ? { color: colors.onPrimary }
                      : undefined,
                  ]}
                >
                  {card.percent ?? Math.round(card.progress)}%
                </Text>
              </View>
            </View>
            <View style={styles.progressBarContainer}>
              <ProgressBar
                progress={card.progress}
                color={card.color}
                backgroundColor={colors.divider}
                height={12}
                style={styles.budgetProgressBar}
              />
            </View>
            <View style={styles.budgetLabels}>
              <Text style={styles.budgetLabel}>{card.labelLeft}</Text>
              <Text style={styles.budgetLabel}>{card.labelRight}</Text>
            </View>
          </View>
        ))}
      </ScrollView>
      {cards.length > 1 ? (
        <View style={styles.paginationDots}>
          {cards.map((card, index) => (
            <View
              key={card.id}
              style={[
                styles.paginationDot,
                index === activeIndex && styles.paginationDotActive,
              ]}
            />
          ))}
        </View>
      ) : null}
    </View>
  );
}

const PERMISSION_ITEMS = [
  { id: 'accessibility', label: '无障碍权限', impact: '支付结果页和页面规则可能无法识别' },
  { id: 'notificationAccess', label: '通知读取权限', impact: '无法读取支付通知和银行短信' },
  { id: 'overlay', label: '悬浮窗权限', impact: '账单仍可入账，但无法显示编辑弹窗' },
  { id: 'appNotifications', label: '系统通知权限', impact: '收不到自动记账、待确认或预算提醒' },
] as const satisfies readonly { id: RecognitionPermission; label: string; impact: string }[];

function RecognitionPermissionBanner() {
  const focused = useIsFocused();
  const styles = useStyles(createStyles);
  const { colors } = useTheme();
  const { alert } = useAlert();
  const enabled = Platform.OS === 'android';
  const [dismissed, setDismissed] = useState<Partial<Record<RecognitionPermission, true>>>({});
  const { data, refetch } = useQuery({
    queryKey: QUERY_KEYS.recognition.permissions,
    queryFn: nativeRuntime.getRecognitionPermissions,
    enabled: enabled && focused,
    staleTime: 0,
    retry: false,
  });

  useFocusEffect(useCallback(() => {
    if (!enabled) return;
    const refresh = () => { void refetch({ cancelRefetch: false }); };
    refresh();
    const subscription = AppState.addEventListener('change', state => {
      if (state === 'active') refresh();
    });
    const timer = setInterval(() => {
      if (AppState.currentState === 'active') refresh();
    }, 10_000);
    return () => {
      subscription.remove();
      clearInterval(timer);
    };
  }, [enabled, refetch]));

  useEffect(() => {
    if (!data) return;
    setDismissed(previous => {
      const next = { ...previous };
      for (const { id } of PERMISSION_ITEMS) {
        if (data[id] === 'ready' || data[id] === 'not_required') delete next[id];
      }
      return Object.keys(next).length === Object.keys(previous).length ? previous : next;
    });
  }, [data]);

  if (!enabled || !data) return null;
  const issues = PERMISSION_ITEMS.filter(({ id }) =>
    !dismissed[id] && (data[id] === 'missing' || data[id] === 'disconnected'),
  );
  if (issues.length === 0) return null;

  const dismiss = () => {
    setDismissed(previous => {
      const next = { ...previous };
      for (const { id } of issues) next[id] = true;
      return next;
    });
  };

  const openSettings = async (id: RecognitionPermission) => {
    try {
      await nativeRuntime.openRecognitionPermissionSettings(id);
    } catch (error) {
      alert('打开设置失败', error instanceof Error ? error.message : '请手动前往系统设置');
    }
  };

  return (
    <View style={styles.permissionBanner}>
      <View style={styles.permissionHeading}>
        <TriangleAlert size={18} color={colors.warning} />
        <Text style={styles.permissionTitle} accessibilityLiveRegion="polite">权限需要处理</Text>
        <TouchableOpacity
          style={styles.permissionDismiss}
          accessibilityLabel="关闭本次权限提醒"
          accessibilityRole="button"
          onPress={dismiss}
        >
          <X size={19} color={colors.textPrimary} />
        </TouchableOpacity>
      </View>
      {issues.map(({ id, label, impact }) => (
        <View key={id} style={styles.permissionRow}>
          <View style={styles.permissionText}>
            <Text style={styles.permissionLabel}>
              {label}{data[id] === 'disconnected' ? '已开启，服务未连接' : '未授权'}
            </Text>
            <Text style={styles.permissionImpact}>{impact}</Text>
          </View>
          <TouchableOpacity
            style={styles.permissionAction}
            onPress={() => { void openSettings(id); }}
            accessibilityRole="button"
            accessibilityLabel={`前往${label}设置`}
          >
            <Text style={styles.permissionActionText}>去设置 →</Text>
          </TouchableOpacity>
        </View>
      ))}
    </View>
  );
}

export interface DashboardScreenProps {
  onSelectTab?: (tab: string) => void;
}

export default function DashboardScreen({ onSelectTab }: DashboardScreenProps = {}) {
  const navigation = useNavigation();
  const styles = useStyles(createStyles);
  const { colors, isSoft, isDark } = useTheme();
  const [hideAmounts, setHideAmounts] = useState(false);
  // 预算与目标卡片默认关闭；偏好读取完成后才允许发起预算/目标请求
  const {
    showDashboardBudget,
    isLoaded: isDisplayPrefsLoaded,
  } = useDisplayPreferences();
  const budgetEnabled = isDisplayPrefsLoaded && showDashboardBudget;
  const {
    recentBills,
    monthIncome,
    monthExpense,
    monthBalance,
    isLoading,
    isFetching,
    isError: isDashboardError,
    error: dashboardError,
    refetch,
  } = useDashboard();

  const { data: categories = [] } = useQuery({
    queryKey: QUERY_KEYS.categories.all,
    queryFn: () => nativeData.getCategories(),
    staleTime: CACHE_TIME.CATEGORIES,
    gcTime: GC_TIME.DEFAULT,
  });

  /** 待确认笔数：与 ReviewScreen 共享同一 queryKey/缓存；桥接失败时
   *  data 为 undefined → count=0 不渲染横幅（失败静默，不打断首页主信息）。 */
  const { data: recognitionStatus } = useQuery({
    queryKey: QUERY_KEYS.recognition.status,
    queryFn: () => nativeRecognition.getRecognitionStatus(),
    staleTime: 30 * 1000,
    gcTime: GC_TIME.DEFAULT,
  });
  const pendingCount = recognitionStatus?.pendingCount ?? 0;
  const categoryById = useMemo(
    () =>
      new Map<string, Category>(
        categories.map(category => [category.id, category]),
      ),
    [categories],
  );

  const {
    data: budgetProgressData,
    isLoading: isBudgetsLoading,
    isError: isBudgetsError,
    error: budgetsError,
    refetch: refetchBudgets,
  } = useQuery({
    queryKey: QUERY_KEYS.budgetProgress,
    queryFn: () => nativeData.getBudgetProgress(),
    enabled: budgetEnabled,
  });

  const {
    data: goalProgressData,
    isLoading: isGoalsLoading,
    isError: isGoalsError,
    error: goalsError,
    refetch: refetchGoals,
  } = useQuery({
    queryKey: QUERY_KEYS.financialGoals,
    queryFn: async (): Promise<GoalProgress[]> => {
      const goals = await nativeData.getFinancialGoals();
      return goals.map(goal => {
        const progress =
          goal.targetAmountMinor === 0
            ? 0
            : (goal.currentAmountMinor / goal.targetAmountMinor) * 100;
        return {
          ...goal,
          progress,
          isCompleted: goal.currentAmountMinor >= goal.targetAmountMinor,
        };
      });
    },
    enabled: budgetEnabled,
  });

  const progressCards: ProgressCardItem[] = useMemo(() => {
    const cards: ProgressCardItem[] = [];

    (budgetProgressData ?? [])
      .filter((b: BudgetProgress) => b.isPeriodActive !== false)
      .forEach((b: BudgetProgress) => {
        cards.push({
          id: `budget-${b.id}`,
          type: 'budget',
          title: b.name,
          icon: '📊',
          progress: b.progress * 100,
          currentAmount: b.spentMinor / 100,
          totalAmount: b.amountMinor / 100,
          color: getBudgetStatusColor(b.isOverBudget, b.needsAlert, colors),
          percent: b.progressPct,
          periodLabel: b.periodLabel,
          labelLeft: `已用 ${formatCurrency(b.spentMinor / 100)}`,
          labelRight: `剩余 ${formatCurrency(Math.max(0, b.remainingMinor / 100))}`,
        });
      });

    (goalProgressData ?? [])
      .filter(g => !g.isCompleted)
      .forEach(g => {
        cards.push({
          id: `goal-${g.id}`,
          type: 'goal',
          title: g.name,
          icon: '🎯',
          progress: g.progress,
          currentAmount: g.currentAmountMinor / 100,
          totalAmount: g.targetAmountMinor / 100,
          color: colors.success,
          percent: Math.round(g.progress),
          labelLeft: `已存 ¥${(g.currentAmountMinor / 100).toLocaleString()}`,
          labelRight: `目标 ¥${(g.targetAmountMinor / 100).toLocaleString()}`,
        });
      });

    return cards;
  }, [budgetProgressData, goalProgressData, colors]);

  const hasAnyBudgetOrGoal =
    (budgetProgressData?.length ?? 0) > 0 || (goalProgressData?.length ?? 0) > 0;

  // 按本地日期分组（30 条原始数据 → 最多 4 个完整日组 / 12 笔）
  const {
    groups: recentDayGroups,
    hasMore: hasMoreRecentBills,
  } = useMemo(() => limitBillDayGroups(groupBillsByDay(recentBills)), [recentBills]);

  const currentDate = new Date();
  const monthName = `${currentDate.getMonth() + 1}月账本`;
  const yearName = `${currentDate.getFullYear()}`;

  const onRefresh = useCallback(async () => {
    const tasks: Promise<unknown>[] = [refetch()];
    // 预算卡片关闭时不触发预算/目标请求，避免无谓的原生桥接开销
    if (budgetEnabled) {
      tasks.push(refetchBudgets(), refetchGoals());
    }
    await Promise.all(tasks);
  }, [refetch, refetchBudgets, refetchGoals, budgetEnabled]);

  const handleAddBill = () => {
    navigation.navigate('CreateBill' as never);
  };

  const handleViewAllBills = () => {
    navigation.navigate('AllBills');
  };

  const handleViewExpense = () => {
    navigation.navigate('AllBills', { initialFilter: 'expense' });
  };

  const handleViewIncome = () => {
    navigation.navigate('AllBills', { initialFilter: 'income' });
  };

  const handleViewBillDetail = (bill: Bill) => {
    navigation.navigate({
      name: 'BillDetail',
      params: { billId: bill.id },
    } as never);
  };

  const embeddedBudgetCard = isSoft && budgetEnabled
    ? progressCards.find(card => card.type === 'budget') ?? null
    : null;
  const remainingProgressCards = embeddedBudgetCard
    ? progressCards.filter(card => card.id !== embeddedBudgetCard.id)
    : progressCards;

  const formattedExpenseParts = useMemo(() => {
    const abs = Math.abs(monthExpense);
    const fixed = abs.toLocaleString('zh-CN', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    });
    const [intPart, decPart] = fixed.split('.');
    return { sign: monthExpense < 0 ? '-' : '', intPart, decPart };
  }, [monthExpense]);

  if (isLoading) {
    return <DashboardSkeleton showBudgetCard={budgetEnabled} />;
  }

  return (
    <ScrollView
      style={styles.container}
      contentContainerStyle={styles.content}
      refreshControl={
        <RefreshControl refreshing={isFetching} onRefresh={onRefresh} />
      }
      showsVerticalScrollIndicator={false}
    >
      {isSoft ? (
        <View style={styles.softHeader}>
          <View>
            <Text style={styles.softHeaderTitle}>{monthName}</Text>
            <Text style={styles.softHeaderSubtitle}>{yearName}年</Text>
          </View>
          <View style={styles.softHeaderActions}>
            <TouchableOpacity
              style={styles.softIconButton}
              onPress={handleViewAllBills}
              activeOpacity={0.75}
              accessibilityLabel="搜索账单"
              accessibilityRole="button"
            >
              <Search size={20} color={colors.textSecondary} strokeWidth={2} />
            </TouchableOpacity>
            <TouchableOpacity
              style={styles.softIconButton}
              onPress={() => navigation.navigate('Review' as never)}
              activeOpacity={0.75}
              accessibilityLabel={pendingCount > 0 ? `${pendingCount} 笔交易待确认` : '待确认中心'}
              accessibilityRole="button"
            >
              <Bell size={20} color={colors.textSecondary} strokeWidth={2} />
              {pendingCount > 0 ? <View style={styles.softBellDot} /> : null}
            </TouchableOpacity>
          </View>
        </View>
      ) : (
        <View style={styles.header}>
          <View>
            <Text style={styles.headerTitle}>{monthName}</Text>
            <Text style={styles.headerSubtitle}>{yearName}</Text>
          </View>
        </View>
      )}

      <RecognitionPermissionBanner />

      {/* 主查询失败：顶部警示条 + 重试；失败时不再渲染「暂无交易」空态，
          避免把故障伪装成"没记账" */}
      <QueryErrorBanner
        error={isDashboardError ? dashboardError : null}
        title="首页数据加载失败"
        hint="这不代表你没有账目记录"
        onRetry={onRefresh}
        style={styles.errorBanner}
      />

      {isSoft ? (
        <>
          {/* 柔光 Hero 主卡：渐变底 + 金额主视觉 + 收入/结余 + 内嵌预算条 */}
          <SoftGradient
            glow
            colors={[colors.gradientPrimaryStart, colors.primary, colors.gradientPrimaryEnd]}
            style={styles.softHeroCard}
          >
            <View style={styles.softHeroTopRow}>
              <View style={styles.softHeroLabelGroup}>
                <Text style={styles.softHeroLabel}>本月支出</Text>
                <TouchableOpacity
                  onPress={() => setHideAmounts(v => !v)}
                  hitSlop={{ top: 10, bottom: 10, left: 10, right: 10 }}
                  accessibilityLabel={hideAmounts ? '显示金额' : '隐藏金额'}
                  accessibilityRole="button"
                >
                  {hideAmounts ? (
                    <EyeOff size={15} color="rgba(255,255,255,0.8)" strokeWidth={2} />
                  ) : (
                    <Eye size={15} color="rgba(255,255,255,0.8)" strokeWidth={2} />
                  )}
                </TouchableOpacity>
              </View>
              <TouchableOpacity
                style={styles.softHeroPillLink}
                onPress={handleViewExpense}
                activeOpacity={0.8}
                accessibilityLabel="查看支出明细"
                accessibilityRole="button"
              >
                <Text style={styles.softHeroPillText}>明细</Text>
                <ChevronRight size={13} color="rgba(255,255,255,0.9)" strokeWidth={2.4} />
              </TouchableOpacity>
            </View>

            <Text
              style={styles.softHeroAmount}
              numberOfLines={1}
              adjustsFontSizeToFit
              minimumFontScale={0.65}
            >
              {hideAmounts ? (
                <>
                  <Text style={styles.softHeroCurrency}>¥ </Text>
                  ••••
                </>
              ) : (
                <>
                  {formattedExpenseParts.sign}
                  <Text style={styles.softHeroCurrency}>¥</Text>
                  {formattedExpenseParts.intPart}
                  <Text style={styles.softHeroDecimal}>.{formattedExpenseParts.decPart}</Text>
                </>
              )}
            </Text>

            <View style={styles.softHeroStats}>
              <TouchableOpacity
                style={styles.softHeroStatItem}
                onPress={handleViewIncome}
                activeOpacity={0.75}
                accessibilityLabel={`本月收入 ${formatCurrency(monthIncome)}`}
                accessibilityRole="button"
              >
                <Text style={styles.softHeroStatLabel}>收入</Text>
                <Text style={styles.softHeroStatValue} numberOfLines={1}>
                  {hideAmounts ? '••••' : formatCurrency(monthIncome)}
                </Text>
              </TouchableOpacity>
              <View style={styles.softHeroStatDivider} />
              <View style={styles.softHeroStatItem}>
                <Text style={styles.softHeroStatLabel}>结余</Text>
                <Text style={styles.softHeroStatValue} numberOfLines={1}>
                  {hideAmounts ? '••••' : formatCurrency(monthBalance)}
                </Text>
              </View>
            </View>

            {embeddedBudgetCard ? (
              <TouchableOpacity
                style={styles.softHeroBudget}
                onPress={() => navigation.navigate('Budgets' as never)}
                activeOpacity={0.85}
              >
                <View style={styles.softHeroBudgetRow}>
                  <Text style={styles.softHeroBudgetText} numberOfLines={1}>
                    {embeddedBudgetCard.title} ·{' '}
                    <Text style={styles.softHeroBudgetBold}>
                      {embeddedBudgetCard.percent ?? Math.round(embeddedBudgetCard.progress)}%
                    </Text>
                  </Text>
                  <Text style={styles.softHeroBudgetText} numberOfLines={1}>
                    {hideAmounts ? '••' : embeddedBudgetCard.labelRight}
                  </Text>
                </View>
                <View style={styles.softHeroBudgetTrack}>
                  <View
                    style={[
                      styles.softHeroBudgetFill,
                      { width: `${Math.min(100, Math.max(0, embeddedBudgetCard.progress))}%` },
                    ]}
                  />
                </View>
              </TouchableOpacity>
            ) : null}
          </SoftGradient>

          {/* 柔光待确认暖色提示卡 */}
          {pendingCount > 0 ? (
            <TouchableOpacity
              style={styles.softPendingCard}
              onPress={() => navigation.navigate('Review' as never)}
              activeOpacity={0.85}
              accessibilityLabel={`${pendingCount} 笔交易待确认，点击前往处理`}
              accessibilityRole="button"
            >
              <View style={styles.softPendingIconBox}>
                <Inbox size={20} color={colors.warning} strokeWidth={2} />
                <View style={styles.softPendingCountBadge}>
                  <Text style={styles.softPendingCountText}>{pendingCount}</Text>
                </View>
              </View>
              <View style={styles.softPendingBody}>
                <Text style={styles.softPendingTitle}>{pendingCount} 笔交易待确认</Text>
                <Text style={styles.softPendingSub} numberOfLines={1}>
                  账户不明或疑似重复，确认后才会入账
                </Text>
              </View>
              <ChevronRight size={18} color={colors.textQuaternary} strokeWidth={2} />
            </TouchableOpacity>
          ) : null}

          {/* 柔光 4 快捷入口 */}
          <View style={styles.softQuickRow}>
            {[
              {
                key: 'import',
                label: '导入账单',
                color: isDark ? '#38BDF8' : '#0EA5E9',
                Icon: Download,
                onPress: () => navigation.navigate('Import' as never),
              },
              {
                key: 'ai',
                label: 'AI 记账',
                color: isDark ? '#A78BFA' : '#8B5CF6',
                Icon: Sparkles,
                onPress: () => (onSelectTab ? onSelectTab('ai') : navigation.navigate('AIChat' as never)),
              },
              {
                key: 'budget',
                label: '预算',
                color: isDark ? '#FBBF24' : '#F59E0B',
                Icon: PieChartIcon,
                onPress: () => navigation.navigate('Budgets' as never),
              },
              {
                key: 'auto',
                label: '自动记账',
                color: isDark ? '#34D399' : '#10B981',
                Icon: Zap,
                onPress: () => navigation.navigate('AutoBookkeeping' as never),
              },
            ].map(({ key, label, color, Icon, onPress }) => (
              <TouchableOpacity
                key={key}
                style={styles.softQuickItem}
                onPress={onPress}
                activeOpacity={0.78}
                accessibilityRole="button"
                accessibilityLabel={label}
              >
                <View style={styles.softQuickIconBox}>
                  <Icon size={22} color={color} strokeWidth={2} />
                </View>
                <Text style={styles.softQuickLabel}>{label}</Text>
              </TouchableOpacity>
            ))}
          </View>
        </>
      ) : (
        <>
          {/* 待确认入口：有待确认卡时才出现，0 笔不占位 */}
          {pendingCount > 0 ? (
            <TouchableOpacity
              style={styles.pendingBanner}
              onPress={() => navigation.navigate('Review' as never)}
              activeOpacity={0.8}
              accessibilityLabel={`${pendingCount} 笔交易待确认，点击前往处理`}
              accessibilityRole="button"
            >
              <View style={styles.pendingBadge}>
                <Text style={styles.pendingBadgeText}>{pendingCount}</Text>
              </View>
              <Text style={styles.pendingText}>有待确认的交易</Text>
              <Text style={styles.pendingAction}>去处理 →</Text>
            </TouchableOpacity>
          ) : null}

          <View style={styles.overviewCard}>
            <View style={styles.overviewContent}>
              <View style={styles.overviewLabelRow}>
                <Text style={styles.overviewLabel}>本月支出</Text>
                <TouchableOpacity
                  onPress={handleViewExpense}
                  activeOpacity={0.75}
                  accessibilityLabel="查看支出明细"
                  accessibilityRole="button"
                >
                  <Text style={styles.overviewLink}>支出明细 →</Text>
                </TouchableOpacity>
              </View>
              <Text
                style={styles.overviewBalance}
                numberOfLines={1}
                adjustsFontSizeToFit
                minimumFontScale={0.6}
              >
                {formatCurrency(monthExpense)}
              </Text>

              <View style={styles.overviewDivider} />

              <View style={styles.overviewStats}>
                <TouchableOpacity
                  style={styles.statBlock}
                  onPress={handleViewIncome}
                  activeOpacity={0.75}
                  accessibilityLabel={`本月收入 ${formatCurrency(monthIncome)}`}
                  accessibilityRole="button"
                >
                  <Text style={styles.statLabel}>本月收入</Text>
                  <Text style={styles.statValue} numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.7}>
                    {formatCurrency(monthIncome)}
                  </Text>
                </TouchableOpacity>
                <View style={styles.statDivider} />
                <View style={styles.statBlock}>
                  <Text style={styles.statLabel}>本月结余</Text>
                  <Text style={styles.statValue} numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.7}>
                    {formatCurrency(monthBalance)}
                  </Text>
                </View>
              </View>
            </View>
          </View>
        </>
      )}

      {/* 预算与目标：默认隐藏，仅在「设置 → 显示」开启后渲染并请求数据 */}
      {budgetEnabled ? (
        remainingProgressCards.length > 0 ? (
          <ProgressCardCarousel
            cards={remainingProgressCards}
            styles={styles}
            colors={colors}
          />
        ) : embeddedBudgetCard ? null : isBudgetsError || isGoalsError ? (
          <QueryErrorBanner
            error={budgetsError ?? goalsError}
            title="预算与目标加载失败"
            onRetry={() => {
              refetchBudgets();
              refetchGoals();
            }}
            style={styles.errorBanner}
          />
        ) : isBudgetsLoading || isGoalsLoading ? (
          // 加载中占位：固定高度，避免数据到达后整段下移跳动
          <View style={styles.progressLoadingPlaceholder} />
        ) : (
          <TouchableOpacity
            style={styles.progressEmptyCard}
            onPress={() => navigation.navigate('FinancialGoals' as never)}
            activeOpacity={0.8}
          >
            <Text style={styles.progressEmptyIcon}>📊</Text>
            <Text style={styles.progressEmptyText}>
              {hasAnyBudgetOrGoal
                ? '当前没有进行中的预算或目标'
                : '设置预算或财务目标来追踪进度'}
            </Text>
            <Text style={styles.progressEmptyHint}>点击前往设置 →</Text>
          </TouchableOpacity>
        )
      ) : null}

      <View style={styles.section}>
        <View style={styles.sectionHeader}>
          <Text style={styles.sectionTitle}>近期交易</Text>
          <TouchableOpacity
            onPress={handleViewAllBills}
            style={styles.viewAllButton}
            activeOpacity={0.75}
            accessibilityLabel="查看全部交易"
            accessibilityRole="button"
          >
            <Text style={styles.viewAllText}>
              {isSoft ? '查看全部 ›' : '查看全部 →'}
            </Text>
          </TouchableOpacity>
        </View>

        {recentBills.length > 0 ? (
          <>
            {recentDayGroups.map(group => (
              <DayTradeGroup
                key={group.dateKey}
                group={group}
                categoryById={categoryById}
                styles={styles}
                isSoft={isSoft}
                isDark={isDark}
                colors={colors}
                onPressBill={handleViewBillDetail}
              />
            ))}
            {hasMoreRecentBills ? (
              <TouchableOpacity
                style={styles.moreDaysButton}
                onPress={handleViewAllBills}
                activeOpacity={0.75}
                accessibilityLabel="查看更早的交易"
                accessibilityRole="button"
              >
                <Text style={styles.moreDaysText}>
                  {isSoft ? '查看更早的交易 ›' : '查看更早的交易 →'}
                </Text>
              </TouchableOpacity>
            ) : null}
          </>
        ) : isDashboardError ? (
          // 主查询失败时顶部已有警示条，这里不再显示「暂无交易记录」空态
          null
        ) : (
          <View style={styles.emptyState}>
            <Text style={styles.emptyIcon}>📝</Text>
            <Text style={styles.emptyText}>暂无交易记录</Text>
            <TouchableOpacity
              style={styles.emptyButton}
              onPress={handleAddBill}
              activeOpacity={0.8}
              accessibilityLabel="添加第一笔账单"
              accessibilityRole="button"
            >
              <Text style={styles.emptyButtonText}>添加第一笔</Text>
            </TouchableOpacity>
          </View>
        )}
      </View>
    </ScrollView>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape, isDark: boolean) => ({
  ...StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: colors.background,
    },
    content: {
      paddingHorizontal: spacing.lg,
      paddingTop: shape.isSoft ? spacing.md : spacing.xxl,
      paddingBottom: shape.isSoft ? 116 : spacing.xxxxl,
    },
    softHeader: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      marginBottom: spacing.md,
      paddingHorizontal: spacing.xs,
    },
    softHeaderTitle: {
      fontSize: 22,
      fontWeight: '700',
      color: colors.textPrimary,
      letterSpacing: -0.3,
    },
    softHeaderSubtitle: {
      fontSize: 12,
      fontWeight: '500',
      color: colors.textTertiary,
      marginTop: 2,
    },
    softHeaderActions: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.xs,
    },
    softIconButton: {
      width: 40,
      height: 40,
      borderRadius: 14,
      alignItems: 'center',
      justifyContent: 'center',
      position: 'relative',
    },
    softBellDot: {
      position: 'absolute',
      top: 9,
      right: 10,
      width: 8,
      height: 8,
      borderRadius: 4,
      backgroundColor: colors.expense,
      borderWidth: 1.5,
      borderColor: colors.background,
    },
    softHeroCard: {
      borderRadius: shape.radius.hero,
      paddingHorizontal: 20,
      paddingTop: 18,
      paddingBottom: 16,
      marginBottom: 14,
      ...shape.elevation.hero,
    },
    softHeroTopRow: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
    },
    softHeroLabelGroup: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 6,
    },
    softHeroLabel: {
      fontSize: 13,
      fontWeight: '500',
      color: 'rgba(255,255,255,0.84)',
    },
    softHeroPillLink: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 2,
      paddingHorizontal: 10,
      paddingVertical: 4,
      borderRadius: 999,
      backgroundColor: 'rgba(255,255,255,0.16)',
    },
    softHeroPillText: {
      fontSize: 12,
      fontWeight: '500',
      color: 'rgba(255,255,255,0.92)',
    },
    softHeroAmount: {
      fontSize: 36,
      fontWeight: '700',
      color: '#FFFFFF',
      letterSpacing: -0.8,
      marginTop: 6,
      marginBottom: 14,
      fontVariant: ['tabular-nums'],
    },
    softHeroCurrency: {
      fontSize: 22,
      fontWeight: '600',
    },
    softHeroDecimal: {
      fontSize: 22,
      fontWeight: '600',
      color: 'rgba(255,255,255,0.82)',
    },
    softHeroStats: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 16,
    },
    softHeroStatItem: {
      gap: 2,
    },
    softHeroStatLabel: {
      fontSize: 12,
      color: 'rgba(255,255,255,0.74)',
    },
    softHeroStatValue: {
      fontSize: 15,
      fontWeight: '600',
      color: '#FFFFFF',
      fontVariant: ['tabular-nums'],
    },
    softHeroStatDivider: {
      width: 1,
      height: 28,
      backgroundColor: 'rgba(255,255,255,0.22)',
    },
    softHeroBudget: {
      marginTop: 14,
      paddingHorizontal: 14,
      paddingVertical: 11,
      borderRadius: 16,
      backgroundColor: 'rgba(255,255,255,0.13)',
    },
    softHeroBudgetRow: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      gap: 8,
    },
    softHeroBudgetText: {
      fontSize: 11.5,
      color: 'rgba(255,255,255,0.86)',
      fontVariant: ['tabular-nums'],
      flexShrink: 1,
    },
    softHeroBudgetBold: {
      fontWeight: '700',
      color: '#FFFFFF',
    },
    softHeroBudgetTrack: {
      height: 6,
      borderRadius: 999,
      backgroundColor: 'rgba(255,255,255,0.2)',
      marginTop: 8,
      overflow: 'hidden',
    },
    softHeroBudgetFill: {
      height: '100%',
      borderRadius: 999,
      backgroundColor: '#FFC35C',
    },
    softPendingCard: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 12,
      padding: 12,
      borderRadius: 20,
      backgroundColor: isDark ? '#2E2410' : '#FFF5E6',
      marginBottom: 14,
    },
    softPendingIconBox: {
      width: 40,
      height: 40,
      borderRadius: 14,
      backgroundColor: colors.surface,
      alignItems: 'center',
      justifyContent: 'center',
      position: 'relative',
    },
    softPendingCountBadge: {
      position: 'absolute',
      top: -4,
      right: -4,
      minWidth: 18,
      height: 18,
      borderRadius: 9,
      backgroundColor: colors.expense,
      paddingHorizontal: 4,
      alignItems: 'center',
      justifyContent: 'center',
    },
    softPendingCountText: {
      fontSize: 10.5,
      fontWeight: '700',
      color: '#FFFFFF',
      fontVariant: ['tabular-nums'],
    },
    softPendingBody: {
      flex: 1,
      gap: 2,
    },
    softPendingTitle: {
      fontSize: 14,
      fontWeight: '600',
      color: colors.textPrimary,
    },
    softPendingSub: {
      fontSize: 12,
      color: colors.textTertiary,
    },
    softQuickRow: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      gap: 8,
      marginBottom: 20,
    },
    softQuickItem: {
      flex: 1,
      alignItems: 'center',
      gap: 6,
    },
    softQuickIconBox: {
      width: 52,
      height: 52,
      borderRadius: 18,
      backgroundColor: colors.surface,
      borderWidth: shape.borderWidth.card,
      borderColor: colors.cardBorder,
      alignItems: 'center',
      justifyContent: 'center',
      ...shape.elevation.card,
    },
    softQuickLabel: {
      fontSize: 12,
      fontWeight: '500',
      color: colors.textSecondary,
    },
    header: {
      marginBottom: spacing.xl,
    },
    headerTitle: {
      fontSize: 28,
      fontWeight: '800',
      color: colors.textPrimary,
      letterSpacing: -0.5,
    },
    headerSubtitle: {
      fontSize: 14,
      fontWeight: '500',
      color: colors.textTertiary,
      marginTop: spacing.xs,
    },
    permissionBanner: {
      backgroundColor: shape.isSoft ? (isDark ? '#2E2410' : '#FFF5E6') : colors.surface,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.warning,
      borderRadius: shape.isSoft ? shape.radius.card : borderRadius.card,
      marginBottom: spacing.lg,
      paddingHorizontal: spacing.md,
    },
    permissionHeading: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
    },
    permissionTitle: {
      flex: 1,
      color: colors.textPrimary,
      fontSize: 15,
      fontWeight: '800',
    },
    permissionDismiss: {
      minHeight: 44,
      minWidth: 44,
      alignItems: 'center',
      justifyContent: 'center',
    },
    permissionRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
      paddingVertical: spacing.sm,
      borderTopWidth: borderWidth.hairline,
      borderTopColor: colors.divider,
    },
    permissionText: {
      flex: 1,
      gap: spacing.xs,
    },
    permissionLabel: {
      color: colors.textPrimary,
      fontSize: 13,
      fontWeight: '700',
    },
    permissionImpact: {
      color: colors.textSecondary,
      fontSize: 12,
    },
    permissionAction: {
      minHeight: 44,
      justifyContent: 'center',
      paddingHorizontal: spacing.sm,
    },
    permissionActionText: {
      color: colors.primary,
      fontSize: 13,
      fontWeight: '700',
    },
    overviewCard: {
      backgroundColor: colors.primaryDark,
      borderRadius: borderRadius.card,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      overflow: 'hidden',
      marginBottom: spacing.xxl,
      ...shadow.soft,
    },
    overviewContent: {
      padding: spacing.xl,
    },
    overviewLabelRow: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
    },
    overviewLabel: {
      fontSize: 14,
      fontWeight: '600',
      color: colors.onPrimaryMuted,
      letterSpacing: 0.5,
    },
    overviewLink: {
      fontSize: 12,
      fontWeight: '600',
      color: colors.onPrimary,
      paddingVertical: spacing.sm,
      paddingHorizontal: spacing.xs,
      minHeight: 44,
      textAlignVertical: 'center',
    },
    overviewBalance: {
      fontSize: 40,
      fontWeight: '800',
      color: colors.onPrimary,
      marginTop: spacing.sm,
      letterSpacing: -1,
      fontVariant: ['tabular-nums'],
    },
    overviewDivider: {
      height: borderWidth.hairline,
      backgroundColor: colors.onPrimaryDivider,
      marginVertical: spacing.lg,
    },
    overviewStats: {
      flexDirection: 'row',
      alignItems: 'center',
    },
    statBlock: {
      flex: 1,
      gap: spacing.xs,
    },
    statDivider: {
      width: borderWidth.hairline,
      height: 36,
      backgroundColor: colors.onPrimaryDivider,
      marginHorizontal: spacing.lg,
    },
    statLabel: {
      fontSize: 12,
      color: colors.onPrimarySubtle,
      fontWeight: '500',
    },
    statValue: {
      fontSize: 17,
      fontWeight: '700',
      color: colors.onPrimary,
      fontVariant: ['tabular-nums'],
    },
    progressSection: {
      marginBottom: spacing.xxl,
    },
    progressHeading: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      marginBottom: spacing.md,
    },
    progressSectionTitle: {
      fontSize: 17,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    progressCount: {
      fontSize: 12,
      fontWeight: '600',
      color: colors.textTertiary,
      fontVariant: ['tabular-nums'],
    },
    progressCard: {
      backgroundColor: colors.surface,
      borderRadius: shape.isSoft ? shape.radius.card : borderRadius.card,
      borderWidth: shape.isSoft ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isSoft ? colors.cardBorder : colors.stroke,
      padding: spacing.lg,
      ...(shape.isSoft ? shape.elevation.card : shadow.soft),
    },
    progressCardHeader: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      marginBottom: spacing.lg,
    },
    progressCardTitle: {
      fontSize: 16,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    progressCardType: {
      fontSize: 12,
      fontWeight: '500',
      color: colors.textTertiary,
      marginTop: 2,
    },
    budgetTitleRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
    },
    budgetPercentBadge: {
      backgroundColor: colors.accent,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.hairline,
      borderColor: colors.stroke,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.xs,
    },
    budgetPercentText: {
      fontSize: 14,
      fontWeight: '700',
      color: colors.textPrimary,
      fontVariant: ['tabular-nums'],
    },
    progressBarContainer: {
      borderRadius: borderRadius.small,
      overflow: 'hidden',
      marginBottom: spacing.md,
    },
    budgetProgressBar: {},
    budgetLabels: {
      flexDirection: 'row',
      justifyContent: 'space-between',
    },
    budgetLabel: {
      fontSize: 12,
      fontWeight: '500',
      color: colors.textTertiary,
      fontVariant: ['tabular-nums'],
    },
    paginationDots: {
      flexDirection: 'row',
      justifyContent: 'center',
      gap: spacing.sm,
      marginTop: spacing.md,
    },
    paginationDot: {
      width: 6,
      height: 6,
      borderRadius: borderRadius.round,
      backgroundColor: colors.divider,
    },
    paginationDotActive: {
      width: 18,
      backgroundColor: colors.primary,
    },
    progressEmptyCard: {
      backgroundColor: colors.surface,
      borderRadius: borderRadius.card,
      borderWidth: borderWidth.hairline,
      borderColor: colors.strokeLight,
      borderStyle: 'dashed',
      padding: spacing.xl,
      marginBottom: spacing.xxl,
      alignItems: 'center',
    },
    progressEmptyIcon: {
      fontSize: 28,
      marginBottom: spacing.sm,
    },
    progressEmptyText: {
      fontSize: 14,
      fontWeight: '600',
      color: colors.textTertiary,
      textAlign: 'center',
    },
    progressEmptyHint: {
      fontSize: 12,
      fontWeight: '600',
      color: colors.primary,
      marginTop: spacing.sm,
    },
    section: {},
    errorBanner: {
      margin: 0,
      marginBottom: spacing.xxl,
    },
    pendingBanner: {
      flexDirection: 'row',
      alignItems: 'center',
      backgroundColor: colors.surface,
      borderRadius: borderRadius.card,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      paddingHorizontal: spacing.lg,
      paddingVertical: spacing.md,
      marginBottom: spacing.xxl,
      minHeight: 44,
      ...shadow.soft,
    },
    pendingBadge: {
      minWidth: 24,
      height: 24,
      borderRadius: borderRadius.small,
      backgroundColor: colors.warning,
      borderWidth: borderWidth.hairline,
      borderColor: colors.stroke,
      alignItems: 'center',
      justifyContent: 'center',
      paddingHorizontal: spacing.xs,
      marginRight: spacing.sm,
    },
    pendingBadgeText: {
      fontSize: 13,
      fontWeight: '800',
      color: colors.textPrimary,
      fontVariant: ['tabular-nums'],
    },
    pendingText: {
      flex: 1,
      fontSize: 14,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    pendingAction: {
      fontSize: 13,
      fontWeight: '700',
      color: colors.primary,
    },
    progressLoadingPlaceholder: {
      height: 148,
      marginBottom: spacing.xxl,
      backgroundColor: colors.surface,
      borderRadius: borderRadius.card,
      borderWidth: borderWidth.hairline,
      borderColor: colors.divider,
    },
    sectionHeader: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      marginBottom: spacing.md,
    },
    sectionSticker: {
      fontSize: 18,
    },
    sectionTitle: {
      fontSize: shape.isSoft ? 17 : 20,
      fontWeight: shape.isSoft ? '600' : '700',
      color: colors.textPrimary,
    },
    viewAllButton: {
      paddingHorizontal: shape.isSoft ? spacing.xs : spacing.md,
      paddingVertical: spacing.xs,
      minHeight: 40,
      justifyContent: 'center',
    },
    viewAllText: {
      fontSize: 13,
      fontWeight: shape.isSoft ? '500' : '700',
      color: shape.isSoft ? colors.textTertiary : colors.primary,
    },
    dayGroup: {
      marginBottom: spacing.lg,
    },
    dayHeader: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      gap: spacing.sm,
      paddingHorizontal: spacing.xs,
      marginBottom: spacing.sm,
    },
    dayHeaderLeft: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
      flexShrink: 1,
    },
    dayBadge: {
      minWidth: 38,
      paddingHorizontal: spacing.sm,
      paddingVertical: 3,
      borderRadius: shape.isSoft ? shape.radius.tag : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : borderWidth.hairline,
      borderColor: colors.stroke,
      backgroundColor: colors.surfaceMuted,
      alignItems: 'center',
    },
    dayBadgeToday: {
      backgroundColor: shape.isSoft ? colors.primaryLight : colors.accent,
    },
    dayBadgeYesterday: {
      backgroundColor: shape.isSoft ? colors.surfaceMuted : colors.primaryLight,
    },
    dayBadgeText: {
      fontSize: shape.isSoft ? 12 : 11,
      fontWeight: shape.isSoft ? '600' : '800',
      color: shape.isSoft ? colors.textSecondary : colors.textPrimary,
      letterSpacing: 0.2,
    },
    dayBadgeTodayText: {
      color: colors.primaryDark,
    },
    dayDate: {
      fontSize: shape.isSoft ? 13 : 14,
      fontWeight: shape.isSoft ? '400' : '700',
      color: shape.isSoft ? colors.textTertiary : colors.textPrimary,
      fontVariant: ['tabular-nums'],
    },
    dayTotals: {
      flexDirection: shape.isSoft ? 'row' : 'column',
      alignItems: shape.isSoft ? 'center' : 'flex-end',
      gap: shape.isSoft ? 8 : 1,
      flexShrink: 0,
    },
    dayExpenseTotal: {
      fontSize: 12,
      fontWeight: shape.isSoft ? '500' : '700',
      color: shape.isSoft ? colors.textTertiary : colors.expense,
      fontVariant: ['tabular-nums'],
    },
    dayIncomeTotal: {
      fontSize: shape.isSoft ? 12 : 11,
      fontWeight: shape.isSoft ? '500' : '600',
      color: shape.isSoft ? colors.textTertiary : colors.income,
      fontVariant: ['tabular-nums'],
    },
    dayCard: {
      backgroundColor: colors.surface,
      borderRadius: shape.isSoft ? shape.radius.card : borderRadius.card,
      borderWidth: shape.isSoft ? shape.borderWidth.card : borderWidth.hairline,
      borderColor: shape.isSoft ? colors.cardBorder : colors.divider,
      overflow: 'hidden',
      ...(shape.isSoft ? shape.elevation.card : {}),
    },
    transactionItem: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingHorizontal: shape.isSoft ? 14 : spacing.md,
      paddingVertical: 12,
      backgroundColor: colors.surface,
    },
    transactionItemBorder: {
      borderTopWidth: StyleSheet.hairlineWidth,
      borderTopColor: colors.divider,
    },
    transactionLeft: {
      flexDirection: 'row',
      alignItems: 'center',
      flex: 1,
      paddingRight: spacing.sm,
    },
    transactionIcon: {
      width: shape.isSoft ? 40 : 42,
      height: shape.isSoft ? 40 : 42,
      borderRadius: shape.isSoft ? 14 : borderRadius.medium,
      borderWidth: shape.isSoft ? 0 : borderWidth.hairline,
      borderColor: colors.stroke,
      alignItems: 'center',
      justifyContent: 'center',
      marginRight: spacing.md,
    },
    transactionIconText: {
      fontSize: 20,
    },
    transactionInfo: {
      flex: 1,
    },
    transactionTitle: {
      fontSize: 15,
      fontWeight: shape.isSoft ? '500' : '600',
      color: colors.textPrimary,
      marginBottom: 2,
    },
    transactionMetaRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 6,
    },
    transactionMeta: {
      fontSize: 12,
      fontWeight: '400',
      color: colors.textTertiary,
      flexShrink: 1,
    },
    autoTag: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 2,
      backgroundColor: colors.primaryLight,
      paddingHorizontal: 5,
      paddingVertical: 1,
      borderRadius: 6,
    },
    autoTagText: {
      fontSize: 10.5,
      fontWeight: '600',
      color: colors.primary,
    },
    transactionAmount: {
      fontSize: shape.isSoft ? 16 : 15,
      fontWeight: shape.isSoft ? '600' : '700',
      fontVariant: ['tabular-nums'],
    },
    incomeAmount: {
      color: colors.income,
    },
    expenseAmount: {
      color: colors.expense,
    },
    moreDaysButton: {
      alignItems: 'center',
      justifyContent: 'center',
      minHeight: 44,
      paddingVertical: spacing.md,
      borderRadius: shape.isSoft ? shape.radius.pill : borderRadius.card,
      borderWidth: shape.isSoft ? 0 : borderWidth.hairline,
      borderColor: colors.strokeLight,
      borderStyle: shape.isSoft ? 'solid' : 'dashed',
      backgroundColor: shape.isSoft ? 'transparent' : colors.surface,
    },
    moreDaysText: {
      fontSize: 13,
      fontWeight: shape.isSoft ? '500' : '700',
      color: shape.isSoft ? colors.textTertiary : colors.primary,
    },
    emptyState: {
      alignItems: 'center',
      padding: spacing.xxl,
      backgroundColor: colors.surface,
      borderRadius: shape.isSoft ? shape.radius.card : borderRadius.card,
      borderWidth: shape.isSoft ? shape.borderWidth.card : borderWidth.hairline,
      borderColor: shape.isSoft ? colors.cardBorder : colors.divider,
      ...(shape.isSoft ? shape.elevation.card : {}),
    },
    emptyIcon: {
      fontSize: 40,
      marginBottom: spacing.md,
    },
    emptyText: {
      fontSize: 15,
      fontWeight: shape.isSoft ? '500' : '600',
      color: colors.textTertiary,
      marginBottom: spacing.lg,
    },
    emptyButton: {
      backgroundColor: shape.isSoft ? colors.primary : colors.accent,
      paddingHorizontal: spacing.xl,
      paddingVertical: spacing.md,
      borderRadius: shape.isSoft ? shape.radius.button : borderRadius.button,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.stroke,
      ...(shape.isSoft ? shape.elevation.none : shadow.soft),
    },
    emptyButtonText: {
      fontSize: 15,
      fontWeight: shape.isSoft ? '600' : '700',
      color: shape.isSoft ? colors.onPrimary : colors.textPrimary,
    },
  }),
});

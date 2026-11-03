/**
 * Dashboard 首页 - 轻量化 Neo-Brutalism 风格
 * 保留暖白、积木色块与偏移阴影，集中强调本月支出和预算进度。
 */
import React, { useCallback, useMemo, useState } from 'react';
import {
  View,
  Text,
  ScrollView,
  RefreshControl,
  StyleSheet,
  TouchableOpacity,
  Dimensions,
} from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { useQuery } from '@tanstack/react-query';
import { ThemeColors } from '../../theme/colors';
import {
  borderRadius,
  borderWidth,
  spacing,
  shadow,
} from '../../theme/spacing';
import { ProgressBar } from '../../components/ui';
import { DashboardSkeleton } from '../../components/skeleton';
import CategoryIcon from '../../components/business/CategoryIcon';
import { useDashboard, useStyles } from '../../hooks';
import { nativeData } from '../../native';
import { formatPlatform } from '../../utils/platform';
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
}

interface GoalProgress extends FinancialGoal {
  progress: number;
  isCompleted: boolean;
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
                    {card.type === 'budget' ? '预算' : '目标'}
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
                      ? { color: '#FFFFFF' }
                      : undefined,
                  ]}
                >
                  {Math.round(card.progress)}%
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

export default function DashboardScreen() {
  const navigation = useNavigation();
  const styles = useStyles(createStyles);
  const {
    recentBills,
    monthIncome,
    monthExpense,
    monthBalance,
    isLoading,
    isFetching,
    refetch,
  } = useDashboard();

  const { data: categories = [] } = useQuery({
    queryKey: QUERY_KEYS.categories.all,
    queryFn: () => nativeData.getCategories(),
    staleTime: CACHE_TIME.CATEGORIES,
    gcTime: GC_TIME.DEFAULT,
  });
  const categoryById = useMemo(
    () =>
      new Map<string, Category>(
        categories.map(category => [category.id, category]),
      ),
    [categories],
  );

  const { data: budgetProgressData, refetch: refetchBudgets } = useQuery({
    queryKey: QUERY_KEYS.budgetProgress,
    queryFn: () => nativeData.getBudgetProgress(),
  });

  const { data: goalProgressData, refetch: refetchGoals } = useQuery({
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
  });

  const progressCards: ProgressCardItem[] = useMemo(() => {
    const cards: ProgressCardItem[] = [];

    (budgetProgressData ?? []).forEach((b: BudgetProgress) => {
      cards.push({
        id: `budget-${b.id}`,
        type: 'budget',
        title: b.name,
        icon: '📊',
        progress: b.progress * 100,
        currentAmount: b.spentMinor / 100,
        totalAmount: b.amountMinor / 100,
        color: styles._colors.primary,
        labelLeft: `已用 ¥${(b.spentMinor / 100).toLocaleString()}`,
        labelRight: `剩余 ¥${Math.max(
          0,
          b.remainingMinor / 100,
        ).toLocaleString()}`,
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
          color: styles._colors.success,
          labelLeft: `已存 ¥${(g.currentAmountMinor / 100).toLocaleString()}`,
          labelRight: `目标 ¥${(g.targetAmountMinor / 100).toLocaleString()}`,
        });
      });

    return cards;
  }, [budgetProgressData, goalProgressData, styles._colors]);

  const currentDate = new Date();
  const monthName = `${currentDate.getMonth() + 1}月账本`;
  const yearName = `${currentDate.getFullYear()}`;

  const onRefresh = useCallback(async () => {
    await Promise.all([refetch(), refetchBudgets(), refetchGoals()]);
  }, [refetch, refetchBudgets, refetchGoals]);

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

  const getCategoryIcon = (categoryName?: string): string => {
    const iconMap: { [key: string]: string } = {
      餐饮: '☕',
      交通: '🚗',
      购物: '🛍️',
      收入: '💰',
      工资: '💼',
      娱乐: '🎮',
      医疗: '🏥',
      教育: '📚',
    };
    return iconMap[categoryName || ''] || '📝';
  };

  const getCategoryBlockColor = (categoryName?: string): string => {
    const colorMap: { [key: string]: string } = {
      餐饮: '#FACC15',
      交通: '#3B82F6',
      购物: '#EC4899',
      收入: '#22C55E',
      工资: '#22C55E',
      娱乐: '#A855F7',
      医疗: '#EF4444',
      教育: '#F97316',
    };
    return colorMap[categoryName || ''] || '#E5E5E5';
  };

  if (isLoading) {
    return <DashboardSkeleton />;
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
      <View style={styles.header}>
        <View>
          <Text style={styles.headerTitle}>{monthName}</Text>
          <Text style={styles.headerSubtitle}>{yearName}</Text>
        </View>
      </View>

      <View style={styles.overviewCard}>
        <View style={styles.overviewContent}>
          <View style={styles.overviewLabelRow}>
            <Text style={styles.overviewLabel}>本月支出</Text>
            <TouchableOpacity onPress={handleViewExpense} activeOpacity={0.75}>
              <Text style={styles.overviewLink}>支出明细 →</Text>
            </TouchableOpacity>
          </View>
          <Text style={styles.overviewBalance}>
            ¥ {monthExpense.toFixed(2)}
          </Text>

          <View style={styles.overviewDivider} />

          <View style={styles.overviewStats}>
            <TouchableOpacity
              style={styles.statBlock}
              onPress={handleViewIncome}
              activeOpacity={0.75}
            >
              <Text style={styles.statLabel}>本月收入</Text>
              <Text style={styles.statValue}>¥ {monthIncome.toFixed(2)}</Text>
            </TouchableOpacity>
            <View style={styles.statDivider} />
            <View style={styles.statBlock}>
              <Text style={styles.statLabel}>本月结余</Text>
              <Text style={styles.statValue}>¥ {monthBalance.toFixed(2)}</Text>
            </View>
          </View>
        </View>
      </View>

      {progressCards.length > 0 ? (
        <ProgressCardCarousel
          cards={progressCards}
          styles={styles}
          colors={styles._colors}
        />
      ) : (
        <TouchableOpacity
          style={styles.progressEmptyCard}
          onPress={() => navigation.navigate('FinancialGoals' as never)}
          activeOpacity={0.8}
        >
          <Text style={styles.progressEmptyIcon}>📊</Text>
          <Text style={styles.progressEmptyText}>
            设置预算或财务目标来追踪进度
          </Text>
          <Text style={styles.progressEmptyHint}>点击前往设置 →</Text>
        </TouchableOpacity>
      )}

      <View style={styles.section}>
        <View style={styles.sectionHeader}>
          <Text style={styles.sectionTitle}>近期交易</Text>
          <TouchableOpacity
            onPress={handleViewAllBills}
            style={styles.viewAllButton}
            activeOpacity={0.75}
          >
            <Text style={styles.viewAllText}>查看全部 →</Text>
          </TouchableOpacity>
        </View>

        {recentBills.length > 0 ? (
          <View style={styles.transactionList}>
            {recentBills.slice(0, 5).map((rawBill, index) => {
              const category = rawBill.categoryId
                ? categoryById.get(rawBill.categoryId)
                : undefined;
              const categoryName =
                category?.name ||
                rawBill.merchant ||
                rawBill.productDescription ||
                formatPlatform(rawBill.platform) ||
                '未分类';
              const categoryIcon =
                category?.icon || getCategoryIcon(category?.name);
              const bill = {
                ...rawBill,
                description:
                  rawBill.description ||
                  rawBill.merchant ||
                  rawBill.productDescription ||
                  categoryName,
                platform: formatPlatform(rawBill.platform) || categoryName,
              };

              return (
                <TouchableOpacity
                  key={bill.id}
                  style={[
                    styles.transactionItem,
                    index > 0 && styles.transactionItemBorder,
                  ]}
                  onPress={() => handleViewBillDetail(bill)}
                  activeOpacity={0.72}
                >
                  <View style={styles.transactionLeft}>
                    <View
                      style={[
                        styles.transactionIcon,
                        {
                          backgroundColor: getCategoryBlockColor(
                            category?.name,
                          ),
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
                        {bill.description ||
                          bill.merchant ||
                          bill.productDescription ||
                          '未分类'}
                      </Text>
                      <Text style={styles.transactionMeta} numberOfLines={1}>
                        {new Date(bill.occurredAt).toLocaleDateString('zh-CN', {
                          month: 'short',
                          day: 'numeric',
                        })}{' '}
                        · {formatPlatform(bill.platform) || '未分类'}
                      </Text>
                    </View>
                  </View>
                  <Text
                    style={[
                      styles.transactionAmount,
                      bill.type === 'income' || bill.type === 'refund'
                        ? styles.incomeAmount
                        : styles.expenseAmount,
                    ]}
                  >
                    {bill.type === 'income' || bill.type === 'refund' ? '+' : '-'}¥
                    {(bill.amountMinor / 100).toFixed(2)}
                  </Text>
                </TouchableOpacity>
              );
            })}
          </View>
        ) : (
          <View style={styles.emptyState}>
            <Text style={styles.emptyIcon}>📝</Text>
            <Text style={styles.emptyText}>暂无交易记录</Text>
            <TouchableOpacity
              style={styles.emptyButton}
              onPress={handleAddBill}
              activeOpacity={0.8}
            >
              <Text style={styles.emptyButtonText}>添加第一笔</Text>
            </TouchableOpacity>
          </View>
        )}
      </View>
    </ScrollView>
  );
}

const createStyles = (colors: ThemeColors) => ({
  ...StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: colors.background,
    },
    content: {
      paddingHorizontal: spacing.lg,
      paddingTop: spacing.xxl,
      paddingBottom: spacing.xxxxl,
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
      color: 'rgba(255, 255, 255, 0.82)',
      letterSpacing: 0.5,
    },
    overviewLink: {
      fontSize: 12,
      fontWeight: '600',
      color: '#FFFFFF',
      paddingVertical: spacing.xs,
    },
    overviewBalance: {
      fontSize: 40,
      fontWeight: '800',
      color: '#FFFFFF',
      marginTop: spacing.sm,
      letterSpacing: -1,
      fontVariant: ['tabular-nums'],
    },
    overviewDivider: {
      height: borderWidth.hairline,
      backgroundColor: 'rgba(255, 255, 255, 0.24)',
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
      backgroundColor: 'rgba(255, 255, 255, 0.24)',
      marginHorizontal: spacing.lg,
    },
    statLabel: {
      fontSize: 12,
      color: 'rgba(255, 255, 255, 0.72)',
      fontWeight: '500',
    },
    statValue: {
      fontSize: 17,
      fontWeight: '700',
      color: '#FFFFFF',
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
      borderRadius: borderRadius.card,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      padding: spacing.lg,
      ...shadow.soft,
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
      fontSize: 20,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    viewAllButton: {
      paddingHorizontal: spacing.xs,
      paddingVertical: spacing.xs,
    },
    viewAllText: {
      fontSize: 13,
      fontWeight: '700',
      color: colors.primary,
    },
    transactionList: {
      backgroundColor: colors.surface,
      borderRadius: borderRadius.card,
      borderWidth: borderWidth.hairline,
      borderColor: colors.divider,
      overflow: 'hidden',
    },
    transactionItem: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      padding: spacing.md,
      backgroundColor: colors.surface,
    },
    transactionItemBorder: {
      borderTopWidth: borderWidth.hairline,
      borderTopColor: colors.divider,
    },
    transactionLeft: {
      flexDirection: 'row',
      alignItems: 'center',
      flex: 1,
      paddingRight: spacing.sm,
    },
    transactionIcon: {
      width: 42,
      height: 42,
      borderRadius: borderRadius.medium,
      borderWidth: borderWidth.hairline,
      borderColor: colors.stroke,
      alignItems: 'center',
      justifyContent: 'center',
      marginRight: spacing.md,
    },
    transactionIconText: {
      fontSize: 21,
    },
    transactionInfo: {
      flex: 1,
    },
    transactionTitle: {
      fontSize: 15,
      fontWeight: '600',
      color: colors.textPrimary,
      marginBottom: spacing.xs,
    },
    transactionMeta: {
      fontSize: 12,
      fontWeight: '400',
      color: colors.textTertiary,
    },
    transactionAmount: {
      fontSize: 15,
      fontWeight: '700',
      fontVariant: ['tabular-nums'],
    },
    incomeAmount: {
      color: colors.amountIncome,
    },
    expenseAmount: {
      color: colors.amountExpense,
    },
    emptyState: {
      alignItems: 'center',
      padding: spacing.xxl,
      backgroundColor: colors.surface,
      borderRadius: borderRadius.card,
      borderWidth: borderWidth.hairline,
      borderColor: colors.divider,
    },
    emptyIcon: {
      fontSize: 40,
      marginBottom: spacing.md,
    },
    emptyText: {
      fontSize: 15,
      fontWeight: '600',
      color: colors.textTertiary,
      marginBottom: spacing.lg,
    },
    emptyButton: {
      backgroundColor: colors.accent,
      paddingHorizontal: spacing.xl,
      paddingVertical: spacing.md,
      borderRadius: borderRadius.button,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      ...shadow.soft,
    },
    emptyButtonText: {
      fontSize: 15,
      fontWeight: '700',
      color: colors.textPrimary,
    },
  }),
  _colors: colors,
});

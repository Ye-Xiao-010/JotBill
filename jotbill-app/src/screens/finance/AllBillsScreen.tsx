/**
 * 全部账单页面 - Neo-Brutalism 风格
 * 描边筛选栏 + 描边月份分组 + 列表项
 */
import React, { useState, useCallback, useMemo } from 'react';
import {
  Image,
  View,
  Text,
  ScrollView,
  SectionList,
  RefreshControl,
  TouchableOpacity,
  StyleSheet,
  ActivityIndicator,
} from 'react-native';
import {
  useNavigation,
  useRoute,
  useFocusEffect,
} from '@react-navigation/native';
import { useQuery } from '@tanstack/react-query';
import { BillItem } from '../../components';
import { HogwartsStarDivider } from '../../components/ui';
import QueryErrorBanner from '../../components/ui/QueryErrorBanner';
import { useToast, useStyles, useRefreshControlProps } from '../../hooks';
import { useTheme } from '../../providers';
import { nativeData, type Account, type Category } from '../../native';
import { CACHE_TIME, GC_TIME, QUERY_KEYS } from '../../lib/queryClient';
import { formatPlatform } from '../../utils/platform';
import { formatCurrency } from '../../utils/format';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { HOGWARTS_FONTS, HOGWARTS_IMAGES } from '../../constants/hogwartsAssets';
import { spacing, borderRadius, typography } from '../../theme';
import {
  getCurrentMonthRange,
  getLastMonthRange,
  formatMonthDisplay,
} from '../../utils/date';
import type { Bill, BillFilter } from '../../native';


const AllBillsScreen: React.FC = () => {
  const navigation = useNavigation<any>();
  const route = useRoute();
  const { showError } = useToast();
  const { isHogwarts, colors } = useTheme();
  const styles = useStyles(createStyles);
  const refreshControlProps = useRefreshControlProps();

  const routeParams = route.params as
    | { initialFilter?: 'all' | 'income' | 'expense' }
    | undefined;

  const [groupedBills, setGroupedBills] = useState<{ [key: string]: Bill[] }>(
    {},
  );
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  /** 加载失败信息；非空时渲染失败态而非"暂无账单"空态 */
  const [loadError, setLoadError] = useState<string | null>(null);
  const [filter, setFilter] = useState<'all' | 'income' | 'expense'>(
    routeParams?.initialFilter || 'all',
  );
  const [timeRange, setTimeRange] = useState<
    'all' | 'month' | 'lastMonth' | 'custom'
  >('all');

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
  const { data: accounts = [], refetch: refetchAccounts } = useQuery({
    queryKey: QUERY_KEYS.accountsList,
    queryFn: () => nativeData.getAccounts(),
    staleTime: CACHE_TIME.CATEGORIES,
    gcTime: GC_TIME.DEFAULT,
  });
  const accountById = useMemo(
    () =>
      new Map<string, Account>(
        accounts.map(account => [account.id, account]),
      ),
    [accounts],
  );

  const fetchBills = useCallback(
    async (isRefresh = false) => {
      try {
        if (isRefresh) {
          setRefreshing(true);
        } else {
          setLoading(true);
        }

        const params: BillFilter = {};
        if (filter !== 'all') {
          params.type = filter;
        }

        if (timeRange === 'month') {
          const dateRange = getCurrentMonthRange();
          params.startDate = dateRange.startDate;
          params.endDate = dateRange.endDate;
        } else if (timeRange === 'lastMonth') {
          const dateRange = getLastMonthRange();
          params.startDate = dateRange.startDate;
          params.endDate = dateRange.endDate;
        }

        const bills: Bill[] = [];
        let page = 1;
        let hasMore = true;
        while (hasMore) {
          const response = await nativeData.getBills({
            ...params,
            page,
            limit: 100,
          });
          bills.push(...response.items);
          hasMore = response.hasMore;
          page += 1;
        }
        groupBillsByMonth(bills);
        setLoadError(null);
      } catch (error: any) {
        setLoadError(error?.message || '加载账单失败');
        showError(error.message || '加载账单失败');
      } finally {
        setLoading(false);
        setRefreshing(false);
      }
    },
    [filter, timeRange, showError],
  );

  const groupBillsByMonth = (billList: Bill[]) => {
    const grouped: { [key: string]: Bill[] } = {};
    billList.forEach(bill => {
      const key = formatMonthDisplay(bill.occurredAt);
      if (!grouped[key]) {
        grouped[key] = [];
      }
      grouped[key].push(bill);
    });
    setGroupedBills(grouped);
  };

  const calculateMonthTotal = useCallback((monthBills: Bill[]) => {
    // 口径与原生统计一致（BillDao.getSummary）：income/refund 计入、expense/fee 计出，
    // 转账/还款/调整不参与收支，否则一笔 5000 转账会让月合计虚减 5000。
    return monthBills.reduce((total, bill) => {
      const amount = bill.amountMinor / 100;
      if (bill.type === 'income' || bill.type === 'refund') return total + amount;
      if (bill.type === 'expense' || bill.type === 'fee') return total - amount;
      return total;
    }, 0);
  }, []);

  useFocusEffect(
    useCallback(() => {
      fetchBills();
    }, [fetchBills]),
  );

  const handleBillPress = (bill: Bill) => {
    navigation.navigate({
      name: 'BillDetail',
      params: { billId: bill.id },
    } as never);
  };

  const handleRefresh = () => {
    fetchBills(true);
    refetchAccounts();
  };

  /** 按月分组为 SectionList sections；月合计口径与原生统计一致 */
  const sections = useMemo(
    () =>
      Object.entries(groupedBills).map(([month, monthBills]) => ({
        title: month,
        total: calculateMonthTotal(monthBills),
        data: monthBills,
      })),
    [groupedBills, calculateMonthTotal],
  );

  return (
    <View style={styles.container}>
      {/* 筛选栏 */}
      <View style={styles.filterBar}>
        <ScrollView horizontal showsHorizontalScrollIndicator={false}>
          <TouchableOpacity
            style={[
              styles.filterBtn,
              filter === 'all' && styles.filterBtnActive,
            ]}
            onPress={() => setFilter('all')}
          >
            <Text
              style={[
                styles.filterText,
                filter === 'all' && styles.filterTextActive,
              ]}
            >
              全部
            </Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[
              styles.filterBtn,
              filter === 'expense' && styles.filterBtnActive,
            ]}
            onPress={() => setFilter('expense')}
          >
            <Text
              style={[
                styles.filterText,
                filter === 'expense' && styles.filterTextActive,
              ]}
            >
              支出
            </Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[
              styles.filterBtn,
              filter === 'income' && styles.filterBtnActive,
            ]}
            onPress={() => setFilter('income')}
          >
            <Text
              style={[
                styles.filterText,
                filter === 'income' && styles.filterTextActive,
              ]}
            >
              收入
            </Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[
              styles.filterBtn,
              timeRange === 'month' && styles.filterBtnActive,
            ]}
            onPress={() => setTimeRange('month')}
          >
            <Text
              style={[
                styles.filterText,
                timeRange === 'month' && styles.filterTextActive,
              ]}
            >
              本月
            </Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[
              styles.filterBtn,
              timeRange === 'lastMonth' && styles.filterBtnActive,
            ]}
            onPress={() => setTimeRange('lastMonth')}
          >
            <Text
              style={[
                styles.filterText,
                timeRange === 'lastMonth' && styles.filterTextActive,
              ]}
            >
              上月
            </Text>
          </TouchableOpacity>
        </ScrollView>
      </View>

      {/* 账单列表：SectionList 虚拟化，避免长账单 ScrollView+map 全量渲染 */}
      {loading && !refreshing ? (
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color={styles._colors.primary} />
          <Text style={styles.loadingText}>加载中...</Text>
        </View>
      ) : (
        <SectionList
          style={styles.scrollView}
          sections={sections}
          keyExtractor={item => String(item.id)}
          showsVerticalScrollIndicator={false}
          stickySectionHeadersEnabled={false}
          refreshControl={
            <RefreshControl {...refreshControlProps} refreshing={refreshing} onRefresh={handleRefresh} />
          }
          renderSectionHeader={({ section }) => (
            <View style={styles.monthHeader}>
              <Text style={styles.monthTitle}>{section.title}</Text>
              <Text
                style={[
                  styles.monthTotal,
                  {
                    color:
                      section.total >= 0
                        ? styles._colors.income
                        : styles._colors.expense,
                  },
                ]}
              >
                {formatCurrency(Math.abs(section.total))}
              </Text>
            </View>
          )}
          renderSectionFooter={() => <View style={styles.monthSection} />}
          renderItem={({ item: rawBill }) => {
            const category = rawBill.categoryId
              ? categoryById.get(rawBill.categoryId)
              : undefined;
            const account = rawBill.accountId
              ? accountById.get(rawBill.accountId)
              : undefined;
            const bill = {
              ...rawBill,
              merchant: rawBill.merchant || category?.name,
            };

            return (
              <BillItem
                bill={{
                  id: bill.id.toString(),
                  category:
                    bill.merchant ||
                    bill.productDescription ||
                    formatPlatform(bill.platform) ||
                    '未分类',
                  amount: bill.amountMinor / 100,
                  type:
                    bill.type === 'income' || bill.type === 'refund'
                      ? 'income'
                      : 'expense',
                  date: new Date(bill.occurredAt).toLocaleString('zh-CN'),
                  description: bill.description,
                  icon: category?.icon || '📝',
                }}
                account={account}
                onPress={() => handleBillPress(rawBill)}
              />
            );
          }}
          ListEmptyComponent={
            loadError ? (
              // 失败态与空态分离：加载失败不能渲染成"暂无账单记录"
              <QueryErrorBanner
                error={loadError}
                title="账单加载失败"
                hint="这不代表你没有账单记录"
                onRetry={() => fetchBills(true)}
              />
            ) : (
              <View style={styles.emptyContainer}>
                {isHogwarts ? (
                  <>
                    <Image
                      source={HOGWARTS_IMAGES.emptyOwl}
                      style={styles.emptyOwlArt}
                      resizeMode="contain"
                    />
                    <HogwartsStarDivider
                      color={colors.accent}
                      width={150}
                      style={styles.emptyStarDivider}
                    />
                  </>
                ) : (
                  <Text style={styles.emptyIcon}>📋</Text>
                )}
                <Text style={styles.emptyText}>
                  {isHogwarts ? '账卷尚无落墨记录' : '暂无账单记录'}
                </Text>
              </View>
            )
          }
        />
      )}
    </View>
  );
};

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    container: { flex: 1, backgroundColor: colors.background },
    filterBar: {
      backgroundColor: shape.isSoft ? colors.background : colors.surface,
      paddingHorizontal: spacing.lg,
      paddingVertical: spacing.md,
      borderBottomWidth: shape.isSoft ? 0 : shape.borderWidth.subtle,
      borderBottomColor: shape.isHogwarts ? colors.divider : colors.stroke,
    },
    filterBtn: {
      paddingHorizontal: spacing.lg,
      paddingVertical: spacing.sm,
      borderRadius: shape.isSoft || shape.isHogwarts ? shape.radius.pill : borderRadius.small,
      marginRight: spacing.sm,
      backgroundColor: colors.surface,
      borderWidth: shape.isSoft ? shape.borderWidth.card : shape.borderWidth.subtle,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.cardBorder : colors.stroke,
      ...(shape.isSoft ? shape.elevation.card : {}),
    },
    filterBtnActive: {
      backgroundColor: colors.primary,
      borderColor: shape.isSoft ? colors.primary : shape.isHogwarts ? colors.accent : colors.stroke,
    },
    filterText: {
      fontSize: 13,
      color: colors.textSecondary,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
    },
    filterTextActive: {
      color: colors.onPrimary,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
    },
    scrollView: { flex: 1 },
    monthSection: { marginBottom: spacing.md },
    monthHeader: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      paddingHorizontal: spacing.lg,
      paddingVertical: shape.isSoft ? spacing.sm : spacing.md,
      backgroundColor: shape.isSoft ? colors.background : shape.isHogwarts ? colors.surfaceMuted : colors.surface,
      borderBottomWidth: shape.isSoft ? 0 : shape.borderWidth.subtle,
      borderBottomColor: shape.isHogwarts ? colors.divider : colors.stroke,
    },
    monthTitle: {
      fontSize: shape.isSoft ? 14 : 15,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: shape.isSoft ? colors.textSecondary : colors.textPrimary,
    },
    monthTotal: {
      fontSize: shape.isSoft ? 14 : 15,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isSoft ? undefined : shape.isHogwarts ? HOGWARTS_FONTS.numeric : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
    },
    loadingContainer: {
      flex: 1,
      justifyContent: 'center',
      alignItems: 'center',
      paddingTop: 100,
    },
    loadingText: {
      marginTop: spacing.md,
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    emptyContainer: {
      flex: 1,
      justifyContent: 'center',
      alignItems: 'center',
      paddingTop: 80,
    },
    emptyOwlArt: {
      width: 128,
      height: 128,
      borderRadius: 16,
      borderWidth: 1,
      borderColor: colors.cardBorder,
      marginBottom: spacing.sm,
    },
    emptyStarDivider: {
      marginBottom: spacing.sm,
    },
    emptyIcon: { fontSize: 48, marginBottom: spacing.md },
    emptyText: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
  }),
  _colors: colors,
});

export default AllBillsScreen;

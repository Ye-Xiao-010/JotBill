/**
 * 全部账单页面
 * 支持关键词搜索、收支/时间/账户/分类多维筛选、按日期或金额排序，
 * 兼容柔光 / 经典积木 / 魔法羊皮纸三套主题（浅/深色）。
 */
import React, { useState, useCallback, useMemo, useEffect } from 'react';
import {
  Image,
  View,
  Text,
  TextInput,
  Modal,
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
import {
  ArrowUpDown,
  Check,
  ChevronDown,
  RotateCcw,
  Search,
  Tag,
  Wallet,
  X,
} from 'lucide-react-native';
import { BillItem } from '../../components';
import AccountIcon from '../../components/business/AccountIcon';
import CategoryIcon from '../../components/business/CategoryIcon';
import { HogwartsStarDivider } from '../../components/ui';
import QueryErrorBanner from '../../components/ui/QueryErrorBanner';
import { useToast, useStyles, useRefreshControlProps } from '../../hooks';
import { useTheme } from '../../providers';
import { nativeData, type Account, type Bill, type BillFilter, type Category } from '../../native';
import { CACHE_TIME, GC_TIME, QUERY_KEYS } from '../../lib/queryClient';
import { formatPlatform } from '../../utils/platform';
import { formatCurrency } from '../../utils/format';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { HOGWARTS_FONTS, HOGWARTS_IMAGES } from '../../constants/hogwartsAssets';
import { spacing, borderRadius, shadow, typography } from '../../theme';
import {
  getCurrentMonthRange,
  getLastMonthRange,
  formatMonthDisplay,
} from '../../utils/date';

const SEARCH_DEBOUNCE_MS = 250;

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

  const [bills, setBills] = useState<Bill[]>([]);
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  /** 加载失败信息；非空时渲染失败态而非"暂无账单"空态 */
  const [loadError, setLoadError] = useState<string | null>(null);

  // 多维筛选与排序状态
  const [searchText, setSearchText] = useState('');
  const [debouncedKeyword, setDebouncedKeyword] = useState('');
  const [filter, setFilter] = useState<'all' | 'income' | 'expense'>(
    routeParams?.initialFilter || 'all',
  );
  const [timeRange, setTimeRange] = useState<'all' | 'month' | 'lastMonth'>('all');
  const [selectedAccountId, setSelectedAccountId] = useState<string | null>(null);
  const [selectedCategoryId, setSelectedCategoryId] = useState<string | null>(null);
  const [sortBy, setSortBy] = useState<'date' | 'amount'>('date');
  const [showAccountModal, setShowAccountModal] = useState(false);
  const [showCategoryModal, setShowCategoryModal] = useState(false);

  useEffect(() => {
    const trimmed = searchText.trim();
    if (!trimmed) {
      setDebouncedKeyword('');
      return;
    }
    const timer = setTimeout(() => {
      setDebouncedKeyword(trimmed);
    }, SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [searchText]);

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

  /** 选中父分类时收集其自身及所有子分类 ID，避免漏掉挂在子分类下的账单 */
  const selectedCategoryIds = useMemo(() => {
    if (!selectedCategoryId) return null;
    const ids = new Set<string>([selectedCategoryId]);
    categories.forEach(cat => {
      if (cat.parentId === selectedCategoryId) {
        ids.add(cat.id);
      }
    });
    return ids;
  }, [selectedCategoryId, categories]);

  const selectableCategories = useMemo(() => {
    if (filter === 'all') return categories;
    return categories.filter(c => c.type === filter);
  }, [categories, filter]);

  const activeAccounts = useMemo(
    () =>
      [...accounts]
        .filter(a => a.isActive !== false)
        .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0)),
    [accounts],
  );

  const selectedAccount = selectedAccountId ? accountById.get(selectedAccountId) : undefined;
  const selectedCategory = selectedCategoryId ? categoryById.get(selectedCategoryId) : undefined;

  const hasActiveFilters = Boolean(
    debouncedKeyword ||
      filter !== 'all' ||
      timeRange !== 'all' ||
      selectedAccountId ||
      selectedCategoryId ||
      sortBy !== 'date',
  );

  const fetchBills = useCallback(
    async (isRefresh = false) => {
      try {
        if (isRefresh) {
          setRefreshing(true);
        } else {
          setLoading(true);
        }

        const params: BillFilter = {
          orderBy: sortBy,
          orderDirection: 'desc',
        };
        if (filter !== 'all') {
          params.type = filter;
        }
        if (selectedAccountId) {
          params.accountId = selectedAccountId;
        }
        // 单一叶子分类直接下推给 SQLite，含子分类的父分类在内存精准包含子分类
        if (selectedCategoryIds && selectedCategoryIds.size === 1) {
          params.categoryId = selectedCategoryId ?? undefined;
        }
        if (debouncedKeyword) {
          params.keyword = debouncedKeyword;
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

        const fetched: Bill[] = [];
        let page = 1;
        let hasMore = true;
        while (hasMore) {
          const response = await nativeData.getBills({
            ...params,
            page,
            limit: 100,
          });
          fetched.push(...response.items);
          hasMore = response.hasMore;
          page += 1;
        }

        const finalBills =
          selectedCategoryIds && selectedCategoryIds.size > 1
            ? fetched.filter(
                item => Boolean(item.categoryId) && selectedCategoryIds.has(item.categoryId!),
              )
            : fetched;

        setBills(finalBills);
        setLoadError(null);
      } catch (error: any) {
        setLoadError(error?.message || '加载账单失败');
        showError(error.message || '加载账单失败');
      } finally {
        setLoading(false);
        setRefreshing(false);
      }
    },
    [
      filter,
      timeRange,
      selectedAccountId,
      selectedCategoryId,
      selectedCategoryIds,
      debouncedKeyword,
      sortBy,
      showError,
    ],
  );

  const calculateNetTotal = useCallback((billList: Bill[]) => {
    // 口径与原生统计一致（BillDao.getSummary）：income/refund 计入、expense/fee 计出，
    // 转账/还款/调整不参与收支，否则一笔 5000 转账会让月合计虚减 5000。
    return billList.reduce((total, bill) => {
      const amount = bill.amountMinor / 100;
      if (bill.type === 'income' || bill.type === 'refund') return total + amount;
      if (bill.type === 'expense' || bill.type === 'fee') return total - amount;
      return total;
    }, 0);
  }, []);

  const summaryMetrics = useMemo(() => {
    let expense = 0;
    let income = 0;
    bills.forEach(bill => {
      const amount = bill.amountMinor / 100;
      if (bill.type === 'income' || bill.type === 'refund') {
        income += amount;
      } else if (bill.type === 'expense' || bill.type === 'fee') {
        expense += amount;
      }
    });
    return { count: bills.length, expense, income };
  }, [bills]);

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

  const handleResetFilters = () => {
    setSearchText('');
    setDebouncedKeyword('');
    setFilter('all');
    setTimeRange('all');
    setSelectedAccountId(null);
    setSelectedCategoryId(null);
    setSortBy('date');
  };

  const handleSelectType = (nextType: 'all' | 'income' | 'expense') => {
    setFilter(nextType);
    if (nextType !== 'all' && selectedCategory && selectedCategory.type !== nextType) {
      setSelectedCategoryId(null);
    }
  };

  /** 按日期排序时按月分组；按金额降序时单组全局排行避免跨月打断金额顺序 */
  const sections = useMemo(() => {
    if (bills.length === 0) return [];
    if (sortBy === 'amount') {
      return [
        {
          title: `金额由高到低 · 共 ${bills.length} 笔`,
          total: calculateNetTotal(bills),
          data: bills,
        },
      ];
    }
    const grouped: { [key: string]: Bill[] } = {};
    bills.forEach(bill => {
      const key = formatMonthDisplay(bill.occurredAt);
      if (!grouped[key]) {
        grouped[key] = [];
      }
      grouped[key].push(bill);
    });
    return Object.entries(grouped).map(([month, monthBills]) => ({
      title: month,
      total: calculateNetTotal(monthBills),
      data: monthBills,
    }));
  }, [bills, sortBy, calculateNetTotal]);

  return (
    <View style={styles.container}>
      {/* 搜索与筛选区 */}
      <View style={styles.filterBar}>
        <View style={styles.searchRow}>
          <Search size={16} color={styles._colors.textTertiary} />
          <TextInput
            testID="all-bills-search-input"
            style={styles.searchInput}
            value={searchText}
            onChangeText={setSearchText}
            placeholder={
              isHogwarts ? '搜索账卷商户、器物或附注...' : '搜索商户、商品说明或备注...'
            }
            placeholderTextColor={styles._colors.textTertiary}
            returnKeyType="search"
          />
          {searchText.length > 0 ? (
            <TouchableOpacity
              testID="all-bills-search-clear"
              style={styles.searchClearBtn}
              onPress={() => {
                setSearchText('');
                setDebouncedKeyword('');
              }}
              accessibilityLabel="清空搜索"
            >
              <X size={15} color={styles._colors.textSecondary} />
            </TouchableOpacity>
          ) : null}
        </View>

        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={styles.filterChipsRow}
        >
          <TouchableOpacity
            testID="filter-type-all"
            style={[styles.filterBtn, filter === 'all' && styles.filterBtnActive]}
            onPress={() => handleSelectType('all')}
          >
            <Text style={[styles.filterText, filter === 'all' && styles.filterTextActive]}>
              全部
            </Text>
          </TouchableOpacity>
          <TouchableOpacity
            testID="filter-type-expense"
            style={[styles.filterBtn, filter === 'expense' && styles.filterBtnActive]}
            onPress={() => handleSelectType('expense')}
          >
            <Text style={[styles.filterText, filter === 'expense' && styles.filterTextActive]}>
              支出
            </Text>
          </TouchableOpacity>
          <TouchableOpacity
            testID="filter-type-income"
            style={[styles.filterBtn, filter === 'income' && styles.filterBtnActive]}
            onPress={() => handleSelectType('income')}
          >
            <Text style={[styles.filterText, filter === 'income' && styles.filterTextActive]}>
              收入
            </Text>
          </TouchableOpacity>

          <TouchableOpacity
            testID="filter-time-month"
            style={[styles.filterBtn, timeRange === 'month' && styles.filterBtnActive]}
            onPress={() => setTimeRange(prev => (prev === 'month' ? 'all' : 'month'))}
          >
            <Text style={[styles.filterText, timeRange === 'month' && styles.filterTextActive]}>
              本月
            </Text>
          </TouchableOpacity>
          <TouchableOpacity
            testID="filter-time-lastMonth"
            style={[styles.filterBtn, timeRange === 'lastMonth' && styles.filterBtnActive]}
            onPress={() => setTimeRange(prev => (prev === 'lastMonth' ? 'all' : 'lastMonth'))}
          >
            <Text style={[styles.filterText, timeRange === 'lastMonth' && styles.filterTextActive]}>
              上月
            </Text>
          </TouchableOpacity>

          <TouchableOpacity
            testID="filter-account-btn"
            style={[styles.filterBtn, Boolean(selectedAccount) && styles.filterBtnActive]}
            onPress={() => setShowAccountModal(true)}
          >
            <Wallet
              size={13}
              color={selectedAccount ? styles._colors.onPrimary : styles._colors.textSecondary}
            />
            <Text
              style={[styles.filterText, Boolean(selectedAccount) && styles.filterTextActive]}
              numberOfLines={1}
            >
              {selectedAccount ? selectedAccount.name : '账户'}
            </Text>
            <ChevronDown
              size={12}
              color={selectedAccount ? styles._colors.onPrimary : styles._colors.textTertiary}
            />
          </TouchableOpacity>

          <TouchableOpacity
            testID="filter-category-btn"
            style={[styles.filterBtn, Boolean(selectedCategory) && styles.filterBtnActive]}
            onPress={() => setShowCategoryModal(true)}
          >
            <Tag
              size={13}
              color={selectedCategory ? styles._colors.onPrimary : styles._colors.textSecondary}
            />
            <Text
              style={[styles.filterText, Boolean(selectedCategory) && styles.filterTextActive]}
              numberOfLines={1}
            >
              {selectedCategory ? selectedCategory.name : '分类'}
            </Text>
            <ChevronDown
              size={12}
              color={selectedCategory ? styles._colors.onPrimary : styles._colors.textTertiary}
            />
          </TouchableOpacity>

          <TouchableOpacity
            testID="filter-sort-toggle"
            style={[styles.filterBtn, sortBy === 'amount' && styles.filterBtnActive]}
            onPress={() => setSortBy(prev => (prev === 'date' ? 'amount' : 'date'))}
          >
            <ArrowUpDown
              size={13}
              color={sortBy === 'amount' ? styles._colors.onPrimary : styles._colors.textSecondary}
            />
            <Text style={[styles.filterText, sortBy === 'amount' && styles.filterTextActive]}>
              {sortBy === 'amount' ? '金额降序' : '按时间'}
            </Text>
          </TouchableOpacity>
        </ScrollView>

        {hasActiveFilters ? (
          <View style={styles.summaryBar} testID="all-bills-filter-summary">
            <Text style={styles.summaryText} numberOfLines={1}>
              共 {summaryMetrics.count} 笔 · 支 {formatCurrency(summaryMetrics.expense)} / 收{' '}
              {formatCurrency(summaryMetrics.income)}
            </Text>
            <TouchableOpacity
              testID="all-bills-reset-filters"
              style={styles.resetBtn}
              onPress={handleResetFilters}
            >
              <RotateCcw size={12} color={styles._colors.primary} />
              <Text style={styles.resetBtnText}>重置</Text>
            </TouchableOpacity>
          </View>
        ) : null}
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
            <RefreshControl
              {...refreshControlProps}
              refreshing={refreshing}
              onRefresh={handleRefresh}
            />
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
                  <Text style={styles.emptyIcon}>{hasActiveFilters ? '🔍' : '📋'}</Text>
                )}
                <Text style={styles.emptyText}>
                  {hasActiveFilters
                    ? '没有找到符合条件的账单'
                    : isHogwarts
                      ? '账卷尚无落墨记录'
                      : '暂无账单记录'}
                </Text>
                {hasActiveFilters ? (
                  <TouchableOpacity style={styles.emptyResetBtn} onPress={handleResetFilters}>
                    <Text style={styles.emptyResetText}>清除筛选条件</Text>
                  </TouchableOpacity>
                ) : null}
              </View>
            )
          }
        />
      )}

      {/* 账户筛选弹窗 */}
      <Modal
        visible={showAccountModal}
        transparent
        animationType="fade"
        onRequestClose={() => setShowAccountModal(false)}
      >
        <TouchableOpacity
          style={styles.modalOverlay}
          activeOpacity={1}
          onPress={() => setShowAccountModal(false)}
        >
          <View style={styles.modalCard} testID="filter-account-modal">
            <Text style={styles.modalTitle}>按账户筛选</Text>
            <ScrollView style={styles.modalScroll} showsVerticalScrollIndicator={false}>
              <TouchableOpacity
                testID="filter-account-option-all"
                style={[styles.modalRow, !selectedAccountId && styles.modalRowActive]}
                onPress={() => {
                  setSelectedAccountId(null);
                  setShowAccountModal(false);
                }}
              >
                <Wallet size={18} color={styles._colors.primary} />
                <Text style={styles.modalRowText}>全部账户</Text>
                {!selectedAccountId ? <Check size={16} color={styles._colors.primary} /> : null}
              </TouchableOpacity>
              {activeAccounts.map(acc => {
                const selected = selectedAccountId === acc.id;
                return (
                  <TouchableOpacity
                    key={acc.id}
                    testID={`filter-account-option-${acc.id}`}
                    style={[styles.modalRow, selected && styles.modalRowActive]}
                    onPress={() => {
                      setSelectedAccountId(acc.id);
                      setShowAccountModal(false);
                    }}
                  >
                    <AccountIcon
                      icon={acc.icon}
                      name={acc.name}
                      subtype={acc.subtype}
                      size={18}
                      color={styles._colors.primary}
                    />
                    <Text style={styles.modalRowText} numberOfLines={1}>
                      {acc.name}
                      {acc.cardTail ? ` · ${acc.cardTail}` : ''}
                    </Text>
                    {selected ? <Check size={16} color={styles._colors.primary} /> : null}
                  </TouchableOpacity>
                );
              })}
            </ScrollView>
          </View>
        </TouchableOpacity>
      </Modal>

      {/* 分类筛选弹窗 */}
      <Modal
        visible={showCategoryModal}
        transparent
        animationType="fade"
        onRequestClose={() => setShowCategoryModal(false)}
      >
        <TouchableOpacity
          style={styles.modalOverlay}
          activeOpacity={1}
          onPress={() => setShowCategoryModal(false)}
        >
          <View style={styles.modalCard} testID="filter-category-modal">
            <Text style={styles.modalTitle}>按分类筛选</Text>
            <ScrollView style={styles.modalScroll} showsVerticalScrollIndicator={false}>
              <TouchableOpacity
                testID="filter-category-option-all"
                style={[styles.modalRow, !selectedCategoryId && styles.modalRowActive]}
                onPress={() => {
                  setSelectedCategoryId(null);
                  setShowCategoryModal(false);
                }}
              >
                <Tag size={18} color={styles._colors.primary} />
                <Text style={styles.modalRowText}>全部分类</Text>
                {!selectedCategoryId ? <Check size={16} color={styles._colors.primary} /> : null}
              </TouchableOpacity>
              {selectableCategories.map(cat => {
                const selected = selectedCategoryId === cat.id;
                return (
                  <TouchableOpacity
                    key={cat.id}
                    testID={`filter-category-option-${cat.id}`}
                    style={[styles.modalRow, selected && styles.modalRowActive]}
                    onPress={() => {
                      setSelectedCategoryId(cat.id);
                      setShowCategoryModal(false);
                    }}
                  >
                    <CategoryIcon
                      icon={cat.icon}
                      size={18}
                      color={cat.type === 'income' ? styles._colors.income : styles._colors.expense}
                    />
                    <Text style={styles.modalRowText} numberOfLines={1}>
                      {cat.name}
                      {cat.parentId ? '（子分类）' : ''}
                    </Text>
                    {selected ? <Check size={16} color={styles._colors.primary} /> : null}
                  </TouchableOpacity>
                );
              })}
            </ScrollView>
          </View>
        </TouchableOpacity>
      </Modal>
    </View>
  );
};

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    container: { flex: 1, backgroundColor: colors.background },
    filterBar: {
      backgroundColor: shape.isSoft ? colors.background : colors.surface,
      paddingHorizontal: spacing.lg,
      paddingTop: spacing.md,
      paddingBottom: spacing.sm + 2,
      gap: spacing.sm,
      borderBottomWidth: shape.isSoft ? 0 : shape.borderWidth.subtle,
      borderBottomColor: shape.isHogwarts ? colors.divider : colors.stroke,
    },
    searchRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
      paddingHorizontal: spacing.md,
      height: 40,
      borderRadius: shape.isSoft ? 14 : shape.radius.input,
      backgroundColor: shape.isSoft ? colors.surface : colors.background,
      borderWidth: shape.isSoft ? shape.borderWidth.card : shape.borderWidth.subtle,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.cardBorder : colors.stroke,
      ...(shape.isSoft ? shape.elevation.card : {}),
    },
    searchInput: {
      flex: 1,
      fontSize: 13.5,
      color: colors.textPrimary,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      paddingVertical: 0,
    },
    searchClearBtn: {
      padding: 4,
    },
    filterChipsRow: {
      alignItems: 'center',
      paddingVertical: 2,
    },
    filterBtn: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 4,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.xs + 3,
      borderRadius: shape.isSoft || shape.isHogwarts ? shape.radius.pill : borderRadius.small,
      marginRight: spacing.sm,
      backgroundColor: colors.surface,
      borderWidth: shape.isSoft ? shape.borderWidth.card : shape.borderWidth.subtle,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.cardBorder : colors.stroke,
      ...(shape.isSoft ? shape.elevation.card : {}),
      maxWidth: 140,
    },
    filterBtnActive: {
      backgroundColor: colors.primary,
      borderColor: shape.isSoft ? colors.primary : shape.isHogwarts ? colors.accent : colors.stroke,
    },
    filterText: {
      flexShrink: 1,
      fontSize: 12.5,
      color: colors.textSecondary,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
    },
    filterTextActive: {
      color: colors.onPrimary,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
    },
    summaryBar: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingHorizontal: spacing.sm + 2,
      paddingVertical: spacing.xs + 2,
      borderRadius: shape.radius.small,
      backgroundColor: colors.surfaceMuted,
      gap: spacing.sm,
    },
    summaryText: {
      flex: 1,
      fontSize: 12,
      color: colors.textSecondary,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.numeric : undefined,
      fontVariant: ['tabular-nums'],
    },
    resetBtn: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 4,
      paddingHorizontal: spacing.xs + 2,
      paddingVertical: 2,
    },
    resetBtnText: {
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.primary,
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
      gap: spacing.xs,
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
    emptyIcon: { fontSize: 48, marginBottom: spacing.sm },
    emptyText: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
    emptyResetBtn: {
      marginTop: spacing.sm,
      paddingHorizontal: spacing.lg,
      paddingVertical: spacing.sm,
      borderRadius: shape.radius.button,
      backgroundColor: colors.primaryLight,
    },
    emptyResetText: {
      fontSize: 13,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.primary,
    },
    modalOverlay: {
      flex: 1,
      backgroundColor: 'rgba(0, 0, 0, 0.45)',
      alignItems: 'center',
      justifyContent: 'center',
      padding: spacing.lg,
    },
    modalCard: {
      width: '100%',
      maxWidth: 380,
      maxHeight: '72%',
      backgroundColor: colors.surface,
      borderRadius: shape.radius.modal,
      borderWidth: shape.isSoft || shape.isHogwarts ? shape.borderWidth.card : shape.borderWidth.subtle,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.cardBorder : colors.stroke,
      padding: spacing.md,
      ...(shape.isSoft || shape.isHogwarts ? shape.elevation.floating : shadow.medium),
    },
    modalTitle: {
      fontSize: 16,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
      paddingHorizontal: spacing.xs,
      paddingBottom: spacing.sm,
    },
    modalScroll: {
      maxHeight: 360,
    },
    modalRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm + 2,
      paddingHorizontal: spacing.sm + 2,
      paddingVertical: spacing.sm + 3,
      borderRadius: shape.isSoft ? 12 : borderRadius.small,
    },
    modalRowActive: {
      backgroundColor: colors.primaryLight,
    },
    modalRowText: {
      flex: 1,
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
  }),
  _colors: colors,
});

export default AllBillsScreen;

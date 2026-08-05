/**
 * 全部账单页面 - Neo-Brutalism 风格
 * 描边筛选栏 + 描边月份分组 + 列表项
 */
import React, { useState, useCallback } from 'react';
import {
  View,
  Text,
  ScrollView,
  RefreshControl,
  TouchableOpacity,
  StyleSheet,
  ActivityIndicator,
} from 'react-native';
import { useNavigation, useRoute, useFocusEffect } from '@react-navigation/native';
import { BillItem } from '../../components';
import { useToast, useStyles } from '../../hooks';
import { nativeData } from '../../native';
import { ThemeColors } from '../../theme/colors';
import { spacing, borderRadius, borderWidth } from '../../theme';
import { getCurrentMonthRange, getLastMonthRange, formatMonthDisplay } from '../../utils/date';
import type { Bill, BillFilter } from '../../native';

const AllBillsScreen: React.FC = () => {
  const navigation = useNavigation<any>();
  const route = useRoute();
  const { showError } = useToast();
  const styles = useStyles(createStyles);

  const routeParams = route.params as { initialFilter?: 'all' | 'income' | 'expense' } | undefined;

  const [groupedBills, setGroupedBills] = useState<{ [key: string]: Bill[] }>({});
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [filter, setFilter] = useState<'all' | 'income' | 'expense'>(routeParams?.initialFilter || 'all');
  const [timeRange, setTimeRange] = useState<'all' | 'month' | 'lastMonth' | 'custom'>('all');

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
      } catch (error: any) {
        showError(error.message || '加载账单失败');
      } finally {
        setLoading(false);
        setRefreshing(false);
      }
    },
    [filter, timeRange, showError]
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

  const calculateMonthTotal = (monthBills: Bill[]) => {
    return monthBills.reduce((total, bill) => {
      const amount = bill.amountMinor / 100;
      const isPositive = bill.type === 'income' || bill.type === 'refund';
      return total + (isPositive ? amount : -amount);
    }, 0);
  };

 useFocusEffect(
    useCallback(() => {
      fetchBills();
    }, [fetchBills])
  );

  const handleBillPress = (bill: Bill) => {
    navigation.navigate({
      name: 'CreateBill',
      params: { bill },
    } as never);
  };

  const handleRefresh = () => {
    fetchBills(true);
  };

  return (
    <View style={styles.container}>
      {/* 筛选栏 */}
      <View style={styles.filterBar}>
        <ScrollView horizontal showsHorizontalScrollIndicator={false}>
          <TouchableOpacity
            style={[styles.filterBtn, filter === 'all' && styles.filterBtnActive]}
            onPress={() => setFilter('all')}
          >
            <Text style={[styles.filterText, filter === 'all' && styles.filterTextActive]}>全部</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.filterBtn, filter === 'expense' && styles.filterBtnActive]}
            onPress={() => setFilter('expense')}
          >
            <Text style={[styles.filterText, filter === 'expense' && styles.filterTextActive]}>支出</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.filterBtn, filter === 'income' && styles.filterBtnActive]}
            onPress={() => setFilter('income')}
          >
            <Text style={[styles.filterText, filter === 'income' && styles.filterTextActive]}>收入</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.filterBtn, timeRange === 'month' && styles.filterBtnActive]}
            onPress={() => setTimeRange('month')}
          >
            <Text style={[styles.filterText, timeRange === 'month' && styles.filterTextActive]}>本月</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.filterBtn, timeRange === 'lastMonth' && styles.filterBtnActive]}
            onPress={() => setTimeRange('lastMonth')}
          >
            <Text style={[styles.filterText, timeRange === 'lastMonth' && styles.filterTextActive]}>上月</Text>
          </TouchableOpacity>
        </ScrollView>
      </View>

      {/* 账单列表 */}
      <ScrollView
        style={styles.scrollView}
        showsVerticalScrollIndicator={false}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={handleRefresh} />}
      >
        {loading && !refreshing ? (
          <View style={styles.loadingContainer}>
            <ActivityIndicator size="large" color={styles._colors.primary} />
            <Text style={styles.loadingText}>加载中...</Text>
          </View>
        ) : Object.keys(groupedBills).length === 0 ? (
          <View style={styles.emptyContainer}>
            <Text style={styles.emptyIcon}>📋</Text>
            <Text style={styles.emptyText}>暂无账单记录</Text>
          </View>
        ) : (
          Object.entries(groupedBills).map(([month, monthBills]) => {
            const monthTotal = calculateMonthTotal(monthBills);

            return (
              <View key={month} style={styles.monthSection}>
                <View style={styles.monthHeader}>
                  <Text style={styles.monthTitle}>{month}</Text>
                  <Text style={[
                    styles.monthTotal,
                    { color: monthTotal >= 0 ? styles._colors.income : styles._colors.expense }
                  ]}>
                    ¥{Math.abs(monthTotal).toFixed(2)}
                  </Text>
                </View>

                <View style={styles.billList}>
                  {monthBills.map(bill => (
                    <BillItem
                      key={bill.id}
                      bill={{
                        id: bill.id.toString(),
                        category: bill.merchant || bill.productDescription || bill.platform || '未分类',
                        amount: bill.amountMinor / 100,
                        type: bill.type === 'income' || bill.type === 'refund' ? 'income' : 'expense',
                        date: new Date(bill.occurredAt).toLocaleString('zh-CN'),
                        description: bill.description,
                        icon: '📝',
                      }}
                      onPress={() => handleBillPress(bill)}
                    />
                  ))}
                </View>
              </View>
            );
          })
        )}
      </ScrollView>
    </View>
  );
};

const createStyles = (colors: ThemeColors) => ({
  ...StyleSheet.create({
    container: { flex: 1, backgroundColor: colors.background },
    filterBar: {
      backgroundColor: colors.surface,
      paddingHorizontal: spacing.lg,
      paddingVertical: spacing.md,
      borderBottomWidth: borderWidth.thin,
      borderBottomColor: colors.stroke,
    },
    filterBtn: {
      paddingHorizontal: spacing.lg,
      paddingVertical: spacing.sm,
      borderRadius: borderRadius.small,
      marginRight: spacing.sm,
      backgroundColor: colors.surface,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
    },
    filterBtnActive: {
      backgroundColor: colors.primary,
      borderColor: colors.stroke,
    },
    filterText: {
      fontSize: 13,
      color: colors.textSecondary,
      fontWeight: '700',
    },
    filterTextActive: { color: '#FFFFFF', fontWeight: '800' },
    scrollView: { flex: 1 },
    monthSection: { marginBottom: spacing.md },
    monthHeader: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      paddingHorizontal: spacing.lg,
      paddingVertical: spacing.md,
      backgroundColor: colors.surface,
      borderBottomWidth: borderWidth.thin,
      borderBottomColor: colors.stroke,
    },
    monthTitle: {
      fontSize: 15,
      fontWeight: '800',
      color: colors.textPrimary,
    },
    monthTotal: {
      fontSize: 15,
      fontWeight: '800',
      fontFamily: 'Courier',
    },
    billList: { backgroundColor: colors.background },
    loadingContainer: { flex: 1, justifyContent: 'center', alignItems: 'center', paddingTop: 100 },
    loadingText: { marginTop: spacing.md, fontSize: 15, fontWeight: '600', color: colors.textSecondary },
    emptyContainer: { flex: 1, justifyContent: 'center', alignItems: 'center', paddingTop: 100 },
    emptyIcon: { fontSize: 48, marginBottom: spacing.md },
    emptyText: { fontSize: 15, fontWeight: '700', color: colors.textTertiary },
  }),
  _colors: colors,
});

export default AllBillsScreen;

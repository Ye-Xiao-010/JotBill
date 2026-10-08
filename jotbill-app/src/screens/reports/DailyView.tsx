/**
 * DailyView - 日收支视图
 * 月日历网格 + 点击某天展示账单明细
 */
import React, { useState, useEffect } from 'react';
import { View, Text, ScrollView, RefreshControl, StyleSheet, ActivityIndicator, TouchableOpacity } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { ThemeColors } from '../../theme/colors';
import { spacing, borderRadius, borderWidth } from '../../theme/spacing';
import { useStyles } from '../../hooks';
import {
  useDailyGridData,
  useDailyBills,
  type ReportBill,
} from '../../hooks/useIncomeExpenseData';
import { CalendarGrid, PeriodNavigator } from '../../components/reports';
import QueryErrorBanner from '../../components/ui/QueryErrorBanner';
import { CalendarSkeleton } from '../../components/skeleton/ReportSkeletons';
import BillItem from '../../components/business/BillItem';
import type { BillData as BillItemData } from '../../components/business/BillItem';

interface DailyViewProps {
  initialYear?: number;
  initialMonth?: number;
  initialSelectedDay?: string | null;
}

export default function DailyView({
  initialYear,
  initialMonth,
  initialSelectedDay = null,
}: DailyViewProps) {
  const styles = useStyles(createStyles);
  const navigation = useNavigation<any>();
  const now = new Date();

  const [year, setYear] = useState(initialYear ?? now.getFullYear());
  const [month, setMonth] = useState(initialMonth ?? now.getMonth());
  const [selectedDay, setSelectedDay] = useState<string | null>(initialSelectedDay);

  // 当外部初始值变化时更新
  useEffect(() => {
    if (initialYear !== undefined) setYear(initialYear);
    if (initialMonth !== undefined) setMonth(initialMonth);
    if (initialSelectedDay !== undefined) setSelectedDay(initialSelectedDay);
  }, [initialYear, initialMonth, initialSelectedDay]);

  const {
    dailyMap,
    isLoading,
    isFetching,
    isError,
    error,
    refetch,
  } = useDailyGridData(year, month);
  const {
    bills,
    total: billsTotal,
    hasMore: billsHasMore,
    isLoading: billsLoading,
    isError: billsError,
    error: billsErrorDetail,
    refetch: refetchBills,
  } = useDailyBills(selectedDay);

  const handlePrev = () => {
    if (month === 0) {
      setYear(year - 1);
      setMonth(11);
    } else {
      setMonth(month - 1);
    }
    setSelectedDay(null);
  };

  const handleNext = () => {
    if (month === 11) {
      setYear(year + 1);
      setMonth(0);
    } else {
      setMonth(month + 1);
    }
    setSelectedDay(null);
  };

  const isNextDisabled = year === now.getFullYear() && month >= now.getMonth();

  const handleDayPress = (dateStr: string) => {
    setSelectedDay(selectedDay === dateStr ? null : dateStr);
  };

  const handleBillPress = (bill: BillItemData) => {
    navigation.navigate('BillDetail', { billId: bill.id });
  };

  // 映射本地账单到列表组件需要的显示格式。
  const mapBill = (bill: ReportBill): BillItemData => ({
    id: bill.id,
    category: bill.category?.name || '未分类',
    amount: bill.amount,
    type: bill.type,
    date: bill.date.split('T')[0],
    description: bill.description,
    icon: bill.category?.icon || '💰',
  });

  // 计算选中日的汇总
  const selectedDayData = selectedDay ? dailyMap.get(selectedDay) : null;

  // ⚠️ 加载中不渲染日历：dailyMap 是空 Map，CalendarGrid 会画出一张
  // "整月无记录"的日历 —— 用户以为这个月没记账。
  //
  // 但**只换内容区、保留 PeriodNavigator**：`isLoading` 在切换月份时同样为 true
  // （queryKey 变了、没有缓存数据），若整屏早返回，用户点"下个月"后
  // 连"2026年10月"这个标题和翻页按钮都消失了 —— 加载完才回来，
  // 比加载中看到空白日历更让人困惑。
  const showGridSkeleton = isLoading;

  return (
    <ScrollView
      style={styles.container}
      refreshControl={
        <RefreshControl refreshing={isFetching && !isLoading} onRefresh={refetch} />
      }
      showsVerticalScrollIndicator={false}
    >
      <PeriodNavigator
        title={`${year}年${month + 1}月`}
        onPrev={handlePrev}
        onNext={handleNext}
        disableNext={isNextDisabled}
      />

      {/* 日历数据失败时，dailyMap 为空 ⇒ CalendarGrid 画出一张"整月无记录"的
          日历，而实际上可能整月都有记录。必须显式说明。 */}
      {isError ? (
        <QueryErrorBanner
          error={error}
          title="日历数据加载失败"
          hint="下方日历显示为空白，不代表这个月没有记录。"
          onRetry={refetch}
        />
      ) : null}

      {showGridSkeleton ? (
        <CalendarSkeleton showNav={false} fill={false} />
      ) : (
        <CalendarGrid
          year={year}
          month={month}
          dailyData={dailyMap}
          selectedDay={selectedDay}
          onDayPress={handleDayPress}
        />
      )}

      {/* 选中日的明细 */}
      {!showGridSkeleton && selectedDay && (
        <View style={styles.detailSection}>
          <View style={styles.detailHeader}>
            <Text style={styles.detailTitle}>
              {parseInt(selectedDay.split('-')[1], 10)}月{parseInt(selectedDay.split('-')[2], 10)}日明细
            </Text>
            {selectedDayData && (
              <View style={styles.daySummary}>
                {selectedDayData.income > 0 && (
                  <Text style={styles.incomeText}>收 +{selectedDayData.income.toFixed(2)}</Text>
                )}
                {selectedDayData.expense > 0 && (
                  <Text style={styles.expenseText}>支 -{selectedDayData.expense.toFixed(2)}</Text>
                )}
              </View>
            )}
          </View>

          {billsError ? (
            <QueryErrorBanner
              error={billsErrorDetail}
              title="明细加载失败"
              hint="当天可能有记录但读不出来。"
              onRetry={refetchBills}
              style={styles.inlineError}
            />
          ) : billsLoading ? (
            <ActivityIndicator
              style={styles.loading}
              color={styles._colors.primary}
            />
          ) : bills.length > 0 ? (
            <>
              {bills.map((bill) => (
                <BillItem
                  key={bill.id}
                  bill={mapBill(bill)}
                  onPress={handleBillPress}
                />
              ))}
              {/* ⚠️ limit=100 的静默截断必须说出来。
                  上方汇总取自 dailyTrends（全量），下方明细只到 100 笔 ——
                  超过时会出现"汇总 ¥5,000、明细只列 ¥300"的自相矛盾画面，
                  而用户完全无从判断缺了多少。 */}
              {billsHasMore ? (
                <View style={styles.truncatedNotice}>
                  <Text style={styles.truncatedText}>
                    仅显示最近 {bills.length} 笔，当天共 {billsTotal} 笔。
                    上方汇算是全量金额，明细列表未全部展示。
                  </Text>
                  <TouchableOpacity
                    style={styles.truncatedButton}
                    onPress={() => {
                      void refetchBills();
                    }}
                  >
                    <Text style={styles.truncatedButtonText}>重试加载</Text>
                  </TouchableOpacity>
                </View>
              ) : null}
            </>
          ) : (
            <Text style={styles.emptyText}>当天暂无记录</Text>
          )}
        </View>
      )}

      <View style={{ height: spacing.xxxl }} />
    </ScrollView>
  );
}

const createStyles = (colors: ThemeColors) => ({
  ...StyleSheet.create({
    container: {
      flex: 1,
    },
    detailSection: {
      marginTop: spacing.lg,
    },
    detailHeader: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingHorizontal: spacing.lg,
      marginBottom: spacing.sm,
    },
    detailTitle: {
      fontSize: 15,
      fontWeight: '800',
      color: colors.textPrimary,
    },
    daySummary: {
      flexDirection: 'row',
      gap: spacing.md,
    },
    incomeText: {
      fontSize: 12,
      fontWeight: '700',
      fontFamily: 'Courier',
      color: colors.income,
    },
    expenseText: {
      fontSize: 12,
      fontWeight: '700',
      fontFamily: 'Courier',
      color: colors.expense,
    },
    loading: {
      padding: spacing.xxxl,
    },
    emptyText: {
      fontSize: 14,
      color: colors.textTertiary,
      textAlign: 'center',
      padding: spacing.xxxl,
    },
    inlineError: {
      margin: 0,
    },
    truncatedNotice: {
      marginTop: spacing.md,
      padding: spacing.md,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.warning,
      backgroundColor: colors.surface,
    },
    truncatedText: {
      fontSize: 12,
      lineHeight: 18,
      color: colors.textSecondary,
    },
    truncatedButton: {
      alignSelf: 'flex-start',
      marginTop: spacing.sm,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.xs,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: colors.primary,
    },
    truncatedButtonText: {
      fontSize: 12,
      fontWeight: '800',
      color: '#FFFFFF',
    },
  }),
  _colors: colors,
});

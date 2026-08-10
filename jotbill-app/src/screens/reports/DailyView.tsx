/**
 * DailyView - 日收支视图
 * 月日历网格 + 点击某天展示账单明细
 */
import React, { useState, useEffect } from 'react';
import { View, Text, ScrollView, RefreshControl, StyleSheet, ActivityIndicator } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { ThemeColors } from '../../theme/colors';
import { spacing } from '../../theme/spacing';
import { useStyles } from '../../hooks';
import {
  useDailyGridData,
  useDailyBills,
  type ReportBill,
} from '../../hooks/useIncomeExpenseData';
import { CalendarGrid, PeriodNavigator } from '../../components/reports';
import { Button } from '../../components/ui';
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

  const { dailyMap, isLoading, refetch } = useDailyGridData(year, month);
  const {
    bills,
    isLoading: billsLoading,
    isFetching: billsFetching,
    isError: billsError,
    error: billsQueryError,
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

  return (
    <ScrollView
      style={styles.container}
      refreshControl={<RefreshControl refreshing={isLoading} onRefresh={refetch} />}
      showsVerticalScrollIndicator={false}
    >
      <PeriodNavigator
        title={`${year}年${month + 1}月`}
        onPrev={handlePrev}
        onNext={handleNext}
        disableNext={isNextDisabled}
      />

      <CalendarGrid
        year={year}
        month={month}
        dailyData={dailyMap}
        selectedDay={selectedDay}
        onDayPress={handleDayPress}
      />

      {/* 选中日的明细 */}
      {selectedDay && (
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

          {billsLoading ? (
            <ActivityIndicator
              style={styles.loading}
              color={styles._colors.primary}
            />
          ) : billsError ? (
            <View style={styles.errorState}>
              <Text style={styles.errorTitle}>当天记录加载失败</Text>
              <Text style={styles.errorText}>
                {billsQueryError instanceof Error && billsQueryError.message
                  ? billsQueryError.message
                  : '本地账单明细读取失败，请重试。'}
              </Text>
              <Button
                title="重新加载"
                variant="outline"
                size="small"
                loading={billsFetching}
                onPress={() => {
                  void refetchBills();
                }}
              />
            </View>
          ) : bills.length > 0 ? (
            bills.map((bill) => (
              <BillItem
                key={bill.id}
                bill={mapBill(bill)}
                onPress={handleBillPress}
              />
            ))
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
    errorState: {
      alignItems: 'center',
      paddingHorizontal: spacing.lg,
      paddingVertical: spacing.xxl,
    },
    errorTitle: {
      fontSize: 15,
      fontWeight: '800',
      color: colors.error,
      textAlign: 'center',
      marginBottom: spacing.sm,
    },
    errorText: {
      fontSize: 13,
      color: colors.textSecondary,
      textAlign: 'center',
      marginBottom: spacing.md,
    },
    emptyText: {
      fontSize: 14,
      color: colors.textTertiary,
      textAlign: 'center',
      padding: spacing.xxxl,
    },
  }),
  _colors: colors,
});

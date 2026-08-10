/**
 * MonthlyView - 月收支视图
 * 12个月网格 + 点击某月展示每日汇总 + 点击某天跳转日收支
 */
import React, { useState, useEffect } from 'react';
import { View, Text, ScrollView, RefreshControl, StyleSheet, ActivityIndicator } from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { spacing } from '../../theme/spacing';
import { useStyles } from '../../hooks';
import { useMonthlyGridData, useDailyGridData } from '../../hooks/useIncomeExpenseData';
import { MonthGrid, PeriodNavigator, DailySummaryList } from '../../components/reports';
import { Button } from '../../components/ui';

interface MonthlyViewProps {
  initialYear?: number;
  initialSelectedMonth?: number | null;
  onJumpToDaily?: (year: number, month: number, day?: string) => void;
}

export default function MonthlyView({
  initialYear,
  initialSelectedMonth = null,
  onJumpToDaily,
}: MonthlyViewProps) {
  const styles = useStyles(createStyles);
  const now = new Date();

  const [year, setYear] = useState(initialYear ?? now.getFullYear());
  const [selectedMonth, setSelectedMonth] = useState<number | null>(initialSelectedMonth);

  // 当外部初始值变化时更新
  useEffect(() => {
    if (initialYear !== undefined) setYear(initialYear);
    if (initialSelectedMonth !== undefined) setSelectedMonth(initialSelectedMonth);
  }, [initialYear, initialSelectedMonth]);

  const {
    monthlyData,
    isFetching: monthlyFetching,
    isError: monthlyError,
    error: monthlyQueryError,
    refetch,
  } = useMonthlyGridData(year);

  // 当选中月份时，获取该月每日数据
  const {
    dailyMap,
    isLoading: dailyLoading,
    isFetching: dailyFetching,
    isError: dailyError,
    error: dailyQueryError,
    refetch: refetchDaily,
  } = useDailyGridData(
    year,
    selectedMonth ? selectedMonth - 1 : 0,
    selectedMonth !== null,
  );

  const isNextDisabled = year >= now.getFullYear();

  const handlePrev = () => {
    setYear(year - 1);
    setSelectedMonth(null);
  };

  const handleNext = () => {
    setYear(year + 1);
    setSelectedMonth(null);
  };

  const handleMonthPress = (month: number) => {
    setSelectedMonth(selectedMonth === month ? null : month);
  };

  const handleDayPress = (dateStr: string) => {
    if (onJumpToDaily) {
      const parts = dateStr.split('-');
      onJumpToDaily(parseInt(parts[0], 10), parseInt(parts[1], 10) - 1, dateStr);
    }
  };

  // 将 dailyMap 转换为 DailySummaryList 需要的数组格式
  const dailySummaryData = React.useMemo(() => {
    if (!selectedMonth) return [];
    const result: Array<{ date: string; income: number; expense: number }> = [];
    dailyMap.forEach((value, key) => {
      result.push({ date: key, ...value });
    });
    return result.sort((a, b) => a.date.localeCompare(b.date));
  }, [dailyMap, selectedMonth]);

  return (
    <ScrollView
      style={styles.container}
      refreshControl={
        <RefreshControl
          refreshing={monthlyFetching || dailyFetching}
          onRefresh={refetch}
        />
      }
      showsVerticalScrollIndicator={false}
    >
      <PeriodNavigator
        title={`${year}年`}
        onPrev={handlePrev}
        onNext={handleNext}
        disableNext={isNextDisabled}
      />

      {monthlyError ? (
        <View style={styles.errorState}>
          <Text style={styles.errorTitle}>月度收支统计加载失败</Text>
          <Text style={styles.errorText}>
            {monthlyQueryError instanceof Error && monthlyQueryError.message
              ? monthlyQueryError.message
              : '本地月度统计读取失败，请重试。'}
          </Text>
          <Button
            title="重新加载"
            variant="outline"
            size="small"
            loading={monthlyFetching}
            onPress={() => {
              void refetch();
            }}
          />
        </View>
      ) : (
        <MonthGrid
          year={year}
          monthlyData={monthlyData}
          selectedMonth={selectedMonth}
          onMonthPress={handleMonthPress}
        />
      )}

      {/* 选中月的每日汇总 */}
      {selectedMonth && !monthlyError && (
        <View style={styles.detailSection}>
          <Text style={styles.detailTitle}>
            {year}年{selectedMonth}月 每日收支
          </Text>
          {dailyLoading ? (
            <ActivityIndicator
              style={styles.loading}
              color={styles._colors.primary}
            />
          ) : dailyError ? (
            <View style={styles.errorState}>
              <Text style={styles.errorTitle}>月内日统计加载失败</Text>
              <Text style={styles.errorText}>
                {dailyQueryError instanceof Error && dailyQueryError.message
                  ? dailyQueryError.message
                  : '本地日统计读取失败，请重试。'}
              </Text>
              <Button
                title="重新加载"
                variant="outline"
                size="small"
                loading={dailyFetching}
                onPress={() => {
                  void refetchDaily();
                }}
              />
            </View>
          ) : (
            <DailySummaryList
              data={dailySummaryData}
              onDayPress={handleDayPress}
            />
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
    detailTitle: {
      fontSize: 15,
      fontWeight: '800',
      color: colors.textPrimary,
      paddingHorizontal: spacing.lg,
      marginBottom: spacing.md,
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
  }),
  _colors: colors,
});

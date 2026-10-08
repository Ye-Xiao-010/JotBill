/**
 * MonthlyView - 月收支视图
 * 12个月网格 + 点击某月展示每日汇总 + 点击某天跳转日收支
 */
import React, { useState, useEffect } from 'react';
import { View, Text, ScrollView, RefreshControl, StyleSheet } from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { spacing } from '../../theme/spacing';
import { useStyles } from '../../hooks';
import { useMonthlyGridData, useDailyGridData } from '../../hooks/useIncomeExpenseData';
import { MonthGrid, PeriodNavigator, DailySummaryList } from '../../components/reports';
import QueryErrorBanner from '../../components/ui/QueryErrorBanner';
import { GridSkeleton } from '../../components/skeleton/ReportSkeletons';

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
    isLoading,
    isFetching,
    isError,
    error,
    refetch,
  } = useMonthlyGridData(year);

  // 当选中月份时，获取该月每日数据（未选月份不发请求）
  const {
    dailyMap,
    isError: dailyError,
    error: dailyErrorDetail,
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

  // ⚠️ 加载中不渲染网格：monthlyData 为空数组，MonthGrid 会画出 12 个全 0 的
  // 月份 —— 读起来是"这一年一分钱没花"。
  //
  // 与 DailyView 同理，`isLoading` 在切换年份时也为 true，所以**只换内容区**，
  // 保留 PeriodNavigator（否则点"下一年"后连标题和翻页按钮都消失）。
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
        title={`${year}年`}
        onPrev={handlePrev}
        onNext={handleNext}
        disableNext={isNextDisabled}
      />

      {/* 失败时 monthlyData 为空 ⇒ 12 个月格子全是 0，
          看上去就是"这一年什么都没花"。必须显式说明是读失败。 */}
      {isError ? (
        <QueryErrorBanner
          error={error}
          title="月度数据加载失败"
          hint="下方网格显示为 0，不代表这一年没有收支。"
          onRetry={refetch}
        />
      ) : null}

      {showGridSkeleton ? (
        <GridSkeleton columns={3} rows={4} fill={false} />
      ) : (
        <MonthGrid
          year={year}
          monthlyData={monthlyData}
          selectedMonth={selectedMonth}
          onMonthPress={handleMonthPress}
        />
      )}

      {/* 选中月的每日汇总 */}
      {!showGridSkeleton && selectedMonth && (
        <View style={styles.detailSection}>
          <Text style={styles.detailTitle}>
            {year}年{selectedMonth}月 每日收支
          </Text>
          {dailyError ? (
            <QueryErrorBanner
              error={dailyErrorDetail}
              title="每日汇总加载失败"
              hint="这个月可能仍有记录，但明细读不出来。"
              onRetry={refetchDaily}
              style={styles.inlineError}
            />
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
    inlineError: {
      margin: spacing.lg,
    },
  }),
  _colors: colors,
});

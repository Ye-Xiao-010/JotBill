/**
 * YearlyView - 年收支视图
 * 近5年网格 + 点击某年展示12月汇总 + 点击某月跳转月收支
 */
import React, { useState } from 'react';
import { View, Text, ScrollView, RefreshControl, StyleSheet } from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { spacing } from '../../theme/spacing';
import { useStyles } from '../../hooks';
import { useYearlyGridData } from '../../hooks/useIncomeExpenseData';
import { YearGrid, MonthlySummaryList } from '../../components/reports';
import QueryErrorBanner from '../../components/ui/QueryErrorBanner';
import { GridSkeleton } from '../../components/skeleton/ReportSkeletons';

interface YearlyViewProps {
  onJumpToMonthly?: (year: number, month?: number) => void;
}

export default function YearlyView({ onJumpToMonthly }: YearlyViewProps) {
  const styles = useStyles(createStyles);
  const [selectedYear, setSelectedYear] = useState<number | null>(null);

  const {
    yearlyData,
    getMonthlyDataForYear,
    isLoading,
    isFetching,
    isError,
    error,
    refetch,
  } = useYearlyGridData();

  const handleYearPress = (year: number) => {
    setSelectedYear(selectedYear === year ? null : year);
  };

  const handleMonthPress = (month: number) => {
    if (onJumpToMonthly && selectedYear) {
      onJumpToMonthly(selectedYear, month);
    }
  };

  // 补全选中年的12个月数据
  const fullMonthlyData = React.useMemo(() => {
    const monthlyForYear = selectedYear ? getMonthlyDataForYear(selectedYear) : [];
    const result = Array.from({ length: 12 }, (_, i) => ({
      month: i + 1,
      income: 0,
      expense: 0,
    }));
    monthlyForYear.forEach((m) => {
      const item = result.find((r) => r.month === m.month);
      if (item) {
        item.income = m.income;
        item.expense = m.expense;
      }
    });
    return result;
  }, [selectedYear, getMonthlyDataForYear]);

  // ⚠️ 加载中不渲染网格：yearlyData 永远返回补齐的 5 个零值年份，
  // 所以"还在读"时看到的是**整齐的全零五年柱状图** —— 比任何加载态都更像
  // 真实数据，最容易被读成"这五年我一分没花"。
  //
  // 同 MonthlyView：只换内容区，保留标题（YearlyView 没有 PeriodNavigator，
  // 标题在下方单独渲染，所以这里保留即可）。
  const showGridSkeleton = isLoading;

  return (
    <ScrollView
      style={styles.container}
      refreshControl={
        <RefreshControl refreshing={isFetching && !isLoading} onRefresh={refetch} />
      }
      showsVerticalScrollIndicator={false}
    >
      <View style={styles.header}>
        <Text style={styles.headerTitle}>近5年收支</Text>
      </View>

      {/* useYearlyGridData 失败时 yearlyData 仍是补齐的 5 个零值年份 ——
          这是最像"真实数据"的失败态：五年柱状图整齐地全为零，
          用户会得出"这五年我一分没花"的结论。必须先说读失败。 */}
      {isError ? (
        <QueryErrorBanner
          error={error}
          title="年度数据加载失败"
          hint="下方五年网格显示为 0，不代表这五年没有收支。"
          onRetry={refetch}
        />
      ) : null}

      {showGridSkeleton ? (
        <GridSkeleton columns={5} rows={1} fill={false} />
      ) : (
        <YearGrid
          years={yearlyData}
          selectedYear={selectedYear}
          onYearPress={handleYearPress}
        />
      )}

      {/* 选中年的12月汇总 */}
      {!showGridSkeleton && selectedYear && (
        <View style={styles.detailSection}>
          <Text style={styles.detailTitle}>
            {selectedYear}年 每月收支
          </Text>
          <MonthlySummaryList
            data={fullMonthlyData}
            onMonthPress={handleMonthPress}
          />
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
    header: {
      paddingHorizontal: spacing.lg,
      paddingVertical: spacing.md,
    },
    headerTitle: {
      fontSize: 18,
      fontWeight: '800',
      color: colors.textPrimary,
      textAlign: 'center',
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
  }),
  _colors: colors,
});

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
import { Button } from '../../components/ui';

interface YearlyViewProps {
  onJumpToMonthly?: (year: number, month?: number) => void;
}

export default function YearlyView({ onJumpToMonthly }: YearlyViewProps) {
  const styles = useStyles(createStyles);
  const [selectedYear, setSelectedYear] = useState<number | null>(null);

  const {
    yearlyData,
    getMonthlyDataForYear,
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

  return (
    <ScrollView
      style={styles.container}
      refreshControl={<RefreshControl refreshing={isFetching} onRefresh={refetch} />}
      showsVerticalScrollIndicator={false}
    >
      <View style={styles.header}>
        <Text style={styles.headerTitle}>近5年收支</Text>
      </View>

      {isError ? (
        <View style={styles.errorState}>
          <Text style={styles.errorTitle}>年度收支统计加载失败</Text>
          <Text style={styles.errorText}>
            {error instanceof Error && error.message
              ? error.message
              : '本地年度统计读取失败，请重试。'}
          </Text>
          <Button
            title="重新加载"
            variant="outline"
            size="small"
            loading={isFetching}
            onPress={() => {
              void refetch();
            }}
          />
        </View>
      ) : (
        <YearGrid
          years={yearlyData}
          selectedYear={selectedYear}
          onYearPress={handleYearPress}
        />
      )}

      {/* 选中年的12月汇总 */}
      {selectedYear && !isError && (
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

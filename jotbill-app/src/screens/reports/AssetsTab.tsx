/**
 * AssetsTab - 资产页签
 * 总资产(收入-支出) + 资产趋势折线图 + 收支构成饼图
 */
import React from 'react';
import { View, Text, ScrollView, RefreshControl, StyleSheet } from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { borderRadius, borderWidth, spacing, shadow } from '../../theme/spacing';
import { useStyles } from '../../hooks';
import { useAssetsData } from '../../hooks/useAssetsData';
import { AssetTrendChart } from '../../components/reports';
import AssetCompositionChart from '../../components/reports/AssetCompositionChart';
import QueryErrorBanner from '../../components/ui/QueryErrorBanner';
import { AssetsTabSkeleton } from '../../components/skeleton/ReportSkeletons';

export default function AssetsTab() {
  const styles = useStyles(createStyles);
  const {
    netWorth,
    totalAssets,
    totalLiabilities,
    totalIncome,
    totalExpense,
    assetTrendData,
    trendDateRange,
    incomeCategoryStats,
    expenseCategoryStats,
    isLoading,
    isFetching,
    isError,
    error,
    refetch,
  } = useAssetsData();

  const formatCurrency = (val: number) => {
    if (Math.abs(val) >= 10000) {
      return `¥${(val / 10000).toFixed(2)}万`;
    }
    return `¥${val.toFixed(2)}`;
  };

  // ⚠️ 首屏加载期间**不能**渲染真实布局。兜底值是 0，直接渲染就是
  // "净资产（账户余额）¥0.00 / 总资产 ¥0.00 / 总负债 ¥0.00"——
  // 用户打开统计页的第一眼是"我的钱没了"，随后数字才跳成真实值。
  // 这与"确实净资产为 0"在界面上完全一样，且没有任何办法自证。
  if (isLoading) return <AssetsTabSkeleton />;

  return (
    <ScrollView
      style={styles.container}
      refreshControl={
        <RefreshControl
          refreshing={isFetching && !isLoading}
          onRefresh={refetch}
        />
      }
      showsVerticalScrollIndicator={false}
    >
      {/* ⚠️ 失败时必须先说"读不出来"，再谈金额。
          兜底值是 0，若直接渲染就是"净资产（账户余额）¥0.00"——
          和"账户真的没钱"在界面上毫无区别，且没有任何办法自证。 */}
      {isError ? (
        <QueryErrorBanner
          error={error}
          title="资产数据加载失败"
          hint="下面的金额是占位 0，不代表你的真实余额。"
          onRetry={refetch}
        />
      ) : null}

      {/* 总资产卡片 */}
      <View style={styles.balanceCard}>
        <Text style={styles.balanceLabel}>净资产（账户余额）</Text>
        <Text style={styles.balanceAmount}>
          {formatCurrency(netWorth)}
        </Text>
        <View style={styles.balanceRow}>
          <View style={styles.balanceItem}>
            <Text style={styles.balanceItemLabel}>总资产</Text>
            <Text style={[styles.balanceItemValue, styles.assetValue]}>
              {formatCurrency(totalAssets)}
            </Text>
          </View>
          <View style={styles.balanceDivider} />
          <View style={styles.balanceItem}>
            <Text style={styles.balanceItemLabel}>总负债</Text>
            <Text style={[styles.balanceItemValue, styles.liabilityValue]}>
              {formatCurrency(totalLiabilities)}
            </Text>
          </View>
        </View>
        <View style={styles.secondaryRow}>
          <View style={styles.balanceItem}>
            <Text style={styles.balanceItemLabel}>累计收入</Text>
            <Text style={[styles.balanceItemValue, styles.assetValue]}>
              {formatCurrency(totalIncome)}
            </Text>
          </View>
          <View style={styles.balanceDivider} />
          <View style={styles.balanceItem}>
            <Text style={styles.balanceItemLabel}>累计支出</Text>
            <Text style={[styles.balanceItemValue, styles.liabilityValue]}>
              {formatCurrency(totalExpense)}
            </Text>
          </View>
        </View>
      </View>

      {/* 资产趋势折线图 */}
      <AssetTrendChart data={assetTrendData} dateRange={trendDateRange} />

      {/* 收支分类构成饼图 */}
      <AssetCompositionChart
        incomeCategoryStats={incomeCategoryStats}
        expenseCategoryStats={expenseCategoryStats}
        dateRange={trendDateRange}
      />

      <View style={{ height: spacing.xxxl }} />
    </ScrollView>
  );
}

const createStyles = (colors: ThemeColors) => ({
  ...StyleSheet.create({
    container: {
      flex: 1,
    },
    balanceCard: {
      margin: spacing.lg,
      backgroundColor: colors.primary,
      borderRadius: borderRadius.card,
      borderWidth: borderWidth.medium,
      borderColor: colors.stroke,
      padding: spacing.xl,
      ...shadow.medium,
    },
    balanceLabel: {
      fontSize: 13,
      fontWeight: '700',
      color: '#FFFFFF',
      opacity: 0.8,
    },
    balanceAmount: {
      fontSize: 32,
      fontWeight: '900',
      fontFamily: 'Courier',
      color: '#FFFFFF',
      marginTop: spacing.sm,
      marginBottom: spacing.lg,
    },
    balanceRow: {
      flexDirection: 'row',
      alignItems: 'center',
    },
    secondaryRow: {
      flexDirection: 'row',
      alignItems: 'center',
      marginTop: spacing.lg,
    },
    balanceItem: {
      flex: 1,
    },
    balanceDivider: {
      width: 1,
      height: 30,
      backgroundColor: 'rgba(255,255,255,0.3)',
      marginHorizontal: spacing.md,
    },
    balanceItemLabel: {
      fontSize: 12,
      fontWeight: '600',
      color: '#FFFFFF',
      opacity: 0.7,
      marginBottom: 4,
    },
    balanceItemValue: {
      fontSize: 16,
      fontWeight: '800',
      fontFamily: 'Courier',
    },
    assetValue: {
      color: '#4ADE80',
    },
    liabilityValue: {
      color: '#F87171',
    },
  }),
  _colors: colors,
});

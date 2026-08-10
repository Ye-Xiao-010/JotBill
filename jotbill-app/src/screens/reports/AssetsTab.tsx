/**
 * AssetsTab - 资产页签
 * 账户余额净资产 + 资产趋势折线图 + 收支构成饼图
 */
import React from 'react';
import { View, Text, ScrollView, RefreshControl, StyleSheet } from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { borderRadius, borderWidth, spacing, shadow } from '../../theme/spacing';
import { useStyles } from '../../hooks';
import { useAssetsData } from '../../hooks/useAssetsData';
import { AssetTrendChart } from '../../components/reports';
import AssetCompositionChart from '../../components/reports/AssetCompositionChart';
import { Button } from '../../components/ui';

export default function AssetsTab() {
  const styles = useStyles(createStyles);
  const {
    netWorth,
    totalAssets,
    totalLiabilities,
    totalIncome,
    totalExpense,
    assetTrendData,
    assetTrendUnavailable,
    trendDateRange,
    incomeCategoryStats,
    expenseCategoryStats,
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

  return (
    <ScrollView
      style={styles.container}
      refreshControl={
        <RefreshControl refreshing={isFetching} onRefresh={refetch} />
      }
      showsVerticalScrollIndicator={false}
    >
      {isError ? (
        <View style={styles.errorState}>
          <Text style={styles.errorTitle}>资产数据加载失败</Text>
          <Text style={styles.errorText}>
            {error instanceof Error && error.message
              ? error.message
              : '本地资产数据读取失败，请重试。'}
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
        <>
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
          <AssetTrendChart
            data={assetTrendData}
            dateRange={trendDateRange}
            unavailable={assetTrendUnavailable}
          />

          {/* 收支分类构成饼图 */}
          <AssetCompositionChart
            incomeCategoryStats={incomeCategoryStats}
            expenseCategoryStats={expenseCategoryStats}
            dateRange={trendDateRange}
          />
        </>
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

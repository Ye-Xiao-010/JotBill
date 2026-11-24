/**
 * AssetTrendChart - 资产趋势折线图
 * 使用 react-native-gifted-charts 展示近12月资产变动
 */
import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { LineChart } from 'react-native-gifted-charts';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { borderWidth, spacing, shadow } from '../../theme/spacing';
import { useStyles } from '../../hooks';
import { HOGWARTS_FONTS } from '../../constants/hogwartsAssets';
import { typography } from '../../theme';

interface AssetTrendChartProps {
  data: Array<{ month: string; value: number }>;
  dateRange?: string;
}

export default function AssetTrendChart({ data, dateRange }: AssetTrendChartProps) {
  const styles = useStyles(createStyles);

  if (data.length === 0) {
    return (
      <View style={styles.emptyContainer}>
        <Text style={styles.emptyText}>该区间暂无账单数据</Text>
      </View>
    );
  }

  const chartData = data.map((d) => ({
    value: d.value,
    label: d.month,
    labelTextStyle: {
      color: styles._colors.textTertiary,
      fontSize: 10,
      fontWeight: '600' as const,
    },
  }));

  const minVal = Math.min(...data.map((d) => d.value), 0);

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        {/* ⚠️ 口径已在 useAssetsData 修正：这条曲线是**账单收支净额累计**，
            不是账户余额历史（后者含期初余额与余额校准，无法由账单反推）。
            标题必须同步 —— 继续叫"资产变动趋势"会让用户以为它与上方
            "净资产（账户余额）"是同一个量，而两者恰好不是。 */}
        <Text style={styles.title}>收支净额累计</Text>
        {dateRange && <Text style={styles.dateRange}>{dateRange}</Text>}
      </View>
      <View style={styles.chartWrap}>
        <LineChart
          data={chartData}
          width={280}
          height={160}
          spacing={Math.max(280 / (data.length || 1), 30)}
          color={styles._colors.expense}
          thickness={3}
          startFillColor={styles._colors.expense + '30'}
          endFillColor={styles._colors.expense + '05'}
          startOpacity={0.3}
          endOpacity={0.05}
          areaChart
          curved
          dataPointsColor={styles._colors.expense}
          dataPointsRadius={4}
          yAxisColor={styles._colors.divider}
          xAxisColor={styles._colors.divider}
          yAxisTextStyle={{
            color: styles._colors.textTertiary,
            fontSize: 10,
            fontFamily: typography.fontFamily.mono,
          }}
          hideRules
          noOfSections={4}
          yAxisOffset={minVal < 0 ? minVal : 0}
          isAnimated
          animationDuration={600}
        />
      </View>
    </View>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    container: {
      marginHorizontal: spacing.lg,
      marginBottom: spacing.lg,
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      padding: spacing.lg,
      ...(shape.isHogwarts ? shape.elevation.card : shadow.small),
    },
    title: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
    },
    header: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      marginBottom: spacing.md,
    },
    dateRange: {
      fontSize: 12,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
    chartWrap: {
      alignItems: 'center',
      overflow: 'hidden',
    },
    emptyContainer: {
      marginHorizontal: spacing.lg,
      marginBottom: spacing.lg,
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      padding: spacing.xxxl,
      alignItems: 'center',
    },
    emptyText: {
      fontSize: 14,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
  }),
  _colors: colors,
});

/**
 * 报表页加载态骨架 - Neo-Brutalism 风格
 *
 * ⚠️ 为什么必须单独做，不能靠 `ActivityIndicator` 或空态顶替：
 *
 * 四个报表视图此前都解构了 `isLoading`，却**只用在 `refreshing` 表达式里**，
 * 首屏加载期间直接渲染真实布局 + 兜底值：
 *
 * | 视图 | 加载中实际显示 |
 * |---|---|
 * | `AssetsTab` | 净资产 ¥0.00 / 总资产 ¥0.00 / 总负债 ¥0.00 / 累计收入 ¥0.00 / 累计支出 ¥0.00 |
 * | `DailyView` | 一张空白日历（读起来像"整月无记录"） |
 * | `MonthlyView` | 12 个月全 0 的网格（读起来像"这一年没花钱"） |
 * | `YearlyView` | 近 5 年全 0 的柱状图（读起来像"这五年一分没花"） |
 *
 * "还在读" 和 "确实没有" 在界面上完全一样。而"净资产 ¥0.00"是最刺眼的那个
 * —— 用户打开统计页第一眼看到的是"我的钱没了"，然后数字才跳成真实值。
 *
 * 这与本轮已修的 `isError` 是同一个 bug 的另一半：那次把"读失败"和"没数据"
 * 分开了，但"还在读"仍然和"没数据"共用同一套渲染。三态（加载 / 失败 / 成功）
 * 必须各自可区分，缺一不可。
 *
 * 骨架屏沿用上游 LiteNote 已有的 `Skeleton` / `SkeletonText` 原语
 * （首页 `DashboardSkeleton` 与 AI 对话 `ChatSkeleton` 已在用），
 * 上游那个从未接线的 `ReportsSkeleton` 形状对不上本项目的 tab 布局，故不复活。
 */
import React from 'react';
import { View, StyleSheet } from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { borderRadius, borderWidth, spacing } from '../../theme/spacing';
import { useStyles } from '../../hooks';
import { Skeleton, SkeletonText, SkeletonCard } from './Skeleton';

/** 资产页：余额卡（主数字 + 两行副指标）+ 两块图表位。 */
export function AssetsTabSkeleton() {
  const styles = useStyles(createStyles);
  return (
    <View style={styles.container}>
      <View style={styles.balanceCard}>
        <SkeletonText width={110} height={13} style={styles.onPrimary} />
        <Skeleton width={180} height={34} borderRadius={8} style={styles.onPrimary} />
        <View style={styles.row}>
          <View style={styles.rowItem}>
            <SkeletonText width={52} height={12} style={styles.onPrimaryFaint} />
            <SkeletonText width={78} height={16} style={styles.onPrimary} />
          </View>
          <View style={styles.rowItem}>
            <SkeletonText width={52} height={12} style={styles.onPrimaryFaint} />
            <SkeletonText width={78} height={16} style={styles.onPrimary} />
          </View>
        </View>
        <View style={[styles.row, styles.rowSecond]}>
          <View style={styles.rowItem}>
            <SkeletonText width={52} height={12} style={styles.onPrimaryFaint} />
            <SkeletonText width={78} height={16} style={styles.onPrimary} />
          </View>
          <View style={styles.rowItem}>
            <SkeletonText width={52} height={12} style={styles.onPrimaryFaint} />
            <SkeletonText width={78} height={16} style={styles.onPrimary} />
          </View>
        </View>
      </View>

      <SkeletonCard style={styles.block}>
        <SkeletonText width={110} height={15} />
        <Skeleton width="100%" height={160} borderRadius={8} style={styles.gapTop} />
      </SkeletonCard>

      <SkeletonCard style={styles.block}>
        <SkeletonText width={130} height={15} />
        <View style={styles.legendRow}>
          <SkeletonCircleRow />
          <SkeletonCircleRow />
          <SkeletonCircleRow />
        </View>
      </SkeletonCard>
    </View>
  );
}

function SkeletonCircleRow() {
  const styles = useStyles(createStyles);
  return (
    <View style={styles.legendItem}>
      <View style={styles.legendRowInner}>
        <Skeleton width={14} height={14} borderRadius={7} />
        <SkeletonText width={44} height={12} />
      </View>
    </View>
  );
}

/**
 * 网格类视图（12 月 / 近 5 年）共用的骨架：
 * 导航条 + 一张方格网格。`columns` 与真实网格一致，避免加载完成时布局跳动。
 *
 * `fill=false` 用于嵌在 `ScrollView` 内容区里的场景：ScrollView 的直接子节点
 * 带 `flex: 1` 高度行为不可靠（内容容器高度由内容决定），会撑出空白或塌陷。
 */
export function GridSkeleton({
  columns = 4,
  rows = 3,
  fill = true,
}: {
  columns?: number;
  rows?: number;
  fill?: boolean;
}) {
  const styles = useStyles(createStyles);
  return (
    <View style={fill ? styles.container : styles.containerFlat}>
      <View style={styles.navRow}>
        <Skeleton width={26} height={26} borderRadius={6} />
        <SkeletonText width={96} height={16} />
        <Skeleton width={26} height={26} borderRadius={6} />
      </View>
      <SkeletonCard style={styles.block}>
        <View style={styles.grid}>
          {Array.from({ length: columns * rows }).map((_, index) => (
            <View key={index} style={styles.gridCell}>
              <Skeleton width="100%" height={44} borderRadius={6} />
            </View>
          ))}
        </View>
      </SkeletonCard>
    </View>
  );
}

/**
 * 日历视图骨架：7 列日历格。
 * 与 `CalendarGrid` 的 7 列一致，形状对得上，加载完成时不会跳。
 *
 * `showNav` 默认 true（自带一条假导航条，用于整屏骨架）；
 * 嵌在已有 `PeriodNavigator` 下面时传 false —— 否则会出现两个导航条，
 * 且假的那条没有真实标题（"2026年10月"）也无法翻页。
 */
export function CalendarSkeleton({
  showNav = true,
  fill = true,
}: {
  showNav?: boolean;
  fill?: boolean;
}) {
  const styles = useStyles(createStyles);
  return (
    <View style={showNav ? (fill ? styles.container : styles.containerFlat) : undefined}>
      {showNav ? (
        <View style={styles.navRow}>
          <Skeleton width={26} height={26} borderRadius={6} />
          <SkeletonText width={96} height={16} />
          <Skeleton width={26} height={26} borderRadius={6} />
        </View>
      ) : null}
      <SkeletonCard style={styles.block}>
        <View style={styles.weekRow}>
          {Array.from({ length: 7 }).map((_, index) => (
            <Skeleton key={index} width="100%" height={12} borderRadius={3} style={styles.weekCell} />
          ))}
        </View>
        {Array.from({ length: 5 }).map((_, rowIndex) => (
          <View key={rowIndex} style={styles.weekRow}>
            {Array.from({ length: 7 }).map((__, colIndex) => (
              <View key={colIndex} style={styles.weekCell}>
                <Skeleton width="100%" height={38} borderRadius={6} />
              </View>
            ))}
          </View>
        ))}
      </SkeletonCard>
    </View>
  );
}

const createStyles = (colors: ThemeColors) => ({
  ...StyleSheet.create({
    container: {
      flex: 1,
      paddingHorizontal: spacing.lg,
      paddingTop: spacing.lg,
    },
    /** 嵌在 ScrollView 内容区时用：同样内边距，但不占满高度。 */
    containerFlat: {
      paddingHorizontal: spacing.lg,
      paddingTop: spacing.lg,
    },
    navRow: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      marginBottom: spacing.md,
    },
    block: {
      marginTop: spacing.md,
    },
    balanceCard: {
      backgroundColor: colors.primary,
      borderRadius: borderRadius.card,
      borderWidth: borderWidth.medium,
      borderColor: colors.stroke,
      padding: spacing.xl,
    },
    // 余额卡是主色底，骨架要提亮才看得见（用白/半透明白）
    onPrimary: {
      backgroundColor: 'rgba(255,255,255,0.55)',
    },
    onPrimaryFaint: {
      backgroundColor: 'rgba(255,255,255,0.3)',
    },
    row: {
      flexDirection: 'row',
      gap: spacing.lg,
    },
    rowSecond: {
      marginTop: spacing.lg,
    },
    rowItem: {
      flex: 1,
      gap: 6,
    },
    gapTop: {
      marginTop: spacing.md,
    },
    legendRow: {
      flexDirection: 'row',
      gap: spacing.md,
      marginTop: spacing.md,
    },
    legendItem: {
      flex: 1,
    },
    legendRowInner: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.xs,
    },
    grid: {
      flexDirection: 'row',
      flexWrap: 'wrap',
    },
    gridCell: {
      // 4 列均分：100 / 4 再减去 gap 的均摊
      width: '23.5%',
      margin: '1.25%',
      height: 44,
    },
    weekRow: {
      flexDirection: 'row',
      marginBottom: spacing.xs,
    },
    weekCell: {
      // 7 列均分
      width: '13%',
      marginHorizontal: '0.5%',
    },
  }),
  _colors: colors,
});
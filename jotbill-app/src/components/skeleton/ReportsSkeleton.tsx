/**
 * 统计页面骨架屏 - Neo-Brutalism 风格
 * 描边骨架卡片 + 粗圆角
 */
import React from 'react';
import { View, StyleSheet, ScrollView } from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { borderRadius, borderWidth, spacing } from '../../theme/spacing';
import { useStyles } from '../../hooks';
import { Skeleton, SkeletonText } from './Skeleton';

export function ReportsSkeleton() {
  const styles = useStyles(createStyles);

  return (
    <ScrollView
      style={styles.container}
      contentContainerStyle={styles.content}
      showsVerticalScrollIndicator={false}
    >
      {/* Header */}
      <View style={styles.header}>
        <Skeleton width={24} height={24} borderRadius={8} />
        <SkeletonText width={120} height={18} />
        <Skeleton width={24} height={24} borderRadius={8} />
        <View style={styles.headerActions}>
          <Skeleton width={24} height={24} borderRadius={8} />
          <Skeleton width={24} height={24} borderRadius={8} />
        </View>
      </View>

      {/* Period Tabs */}
      <View style={styles.periodTabs}>
        <Skeleton width={60} height={32} borderRadius={borderRadius.small} />
        <Skeleton width={60} height={32} borderRadius={borderRadius.small} />
        <Skeleton width={60} height={32} borderRadius={borderRadius.small} />
      </View>

      {/* Total Display */}
      <View style={styles.totalSection}>
        <SkeletonText width={60} height={14} />
        <Skeleton width={180} height={36} borderRadius={8} style={{ marginTop: 8 }} />
        <SkeletonText width={140} height={14} style={{ marginTop: 12 }} />
      </View>

      {/* Analysis Tabs */}
      <View style={styles.analysisTabs}>
        <SkeletonText width={80} height={15} />
        <SkeletonText width={80} height={15} />
      </View>

      {/* Chart Section */}
      <View style={styles.section}>
        <View style={styles.sectionHeader}>
          <SkeletonText width={100} height={16} />
          <Skeleton width={24} height={24} borderRadius={8} />
        </View>
        <View style={styles.chartContainer}>
          <View style={styles.chartArea}>
            <Skeleton width="100%" height={120} borderRadius={8} />
          </View>
          <View style={styles.chartLabels}>
            {[1, 2, 3, 4, 5, 6, 7].map(i => (
              <SkeletonText key={i} width={30} height={12} />
            ))}
          </View>
        </View>
      </View>
    </ScrollView>
  );
}

/**
 * 分类列表骨架屏
 */
export function CategoryListSkeleton() {
  const styles = useStyles(createStyles);

  return (
    <View style={styles.categoryList}>
      {[1, 2, 3, 4, 5].map(i => (
        <View key={i} style={styles.categoryItem}>
          <Skeleton width={24} height={24} borderRadius={8} />
          <SkeletonText width={60} height={15} style={{ marginLeft: 12 }} />
          <View style={styles.categoryRight}>
            <SkeletonText width={50} height={15} />
            <SkeletonText width={30} height={12} style={{ marginTop: 4 }} />
          </View>
        </View>
      ))}
    </View>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: colors.background,
    },
    content: {
      paddingBottom: spacing.xxxxl,
    },
    header: {
      flexDirection: 'row',
      alignItems: 'center',
      paddingHorizontal: spacing.lg,
      paddingVertical: spacing.md,
      backgroundColor: shape.isSoft ? colors.background : colors.surface,
      gap: spacing.sm,
    },
    headerActions: {
      flexDirection: 'row',
      gap: spacing.sm,
      marginLeft: 'auto',
    },
    periodTabs: {
      flexDirection: 'row',
      justifyContent: 'center',
      gap: spacing.md,
      paddingVertical: spacing.lg,
      backgroundColor: shape.isSoft ? colors.background : colors.surface,
    },
    totalSection: {
      alignItems: 'center',
      paddingVertical: spacing.xxl,
      backgroundColor: shape.isSoft ? colors.background : colors.surface,
    },
    analysisTabs: {
      flexDirection: 'row',
      justifyContent: 'center',
      gap: spacing.xl,
      paddingVertical: spacing.md,
      backgroundColor: shape.isSoft ? colors.background : colors.surface,
      borderBottomWidth: shape.isSoft ? StyleSheet.hairlineWidth : borderWidth.thin,
      borderBottomColor: shape.isSoft ? colors.divider : colors.stroke,
    },
    section: {
      marginTop: spacing.lg,
      paddingHorizontal: spacing.lg,
    },
    sectionHeader: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      marginBottom: spacing.md,
    },
    chartContainer: {
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.isSoft ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isSoft ? colors.cardBorder : colors.stroke,
      padding: spacing.lg,
      minHeight: 200,
      ...(shape.isSoft ? shape.elevation.card : {}),
    },
    chartArea: {
      marginBottom: spacing.md,
    },
    chartLabels: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      paddingTop: spacing.sm,
    },
    categoryList: {
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.isSoft ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isSoft ? colors.cardBorder : colors.stroke,
      padding: spacing.md,
      ...(shape.isSoft ? shape.elevation.card : {}),
    },
    categoryItem: {
      flexDirection: 'row',
      alignItems: 'center',
      paddingVertical: spacing.md,
      borderBottomWidth: shape.isSoft ? StyleSheet.hairlineWidth : borderWidth.thin,
      borderBottomColor: colors.divider,
    },
    categoryRight: {
      alignItems: 'flex-end',
      marginLeft: 'auto',
    },
  }),
  _colors: colors,
});

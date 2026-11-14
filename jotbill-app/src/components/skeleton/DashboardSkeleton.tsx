/**
 * 首页骨架屏 - Neo-Brutalism 风格
 * 粗描边色块占位
 */
import React from 'react';
import { View, StyleSheet, ScrollView } from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { borderRadius, borderWidth, spacing, shadow } from '../../theme/spacing';
import { useStyles } from '../../hooks';
import { Skeleton, SkeletonText } from './Skeleton';

export function DashboardSkeleton({
  showBudgetCard = false,
}: {
  showBudgetCard?: boolean;
}) {
  const styles = useStyles(createStyles);

  return (
    <ScrollView
      style={styles.container}
      contentContainerStyle={styles.content}
      showsVerticalScrollIndicator={false}
    >
      {/* Header */}
      <View style={styles.header}>
        <View>
          <SkeletonText width={120} height={28} />
          <SkeletonText width={60} height={14} style={{ marginTop: 8 }} />
        </View>
      </View>

      {/* Overview Card */}
      <View style={styles.overviewCard}>
        <View style={styles.overviewHeader}>
          <View>
            <Skeleton width={80} height={14} borderRadius={4} style={{ backgroundColor: 'rgba(255,255,255,0.25)' }} />
            <Skeleton width={180} height={40} borderRadius={8} style={{ marginTop: 10, backgroundColor: 'rgba(255,255,255,0.25)' }} />
          </View>
        </View>
        <View style={styles.overviewDivider} />
        <View style={styles.overviewStats}>
          <View style={styles.statBlock}>
            <Skeleton width={56} height={12} borderRadius={3} style={{ backgroundColor: 'rgba(255,255,255,0.2)' }} />
            <Skeleton width={100} height={17} borderRadius={4} style={{ marginTop: 8, backgroundColor: 'rgba(255,255,255,0.2)' }} />
          </View>
          <View style={styles.statBlock}>
            <Skeleton width={56} height={12} borderRadius={3} style={{ backgroundColor: 'rgba(255,255,255,0.2)' }} />
            <Skeleton width={100} height={17} borderRadius={4} style={{ marginTop: 8, backgroundColor: 'rgba(255,255,255,0.2)' }} />
          </View>
        </View>
      </View>

      {/* Budget Card：与首页实际开关保持一致，关闭时不占位 */}
      {showBudgetCard ? (
        <View style={styles.budgetCard}>
          <View style={styles.budgetHeader}>
            <SkeletonText width={100} height={20} />
            <View style={styles.percentBadge}>
              <Skeleton width={40} height={14} borderRadius={4} />
            </View>
          </View>
          <View style={styles.progressContainer}>
            <Skeleton width={'100%'} height={16} borderRadius={4} />
          </View>
          <View style={styles.budgetLabels}>
            <SkeletonText width={80} height={12} />
            <SkeletonText width={80} height={12} />
          </View>
        </View>
      ) : null}

      {/* Recent Transactions：按天分组的骨架构型 */}
      <View style={styles.section}>
        <View style={styles.sectionHeader}>
          <SkeletonText width={100} height={20} />
          <View style={styles.viewAllSkeleton}>
            <Skeleton width={60} height={14} borderRadius={4} />
          </View>
        </View>
        {[3, 2].map((rowCount, groupIndex) => (
          <View key={groupIndex} style={styles.dayGroup}>
            <View style={styles.dayHeader}>
              <View style={styles.dayHeaderLeft}>
                <Skeleton width={38} height={20} borderRadius={6} />
                <SkeletonText width={64} height={14} />
              </View>
              <SkeletonText width={72} height={12} />
            </View>
            <View style={styles.transactionList}>
              {Array.from({ length: rowCount }).map((_, rowIndex) => (
                <View
                  key={rowIndex}
                  style={[
                    styles.transactionItem,
                    rowIndex > 0 && styles.transactionItemBorder,
                  ]}
                >
                  <View style={styles.transactionContent}>
                    <View style={styles.transactionLeft}>
                      <Skeleton width={48} height={48} borderRadius={12} />
                      <View style={styles.transactionInfo}>
                        <SkeletonText width={100} height={15} />
                        <SkeletonText width={140} height={12} style={{ marginTop: 8 }} />
                      </View>
                    </View>
                    <View style={styles.amountBadge}>
                      <Skeleton width={60} height={15} borderRadius={4} />
                    </View>
                  </View>
                </View>
              ))}
            </View>
          </View>
        ))}
      </View>
    </ScrollView>
  );
}

const createStyles = (colors: ThemeColors) => ({
  ...StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: colors.background,
    },
    content: {
      paddingHorizontal: spacing.lg,
      paddingTop: spacing.xxl,
      paddingBottom: 80,
    },
    header: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'flex-start',
      marginBottom: spacing.xxl,
    },
    overviewCard: {
      backgroundColor: colors.primaryDark,
      borderRadius: borderRadius.card,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      padding: spacing.xl,
      marginBottom: spacing.xxl,
      ...shadow.soft,
    },
    overviewHeader: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'flex-start',
    },
    overviewDivider: {
      height: borderWidth.thin,
      backgroundColor: 'rgba(255, 255, 255, 0.3)',
      marginVertical: spacing.lg,
    },
    overviewStats: {
      flexDirection: 'row',
      gap: spacing.md,
    },
    statBlock: {
      flex: 1,
    },
    budgetCard: {
      backgroundColor: colors.surface,
      borderRadius: borderRadius.card,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      padding: spacing.lg,
      marginBottom: spacing.xxl,
      ...shadow.soft,
    },
    budgetHeader: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      marginBottom: spacing.md,
    },
    percentBadge: {
      backgroundColor: colors.divider,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.xs,
    },
    progressContainer: {
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      overflow: 'hidden',
      marginBottom: spacing.md,
    },
    budgetLabels: {
      flexDirection: 'row',
      justifyContent: 'space-between',
    },
    section: {
      marginBottom: spacing.xxl,
    },
    dayGroup: {
      marginBottom: spacing.lg,
    },
    dayHeader: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingHorizontal: spacing.xs,
      marginBottom: spacing.sm,
    },
    dayHeaderLeft: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
    },
    sectionHeader: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      marginBottom: spacing.lg,
    },
    viewAllSkeleton: {
      backgroundColor: colors.surface,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.xs,
    },
    transactionList: {
      backgroundColor: colors.surface,
      borderRadius: borderRadius.card,
      borderWidth: borderWidth.hairline,
      borderColor: colors.divider,
      overflow: 'hidden',
    },
    transactionItem: {
      backgroundColor: colors.surface,
      padding: spacing.md,
    },
    transactionItemBorder: {
      borderTopWidth: borderWidth.hairline,
      borderTopColor: colors.divider,
    },
    transactionContent: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
    },
    transactionLeft: {
      flexDirection: 'row',
      alignItems: 'center',
      flex: 1,
    },
    transactionInfo: {
      marginLeft: spacing.md,
      flex: 1,
    },
    amountBadge: {
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.xs,
      backgroundColor: colors.divider,
    },
  }),
  _colors: colors,
});

/**
 * 账单详情页面 - Neo-Brutalism 风格
 * 粗描边详情卡片 + 大糖果色分类图标 + Courier 金额
 */
import React, { useState, useCallback } from 'react';
import {
  View,
  Text,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  ActivityIndicator,
} from 'react-native';
import { useFocusEffect, useNavigation, useRoute } from '@react-navigation/native';
import { skipToken, useQuery } from '@tanstack/react-query';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { borderRadius, borderWidth, spacing, shadow } from '../../theme/spacing';
import { nativeData } from '../../native';
import { invalidateCache, QUERY_KEYS } from '../../lib/queryClient';
import { formatPlatform } from '../../utils/platform';
import { formatCurrency } from '../../utils/format';
import { useStyles } from '../../hooks';
import AccountIcon from '../../components/business/AccountIcon';
import CategoryIcon from '../../components/business/CategoryIcon';
import { HogwartsStarDivider } from '../../components/ui';
import QueryErrorBanner from '../../components/ui/QueryErrorBanner';
import { HOGWARTS_FONTS } from '../../constants/hogwartsAssets';
import { useAlert, useTheme } from '../../providers';
import { typography } from '../../theme';

export default function BillDetailScreen() {
  const navigation = useNavigation();
  const route = useRoute();
  const rawBillId = (route.params as { billId: string | number }).billId;
  const billId = String(rawBillId);
  const styles = useStyles(createStyles);
  const { isHogwarts, colors } = useTheme();
  const { alert, confirm } = useAlert();

  const [deleting, setDeleting] = useState(false);
  const { data: bill, isLoading: loading, error: loadError, refetch: fetchBill } = useQuery({
    queryKey: QUERY_KEYS.bills.detail(billId),
    queryFn: () => nativeData.getBillById(billId),
  });
  const categoryType = bill?.type === 'income' || bill?.type === 'refund' ? 'income' : 'expense';
  const { data: categories } = useQuery({
    queryKey: QUERY_KEYS.categories.byType(categoryType),
    queryFn: () => nativeData.getCategories(categoryType),
    enabled: Boolean(bill),
  });
  const accountId = bill?.accountId;
  const accountQuery = useQuery({
    queryKey: ['accounts', 'detail', accountId],
    queryFn: accountId ? () => nativeData.getAccountById(accountId) : skipToken,
  });
  const category = categories?.find(item => item.id === bill?.categoryId);
  const account = accountQuery.data;

  useFocusEffect(
    useCallback(() => {
      void fetchBill();
    }, [fetchBill]),
  );

  const handleEdit = () => {
    if (bill && bill.type !== 'adjustment') {
      navigation.navigate('CreateBill' as any, { bill });
    }
  };

  const handleDelete = () => {
    confirm(
      '确认删除',
      '确定要删除这条账单记录吗？此操作不可撤销。',
      async () => {
        try {
          setDeleting(true);
          await nativeData.deleteBill(billId);
          invalidateCache.bills();
          navigation.goBack();
        } catch (error) {
          console.error('Failed to delete bill:', error);
          alert('错误', '删除账单失败');
        } finally {
          setDeleting(false);
        }
      },
      undefined,
      { confirmText: '删除', destructive: true }
    );
  };

  const formatDate = (dateStr: string) => {
    const date = new Date(dateStr);
    return date.toLocaleDateString('zh-CN', {
      year: 'numeric',
      month: 'long',
      day: 'numeric',
    });
  };

  const formatTime = (dateStr: string) => {
    const date = new Date(dateStr);
    return date.toLocaleTimeString('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  if (loading) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" color={styles._colors.primary} />
      </View>
    );
  }

  if (!bill) {
    // 失败态与空态分离：加载失败给重试入口，成功但无数据才显示"账单不存在"
    if (loadError) {
      return (
        <View style={styles.loadingContainer}>
          <QueryErrorBanner
            error={loadError}
            title="账单详情加载失败"
            hint="这不代表这条账单不存在"
            onRetry={() => { void fetchBill(); }}
            style={styles.errorBanner}
          />
        </View>
      );
    }
    return (
      <View style={styles.loadingContainer}>
        <Text style={styles.errorText}>账单不存在</Text>
      </View>
    );
  }

  const isAdjustment = bill.type === 'adjustment';
  const isPositive = bill.type === 'income' || bill.type === 'refund';
  const typeLabel = isAdjustment
    ? '余额校准'
    : bill.type === 'refund'
      ? '退款'
      : bill.type === 'transfer'
        ? '转账'
        : bill.type === 'repayment'
          ? '还款'
          : bill.type === 'fee'
            ? '手续费'
            : isPositive
              ? '收入'
              : '支出';
  const amountColor = isPositive ? styles._colors.income : styles._colors.expense;
  const accountLabel = bill.type === 'refund'
    ? '退款到账账户'
    : bill.type === 'expense' || bill.type === 'fee'
      ? '扣款账户'
      : bill.type === 'income' ? '到账账户' : '账户';
  const accountName = account?.name ?? (bill.accountId
    ? accountQuery.isFetching ? '正在读取' : '账户未能读取'
    : '未关联账户');

  return (
    <View style={styles.container}>
      <ScrollView style={styles.scrollView} contentContainerStyle={styles.content}>
        {/* 金额区域 */}
        <View style={styles.amountSection}>
          <View
            style={[
              styles.categoryIconBlock,
              styles._isSoft && {
                backgroundColor: isPositive
                  ? styles._colors.incomeLight
                  : styles._colors.expenseLight,
              },
            ]}
          >
            <CategoryIcon
              icon={category?.icon}
              size={32}
              color={isHogwarts ? colors.primary : undefined}
              emojiStyle={styles.categoryEmoji}
            />
          </View>
          <Text
            style={[
              styles.typeBadge,
              {
                backgroundColor: styles._isSoft
                  ? (isPositive ? styles._colors.incomeLight : styles._colors.expenseLight)
                  : (isPositive ? styles._colors.income + '20' : styles._colors.expense + '20'),
                color: styles._isSoft
                  ? (isPositive ? styles._colors.incomeStrong : styles._colors.expenseStrong)
                  : amountColor,
              },
            ]}
          >
            {typeLabel}
          </Text>
          <Text
            style={[styles.amount, { color: amountColor }]}
            numberOfLines={1}
            adjustsFontSizeToFit
            minimumFontScale={0.6}
          >
            {formatCurrency(bill.amountMinor / 100)}
          </Text>
          {isHogwarts ? (
            <HogwartsStarDivider
              color={colors.accent}
              width={160}
              style={styles.starDivider}
            />
          ) : null}
          <Text style={styles.categoryLabel}>{category?.name || '未分类'}</Text>
        </View>

        {/* 详情卡片 */}
        <View style={styles.detailCard}>
          <View style={styles.detailRow}>
            <Text style={styles.detailLabel}>分类</Text>
            <View style={styles.detailValueContainer}>
              {category?.icon && <CategoryIcon icon={category.icon} size={16} emojiStyle={styles.detailIcon} />}
              <Text style={styles.detailValue}>{category?.name || '未分类'}</Text>
            </View>
          </View>
          <View style={styles.divider} />
          <View style={styles.detailRow}>
            <Text style={styles.detailLabel}>时间</Text>
            <Text style={styles.detailValueCourier}>{`${formatDate(bill.occurredAt)} ${formatTime(bill.occurredAt)}`}</Text>
          </View>
          <View style={styles.divider} />
          <View style={styles.detailRow}>
            <Text style={styles.detailLabel}>{accountLabel}</Text>
            <View style={styles.accountValueContainer}>
              {account && <AccountIcon icon={account.icon} name={account.name} subtype={account.subtype} size={16} />}
              <Text style={[styles.detailValue, styles.accountName]}>{accountName}</Text>
            </View>
          </View>
          {bill.platform && (
            <>
              <View style={styles.divider} />
              <View style={styles.detailRow}>
                <Text style={styles.detailLabel}>来源</Text>
                <Text style={styles.detailValue}>{formatPlatform(bill.platform)}</Text>
              </View>
            </>
          )}
          {bill.description && (
            <>
              <View style={styles.divider} />
              <View style={styles.detailRow}>
                <Text style={styles.detailLabel}>描述</Text>
                <Text style={styles.detailValue}>{bill.description}</Text>
              </View>
            </>
          )}
        </View>
      </ScrollView>

      {/* 底部操作按钮 */}
      <View style={[styles.bottomActions, isAdjustment && styles.adjustmentActions]}>
        <TouchableOpacity
          style={styles.editButton}
          onPress={handleEdit}
          activeOpacity={0.8}
        >
          <Text style={styles.editButtonText}>编辑</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={styles.deleteButton}
          onPress={handleDelete}
          disabled={deleting}
          activeOpacity={0.8}
        >
          {deleting ? (
            <ActivityIndicator size="small" color={styles._colors.error} />
          ) : (
            <Text style={styles.deleteButtonText}>删除</Text>
          )}
        </TouchableOpacity>
      </View>
    </View>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: colors.background,
    },
    loadingContainer: {
      flex: 1,
      alignItems: 'center',
      justifyContent: 'center',
      backgroundColor: colors.background,
    },
    errorText: {
      fontSize: 16,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
    errorBanner: {
      margin: 0,
      width: '100%',
    },
    scrollView: {
      flex: 1,
    },
    content: {
      padding: spacing.lg,
      paddingBottom: spacing.xxxxl,
    },
    amountSection: {
      alignItems: 'center',
      paddingVertical: spacing.xl,
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.isSoft || shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.cardBorder : colors.stroke,
      marginBottom: spacing.lg,
      padding: spacing.xl,
      ...(shape.isSoft || shape.isHogwarts ? shape.elevation.card : {}),
    },
    categoryIconBlock: {
      width: 64,
      height: 64,
      borderRadius: shape.isSoft ? 20 : shape.isHogwarts ? borderRadius.round : borderRadius.medium,
      borderWidth: shape.isSoft ? 0 : shape.isHogwarts ? 1.5 : borderWidth.medium,
      borderColor: shape.isHogwarts ? colors.accent : colors.stroke,
      backgroundColor: shape.isHogwarts ? colors.primaryLight : colors.accent,
      alignItems: 'center',
      justifyContent: 'center',
      marginBottom: spacing.md,
    },
    categoryEmoji: {
      fontSize: 32,
    },
    typeBadge: {
      fontSize: 13,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.xs,
      borderRadius: shape.isSoft || shape.isHogwarts ? shape.radius.pill : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : shape.borderWidth.subtle,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      overflow: 'hidden',
      marginBottom: spacing.sm,
    },
    amount: {
      fontSize: 40,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isSoft ? undefined : shape.isHogwarts ? HOGWARTS_FONTS.numeric : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
      letterSpacing: shape.isHogwarts ? 0 : -1,
      marginBottom: spacing.sm,
    },
    starDivider: {
      marginBottom: spacing.xs,
    },
    categoryLabel: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    detailCard: {
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.isSoft || shape.isHogwarts ? shape.borderWidth.card : borderWidth.medium,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.cardBorder : colors.stroke,
      overflow: 'hidden',
      marginBottom: spacing.lg,
      ...(shape.isSoft || shape.isHogwarts ? shape.elevation.card : shadow.small),
    },
    detailRow: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      paddingVertical: spacing.lg,
      paddingHorizontal: spacing.lg,
    },
    detailLabel: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '500' : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
    detailValueContainer: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.xs,
    },
    accountValueContainer: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.xs,
      marginLeft: spacing.sm,
      flexShrink: 1,
    },
    accountName: {
      flexShrink: 1,
      textAlign: 'right',
    },
    detailIcon: {
      fontSize: 16,
    },
    detailValue: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    detailValueCourier: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '700',
      fontFamily: shape.isSoft ? undefined : shape.isHogwarts ? HOGWARTS_FONTS.numeric : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
      color: colors.textPrimary,
    },
    divider: {
      height: shape.isSoft ? StyleSheet.hairlineWidth : shape.borderWidth.subtle,
      backgroundColor: shape.isSoft || shape.isHogwarts ? colors.divider : colors.stroke,
      marginHorizontal: spacing.lg,
    },
    bottomActions: {
      flexDirection: 'row',
      gap: spacing.md,
      padding: spacing.lg,
      paddingBottom: spacing.xxl,
      backgroundColor: colors.surface,
      borderTopWidth: shape.isSoft ? StyleSheet.hairlineWidth : shape.borderWidth.subtle,
      borderTopColor: shape.isSoft || shape.isHogwarts ? colors.divider : colors.stroke,
    },
    adjustmentActions: {
      display: 'none',
    },
    editButton: {
      flex: 1,
      height: 48,
      borderRadius: shape.radius.button,
      backgroundColor: colors.primary,
      borderWidth: shape.isSoft ? 0 : shape.borderWidth.card,
      borderColor: shape.isHogwarts ? colors.accent : colors.stroke,
      alignItems: 'center',
      justifyContent: 'center',
      ...(shape.isSoft ? shape.elevation.none : shape.isHogwarts ? shape.elevation.card : shadow.small),
    },
    editButtonText: {
      fontSize: 16,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.onPrimary,
    },
    deleteButton: {
      flex: 1,
      height: 48,
      borderRadius: shape.radius.button,
      backgroundColor: shape.isSoft ? colors.expenseLight : colors.error + '15',
      borderWidth: shape.isSoft ? 0 : shape.borderWidth.card,
      borderColor: colors.error,
      alignItems: 'center',
      justifyContent: 'center',
    },
    deleteButtonText: {
      fontSize: 16,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: shape.isSoft ? colors.expenseStrong : colors.error,
    },
  }),
  _colors: colors,
  _isSoft: shape.isSoft,
});

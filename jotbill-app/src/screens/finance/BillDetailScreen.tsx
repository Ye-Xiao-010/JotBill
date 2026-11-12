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
import { ThemeColors } from '../../theme/colors';
import { borderRadius, borderWidth, spacing, shadow } from '../../theme/spacing';
import { nativeData, type Bill, type Category } from '../../native';
import { invalidateCache } from '../../lib/queryClient';
import { formatPlatform } from '../../utils/platform';
import { formatCurrency } from '../../utils/format';
import { useStyles } from '../../hooks';
import CategoryIcon from '../../components/business/CategoryIcon';
import QueryErrorBanner from '../../components/ui/QueryErrorBanner';
import { useAlert } from '../../providers';
import { typography } from '../../theme';

export default function BillDetailScreen() {
  const navigation = useNavigation();
  const route = useRoute();
  const rawBillId = (route.params as { billId: string | number }).billId;
  const billId = String(rawBillId);
  const styles = useStyles(createStyles);
  const { alert, confirm } = useAlert();

  const [bill, setBill] = useState<Bill | null>(null);
  const [category, setCategory] = useState<Category | null>(null);
  const [loading, setLoading] = useState(true);
  const [deleting, setDeleting] = useState(false);
  /** 加载失败信息；非空时渲染失败态而非"账单不存在" */
  const [loadError, setLoadError] = useState<string | null>(null);

  const fetchBill = useCallback(async () => {
    try {
      setLoading(true);
      const loadedBill = await nativeData.getBillById(billId);
      setBill(loadedBill);

      const categoryType = loadedBill.type === 'income' || loadedBill.type === 'refund'
        ? 'income'
        : 'expense';
      const categories = await nativeData.getCategories(categoryType);
      setCategory(categories.find(item => item.id === loadedBill.categoryId) || null);
      setLoadError(null);
    } catch (error) {
      console.error('Failed to fetch bill:', error);
      setLoadError(error instanceof Error ? error.message : '获取账单详情失败');
      alert('错误', '获取账单详情失败');
    } finally {
      setLoading(false);
    }
  }, [billId, alert]);

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
            onRetry={fetchBill}
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

  return (
    <View style={styles.container}>
      <ScrollView style={styles.scrollView} contentContainerStyle={styles.content}>
        {/* 金额区域 */}
        <View style={styles.amountSection}>
          <View style={styles.categoryIconBlock}>
            <CategoryIcon icon={category?.icon} size={32} emojiStyle={styles.categoryEmoji} />
          </View>
          <Text style={[styles.typeBadge, { backgroundColor: isPositive ? styles._colors.income + '20' : styles._colors.expense + '20', color: amountColor }]}>
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

const createStyles = (colors: ThemeColors) => ({
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
      fontWeight: '700',
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
      borderRadius: borderRadius.card,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      marginBottom: spacing.lg,
      padding: spacing.xl,
    },
    categoryIconBlock: {
      width: 64,
      height: 64,
      borderRadius: borderRadius.medium,
      borderWidth: borderWidth.medium,
      borderColor: colors.stroke,
      backgroundColor: colors.accent,
      alignItems: 'center',
      justifyContent: 'center',
      marginBottom: spacing.md,
    },
    categoryEmoji: {
      fontSize: 32,
    },
    typeBadge: {
      fontSize: 13,
      fontWeight: '800',
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.xs,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      overflow: 'hidden',
      marginBottom: spacing.sm,
    },
    amount: {
      fontSize: 40,
      fontWeight: '800',
      fontFamily: typography.fontFamily.mono,
      letterSpacing: -1,
      marginBottom: spacing.sm,
    },
    categoryLabel: {
      fontSize: 14,
      fontWeight: '700',
      color: colors.textSecondary,
    },
    detailCard: {
      backgroundColor: colors.surface,
      borderRadius: borderRadius.card,
      borderWidth: borderWidth.medium,
      borderColor: colors.stroke,
      overflow: 'hidden',
      marginBottom: spacing.lg,
      ...shadow.small,
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
      fontWeight: '700',
      color: colors.textTertiary,
    },
    detailValueContainer: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.xs,
    },
    detailIcon: {
      fontSize: 16,
    },
    detailValue: {
      fontSize: 14,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    detailValueCourier: {
      fontSize: 14,
      fontWeight: '700',
      fontFamily: typography.fontFamily.mono,
      color: colors.textPrimary,
    },
    divider: {
      height: borderWidth.thin,
      backgroundColor: colors.stroke,
      marginHorizontal: spacing.lg,
    },
    bottomActions: {
      flexDirection: 'row',
      gap: spacing.md,
      padding: spacing.lg,
      paddingBottom: spacing.xxl,
      backgroundColor: colors.surface,
      borderTopWidth: borderWidth.thin,
      borderTopColor: colors.stroke,
    },
    adjustmentActions: {
      display: 'none',
    },
    editButton: {
      flex: 1,
      height: 48,
      borderRadius: borderRadius.button,
      backgroundColor: colors.primary,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      alignItems: 'center',
      justifyContent: 'center',
      ...shadow.small,
    },
    editButtonText: {
      fontSize: 16,
      fontWeight: '800',
      color: colors.onPrimary,
    },
    deleteButton: {
      flex: 1,
      height: 48,
      borderRadius: borderRadius.button,
      backgroundColor: colors.error + '15',
      borderWidth: borderWidth.thin,
      borderColor: colors.error,
      alignItems: 'center',
      justifyContent: 'center',
    },
    deleteButtonText: {
      fontSize: 16,
      fontWeight: '800',
      color: colors.error,
    },
  }),
  _colors: colors,
});

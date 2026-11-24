/**
 * 预算管理页面 - Neo-Brutalism 风格
 * 预算列表 + 新增/编辑弹窗 + 分类选择 + 进度追踪
 */
import React, { useState, useCallback } from 'react';
import {
  View,
  Text,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  TextInput,
  Modal,
  RefreshControl,
} from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { useQuery, useMutation } from '@tanstack/react-query';
import { ChevronLeft, Plus, Trash2, Pencil } from 'lucide-react-native';
import { ThemeColors } from '../theme/colors';
import { ThemeShape } from '../theme/shape';
import { spacing, borderRadius, borderWidth, shadow } from '../theme/spacing';
import { ProgressBar, BrutalPressable } from '../components/ui';
import CategoryIcon from '../components/business/CategoryIcon';
import { getBudgetStatusColor } from '../constants/app';
import { useStyles, useToast, useRefreshControlProps } from '../hooks';
import { useAlert, useTheme } from '../providers';
import { nativeData, type BudgetInput, type BudgetPeriod, type BudgetProgress, type Category } from '../native';
import { QUERY_KEYS, invalidateCache } from '../lib/queryClient';
import { SafeAreaView } from 'react-native-safe-area-context';
import { typography } from '../theme';

const PERIOD_OPTIONS: Array<{ value: BudgetPeriod; label: string }> = [
  { value: 'monthly', label: '月度' },
  { value: 'weekly', label: '每周' },
  { value: 'biweekly', label: '每两周' },
  { value: 'quarterly', label: '每季度' },
  { value: 'yearly', label: '年度' },
  { value: 'once', label: '一次性' },
];

const WEEKDAY_OPTIONS = [
  { value: 1, label: '周一' },
  { value: 2, label: '周二' },
  { value: 3, label: '周三' },
  { value: 4, label: '周四' },
  { value: 5, label: '周五' },
  { value: 6, label: '周六' },
  { value: 7, label: '周日' },
];

function formatAmount(amountMinor: number): string {
  return (amountMinor / 100).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

function localDateString(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

function todayDateString(): string {
  return localDateString(new Date());
}

function addDays(dateString: string, days: number): string {
  const [year, month, day] = dateString.split('-').map(Number);
  const date = new Date(year, month - 1, day + days);
  return localDateString(date);
}

function isValidDateString(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return false;
  }
  const [year, month, day] = value.split('-').map(Number);
  const date = new Date(year, month - 1, day);
  return (
    date.getFullYear() === year &&
    date.getMonth() === month - 1 &&
    date.getDate() === day
  );
}

function periodTypeLabel(period: BudgetPeriod): string {
  return PERIOD_OPTIONS.find(option => option.value === period)?.label ?? period;
}

function describePeriod(
  period: BudgetPeriod,
  startDay: string,
  weekStart: number,
  anchorDate: string,
  rangeStartDate: string,
  rangeEndDate: string,
): string {
  switch (period) {
    case 'monthly': {
      const day = Number(startDay || '1');
      const shortMonthHint = day >= 29 && day <= 31 ? '（短月按当月最后一天）' : '';
      return `每月 ${startDay || '1'} 日至次月前一日${shortMonthHint}`;
    }
    case 'weekly':
      return `从${WEEKDAY_OPTIONS.find(option => option.value === weekStart)?.label ?? '周一'}起，7 天为一期`;
    case 'biweekly':
      return `从 ${anchorDate || '锚点日'} 起，每 14 天为一期`;
    case 'quarterly':
      return '自然季度：1/4/7/10 月 1 日起';
    case 'yearly':
      return '自然年：1 月 1 日起';
    case 'once':
      return `${rangeStartDate || '开始日'} 至 ${rangeEndDate || '结束日'}（含当日）`;
    default:
      return '';
  }
}

export default function BudgetScreen() {
  const navigation = useNavigation();
  const styles = useStyles(createStyles);
  const refreshControlProps = useRefreshControlProps();
  const { colors, isSoft } = useTheme();
  const { confirm } = useAlert();
  const { showError } = useToast();

  // 弹窗状态
  const [showFormModal, setShowFormModal] = useState(false);
  const [editingBudget, setEditingBudget] = useState<BudgetProgress | null>(null);

  // 表单状态
  const [formName, setFormName] = useState('');
  const [formAmount, setFormAmount] = useState('');
  const [formPeriod, setFormPeriod] = useState<BudgetPeriod>('monthly');
  const [formStartDay, setFormStartDay] = useState('1');
  const [formWeekStart, setFormWeekStart] = useState(1);
  const [formAnchorDate, setFormAnchorDate] = useState('');
  const [formRangeStartDate, setFormRangeStartDate] = useState('');
  const [formRangeEndDate, setFormRangeEndDate] = useState('');
  const [formCategoryId, setFormCategoryId] = useState<string | undefined>(undefined);
  const [formAlertAt, setFormAlertAt] = useState('80');

  // 数据查询
  const { data: budgetsData, isFetching: isBudgetsFetching, refetch } = useQuery({
    queryKey: QUERY_KEYS.budgetProgress,
    queryFn: () => nativeData.getBudgetProgress(),
  });

  const { data: categories, isFetching: isCategoriesFetching } = useQuery({
    queryKey: QUERY_KEYS.categories.byType('expense'),
    queryFn: () => nativeData.getCategories('expense'),
  });

  const budgets = budgetsData ?? [];
  const categoryMap = new Map((categories ?? []).map((category) => [category.id, category]));
  const totalBudgets = budgets.length;
  const activeBudgets = budgets.filter(
    b => b.isActive && b.isPeriodActive !== false && !b.isOverBudget,
  ).length;
  const overBudgets = budgets.filter(
    b => b.isActive && b.isPeriodActive !== false && b.isOverBudget,
  ).length;

  // 创建 mutation
  const createMutation = useMutation({
    mutationFn: (data: BudgetInput) => nativeData.addBudget(data),
    onSuccess: () => {
      invalidateCache.budgets();
      closeFormModal();
    },
    onError: (error: any) => showError(error?.message || '创建预算失败'),
  });

  // 更新 mutation
  const updateMutation = useMutation({
    mutationFn: ({ id, data }: { id: string; data: BudgetInput }) =>
      nativeData.updateBudget(id, data),
    onSuccess: () => {
      invalidateCache.budgets();
      closeFormModal();
    },
    onError: (error: any) => showError(error?.message || '更新预算失败'),
  });

  // 删除 mutation
  const deleteMutation = useMutation({
    mutationFn: (id: string) => nativeData.deleteBudget(id),
    onSuccess: () => invalidateCache.budgets(),
    onError: (error: any) => showError(error?.message || '删除预算失败'),
  });

  // 表单操作
  const openCreateModal = () => {
    const today = todayDateString();
    setEditingBudget(null);
    setFormName('');
    setFormAmount('');
    setFormPeriod('monthly');
    setFormStartDay('1');
    setFormWeekStart(1);
    setFormAnchorDate(today);
    setFormRangeStartDate(today);
    setFormRangeEndDate(addDays(today, 30));
    setFormCategoryId(undefined);
    setFormAlertAt('80');
    setShowFormModal(true);
  };

  const openEditModal = (budget: BudgetProgress) => {
    const today = todayDateString();
    setEditingBudget(budget);
    setFormName(budget.name);
    setFormAmount((budget.amountMinor / 100).toFixed(2));
    setFormPeriod(budget.period);
    setFormStartDay(String(budget.startDay ?? 1));
    setFormWeekStart(budget.weekStart ?? 1);
    setFormAnchorDate(budget.anchorDate ?? today);
    setFormRangeStartDate(budget.rangeStartDate ?? today);
    setFormRangeEndDate(budget.rangeEndDate ?? addDays(today, 30));
    setFormCategoryId(budget.categoryId);
    setFormAlertAt(String(budget.alertAt));
    setShowFormModal(true);
  };

  const closeFormModal = () => {
    setShowFormModal(false);
    setEditingBudget(null);
  };

  const handleSubmitForm = () => {
    const amount = parseFloat(formAmount);
    const alertAt = parseInt(formAlertAt, 10);
    if (!formName.trim()) {
      showError('请输入预算名称');
      return;
    }
    if (isNaN(amount) || amount <= 0) {
      showError('请输入有效的预算金额');
      return;
    }
    if (isNaN(alertAt) || alertAt < 0 || alertAt > 100) {
      showError('预警阈值须在 0-100 之间');
      return;
    }
    if (formPeriod === 'monthly') {
      const startDay = parseInt(formStartDay, 10);
      if (!/^\d{1,2}$/.test(formStartDay) || startDay < 1 || startDay > 31) {
        showError('每月起始日须在 1-31 之间');
        return;
      }
    }
    if (formPeriod === 'biweekly' && !isValidDateString(formAnchorDate)) {
      showError('请输入有效的锚点日期（YYYY-MM-DD）');
      return;
    }
    if (formPeriod === 'once') {
      if (!isValidDateString(formRangeStartDate) || !isValidDateString(formRangeEndDate)) {
        showError('请输入有效的起止日期（YYYY-MM-DD）');
        return;
      }
      if (formRangeEndDate < formRangeStartDate) {
        showError('结束日期不能早于开始日期');
        return;
      }
    }

    const payload: BudgetInput = {
      name: formName.trim(),
      amountMinor: Math.round(amount * 100),
      period: formPeriod,
      categoryId: formCategoryId,
      alertAt,
    };
    if (formPeriod === 'monthly') {
      payload.startDay = parseInt(formStartDay, 10);
    } else if (formPeriod === 'weekly') {
      payload.weekStart = formWeekStart;
    } else if (formPeriod === 'biweekly') {
      payload.anchorDate = formAnchorDate;
    } else if (formPeriod === 'once') {
      payload.rangeStartDate = formRangeStartDate;
      payload.rangeEndDate = formRangeEndDate;
    }

    if (editingBudget) {
      updateMutation.mutate({ id: editingBudget.id, data: payload });
    } else {
      createMutation.mutate(payload);
    }
  };

  const handleDelete = (budget: BudgetProgress) => {
    confirm(
      '删除预算',
      `确定要删除「${budget.name}」吗？此操作不可撤销。`,
      () => deleteMutation.mutate(budget.id),
      undefined,
      { confirmText: '删除', destructive: true },
    );
  };

  const onRefresh = useCallback(async () => {
    await refetch();
  }, [refetch]);

  const getProgressColor = (budget: BudgetProgress) => {
    return getBudgetStatusColor(budget.isOverBudget, budget.needsAlert, colors);
  };

  const getCategoryIcon = (budget: BudgetProgress) => {
    return categoryMap.get(budget.categoryId ?? '')?.icon || '💰';
  };

  return (
    <SafeAreaView style={styles.container} edges={['top', 'bottom']}>
      {/* Header */}
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} style={styles.backButton}>
          <ChevronLeft size={24} color={colors.textPrimary} strokeWidth={2.5} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>预算管理</Text>
        <TouchableOpacity onPress={openCreateModal} style={styles.addButton}>
          <Plus size={20} color={isSoft ? colors.primary : colors.textPrimary} strokeWidth={isSoft ? 2.4 : 3} />
        </TouchableOpacity>
      </View>

      {/* Summary */}
      <View style={styles.summaryBar}>
        <View style={styles.summaryItem}>
          <Text style={styles.summaryNumber}>{totalBudgets}</Text>
          <Text style={styles.summaryLabel}>个预算</Text>
        </View>
        <View style={styles.summaryDivider} />
        <View style={styles.summaryItem}>
          <Text style={styles.summaryNumber}>{activeBudgets}</Text>
          <Text style={styles.summaryLabel}>进行中</Text>
        </View>
        <View style={styles.summaryDivider} />
        <View style={styles.summaryItem}>
          <Text style={[styles.summaryNumber, overBudgets > 0 && { color: colors.error }]}>
            {overBudgets}
          </Text>
          <Text style={styles.summaryLabel}>已超支</Text>
        </View>
      </View>

      {/* Budget List */}
      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.scrollContent}
        refreshControl={
          <RefreshControl
            {...refreshControlProps}
            refreshing={isBudgetsFetching || isCategoriesFetching}
            onRefresh={onRefresh}
          />
        }
        showsVerticalScrollIndicator={false}
      >
        {budgets.length === 0 ? (
          <View style={styles.emptyState}>
            <Text style={styles.emptyIcon}>💰</Text>
            <Text style={styles.emptyText}>还没有预算计划</Text>
            <Text style={styles.emptySubtext}>创建预算，合理控制你的开支</Text>
            <TouchableOpacity style={styles.emptyButton} onPress={openCreateModal}>
              <Text style={styles.emptyButtonText}>创建预算</Text>
            </TouchableOpacity>
          </View>
        ) : (
          budgets.map((budget) => (
            <View key={budget.id} style={styles.budgetCard}>
              {/* Card Header */}
              <View style={styles.budgetHeader}>
                <View style={styles.budgetTitleRow}>
                  <View style={styles.budgetIconBox}>
                    <CategoryIcon icon={getCategoryIcon(budget)} size={22} emojiStyle={styles.budgetIconText} />
                  </View>
                  <View style={styles.budgetTitleGroup}>
                    <Text style={styles.budgetName} numberOfLines={1}>{budget.name}</Text>
                    <Text style={styles.budgetCategory}>
                      {periodTypeLabel(budget.period)}
                      {categoryMap.get(budget.categoryId ?? '')?.name
                        ? ` · ${categoryMap.get(budget.categoryId ?? '')?.name}`
                        : ''}
                    </Text>
                  </View>
                </View>
                <View style={styles.badgeRow}>
                  {budget.isPeriodActive === false && (
                    <View style={styles.inactiveBadge}>
                      <Text style={styles.inactiveBadgeText}>
                        {new Date(budget.periodEndAt).getTime() < Date.now()
                          ? '已结束'
                          : '未开始'}
                      </Text>
                    </View>
                  )}
                  <View style={styles.periodBadge}>
                    <Text style={styles.periodText} numberOfLines={1}>
                      {budget.periodLabel ?? periodTypeLabel(budget.period)}
                    </Text>
                  </View>
                  <View style={[
                    styles.percentBadge,
                    budget.isOverBudget && styles.overBudgetBadge,
                  ]}>
                    <Text style={[
                      styles.percentText,
                      budget.isOverBudget && styles.overBudgetBadgeText,
                    ]}>
                      {budget.progressPct ?? Math.round(budget.progress * 100)}%
                    </Text>
                  </View>
                </View>
              </View>

              {/* Progress */}
              <View style={styles.budgetProgressContainer}>
                <ProgressBar
                  progress={Math.min(budget.progress * 100, 100)}
                  color={getProgressColor(budget)}
                  backgroundColor={isSoft ? colors.surfaceMuted : colors.divider}
                  height={isSoft ? 8 : 14}
                />
              </View>

              {/* Amounts */}
              <View style={styles.budgetAmounts}>
                <Text style={styles.budgetAmountSpent}>
                  已用 ¥{formatAmount(budget.spentMinor)}
                </Text>
                <Text style={styles.budgetAmountTotal}>
                  / 预算 ¥{formatAmount(budget.amountMinor)}
                </Text>
              </View>

              {budget.refundMinor > 0 && (
                <Text style={styles.budgetRefundText}>
                  支出 ¥{formatAmount(budget.grossSpentMinor)} − 退款 ¥{formatAmount(budget.refundMinor)} = 已用 ¥{formatAmount(budget.spentMinor)}
                  {budget.refundMinor > budget.grossSpentMinor ? '（净额下限 0）' : ''}
                </Text>
              )}

              {/* Over-budget warning */}
              {budget.isOverBudget && (
                <View style={styles.warningBar}>
                  <Text style={styles.warningText}>
                    超支 ¥{formatAmount(Math.abs(budget.remainingMinor))}
                  </Text>
                </View>
              )}

              {/* Actions */}
              <View style={styles.budgetActions}>
                <BrutalPressable
                  style={styles.editButton}
                  shadowOffset={2}
                  shadowColor={colors.stroke}
                  onPress={() => openEditModal(budget)}
                >
                  <Pencil size={16} color={colors.textPrimary} strokeWidth={2.5} />
                  <Text style={styles.editButtonText}>编辑</Text>
                </BrutalPressable>
                <TouchableOpacity
                  style={styles.deleteButton}
                  onPress={() => handleDelete(budget)}
                >
                  <Trash2 size={16} color={colors.error} strokeWidth={2.5} />
                </TouchableOpacity>
              </View>
            </View>
          ))
        )}
      </ScrollView>

      {/* Create/Edit Modal */}
      <Modal visible={showFormModal} transparent animationType="fade">
        <View style={styles.modalOverlay}>
          <ScrollView
            contentContainerStyle={styles.modalScrollContent}
            keyboardShouldPersistTaps="handled"
          >
            <View style={styles.modalContent}>
              <Text style={styles.modalTitle}>
                {editingBudget ? '编辑预算' : '新建预算'}
              </Text>

              <Text style={styles.inputLabel}>预算名称</Text>
              <TextInput
                style={styles.textInput}
                value={formName}
                onChangeText={setFormName}
                placeholder="如：每月餐饮、年度旅行"
                placeholderTextColor={colors.textTertiary}
                maxLength={50}
              />

              <Text style={styles.inputLabel}>预算金额</Text>
              <TextInput
                style={styles.textInput}
                value={formAmount}
                onChangeText={setFormAmount}
                placeholder="0.00"
                placeholderTextColor={colors.textTertiary}
                keyboardType="decimal-pad"
              />

              <Text style={styles.inputLabel}>预算周期</Text>
              <View style={styles.periodPicker}>
                {PERIOD_OPTIONS.map((option) => (
                  <TouchableOpacity
                    key={option.value}
                    style={[
                      styles.periodOption,
                      formPeriod === option.value && styles.periodOptionSelected,
                    ]}
                    onPress={() => setFormPeriod(option.value)}
                  >
                    <Text style={[
                      styles.periodOptionText,
                      formPeriod === option.value && styles.periodOptionTextSelected,
                    ]}>{option.label}</Text>
                  </TouchableOpacity>
                ))}
              </View>

              {formPeriod === 'monthly' && (
                <>
                  <Text style={styles.inputLabel}>每月起始日（1-31，31 表示月末）</Text>
                  <TextInput
                    style={styles.textInput}
                    value={formStartDay}
                    onChangeText={setFormStartDay}
                    placeholder="1"
                    placeholderTextColor={colors.textTertiary}
                    keyboardType="number-pad"
                    maxLength={2}
                  />
                </>
              )}

              {formPeriod === 'weekly' && (
                <>
                  <Text style={styles.inputLabel}>每周起始日</Text>
                  <View style={styles.weekdayPicker}>
                    {WEEKDAY_OPTIONS.map((option) => (
                      <TouchableOpacity
                        key={option.value}
                        style={[
                          styles.weekdayOption,
                          formWeekStart === option.value && styles.weekdayOptionSelected,
                        ]}
                        onPress={() => setFormWeekStart(option.value)}
                      >
                        <Text style={[
                          styles.weekdayText,
                          formWeekStart === option.value && styles.weekdayTextSelected,
                        ]}>{option.label}</Text>
                      </TouchableOpacity>
                    ))}
                  </View>
                </>
              )}

              {formPeriod === 'biweekly' && (
                <>
                  <Text style={styles.inputLabel}>锚点日期（YYYY-MM-DD）</Text>
                  <TextInput
                    style={styles.textInput}
                    value={formAnchorDate}
                    onChangeText={setFormAnchorDate}
                    placeholder="如：2026-11-02"
                    placeholderTextColor={colors.textTertiary}
                    autoCapitalize="none"
                  />
                </>
              )}

              {formPeriod === 'once' && (
                <>
                  <Text style={styles.inputLabel}>开始日期（YYYY-MM-DD）</Text>
                  <TextInput
                    style={styles.textInput}
                    value={formRangeStartDate}
                    onChangeText={setFormRangeStartDate}
                    placeholder="如：2026-11-01"
                    placeholderTextColor={colors.textTertiary}
                    autoCapitalize="none"
                  />
                  <Text style={styles.inputLabel}>结束日期（YYYY-MM-DD，含当日）</Text>
                  <TextInput
                    style={styles.textInput}
                    value={formRangeEndDate}
                    onChangeText={setFormRangeEndDate}
                    placeholder="如：2026-11-30"
                    placeholderTextColor={colors.textTertiary}
                    autoCapitalize="none"
                  />
                </>
              )}

              <Text style={styles.periodHint}>
                {describePeriod(
                  formPeriod,
                  formStartDay,
                  formWeekStart,
                  formAnchorDate,
                  formRangeStartDate,
                  formRangeEndDate,
                )}
              </Text>

              <Text style={styles.inputLabel}>关联分类（可选）</Text>
              <ScrollView
                style={styles.categoryScroll}
                nestedScrollEnabled
                showsVerticalScrollIndicator={false}
              >
                <TouchableOpacity
                  style={[styles.categoryChip, !formCategoryId && styles.categoryChipSelected]}
                  onPress={() => setFormCategoryId(undefined)}
                >
                  <Text style={styles.categoryChipIcon}>📊</Text>
                  <Text style={[
                    styles.categoryChipText,
                    !formCategoryId && styles.categoryChipTextSelected,
                  ]}>全部分类</Text>
                </TouchableOpacity>
                {(categories ?? []).map((cat: Category) => (
                  <TouchableOpacity
                    key={cat.id}
                    style={[styles.categoryChip, formCategoryId === cat.id && styles.categoryChipSelected]}
                    onPress={() => setFormCategoryId(cat.id)}
                  >
                    <CategoryIcon icon={cat.icon} fallback="📁" size={16} emojiStyle={styles.categoryChipIcon} />
                    <Text style={[
                      styles.categoryChipText,
                      formCategoryId === cat.id && styles.categoryChipTextSelected,
                    ]}>{cat.name}</Text>
                  </TouchableOpacity>
                ))}
              </ScrollView>

              <Text style={styles.inputLabel}>预警阈值（%）</Text>
              <TextInput
                style={styles.textInput}
                value={formAlertAt}
                onChangeText={setFormAlertAt}
                placeholder="80"
                placeholderTextColor={colors.textTertiary}
                keyboardType="number-pad"
                maxLength={3}
              />

              <View style={styles.modalActions}>
                <TouchableOpacity style={styles.cancelButton} onPress={closeFormModal}>
                  <Text style={styles.cancelButtonText}>取消</Text>
                </TouchableOpacity>
                <TouchableOpacity
                  style={[styles.submitButton, (!formName.trim() || !formAmount) && styles.submitButtonDisabled]}
                  onPress={handleSubmitForm}
                  disabled={!formName.trim() || !formAmount || createMutation.isPending || updateMutation.isPending}
                >
                  <Text style={styles.submitButtonText}>
                    {editingBudget ? '保存' : '创建'}
                  </Text>
                </TouchableOpacity>
              </View>
            </View>
          </ScrollView>
        </View>
      </Modal>
    </SafeAreaView>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: colors.background,
    },

    // ===== Header =====
    header: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingHorizontal: spacing.md,
      paddingTop: spacing.xl,
      paddingBottom: spacing.md,
      backgroundColor: shape.isSoft ? colors.background : colors.surface,
      borderBottomWidth: shape.isSoft ? 0 : borderWidth.medium,
      borderBottomColor: colors.stroke,
    },
    backButton: {
      width: 40,
      height: 40,
      alignItems: 'center',
      justifyContent: 'center',
    },
    headerTitle: {
      fontSize: shape.isSoft ? 18 : 20,
      fontWeight: shape.isSoft ? '700' : '800',
      color: colors.textPrimary,
    },
    addButton: {
      width: 40,
      height: 40,
      borderRadius: shape.isSoft ? 12 : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: shape.isSoft ? colors.primaryLight : colors.accent,
      alignItems: 'center',
      justifyContent: 'center',
    },

    // ===== Summary =====
    summaryBar: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-around',
      marginHorizontal: spacing.lg,
      marginTop: shape.isSoft ? spacing.sm : spacing.lg,
      paddingVertical: spacing.md,
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.isSoft ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isSoft ? colors.cardBorder : colors.stroke,
      ...(shape.isSoft ? shape.elevation.card : shadow.small),
    },
    summaryItem: {
      alignItems: 'center',
    },
    summaryNumber: {
      fontSize: 22,
      fontWeight: shape.isSoft ? '800' : '900',
      color: colors.textPrimary,
      fontFamily: shape.isSoft ? undefined : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
    },
    summaryLabel: {
      fontSize: 12,
      fontWeight: '600',
      color: colors.textTertiary,
      marginTop: 2,
    },
    summaryDivider: {
      width: shape.isSoft ? StyleSheet.hairlineWidth : 1,
      height: 28,
      backgroundColor: colors.divider,
    },

    // ===== Scroll =====
    scrollView: {
      flex: 1,
    },
    scrollContent: {
      padding: spacing.lg,
      paddingBottom: 40,
    },

    // ===== Empty =====
    emptyState: {
      alignItems: 'center',
      padding: spacing.xxxl,
      marginTop: spacing.xxl,
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.isSoft ? shape.borderWidth.card : borderWidth.medium,
      borderColor: shape.isSoft ? colors.cardBorder : colors.stroke,
      ...(shape.isSoft ? shape.elevation.card : shadow.medium),
    },
    emptyIcon: {
      fontSize: 56,
      marginBottom: spacing.md,
    },
    emptyText: {
      fontSize: 18,
      fontWeight: shape.isSoft ? '700' : '800',
      color: colors.textPrimary,
      marginBottom: spacing.sm,
    },
    emptySubtext: {
      fontSize: 14,
      fontWeight: '500',
      color: colors.textTertiary,
      marginBottom: spacing.xl,
      textAlign: 'center',
    },
    emptyButton: {
      backgroundColor: colors.primary,
      paddingHorizontal: spacing.xl,
      paddingVertical: spacing.md,
      borderRadius: shape.radius.button,
      borderWidth: shape.isSoft ? 0 : borderWidth.medium,
      borderColor: colors.stroke,
      ...(shape.isSoft ? shape.elevation.none : shadow.small),
    },
    emptyButtonText: {
      fontSize: 15,
      fontWeight: shape.isSoft ? '700' : '800',
      color: shape.isSoft ? colors.onPrimary : '#FFFFFF',
    },

    // ===== Budget Card =====
    budgetCard: {
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.isSoft ? shape.borderWidth.card : borderWidth.medium,
      borderColor: shape.isSoft ? colors.cardBorder : colors.stroke,
      padding: spacing.lg,
      marginBottom: spacing.md,
      ...(shape.isSoft ? shape.elevation.card : shadow.small),
    },
    budgetHeader: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      marginBottom: spacing.md,
    },
    budgetTitleRow: {
      flexDirection: 'row',
      alignItems: 'center',
      flex: 1,
      gap: spacing.md,
    },
    budgetIconBox: {
      width: 44,
      height: 44,
      borderRadius: shape.isSoft ? 14 : borderRadius.medium,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: shape.isSoft ? colors.primaryLight : colors.accent + '30',
      alignItems: 'center',
      justifyContent: 'center',
    },
    budgetIconText: {
      fontSize: 22,
    },
    budgetTitleGroup: {
      flex: 1,
    },
    budgetName: {
      fontSize: 16,
      fontWeight: shape.isSoft ? '700' : '800',
      color: colors.textPrimary,
    },
    budgetCategory: {
      fontSize: 12,
      fontWeight: shape.isSoft ? '500' : '600',
      color: colors.textTertiary,
      marginTop: 2,
    },
    badgeRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.xs,
      marginLeft: spacing.sm,
    },
    periodBadge: {
      backgroundColor: shape.isSoft ? colors.surfaceMuted : colors.primaryLight,
      borderRadius: shape.isSoft ? shape.radius.pill : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.stroke,
      paddingHorizontal: spacing.sm,
      paddingVertical: spacing.xs,
      flexShrink: 1,
    },
    periodText: {
      fontSize: 11,
      fontWeight: shape.isSoft ? '600' : '700',
      color: shape.isSoft ? colors.textSecondary : colors.textPrimary,
    },
    inactiveBadge: {
      backgroundColor: shape.isSoft ? colors.surfaceMuted : colors.divider,
      borderRadius: shape.isSoft ? shape.radius.pill : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.stroke,
      paddingHorizontal: spacing.xs + 2,
      paddingVertical: 2,
    },
    inactiveBadgeText: {
      fontSize: 10,
      fontWeight: shape.isSoft ? '700' : '800',
      color: colors.textSecondary,
    },
    percentBadge: {
      backgroundColor: shape.isSoft ? colors.primaryLight : colors.accent,
      borderRadius: shape.isSoft ? shape.radius.pill : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.stroke,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.xs,
    },
    overBudgetBadge: {
      backgroundColor: colors.error,
    },
    percentText: {
      fontSize: 13,
      fontWeight: shape.isSoft ? '700' : '800',
      color: shape.isSoft ? colors.primaryDark : colors.textPrimary,
      fontFamily: shape.isSoft ? undefined : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
    },
    overBudgetBadgeText: {
      color: '#FFFFFF',
    },

    // Progress
    budgetProgressContainer: {
      borderRadius: shape.isSoft ? 999 : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.stroke,
      overflow: 'hidden',
      marginBottom: spacing.sm,
    },

    // Amounts
    budgetAmounts: {
      flexDirection: 'row',
      alignItems: 'baseline',
      marginBottom: spacing.sm,
    },
    budgetAmountSpent: {
      fontSize: 16,
      fontWeight: shape.isSoft ? '800' : '900',
      color: colors.textPrimary,
      fontFamily: shape.isSoft ? undefined : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
    },
    budgetAmountTotal: {
      fontSize: 13,
      fontWeight: '600',
      color: colors.textTertiary,
      fontFamily: shape.isSoft ? undefined : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
      marginLeft: spacing.xs,
    },
    budgetRefundText: {
      fontSize: 12,
      fontWeight: '600',
      color: colors.textTertiary,
      fontFamily: shape.isSoft ? undefined : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
      marginBottom: spacing.sm,
    },

    // Warning
    warningBar: {
      backgroundColor: shape.isSoft ? colors.expenseLight : colors.error + '15',
      borderRadius: shape.isSoft ? 12 : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.error,
      paddingVertical: spacing.xs + 2,
      paddingHorizontal: spacing.md,
      marginBottom: spacing.sm,
    },
    warningText: {
      fontSize: 13,
      fontWeight: '700',
      color: shape.isSoft ? colors.expenseStrong : colors.error,
      fontFamily: shape.isSoft ? undefined : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
      textAlign: 'center',
    },

    // Actions
    budgetActions: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
    },
    editButton: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.xs,
      backgroundColor: shape.isSoft ? colors.surfaceMuted : colors.surface,
      borderRadius: shape.isSoft ? 12 : borderRadius.button,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.stroke,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.sm,
    },
    editButtonText: {
      fontSize: 13,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    deleteButton: {
      marginLeft: 'auto',
      padding: spacing.sm,
    },

    // ===== Modal =====
    modalOverlay: {
      flex: 1,
      backgroundColor: 'rgba(0, 0, 0, 0.5)',
      justifyContent: 'center',
    },
    modalScrollContent: {
      flexGrow: 1,
      justifyContent: 'center',
      paddingHorizontal: spacing.xl,
      paddingVertical: spacing.xxl,
    },
    modalContent: {
      backgroundColor: colors.surface,
      borderRadius: shape.radius.modal,
      borderWidth: shape.isSoft ? shape.borderWidth.card : borderWidth.medium,
      borderColor: shape.isSoft ? colors.cardBorder : colors.stroke,
      padding: spacing.xl,
      ...(shape.isSoft ? shape.elevation.floating : shadow.large),
    },
    modalTitle: {
      fontSize: shape.isSoft ? 18 : 20,
      fontWeight: shape.isSoft ? '700' : '800',
      color: colors.textPrimary,
      marginBottom: spacing.lg,
      textAlign: 'center',
    },
    inputLabel: {
      fontSize: 13,
      fontWeight: shape.isSoft ? '600' : '700',
      color: colors.textSecondary,
      marginBottom: spacing.xs,
      marginTop: spacing.md,
    },
    textInput: {
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.stroke,
      borderRadius: shape.radius.input,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.md,
      fontSize: 15,
      fontWeight: '600',
      color: colors.textPrimary,
      backgroundColor: shape.isSoft ? colors.surfaceMuted : colors.background,
    },

    // Period picker
    periodPicker: {
      flexDirection: 'row',
      flexWrap: 'wrap',
      gap: spacing.sm,
    },
    periodOption: {
      flexGrow: 1,
      flexBasis: '30%',
      paddingVertical: spacing.md,
      borderRadius: shape.isSoft ? 12 : borderRadius.button,
      borderWidth: shape.isSoft ? 1 : borderWidth.thin,
      borderColor: shape.isSoft ? 'transparent' : colors.stroke,
      alignItems: 'center',
      backgroundColor: shape.isSoft ? colors.surfaceMuted : colors.background,
    },
    periodOptionSelected: {
      borderWidth: shape.isSoft ? 1.5 : borderWidth.medium,
      borderColor: shape.isSoft ? colors.primary : colors.stroke,
      backgroundColor: colors.primaryLight,
    },
    periodOptionText: {
      fontSize: 15,
      fontWeight: shape.isSoft ? '600' : '700',
      color: colors.textSecondary,
    },
    periodOptionTextSelected: {
      color: colors.primary,
      fontWeight: shape.isSoft ? '700' : '800',
    },
    periodHint: {
      fontSize: 12,
      fontWeight: '600',
      color: colors.textTertiary,
      marginTop: spacing.xs,
      lineHeight: 18,
    },

    // Weekday picker
    weekdayPicker: {
      flexDirection: 'row',
      flexWrap: 'wrap',
      gap: spacing.xs,
    },
    weekdayOption: {
      paddingVertical: spacing.sm,
      paddingHorizontal: spacing.sm,
      borderRadius: shape.isSoft ? 10 : borderRadius.button,
      borderWidth: shape.isSoft ? 1 : borderWidth.thin,
      borderColor: shape.isSoft ? 'transparent' : colors.stroke,
      backgroundColor: shape.isSoft ? colors.surfaceMuted : colors.background,
    },
    weekdayOptionSelected: {
      borderWidth: shape.isSoft ? 1.5 : borderWidth.medium,
      borderColor: shape.isSoft ? colors.primary : colors.stroke,
      backgroundColor: colors.primaryLight,
    },
    weekdayText: {
      fontSize: 13,
      fontWeight: shape.isSoft ? '600' : '700',
      color: colors.textSecondary,
    },
    weekdayTextSelected: {
      color: colors.primary,
      fontWeight: shape.isSoft ? '700' : '800',
    },

    // Category picker
    categoryScroll: {
      maxHeight: 160,
      marginTop: spacing.xs,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.stroke,
      borderRadius: shape.radius.input,
      backgroundColor: shape.isSoft ? colors.surfaceMuted : colors.background,
    },
    categoryChip: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.md,
      borderBottomWidth: StyleSheet.hairlineWidth,
      borderBottomColor: colors.divider,
    },
    categoryChipSelected: {
      backgroundColor: colors.primaryLight,
    },
    categoryChipIcon: {
      fontSize: 16,
    },
    categoryChipText: {
      fontSize: 13,
      fontWeight: '600',
      color: colors.textSecondary,
    },
    categoryChipTextSelected: {
      color: colors.primary,
      fontWeight: shape.isSoft ? '700' : '800',
    },

    // Modal Actions
    modalActions: {
      flexDirection: 'row',
      gap: spacing.md,
      marginTop: spacing.xl,
    },
    cancelButton: {
      flex: 1,
      paddingVertical: spacing.md,
      borderRadius: shape.radius.button,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: shape.isSoft ? colors.surfaceMuted : 'transparent',
      alignItems: 'center',
    },
    cancelButtonText: {
      fontSize: 15,
      fontWeight: '700',
      color: shape.isSoft ? colors.textSecondary : colors.textPrimary,
    },
    submitButton: {
      flex: 1,
      paddingVertical: spacing.md,
      borderRadius: shape.radius.button,
      borderWidth: shape.isSoft ? 0 : borderWidth.medium,
      borderColor: colors.stroke,
      backgroundColor: colors.primary,
      alignItems: 'center',
      ...(shape.isSoft ? shape.elevation.none : shadow.small),
    },
    submitButtonDisabled: {
      opacity: 0.5,
    },
    submitButtonText: {
      fontSize: 15,
      fontWeight: shape.isSoft ? '700' : '800',
      color: shape.isSoft ? colors.onPrimary : '#FFFFFF',
    },
  }),
});

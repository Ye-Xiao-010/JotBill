/**
 * 财务目标管理页面 - Neo-Brutalism 风格
 * 目标列表 + 新增/编辑弹窗 + 存入弹窗 + 进度追踪
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
import { ChevronLeft, Plus, Trash2 } from 'lucide-react-native';
import { ThemeColors } from '../theme/colors';
import { ThemeShape } from '../theme/shape';
import { spacing, borderRadius, borderWidth, shadow } from '../theme/spacing';
import { ProgressBar, BrutalPressable } from '../components/ui';
import { useStyles, useToast, useRefreshControlProps } from '../hooks';
import { useAlert } from '../providers';
import {
  nativeData,
  type FinancialGoal,
  type FinancialGoalInput,
  type FinancialGoalUpdateInput,
} from '../native';
import { QUERY_KEYS, invalidateCache } from '../lib/queryClient';
import { SafeAreaView } from 'react-native-safe-area-context';
import { typography } from '../theme';

interface GoalView extends FinancialGoal {
  progress: number;
  isCompleted: boolean;
}

function toGoalView(goal: FinancialGoal): GoalView {
  const progress = goal.targetAmountMinor === 0
    ? 100
    : Math.min(100, (goal.currentAmountMinor / goal.targetAmountMinor) * 100);
  return {
    ...goal,
    progress,
    isCompleted: goal.currentAmountMinor >= goal.targetAmountMinor,
  };
}

function formatAmount(amountMinor: number): string {
  return (amountMinor / 100).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

export default function FinancialGoalScreen() {
  const navigation = useNavigation();
  const styles = useStyles(createStyles);
  const refreshControlProps = useRefreshControlProps();
  const { confirm } = useAlert();
  const { showError } = useToast();

  // 弹窗状态
  const [showFormModal, setShowFormModal] = useState(false);
  const [showDepositModal, setShowDepositModal] = useState(false);
  const [editingGoal, setEditingGoal] = useState<GoalView | null>(null);
  const [depositGoal, setDepositGoal] = useState<GoalView | null>(null);

  // 表单状态
  const [formName, setFormName] = useState('');
  const [formTarget, setFormTarget] = useState('');
  const [formDeadline, setFormDeadline] = useState('');
  const [depositAmount, setDepositAmount] = useState('');

  // 数据查询
  const { data: goalsData, isFetching, refetch } = useQuery({
    queryKey: QUERY_KEYS.financialGoals,
    queryFn: () => nativeData.getFinancialGoals(),
  });

  const goals = (goalsData ?? []).map(toGoalView);
  const totalGoals = goals.length;
  const completedGoals = goals.filter(g => g.isCompleted).length;

  // 创建/更新 mutation
  const createMutation = useMutation({
    mutationFn: (data: FinancialGoalInput) => nativeData.addFinancialGoal(data),
    onSuccess: () => {
      invalidateCache.financialGoals();
      closeFormModal();
    },
    onError: (error: any) => showError(error?.message || '创建目标失败'),
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, data }: { id: string; data: FinancialGoalUpdateInput }) =>
      nativeData.updateFinancialGoal(id, data),
    onSuccess: () => {
      invalidateCache.financialGoals();
      closeFormModal();
      setShowDepositModal(false);
      setDepositAmount('');
    },
    onError: (error: any) => showError(error?.message || '更新目标失败'),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => nativeData.deleteFinancialGoal(id),
    onSuccess: () => invalidateCache.financialGoals(),
    onError: (error: any) => showError(error?.message || '删除目标失败'),
  });

  // 表单操作
  const openCreateModal = () => {
    setEditingGoal(null);
    setFormName('');
    setFormTarget('');
    setFormDeadline('');
    setShowFormModal(true);
  };

  const openEditModal = (goal: GoalView) => {
    setEditingGoal(goal);
    setFormName(goal.name);
    setFormTarget((goal.targetAmountMinor / 100).toFixed(2));
    setFormDeadline(goal.targetDate ? goal.targetDate.split('T')[0] : '');
    setShowFormModal(true);
  };

  const closeFormModal = () => {
    setShowFormModal(false);
    setEditingGoal(null);
  };

  const openDepositModal = (goal: GoalView) => {
    setDepositGoal(goal);
    setDepositAmount('');
    setShowDepositModal(true);
  };

  const handleSubmitForm = () => {
    const target = parseFloat(formTarget);
    if (!formName.trim() || isNaN(target) || target <= 0) {return;}

    const payload: FinancialGoalInput = {
      name: formName.trim(),
      targetAmountMinor: Math.round(target * 100),
      targetDate: formDeadline.trim() || null,
    };

    if (editingGoal) {
      updateMutation.mutate({ id: editingGoal.id, data: payload });
    } else {
      createMutation.mutate(payload);
    }
  };

  const handleDeposit = () => {
    if (!depositGoal) {return;}
    const amount = parseFloat(depositAmount);
    if (isNaN(amount) || amount === 0) {return;}

    const newAmount = Math.max(
      0,
      Math.min(
        depositGoal.targetAmountMinor,
        depositGoal.currentAmountMinor + Math.round(amount * 100),
      ),
    );

    updateMutation.mutate({
      id: depositGoal.id,
      data: {
        currentAmountMinor: newAmount,
      },
    });
  };

  const handleDelete = (goal: GoalView) => {
    confirm(
      '删除目标',
      `确定要删除「${goal.name}」吗？此操作不可撤销。`,
      () => deleteMutation.mutate(goal.id),
      undefined,
      { confirmText: '删除', destructive: true },
    );
  };

  const onRefresh = useCallback(async () => {
    await refetch();
  }, [refetch]);

  const formatDeadline = (deadline?: string) => {
    if (!deadline) {return null;}
    const d = new Date(deadline);
    const now = new Date();
    const diff = Math.ceil((d.getTime() - now.getTime()) / (1000 * 60 * 60 * 24));
    const dateStr = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    if (diff < 0) {return `已过期 (${dateStr})`;}
    if (diff === 0) {return '今天截止';}
    if (diff <= 30) {return `剩余 ${diff} 天`;}
    return dateStr;
  };

  return (
    <SafeAreaView style={styles.container} edges={['top', 'bottom']}>
      {/* Header */}
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} style={styles.backButton}>
          <ChevronLeft size={24} color={styles._colors.textPrimary} strokeWidth={2.5} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>财务目标</Text>
        <TouchableOpacity onPress={openCreateModal} style={styles.addButton}>
          <Plus size={20} color={styles._isSoft ? styles._colors.primary : styles._colors.textPrimary} strokeWidth={styles._isSoft ? 2.4 : 3} />
        </TouchableOpacity>
      </View>

      {/* Summary */}
      <View style={styles.summaryBar}>
        <View style={styles.summaryItem}>
          <Text style={styles.summaryNumber}>{totalGoals}</Text>
          <Text style={styles.summaryLabel}>个目标</Text>
        </View>
        <View style={styles.summaryDivider} />
        <View style={styles.summaryItem}>
          <Text style={styles.summaryNumber}>{completedGoals}</Text>
          <Text style={styles.summaryLabel}>已完成</Text>
        </View>
        <View style={styles.summaryDivider} />
        <View style={styles.summaryItem}>
          <Text style={styles.summaryNumber}>{totalGoals - completedGoals}</Text>
          <Text style={styles.summaryLabel}>进行中</Text>
        </View>
      </View>

      {/* Goal List */}
      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.scrollContent}
        refreshControl={<RefreshControl {...refreshControlProps} refreshing={isFetching} onRefresh={onRefresh} />}
        showsVerticalScrollIndicator={false}
      >
        {goals.length === 0 ? (
          <View style={styles.emptyState}>
            <Text style={styles.emptyIcon}>🎯</Text>
            <Text style={styles.emptyText}>还没有财务目标</Text>
            <Text style={styles.emptySubtext}>设定一个目标，开始你的储蓄计划</Text>
            <TouchableOpacity style={styles.emptyButton} onPress={openCreateModal}>
              <Text style={styles.emptyButtonText}>创建目标</Text>
            </TouchableOpacity>
          </View>
        ) : (
          goals.map((goal) => (
            <View key={goal.id} style={[
              styles.goalCard,
              goal.isCompleted && styles.goalCardCompleted,
            ]}>
              {/* Card Header */}
              <View style={styles.goalHeader}>
                <View style={styles.goalTitleRow}>
                  <View style={styles.goalIconBox}>
                    <Text style={styles.goalIconText}>🎯</Text>
                  </View>
                  <View style={styles.goalTitleGroup}>
                    <Text style={styles.goalName} numberOfLines={1}>{goal.name}</Text>
                    {goal.targetDate && (
                      <Text style={styles.goalDeadline}>{formatDeadline(goal.targetDate)}</Text>
                    )}
                  </View>
                </View>
                <View style={[
                  styles.percentBadge,
                  goal.isCompleted ? styles.completedBadge : undefined,
                ]}>
                  <Text style={[
                    styles.percentText,
                    goal.isCompleted ? styles.completedBadgeText : undefined,
                  ]}>
                    {goal.isCompleted ? '✓' : `${Math.round(goal.progress)}%`}
                  </Text>
                </View>
              </View>

              {/* Progress */}
              <View style={styles.goalProgressContainer}>
                <ProgressBar
                  progress={goal.progress}
                  color={goal.isCompleted ? styles._colors.success : styles._colors.primary}
                  backgroundColor={styles._isSoft ? styles._colors.surfaceMuted : styles._colors.divider}
                  height={styles._isSoft ? 8 : 14}
                  style={styles.goalProgressBar}
                />
              </View>

              {/* Amounts */}
              <View style={styles.goalAmounts}>
                <Text style={styles.goalAmountCurrent}>
                  ¥{formatAmount(goal.currentAmountMinor)}
                </Text>
                <Text style={styles.goalAmountTarget}>
                  / ¥{formatAmount(goal.targetAmountMinor)}
                </Text>
              </View>

              {/* Actions */}
              <View style={styles.goalActions}>
                <BrutalPressable
                  style={styles.depositButton}
                  shadowOffset={2}
                  shadowColor={styles._colors.stroke}
                  onPress={() => openDepositModal(goal)}
                >
                  <Text style={styles.depositButtonText}>存入</Text>
                </BrutalPressable>
                <BrutalPressable
                  style={styles.editButton}
                  shadowOffset={2}
                  shadowColor={styles._colors.stroke}
                  onPress={() => openEditModal(goal)}
                >
                  <Text style={styles.editButtonText}>编辑</Text>
                </BrutalPressable>
                <TouchableOpacity
                  style={styles.deleteButton}
                  onPress={() => handleDelete(goal)}
                >
                  <Trash2 size={16} color={styles._colors.error} strokeWidth={2.5} />
                </TouchableOpacity>
              </View>
            </View>
          ))
        )}
      </ScrollView>

      {/* Create/Edit Modal */}
      <Modal visible={showFormModal} transparent animationType="fade">
        <View style={styles.modalOverlay}>
          <View style={styles.modalContent}>
            <Text style={styles.modalTitle}>
              {editingGoal ? '编辑目标' : '新建目标'}
            </Text>

            <Text style={styles.inputLabel}>目标名称</Text>
            <TextInput
              style={styles.textInput}
              value={formName}
              onChangeText={setFormName}
              placeholder="如：攒首付、年度存款5万"
              placeholderTextColor={styles._colors.textTertiary}
              maxLength={50}
            />

            <Text style={styles.inputLabel}>目标金额</Text>
            <TextInput
              style={styles.textInput}
              value={formTarget}
              onChangeText={setFormTarget}
              placeholder="0.00"
              placeholderTextColor={styles._colors.textTertiary}
              keyboardType="decimal-pad"
            />

            <Text style={styles.inputLabel}>截止日期（可选，格式 YYYY-MM-DD）</Text>
            <TextInput
              style={styles.textInput}
              value={formDeadline}
              onChangeText={setFormDeadline}
              placeholder="2026-12-31"
              placeholderTextColor={styles._colors.textTertiary}
              maxLength={10}
            />

            <View style={styles.modalActions}>
              <TouchableOpacity style={styles.cancelButton} onPress={closeFormModal}>
                <Text style={styles.cancelButtonText}>取消</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.submitButton, (!formName.trim() || !formTarget) && styles.submitButtonDisabled]}
                onPress={handleSubmitForm}
                disabled={!formName.trim() || !formTarget || createMutation.isPending || updateMutation.isPending}
              >
                <Text style={styles.submitButtonText}>
                  {editingGoal ? '保存' : '创建'}
                </Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>

      {/* Deposit Modal */}
      <Modal visible={showDepositModal} transparent animationType="fade">
        <View style={styles.modalOverlay}>
          <View style={styles.modalContent}>
            <Text style={styles.modalTitle}>
              🎯 {depositGoal?.name}
            </Text>
            <Text style={styles.depositInfo}>
              当前 ¥{formatAmount(depositGoal?.currentAmountMinor ?? 0)} / 目标 ¥{formatAmount(depositGoal?.targetAmountMinor ?? 0)}
            </Text>

            <Text style={styles.inputLabel}>存入/取出金额</Text>
            <TextInput
              style={styles.textInput}
              value={depositAmount}
              onChangeText={setDepositAmount}
              placeholder="输入正数存入，负数取出"
              placeholderTextColor={styles._colors.textTertiary}
              keyboardType="numeric"
            />

            <View style={styles.modalActions}>
              <TouchableOpacity
                style={styles.cancelButton}
                onPress={() => { setShowDepositModal(false); setDepositAmount(''); }}
              >
                <Text style={styles.cancelButtonText}>取消</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.submitButton, !depositAmount && styles.submitButtonDisabled]}
                onPress={handleDeposit}
                disabled={!depositAmount || updateMutation.isPending}
              >
                <Text style={styles.submitButtonText}>确认</Text>
              </TouchableOpacity>
            </View>
          </View>
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

    // ===== Goal Card =====
    goalCard: {
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.isSoft ? shape.borderWidth.card : borderWidth.medium,
      borderColor: shape.isSoft ? colors.cardBorder : colors.stroke,
      padding: spacing.lg,
      marginBottom: spacing.md,
      ...(shape.isSoft ? shape.elevation.card : shadow.small),
    },
    goalCardCompleted: {
      opacity: 0.7,
    },
    goalHeader: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      marginBottom: spacing.md,
    },
    goalTitleRow: {
      flexDirection: 'row',
      alignItems: 'center',
      flex: 1,
      gap: spacing.md,
    },
    goalIconBox: {
      width: 44,
      height: 44,
      borderRadius: shape.isSoft ? 14 : borderRadius.medium,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: shape.isSoft ? colors.primaryLight : colors.accent + '30',
      alignItems: 'center',
      justifyContent: 'center',
    },
    goalIconText: {
      fontSize: 22,
    },
    goalTitleGroup: {
      flex: 1,
    },
    goalName: {
      fontSize: 16,
      fontWeight: shape.isSoft ? '700' : '800',
      color: colors.textPrimary,
    },
    goalDeadline: {
      fontSize: 12,
      fontWeight: shape.isSoft ? '500' : '600',
      color: colors.textTertiary,
      fontFamily: shape.isSoft ? undefined : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
      marginTop: 2,
    },
    percentBadge: {
      backgroundColor: shape.isSoft ? colors.primaryLight : colors.accent,
      borderRadius: shape.isSoft ? shape.radius.pill : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.stroke,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.xs,
      marginLeft: spacing.sm,
    },
    completedBadge: {
      backgroundColor: colors.success,
    },
    percentText: {
      fontSize: 13,
      fontWeight: shape.isSoft ? '700' : '800',
      color: shape.isSoft ? colors.primaryDark : colors.textPrimary,
      fontFamily: shape.isSoft ? undefined : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
    },
    completedBadgeText: {
      color: '#FFFFFF',
    },

    // Progress
    goalProgressContainer: {
      borderRadius: shape.isSoft ? 999 : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.stroke,
      overflow: 'hidden',
      marginBottom: spacing.sm,
    },
    goalProgressBar: {},

    // Amounts
    goalAmounts: {
      flexDirection: 'row',
      alignItems: 'baseline',
      marginBottom: spacing.md,
    },
    goalAmountCurrent: {
      fontSize: 18,
      fontWeight: shape.isSoft ? '800' : '900',
      color: colors.textPrimary,
      fontFamily: shape.isSoft ? undefined : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
    },
    goalAmountTarget: {
      fontSize: 13,
      fontWeight: '600',
      color: colors.textTertiary,
      fontFamily: shape.isSoft ? undefined : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
      marginLeft: spacing.xs,
    },

    // Actions
    goalActions: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
    },
    depositButton: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.xs,
      backgroundColor: shape.isSoft ? colors.incomeLight : colors.successLight,
      borderRadius: shape.isSoft ? 12 : borderRadius.button,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.success,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.sm,
    },
    depositButtonText: {
      fontSize: 13,
      fontWeight: '700',
      color: shape.isSoft ? colors.incomeStrong : colors.textPrimary,
    },
    editButton: {
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
      paddingHorizontal: spacing.xl,
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
    depositInfo: {
      fontSize: 14,
      fontWeight: '600',
      color: colors.textSecondary,
      textAlign: 'center',
      fontFamily: shape.isSoft ? undefined : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
      marginBottom: spacing.sm,
    },
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
  _colors: colors,
  _isSoft: shape.isSoft,
});

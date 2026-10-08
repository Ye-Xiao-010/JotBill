/**
 * 创建/编辑账单屏幕 - Neo-Brutalism 风格
 * 描边金额输入 + 糖果色类型切换 + 描边分类网格
 */
import React, { useEffect, useState } from 'react';
import {
  View,
  ScrollView,
  StyleSheet,
  TextInput,
  TouchableOpacity,
} from 'react-native';
import { Text } from 'react-native';
import { Toast } from '../../components';
import { AddCategoryModal, ConfirmModal } from '../../components/modals';
import { useToast, useCategories, useStyles } from '../../hooks';
import { nativeData, type Account, type Bill, type Category } from '../../native';
import { invalidateCache } from '../../lib/queryClient';
import { ThemeColors } from '../../theme/colors';
import { spacing, borderRadius, borderWidth, shadow } from '../../theme';

// 固定分类列表
const FIXED_CATEGORIES = [
  { icon: '🍽️', name: '餐饮' },
  { icon: '🚗', name: '交通' },
  { icon: '🛍️', name: '购物' },
  { icon: '🏠', name: '居住' },
  { icon: '🎮', name: '娱乐' },
  { icon: '💊', name: '医疗' },
  { icon: '📚', name: '教育' },
];

/** 阶段五 T7：手动表单支持的交易类型（转账/还款需要双账户，退款/手续费走待确认链路）。 */
type BillFormType = 'expense' | 'income' | 'transfer' | 'repayment';

/** 类型选项标签（阶段五 T7 双账户语义）。 */
const FORM_TYPE_OPTIONS: Array<{ value: BillFormType; label: string }> = [
  { value: 'expense', label: '支出' },
  { value: 'income', label: '收入' },
  { value: 'transfer', label: '转账' },
  { value: 'repayment', label: '还款' },
];

/** 编辑既有账单时，回显支持的手动类型；不支持的类型（退款/手续费等）回退支出。 */
function resolveInitialFormType(billType: string | undefined, fallback?: 'income' | 'expense'): BillFormType {
  const supported: BillFormType[] = ['expense', 'income', 'transfer', 'repayment'];
  if (billType && (supported as string[]).includes(billType)) {
    return billType as BillFormType;
  }
  return fallback ?? 'expense';
}

interface CreateBillScreenProps {
  navigation: any;
  route?: {
    params?: {
      bill?: Bill;
      initialType?: 'income' | 'expense';
    };
  };
}

const CreateBillScreen: React.FC<CreateBillScreenProps> = ({ navigation, route }) => {
  const { toastState, showSuccess, showError, hideToast } = useToast();
  const styles = useStyles(createStyles);

  const editingBill = route?.params?.bill;
  const initialType = route?.params?.initialType;
  const isEditing = !!editingBill;
  // 编辑退款/手续费/调整类账单时锁定类型：表单不支持这些类型，若随表单提交会把
  // refund/fee 静默改写成 expense，收支统计双向偏移（同笔金额从收入侧挪到支出侧）。
  const editingTypeLocked = !!editingBill &&
    !(['expense', 'income', 'transfer', 'repayment'] as string[]).includes(editingBill.type);

  const [amount, setAmount] = useState(
    editingBill ? (editingBill.amountMinor / 100).toFixed(2) : ''
  );
  const [type, setType] = useState<BillFormType>(
    resolveInitialFormType(editingBill?.type, initialType)
  );
  const [selectedCategory, setSelectedCategory] = useState<Category | null>(null);
  // 批 6:账单表单中展开子分类区域的顶级分类 ID(点顶级展开,点子分类选中)
  const [expandedBillCategoryId, setExpandedBillCategoryId] = useState<string | null>(null);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [selectedAccountId, setSelectedAccountId] = useState<string | undefined>(editingBill?.accountId);
  // 阶段五 T7：转账/还款目标账户（编辑回显）
  const [selectedTargetAccountId, setSelectedTargetAccountId] = useState<string | undefined>(
    editingBill?.targetAccountId
  );
  const [description, setDescription] = useState(editingBill?.description || '');
  const [loading, setLoading] = useState(false);

  // 转账/还款不计收支，无分类概念
  const isTransferLike = type === 'transfer' || type === 'repayment';
  const { categories } = useCategories(isTransferLike ? 'expense' : type);

  useEffect(() => {
    let mounted = true;
    nativeData.getAccounts()
      .then(nextAccounts => {
        if (mounted) setAccounts(nextAccounts);
      })
      .catch(() => {
        if (mounted) setAccounts([]);
      });
    return () => {
      mounted = false;
    };
  }, []);

  useEffect(() => {
    setSelectedAccountId(editingBill?.accountId);
    setSelectedTargetAccountId(editingBill?.targetAccountId);
  }, [editingBill?.accountId, editingBill?.targetAccountId]);

  useEffect(() => {
    if (!editingBill?.categoryId) return;
    const matched = categories.find(category => category.id === editingBill.categoryId) || null;
    setSelectedCategory(matched);
    // 批 6 缺陷修复:编辑回显时若分类为子分类,自动展开其父级,便于用户看到当前选中项
    setExpandedBillCategoryId(matched?.parentId ?? null);
  }, [categories, editingBill?.categoryId]);

  const [showAddCategoryModal, setShowAddCategoryModal] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [categoryToDelete, setCategoryToDelete] = useState<Category | null>(null);

  /** 切换类型时清空分类与目标账户（不同类型的字段语义不同）。 */
  const handleTypeChange = (newType: BillFormType) => {
    setType(newType);
    setSelectedCategory(null);
    setExpandedBillCategoryId(null);
    setSelectedTargetAccountId(undefined);
  };

  const handleCategoryLongPress = (category: Category) => {
    if (category.isDefault) {
      showError('系统默认分类不能删除');
      return;
    }
    setCategoryToDelete(category);
    setShowDeleteConfirm(true);
  };

  const handleConfirmDelete = async () => {
    if (!categoryToDelete) return;
    try {
      await nativeData.deleteCategory(categoryToDelete.id);
      showSuccess('分类删除成功');
      invalidateCache.categories();
      if (selectedCategory?.id === categoryToDelete.id) {
        setSelectedCategory(null);
      }
    } catch (error: any) {
      showError(error.message || '删除分类失败');
    } finally {
      setShowDeleteConfirm(false);
      setCategoryToDelete(null);
    }
  };

  const handleOtherClick = () => {
    setShowAddCategoryModal(true);
  };

  const handleAddCategorySuccess = () => {
    invalidateCache.categories();
  };

  const getCategoryList = () => {
    const mergedCategories: Category[] = [];
    FIXED_CATEGORIES.forEach(fixedCat => {
      const matchedCategory = categories.find(cat => cat.name === fixedCat.name);
      if (matchedCategory) {
        mergedCategories.push(matchedCategory);
      }
    });
    const customCategories = categories.filter(
      cat => !FIXED_CATEGORIES.some(fixed => fixed.name === cat.name)
    );
    return [...mergedCategories, ...customCategories];
  };

  /** 子分类展示排序:预设子分类在前,自定义在后;同组内按 sortOrder 升序 */
  const sortChildren = (children: Category[]) =>
    [...children].sort(
      (a, b) =>
        Number(b.isDefault) - Number(a.isDefault) || a.sortOrder - b.sortOrder,
    );

  const selectableAccounts = accounts.filter(
    account => account.isActive || account.id === editingBill?.accountId,
  );

  /** 转账/还款的目标账户候选：转账为全部启用账户，还款限定负债账户。 */
  const selectableTargetAccounts = selectableAccounts.filter(account =>
    type === 'repayment' ? account.kind === 'liability' : true,
  );

  /** 表单校验：金额合法且有上限；账户必填；非转账类必选分类；转账/还款必选双账户且源≠目标、还款目标为负债。 */
  const validateForm = () => {
    const trimmedAmount = amount.trim();
    // ⚠️ 原来写的是 `!amount || parseFloat(amount) <= 0`：parseFloat('abc') 返回
    // NaN，而 `NaN <= 0` 为 false ⇒ 校验通过 ⇒ amountMinor = NaN ⇒ 原生
    // assertMinorUnit 抛英文 "amountMinor must be a safe integer minor unit"，
    // 用户填的是"12块"却收到一句天书。改用 Number.isFinite 兜住 NaN。
    // 另外补小数位与上限校验：此前无上限，一笔亿元账单会直接落库并把余额扣成大额负数。
    const parsedAmount = Number(trimmedAmount);
    if (!trimmedAmount || !Number.isFinite(parsedAmount) || parsedAmount <= 0) {
      showError('请输入有效的金额');
      return false;
    }
    if (!/^\d+(\.\d{1,2})?$/.test(trimmedAmount)) {
      showError('金额最多两位小数');
      return false;
    }
    if (parsedAmount > 99_999_999.99) {
      showError('金额过大（上限 9999 万）');
      return false;
    }
    if (isTransferLike) {
      if (!selectedAccountId) {
        showError('请选择源账户');
        return false;
      }
      if (!selectedTargetAccountId) {
        showError(type === 'repayment' ? '请选择还款的负债账户' : '请选择目标账户');
        return false;
      }
      if (selectedAccountId === selectedTargetAccountId) {
        showError('源账户与目标账户不能相同');
        return false;
      }
      const target = accounts.find(account => account.id === selectedTargetAccountId);
      if (type === 'repayment' && target && target.kind !== 'liability') {
        showError('还款的目标账户必须是负债账户（信用卡/花呗等）');
        return false;
      }
      return true;
    }
    // ⚠️ 账户是原生层的硬性入账前提：LedgerRepository.validateBillAccounts
    // 无条件要求 accountId（缺失即抛 "Transaction requires an account"）。
    // 此前只有转账分支校验账户，非转账分支"不选就不传"，于是每一笔普通支出
    // 都要先失败一次才能看到错误 —— 而错误提示来自 Toast，恰好在本页可见，
    // 用户看到的是一句英文。这里补上前端校验，把问题拦在提交之前。
    if (!selectedAccountId) {
      showError('请选择账户');
      return false;
    }
    if (!selectedCategory) {
      showError('请选择分类');
      return false;
    }
    return true;
  };

  const handleSave = async () => {
    if (!validateForm()) return;
    try {
      setLoading(true);
      const amountMinor = Math.round(parseFloat(amount) * 100);
      const billData = {
        amountMinor,
        // 类型锁定的编辑（退款/手续费等）保留原类型，仅允许修改金额/备注/分类/账户
        type: editingTypeLocked ? editingBill!.type : type,
        description: description.trim() || null,
        occurredAt: editingBill?.occurredAt || new Date().toISOString(),
        // 转账/还款不计收支，不写分类
        ...(isTransferLike ? {} : { categoryId: selectedCategory!.id }),
        sourceType: editingBill?.sourceType || 'manual' as const,
        ...(isTransferLike
          ? { accountId: selectedAccountId, targetAccountId: selectedTargetAccountId }
          : selectedAccountId
            ? { accountId: selectedAccountId }
            : {}),
      };

      if (isEditing) {
        await nativeData.updateBill(editingBill!.id, {
          ...billData,
          version: editingBill!.version,
        });
      } else {
        await nativeData.addBill(billData);
      }

      invalidateCache.bills();
      showSuccess(isEditing ? '账单更新成功' : '账单创建成功');
      navigation.goBack();
    } catch (error: any) {
      showError(error.message || '操作失败');
    } finally {
      setLoading(false);
    }
  };


  return (
    <View style={styles.container}>
      <ScrollView style={styles.scrollView} showsVerticalScrollIndicator={false}>
        <View style={styles.content}>
          {/* 类型切换（阶段五 T7：含转账/还款双账户语义） */}
          <View style={styles.typeSelector}>
            {FORM_TYPE_OPTIONS.map(option => (
              <TouchableOpacity
                key={option.value}
                disabled={editingTypeLocked}
                style={[
                  styles.typeButton,
                  type === option.value && styles.typeButtonActive,
                  type === option.value && option.value === 'expense' && styles.typeButtonExpenseActive,
                  type === option.value && option.value === 'income' && styles.typeButtonIncomeActive,
                  type === option.value && option.value === 'transfer' && styles.typeButtonTransferActive,
                  type === option.value && option.value === 'repayment' && styles.typeButtonRepaymentActive,
                ]}
                onPress={() => handleTypeChange(option.value)}
              >
                <Text style={[styles.typeButtonText, type === option.value && styles.typeButtonTextActive]}>
                  {option.label}
                </Text>
              </TouchableOpacity>
            ))}
          </View>
          {editingTypeLocked && (
            <Text style={styles.typeLockHint}>
              退款/手续费类型不可更改（仅可修改金额、备注、分类、账户）；如需变更类型请删除后重新记账。
            </Text>
          )}

          {/* 金额输入 */}
          <View style={styles.formGroup}>
            <Text style={styles.formLabel}>💰 金额</Text>
            <View style={styles.amountInputContainer}>
              <Text style={styles.currencySymbol}>¥</Text>
              <TextInput
                style={styles.amountInput}
                value={amount}
                onChangeText={setAmount}
                placeholder="0.00"
                keyboardType="decimal-pad"
                placeholderTextColor={styles._colors.textTertiary}
              />
            </View>
          </View>

          {selectableAccounts.length === 0 ? (
            // ⚠️ 此前此处直接不渲染账户区块：账户加载失败（被 .catch 静默吞成 []）
            // 或用户把账户全部停用时，用户会看到一个没有任何账户选项的表单，
            // 点了保存才收到一句英文 "Transaction requires an account" ——
            // 既不知道发生了什么，也不知道该去哪建账户。显式空态 + 去添加入口。
            <View style={styles.formGroup}>
              <Text style={styles.formLabel}>{isTransferLike ? '💳 源账户' : '账户'}</Text>
              <View style={styles.accountGrid}>
                <TouchableOpacity
                  style={[styles.accountOption, styles.accountOptionSelected]}
                  onPress={() => navigation.navigate('Accounts')}
                >
                  <Text style={styles.accountOptionName}>尚未创建账户</Text>
                  <Text style={styles.accountOptionType}>点这里去「账户管理」添加</Text>
                </TouchableOpacity>
              </View>
            </View>
          ) : (
            <View style={styles.formGroup}>
              <Text style={styles.formLabel}>{isTransferLike ? '💳 源账户' : '账户'}</Text>
              <View style={styles.accountGrid}>
                {selectableAccounts.map(account => (
                  <TouchableOpacity
                    key={account.id}
                    style={[
                      styles.accountOption,
                      selectedAccountId === account.id && styles.accountOptionSelected,
                    ]}
                    onPress={() => setSelectedAccountId(account.id)}
                  >
                    <Text style={styles.accountOptionName} numberOfLines={1}>
                      {account.name}
                    </Text>
                    <Text style={styles.accountOptionType}>
                      {account.kind === 'liability' ? '负债' : '资产'}
                    </Text>
                  </TouchableOpacity>
                ))}
              </View>
            </View>
          )}

          {/* 目标账户（阶段五 T7：转账/还款双账户，还款限负债账户） */}
          {isTransferLike && selectableTargetAccounts.length > 0 && (
            <View style={styles.formGroup}>
              <Text style={styles.formLabel}>
                {type === 'repayment' ? '🏦 目标账户（限负债）' : '🏦 目标账户'}
              </Text>
              <View style={styles.accountGrid}>
                {selectableTargetAccounts.map(account => (
                  <TouchableOpacity
                    key={account.id}
                    style={[
                      styles.accountOption,
                      selectedTargetAccountId === account.id && styles.accountOptionSelected,
                    ]}
                    onPress={() => setSelectedTargetAccountId(account.id)}
                  >
                    <Text style={styles.accountOptionName} numberOfLines={1}>
                      {account.name}
                    </Text>
                    <Text style={styles.accountOptionType}>
                      {account.kind === 'liability' ? '负债' : '资产'}
                    </Text>
                  </TouchableOpacity>
                ))}
              </View>
              {type === 'repayment' ? (
                <Text style={styles.targetAccountHint}>
                  还款目标须为负债账户（信用卡/花呗等），确认后欠款减少。
                </Text>
              ) : (
                <Text style={styles.targetAccountHint}>
                  转账不计收支，源账户扣减、目标账户入账。
                </Text>
              )}
            </View>
          )}

          {/* 分类选择（转账/还款不计收支，无分类） */}
          {!isTransferLike && (
          <View style={styles.formGroup}>
            <Text style={styles.formLabel}>📂 分类</Text>
            {(() => {
              const allCats = getCategoryList();
              const topLevel = allCats.filter(cat => !cat.parentId);
              const rows = [];
              for (let i = 0; i < topLevel.length; i += 4) {
                rows.push(topLevel.slice(i, i + 4));
              }
              return rows.map((rowCats, rowIndex) => (
                <View key={`cat-row-${rowIndex}`} style={styles.categoryRowGrid}>
                  {rowCats.map((category) => {
                    const children = allCats.filter(cat => cat.parentId === category.id);
                    const childSelected = children.some(cat => cat.id === selectedCategory?.id);
                    const topLevelSelected =
                      selectedCategory?.id === category.id || childSelected;
                    const expanded = expandedBillCategoryId === category.id;
                    return (
                      <TouchableOpacity
                        key={category.id}
                        style={[styles.categoryItem, topLevelSelected && styles.categoryItemSelected]}
                        onPress={() => {
                          // 点击顶级分类:若其子分类已被选中,再次点击顶级会取消子分类
                          // 并使用顶级分类;否则按原逻辑展开/收起子分类面板。
                          setSelectedCategory(category);
                          if (children.length > 0) {
                            if (expanded) {
                              setExpandedBillCategoryId(null);
                            } else if (childSelected) {
                              // 取消子分类,使用顶级分类;保持面板关闭
                              setExpandedBillCategoryId(null);
                            } else {
                              setExpandedBillCategoryId(category.id);
                            }
                          } else {
                            setExpandedBillCategoryId(null);
                          }
                        }}
                        onLongPress={() => handleCategoryLongPress(category)}
                        delayLongPress={500}
                      >
                        <Text style={styles.categoryIcon}>{category.icon}</Text>
                        <Text
                          style={[styles.categoryName, topLevelSelected && styles.categoryNameSelected]}
                        >
                          {category.name}
                          {children.length > 0 ? (expanded ? ' ▾' : ' ›') : ''}
                        </Text>
                      </TouchableOpacity>
                    );
                  })}
                  {rowIndex === rows.length - 1 ? (
                    <TouchableOpacity
                      style={styles.categoryItem}
                      onPress={handleOtherClick}
                    >
                      <Text style={styles.categoryIcon}>➕</Text>
                      <Text style={styles.categoryName}>其他</Text>
                    </TouchableOpacity>
                  ) : null}
                  {expandedBillCategoryId &&
                  rowCats.some(cat => cat.id === expandedBillCategoryId) ? (
                    <View style={styles.childChipArea}>
                      {sortChildren(
                        allCats.filter(cat => cat.parentId === expandedBillCategoryId),
                      ).map((child) => (
                          <TouchableOpacity
                            key={child.id}
                            style={[
                              styles.childChip,
                              selectedCategory?.id === child.id && styles.childChipSelected,
                            ]}
                            onPress={() => {
                              // 选中子分类后保持子分类面板展开,只有点击顶级分类才收起
                              setSelectedCategory(child);
                            }}
                          >
                            <Text style={styles.childChipText}>{child.name}</Text>
                          </TouchableOpacity>
                        ))}
                    </View>
                  ) : null}
                </View>
              ));
            })()}
          </View>
          )}

          {/* 备注输入 */}
          <View style={styles.formGroup}>
            <Text style={styles.formLabel}>📝 备注</Text>
            <TextInput
              style={styles.descriptionInput}
              value={description}
              onChangeText={setDescription}
              placeholder="添加备注..."
              placeholderTextColor={styles._colors.textTertiary}
              multiline
              numberOfLines={3}
              textAlignVertical="top"
            />
          </View>
        </View>
      </ScrollView>

      {/* 底部操作按钮 */}
      <View style={styles.bottomActions}>
        <TouchableOpacity style={styles.cancelButton} onPress={() => navigation.goBack()} activeOpacity={0.8}>
          <Text style={styles.cancelButtonText}>取消</Text>
        </TouchableOpacity>
        <TouchableOpacity style={styles.saveButton} onPress={handleSave} disabled={loading} activeOpacity={0.8}>
          {/* 文案此前写"下一步"，但 handleSave 没有任何后续步骤：直接提交并 goBack。
            与行为不符的按钮会让人以为还有一屏内容。 */}
          <Text style={styles.saveButtonText}>{isEditing ? '更新账单' : '保存'}</Text>
        </TouchableOpacity>
      </View>

      {/* Toast 已提升为全局单例（ToastProvider），此处不再重复渲染 ——
          否则本页面与全局 Toast 会同时显示同一条提示。 */}

      <AddCategoryModal
        visible={showAddCategoryModal}
        onClose={() => setShowAddCategoryModal(false)}
        onSuccess={handleAddCategorySuccess}
        billType={type === 'income' ? 'income' : 'expense'}
      />

      <ConfirmModal
        visible={showDeleteConfirm}
        title="删除分类"
        message={`确定要删除分类"${categoryToDelete?.name}"吗？`}
        confirmText="删除"
        cancelText="取消"
        onConfirm={handleConfirmDelete}
        onCancel={() => { setShowDeleteConfirm(false); setCategoryToDelete(null); }}
      />
    </View>
  );
};

const createStyles = (colors: ThemeColors) => ({
  ...StyleSheet.create({
    container: { flex: 1, backgroundColor: colors.background },
    scrollView: { flex: 1 },
    content: { padding: spacing.lg, paddingBottom: 100 },
    typeSelector: {
      flexDirection: 'row',
      backgroundColor: colors.surface,
      borderRadius: borderRadius.button,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      padding: 4,
      marginBottom: spacing.lg,
      alignSelf: 'center',
      width: '100%',
    },
    typeButton: {
      flex: 1,
      paddingVertical: spacing.sm,
      alignItems: 'center',
      borderRadius: borderRadius.small,
    },
    typeButtonActive: {
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
    },
    typeButtonExpenseActive: {
      backgroundColor: colors.expense,
    },
    typeButtonIncomeActive: {
      backgroundColor: colors.income,
    },
    typeButtonTransferActive: {
      backgroundColor: colors.primary,
    },
    typeButtonRepaymentActive: {
      backgroundColor: colors.warning,
    },
    targetAccountHint: {
      marginTop: spacing.xs,
      fontSize: 12,
      fontWeight: '600',
      color: colors.textSecondary,
    },
    typeButtonText: { fontSize: 16, fontWeight: '700', color: colors.textSecondary },
    typeButtonTextActive: { color: '#FFFFFF', fontWeight: '800' },
    typeLockHint: {
      fontSize: 12,
      color: colors.textSecondary,
      marginTop: spacing.xs,
    },
    formGroup: { marginBottom: spacing.lg },
    formLabel: { fontSize: 14, fontWeight: '800', color: colors.textPrimary, marginBottom: spacing.sm },
    amountInputContainer: {
      flexDirection: 'row',
      alignItems: 'center',
      backgroundColor: colors.surface,
      borderRadius: borderRadius.card,
      borderWidth: borderWidth.medium,
      borderColor: colors.stroke,
      paddingHorizontal: spacing.lg,
      paddingVertical: spacing.lg,
    },
    currencySymbol: {
      fontSize: 28,
      fontWeight: '800',
      fontFamily: 'Courier',
      color: colors.textPrimary,
      marginRight: spacing.sm,
    },
    amountInput: {
      flex: 1,
      fontSize: 28,
      fontWeight: '800',
      fontFamily: 'Courier',
      color: colors.textPrimary,
      padding: 0,
    },
      descriptionInput: {
        backgroundColor: colors.surface,
        borderRadius: borderRadius.input,
        borderWidth: borderWidth.thin,
        borderColor: colors.stroke,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.md,
      fontSize: 15,
        fontWeight: '600',
        color: colors.textPrimary,
        minHeight: 80,
      },
      accountGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
      accountOption: {
        width: '48%',
        padding: spacing.md,
        borderRadius: borderRadius.small,
        borderWidth: borderWidth.thin,
        borderColor: colors.stroke,
        backgroundColor: colors.surface,
        gap: spacing.xs,
      },
      accountOptionSelected: {
        backgroundColor: colors.accent,
        borderWidth: borderWidth.medium,
        borderColor: colors.stroke,
      },
      accountOptionName: {
        fontSize: 14,
        fontWeight: '800',
        color: colors.textPrimary,
      },
      accountOptionType: {
        fontSize: 12,
        fontWeight: '600',
        color: colors.textSecondary,
      },
      categoryGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.md },
      // 批 6:按行分块的容器,行内 4 个正方形瓷贴;展开的子分类区插在行后
      categoryRowGrid: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        width: '100%',
        gap: spacing.sm,
        marginBottom: spacing.xs,
      },
      categoryItem: {
        width: '22%',
        aspectRatio: 1,
        justifyContent: 'center',
        alignItems: 'center',
        borderRadius: borderRadius.small,
        borderWidth: borderWidth.thin,
        borderColor: colors.stroke,
        backgroundColor: colors.surface,
        padding: spacing.sm,
      },
    categoryItemSelected: {
      backgroundColor: colors.accent,
      borderWidth: borderWidth.medium,
      borderColor: colors.stroke,
    },
    categoryIcon: { fontSize: 24, marginBottom: 4 },
    categoryName: { fontSize: 12, color: colors.textPrimary, textAlign: 'center', fontWeight: '600' },
    categoryNameSelected: { color: colors.textPrimary, fontWeight: '800' },
    // 批 6:子分类动态区(点顶级后展开,芯片自动换行,区域高度动态)
    childChipArea: {
      width: '100%',
      flexDirection: 'row',
      flexWrap: 'wrap',
      gap: spacing.sm,
      marginTop: spacing.sm,
      marginBottom: spacing.sm,
      paddingHorizontal: spacing.xs,
    },
    childChip: {
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.sm,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: colors.surface,
    },
    childChipSelected: {
      backgroundColor: colors.accent,
      borderWidth: borderWidth.medium,
    },
    childChipText: {
      fontSize: 13,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    bottomActions: {
      flexDirection: 'row',
      gap: spacing.md,
      padding: spacing.lg,
      paddingBottom: spacing.xxl,
      backgroundColor: colors.background,
      borderTopWidth: borderWidth.thin,
      borderTopColor: colors.stroke,
    },
    cancelButton: {
      flex: 1,
      height: 52,
      borderRadius: borderRadius.button,
      backgroundColor: colors.surface,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      alignItems: 'center',
      justifyContent: 'center',
    },
    cancelButtonText: { fontSize: 16, fontWeight: '700', color: colors.textPrimary },
    saveButton: {
      flex: 1,
      height: 52,
      borderRadius: borderRadius.button,
      backgroundColor: colors.primary,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      alignItems: 'center',
      justifyContent: 'center',
      ...shadow.small,
    },
    saveButtonText: { fontSize: 16, fontWeight: '800', color: '#FFFFFF' },
  }),
  _colors: colors,
});

export default CreateBillScreen;

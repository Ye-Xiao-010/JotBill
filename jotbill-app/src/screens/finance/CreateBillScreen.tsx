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
import { useAlert } from '../../providers';
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
  const { confirm } = useAlert();
  const styles = useStyles(createStyles);

  const editingBill = route?.params?.bill;
  const initialType = route?.params?.initialType;
  const isEditing = !!editingBill;

  const [amount, setAmount] = useState(
    editingBill ? (editingBill.amountMinor / 100).toFixed(2) : ''
  );
  const [type, setType] = useState<'income' | 'expense'>(
    editingBill?.type === 'income' ? 'income' : initialType || 'expense'
  );
  const [selectedCategory, setSelectedCategory] = useState<Category | null>(null);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [selectedAccountId, setSelectedAccountId] = useState<string | undefined>(editingBill?.accountId);
  const [description, setDescription] = useState(editingBill?.description || '');
  const [loading, setLoading] = useState(false);

  const { categories } = useCategories(type);

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
  }, [editingBill?.accountId]);

  useEffect(() => {
    if (!editingBill?.categoryId) return;
    setSelectedCategory(
      categories.find(category => category.id === editingBill.categoryId) || null
    );
  }, [categories, editingBill?.categoryId]);

  const [showAddCategoryModal, setShowAddCategoryModal] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [categoryToDelete, setCategoryToDelete] = useState<Category | null>(null);

  const handleTypeChange = (newType: 'income' | 'expense') => {
    setType(newType);
    setSelectedCategory(null);
  };

  const handleCategorySelect = (category: Category) => {
    setSelectedCategory(category);
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

  const selectableAccounts = accounts.filter(
    account => account.isActive || account.id === editingBill?.accountId,
  );

  const validateForm = () => {
    if (!amount || parseFloat(amount) <= 0) {
      showError('请输入有效的金额');
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
        type,
        description: description.trim() || null,
        occurredAt: editingBill?.occurredAt || new Date().toISOString(),
        categoryId: selectedCategory!.id,
        sourceType: editingBill?.sourceType || 'manual' as const,
        ...(selectedAccountId ? { accountId: selectedAccountId } : {}),
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

  const handleDelete = async () => {
    if (!isEditing) return;
    confirm(
      '确认删除',
      '确定要删除这笔账单吗？此操作无法撤销。',
      async () => {
        try {
          setLoading(true);
          await nativeData.deleteBill(editingBill!.id);
          invalidateCache.bills();
          showSuccess('账单删除成功');
          navigation.goBack();
        } catch (error: any) {
          showError(error.message || '删除失败');
        } finally {
          setLoading(false);
        }
      },
      undefined,
      { confirmText: '删除', destructive: true }
    );
  };

  return (
    <View style={styles.container}>
      <ScrollView style={styles.scrollView} showsVerticalScrollIndicator={false}>
        <View style={styles.content}>
          {/* 支出/收入切换 */}
          <View style={styles.typeSelector}>
            <TouchableOpacity
              style={[styles.typeButton, type === 'expense' && styles.typeButtonExpenseActive]}
              onPress={() => handleTypeChange('expense')}
            >
              <Text style={[styles.typeButtonText, type === 'expense' && styles.typeButtonTextActive]}>
                支出
              </Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={[styles.typeButton, type === 'income' && styles.typeButtonIncomeActive]}
              onPress={() => handleTypeChange('income')}
            >
              <Text style={[styles.typeButtonText, type === 'income' && styles.typeButtonTextActive]}>
                收入
              </Text>
            </TouchableOpacity>
          </View>

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
                keyboardType="numeric"
                placeholderTextColor={styles._colors.textTertiary}
              />
            </View>
          </View>

          {selectableAccounts.length > 0 && (
            <View style={styles.formGroup}>
              <Text style={styles.formLabel}>账户</Text>
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

          {/* 分类选择 */}
          <View style={styles.formGroup}>
            <Text style={styles.formLabel}>📂 分类</Text>
            <View style={styles.categoryGrid}>
              {getCategoryList().map((category) => (
                <TouchableOpacity
                  key={category.id}
                  style={[styles.categoryItem, selectedCategory?.id === category.id && styles.categoryItemSelected]}
                  onPress={() => handleCategorySelect(category)}
                  onLongPress={() => handleCategoryLongPress(category)}
                  delayLongPress={500}
                >
                  <Text style={styles.categoryIcon}>{category.icon}</Text>
                  <Text style={[styles.categoryName, selectedCategory?.id === category.id && styles.categoryNameSelected]}>
                    {category.name}
                  </Text>
                </TouchableOpacity>
              ))}
              <TouchableOpacity style={styles.categoryItem} onPress={handleOtherClick}>
                <Text style={styles.categoryIcon}>➕</Text>
                <Text style={styles.categoryName}>其他</Text>
              </TouchableOpacity>
            </View>
          </View>

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
          <Text style={styles.saveButtonText}>{isEditing ? '更新账单' : '下一步'}</Text>
        </TouchableOpacity>
      </View>

      <Toast
        visible={toastState.visible}
        message={toastState.message}
        type={toastState.type}
        duration={toastState.duration}
        position={toastState.position}
        onHide={hideToast}
      />

      <AddCategoryModal
        visible={showAddCategoryModal}
        onClose={() => setShowAddCategoryModal(false)}
        onSuccess={handleAddCategorySuccess}
        billType={type}
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
      width: '60%',
    },
    typeButton: {
      flex: 1,
      paddingVertical: spacing.md,
      alignItems: 'center',
      borderRadius: borderRadius.small,
    },
    typeButtonExpenseActive: {
      backgroundColor: colors.expense,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
    },
    typeButtonIncomeActive: {
      backgroundColor: colors.income,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
    },
    typeButtonText: { fontSize: 16, fontWeight: '700', color: colors.textSecondary },
    typeButtonTextActive: { color: '#FFFFFF', fontWeight: '800' },
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

/**
 * 分类管理页面 - Neo-Brutalism 风格(批 6 两级分类)
 * 点按顶级分类展开其子分类管理面板;添加/编辑/删除均在此完成。
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
import { useFocusEffect } from '@react-navigation/native';
import { AddCategoryModal } from '../../components/modals/AddCategoryModal';
import CategoryIcon from '../../components/business/CategoryIcon';
import QueryErrorBanner from '../../components/ui/QueryErrorBanner';
import { useToast, useStyles } from '../../hooks';
import { useAlert } from '../../providers';
import { nativeData, type Category } from '../../native';
import { invalidateCache } from '../../lib/queryClient';
import { HOGWARTS_FONTS } from '../../constants/hogwartsAssets';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { spacing, borderRadius, borderWidth } from '../../theme';

const CategoryManageScreen: React.FC = () => {
  const { showSuccess, showError } = useToast();
  const { confirm } = useAlert();
  const styles = useStyles(createStyles);

  const [categories, setCategories] = useState<Category[]>([]);
  const [loading, setLoading] = useState(false);
  /** 加载失败信息；非空时渲染失败态而非空列表 */
  const [loadError, setLoadError] = useState<string | null>(null);
  const [selectedType, setSelectedType] = useState<'expense' | 'income'>('expense');
  const [showAddModal, setShowAddModal] = useState(false);
  const [selectedCategory, setSelectedCategory] = useState<Category | null>(null);
  const [parentForModal, setParentForModal] = useState<Category | null>(null);
  /** 当前展开子分类面板的顶级分类 ID;null = 全部收起 */
  const [expandedCategoryId, setExpandedCategoryId] = useState<string | null>(null);

  const fetchCategories = useCallback(async () => {
    try {
      setLoading(true);
      setCategories(await nativeData.getCategories(selectedType));
      setLoadError(null);
    } catch (error: any) {
      setLoadError(error?.message || '加载分类失败');
      showError(error.message || '加载分类失败');
    } finally {
      setLoading(false);
    }
  }, [selectedType, showError]);

  useFocusEffect(
    useCallback(() => {
      void fetchCategories();
    }, [fetchCategories]),
  );

  const refreshCategoryQueries = useCallback(() => {
    invalidateCache.categories();
    void fetchCategories();
  }, [fetchCategories]);

  const handleDeleteCategory = (category: Category) => {
    if (category.isDefault) {
      showError('默认分类不能删除');
      return;
    }

    confirm(
      '删除分类',
      `确定要删除"${category.name}"分类吗？`,
      async () => {
        try {
          await nativeData.deleteCategory(category.id);
          showSuccess('分类删除成功');
          if (expandedCategoryId === category.id) {
            setExpandedCategoryId(null);
          }
          refreshCategoryQueries();
        } catch (error: any) {
          showError(error.message || '删除失败');
        }
      },
      undefined,
      { confirmText: '删除', destructive: true }
    );
  };

  const handleEditCategory = (category: Category) => {
    const parent = category.parentId
      ? categories.find((c) => c.id === category.parentId) ?? null
      : null;
    setSelectedCategory(category);
    setParentForModal(parent);
    setShowAddModal(true);
  };

  const handleAddChild = (parent: Category) => {
    setSelectedCategory(null);
    setParentForModal(parent);
    setShowAddModal(true);
  };

  const handleAddTopLevel = () => {
    setSelectedCategory(null);
    setParentForModal(null);
    setShowAddModal(true);
  };

  const handleAddSuccess = () => {
    refreshCategoryQueries();
  };

  const toggleExpanded = (categoryId: string) => {
    setExpandedCategoryId((current) => (current === categoryId ? null : categoryId));
  };

  const defaultCategories = categories.filter(cat => cat.isDefault && !cat.parentId);
  const customTopLevel = categories.filter(cat => !cat.isDefault && !cat.parentId);
  const childCategories = categories.filter(cat => cat.parentId);
  /** 子分类展示排序:预设子分类在前,自定义在后;同组内按 sortOrder 升序 */
  const sortChildren = (children: Category[]) =>
    [...children].sort(
      (a, b) =>
        Number(b.isDefault) - Number(a.isDefault) || a.sortOrder - b.sortOrder,
    );
  /** 展示顺序:默认分类在前,自定义顶级在后 */
  const topLevelCategories = [...defaultCategories, ...customTopLevel];

  return (
    <View style={styles.container}>
      {/* 类型切换标签 */}
      <View style={styles.tabs}>
        <TouchableOpacity
          style={[styles.tab, selectedType === 'expense' && styles.tabExpenseActive]}
          onPress={() => {
            setSelectedType('expense');
            setExpandedCategoryId(null);
          }}
        >
          <Text style={[styles.tabText, selectedType === 'expense' && styles.tabTextActive]}>
            支出分类
          </Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.tab, selectedType === 'income' && styles.tabIncomeActive]}
          onPress={() => {
            setSelectedType('income');
            setExpandedCategoryId(null);
          }}
        >
          <Text style={[styles.tabText, selectedType === 'income' && styles.tabTextActive]}>
            收入分类
          </Text>
        </TouchableOpacity>
      </View>

      <ScrollView style={styles.scrollView} showsVerticalScrollIndicator={false}>
        {loading ? (
          <View style={styles.loadingContainer}>
            <ActivityIndicator size="large" color={styles._colors.primary} />
            <Text style={styles.loadingText}>加载中...</Text>
          </View>
        ) : loadError ? (
          // 失败态与空态分离：加载失败不能渲染成空分类列表
          <QueryErrorBanner
            error={loadError}
            title="分类加载失败"
            hint="这不代表你没有分类数据"
            onRetry={() => void fetchCategories()}
          />
        ) : (
          <>
            <View style={styles.section}>
              <Text style={styles.sectionTitle}>🗂 分类(点按展开子分类管理)</Text>
              <View style={styles.categoryGrid}>
              {(() => {
                const rows = [];
                for (let i = 0; i < topLevelCategories.length; i += 4) {
                  rows.push(topLevelCategories.slice(i, i + 4));
                }
                return rows.map((rowCats, rowIndex) => (
                  <View key={`cat-row-${rowIndex}`} style={styles.categoryRowGrid}>
                    {rowCats.map((category) => {
                      const children = childCategories.filter(
                        (c) => c.parentId === category.id,
                      );
                      const expanded = expandedCategoryId === category.id;
                      return (
                        <TouchableOpacity
                          key={category.id}
                          style={[
                            styles.categoryItem,
                            expanded && styles.categoryItemExpanded,
                          ]}
                          onPress={() => toggleExpanded(category.id)}
                          activeOpacity={0.8}
                        >
                          <View style={styles.categoryIconBlock}>
                            <CategoryIcon icon={category.icon} size={22} emojiStyle={styles.categoryEmoji} />
                          </View>
                          <Text style={styles.categoryName}>{category.name}</Text>
                          <Text style={styles.categoryMeta}>
                            {children.length > 0
                              ? `${children.length} 个子分类`
                              : category.isDefault
                                ? '默认'
                                : '自定义'}
                          </Text>
                        </TouchableOpacity>
                      );
                    })}
                    {expandedCategoryId &&
                    rowCats.some(cat => cat.id === expandedCategoryId) ? (
                      (() => {
                        const expandedCategory = topLevelCategories.find(
                          (cat) => cat.id === expandedCategoryId,
                        );
                        if (!expandedCategory) return null;
                        const children = childCategories.filter(
                          (c) => c.parentId === expandedCategory.id,
                        );
                        return (
                          <View style={styles.expandPanel}>
                            <View style={styles.panelTitleRow}>
                              <CategoryIcon icon={expandedCategory.icon} size={16} emojiStyle={styles.panelTitleIcon} />
                              <Text style={styles.panelTitle}>{expandedCategory.name} · 子分类</Text>
                            </View>
                            {sortChildren(children).map((child) => (
                              <View key={child.id} style={styles.childRow}>
                                <CategoryIcon icon={child.icon} size={14} emojiStyle={styles.childIcon} />
                                <Text style={styles.childText}>
                                  {child.name}
                                </Text>
                                <View style={styles.categoryRowActions}>
                                  <TouchableOpacity
                                    style={styles.rowBtn}
                                    onPress={() => handleEditCategory(child)}
                                  >
                                    <Text style={styles.rowBtnText}>编辑</Text>
                                  </TouchableOpacity>
                                  {!child.isDefault ? (
                                    <TouchableOpacity
                                      style={styles.rowBtnDelete}
                                      onPress={() => handleDeleteCategory(child)}
                                    >
                                      <Text style={styles.rowBtnText}>删除</Text>
                                    </TouchableOpacity>
                                  ) : null}
                                </View>
                              </View>
                            ))}
                            {children.length === 0 ? (
                              <Text style={styles.panelEmptyText}>
                                暂无子分类,点击下方按钮添加
                              </Text>
                            ) : null}
                            <TouchableOpacity
                              style={styles.panelAddBtn}
                              onPress={() => handleAddChild(expandedCategory)}
                            >
                              <Text style={styles.panelAddText}>+ 添加子分类</Text>
                            </TouchableOpacity>
                            <View style={styles.panelActions}>
                              <TouchableOpacity
                                style={styles.rowBtn}
                                onPress={() => handleEditCategory(expandedCategory)}
                              >
                                <Text style={styles.rowBtnText}>编辑本分类</Text>
                              </TouchableOpacity>
                              {!expandedCategory.isDefault ? (
                                <TouchableOpacity
                                  style={styles.rowBtnDelete}
                                  onPress={() => handleDeleteCategory(expandedCategory)}
                                >
                                  <Text style={styles.rowBtnTextDelete}>删除本分类</Text>
                                </TouchableOpacity>
                              ) : null}
                            </View>
                          </View>
                        );
                      })()
                    ) : null}
                  </View>
                ));
              })()}
              </View>
              <TouchableOpacity
                style={styles.addCategoryBtnFull}
                onPress={handleAddTopLevel}
              >
                <Text style={styles.addLabel}>+ 添加顶级分类</Text>
              </TouchableOpacity>
            </View>

            <View style={{ height: 100 }} />
          </>
        )}
      </ScrollView>

      <AddCategoryModal
        visible={showAddModal}
        onClose={() => {
          setShowAddModal(false);
          setSelectedCategory(null);
          setParentForModal(null);
        }}
        onSuccess={handleAddSuccess}
        billType={selectedCategory?.type || parentForModal?.type || selectedType}
        category={selectedCategory}
        parentCategory={parentForModal}
      />
    </View>
  );
};

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: colors.background,
    },
    tabs: {
      flexDirection: 'row',
      backgroundColor: shape.isSoft || shape.isHogwarts ? colors.background : colors.surface,
      borderBottomWidth: shape.isSoft ? 0 : shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderBottomColor: shape.isHogwarts ? colors.divider : colors.stroke,
      padding: spacing.sm,
      gap: spacing.sm,
      paddingHorizontal: spacing.lg,
    },
    tab: {
      flex: 1,
      paddingVertical: spacing.md,
      alignItems: 'center',
      borderRadius: shape.isSoft || shape.isHogwarts ? shape.radius.button : borderRadius.small,
      borderWidth: shape.isSoft || shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.cardBorder : colors.stroke,
      backgroundColor: colors.surface,
      ...(shape.isSoft || shape.isHogwarts ? shape.elevation.card : {}),
    },
    tabExpenseActive: {
      backgroundColor: colors.expense,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.expense : colors.stroke,
    },
    tabIncomeActive: {
      backgroundColor: colors.income,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.income : colors.stroke,
    },
    tabText: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    tabTextActive: {
      color: '#FFFFFF',
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
    },
    scrollView: {
      flex: 1,
    },
    loadingContainer: {
      flex: 1,
      justifyContent: 'center',
      alignItems: 'center',
      paddingTop: 100,
    },
    loadingText: {
      marginTop: spacing.md,
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    section: {
      margin: spacing.lg,
      padding: spacing.lg,
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.isSoft || shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.cardBorder : colors.stroke,
      ...(shape.isSoft || shape.isHogwarts ? shape.elevation.card : {}),
    },
    sectionTitle: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
      marginBottom: spacing.lg,
    },
    branchWrap: {
      marginBottom: spacing.sm,
    },
    categoryGrid: {
      flexDirection: 'row',
      flexWrap: 'wrap',
      gap: spacing.md,
    },
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
      alignItems: 'center',
      justifyContent: 'center',
      paddingVertical: spacing.sm,
      borderWidth: shape.isSoft || shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isSoft ? 'transparent' : shape.isHogwarts ? colors.cardBorder : colors.stroke,
      borderRadius: shape.isSoft || shape.isHogwarts ? 14 : borderRadius.small,
      backgroundColor: shape.isSoft || shape.isHogwarts ? colors.surfaceMuted : colors.background,
    },
    categoryItemExpanded: {
      backgroundColor: shape.isSoft || shape.isHogwarts ? colors.primaryLight : colors.accent,
      borderWidth: shape.isSoft || shape.isHogwarts ? 1.5 : borderWidth.medium,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.primary : colors.stroke,
    },
    categoryIconBlock: {
      width: 44,
      height: 44,
      borderRadius: shape.isSoft || shape.isHogwarts ? 12 : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      backgroundColor: shape.isSoft || shape.isHogwarts ? colors.surface : colors.accent,
      justifyContent: 'center',
      alignItems: 'center',
      marginBottom: spacing.xs,
    },
    categoryEmoji: {
      fontSize: 22,
    },
    categoryName: {
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
      textAlign: 'center',
    },
    categoryMeta: {
      fontSize: 11,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '500' : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
      marginTop: spacing.xs,
    },
    expandPanel: {
      width: '100%',
      marginTop: spacing.sm,
      padding: spacing.md,
      borderWidth: shape.isSoft ? 0 : shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      borderRadius: shape.isSoft || shape.isHogwarts ? 14 : borderRadius.small,
      backgroundColor: shape.isSoft || shape.isHogwarts ? colors.surfaceMuted : colors.background,
    },
    panelTitleRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.xs,
      marginBottom: spacing.sm,
    },
    panelTitle: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
    },
    panelTitleIcon: {
      fontSize: 16,
    },
    panelEmptyText: {
      fontSize: 13,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
      paddingVertical: spacing.sm,
    },
    childRow: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingVertical: spacing.sm,
      borderBottomWidth: shape.isSoft || shape.isHogwarts ? StyleSheet.hairlineWidth : borderWidth.thin,
      borderBottomColor: colors.divider,
    },
    childIcon: {
      fontSize: 14,
      marginRight: spacing.xs,
    },
    childText: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
      flex: 1,
    },
    categoryRowActions: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.xs,
    },
    rowBtn: {
      paddingHorizontal: spacing.sm + 2,
      paddingVertical: shape.isSoft || shape.isHogwarts ? 6 : 4,
      borderRadius: shape.isSoft || shape.isHogwarts ? 10 : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      backgroundColor: shape.isSoft || shape.isHogwarts ? colors.surface : colors.background,
    },
    rowBtnText: {
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    rowBtnDelete: {
      paddingHorizontal: spacing.sm + 2,
      paddingVertical: shape.isSoft || shape.isHogwarts ? 6 : 4,
      borderRadius: shape.isSoft || shape.isHogwarts ? 10 : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: colors.error,
      backgroundColor: shape.isSoft || shape.isHogwarts ? colors.expenseLight : colors.error + '15',
    },
    rowBtnTextDelete: {
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: shape.isSoft || shape.isHogwarts ? colors.expenseStrong : colors.error,
    },
    panelAddBtn: {
      marginTop: spacing.sm,
      paddingVertical: spacing.sm,
      borderRadius: shape.isSoft || shape.isHogwarts ? 12 : borderRadius.small,
      borderWidth: shape.isSoft || shape.isHogwarts ? 1 : borderWidth.thin,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.cardBorder : colors.stroke,
      borderStyle: 'dashed',
      alignItems: 'center',
    },
    panelAddText: {
      fontSize: 13,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: shape.isSoft || shape.isHogwarts ? colors.primary : colors.textSecondary,
    },
    panelActions: {
      flexDirection: 'row',
      gap: spacing.sm,
      marginTop: spacing.sm,
    },
    addCategoryBtnFull: {
      marginTop: spacing.sm,
      paddingVertical: spacing.md,
      borderRadius: shape.isSoft || shape.isHogwarts ? 14 : borderRadius.small,
      borderWidth: shape.isSoft || shape.isHogwarts ? 1 : borderWidth.thin,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.cardBorder : colors.stroke,
      borderStyle: 'dashed',
      alignItems: 'center',
      backgroundColor: 'transparent',
    },
    addLabel: {
      fontSize: 13,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: shape.isSoft || shape.isHogwarts ? colors.primary : colors.textTertiary,
    },
  }),
  _colors: colors,
});

export default CategoryManageScreen;

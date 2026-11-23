/**
 * 分类选择器组件 - Neo-Brutalism 风格
 * 描边网格项 + 糖果色 + BrutalPressable 选中
 */
import React, { useState, useEffect } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
} from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { spacing, borderRadius, borderWidth } from '../../theme';
import { useToast, useStyles } from '../../hooks';
import CategoryIcon from '../business/CategoryIcon';
import { nativeData, type Category } from '../../native';
import type { BillType } from '../../types/bill';

interface CategorySelectorProps {
  selectedCategoryId?: string;
  onCategorySelect: (category: Category) => void;
  billType: BillType;
}

export const CategorySelector: React.FC<CategorySelectorProps> = ({
  selectedCategoryId,
  onCategorySelect,
  billType,
}) => {
  const styles = useStyles(createStyles);
  const [categories, setCategories] = useState<Category[]>([]);
  const [loading, setLoading] = useState(false);
  const { showError } = useToast();

  const loadCategories = async () => {
    setLoading(true);
    try {
      setCategories(await nativeData.getCategories(billType));
    } catch (error: any) {
      console.error('加载分类失败:', error);
      showError('加载分类失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadCategories();
  }, [billType]);

  return (
    <View style={styles.container}>
      <Text style={styles.label}>分类</Text>
      <View style={styles.categoryGrid}>
        {categories.map((category) => {
          const isSelected = selectedCategoryId === category.id;
          return (
            <TouchableOpacity
              key={category.id}
              style={[
                styles.categoryItem,
                isSelected && styles.categoryItemSelected,
              ]}
              onPress={() => onCategorySelect(category)}
              activeOpacity={0.7}
            >
              <CategoryIcon icon={category.icon} size={24} emojiStyle={styles.categoryIcon} />
              <Text style={[
                styles.categoryName,
                isSelected && styles.categoryNameSelected,
              ]}>
                {category.name}
              </Text>
            </TouchableOpacity>
          );
        })}
      </View>
    </View>
  );
};

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    container: {
      marginBottom: spacing.lg,
    },
    label: {
      fontSize: 14,
      fontWeight: shape.isSoft ? '600' : '700',
      color: shape.isSoft ? colors.textSecondary : colors.textPrimary,
      marginBottom: spacing.md,
    },
    categoryGrid: {
      flexDirection: 'row',
      flexWrap: 'wrap',
      gap: spacing.md,
    },
    categoryItem: {
      width: '22%',
      aspectRatio: 1,
      justifyContent: 'center',
      alignItems: 'center',
      borderRadius: shape.isSoft ? 16 : borderRadius.medium,
      borderWidth: shape.isSoft ? 0 : borderWidth.medium,
      borderColor: colors.stroke,
      backgroundColor: shape.isSoft ? colors.surfaceMuted : colors.surface,
      padding: spacing.sm,
    },
    categoryItemSelected: {
      backgroundColor: shape.isSoft ? colors.primaryLight : colors.accent,
      borderWidth: shape.isSoft ? 1.5 : borderWidth.medium,
      borderColor: shape.isSoft ? colors.primary : colors.stroke,
    },
    categoryIcon: {
      fontSize: 24,
      marginBottom: 4,
    },
    categoryName: {
      fontSize: 12,
      color: colors.textPrimary,
      textAlign: 'center',
      fontWeight: '600',
    },
    categoryNameSelected: {
      color: shape.isSoft ? colors.primary : colors.textPrimary,
      fontWeight: shape.isSoft ? '700' : '800',
    },
  }),
  _colors: colors,
});

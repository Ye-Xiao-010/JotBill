/**
 * 添加分类模态框组件 - Neo-Brutalism 风格
 * 描边图标网格 + BrutalPressable 按钮
 */
import React, { useEffect, useState } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  ScrollView,
} from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { spacing, borderRadius, borderWidth, shadow } from '../../theme';
import { useToast, useStyles } from '../../hooks';
import { nativeData, type Category } from '../../native';
import type { BillType } from '../../types/bill';
import Modal from '../ui/Modal';

interface AddCategoryModalProps {
  visible: boolean;
  onClose: () => void;
  onSuccess: () => void;
  billType: BillType;
  category?: Category | null;
  /** 添加子分类时的父分类(与 category 编辑态互斥使用) */
  parentCategory?: Category | null;
}

const ICON_OPTIONS = [
  '🍔', '🚕', '🛍️', '🏠', '🎮', '📚',
  '💊', '🎁', '✈️', '🏋️', '🍿', '📱',
  '💻', '🚗', '👕', '🎵', '🎬', '📺',
  '⚽', '🎾', '🎨', '📷', '🌹', '🐶',
  '💰', '💼', '🏦', '📈', '🎯', '⭐',
  '🔥', '❤️', '🌟', '🎉', '🚀', '💎',
];

export const AddCategoryModal: React.FC<AddCategoryModalProps> = ({
  visible,
  onClose,
  onSuccess,
  billType,
  category = null,
  parentCategory = null,
}) => {
  const styles = useStyles(createStyles);
  const [categoryName, setCategoryName] = useState(category?.name || '');
  const [selectedIcon, setSelectedIcon] = useState(category?.icon || '🍔');
  const [loading, setLoading] = useState(false);
  const { showError, showSuccess } = useToast();

  useEffect(() => {
    if (!visible) return;
    setCategoryName(category?.name || '');
    setSelectedIcon(category?.icon || '🍔');
  }, [category, visible]);

  const handleSave = async () => {
    if (!categoryName.trim()) {
      showError('请输入分类名称');
      return;
    }

    setLoading(true);
    try {
      const input = {
        name: categoryName.trim(),
        type: billType,
        icon: selectedIcon,
        color: billType === 'income' ? styles._colors.income : styles._colors.expense,
        ...(category ? {} : { parentId: parentCategory?.id }),
      };

      if (category) {
        await nativeData.updateCategory(category.id, input);
      } else {
        await nativeData.addCategory(input);
      }

      showSuccess(category ? '分类更新成功' : '分类创建成功');
      setCategoryName('');
      setSelectedIcon('🍔');
      onSuccess();
      onClose();
    } catch (error: any) {
      showError(error.message || '创建分类失败');
    } finally {
      setLoading(false);
    }
  };

  const handleClose = () => {
    setCategoryName('');
    setSelectedIcon('🍔');
    onClose();
  };

  return (
    <Modal
      visible={visible}
      onClose={handleClose}
      title={category ? '编辑分类' : parentCategory ? `添加子分类 · ${parentCategory.name}` : '添加分类'}
    >
      <ScrollView
        style={styles.content}
        contentContainerStyle={styles.contentContainer}
        showsVerticalScrollIndicator={false}
      >
        {category?.parentId ? (
          <View style={styles.section}>
            <Text style={styles.label}>所属父分类</Text>
            <Text style={styles.parentHint}>{category.parentId === parentCategory?.id ? parentCategory.name : '　'}</Text>
          </View>
        ) : null}
        {/* 分类名称输入 */}
        <View style={styles.section}>
          <Text style={styles.label}>分类名称</Text>
          <TextInput
            style={styles.input}
            value={categoryName}
            onChangeText={setCategoryName}
            placeholder="输入分类名称"
            placeholderTextColor={styles._colors.textTertiary}
            maxLength={20}
          />
        </View>

        {/* 图标选择 */}
        <View style={styles.section}>
          <Text style={styles.label}>选择图标</Text>
          <View style={styles.iconGrid}>
            {ICON_OPTIONS.map((icon, index) => (
              <TouchableOpacity
                key={index}
                style={[
                  styles.iconOption,
                  selectedIcon === icon && styles.iconOptionSelected,
                ]}
                onPress={() => setSelectedIcon(icon)}
                activeOpacity={0.7}
              >
                <Text style={styles.iconText}>{icon}</Text>
              </TouchableOpacity>
            ))}
          </View>
        </View>
      </ScrollView>

      {/* 底部按钮 */}
      <View style={styles.footer}>
        <TouchableOpacity
          style={[styles.button, styles.cancelButton]}
          onPress={handleClose}
        >
          <Text style={styles.cancelButtonText}>取消</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.button, styles.saveButton]}
          onPress={handleSave}
          disabled={loading}
        >
          <Text style={styles.saveButtonText}>
            {loading ? '保存中...' : category ? '更新' : '保存'}
          </Text>
        </TouchableOpacity>
      </View>
    </Modal>
  );
};

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    content: {
      maxHeight: 400,
    },
    contentContainer: {
      paddingVertical: spacing.sm,
    },
    section: {
      marginBottom: spacing.lg,
    },
    label: {
      fontSize: 14,
      fontWeight: shape.isSoft ? '600' : '700',
      color: shape.isSoft ? colors.textSecondary : colors.textPrimary,
      marginBottom: spacing.sm,
    },
    input: {
      borderWidth: shape.isSoft ? 0 : borderWidth.medium,
      borderColor: colors.stroke,
      borderRadius: shape.radius.input,
      padding: spacing.md,
      fontSize: 15,
      fontWeight: '600',
      color: colors.textPrimary,
      backgroundColor: shape.isSoft ? colors.surfaceMuted : colors.surface,
    },
    parentHint: {
      fontSize: 14,
      fontWeight: '700',
      color: colors.textSecondary,
    },
    iconGrid: {
      flexDirection: 'row',
      flexWrap: 'wrap',
      justifyContent: 'flex-start',
      marginHorizontal: -4,
    },
    iconOption: {
      width: 48,
      height: 48,
      borderRadius: shape.isSoft ? 14 : borderRadius.medium,
      backgroundColor: shape.isSoft ? colors.surfaceMuted : colors.background,
      justifyContent: 'center',
      alignItems: 'center',
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.stroke,
      margin: 4,
    },
    iconOptionSelected: {
      backgroundColor: shape.isSoft ? colors.primaryLight : colors.accent,
      borderWidth: shape.isSoft ? 1.5 : borderWidth.medium,
      borderColor: shape.isSoft ? colors.primary : colors.stroke,
    },
    iconText: {
      fontSize: 22,
    },
    footer: {
      flexDirection: 'row',
      paddingTop: spacing.md,
      borderTopWidth: shape.isSoft ? StyleSheet.hairlineWidth : borderWidth.thin,
      borderTopColor: shape.isSoft ? colors.divider : colors.stroke,
      gap: spacing.md,
    },
    button: {
      flex: 1,
      paddingVertical: spacing.md,
      borderRadius: shape.radius.button,
      borderWidth: shape.isSoft ? 0 : borderWidth.medium,
      borderColor: colors.stroke,
      alignItems: 'center',
      ...(shape.isSoft ? shape.elevation.none : shadow.small),
    },
    cancelButton: {
      backgroundColor: shape.isSoft ? colors.surfaceMuted : colors.surface,
    },
    cancelButtonText: {
      color: shape.isSoft ? colors.textSecondary : colors.textPrimary,
      fontSize: 15,
      fontWeight: '700',
    },
    saveButton: {
      backgroundColor: colors.primary,
    },
    saveButtonText: {
      color: shape.isSoft ? colors.onPrimary : '#FFFFFF',
      fontSize: 15,
      fontWeight: shape.isSoft ? '700' : '800',
    },
  }),
  _colors: colors,
});

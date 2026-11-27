/**
 * 改分类一键生成规则自学习弹窗
 * 兼容三套主题家族（Blocks / Soft / Hogwarts）及浅色/深色模式
 */
import React from 'react';
import {
  ActivityIndicator,
  Modal,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { ArrowRight, Sparkles } from 'lucide-react-native';
import CategoryIcon from '../business/CategoryIcon';
import { useStyles } from '../../hooks';
import { useTheme } from '../../providers';
import type { CategoryRuleSuggestion } from '../../native';
import { HOGWARTS_FONTS } from '../../constants/hogwartsAssets';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { borderRadius, borderWidth, shadow, spacing } from '../../theme/spacing';

export interface CategoryRuleLearningModalProps {
  visible: boolean;
  suggestion: CategoryRuleSuggestion | null;
  categoryName: string;
  categoryIcon?: string;
  submitting?: boolean;
  onConfirm: (applyToHistory: boolean) => void;
  onDismiss: () => void;
}

export const CategoryRuleLearningModal: React.FC<CategoryRuleLearningModalProps> = ({
  visible,
  suggestion,
  categoryName,
  categoryIcon,
  submitting = false,
  onConfirm,
  onDismiss,
}) => {
  const styles = useStyles(createStyles);
  const { colors, isHogwarts } = useTheme();

  if (!suggestion) {
    return null;
  }

  const isUpdate = suggestion.status === 'update';
  const resolvedCategoryName = categoryName || suggestion.categoryName || '该分类';
  const historicalCount = suggestion.matchingHistoricalCount;
  const lockedSkippedCount = suggestion.lockedSkippedCount;

  const title = isUpdate ? '更新自动分类规则？' : '记住这个分类习惯？';
  const description = isUpdate
    ? suggestion.existingCategoryName
      ? `原规则会将「${suggestion.merchant}」归为「${suggestion.existingCategoryName}」，是否改为「${resolvedCategoryName}」？`
      : `将「${suggestion.merchant}」的自动分类规则更新为「${resolvedCategoryName}」？`
    : `以后来自「${suggestion.merchant}」的同类账单将自动归为「${resolvedCategoryName}」。`;

  const primaryWithHistoryText = isUpdate
    ? `更新规则并同步历史 ${historicalCount} 笔`
    : `记住并更新历史 ${historicalCount} 笔`;

  const futureOnlyText = isUpdate
    ? '仅更新规则，不动历史'
    : '仅记住，以后自动归类';

  const singleConfirmText = isUpdate
    ? `更新为「${resolvedCategoryName}」`
    : `以后自动归为「${resolvedCategoryName}」`;

  return (
    <Modal
      visible={visible}
      transparent
      animationType="fade"
      onRequestClose={() => {
        if (!submitting) onDismiss();
      }}
    >
      <View style={styles.overlay}>
        <View style={styles.card}>
          <View style={styles.badge}>
            <Sparkles size={15} color={colors.primary} strokeWidth={2.2} />
            <Text style={styles.badgeText}>规则自学习</Text>
          </View>

          <Text style={styles.title}>{title}</Text>

          <View style={styles.mappingPill}>
            <Text style={styles.merchantText} numberOfLines={1}>
              {suggestion.merchant}
            </Text>
            <ArrowRight size={14} color={colors.textTertiary} strokeWidth={2.2} />
            <View style={styles.categoryChip}>
              {categoryIcon ? (
                <CategoryIcon
                  icon={categoryIcon}
                  size={14}
                  color={isHogwarts ? colors.primary : undefined}
                  emojiStyle={styles.categoryEmoji}
                />
              ) : null}
              <Text style={styles.categoryText} numberOfLines={1}>
                {resolvedCategoryName}
              </Text>
            </View>
          </View>

          <Text style={styles.message}>{description}</Text>

          {lockedSkippedCount > 0 ? (
            <Text style={styles.lockedHint}>
              另有 {lockedSkippedCount} 笔你手动改过分类的历史账单将保持不变
            </Text>
          ) : null}

          <View style={styles.actions}>
            {historicalCount > 0 ? (
              <>
                <TouchableOpacity
                  style={[styles.button, styles.primaryButton, submitting && styles.buttonDisabled]}
                  onPress={() => onConfirm(true)}
                  disabled={submitting}
                  activeOpacity={0.85}
                  accessibilityRole="button"
                  accessibilityLabel={primaryWithHistoryText}
                >
                  {submitting ? (
                    <ActivityIndicator size="small" color={styles._onPrimaryColor} />
                  ) : (
                    <Text style={styles.primaryButtonText}>{primaryWithHistoryText}</Text>
                  )}
                </TouchableOpacity>

                <TouchableOpacity
                  style={[styles.button, styles.secondaryButton, submitting && styles.buttonDisabled]}
                  onPress={() => onConfirm(false)}
                  disabled={submitting}
                  activeOpacity={0.85}
                  accessibilityRole="button"
                  accessibilityLabel={futureOnlyText}
                >
                  <Text style={styles.secondaryButtonText}>{futureOnlyText}</Text>
                </TouchableOpacity>
              </>
            ) : (
              <TouchableOpacity
                style={[styles.button, styles.primaryButton, submitting && styles.buttonDisabled]}
                onPress={() => onConfirm(false)}
                disabled={submitting}
                activeOpacity={0.85}
                accessibilityRole="button"
                accessibilityLabel={singleConfirmText}
              >
                {submitting ? (
                  <ActivityIndicator size="small" color={styles._onPrimaryColor} />
                ) : (
                  <Text style={styles.primaryButtonText}>{singleConfirmText}</Text>
                )}
              </TouchableOpacity>
            )}

            <TouchableOpacity
              style={[styles.button, styles.cancelButton, submitting && styles.buttonDisabled]}
              onPress={onDismiss}
              disabled={submitting}
              activeOpacity={0.8}
              accessibilityRole="button"
              accessibilityLabel="暂不需要"
            >
              <Text style={styles.cancelButtonText}>暂不需要</Text>
            </TouchableOpacity>
          </View>
        </View>
      </View>
    </Modal>
  );
};

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    overlay: {
      flex: 1,
      backgroundColor: colors.overlay,
      justifyContent: 'center',
      alignItems: 'center',
      padding: spacing.lg,
    },
    card: {
      backgroundColor: colors.surface,
      borderRadius: shape.radius.modal,
      borderWidth: shape.isSoft
        ? shape.borderWidth.card
        : shape.isHogwarts
          ? shape.borderWidth.strong
          : borderWidth.thick,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.cardBorder : colors.stroke,
      width: '90%',
      maxWidth: 348,
      padding: spacing.xl,
      alignItems: 'center',
      ...(shape.isSoft || shape.isHogwarts ? shape.elevation.floating : shadow.medium),
    },
    badge: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 6,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.xs,
      borderRadius: shape.isSoft || shape.isHogwarts ? shape.radius.pill : borderRadius.small,
      backgroundColor: colors.primaryLight,
      borderWidth: shape.isSoft ? 0 : shape.borderWidth.subtle,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      marginBottom: spacing.md,
    },
    badgeText: {
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: shape.isSoft ? colors.primaryDark : colors.primary,
    },
    title: {
      fontSize: shape.isSoft ? 18 : 20,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
      textAlign: 'center',
      marginBottom: spacing.md,
    },
    mappingPill: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'center',
      flexWrap: 'wrap',
      gap: spacing.xs,
      width: '100%',
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.sm,
      borderRadius: shape.radius.input,
      backgroundColor: shape.isSoft || shape.isHogwarts ? colors.surfaceMuted : colors.background,
      borderWidth: shape.isSoft ? 0 : shape.borderWidth.subtle,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      marginBottom: spacing.md,
    },
    merchantText: {
      maxWidth: '52%',
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    categoryChip: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 4,
      paddingHorizontal: spacing.sm,
      paddingVertical: 3,
      borderRadius: shape.isSoft || shape.isHogwarts ? shape.radius.pill : borderRadius.small,
      backgroundColor: colors.primaryLight,
    },
    categoryEmoji: {
      fontSize: 14,
    },
    categoryText: {
      fontSize: 13,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: shape.isSoft ? colors.primaryDark : colors.primary,
    },
    message: {
      fontSize: 14,
      lineHeight: 21,
      fontWeight: shape.isHogwarts ? undefined : '500',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
      textAlign: 'center',
      marginBottom: spacing.sm,
    },
    lockedHint: {
      fontSize: 12,
      lineHeight: 18,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
      textAlign: 'center',
      marginBottom: spacing.sm,
    },
    actions: {
      width: '100%',
      gap: spacing.sm,
      marginTop: spacing.md,
    },
    button: {
      width: '100%',
      minHeight: 46,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.sm + 2,
      borderRadius: shape.radius.button,
      alignItems: 'center',
      justifyContent: 'center',
      borderWidth: shape.isSoft ? 0 : shape.isHogwarts ? shape.borderWidth.card : borderWidth.medium,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
    },
    primaryButton: {
      backgroundColor: colors.primary,
      borderColor: shape.isHogwarts ? colors.accent : colors.stroke,
      ...(shape.isSoft ? shape.elevation.none : shape.isHogwarts ? shape.elevation.card : shadow.small),
    },
    secondaryButton: {
      backgroundColor: colors.primaryLight,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
    },
    cancelButton: {
      backgroundColor: shape.isSoft || shape.isHogwarts ? colors.surfaceMuted : colors.surface,
    },
    buttonDisabled: {
      opacity: 0.55,
    },
    primaryButtonText: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: shape.isSoft || shape.isHogwarts ? colors.onPrimary : '#FFFFFF',
    },
    secondaryButtonText: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: shape.isSoft ? colors.primaryDark : colors.textPrimary,
    },
    cancelButtonText: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
  }),
  _onPrimaryColor: shape.isSoft || shape.isHogwarts ? colors.onPrimary : '#FFFFFF',
});

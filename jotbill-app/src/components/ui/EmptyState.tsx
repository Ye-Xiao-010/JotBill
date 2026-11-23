/**
 * 空状态组件 - Neo-Brutalism 风格
 * 描边容器 + 大 emoji 方块 + 粗标题 + BrutalPressable 按钮
 */
import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { spacing, borderRadius, borderWidth, shadow } from '../../theme/spacing';
import { useStyles } from '../../hooks';
import BrutalPressable from './BrutalPressable';

export interface EmptyStateProps {
  icon?: string;
  title: string;
  description?: string;
  actionLabel?: string;
  onAction?: () => void;
  style?: object;
}

export default function EmptyState({
  icon = '📭',
  title,
  description,
  actionLabel,
  onAction,
  style,
}: EmptyStateProps) {
  const styles = useStyles(createStyles);

  return (
    <View style={[styles.container, style]}>
      {/* Emoji 方块 */}
      <View style={styles.iconBlock}>
        <Text style={styles.icon}>{icon}</Text>
      </View>
      <Text style={styles.title}>{title}</Text>
      {description && <Text style={styles.description}>{description}</Text>}
      {actionLabel && onAction && (
        <BrutalPressable
          onPress={onAction}
          style={styles.button}
          shadowOffset={3}
          shadowColor={styles._colors.stroke}
        >
          <Text style={styles.buttonText}>{actionLabel}</Text>
        </BrutalPressable>
      )}
    </View>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    container: {
      flex: 1,
      alignItems: 'center',
      justifyContent: 'center',
      padding: spacing.xxl,
      backgroundColor: colors.surface,
      borderRadius: shape.isSoft ? shape.radius.card : borderRadius.card,
      borderWidth: shape.isSoft ? shape.borderWidth.card : borderWidth.medium,
      borderColor: shape.isSoft ? colors.cardBorder : colors.stroke,
      ...(shape.isSoft ? shape.elevation.card : shadow.small),
    },
    iconBlock: {
      width: 76,
      height: 76,
      borderRadius: shape.isSoft ? 26 : borderRadius.medium,
      borderWidth: shape.isSoft ? 0 : borderWidth.medium,
      borderColor: colors.stroke,
      backgroundColor: shape.isSoft ? colors.primaryLight : colors.accent,
      alignItems: 'center',
      justifyContent: 'center',
      marginBottom: spacing.lg,
    },
    icon: {
      fontSize: 36,
    },
    title: {
      fontSize: shape.isSoft ? 17 : 18,
      fontWeight: shape.isSoft ? '600' : '800',
      color: colors.textPrimary,
      textAlign: 'center',
      marginBottom: spacing.sm,
    },
    description: {
      fontSize: 13.5,
      fontWeight: shape.isSoft ? '400' : '500',
      color: colors.textTertiary,
      textAlign: 'center',
      lineHeight: 20,
      marginBottom: spacing.lg,
    },
    button: {
      marginTop: spacing.md,
      backgroundColor: colors.primary,
      borderRadius: shape.isSoft ? shape.radius.button : borderRadius.button,
      borderWidth: shape.isSoft ? 0 : borderWidth.medium,
      borderColor: colors.stroke,
      paddingHorizontal: spacing.xl,
      paddingVertical: spacing.md,
    },
    buttonText: {
      fontSize: 15,
      fontWeight: shape.isSoft ? '600' : '800',
      color: colors.onPrimary,
    },
  }),
  _colors: colors,
});

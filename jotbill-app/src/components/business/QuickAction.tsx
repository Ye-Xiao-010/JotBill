/**
 * 快速操作按钮组件 - Neo-Brutalism 风格
 * BrutalPressable + 描边图标块 + 糖果色
 */
import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { borderRadius, borderWidth, spacing } from '../../theme/spacing';
import { useStyles } from '../../hooks';
import BrutalPressable from '../ui/BrutalPressable';

export interface QuickActionProps {
  icon: string;
  label: string;
  description?: string;
  color?: string;
  onPress: () => void;
  disabled?: boolean;
}

export default function QuickAction({
  icon,
  label,
  description,
  color,
  onPress,
  disabled = false,
}: QuickActionProps) {
  const styles = useStyles(createStyles);
  const bgColor = color || styles._colors.accent;

  return (
    <BrutalPressable
      onPress={onPress}
      style={[styles.container, disabled && styles.containerDisabled]}
      shadowOffset={3}
      shadowColor={styles._colors.stroke}
    >
      <View style={[styles.iconContainer, { backgroundColor: bgColor }]}>
        <Text style={styles.icon}>{icon}</Text>
      </View>
      <Text style={[styles.label, disabled && styles.labelDisabled]}>{label}</Text>
      {description && (
        <Text style={styles.description} numberOfLines={1}>
          {description}
        </Text>
      )}
    </BrutalPressable>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    container: {
      alignItems: 'center',
      padding: spacing.md,
      borderRadius: shape.radius.card,
      borderWidth: shape.isSoft ? shape.borderWidth.card : borderWidth.medium,
      borderColor: shape.isSoft ? colors.cardBorder : colors.stroke,
      backgroundColor: colors.surface,
      minWidth: 80,
      ...(shape.isSoft ? shape.elevation.card : {}),
    },
    containerDisabled: {
      opacity: 0.5,
    },
    iconContainer: {
      width: 48,
      height: 48,
      borderRadius: shape.isSoft ? 16 : borderRadius.medium,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.stroke,
      alignItems: 'center',
      justifyContent: 'center',
      marginBottom: spacing.sm,
    },
    icon: {
      fontSize: 24,
    },
    label: {
      fontSize: 13,
      fontWeight: '700',
      color: colors.textPrimary,
      textAlign: 'center',
    },
    labelDisabled: {
      color: colors.textTertiary,
    },
    description: {
      fontSize: 11,
      fontWeight: '500',
      color: colors.textTertiary,
      textAlign: 'center',
      marginTop: spacing.xs,
    },
  }),
  _colors: colors,
});

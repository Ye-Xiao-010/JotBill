/**
 * 徽章组件 - Neo-Brutalism 风格
 * 描边 + 小圆角(非全圆) + 糖果色变体
 */
import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { borderRadius, borderWidth, spacing } from '../../theme/spacing';
import { useStyles } from '../../hooks';

export type BadgeVariant = 'default' | 'success' | 'warning' | 'error' | 'info';

export interface BadgeProps {
  label: string;
  variant?: BadgeVariant;
  size?: 'small' | 'medium';
  style?: object;
}

export default function Badge({ label, variant = 'default', size = 'medium', style }: BadgeProps) {
  const styles = useStyles(createStyles);
  const isSmall = size === 'small';
  const isSoft = styles._isSoft;
  const c = styles._colors;

  const getVariantStyle = () => {
    if (isSoft) {
      switch (variant) {
        case 'success':
          return { bg: c.incomeLight, text: c.incomeStrong };
        case 'warning':
          return { bg: styles._isDark ? '#2E2410' : '#FFF5E6', text: styles._isDark ? '#FBBF24' : '#B45309' };
        case 'error':
          return { bg: c.expenseLight, text: c.expenseStrong };
        case 'info':
          return { bg: c.primaryLight, text: c.primaryDark };
        default:
          return { bg: c.surfaceMuted, text: c.textSecondary };
      }
    }
    switch (variant) {
      case 'success':
        return { bg: c.success, text: '#FFFFFF' };
      case 'warning':
        return { bg: c.accent, text: c.textPrimary };
      case 'error':
        return { bg: c.error, text: '#FFFFFF' };
      case 'info':
        return { bg: c.primary, text: '#FFFFFF' };
      default:
        return { bg: c.divider, text: c.textPrimary };
    }
  };

  const variantStyle = getVariantStyle();

  return (
    <View
      style={[
        styles.badge,
        {
          backgroundColor: variantStyle.bg,
          paddingVertical: isSmall ? 2 : spacing.xs,
          paddingHorizontal: isSmall ? spacing.sm : spacing.md,
        },
        style,
      ]}
    >
      <Text
        style={[
          styles.label,
          {
            color: variantStyle.text,
            fontSize: isSmall ? 11 : 12,
          },
        ]}
      >
        {label}
      </Text>
    </View>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape, isDark: boolean) => ({
  ...StyleSheet.create({
    badge: {
      borderRadius: shape.isSoft ? shape.radius.tag : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.stroke,
      alignSelf: 'flex-start',
    },
    label: {
      fontWeight: shape.isSoft ? '600' : '700',
    },
  }),
  _colors: colors,
  _isSoft: shape.isSoft,
  _isDark: isDark,
});

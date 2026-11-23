/**
 * AI 洞察卡片组件 - Neo-Brutalism 风格
 * 描边卡片 + emoji 贴纸 + 糖果色强调
 */
import React from 'react';
import { View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { borderRadius, borderWidth, spacing, shadow } from '../../theme/spacing';
import { useStyles } from '../../hooks';

export type InsightType = 'tip' | 'warning' | 'success' | 'info';

export interface InsightCardProps {
  type: InsightType;
  title: string;
  content: string;
  actionLabel?: string;
  onAction?: () => void;
  onDismiss?: () => void;
}

export default function InsightCard({
  type,
  title,
  content,
  actionLabel,
  onAction,
  onDismiss,
}: InsightCardProps) {
  const styles = useStyles(createStyles);

  const getConfig = () => {
    switch (type) {
      case 'tip':
        return { icon: '💡', bg: styles._colors.accent };
      case 'warning':
        return { icon: '⚠️', bg: styles._colors.warning };
      case 'success':
        return { icon: '✨', bg: styles._colors.success };
      default:
        return { icon: '🤖', bg: styles._colors.primary };
    }
  };

  const config = getConfig();

  return (
    <View style={styles.container}>
      {onDismiss && (
        <TouchableOpacity onPress={onDismiss} style={styles.dismissButton}>
          <Text style={styles.dismissIcon}>✕</Text>
        </TouchableOpacity>
      )}

      <View style={styles.contentRow}>
        <View style={[styles.iconContainer, { backgroundColor: config.bg }]}>
          <Text style={styles.icon}>{config.icon}</Text>
        </View>

        <View style={styles.textContainer}>
          <Text style={styles.title}>{title}</Text>
          <Text style={styles.contentText}>{content}</Text>

          {actionLabel && onAction && (
            <TouchableOpacity onPress={onAction} style={styles.actionButton}>
              <Text style={styles.actionText}>{actionLabel} →</Text>
            </TouchableOpacity>
          )}
        </View>
      </View>
    </View>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    container: {
      borderRadius: shape.radius.card,
      borderWidth: shape.isSoft ? shape.borderWidth.card : borderWidth.medium,
      borderColor: shape.isSoft ? colors.cardBorder : colors.stroke,
      backgroundColor: colors.surface,
      padding: spacing.lg,
      marginBottom: spacing.md,
      position: 'relative',
      ...(shape.isSoft ? shape.elevation.card : shadow.small),
    },
    dismissButton: {
      position: 'absolute',
      top: spacing.sm,
      right: spacing.sm,
      width: 28,
      height: 28,
      borderRadius: shape.isSoft ? 14 : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: shape.isSoft ? colors.surfaceMuted : 'transparent',
      alignItems: 'center',
      justifyContent: 'center',
      zIndex: 1,
    },
    dismissIcon: {
      fontSize: 12,
      color: shape.isSoft ? colors.textSecondary : colors.textPrimary,
      fontWeight: '800',
    },
    contentRow: {
      flexDirection: 'row',
    },
    iconContainer: {
      width: 44,
      height: 44,
      borderRadius: shape.isSoft ? 14 : borderRadius.medium,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.stroke,
      alignItems: 'center',
      justifyContent: 'center',
      marginRight: spacing.md,
    },
    icon: {
      fontSize: 22,
    },
    textContainer: {
      flex: 1,
      paddingRight: spacing.lg,
    },
    title: {
      fontSize: 15,
      fontWeight: '700',
      color: colors.textPrimary,
      marginBottom: spacing.xs,
    },
    contentText: {
      fontSize: 13,
      fontWeight: '500',
      color: colors.textSecondary,
      lineHeight: 18,
    },
    actionButton: {
      marginTop: spacing.sm,
      alignSelf: 'flex-start',
      backgroundColor: colors.primaryLight,
      borderRadius: shape.isSoft ? shape.radius.pill : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.stroke,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.xs,
    },
    actionText: {
      fontSize: 13,
      fontWeight: '700',
      color: colors.primary,
    },
  }),
  _colors: colors,
});

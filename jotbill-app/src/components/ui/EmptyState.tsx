/**
 * 空状态组件
 * - brutal：描边容器 + 大 emoji 方块 + 粗标题 + BrutalPressable 按钮
 * - hogwarts：雪鸮与羊皮纸账卷复古水彩插画 + 星辰花饰分隔线 + 古籍衬线标题
 */
import React from 'react';
import { Image, View, Text, StyleSheet } from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { spacing } from '../../theme/spacing';
import { useStyles } from '../../hooks';
import { useTheme } from '../../providers';
import { HOGWARTS_FONTS, HOGWARTS_IMAGES } from '../../constants/hogwartsAssets';
import BrutalPressable from './BrutalPressable';
import { HogwartsStarDivider } from './HogwartsOrnaments';

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
  const { isHogwarts, colors } = useTheme();

  return (
    <View style={[styles.container, style]}>
      {isHogwarts ? (
        <>
          <Image
            source={HOGWARTS_IMAGES.emptyOwl}
            style={styles.hogwartsArtwork}
            resizeMode="contain"
          />
          <HogwartsStarDivider
            color={colors.accent}
            width={180}
            style={styles.hogwartsDivider}
          />
        </>
      ) : (
        <View style={styles.iconBlock}>
          <Text style={styles.icon}>{icon}</Text>
        </View>
      )}
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
      borderRadius: shape.radius.card,
      borderWidth: shape.isHogwarts ? shape.borderWidth.card : shape.borderWidth.control,
      borderColor: colors.cardBorder,
      ...shape.elevation.card,
    },
    hogwartsArtwork: {
      width: 148,
      height: 148,
      borderRadius: 20,
      marginBottom: spacing.sm,
      borderWidth: 1,
      borderColor: colors.cardBorder,
    },
    hogwartsDivider: {
      marginBottom: spacing.md,
    },
    iconBlock: {
      width: 80,
      height: 80,
      borderRadius: shape.radius.medium,
      borderWidth: shape.borderWidth.control,
      borderColor: colors.stroke,
      backgroundColor: colors.accent,
      alignItems: 'center',
      justifyContent: 'center',
      marginBottom: spacing.lg,
    },
    icon: {
      fontSize: 40,
    },
    title: {
      fontSize: shape.isHogwarts ? 19 : 18,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
      textAlign: 'center',
      marginBottom: spacing.sm,
    },
    description: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : '500',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
      textAlign: 'center',
      lineHeight: 20,
      marginBottom: spacing.lg,
    },
    button: {
      marginTop: spacing.md,
      backgroundColor: colors.primary,
      borderRadius: shape.radius.button,
      borderWidth: shape.borderWidth.control,
      borderColor: shape.isHogwarts ? colors.accent : colors.stroke,
      paddingHorizontal: spacing.xl,
      paddingVertical: spacing.md,
    },
    buttonText: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.onPrimary,
    },
  }),
  _colors: colors,
});

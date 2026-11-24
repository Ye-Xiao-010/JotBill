/**
 * 空状态组件
 * - brutal：描边容器 + 大 emoji 方块 + 粗标题 + BrutalPressable 按钮
 * - hogwarts：雪鸮与羊皮纸账卷复古水彩插画 + 星辰花饰分隔线 + 古籍衬线标题
 */
import React from 'react';
import { Image, View, Text, StyleSheet } from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { spacing, borderRadius, borderWidth, shadow } from '../../theme/spacing';
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
      borderRadius: shape.isSoft || shape.isHogwarts ? shape.radius.card : borderRadius.card,
      borderWidth: shape.isSoft || shape.isHogwarts ? shape.borderWidth.card : borderWidth.medium,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.cardBorder : colors.stroke,
      ...(shape.isSoft || shape.isHogwarts ? shape.elevation.card : shadow.small),
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
      width: shape.isSoft ? 76 : 80,
      height: shape.isSoft ? 76 : 80,
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
      fontSize: shape.isSoft ? 17 : shape.isHogwarts ? 19 : 18,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
      textAlign: 'center',
      marginBottom: spacing.sm,
    },
    description: {
      fontSize: shape.isSoft ? 13.5 : 14,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '400' : '500',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: shape.isSoft ? colors.textTertiary : colors.textSecondary,
      textAlign: 'center',
      lineHeight: 20,
      marginBottom: spacing.lg,
    },
    button: {
      marginTop: spacing.md,
      backgroundColor: colors.primary,
      borderRadius: shape.isSoft || shape.isHogwarts ? shape.radius.button : borderRadius.button,
      borderWidth: shape.isSoft ? 0 : shape.isHogwarts ? shape.borderWidth.control : borderWidth.medium,
      borderColor: shape.isHogwarts ? colors.accent : colors.stroke,
      paddingHorizontal: spacing.xl,
      paddingVertical: spacing.md,
    },
    buttonText: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.onPrimary,
    },
  }),
  _colors: colors,
});

/**
 * 账户卡片组件 - Neo-Brutalism 风格
 * 粗描边卡片 + 糖果色图标块
 */
import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { borderRadius, borderWidth, spacing } from '../../theme/spacing';
import { useStyles } from '../../hooks';
import { HOGWARTS_FONTS } from '../../constants/hogwartsAssets';
import BrutalPressable from '../ui/BrutalPressable';
import { typography } from '../../theme';

export type AccountType = 'bank_card' | 'e_wallet' | 'credit_card' | 'cash';

export interface AccountCardProps {
  id: number;
  name: string;
  type: AccountType;
  balance: number;
  icon?: string;
  color?: string;
  onPress?: () => void;
}

const accountTypeConfig: Record<AccountType, { icon: string; label: string }> = {
  bank_card: { icon: '💳', label: '银行卡' },
  e_wallet: { icon: '📱', label: '电子钱包' },
  credit_card: { icon: '💎', label: '信用卡' },
  cash: { icon: '💵', label: '现金' },
};

export default function AccountCard({
  name,
  type,
  balance,
  icon,
  color,
  onPress,
}: AccountCardProps) {
  const styles = useStyles(createStyles);
  const typeConfig = accountTypeConfig[type] || accountTypeConfig.cash;
  const displayIcon = icon || typeConfig.icon;
  const cardColor = color || styles._colors.primary;

  const content = (
    <>
      <View style={styles.header}>
        <View style={styles.iconContainer}>
          <Text style={styles.icon}>{displayIcon}</Text>
        </View>
        <View style={styles.nameContainer}>
          <Text style={styles.name} numberOfLines={1}>{name}</Text>
          <Text style={styles.type}>{typeConfig.label}</Text>
        </View>
      </View>

      <View style={styles.balanceContainer}>
        <Text style={styles.balanceLabel}>余额</Text>
        <Text style={styles.balance}>¥{balance.toLocaleString('zh-CN', { minimumFractionDigits: 2 })}</Text>
      </View>
    </>
  );

  if (onPress) {
    return (
      <BrutalPressable
        onPress={onPress}
        style={[styles.container, { backgroundColor: cardColor }]}
        shadowOffset={4}
        shadowColor={styles._colors.stroke}
      >
        {content}
      </BrutalPressable>
    );
  }

  return (
    <View style={[styles.container, { backgroundColor: cardColor }]}>
      {content}
    </View>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    container: {
      borderRadius: shape.radius.card,
      borderWidth: shape.isHogwarts ? shape.borderWidth.card : borderWidth.thick,
      borderColor: shape.isHogwarts ? colors.accent : colors.stroke,
      padding: spacing.lg,
      minWidth: 200,
      marginRight: spacing.md,
      ...(shape.isHogwarts ? shape.elevation.card : {}),
    },
    header: {
      flexDirection: 'row',
      alignItems: 'center',
      marginBottom: spacing.lg,
    },
    iconContainer: {
      width: 44,
      height: 44,
      borderRadius: shape.isHogwarts ? borderRadius.large : borderRadius.medium,
      backgroundColor: 'rgba(255, 255, 255, 0.25)',
      borderWidth: shape.borderWidth.subtle,
      borderColor: 'rgba(255, 255, 255, 0.4)',
      alignItems: 'center',
      justifyContent: 'center',
      marginRight: spacing.md,
    },
    icon: {
      fontSize: 22,
    },
    nameContainer: {
      flex: 1,
    },
    name: {
      fontSize: 16,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: '#FFFFFF',
      marginBottom: 2,
    },
    type: {
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: 'rgba(255, 255, 255, 0.78)',
    },
    balanceContainer: {
      marginTop: spacing.sm,
    },
    balanceLabel: {
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: 'rgba(255, 255, 255, 0.78)',
      marginBottom: spacing.xs,
    },
    balance: {
      fontSize: 24,
      fontWeight: shape.isHogwarts ? undefined : '900',
      color: '#FFFFFF',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.numeric : typography.fontFamily.mono,
    },
  }),
  _colors: colors,
});

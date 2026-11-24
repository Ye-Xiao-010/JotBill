/**
 * 账单项组件 - Neo-Brutalism 风格
 * 描边卡片 + Courier 金额（列表用，无实心阴影）
 */
import React from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
} from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { spacing, borderRadius, borderWidth, typography } from '../../theme';
import { useStyles } from '../../hooks';
import { HOGWARTS_FONTS } from '../../constants/hogwartsAssets';
import type { AccountBadgeInput } from '../../constants/accountStyles';
import { formatSignedCurrency } from '../../utils/format';
import AccountBadge from './AccountBadge';
import CategoryIcon from './CategoryIcon';


export interface BillData {
  id: string;
  category: string;
  amount: number;
  type: 'income' | 'expense';
  date: string;
  description?: string;
  icon: string;
}

interface BillItemProps {
  bill: BillData;
  /** 扣款/入账账户：提供时在金额下方渲染主题适配的彩色账户微标 */
  account?: AccountBadgeInput;
  onPress?: (bill: BillData) => void;
}

const BillItem: React.FC<BillItemProps> = ({ bill, account, onPress }) => {
  const styles = useStyles(createStyles);

  const handlePress = () => {
    onPress?.(bill);
  };

  const isIncome = bill.type === 'income';

  return (
    <TouchableOpacity
      onPress={handlePress}
      style={styles.container}
      activeOpacity={0.8}
    >
      <View style={styles.leftSection}>
        <View style={[
          styles.iconContainer,
          {
            backgroundColor: (styles._isSoft || styles._isHogwarts)
              ? (isIncome ? styles._colors.incomeLight : styles._colors.expenseLight)
              : (isIncome ? styles._colors.income : styles._colors.expense),
          },
        ]}>
          <CategoryIcon
            icon={bill.icon}
            fallback={isIncome ? '💰' : '💸'}
            size={20}
            color={styles._isHogwarts ? (isIncome ? styles._colors.incomeStrong : styles._colors.expenseStrong) : undefined}
            emojiStyle={styles.icon}
          />
        </View>
        <View style={styles.billInfo}>
          <Text style={styles.category}>{bill.category}</Text>
          <Text style={styles.date}>{bill.date}</Text>
          {bill.description && (
            <Text style={styles.description} numberOfLines={1}>
              {bill.description}
            </Text>
          )}
        </View>
      </View>

      <View style={styles.rightSection}>
        <View style={[
          styles.amountBadge,
          isIncome ? styles.incomeBadge : styles.expenseBadge,
        ]}>
          <Text style={[styles.amount, isIncome ? styles.incomeAmount : styles.expenseAmount]} numberOfLines={1}>
            {formatSignedCurrency(bill.amount, isIncome)}
          </Text>
        </View>
        {account ? <AccountBadge account={account} /> : null}
      </View>
    </TouchableOpacity>
  );
};

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    container: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      backgroundColor: colors.surface,
      marginHorizontal: spacing.lg,
      marginVertical: spacing.xs,
      borderRadius: shape.radius.card,
      paddingHorizontal: spacing.lg,
      paddingVertical: spacing.md,
      borderWidth: shape.isSoft || shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.cardBorder : colors.stroke,
      ...(shape.isSoft || shape.isHogwarts ? shape.elevation.card : {}),
    },
    leftSection: {
      flexDirection: 'row',
      alignItems: 'center',
      flex: 1,
      minWidth: 0,
      paddingRight: spacing.sm,
    },
    iconContainer: {
      width: 44,
      height: 44,
      borderRadius: shape.isSoft ? 14 : shape.isHogwarts ? borderRadius.large : borderRadius.medium,
      borderWidth: shape.isSoft ? 0 : shape.borderWidth.subtle,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      alignItems: 'center',
      justifyContent: 'center',
      marginRight: spacing.md,
    },
    icon: {
      fontSize: 20,
    },
    billInfo: {
      flex: 1,
      minWidth: 0,
    },
    rightSection: {
      alignItems: 'flex-end',
      justifyContent: 'center',
      gap: 4,
      flexShrink: 0,
    },
    category: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
      marginBottom: 2,
    },
    date: {
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : '500',
      color: colors.textTertiary,
      fontFamily: shape.isSoft ? undefined : shape.isHogwarts ? HOGWARTS_FONTS.numeric : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
      marginBottom: 2,
    },
    description: {
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : '500',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    amountBadge: {
      borderRadius: shape.isSoft ? shape.radius.small : shape.radius.badge,
      borderWidth: shape.isSoft ? 0 : shape.borderWidth.subtle,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      paddingHorizontal: shape.isSoft ? 0 : spacing.md,
      paddingVertical: spacing.xs,
    },
    incomeBadge: {
      backgroundColor: shape.isSoft ? 'transparent' : colors.incomeLight,
    },
    expenseBadge: {
      backgroundColor: shape.isSoft ? 'transparent' : colors.expenseLight,
    },
    amount: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isSoft ? undefined : shape.isHogwarts ? HOGWARTS_FONTS.numeric : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
    },
    incomeAmount: {
      color: shape.isSoft ? colors.income : colors.incomeStrong,
    },
    expenseAmount: {
      color: shape.isSoft ? colors.expense : colors.expenseStrong,
    },
  }),
  _colors: colors,
  _isSoft: shape.isSoft,
  _isHogwarts: shape.isHogwarts,
});

export default BillItem;

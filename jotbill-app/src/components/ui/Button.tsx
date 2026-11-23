/**
 * 按钮组件 - Neo-Brutalism 风格
 * 粗描边 + 实心偏移阴影 + 饱和色块
 */
import React from 'react';
import {
  TouchableOpacity,
  Text,
  StyleSheet,
  ViewStyle,
  TextStyle,
  ActivityIndicator,
} from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { spacing, borderRadius, borderWidth, shadow } from '../../theme/spacing';
import { useStyles } from '../../hooks';

type ButtonVariant = 'primary' | 'secondary' | 'outline' | 'text';
type ButtonSize = 'small' | 'medium' | 'large';

interface ButtonProps {
  title: string;
  onPress: () => void;
  variant?: ButtonVariant;
  size?: ButtonSize;
  disabled?: boolean;
  loading?: boolean;
  style?: ViewStyle;
  textStyle?: TextStyle;
  icon?: string;
}

const Button: React.FC<ButtonProps> = ({
  title,
  onPress,
  variant = 'primary',
  size = 'medium',
  disabled = false,
  loading = false,
  style,
  textStyle,
  icon,
}) => {
  const styles = useStyles(createStyles);

  const buttonStyle = [
    styles.button,
    styles[variant],
    styles[size],
    disabled && styles.disabled,
    style,
  ];

  const buttonTextStyle = [
    styles.buttonText,
    styles[`${variant}Text`],
    styles[`${size}Text`],
    disabled && styles.disabledText,
    textStyle,
  ];

  return (
    <TouchableOpacity
      style={buttonStyle}
      onPress={onPress}
      disabled={disabled || loading}
      activeOpacity={0.85}
    >
      {loading ? (
        <ActivityIndicator
          size="small"
          color={variant === 'primary' ? styles._colors.onPrimary : styles._colors.primary}
        />
      ) : (
        <>
          {icon && <Text style={[buttonTextStyle, styles.icon]}>{icon}</Text>}
          <Text style={buttonTextStyle}>{title}</Text>
        </>
      )}
    </TouchableOpacity>
  );
};

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    button: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'center',
      borderRadius: shape.isSoft ? shape.radius.button : borderRadius.button,
      borderWidth: shape.isSoft ? 0 : borderWidth.medium,
      borderColor: shape.isSoft ? colors.cardBorder : colors.stroke,
      ...(shape.isSoft ? shape.elevation.none : shadow.small),
    },

    // 变体样式
    primary: {
      backgroundColor: colors.primary,
    },
    secondary: {
      backgroundColor: shape.isSoft ? colors.primaryLight : colors.accent,
    },
    outline: {
      backgroundColor: colors.surface,
      borderWidth: shape.isSoft ? 1 : borderWidth.medium,
      borderColor: shape.isSoft ? colors.cardBorder : colors.stroke,
    },
    text: {
      backgroundColor: 'transparent',
      borderWidth: 0,
      shadowOpacity: 0,
      elevation: 0,
    },

    // 尺寸样式
    small: {
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.sm,
      minHeight: 36,
    },
    medium: {
      paddingHorizontal: spacing.lg,
      paddingVertical: spacing.md,
      minHeight: 48,
    },
    large: {
      paddingHorizontal: spacing.xl,
      paddingVertical: spacing.lg,
      minHeight: 56,
    },

    // 文本样式
    buttonText: {
      fontWeight: shape.isSoft ? shape.fontWeight.semibold : '800',
      textAlign: 'center',
    },
    primaryText: {
      color: colors.onPrimary,
    },
    secondaryText: {
      color: shape.isSoft ? colors.primaryDark : colors.textPrimary,
    },
    outlineText: {
      color: colors.textPrimary,
    },
    textText: {
      color: colors.primary,
    },

    // 尺寸文本样式
    smallText: {
      fontSize: 13,
    },
    mediumText: {
      fontSize: 15,
    },
    largeText: {
      fontSize: 18,
    },

    // 禁用状态
    disabled: {
      backgroundColor: shape.isSoft ? colors.surfaceMuted : colors.divider,
      borderColor: shape.isSoft ? 'transparent' : colors.textTertiary,
      shadowOpacity: 0,
      elevation: 0,
    },
    disabledText: {
      color: colors.textTertiary,
    },

    // 图标样式
    icon: {
      marginRight: spacing.sm,
    },
  }),
  _colors: colors,
});

export default Button;

/**
 * 卡片组件 - Neo-Brutalism 风格
 * 粗描边 + 实心偏移阴影
 */
import React from 'react';
import {
  View,
  StyleSheet,
  ViewStyle,
  TouchableOpacity,
  TouchableOpacityProps,
} from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { spacing, borderRadius, borderWidth, shadow } from '../../theme/spacing';
import { useStyles } from '../../hooks';

interface CardProps extends TouchableOpacityProps {
  children: React.ReactNode;
  style?: ViewStyle;
  padding?: keyof typeof spacing;
  margin?: keyof typeof spacing;
  shadow?: boolean;
  onPress?: () => void;
}

const Card: React.FC<CardProps> = ({
  children,
  style,
  padding = 'lg',
  margin = 'md',
  shadow: showShadow = true,
  onPress,
  ...props
}) => {
  const styles = useStyles(createStyles);

  const cardStyle = [
    styles.card,
    {
      padding: spacing[padding],
      margin: spacing[margin],
    },
    showShadow && styles._elevation,
    style,
  ];

  if (onPress) {
    return (
      <TouchableOpacity
        style={cardStyle}
        onPress={onPress}
        activeOpacity={0.85}
        {...props}
      >
        {children}
      </TouchableOpacity>
    );
  }

  return <View style={cardStyle}>{children}</View>;
};

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    card: {
      backgroundColor: colors.surface,
      borderRadius: shape.isSoft ? shape.radius.card : borderRadius.card,
      borderWidth: shape.isSoft ? shape.borderWidth.card : borderWidth.medium,
      borderColor: shape.isSoft ? colors.cardBorder : colors.stroke,
    },
  }),
  _colors: colors,
  _elevation: shape.isSoft ? shape.elevation.card : shadow.small,
});

export default Card;

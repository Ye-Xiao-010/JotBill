/**
 * 用户头像组件 - Neo-Brutalism 风格
 * 粗描边 + 方圆角(medium) + 粗首字母
 */
import React from 'react';
import { View, Text, Image, StyleSheet } from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { borderRadius, borderWidth } from '../../theme/spacing';
import { useStyles } from '../../hooks';

export interface AvatarProps {
  source?: string;
  name?: string;
  size?: 'small' | 'medium' | 'large';
  style?: object;
}

const sizeMap = {
  small: 32,
  medium: 48,
  large: 64,
};

const fontSizeMap = {
  small: 14,
  medium: 18,
  large: 24,
};

export default function Avatar({ source, name, size = 'medium', style }: AvatarProps) {
  const styles = useStyles(createStyles);
  const avatarSize = sizeMap[size];
  const fontSize = fontSizeMap[size];

  const getInitials = (name?: string): string => {
    if (!name) return '?';
    const parts = name.trim().split(' ');
    if (parts.length === 1) {
      return parts[0].charAt(0).toUpperCase();
    }
    return (parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
  };

  const resolvedRadius = styles._isSoft ? avatarSize / 2 : borderRadius.medium;

  if (source) {
    return (
      <Image
        source={{ uri: source }}
        style={[
          styles.avatar,
          {
            width: avatarSize,
            height: avatarSize,
            borderRadius: resolvedRadius,
          },
          style,
        ]}
      />
    );
  }

  return (
    <View
      style={[
        styles.placeholder,
        {
          width: avatarSize,
          height: avatarSize,
          borderRadius: resolvedRadius,
        },
        style,
      ]}
    >
      <Text style={[styles.initials, { fontSize }]}>{getInitials(name)}</Text>
    </View>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    avatar: {
      backgroundColor: shape.isSoft ? colors.surfaceMuted : colors.divider,
      borderWidth: shape.isSoft ? 0 : borderWidth.medium,
      borderColor: colors.stroke,
    },
    placeholder: {
      backgroundColor: shape.isSoft ? colors.primaryLight : colors.accent,
      borderWidth: shape.isSoft ? 0 : borderWidth.medium,
      borderColor: colors.stroke,
      alignItems: 'center',
      justifyContent: 'center',
    },
    initials: {
      color: shape.isSoft ? colors.primary : colors.textPrimary,
      fontWeight: shape.isSoft ? '700' : '800',
    },
  }),
  _colors: colors,
  _isSoft: shape.isSoft,
});

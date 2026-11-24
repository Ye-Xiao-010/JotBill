/**
 * 搜索栏组件 - Neo-Brutalism 风格
 * 粗描边 + stroke 色 + 粗图标
 */
import React, { useState } from 'react';
import { View, TextInput, TouchableOpacity, Text, StyleSheet } from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { borderRadius, borderWidth, spacing } from '../../theme/spacing';
import { useStyles } from '../../hooks';
import { HOGWARTS_FONTS } from '../../constants/hogwartsAssets';

export interface SearchBarProps {
  value: string;
  onChangeText: (text: string) => void;
  placeholder?: string;
  onSubmit?: () => void;
  onClear?: () => void;
  style?: object;
}

export default function SearchBar({
  value,
  onChangeText,
  placeholder = '搜索...',
  onSubmit,
  onClear,
  style,
}: SearchBarProps) {
  const styles = useStyles(createStyles);
  const [isFocused, setIsFocused] = useState(false);

  const handleClear = () => {
    onChangeText('');
    onClear?.();
  };

  return (
    <View style={[styles.container, isFocused && styles.containerFocused, style]}>
      <Text style={styles.icon}>🔍</Text>
      <TextInput
        value={value}
        onChangeText={onChangeText}
        placeholder={placeholder}
        placeholderTextColor={styles._colors.textTertiary}
        style={styles.input}
        onFocus={() => setIsFocused(true)}
        onBlur={() => setIsFocused(false)}
        onSubmitEditing={onSubmit}
        returnKeyType="search"
      />
      {value.length > 0 && (
        <TouchableOpacity onPress={handleClear} style={styles.clearButton}>
          <Text style={styles.clearIcon}>✕</Text>
        </TouchableOpacity>
      )}
    </View>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    container: {
      flexDirection: 'row',
      alignItems: 'center',
      backgroundColor: colors.surface,
      borderRadius: shape.radius.input,
      paddingHorizontal: spacing.md,
      height: 48,
      borderWidth: shape.isHogwarts ? shape.borderWidth.card : borderWidth.medium,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
    },
    containerFocused: {
      borderColor: colors.accent,
    },
    icon: {
      fontSize: 18,
      marginRight: spacing.sm,
    },
    input: {
      flex: 1,
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
      padding: 0,
    },
    clearButton: {
      width: 28,
      height: 28,
      borderRadius: shape.isHogwarts ? borderRadius.round : borderRadius.small,
      borderWidth: shape.borderWidth.subtle,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      backgroundColor: colors.divider,
      alignItems: 'center',
      justifyContent: 'center',
    },
    clearIcon: {
      fontSize: 12,
      color: colors.textPrimary,
      fontWeight: '800',
    },
  }),
  _colors: colors,
});

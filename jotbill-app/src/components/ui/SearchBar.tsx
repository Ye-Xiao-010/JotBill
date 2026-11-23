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
      backgroundColor: shape.isSoft ? colors.surfaceMuted : colors.surface,
      borderRadius: shape.isSoft ? shape.radius.pill : borderRadius.input,
      paddingHorizontal: spacing.md,
      height: shape.isSoft ? 44 : 48,
      borderWidth: shape.isSoft ? shape.borderWidth.control : borderWidth.medium,
      borderColor: shape.isSoft ? colors.cardBorder : colors.stroke,
    },
    containerFocused: {
      borderColor: shape.isSoft ? colors.primary : colors.accent,
      borderWidth: shape.isSoft ? 1.5 : borderWidth.medium,
      backgroundColor: colors.surface,
    },
    icon: {
      fontSize: 16,
      marginRight: spacing.sm,
    },
    input: {
      flex: 1,
      fontSize: 15,
      fontWeight: shape.isSoft ? '500' : '600',
      color: colors.textPrimary,
      padding: 0,
    },
    clearButton: {
      width: 26,
      height: 26,
      borderRadius: shape.isSoft ? 13 : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: colors.divider,
      alignItems: 'center',
      justifyContent: 'center',
    },
    clearIcon: {
      fontSize: 12,
      color: colors.textPrimary,
      fontWeight: shape.isSoft ? '600' : '800',
    },
  }),
  _colors: colors,
});

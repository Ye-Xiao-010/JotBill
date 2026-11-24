/**
 * 分段控制器组件 - Neo-Brutalism 风格
 * 描边分段，选中态糖果色填充+粗边，粗文字
 */
import React from 'react';
import { View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { spacing } from '../../theme/spacing';
import { useStyles } from '../../hooks';
import { HOGWARTS_FONTS } from '../../constants/hogwartsAssets';

export interface SegmentedControlOption {
  key: string;
  label: string;
}

export interface SegmentedControlProps {
  options: SegmentedControlOption[];
  selectedKey: string;
  onSelect: (key: string) => void;
  style?: object;
}

export default function SegmentedControl({
  options,
  selectedKey,
  onSelect,
  style,
}: SegmentedControlProps) {
  const styles = useStyles(createStyles);

  return (
    <View style={[styles.container, style]}>
      {options.map((option) => {
        const isSelected = option.key === selectedKey;

        return (
          <TouchableOpacity
            key={option.key}
            onPress={() => onSelect(option.key)}
            style={[
              styles.option,
              isSelected && styles.optionSelected,
            ]}
            activeOpacity={0.7}
          >
            <Text style={[styles.label, isSelected && styles.labelSelected]}>
              {option.label}
            </Text>
          </TouchableOpacity>
        );
      })}
    </View>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    container: {
      flexDirection: 'row',
      backgroundColor: shape.isHogwarts ? colors.surfaceMuted : colors.surface,
      borderRadius: shape.radius.button,
      borderWidth: shape.borderWidth.control,
      borderColor: colors.cardBorder,
      padding: 3,
    },
    option: {
      flex: 1,
      paddingVertical: spacing.sm + 2,
      paddingHorizontal: spacing.md,
      alignItems: 'center',
      justifyContent: 'center',
      borderRadius: shape.radius.small,
    },
    optionSelected: {
      backgroundColor: shape.isHogwarts ? colors.primary : colors.accent,
      borderWidth: shape.isHogwarts ? 1 : shape.borderWidth.card,
      borderColor: shape.isHogwarts ? colors.accent : colors.stroke,
    },
    label: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
    labelSelected: {
      color: shape.isHogwarts ? colors.onPrimary : colors.textPrimary,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
    },
  }),
  _colors: colors,
});

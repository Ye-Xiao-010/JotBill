/**
 * 分段控制器组件
 * - brutal：粗描边 + 糖果色选中块
 * - soft：次级浅槽 + 滑动指示滑块
 */
import React, { useEffect, useRef, useState } from 'react';
import { Animated, Easing, View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { borderRadius, borderWidth, spacing } from '../../theme/spacing';
import { useStyles } from '../../hooks';

export interface SegmentedControlOption {
  key: string;
  label: string;
}

export interface SegmentedControlProps {
  options: SegmentedControlOption[];
  selectedKey: string;
  onSelect: (key: string) => void;
  size?: 'small' | 'medium';
  style?: object;
}

export default function SegmentedControl({
  options,
  selectedKey,
  onSelect,
  size = 'medium',
  style,
}: SegmentedControlProps) {
  const styles = useStyles(createStyles);
  const isSoft = styles._isSoft;
  const isSmall = size === 'small';
  const [innerWidth, setInnerWidth] = useState(0);
  const slideAnim = useRef(new Animated.Value(0)).current;
  const didLayoutRef = useRef(false);

  const selectedIndex = Math.max(
    0,
    options.findIndex((option) => option.key === selectedKey),
  );
  const segmentWidth = options.length > 0 ? innerWidth / options.length : 0;

  useEffect(() => {
    if (!isSoft || segmentWidth <= 0) return;
    const toValue = selectedIndex * segmentWidth;
    if (!didLayoutRef.current) {
      didLayoutRef.current = true;
      slideAnim.setValue(toValue);
      return;
    }
    Animated.timing(slideAnim, {
      toValue,
      duration: 200,
      easing: Easing.out(Easing.cubic),
      useNativeDriver: true,
    }).start();
  }, [isSoft, selectedIndex, segmentWidth, slideAnim]);

  return (
    <View style={[styles.container, isSmall && isSoft && styles.containerSmall, style]}>
      <View
        style={styles.innerRow}
        onLayout={(e) => setInnerWidth(e.nativeEvent.layout.width)}
      >
        {isSoft && segmentWidth > 0 ? (
          <Animated.View
            pointerEvents="none"
            style={[
              styles.softIndicator,
              isSmall && styles.softIndicatorSmall,
              {
                width: segmentWidth,
                transform: [{ translateX: slideAnim }],
              },
            ]}
          />
        ) : null}
        {options.map((option) => {
          const isSelected = option.key === selectedKey;

          return (
            <TouchableOpacity
              key={option.key}
              onPress={() => onSelect(option.key)}
              style={[
                styles.option,
                isSmall && isSoft && styles.optionSmall,
                !isSoft && isSelected && styles.optionSelected,
              ]}
              activeOpacity={0.75}
              accessibilityRole="tab"
              accessibilityState={{ selected: isSelected }}
            >
              <Text
                style={[
                  styles.label,
                  isSmall && isSoft && styles.labelSmall,
                  isSelected && styles.labelSelected,
                ]}
                numberOfLines={1}
              >
                {option.label}
              </Text>
            </TouchableOpacity>
          );
        })}
      </View>
    </View>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape, isDark: boolean) => ({
  ...StyleSheet.create({
    container: {
      backgroundColor: shape.isSoft ? colors.surfaceMuted : colors.surface,
      borderRadius: shape.isSoft ? shape.radius.medium : borderRadius.button,
      borderWidth: shape.isSoft ? 0 : borderWidth.medium,
      borderColor: colors.stroke,
      padding: 3,
    },
    containerSmall: {
      borderRadius: 12,
    },
    innerRow: {
      flexDirection: 'row',
      position: 'relative',
    },
    softIndicator: {
      position: 'absolute',
      top: 0,
      bottom: 0,
      left: 0,
      borderRadius: 11,
      backgroundColor: isDark ? '#2C313C' : colors.surface,
      ...(isDark
        ? {}
        : {
            shadowColor: '#12141A',
            shadowOffset: { width: 0, height: 1 },
            shadowOpacity: 0.08,
            shadowRadius: 3,
            elevation: 2,
          }),
    },
    softIndicatorSmall: {
      borderRadius: 9,
    },
    option: {
      flex: 1,
      paddingVertical: shape.isSoft ? 7 : spacing.sm + 2,
      paddingHorizontal: shape.isSoft ? spacing.sm : spacing.md,
      alignItems: 'center',
      justifyContent: 'center',
      borderRadius: shape.isSoft ? 11 : borderRadius.small,
    },
    optionSmall: {
      paddingVertical: 5,
      paddingHorizontal: spacing.xs + 2,
    },
    optionSelected: {
      backgroundColor: colors.accent,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
    },
    label: {
      fontSize: shape.isSoft ? 13 : 14,
      fontWeight: shape.isSoft ? '500' : '600',
      color: colors.textTertiary,
    },
    labelSmall: {
      fontSize: 12,
    },
    labelSelected: {
      color: colors.textPrimary,
      fontWeight: shape.isSoft ? '600' : '800',
    },
  }),
  _colors: colors,
  _isSoft: shape.isSoft,
});

/**
 * SoftBillKeypad - 柔光主题内置 4×4 记账计算键盘
 * 支持数字、两位小数、+ / − 算式运算、退格、「再记」与「保存」。
 */
import React from 'react';
import { View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import { Delete } from 'lucide-react-native';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { useStyles } from '../../hooks';
import type { KeypadKey } from '../../utils/amountExpression';

interface SoftBillKeypadProps {
  onKey: (key: KeypadKey) => void;
  onSave: () => void;
  onSaveAndContinue?: () => void;
  saveLabel: string;
  showContinue?: boolean;
  disabled?: boolean;
}

type Cell =
  | { kind: 'key'; value: KeypadKey; label?: string; variant?: 'default' | 'op' }
  | { kind: 'again' }
  | { kind: 'save' };

const ROWS: Cell[][] = [
  [
    { kind: 'key', value: '1' },
    { kind: 'key', value: '2' },
    { kind: 'key', value: '3' },
    { kind: 'key', value: 'del', variant: 'op' },
  ],
  [
    { kind: 'key', value: '4' },
    { kind: 'key', value: '5' },
    { kind: 'key', value: '6' },
    { kind: 'key', value: '+', variant: 'op' },
  ],
  [
    { kind: 'key', value: '7' },
    { kind: 'key', value: '8' },
    { kind: 'key', value: '9' },
    { kind: 'key', value: '-', label: '−', variant: 'op' },
  ],
  [
    { kind: 'key', value: '.' },
    { kind: 'key', value: '0' },
    { kind: 'again' },
    { kind: 'save' },
  ],
];

export default function SoftBillKeypad({
  onKey,
  onSave,
  onSaveAndContinue,
  saveLabel,
  showContinue = true,
  disabled = false,
}: SoftBillKeypadProps) {
  const styles = useStyles(createStyles);

  return (
    <View style={styles.container}>
      {ROWS.map((row, rowIdx) => (
        <View key={rowIdx} style={styles.row}>
          {row.map((cell, colIdx) => {
            if (cell.kind === 'again') {
              if (!showContinue) {
                return (
                  <TouchableOpacity
                    key="zero-wide-placeholder"
                    style={styles.key}
                    onPress={() => onKey('0')}
                    disabled={disabled}
                    activeOpacity={0.72}
                    accessibilityLabel="00"
                    accessibilityRole="button"
                  >
                    <Text style={styles.keyText}>00</Text>
                  </TouchableOpacity>
                );
              }
              return (
                <TouchableOpacity
                  key="again"
                  style={[styles.key, styles.fnKey, disabled && styles.disabled]}
                  onPress={onSaveAndContinue}
                  disabled={disabled}
                  activeOpacity={0.78}
                  accessibilityLabel="保存并再记一笔"
                  accessibilityRole="button"
                >
                  <Text style={styles.fnKeyText}>再记</Text>
                </TouchableOpacity>
              );
            }

            if (cell.kind === 'save') {
              return (
                <TouchableOpacity
                  key="save"
                  style={[styles.key, styles.saveKey, disabled && styles.disabled]}
                  onPress={onSave}
                  disabled={disabled}
                  activeOpacity={0.82}
                  accessibilityLabel={saveLabel}
                  accessibilityRole="button"
                >
                  <Text style={styles.saveKeyText}>{saveLabel}</Text>
                </TouchableOpacity>
              );
            }

            const isDel = cell.value === 'del';
            const isOp = cell.variant === 'op';

            return (
              <TouchableOpacity
                key={`${rowIdx}-${colIdx}`}
                style={styles.key}
                onPress={() => onKey(cell.value)}
                disabled={disabled}
                activeOpacity={0.72}
                accessibilityLabel={isDel ? '删除' : cell.label ?? cell.value}
                accessibilityRole="button"
              >
                {isDel ? (
                  <Delete size={21} color={styles._colors.primary} strokeWidth={2} />
                ) : (
                  <Text style={[styles.keyText, isOp && styles.opText]}>
                    {cell.label ?? cell.value}
                  </Text>
                )}
              </TouchableOpacity>
            );
          })}
        </View>
      ))}
    </View>
  );
}

const createStyles = (colors: ThemeColors, _shape: ThemeShape, isDark: boolean) => ({
  ...StyleSheet.create({
    container: {
      gap: 8,
      paddingTop: 8,
    },
    row: {
      flexDirection: 'row',
      gap: 8,
    },
    key: {
      flex: 1,
      height: 50,
      borderRadius: 16,
      backgroundColor: isDark ? '#1F232C' : colors.surfaceMuted,
      alignItems: 'center',
      justifyContent: 'center',
    },
    keyText: {
      fontSize: 20,
      fontWeight: '500',
      color: colors.textPrimary,
      fontVariant: ['tabular-nums'],
    },
    opText: {
      color: colors.primary,
      fontWeight: '600',
      fontSize: 22,
    },
    fnKey: {
      backgroundColor: colors.primaryLight,
    },
    fnKeyText: {
      fontSize: 15,
      fontWeight: '600',
      color: colors.primaryDark,
    },
    saveKey: {
      backgroundColor: colors.primary,
    },
    saveKeyText: {
      fontSize: 16,
      fontWeight: '600',
      color: colors.onPrimary,
    },
    disabled: {
      opacity: 0.5,
    },
  }),
  _colors: colors,
});

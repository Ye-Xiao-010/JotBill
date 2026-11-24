/**
 * 确认对话框组件 - Neo-Brutalism 风格
 * 粗描边 + 红色糖果危险按钮
 */
import React from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
  Modal,
} from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { spacing, borderWidth, shadow } from '../../theme';
import { useStyles } from '../../hooks';
import { HOGWARTS_FONTS } from '../../constants/hogwartsAssets';

interface ConfirmModalProps {
  visible: boolean;
  title: string;
  message: string;
  confirmText?: string;
  cancelText?: string;
  onConfirm: () => void;
  onCancel: () => void;
  confirmColor?: string;
}

export const ConfirmModal: React.FC<ConfirmModalProps> = ({
  visible,
  title,
  message,
  confirmText = '确定',
  cancelText = '取消',
  onConfirm,
  onCancel,
  confirmColor,
}) => {
  const styles = useStyles(createStyles);
  const btnColor = confirmColor || styles._colors.error;

  return (
    <Modal
      visible={visible}
      transparent
      animationType="fade"
      onRequestClose={onCancel}
    >
      <View style={styles.overlay}>
        <View style={styles.modalContainer}>
          <Text style={styles.title}>{title}</Text>
          <Text style={styles.message}>{message}</Text>

          <View style={styles.buttonRow}>
            <TouchableOpacity
              style={[styles.button, styles.cancelButton]}
              onPress={onCancel}
              activeOpacity={0.8}
            >
              <Text style={styles.cancelButtonText}>{cancelText}</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={[styles.button, styles.confirmButton, { backgroundColor: btnColor }]}
              onPress={onConfirm}
              activeOpacity={0.8}
            >
              <Text style={styles.confirmButtonText}>{confirmText}</Text>
            </TouchableOpacity>
          </View>
        </View>
      </View>
    </Modal>
  );
};

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    overlay: {
      flex: 1,
      backgroundColor: colors.overlay,
      justifyContent: 'center',
      alignItems: 'center',
      padding: spacing.lg,
    },
    modalContainer: {
      backgroundColor: colors.surface,
      borderRadius: shape.radius.modal,
      borderWidth: shape.isHogwarts ? shape.borderWidth.strong : borderWidth.thick,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      width: '85%',
      padding: spacing.xl,
      ...(shape.isHogwarts ? shape.elevation.floating : shadow.medium),
    },
    title: {
      fontSize: 20,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
      marginBottom: spacing.md,
      textAlign: 'center',
    },
    message: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : '500',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
      marginBottom: spacing.xl,
      textAlign: 'center',
      lineHeight: 22,
    },
    buttonRow: {
      flexDirection: 'row',
      gap: spacing.md,
    },
    button: {
      flex: 1,
      paddingVertical: spacing.md,
      borderRadius: shape.radius.button,
      borderWidth: shape.isHogwarts ? shape.borderWidth.card : borderWidth.medium,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      alignItems: 'center',
      minHeight: 48,
      justifyContent: 'center',
      ...(shape.isHogwarts ? shape.elevation.card : shadow.small),
    },
    cancelButton: {
      backgroundColor: colors.surface,
    },
    cancelButtonText: {
      fontSize: 16,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    confirmButton: {
      backgroundColor: colors.error,
    },
    confirmButtonText: {
      fontSize: 16,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: '#FFFFFF',
    },
  }),
  _colors: colors,
});

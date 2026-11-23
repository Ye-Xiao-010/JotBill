/**
 * Alert 弹窗组件 - Neo-Brutalism 风格
 * 方形图标容器+描边，糖果色按钮+描边，实心阴影
 */
import React from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
} from 'react-native';
import Modal from './Modal';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { spacing, borderRadius, borderWidth, shadow } from '../../theme/spacing';
import { useStyles } from '../../hooks';

export interface AlertButton {
  text: string;
  onPress?: () => void;
  style?: 'default' | 'cancel' | 'destructive';
}

interface AlertProps {
  visible: boolean;
  title?: string;
  message: string;
  buttons?: AlertButton[];
  onClose: () => void;
  type?: 'info' | 'success' | 'warning' | 'error';
}

const Alert: React.FC<AlertProps> = ({
  visible,
  title,
  message,
  buttons = [{ text: '确定' }],
  onClose,
  type = 'info',
}) => {
  const styles = useStyles(createStyles);

  const getTypeIcon = () => {
    switch (type) {
      case 'success':
        return { icon: '✓', color: styles._colors.success };
      case 'warning':
        return { icon: '⚠', color: styles._colors.warning };
      case 'error':
        return { icon: '✕', color: styles._colors.error };
      default:
        return { icon: 'ℹ', color: styles._colors.primary };
    }
  };

  const typeStyle = getTypeIcon();

  const handleButtonPress = (button: AlertButton) => {
    button.onPress?.();
    onClose();
  };

  const getButtonStyle = (buttonStyle?: string) => {
    switch (buttonStyle) {
      case 'cancel':
        return styles.cancelButton;
      case 'destructive':
        return styles.destructiveButton;
      default:
        return styles.defaultButton;
    }
  };

  const getButtonTextStyle = (buttonStyle?: string) => {
    switch (buttonStyle) {
      case 'cancel':
        return styles.cancelButtonText;
      case 'destructive':
        return styles.destructiveButtonText;
      default:
        return styles.defaultButtonText;
    }
  };

  return (
    <Modal
      visible={visible}
      onClose={onClose}
      showCloseButton={false}
      closeOnBackdrop={false}
    >
      <View style={styles.alertContent}>
        {/* 图标 - 方形容器+描边 */}
        <View style={[styles.iconContainer, { backgroundColor: typeStyle.color }]}>
          <Text style={styles.icon}>
            {typeStyle.icon}
          </Text>
        </View>

        {/* 标题 */}
        {title && <Text style={styles.title}>{title}</Text>}

        {/* 消息内容 */}
        <Text style={styles.message}>{message}</Text>

        {/* 按钮组 */}
        <View style={styles.buttonContainer}>
          {buttons.map((button, index) => (
            <TouchableOpacity
              key={index}
              style={[
                styles.button,
                getButtonStyle(button.style),
                buttons.length === 1 && styles.singleButton,
              ]}
              onPress={() => handleButtonPress(button)}
              activeOpacity={0.8}
            >
              <Text style={[styles.buttonText, getButtonTextStyle(button.style)]}>
                {button.text}
              </Text>
            </TouchableOpacity>
          ))}
        </View>
      </View>
    </Modal>
  );
};

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    alertContent: {
      alignItems: 'center',
      paddingVertical: spacing.lg,
    },
    iconContainer: {
      width: 56,
      height: 56,
      borderRadius: shape.isSoft ? 18 : borderRadius.medium,
      borderWidth: shape.isSoft ? 0 : borderWidth.medium,
      borderColor: colors.stroke,
      alignItems: 'center',
      justifyContent: 'center',
      marginBottom: spacing.lg,
    },
    icon: {
      fontSize: 26,
      fontWeight: shape.isSoft ? '700' : '800',
      color: '#FFFFFF',
    },
    title: {
      fontSize: shape.isSoft ? 18 : 20,
      fontWeight: shape.isSoft ? '700' : '800',
      color: colors.textPrimary,
      textAlign: 'center',
      marginBottom: spacing.md,
    },
    message: {
      fontSize: 15,
      fontWeight: shape.isSoft ? '400' : '500',
      color: colors.textSecondary,
      textAlign: 'center',
      lineHeight: 22,
      marginBottom: spacing.xl,
      paddingHorizontal: spacing.md,
    },
    buttonContainer: {
      flexDirection: 'row',
      width: '100%',
      gap: spacing.md,
    },
    button: {
      flex: 1,
      paddingVertical: spacing.md,
      borderRadius: shape.isSoft ? shape.radius.button : borderRadius.button,
      borderWidth: shape.isSoft ? 0 : borderWidth.medium,
      borderColor: colors.stroke,
      alignItems: 'center',
      justifyContent: 'center',
      minHeight: 46,
      ...(shape.isSoft ? shape.elevation.none : shadow.small),
    },
    singleButton: {
      flex: 1,
    },
    defaultButton: {
      backgroundColor: colors.primary,
    },
    cancelButton: {
      backgroundColor: shape.isSoft ? colors.surfaceMuted : colors.surface,
    },
    destructiveButton: {
      backgroundColor: colors.error,
    },
    buttonText: {
      fontSize: 15,
      fontWeight: shape.isSoft ? '600' : '800',
    },
    defaultButtonText: {
      color: colors.onPrimary,
    },
    cancelButtonText: {
      color: shape.isSoft ? colors.textSecondary : colors.textPrimary,
    },
    destructiveButtonText: {
      color: '#FFFFFF',
    },
  }),
  _colors: colors,
});

export default Alert;

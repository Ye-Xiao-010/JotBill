/**
 * Toast 提示组件 - Neo-Brutalism 风格
 * 粗描边 + 实心阴影 + 粗文字
 */
import React, { useEffect, useRef } from 'react';
import {
  View,
  Text,
  StyleSheet,
  Animated,
  Dimensions,
} from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { spacing, borderRadius, borderWidth, shadow } from '../../theme/spacing';
import { useStyles } from '../../hooks';
import { HOGWARTS_FONTS } from '../../constants/hogwartsAssets';

export type ToastType = 'success' | 'error' | 'warning' | 'info';

interface ToastProps {
  visible: boolean;
  message: string;
  type?: ToastType;
  duration?: number;
  onHide?: () => void;
  position?: 'top' | 'center' | 'bottom';
}

const { width: screenWidth } = Dimensions.get('window');

const Toast: React.FC<ToastProps> = ({
  visible,
  message,
  type = 'info',
  duration = 3000,
  onHide,
  position = 'center',
}) => {
  const styles = useStyles(createStyles);
  const fadeAnim = useRef(new Animated.Value(0)).current;
  const translateY = useRef(new Animated.Value(50)).current;

  useEffect(() => {
    if (visible) {
      Animated.parallel([
        Animated.timing(fadeAnim, {
          toValue: 1,
          duration: 300,
          useNativeDriver: true,
        }),
        Animated.timing(translateY, {
          toValue: 0,
          duration: 300,
          useNativeDriver: true,
        }),
      ]).start();

      const timer = setTimeout(() => {
        hideToast();
      }, duration);

      return () => clearTimeout(timer);
    } else {
      hideToast();
    }
  }, [visible, duration]);

  const hideToast = () => {
    Animated.parallel([
      Animated.timing(fadeAnim, {
        toValue: 0,
        duration: 200,
        useNativeDriver: true,
      }),
      Animated.timing(translateY, {
        toValue: 50,
        duration: 200,
        useNativeDriver: true,
      }),
    ]).start(() => {
      onHide?.();
    });
  };

  const getToastConfig = () => {
    switch (type) {
      case 'success':
        return { bg: styles._colors.success, icon: '✓' };
      case 'error':
        return { bg: styles._colors.error, icon: '✕' };
      case 'warning':
        return { bg: styles._colors.warning, icon: '⚠' };
      default:
        return { bg: styles._colors.primary, icon: 'ℹ' };
    }
  };

  const toastConfig = getToastConfig();

  const getPositionStyle = () => {
    switch (position) {
      case 'top':
        return { top: 100 };
      case 'bottom':
        return { bottom: 100 };
      default:
        return {
          top: Dimensions.get('window').height / 2 - 30,
        };
    }
  };

  if (!visible) return null;

  return (
    <View style={[styles.container, getPositionStyle()]}>
      <Animated.View
        style={[
          styles.toast,
          !styles._isSoft && { backgroundColor: toastConfig.bg },
          {
            opacity: fadeAnim,
            transform: [{ translateY }],
          },
        ]}
      >
        <View
          style={[
            styles.iconContainer,
            styles._isSoft && { backgroundColor: toastConfig.bg },
          ]}
        >
          <Text style={styles.icon}>{toastConfig.icon}</Text>
        </View>
        <Text style={styles.message} numberOfLines={3}>
          {message}
        </Text>
      </Animated.View>
    </View>
  );
};

const createStyles = (colors: ThemeColors, shape: ThemeShape, isDark: boolean) => ({
  ...StyleSheet.create({
    container: {
      position: 'absolute',
      left: 0,
      right: 0,
      alignItems: 'center',
      zIndex: 9999,
      paddingHorizontal: spacing.lg,
    },
    toast: {
      flexDirection: 'row',
      alignItems: 'center',
      paddingHorizontal: shape.isSoft ? 16 : spacing.lg,
      paddingVertical: shape.isSoft ? 10 : spacing.md,
      borderRadius: shape.isSoft ? shape.radius.pill : shape.isHogwarts ? shape.radius.modal : borderRadius.button,
      borderWidth: shape.isSoft ? 0 : shape.isHogwarts ? 1.5 : borderWidth.medium,
      borderColor: shape.isHogwarts ? colors.accent : colors.stroke,
      backgroundColor: shape.isSoft ? (isDark ? '#F3F4F7' : '#1C1F27') : colors.primary,
      maxWidth: screenWidth - spacing.lg * 2,
      minWidth: shape.isSoft ? undefined : 200,
      ...(shape.isSoft || shape.isHogwarts ? shape.elevation.floating : shadow.medium),
    },
    iconContainer: {
      width: shape.isSoft ? 22 : 28,
      height: shape.isSoft ? 22 : 28,
      borderRadius: shape.isSoft ? 11 : shape.isHogwarts ? shape.radius.badge : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : shape.borderWidth.subtle,
      borderColor: 'rgba(255, 255, 255, 0.4)',
      backgroundColor: 'rgba(255, 255, 255, 0.2)',
      alignItems: 'center',
      justifyContent: 'center',
      marginRight: shape.isSoft ? 10 : spacing.md,
    },
    icon: {
      fontSize: shape.isSoft ? 12 : 14,
      color: '#FFFFFF',
      fontWeight: shape.isSoft ? '700' : '800',
    },
    message: {
      ...(shape.isSoft ? { flexShrink: 1 } : { flex: 1 }),
      fontSize: shape.isSoft ? 13 : 15,
      color: shape.isSoft ? (isDark ? '#12141A' : '#FFFFFF') : '#FFFFFF',
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '500' : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      lineHeight: shape.isSoft ? 19 : 21,
    },
  }),
  _colors: colors,
  _isSoft: shape.isSoft,
});

export default Toast;

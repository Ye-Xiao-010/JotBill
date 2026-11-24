/**
 * 模态框组件 - Neo-Brutalism 风格
 * 粗描边容器、实心阴影、粗标题、描边关闭按钮
 */
import React from 'react';
import {
  Modal as RNModal,
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
  Dimensions,
  TouchableWithoutFeedback,
  SafeAreaView,
  KeyboardAvoidingView,
  Platform,
} from 'react-native';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { spacing, borderRadius, borderWidth, shadow } from '../../theme/spacing';
import { useStyles } from '../../hooks';
import { HOGWARTS_FONTS } from '../../constants/hogwartsAssets';

interface ModalProps {
  visible: boolean;
  onClose: () => void;
  title?: string;
  children: React.ReactNode;
  showCloseButton?: boolean;
  closeOnBackdrop?: boolean;
  animationType?: 'slide' | 'fade' | 'none';
  variant?: 'center' | 'bottomSheet';
}

const { width: screenWidth, height: screenHeight } = Dimensions.get('window');

const Modal: React.FC<ModalProps> = ({
  visible,
  onClose,
  title,
  children,
  showCloseButton = true,
  closeOnBackdrop = true,
  animationType = 'slide',
  variant = 'center',
}) => {
  const styles = useStyles(createStyles);
  const isBottomSheet = variant === 'bottomSheet';

  const handleBackdropPress = () => {
    if (closeOnBackdrop) {
      onClose();
    }
  };

  return (
    <RNModal
      visible={visible}
      transparent
      animationType={animationType}
      onRequestClose={onClose}
    >
      <KeyboardAvoidingView
        style={styles.keyboardAvoiding}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <SafeAreaView
          style={[styles.overlay, isBottomSheet && styles.overlayBottom]}
        >
          <TouchableWithoutFeedback onPress={handleBackdropPress}>
            <View style={styles.backdrop} />
          </TouchableWithoutFeedback>

          <View
            style={[
              styles.modalContainer,
              isBottomSheet && styles.bottomSheetContainer,
            ]}
          >
            <View
              style={[styles.modal, isBottomSheet && styles.bottomSheetModal]}
            >
              {isBottomSheet ? <View style={styles.bottomSheetGrabber} /> : null}
              {(title || showCloseButton) && (
                <View style={styles.header}>
                  <View style={styles.titleContainer}>
                    {title && <Text style={styles.title}>{title}</Text>}
                  </View>

                  {showCloseButton && (
                    <TouchableOpacity
                      style={styles.closeButton}
                      onPress={onClose}
                      activeOpacity={0.7}
                      accessibilityLabel="关闭"
                      accessibilityRole="button"
                    >
                      <Text style={styles.closeIcon}>✕</Text>
                    </TouchableOpacity>
                  )}
                </View>
              )}

              <View
                style={[
                  styles.content,
                  isBottomSheet && styles.bottomSheetContent,
                ]}
              >
                {children}
              </View>
            </View>
          </View>
        </SafeAreaView>
      </KeyboardAvoidingView>
    </RNModal>
  );
};

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    keyboardAvoiding: {
      flex: 1,
    },
    overlay: {
      flex: 1,
      justifyContent: 'center',
      alignItems: 'center',
    },
    overlayBottom: {
      justifyContent: 'flex-end',
    },
    backdrop: {
      position: 'absolute',
      top: 0,
      left: 0,
      right: 0,
      bottom: 0,
      backgroundColor: colors.overlay,
    },
    modalContainer: {
      width: screenWidth * 0.9,
      maxWidth: 400,
      maxHeight: screenHeight * 0.8,
    },
    bottomSheetContainer: {
      width: '100%',
      maxWidth: '100%',
      height: screenHeight * 0.9,
      maxHeight: screenHeight * 0.9,
    },
    modal: {
      backgroundColor: colors.surface,
      borderRadius: shape.isSoft ? 24 : shape.isHogwarts ? shape.radius.modal : borderRadius.card,
      borderWidth: shape.isSoft
        ? shape.borderWidth.card
        : shape.isHogwarts
          ? shape.borderWidth.strong
          : borderWidth.thick,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.cardBorder : colors.stroke,
      overflow: 'hidden',
      ...(shape.isSoft || shape.isHogwarts ? shape.elevation.floating : shadow.medium),
    },
    bottomSheetModal: {
      flex: 1,
      borderTopLeftRadius: shape.isSoft || shape.isHogwarts ? shape.radius.sheet : borderRadius.xlarge,
      borderTopRightRadius: shape.isSoft || shape.isHogwarts ? shape.radius.sheet : borderRadius.xlarge,
      borderBottomLeftRadius: 0,
      borderBottomRightRadius: 0,
      borderWidth: shape.isSoft || shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderBottomWidth: 0,
      ...(shape.isSoft || shape.isHogwarts ? shape.elevation.floating : shadow.soft),
    },
    bottomSheetGrabber: {
      width: 40,
      height: shape.isSoft ? 5 : 4,
      borderRadius: borderRadius.round,
      backgroundColor: shape.isSoft ? colors.stroke : colors.divider,
      alignSelf: 'center',
      marginTop: spacing.sm,
    },
    header: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingHorizontal: spacing.lg,
      paddingVertical: spacing.md,
      borderBottomWidth: shape.isSoft ? StyleSheet.hairlineWidth : shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderBottomColor: shape.isSoft || shape.isHogwarts ? colors.divider : colors.stroke,
    },
    titleContainer: {
      flex: 1,
    },
    title: {
      fontSize: shape.isSoft ? 18 : 20,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
    },
    closeButton: {
      width: shape.isSoft ? 36 : 44,
      height: shape.isSoft ? 36 : 44,
      borderRadius: shape.isSoft ? 12 : shape.isHogwarts ? shape.radius.small : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      alignItems: 'center',
      justifyContent: 'center',
      backgroundColor: shape.isSoft ? colors.surfaceMuted : colors.surface,
    },
    closeIcon: {
      fontSize: 15,
      color: shape.isSoft ? colors.textSecondary : colors.textPrimary,
      fontWeight: shape.isSoft ? '600' : '800',
    },
    content: {
      padding: spacing.lg,
      maxHeight: screenHeight * 0.6,
    },
    bottomSheetContent: {
      flex: 1,
    },
  }),
  _colors: colors,
});

export default Modal;

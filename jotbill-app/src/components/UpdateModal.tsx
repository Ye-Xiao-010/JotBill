/**
 * 应用更新弹窗组件 - Neo-Brutalism 风格
 */
import React from 'react';
import {
  Modal,
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
} from 'react-native';
import { AppVersionInfo } from '../services/update/appVersion';
import { ThemeColors } from '../theme/colors';
import { ThemeShape } from '../theme/shape';
import { borderRadius, borderWidth, spacing, shadow } from '../theme/spacing';
import { useStyles } from '../hooks';
import { typography } from '../theme';

interface UpdateModalProps {
  visible: boolean;
  versionInfo: AppVersionInfo | null;
  downloading: boolean;
  progress: number;
  onConfirm: () => void;
  onCancel: () => void;
}

export function UpdateModal({
  visible,
  versionInfo,
  downloading,
  progress,
  onConfirm,
  onCancel,
}: UpdateModalProps) {
  const styles = useStyles(createStyles);

  if (!versionInfo) return null;

  return (
    <Modal
      visible={visible}
      transparent
      animationType="fade"
      onRequestClose={versionInfo.forceUpdate ? undefined : onCancel}
    >
      <View style={styles.overlay}>
        <View style={styles.container}>
          <Text style={styles.title}>发现新版本 v{versionInfo.version}</Text>

          <Text style={styles.updateLog}>{versionInfo.updateLog}</Text>

          {downloading ? (
            <View style={styles.progressContainer}>
              <View style={styles.progressBar}>
                <View style={[styles.progressFill, { width: `${progress}%` }]} />
              </View>
              <Text style={styles.progressText}>下载中 {progress}%</Text>
            </View>
          ) : (
            <View style={styles.buttons}>
              {!versionInfo.forceUpdate && (
                <TouchableOpacity style={styles.cancelBtn} onPress={onCancel}>
                  <Text style={styles.cancelText}>稍后再说</Text>
                </TouchableOpacity>
              )}
              <TouchableOpacity style={styles.confirmBtn} onPress={onConfirm}>
                <Text style={styles.confirmText}>立即更新</Text>
              </TouchableOpacity>
            </View>
          )}
        </View>
      </View>
    </Modal>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    overlay: {
      flex: 1,
      backgroundColor: 'rgba(0,0,0,0.5)',
      justifyContent: 'center',
      alignItems: 'center',
    },
    container: {
      width: '80%',
      backgroundColor: colors.surface,
      borderRadius: shape.radius.modal,
      borderWidth: shape.isSoft ? shape.borderWidth.card : borderWidth.thick,
      borderColor: shape.isSoft ? colors.cardBorder : colors.stroke,
      padding: spacing.xl,
      ...(shape.isSoft ? shape.elevation.floating : shadow.medium),
    },
    title: {
      fontSize: 18,
      fontWeight: shape.isSoft ? '700' : '800',
      color: colors.textPrimary,
      textAlign: 'center',
      marginBottom: spacing.md,
    },
    updateLog: {
      fontSize: 14,
      fontWeight: shape.isSoft ? '500' : '600',
      color: colors.textSecondary,
      lineHeight: 20,
      marginBottom: spacing.lg,
    },
    progressContainer: {
      alignItems: 'center',
    },
    progressBar: {
      width: '100%',
      height: shape.isSoft ? 8 : 10,
      backgroundColor: shape.isSoft ? colors.surfaceMuted : colors.divider,
      borderRadius: shape.isSoft ? 999 : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.stroke,
      overflow: 'hidden',
    },
    progressFill: {
      height: '100%',
      backgroundColor: colors.primary,
      borderRadius: shape.isSoft ? 999 : borderRadius.small,
    },
    progressText: {
      marginTop: spacing.sm,
      fontSize: 14,
      fontWeight: '700',
      fontFamily: shape.isSoft ? undefined : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
      color: colors.textSecondary,
    },
    buttons: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      gap: spacing.md,
    },
    cancelBtn: {
      flex: 1,
      paddingVertical: spacing.md,
      borderRadius: shape.radius.button,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: shape.isSoft ? colors.surfaceMuted : colors.accent,
      alignItems: 'center',
    },
    cancelText: {
      fontSize: 15,
      fontWeight: '700',
      color: colors.textSecondary,
    },
    confirmBtn: {
      flex: 1,
      paddingVertical: spacing.md,
      borderRadius: shape.radius.button,
      borderWidth: shape.isSoft ? 0 : borderWidth.medium,
      borderColor: colors.stroke,
      backgroundColor: colors.primary,
      alignItems: 'center',
      ...(shape.isSoft ? shape.elevation.none : shadow.small),
    },
    confirmText: {
      fontSize: 15,
      fontWeight: '700',
      color: shape.isSoft ? colors.onPrimary : '#FFFFFF',
    },
  }),
  _colors: colors,
});

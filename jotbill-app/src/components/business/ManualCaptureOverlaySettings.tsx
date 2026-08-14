import React, { useCallback, useRef, useState } from 'react';
import {
  AppState,
  StyleSheet,
  Switch,
  Text,
  View,
  type AppStateStatus,
  type StyleProp,
  type ViewStyle,
} from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import { Button, SegmentedControl } from '../ui';
import { useStyles } from '../../hooks';
import { useAlert } from '../../providers';
import { borderRadius, borderWidth, spacing } from '../../theme';
import type { ThemeColors } from '../../theme/colors';
import {
  addCheckoutCaptureOverlayStateListener,
  nativeOverlay,
  type CheckoutOverlayMode,
  type CheckoutOverlayState,
  type CheckoutOverlayStatus,
} from '../../native/overlay';

type OverlayPermissionStatus = 'authorized' | 'denied' | 'unknown';

interface ManualCaptureOverlaySettingsProps {
  style?: StyleProp<ViewStyle>;
}

interface BridgeReadResult<T> {
  value: T | null;
  failed: boolean;
}

const DEFAULT_MODE: CheckoutOverlayMode = 'checkout_only';

const INITIAL_STATE: CheckoutOverlayState = {
  status: 'SERVICE_NOT_CONNECTED',
  visible: false,
  enabled: false,
  mode: DEFAULT_MODE,
  reasonCode: 'SERVICE_NOT_CONNECTED',
  requiresDisambiguation: false,
};

const MODE_OPTIONS = [
  { key: 'always_on', label: '始终显示' },
  { key: 'checkout_only', label: '仅结算页' },
  { key: 'off', label: '关闭' },
];

const STATUS_LABELS: Record<CheckoutOverlayStatus, string> = {
  HIDDEN: '未显示',
  READY: '可截图',
  CAPTURING: '处理中',
  CAPTURED_WAITING_PAYMENT: '已捕获，等待支付',
  EXPIRED_RETRY: '已过期，可重试',
  SENSITIVE: '敏感页面，已隐藏',
  UNAVAILABLE: '暂不可用，可重试',
  SERVICE_NOT_CONNECTED: '服务未连接',
};

function isCheckoutOverlayMode(value: string): value is CheckoutOverlayMode {
  return value === 'always_on' || value === 'checkout_only' || value === 'off';
}

function normalizeMode(value: string | null | undefined): CheckoutOverlayMode {
  const normalizedValue = value ?? '';
  return isCheckoutOverlayMode(normalizedValue) ? normalizedValue : DEFAULT_MODE;
}

function normalizePermission(value: string | null | undefined): OverlayPermissionStatus {
  if (value === 'authorized') {
    return 'authorized';
  }
  if (value === 'denied') {
    return 'denied';
  }
  return 'unknown';
}

function normalizeStatus(value: string | null | undefined): CheckoutOverlayStatus {
  switch (value) {
    case 'HIDDEN':
    case 'READY':
    case 'CAPTURING':
    case 'CAPTURED_WAITING_PAYMENT':
    case 'EXPIRED_RETRY':
    case 'SENSITIVE':
    case 'UNAVAILABLE':
    case 'SERVICE_NOT_CONNECTED':
      return value;
    default:
      return 'UNAVAILABLE';
  }
}

function normalizeState(state: CheckoutOverlayState): CheckoutOverlayState {
  return {
    ...state,
    status: normalizeStatus(state.status),
    visible: state.visible === true,
    enabled: state.enabled === true,
    mode: normalizeMode(state.mode),
    requiresDisambiguation: state.requiresDisambiguation === true,
  };
}

async function readBridgeValue<T>(reader: () => Promise<T>): Promise<BridgeReadResult<T>> {
  try {
    return { value: await reader(), failed: false };
  } catch {
    return { value: null, failed: true };
  }
}

function getStatusColor(status: CheckoutOverlayStatus, colors: ThemeColors): string {
  switch (status) {
    case 'READY':
    case 'CAPTURED_WAITING_PAYMENT':
      return colors.success;
    case 'CAPTURING':
      return colors.primary;
    case 'EXPIRED_RETRY':
    case 'UNAVAILABLE':
      return colors.warning;
    case 'SENSITIVE':
    case 'SERVICE_NOT_CONNECTED':
      return colors.error;
    default:
      return colors.divider;
  }
}

export const ManualCaptureOverlaySettings: React.FC<ManualCaptureOverlaySettingsProps> = ({
  style,
}) => {
  const styles = useStyles(createStyles);
  const { alert } = useAlert();
  const [permission, setPermission] = useState<OverlayPermissionStatus>('unknown');
  const [mode, setMode] = useState<CheckoutOverlayMode>(DEFAULT_MODE);
  const [enabled, setEnabled] = useState(false);
  const [overlayState, setOverlayState] = useState<CheckoutOverlayState>(INITIAL_STATE);
  const [loading, setLoading] = useState(true);
  const [updating, setUpdating] = useState(false);
  const isFocusedRef = useRef(false);
  const refreshTokenRef = useRef(0);
  const bridgeErrorShownRef = useRef(false);

  const showBridgeError = useCallback(
    (message: string, force = false) => {
      if (!isFocusedRef.current || (!force && bridgeErrorShownRef.current)) {
        return;
      }
      bridgeErrorShownRef.current = true;
      alert('悬浮截图设置不可用', message);
    },
    [alert],
  );

  const refreshState = useCallback(async () => {
    if (!isFocusedRef.current) {
      return;
    }

    const refreshToken = refreshTokenRef.current + 1;
    refreshTokenRef.current = refreshToken;
    setLoading(true);

    const [permissionResult, modeResult, stateResult] = await Promise.all([
      readBridgeValue(() => nativeOverlay.getOverlayPermissionStatus()),
      readBridgeValue(() => nativeOverlay.getManualCaptureButtonMode()),
      readBridgeValue(() => nativeOverlay.getManualCaptureButtonState()),
    ]);

    const isCurrent =
      isFocusedRef.current && refreshTokenRef.current === refreshToken;
    if (!isCurrent) {
      return;
    }

    const hasValue =
      permissionResult.value !== null ||
      modeResult.value !== null ||
      stateResult.value !== null;

    if (permissionResult.value !== null) {
      setPermission(normalizePermission(permissionResult.value));
    }
    if (modeResult.value !== null) {
      setMode(normalizeMode(modeResult.value));
    }
    if (stateResult.value !== null) {
      const nextState = normalizeState(stateResult.value);
      setOverlayState(nextState);
      setEnabled(nextState.enabled);
      setMode(nextState.mode);
    }

    const hasFailure =
      permissionResult.failed || modeResult.failed || stateResult.failed;
    if (!hasValue) {
      showBridgeError('无法读取悬浮截图状态，请确认原生模块已加载。');
    } else if (hasFailure) {
      showBridgeError('部分悬浮截图设置读取失败，请稍后刷新。');
    } else {
      bridgeErrorShownRef.current = false;
    }
    setLoading(false);
  }, [showBridgeError]);

  const handleOverlayState = useCallback(
    (nextState: CheckoutOverlayState) => {
      if (!isFocusedRef.current) {
        return;
      }
      if (!nextState || typeof nextState !== 'object') {
        showBridgeError('收到的悬浮截图状态无效。');
        return;
      }
      const normalizedState = normalizeState(nextState);
      setOverlayState(normalizedState);
      setEnabled(normalizedState.enabled);
      setMode(normalizedState.mode);
    },
    [showBridgeError],
  );

  // 焦点期间订阅原生状态，失焦立即解除监听并使晚到的读取结果失效。
  useFocusEffect(
    useCallback(() => {
      isFocusedRef.current = true;
      bridgeErrorShownRef.current = false;

      let stateSubscription: { remove: () => void } | undefined;
      try {
        stateSubscription = addCheckoutCaptureOverlayStateListener(handleOverlayState);
      } catch {
        showBridgeError('无法订阅悬浮截图状态，请稍后重试。');
      }

      const appStateSubscription = AppState.addEventListener(
        'change',
        (nextAppState: AppStateStatus) => {
          if (nextAppState === 'active') {
            void refreshState();
          }
        },
      );

      void refreshState();

      return () => {
        isFocusedRef.current = false;
        refreshTokenRef.current += 1;
        setUpdating(false);
        stateSubscription?.remove();
        appStateSubscription.remove();
      };
    }, [handleOverlayState, refreshState, showBridgeError]),
  );

  const openOverlayPermissionSettings = useCallback(async () => {
    try {
      await nativeOverlay.requestOverlayPermission();
      await refreshState();
    } catch {
      showBridgeError('无法打开悬浮窗权限设置，请稍后重试。', true);
    }
  }, [refreshState, showBridgeError]);

  const promptOverlayPermission = useCallback(() => {
    alert('需要悬浮窗权限', '开启手动截图悬浮按钮前，请先授权悬浮窗权限。', [
      { text: '取消', style: 'cancel' },
      {
        text: '去授权',
        style: 'default',
        onPress: () => {
          void openOverlayPermissionSettings();
        },
      },
    ]);
  }, [alert, openOverlayPermissionSettings]);

  const handlePermissionAction = useCallback(() => {
    if (permission === 'authorized') {
      void refreshState();
      return;
    }
    promptOverlayPermission();
  }, [permission, promptOverlayPermission, refreshState]);

  const handleEnabledChange = useCallback(
    async (nextEnabled: boolean) => {
      if (nextEnabled && permission !== 'authorized') {
        promptOverlayPermission();
        return;
      }

      setUpdating(true);
      try {
        await nativeOverlay.setManualCaptureButtonEnabled(nextEnabled);
        if (isFocusedRef.current) {
          setEnabled(nextEnabled);
        }
        await refreshState();
      } catch {
        showBridgeError(
          nextEnabled
            ? '无法开启手动截图悬浮按钮，请检查悬浮窗权限。'
            : '无法关闭手动截图悬浮按钮，请稍后重试。',
          true,
        );
      } finally {
        if (isFocusedRef.current) {
          setUpdating(false);
        }
      }
    },
    [permission, promptOverlayPermission, refreshState, showBridgeError],
  );

  const handleModeChange = useCallback(
    async (selectedKey: string) => {
      if (!isCheckoutOverlayMode(selectedKey)) {
        return;
      }

      setUpdating(true);
      try {
        await nativeOverlay.setManualCaptureButtonMode(selectedKey);
        if (isFocusedRef.current) {
          setMode(selectedKey);
        }
        await refreshState();
      } catch {
        showBridgeError('无法更新悬浮截图显示方式，请稍后重试。', true);
      } finally {
        if (isFocusedRef.current) {
          setUpdating(false);
        }
      }
    },
    [refreshState, showBridgeError],
  );

  const statusColor = getStatusColor(overlayState.status, styles._colors);

  return (
    <View style={[styles.container, style]}>
      <View style={styles.header}>
        <Text style={styles.title}>手动截图悬浮按钮</Text>
        <Text style={styles.description}>
          仅保存到本机内存，购买意图触发后才会交给后续识别流程
        </Text>
      </View>

      <View style={styles.settingRow}>
        <View style={styles.settingInfo}>
          <Text style={styles.settingLabel}>启用悬浮按钮</Text>
          <Text style={styles.settingHint}>关闭后立即隐藏按钮，保留已选显示方式</Text>
        </View>
        <Switch
          value={enabled}
          onValueChange={(value) => {
            void handleEnabledChange(value);
          }}
          disabled={loading || updating}
          trackColor={{ false: styles._colors.divider, true: styles._colors.primary }}
          thumbColor="#FFFFFF"
        />
      </View>

      <View style={styles.modeSection}>
        <Text style={styles.settingLabel}>显示方式</Text>
        <SegmentedControl
          options={MODE_OPTIONS}
          selectedKey={mode}
          onSelect={(key) => {
            void handleModeChange(key);
          }}
          style={styles.segmentedControl}
        />
      </View>

      <View style={styles.permissionRow}>
        <View style={styles.settingInfo}>
          <Text style={styles.settingLabel}>悬浮窗权限</Text>
          <Text style={styles.settingHint}>
            {permission === 'authorized' ? '已授权' : '未授权，开启按钮前需要授权'}
          </Text>
        </View>
        <Button
          title={permission === 'authorized' ? '刷新' : '去授权'}
          onPress={handlePermissionAction}
          variant="outline"
          size="small"
          disabled={loading || updating}
          style={styles.permissionButton}
        />
      </View>

      <View style={styles.statusRow}>
        <Text style={styles.settingLabel}>当前状态</Text>
        {loading ? (
          <Text style={styles.statusText}>正在读取</Text>
        ) : (
          <View style={[styles.statusBadge, { backgroundColor: statusColor }]}>
            <Text style={styles.statusText}>{STATUS_LABELS[overlayState.status]}</Text>
          </View>
        )}
      </View>
    </View>
  );
};

const createStyles = (colors: ThemeColors) => ({
  ...StyleSheet.create({
    container: {
      backgroundColor: colors.surface,
      borderRadius: borderRadius.card,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      padding: spacing.lg,
    },
    header: {
      marginBottom: spacing.md,
    },
    title: {
      fontSize: 18,
      fontWeight: '800',
      color: colors.textPrimary,
      marginBottom: spacing.xs,
    },
    description: {
      fontSize: 13,
      fontWeight: '500',
      color: colors.textSecondary,
      lineHeight: 20,
    },
    settingRow: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingVertical: spacing.sm,
    },
    settingInfo: {
      flex: 1,
      marginRight: spacing.md,
    },
    settingLabel: {
      fontSize: 15,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    settingHint: {
      marginTop: spacing.xs,
      fontSize: 12,
      fontWeight: '500',
      color: colors.textSecondary,
      lineHeight: 18,
    },
    modeSection: {
      borderTopWidth: borderWidth.thin,
      borderTopColor: colors.stroke,
      paddingTop: spacing.md,
      marginTop: spacing.sm,
    },
    segmentedControl: {
      marginTop: spacing.sm,
    },
    permissionRow: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      borderTopWidth: borderWidth.thin,
      borderTopColor: colors.stroke,
      paddingTop: spacing.md,
      marginTop: spacing.md,
    },
    permissionButton: {
      minWidth: 96,
    },
    statusRow: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      borderTopWidth: borderWidth.thin,
      borderTopColor: colors.stroke,
      paddingTop: spacing.md,
      marginTop: spacing.md,
    },
    statusBadge: {
      maxWidth: '68%',
      paddingHorizontal: spacing.sm,
      paddingVertical: spacing.xs,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
    },
    statusText: {
      fontSize: 13,
      fontWeight: '700',
      color: colors.textPrimary,
      textAlign: 'right',
    },
  }),
  _colors: colors,
});

export default ManualCaptureOverlaySettings;

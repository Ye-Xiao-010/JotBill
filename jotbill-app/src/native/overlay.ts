import { NativeEventEmitter, NativeModules } from 'react-native';

export type CheckoutOverlayMode = 'always_on' | 'checkout_only' | 'off';

export type CheckoutOverlayStatus =
  | 'HIDDEN'
  | 'READY'
  | 'CAPTURING'
  | 'CAPTURED_WAITING_PAYMENT'
  | 'EXPIRED_RETRY'
  | 'SENSITIVE'
  | 'UNAVAILABLE'
  | 'SERVICE_NOT_CONNECTED';

export interface CheckoutOverlayState {
  status: CheckoutOverlayStatus;
  visible: boolean;
  enabled: boolean;
  mode: CheckoutOverlayMode;
  reasonCode?: string | null;
  shoppingPackage?: string | null;
  pageFingerprint?: string | null;
  sessionId?: string | null;
  snapshotId?: string | null;
  candidateRevision?: number | null;
  requiresDisambiguation: boolean;
}

export interface SnapshotCaptureResult {
  status:
    | 'STORED'
    | 'EXPIRED'
    | 'REJECTED'
    | 'UNAVAILABLE'
    | 'DUPLICATE'
    | 'CANCELLED'
    | 'FAILED';
  sourceReason: 'CHECKOUT_STABLE' | 'MANUAL_CAPTURE';
  reasonCode: string;
  sessionId?: string | null;
  slotId?: string | null;
  shoppingPackage?: string | null;
  pageFingerprint?: string | null;
  snapshotId?: string | null;
  candidateRevision?: number | null;
  requiresDisambiguation: boolean;
}

interface RawOverlayModule {
  getOverlayPermissionStatus(): Promise<'authorized' | 'denied'>;
  requestOverlayPermission(): Promise<void>;
  setManualCaptureButtonEnabled(enabled: boolean): Promise<void>;
  setManualCaptureButtonMode(mode: CheckoutOverlayMode): Promise<void>;
  getManualCaptureButtonMode(): Promise<CheckoutOverlayMode>;
  getManualCaptureButtonState(): Promise<CheckoutOverlayState>;
  requestManualCapture(): Promise<SnapshotCaptureResult>;
  setPendingConfirmPopupEnabled(enabled: boolean): Promise<void>;
  getPendingConfirmPopupEnabled(): Promise<boolean>;
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

export const CHECKOUT_CAPTURE_OVERLAY_STATE_EVENT =
  'onCheckoutCaptureOverlayStateChanged';

function getRawOverlayModule(): RawOverlayModule {
  const module = NativeModules.Overlay as RawOverlayModule | undefined;
  if (!module) {
    throw new Error('Native Overlay module is unavailable on this platform');
  }
  return module;
}

export const nativeOverlay = {
  async getOverlayPermissionStatus(): Promise<'authorized' | 'denied'> {
    return getRawOverlayModule().getOverlayPermissionStatus();
  },

  async requestOverlayPermission(): Promise<void> {
    await getRawOverlayModule().requestOverlayPermission();
  },

  async setManualCaptureButtonEnabled(enabled: boolean): Promise<void> {
    await getRawOverlayModule().setManualCaptureButtonEnabled(enabled);
  },

  async setManualCaptureButtonMode(mode: CheckoutOverlayMode): Promise<void> {
    await getRawOverlayModule().setManualCaptureButtonMode(mode);
  },

  async getManualCaptureButtonMode(): Promise<CheckoutOverlayMode> {
    return getRawOverlayModule().getManualCaptureButtonMode();
  },

  async getManualCaptureButtonState(): Promise<CheckoutOverlayState> {
    return getRawOverlayModule().getManualCaptureButtonState();
  },

  async requestManualCapture(): Promise<SnapshotCaptureResult> {
    return getRawOverlayModule().requestManualCapture();
  },

  async setPendingConfirmPopupEnabled(enabled: boolean): Promise<void> {
    await getRawOverlayModule().setPendingConfirmPopupEnabled(enabled);
  },

  async getPendingConfirmPopupEnabled(): Promise<boolean> {
    return getRawOverlayModule().getPendingConfirmPopupEnabled();
  },
};

export function addCheckoutCaptureOverlayStateListener(
  listener: (state: CheckoutOverlayState) => void,
): { remove: () => void } {
  const emitter = new NativeEventEmitter(getRawOverlayModule() as never);
  return emitter.addListener(CHECKOUT_CAPTURE_OVERLAY_STATE_EVENT, listener);
}

export type NativeOverlay = typeof nativeOverlay;

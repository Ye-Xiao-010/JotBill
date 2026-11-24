/**
 * 柔光主题轻量触感反馈封装
 *
 * 通过原生 View.performHapticFeedback 调用系统线性马达，无需 VIBRATE 权限，
 * 且自动遵循系统触感开关。非 Android 或测试环境安全降级为空操作。
 */
import { nativeTheme, type SoftHapticKind } from '../native/theme';

export function triggerSoftHaptic(kind: SoftHapticKind = 'light'): void {
  nativeTheme.performHaptic(kind);
}

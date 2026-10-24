/**
 * 平台/来源的展示名映射（2026-10-24）。
 *
 * 背景：账单与待确认的 `platform` 字段存的是**支付包名**（孤儿路径，
 * `recordOrphanPaymentSignal` 写 `com.tencent.mm`）或**购物平台名**（会话路径，
 * 如“拼多多”）。规则引擎的 `platform_equals` 期望包名（RulesScreen 的提示也是
 * “如 com.tencent.mm”），因此存储层保持包名语义不改；**只在展示层**把已知包名
 * 映射为用户可读名称。
 */
const PLATFORM_LABELS: Record<string, string> = {
  'com.tencent.mm': '微信支付',
  'com.eg.android.AlipayGphone': '支付宝',
};

/** 把平台字段转为展示名；未知包名/平台名原样返回，空值返回空串。 */
export function formatPlatform(platform?: string | null): string {
  if (!platform) {
    return '';
  }
  return PLATFORM_LABELS[platform] ?? platform;
}

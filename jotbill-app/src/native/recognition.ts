import { NativeModules } from 'react-native';

/** 结算会话状态机；与 Native.d.ts 第七部分保持一致。 */
export type SessionStatus =
  | 'OBSERVING'
  | 'SNAPSHOT_BUFFERED'
  | 'AI_REQUESTING'
  | 'WAITING_ENRICHMENT'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
  | 'EXPIRED'
  // 兼容识别链路内部状态机写入的语义化新值，前端需容忍未知状态。
  | 'AI_PENDING'
  | 'AI_TRIGGER_ARMED'
  | 'AI_COMPLETED'
  | 'AI_FAILED'
  | 'WAITING_PAYMENT'
  | 'PAYMENT_CONFIRMED'
  | 'AUTO_COMMITTED'
  | 'NEEDS_CONFIRMATION'
  | 'DISMISSED';

/** 页面角色；PAYMENT_HANDOFF 在 RN 契约中以 PAYMENT_APP 暴露。 */
export type PageRole =
  | 'UNKNOWN'
  | 'SHOPPING_HOME'
  | 'PRODUCT_DETAIL'
  | 'CART'
  | 'CHECKOUT'
  | 'PAYMENT_RESULT'
  | 'PAYMENT_APP';

/** 截图来源；用于 SnapshotCaptureResult 与状态事件。 */
export type SnapshotSourceReason = 'CHECKOUT_STABLE' | 'MANUAL_CAPTURE';

/** 支付证据来源；通知、无障碍节点或手动触发。 */
export type EvidenceSourceType = 'notification' | 'accessibility' | 'manual';

/** 结算会话；时间戳均为毫秒。 */
export interface CheckoutSession {
  sessionId: string;
  status: SessionStatus | string;
  shoppingPackage?: string;
  pageRole?: PageRole | string;
  pageFingerprint?: string;
  snapshotId?: string;
  checkoutRequestKey?: string;
  aiRequestId?: string;
  candidateId?: string;
  paymentEvidenceIds: string[];
  createdAt: number;
  updatedAt: number;
  expiresAt: number;
}

/** 交易候选；保留 AI 字段、证据字段和匹配状态。 */
export interface TransactionCandidate {
  candidateId: string;
  sessionId: string;
  amountMinor?: number;
  platform?: string;
  merchant?: string;
  productDescription?: string;
  evidenceAmountMinor?: number;
  evidenceMerchant?: string;
  evidenceOrderId?: string;
  matched: boolean;
  conflictReason?: string;
  appliedRules?: string[];
  createdAt: number;
  version: number;
}

/** 支付证据；occurredAt 为 ISO-8601，其余时间戳为毫秒。 */
export interface PaymentEvidence {
  evidenceId: string;
  sessionId: string;
  sourceType: EvidenceSourceType | string;
  amountMinor?: number;
  merchant?: string;
  platform?: string;
  orderId?: string;
  paymentMethod?: string;
  paymentStatus?: string;
  occurredAt?: string;
  rawTextHash?: string;
  capturedAt: number;
}

/** 识别链路整体状态；用于设置页或首页提示。 */
export interface RecognitionStatus {
  activeSessions: number;
  pendingCount: number;
  aiRequesting: boolean;
}

/** 会话查询过滤；不传 status 时返回所有活跃会话。 */
export interface CheckoutSessionFilter {
  status?: SessionStatus[];
}

interface RawRecognitionModule {
  getCheckoutSessions(filterJson?: string | null): Promise<string>;
  getCheckoutSession(sessionId: string): Promise<string>;
  getTransactionCandidate(candidateId: string): Promise<string>;
  getPaymentEvidence(sessionId: string): Promise<string>;
  retryAiRecognition(candidateId: string): Promise<null>;
  getRecognitionStatus(): Promise<string>;
  openAccessibilitySettings(): Promise<null>;
  isAccessibilityEnabled(): Promise<string>;
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

function getRawRecognitionModule(): RawRecognitionModule {
  const module = NativeModules.Recognition as RawRecognitionModule | undefined;
  if (!module) {
    throw new Error('Native Recognition module is unavailable on this platform');
  }
  return module;
}

function parseJson<T>(value: string, operation: string): T {
  try {
    return JSON.parse(value) as T;
  } catch (error) {
    throw new Error(`Native Recognition.${operation} returned invalid JSON`);
  }
}

/** 识别链路桥接封装；不暴露图片或原文，仅提供查询和重试入口。 */
export const nativeRecognition = {
  async getCheckoutSessions(
    filter: CheckoutSessionFilter = {},
  ): Promise<CheckoutSession[]> {
    const raw = await getRawRecognitionModule().getCheckoutSessions(
      JSON.stringify(filter),
    );
    return parseJson<CheckoutSession[]>(raw, 'getCheckoutSessions');
  },

  async getCheckoutSession(sessionId: string): Promise<CheckoutSession> {
    const raw = await getRawRecognitionModule().getCheckoutSession(sessionId);
    return parseJson<CheckoutSession>(raw, 'getCheckoutSession');
  },

  async getTransactionCandidate(
    candidateId: string,
  ): Promise<TransactionCandidate> {
    const raw = await getRawRecognitionModule().getTransactionCandidate(
      candidateId,
    );
    return parseJson<TransactionCandidate>(raw, 'getTransactionCandidate');
  },

  async getPaymentEvidence(sessionId: string): Promise<PaymentEvidence[]> {
    const raw = await getRawRecognitionModule().getPaymentEvidence(sessionId);
    return parseJson<PaymentEvidence[]>(raw, 'getPaymentEvidence');
  },

  async retryAiRecognition(candidateId: string): Promise<void> {
    await getRawRecognitionModule().retryAiRecognition(candidateId);
  },

  async getRecognitionStatus(): Promise<RecognitionStatus> {
    const raw = await getRawRecognitionModule().getRecognitionStatus();
    return parseJson<RecognitionStatus>(raw, 'getRecognitionStatus');
  },

  async openAccessibilitySettings(): Promise<void> {
    await getRawRecognitionModule().openAccessibilitySettings();
  },

  async isAccessibilityEnabled(): Promise<boolean> {
    const raw = await getRawRecognitionModule().isAccessibilityEnabled();
    const parsed = parseJson<{ enabled: boolean }>(raw, 'isAccessibilityEnabled');
    return parsed.enabled === true;
  },
};

export type NativeRecognition = typeof nativeRecognition;

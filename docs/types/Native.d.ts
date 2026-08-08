/**
 * JotBill Native Module 类型定义
 *
 * 本文件定义 RN 前端与 Android 原生层之间的所有接口契约,是相关 TDD 和启动文档的唯一边界。
 * 前端可基于此类型定义并行开发,无需等待原生实现完成。
 *
 * 基础来源:LiteNote (MIT) src/types/ + 设计文档 NEW_PROJECT_KICKOFF_V2.md 第16节
 *
 * 与 LiteNote 的主要差异:
 * - 去除 userId 字段(单账本,无账号体系)
 * - 账户扩展为 initialBalance + currentBalance(流派B)
 * - 新增 CheckoutSession / TransactionCandidate / AiRecognitionResult 等识别链路类型
 * - 新增 NetWorthSummary / BalanceCalibration 等资产管理类型
 * - Bill 扩展为七种交易类型(含转账/退款/还款/手续费/不计收支)
 */

// ============================================================================
// 第一部分:通用类型
// ============================================================================

/**
 * 通用 API 响应(原生层统一返回格式)
 */
export interface NativeResponse<T> {
  success: boolean;
  data: T;
  error?: NativeError;
}

/**
 * 原生错误码(对应设计文档 13.1 节)
 */
export type NativeErrorCode =
  | 'INVALID_ARGUMENT'     // 参数格式或取值无效
  | 'NOT_FOUND'             // 本地记录不存在
  | 'VERSION_CONFLICT'      // 乐观锁版本冲突
  | 'DATA_CONFLICT'         // 唯一约束或外键约束冲突
  | 'DB_ERROR'              // 本地数据库错误
  | 'NOT_IMPLEMENTED'       // 当前阶段尚未开放的能力
  | 'AI_CONFIG_ERROR'       // AI 配置错误
  | 'AI_CONFIG_MISSING'     // 未配置可用的 AI 服务商
  | 'AI_VISION_UNSUPPORTED' // 当前服务商不支持图片输入
  | 'AI_ASR_ERROR'          // 语音转写失败
  | 'RUNTIME_STATUS_ERROR'  // 运行状态读取失败
  | 'UNSUPPORTED_FORMAT'    // 不支持的导出格式
  | 'EXPORT_IN_PROGRESS'    // 已有导出任务等待文件位置
  | 'EXPORT_CANCELLED'      // 用户取消导出
  | 'EXPORT_ERROR'          // 导出失败
  | 'CLEAR_DATA_ERROR'      // 清除本地数据失败
  | 'AI_TIMEOUT'          // AI 识别超时
  | 'AI_AUTH'             // API Key 无效或模型下线
  | 'AI_PARSE'            // AI 返回格式无法解析
  | 'AI_BUDGET_EXCEEDED'  // 月度预算已耗尽
  | 'AI_RATE_LIMIT'       // 触发频率限制
  | 'SCREENSHOT_SECURE'   // 页面 FLAG_SECURE 无法截图
  | 'SCREENSHOT_FAIL'     // 截图技术失败
  | 'NO_CHECKOUT_PAGE'    // 未检测到结算页
  | 'NO_PAYMENT_EVIDENCE' // 缺少支付证据
  | 'SESSION_EXPIRED'     // 支付会话已过期
  | 'SESSION_CONFLICT'    // 支付会话冲突
  | 'DUPLICATE_TRANSACTION' // 重复交易
  | 'DB_MIGRATION_FAILED' // 数据库迁移失败
  | 'PERMISSION_DENIED'   // 权限被拒绝
  | 'SERVICE_UNAVAILABLE' // 服务不可用
  | 'UNKNOWN';            // 未知错误

export interface NativeError {
  code: NativeErrorCode;
  message: string;
  retryable: boolean;
}

/**
 * 分页响应
 */
export interface PaginatedResponse<T> {
  items: T[];
  total: number;
  page: number;
  limit: number;
  hasMore: boolean;
}

// ============================================================================
// 第二部分:账单与交易类型(对应设计文档第5节)
// ============================================================================

/**
 * 七种交易类型(对应设计文档 5.2 节)
 * LiteNote 原只有 'income' | 'expense',扩展为七种
 */
export type TransactionType =
  | 'income'        // 收入
  | 'expense'       // 支出
  | 'transfer'      // 转账(不计收支)
  | 'refund'        // 退款(冲减原支出)
  | 'repayment'     // 还款(不计收支,负债账户减少)
  | 'fee'           // 手续费(单独记支出)
  | 'adjustment';   // 不计收支(押金/余额校准等)

/**
 * 交易来源类型(对应设计文档 10.4 节)
 */
export type TransactionSource =
  | 'ai_vision'           // AI 视觉识别
  | 'notification'        // 微信/支付宝通知
  | 'accessibility'       // 无障碍节点
  | 'sms'                 // 银行短信(MVP 不启用)
  | 'import'              // 账单文件导入
  | 'manual'              // 手动记账
  | 'ai_chat';            // AI 对话记账

/**
 * 账单数据(扩展自 LiteNote BillData)
 *
 * 主要扩展:
 * - type 从 'income'|'expense' 扩展为七种
 * - 新增 platform/merchant/productDescription(五维度区分)
 * - 新增 sourceType/originalTransactionId(来源与退款关联)
 * - 新增 accountId/targetAccountId(资金账户)
 * - 金额使用 Long(分),避免浮点
 */
export interface Bill {
  id: string;
  amountMinor: number;          // 金额(分),Long
  type: TransactionType;
  occurredAt: string;           // 发生时间(ISO,跨午夜以此为准)
  description?: string;
  categoryId?: string;
  accountId?: string;           // 资金账户
  targetAccountId?: string;     // 转账目标账户(仅 transfer/repayment)
  // 五维度:支付入口由 accountId 推断,以下为消费平台/商户/商品
  platform?: string;            // 消费平台(京东/美团/淘宝)
  merchant?: string;            // 商户(场景相关字段,非必填)
  productDescription?: string;  // 商品说明(电商场景比商户更有意义)
  // 来源与关联
  sourceType: TransactionSource;
  originalTransactionId?: string; // 退款关联的原交易 ID
  orderId?: string;             // 订单号(去重关键)
  // 元数据
  createdAt: string;
  updatedAt: string;
  version: number;              // 乐观锁版本号
  // 关联对象(查询时可展开)
  category?: Category;
  account?: Account;
  targetAccount?: Account;
}

/**
 * 创建账单输入
 */
export interface BillInput {
  amountMinor: number;
  type: TransactionType;
  occurredAt: string;
  description?: string | null;
  categoryId?: string;
  accountId?: string;
  targetAccountId?: string;
  platform?: string;
  merchant?: string;
  productDescription?: string;
  sourceType: TransactionSource;
  orderId?: string;
  originalTransactionId?: string;
}

/**
 * 更新账单输入(全部可选,含 version 用于乐观锁)
 */
export interface BillUpdateInput extends Partial<BillInput> {
  version: number;  // 必须提供当前版本号
}

/**
 * 账单查询过滤
 */
export interface BillFilter {
  page?: number;
  limit?: number;
  type?: TransactionType;
  categoryId?: string;
  accountId?: string;
  platform?: string;
  merchant?: string;
  startDate?: string;          // ISO 日期
  endDate?: string;
  startDateMinor?: number;     // 时间戳(分),用于精确范围
  endDateMinor?: number;
  orderBy?: 'date' | 'amount' | 'createdAt';
  orderDirection?: 'asc' | 'desc';
  sourceType?: TransactionSource;
  keyword?: string;            // 描述/商户/商品模糊搜索
}

/**
 * 账单统计(对应 LiteNote BillStatistics,扩展七类型)
 */
export interface BillStatistics {
  totalIncome: number;
  totalExpense: number;
  balance: number;
  incomeCount: number;
  expenseCount: number;
  // 分类统计
  expenseCategoryStats: CategoryStatistic[];
  incomeCategoryStats: CategoryStatistic[];
  // 趋势
  monthlyTrends: MonthlyTrend[];
  dailyTrends: DailyTrend[];
  // 平台统计(新增)
  platformStats: PlatformStatistic[];
}

export interface CategoryStatistic {
  categoryId: string;
  categoryName: string;
  categoryIcon?: string;
  amountMinor: number;
  percentage: number;
  count: number;
}

export interface PlatformStatistic {
  platform: string;
  amountMinor: number;
  percentage: number;
  count: number;
}

export interface MonthlyTrend {
  month: string;
  year: number;
  income: number;
  expense: number;
}

export interface DailyTrend {
  date: string;
  income: number;
  expense: number;
}

// ============================================================================
// 第三部分:账户与资产类型(流派B,对应设计文档 5.3 节)
// ============================================================================

/**
 * 账户类型(LiteNote 原为 bank_card|e_wallet|credit_card|cash,扩展为6种+资产/负债区分)
 */
export type AccountSubtype =
  | 'cash'           // 现金(资产)
  | 'e_wallet'       // 电子钱包(资产)
  | 'bank_debit'     // 银行借记(资产)
  | 'credit_card'    // 信用卡(负债)
  | 'consumer_loan'  // 消费信贷(负债,花呗/白条)
  | 'other';

export type AccountKind = 'asset' | 'liability';

/**
 * 账户数据(扩展自 LiteNote AccountData)
 *
 * 主要扩展:
 * - balance 拆分为 initialBalance + currentBalance
 * - 新增 subtype/kind/isActive
 * - 信用卡不配置账单日/还款日/额度
 */
export interface Account {
  id: string;
  name: string;
  subtype: AccountSubtype;
  kind: AccountKind;             // 资产/负债(由 subtype 推断,显式存储)
  initialBalanceMinor: number;   // 期初余额(分),用户设定
  currentBalanceMinor: number;   // 当前余额(分),系统维护
  currency: string;              // 默认 CNY
  icon?: string;
  color?: string;
  sortOrder: number;
  isActive: boolean;             // 启用/停用
  createdAt: string;
  updatedAt: string;
}

export interface AccountInput {
  name: string;
  subtype: AccountSubtype;
  kind?: AccountKind;
  initialBalanceMinor?: number;
  currency?: string;
  icon?: string;
  color?: string;
  sortOrder?: number;
}

export interface AccountUpdateInput extends Partial<AccountInput> {
  isActive?: boolean;
}

/**
 * 余额校准记录
 */
export interface BalanceCalibration {
  id: string;
  accountId: string;
  beforeBalanceMinor: number;
  afterBalanceMinor: number;
  diffMinor: number;
  note?: string;
  createdAt: string;
}

/**
 * 净资产汇总(对应设计文档 5.3 节净资产视图)
 */
export interface NetWorthSummary {
  totalAssetsMinor: number;      // 资产账户余额之和
  totalLiabilitiesMinor: number; // 负债账户余额绝对值之和
  netWorthMinor: number;         // 净资产 = 资产 - 负债
  accountCount: number;
  updatedAt: string;
}

/**
 * 账户余额统计(LiteNote AccountBalance,保留兼容)
 * @deprecated 建议使用 NetWorthSummary
 */
export interface AccountBalance {
  totalAssets: number;
  totalLiabilities: number;
  netWorth: number;
}

// ============================================================================
// 第四部分:分类类型(基本沿用 LiteNote)
// ============================================================================

export interface Category {
  id: string;
  name: string;
  type: 'income' | 'expense';
  icon?: string;
  color?: string;
  isDefault: boolean;
  sortOrder: number;
  createdAt: string;
  updatedAt: string;
}

export interface CategoryInput {
  name: string;
  type: 'income' | 'expense';
  icon?: string;
  color?: string;
  sortOrder?: number;
}

export interface CategoryUpdateInput extends Partial<CategoryInput> {}

// ============================================================================
// 第五部分:预算类型(沿用 LiteNote,新增超支提醒)
// ============================================================================

export type BudgetPeriod = 'monthly' | 'yearly';

export interface Budget {
  id: string;
  name: string;
  amountMinor: number;
  period: BudgetPeriod;
  categoryId?: string;
  category?: Category;
  alertAt: number;              // 预警阈值百分比(0-100)
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface BudgetInput {
  name: string;
  amountMinor: number;
  period?: BudgetPeriod;
  categoryId?: string;
  alertAt?: number;
}

export interface BudgetUpdateInput extends Partial<BudgetInput> {
  isActive?: boolean;
}

export interface BudgetProgress extends Budget {
  spentMinor: number;
  remainingMinor: number;
  progress: number;             // 0-1
  isOverBudget: boolean;
  needsAlert: boolean;          // 是否需要提醒(80%/100%)
}

// ============================================================================
// 第六部分:财务目标类型(沿用 LiteNote)
// ============================================================================

export interface FinancialGoal {
  id: string;
  name: string;
  targetAmountMinor: number;
  currentAmountMinor: number;
  targetDate?: string;
  createdAt: string;
  updatedAt: string;
}

export interface FinancialGoalInput {
  name: string;
  targetAmountMinor: number;
  targetDate?: string | null;
}

export type FinancialGoalUpdateInput = Partial<FinancialGoalInput> & {
  currentAmountMinor?: number;
};

// ============================================================================
// 第七部分:识别链路类型(新增,对应设计文档第6-10节)
// ============================================================================

/**
 * 支付会话状态(对应设计文档 10.2 节状态机)
 */
export type SessionStatus =
  | 'OBSERVING'           // 观察中,检测结算页
  | 'SNAPSHOT_BUFFERED'   // 快照已入 buffer,等待购买意图
  | 'AI_REQUESTING'       // AI 请求中
  | 'WAITING_ENRICHMENT'  // 支付已确认,等待 AI 回填
  | 'COMPLETED'           // 已完成入账
  | 'FAILED'              // 失败
  | 'CANCELLED'           // 取消
  | 'EXPIRED';            // 过期

/**
 * 页面角色(对应设计文档 7.5 节)
 */
export type PageRole =
  | 'UNKNOWN'
  | 'SHOPPING_HOME'       // 购物首页
  | 'PRODUCT_DETAIL'      // 商品详情
  | 'CART'                // 购物车
  | 'CHECKOUT'            // 结算页
  | 'PAYMENT_RESULT'      // 支付结果页
  | 'PAYMENT_APP';        // 支付应用(微信/支付宝)

/**
 * 快照来源
 */
export type SnapshotSourceReason =
  | 'CHECKOUT_STABLE'     // 自动检测稳定
  | 'MANUAL_CAPTURE';     // 用户手动点击悬浮按钮

/**
 * 结算页快照(仅内存,不持久化)
 */
export interface CheckoutSnapshot {
  snapshotId: string;
  sessionId: string;
  shoppingPackage: string;
  pageRole: 'CHECKOUT';
  pageFingerprint: string;
  capturedAt: number;          // 时间戳(毫秒)
  expiresAt: number;           // capturedAt + 30000
  // imageBytes 不暴露给 RN(仅内存)
  sourceReason: SnapshotSourceReason;
}

/**
 * 支付会话
 */
export interface CheckoutSession {
  sessionId: string;
  status: SessionStatus;
  shoppingPackage?: string;
  pageRole?: PageRole;
  pageFingerprint?: string;
  snapshotId?: string;
  checkoutRequestKey?: string;   // 请求幂等键
  aiRequestId?: string;
  candidateId?: string;
  paymentEvidenceIds: string[];
  createdAt: number;
  updatedAt: number;
  expiresAt: number;
}

/**
 * 支付证据来源
 */
export type EvidenceSourceType =
  | 'notification'
  | 'accessibility'
  | 'manual';

/**
 * 支付证据
 */
export interface PaymentEvidence {
  evidenceId: string;
  sessionId: string;
  sourceType: EvidenceSourceType;
  amountMinor?: number;
  merchant?: string;
  platform?: string;
  orderId?: string;
  paymentMethod?: string;
  paymentStatus?: string;
  occurredAt?: string;          // ISO-8601; Room/原生内部使用毫秒时间戳
  rawTextHash?: string;         // 原文哈希(不存原文)
  capturedAt: number;
}

/**
 * 交易候选(对应设计文档 10.3 节)
 */
export interface TransactionCandidate {
  candidateId: string;
  sessionId: string;
  // AI 返回字段
  amountMinor?: number;
  platform?: string;
  merchant?: string;
  productDescription?: string;
  // 证据字段
  evidenceAmountMinor?: number;
  evidenceMerchant?: string;
  evidenceOrderId?: string;
  // 匹配状态
  matched: boolean;
  conflictReason?: string;
  // 规则引擎
  appliedRules?: string[];
  // 元数据
  createdAt: number;
  version: number;
}

/**
 * 待确认交易(对应设计文档第9节)
 */
export interface PendingTransaction {
  pendingId: string;
  candidateId?: string;
  sessionId?: string;
  amountMinor: number;
  type: TransactionType;
  occurredAt: string;
  platform?: string;
  merchant?: string;
  productDescription?: string;
  orderId?: string;
  accountId?: string;
  categoryId?: string;
  conflictReason?: string;
  sourceType: TransactionSource;
  createdAt: string;
  version: number;
}

export interface PendingTransactionFilter {
  page?: number;
  limit?: number;
}

export type PendingConfirmationInput = Partial<BillInput>;

/**
 * AI 识别结果(对应设计文档 8.1 节 JSON Schema)
 */
export interface AiRecognitionResult {
  amountMinor?: number;
  platform?: string;
  merchant?: string;
  productDescription?: string;
  orderId?: string;
  confidence: number;          // 0-1
  promptVersion: string;
}

// ============================================================================
// 第八部分:规则引擎类型(新增,对应设计文档 FR-050)
// ============================================================================

export type RuleCondition =
  | 'platform_equals'
  | 'merchant_contains'
  | 'merchant_equals'
  | 'amount_range'
  | 'description_contains'
  | 'source_equals';

export type RuleAction =
  | 'set_platform'
  | 'set_merchant'
  | 'set_category'
  | 'set_account'
  | 'ignore'
  | 'send_to_review';

export interface Rule {
  id: string;
  name: string;
  condition: RuleCondition;
  conditionValue: string;
  action: RuleAction;
  actionValue?: string;
  priority: number;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface RuleInput {
  name: string;
  condition: RuleCondition;
  conditionValue: string;
  action: RuleAction;
  actionValue?: string;
  priority?: number;
}

export type RuleUpdateInput = Partial<RuleInput> & {
  isActive?: boolean;
};

// ============================================================================
// 第九部分:AI 服务商配置类型(对应设计文档 FR-013)
// ============================================================================

/**
 * AI 服务商(扩展自 LiteNote AIProvider)
 * LiteNote 原为 claude|openai|deepseek|qwen
 * JotBill 改为 qwen(主)/glm(备)/siliconflow(调试)
 */
export type AiProvider = 'qwen' | 'glm' | 'siliconflow' | 'custom';

/**
 * AI 服务商配置
 */
export interface AiProviderConfig {
  id: string;
  name: string;
  provider: AiProvider;
  model: string;
  apiKey?: string;              // 读取时返回掩码
  apiBaseUrl?: string;
  isDefault: boolean;
  supportsVision: boolean;      // 防止配置不支持视觉的模型
  createdAt: string;
  updatedAt: string;
}

export interface AiProviderInput {
  name: string;
  provider: AiProvider;
  model: string;
  apiKey: string;
  apiBaseUrl?: string;
  isDefault?: boolean;
  supportsVision: boolean;
}

export interface AiProviderUpdateInput extends Partial<AiProviderInput> {}

/**
 * AI 成本记录
 */
export interface AiCostRecord {
  id: string;
  provider: AiProvider;
  model: string;
  requestType: 'vision' | 'chat' | 'asr';
  tokensIn?: number;
  tokensOut?: number;
  costMinor: number;           // 成本(分)
  success: boolean;
  createdAt: string;
}

/**
 * AI 月度预算
 */
export interface AiBudgetSummary {
  monthlyBudgetMinor: number;
  monthlySpentMinor: number;
  remainingMinor: number;
  requestCount: number;
  month: string;               // YYYY-MM
}

export interface TestConnectionResult {
  success: boolean;
  message: string;
  latencyMs?: number;
}

/** 本地 AI 对话会话。 */
export interface AiChatSession {
  id: string;
  title: string | null;
  aiConfigId: string | null;
  isActive: boolean;
  isPinned: boolean;
  createdAt: string;
  updatedAt: string;
  _count?: { messages: number };
}

/** 本地 AI 对话消息。 */
export interface AiChatMessage {
  id: string;
  sessionId: string;
  seqNum: number;
  role: 'system' | 'user' | 'assistant' | 'tool';
  content: string | null;
  toolCalls: unknown[] | null;
  toolCallId: string | null;
  toolName: string | null;
  toolResult: unknown | null;
  imageUrl: string | null;
  createdAt: string;
}

/** 发起一次原生 AI 流式请求。 */
export interface AiChatRequest {
  requestId: string;
  content: string;
  sessionId?: string;
  configId?: string;
  imageBase64List?: string[];
}

/** 原生 AI 流式事件。 */
export interface AiStreamChunk {
  requestId: string;
  sessionId: string;
  type: 'session_created' | 'thinking' | 'thinking_delta' | 'text_delta' | 'tool_call_start' | 'tool_result' | 'done' | 'error';
  content?: string;
  round?: number;
  thinkingTimeMs?: number;
  cancelled?: boolean;
  message?: string;
  toolCallId?: string;
  toolName?: string;
  resultJson?: string;
}

// ============================================================================
// 第十部分:运行健康状态(对应设计文档 FR-003)
// ============================================================================

export interface RuntimeHealth {
  databaseVersion: number;
  billCount: number;
  categoryCount: number;
  accountCount: number;
  lastLaunchAt: string;
  checkedAt: string;
  // 以下字段由阶段二原生实现附带返回，后续阶段再补齐真实健康检查。
  aiProviderConfigured: boolean;
  accessibilityEnabled: boolean;
  notificationEnabled: boolean;
  overlayPermissionGranted: boolean;  // 悬浮窗权限
}

// ============================================================================
// 第十一部分:备份与导入(对应设计文档 FR-100~FR-105)
// ============================================================================

export interface BackupSummary {
  filePath: string;
  fileSize: number;
  billCount: number;
  accountCount: number;
  categoryCount: number;
  ruleCount: number;
  createdAt: string;
  version: number;
  encrypted: boolean;
}

export interface RestorePreview {
  version: number;
  billCount: number;
  accountCount: number;
  categoryCount: number;
  ruleCount: number;
  backupCreatedAt: string;
  compatible: boolean;
}

export interface ImportResult {
  imported: number;
  skipped: number;
  duplicates: number;
  errors: string[];
}

// ============================================================================
// 第十二部分:Native Module 接口(对应设计文档第16节)
// ============================================================================

/**
 * 数据访问接口
 * 替代 LiteNote 的 billsService/accountsService 等 HTTP 调用
 */
export interface DataModule {
  // 账单
  getBills(filter?: BillFilter): Promise<PaginatedResponse<Bill>>;
  getBillById(id: string): Promise<Bill>;
  addBill(input: BillInput): Promise<Bill>;
  updateBill(id: string, input: BillUpdateInput): Promise<Bill>;
  deleteBill(id: string): Promise<void>;
  getBillStatistics(filter?: BillFilter): Promise<BillStatistics>;

  // 待确认
  getPendingTransactions(filter?: PendingTransactionFilter): Promise<PaginatedResponse<PendingTransaction>>;
  getPendingTransaction(id: string): Promise<PendingTransaction>;
  confirmPendingTransaction(id: string, input: PendingConfirmationInput, version: number): Promise<Bill>;
  ignorePendingTransaction(id: string, version: number): Promise<void>;

  // 账户
  getAccounts(): Promise<Account[]>;
  getAccountById(id: string): Promise<Account>;
  addAccount(input: AccountInput): Promise<Account>;
  updateAccount(id: string, input: AccountUpdateInput): Promise<Account>;
  deleteAccount(id: string): Promise<void>;
  calibrateAccountBalance(id: string, actualBalanceMinor: number, note?: string): Promise<BalanceCalibration>;
  getNetWorth(): Promise<NetWorthSummary>;
  getBalanceCalibrations(accountId: string): Promise<BalanceCalibration[]>;

  // 分类
  getCategories(type?: 'income' | 'expense'): Promise<Category[]>;
  addCategory(input: CategoryInput): Promise<Category>;
  updateCategory(id: string, input: CategoryUpdateInput): Promise<Category>;
  deleteCategory(id: string): Promise<void>;
  initDefaultCategories(): Promise<void>;

  // 预算
  getBudgets(): Promise<Budget[]>;
  addBudget(input: BudgetInput): Promise<Budget>;
  updateBudget(id: string, input: BudgetUpdateInput): Promise<Budget>;
  deleteBudget(id: string): Promise<void>;
  getBudgetProgress(): Promise<BudgetProgress[]>;

  // 财务目标
  getFinancialGoals(): Promise<FinancialGoal[]>;
  addFinancialGoal(input: FinancialGoalInput): Promise<FinancialGoal>;
  updateFinancialGoal(id: string, input: FinancialGoalUpdateInput): Promise<FinancialGoal>;
  deleteFinancialGoal(id: string): Promise<void>;

  // 规则
  getRules(): Promise<Rule[]>;
  addRule(input: RuleInput): Promise<Rule>;
  updateRule(id: string, input: RuleUpdateInput): Promise<Rule>;
  deleteRule(id: string): Promise<void>;
  applyRulesToExisting(ruleId?: string): Promise<{ affected: number }>;
}

/**
 * 识别状态接口
 */
export interface RecognitionModule {
  getCheckoutSessions(filter?: { status?: SessionStatus[] }): Promise<CheckoutSession[]>;
  getCheckoutSession(sessionId: string): Promise<CheckoutSession>;
  getTransactionCandidate(candidateId: string): Promise<TransactionCandidate>;
  getPaymentEvidence(sessionId: string): Promise<PaymentEvidence[]>;
  retryAiRecognition(candidateId: string): Promise<void>;
  getRecognitionStatus(): Promise<{
    activeSessions: number;
    pendingCount: number;
    aiRequesting: boolean;
  }>;
}

/**
 * AI 服务商接口
 */
export interface AiModule {
  getAiProviders(): Promise<AiProviderConfig[]>;
  addAiProvider(input: AiProviderInput): Promise<AiProviderConfig>;
  updateAiProvider(id: string, input: AiProviderUpdateInput): Promise<AiProviderConfig>;
  deleteAiProvider(id: string): Promise<void>;
  setDefaultAiProvider(id: string): Promise<AiProviderConfig>;
  testAiProvider(id: string): Promise<TestConnectionResult>;
  getAiBudget(): Promise<AiBudgetSummary>;
  setAiBudget(monthlyBudgetMinor: number): Promise<void>;
  getAiCostRecords(month?: string): Promise<AiCostRecord[]>;
  sendMessage(input: AiChatRequest): Promise<{ requestId: string; sessionId: string }>;
  cancelMessage(sessionId: string): Promise<boolean>;
  getChatSessions(page?: number, limit?: number): Promise<AiChatSession[]>;
  getChatSession(sessionId: string): Promise<{
    session: AiChatSession;
    messages: AiChatMessage[];
  }>;
  deleteChatSession(sessionId: string): Promise<void>;
  renameChatSession(sessionId: string, title: string): Promise<void>;
  togglePinChatSession(sessionId: string): Promise<boolean>;
  transcribeAudio(audioBase64: string, mimeType?: string): Promise<{ text: string; latencyMs: number }>;
}

/**
 * 运行状态接口
 */
export interface RuntimeModule {
  getRuntimeHealth(): Promise<RuntimeHealth>;
}

/**
 * 悬浮按钮接口(新增)
 */
export interface OverlayModule {
  setManualCaptureButtonEnabled(enabled: boolean): Promise<void>;
  setManualCaptureButtonMode(mode: 'always_on' | 'checkout_only' | 'off'): Promise<void>;
  getManualCaptureButtonMode(): Promise<'always_on' | 'checkout_only' | 'off'>;
}

/**
 * 备份与导入接口
 */
export interface BackupModule {
  // 阶段二只开放 CSV 导出和清除本地数据，完整备份恢复在后续阶段实现。
  exportData(format: 'csv'): Promise<{ filePath: string }>;
  clearData(): Promise<void>;
}

/**
 * 事件订阅(原生 -> RN 的事件通知)
 */
export interface RecognitionEvents {
  onCheckoutDetected?: (data: { sessionId: string; shoppingPackage: string }) => void;
  onSnapshotCaptured?: (data: { sessionId: string; snapshotId: string; sourceReason: SnapshotSourceReason }) => void;
  onAiRecognitionCompleted?: (data: { sessionId: string; candidateId: string; success: boolean }) => void;
  onPaymentConfirmed?: (data: { sessionId: string; evidenceId: string }) => void;
  onReviewRequired?: (data: { pendingId: string; reason: string }) => void;
  onSessionExpired?: (data: { sessionId: string }) => void;
  onRuntimeHealthChanged?: (data: RuntimeHealth) => void;
  onAiBudgetExceeded?: (data: AiBudgetSummary) => void;
  onBudgetAlert?: (data: { budgetId: string; budgetName: string; progress: number; isOverBudget: boolean }) => void;
  onAiStreamChunk?: (data: AiStreamChunk) => void;
}

/**
 * 主 Native Module 接口(聚合所有子模块)
 */
export interface NativeModules {
  Data: DataModule;
  Recognition: RecognitionModule;
  Ai: AiModule;
  Runtime: RuntimeModule;
  Overlay: OverlayModule;
  Backup: BackupModule;
}

/**
 * 事件发射器
 */
export interface NativeEventEmitter {
  addListener<K extends keyof RecognitionEvents>(
    eventName: K,
    listener: NonNullable<RecognitionEvents[K]>
  ): { remove: () => void };
  removeAllListeners<K extends keyof RecognitionEvents>(eventName: K): void;
}

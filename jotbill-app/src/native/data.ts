import { NativeModules } from 'react-native';

export type TransactionType =
  | 'income'
  | 'expense'
  | 'transfer'
  | 'refund'
  | 'repayment'
  | 'fee'
  | 'adjustment';

export type TransactionSource =
  | 'ai_vision'
  | 'notification'
  | 'accessibility'
  | 'sms'
  | 'import'
  | 'manual'
  | 'ai_chat';

export interface Bill {
  id: string;
  amountMinor: number;
  type: TransactionType;
  occurredAt: string;
  description?: string;
  categoryId?: string;
  accountId?: string;
  targetAccountId?: string;
  platform?: string;
  merchant?: string;
  productDescription?: string;
  sourceType: TransactionSource;
  originalTransactionId?: string;
  orderId?: string;
  createdAt: string;
  updatedAt: string;
  version: number;
}

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
  originalTransactionId?: string;
  orderId?: string;
  /**
   * P15：可选客户端幂等键。表单会话/AI 提案在生命周期内生成一次并随重试
   * 原样重传；原生侧据此确定性生成账单 id，重放命中既有账单即幂等返回，
   * 不再产生重复账单（此前 addBill 无幂等键，重试/双击必然双记）。
   * 字符集 [A-Za-z0-9_.:-]，最长 128。
   */
  clientRequestId?: string;
}

export interface BillFilter {
  page?: number;
  limit?: number;
  type?: TransactionType;
  categoryId?: string;
  accountId?: string;
  platform?: string;
  merchant?: string;
  startDate?: string;
  endDate?: string;
  startDateMinor?: number;
  endDateMinor?: number;
  orderBy?: 'date' | 'amount' | 'createdAt';
  orderDirection?: 'asc' | 'desc';
  sourceType?: TransactionSource;
  keyword?: string;
}

export interface BillUpdateInput extends Partial<BillInput> {
  version: number;
}

export interface PaginatedResponse<T> {
  items: T[];
  total: number;
  page: number;
  limit: number;
  hasMore: boolean;
}

export type AccountSubtype =
  | 'cash'
  | 'e_wallet'
  | 'bank_debit'
  | 'credit_card'
  | 'consumer_loan'
  | 'other';

export interface Account {
  id: string;
  name: string;
  subtype: AccountSubtype;
  kind: 'asset' | 'liability';
  initialBalanceMinor: number;
  currentBalanceMinor: number;
  currency: string;
  icon?: string;
  color?: string;
  sortOrder: number;
  isActive: boolean;
  /** 银行卡尾号（后 4 位）；用于银行短信精准匹配账户 */
  cardTail?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AccountInput {
  name: string;
  subtype: AccountSubtype;
  kind?: Account['kind'];
  initialBalanceMinor?: number;
  currency?: string;
  /** `bank:<code>` / `brand:<code>` 显式指定；null 清空（回到名称自动匹配） */
  icon?: string | null;
  color?: string;
  sortOrder?: number;
  /** 银行卡尾号（后 4 位，可空）：填了按尾号精准匹配短信账户 */
  cardTail?: string | null;
}

export interface AccountUpdateInput extends Partial<AccountInput> {
  isActive?: boolean;
}

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
  /** 批 6 子分类:null = 顶级分类 */
  parentId?: string | null;
}

export interface CategoryInput {
  name: string;
  type: 'income' | 'expense';
  icon?: string;
  color?: string;
  sortOrder?: number;
  /** 添加子分类时指定父分类(体系两级) */
  parentId?: string;
}

export interface CategoryUpdateInput extends Partial<CategoryInput> {}

export type BudgetPeriod =
  | 'monthly'
  | 'weekly'
  | 'biweekly'
  | 'quarterly'
  | 'yearly'
  | 'once';

export interface Budget {
  id: string;
  name: string;
  amountMinor: number;
  period: BudgetPeriod;
  /** 月周期起始日 1..31；31 表示该月最后一天 */
  startDay?: number;
  /** 周周期起始星期（1=周一 … 7=周日）；双周由 anchorDate 决定 */
  weekStart?: number;
  /** 双周锚点日（YYYY-MM-DD） */
  anchorDate?: string;
  /** 一次性区间开始日（YYYY-MM-DD，含当日） */
  rangeStartDate?: string;
  /** 一次性区间结束日（YYYY-MM-DD，含当日） */
  rangeEndDate?: string;
  categoryId?: string;
  alertAt: number;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface BudgetInput {
  name: string;
  amountMinor: number;
  period?: BudgetPeriod;
  startDay?: number;
  weekStart?: number;
  anchorDate?: string;
  rangeStartDate?: string;
  rangeEndDate?: string;
  categoryId?: string;
  alertAt?: number;
}

export type BudgetUpdateInput = Partial<BudgetInput> & {
  isActive?: boolean;
};

export interface BudgetProgress extends Budget {
  /** 已用金额（支出 − 期内退款，下限 0） */
  spentMinor: number;
  /** 期内支出合计（未扣退款） */
  grossSpentMinor: number;
  /** 期内退款抵扣合计 */
  refundMinor: number;
  remainingMinor: number;
  progress: number;
  /** 展示百分比（0-x 整数，三端统一向下取整，避免 99.5% 显示成 100%） */
  progressPct: number;
  isOverBudget: boolean;
  needsAlert: boolean;
  /** 本期开始（ISO 时间） */
  periodStartAt: string;
  /** 本期结束（ISO 时间） */
  periodEndAt: string;
  /** 周期展示标签，如 11/25–12/24、2026 Q4、2026年 */
  periodLabel: string;
  /** 当前是否处于周期内（一次性预算结束后为 false） */
  isPeriodActive: boolean;
}

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

export interface Rule {
  id: string;
  name: string;
  condition: string;
  conditionValue: string;
  action: string;
  actionValue?: string;
  priority: number;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

/**
 * 阶段五 T7：规则条件类型（与原生 RuleEngine 支持的条件一致）。
 * 契约字段仍为 string（Native.d.ts 未声明枚举），此联合类型仅供表单层收紧。
 */
export type RuleConditionType =
  | 'platform_equals'
  | 'merchant_equals'
  | 'merchant_contains'
  | 'description_contains'
  | 'source_equals'
  | 'amount_range';

/**
 * 阶段五 T7：规则动作类型（与原生 RuleEngine 支持的动作一致）。
 * set_* 携带 actionValue；ignore/send_to_review 无值。
 */
export type RuleActionType =
  | 'set_platform'
  | 'set_merchant'
  | 'set_category'
  | 'set_account'
  | 'ignore'
  | 'send_to_review';

export interface RuleInput {
  name: string;
  condition: string;
  conditionValue: string;
  action: string;
  actionValue?: string;
  priority?: number;
}

export type RuleUpdateInput = Partial<RuleInput> & {
  isActive?: boolean;
};

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
  /**
   * 归并计数（隐私窄化，2026-11-06）：native 侧从 appliedRules 算好的
   * mergedSourceEventKeys 数组长度 + overflow 计数之和，卡面据此显示
   * 「已归并 N 条信号」（M-补③）。原始 appliedRules JSON 不再下发
   * （含 sourceEventKey 明细），仍留存于原生数据库。
   */
  mergedSignalCount: number;
  sourceType: TransactionSource;
  createdAt: string;
  version: number;
}

export interface PendingTransactionFilter {
  page?: number;
  limit?: number;
}

export type PendingConfirmationInput = Partial<BillInput> & {
  /**
   * M4：疑似重复卡的二次确认标记。卡带 SUSPECTED_DUPLICATE 冲突码时，
   * 原生首次确认返回 DUPLICATE_CONFIRM_REQUIRED；RN 弹「疑似重复」确认后
   * 带本标记重试即放行（不做硬阻断，用户明确后必须能过）。
   */
  confirmedDuplicate?: boolean;
};

export interface BalanceCalibration {
  id: string;
  accountId: string;
  beforeBalanceMinor: number;
  afterBalanceMinor: number;
  diffMinor: number;
  note?: string;
  createdAt: string;
}

export interface NetWorthSummary {
  totalAssetsMinor: number;
  totalLiabilitiesMinor: number;
  netWorthMinor: number;
  accountCount: number;
  updatedAt: string;
}

/** B4：单个账户的余额不一致快照（diffMinor = actual - expected）。 */
export interface BalanceReconcileMismatch {
  accountId: string;
  expectedMinor: number;
  actualMinor: number;
  diffMinor: number;
}

/** B4：余额一致性抽检结果；mismatches 为空表示全部一致。 */
export interface BalanceReconcileWarnings {
  checkedAt: number;
  mismatches: BalanceReconcileMismatch[];
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

export interface BillStatistics {
  totalIncome: number;
  totalExpense: number;
  balance: number;
  incomeCount: number;
  expenseCount: number;
  expenseCategoryStats: CategoryStatistic[];
  incomeCategoryStats: CategoryStatistic[];
  platformStats: PlatformStatistic[];
  dailyTrends: Array<{ date: string; income: number; expense: number }>;
  monthlyTrends: Array<{ month: string; year: number; income: number; expense: number }>;
}

interface RawDataModule {
  getBills(filterJson?: string | null): Promise<string>;
  getBillById(id: string): Promise<string>;
  addBill(inputJson: string): Promise<string>;
  updateBill(id: string, inputJson: string): Promise<string>;
  deleteBill(id: string): Promise<null>;
  getBillStatistics(filterJson?: string | null): Promise<string>;
  getAccounts(): Promise<string>;
  getAccountById(id: string): Promise<string>;
  addAccount(inputJson: string): Promise<string>;
  updateAccount(id: string, inputJson: string): Promise<string>;
  deleteAccount(id: string): Promise<null>;
  calibrateAccountBalance(id: string, actualBalanceMinor: number, note?: string | null): Promise<string>;
  getNetWorth(): Promise<string>;
  getBalanceCalibrations(accountId: string): Promise<string>;
  getBalanceReconcileWarnings(): Promise<string>;
  getCategories(type?: Category['type'] | null): Promise<string>;
  addCategory(inputJson: string): Promise<string>;
  updateCategory(id: string, inputJson: string): Promise<string>;
  deleteCategory(id: string): Promise<null>;
  initDefaultCategories(): Promise<null>;
  getBudgets(): Promise<string>;
  addBudget(inputJson: string): Promise<string>;
  updateBudget(id: string, inputJson: string): Promise<string>;
  deleteBudget(id: string): Promise<null>;
  getBudgetProgress(): Promise<string>;
  getFinancialGoals(): Promise<string>;
  addFinancialGoal(inputJson: string): Promise<string>;
  updateFinancialGoal(id: string, inputJson: string): Promise<string>;
  deleteFinancialGoal(id: string): Promise<null>;
  getRules(): Promise<string>;
  addRule(inputJson: string): Promise<string>;
  updateRule(id: string, inputJson: string): Promise<string>;
  deleteRule(id: string): Promise<null>;
  applyRulesToExisting(ruleId: string | null): Promise<string>;
  getPendingTransactions(filterJson?: string | null): Promise<string>;
  getPendingTransaction(id: string): Promise<string>;
  ignorePendingTransaction(id: string, version: number): Promise<null>;
  confirmPendingTransaction(
    id: string,
    inputJson: string,
    version: number,
  ): Promise<string>;
}

function getRawDataModule(): RawDataModule {
  const module = NativeModules.Data as RawDataModule | undefined;
  if (!module) {
    throw new Error('Native Data module is unavailable on this platform');
  }
  return module;
}

function parseJson<T>(value: string, operation: string): T {
  try {
    return JSON.parse(value) as T;
  } catch (error) {
    throw new Error(`Native Data.${operation} returned invalid JSON`);
  }
}

function assertMinorUnit(value: number, field: string): number {
  if (!Number.isSafeInteger(value)) {
    throw new Error(`${field} must be a safe integer minor unit`);
  }
  return value;
}

export const nativeData = {
  async getBills(filter: BillFilter = {}): Promise<PaginatedResponse<Bill>> {
    return parseJson(await getRawDataModule().getBills(JSON.stringify(filter)), 'getBills');
  },

  async getBillById(id: string): Promise<Bill> {
    return parseJson(await getRawDataModule().getBillById(id), 'getBillById');
  },

  async addBill(input: BillInput): Promise<Bill> {
    const normalizedInput = {
      ...input,
      amountMinor: assertMinorUnit(input.amountMinor, 'amountMinor'),
    };
    return parseJson(
      await getRawDataModule().addBill(JSON.stringify(normalizedInput)),
      'addBill',
    );
  },

  async updateBill(id: string, input: BillUpdateInput): Promise<Bill> {
    const normalizedInput = input.amountMinor === undefined
      ? input
      : {
          ...input,
          amountMinor: assertMinorUnit(input.amountMinor, 'amountMinor'),
        };
    return parseJson(
      await getRawDataModule().updateBill(id, JSON.stringify(normalizedInput)),
      'updateBill',
    );
  },

  async deleteBill(id: string): Promise<void> {
    await getRawDataModule().deleteBill(id);
  },

  async getBillStatistics(filter: BillFilter = {}): Promise<BillStatistics> {
    return parseJson(
      await getRawDataModule().getBillStatistics(JSON.stringify(filter)),
      'getBillStatistics',
    );
  },

  async getAccounts(): Promise<Account[]> {
    return parseJson(await getRawDataModule().getAccounts(), 'getAccounts');
  },

  async getAccountById(id: string): Promise<Account> {
    return parseJson(await getRawDataModule().getAccountById(id), 'getAccountById');
  },

  async addAccount(input: AccountInput): Promise<Account> {
    const normalizedInput = input.initialBalanceMinor === undefined
      ? input
      : {
          ...input,
          initialBalanceMinor: assertMinorUnit(input.initialBalanceMinor, 'initialBalanceMinor'),
        };
    return parseJson(
      await getRawDataModule().addAccount(JSON.stringify(normalizedInput)),
      'addAccount',
    );
  },

  async updateAccount(id: string, input: AccountUpdateInput): Promise<Account> {
    return parseJson(
      await getRawDataModule().updateAccount(id, JSON.stringify(input)),
      'updateAccount',
    );
  },

  async deleteAccount(id: string): Promise<void> {
    await getRawDataModule().deleteAccount(id);
  },

  async calibrateAccountBalance(
    id: string,
    actualBalanceMinor: number,
    note?: string,
  ): Promise<BalanceCalibration> {
    const actual = assertMinorUnit(actualBalanceMinor, 'actualBalanceMinor');
    return parseJson(
      await getRawDataModule().calibrateAccountBalance(id, actual, note ?? null),
      'calibrateAccountBalance',
    );
  },

  async getNetWorth(): Promise<NetWorthSummary> {
    return parseJson(await getRawDataModule().getNetWorth(), 'getNetWorth');
  },

  async getBalanceCalibrations(accountId: string): Promise<BalanceCalibration[]> {
    return parseJson(
      await getRawDataModule().getBalanceCalibrations(accountId),
      'getBalanceCalibrations',
    );
  },

  /**
   * B4：余额一致性实时重算。不一致时 mismatches 逐条给出
   * 账户 ID 与 expected/actual/diff，账户页据此提示并指向「校准余额」。
   */
  async getBalanceReconcileWarnings(): Promise<BalanceReconcileWarnings> {
    return parseJson(
      await getRawDataModule().getBalanceReconcileWarnings(),
      'getBalanceReconcileWarnings',
    );
  },

  async getCategories(type?: Category['type']): Promise<Category[]> {
    return parseJson(
      await getRawDataModule().getCategories(type ?? null),
      'getCategories',
    );
  },

  async addCategory(input: CategoryInput): Promise<Category> {
    return parseJson(
      await getRawDataModule().addCategory(JSON.stringify(input)),
      'addCategory',
    );
  },

  async updateCategory(id: string, input: CategoryUpdateInput): Promise<Category> {
    return parseJson(
      await getRawDataModule().updateCategory(id, JSON.stringify(input)),
      'updateCategory',
    );
  },

  async deleteCategory(id: string): Promise<void> {
    await getRawDataModule().deleteCategory(id);
  },

  async initDefaultCategories(): Promise<void> {
    await getRawDataModule().initDefaultCategories();
  },

  async getBudgets(): Promise<Budget[]> {
    return parseJson(await getRawDataModule().getBudgets(), 'getBudgets');
  },

  async addBudget(input: BudgetInput): Promise<Budget> {
    return parseJson(
      await getRawDataModule().addBudget(JSON.stringify(input)),
      'addBudget',
    );
  },

  async updateBudget(id: string, input: BudgetUpdateInput): Promise<Budget> {
    return parseJson(
      await getRawDataModule().updateBudget(id, JSON.stringify(input)),
      'updateBudget',
    );
  },

  async deleteBudget(id: string): Promise<void> {
    await getRawDataModule().deleteBudget(id);
  },

  async getBudgetProgress(): Promise<BudgetProgress[]> {
    return parseJson(
      await getRawDataModule().getBudgetProgress(),
      'getBudgetProgress',
    );
  },

  async getFinancialGoals(): Promise<FinancialGoal[]> {
    return parseJson(
      await getRawDataModule().getFinancialGoals(),
      'getFinancialGoals',
    );
  },

  async addFinancialGoal(input: FinancialGoalInput): Promise<FinancialGoal> {
    return parseJson(
      await getRawDataModule().addFinancialGoal(JSON.stringify(input)),
      'addFinancialGoal',
    );
  },

  async updateFinancialGoal(
    id: string,
    input: FinancialGoalUpdateInput,
  ): Promise<FinancialGoal> {
    return parseJson(
      await getRawDataModule().updateFinancialGoal(id, JSON.stringify(input)),
      'updateFinancialGoal',
    );
  },

  async deleteFinancialGoal(id: string): Promise<void> {
    await getRawDataModule().deleteFinancialGoal(id);
  },

  async getRules(): Promise<Rule[]> {
    return parseJson(await getRawDataModule().getRules(), 'getRules');
  },

  async addRule(input: RuleInput): Promise<Rule> {
    return parseJson(
      await getRawDataModule().addRule(JSON.stringify(input)),
      'addRule',
    );
  },

  async updateRule(id: string, input: RuleUpdateInput): Promise<Rule> {
    return parseJson(
      await getRawDataModule().updateRule(id, JSON.stringify(input)),
      'updateRule',
    );
  },

  async deleteRule(id: string): Promise<void> {
    await getRawDataModule().deleteRule(id);
  },

  async applyRulesToExisting(ruleId?: string): Promise<{ affected: number }> {
    return parseJson(
      await getRawDataModule().applyRulesToExisting(ruleId ?? null),
      'applyRulesToExisting',
    );
  },

  async getPendingTransactions(
    filter: PendingTransactionFilter = {},
  ): Promise<PaginatedResponse<PendingTransaction>> {
    return parseJson(
      await getRawDataModule().getPendingTransactions(JSON.stringify(filter)),
      'getPendingTransactions',
    );
  },

  async getPendingTransaction(id: string): Promise<PendingTransaction> {
    return parseJson(
      await getRawDataModule().getPendingTransaction(id),
      'getPendingTransaction',
    );
  },

  async ignorePendingTransaction(id: string, version: number): Promise<void> {
    await getRawDataModule().ignorePendingTransaction(id, version);
  },

  async confirmPendingTransaction(
    id: string,
    input: PendingConfirmationInput,
    version: number,
  ): Promise<Bill> {
    return parseJson(
      await getRawDataModule().confirmPendingTransaction(
        id,
        JSON.stringify(input),
        version,
      ),
      'confirmPendingTransaction',
    );
  },
};

export type NativeData = typeof nativeData;

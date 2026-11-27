/**
 * 改分类一键生成规则自学习逻辑。
 *
 * 负责判断一次账单分类修正是否值得提示学习规则、管理本会话内的免打扰记忆、
 * 以及一键新建/更新 `from_correction` 规则并可选同步应用到历史账单。
 */
import {
  nativeData,
  type Bill,
  type CategoryRuleSuggestion,
  type Rule,
  type TransactionType,
} from '../native';
import { invalidateCache } from './queryClient';

const ELIGIBLE_BILL_TYPES: ReadonlySet<TransactionType> = new Set([
  'expense',
  'income',
  'fee',
]);

/** 会话级已忽略建议集合（merchant 小写 + categoryId），避免用户点"暂不需要"后反复弹窗。 */
const dismissedSuggestionKeys = new Set<string>();

function makeDismissKey(merchant: string, categoryId: string): string {
  return `${merchant.trim().toLowerCase()}::${categoryId}`;
}

export function dismissCategoryRuleSuggestion(merchant: string, categoryId: string): void {
  const trimmed = merchant.trim();
  if (!trimmed || !categoryId) return;
  dismissedSuggestionKeys.add(makeDismissKey(trimmed, categoryId));
}

export function isCategoryRuleSuggestionDismissed(merchant: string, categoryId: string): boolean {
  const trimmed = merchant.trim();
  if (!trimmed || !categoryId) return false;
  return dismissedSuggestionKeys.has(makeDismissKey(trimmed, categoryId));
}

export function resetDismissedCategoryRuleSuggestions(): void {
  dismissedSuggestionKeys.clear();
}

/**
 * 本地快速前置过滤：仅在「账单带商户、分类实际发生变化、且账单类型支持自动分类」时
 * 才发桥接请求询问原生规则引擎与历史账单数量。
 */
export function isBillEligibleForCategoryRule(
  bill: Pick<Bill, 'merchant' | 'categoryId'>,
  nextBillType: TransactionType,
  nextCategoryId?: string | null,
): boolean {
  if (!ELIGIBLE_BILL_TYPES.has(nextBillType)) return false;
  if (!nextCategoryId) return false;
  if (nextCategoryId === bill.categoryId) return false;
  const merchant = bill.merchant?.trim();
  if (!merchant || merchant.length < 2) return false;
  return true;
}

export function formatLearnedRuleName(merchant: string, categoryName: string): string {
  const normalizedMerchant = merchant.trim();
  const normalizedCategory = categoryName.trim();
  return `${normalizedMerchant} → ${normalizedCategory}`;
}

export interface CheckCategoryRuleSuggestionParams {
  bill: Pick<Bill, 'id' | 'merchant' | 'categoryId'>;
  nextBillType: TransactionType;
  nextCategoryId?: string | null;
}

/**
 * 预览改分类自学习建议。
 * 只有需要向用户展示提示（`create` 或 `update`）且本会话未被忽略时返回建议对象；
 * 其余情况返回 `null`。
 */
export async function checkCategoryRuleSuggestion(
  params: CheckCategoryRuleSuggestionParams,
): Promise<CategoryRuleSuggestion | null> {
  const { bill, nextBillType, nextCategoryId } = params;
  if (!isBillEligibleForCategoryRule(bill, nextBillType, nextCategoryId)) {
    return null;
  }
  const merchant = bill.merchant!.trim();
  const categoryId = nextCategoryId!;
  if (isCategoryRuleSuggestionDismissed(merchant, categoryId)) {
    return null;
  }

  const suggestion = await nativeData.previewCategoryRuleSuggestion({
    merchant,
    categoryId,
    billType: nextBillType,
    excludeBillId: bill.id,
  });

  if (suggestion.status !== 'create' && suggestion.status !== 'update') {
    return null;
  }
  if (isCategoryRuleSuggestionDismissed(suggestion.merchant, suggestion.categoryId)) {
    return null;
  }
  return suggestion;
}

export interface ApplyLearnedCategoryRuleResult {
  rule: Rule;
  appliedCount: number;
  historyApplyFailed: boolean;
}

/**
 * 执行一键自学习：创建或更新 `from_correction` 精确商户规则，并按用户选择应用到历史账单。
 */
export async function applyLearnedCategoryRule(
  suggestion: CategoryRuleSuggestion,
  categoryName: string,
  applyToHistory: boolean,
): Promise<ApplyLearnedCategoryRuleResult> {
  const resolvedCategoryName = categoryName.trim() || suggestion.categoryName?.trim() || suggestion.categoryId;
  const ruleName = formatLearnedRuleName(suggestion.merchant, resolvedCategoryName);

  const savedRule =
    suggestion.status === 'update' && suggestion.existingRuleId
      ? await nativeData.updateRule(suggestion.existingRuleId, {
          name: ruleName,
          condition: 'merchant_equals',
          conditionValue: suggestion.merchant,
          action: 'set_category',
          actionValue: suggestion.categoryId,
          priority: suggestion.suggestedPriority,
          isActive: true,
          source: 'from_correction',
        })
      : await nativeData.addRule({
          name: ruleName,
          condition: 'merchant_equals',
          conditionValue: suggestion.merchant,
          action: 'set_category',
          actionValue: suggestion.categoryId,
          priority: suggestion.suggestedPriority,
          source: 'from_correction',
        });

  invalidateCache.rules();

  if (!applyToHistory) {
    return {
      rule: savedRule,
      appliedCount: 0,
      historyApplyFailed: false,
    };
  }

  try {
    const result = await nativeData.applyRulesToExisting(savedRule.id);
    if (result.affected > 0) {
      invalidateCache.bills();
    }
    invalidateCache.rules();
    return {
      rule: savedRule,
      appliedCount: result.affected,
      historyApplyFailed: false,
    };
  } catch (error) {
    console.warn('自学习规则应用到历史账单失败', error);
    return {
      rule: savedRule,
      appliedCount: 0,
      historyApplyFailed: true,
    };
  }
}

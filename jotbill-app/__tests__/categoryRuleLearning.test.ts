import {
  applyLearnedCategoryRule,
  checkCategoryRuleSuggestion,
  dismissCategoryRuleSuggestion,
  formatLearnedRuleName,
  isBillEligibleForCategoryRule,
  resetDismissedCategoryRuleSuggestions,
} from '../src/lib/categoryRuleLearning';
import { invalidateCache } from '../src/lib/queryClient';
import { nativeData, type CategoryRuleSuggestion, type Rule } from '../src/native';

jest.mock('../src/native', () => ({
  nativeData: {
    previewCategoryRuleSuggestion: jest.fn(),
    addRule: jest.fn(),
    updateRule: jest.fn(),
    applyRulesToExisting: jest.fn(),
  },
}));

jest.mock('../src/lib/queryClient', () => ({
  invalidateCache: {
    rules: jest.fn(),
    bills: jest.fn(),
  },
}));

const sampleRule: Rule = {
  id: 'rule-1',
  name: '瑞幸咖啡 → 咖啡',
  condition: 'merchant_equals',
  conditionValue: '瑞幸咖啡',
  action: 'set_category',
  actionValue: 'cat-coffee',
  priority: 100,
  isActive: true,
  hitCount: 0,
  source: 'from_correction',
  createdAt: '2026-11-27T00:00:00Z',
  updatedAt: '2026-11-27T00:00:00Z',
};

describe('改分类一键生成规则自学习逻辑', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    resetDismissedCategoryRuleSuggestions();
  });

  test('前置资格判断：仅在带商户、分类变化且为支出/收入/手续费时返回 true', () => {
    expect(
      isBillEligibleForCategoryRule(
        { merchant: '瑞幸咖啡', categoryId: 'cat-food' },
        'expense',
        'cat-coffee',
      ),
    ).toBe(true);
    expect(
      isBillEligibleForCategoryRule(
        { merchant: '瑞幸咖啡', categoryId: undefined },
        'fee',
        'cat-coffee',
      ),
    ).toBe(true);
    expect(
      isBillEligibleForCategoryRule(
        { merchant: '某公司', categoryId: 'cat-other-income' },
        'income',
        'cat-salary',
      ),
    ).toBe(true);

    // 分类未变
    expect(
      isBillEligibleForCategoryRule(
        { merchant: '瑞幸咖啡', categoryId: 'cat-coffee' },
        'expense',
        'cat-coffee',
      ),
    ).toBe(false);
    // 无商户或单字商户
    expect(
      isBillEligibleForCategoryRule(
        { merchant: undefined, categoryId: 'cat-food' },
        'expense',
        'cat-coffee',
      ),
    ).toBe(false);
    expect(
      isBillEligibleForCategoryRule(
        { merchant: '店', categoryId: 'cat-food' },
        'expense',
        'cat-coffee',
      ),
    ).toBe(false);
    // 不支持类型
    expect(
      isBillEligibleForCategoryRule(
        { merchant: '瑞幸咖啡', categoryId: undefined },
        'transfer',
        'cat-coffee',
      ),
    ).toBe(false);
    expect(
      isBillEligibleForCategoryRule(
        { merchant: '瑞幸咖啡', categoryId: undefined },
        'refund',
        'cat-coffee',
      ),
    ).toBe(false);
  });

  test('checkCategoryRuleSuggestion 透传原生预览，跳过 already_covered / ineligible 与会话已忽略项', async () => {
    const createSuggestion: CategoryRuleSuggestion = {
      status: 'create',
      merchant: '瑞幸咖啡',
      categoryId: 'cat-coffee',
      categoryName: '咖啡',
      matchingHistoricalCount: 3,
      lockedSkippedCount: 1,
      suggestedPriority: 100,
    };
    (nativeData.previewCategoryRuleSuggestion as jest.Mock).mockResolvedValueOnce(
      createSuggestion,
    );

    const res = await checkCategoryRuleSuggestion({
      bill: { id: 'bill-1', merchant: ' 瑞幸咖啡 ', categoryId: 'cat-food' },
      nextBillType: 'expense',
      nextCategoryId: 'cat-coffee',
    });
    expect(res).toEqual(createSuggestion);
    expect(nativeData.previewCategoryRuleSuggestion).toHaveBeenCalledWith({
      merchant: '瑞幸咖啡',
      categoryId: 'cat-coffee',
      billType: 'expense',
      excludeBillId: 'bill-1',
    });

    // 用户点"暂不需要"后，本会话内同商户+同分类不再请求桥接
    dismissCategoryRuleSuggestion('瑞幸咖啡', 'cat-coffee');
    const dismissedRes = await checkCategoryRuleSuggestion({
      bill: { id: 'bill-2', merchant: '瑞幸咖啡', categoryId: 'cat-food' },
      nextBillType: 'expense',
      nextCategoryId: 'cat-coffee',
    });
    expect(dismissedRes).toBeNull();
    expect(nativeData.previewCategoryRuleSuggestion).toHaveBeenCalledTimes(1);

    // already_covered 返回 null
    (nativeData.previewCategoryRuleSuggestion as jest.Mock).mockResolvedValueOnce({
      ...createSuggestion,
      merchant: '星巴克',
      status: 'already_covered',
    });
    const covered = await checkCategoryRuleSuggestion({
      bill: { id: 'bill-3', merchant: '星巴克', categoryId: 'cat-food' },
      nextBillType: 'expense',
      nextCategoryId: 'cat-coffee',
    });
    expect(covered).toBeNull();
  });

  test('applyLearnedCategoryRule 新建规则（仅未来）只写 from_correction 规则并刷新规则缓存', async () => {
    (nativeData.addRule as jest.Mock).mockResolvedValueOnce(sampleRule);

    const suggestion: CategoryRuleSuggestion = {
      status: 'create',
      merchant: '瑞幸咖啡',
      categoryId: 'cat-coffee',
      categoryName: '咖啡',
      matchingHistoricalCount: 2,
      lockedSkippedCount: 0,
      suggestedPriority: 90,
    };
    const result = await applyLearnedCategoryRule(suggestion, '咖啡', false);

    expect(nativeData.addRule).toHaveBeenCalledWith({
      name: formatLearnedRuleName('瑞幸咖啡', '咖啡'),
      condition: 'merchant_equals',
      conditionValue: '瑞幸咖啡',
      action: 'set_category',
      actionValue: 'cat-coffee',
      priority: 90,
      source: 'from_correction',
    });
    expect(nativeData.applyRulesToExisting).not.toHaveBeenCalled();
    expect(invalidateCache.rules).toHaveBeenCalledTimes(1);
    expect(invalidateCache.bills).not.toHaveBeenCalled();
    expect(result).toEqual({
      rule: sampleRule,
      appliedCount: 0,
      historyApplyFailed: false,
    });
  });

  test('applyLearnedCategoryRule 更新规则并应用历史账单时刷新规则与账单缓存', async () => {
    (nativeData.updateRule as jest.Mock).mockResolvedValueOnce({
      ...sampleRule,
      id: 'rule-existing',
    });
    (nativeData.applyRulesToExisting as jest.Mock).mockResolvedValueOnce({ affected: 4 });

    const suggestion: CategoryRuleSuggestion = {
      status: 'update',
      merchant: '瑞幸咖啡',
      categoryId: 'cat-coffee',
      categoryName: '咖啡',
      existingRuleId: 'rule-existing',
      existingCategoryId: 'cat-food',
      existingCategoryName: '餐饮',
      matchingHistoricalCount: 4,
      lockedSkippedCount: 0,
      suggestedPriority: 80,
    };

    const result = await applyLearnedCategoryRule(suggestion, '咖啡', true);

    expect(nativeData.updateRule).toHaveBeenCalledWith('rule-existing', {
      name: '瑞幸咖啡 → 咖啡',
      condition: 'merchant_equals',
      conditionValue: '瑞幸咖啡',
      action: 'set_category',
      actionValue: 'cat-coffee',
      priority: 80,
      isActive: true,
      source: 'from_correction',
    });
    expect(nativeData.applyRulesToExisting).toHaveBeenCalledWith('rule-existing');
    expect(invalidateCache.bills).toHaveBeenCalledTimes(1);
    expect(invalidateCache.rules).toHaveBeenCalledTimes(2);
    expect(result.appliedCount).toBe(4);
    expect(result.historyApplyFailed).toBe(false);
  });

  test('历史批量应用失败时保留已保存规则并标记 historyApplyFailed', async () => {
    const warnSpy = jest.spyOn(console, 'warn').mockImplementation(() => {});
    (nativeData.addRule as jest.Mock).mockResolvedValueOnce(sampleRule);
    (nativeData.applyRulesToExisting as jest.Mock).mockRejectedValueOnce(new Error('DB busy'));

    const suggestion: CategoryRuleSuggestion = {
      status: 'create',
      merchant: '瑞幸咖啡',
      categoryId: 'cat-coffee',
      matchingHistoricalCount: 2,
      lockedSkippedCount: 0,
      suggestedPriority: 100,
    };

    const result = await applyLearnedCategoryRule(suggestion, '咖啡', true);

    expect(result).toEqual({
      rule: sampleRule,
      appliedCount: 0,
      historyApplyFailed: true,
    });
    expect(invalidateCache.rules).toHaveBeenCalledTimes(1);
    expect(invalidateCache.bills).not.toHaveBeenCalled();
    warnSpy.mockRestore();
  });
});

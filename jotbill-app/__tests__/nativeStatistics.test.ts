import { toReportStatistics } from '../src/utils/nativeStatistics';

test('将 Native 统计金额从分转换为元并保留分类标识', () => {
  const result = toReportStatistics({
    totalIncome: 12345,
    totalExpense: 6789,
    balance: 5556,
    incomeCount: 1,
    expenseCount: 2,
    expenseCategoryStats: [
      {
        categoryId: 'category-uuid',
        categoryName: '餐饮',
        categoryIcon: '🍜',
        amountMinor: 6789,
        percentage: 100,
        count: 2,
      },
    ],
    incomeCategoryStats: [],
    platformStats: [],
    monthlyTrends: [
      { month: '2026-08', year: 2026, income: 12345, expense: 6789 },
    ],
    dailyTrends: [
      { date: '2026-08-06', income: 12345, expense: 6789 },
    ],
  });

  expect(result.totalIncome).toBe(123.45);
  expect(result.totalExpense).toBe(67.89);
  expect(result.balance).toBe(55.56);
  expect(result.expenseCategoryStats[0]).toEqual({
    categoryId: 'category-uuid',
    categoryName: '餐饮',
    categoryIcon: '🍜',
    amount: 67.89,
    percentage: 100,
    count: 2,
  });
  expect(result.monthlyTrends[0]).toEqual({
    month: '2026-08',
    year: 2026,
    income: 123.45,
    expense: 67.89,
  });
});

import type { BillStatistics as NativeBillStatistics } from '../native';

/** 报表页面使用的金额单位为元，原生数据层统一返回分。 */
export interface ReportCategoryStatistic {
  categoryId: string;
  categoryName: string;
  categoryIcon?: string;
  amount: number;
  percentage: number;
  count: number;
}

export interface ReportStatistics {
  totalIncome: number;
  totalExpense: number;
  balance: number;
  incomeCount: number;
  expenseCount: number;
  expenseCategoryStats: ReportCategoryStatistic[];
  incomeCategoryStats: ReportCategoryStatistic[];
  monthlyTrends: Array<{
    month: string;
    year: number;
    income: number;
    expense: number;
  }>;
  dailyTrends: Array<{
    date: string;
    income: number;
    expense: number;
  }>;
}

/** 将 Native Data Module 返回的分转换为报表显示使用的元。 */
export function toReportStatistics(
  statistics: NativeBillStatistics,
): ReportStatistics {
  // 原生 `percentage` 不取整（DataModule:1072-1073 直接返回 Double），
  // 前端原样透传 ⇒ 三等分场景显示 `33.33333333333333%`，塞进
  // legendValue（minWidth 35）后行宽溢出。同项目 AssetCompositionChart.tsx
  // 正确写了 .toFixed(0)，说明这是遗漏而非设计。
  // 在这个**单一出口**统一保留一位小数：消费方无需各自处理，也不会
  // 出现"有的页面取整、有的不取整"的漂移。
  const roundPercentage = (value: number): number =>
    Number.isFinite(value) ? Math.round(value * 10) / 10 : 0;

  const toCategoryStats = (
    items: NativeBillStatistics['expenseCategoryStats'],
  ): ReportCategoryStatistic[] => items.map((item) => ({
    categoryId: item.categoryId,
    categoryName: item.categoryName,
    categoryIcon: item.categoryIcon,
    amount: item.amountMinor / 100,
    percentage: roundPercentage(item.percentage),
    count: item.count,
  }));

  return {
    totalIncome: statistics.totalIncome / 100,
    totalExpense: statistics.totalExpense / 100,
    balance: statistics.balance / 100,
    incomeCount: statistics.incomeCount,
    expenseCount: statistics.expenseCount,
    expenseCategoryStats: toCategoryStats(statistics.expenseCategoryStats),
    incomeCategoryStats: toCategoryStats(statistics.incomeCategoryStats),
    monthlyTrends: statistics.monthlyTrends.map((trend) => ({
      month: trend.month,
      year: trend.year,
      income: trend.income / 100,
      expense: trend.expense / 100,
    })),
    dailyTrends: statistics.dailyTrends.map((trend) => ({
      date: trend.date,
      income: trend.income / 100,
      expense: trend.expense / 100,
    })),
  };
}

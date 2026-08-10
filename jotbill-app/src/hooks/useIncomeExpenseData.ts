/**
 * useIncomeExpenseData - 收支网格数据 hook
 * 提供日/月/年收支数据查询
 */
import { useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';
import { nativeData, type Bill, type Category } from '../native';
import { QUERY_KEYS, CACHE_TIME, GC_TIME } from '../lib/queryClient';
import {
  toReportStatistics,
  type ReportStatistics,
} from '../utils/nativeStatistics';

interface LocalDateRange {
  startDate: Date;
  endDate: Date;
  startKey: string;
  endKey: string;
}

const EMPTY_REPORT_STATISTICS: ReportStatistics = {
  totalIncome: 0,
  totalExpense: 0,
  balance: 0,
  incomeCount: 0,
  expenseCount: 0,
  expenseCategoryStats: [],
  incomeCategoryStats: [],
  monthlyTrends: [],
  dailyTrends: [],
};

function formatDate(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

/** 严格按本地日历解析日期，拒绝溢出日期和非标准输入。 */
function parseLocalDate(dateString: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dateString);
  if (!match) return null;

  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  if (
    !Number.isInteger(year) ||
    !Number.isInteger(month) ||
    !Number.isInteger(day) ||
    year < 1 ||
    year > 9999 ||
    month < 1 ||
    month > 12 ||
    day < 1 ||
    day > 31
  ) {
    return null;
  }

  const date = new Date(0);
  date.setHours(0, 0, 0, 0);
  date.setFullYear(year, month - 1, day);
  return date.getFullYear() === year &&
    date.getMonth() === month - 1 &&
    date.getDate() === day
    ? date
    : null;
}

function createDateRange(startDate: Date, endDate: Date): LocalDateRange {
  return {
    startDate,
    endDate,
    startKey: formatDate(startDate),
    endKey: formatDate(endDate),
  };
}

function getMonthRange(year: number, month: number): LocalDateRange | null {
  if (!Number.isInteger(year) || !Number.isInteger(month) || month < 0 || month > 11) {
    return null;
  }

  const startDate = parseLocalDate(
    `${year}-${String(month + 1).padStart(2, '0')}-01`,
  );
  if (!startDate) return null;

  const endDate = new Date(startDate);
  endDate.setMonth(endDate.getMonth() + 1, 0);
  return createDateRange(startDate, endDate);
}

function getYearRange(year: number): LocalDateRange | null {
  if (!Number.isInteger(year)) return null;
  const startDate = parseLocalDate(`${year}-01-01`);
  const endDate = parseLocalDate(`${year}-12-31`);
  if (!startDate || !endDate) return null;
  return createDateRange(startDate, endDate);
}

export interface ReportBill {
  id: string;
  amount: number;
  type: 'income' | 'expense';
  date: string;
  description?: string;
  category?: {
    name: string;
    icon?: string;
  };
}

function toReportBill(bill: Bill, categories: Map<string, Category>): ReportBill | null {
  let type: ReportBill['type'];
  if (bill.type === 'income' || bill.type === 'refund') {
    type = 'income';
  } else if (bill.type === 'expense' || bill.type === 'fee') {
    type = 'expense';
  } else {
    return null;
  }

  const category = bill.categoryId ? categories.get(bill.categoryId) : undefined;
  return {
    id: bill.id,
    amount: bill.amountMinor / 100,
    type,
    date: bill.occurredAt,
    description: bill.description || bill.merchant || bill.productDescription,
    category: category
      ? { name: category.name, icon: category.icon }
      : undefined,
  };
}

/**
 * 获取某月的每日收支数据（日收支视图用）
 */
export function useDailyGridData(year: number, month: number, enabled = true) {
  const dateRange = useMemo(() => getMonthRange(year, month), [year, month]);

  const query = useQuery({
    queryKey: QUERY_KEYS.dailyGrid(year, month),
    queryFn: async () => {
      if (!enabled || !dateRange) return EMPTY_REPORT_STATISTICS;
      return toReportStatistics(
        await nativeData.getBillStatistics({
          startDate: dateRange.startKey,
          endDate: dateRange.endKey,
        }),
      );
    },
    enabled: enabled && dateRange !== null,
    staleTime: CACHE_TIME.BILLS_LIST,
    gcTime: GC_TIME.DEFAULT,
  });

  const dailyMap = useMemo(() => {
    const map = new Map<string, { income: number; expense: number }>();
    if (!enabled || !dateRange) return map;

    const trendByDate = new Map(
      (query.data?.dailyTrends ?? []).map((trend) => [trend.date, trend]),
    );
    const cursor = new Date(dateRange.startDate);
    while (cursor.getTime() <= dateRange.endDate.getTime()) {
      const dateKey = formatDate(cursor);
      const trend = trendByDate.get(dateKey);
      map.set(dateKey, {
        income: trend?.income ?? 0,
        expense: trend?.expense ?? 0,
      });
      cursor.setDate(cursor.getDate() + 1);
    }
    return map;
  }, [query.data, dateRange, enabled]);

  return {
    dailyMap,
    statistics: query.data,
    isLoading: query.isLoading,
    refetch: query.refetch,
  };
}

/**
 * 获取某天的账单列表（日收支明细用）
 */
export function useDailyBills(date: string | null) {
  const dateKey = useMemo(() => {
    const parsedDate = date ? parseLocalDate(date) : null;
    return parsedDate ? formatDate(parsedDate) : '';
  }, [date]);

  const query = useQuery({
    queryKey: QUERY_KEYS.dailyBills(dateKey),
    queryFn: async () => {
      if (!dateKey) return [];
      const [response, categories] = await Promise.all([
        nativeData.getBills({
          startDate: dateKey,
          endDate: dateKey,
          limit: 100,
          orderBy: 'date',
          orderDirection: 'desc',
        }),
        nativeData.getCategories(),
      ]);
      const categoryMap = new Map(categories.map((category) => [category.id, category]));
      return response.items.flatMap((bill) => {
        const reportBill = toReportBill(bill, categoryMap);
        return reportBill ? [reportBill] : [];
      });
    },
    enabled: !!dateKey,
    staleTime: CACHE_TIME.BILL_DETAIL,
    gcTime: GC_TIME.DEFAULT,
  });

  return {
    bills: query.data ?? [],
    isLoading: query.isLoading,
  };
}

/**
 * 获取某年12个月的收支数据（月收支视图用）
 */
export function useMonthlyGridData(year: number) {
  const dateRange = useMemo(() => getYearRange(year), [year]);

  const query = useQuery({
    queryKey: QUERY_KEYS.monthlyGrid(year),
    queryFn: async () => {
      if (!dateRange) return EMPTY_REPORT_STATISTICS;
      return toReportStatistics(
        await nativeData.getBillStatistics({
          startDate: dateRange.startKey,
          endDate: dateRange.endKey,
        }),
      );
    },
    enabled: dateRange !== null,
    staleTime: CACHE_TIME.STATISTICS,
    gcTime: GC_TIME.DEFAULT,
  });

  const monthlyData = useMemo(() => {
    const trendByMonth = new Map(
      (query.data?.monthlyTrends ?? []).map((trend) => [trend.month, trend]),
    );
    return Array.from({ length: 12 }, (_, index) => {
      const month = index + 1;
      const monthKey = `${year}-${String(month).padStart(2, '0')}`;
      const trend = trendByMonth.get(monthKey);
      return {
        month,
        income: trend?.income ?? 0,
        expense: trend?.expense ?? 0,
      };
    });
  }, [query.data, year]);

  return {
    monthlyData,
    statistics: query.data,
    isLoading: query.isLoading,
    refetch: query.refetch,
  };
}

/**
 * 获取近5年的收支数据（年收支视图用）
 * 单次请求，前端按年聚合
 */
export function useYearlyGridData() {
  const currentYear = new Date().getFullYear();
  const startYear = currentYear - 4;
  const dateRange = useMemo(() => {
    const startDate = parseLocalDate(`${startYear}-01-01`);
    const endDate = parseLocalDate(`${currentYear}-12-31`);
    return startDate && endDate ? createDateRange(startDate, endDate) : null;
  }, [startYear, currentYear]);

  const query = useQuery({
    queryKey: QUERY_KEYS.yearlyGrid(startYear, currentYear),
    queryFn: async () => {
      if (!dateRange) return EMPTY_REPORT_STATISTICS;
      return toReportStatistics(
        await nativeData.getBillStatistics({
          startDate: dateRange.startKey,
          endDate: dateRange.endKey,
        }),
      );
    },
    enabled: dateRange !== null,
    staleTime: CACHE_TIME.STATISTICS * 2, // 10min
    gcTime: GC_TIME.LONG,
  });

  // 按年聚合
  const yearlyData = useMemo(() => {
    const years: Array<{ year: number; income: number; expense: number }> = [];
    for (let y = startYear; y <= currentYear; y++) {
      years.push({ year: y, income: 0, expense: 0 });
    }

    if (query.data?.monthlyTrends) {
      query.data.monthlyTrends.forEach((t) => {
        const yearItem = years.find((y) => y.year === t.year);
        if (yearItem) {
          yearItem.income += t.income;
          yearItem.expense += t.expense;
        }
      });
    }

    return years;
  }, [query.data, startYear, currentYear]);

  // 获取某年的12月数据（从已缓存数据中提取）
  const getMonthlyDataForYear = (year: number) => {
    const trendByMonth = new Map(
      (query.data?.monthlyTrends ?? [])
        .filter((trend) => trend.year === year)
        .map((trend) => [trend.month, trend]),
    );
    return Array.from({ length: 12 }, (_, index) => {
      const month = index + 1;
      const monthKey = `${year}-${String(month).padStart(2, '0')}`;
      const trend = trendByMonth.get(monthKey);
      return {
        month,
        income: trend?.income ?? 0,
        expense: trend?.expense ?? 0,
      };
    });
  };

  return {
    yearlyData,
    getMonthlyDataForYear,
    isLoading: query.isLoading,
    refetch: query.refetch,
  };
}

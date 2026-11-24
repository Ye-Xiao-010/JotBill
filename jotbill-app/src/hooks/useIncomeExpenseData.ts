/**
 * useIncomeExpenseData - 收支网格数据 hook
 * 提供日/月/年收支数据查询
 */
import { useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';
import { nativeData, type Bill, type Category } from '../native';
import { QUERY_KEYS, CACHE_TIME, GC_TIME } from '../lib/queryClient';
import { toReportStatistics } from '../utils/nativeStatistics';

function getMonthNumber(month: string): number {
  const separatorIndex = month.indexOf('-');
  return Number(separatorIndex >= 0 ? month.slice(separatorIndex + 1) : month);
}

export interface ReportBill {
  id: string;
  amount: number;
  type: 'income' | 'expense';
  date: string;
  description?: string;
  accountId?: string;
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
    accountId: bill.accountId,
    category: category
      ? { name: category.name, icon: category.icon }
      : undefined,
  };
}

/**
 * 获取某月的每日收支数据（日收支视图用）
 */
export function useDailyGridData(year: number, month: number, enabled = true) {
  const startDate = `${year}-${String(month + 1).padStart(2, '0')}-01`;
  const lastDay = new Date(year, month + 1, 0).getDate();
  const endDate = `${year}-${String(month + 1).padStart(2, '0')}-${String(lastDay).padStart(2, '0')}`;

  const query = useQuery({
    queryKey: QUERY_KEYS.dailyGrid(year, month),
    queryFn: async () => {
      return toReportStatistics(
        await nativeData.getBillStatistics({ startDate, endDate }),
      );
    },
    staleTime: CACHE_TIME.BILLS_LIST,
    gcTime: GC_TIME.DEFAULT,
    // 未选中月份时不发起请求(此前默认请求 1 月的日明细,纯多余桥接调用)
    enabled,
  });

  const dailyMap = useMemo(() => {
    const map = new Map<string, { income: number; expense: number }>();
    if (query.data?.dailyTrends) {
      query.data.dailyTrends.forEach((d) => {
        map.set(d.date, { income: d.income, expense: d.expense });
      });
    }
    return map;
  }, [query.data]);

  return {
    dailyMap,
    statistics: query.data,
    isLoading: query.isLoading,
    isFetching: query.isFetching,
    // 失败时 dailyMap 是空 Map —— CalendarGrid 会画出一张"整月无记录"的
    // 日历，DailyView 的空态文案则是"当天暂无记录"。故障伪装成了事实。
    isError: query.isError,
    error: query.error,
    refetch: query.refetch,
  };
}

/**
 * 获取某天的账单列表（日收支明细用）
 */
export function useDailyBills(date: string | null) {
  const query = useQuery({
    queryKey: QUERY_KEYS.dailyBills(date || ''),
    queryFn: async () => {
      if (!date) return { bills: [] as ReportBill[], total: 0, hasMore: false };
      const [response, categories] = await Promise.all([
        nativeData.getBills({
          startDate: date,
          endDate: date,
          limit: 100,
          orderBy: 'date',
          orderDirection: 'desc',
        }),
        nativeData.getCategories(),
      ]);
      const categoryMap = new Map(categories.map((category) => [category.id, category]));
      const bills = response.items.flatMap((bill) => {
        const reportBill = toReportBill(bill, categoryMap);
        return reportBill ? [reportBill] : [];
      });
      // ⚠️ 原生返回分页信息（total/hasMore），但此前只取 items。
      // limit=100 意味着单日 100 笔以上的账单被静默截断，UI 一个字都不说 ——
      // 而上方的当日汇总显示的是**全量** income/expense，于是出现
      // "汇总 ¥5,000，明细只列了 ¥300" 的自相矛盾画面。
      // 现在把 total / hasMore 传出去，由调用方决定提示或分页。
      return { bills, total: response.total, hasMore: response.hasMore };
    },
    enabled: !!date,
    staleTime: CACHE_TIME.BILL_DETAIL,
    gcTime: GC_TIME.DEFAULT,
  });

  return {
    bills: query.data?.bills ?? [],
    /** 当日账单总笔数（native 计数，未被 limit 截断）。 */
    total: query.data?.total ?? 0,
    /** 是否有更多记录未被本页返回。 */
    hasMore: query.data?.hasMore ?? false,
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
    refetch: query.refetch,
  };
}

/**
 * 获取某年12个月的收支数据（月收支视图用）
 */
export function useMonthlyGridData(year: number, enabled = true) {
  const query = useQuery({
    queryKey: QUERY_KEYS.monthlyGrid(year),
    queryFn: async () => {
      return toReportStatistics(
        await nativeData.getBillStatistics({
          startDate: `${year}-01-01`,
          endDate: `${year}-12-31`,
        }),
      );
    },
    staleTime: CACHE_TIME.STATISTICS,
    gcTime: GC_TIME.DEFAULT,
    enabled,
  });

  const monthlyData = useMemo(() => {
    if (!query.data?.monthlyTrends) return [];
    return query.data.monthlyTrends.map((t) => ({
      month: getMonthNumber(t.month),
      income: t.income,
      expense: t.expense,
    }));
  }, [query.data]);

  return {
    monthlyData,
    statistics: query.data,
    isLoading: query.isLoading,
    isFetching: query.isFetching,
    isError: query.isError,
    error: query.error,
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

  const query = useQuery({
    queryKey: QUERY_KEYS.yearlyGrid(startYear, currentYear),
    queryFn: async () => {
      return toReportStatistics(
        await nativeData.getBillStatistics({
          startDate: `${startYear}-01-01`,
          endDate: `${currentYear}-12-31`,
        }),
      );
    },
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
    if (!query.data?.monthlyTrends) return [];
    return query.data.monthlyTrends
      .filter((t) => t.year === year)
      .map((t) => ({
        month: getMonthNumber(t.month),
        income: t.income,
        expense: t.expense,
      }));
  };

  return {
    yearlyData,
    getMonthlyDataForYear,
    isLoading: query.isLoading,
    isFetching: query.isFetching,
    // yearlyData 永远返回 5 个年份（补零），所以失败时它长得**完全正常**：
    // 五年柱状图全为零。用户会以为"今年没花钱"，而不是"读不出来"。
    isError: query.isError,
    error: query.error,
    refetch: query.refetch,
  };
}

/**
 * useAssetsData - 资产 Tab 数据 hook
 * 净资产以账户余额为准，收支统计只用于趋势和构成展示。
 */
import { useQuery } from '@tanstack/react-query';
import { nativeData } from '../native';
import { CACHE_TIME, GC_TIME } from '../lib/queryClient';
import { formatDate } from '../utils/date';
import { toReportStatistics } from '../utils/nativeStatistics';

export function useAssetsData() {
  const now = new Date();
  const currentYear = now.getFullYear();
  const currentMonth = now.getMonth();
  const trendStart = new Date(currentYear, currentMonth - 11, 1);
  const trendEnd = new Date(currentYear, currentMonth + 1, 0);

  // 收支统计用于构成和总收入/总支出，不再作为净资产来源。
  const totalQuery = useQuery({
    queryKey: ['total-assets'],
    queryFn: async () => {
      return toReportStatistics(await nativeData.getBillStatistics());
    },
    staleTime: CACHE_TIME.DASHBOARD,
    gcTime: GC_TIME.DEFAULT,
  });

  const netWorthQuery = useQuery({
    queryKey: ['net-worth'],
    queryFn: () => nativeData.getNetWorth(),
    staleTime: CACHE_TIME.DASHBOARD,
    gcTime: GC_TIME.DEFAULT,
  });

  // 资产趋势必须使用 Native 账户余额时间线，不能用收支差反推净资产。
  const assetTimelineQuery = useQuery({
    queryKey: ['asset-balance-timeline', currentYear, currentMonth],
    queryFn: () =>
      nativeData.getAccountBalanceTimeline({
        startDate: formatDate(trendStart),
        endDate: formatDate(trendEnd),
        period: 'month',
      }),
    staleTime: CACHE_TIME.STATISTICS,
    gcTime: GC_TIME.DEFAULT,
  });

  // 获取近12月统计（趋势折线图用）
  // 收支统计仍用于资产页的分类构成展示。
  const trendQuery = useQuery({
    queryKey: ['asset-trend', currentYear, currentMonth],
    queryFn: async () => {
      const statistics = await nativeData.getBillStatistics({
        startDate: formatDate(trendStart),
        endDate: formatDate(trendEnd),
      });
      return toReportStatistics(statistics);
    },
    staleTime: CACHE_TIME.STATISTICS,
    gcTime: GC_TIME.DEFAULT,
  });

  const netWorth = (netWorthQuery.data?.netWorthMinor ?? 0) / 100;
  const totalAssets = (netWorthQuery.data?.totalAssetsMinor ?? 0) / 100;
  const totalLiabilities = (netWorthQuery.data?.totalLiabilitiesMinor ?? 0) / 100;
  const totalIncome = totalQuery.data?.totalIncome ?? 0;
  const totalExpense = totalQuery.data?.totalExpense ?? 0;

  // Native 返回分单位，转换为展示金额只在这里进行一次。
  const assetTrendData = (assetTimelineQuery.data?.points ?? []).map((point) => ({
    month: point.period,
    value: point.netWorthMinor / 100,
  }));

  // 收入/支出分类构成（用于饼图）
  const incomeCategoryStats = trendQuery.data?.incomeCategoryStats ?? [];
  const expenseCategoryStats = trendQuery.data?.expenseCategoryStats ?? [];

  const isError =
    totalQuery.isError ||
    netWorthQuery.isError ||
    assetTimelineQuery.isError ||
    trendQuery.isError;
  const error =
    totalQuery.error ??
    netWorthQuery.error ??
    assetTimelineQuery.error ??
    trendQuery.error;

  // 趋势图日期范围描述
  const trendDateRange = `${trendStart.getFullYear()}.${trendStart.getMonth() + 1} - ${
    now.getFullYear()
  }.${now.getMonth() + 1}`;

  return {
    netWorth,
    totalAssets,
    totalLiabilities,
    totalIncome,
    totalExpense,
    assetTrendData,
    assetTrendUnavailable: false,
    trendDateRange,
    incomeCategoryStats,
    expenseCategoryStats,
    isLoading:
      totalQuery.isLoading ||
      netWorthQuery.isLoading ||
      assetTimelineQuery.isLoading ||
      trendQuery.isLoading,
    isFetching:
      totalQuery.isFetching ||
      netWorthQuery.isFetching ||
      assetTimelineQuery.isFetching ||
      trendQuery.isFetching,
    isError,
    error,
    refetch: async () => {
      await Promise.all([
        totalQuery.refetch(),
        netWorthQuery.refetch(),
        assetTimelineQuery.refetch(),
        trendQuery.refetch(),
      ]);
    },
  };
}

/**
 * useAssetsData - 资产 Tab 数据 hook
 * 净资产以账户余额为准，收支统计只用于趋势和构成展示。
 */
import { useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';
import { nativeData } from '../native';
import { QUERY_KEYS, CACHE_TIME, GC_TIME } from '../lib/queryClient';
import { formatDate } from '../utils/date';
import { toReportStatistics } from '../utils/nativeStatistics';

export function useAssetsData() {
  const now = new Date();
  const currentYear = now.getFullYear();
  const trendStart = new Date(now.getFullYear(), now.getMonth() - 11, 1);
  const trendEnd = new Date(now.getFullYear(), now.getMonth() + 1, 0);

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

  // 获取近12月统计（趋势折线图用）
  const trendQuery = useQuery({
    queryKey: QUERY_KEYS.assetTrend(currentYear),
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

  // 计算资产趋势折线图数据（逐月累计）
  const assetTrendData = useMemo(() => {
    if (!trendQuery.data?.monthlyTrends || trendQuery.data.monthlyTrends.length === 0) {
      return [];
    }

    const trends = trendQuery.data.monthlyTrends;
    const totalNetInRange = trends.reduce((sum, t) => sum + (t.income - t.expense), 0);

    // 从后往前反推每月末的累计资产
    let cumulative = 0;
    return trends.map((t) => {
      cumulative += t.income - t.expense;
      return {
        month: t.month,
        value: Math.round((netWorth - totalNetInRange + cumulative) * 100) / 100,
      };
    });
  }, [trendQuery.data, netWorth]);

  // 收入/支出分类构成（用于饼图）
  const incomeCategoryStats = trendQuery.data?.incomeCategoryStats ?? [];
  const expenseCategoryStats = trendQuery.data?.expenseCategoryStats ?? [];

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
    trendDateRange,
    incomeCategoryStats,
    expenseCategoryStats,
    isLoading: totalQuery.isLoading || netWorthQuery.isLoading || trendQuery.isLoading,
    refetch: async () => {
      await Promise.all([
        totalQuery.refetch(),
        netWorthQuery.refetch(),
        trendQuery.refetch(),
      ]);
    },
  };
}

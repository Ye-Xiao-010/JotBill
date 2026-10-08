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

  /**
 * 资产趋势折线图数据。
   *
   * ⚠️ 此前是"用当前净资产反推历史各月资产"：
   *     value = netWorth - totalNetInRange + cumulative
   * 这隐含一个等式 `netWorth == 窗口期初净资产 + Σ(收入-支出)`，而实际上
   * `netWorth` 来自 `AccountDao.getNetWorth()` —— 它是**账户余额**口径，
   * 包含 `initial_balance_minor`（开户初始余额）与 `balance_calibrations`
   * （余额校准），**这些从不对应任何账单**。
   *
   * 后果：期初 5 万、近 12 个月净流入 3 千的用户，曲线第一个点是 -47000 而不是 0，
   * 整条线被平移了 5 万。而同一个页面上方 `AssetsTab` 明写"净资产（账户余额）"
   * = 5.3 万 —— 同页两个数字互相矛盾，且下方那张是错的。
   *
   * 修：改为**诚实的收支净额累计**（不再锚定 netWorth）。这是账单数据唯一
   * 能支撑的口径 —— 要画真实的"资产余额历史"需要原生提供按时间切片的
   * 余额查询（对 audit_entries 的余额变更回放），那属于新增功能。
   */
  const assetTrendData = useMemo(() => {
    const trends = trendQuery.data?.monthlyTrends ?? [];
    let cumulative = 0;
    return trends.map((t) => {
      cumulative += t.income - t.expense;
      return {
        month: t.month,
        value: Math.round(cumulative * 100) / 100,
      };
    });
  }, [trendQuery.data]);

  // 收入/支出分类构成（用于饼图）
  const incomeCategoryStats = trendQuery.data?.incomeCategoryStats ?? [];
  const expenseCategoryStats = trendQuery.data?.expenseCategoryStats ?? [];

  // 趋势图日期范围描述
  const trendDateRange = `${trendStart.getFullYear()}.${trendStart.getMonth() + 1} - ${
    now.getFullYear()
  }.${now.getMonth() + 1}`;

  /**
   * 聚合错误。三个 query 任意一个失败都要冒泡：
   * 此前本 hook 只返回 isLoading，AssetsTab 于是把 `?? 0` 的兜底值
   * 直接渲染成"净资产（账户余额）¥0.00" —— 用户看到的是"我的钱没了"
   * 而不是"读不出来"。netWorth 失败和账户真的净资产为 0 在界面上完全一样。
   */
  const error = netWorthQuery.error ?? totalQuery.error ?? trendQuery.error ?? null;
  const isError =
    netWorthQuery.isError || totalQuery.isError || trendQuery.isError;

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
    isFetching:
      totalQuery.isFetching || netWorthQuery.isFetching || trendQuery.isFetching,
    isError,
    error,
    refetch: async () => {
      await Promise.all([
        totalQuery.refetch(),
        netWorthQuery.refetch(),
        trendQuery.refetch(),
      ]);
    },
  };
}

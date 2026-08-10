/**
 * useAssetsData - 资产 Tab 数据 hook
 * 净资产以账户余额为准，收支统计只用于趋势和构成展示。
 */
import { useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';
import {
  nativeData,
  type Account,
  type BalanceCalibration,
} from '../native';
import { CACHE_TIME, GC_TIME } from '../lib/queryClient';
import { formatDate } from '../utils/date';
import {
  toReportStatistics,
  type ReportStatistics,
} from '../utils/nativeStatistics';

interface AssetTrendQueryData {
  statistics: ReportStatistics;
  calibrationByMonth: Record<string, number>;
}

function getMonthKey(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}

function getMonthKeys(startDate: Date, endDate: Date): string[] {
  const keys: string[] = [];
  const cursor = new Date(startDate);
  while (cursor.getTime() <= endDate.getTime()) {
    keys.push(getMonthKey(cursor));
    cursor.setMonth(cursor.getMonth() + 1, 1);
  }
  return keys;
}

function netWorthBalance(balanceMinor: number, kind: Account['kind']): number {
  return kind === 'liability' ? -Math.abs(balanceMinor) : balanceMinor;
}

function calibrationEffectMinor(
  calibration: BalanceCalibration,
  kind: Account['kind'],
): number {
  return netWorthBalance(calibration.afterBalanceMinor, kind) -
    netWorthBalance(calibration.beforeBalanceMinor, kind);
}

function calibrationMonthKey(createdAt: string): string | null {
  const date = new Date(createdAt);
  return Number.isNaN(date.getTime()) ? null : getMonthKey(date);
}

export function useAssetsData() {
  const now = new Date();
  const currentYear = now.getFullYear();
  const currentMonth = now.getMonth();
  const trendStart = new Date(currentYear, currentMonth - 11, 1);
  const trendEnd = new Date(currentYear, currentMonth + 1, 0);
  const monthKeys = useMemo(
    () => getMonthKeys(trendStart, trendEnd),
    [currentYear, currentMonth],
  );

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
    queryKey: ['asset-trend', currentYear, currentMonth],
    queryFn: async (): Promise<AssetTrendQueryData> => {
      const [statistics, accounts] = await Promise.all([
        nativeData.getBillStatistics({
          startDate: formatDate(trendStart),
          endDate: formatDate(trendEnd),
        }),
        nativeData.getAccounts(),
      ]);

      // 校准接口按账户提供，限制在启用账户，避免按账单逐条读取。
      const calibrationGroups = await Promise.all(
        accounts
          .filter((account) => account.isActive)
          .map(async (account) => ({
            kind: account.kind,
            calibrations: await nativeData.getBalanceCalibrations(account.id),
          })),
      );
      const firstMonth = monthKeys[0];
      const lastMonth = monthKeys[monthKeys.length - 1];
      const calibrationByMonth: Record<string, number> = {};
      calibrationGroups.forEach(({ kind, calibrations }) => {
        calibrations.forEach((calibration) => {
          const month = calibrationMonthKey(calibration.createdAt);
          if (!month) {
            throw new Error('余额校准记录时间格式无效');
          }
          if (month < firstMonth || month > lastMonth) return;
          calibrationByMonth[month] =
            (calibrationByMonth[month] ?? 0) + calibrationEffectMinor(calibration, kind);
        });
      });

      return {
        statistics: toReportStatistics(statistics),
        calibrationByMonth,
      };
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
    if (!trendQuery.data) {
      return [];
    }

    const trendsByMonth = new Map(
      trendQuery.data.statistics.monthlyTrends.map((trend) => [trend.month, trend]),
    );
    const monthlyNetChanges = monthKeys.map((month) => {
      const trend = trendsByMonth.get(month);
      const calibrationMinor = trendQuery.data?.calibrationByMonth[month] ?? 0;
      return {
        month,
        netChange: (trend?.income ?? 0) -
          (trend?.expense ?? 0) +
          calibrationMinor / 100,
      };
    });
    const totalNetInRange = monthlyNetChanges.reduce(
      (sum, item) => sum + item.netChange,
      0,
    );

    // 从后往前反推每月末的累计资产
    let cumulative = 0;
    return monthlyNetChanges.map((item) => {
      cumulative += item.netChange;
      return {
        month: item.month,
        value: Math.round((netWorth - totalNetInRange + cumulative) * 100) / 100,
      };
    });
  }, [trendQuery.data, netWorth, monthKeys]);

  // 收入/支出分类构成（用于饼图）
  const incomeCategoryStats = trendQuery.data?.statistics.incomeCategoryStats ?? [];
  const expenseCategoryStats = trendQuery.data?.statistics.expenseCategoryStats ?? [];

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

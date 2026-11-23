/**
 * SoftIncomeExpenseView - 柔光主题收支统计视图
 * 月 / 年切换；月视图内「趋势柱图 / 热力日历」二合一 + 选中日明细 + SVG 支出构成圆环与排行。
 * 严格遵循诚实状态约定：加载中显示骨架屏、失败时显式展示 QueryErrorBanner。
 */
import React, { useMemo, useState } from 'react';
import {
  ActivityIndicator,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { ChevronLeft, ChevronRight } from 'lucide-react-native';
import Svg, { Circle } from 'react-native-svg';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { spacing } from '../../theme/spacing';
import { useStyles } from '../../hooks';
import { useTheme } from '../../providers';
import {
  useDailyBills,
  useDailyGridData,
  useMonthlyGridData,
} from '../../hooks/useIncomeExpenseData';
import { QueryErrorBanner, SegmentedControl } from '../../components/ui';
import { CalendarSkeleton } from '../../components/skeleton/ReportSkeletons';
import CategoryIcon from '../../components/business/CategoryIcon';
import { getCategoryColor, getCategoryIcon } from '../../constants/app';
import { formatCurrency, formatNumber } from '../../utils/format';

type PeriodMode = 'month' | 'year';
type TrendMode = 'bars' | 'cal';

const PERIOD_OPTIONS = [
  { key: 'month', label: '月' },
  { key: 'year', label: '年' },
];

const TREND_OPTIONS = [
  { key: 'bars', label: '趋势' },
  { key: 'cal', label: '日历' },
];

const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日'];
const DONUT_PALETTE = ['#14B8A6', '#F97316', '#EC4899', '#3B82F6', '#8B5CF6', '#64748B'];

function formatDateKey(year: number, monthZeroBased: number, day: number): string {
  return `${year}-${String(monthZeroBased + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

function getCategoryAccent(name: string, index: number): string {
  const fromApp = getCategoryColor(name, '');
  if (fromApp && /^#[0-9a-fA-F]{6}$/.test(fromApp)) return fromApp;
  return DONUT_PALETTE[index % DONUT_PALETTE.length];
}

export default function SoftIncomeExpenseView() {
  const styles = useStyles(createStyles);
  const { colors, isDark } = useTheme();
  const navigation = useNavigation<any>();
  const now = new Date();

  const [period, setPeriod] = useState<PeriodMode>('month');
  const [trendView, setTrendView] = useState<TrendMode>('bars');
  const [year, setYear] = useState(now.getFullYear());
  const [month, setMonth] = useState(now.getMonth()); // 0..11
  const [selectedDay, setSelectedDay] = useState(now.getDate());
  const [selectedMonth, setSelectedMonth] = useState(now.getMonth() + 1); // 1..12

  const isCurrentMonth = year === now.getFullYear() && month === now.getMonth();
  const isCurrentYear = year === now.getFullYear();
  const daysInMonth = new Date(year, month + 1, 0).getDate();
  const clampedSelectedDay = Math.min(selectedDay, daysInMonth);

  const monthQuery = useDailyGridData(year, month, period === 'month');
  const yearQuery = useMonthlyGridData(year, period === 'year');

  const selectedDateKey =
    period === 'month' ? formatDateKey(year, month, clampedSelectedDay) : null;
  const dailyBillsQuery = useDailyBills(selectedDateKey);

  const activeQuery = period === 'month' ? monthQuery : yearQuery;
  const stats = activeQuery.statistics;

  const isNextDisabled = period === 'month'
    ? year > now.getFullYear() || (year === now.getFullYear() && month >= now.getMonth())
    : year >= now.getFullYear();

  const handlePrev = () => {
    if (period === 'month') {
      if (month === 0) {
        setYear(y => y - 1);
        setMonth(11);
      } else {
        setMonth(m => m - 1);
      }
      setSelectedDay(1);
    } else {
      setYear(y => y - 1);
      setSelectedMonth(12);
    }
  };

  const handleNext = () => {
    if (isNextDisabled) return;
    if (period === 'month') {
      const nextYear = month === 11 ? year + 1 : year;
      const nextMonth = month === 11 ? 0 : month + 1;
      setYear(nextYear);
      setMonth(nextMonth);
      setSelectedDay(
        nextYear === now.getFullYear() && nextMonth === now.getMonth()
          ? now.getDate()
          : 1,
      );
    } else {
      const nextYear = year + 1;
      setYear(nextYear);
      setSelectedMonth(nextYear === now.getFullYear() ? now.getMonth() + 1 : 12);
    }
  };

  // 当月每日支出数组（索引 0 = 1 日）
  const dailyExpenseList = useMemo(() => {
    const arr: number[] = [];
    for (let d = 1; d <= daysInMonth; d++) {
      const key = formatDateKey(year, month, d);
      arr.push(monthQuery.dailyMap.get(key)?.expense ?? 0);
    }
    return arr;
  }, [daysInMonth, year, month, monthQuery.dailyMap]);

  const elapsedDays = isCurrentMonth ? Math.min(now.getDate(), daysInMonth) : daysInMonth;
  const recordedDays = useMemo(
    () => dailyExpenseList.filter(v => v > 0).length,
    [dailyExpenseList],
  );
  const dailyAvg = elapsedDays > 0 ? (stats?.totalExpense ?? 0) / elapsedDays : 0;

  // 柱图封顶阈值：避免单日大额房租把其余日子压成一条线
  const dailyBarCap = useMemo(() => {
    const nonZero = dailyExpenseList.filter(v => v > 0);
    if (nonZero.length === 0) return 100;
    const maxVal = Math.max(...nonZero);
    const softCap = dailyAvg > 0 ? Math.max(dailyAvg * 2.4, 50) : maxVal;
    return Math.min(maxVal, softCap);
  }, [dailyExpenseList, dailyAvg]);

  // 年视图 12 个月支出数组
  const monthlyExpenseList = useMemo(() => {
    const arr = Array.from({ length: 12 }, () => 0);
    yearQuery.monthlyData.forEach(item => {
      if (item.month >= 1 && item.month <= 12) {
        arr[item.month - 1] = item.expense;
      }
    });
    return arr;
  }, [yearQuery.monthlyData]);

  const monthlyMax = useMemo(
    () => Math.max(...monthlyExpenseList, 100),
    [monthlyExpenseList],
  );
  const elapsedMonths = isCurrentYear ? now.getMonth() + 1 : 12;
  const monthlyAvg = elapsedMonths > 0 ? (stats?.totalExpense ?? 0) / elapsedMonths : 0;

  // 支出构成（前 5 + 其他汇总）
  const breakdown = useMemo(() => {
    const raw = [...(stats?.expenseCategoryStats ?? [])].sort((a, b) => b.amount - a.amount);
    if (raw.length <= 6) {
      return raw.map((item, idx) => ({
        ...item,
        color: getCategoryAccent(item.categoryName, idx),
      }));
    }
    const top = raw.slice(0, 5).map((item, idx) => ({
      ...item,
      color: getCategoryAccent(item.categoryName, idx),
    }));
    const rest = raw.slice(5);
    const restAmount = rest.reduce((s, r) => s + r.amount, 0);
    const restPct = Math.round(rest.reduce((s, r) => s + r.percentage, 0) * 10) / 10;
    const restCount = rest.reduce((s, r) => s + r.count, 0);
    return [
      ...top,
      {
        categoryId: '__rest__',
        categoryName: '其他',
        categoryIcon: '📝',
        amount: restAmount,
        percentage: restPct,
        count: restCount,
        color: '#64748B',
      },
    ];
  }, [stats?.expenseCategoryStats]);

  // 日历前导空格（周一为首列）
  const firstDayOffset = useMemo(() => {
    const jsDay = new Date(year, month, 1).getDay(); // 0=周日..6=周六
    return (jsDay + 6) % 7;
  }, [year, month]);

  const totalExpense = stats?.totalExpense ?? 0;
  const totalIncome = stats?.totalIncome ?? 0;
  const totalBalance = totalIncome - totalExpense;
  const maxPct = breakdown[0]?.percentage || 1;

  // SVG 圆环参数
  const donutRadius = 50;
  const donutCircumference = 2 * Math.PI * donutRadius;

  const renderDonut = () => {
    let offset = 0;
    return (
      <Svg width={128} height={128} viewBox="0 0 128 128">
        <Circle
          cx={64}
          cy={64}
          r={donutRadius}
          fill="none"
          stroke={colors.surfaceMuted}
          strokeWidth={14}
        />
        {breakdown.map(seg => {
          const arcLen = (seg.percentage / 100) * donutCircumference;
          const visibleLen = Math.max(arcLen - 2.5, 0.2);
          const currentOffset = offset;
          offset += arcLen;
          return (
            <Circle
              key={seg.categoryId}
              cx={64}
              cy={64}
              r={donutRadius}
              fill="none"
              stroke={seg.color}
              strokeWidth={14}
              strokeDasharray={`${visibleLen} ${donutCircumference}`}
              strokeDashoffset={-currentOffset}
              rotation={-90}
              origin="64, 64"
              strokeLinecap="round"
            />
          );
        })}
      </Svg>
    );
  };

  return (
    <ScrollView
      style={styles.container}
      contentContainerStyle={styles.content}
      refreshControl={
        <RefreshControl
          refreshing={activeQuery.isFetching && !activeQuery.isLoading}
          onRefresh={() => { void activeQuery.refetch(); }}
        />
      }
      showsVerticalScrollIndicator={false}
    >
      {/* 期间切换条 */}
      <View style={styles.periodBar}>
        <View style={styles.periodNav}>
          <TouchableOpacity
            style={styles.navBtn}
            onPress={handlePrev}
            activeOpacity={0.7}
            accessibilityLabel="上一期"
          >
            <ChevronLeft size={18} color={colors.textSecondary} strokeWidth={2} />
          </TouchableOpacity>
          <Text style={styles.periodTitle}>
            {period === 'month' ? `${year}年${month + 1}月` : `${year}年`}
          </Text>
          <TouchableOpacity
            style={[styles.navBtn, isNextDisabled && styles.navBtnDisabled]}
            onPress={handleNext}
            disabled={isNextDisabled}
            activeOpacity={0.7}
            accessibilityLabel="下一期"
          >
            <ChevronRight size={18} color={colors.textSecondary} strokeWidth={2} />
          </TouchableOpacity>
        </View>
        <View style={styles.periodSegWrap}>
          <SegmentedControl
            size="small"
            options={PERIOD_OPTIONS}
            selectedKey={period}
            onSelect={k => setPeriod(k as PeriodMode)}
          />
        </View>
      </View>

      {activeQuery.isError ? (
        <QueryErrorBanner
          error={activeQuery.error}
          title={period === 'month' ? '月度统计加载失败' : '年度统计加载失败'}
          hint="下方数据为占位 0，不代表该期间没有收支。"
          onRetry={() => { void activeQuery.refetch(); }}
          style={styles.errorBanner}
        />
      ) : null}

      {activeQuery.isLoading ? (
        <CalendarSkeleton showNav={false} fill={false} />
      ) : (
        <>
          {/* 三列汇总卡 */}
          <View style={styles.sumCard}>
            <View style={styles.sumCell}>
              <Text style={styles.sumLabel}>支出</Text>
              <Text style={[styles.sumValue, styles.expenseText]} numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.75}>
                {formatNumber(totalExpense, 2)}
              </Text>
            </View>
            <View style={[styles.sumCell, styles.sumCellBorder]}>
              <Text style={styles.sumLabel}>收入</Text>
              <Text style={[styles.sumValue, styles.incomeText]} numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.75}>
                {formatNumber(totalIncome, 2)}
              </Text>
            </View>
            <View style={[styles.sumCell, styles.sumCellBorder]}>
              <Text style={styles.sumLabel}>结余</Text>
              <Text style={styles.sumValue} numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.75}>
                {formatNumber(totalBalance, 2)}
              </Text>
            </View>
          </View>

          {/* 趋势 / 热力日历卡片 */}
          <View style={styles.panelCard}>
            <View style={styles.panelHead}>
              <Text style={styles.panelTitle}>
                {period === 'month' ? '每日支出' : '每月支出'}
              </Text>
              {period === 'month' ? (
                <View style={styles.trendSegWrap}>
                  <SegmentedControl
                    size="small"
                    options={TREND_OPTIONS}
                    selectedKey={trendView}
                    onSelect={k => setTrendView(k as TrendMode)}
                  />
                </View>
              ) : null}
            </View>

            {/* 选中点读数 */}
            <View style={styles.tipLine}>
              <Text style={styles.tipAmount}>
                {period === 'month'
                  ? formatCurrency(dailyExpenseList[clampedSelectedDay - 1] ?? 0)
                  : formatCurrency(monthlyExpenseList[selectedMonth - 1] ?? 0)}
              </Text>
              <Text style={styles.tipDate}>
                {period === 'month'
                  ? `${month + 1}月${clampedSelectedDay}日${
                      isCurrentMonth && clampedSelectedDay === now.getDate() ? ' · 今天' : ''
                    }`
                  : `${year}年${selectedMonth}月${
                      isCurrentYear && selectedMonth === now.getMonth() + 1 ? ' · 进行中' : ''
                    }`}
              </Text>
            </View>

            {period === 'month' ? (
              trendView === 'bars' ? (
                <>
                  <View style={styles.barsArea}>
                    {dailyAvg > 0 && dailyBarCap > 0 ? (
                      <View
                        pointerEvents="none"
                        style={[
                          styles.avgLine,
                          {
                            bottom: `${Math.min(95, (dailyAvg / dailyBarCap) * 100)}%`,
                          },
                        ]}
                      >
                        <Text style={styles.avgLabel}>日均 {Math.round(dailyAvg)}</Text>
                      </View>
                    ) : null}
                    {dailyExpenseList.map((val, idx) => {
                      const dayNum = idx + 1;
                      const isFuture = isCurrentMonth && dayNum > now.getDate();
                      const isSelected = dayNum === clampedSelectedDay;
                      const ratio = dailyBarCap > 0 ? Math.min(val, dailyBarCap) / dailyBarCap : 0;
                      const heightPct = isFuture || val <= 0 ? 3 : Math.max(5, ratio * 100);
                      const isCapped = val > dailyBarCap;
                      return (
                        <TouchableOpacity
                          key={dayNum}
                          style={styles.barSlot}
                          disabled={isFuture}
                          onPress={() => setSelectedDay(dayNum)}
                          activeOpacity={0.75}
                        >
                          {isCapped ? <View style={styles.cappedDot} /> : null}
                          <View
                            style={[
                              styles.barFill,
                              { height: `${heightPct}%` },
                              isFuture && styles.barFuture,
                              isSelected && styles.barActive,
                            ]}
                          />
                        </TouchableOpacity>
                      );
                    })}
                  </View>
                  <View style={styles.axisRow}>
                    <Text style={styles.axisText}>1日</Text>
                    <Text style={styles.axisText}>8日</Text>
                    <Text style={styles.axisText}>15日</Text>
                    <Text style={styles.axisText}>22日</Text>
                    <Text style={styles.axisText}>{daysInMonth}日</Text>
                  </View>
                </>
              ) : (
                <View style={styles.calGrid}>
                  {WEEKDAYS.map(w => (
                    <View key={w} style={styles.calWeekdayCell}>
                      <Text style={styles.calWeekdayText}>{w}</Text>
                    </View>
                  ))}
                  {Array.from({ length: firstDayOffset }).map((_, i) => (
                    <View key={`blank-${i}`} style={styles.calCellWrap} />
                  ))}
                  {dailyExpenseList.map((val, idx) => {
                    const dayNum = idx + 1;
                    const isFuture = isCurrentMonth && dayNum > now.getDate();
                    const isSelected = dayNum === clampedSelectedDay;
                    const intensity = dailyBarCap > 0 ? Math.min(1, val / dailyBarCap) : 0;
                    const isHot = intensity > 0.55;
                    const bgOpacity = val > 0 ? 0.14 + intensity * 0.72 : 0;
                    const cellBg = isFuture
                      ? 'transparent'
                      : val > 0
                        ? `rgba(${isDark ? '139,141,251' : '79,70,229'}, ${bgOpacity.toFixed(2)})`
                        : colors.surfaceMuted;

                    return (
                      <View key={dayNum} style={styles.calCellWrap}>
                        <TouchableOpacity
                          disabled={isFuture}
                          onPress={() => setSelectedDay(dayNum)}
                          activeOpacity={0.75}
                          style={[
                            styles.calCell,
                            { backgroundColor: cellBg },
                            isSelected && styles.calCellActive,
                          ]}
                        >
                          <Text
                            style={[
                              styles.calDayText,
                              isFuture && styles.calFutureText,
                              isHot && styles.calHotText,
                            ]}
                          >
                            {dayNum}
                          </Text>
                          {!isFuture && val > 0 ? (
                            <Text
                              style={[
                                styles.calValText,
                                isHot && styles.calHotText,
                              ]}
                              numberOfLines={1}
                            >
                              {val >= 1000 ? `${(val / 1000).toFixed(1)}k` : Math.round(val)}
                            </Text>
                          ) : null}
                        </TouchableOpacity>
                      </View>
                    );
                  })}
                </View>
              )
            ) : (
              <>
                <View style={styles.barsArea}>
                  {monthlyExpenseList.map((val, idx) => {
                    const monthNum = idx + 1;
                    const isFuture = isCurrentYear && monthNum > now.getMonth() + 1;
                    const isSelected = monthNum === selectedMonth;
                    const heightPct = isFuture || val <= 0 ? 3 : Math.max(6, (val / monthlyMax) * 100);
                    return (
                      <TouchableOpacity
                        key={monthNum}
                        style={[styles.barSlot, { paddingHorizontal: 2 }]}
                        disabled={isFuture}
                        onPress={() => setSelectedMonth(monthNum)}
                        activeOpacity={0.75}
                      >
                        <View
                          style={[
                            styles.barFill,
                            { height: `${heightPct}%`, borderRadius: 6 },
                            isFuture && styles.barFuture,
                            isSelected && styles.barActive,
                          ]}
                        />
                      </TouchableOpacity>
                    );
                  })}
                </View>
                <View style={styles.axisRow}>
                  <Text style={styles.axisText}>1月</Text>
                  <Text style={styles.axisText}>4月</Text>
                  <Text style={styles.axisText}>7月</Text>
                  <Text style={styles.axisText}>10月</Text>
                  <Text style={styles.axisText}>12月</Text>
                </View>
              </>
            )}

            <View style={styles.panelFoot}>
              <Text style={styles.panelFootText}>
                {period === 'month'
                  ? `日均 ${formatCurrency(dailyAvg)} · 本月有支出 ${recordedDays} 天`
                  : `月均 ${formatCurrency(monthlyAvg)} · 全年累计 ${formatCurrency(totalExpense)}`}
              </Text>
            </View>
          </View>

          {/* 选中日账单明细（仅月视图且选中日存在加载/报错/记录时展示） */}
          {period === 'month' && (dailyBillsQuery.isLoading || dailyBillsQuery.isError || dailyBillsQuery.bills.length > 0) ? (
            <View style={styles.panelCard}>
              <View style={styles.panelHead}>
                <Text style={styles.panelTitle}>
                  {month + 1}月{clampedSelectedDay}日 明细
                </Text>
                <Text style={styles.tipDate}>
                  共 {dailyBillsQuery.total} 笔
                </Text>
              </View>
              {dailyBillsQuery.isError ? (
                <QueryErrorBanner
                  error={dailyBillsQuery.error}
                  title="当日明细加载失败"
                  onRetry={() => { void dailyBillsQuery.refetch(); }}
                  style={{ margin: 0 }}
                />
              ) : dailyBillsQuery.isLoading ? (
                <ActivityIndicator size="small" color={colors.primary} style={{ paddingVertical: 16 }} />
              ) : (
                <>
                  {dailyBillsQuery.bills.slice(0, 8).map((bill, idx) => {
                    const isInc = bill.type === 'income';
                    const catName = bill.category?.name || '未分类';
                    const catColor = getCategoryAccent(catName, idx);
                    return (
                      <TouchableOpacity
                        key={bill.id}
                        style={[styles.dayBillRow, idx > 0 && styles.dayBillBorder]}
                        onPress={() => navigation.navigate('BillDetail', { billId: bill.id })}
                        activeOpacity={0.72}
                      >
                        <View
                          style={[
                            styles.dayBillIcon,
                            { backgroundColor: `${catColor}${isDark ? '33' : '22'}` },
                          ]}
                        >
                          <CategoryIcon
                            icon={bill.category?.icon || getCategoryIcon(catName)}
                            size={16}
                            emojiStyle={{ fontSize: 16 }}
                          />
                        </View>
                        <View style={{ flex: 1 }}>
                          <Text style={styles.dayBillTitle} numberOfLines={1}>
                            {bill.description || catName}
                          </Text>
                          <Text style={styles.dayBillSub}>{catName}</Text>
                        </View>
                        <Text
                          style={[
                            styles.dayBillAmt,
                            isInc ? styles.incomeText : styles.expenseText,
                          ]}
                        >
                          {isInc ? '+' : '-'}
                          {formatNumber(bill.amount, 2)}
                        </Text>
                      </TouchableOpacity>
                    );
                  })}
                  {dailyBillsQuery.hasMore ? (
                    <Text style={styles.truncatedHint}>
                      仅展示前部分记录，当天共 {dailyBillsQuery.total} 笔（上方汇总为全量）。
                    </Text>
                  ) : null}
                </>
              )}
            </View>
          ) : null}

          {/* 支出构成圆环 + 排行 */}
          <View style={styles.panelCard}>
            <View style={styles.panelHead}>
              <Text style={styles.panelTitle}>支出构成</Text>
              <TouchableOpacity
                onPress={() => navigation.navigate('AllBills', { initialFilter: 'expense' })}
                activeOpacity={0.7}
              >
                <Text style={styles.panelLink}>全部支出 ›</Text>
              </TouchableOpacity>
            </View>

            {breakdown.length === 0 ? (
              <Text style={styles.emptyChartText}>该期间暂无支出记录</Text>
            ) : (
              <>
                <View style={styles.donutWrap}>
                  <View style={styles.donutBox}>
                    {renderDonut()}
                    <View style={styles.donutCenter}>
                      <Text style={styles.donutCenterLabel}>
                        {period === 'month' ? '月支出' : '年支出'}
                      </Text>
                      <Text style={styles.donutCenterValue} numberOfLines={1}>
                        {Math.round(totalExpense).toLocaleString('zh-CN')}
                      </Text>
                    </View>
                  </View>
                  <View style={styles.legendList}>
                    {breakdown.map(item => (
                      <View key={item.categoryId} style={styles.legendRow}>
                        <View style={[styles.legendDot, { backgroundColor: item.color }]} />
                        <Text style={styles.legendName} numberOfLines={1}>
                          {item.categoryName}
                        </Text>
                        <Text style={styles.legendPct}>{item.percentage}%</Text>
                      </View>
                    ))}
                  </View>
                </View>

                <View style={styles.rankList}>
                  {breakdown.slice(0, 5).map((item, idx) => (
                    <View
                      key={item.categoryId}
                      style={[styles.rankRow, idx > 0 && styles.rankRowBorder]}
                    >
                      <View
                        style={[
                          styles.rankIcon,
                          { backgroundColor: `${item.color}${isDark ? '33' : '22'}` },
                        ]}
                      >
                        <CategoryIcon
                          icon={item.categoryIcon || getCategoryIcon(item.categoryName)}
                          size={16}
                          emojiStyle={{ fontSize: 16 }}
                        />
                      </View>
                      <View style={styles.rankMain}>
                        <View style={styles.rankTop}>
                          <Text style={styles.rankName} numberOfLines={1}>
                            {item.categoryName}
                          </Text>
                          <Text style={styles.rankAmt}>{formatCurrency(item.amount)}</Text>
                        </View>
                        <View style={styles.rankTrack}>
                          <View
                            style={[
                              styles.rankFill,
                              {
                                width: `${Math.min(100, (item.percentage / maxPct) * 100)}%`,
                                backgroundColor: item.color,
                              },
                            ]}
                          />
                        </View>
                        <Text style={styles.rankSub}>
                          {item.count} 笔 · {item.percentage}%
                        </Text>
                      </View>
                    </View>
                  ))}
                </View>
              </>
            )}
          </View>
        </>
      )}
    </ScrollView>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape, isDark: boolean) => ({
  ...StyleSheet.create({
    container: { flex: 1 },
    content: {
      paddingHorizontal: spacing.lg,
      paddingBottom: 116,
    },
    periodBar: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingVertical: 6,
      marginBottom: 8,
    },
    periodNav: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 2,
    },
    navBtn: {
      width: 34,
      height: 34,
      borderRadius: 12,
      alignItems: 'center',
      justifyContent: 'center',
    },
    navBtnDisabled: {
      opacity: 0.32,
    },
    periodTitle: {
      fontSize: 15,
      fontWeight: '600',
      color: colors.textPrimary,
      minWidth: 92,
      textAlign: 'center',
      fontVariant: ['tabular-nums'],
    },
    periodSegWrap: {
      width: 100,
    },
    errorBanner: {
      margin: 0,
      marginBottom: 12,
    },
    sumCard: {
      flexDirection: 'row',
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.borderWidth.card,
      borderColor: colors.cardBorder,
      paddingVertical: 16,
      paddingHorizontal: 4,
      ...shape.elevation.card,
    },
    sumCell: {
      flex: 1,
      paddingHorizontal: 10,
      gap: 3,
    },
    sumCellBorder: {
      borderLeftWidth: StyleSheet.hairlineWidth,
      borderLeftColor: colors.divider,
    },
    sumLabel: {
      fontSize: 12,
      color: colors.textTertiary,
    },
    sumValue: {
      fontSize: 14.5,
      fontWeight: '700',
      color: colors.textPrimary,
      fontVariant: ['tabular-nums'],
    },
    expenseText: { color: colors.expense },
    incomeText: { color: colors.income },
    panelCard: {
      marginTop: 12,
      padding: 16,
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.borderWidth.card,
      borderColor: colors.cardBorder,
      ...shape.elevation.card,
    },
    panelHead: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      marginBottom: 10,
    },
    panelTitle: {
      fontSize: 15,
      fontWeight: '600',
      color: colors.textPrimary,
    },
    panelLink: {
      fontSize: 13,
      fontWeight: '500',
      color: colors.textTertiary,
    },
    trendSegWrap: {
      width: 116,
    },
    tipLine: {
      flexDirection: 'row',
      alignItems: 'baseline',
      gap: 8,
      marginBottom: 10,
    },
    tipAmount: {
      fontSize: 20,
      fontWeight: '700',
      color: colors.textPrimary,
      fontVariant: ['tabular-nums'],
    },
    tipDate: {
      fontSize: 12,
      color: colors.textTertiary,
    },
    barsArea: {
      height: 116,
      flexDirection: 'row',
      alignItems: 'flex-end',
      gap: 2,
      position: 'relative',
      paddingTop: 10,
    },
    avgLine: {
      position: 'absolute',
      left: 0,
      right: 0,
      borderTopWidth: 1,
      borderStyle: 'dashed',
      borderTopColor: colors.strokeLight,
      zIndex: 1,
    },
    avgLabel: {
      position: 'absolute',
      right: 0,
      top: -15,
      fontSize: 10,
      color: colors.textTertiary,
      backgroundColor: colors.surface,
      paddingHorizontal: 3,
      fontVariant: ['tabular-nums'],
    },
    barSlot: {
      flex: 1,
      height: '100%',
      justifyContent: 'flex-end',
      alignItems: 'center',
    },
    barFill: {
      width: '100%',
      minHeight: 3,
      borderTopLeftRadius: 4,
      borderTopRightRadius: 4,
      borderBottomLeftRadius: 2,
      borderBottomRightRadius: 2,
      backgroundColor: isDark ? '#2A2E52' : colors.primaryLight,
    },
    barActive: {
      backgroundColor: colors.primary,
    },
    barFuture: {
      backgroundColor: colors.surfaceMuted,
    },
    cappedDot: {
      width: 4,
      height: 4,
      borderRadius: 2,
      backgroundColor: colors.primary,
      marginBottom: 3,
    },
    axisRow: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      marginTop: 6,
    },
    axisText: {
      fontSize: 10,
      color: colors.textQuaternary,
      fontVariant: ['tabular-nums'],
    },
    calGrid: {
      flexDirection: 'row',
      flexWrap: 'wrap',
    },
    calWeekdayCell: {
      width: '14.285%',
      alignItems: 'center',
      paddingBottom: 6,
    },
    calWeekdayText: {
      fontSize: 11,
      color: colors.textQuaternary,
    },
    calCellWrap: {
      width: '14.285%',
      padding: 2,
    },
    calCell: {
      aspectRatio: 1,
      borderRadius: 10,
      alignItems: 'center',
      justifyContent: 'center',
      paddingHorizontal: 2,
    },
    calCellActive: {
      borderWidth: 2,
      borderColor: colors.primary,
    },
    calDayText: {
      fontSize: 12,
      fontWeight: '600',
      color: colors.textPrimary,
      fontVariant: ['tabular-nums'],
    },
    calValText: {
      fontSize: 9,
      color: colors.textTertiary,
      fontVariant: ['tabular-nums'],
    },
    calHotText: {
      color: isDark ? '#0E1014' : '#FFFFFF',
    },
    calFutureText: {
      color: colors.textQuaternary,
    },
    panelFoot: {
      marginTop: 10,
      paddingTop: 10,
      borderTopWidth: StyleSheet.hairlineWidth,
      borderTopColor: colors.divider,
    },
    panelFootText: {
      fontSize: 12,
      color: colors.textTertiary,
      fontVariant: ['tabular-nums'],
    },
    dayBillRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 10,
      paddingVertical: 10,
    },
    dayBillBorder: {
      borderTopWidth: StyleSheet.hairlineWidth,
      borderTopColor: colors.divider,
    },
    dayBillIcon: {
      width: 34,
      height: 34,
      borderRadius: 11,
      alignItems: 'center',
      justifyContent: 'center',
    },
    dayBillTitle: {
      fontSize: 14,
      fontWeight: '500',
      color: colors.textPrimary,
    },
    dayBillSub: {
      fontSize: 11.5,
      color: colors.textTertiary,
      marginTop: 1,
    },
    dayBillAmt: {
      fontSize: 14.5,
      fontWeight: '600',
      fontVariant: ['tabular-nums'],
    },
    truncatedHint: {
      fontSize: 11.5,
      color: colors.textTertiary,
      marginTop: 6,
    },
    emptyChartText: {
      fontSize: 13,
      color: colors.textTertiary,
      textAlign: 'center',
      paddingVertical: 24,
    },
    donutWrap: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 16,
    },
    donutBox: {
      width: 128,
      height: 128,
      alignItems: 'center',
      justifyContent: 'center',
    },
    donutCenter: {
      position: 'absolute',
      alignItems: 'center',
      justifyContent: 'center',
      paddingHorizontal: 14,
    },
    donutCenterLabel: {
      fontSize: 11,
      color: colors.textTertiary,
    },
    donutCenterValue: {
      fontSize: 15,
      fontWeight: '700',
      color: colors.textPrimary,
      fontVariant: ['tabular-nums'],
      marginTop: 2,
    },
    legendList: {
      flex: 1,
      gap: 7,
    },
    legendRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 8,
    },
    legendDot: {
      width: 8,
      height: 8,
      borderRadius: 3,
    },
    legendName: {
      flex: 1,
      fontSize: 12.5,
      color: colors.textSecondary,
    },
    legendPct: {
      fontSize: 12,
      color: colors.textTertiary,
      fontVariant: ['tabular-nums'],
    },
    rankList: {
      marginTop: 14,
    },
    rankRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 12,
      paddingVertical: 10,
    },
    rankRowBorder: {
      borderTopWidth: StyleSheet.hairlineWidth,
      borderTopColor: colors.divider,
    },
    rankIcon: {
      width: 34,
      height: 34,
      borderRadius: 11,
      alignItems: 'center',
      justifyContent: 'center',
    },
    rankMain: {
      flex: 1,
    },
    rankTop: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
    },
    rankName: {
      fontSize: 14,
      fontWeight: '500',
      color: colors.textPrimary,
      flex: 1,
    },
    rankAmt: {
      fontSize: 14,
      fontWeight: '600',
      color: colors.textPrimary,
      fontVariant: ['tabular-nums'],
    },
    rankTrack: {
      height: 5,
      borderRadius: 999,
      backgroundColor: colors.surfaceMuted,
      marginTop: 6,
      overflow: 'hidden',
    },
    rankFill: {
      height: '100%',
      borderRadius: 999,
    },
    rankSub: {
      fontSize: 11,
      color: colors.textTertiary,
      marginTop: 4,
    },
  }),
  _colors: colors,
});

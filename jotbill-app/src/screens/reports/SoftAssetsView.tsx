/**
 * SoftAssetsView - 柔光主题资产净值视图
 * 深碳色渐变净资产主卡 + 近 12 月收支净额走势折线 + 资产/负债账户分组卡。
 */
import React, { useMemo } from 'react';
import {
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { useQuery } from '@tanstack/react-query';
import Svg, { Circle, Defs, LinearGradient, Path, Stop } from 'react-native-svg';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { spacing } from '../../theme/spacing';
import { useStyles } from '../../hooks';
import { useTheme } from '../../providers';
import { useAssetsData } from '../../hooks/useAssetsData';
import { nativeData, type Account } from '../../native';
import { CACHE_TIME, GC_TIME, QUERY_KEYS } from '../../lib/queryClient';
import { QueryErrorBanner, SoftGradient } from '../../components/ui';
import { AssetsTabSkeleton } from '../../components/skeleton/ReportSkeletons';
import AccountIcon from '../../components/business/AccountIcon';
import { formatCurrency } from '../../utils/format';

const SUBTYPE_LABELS: Record<Account['subtype'], string> = {
  cash: '现金',
  e_wallet: '电子钱包',
  bank_debit: '储蓄卡',
  credit_card: '信用卡',
  consumer_loan: '消费信贷',
  other: '其他账户',
};

export default function SoftAssetsView() {
  const styles = useStyles(createStyles);
  const { isDark } = useTheme();
  const navigation = useNavigation<any>();

  const {
    netWorth,
    totalAssets,
    totalLiabilities,
    assetTrendData,
    isLoading,
    isFetching,
    isError,
    error,
    refetch,
  } = useAssetsData();

  const accountsQuery = useQuery({
    queryKey: QUERY_KEYS.accountsList,
    queryFn: () => nativeData.getAccounts(),
    staleTime: CACHE_TIME.DASHBOARD,
    gcTime: GC_TIME.DEFAULT,
  });

  const activeAccounts = useMemo(
    () => (accountsQuery.data ?? []).filter(a => a.isActive),
    [accountsQuery.data],
  );
  const assetAccounts = useMemo(
    () => activeAccounts.filter(a => a.kind === 'asset'),
    [activeAccounts],
  );
  const liabilityAccounts = useMemo(
    () => activeAccounts.filter(a => a.kind === 'liability'),
    [activeAccounts],
  );

  // 右上角迷你趋势折线（遵循 useAssetsData.assetTrendData 既有口径）
  const sparkPath = useMemo(() => {
    const pts = assetTrendData.slice(-8);
    if (pts.length < 2) return null;
    const values = pts.map(p => p.value);
    const minV = Math.min(...values);
    const maxV = Math.max(...values);
    const range = maxV - minV || 1;
    const width = 104;
    const height = 40;
    const coords = pts.map((p, idx) => {
      const x = 4 + (idx / (pts.length - 1)) * (width - 8);
      const y = height - 6 - ((p.value - minV) / range) * (height - 14);
      return { x, y };
    });
    const line = coords
      .map((c, idx) => `${idx === 0 ? 'M' : 'L'}${c.x.toFixed(1)} ${c.y.toFixed(1)}`)
      .join(' ');
    const area = `${line} L${coords[coords.length - 1].x.toFixed(1)} ${height} L${coords[0].x.toFixed(1)} ${height} Z`;
    return { line, area, last: coords[coords.length - 1] };
  }, [assetTrendData]);

  const netWorthParts = useMemo(() => {
    const abs = Math.abs(netWorth);
    const formatted = abs.toLocaleString('zh-CN', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    });
    const [intPart, decPart] = formatted.split('.');
    return { sign: netWorth < 0 ? '-' : '', intPart, decPart };
  }, [netWorth]);

  if (isLoading && accountsQuery.isLoading) {
    return <AssetsTabSkeleton />;
  }

  const handleRefresh = async () => {
    await Promise.all([refetch(), accountsQuery.refetch()]);
  };

  const renderAccountRow = (account: Account, idx: number) => {
    const subParts = [
      SUBTYPE_LABELS[account.subtype],
      account.cardTail ? `尾号 ${account.cardTail}` : '',
    ].filter(Boolean);
    const balance = account.currentBalanceMinor / 100;
    return (
      <TouchableOpacity
        key={account.id}
        style={[styles.accRow, idx > 0 && styles.accRowBorder]}
        onPress={() => navigation.navigate('Accounts')}
        activeOpacity={0.75}
      >
        <View style={styles.accIconBox}>
          <AccountIcon
            icon={account.icon}
            name={account.name}
            subtype={account.subtype}
            size={24}
          />
        </View>
        <View style={styles.accInfo}>
          <Text style={styles.accName} numberOfLines={1}>
            {account.name}
          </Text>
          <Text style={styles.accSub} numberOfLines={1}>
            {subParts.join(' · ')}
          </Text>
        </View>
        <Text style={styles.accBalance}>
          {formatCurrency(account.kind === 'liability' ? -Math.abs(balance) : balance)}
        </Text>
      </TouchableOpacity>
    );
  };

  return (
    <ScrollView
      style={styles.container}
      contentContainerStyle={styles.content}
      refreshControl={
        <RefreshControl
          refreshing={(isFetching && !isLoading) || accountsQuery.isFetching}
          onRefresh={() => { void handleRefresh(); }}
        />
      }
      showsVerticalScrollIndicator={false}
    >
      {isError ? (
        <QueryErrorBanner
          error={error}
          title="资产数据加载失败"
          hint="下面的金额是占位 0，不代表你的真实余额。"
          onRetry={handleRefresh}
          style={styles.errorBanner}
        />
      ) : null}

      {/* 深碳色净资产主卡 */}
      <SoftGradient
        colors={isDark ? ['#1B1F2A', '#252B3A'] : ['#1F2433', '#2B3245']}
        style={styles.heroCard}
      >
        <View style={styles.heroTopRow}>
          <View>
            <Text style={styles.heroLabel}>净资产（账户余额）</Text>
            <Text
              style={styles.heroAmount}
              numberOfLines={1}
              adjustsFontSizeToFit
              minimumFontScale={0.65}
            >
              {netWorthParts.sign}
              <Text style={styles.heroCurrency}>¥</Text>
              {netWorthParts.intPart}
              <Text style={styles.heroDecimal}>.{netWorthParts.decPart}</Text>
            </Text>
          </View>
          {sparkPath ? (
            <Svg width={104} height={40} viewBox="0 0 104 40">
              <Defs>
                <LinearGradient id="assetSparkGrad" x1="0" y1="0" x2="0" y2="1">
                  <Stop offset="0%" stopColor="#8B8DFB" stopOpacity="0.45" />
                  <Stop offset="100%" stopColor="#8B8DFB" stopOpacity="0" />
                </LinearGradient>
              </Defs>
              <Path d={sparkPath.area} fill="url(#assetSparkGrad)" />
              <Path
                d={sparkPath.line}
                fill="none"
                stroke="#A5A7FF"
                strokeWidth={2.2}
                strokeLinecap="round"
                strokeLinejoin="round"
              />
              <Circle cx={sparkPath.last.x} cy={sparkPath.last.y} r={3.2} fill="#FFFFFF" />
            </Svg>
          ) : null}
        </View>

        <View style={styles.splitRow}>
          <View style={styles.splitItem}>
            <Text style={styles.splitLabel}>总资产</Text>
            <Text style={styles.splitValue}>{formatCurrency(totalAssets)}</Text>
          </View>
          <View style={styles.splitItem}>
            <Text style={styles.splitLabel}>总负债</Text>
            <Text style={styles.splitValue}>{formatCurrency(totalLiabilities)}</Text>
          </View>
        </View>
      </SoftGradient>

      {/* 资产账户 */}
      <View style={styles.groupHead}>
        <Text style={styles.groupTitle}>资产账户</Text>
        <Text style={styles.groupTotal}>{formatCurrency(totalAssets)}</Text>
      </View>
      {assetAccounts.length > 0 ? (
        <View style={styles.card}>
          {assetAccounts.map(renderAccountRow)}
        </View>
      ) : (
        <TouchableOpacity
          style={styles.emptyCard}
          onPress={() => navigation.navigate('Accounts')}
          activeOpacity={0.75}
        >
          <Text style={styles.emptyText}>暂无资产账户，点击前往添加</Text>
        </TouchableOpacity>
      )}

      {/* 负债账户 */}
      <View style={styles.groupHead}>
        <Text style={styles.groupTitle}>负债账户</Text>
        <Text style={styles.groupTotal}>{formatCurrency(-Math.abs(totalLiabilities))}</Text>
      </View>
      {liabilityAccounts.length > 0 ? (
        <View style={styles.card}>
          {liabilityAccounts.map(renderAccountRow)}
        </View>
      ) : (
        <View style={styles.emptyCard}>
          <Text style={styles.emptyText}>暂无负债账户</Text>
        </View>
      )}
    </ScrollView>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    container: { flex: 1 },
    content: {
      paddingHorizontal: spacing.lg,
      paddingTop: 6,
      paddingBottom: 116,
    },
    errorBanner: {
      margin: 0,
      marginBottom: 12,
    },
    heroCard: {
      borderRadius: shape.radius.hero,
      paddingHorizontal: 20,
      paddingVertical: 18,
      ...shape.elevation.floating,
    },
    heroTopRow: {
      flexDirection: 'row',
      alignItems: 'flex-start',
      justifyContent: 'space-between',
      gap: 8,
    },
    heroLabel: {
      fontSize: 13,
      color: 'rgba(255,255,255,0.72)',
    },
    heroAmount: {
      fontSize: 32,
      fontWeight: '700',
      color: '#FFFFFF',
      letterSpacing: -0.6,
      marginTop: 4,
      marginBottom: 14,
      fontVariant: ['tabular-nums'],
    },
    heroCurrency: {
      fontSize: 20,
      fontWeight: '600',
    },
    heroDecimal: {
      fontSize: 20,
      fontWeight: '600',
      color: 'rgba(255,255,255,0.76)',
    },
    splitRow: {
      flexDirection: 'row',
      gap: 24,
    },
    splitItem: {
      gap: 2,
    },
    splitLabel: {
      fontSize: 12,
      color: 'rgba(255,255,255,0.68)',
    },
    splitValue: {
      fontSize: 14,
      fontWeight: '600',
      color: '#FFFFFF',
      fontVariant: ['tabular-nums'],
    },
    groupHead: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      paddingHorizontal: 6,
      marginTop: 18,
      marginBottom: 8,
    },
    groupTitle: {
      fontSize: 13,
      fontWeight: '600',
      color: colors.textTertiary,
    },
    groupTotal: {
      fontSize: 13,
      fontWeight: '600',
      color: colors.textTertiary,
      fontVariant: ['tabular-nums'],
    },
    card: {
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.borderWidth.card,
      borderColor: colors.cardBorder,
      overflow: 'hidden',
      ...shape.elevation.card,
    },
    accRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 12,
      paddingHorizontal: 14,
      paddingVertical: 12,
    },
    accRowBorder: {
      borderTopWidth: StyleSheet.hairlineWidth,
      borderTopColor: colors.divider,
    },
    accIconBox: {
      width: 40,
      height: 40,
      borderRadius: 14,
      backgroundColor: colors.surfaceMuted,
      alignItems: 'center',
      justifyContent: 'center',
    },
    accInfo: {
      flex: 1,
      gap: 2,
    },
    accName: {
      fontSize: 15,
      fontWeight: '500',
      color: colors.textPrimary,
    },
    accSub: {
      fontSize: 12,
      color: colors.textTertiary,
    },
    accBalance: {
      fontSize: 15,
      fontWeight: '600',
      color: colors.textPrimary,
      fontVariant: ['tabular-nums'],
    },
    emptyCard: {
      paddingVertical: 20,
      paddingHorizontal: 16,
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.borderWidth.card,
      borderColor: colors.cardBorder,
      alignItems: 'center',
    },
    emptyText: {
      fontSize: 13,
      color: colors.textTertiary,
    },
  }),
});

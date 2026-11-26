/**
 * AccountsScreen - 账户管理主页面
 *
 * 三套主题原生质感适配：
 * - 魔法羊皮纸（hogwarts）：古灵阁羊皮纸/星夜底图 + 鎏金双细框 + 学院徽章 + 星芒分隔线；
 * - 柔光（soft）：深邃靛夜渐变净资产主卡 + 无粗描边轻投影卡片；
 * - 积木风（brutal）：高对比主色净资产块 + 粗描边硬阴影。
 *
 * 交互升级：
 * - 净资产主卡新增隐私金额遮罩开关与资产/负债占比条；
 * - 账户按「资产账户 / 负债账户 / 折叠已停用账户」清晰分组，同步 accountStyles 品牌染色与负债虚线框；
 * - 列表默认清爽扫描，点击账户行展开轻量「校准余额 / 编辑 / 停用」快捷操作条；
 * - 添加/编辑与余额校准均采用底部抽屉（AccountFormSheet / BalanceCalibrationSheet）。
 */
import React, { useCallback, useMemo, useState } from 'react';
import {
  Image,
  ImageBackground,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { SafeAreaView } from 'react-native-safe-area-context';
import {
  AlertTriangle,
  ChevronDown,
  ChevronLeft,
  ChevronUp,
  Eye,
  EyeOff,
  PauseCircle,
  Pencil,
  Plus,
  SlidersHorizontal,
  Wallet,
} from 'lucide-react-native';
import {
  nativeData,
  type Account,
  type AccountInput,
  type AccountUpdateInput,
} from '../../native';
import AccountIcon from '../../components/business/AccountIcon';
import { HogwartsStarDivider, SoftGradient } from '../../components/ui';
import { resolveAccountBadgeStyle } from '../../constants/accountStyles';
import {
  HOGWARTS_FONTS,
  HOGWARTS_HOUSES,
  HOGWARTS_IMAGES,
} from '../../constants/hogwartsAssets';
import { QUERY_KEYS } from '../../lib/queryClient';
import { useRefreshControlProps, useStyles } from '../../hooks';
import { useAlert, useTheme } from '../../providers';
import { borderWidth, shadow, typography } from '../../theme';
import type { ThemeColors } from '../../theme/colors';
import type { ThemeShape } from '../../theme/shape';
import AccountFormSheet, {
  type AccountFormSubmitPayload,
} from './accounts/AccountFormSheet';
import BalanceCalibrationSheet from './accounts/BalanceCalibrationSheet';
import {
  computeAssetLiabilityRatio,
  formatAccountMoney,
  getAccountOption,
  partitionAccounts,
  splitHeroAmountParts,
} from './accounts/accountUiHelpers';

export default function AccountsScreen() {
  const navigation = useNavigation();
  const queryClient = useQueryClient();
  const {
    colors,
    styleVariant,
    isDark,
    isSoft,
    isHogwarts,
    hogwartsHouse,
    playMagicSound,
  } = useTheme();
  const styles = useStyles(createStyles);
  const { alert } = useAlert();
  const refreshControlProps = useRefreshControlProps();

  const [hideAmounts, setHideAmounts] = useState(false);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [showInactive, setShowInactive] = useState(false);

  const [showForm, setShowForm] = useState(false);
  const [editingAccount, setEditingAccount] = useState<Account | null>(null);

  const [showCalibration, setShowCalibration] = useState(false);
  const [calibrationAccount, setCalibrationAccount] = useState<Account | null>(null);

  const activeHouseMeta = HOGWARTS_HOUSES[hogwartsHouse] ?? HOGWARTS_HOUSES.classic;

  const accountsQuery = useQuery({
    queryKey: QUERY_KEYS.accountsList,
    queryFn: () => nativeData.getAccounts(),
  });
  const netWorthQuery = useQuery({
    queryKey: QUERY_KEYS.accountsBalance,
    queryFn: () => nativeData.getNetWorth(),
  });
  // B4：余额一致性提示 —— 进入账户页实时重算，命中时展示横幅并支持一键直达校准
  const reconcileQuery = useQuery({
    queryKey: ['balance-reconcile-warnings'],
    queryFn: () => nativeData.getBalanceReconcileWarnings(),
  });

  const refetchAccounts = accountsQuery.refetch;
  const refetchNetWorth = netWorthQuery.refetch;
  const refetchReconcile = reconcileQuery.refetch;

  const invalidateAccounts = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: QUERY_KEYS.accountsList });
    queryClient.invalidateQueries({ queryKey: QUERY_KEYS.accountsBalance });
    queryClient.invalidateQueries({ queryKey: ['net-worth'] });
    queryClient.invalidateQueries({ queryKey: ['asset-trend'] });
    queryClient.invalidateQueries({ queryKey: ['dashboard'] });
    queryClient.invalidateQueries({ queryKey: ['total-assets'] });
    queryClient.invalidateQueries({ queryKey: ['balance-reconcile-warnings'] });
  }, [queryClient]);

  const saveMutation = useMutation({
    mutationFn: ({ id, input }: AccountFormSubmitPayload) =>
      id
        ? nativeData.updateAccount(id, input as AccountUpdateInput)
        : nativeData.addAccount(input as AccountInput),
    onSuccess: saved => {
      invalidateAccounts();
      setShowForm(false);
      setEditingAccount(null);
      if (saved?.id) {
        setExpandedId(saved.id);
      }
      playMagicSound('coin_clink');
    },
    onError: (error: Error) => alert('保存失败', error.message),
  });

  const activeMutation = useMutation({
    mutationFn: ({ id, isActive }: { id: string; isActive: boolean }) =>
      nativeData.updateAccount(id, { isActive }),
    onSuccess: (_data, variables) => {
      invalidateAccounts();
      setExpandedId(null);
      if (!variables.isActive) {
        setShowInactive(true);
      }
    },
    onError: (error: Error) => alert('更新失败', error.message),
  });

  const calibrationMutation = useMutation({
    mutationFn: ({
      id,
      amountMinor,
      note,
    }: {
      id: string;
      amountMinor: number;
      note?: string;
    }) => nativeData.calibrateAccountBalance(id, amountMinor, note),
    onSuccess: () => {
      invalidateAccounts();
      setShowCalibration(false);
      setCalibrationAccount(null);
      playMagicSound('chime');
    },
    onError: (error: Error) => alert('校准失败', error.message),
  });

  useFocusEffect(
    useCallback(() => {
      refetchAccounts();
      refetchNetWorth();
      refetchReconcile();
    }, [refetchAccounts, refetchNetWorth, refetchReconcile]),
  );

  const onRefresh = useCallback(async () => {
    await Promise.all([refetchAccounts(), refetchNetWorth(), refetchReconcile()]);
  }, [refetchAccounts, refetchNetWorth, refetchReconcile]);

  const accounts = useMemo(
    () => accountsQuery.data ?? [],
    [accountsQuery.data],
  );

  const partitioned = useMemo(() => partitionAccounts(accounts), [accounts]);

  // B4：不一致账户清单（映射为 Map 便于行内徽章与横幅直达校准复用）
  const reconcileMismatches = useMemo(
    () => reconcileQuery.data?.mismatches ?? [],
    [reconcileQuery.data?.mismatches],
  );
  const mismatchMap = useMemo(() => {
    const map = new Map<string, number>();
    for (const item of reconcileMismatches) {
      map.set(item.accountId, item.diffMinor);
    }
    return map;
  }, [reconcileMismatches]);

  const firstMismatch = useMemo(() => {
    if (reconcileMismatches.length === 0) return null;
    const item = reconcileMismatches[0];
    const matchedAccount = accounts.find(a => a.id === item.accountId) ?? null;
    return {
      diffMinor: item.diffMinor,
      account: matchedAccount,
      accountName: matchedAccount?.name ?? '未知账户',
    };
  }, [reconcileMismatches, accounts]);

  const netWorthMinor = netWorthQuery.data?.netWorthMinor ?? 0;
  const totalAssetsMinor = netWorthQuery.data?.totalAssetsMinor ?? 0;
  const totalLiabilitiesMinor = netWorthQuery.data?.totalLiabilitiesMinor ?? 0;

  const heroParts = useMemo(
    () => splitHeroAmountParts(netWorthMinor, hideAmounts),
    [netWorthMinor, hideAmounts],
  );
  const ratio = useMemo(
    () => computeAssetLiabilityRatio(totalAssetsMinor, totalLiabilitiesMinor),
    [totalAssetsMinor, totalLiabilitiesMinor],
  );

  const openCreate = () => {
    setEditingAccount(null);
    setShowForm(true);
  };

  const openEdit = (account: Account) => {
    setEditingAccount(account);
    setShowForm(true);
  };

  const openCalibration = (account: Account) => {
    setCalibrationAccount(account);
    setShowCalibration(true);
  };

  const toggleExpand = (accountId: string) => {
    setExpandedId(prev => (prev === accountId ? null : accountId));
  };

  const renderHeroContent = () => {
    const heroEyeColor = isHogwarts
      ? isDark
        ? '#F5ECD9'
        : '#26190D'
      : isSoft
        ? '#FFFFFF'
        : colors.onPrimary;

    return (
      <>
        {isHogwarts ? <View style={styles.hogwartsInnerFrame} pointerEvents="none" /> : null}

        <View style={styles.heroTopRow}>
          <View style={styles.heroBadgeGroup}>
            <Text style={styles.heroLabel}>
              {isHogwarts ? '古灵阁净资产' : '净资产'}
            </Text>
            <View style={styles.heroCountPill}>
              <Text style={styles.heroCountText}>
                {partitioned.activeCount} 个账户
              </Text>
            </View>
          </View>
          <TouchableOpacity
            testID="toggle-hide-amounts"
            style={styles.heroEyeBtn}
            onPress={() => setHideAmounts(v => !v)}
            activeOpacity={0.75}
            hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
            accessibilityRole="button"
            accessibilityLabel={hideAmounts ? '显示账户金额' : '隐藏账户金额'}
          >
            {hideAmounts ? (
              <EyeOff size={15} color={heroEyeColor} strokeWidth={2.1} />
            ) : (
              <Eye size={15} color={heroEyeColor} strokeWidth={2.1} />
            )}
          </TouchableOpacity>
        </View>

        <View style={styles.heroAmountRow}>
          <Text
            testID="net-worth-amount"
            style={styles.heroAmount}
            numberOfLines={1}
            adjustsFontSizeToFit
            minimumFontScale={0.65}
          >
            {heroParts.sign}
            {heroParts.currency ? (
              <Text style={styles.heroCurrency}>{heroParts.currency}</Text>
            ) : null}
            {heroParts.intPart}
            {heroParts.decPart ? (
              <Text style={styles.heroDecimal}>{heroParts.decPart}</Text>
            ) : null}
          </Text>
          {isHogwarts ? (
            <Image
              source={activeHouseMeta.crest}
              style={styles.houseCrestMini}
              accessibilityIgnoresInvertColors
            />
          ) : null}
        </View>

        {isHogwarts ? (
          <HogwartsStarDivider
            color={colors.accent}
            width={240}
            style={styles.heroStarDivider}
          />
        ) : null}

        {/* 资产 / 负债占比条 */}
        <View style={styles.ratioTrack}>
          <View
            style={[
              styles.ratioAssetFill,
              { flex: Math.max(ratio.assetPct, ratio.debtPct > 0 ? 4 : 100) },
            ]}
          />
          {ratio.debtPct > 0 ? (
            <View
              style={[
                styles.ratioDebtFill,
                { flex: Math.max(ratio.debtPct, 4) },
              ]}
            />
          ) : null}
        </View>

        {/* 总资产 / 总负债双列卡片 */}
        <View style={styles.heroSplitRow}>
          <View style={styles.heroMetricCard}>
            <View style={styles.heroMetricHeader}>
              <View style={[styles.metricDot, styles.metricDotAsset]} />
              <Text style={styles.heroMetricLabel}>总资产 · {ratio.assetPct}%</Text>
            </View>
            <Text style={styles.heroMetricVal} numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.75}>
              {formatAccountMoney(totalAssetsMinor, 'asset', hideAmounts)}
            </Text>
          </View>
          <View style={styles.heroMetricCard}>
            <View style={styles.heroMetricHeader}>
              <View style={[styles.metricDot, styles.metricDotDebt]} />
              <Text style={styles.heroMetricLabel}>总负债 · {ratio.debtPct}%</Text>
            </View>
            <Text style={styles.heroMetricVal} numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.75}>
              {totalLiabilitiesMinor === 0
                ? hideAmounts
                  ? '••••••'
                  : '¥0.00'
                : formatAccountMoney(
                    -Math.abs(totalLiabilitiesMinor),
                    'liability',
                    hideAmounts,
                  )}
            </Text>
          </View>
        </View>
      </>
    );
  };

  const renderHeroCard = () => {
    if (isHogwarts) {
      return (
        <ImageBackground
          source={isDark ? HOGWARTS_IMAGES.heroDark : HOGWARTS_IMAGES.heroLight}
          style={styles.heroCard}
          imageStyle={styles.heroBgImage}
          resizeMode="cover"
        >
          <View
            style={[
              StyleSheet.absoluteFill,
              {
                backgroundColor: isDark
                  ? activeHouseMeta.heroWashDark
                  : activeHouseMeta.heroWashLight,
              },
            ]}
            pointerEvents="none"
          />
          <View
            style={[StyleSheet.absoluteFill, styles.heroVeil]}
            pointerEvents="none"
          />
          {renderHeroContent()}
        </ImageBackground>
      );
    }

    if (isSoft) {
      return (
        <SoftGradient
          glow
          colors={
            isDark
              ? ['#191D2C', '#252A4A', '#35327A']
              : ['#21263B', '#343A64', '#47449E']
          }
          style={styles.heroCard}
        >
          {renderHeroContent()}
        </SoftGradient>
      );
    }

    return <View style={styles.heroCard}>{renderHeroContent()}</View>;
  };

  const renderActiveAccountRow = (account: Account, index: number) => {
    const badgeStyle = resolveAccountBadgeStyle(account, {
      variant: styleVariant,
      isDark,
    });
    const option = getAccountOption(account.subtype);
    const expanded = expandedId === account.id;
    const hasMismatch = mismatchMap.has(account.id);
    const isDebt = account.kind === 'liability';

    return (
      <View
        key={account.id}
        style={[
          styles.accountItem,
          index > 0 && styles.accountItemDivider,
          expanded && styles.accountItemExpanded,
        ]}
      >
        <TouchableOpacity
          testID={`account-row-${account.id}`}
          style={styles.accountRow}
          onPress={() => toggleExpand(account.id)}
          activeOpacity={0.78}
          accessibilityRole="button"
          accessibilityState={{ expanded }}
          accessibilityLabel={`${account.name}，${option.shortLabel}，余额 ${formatAccountMoney(account.currentBalanceMinor, account.kind, hideAmounts)}`}
        >
          <View
            style={[
              styles.accountIconTile,
              {
                backgroundColor: badgeStyle.backgroundColor,
                borderColor: badgeStyle.borderColor,
                borderStyle: badgeStyle.borderStyle,
              },
            ]}
          >
            <View
              style={[
                styles.accountIconInner,
                badgeStyle.bitmapBackdropColor
                  ? { backgroundColor: badgeStyle.bitmapBackdropColor }
                  : null,
              ]}
            >
              <AccountIcon
                icon={account.icon}
                name={account.name}
                subtype={account.subtype}
                size={24}
                color={badgeStyle.textColor}
              />
            </View>
          </View>

          <View style={styles.accountInfo}>
            <View style={styles.accountNameLine}>
              <Text style={styles.accountName} numberOfLines={1}>
                {account.name}
              </Text>
              {hasMismatch ? (
                <View style={styles.warnBadge}>
                  <Text style={styles.warnBadgeText}>需校准</Text>
                </View>
              ) : null}
            </View>
            <View style={styles.accountMetaLine}>
              <Text style={styles.accountSubtype}>{option.shortLabel}</Text>
              {account.cardTail ? (
                <View
                  style={[
                    styles.tailChip,
                    {
                      backgroundColor: badgeStyle.backgroundColor,
                      borderColor: badgeStyle.borderColor,
                      borderStyle: badgeStyle.borderStyle,
                    },
                  ]}
                >
                  <Text
                    style={[styles.tailChipText, { color: badgeStyle.textColor }]}
                  >
                    •••• {account.cardTail}
                  </Text>
                </View>
              ) : null}
            </View>
          </View>

          <View style={styles.accountRight}>
            <Text
              style={[styles.accountBalance, isDebt && styles.debtBalance]}
              numberOfLines={1}
            >
              {formatAccountMoney(
                account.currentBalanceMinor,
                account.kind,
                hideAmounts,
              )}
            </Text>
            {expanded ? (
              <ChevronUp size={16} color={colors.primary} strokeWidth={2.2} />
            ) : (
              <ChevronDown
                size={16}
                color={colors.textQuaternary}
                strokeWidth={2.1}
              />
            )}
          </View>
        </TouchableOpacity>

        {/* 点击展开的轻量快捷操作条 */}
        {expanded ? (
          <View
            testID={`account-actions-${account.id}`}
            style={styles.actionsTray}
          >
            <TouchableOpacity
              style={[styles.trayBtn, styles.trayBtnPrimary]}
              onPress={() => openCalibration(account)}
              activeOpacity={0.78}
              accessibilityRole="button"
              accessibilityLabel={`校准${account.name}余额`}
            >
              <SlidersHorizontal
                size={14}
                color={colors.primary}
                strokeWidth={2.2}
              />
              <Text style={[styles.trayBtnText, styles.trayBtnTextPrimary]}>
                校准余额
              </Text>
            </TouchableOpacity>

            <TouchableOpacity
              style={styles.trayBtn}
              onPress={() => openEdit(account)}
              activeOpacity={0.78}
              accessibilityRole="button"
              accessibilityLabel={`编辑${account.name}`}
            >
              <Pencil
                size={14}
                color={colors.textPrimary}
                strokeWidth={2.1}
              />
              <Text style={styles.trayBtnText}>编辑</Text>
            </TouchableOpacity>

            <TouchableOpacity
              style={styles.trayBtn}
              onPress={() =>
                activeMutation.mutate({ id: account.id, isActive: false })
              }
              activeOpacity={0.78}
              accessibilityRole="button"
              accessibilityLabel={`停用${account.name}`}
            >
              <PauseCircle
                size={14}
                color={colors.expense}
                strokeWidth={2.1}
              />
              <Text style={[styles.trayBtnText, styles.trayBtnTextDanger]}>
                停用
              </Text>
            </TouchableOpacity>
          </View>
        ) : null}
      </View>
    );
  };

  return (
    <SafeAreaView style={styles.container} edges={['top', 'bottom']}>
      {/* 顶部导航栏 */}
      <View style={styles.header}>
        <TouchableOpacity
          onPress={() => navigation.goBack()}
          style={styles.navBackBtn}
          activeOpacity={0.78}
          accessibilityRole="button"
          accessibilityLabel="返回"
        >
          <ChevronLeft size={21} color={colors.textPrimary} strokeWidth={2.3} />
        </TouchableOpacity>

        <View style={styles.headerTitleBox}>
          <Text style={styles.headerTitle}>账户管理</Text>
          {isHogwarts ? (
            <Text style={styles.headerSubtitle}>
              {activeHouseMeta.enBadge} VAULTS
            </Text>
          ) : null}
        </View>

        <TouchableOpacity
          testID="add-account-btn"
          onPress={openCreate}
          style={styles.navAddBtn}
          activeOpacity={0.82}
          accessibilityRole="button"
          accessibilityLabel="添加账户"
        >
          <Plus
            size={15}
            color={isSoft || isHogwarts ? colors.onPrimary : '#FFFFFF'}
            strokeWidth={2.7}
          />
          <Text style={styles.navAddText}>添加</Text>
        </TouchableOpacity>
      </View>

      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.content}
        showsVerticalScrollIndicator={false}
        refreshControl={
          <RefreshControl
            {...refreshControlProps}
            refreshing={accountsQuery.isFetching || netWorthQuery.isFetching}
            onRefresh={onRefresh}
          />
        }
      >
        {/* 净资产 Hero 主卡 */}
        {renderHeroCard()}

        {/* B4：余额一致性提醒横幅（支持一键直达校准） */}
        {firstMismatch ? (
          <View testID="reconcile-warning-banner" style={styles.reconcileBanner}>
            <View style={styles.reconcileLeft}>
              <View style={styles.reconcileIconBox}>
                <AlertTriangle
                  size={16}
                  color={colors.warning}
                  strokeWidth={2.2}
                />
              </View>
              <View style={styles.reconcileTextWrap}>
                <Text style={styles.reconcileTitle}>
                  发现 {reconcileMismatches.length} 个账户余额需核对
                </Text>
                <Text style={styles.reconcileDesc} numberOfLines={1}>
                  {firstMismatch.accountName} 账面比账单
                  {firstMismatch.diffMinor >= 0 ? '多' : '少'}{' '}
                  {formatAccountMoney(Math.abs(firstMismatch.diffMinor), 'asset')}
                </Text>
              </View>
            </View>
            {firstMismatch.account ? (
              <TouchableOpacity
                testID="reconcile-jump-btn"
                style={styles.reconcileCta}
                onPress={() => openCalibration(firstMismatch.account!)}
                activeOpacity={0.8}
                accessibilityRole="button"
                accessibilityLabel={`立即校准${firstMismatch.accountName}`}
              >
                <Text style={styles.reconcileCtaText}>去校准 →</Text>
              </TouchableOpacity>
            ) : null}
          </View>
        ) : null}

        {/* 空状态 vs 分组列表 */}
        {accounts.length === 0 ? (
          <View testID="accounts-empty-state" style={styles.emptyState}>
            <View style={styles.emptyMedallion}>
              {isHogwarts ? (
                <Image
                  source={HOGWARTS_IMAGES.emptyOwl}
                  style={styles.emptyOwlImg}
                  resizeMode="cover"
                />
              ) : (
                <Wallet size={34} color={colors.primary} strokeWidth={1.9} />
              )}
            </View>
            <Text style={styles.emptyTitle}>
              {isHogwarts ? '金库尚未开启' : '暂无账户'}
            </Text>
            <Text style={styles.emptySubtitle}>
              添加你常用的银行卡、微信、支付宝或信用卡，{'\n'}
              记账时即可自动同步余额与资产净值。
            </Text>
            <TouchableOpacity
              style={styles.emptyCtaBtn}
              onPress={openCreate}
              activeOpacity={0.85}
              accessibilityRole="button"
              accessibilityLabel="添加首个账户"
            >
              <Plus
                size={16}
                color={isSoft || isHogwarts ? colors.onPrimary : '#FFFFFF'}
                strokeWidth={2.6}
              />
              <Text style={styles.emptyCtaText}>添加首个账户</Text>
            </TouchableOpacity>
          </View>
        ) : (
          <>
            {/* 资产账户组 */}
            {partitioned.activeAssets.length > 0 ? (
              <View style={styles.sectionBlock}>
                <View style={styles.sectionHeader}>
                  <View style={styles.sectionTitleGroup}>
                    <View
                      style={[
                        styles.sectionDot,
                        { backgroundColor: colors.income },
                      ]}
                    />
                    <Text style={styles.sectionTitle}>资产账户</Text>
                    <View style={styles.sectionCountBadge}>
                      <Text style={styles.sectionCountText}>
                        {partitioned.activeAssets.length}
                      </Text>
                    </View>
                  </View>
                  <Text style={styles.sectionTotal}>
                    {formatAccountMoney(
                      partitioned.assetsSubtotalMinor,
                      'asset',
                      hideAmounts,
                    )}
                  </Text>
                </View>

                <View style={styles.groupCard}>
                  {partitioned.activeAssets.map((account, idx) =>
                    renderActiveAccountRow(account, idx),
                  )}
                </View>
              </View>
            ) : null}

            {/* 负债账户组 */}
            {partitioned.activeLiabilities.length > 0 ? (
              <View style={styles.sectionBlock}>
                <View style={styles.sectionHeader}>
                  <View style={styles.sectionTitleGroup}>
                    <View
                      style={[
                        styles.sectionDot,
                        styles.sectionDotDebt,
                        { backgroundColor: colors.expense },
                      ]}
                    />
                    <Text style={styles.sectionTitle}>负债账户</Text>
                    <View style={styles.sectionCountBadge}>
                      <Text style={styles.sectionCountText}>
                        {partitioned.activeLiabilities.length}
                      </Text>
                    </View>
                  </View>
                  <Text style={[styles.sectionTotal, styles.sectionTotalDebt]}>
                    {partitioned.liabilitiesSubtotalMinor === 0
                      ? hideAmounts
                        ? '••••••'
                        : '¥0.00'
                      : formatAccountMoney(
                          -partitioned.liabilitiesSubtotalMinor,
                          'liability',
                          hideAmounts,
                        )}
                  </Text>
                </View>

                <View style={styles.groupCard}>
                  {partitioned.activeLiabilities.map((account, idx) =>
                    renderActiveAccountRow(account, idx),
                  )}
                </View>
              </View>
            ) : null}

            {/* 折叠式已停用账户 */}
            {partitioned.inactiveAccounts.length > 0 ? (
              <View style={styles.inactiveBlock}>
                <TouchableOpacity
                  testID="toggle-inactive-accounts"
                  style={styles.inactiveHeaderBtn}
                  onPress={() => setShowInactive(v => !v)}
                  activeOpacity={0.75}
                  accessibilityRole="button"
                  accessibilityState={{ expanded: showInactive }}
                >
                  <Text style={styles.inactiveHeaderText}>
                    已停用账户 ({partitioned.inactiveAccounts.length})
                  </Text>
                  <View style={styles.inactiveToggleRight}>
                    <Text style={styles.inactiveToggleLabel}>
                      {showInactive ? '收起' : '展开'}
                    </Text>
                    {showInactive ? (
                      <ChevronUp
                        size={15}
                        color={colors.textTertiary}
                        strokeWidth={2.2}
                      />
                    ) : (
                      <ChevronDown
                        size={15}
                        color={colors.textTertiary}
                        strokeWidth={2.2}
                      />
                    )}
                  </View>
                </TouchableOpacity>

                {showInactive ? (
                  <View style={styles.groupCard}>
                    {partitioned.inactiveAccounts.map((account, idx) => {
                      const badgeStyle = resolveAccountBadgeStyle(account, {
                        variant: styleVariant,
                        isDark,
                      });
                      const option = getAccountOption(account.subtype);
                      return (
                        <View
                          key={account.id}
                          style={[
                            styles.inactiveRow,
                            idx > 0 && styles.accountItemDivider,
                          ]}
                        >
                          <View
                            style={[
                              styles.inactiveIconTile,
                              {
                                backgroundColor: badgeStyle.backgroundColor,
                                borderColor: badgeStyle.borderColor,
                                borderStyle: badgeStyle.borderStyle,
                              },
                            ]}
                          >
                            <View
                              style={[
                                styles.inactiveIconInner,
                                badgeStyle.bitmapBackdropColor
                                  ? {
                                      backgroundColor:
                                        badgeStyle.bitmapBackdropColor,
                                    }
                                  : null,
                              ]}
                            >
                              <AccountIcon
                                icon={account.icon}
                                name={account.name}
                                subtype={account.subtype}
                                size={20}
                                color={badgeStyle.textColor}
                              />
                            </View>
                          </View>

                          <View style={styles.accountInfo}>
                            <Text style={styles.inactiveName} numberOfLines={1}>
                              {account.name}
                            </Text>
                            <Text style={styles.accountSubtype} numberOfLines={1}>
                              {option.shortLabel} · 已停用
                              {account.cardTail
                                ? ` · 尾号 ${account.cardTail}`
                                : ''}
                            </Text>
                          </View>

                          <TouchableOpacity
                            style={styles.reactivateBtn}
                            onPress={() =>
                              activeMutation.mutate({
                                id: account.id,
                                isActive: true,
                              })
                            }
                            activeOpacity={0.78}
                            accessibilityRole="button"
                            accessibilityLabel={`重新启用${account.name}`}
                          >
                            <Text style={styles.reactivateText}>重新启用</Text>
                          </TouchableOpacity>
                        </View>
                      );
                    })}
                  </View>
                ) : null}
              </View>
            ) : null}
          </>
        )}
      </ScrollView>

      {/* 添加 / 编辑账户底部抽屉 */}
      <AccountFormSheet
        visible={showForm}
        editingAccount={editingAccount}
        isSubmitting={saveMutation.isPending}
        onClose={() => {
          setShowForm(false);
          setEditingAccount(null);
        }}
        onSubmit={payload => saveMutation.mutate(payload)}
      />

      {/* 校准余额底部抽屉 */}
      <BalanceCalibrationSheet
        visible={showCalibration}
        account={calibrationAccount}
        mismatchDiffMinor={
          calibrationAccount
            ? (mismatchMap.get(calibrationAccount.id) ?? null)
            : null
        }
        isSubmitting={calibrationMutation.isPending}
        onClose={() => {
          setShowCalibration(false);
          setCalibrationAccount(null);
        }}
        onSubmit={payload => calibrationMutation.mutate(payload)}
      />
    </SafeAreaView>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape, isDark: boolean) => {
  const isBrutal = !shape.isSoft && !shape.isHogwarts;
  const isHogwarts = shape.isHogwarts;

  // Hero 卡片文字与容器派生色
  const heroFg = isHogwarts
    ? isDark
      ? '#F5ECD9'
      : '#26190D'
    : shape.isSoft
      ? '#FFFFFF'
      : colors.onPrimary;
  const heroMuted = isHogwarts
    ? isDark
      ? 'rgba(243, 234, 215, 0.74)'
      : 'rgba(74, 54, 33, 0.78)'
    : shape.isSoft
      ? 'rgba(255, 255, 255, 0.74)'
      : colors.onPrimaryMuted;
  const heroChipBg = isHogwarts
    ? isDark
      ? 'rgba(18, 15, 32, 0.48)'
      : 'rgba(252, 247, 234, 0.76)'
    : shape.isSoft
      ? 'rgba(255, 255, 255, 0.14)'
      : isDark
        ? 'rgba(0, 0, 0, 0.16)'
        : 'rgba(255, 255, 255, 0.18)';
  const heroChipBorder = isHogwarts
    ? isDark
      ? 'rgba(226, 188, 99, 0.36)'
      : 'rgba(182, 142, 62, 0.42)'
    : shape.isSoft
      ? 'rgba(255, 255, 255, 0.18)'
      : colors.onPrimaryDivider;
  const heroCellBg = isHogwarts
    ? isDark
      ? 'rgba(18, 15, 32, 0.50)'
      : 'rgba(252, 247, 234, 0.78)'
    : shape.isSoft
      ? 'rgba(255, 255, 255, 0.10)'
      : isDark
        ? 'rgba(0, 0, 0, 0.12)'
        : 'rgba(255, 255, 255, 0.14)';
  const heroBarAsset = isHogwarts
    ? isDark
      ? '#E2BC63'
      : colors.primary
    : shape.isSoft
      ? '#6EE7B7'
      : '#FACC15';
  const heroBarDebt = isHogwarts
    ? isDark
      ? '#F4766E'
      : '#A62323'
    : shape.isSoft
      ? '#FDA4AF'
      : '#FB7185';
  const warnBg = isHogwarts
    ? isDark
      ? '#2A2018'
      : '#FBF2DF'
    : shape.isSoft
      ? isDark
        ? '#272115'
        : '#FFFBEB'
      : isDark
        ? '#2E2318'
        : '#FEF3C7';
  const warnBorder = isHogwarts
    ? isDark
      ? '#6E4B20'
      : '#DFB877'
    : shape.isSoft
      ? isDark
        ? '#54431D'
        : '#FDE68A'
      : colors.stroke;
  const expandedBg = isHogwarts
    ? isDark
      ? 'rgba(226, 188, 99, 0.06)'
      : 'rgba(182, 142, 62, 0.08)'
    : shape.isSoft
      ? isDark
        ? 'rgba(139, 141, 251, 0.07)'
        : 'rgba(79, 70, 229, 0.04)'
      : isDark
        ? 'rgba(96, 165, 250, 0.10)'
        : 'rgba(37, 99, 235, 0.06)';

  return StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: colors.background,
    },
    /* 顶部导航栏 */
    header: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingHorizontal: 16,
      paddingTop: 10,
      paddingBottom: 12,
      backgroundColor: colors.background,
    },
    navBackBtn: {
      width: 38,
      height: 38,
      borderRadius: shape.radius.button,
      backgroundColor: colors.surface,
      borderWidth: isBrutal ? borderWidth.medium : shape.borderWidth.control,
      borderColor: isBrutal ? colors.stroke : colors.cardBorder,
      alignItems: 'center',
      justifyContent: 'center',
      ...(isBrutal ? shadow.small : shape.elevation.card),
    },
    headerTitleBox: {
      alignItems: 'center',
    },
    headerTitle: {
      fontSize: 18,
      fontWeight: isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
    },
    headerSubtitle: {
      marginTop: 2,
      fontSize: 10,
      fontFamily: HOGWARTS_FONTS.ornate,
      color: colors.accent,
      letterSpacing: 1.4,
    },
    navAddBtn: {
      height: 36,
      paddingHorizontal: 13,
      borderRadius: isBrutal ? shape.radius.button : shape.radius.pill,
      backgroundColor: colors.primary,
      borderWidth: isBrutal ? borderWidth.medium : isHogwarts ? 1 : 0,
      borderColor: isHogwarts ? colors.accent : colors.stroke,
      flexDirection: 'row',
      alignItems: 'center',
      gap: 4,
      ...(isBrutal ? shadow.small : shape.elevation.card),
    },
    navAddText: {
      fontSize: 13.5,
      fontWeight: isHogwarts ? undefined : '700',
      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: shape.isSoft || isHogwarts ? colors.onPrimary : '#FFFFFF',
    },
    scrollView: {
      flex: 1,
    },
    content: {
      paddingHorizontal: 16,
      paddingTop: 4,
      paddingBottom: 32,
    },
    /* 净资产 Hero 主卡 */
    heroCard: {
      borderRadius: shape.radius.hero,
      paddingHorizontal: 18,
      paddingTop: 18,
      paddingBottom: 16,
      marginBottom: 14,
      overflow: 'hidden',
      backgroundColor: isBrutal ? colors.primary : undefined,
      borderWidth: isBrutal
        ? borderWidth.medium
        : isHogwarts
          ? 1.5
          : shape.isSoft && isDark
            ? 1
            : 0,
      borderColor: isBrutal
        ? colors.stroke
        : isHogwarts
          ? isDark
            ? 'rgba(226, 188, 99, 0.45)'
            : 'rgba(182, 142, 62, 0.65)'
          : 'rgba(139, 141, 251, 0.22)',
      ...(isBrutal ? shadow.medium : shape.elevation.hero),
    },
    heroBgImage: {
      borderRadius: shape.radius.hero,
    },
    heroVeil: {
      backgroundColor: isDark
        ? 'rgba(18, 15, 32, 0.56)'
        : 'rgba(252, 247, 234, 0.58)',
    },
    hogwartsInnerFrame: {
      position: 'absolute',
      top: 6,
      right: 6,
      bottom: 6,
      left: 6,
      borderRadius: shape.radius.hero - 5,
      borderWidth: 1,
      borderColor: isDark
        ? 'rgba(226, 188, 99, 0.26)'
        : 'rgba(182, 142, 62, 0.36)',
    },
    heroTopRow: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
    },
    heroBadgeGroup: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 7,
    },
    heroLabel: {
      fontSize: 12.5,
      fontWeight: isHogwarts ? undefined : '600',
      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: heroMuted,
    },
    heroCountPill: {
      paddingHorizontal: 8,
      paddingVertical: 2,
      borderRadius: 99,
      backgroundColor: heroChipBg,
      borderWidth: 1,
      borderColor: heroChipBorder,
    },
    heroCountText: {
      fontSize: 10.5,
      fontWeight: isHogwarts ? undefined : '600',
      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: isHogwarts
        ? isDark
          ? '#E2BC63'
          : colors.primary
        : heroFg,
    },
    heroEyeBtn: {
      width: 30,
      height: 30,
      borderRadius: 15,
      backgroundColor: heroChipBg,
      borderWidth: 1,
      borderColor: heroChipBorder,
      alignItems: 'center',
      justifyContent: 'center',
    },
    heroAmountRow: {
      marginTop: 8,
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      gap: 10,
    },
    heroAmount: {
      flex: 1,
      fontSize: 32,
      fontWeight: isHogwarts ? undefined : shape.isSoft ? '800' : '900',
      fontFamily: isHogwarts
        ? HOGWARTS_FONTS.numeric
        : shape.isSoft
          ? undefined
          : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
      color: heroFg,
      letterSpacing: -0.4,
    },
    heroCurrency: {
      fontSize: 21,
      fontWeight: isHogwarts ? undefined : '700',
      opacity: 0.88,
    },
    heroDecimal: {
      fontSize: 19,
      fontWeight: isHogwarts ? undefined : '700',
      opacity: 0.74,
    },
    houseCrestMini: {
      width: 36,
      height: 36,
      borderRadius: 18,
      borderWidth: 1.5,
      borderColor: colors.accent,
    },
    heroStarDivider: {
      marginTop: 6,
      marginBottom: 2,
    },
    ratioTrack: {
      marginTop: 12,
      height: isBrutal ? 8 : 6,
      borderRadius: isBrutal ? 4 : 99,
      backgroundColor: isHogwarts
        ? isDark
          ? 'rgba(255, 255, 255, 0.12)'
          : 'rgba(74, 54, 33, 0.12)'
        : 'rgba(255, 255, 255, 0.18)',
      borderWidth: isBrutal ? 1.5 : 0,
      borderColor: colors.stroke,
      flexDirection: 'row',
      overflow: 'hidden',
      gap: isBrutal ? 0 : 2,
    },
    ratioAssetFill: {
      height: '100%',
      backgroundColor: heroBarAsset,
      borderRadius: isBrutal ? 0 : 99,
    },
    ratioDebtFill: {
      height: '100%',
      backgroundColor: heroBarDebt,
      borderRadius: isBrutal ? 0 : 99,
    },
    heroSplitRow: {
      marginTop: 12,
      flexDirection: 'row',
      gap: 8,
    },
    heroMetricCard: {
      flex: 1,
      paddingHorizontal: 11,
      paddingVertical: 9,
      borderRadius: isBrutal ? 9 : 13,
      backgroundColor: heroCellBg,
      borderWidth: isBrutal ? 1.5 : 1,
      borderColor: heroChipBorder,
    },
    heroMetricHeader: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 5,
    },
    metricDot: {
      width: 6,
      height: 6,
      borderRadius: 3,
    },
    metricDotAsset: {
      backgroundColor: heroBarAsset,
    },
    metricDotDebt: {
      backgroundColor: heroBarDebt,
    },
    heroMetricLabel: {
      fontSize: 11,
      fontWeight: isHogwarts ? undefined : '600',
      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: heroMuted,
    },
    heroMetricVal: {
      marginTop: 4,
      fontSize: 15.5,
      fontWeight: isHogwarts ? undefined : '700',
      fontFamily: isHogwarts
        ? HOGWARTS_FONTS.numeric
        : shape.isSoft
          ? undefined
          : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
      color: heroFg,
    },
    /* 对账提醒横幅 */
    reconcileBanner: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      gap: 10,
      paddingHorizontal: 12,
      paddingVertical: 11,
      borderRadius: shape.isSoft ? 16 : isHogwarts ? 15 : 12,
      backgroundColor: warnBg,
      borderWidth: isBrutal ? borderWidth.medium : 1,
      borderColor: warnBorder,
      marginBottom: 14,
      ...(isBrutal ? shadow.small : shape.elevation.card),
    },
    reconcileLeft: {
      flex: 1,
      flexDirection: 'row',
      alignItems: 'center',
      gap: 9,
      minWidth: 0,
    },
    reconcileIconBox: {
      width: 30,
      height: 30,
      borderRadius: 10,
      backgroundColor: isDark
        ? 'rgba(229, 169, 76, 0.18)'
        : 'rgba(184, 107, 20, 0.14)',
      alignItems: 'center',
      justifyContent: 'center',
    },
    reconcileTextWrap: {
      flex: 1,
      minWidth: 0,
    },
    reconcileTitle: {
      fontSize: 13,
      fontWeight: isHogwarts ? undefined : '700',
      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    reconcileDesc: {
      marginTop: 2,
      fontSize: 11.5,
      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    reconcileCta: {
      paddingHorizontal: 10,
      paddingVertical: 6,
      borderRadius: 99,
      backgroundColor: colors.surface,
      borderWidth: 1,
      borderColor: warnBorder,
    },
    reconcileCtaText: {
      fontSize: 12,
      fontWeight: isHogwarts ? undefined : '700',
      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.warning,
    },
    /* 分组标题 */
    sectionBlock: {
      marginBottom: 14,
    },
    sectionHeader: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingHorizontal: 4,
      paddingBottom: 8,
    },
    sectionTitleGroup: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 7,
    },
    sectionDot: {
      width: 7,
      height: 7,
      borderRadius: 4,
    },
    sectionDotDebt: {
      borderRadius: 2,
    },
    sectionTitle: {
      fontSize: 13.5,
      fontWeight: isHogwarts ? undefined : '700',
      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    sectionCountBadge: {
      paddingHorizontal: 7,
      paddingVertical: 1,
      borderRadius: 99,
      backgroundColor: colors.surfaceMuted,
    },
    sectionCountText: {
      fontSize: 11,
      fontWeight: isHogwarts ? undefined : '700',
      fontFamily: isHogwarts ? HOGWARTS_FONTS.numeric : undefined,
      color: colors.textTertiary,
    },
    sectionTotal: {
      fontSize: 13.5,
      fontWeight: isHogwarts ? undefined : '700',
      fontFamily: isHogwarts
        ? HOGWARTS_FONTS.numeric
        : shape.isSoft
          ? undefined
          : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
      color: colors.textSecondary,
    },
    sectionTotalDebt: {
      color: colors.expense,
    },
    /* 分组账户卡片容器 */
    groupCard: {
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: isBrutal ? borderWidth.medium : shape.borderWidth.card,
      borderColor: isBrutal ? colors.stroke : colors.cardBorder,
      overflow: 'hidden',
      ...(isBrutal ? shadow.small : shape.elevation.card),
    },
    accountItem: {},
    accountItemDivider: {
      borderTopWidth: StyleSheet.hairlineWidth,
      borderTopColor: colors.divider,
    },
    accountItemExpanded: {
      backgroundColor: expandedBg,
    },
    accountRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 12,
      paddingHorizontal: 14,
      paddingVertical: 13,
    },
    accountIconTile: {
      width: 44,
      height: 44,
      borderRadius: shape.isSoft ? 14 : isHogwarts ? 13 : 10,
      borderWidth: 1.5,
      alignItems: 'center',
      justifyContent: 'center',
    },
    accountIconInner: {
      width: 30,
      height: 30,
      borderRadius: 9,
      alignItems: 'center',
      justifyContent: 'center',
    },
    accountInfo: {
      flex: 1,
      minWidth: 0,
    },
    accountNameLine: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 6,
    },
    accountName: {
      flexShrink: 1,
      fontSize: 15,
      fontWeight: isHogwarts ? undefined : '700',
      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    warnBadge: {
      paddingHorizontal: 6,
      paddingVertical: 1,
      borderRadius: 99,
      backgroundColor: isDark
        ? 'rgba(229, 169, 76, 0.20)'
        : 'rgba(184, 107, 20, 0.14)',
    },
    warnBadgeText: {
      fontSize: 10,
      fontWeight: isHogwarts ? undefined : '700',
      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.warning,
    },
    accountMetaLine: {
      marginTop: 4,
      flexDirection: 'row',
      alignItems: 'center',
      gap: 6,
    },
    accountSubtype: {
      fontSize: 11.5,
      fontWeight: isHogwarts ? undefined : '600',
      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
    tailChip: {
      paddingHorizontal: 6,
      paddingVertical: 1,
      borderRadius: 6,
      borderWidth: 1,
    },
    tailChipText: {
      fontSize: 10.5,
      fontWeight: '700',
      fontFamily: typography.fontFamily.mono,
      letterSpacing: 0.4,
    },
    accountRight: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 5,
      flexShrink: 0,
    },
    accountBalance: {
      fontSize: 16,
      fontWeight: isHogwarts ? undefined : '700',
      fontFamily: isHogwarts
        ? HOGWARTS_FONTS.numeric
        : shape.isSoft
          ? undefined
          : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
      color: colors.textPrimary,
    },
    debtBalance: {
      color: colors.expense,
    },
    /* 展开后的轻巧快捷操作条 */
    actionsTray: {
      flexDirection: 'row',
      gap: 8,
      paddingHorizontal: 14,
      paddingBottom: 12,
    },
    trayBtn: {
      flex: 1,
      height: 36,
      borderRadius: isBrutal ? 9 : 11,
      backgroundColor: colors.surface,
      borderWidth: isBrutal ? 1.5 : 1,
      borderColor: isBrutal ? colors.stroke : colors.divider,
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'center',
      gap: 5,
    },
    trayBtnPrimary: {
      flex: 1.25,
      backgroundColor: colors.primaryLight,
      borderColor: isBrutal ? colors.stroke : 'transparent',
    },
    trayBtnText: {
      fontSize: 12.5,
      fontWeight: isHogwarts ? undefined : '700',
      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    trayBtnTextPrimary: {
      color: colors.primary,
    },
    trayBtnTextDanger: {
      color: colors.expense,
    },
    /* 折叠已停用账户 */
    inactiveBlock: {
      marginTop: 2,
    },
    inactiveHeaderBtn: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingHorizontal: 4,
      paddingVertical: 10,
    },
    inactiveHeaderText: {
      fontSize: 13,
      fontWeight: isHogwarts ? undefined : '600',
      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
    inactiveToggleRight: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 3,
    },
    inactiveToggleLabel: {
      fontSize: 12.5,
      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
    inactiveRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 11,
      paddingHorizontal: 14,
      paddingVertical: 12,
      opacity: 0.78,
    },
    inactiveIconTile: {
      width: 40,
      height: 40,
      borderRadius: shape.isSoft ? 12 : 10,
      borderWidth: 1.5,
      alignItems: 'center',
      justifyContent: 'center',
    },
    inactiveIconInner: {
      width: 26,
      height: 26,
      borderRadius: 8,
      alignItems: 'center',
      justifyContent: 'center',
    },
    inactiveName: {
      fontSize: 14.5,
      fontWeight: isHogwarts ? undefined : '700',
      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    reactivateBtn: {
      paddingHorizontal: 11,
      paddingVertical: 6,
      borderRadius: 99,
      backgroundColor: colors.incomeLight,
    },
    reactivateText: {
      fontSize: 12,
      fontWeight: isHogwarts ? undefined : '700',
      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.incomeStrong,
    },
    /* 空状态 */
    emptyState: {
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: isBrutal ? borderWidth.medium : shape.borderWidth.card,
      borderColor: isBrutal ? colors.stroke : colors.cardBorder,
      paddingHorizontal: 24,
      paddingVertical: 36,
      alignItems: 'center',
      marginTop: 6,
      ...(isBrutal ? shadow.small : shape.elevation.card),
    },
    emptyMedallion: {
      width: 84,
      height: 84,
      borderRadius: 42,
      overflow: 'hidden',
      backgroundColor: colors.primaryLight,
      borderWidth: 2,
      borderColor: isHogwarts ? colors.accent : colors.primary,
      alignItems: 'center',
      justifyContent: 'center',
      marginBottom: 14,
    },
    emptyOwlImg: {
      width: '100%',
      height: '100%',
    },
    emptyTitle: {
      fontSize: 17,
      fontWeight: isHogwarts ? undefined : '700',
      fontFamily: isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
    },
    emptySubtitle: {
      marginTop: 7,
      fontSize: 13,
      lineHeight: 20,
      textAlign: 'center',
      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
    emptyCtaBtn: {
      marginTop: 20,
      height: 44,
      paddingHorizontal: 22,
      borderRadius: shape.radius.button,
      backgroundColor: colors.primary,
      borderWidth: isBrutal ? borderWidth.medium : isHogwarts ? 1 : 0,
      borderColor: isHogwarts ? colors.accent : colors.stroke,
      flexDirection: 'row',
      alignItems: 'center',
      gap: 6,
      ...(isBrutal ? shadow.small : shape.elevation.card),
    },
    emptyCtaText: {
      fontSize: 14.5,
      fontWeight: isHogwarts ? undefined : '700',
      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: shape.isSoft || isHogwarts ? colors.onPrimary : '#FFFFFF',
    },
  });
};

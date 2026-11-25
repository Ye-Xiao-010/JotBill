/**
 * 设置屏幕 —— 方案 C（Bento 双卡控制台）
 *
 * 信息架构（柔光 / 经典积木 / 魔法羊皮纸全主题统一，保留各主题材质、描边、圆角与字体）：
 * 1. 顶部栏：标题 + 本地加密副文案 + 右上角唯一的浅/深色模式切换按钮；
 * 2. 首屏 Bento 双卡：左卡专注「自动记账」（总开关 + 启用渠道/权限，不混入待确认），
 *    右卡专注「待确认中心」（独立承载待确认笔数）；彻底不展示净资产；
 * 3. 4 格财务快捷瓷砖：账户 / 分类 / 预算 / 目标；
 * 4. 「自动化与数据」5 行：识别与分类规则 / 权限与提醒 / 运行状态 / AI 服务商 / 数据管理；
 * 5. 「外观与显示」：首页预算与目标开关（置顶）+ 默认折叠的「主题风格」三大主卡选择区。
 */
import React, { useCallback, useMemo } from 'react';
import {
  Image,
  Platform,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import { useQuery } from '@tanstack/react-query';
import {
  Activity,
  Bell,
  Bot,
  ChevronRight,
  CreditCard,
  Database,
  Flag,
  Inbox,
  Layers,
  ListFilter,
  Moon,
  PieChart,
  ShieldCheck,
  Sun,
  Tags,
  Wallet,
  Zap,
} from 'lucide-react-native';
import { ThemeColors } from '../theme/colors';
import { borderRadius, spacing, type ThemeShape } from '../theme';
import { useStyles } from '../hooks';
import { useDisplayPreferences, useTheme } from '../providers';
import {
  nativeAi,
  nativeAutoBookkeeping,
  nativeData,
  nativeRecognition,
  nativeRuntime,
} from '../native';
import type { RecognitionPermission } from '../native/runtime';
import { CACHE_TIME, GC_TIME, QUERY_KEYS } from '../lib/queryClient';
import {
  HOGWARTS_FONTS,
  HOGWARTS_HOUSES,
  HOGWARTS_IMAGES,
} from '../constants/hogwartsAssets';
import SettingsThemeSection from '../components/settings/SettingsThemeSection';

export interface SettingsScreenProps {
  navigation?: any;
}

type SettingsIconKey =
  | 'accounts'
  | 'category'
  | 'budget'
  | 'flag'
  | 'review'
  | 'autoBookkeeping'
  | 'rules'
  | 'permissions'
  | 'runtime'
  | 'ai'
  | 'data'
  | 'budgetSwitch';

const ICON_CANDY_COLORS: Record<SettingsIconKey, string> = {
  accounts: '#A5D8FF',
  category: '#FFE8CC',
  budget: '#FFD93D',
  flag: '#FF90B3',
  review: '#FFC9DE',
  autoBookkeeping: '#99E9F2',
  rules: '#D3F9D8',
  permissions: '#B5E8DC',
  runtime: '#B2F2BB',
  ai: '#D0BFFF',
  data: '#FFD8A8',
  budgetSwitch: '#FFD93D',
};

const ICON_SOFT_COLORS: Record<SettingsIconKey, string> = {
  accounts: '#4F46E5',
  category: '#EC4899',
  budget: '#F59E0B',
  flag: '#10B981',
  review: '#F97316',
  autoBookkeeping: '#8B5CF6',
  rules: '#0891B2',
  permissions: '#EF4444',
  runtime: '#14B8A6',
  ai: '#6366F1',
  data: '#64748B',
  budgetSwitch: '#F59E0B',
};

const PERMISSION_KEYS: readonly RecognitionPermission[] = [
  'accessibility',
  'notificationAccess',
  'overlay',
  'appNotifications',
];

const RULES_QUERY_KEY = ['rules'] as const;
const AI_PROVIDERS_QUERY_KEY = ['ai-providers'] as const;

export default function SettingsScreen({ navigation }: SettingsScreenProps) {
  const {
    isDark,
    isSoft,
    isHogwarts,
    toggleTheme,
    colors,
    hogwartsHouse,
  } = useTheme();
  const {
    showDashboardBudget,
    setShowDashboardBudget,
  } = useDisplayPreferences();
  const styles = useStyles(createStyles);
  const isAndroid = Platform.OS === 'android';

  // 只读状态查询（全部复用现有 QueryKey，失败静默降级不阻塞设置页）
  const { data: bookkeepingSettings, refetch: refetchBookkeeping } = useQuery({
    queryKey: QUERY_KEYS.bookkeeping.settings,
    queryFn: () => nativeAutoBookkeeping.getSettings(),
    enabled: isAndroid,
    staleTime: 30 * 1000,
    gcTime: GC_TIME.DEFAULT,
    retry: false,
  });

  const { data: recognitionStatus, refetch: refetchRecognition } = useQuery({
    queryKey: QUERY_KEYS.recognition.status,
    queryFn: () => nativeRecognition.getRecognitionStatus(),
    staleTime: 30 * 1000,
    gcTime: GC_TIME.DEFAULT,
    retry: false,
  });

  const { data: permissionsData, refetch: refetchPermissions } = useQuery({
    queryKey: QUERY_KEYS.recognition.permissions,
    queryFn: () => nativeRuntime.getRecognitionPermissions(),
    enabled: isAndroid,
    staleTime: 0,
    gcTime: GC_TIME.DEFAULT,
    retry: false,
  });

  const { data: accountsList = [] } = useQuery({
    queryKey: QUERY_KEYS.accountsList,
    queryFn: () => nativeData.getAccounts(),
    staleTime: CACHE_TIME.DASHBOARD,
    gcTime: GC_TIME.DEFAULT,
    retry: false,
  });

  const { data: categoriesList = [] } = useQuery({
    queryKey: QUERY_KEYS.categories.all,
    queryFn: () => nativeData.getCategories(),
    staleTime: CACHE_TIME.CATEGORIES,
    gcTime: GC_TIME.DEFAULT,
    retry: false,
  });

  const { data: rulesList = [] } = useQuery({
    queryKey: RULES_QUERY_KEY,
    queryFn: () => nativeData.getRules(),
    staleTime: CACHE_TIME.DASHBOARD,
    gcTime: GC_TIME.DEFAULT,
    retry: false,
  });

  const { data: aiProviders = [] } = useQuery({
    queryKey: AI_PROVIDERS_QUERY_KEY,
    queryFn: () => nativeAi.getAiProviders(),
    staleTime: CACHE_TIME.DASHBOARD,
    gcTime: GC_TIME.DEFAULT,
    retry: false,
  });

  // 从子页（自动记账 / 权限与提醒 / 待确认）返回时及时刷新动态状态
  useFocusEffect(
    useCallback(() => {
      refetchRecognition().catch(() => {});
      if (isAndroid) {
        refetchBookkeeping().catch(() => {});
        refetchPermissions().catch(() => {});
      }
    }, [isAndroid, refetchBookkeeping, refetchPermissions, refetchRecognition]),
  );

  const activeHouseMeta = HOGWARTS_HOUSES[hogwartsHouse] ?? HOGWARTS_HOUSES.classic;
  const pendingCount = recognitionStatus?.pendingCount ?? 0;
  const activeAccountCount = useMemo(
    () => accountsList.filter(a => a.isActive).length,
    [accountsList],
  );
  const categoryCount = categoriesList.length;
  const ruleCount = rulesList.length;

  const permSummary = useMemo(() => {
    if (!permissionsData) {
      return { total: 0, ready: 0, issues: 0 };
    }
    let total = 0;
    let ready = 0;
    let issues = 0;
    for (const key of PERMISSION_KEYS) {
      const state = permissionsData[key];
      if (state === 'not_required') continue;
      total += 1;
      if (state === 'ready') ready += 1;
      else if (state === 'missing' || state === 'disconnected') issues += 1;
    }
    return { total, ready, issues };
  }, [permissionsData]);

  // 自动记账卡状态（仅反映自动记账总开关、启用渠道与权限健康度，绝不混入待确认）
  const autoCardState = useMemo(() => {
    const warnBg = isDark ? 'rgba(251, 191, 36, 0.18)' : 'rgba(245, 158, 11, 0.14)';
    if (!bookkeepingSettings) {
      return {
        pillLabel: permSummary.issues > 0 ? '需权限' : '去配置',
        pillColor: permSummary.issues > 0 ? colors.warning : colors.textSecondary,
        pillBg: permSummary.issues > 0 ? warnBg : colors.surfaceMuted,
        subText:
          permSummary.issues > 0
            ? `${permSummary.issues} 项识别权限未就绪`
            : '管理自动记账渠道与开关',
        subAlert: permSummary.issues > 0,
      };
    }

    if (!bookkeepingSettings.masterEnabled) {
      return {
        pillLabel: '已暂停',
        pillColor: colors.textTertiary,
        pillBg: colors.surfaceMuted,
        subText: '总开关已关闭 · 点此开启',
        subAlert: false,
      };
    }

    const totalChannels =
      bookkeepingSettings.channelMeta?.length ||
      Object.keys(bookkeepingSettings.channels ?? {}).length;
    const enabledChannels = Object.values(bookkeepingSettings.channels ?? {}).filter(
      Boolean,
    ).length;

    if (permSummary.issues > 0) {
      return {
        pillLabel: '需权限',
        pillColor: colors.warning,
        pillBg: warnBg,
        subText: `${permSummary.issues} 项识别权限未就绪`,
        subAlert: true,
      };
    }

    const modeHint = bookkeepingSettings.silentBookkeepingEnabled ? '静默入账' : '弹窗确认';
    const channelText =
      totalChannels > 0 ? `${enabledChannels}/${totalChannels} 渠道` : '渠道已启用';
    return {
      pillLabel: '已开启',
      pillColor: colors.incomeStrong,
      pillBg: colors.incomeLight,
      subText: `${channelText} · ${modeHint}`,
      subAlert: false,
    };
  }, [bookkeepingSettings, colors, isDark, permSummary.issues]);

  const activeAiLabel = useMemo(() => {
    if (aiProviders.length === 0) return '未配置';
    const def = aiProviders.find(p => p.isDefault) ?? aiProviders[0];
    return def.name || def.provider;
  }, [aiProviders]);

  const iconMap: Record<
    SettingsIconKey,
    React.FC<{ size: number; color: string; strokeWidth?: number }>
  > = {
    accounts: CreditCard,
    category: Tags,
    budget: isSoft ? PieChart : Wallet,
    flag: Flag,
    review: Inbox,
    autoBookkeeping: Zap,
    rules: ListFilter,
    permissions: isSoft ? Bell : ShieldCheck,
    runtime: Activity,
    ai: Bot,
    data: Database,
    budgetSwitch: Layers,
  };

  const renderIconTile = (key: SettingsIconKey, size: 'sm' | 'md' = 'md') => {
    const IconComp = iconMap[key];
    const bg = isSoft
      ? ICON_SOFT_COLORS[key]
      : isHogwarts
        ? colors.primaryLight
        : ICON_CANDY_COLORS[key];
    const fg = isSoft ? '#FFFFFF' : isHogwarts ? colors.primary : colors.stroke;
    const iconSize = size === 'sm' ? 16 : isSoft ? 17 : 18;
    return (
      <View
        style={[
          size === 'sm' ? styles.iconTileSm : styles.iconTileMd,
          { backgroundColor: bg },
        ]}
      >
        <IconComp size={iconSize} color={fg} strokeWidth={2.2} />
      </View>
    );
  };

  const warnBg = isDark ? 'rgba(251, 191, 36, 0.18)' : 'rgba(245, 158, 11, 0.14)';
  const financeTiles: Array<{
    id: string;
    icon: SettingsIconKey;
    label: string;
    sub: string;
    route: string;
  }> = [
    {
      id: 'accounts',
      icon: 'accounts',
      label: '账户',
      sub: activeAccountCount > 0 ? `${activeAccountCount} 个` : '管理',
      route: 'Accounts',
    },
    {
      id: 'categories',
      icon: 'category',
      label: '分类',
      sub: categoryCount > 0 ? `${categoryCount} 个` : '管理',
      route: 'Categories',
    },
    {
      id: 'budgets',
      icon: 'budget',
      label: '预算',
      sub: '上限',
      route: 'Budgets',
    },
    {
      id: 'goals',
      icon: 'flag',
      label: '目标',
      sub: '攒钱',
      route: 'FinancialGoals',
    },
  ];

  const automationRows: Array<{
    id: string;
    icon: SettingsIconKey;
    label: string;
    route: string;
    rightNode?: React.ReactNode;
  }> = [
    {
      id: 'rules',
      icon: 'rules',
      label: '识别与分类规则',
      route: 'Rules',
      rightNode:
        ruleCount > 0 ? (
          <Text style={styles.rowRightMuted}>{ruleCount} 条</Text>
        ) : null,
    },
    {
      id: 'permissions',
      icon: 'permissions',
      label: '权限与提醒',
      route: 'GeneralSettings',
      rightNode:
        permSummary.total > 0 ? (
          permSummary.issues > 0 ? (
            <View style={[styles.statusPill, { backgroundColor: warnBg }]}>
              <Text style={[styles.statusPillText, { color: colors.warning }]}>
                {permSummary.issues} 项异常
              </Text>
            </View>
          ) : (
            <View style={[styles.statusPill, { backgroundColor: colors.incomeLight }]}>
              <Text style={[styles.statusPillText, { color: colors.incomeStrong }]}>
                {permSummary.ready}/{permSummary.total} 就绪
              </Text>
            </View>
          )
        ) : null,
    },
    {
      id: 'runtime',
      icon: 'runtime',
      label: '运行状态',
      route: 'RuntimeStatus',
      rightNode:
        permSummary.total > 0 ? (
          <Text
            style={[
              styles.rowRightStatus,
              { color: permSummary.issues > 0 ? colors.warning : colors.income },
            ]}
          >
            {permSummary.issues > 0 ? '● 需检查' : '● 正常'}
          </Text>
        ) : null,
    },
    {
      id: 'ai',
      icon: 'ai',
      label: 'AI 服务商',
      route: 'AiProviders',
      rightNode: (
        <Text style={styles.rowRightMuted} numberOfLines={1}>
          {activeAiLabel}
        </Text>
      ),
    },
    {
      id: 'data',
      icon: 'data',
      label: '数据管理',
      route: 'DataManagement',
      rightNode: <Text style={styles.rowRightMuted}>备份 · 导入</Text>,
    },
  ];

  const hasPending = pendingCount > 0;

  return (
    <View style={styles.container}>
      {isHogwarts && !isDark ? (
        <View pointerEvents="none" style={styles.parchmentBackdrop}>
          <Image
            source={HOGWARTS_IMAGES.parchmentTexture}
            style={StyleSheet.absoluteFill}
            resizeMode="cover"
          />
        </View>
      ) : null}

      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.scrollContent}
        showsVerticalScrollIndicator={false}
      >
        {/* 顶部标题栏 + 右上角唯一的浅/深色切换按钮 */}
        <View style={styles.topBar}>
          <View style={styles.topLeft}>
            {isHogwarts ? (
              <Image
                source={activeHouseMeta.crest}
                style={styles.hogwartsCrestMini}
                resizeMode="cover"
              />
            ) : null}
            <View style={styles.topTitleBlock}>
              <Text style={styles.topTitle}>
                {isHogwarts ? '古灵阁设置' : isSoft ? '我的' : '设置'}
              </Text>
              <View style={styles.shieldRow}>
                <ShieldCheck size={12} color={colors.income} strokeWidth={2.3} />
                <Text style={styles.topSubtitle} numberOfLines={1}>
                  {isHogwarts
                    ? `${activeHouseMeta.label} · 本地全库加密`
                    : '本地账本 · 全库加密存储'}
                </Text>
              </View>
            </View>
          </View>

          <TouchableOpacity
            style={styles.darkModeButton}
            onPress={toggleTheme}
            activeOpacity={0.78}
            accessibilityRole="button"
            accessibilityLabel={isDark ? '切换到浅色模式' : '切换到深色模式'}
            accessibilityState={{ checked: isDark }}
          >
            {isDark ? (
              <Sun size={15} color={colors.textPrimary} strokeWidth={2.2} />
            ) : (
              <Moon size={15} color={colors.textPrimary} strokeWidth={2.2} />
            )}
            <Text style={styles.darkModeButtonText}>
              {isDark ? '浅色' : '深色'}
            </Text>
          </TouchableOpacity>
        </View>

        {/* 首屏 Bento 双主卡：职责完全独立，不展示净资产 */}
        <View style={styles.bentoRow}>
          {/* 左卡：自动记账（只展示开关/渠道/权限，不含待确认） */}
          <TouchableOpacity
            style={styles.bentoCard}
            onPress={() => navigation?.navigate('AutoBookkeeping')}
            activeOpacity={0.8}
            accessibilityRole="button"
            accessibilityLabel={`自动记账，${autoCardState.pillLabel}，${autoCardState.subText}`}
          >
            <View style={styles.bentoCardTop}>
              {renderIconTile('autoBookkeeping', 'sm')}
              <View
                style={[
                  styles.statusPill,
                  { backgroundColor: autoCardState.pillBg },
                ]}
              >
                <Text
                  style={[
                    styles.statusPillText,
                    { color: autoCardState.pillColor },
                  ]}
                  numberOfLines={1}
                >
                  {autoCardState.pillLabel}
                </Text>
              </View>
            </View>
            <View style={styles.bentoCardBody}>
              <Text style={styles.bentoTitle} numberOfLines={1}>
                自动记账
              </Text>
              <Text
                style={[
                  styles.bentoSub,
                  autoCardState.subAlert && { color: colors.warning },
                ]}
                numberOfLines={2}
              >
                {autoCardState.subText}
              </Text>
            </View>
          </TouchableOpacity>

          {/* 右卡：待确认中心（独立承载待确认笔数） */}
          <TouchableOpacity
            style={[
              styles.bentoCard,
              hasPending && isSoft && styles.bentoCardPendingSoft,
            ]}
            onPress={() => navigation?.navigate('Review')}
            activeOpacity={0.8}
            accessibilityRole="button"
            accessibilityLabel={
              hasPending
                ? `待确认中心，${pendingCount} 笔交易待核对`
                : '待确认中心，已清空'
            }
          >
            <View style={styles.bentoCardTop}>
              {renderIconTile('review', 'sm')}
              <View
                style={[
                  styles.statusPill,
                  {
                    backgroundColor: hasPending
                      ? colors.expenseLight
                      : colors.incomeLight,
                  },
                ]}
              >
                <Text
                  style={[
                    styles.statusPillText,
                    {
                      color: hasPending
                        ? colors.expenseStrong
                        : colors.incomeStrong,
                    },
                  ]}
                  numberOfLines={1}
                >
                  {hasPending ? `${pendingCount} 笔待核` : '已清空'}
                </Text>
              </View>
            </View>
            <View style={styles.bentoCardBody}>
              <Text style={styles.bentoTitle} numberOfLines={1}>
                待确认中心
              </Text>
              <Text
                style={[
                  styles.bentoSub,
                  hasPending && styles.bentoSubAlert,
                ]}
                numberOfLines={2}
              >
                {hasPending ? '点击核对后入账 ›' : '暂无待核对交易'}
              </Text>
            </View>
          </TouchableOpacity>
        </View>

        {/* 4 格财务快捷瓷砖（不含净资产） */}
        <View style={styles.financeGrid}>
          {financeTiles.map(tile => (
            <TouchableOpacity
              key={tile.id}
              style={styles.financeTile}
              onPress={() => navigation?.navigate(tile.route)}
              activeOpacity={0.78}
              accessibilityRole="button"
              accessibilityLabel={`${tile.label}管理 ${tile.sub}`}
            >
              {renderIconTile(tile.icon, 'md')}
              <Text style={styles.financeTileLabel} numberOfLines={1}>
                {tile.label}
              </Text>
              <Text style={styles.financeTileSub} numberOfLines={1}>
                {tile.sub}
              </Text>
            </TouchableOpacity>
          ))}
        </View>

        {/* 自动化与数据（5 行，不含重复的待确认中心） */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>自动化与数据</Text>
          <View style={styles.groupCard}>
            {automationRows.map((row, idx) => (
              <View key={row.id}>
                {idx > 0 ? <View style={styles.rowDivider} /> : null}
                <TouchableOpacity
                  style={styles.listRow}
                  onPress={() => navigation?.navigate(row.route)}
                  activeOpacity={0.74}
                  accessibilityRole="button"
                  accessibilityLabel={row.label}
                >
                  {renderIconTile(row.icon, 'md')}
                  <Text style={styles.rowLabel} numberOfLines={1}>
                    {row.label}
                  </Text>
                  <View style={styles.rowRight}>
                    {row.rightNode}
                    <ChevronRight
                      size={17}
                      color={colors.textQuaternary}
                      strokeWidth={2.2}
                    />
                  </View>
                </TouchableOpacity>
              </View>
            ))}
          </View>
        </View>

        {/* 外观与显示：首页预算开关置顶，下方默认折叠的「主题风格」 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>外观与显示</Text>
          <View style={styles.groupCard}>
            <View style={styles.listRow}>
              {renderIconTile('budgetSwitch', 'md')}
              <View style={styles.rowTextBlock}>
                <Text style={styles.rowLabelInline}>首页预算与目标</Text>
                <Text style={styles.rowDescription}>
                  在首页展示预算进度与目标卡片
                </Text>
              </View>
              <Switch
                value={showDashboardBudget}
                onValueChange={setShowDashboardBudget}
                trackColor={{
                  false: isSoft ? colors.surfaceMuted : colors.divider,
                  true: colors.primary,
                }}
                thumbColor="#FFFFFF"
                accessibilityLabel="首页预算与目标"
              />
            </View>
            <View style={styles.fullDivider} />
            <SettingsThemeSection />
          </View>
        </View>

        <View style={styles.versionInfo}>
          <Text style={styles.versionText}>招财猫猫 · © 2026</Text>
        </View>
      </ScrollView>
    </View>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape, isDark: boolean) => ({
  ...StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: colors.background,
    },
    parchmentBackdrop: {
      ...StyleSheet.absoluteFillObject,
      opacity: 0.07,
    },
    scrollView: {
      flex: 1,
    },
    scrollContent: {
      paddingHorizontal: spacing.lg,
      paddingTop: spacing.md,
      paddingBottom: shape.isSoft ? 116 : spacing.xxl,
    },
    topBar: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      gap: spacing.md,
      marginBottom: spacing.md,
      paddingHorizontal: 2,
    },
    topLeft: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 10,
      flex: 1,
      minWidth: 0,
    },
    hogwartsCrestMini: {
      width: 38,
      height: 38,
      borderRadius: 19,
      borderWidth: 1.5,
      borderColor: colors.accent,
      flexShrink: 0,
    },
    topTitleBlock: {
      flex: 1,
      minWidth: 0,
    },
    topTitle: {
      fontSize: 22,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
      letterSpacing: shape.isSoft ? -0.3 : 0,
    },
    shieldRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 4,
      marginTop: 2,
    },
    topSubtitle: {
      fontSize: 12,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
      flexShrink: 1,
    },
    darkModeButton: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 5,
      height: 36,
      paddingHorizontal: 12,
      borderRadius: shape.radius.pill,
      backgroundColor: colors.surface,
      borderWidth: shape.isSoft && !isDark ? 1 : shape.borderWidth.card,
      borderColor: colors.cardBorder,
      flexShrink: 0,
      ...(shape.isSoft ? shape.elevation.card : {}),
    },
    darkModeButtonText: {
      fontSize: 12.5,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    bentoRow: {
      flexDirection: 'row',
      gap: 10,
    },
    bentoCard: {
      flex: 1,
      minHeight: 112,
      padding: 13,
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.borderWidth.card,
      borderColor: colors.cardBorder,
      justifyContent: 'space-between',
      ...shape.elevation.card,
    },
    bentoCardPendingSoft: {
      borderWidth: 1,
      borderColor: isDark
        ? 'rgba(248, 113, 113, 0.35)'
        : 'rgba(220, 38, 38, 0.22)',
    },
    bentoCardTop: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      gap: 6,
    },
    bentoCardBody: {
      marginTop: 12,
      gap: 3,
    },
    bentoTitle: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
    },
    bentoSub: {
      fontSize: 11.5,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
      lineHeight: 15,
    },
    bentoSubAlert: {
      color: colors.expense,
      fontWeight: shape.isHogwarts ? undefined : '600',
    },
    statusPill: {
      paddingHorizontal: 7,
      paddingVertical: 2.5,
      borderRadius: 999,
      flexShrink: 1,
    },
    statusPillText: {
      fontSize: 11,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      fontVariant: ['tabular-nums'],
    },
    financeGrid: {
      flexDirection: 'row',
      gap: 8,
      marginTop: 10,
    },
    financeTile: {
      flex: 1,
      alignItems: 'center',
      paddingVertical: 11,
      paddingHorizontal: 6,
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.borderWidth.card,
      borderColor: colors.cardBorder,
      gap: 4,
      ...shape.elevation.card,
    },
    financeTileLabel: {
      fontSize: 13,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
      marginTop: 2,
    },
    financeTileSub: {
      fontSize: 10.5,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
      fontVariant: ['tabular-nums'],
    },
    iconTileSm: {
      width: 30,
      height: 30,
      borderRadius: shape.isSoft ? 10 : shape.isHogwarts ? 10 : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : shape.isHogwarts ? 1 : 1.5,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      alignItems: 'center',
      justifyContent: 'center',
      flexShrink: 0,
    },
    iconTileMd: {
      width: shape.isSoft ? 32 : 34,
      height: shape.isSoft ? 32 : 34,
      borderRadius: shape.isSoft ? 11 : shape.isHogwarts ? 11 : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : shape.isHogwarts ? 1 : 1.5,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      alignItems: 'center',
      justifyContent: 'center',
      flexShrink: 0,
    },
    section: {
      marginTop: 16,
    },
    sectionTitle: {
      fontSize: 13,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textTertiary,
      paddingHorizontal: 6,
      marginBottom: 8,
    },
    groupCard: {
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.borderWidth.card,
      borderColor: colors.cardBorder,
      overflow: 'hidden',
      ...shape.elevation.card,
    },
    listRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 12,
      paddingHorizontal: 14,
      paddingVertical: 12,
      minHeight: 54,
    },
    rowDivider: {
      height: StyleSheet.hairlineWidth,
      backgroundColor: colors.divider,
      marginLeft: shape.isSoft ? 58 : 60,
      marginRight: 14,
    },
    fullDivider: {
      height: StyleSheet.hairlineWidth,
      backgroundColor: colors.divider,
      marginHorizontal: 14,
    },
    rowTextBlock: {
      flex: 1,
      minWidth: 0,
      gap: 2,
    },
    rowLabel: {
      flex: 1,
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '500' : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    rowLabelInline: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '500' : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    rowDescription: {
      fontSize: 12,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
      lineHeight: 16,
    },
    rowRight: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 6,
      flexShrink: 0,
      maxWidth: '45%',
    },
    rowRightMuted: {
      fontSize: 12.5,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
      fontVariant: ['tabular-nums'],
      flexShrink: 1,
    },
    rowRightStatus: {
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
    },
    versionInfo: {
      alignItems: 'center',
      paddingTop: spacing.lg,
      paddingBottom: spacing.md,
    },
    versionText: {
      fontSize: 12,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textQuaternary,
    },
  }),
});

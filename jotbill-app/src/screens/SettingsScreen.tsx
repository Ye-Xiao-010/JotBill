/**
 * 设置屏幕 - Neo-Brutalism 风格
 * 描边用户卡片 + 糖果色图标块 + 描边设置分组
 * 仅保留入口标题；权限相关统一收进「权限与提醒」页。
 */
import React, { useState } from 'react';
import {
  Image,
  View,
  Text,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  Switch,
} from 'react-native';
import { useQuery } from '@tanstack/react-query';
import {
  Activity,
  Bell,
  Bot,
  CreditCard,
  Database,
  Wallet,
  Flag,
  Inbox,
  Layers,
  ListFilter,
  Moon,
  Palette,
  PieChart,
  ShieldCheck,
  Tags,
  TrendingUp,
  ChevronRight,
  Zap,
  Volume2,
} from 'lucide-react-native';
import { ThemeColors, themes, THEME_LABELS, type ThemeName } from '../theme/colors';
import { spacing, borderRadius, borderWidth, shadow, typography, type ThemeShape } from '../theme';
import { SoftGradient } from '../components/ui';
import { useStyles, useToast } from '../hooks';
import { useDisplayPreferences, useTheme } from '../providers';
import { nativeData, nativeRecognition } from '../native';
import { CACHE_TIME, GC_TIME, QUERY_KEYS } from '../lib/queryClient';
import {
  HOGWARTS_FONTS,
  HOGWARTS_HOUSES,
  HOGWARTS_HOUSE_ORDER,
  HOGWARTS_IMAGES,
} from '../constants/hogwartsAssets';
import type { MagicSoundKey } from '../native/theme';


interface SettingsScreenProps {
  navigation?: any;
}

// 设置图标键：与下面的 iconMap / ICON_CANDY_COLORS 强绑定，
// 新增设置项时漏配图标会直接编译报错（此前「自动记账」就因键名漂移变成空白方块）。
type SettingsIconKey =
  | 'accounts'
  | 'category'
  | 'wallet'
  | 'flag'
  | 'netWorth'
  | 'review'
  | 'autoBookkeeping'
  | 'permissions'
  | 'ai'
  | 'runtime'
  | 'data'
  | 'rules'
  | 'moon';

// 设置项类型定义
interface BaseSettingItem {
  id: string;
  label: string;
  icon: SettingsIconKey;
  /** 可选副标题：解释开关/入口的作用，帮助用户判断是否要改 */
  description?: string;
}

interface PressableSettingItem extends BaseSettingItem {
  isSwitch?: false;
  onPress: () => void;
}

interface SwitchSettingItem extends BaseSettingItem {
  isSwitch: true;
  value: boolean;
  onValueChange: (value: boolean) => void;
}

type SettingItem = PressableSettingItem | SwitchSettingItem;

interface SettingGroup {
  title: string;
  items: SettingItem[];
}

// 糖果色图标背景映射
const ICON_CANDY_COLORS: Record<SettingsIconKey, string> = {
  accounts: '#A5D8FF',
  category: '#FFE8CC',
  wallet: '#FFD93D',
  flag: '#FF90B3',
  netWorth: '#96F2D7',
  review: '#FFC9DE',
  autoBookkeeping: '#99E9F2',
  permissions: '#B5E8DC',
  ai: '#D0BFFF',
  runtime: '#B2F2BB',
  data: '#FFD8A8',
  rules: '#D3F9D8',
  moon: '#C5A3FF',
};

// 柔光主题彩色图块背景（实色圆角块 + 白色图标）
const ICON_SOFT_COLORS: Record<SettingsIconKey, string> = {
  accounts: '#4F46E5',
  category: '#EC4899',
  wallet: '#F59E0B',
  flag: '#10B981',
  netWorth: '#0EA5E9',
  review: '#F97316',
  autoBookkeeping: '#8B5CF6',
  permissions: '#EF4444',
  ai: '#6366F1',
  runtime: '#14B8A6',
  data: '#64748B',
  rules: '#0891B2',
  moon: '#334155',
};

const SOFT_THEME_ORDER: ThemeName[] = ['soft', 'blocks', 'mint', 'sunset', 'ink'];

export default function SettingsScreen({ navigation }: SettingsScreenProps) {
  const {
    isDark,
    isSoft,
    toggleTheme,
    colors,
    themeName,
    setThemeName,
    isHogwarts,
    hogwartsHouse,
    setHogwartsHouse,
    hogwartsSfxEnabled,
    setHogwartsSfxEnabled,
    hogwartsDynamicIconEnabled,
    setHogwartsDynamicIconEnabled,
    playMagicSound,
  } = useTheme();
  const [auditionIndex, setAuditionIndex] = useState(0);
  const {
    showDashboardBudget,
    setShowDashboardBudget,
  } = useDisplayPreferences();
  const { showInfo } = useToast();
  const styles = useStyles(createStyles);

  // 共享 React Query 缓存：待确认角标 & 账户计数（仅柔光展示，不影响原缓存策略）
  const { data: recognitionStatus } = useQuery({
    queryKey: QUERY_KEYS.recognition.status,
    queryFn: () => nativeRecognition.getRecognitionStatus(),
    enabled: isSoft,
    staleTime: 30 * 1000,
    gcTime: GC_TIME.DEFAULT,
  });
  const { data: accountsList = [] } = useQuery({
    queryKey: QUERY_KEYS.accountsList,
    queryFn: () => nativeData.getAccounts(),
    enabled: isSoft,
    staleTime: CACHE_TIME.DASHBOARD,
    gcTime: GC_TIME.DEFAULT,
  });
  const pendingCount = recognitionStatus?.pendingCount ?? 0;
  const activeAccountCount = accountsList.filter(a => a.isActive).length;

  const activeHouseMeta = HOGWARTS_HOUSES[hogwartsHouse] ?? HOGWARTS_HOUSES.classic;

  const handleSelectTheme = (name: ThemeName) => {
    const prevWasHogwarts = isHogwarts;
    setThemeName(name);
    if (name === 'hogwarts') {
      playMagicSound('chime', { force: hogwartsSfxEnabled });
      if (hogwartsDynamicIconEnabled) {
        showInfo(`返回桌面后图标将切换为「${activeHouseMeta.label}」`);
      }
    } else if (prevWasHogwarts && hogwartsDynamicIconEnabled) {
      showInfo('返回桌面后图标将恢复为默认「招财猫猫」');
    }
  };

  const handleToggleDynamicIcon = (enabled: boolean) => {
    setHogwartsDynamicIconEnabled(enabled);
    if (enabled) {
      showInfo(`返回桌面后图标将自动切换为「${activeHouseMeta.label}」`);
    } else {
      showInfo('已关闭同步切换，返回桌面后恢复默认图标');
    }
  };

  const handleAuditionSound = () => {
    const order: MagicSoundKey[] = ['coin_drop', 'quill', 'chime', 'coin_clink'];
    const key = order[auditionIndex % order.length];
    setAuditionIndex(prev => prev + 1);
    playMagicSound(key, { force: true });
  };

  const iconMap: Record<
    SettingsIconKey,
    React.FC<{ size: number; color: string; strokeWidth?: number }>
  > = {
    accounts: CreditCard,
    category: Tags,
    wallet: isSoft ? PieChart : Wallet,
    flag: Flag,
    netWorth: isSoft ? TrendingUp : Wallet,
    review: isSoft ? Inbox : Bell,
    autoBookkeeping: Zap,
    permissions: isSoft ? Bell : ShieldCheck,
    ai: Bot,
    runtime: Activity,
    data: Database,
    rules: ListFilter,
    moon: Moon,
  };

  const settingGroups: SettingGroup[] = [
    {
      title: '💰 财务管理',
      items: [
        { id: 'accounts', label: '账户管理', icon: 'accounts', onPress: () => navigation?.navigate('Accounts') },
        { id: 'category-manage', label: '分类管理', icon: 'category', onPress: () => navigation?.navigate('Categories') },
        { id: 'budget-manage', label: '预算管理', icon: 'wallet', onPress: () => navigation?.navigate('Budgets') },
        { id: 'financial-goals', label: '财务目标', icon: 'flag', onPress: () => navigation?.navigate('FinancialGoals') },
        { id: 'net-worth', label: '净资产', icon: 'netWorth', onPress: () => navigation?.navigate('Reports') },
      ],
    },
    {
      title: '🧩 识别与自动化',
      items: [
        { id: 'review', label: '待确认中心', icon: 'review', onPress: () => navigation?.navigate('Review') },
        { id: 'auto-bookkeeping', label: '自动记账', icon: 'autoBookkeeping', onPress: () => navigation?.navigate('AutoBookkeeping') },
        { id: 'runtime-status', label: '运行状态', icon: 'runtime', onPress: () => navigation?.navigate('RuntimeStatus') },
      ],
    },
    {
      title: '🔔 权限与提醒',
      items: [
        {
          id: 'general-settings',
          label: '权限与提醒',
          icon: 'permissions',
          onPress: () => navigation?.navigate('GeneralSettings'),
        },
      ],
    },
    {
      title: '🤖 AI 与数据',
      items: [
        { id: 'ai-providers', label: 'AI 服务商', icon: 'ai', onPress: () => navigation?.navigate('AiProviders') },
        { id: 'data-management', label: '数据管理', icon: 'data', onPress: () => navigation?.navigate('DataManagement') },
        { id: 'rules', label: '规则', icon: 'rules', onPress: () => navigation?.navigate('Rules') },
      ],
    },
    {
      title: '🎨 显示',
      items: [
        {
          id: 'dark-mode',
          label: '深色模式',
          icon: 'moon',
          isSwitch: true,
          value: isDark,
          onValueChange: toggleTheme,
        },
        {
          id: 'dashboard-budget',
          label: '首页预算与目标',
          description: '显示预算进度和财务目标卡片',
          icon: 'wallet',
          isSwitch: true,
          value: showDashboardBudget,
          onValueChange: setShowDashboardBudget,
        },
      ],
    },
  ];

  const stripLeadingEmoji = (text: string) => text.replace(/^[^\u4e00-\u9fa5A-Za-z0-9]+/, '').trim();

  if (isSoft) {
    const softGroups = settingGroups.slice(0, 4);
    const displayGroup = settingGroups[4];
    const renderRightBadge = (id: string) => {
      if (id === 'review' && pendingCount > 0) {
        return (
          <View style={styles.softBadge}>
            <Text style={styles.softBadgeText}>{pendingCount}</Text>
          </View>
        );
      }
      if (id === 'accounts' && activeAccountCount > 0) {
        return <Text style={styles.softValueText}>{activeAccountCount} 个</Text>;
      }
      return null;
    };

    return (
      <View style={styles.container}>
        <View style={styles.softTopBar}>
          <Text style={styles.softTopTitle}>我的</Text>
        </View>
        <ScrollView
          style={styles.scrollView}
          contentContainerStyle={styles.softScrollContent}
          showsVerticalScrollIndicator={false}
        >
          {/* 本地账本头卡 */}
          <View style={styles.softProfileCard}>
            <View style={styles.softProfileRow}>
              <SoftGradient
                colors={[colors.gradientPrimaryStart, colors.primary, colors.gradientPrimaryEnd]}
                style={styles.softAvatar}
              >
                <Text style={styles.softAvatarText}>招</Text>
              </SoftGradient>
              <View style={styles.userDetails}>
                <Text style={styles.softUserName}>本地账本</Text>
                <View style={styles.softShieldRow}>
                  <ShieldCheck size={13} color={colors.income} strokeWidth={2.3} />
                  <Text style={styles.softUserSub}>数据仅保存在此设备 · 全库加密</Text>
                </View>
              </View>
            </View>
          </View>

          {softGroups.map((group, groupIndex) => (
            <View key={groupIndex} style={styles.softGroup}>
              <Text style={styles.softGroupTitle}>{stripLeadingEmoji(group.title)}</Text>
              <View style={styles.softGroupCard}>
                {group.items.map((item, idx) => {
                  const IconComponent = iconMap[item.icon];
                  const tileColor = ICON_SOFT_COLORS[item.icon] || colors.primary;
                  return (
                    <View key={item.id}>
                      {idx > 0 ? <View style={styles.softRowDivider} /> : null}
                      {item.isSwitch ? (
                        <View style={styles.softRow}>
                          <View style={[styles.softRowIcon, { backgroundColor: tileColor }]}>
                            <IconComponent size={17} color="#FFFFFF" strokeWidth={2.2} />
                          </View>
                          <View style={styles.settingTextBlock}>
                            <Text style={styles.softRowLabel}>{item.label}</Text>
                            {item.description ? (
                              <Text style={styles.settingDescription}>{item.description}</Text>
                            ) : null}
                          </View>
                          <Switch
                            value={item.value}
                            onValueChange={item.onValueChange}
                            trackColor={{ false: colors.surfaceMuted, true: colors.primary }}
                            thumbColor="#FFFFFF"
                            accessibilityLabel={item.label}
                          />
                        </View>
                      ) : (
                        <TouchableOpacity
                          style={styles.softRow}
                          onPress={item.onPress}
                          activeOpacity={0.72}
                        >
                          <View style={[styles.softRowIcon, { backgroundColor: tileColor }]}>
                            <IconComponent size={17} color="#FFFFFF" strokeWidth={2.2} />
                          </View>
                          <Text style={styles.softRowLabel}>{item.label}</Text>
                          {renderRightBadge(item.id)}
                          <ChevronRight size={18} color={colors.textQuaternary} strokeWidth={2} />
                        </TouchableOpacity>
                      )}
                    </View>
                  );
                })}
              </View>
            </View>
          ))}

          {/* 显示组：主题风格 + 深色模式 + 首页预算 */}
          <View style={styles.softGroup}>
            <Text style={styles.softGroupTitle}>显示</Text>
            <View style={styles.softGroupCard}>
              <View style={styles.softThemeBlock}>
                <View style={styles.softThemeHeader}>
                  <View style={[styles.softRowIcon, { backgroundColor: '#A855F7' }]}>
                    <Palette size={17} color="#FFFFFF" strokeWidth={2.2} />
                  </View>
                  <Text style={styles.softRowLabel}>主题风格</Text>
                </View>
                <View style={styles.softThemeGrid}>
                  {SOFT_THEME_ORDER.map(name => {
                    const preview = themes[name].light;
                    const active = name === themeName;
                    const isSoftOption = name === 'soft';
                    return (
                      <TouchableOpacity
                        key={name}
                        style={styles.softThemeOption}
                        onPress={() => handleSelectTheme(name)}
                        activeOpacity={0.78}
                        accessibilityLabel={`主题 ${THEME_LABELS[name]}${active ? '，当前使用中' : ''}`}
                        accessibilityRole="button"
                        accessibilityState={{ selected: active }}
                      >
                        <View
                          style={[
                            styles.softThemeRing,
                            active && styles.softThemeRingActive,
                          ]}
                        >
                          <View
                            style={[
                              styles.softThemeSwatch,
                              { backgroundColor: preview.background },
                              !isSoftOption && styles.softThemeSwatchBrutal,
                            ]}
                          >
                            <View
                              style={[
                                styles.softThemeBar,
                                { backgroundColor: preview.primary },
                              ]}
                            />
                          </View>
                        </View>
                        {isSoftOption ? (
                          <View style={styles.softNewTag}>
                            <Text style={styles.softNewTagText}>新</Text>
                          </View>
                        ) : null}
                        <Text
                          style={[
                            styles.softThemeLabel,
                            active && styles.softThemeLabelActive,
                          ]}
                          numberOfLines={1}
                        >
                          {THEME_LABELS[name]}
                        </Text>
                      </TouchableOpacity>
                    );
                  })}
                </View>
                <TouchableOpacity
                  style={styles.softHogwartsBanner}
                  onPress={() => handleSelectTheme('hogwarts')}
                  activeOpacity={0.82}
                  accessibilityLabel={`主题 ${THEME_LABELS.hogwarts}`}
                  accessibilityRole="button"
                  accessibilityState={{ selected: false }}
                >
                  <Image
                    source={HOGWARTS_IMAGES.themeCover}
                    style={styles.softHogwartsThumb}
                    resizeMode="cover"
                  />
                  <View style={styles.softHogwartsMeta}>
                    <Text style={styles.softHogwartsEn}>HOGWARTS EDITION</Text>
                    <Text style={styles.softHogwartsTitle}>{THEME_LABELS.hogwarts}</Text>
                    <Text style={styles.softHogwartsDesc} numberOfLines={1}>
                      羊皮纸双语字库 · 四学院纹章 · 魔法音效
                    </Text>
                  </View>
                  <ChevronRight size={18} color={colors.textQuaternary} strokeWidth={2} />
                </TouchableOpacity>
              </View>

              {displayGroup.items.map(item => {
                if (!item.isSwitch) return null;
                const isBudget = item.id === 'dashboard-budget';
                const IconComponent = isBudget ? Layers : Moon;
                const tileColor = isBudget ? '#F59E0B' : '#334155';
                return (
                  <View key={item.id}>
                    <View style={styles.softRowDivider} />
                    <View style={styles.softRow}>
                      <View style={[styles.softRowIcon, { backgroundColor: tileColor }]}>
                        <IconComponent size={17} color="#FFFFFF" strokeWidth={2.2} />
                      </View>
                      <View style={styles.settingTextBlock}>
                        <Text style={styles.softRowLabel}>{item.label}</Text>
                        {item.description ? (
                          <Text style={styles.settingDescription}>{item.description}</Text>
                        ) : null}
                      </View>
                      <Switch
                        value={item.value}
                        onValueChange={item.onValueChange}
                        trackColor={{ false: colors.surfaceMuted, true: colors.primary }}
                        thumbColor="#FFFFFF"
                        accessibilityLabel={item.label}
                      />
                    </View>
                  </View>
                );
              })}
            </View>
          </View>

          <View style={styles.versionInfo}>
            <Text style={styles.softVersionText}>招财猫猫 · © 2026</Text>
          </View>
        </ScrollView>
      </View>
    );
  }

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
      <View style={styles.header}>
        <View style={styles.userInfo}>
          <View style={styles.avatarContainer}>
            {isHogwarts ? (
              <Image
                source={activeHouseMeta.crest}
                style={styles.avatar}
                resizeMode="cover"
              />
            ) : (
              <View style={styles.avatarPlaceholder}>
                <Wallet size={32} color={colors.primary} />
              </View>
            )}
          </View>
          <View style={styles.userDetails}>
            <Text style={styles.userName}>
              {isHogwarts ? '古灵阁本地账卷' : '本地账本'}
            </Text>
            <Text style={styles.userType}>
              {isHogwarts
                ? `${activeHouseMeta.label} · 数据保存在此设备`
                : '数据保存在此设备'}
            </Text>
          </View>
        </View>
      </View>

      <ScrollView style={styles.scrollView} showsVerticalScrollIndicator={false}>
        {settingGroups.map((group, groupIndex) => (
          <View key={groupIndex} style={styles.settingGroup}>
            <Text style={styles.groupTitle}>{group.title}</Text>
            <View style={styles.groupItems}>
              {group.items.map((item) => {
                const IconComponent = iconMap[item.icon];
                const iconBg = isHogwarts
                  ? colors.primaryLight
                  : ICON_CANDY_COLORS[item.icon] || colors.accent;
                const iconColor = isHogwarts ? colors.primary : colors.stroke;
                return (
                <View key={item.id}>
                  {item.isSwitch ? (
                    <View style={styles.settingItem}>
                      <View style={styles.settingLeft}>
                        <View style={[styles.iconContainer, { backgroundColor: iconBg }]}>
                          {IconComponent && <IconComponent size={20} color={iconColor} />}
                        </View>
                        <View style={styles.settingTextBlock}>
                          <Text style={styles.settingLabel}>{item.label}</Text>
                          {item.description ? (
                            <Text style={styles.settingDescription}>{item.description}</Text>
                          ) : null}
                        </View>
                      </View>
                      <Switch
                        value={item.value}
                        onValueChange={item.onValueChange}
                        trackColor={{ false: colors.divider, true: colors.primary }}
                        thumbColor="#FFFFFF"
                        accessibilityLabel={item.label}
                      />
                    </View>
                  ) : (
                    <TouchableOpacity
                      style={styles.settingItem}
                      onPress={item.onPress}
                      activeOpacity={0.8}
                    >
                      <View style={styles.settingLeft}>
                        <View style={[styles.iconContainer, { backgroundColor: iconBg }]}>
                          {IconComponent && <IconComponent size={20} color={iconColor} />}
                        </View>
                        <View style={styles.settingTextBlock}>
                          <Text style={styles.settingLabel}>{item.label}</Text>
                          {item.description ? (
                            <Text style={styles.settingDescription}>{item.description}</Text>
                          ) : null}
                        </View>
                      </View>
                      <ChevronRight size={20} color={colors.textTertiary} strokeWidth={2.5} />
                    </TouchableOpacity>
                  )}
                </View>
              )})}
            </View>
          </View>
        ))}

        {/* 主题选择：色块预览 + 名称，切换即时生效并持久化 */}
        <View style={styles.settingGroup}>
          <Text style={styles.groupTitle}>🎨 主题风格</Text>
          <View style={styles.themeGrid}>
            {(Object.keys(themes) as ThemeName[]).map(name => {
              const preview = themes[name].light;
              const active = name === themeName;
              const isHogwartsCard = name === 'hogwarts';
              return (
                <TouchableOpacity
                  key={name}
                  style={[
                    styles.themeCard,
                    isHogwartsCard && styles.themeCardFeatured,
                    active && styles.themeCardActive,
                  ]}
                  onPress={() => handleSelectTheme(name)}
                  activeOpacity={0.8}
                  accessibilityLabel={`主题 ${THEME_LABELS[name]}${active ? '，当前使用中' : ''}`}
                  accessibilityRole="button"
                  accessibilityState={{ selected: active }}
                >
                  {isHogwartsCard ? (
                    <View style={styles.themeFeaturedRow}>
                      <Image
                        source={HOGWARTS_IMAGES.themeCover}
                        style={styles.themeCoverThumb}
                        resizeMode="cover"
                      />
                      <View style={styles.themeFeaturedMeta}>
                        <Text style={styles.themeFeaturedEn}>HOGWARTS EDITION</Text>
                        <Text style={styles.themeName}>
                          {THEME_LABELS[name]}{active ? ' ✓' : ''}
                        </Text>
                        <Text style={styles.themeFeaturedDesc}>
                          羊皮纸双语字库 · 四学院纹章 · 魔法音效
                        </Text>
                      </View>
                    </View>
                  ) : (
                    <>
                      <View style={styles.themeSwatches}>
                        <View style={[styles.themeSwatch, { backgroundColor: preview.primary }]} />
                        <View style={[styles.themeSwatch, { backgroundColor: preview.accent }]} />
                        <View
                          style={[
                            styles.themeSwatch,
                            { backgroundColor: preview.background },
                            styles.themeSwatchBordered,
                          ]}
                        />
                      </View>
                      <Text style={styles.themeName}>
                        {THEME_LABELS[name]}{active ? ' ✓' : ''}
                      </Text>
                    </>
                  )}
                </TouchableOpacity>
              );
            })}
          </View>
        </View>

        {/* 魔法羊皮纸专属：四学院子风格纹章切换 + 魔法交互音效 */}
        {isHogwarts ? (
          <View style={styles.settingGroup}>
            <Text style={styles.groupTitle}>🏰 学院纹章与魔法音效</Text>
            <View style={styles.houseGrid}>
              {HOGWARTS_HOUSE_ORDER.map(houseId => {
                const house = HOGWARTS_HOUSES[houseId];
                const active = houseId === hogwartsHouse;
                return (
                  <TouchableOpacity
                    key={houseId}
                    style={[styles.houseCard, active && styles.houseCardActive]}
                    onPress={() => {
                      setHogwartsHouse(houseId);
                      playMagicSound('chime');
                      if (hogwartsDynamicIconEnabled) {
                        showInfo(`已切换「${house.label}」，返回桌面后图标同步更新`);
                      }
                    }}
                    activeOpacity={0.8}
                    accessibilityLabel={`学院风格 ${house.label}${active ? '，当前使用中' : ''}`}
                    accessibilityRole="button"
                    accessibilityState={{ selected: active }}
                  >
                    <Image
                      source={house.crest}
                      style={styles.houseCrest}
                      resizeMode="cover"
                    />
                    <View style={styles.houseTextBlock}>
                      <Text style={styles.houseLabel} numberOfLines={1}>
                        {house.label}{active ? ' ✓' : ''}
                      </Text>
                      <Text style={styles.houseMotto} numberOfLines={1}>
                        {house.motto}
                      </Text>
                    </View>
                  </TouchableOpacity>
                );
              })}
            </View>

            <View style={[styles.settingItem, styles.sfxCard]}>
              <View style={styles.settingLeft}>
                <View style={[styles.iconContainer, styles.sfxIconContainer]}>
                  <Volume2 size={20} color={colors.primary} />
                </View>
                <View style={styles.settingTextBlock}>
                  <Text style={styles.settingLabel}>魔法交互音效</Text>
                  <Text style={styles.settingDescription}>
                    记账成功金币落袋、待确认钟铃与魔杖轻响
                  </Text>
                </View>
              </View>
              <View style={styles.sfxControls}>
                <TouchableOpacity
                  style={styles.auditionButton}
                  onPress={handleAuditionSound}
                  activeOpacity={0.8}
                  accessibilityLabel="试听魔法音效"
                  accessibilityRole="button"
                >
                  <Text style={styles.auditionButtonText}>试听</Text>
                </TouchableOpacity>
                <Switch
                  value={hogwartsSfxEnabled}
                  onValueChange={setHogwartsSfxEnabled}
                  trackColor={{ false: colors.divider, true: colors.primary }}
                  thumbColor="#FFFFFF"
                  accessibilityLabel="魔法交互音效"
                />
              </View>
            </View>

            <View style={[styles.settingItem, styles.sfxCard]}>
              <View style={styles.settingLeft}>
                <Image
                  source={activeHouseMeta.crest}
                  style={styles.desktopIconPreview}
                  resizeMode="cover"
                />
                <View style={styles.settingTextBlock}>
                  <Text style={styles.settingLabel}>同步切换桌面图标</Text>
                  <Text style={styles.settingDescription}>
                    随学院切换招财巫师猫 / 院徽图标，返回桌面 1–3 秒生效
                  </Text>
                </View>
              </View>
              <Switch
                value={hogwartsDynamicIconEnabled}
                onValueChange={handleToggleDynamicIcon}
                trackColor={{ false: colors.divider, true: colors.primary }}
                thumbColor="#FFFFFF"
                accessibilityLabel="同步切换桌面图标"
              />
            </View>
          </View>
        ) : null}

        <View style={styles.versionInfo}>
          <Text style={styles.versionText}>招财猫猫</Text>
          <Text style={styles.copyrightText}>© 2026 招财猫猫</Text>
        </View>
      </ScrollView>
    </View>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: colors.background,
    },
    softTopBar: {
      paddingHorizontal: 20,
      paddingTop: spacing.md,
      paddingBottom: spacing.sm,
    },
    softTopTitle: {
      fontSize: 22,
      fontWeight: '700',
      color: colors.textPrimary,
      letterSpacing: -0.3,
    },
    softScrollContent: {
      paddingBottom: 116,
    },
    softProfileCard: {
      marginHorizontal: spacing.lg,
      marginTop: spacing.xs,
      padding: 18,
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.borderWidth.card,
      borderColor: colors.cardBorder,
      ...shape.elevation.card,
    },
    softProfileRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 14,
    },
    softAvatar: {
      width: 56,
      height: 56,
      borderRadius: 20,
      alignItems: 'center',
      justifyContent: 'center',
    },
    softAvatarText: {
      fontSize: 22,
      fontWeight: '700',
      color: '#FFFFFF',
    },
    softUserName: {
      fontSize: 18,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    softShieldRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 4,
      marginTop: 4,
    },
    softUserSub: {
      fontSize: 12,
      color: colors.textTertiary,
    },
    softGroup: {
      marginTop: 18,
      paddingHorizontal: spacing.lg,
    },
    softGroupTitle: {
      fontSize: 13,
      fontWeight: '600',
      color: colors.textTertiary,
      paddingHorizontal: 6,
      marginBottom: 8,
    },
    softGroupCard: {
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.borderWidth.card,
      borderColor: colors.cardBorder,
      overflow: 'hidden',
      ...shape.elevation.card,
    },
    softRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 12,
      paddingHorizontal: 14,
      paddingVertical: 12,
      minHeight: 54,
    },
    softRowDivider: {
      height: StyleSheet.hairlineWidth,
      backgroundColor: colors.divider,
      marginLeft: 58,
      marginRight: 14,
    },
    softRowIcon: {
      width: 32,
      height: 32,
      borderRadius: 11,
      alignItems: 'center',
      justifyContent: 'center',
    },
    softRowLabel: {
      flex: 1,
      fontSize: 15,
      fontWeight: '500',
      color: colors.textPrimary,
    },
    softValueText: {
      fontSize: 13,
      color: colors.textTertiary,
    },
    softBadge: {
      minWidth: 20,
      height: 20,
      borderRadius: 10,
      backgroundColor: colors.expense,
      paddingHorizontal: 6,
      alignItems: 'center',
      justifyContent: 'center',
    },
    softBadgeText: {
      fontSize: 11,
      fontWeight: '700',
      color: '#FFFFFF',
      fontVariant: ['tabular-nums'],
    },
    softThemeBlock: {
      paddingHorizontal: 14,
      paddingTop: 12,
      paddingBottom: 14,
    },
    softThemeHeader: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 12,
      marginBottom: 12,
    },
    softThemeGrid: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      gap: 6,
    },
    softThemeOption: {
      flex: 1,
      alignItems: 'center',
      gap: 6,
      position: 'relative',
    },
    softThemeRing: {
      padding: 2,
      borderRadius: 18,
      borderWidth: 2,
      borderColor: 'transparent',
    },
    softThemeRingActive: {
      borderColor: colors.primary,
    },
    softThemeSwatch: {
      width: 46,
      height: 46,
      borderRadius: 14,
      justifyContent: 'flex-end',
      padding: 6,
      borderWidth: StyleSheet.hairlineWidth,
      borderColor: 'rgba(0,0,0,0.08)',
    },
    softThemeSwatchBrutal: {
      borderWidth: 2,
      borderColor: '#1A1A1A',
    },
    softThemeBar: {
      height: 13,
      borderRadius: 5,
    },
    softNewTag: {
      position: 'absolute',
      top: -4,
      right: 2,
      backgroundColor: colors.accent,
      paddingHorizontal: 5,
      paddingVertical: 1,
      borderRadius: 6,
    },
    softNewTagText: {
      fontSize: 9,
      fontWeight: '700',
      color: '#3B2500',
    },
    softThemeLabel: {
      fontSize: 11,
      color: colors.textTertiary,
    },
    softThemeLabelActive: {
      color: colors.textPrimary,
      fontWeight: '600',
    },
    softVersionText: {
      fontSize: 12,
      color: colors.textQuaternary,
    },
    softHogwartsBanner: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 12,
      marginTop: 12,
      padding: 10,
      borderRadius: 16,
      backgroundColor: colors.surfaceMuted,
      borderWidth: 1,
      borderColor: 'rgba(201, 162, 39, 0.35)',
    },
    softHogwartsThumb: {
      width: 42,
      height: 54,
      borderRadius: 8,
      borderWidth: 1.5,
      borderColor: '#C9A227',
    },
    softHogwartsMeta: {
      flex: 1,
      gap: 2,
    },
    softHogwartsEn: {
      fontSize: 9.5,
      fontFamily: HOGWARTS_FONTS.ornate,
      color: colors.primary,
      letterSpacing: 1.1,
    },
    softHogwartsTitle: {
      fontSize: 14,
      fontWeight: '600',
      color: colors.textPrimary,
    },
    softHogwartsDesc: {
      fontSize: 11.5,
      color: colors.textTertiary,
    },
    parchmentBackdrop: {
      ...StyleSheet.absoluteFillObject,
      opacity: 0.07,
    },
    header: {
      backgroundColor: colors.surface,
      marginHorizontal: spacing.lg,
      marginTop: spacing.lg,
      borderRadius: shape.radius.card,
      borderWidth: shape.borderWidth.card,
      borderColor: colors.cardBorder,
      padding: spacing.lg,
      ...shape.elevation.card,
    },
    userInfo: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.md,
    },
    avatarContainer: {
      width: 64,
      height: 64,
      borderRadius: shape.isHogwarts ? 32 : borderRadius.medium,
      borderWidth: shape.isHogwarts ? 2 : borderWidth.medium,
      borderColor: shape.isHogwarts ? colors.accent : colors.stroke,
      backgroundColor: colors.accent,
      overflow: 'hidden',
    },
    avatar: {
      width: '100%',
      height: '100%',
    },
    avatarPlaceholder: {
      width: '100%',
      height: '100%',
      alignItems: 'center',
      justifyContent: 'center',
    },
    userDetails: {
      flex: 1,
    },
    userName: {
      fontSize: 18,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
      marginBottom: 2,
    },
    userType: {
      fontSize: 13,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    scrollView: {
      flex: 1,
    },
    settingGroup: {
      marginTop: spacing.lg,
      paddingHorizontal: spacing.lg,
    },
    groupTitle: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textSecondary,
      marginBottom: spacing.sm,
      paddingHorizontal: spacing.xs,
    },
    groupItems: {
      gap: spacing.sm,
    },
    themeGrid: {
      flexDirection: 'row',
      flexWrap: 'wrap',
      gap: spacing.sm,
    },
    themeCard: {
      flexBasis: '48%',
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.borderWidth.card,
      borderColor: colors.cardBorder,
      padding: spacing.md,
      minHeight: 44,
    },
    themeCardFeatured: {
      flexBasis: '100%',
    },
    themeFeaturedRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.md,
    },
    themeCoverThumb: {
      width: 52,
      height: 68,
      borderRadius: 10,
      borderWidth: 1.5,
      borderColor: '#C9A227',
    },
    themeFeaturedMeta: {
      flex: 1,
      gap: 2,
    },
    themeFeaturedEn: {
      fontSize: 10,
      fontFamily: HOGWARTS_FONTS.ornate,
      color: colors.primary,
      letterSpacing: 1.2,
    },
    themeFeaturedDesc: {
      fontSize: 12,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    themeCardActive: {
      borderWidth: shape.isHogwarts ? 2 : borderWidth.medium,
      borderColor: colors.primary,
      ...shape.elevation.card,
    },
    themeSwatches: {
      flexDirection: 'row',
      gap: spacing.xs,
      marginBottom: spacing.sm,
    },
    themeSwatch: {
      width: 24,
      height: 24,
      borderRadius: borderRadius.small,
    },
    themeSwatchBordered: {
      borderWidth: borderWidth.hairline,
      borderColor: colors.divider,
    },
    themeName: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    houseGrid: {
      gap: spacing.sm,
      marginBottom: spacing.sm,
    },
    houseCard: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.md,
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: 1,
      borderColor: colors.cardBorder,
      padding: spacing.sm + 2,
    },
    houseCardActive: {
      borderWidth: 2,
      borderColor: colors.primary,
      backgroundColor: colors.primaryLight,
      ...shape.elevation.card,
    },
    houseCrest: {
      width: 44,
      height: 44,
      borderRadius: 22,
      borderWidth: 1.5,
      borderColor: colors.accent,
    },
    houseTextBlock: {
      flex: 1,
    },
    houseLabel: {
      fontSize: 15,
      fontFamily: HOGWARTS_FONTS.display,
      color: colors.textPrimary,
    },
    houseMotto: {
      fontSize: 12,
      fontFamily: HOGWARTS_FONTS.body,
      color: colors.textSecondary,
      marginTop: 2,
    },
    sfxCard: {
      marginTop: spacing.xs,
    },
    sfxIconContainer: {
      backgroundColor: colors.primaryLight,
      borderColor: colors.accent,
    },
    desktopIconPreview: {
      width: 40,
      height: 40,
      borderRadius: 12,
      borderWidth: 1.5,
      borderColor: colors.accent,
    },
    sfxControls: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
    },
    auditionButton: {
      paddingHorizontal: spacing.sm + 2,
      paddingVertical: spacing.xs + 1,
      borderRadius: shape.radius.pill,
      backgroundColor: colors.primaryLight,
      borderWidth: 1,
      borderColor: colors.primary,
    },
    auditionButtonText: {
      fontSize: 12,
      fontFamily: HOGWARTS_FONTS.body,
      color: colors.primaryDark,
    },
    settingItem: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingVertical: spacing.md,
      paddingHorizontal: spacing.md,
      minHeight: 56,
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.borderWidth.card,
      borderColor: colors.cardBorder,
    },
    settingLeft: {
      flexDirection: 'row',
      alignItems: 'center',
      flex: 1,
      gap: spacing.md,
    },
    iconContainer: {
      width: 40,
      height: 40,
      borderRadius: shape.isHogwarts ? 12 : borderRadius.small,
      borderWidth: shape.borderWidth.card,
      borderColor: colors.cardBorder,
      alignItems: 'center',
      justifyContent: 'center',
    },
    settingLabel: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    settingTextBlock: {
      flex: 1,
      justifyContent: 'center',
      gap: 2,
    },
    settingDescription: {
      fontSize: 12,
      fontWeight: '500',
      color: colors.textTertiary,
      lineHeight: 16,
    },
    versionInfo: {
      alignItems: 'center',
      paddingTop: spacing.lg,
      paddingBottom: spacing.xl,
    },
    versionText: {
      fontSize: 12,
      fontWeight: '600',
      fontFamily: typography.fontFamily.mono,
      color: colors.textTertiary,
    },
    copyrightText: {
      fontSize: 12,
      fontWeight: '600',
      fontFamily: typography.fontFamily.mono,
      color: colors.textTertiary,
      marginTop: 4,
    },
  }),
});

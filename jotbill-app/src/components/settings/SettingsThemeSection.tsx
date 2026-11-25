/**
 * 设置页「主题风格」折叠与三大主题选择区。
 *
 * 交互结构：
 * - 默认收起为单行入口（右侧展示当前主题色点 + 摘要名 + 展开箭头）；
 * - 点击展开 3 张主题主卡，与底层 ThemeStyleVariant 1:1 对齐：
 *   1. 魔法羊皮纸（首位）：点击切换并展开/收起现有样式的 5 枚院徽+格言卡片、魔法音效试听与同步桌面图标开关；
 *   2. 柔光：独立现代柔和风，一点即切；
 *   3. 经典积木（聚合 4 色）：卡片展示 4 色预览点，点击切换并展开/收起 2×2 四色网格（积木蓝/薄荷账本/落日橙/墨黑）。
 */
import React, { useEffect, useState } from 'react';
import {
  Image,
  StyleSheet,
  Switch,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import {
  Check,
  ChevronDown,
  ChevronUp,
  Palette,
  Volume2,
} from 'lucide-react-native';
import {
  THEME_LABELS,
  themes,
  type ThemeColors,
  type ThemeName,
} from '../../theme/colors';
import { borderRadius, spacing, type ThemeShape } from '../../theme';
import { SoftGradient } from '../ui';
import { useStyles } from '../../hooks';
import { useTheme } from '../../providers';
import {
  HOGWARTS_FONTS,
  HOGWARTS_HOUSES,
  HOGWARTS_HOUSE_ORDER,
  HOGWARTS_IMAGES,
} from '../../constants/hogwartsAssets';
import type { MagicSoundKey } from '../../native/theme';

const BRUTAL_THEMES = ['blocks', 'mint', 'sunset', 'ink'] as const;
type BrutalThemeName = (typeof BRUTAL_THEMES)[number];

const BRUTAL_DESCRIPTIONS: Record<BrutalThemeName, string> = {
  blocks: '暖白纸底 · 经典亮蓝',
  mint: '清透薄荷 · 沉稳深绿',
  sunset: '暖橘纸感 · 活力落日',
  ink: '极简留白 · 高对比黑',
};

function isBrutalTheme(name: ThemeName): name is BrutalThemeName {
  return (BRUTAL_THEMES as readonly string[]).includes(name);
}

export default function SettingsThemeSection() {
  const {
    colors,
    isSoft,
    isHogwarts,
    themeName,
    setThemeName,
    hogwartsHouse,
    setHogwartsHouse,
    hogwartsSfxEnabled,
    setHogwartsSfxEnabled,
    hogwartsDynamicIconEnabled,
    setHogwartsDynamicIconEnabled,
    playMagicSound,
  } = useTheme();
  const styles = useStyles(createStyles);

  const [panelOpen, setPanelOpen] = useState(false);
  const [hogwartsOpen, setHogwartsOpen] = useState(false);
  const [brutalOpen, setBrutalOpen] = useState(false);
  const [lastBrutalColor, setLastBrutalColor] = useState<BrutalThemeName>(
    isBrutalTheme(themeName) ? themeName : 'blocks',
  );
  const [auditionIndex, setAuditionIndex] = useState(0);

  useEffect(() => {
    if (isBrutalTheme(themeName)) {
      setLastBrutalColor(themeName);
    }
  }, [themeName]);

  const activeHouseMeta = HOGWARTS_HOUSES[hogwartsHouse] ?? HOGWARTS_HOUSES.classic;
  const isBrutalActive = isBrutalTheme(themeName);
  const activeBrutalKey: BrutalThemeName = isBrutalActive ? themeName : lastBrutalColor;
  const activeBrutalPreview = themes[activeBrutalKey].light;

  const currentSummary = isHogwarts
    ? `魔法羊皮纸 · ${activeHouseMeta.shortLabel}`
    : isSoft
      ? THEME_LABELS.soft
      : `经典积木 · ${THEME_LABELS[activeBrutalKey]}`;

  const handleTogglePanel = () => {
    setPanelOpen(prev => !prev);
  };

  const handlePressHogwarts = () => {
    setBrutalOpen(false);
    if (!isHogwarts) {
      setThemeName('hogwarts');
      setHogwartsOpen(true);
      playMagicSound('chime', { force: hogwartsSfxEnabled });
      return;
    }
    setHogwartsOpen(prev => !prev);
  };

  const handlePressSoft = () => {
    setHogwartsOpen(false);
    setBrutalOpen(false);
    setThemeName('soft');
  };

  const handlePressBrutal = () => {
    setHogwartsOpen(false);
    if (!isBrutalActive) {
      setThemeName(activeBrutalKey);
      setBrutalOpen(true);
      return;
    }
    setBrutalOpen(prev => !prev);
  };

  const handleSelectBrutalColor = (name: BrutalThemeName) => {
    setLastBrutalColor(name);
    setHogwartsOpen(false);
    setThemeName(name);
  };

  const handleAuditionSound = () => {
    const order: MagicSoundKey[] = ['coin_drop', 'quill', 'chime', 'coin_clink'];
    const key = order[auditionIndex % order.length];
    setAuditionIndex(prev => prev + 1);
    playMagicSound(key, { force: true });
  };

  const iconColor = isSoft ? '#FFFFFF' : isHogwarts ? colors.primary : colors.stroke;
  const showHogwartsDropdown = isHogwarts && hogwartsOpen;
  const showBrutalDropdown = isBrutalActive && brutalOpen;

  return (
    <View>
      {/* 默认折叠的「主题风格」入口行 */}
      <TouchableOpacity
        style={styles.triggerRow}
        onPress={handleTogglePanel}
        activeOpacity={0.75}
        accessibilityRole="button"
        accessibilityLabel={`主题风格，当前 ${currentSummary}`}
        accessibilityState={{ expanded: panelOpen }}
      >
        <View style={styles.triggerIconBox}>
          <Palette size={isSoft ? 17 : 18} color={iconColor} strokeWidth={2.2} />
        </View>
        <View style={styles.triggerTextBlock}>
          <Text style={styles.triggerTitle}>主题风格</Text>
          <Text style={styles.triggerSub} numberOfLines={1}>
            魔法羊皮纸 · 柔光 · 经典积木
          </Text>
        </View>
        <View style={styles.triggerRight}>
          <View style={[styles.summaryDot, { backgroundColor: colors.primary }]} />
          <Text style={styles.summaryText} numberOfLines={1}>
            {currentSummary}
          </Text>
          {panelOpen ? (
            <ChevronUp size={17} color={colors.textTertiary} strokeWidth={2.2} />
          ) : (
            <ChevronDown size={17} color={colors.textTertiary} strokeWidth={2.2} />
          )}
        </View>
      </TouchableOpacity>

      {/* 点击展开：3 张主题主卡 */}
      {panelOpen ? (
        <View style={styles.panelBody}>
          {/* 1. 魔法羊皮纸（首位 + 点击展开现有样式的四学院下拉） */}
          <View>
            <TouchableOpacity
              style={[
                styles.themeCard,
                styles.hogwartsCardBorder,
                isHogwarts && styles.themeCardActive,
              ]}
              onPress={handlePressHogwarts}
              activeOpacity={0.8}
              accessibilityRole="button"
              accessibilityLabel={`主题 ${THEME_LABELS.hogwarts}${isHogwarts ? '，当前使用中' : ''}`}
              accessibilityState={{ selected: isHogwarts, expanded: showHogwartsDropdown }}
            >
              <Image
                source={HOGWARTS_IMAGES.themeCover}
                style={styles.hogwartsThumb}
                resizeMode="cover"
              />
              <View style={styles.themeCardMeta}>
                <View style={styles.themeCardTitleRow}>
                  <Text style={styles.hogwartsEn}>HOGWARTS EDITION</Text>
                  {isHogwarts ? (
                    <View style={styles.activePill}>
                      <Text style={styles.activePillText}>使用中</Text>
                    </View>
                  ) : null}
                </View>
                <Text style={styles.hogwartsTitle}>{THEME_LABELS.hogwarts}</Text>
                <Text style={styles.themeCardDesc} numberOfLines={1}>
                  {isHogwarts
                    ? `${activeHouseMeta.label} · 点击${showHogwartsDropdown ? '收起' : '展开'}学院与音效`
                    : '羊皮纸双语字库 · 四学院纹章 · 魔法音效'}
                </Text>
              </View>
              {showHogwartsDropdown ? (
                <ChevronUp size={18} color={isHogwarts ? colors.primary : colors.textTertiary} strokeWidth={2.2} />
              ) : (
                <ChevronDown size={18} color={isHogwarts ? colors.primary : colors.textTertiary} strokeWidth={2.2} />
              )}
            </TouchableOpacity>

            {/* 魔法羊皮纸下拉列表：完整沿用现状院徽 + 格言 + 音效试听 + 桌面图标 */}
            {showHogwartsDropdown ? (
              <View style={styles.dropdownBox}>
                <View style={styles.dropdownHeader}>
                  <Text style={styles.dropdownTitle}>🏰 学院纹章与魔法音效</Text>
                  <Text style={styles.dropdownHint}>点上方卡片可收起</Text>
                </View>

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
                        }}
                        activeOpacity={0.8}
                        accessibilityRole="button"
                        accessibilityLabel={`学院风格 ${house.label}${active ? '，当前使用中' : ''}`}
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

                {/* 魔法交互音效 */}
                <View style={styles.hogwartsSwitchCard}>
                  <View style={styles.hogwartsSwitchLeft}>
                    <View style={styles.sfxIconContainer}>
                      <Volume2 size={18} color={colors.primary} />
                    </View>
                    <View style={styles.hogwartsSwitchText}>
                      <Text style={styles.hogwartsSwitchTitle}>魔法交互音效</Text>
                      <Text style={styles.hogwartsSwitchDesc}>
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

                {/* 同步切换桌面图标 */}
                <View style={styles.hogwartsSwitchCard}>
                  <View style={styles.hogwartsSwitchLeft}>
                    <Image
                      source={activeHouseMeta.crest}
                      style={styles.desktopIconPreview}
                      resizeMode="cover"
                    />
                    <View style={styles.hogwartsSwitchText}>
                      <Text style={styles.hogwartsSwitchTitle}>同步切换桌面图标</Text>
                      <Text style={styles.hogwartsSwitchDesc}>
                        随学院切换招财巫师猫 / 院徽图标，返回桌面 1–3 秒生效
                      </Text>
                    </View>
                  </View>
                  <Switch
                    value={hogwartsDynamicIconEnabled}
                    onValueChange={setHogwartsDynamicIconEnabled}
                    trackColor={{ false: colors.divider, true: colors.primary }}
                    thumbColor="#FFFFFF"
                    accessibilityLabel="同步切换桌面图标"
                  />
                </View>
              </View>
            ) : null}
          </View>

          {/* 2. 柔光（第二位，独立单卡一点即切） */}
          <TouchableOpacity
            style={[styles.themeCard, isSoft && styles.themeCardActive]}
            onPress={handlePressSoft}
            activeOpacity={0.8}
            accessibilityRole="button"
            accessibilityLabel={`主题 ${THEME_LABELS.soft}${isSoft ? '，当前使用中' : ''}`}
            accessibilityState={{ selected: isSoft }}
          >
            <View style={styles.softSwatchBox}>
              <SoftGradient
                colors={[
                  themes.soft.light.gradientPrimaryStart,
                  themes.soft.light.primary,
                  themes.soft.light.secondary,
                ]}
                style={styles.softSwatchBar}
              />
            </View>
            <View style={styles.themeCardMeta}>
              <View style={styles.themeCardTitleRow}>
                <Text style={styles.themeCardTitle}>{THEME_LABELS.soft}</Text>
                {isSoft ? (
                  <View style={styles.activePill}>
                    <Text style={styles.activePillText}>使用中</Text>
                  </View>
                ) : null}
              </View>
              <Text style={styles.themeCardDesc} numberOfLines={1}>
                大圆角卡片 · 柔和轻投影 · 靛蓝渐变
              </Text>
            </View>
            {isSoft ? (
              <Check size={18} color={colors.primary} strokeWidth={2.6} />
            ) : null}
          </TouchableOpacity>

          {/* 3. 经典积木（第三位，聚合 4 色，点击展开 2×2 四色网格） */}
          <View>
            <TouchableOpacity
              style={[styles.themeCard, isBrutalActive && styles.themeCardActive]}
              onPress={handlePressBrutal}
              activeOpacity={0.8}
              accessibilityRole="button"
              accessibilityLabel={`主题 经典积木，当前 ${THEME_LABELS[activeBrutalKey]}${isBrutalActive ? '，使用中' : ''}`}
              accessibilityState={{ selected: isBrutalActive, expanded: showBrutalDropdown }}
            >
              <View
                style={[
                  styles.brutalSwatchBox,
                  { backgroundColor: activeBrutalPreview.background },
                ]}
              >
                <View
                  style={[
                    styles.brutalSwatchBar,
                    { backgroundColor: activeBrutalPreview.primary },
                  ]}
                />
              </View>
              <View style={styles.themeCardMeta}>
                <View style={styles.themeCardTitleRow}>
                  <Text style={styles.themeCardTitle}>经典积木</Text>
                  {isBrutalActive ? (
                    <View style={styles.activePill}>
                      <Text style={styles.activePillText}>
                        {THEME_LABELS[activeBrutalKey]}
                      </Text>
                    </View>
                  ) : null}
                </View>
                <Text style={styles.themeCardDesc} numberOfLines={1}>
                  {isBrutalActive
                    ? `点击${showBrutalDropdown ? '收起' : '切换'} 4 种积木配色`
                    : '4 色可选：积木蓝 · 薄荷 · 落日橙 · 墨黑'}
                </Text>
              </View>
              <View style={styles.brutalCardRight}>
                <View style={styles.brutalDotsRow}>
                  {BRUTAL_THEMES.map(name => {
                    const selected = isBrutalActive && themeName === name;
                    return (
                      <View
                        key={name}
                        style={[
                          styles.brutalDot,
                          selected && styles.brutalDotSelected,
                          { backgroundColor: themes[name].light.primary },
                        ]}
                      />
                    );
                  })}
                </View>
                {showBrutalDropdown ? (
                  <ChevronUp size={18} color={isBrutalActive ? colors.primary : colors.textTertiary} strokeWidth={2.2} />
                ) : (
                  <ChevronDown size={18} color={isBrutalActive ? colors.primary : colors.textTertiary} strokeWidth={2.2} />
                )}
              </View>
            </TouchableOpacity>

            {/* 经典积木展开下拉：2×2 四色选择卡片 */}
            {showBrutalDropdown ? (
              <View style={styles.dropdownBox}>
                <View style={styles.dropdownHeader}>
                  <Text style={styles.dropdownTitle}>选择积木配色（4 色）</Text>
                  <Text style={styles.dropdownHint}>点上方卡片可收起</Text>
                </View>
                <View style={styles.brutalColorGrid}>
                  {BRUTAL_THEMES.map(name => {
                    const preview = themes[name].light;
                    const active = themeName === name;
                    return (
                      <TouchableOpacity
                        key={name}
                        style={[
                          styles.brutalColorCard,
                          active && styles.brutalColorCardActive,
                        ]}
                        onPress={() => handleSelectBrutalColor(name)}
                        activeOpacity={0.8}
                        accessibilityRole="button"
                        accessibilityLabel={`主题 ${THEME_LABELS[name]}${active ? '，当前使用中' : ''}`}
                        accessibilityState={{ selected: active }}
                      >
                        <View style={styles.brutalColorTopRow}>
                          <View style={styles.brutalColorSwatches}>
                            <View
                              style={[
                                styles.brutalMiniSwatch,
                                { backgroundColor: preview.primary },
                              ]}
                            />
                            <View
                              style={[
                                styles.brutalMiniSwatch,
                                { backgroundColor: preview.accent },
                              ]}
                            />
                            <View
                              style={[
                                styles.brutalMiniSwatch,
                                styles.brutalMiniSwatchBordered,
                                { backgroundColor: preview.background },
                              ]}
                            />
                          </View>
                          {active ? (
                            <Check size={14} color={colors.primary} strokeWidth={2.8} />
                          ) : null}
                        </View>
                        <Text style={styles.brutalColorLabel} numberOfLines={1}>
                          {THEME_LABELS[name]}
                        </Text>
                        <Text style={styles.brutalColorDesc} numberOfLines={1}>
                          {BRUTAL_DESCRIPTIONS[name]}
                        </Text>
                      </TouchableOpacity>
                    );
                  })}
                </View>
              </View>
            ) : null}
          </View>
        </View>
      ) : null}
    </View>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    triggerRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 12,
      paddingHorizontal: 14,
      paddingVertical: 12,
      minHeight: 54,
    },
    triggerIconBox: {
      width: shape.isSoft ? 32 : 34,
      height: shape.isSoft ? 32 : 34,
      borderRadius: shape.isSoft ? 11 : shape.isHogwarts ? 11 : borderRadius.small,
      backgroundColor: shape.isSoft
        ? '#A855F7'
        : shape.isHogwarts
          ? colors.primaryLight
          : '#E5DBFF',
      borderWidth: shape.isSoft ? 0 : shape.isHogwarts ? 1 : 1.5,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      alignItems: 'center',
      justifyContent: 'center',
      flexShrink: 0,
    },
    triggerTextBlock: {
      flex: 1,
      minWidth: 0,
      gap: 2,
    },
    triggerTitle: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '500' : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    triggerSub: {
      fontSize: 11.5,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
    triggerRight: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 6,
      flexShrink: 0,
      maxWidth: '46%',
    },
    summaryDot: {
      width: 9,
      height: 9,
      borderRadius: 5,
      flexShrink: 0,
    },
    summaryText: {
      fontSize: 12.5,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.primary,
      flexShrink: 1,
    },
    panelBody: {
      paddingHorizontal: 14,
      paddingTop: 2,
      paddingBottom: 14,
      gap: 10,
    },
    themeCard: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 11,
      padding: 11,
      borderRadius: shape.isSoft ? 16 : shape.radius.card,
      backgroundColor: colors.surfaceMuted,
      borderWidth: 1,
      borderColor: colors.cardBorder,
    },
    hogwartsCardBorder: {
      borderColor: 'rgba(201, 162, 39, 0.48)',
    },
    themeCardActive: {
      backgroundColor: colors.primaryLight,
      borderWidth: 2,
      borderColor: colors.primary,
    },
    themeCardMeta: {
      flex: 1,
      minWidth: 0,
      gap: 2,
    },
    themeCardTitleRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 6,
    },
    themeCardTitle: {
      fontSize: 14.5,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    themeCardDesc: {
      fontSize: 11.5,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
    activePill: {
      paddingHorizontal: 6,
      paddingVertical: 1.5,
      borderRadius: 999,
      backgroundColor: colors.primary,
    },
    activePillText: {
      fontSize: 10,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.onPrimary,
    },
    hogwartsThumb: {
      width: 42,
      height: 50,
      borderRadius: 9,
      borderWidth: 1.5,
      borderColor: '#C9A227',
      flexShrink: 0,
    },
    hogwartsEn: {
      fontSize: 9.5,
      fontFamily: HOGWARTS_FONTS.ornate,
      color: colors.primary,
      letterSpacing: 1.1,
    },
    hogwartsTitle: {
      fontSize: 14.5,
      fontFamily: HOGWARTS_FONTS.display,
      color: colors.textPrimary,
    },
    softSwatchBox: {
      width: 42,
      height: 42,
      borderRadius: 13,
      backgroundColor: '#F4F5F9',
      borderWidth: 1,
      borderColor: 'rgba(79, 70, 229, 0.22)',
      padding: 5,
      justifyContent: 'flex-end',
      flexShrink: 0,
    },
    softSwatchBar: {
      height: 14,
      borderRadius: 5,
    },
    brutalSwatchBox: {
      width: 42,
      height: 42,
      borderRadius: 10,
      borderWidth: 2,
      borderColor: '#1A1A1A',
      padding: 5,
      justifyContent: 'flex-end',
      flexShrink: 0,
    },
    brutalSwatchBar: {
      height: 13,
      borderRadius: 4,
    },
    brutalCardRight: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 6,
      flexShrink: 0,
    },
    brutalDotsRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 4,
    },
    brutalDot: {
      width: 9,
      height: 9,
      borderRadius: 5,
      borderWidth: 1,
      borderColor: 'rgba(0, 0, 0, 0.16)',
    },
    brutalDotSelected: {
      width: 12,
      height: 12,
      borderRadius: 6,
      borderWidth: 2,
      borderColor: colors.surface,
    },
    dropdownBox: {
      marginTop: 8,
      padding: 10,
      borderRadius: shape.isSoft ? 16 : shape.radius.card,
      backgroundColor: colors.surfaceMuted,
      borderWidth: 1,
      borderColor: colors.cardBorder,
      gap: 8,
    },
    dropdownHeader: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingHorizontal: 2,
    },
    dropdownTitle: {
      fontSize: 12.5,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.primary,
    },
    dropdownHint: {
      fontSize: 11,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
    houseGrid: {
      gap: spacing.sm,
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
      flexShrink: 0,
    },
    houseTextBlock: {
      flex: 1,
      minWidth: 0,
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
    hogwartsSwitchCard: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      gap: spacing.sm,
      paddingVertical: 10,
      paddingHorizontal: 11,
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: 1,
      borderColor: colors.cardBorder,
    },
    hogwartsSwitchLeft: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 10,
      flex: 1,
      minWidth: 0,
    },
    hogwartsSwitchText: {
      flex: 1,
      minWidth: 0,
      gap: 2,
    },
    hogwartsSwitchTitle: {
      fontSize: 13.5,
      fontFamily: HOGWARTS_FONTS.body,
      color: colors.textPrimary,
    },
    hogwartsSwitchDesc: {
      fontSize: 11,
      fontFamily: HOGWARTS_FONTS.body,
      color: colors.textTertiary,
      lineHeight: 15,
    },
    sfxIconContainer: {
      width: 38,
      height: 38,
      borderRadius: 11,
      backgroundColor: colors.primaryLight,
      borderWidth: 1,
      borderColor: colors.accent,
      alignItems: 'center',
      justifyContent: 'center',
      flexShrink: 0,
    },
    desktopIconPreview: {
      width: 38,
      height: 38,
      borderRadius: 11,
      borderWidth: 1.5,
      borderColor: colors.accent,
      flexShrink: 0,
    },
    sfxControls: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 6,
      flexShrink: 0,
    },
    auditionButton: {
      paddingHorizontal: 9,
      paddingVertical: 4,
      borderRadius: shape.radius.pill,
      backgroundColor: colors.primaryLight,
      borderWidth: 1,
      borderColor: colors.primary,
    },
    auditionButtonText: {
      fontSize: 11.5,
      fontFamily: HOGWARTS_FONTS.body,
      color: colors.primaryDark,
    },
    brutalColorGrid: {
      flexDirection: 'row',
      flexWrap: 'wrap',
      gap: 8,
    },
    brutalColorCard: {
      flexBasis: '48%',
      flexGrow: 1,
      padding: 10,
      borderRadius: borderRadius.medium,
      backgroundColor: colors.surface,
      borderWidth: 1.5,
      borderColor: colors.cardBorder,
      gap: 3,
    },
    brutalColorCardActive: {
      backgroundColor: colors.primaryLight,
      borderWidth: 2,
      borderColor: colors.primary,
    },
    brutalColorTopRow: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      marginBottom: 4,
    },
    brutalColorSwatches: {
      flexDirection: 'row',
      gap: 4,
    },
    brutalMiniSwatch: {
      width: 16,
      height: 16,
      borderRadius: 5,
    },
    brutalMiniSwatchBordered: {
      borderWidth: 1,
      borderColor: colors.divider,
    },
    brutalColorLabel: {
      fontSize: 13.5,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    brutalColorDesc: {
      fontSize: 10.5,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
  }),
});

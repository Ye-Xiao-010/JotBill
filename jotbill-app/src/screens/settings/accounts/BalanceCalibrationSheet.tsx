/**
 * BalanceCalibrationSheet - 余额校准底部抽屉
 *
 * 亮点：
 * 1. 顶部展示被校准账户的品牌微标与对账差额提醒；
 * 2. 左右直观对照「当前账面余额 → 实际余额」；
 * 3. 输入时实时计算差额并高亮显示「自动补记调增 / 余额核减」；
 * 4. 附带常用备注快捷标签，一键填入。
 */
import React, { useContext, useEffect, useMemo, useState } from 'react';
import {
  KeyboardAvoidingView,
  Modal,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { SafeAreaInsetsContext } from 'react-native-safe-area-context';
import { ArrowRight, X } from 'lucide-react-native';
import type { Account } from '../../../native';
import AccountIcon from '../../../components/business/AccountIcon';
import { resolveAccountBadgeStyle } from '../../../constants/accountStyles';
import { HOGWARTS_FONTS } from '../../../constants/hogwartsAssets';
import { useStyles } from '../../../hooks';
import { useAlert, useTheme } from '../../../providers';
import { borderWidth, shadow, spacing, typography } from '../../../theme';
import type { ThemeColors } from '../../../theme/colors';
import type { ThemeShape } from '../../../theme/shape';
import {
  CALIBRATION_NOTE_PRESETS,
  formatAccountMoney,
  getAccountOption,
  normalizeCalibrationMinor,
  parseMinorInput,
} from './accountUiHelpers';

export interface BalanceCalibrationSheetProps {
  visible: boolean;
  account: Account | null;
  mismatchDiffMinor?: number | null;
  isSubmitting?: boolean;
  onClose: () => void;
  onSubmit: (payload: { id: string; amountMinor: number; note?: string }) => void;
}

export default function BalanceCalibrationSheet({
  visible,
  account,
  mismatchDiffMinor = null,
  isSubmitting = false,
  onClose,
  onSubmit,
}: BalanceCalibrationSheetProps) {
  const { colors, styleVariant, isDark } = useTheme();
  const { alert } = useAlert();
  const styles = useStyles(createStyles);
  const insets = useContext(SafeAreaInsetsContext) ?? { top: 0, right: 0, bottom: 0, left: 0 };

  const [actualInput, setActualInput] = useState('');
  const [note, setNote] = useState('');

  useEffect(() => {
    if (!visible || !account) return;
    // 负债账户前缀自带「-¥」，初始填入正数欠款绝对值，方便 Android decimal-pad 编辑
    const initialVal =
      account.kind === 'liability'
        ? Math.abs(account.currentBalanceMinor / 100)
        : account.currentBalanceMinor / 100;
    setActualInput(initialVal.toFixed(2));
    setNote(mismatchDiffMinor ? '对账校准差额' : '');
  }, [visible, account, mismatchDiffMinor]);

  const badgeStyle = useMemo(() => {
    if (!account) return null;
    return resolveAccountBadgeStyle(account, { variant: styleVariant, isDark });
  }, [account, styleVariant, isDark]);

  const parsedActualMinor = useMemo(() => {
    if (!account) return null;
    const raw = parseMinorInput(actualInput);
    if (raw === null) return null;
    return normalizeCalibrationMinor(raw, account.kind);
  }, [account, actualInput]);

  const diffMinor = useMemo(() => {
    if (!account || parsedActualMinor === null) return 0;
    return parsedActualMinor - account.currentBalanceMinor;
  }, [account, parsedActualMinor]);

  if (!account) return null;

  const option = getAccountOption(account.subtype);

  const handleSubmit = () => {
    if (parsedActualMinor === null) {
      alert('提示', '请输入有效余额');
      return;
    }
    onSubmit({
      id: account.id,
      amountMinor: parsedActualMinor,
      note: note.trim() || undefined,
    });
  };

  const diffLabel =
    parsedActualMinor === null
      ? '请输入有效的实际余额'
      : diffMinor > 0
        ? '将自动补记余额调增'
        : diffMinor < 0
          ? '将自动记录余额核减'
          : '与当前账面一致，无需调整';

  const diffAmountText =
    parsedActualMinor === null
      ? '--'
      : diffMinor > 0
        ? `+${formatAccountMoney(diffMinor, 'asset')}`
        : diffMinor < 0
          ? `-${formatAccountMoney(Math.abs(diffMinor), 'asset')}`
          : '¥0.00';

  const diffBannerStyle =
    parsedActualMinor === null || diffMinor === 0
      ? styles.diffBannerNeutral
      : diffMinor > 0
        ? styles.diffBannerPositive
        : styles.diffBannerNegative;

  const diffTextStyle =
    parsedActualMinor === null || diffMinor === 0
      ? styles.diffTextNeutral
      : diffMinor > 0
        ? styles.diffTextPositive
        : styles.diffTextNegative;

  return (
    <Modal
      visible={visible}
      transparent
      animationType="slide"
      onRequestClose={onClose}
    >
      <KeyboardAvoidingView
        style={styles.backdrop}
        behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      >
        <Pressable
          style={styles.backdropDismiss}
          onPress={onClose}
          accessibilityLabel="关闭校准余额弹窗"
        />

        <View style={styles.sheet}>
          <View style={styles.handle} />

          <View style={styles.sheetHeader}>
            <Text style={styles.sheetTitle}>校准余额</Text>
            <TouchableOpacity
              style={styles.closeBtn}
              onPress={onClose}
              activeOpacity={0.75}
              accessibilityRole="button"
              accessibilityLabel="关闭"
            >
              <X size={17} color={colors.textSecondary} strokeWidth={2.4} />
            </TouchableOpacity>
          </View>

          <ScrollView
            style={styles.bodyScroll}
            contentContainerStyle={styles.bodyContent}
            keyboardShouldPersistTaps="handled"
            showsVerticalScrollIndicator={false}
          >
            {/* 被校准账户身份条 */}
            <View style={styles.accountCard}>
              {badgeStyle ? (
                <View
                  style={[
                    styles.iconTile,
                    {
                      backgroundColor: badgeStyle.backgroundColor,
                      borderColor: badgeStyle.borderColor,
                      borderStyle: badgeStyle.borderStyle,
                    },
                  ]}
                >
                  <View
                    style={[
                      styles.iconInner,
                      badgeStyle.bitmapBackdropColor
                        ? { backgroundColor: badgeStyle.bitmapBackdropColor }
                        : null,
                    ]}
                  >
                    <AccountIcon
                      icon={account.icon}
                      name={account.name}
                      subtype={account.subtype}
                      size={22}
                      color={badgeStyle.textColor}
                    />
                  </View>
                </View>
              ) : null}
              <View style={styles.accountMeta}>
                <Text style={styles.accountName} numberOfLines={1}>
                  {account.name}
                </Text>
                <Text style={styles.accountSub} numberOfLines={1}>
                  {option.label}
                  {account.cardTail ? ` · 尾号 ${account.cardTail}` : ''}
                </Text>
              </View>
              {mismatchDiffMinor ? (
                <View style={styles.mismatchPill}>
                  <Text style={styles.mismatchPillText}>
                    差额 {formatAccountMoney(Math.abs(mismatchDiffMinor), 'asset')}
                  </Text>
                </View>
              ) : null}
            </View>

            {/* 账面 → 实际左右对照 */}
            <View style={styles.compareCard}>
              <View style={styles.compareCol}>
                <Text style={styles.compareLabel}>当前账面余额</Text>
                <Text
                  style={styles.bookBalance}
                  numberOfLines={1}
                  adjustsFontSizeToFit
                  minimumFontScale={0.75}
                >
                  {formatAccountMoney(account.currentBalanceMinor, account.kind)}
                </Text>
              </View>

              <ArrowRight size={18} color={colors.textQuaternary} strokeWidth={2.2} />

              <View style={styles.compareCol}>
                <Text style={[styles.compareLabel, styles.compareLabelActive]}>
                  实际余额（{account.kind === 'liability' ? '欠款' : '可用'}）
                </Text>
                <View style={styles.actualInputWrap}>
                  <Text style={styles.actualPrefix}>
                    {account.kind === 'liability' ? '-¥' : '¥'}
                  </Text>
                  <TextInput
                    testID="calibration-actual-input"
                    style={styles.actualInput}
                    value={actualInput}
                    onChangeText={setActualInput}
                    keyboardType="decimal-pad"
                    placeholder="0.00"
                    placeholderTextColor={colors.textQuaternary}
                    selectTextOnFocus
                  />
                </View>
              </View>
            </View>

            {/* 实时差额计算横幅 */}
            <View testID="calibration-diff-banner" style={[styles.diffBanner, diffBannerStyle]}>
              <Text style={[styles.diffLabel, diffTextStyle]}>{diffLabel}</Text>
              <Text style={[styles.diffAmount, diffTextStyle]}>{diffAmountText}</Text>
            </View>

            {/* 备注与快捷标签 */}
            <Text style={styles.fieldLabel}>校准备注（选填）</Text>
            <View style={styles.noteCard}>
              <TextInput
                style={styles.noteInput}
                value={note}
                onChangeText={setNote}
                placeholder="例如：月末核对银行余额"
                placeholderTextColor={colors.textQuaternary}
              />
            </View>

            <View style={styles.presetsRow}>
              {CALIBRATION_NOTE_PRESETS.map(preset => {
                const selected = note.trim() === preset;
                return (
                  <TouchableOpacity
                    key={preset}
                    style={[
                      styles.presetChip,
                      selected && styles.presetChipActive,
                    ]}
                    onPress={() => setNote(preset)}
                    activeOpacity={0.75}
                    accessibilityRole="button"
                  >
                    <Text
                      style={[
                        styles.presetChipText,
                        selected && styles.presetChipTextActive,
                      ]}
                    >
                      {preset}
                    </Text>
                  </TouchableOpacity>
                );
              })}
            </View>
          </ScrollView>

          <View
            style={[
              styles.sheetFooter,
              { paddingBottom: Math.max(insets.bottom, spacing.md) },
            ]}
          >
            <TouchableOpacity
              style={[
                styles.submitBtn,
                isSubmitting && styles.submitBtnDisabled,
              ]}
              onPress={handleSubmit}
              disabled={isSubmitting}
              activeOpacity={0.85}
              accessibilityRole="button"
              accessibilityLabel="确认保存校准"
            >
              <Text style={styles.submitBtnText}>确认保存校准</Text>
            </TouchableOpacity>
          </View>
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape, isDark: boolean) => {
  const isBrutal = !shape.isSoft && !shape.isHogwarts;
  const sheetBg = shape.isHogwarts
    ? isDark
      ? '#18132B'
      : '#F8F1E0'
    : shape.isSoft
      ? isDark
        ? '#14171E'
        : '#F8F9FC'
      : colors.background;

  return StyleSheet.create({
    backdrop: {
      flex: 1,
      justifyContent: 'flex-end',
      backgroundColor: colors.overlay,
    },
    backdropDismiss: {
      flex: 1,
    },
    sheet: {
      maxHeight: '85%',
      backgroundColor: sheetBg,
      borderTopLeftRadius: shape.radius.sheet,
      borderTopRightRadius: shape.radius.sheet,
      borderTopWidth: isBrutal ? borderWidth.medium : shape.isHogwarts ? 1.5 : isDark ? 1 : 0,
      borderLeftWidth: isBrutal ? borderWidth.medium : 0,
      borderRightWidth: isBrutal ? borderWidth.medium : 0,
      borderColor: isBrutal ? colors.stroke : colors.cardBorder,
      overflow: 'hidden',
    },
    handle: {
      width: 38,
      height: 4,
      borderRadius: 99,
      backgroundColor: colors.textQuaternary,
      opacity: 0.45,
      alignSelf: 'center',
      marginTop: 10,
      marginBottom: 4,
    },
    sheetHeader: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingHorizontal: 18,
      paddingTop: 4,
      paddingBottom: 10,
    },
    sheetTitle: {
      fontSize: 18,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
    },
    closeBtn: {
      width: 32,
      height: 32,
      borderRadius: 16,
      backgroundColor: colors.surfaceMuted,
      alignItems: 'center',
      justifyContent: 'center',
    },
    bodyScroll: {
      flexGrow: 0,
    },
    bodyContent: {
      paddingHorizontal: 16,
      paddingTop: 4,
      paddingBottom: 16,
    },
    accountCard: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 11,
      paddingHorizontal: 13,
      paddingVertical: 12,
      borderRadius: isBrutal ? 12 : 16,
      backgroundColor: colors.surface,
      borderWidth: isBrutal ? borderWidth.medium : shape.borderWidth.card,
      borderColor: isBrutal ? colors.stroke : colors.cardBorder,
      marginBottom: 12,
      ...(shape.isSoft && !isDark ? shape.elevation.card : {}),
    },
    iconTile: {
      width: 40,
      height: 40,
      borderRadius: isBrutal ? 10 : 12,
      borderWidth: 1.5,
      alignItems: 'center',
      justifyContent: 'center',
    },
    iconInner: {
      width: 28,
      height: 28,
      borderRadius: 8,
      alignItems: 'center',
      justifyContent: 'center',
    },
    accountMeta: {
      flex: 1,
      minWidth: 0,
    },
    accountName: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    accountSub: {
      marginTop: 2,
      fontSize: 12,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
    mismatchPill: {
      paddingHorizontal: 8,
      paddingVertical: 3,
      borderRadius: 99,
      backgroundColor: isDark
        ? 'rgba(229, 169, 76, 0.20)'
        : 'rgba(184, 107, 20, 0.14)',
    },
    mismatchPillText: {
      fontSize: 10.5,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.warning,
    },
    /* 账面 -> 实际对照 */
    compareCard: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 10,
      padding: 14,
      borderRadius: isBrutal ? 12 : 16,
      backgroundColor: colors.surface,
      borderWidth: isBrutal ? borderWidth.medium : shape.borderWidth.card,
      borderColor: isBrutal ? colors.stroke : colors.cardBorder,
      marginBottom: 10,
      ...(shape.isSoft && !isDark ? shape.elevation.card : {}),
    },
    compareCol: {
      flex: 1,
      minWidth: 0,
    },
    compareLabel: {
      fontSize: 11.5,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
    compareLabelActive: {
      color: colors.primary,
    },
    bookBalance: {
      marginTop: 6,
      fontSize: 18,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts
        ? HOGWARTS_FONTS.numeric
        : shape.isSoft
          ? undefined
          : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
      color: colors.textSecondary,
    },
    actualInputWrap: {
      marginTop: 4,
      flexDirection: 'row',
      alignItems: 'baseline',
      gap: 2,
      borderBottomWidth: 2,
      borderBottomColor: colors.primary,
      paddingBottom: 2,
    },
    actualPrefix: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts
        ? HOGWARTS_FONTS.numeric
        : shape.isSoft
          ? undefined
          : typography.fontFamily.mono,
      color: colors.textSecondary,
    },
    actualInput: {
      flex: 1,
      fontSize: 18,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts
        ? HOGWARTS_FONTS.numeric
        : shape.isSoft
          ? undefined
          : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
      color: colors.textPrimary,
      paddingVertical: 0,
    },
    /* 实时差额横幅 */
    diffBanner: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingHorizontal: 13,
      paddingVertical: 10,
      borderRadius: 12,
      marginBottom: 14,
    },
    diffBannerNeutral: {
      backgroundColor: colors.surfaceMuted,
    },
    diffBannerPositive: {
      backgroundColor: colors.incomeLight,
    },
    diffBannerNegative: {
      backgroundColor: colors.expenseLight,
    },
    diffLabel: {
      fontSize: 12.5,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
    },
    diffAmount: {
      fontSize: 14.5,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts
        ? HOGWARTS_FONTS.numeric
        : shape.isSoft
          ? undefined
          : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
    },
    diffTextNeutral: {
      color: colors.textTertiary,
    },
    diffTextPositive: {
      color: colors.incomeStrong,
    },
    diffTextNegative: {
      color: colors.expenseStrong,
    },
    /* 备注与预设 */
    fieldLabel: {
      fontSize: 12.5,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
      marginBottom: 7,
      paddingHorizontal: 2,
    },
    noteCard: {
      backgroundColor: colors.surface,
      borderRadius: isBrutal ? 12 : 14,
      borderWidth: isBrutal ? borderWidth.medium : shape.borderWidth.card,
      borderColor: isBrutal ? colors.stroke : colors.cardBorder,
      paddingHorizontal: 13,
      ...(shape.isSoft && !isDark ? shape.elevation.card : {}),
    },
    noteInput: {
      height: 44,
      fontSize: 14,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    presetsRow: {
      flexDirection: 'row',
      flexWrap: 'wrap',
      gap: 7,
      marginTop: 10,
    },
    presetChip: {
      paddingHorizontal: 11,
      paddingVertical: 6,
      borderRadius: 99,
      backgroundColor: colors.surface,
      borderWidth: 1,
      borderColor: isBrutal ? colors.stroke : colors.divider,
    },
    presetChipActive: {
      backgroundColor: colors.primaryLight,
      borderColor: isBrutal ? colors.stroke : colors.primary,
    },
    presetChipText: {
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    presetChipTextActive: {
      color: colors.primary,
    },
    sheetFooter: {
      paddingHorizontal: 16,
      paddingTop: 10,
      borderTopWidth: StyleSheet.hairlineWidth,
      borderTopColor: colors.divider,
      backgroundColor: sheetBg,
    },
    submitBtn: {
      height: 48,
      borderRadius: shape.radius.button,
      backgroundColor: colors.primary,
      borderWidth: isBrutal ? borderWidth.medium : shape.isHogwarts ? 1 : 0,
      borderColor: shape.isHogwarts ? colors.accent : colors.stroke,
      alignItems: 'center',
      justifyContent: 'center',
      ...(isBrutal ? shadow.small : shape.elevation.card),
    },
    submitBtnDisabled: {
      opacity: 0.6,
    },
    submitBtnText: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: shape.isSoft || shape.isHogwarts ? colors.onPrimary : '#FFFFFF',
    },
  });
};

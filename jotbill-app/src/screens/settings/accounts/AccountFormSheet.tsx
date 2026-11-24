/**
 * AccountFormSheet - 添加 / 编辑账户底部抽屉
 *
 * 设计亮点：
 * 1. 顶部实时账户卡面预览（随类型、发卡银行、尾号、名称与余额实时染色更新）；
 * 2. 资产 / 负债两步分流 + 图标化子类型卡片（编辑态锁定账户性质，匹配原生桥约束）；
 * 3. 常用 8 大银行横向直选 + 同抽屉内无缝切换可搜索的 23 家银行目录，消除套娃弹窗；
 * 4. 非银行卡模式内联「自动识别 / 微信 / 支付宝」三选一芯片；
 * 5. 大号金额输入区，负债模式直观呈现「-¥」前缀与自动记负提示。
 */
import React, { useContext, useEffect, useMemo, useState } from 'react';
import {
  Alert,
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
import {
  Banknote,
  Check,
  ChevronLeft,
  ChevronRight,
  CircleEllipsis,
  CreditCard,
  HandCoins,
  Landmark,
  Search,
  Sparkles,
  Wallet,
  X,
  type LucideIcon,
} from 'lucide-react-native';
import type {
  Account,
  AccountInput,
  AccountSubtype,
  AccountUpdateInput,
} from '../../../native';
import AccountIcon from '../../../components/business/AccountIcon';
import { BANKS, resolveAccountIcon } from '../../../constants/accountIcons';
import { resolveAccountBadgeStyle } from '../../../constants/accountStyles';
import { HOGWARTS_FONTS } from '../../../constants/hogwartsAssets';
import { useStyles } from '../../../hooks';
import { useTheme } from '../../../providers';
import { borderWidth, shadow, spacing, typography } from '../../../theme';
import type { ThemeColors } from '../../../theme/colors';
import type { ThemeShape } from '../../../theme/shape';
import {
  filterBanksByQuery,
  formatAccountMoney,
  getAccountOption,
  getOptionsForKind,
  HOT_BANKS,
  isBankCardSubtype,
  parseMinorInput,
} from './accountUiHelpers';

const SUBTYPE_LUCIDE_ICONS: Record<AccountSubtype, LucideIcon> = {
  bank_debit: Landmark,
  e_wallet: Wallet,
  cash: Banknote,
  other: CircleEllipsis,
  credit_card: CreditCard,
  consumer_loan: HandCoins,
};

export interface AccountFormSubmitPayload {
  id?: string;
  input: AccountInput | AccountUpdateInput;
}

export interface AccountFormSheetProps {
  visible: boolean;
  editingAccount: Account | null;
  isSubmitting?: boolean;
  onClose: () => void;
  onSubmit: (payload: AccountFormSubmitPayload) => void;
}

export default function AccountFormSheet({
  visible,
  editingAccount,
  isSubmitting = false,
  onClose,
  onSubmit,
}: AccountFormSheetProps) {
  const { colors, styleVariant, isDark, isSoft, isHogwarts } = useTheme();
  const styles = useStyles(createStyles);
  const insets = useContext(SafeAreaInsetsContext) ?? { top: 0, right: 0, bottom: 0, left: 0 };

  const [kind, setKind] = useState<Account['kind']>('asset');
  const [subtype, setSubtype] = useState<AccountSubtype>('bank_debit');
  const [name, setName] = useState('');
  const [cardTail, setCardTail] = useState('');
  const [initialBalance, setInitialBalance] = useState('');
  /** 图标：null = 按名称自动匹配；'bank:<code>' / 'brand:<code>' = 用户手动指定 */
  const [iconOverride, setIconOverride] = useState<string | null>(null);
  /** 用户是否显式改过图标 —— 编辑老账户未改图标时不写 icon 字段，避免误清旧 emoji */
  const [iconTouched, setIconTouched] = useState(false);
  /** 同抽屉内无缝切换「表单视图」与「全部 23 家银行目录」 */
  const [viewMode, setViewMode] = useState<'form' | 'banks'>('form');
  const [bankSearch, setBankSearch] = useState('');

  // 每次打开抽屉时按创建/编辑上下文重置表单
  useEffect(() => {
    if (!visible) return;
    setViewMode('form');
    setBankSearch('');
    if (editingAccount) {
      setKind(editingAccount.kind);
      setSubtype(editingAccount.subtype);
      setName(editingAccount.name);
      setCardTail(editingAccount.cardTail ?? '');
      setInitialBalance('');
      setIconOverride(
        editingAccount.icon && editingAccount.icon.includes(':')
          ? editingAccount.icon
          : null,
      );
      setIconTouched(false);
    } else {
      setKind('asset');
      setSubtype('bank_debit');
      setName('');
      setCardTail('');
      setInitialBalance('');
      setIconOverride(null);
      setIconTouched(false);
    }
  }, [visible, editingAccount]);

  const isEditing = Boolean(editingAccount);
  const isBankCard = isBankCardSubtype(subtype);
  const selectedOption = useMemo(() => getAccountOption(subtype), [subtype]);
  const visibleOptions = useMemo(() => getOptionsForKind(kind), [kind]);

  const selectedBank = useMemo(() => {
    const effectiveIcon = iconTouched ? iconOverride : (editingAccount?.icon ?? null);
    const match = resolveAccountIcon(effectiveIcon, name);
    if (!match || match.kind !== 'bank') return null;
    return BANKS.find(bank => bank.code === match.code) ?? null;
  }, [iconTouched, iconOverride, editingAccount?.icon, name]);

  const filteredBanks = useMemo(
    () => filterBanksByQuery(bankSearch),
    [bankSearch],
  );

  // 实时账户卡面预览配色（复用 accountStyles.ts 单一数据源）
  const effectivePreviewIcon = iconTouched
    ? iconOverride
    : (editingAccount?.icon ?? null);

  const previewBadgeStyle = useMemo(
    () =>
      resolveAccountBadgeStyle(
        {
          id: editingAccount?.id ?? `preview-${subtype}-${selectedBank?.code ?? 'new'}`,
          name: name.trim() || selectedOption.label,
          subtype,
          kind,
          icon: effectivePreviewIcon,
          cardTail: isBankCard ? cardTail : null,
        },
        { variant: styleVariant, isDark },
      ),
    [
      editingAccount?.id,
      subtype,
      selectedBank?.code,
      name,
      selectedOption.label,
      kind,
      effectivePreviewIcon,
      isBankCard,
      cardTail,
      styleVariant,
      isDark,
    ],
  );

  const previewBalanceMinor = useMemo(() => {
    const parsed = parseMinorInput(initialBalance || '0');
    if (parsed === null) return 0;
    return kind === 'liability' ? -Math.abs(parsed) : Math.max(0, parsed);
  }, [initialBalance, kind]);

  const handleSelectKind = (nextKind: Account['kind']) => {
    if (isEditing || nextKind === kind) return;
    setKind(nextKind);
    const defaultSubtype: AccountSubtype =
      nextKind === 'asset' ? 'bank_debit' : 'credit_card';
    setSubtype(defaultSubtype);
  };

  const handleSelectSubtype = (nextSubtype: AccountSubtype) => {
    setSubtype(nextSubtype);
    if (!isBankCardSubtype(nextSubtype)) {
      if (iconOverride?.startsWith('bank:')) {
        setIconOverride(null);
        setIconTouched(true);
      }
      // 若名称仍是银行默认名，切到非银行卡时清空，避免「现金」顶着「招商银行」名字
      const trimmed = name.trim();
      if (BANKS.some(b => b.shortName === trimmed || b.name === trimmed)) {
        setName('');
      }
    }
  };

  const handleSelectBank = (bank: (typeof BANKS)[number], returnToForm = false) => {
    setIconOverride(`bank:${bank.code}`);
    setIconTouched(true);
    setName(prev => {
      const trimmed = prev.trim();
      const isEmptyOrBankName =
        !trimmed ||
        BANKS.some(
          b => b.code !== bank.code && (b.name === trimmed || b.shortName === trimmed),
        );
      return isEmptyOrBankName ? bank.shortName : prev;
    });
    if (returnToForm) {
      setViewMode('form');
      setBankSearch('');
    }
  };

  const handleSelectBrand = (brandIcon: string | null) => {
    setIconOverride(brandIcon);
    setIconTouched(true);
    if (!name.trim()) {
      if (brandIcon === 'brand:wechat') setName('微信零钱');
      else if (brandIcon === 'brand:alipay') setName('支付宝');
    }
  };

  const handleCardTailChange = (raw: string) => {
    setCardTail(raw.replace(/\D/g, '').slice(0, 4));
  };

  const handleSubmit = () => {
    const trimmedName = name.trim();
    if (!trimmedName) {
      Alert.alert('提示', '请输入账户名称');
      return;
    }
    const trimmedTail = cardTail.trim();
    if (isBankCard && trimmedTail && !/^\d{4}$/.test(trimmedTail)) {
      Alert.alert('提示', '卡尾号请填写后 4 位数字');
      return;
    }

    if (editingAccount) {
      onSubmit({
        id: editingAccount.id,
        input: {
          name: trimmedName,
          subtype,
          ...(iconTouched ? { icon: iconOverride } : {}),
          ...(isBankCard ? { cardTail: trimmedTail || null } : {}),
        },
      });
      return;
    }

    const amountMinor = parseMinorInput(initialBalance || '0');
    if (amountMinor === null) {
      Alert.alert('提示', '请输入有效的初始余额');
      return;
    }
    if (selectedOption.kind === 'asset' && amountMinor < 0) {
      Alert.alert('提示', '资产账户初始余额不能为负数');
      return;
    }
    const normalizedInitialBalance =
      selectedOption.kind === 'liability'
        ? -Math.abs(amountMinor)
        : amountMinor;

    onSubmit({
      input: {
        name: trimmedName,
        subtype,
        kind: selectedOption.kind,
        initialBalanceMinor: normalizedInitialBalance,
        currency: 'CNY',
        icon: iconOverride,
        cardTail: isBankCard ? (trimmedTail || null) : null,
      },
    });
  };

  const previewDisplayName =
    name.trim() ||
    (isBankCard
      ? '选择发卡银行'
      : `例如：${selectedOption.hint.split(' / ')[0]}`);

  return (
    <Modal
      visible={visible}
      transparent
      animationType="slide"
      onRequestClose={() => {
        if (viewMode === 'banks') {
          setViewMode('form');
        } else {
          onClose();
        }
      }}
    >
      <KeyboardAvoidingView
        style={styles.backdrop}
        behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      >
        <Pressable
          style={styles.backdropDismiss}
          onPress={onClose}
          accessibilityLabel="关闭账户弹窗"
        />

        <View style={styles.sheet}>
          <View style={styles.handle} />

          {viewMode === 'banks' ? (
            /* ==================== 全部 23 家银行目录视图 ==================== */
            <>
              <View style={styles.sheetHeader}>
                <TouchableOpacity
                  style={styles.backLinkBtn}
                  onPress={() => setViewMode('form')}
                  activeOpacity={0.75}
                  accessibilityRole="button"
                  accessibilityLabel="返回账户表单"
                >
                  <ChevronLeft size={18} color={colors.primary} strokeWidth={2.4} />
                  <Text style={styles.backLinkText}>返回</Text>
                </TouchableOpacity>
                <Text style={styles.sheetTitle}>
                  选择发卡银行 ({filteredBanks.length})
                </Text>
                <View style={styles.headerSpacer} />
              </View>

              <View style={styles.searchWrap}>
                <View style={styles.searchBox}>
                  <Search size={16} color={colors.textTertiary} strokeWidth={2.2} />
                  <TextInput
                    style={styles.searchInput}
                    value={bankSearch}
                    onChangeText={setBankSearch}
                    placeholder="搜索银行，如 招商 / 兴业 / 宁波..."
                    placeholderTextColor={colors.textQuaternary}
                    returnKeyType="search"
                  />
                  {bankSearch.length > 0 ? (
                    <TouchableOpacity
                      onPress={() => setBankSearch('')}
                      hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
                      accessibilityLabel="清空搜索"
                    >
                      <X size={15} color={colors.textTertiary} />
                    </TouchableOpacity>
                  ) : null}
                </View>
              </View>

              <ScrollView
                style={styles.bankDirScroll}
                contentContainerStyle={[
                  styles.bankDirContent,
                  { paddingBottom: Math.max(insets.bottom, spacing.md) + spacing.md },
                ]}
                keyboardShouldPersistTaps="handled"
                showsVerticalScrollIndicator={false}
              >
                {filteredBanks.length === 0 ? (
                  <View style={styles.emptySearchBox}>
                    <Text style={styles.emptySearchText}>
                      未找到匹配的银行，可直接返回手动输入账户名
                    </Text>
                  </View>
                ) : (
                  filteredBanks.map(bank => {
                    const isSelected = selectedBank?.code === bank.code;
                    return (
                      <TouchableOpacity
                        key={bank.code}
                        style={[
                          styles.bankDirItem,
                          isSelected && styles.bankDirItemSelected,
                        ]}
                        onPress={() => handleSelectBank(bank, true)}
                        activeOpacity={0.75}
                        accessibilityRole="button"
                        accessibilityLabel={`选择${bank.name}`}
                      >
                        <View
                          style={[
                            styles.bankDirLogoBox,
                            isDark && styles.darkBitmapBackdrop,
                          ]}
                        >
                          <AccountIcon icon={`bank:${bank.code}`} size={26} />
                        </View>
                        <View style={styles.bankDirTextWrap}>
                          <Text style={styles.bankDirName}>{bank.name}</Text>
                          {bank.shortName !== bank.name ? (
                            <Text style={styles.bankDirShort}>
                              简称：{bank.shortName}
                            </Text>
                          ) : null}
                        </View>
                        {isSelected ? (
                          <Check size={18} color={colors.primary} strokeWidth={2.6} />
                        ) : null}
                      </TouchableOpacity>
                    );
                  })
                )}
              </ScrollView>
            </>
          ) : (
            /* ==================== 添加 / 编辑账户表单视图 ==================== */
            <>
              <View style={styles.sheetHeader}>
                <Text style={styles.sheetTitle}>
                  {isEditing ? '编辑账户' : '添加账户'}
                </Text>
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
                {/* 顶部实时账户卡面预览 */}
                <View
                  testID="account-live-preview"
                  style={[
                    styles.livePreviewCard,
                    {
                      backgroundColor: previewBadgeStyle.backgroundColor,
                      borderColor: previewBadgeStyle.borderColor,
                      borderStyle: previewBadgeStyle.borderStyle,
                    },
                  ]}
                >
                  <View style={styles.livePreviewLeft}>
                    <View
                      style={[
                        styles.livePreviewIcon,
                        previewBadgeStyle.bitmapBackdropColor
                          ? { backgroundColor: previewBadgeStyle.bitmapBackdropColor }
                          : null,
                      ]}
                    >
                      <AccountIcon
                        icon={effectivePreviewIcon}
                        name={name}
                        subtype={subtype}
                        size={24}
                        color={previewBadgeStyle.textColor}
                      />
                    </View>
                    <View style={styles.livePreviewMeta}>
                      <Text
                        style={[
                          styles.livePreviewName,
                          !name.trim() && styles.livePreviewPlaceholder,
                        ]}
                        numberOfLines={1}
                      >
                        {previewDisplayName}
                      </Text>
                      <Text
                        style={[
                          styles.livePreviewSub,
                          { color: previewBadgeStyle.textColor },
                        ]}
                        numberOfLines={1}
                      >
                        {kind === 'asset' ? '资产' : '负债'} · {selectedOption.shortLabel}
                        {isBankCard && cardTail.trim()
                          ? ` · •••• ${cardTail.trim()}`
                          : ''}
                      </Text>
                    </View>
                  </View>

                  {!isEditing ? (
                    <View style={styles.livePreviewRight}>
                      <Text style={styles.livePreviewAmtLabel}>
                        {kind === 'liability' ? '待还欠款' : '初始余额'}
                      </Text>
                      <Text
                        style={[
                          styles.livePreviewAmtVal,
                          kind === 'liability' &&
                            previewBalanceMinor !== 0 &&
                            styles.debtAmountText,
                        ]}
                        numberOfLines={1}
                      >
                        {formatAccountMoney(previewBalanceMinor, kind)}
                      </Text>
                    </View>
                  ) : null}
                </View>

                {/* 1. 资产 / 负债分段切换 */}
                {isEditing ? (
                  <View style={styles.lockedKindRow}>
                    <View
                      style={[
                        styles.kindDot,
                        {
                          backgroundColor:
                            kind === 'liability' ? colors.expense : colors.income,
                        },
                      ]}
                    />
                    <Text style={styles.lockedKindText}>
                      {kind === 'asset' ? '资产账户' : '负债账户'} · 创建后不可变更性质
                    </Text>
                  </View>
                ) : (
                  <View style={styles.kindSegment}>
                    <TouchableOpacity
                      style={[
                        styles.kindTab,
                        kind === 'asset' && styles.kindTabActive,
                      ]}
                      onPress={() => handleSelectKind('asset')}
                      activeOpacity={0.8}
                      accessibilityRole="button"
                      accessibilityState={{ selected: kind === 'asset' }}
                    >
                      <View
                        style={[styles.kindDot, { backgroundColor: colors.income }]}
                      />
                      <Text
                        style={[
                          styles.kindTabText,
                          kind === 'asset' && styles.kindTabTextActive,
                        ]}
                      >
                        资产账户
                      </Text>
                    </TouchableOpacity>
                    <TouchableOpacity
                      style={[
                        styles.kindTab,
                        kind === 'liability' && styles.kindTabActive,
                      ]}
                      onPress={() => handleSelectKind('liability')}
                      activeOpacity={0.8}
                      accessibilityRole="button"
                      accessibilityState={{ selected: kind === 'liability' }}
                    >
                      <View
                        style={[
                          styles.kindDot,
                          styles.kindDotDebt,
                          { backgroundColor: colors.expense },
                        ]}
                      />
                      <Text
                        style={[
                          styles.kindTabText,
                          kind === 'liability' && styles.kindTabTextActive,
                        ]}
                      >
                        负债账户
                      </Text>
                    </TouchableOpacity>
                  </View>
                )}

                {/* 图标化子类型网格 */}
                <View style={styles.subtypeGrid}>
                  {visibleOptions.map(option => {
                    const active = subtype === option.subtype;
                    const IconComp = SUBTYPE_LUCIDE_ICONS[option.subtype];
                    return (
                      <TouchableOpacity
                        key={option.subtype}
                        style={[
                          styles.subtypeCard,
                          active && styles.subtypeCardActive,
                        ]}
                        onPress={() => handleSelectSubtype(option.subtype)}
                        activeOpacity={0.8}
                        accessibilityRole="button"
                        accessibilityState={{ selected: active }}
                      >
                        <View
                          style={[
                            styles.subtypeIconBox,
                            active && styles.subtypeIconBoxActive,
                          ]}
                        >
                          <IconComp
                            size={17}
                            strokeWidth={isSoft ? 2 : 2.3}
                            color={
                              active
                                ? isSoft || isHogwarts
                                  ? colors.onPrimary
                                  : '#FFFFFF'
                                : colors.textSecondary
                            }
                          />
                        </View>
                        <View style={styles.subtypeTextWrap}>
                          <Text
                            style={[
                              styles.subtypeName,
                              active && styles.subtypeNameActive,
                            ]}
                            numberOfLines={1}
                          >
                            {option.label}
                          </Text>
                          <Text style={styles.subtypeHint} numberOfLines={1}>
                            {option.hint}
                          </Text>
                        </View>
                      </TouchableOpacity>
                    );
                  })}
                </View>

                {/* 2. 银行卡类：常用 8 大银行横向直选 + 全部 23 家入口 */}
                {isBankCard ? (
                  <>
                    <View style={styles.sectionHeaderRow}>
                      <Text style={styles.fieldLabel}>发卡银行</Text>
                      <TouchableOpacity
                        style={styles.moreBanksBtn}
                        onPress={() => setViewMode('banks')}
                        activeOpacity={0.75}
                        accessibilityRole="button"
                        accessibilityLabel="查看全部 23 家银行"
                      >
                        <Text style={styles.moreBanksText}>
                          全部 {BANKS.length} 家
                        </Text>
                        <ChevronRight
                          size={14}
                          color={colors.primary}
                          strokeWidth={2.4}
                        />
                      </TouchableOpacity>
                    </View>

                    <ScrollView
                      horizontal
                      showsHorizontalScrollIndicator={false}
                      contentContainerStyle={styles.bankRail}
                      keyboardShouldPersistTaps="handled"
                    >
                      {HOT_BANKS.map(bank => {
                        const active = selectedBank?.code === bank.code;
                        return (
                          <TouchableOpacity
                            key={bank.code}
                            style={[
                              styles.bankChip,
                              active && styles.bankChipActive,
                            ]}
                            onPress={() => handleSelectBank(bank, false)}
                            activeOpacity={0.78}
                            accessibilityRole="button"
                            accessibilityState={{ selected: active }}
                            accessibilityLabel={bank.shortName}
                          >
                            <View
                              style={[
                                styles.bankChipLogo,
                                isDark && styles.darkBitmapBackdrop,
                              ]}
                            >
                              <AccountIcon icon={`bank:${bank.code}`} size={24} />
                            </View>
                            <Text
                              style={[
                                styles.bankChipName,
                                active && styles.bankChipNameActive,
                              ]}
                              numberOfLines={1}
                            >
                              {bank.shortName.replace('银行', '')}
                            </Text>
                          </TouchableOpacity>
                        );
                      })}
                    </ScrollView>

                    {selectedBank &&
                    !HOT_BANKS.some(b => b.code === selectedBank.code) ? (
                      <TouchableOpacity
                        style={styles.selectedNonHotRow}
                        onPress={() => setViewMode('banks')}
                        activeOpacity={0.78}
                      >
                        <View
                          style={[
                            styles.selectedNonHotLogo,
                            isDark && styles.darkBitmapBackdrop,
                          ]}
                        >
                          <AccountIcon icon={`bank:${selectedBank.code}`} size={20} />
                        </View>
                        <Text style={styles.selectedNonHotText}>
                          已选：{selectedBank.name}
                        </Text>
                        <Text style={styles.moreBanksText}>更换</Text>
                      </TouchableOpacity>
                    ) : null}
                  </>
                ) : null}

                {/* 3. 组合式基本信息卡（账户名称 + 卡尾号） */}
                <View style={styles.sectionHeaderRow}>
                  <Text style={styles.fieldLabel}>基本信息</Text>
                </View>
                <View style={styles.formCard}>
                  <View style={styles.formRow}>
                    <Text style={styles.formRowLabel}>账户名称</Text>
                    <TextInput
                      style={styles.formInput}
                      value={name}
                      onChangeText={setName}
                      placeholder={
                        isBankCard
                          ? '选择银行自动带出，可自定义'
                          : '例如：微信零钱 / 现金钱包'
                      }
                      placeholderTextColor={colors.textQuaternary}
                      returnKeyType="done"
                    />
                  </View>

                  {isBankCard ? (
                    <View style={[styles.formRow, styles.formRowDivider]}>
                      <Text style={styles.formRowLabel}>卡尾号</Text>
                      <Text style={styles.tailPrefix}>••••</Text>
                      <TextInput
                        style={[styles.formInput, styles.tailInput]}
                        value={cardTail}
                        onChangeText={handleCardTailChange}
                        placeholder="2510"
                        placeholderTextColor={colors.textQuaternary}
                        keyboardType="number-pad"
                        maxLength={4}
                      />
                      <View style={styles.smsBadge}>
                        <Text style={styles.smsBadgeText}>短信自动匹配</Text>
                      </View>
                    </View>
                  ) : null}
                </View>

                {/* 非银行卡：内联图标快捷三选一（无套娃弹窗） */}
                {!isBankCard ? (
                  <>
                    <View style={styles.sectionHeaderRow}>
                      <Text style={styles.fieldLabel}>账户图标</Text>
                    </View>
                    <View style={styles.brandPillsRow}>
                      <TouchableOpacity
                        style={[
                          styles.brandPill,
                          iconOverride === null && styles.brandPillActive,
                        ]}
                        onPress={() => handleSelectBrand(null)}
                        activeOpacity={0.78}
                        accessibilityRole="button"
                        accessibilityState={{ selected: iconOverride === null }}
                      >
                        <Sparkles
                          size={14}
                          color={
                            iconOverride === null
                              ? colors.primary
                              : colors.textSecondary
                          }
                          strokeWidth={2.2}
                        />
                        <Text
                          style={[
                            styles.brandPillText,
                            iconOverride === null && styles.brandPillTextActive,
                          ]}
                        >
                          自动识别
                        </Text>
                      </TouchableOpacity>

                      {(
                        [
                          { code: 'wechat', label: '微信' },
                          { code: 'alipay', label: '支付宝' },
                        ] as const
                      ).map(brand => {
                        const token = `brand:${brand.code}`;
                        const active = iconOverride === token;
                        return (
                          <TouchableOpacity
                            key={brand.code}
                            style={[
                              styles.brandPill,
                              active && styles.brandPillActive,
                            ]}
                            onPress={() => handleSelectBrand(token)}
                            activeOpacity={0.78}
                            accessibilityRole="button"
                            accessibilityState={{ selected: active }}
                          >
                            <AccountIcon icon={token} size={18} />
                            <Text
                              style={[
                                styles.brandPillText,
                                active && styles.brandPillTextActive,
                              ]}
                            >
                              {brand.label}
                            </Text>
                          </TouchableOpacity>
                        );
                      })}
                    </View>
                  </>
                ) : null}

                {/* 4. 初始余额大字输入卡（仅新建） */}
                {!isEditing ? (
                  <>
                    <View style={styles.sectionHeaderRow}>
                      <Text style={styles.fieldLabel}>
                        {kind === 'liability'
                          ? '当前待还欠款（选填）'
                          : '初始余额（选填）'}
                      </Text>
                    </View>
                    <View style={styles.amountCard}>
                      <View style={styles.amountInputRow}>
                        <Text style={styles.amountSymbol}>
                          {kind === 'liability' ? '-¥' : '¥'}
                        </Text>
                        <TextInput
                          style={styles.amountBigInput}
                          value={initialBalance}
                          onChangeText={setInitialBalance}
                          placeholder="0.00"
                          placeholderTextColor={colors.textQuaternary}
                          keyboardType="decimal-pad"
                        />
                      </View>
                      <Text
                        style={[
                          styles.amountHelper,
                          kind === 'liability' && styles.amountHelperDebt,
                        ]}
                      >
                        {kind === 'liability'
                          ? '直接输入正数欠款即可，系统会自动按负债（负余额）计入净资产'
                          : '后续可随时通过「校准余额」对齐真实账户余额'}
                      </Text>
                    </View>
                  </>
                ) : null}
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
                  accessibilityLabel={isEditing ? '保存修改' : '创建账户'}
                >
                  <Text style={styles.submitBtnText}>
                    {isEditing ? '保存修改' : '创建账户'}
                  </Text>
                </TouchableOpacity>
              </View>
            </>
          )}
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
      maxHeight: '90%',
      backgroundColor: sheetBg,
      borderTopLeftRadius: shape.radius.sheet,
      borderTopRightRadius: shape.radius.sheet,
      borderTopWidth: isBrutal ? borderWidth.medium : shape.isHogwarts ? 1.5 : isDark ? 1 : 0,
      borderLeftWidth: isBrutal ? borderWidth.medium : 0,
      borderRightWidth: isBrutal ? borderWidth.medium : 0,
      borderColor: shape.isHogwarts
        ? colors.cardBorder
        : isBrutal
          ? colors.stroke
          : colors.cardBorder,
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
    backLinkBtn: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 2,
      minWidth: 56,
    },
    backLinkText: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.primary,
    },
    headerSpacer: {
      width: 56,
    },
    bodyScroll: {
      flexGrow: 0,
    },
    bodyContent: {
      paddingHorizontal: 16,
      paddingTop: 4,
      paddingBottom: 18,
    },
    /* 实时账户卡面预览 */
    livePreviewCard: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      gap: 12,
      paddingHorizontal: 14,
      paddingVertical: 14,
      borderRadius: shape.radius.card,
      borderWidth: 1.5,
      marginBottom: 14,
    },
    livePreviewLeft: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 11,
      flex: 1,
      minWidth: 0,
    },
    livePreviewIcon: {
      width: 44,
      height: 44,
      borderRadius: shape.isSoft ? 14 : shape.isHogwarts ? 13 : 10,
      backgroundColor: colors.surface,
      alignItems: 'center',
      justifyContent: 'center',
    },
    livePreviewMeta: {
      flex: 1,
      minWidth: 0,
    },
    livePreviewName: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    livePreviewPlaceholder: {
      opacity: 0.65,
    },
    livePreviewSub: {
      marginTop: 3,
      fontSize: 11.5,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
    },
    livePreviewRight: {
      alignItems: 'flex-end',
      flexShrink: 0,
    },
    livePreviewAmtLabel: {
      fontSize: 10.5,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
    livePreviewAmtVal: {
      marginTop: 2,
      fontSize: 17,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts
        ? HOGWARTS_FONTS.numeric
        : shape.isSoft
          ? undefined
          : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
      color: colors.textPrimary,
    },
    debtAmountText: {
      color: colors.expense,
    },
    /* 资产/负债切换 */
    kindSegment: {
      flexDirection: 'row',
      gap: 4,
      padding: 4,
      borderRadius: 14,
      backgroundColor: colors.surfaceMuted,
      borderWidth: isBrutal ? borderWidth.thin : shape.isHogwarts ? 1 : 0,
      borderColor: isBrutal ? colors.stroke : colors.divider,
      marginBottom: 10,
    },
    kindTab: {
      flex: 1,
      height: 36,
      borderRadius: 10,
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'center',
      gap: 6,
    },
    kindTabActive: {
      backgroundColor: colors.surface,
      borderWidth: isBrutal ? 1.5 : 0,
      borderColor: colors.stroke,
      ...(shape.isSoft || shape.isHogwarts ? shape.elevation.card : {}),
    },
    kindDot: {
      width: 7,
      height: 7,
      borderRadius: 4,
    },
    kindDotDebt: {
      borderRadius: 2,
    },
    kindTabText: {
      fontSize: 13,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
    kindTabTextActive: {
      color: colors.textPrimary,
    },
    lockedKindRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 7,
      paddingHorizontal: 12,
      paddingVertical: 8,
      borderRadius: 12,
      backgroundColor: colors.surfaceMuted,
      marginBottom: 10,
    },
    lockedKindText: {
      fontSize: 12,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    /* 子类型卡片网格 */
    subtypeGrid: {
      flexDirection: 'row',
      flexWrap: 'wrap',
      gap: 8,
    },
    subtypeCard: {
      width: '48.6%',
      flexDirection: 'row',
      alignItems: 'center',
      gap: 9,
      paddingHorizontal: 11,
      paddingVertical: 10,
      borderRadius: isBrutal ? 10 : 14,
      backgroundColor: colors.surface,
      borderWidth: isBrutal ? borderWidth.medium : 1.5,
      borderColor: isBrutal
        ? colors.stroke
        : shape.isSoft && !isDark
          ? '#E6E9F0'
          : colors.cardBorder,
    },
    subtypeCardActive: {
      backgroundColor: colors.primaryLight,
      borderColor: isBrutal ? colors.stroke : colors.primary,
      ...(isBrutal ? shadow.small : {}),
    },
    subtypeIconBox: {
      width: 32,
      height: 32,
      borderRadius: 10,
      backgroundColor: colors.surfaceMuted,
      alignItems: 'center',
      justifyContent: 'center',
    },
    subtypeIconBoxActive: {
      backgroundColor: colors.primary,
    },
    subtypeTextWrap: {
      flex: 1,
      minWidth: 0,
    },
    subtypeName: {
      fontSize: 13,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    subtypeNameActive: {
      color: colors.primary,
    },
    subtypeHint: {
      marginTop: 1,
      fontSize: 10.5,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
    /* 分区标题 */
    sectionHeaderRow: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      marginTop: 14,
      marginBottom: 7,
      paddingHorizontal: 2,
    },
    fieldLabel: {
      fontSize: 12.5,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    moreBanksBtn: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 2,
    },
    moreBanksText: {
      fontSize: 12.5,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.primary,
    },
    /* 常用银行横向直选 */
    bankRail: {
      gap: 8,
      paddingVertical: 2,
      paddingRight: 4,
    },
    bankChip: {
      width: 68,
      paddingVertical: 8,
      paddingHorizontal: 4,
      borderRadius: isBrutal ? 10 : 13,
      backgroundColor: colors.surface,
      borderWidth: isBrutal ? borderWidth.thin : 1.5,
      borderColor: isBrutal
        ? colors.stroke
        : shape.isSoft && !isDark
          ? '#E6E9F0'
          : colors.cardBorder,
      alignItems: 'center',
      gap: 5,
    },
    bankChipActive: {
      borderColor: isBrutal ? colors.stroke : colors.primary,
      backgroundColor: colors.primaryLight,
    },
    bankChipLogo: {
      width: 30,
      height: 30,
      borderRadius: 9,
      alignItems: 'center',
      justifyContent: 'center',
    },
    darkBitmapBackdrop: {
      backgroundColor: shape.isHogwarts
        ? 'rgba(252, 247, 234, 0.90)'
        : 'rgba(255, 255, 255, 0.90)',
    },
    bankChipName: {
      fontSize: 11,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    bankChipNameActive: {
      color: colors.primary,
    },
    selectedNonHotRow: {
      marginTop: 8,
      flexDirection: 'row',
      alignItems: 'center',
      gap: 8,
      paddingHorizontal: 12,
      paddingVertical: 8,
      borderRadius: 12,
      backgroundColor: colors.primaryLight,
    },
    selectedNonHotLogo: {
      width: 26,
      height: 26,
      borderRadius: 8,
      alignItems: 'center',
      justifyContent: 'center',
    },
    selectedNonHotText: {
      flex: 1,
      fontSize: 12.5,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    /* 组合表单卡片 */
    formCard: {
      backgroundColor: colors.surface,
      borderRadius: isBrutal ? 12 : 16,
      borderWidth: isBrutal ? borderWidth.medium : shape.borderWidth.card,
      borderColor: isBrutal ? colors.stroke : colors.cardBorder,
      overflow: 'hidden',
      ...(shape.isSoft && !isDark ? shape.elevation.card : {}),
    },
    formRow: {
      flexDirection: 'row',
      alignItems: 'center',
      paddingHorizontal: 13,
      minHeight: 48,
      gap: 10,
    },
    formRowDivider: {
      borderTopWidth: StyleSheet.hairlineWidth,
      borderTopColor: colors.divider,
    },
    formRowLabel: {
      width: 66,
      fontSize: 13,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    formInput: {
      flex: 1,
      fontSize: 14.5,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
      paddingVertical: 10,
    },
    tailPrefix: {
      fontSize: 13,
      color: colors.textTertiary,
      fontFamily: typography.fontFamily.mono,
      letterSpacing: 1.5,
    },
    tailInput: {
      flex: 0,
      width: 64,
      fontFamily: typography.fontFamily.mono,
      letterSpacing: 2,
    },
    smsBadge: {
      marginLeft: 'auto',
      paddingHorizontal: 8,
      paddingVertical: 3,
      borderRadius: 99,
      backgroundColor: colors.primaryLight,
    },
    smsBadgeText: {
      fontSize: 10.5,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.primary,
    },
    /* 非银行卡内联图标芯片 */
    brandPillsRow: {
      flexDirection: 'row',
      gap: 8,
    },
    brandPill: {
      flex: 1,
      height: 42,
      borderRadius: isBrutal ? 10 : 12,
      backgroundColor: colors.surface,
      borderWidth: isBrutal ? borderWidth.thin : 1.5,
      borderColor: isBrutal
        ? colors.stroke
        : shape.isSoft && !isDark
          ? '#E6E9F0'
          : colors.cardBorder,
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'center',
      gap: 6,
    },
    brandPillActive: {
      borderColor: isBrutal ? colors.stroke : colors.primary,
      backgroundColor: colors.primaryLight,
    },
    brandPillText: {
      fontSize: 12.5,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    brandPillTextActive: {
      color: colors.primary,
    },
    /* 初始余额大字输入卡 */
    amountCard: {
      backgroundColor: colors.surface,
      borderRadius: isBrutal ? 12 : 16,
      borderWidth: isBrutal ? borderWidth.medium : shape.borderWidth.card,
      borderColor: isBrutal ? colors.stroke : colors.cardBorder,
      paddingHorizontal: 14,
      paddingVertical: 12,
      ...(shape.isSoft && !isDark ? shape.elevation.card : {}),
    },
    amountInputRow: {
      flexDirection: 'row',
      alignItems: 'baseline',
      gap: 6,
    },
    amountSymbol: {
      fontSize: 22,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts
        ? HOGWARTS_FONTS.numeric
        : shape.isSoft
          ? undefined
          : typography.fontFamily.mono,
      color: colors.textSecondary,
    },
    amountBigInput: {
      flex: 1,
      fontSize: 26,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts
        ? HOGWARTS_FONTS.numeric
        : shape.isSoft
          ? undefined
          : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
      color: colors.textPrimary,
      paddingVertical: 2,
    },
    amountHelper: {
      marginTop: 6,
      fontSize: 11.5,
      lineHeight: 16,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
    amountHelperDebt: {
      color: colors.expense,
    },
    /* 全部银行搜索目录 */
    searchWrap: {
      paddingHorizontal: 16,
      paddingBottom: 8,
    },
    searchBox: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 8,
      paddingHorizontal: 12,
      height: 42,
      borderRadius: 12,
      backgroundColor: colors.surfaceMuted,
      borderWidth: isBrutal ? borderWidth.thin : 0,
      borderColor: colors.stroke,
    },
    searchInput: {
      flex: 1,
      fontSize: 14,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
      paddingVertical: 0,
    },
    bankDirScroll: {
      maxHeight: 440,
    },
    bankDirContent: {
      paddingHorizontal: 12,
      gap: 2,
    },
    bankDirItem: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 11,
      paddingHorizontal: 10,
      paddingVertical: 10,
      borderRadius: 13,
    },
    bankDirItemSelected: {
      backgroundColor: colors.primaryLight,
    },
    bankDirLogoBox: {
      width: 36,
      height: 36,
      borderRadius: 10,
      backgroundColor: colors.surfaceMuted,
      alignItems: 'center',
      justifyContent: 'center',
    },
    bankDirTextWrap: {
      flex: 1,
    },
    bankDirName: {
      fontSize: 14.5,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    bankDirShort: {
      marginTop: 2,
      fontSize: 11.5,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
    emptySearchBox: {
      paddingVertical: 32,
      alignItems: 'center',
    },
    emptySearchText: {
      fontSize: 13,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
    /* 底部主按钮 */
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

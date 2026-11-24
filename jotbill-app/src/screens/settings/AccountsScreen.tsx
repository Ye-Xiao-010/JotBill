import React, { useCallback, useMemo, useState } from 'react';
import {
  Alert,
  Modal,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Check, ChevronLeft, CreditCard, Pencil, Plus, SlidersHorizontal } from 'lucide-react-native';
import {
  nativeData,
  type Account,
  type AccountInput,
  type AccountSubtype,
  type AccountUpdateInput,
} from '../../native';
import AccountIcon from '../../components/business/AccountIcon';
import { BANKS, resolveAccountIcon } from '../../constants/accountIcons';
import { QUERY_KEYS } from '../../lib/queryClient';
import { borderRadius, borderWidth, shadow, spacing } from '../../theme/spacing';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { HOGWARTS_FONTS } from '../../constants/hogwartsAssets';
import { useStyles } from '../../hooks';
import { SafeAreaView } from 'react-native-safe-area-context';
import { typography } from '../../theme';

interface AccountOption {
  label: string;
  subtype: AccountSubtype;
  kind: Account['kind'];
}

const ACCOUNT_OPTIONS: AccountOption[] = [
  { label: '现金', subtype: 'cash', kind: 'asset' },
  { label: '电子钱包', subtype: 'e_wallet', kind: 'asset' },
  { label: '银行借记卡', subtype: 'bank_debit', kind: 'asset' },
  { label: '信用卡', subtype: 'credit_card', kind: 'liability' },
  { label: '消费信贷', subtype: 'consumer_loan', kind: 'liability' },
  { label: '其他账户', subtype: 'other', kind: 'asset' },
];

function formatMoney(amountMinor: number, kind: Account['kind']): string {
  const amount = Math.abs(amountMinor / 100).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  if (amountMinor === 0) return '¥0.00';
  return kind === 'liability' || amountMinor < 0 ? `-¥${amount}` : `¥${amount}`;
}

function parseMinorInput(value: string): number | null {
  const normalized = value.trim();
  if (!normalized) return null;
  const amount = Number(normalized);
  if (!Number.isFinite(amount)) return null;
  const minor = Math.round(amount * 100);
  return Number.isSafeInteger(minor) ? minor : null;
}

export default function AccountsScreen() {
  const navigation = useNavigation();
  const queryClient = useQueryClient();
  const styles = useStyles(createStyles);
  const [showForm, setShowForm] = useState(false);
  const [showCalibration, setShowCalibration] = useState(false);
  const [editingAccount, setEditingAccount] = useState<Account | null>(null);
  const [calibrationAccount, setCalibrationAccount] = useState<Account | null>(null);
  const [name, setName] = useState('');
  const [subtype, setSubtype] = useState<AccountSubtype>('cash');
  const [initialBalance, setInitialBalance] = useState('');
  const [actualBalance, setActualBalance] = useState('');
  const [calibrationNote, setCalibrationNote] = useState('');
  /** 图标：null = 按名称自动匹配；'bank:<code>' / 'brand:<code>' = 用户手动指定 */
  const [iconOverride, setIconOverride] = useState<string | null>(null);
  /** 用户是否动过图标选择器 —— 没动过时编辑不写 icon 字段，避免误清旧 emoji */
  const [iconTouched, setIconTouched] = useState(false);
  const [showIconPicker, setShowIconPicker] = useState(false);
  const [showBankPicker, setShowBankPicker] = useState(false);
  /** 银行卡尾号（后 4 位）：填写后银行短信按尾号精准匹配账户 */
  const [cardTail, setCardTail] = useState('');

  /** 银行卡类账户（借记卡/信用卡）：走银行列表选择，而非自由输入 */
  const isBankCardType = subtype === 'bank_debit' || subtype === 'credit_card';

  const selectedBank = useMemo(() => {
    // 显式指定优先；未指定时按名称自动匹配（编辑老账户 name=招商银行也能显示已选银行）
    const match = resolveAccountIcon(iconOverride, name);
    if (!match || match.kind !== 'bank') return null;
    return BANKS.find(bank => bank.code === match.code) ?? null;
  }, [iconOverride, name]);

  const accountsQuery = useQuery({
    queryKey: QUERY_KEYS.accountsList,
    queryFn: () => nativeData.getAccounts(),
  });
  const netWorthQuery = useQuery({
    queryKey: QUERY_KEYS.accountsBalance,
    queryFn: () => nativeData.getNetWorth(),
  });
  // B4：余额一致性提示 —— 进入账户页实时重算，命中时展示横幅并指向「校准余额」
  const reconcileQuery = useQuery({
    queryKey: ['balance-reconcile-warnings'],
    queryFn: () => nativeData.getBalanceReconcileWarnings(),
  });
  const refetchAccounts = accountsQuery.refetch;
  const refetchNetWorth = netWorthQuery.refetch;
  const refetchReconcile = reconcileQuery.refetch;

  const selectedOption = useMemo(
    () => ACCOUNT_OPTIONS.find((option) => option.subtype === subtype) ?? ACCOUNT_OPTIONS[0],
    [subtype],
  );

  const invalidateAccounts = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: QUERY_KEYS.accountsList });
    queryClient.invalidateQueries({ queryKey: QUERY_KEYS.accountsBalance });
    queryClient.invalidateQueries({ queryKey: ['net-worth'] });
    queryClient.invalidateQueries({ queryKey: ['asset-trend'] });
    queryClient.invalidateQueries({ queryKey: ['balance-reconcile-warnings'] });
  }, [queryClient]);

  const saveMutation = useMutation({
    mutationFn: ({ id, input }: { id?: string; input: AccountInput | AccountUpdateInput }) =>
      id ? nativeData.updateAccount(id, input) : nativeData.addAccount(input as AccountInput),
    onSuccess: () => {
      invalidateAccounts();
      closeForm();
    },
    onError: (error: Error) => Alert.alert('保存失败', error.message),
  });

  const activeMutation = useMutation({
    mutationFn: ({ id, isActive }: { id: string; isActive: boolean }) =>
      nativeData.updateAccount(id, { isActive }),
    onSuccess: invalidateAccounts,
    onError: (error: Error) => Alert.alert('更新失败', error.message),
  });

  const calibrationMutation = useMutation({
    mutationFn: ({ id, amountMinor, note }: { id: string; amountMinor: number; note?: string }) =>
      nativeData.calibrateAccountBalance(id, amountMinor, note),
    onSuccess: () => {
      invalidateAccounts();
      setShowCalibration(false);
      setCalibrationAccount(null);
      setActualBalance('');
      setCalibrationNote('');
    },
    onError: (error: Error) => Alert.alert('校准失败', error.message),
  });

  const openCreate = () => {
    setEditingAccount(null);
    setName('');
    setSubtype('cash');
    setInitialBalance('');
    setIconOverride(null);
    setIconTouched(false);
    setCardTail('');
    setShowForm(true);
  };

  const openEdit = (account: Account) => {
    setEditingAccount(account);
    setName(account.name);
    setSubtype(account.subtype);
    setInitialBalance('');
    setIconOverride(
      account.icon && account.icon.includes(':') ? account.icon : null,
    );
    setIconTouched(false);
    setCardTail(account.cardTail ?? '');
    setShowForm(true);
  };

  const closeForm = () => {
    setShowForm(false);
    setEditingAccount(null);
    setIconOverride(null);
    setIconTouched(false);
    setCardTail('');
    setShowBankPicker(false);
  };

  /** 选中银行：带出名称（用户没改过名时）与图标。 */
  const selectBank = (bank: (typeof BANKS)[number]) => {
    setIconOverride(`bank:${bank.code}`);
    setIconTouched(true);
    // 名称用简称（「工商银行」比「中国工商银行」更适合做账户名）；
    // 名称留空或仍是别的银行名时跟随切换，用户自定义过的名字不覆盖
    setName(prev => {
      const trimmed = prev.trim();
      const isPrevBankName =
        !trimmed ||
        BANKS.some(b => b.code !== bank.code && (b.name === trimmed || b.shortName === trimmed));
      return isPrevBankName ? bank.shortName : prev;
    });
    setShowBankPicker(false);
  };

  const submitForm = () => {
    if (!name.trim()) {
      Alert.alert('提示', '请输入账户名称');
      return;
    }
    const trimmedTail = cardTail.trim();
    if (isBankCardType && trimmedTail && !/^\d{4}$/.test(trimmedTail)) {
      Alert.alert('提示', '卡尾号请填写后 4 位数字');
      return;
    }
    if (editingAccount) {
      saveMutation.mutate({
        id: editingAccount.id,
        // 只有用户动过图标选择器才写 icon：未动过时省略该字段（旧 emoji 原样保留），
        // 动过且选了「自动识别」时显式传 null 清除用户指定。
        input: {
          name: name.trim(),
          subtype,
          ...(iconTouched ? { icon: iconOverride } : {}),
          ...(isBankCardType ? { cardTail: trimmedTail || null } : {}),
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
    const normalizedInitialBalance = selectedOption.kind === 'liability'
      ? -Math.abs(amountMinor)
      : amountMinor;
    saveMutation.mutate({
      input: {
        name: name.trim(),
        subtype,
        kind: selectedOption.kind,
        initialBalanceMinor: normalizedInitialBalance,
        currency: 'CNY',
        icon: iconOverride,
        cardTail: isBankCardType ? (trimmedTail || null) : null,
      },
    });
  };

  const openCalibration = (account: Account) => {
    setCalibrationAccount(account);
    setActualBalance((account.currentBalanceMinor / 100).toFixed(2));
    setCalibrationNote('');
    setShowCalibration(true);
  };

  const submitCalibration = () => {
    if (!calibrationAccount) return;
    const amountMinor = parseMinorInput(actualBalance);
    if (amountMinor === null) {
      Alert.alert('提示', '请输入有效余额');
      return;
    }
    calibrationMutation.mutate({
      id: calibrationAccount.id,
      amountMinor,
      note: calibrationNote.trim() || undefined,
    });
  };

  const onRefresh = useCallback(async () => {
    await Promise.all([refetchAccounts(), refetchNetWorth(), refetchReconcile()]);
  }, [refetchAccounts, refetchNetWorth, refetchReconcile]);

  const accounts = accountsQuery.data ?? [];
  // B4：不一致账户清单（含名称解析，账户可能被停用但仍有余额）
  const reconcileMismatches = reconcileQuery.data?.mismatches ?? [];
  const reconcileRows = reconcileMismatches.map((mismatch) => ({
    mismatch,
    name: accounts.find((account) => account.id === mismatch.accountId)?.name ?? '未知账户',
  }));

  useFocusEffect(
    useCallback(() => {
      void refetchAccounts();
      void refetchNetWorth();
      void refetchReconcile();
    }, [refetchAccounts, refetchNetWorth, refetchReconcile]),
  );

  return (
    <SafeAreaView style={styles.container} edges={['top', 'bottom']}>
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} style={styles.iconButton}>
          <ChevronLeft size={24} color={styles._colors.textPrimary} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>账户管理</Text>
        <TouchableOpacity onPress={openCreate} style={styles.iconButton}>
          <Plus size={22} color={styles._colors.textPrimary} strokeWidth={3} />
        </TouchableOpacity>
      </View>

      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.content}
        refreshControl={
          <RefreshControl
            refreshing={accountsQuery.isFetching || netWorthQuery.isFetching}
            onRefresh={onRefresh}
          />
        }
      >
        <View style={styles.summaryCard}>
          <Text style={styles.summaryLabel}>净资产</Text>
          <Text style={styles.summaryAmount}>
            ¥{((netWorthQuery.data?.netWorthMinor ?? 0) / 100).toLocaleString('zh-CN', {
              minimumFractionDigits: 2,
              maximumFractionDigits: 2,
            })}
          </Text>
          <View style={styles.summaryRow}>
            <Text style={styles.summaryMeta}>资产 {formatMoney(netWorthQuery.data?.totalAssetsMinor ?? 0, 'asset')}</Text>
            <Text style={styles.summaryMeta}>负债 {formatMoney(netWorthQuery.data?.totalLiabilitiesMinor ?? 0, 'liability')}</Text>
          </View>
        </View>

        {reconcileRows.length > 0 && (
          <View style={styles.warningCard}>
            <Text style={styles.warningTitle}>余额与账单汇总不一致</Text>
            {reconcileRows.map(({ mismatch, name }) => (
              <Text key={mismatch.accountId} style={styles.warningText}>
                {name}：账面比账单{mismatch.diffMinor >= 0 ? '多' : '少'} {formatMoney(Math.abs(mismatch.diffMinor), 'asset')}
              </Text>
            ))}
            <Text style={styles.warningHint}>请核对后使用对应账户的「校准余额」修正</Text>
          </View>
        )}

        {accounts.length === 0 ? (
          <View style={styles.emptyState}>
            <CreditCard size={40} color={styles._colors.textTertiary} />
            <Text style={styles.emptyTitle}>暂无账户</Text>
            <Text style={styles.emptySubtitle}>添加账户后，净资产会按余额计算</Text>
            <TouchableOpacity style={styles.primaryButton} onPress={openCreate}>
              <Plus size={18} color="#FFFFFF" />
              <Text style={styles.primaryButtonText}>添加账户</Text>
            </TouchableOpacity>
          </View>
        ) : (
          accounts.map((account) => (
            <View key={account.id} style={[styles.accountCard, !account.isActive && styles.inactiveCard]}>
              <View style={styles.accountTopRow}>
                <View style={styles.accountIcon}>
                  <AccountIcon
                    icon={account.icon}
                    name={account.name}
                    subtype={account.subtype}
                    size={24}
                  />
                </View>
                <View style={styles.accountMain}>
                  <Text style={styles.accountName} numberOfLines={1}>{account.name}</Text>
                  <Text style={styles.accountType}>
                    {ACCOUNT_OPTIONS.find((option) => option.subtype === account.subtype)?.label ?? account.subtype}
                    {!account.isActive ? ' · 已停用' : ''}
                  </Text>
                </View>
                <Text style={[styles.accountBalance, account.kind === 'liability' && styles.liabilityText]}>
                  {formatMoney(account.currentBalanceMinor, account.kind)}
                </Text>
              </View>
              <View style={styles.accountActions}>
                <TouchableOpacity style={styles.actionButton} onPress={() => openCalibration(account)}>
                  <SlidersHorizontal size={16} color={styles._colors.textPrimary} />
                  <Text style={styles.actionText}>校准余额</Text>
                </TouchableOpacity>
                <TouchableOpacity style={styles.actionButton} onPress={() => openEdit(account)}>
                  <Pencil size={16} color={styles._colors.textPrimary} />
                  <Text style={styles.actionText}>编辑</Text>
                </TouchableOpacity>
                <TouchableOpacity
                  style={styles.actionButton}
                  onPress={() => activeMutation.mutate({ id: account.id, isActive: !account.isActive })}
                >
                  <Check size={16} color={account.isActive ? styles._colors.error : styles._colors.success} />
                  <Text style={styles.actionText}>{account.isActive ? '停用' : '启用'}</Text>
                </TouchableOpacity>
              </View>
            </View>
          ))
        )}
      </ScrollView>

      <Modal visible={showForm} transparent animationType="fade" onRequestClose={closeForm}>
        <View style={styles.modalOverlay}>
          <ScrollView contentContainerStyle={styles.modalScroll} keyboardShouldPersistTaps="handled">
            <View style={styles.modalCard}>
              <Text style={styles.modalTitle}>{editingAccount ? '编辑账户' : '添加账户'}</Text>
              <Text style={styles.inputLabel}>账户类型</Text>
              <View style={styles.optionGrid}>
                {ACCOUNT_OPTIONS.map((option) => (
                  <TouchableOpacity
                    key={option.subtype}
                    style={[styles.option, subtype === option.subtype && styles.optionSelected]}
                    onPress={() => {
                      setSubtype(option.subtype);
                      // 银行卡类：立即弹银行列表（图标 + 银行名），而不是让用户手输
                      if (option.subtype === 'bank_debit' || option.subtype === 'credit_card') {
                        setShowBankPicker(true);
                        return;
                      }
                      // 切到非银行卡类型时，清掉之前选的银行图标（现金账户带招行 logo 很怪）
                      if (iconOverride?.startsWith('bank:')) {
                        setIconOverride(null);
                        setIconTouched(true);
                      }
                    }}
                  >
                    <Text style={[styles.optionText, subtype === option.subtype && styles.optionTextSelected]}>
                      {option.label}
                    </Text>
                    <Text style={styles.optionKind}>{option.kind === 'asset' ? '资产' : '负债'}</Text>
                  </TouchableOpacity>
                ))}
              </View>
              {isBankCardType ? (
                <>
                  <Text style={styles.inputLabel}>发卡银行</Text>
                  <TouchableOpacity
                    style={styles.bankEntry}
                    onPress={() => setShowBankPicker(true)}
                  >
                    {selectedBank ? (
                      <>
                        <AccountIcon icon={`bank:${selectedBank.code}`} size={22} />
                        <Text style={styles.bankEntryName}>{selectedBank.name}</Text>
                        <Text style={styles.bankEntryAction}>更换</Text>
                      </>
                    ) : (
                      <Text style={styles.bankEntryPlaceholder}>选择或手动指定图标</Text>
                    )}
                  </TouchableOpacity>
                  <Text style={styles.inputLabel}>卡尾号（后 4 位，可选）</Text>
                  <TextInput
                    style={styles.input}
                    value={cardTail}
                    onChangeText={setCardTail}
                    placeholder="如 2510，用于银行短信自动匹配账户"
                    placeholderTextColor={styles._colors.textTertiary}
                    keyboardType="number-pad"
                    maxLength={4}
                  />
                </>
              ) : null}
              <Text style={styles.inputLabel}>账户名称</Text>
              <View style={styles.nameRow}>
                <View style={styles.nameIconPreview}>
                  <AccountIcon
                    // 未动过图标时展示账户现有 icon（含旧 emoji），与保存后的实际效果一致
                    icon={iconTouched ? iconOverride : (editingAccount?.icon ?? null)}
                    name={name}
                    subtype={subtype}
                    size={22}
                  />
                </View>
                <TextInput
                  style={[styles.input, styles.nameInput]}
                  value={name}
                  onChangeText={setName}
                  placeholder={isBankCardType ? '选择银行后自动带出，可修改' : '例如：现金钱包'}
                  placeholderTextColor={styles._colors.textTertiary}
                />
              </View>
              {!isBankCardType ? (
                <TouchableOpacity
                  style={styles.iconPickerEntry}
                  onPress={() => setShowIconPicker(true)}
                >
                  <Text style={styles.iconPickerEntryText}>
                    {iconOverride
                      ? '已手动指定图标，点击更换'
                      : '图标按名称自动识别，点击手动指定'}
                  </Text>
                </TouchableOpacity>
              ) : null}
              {!editingAccount && (
                <>
                  <Text style={styles.inputLabel}>初始余额</Text>
                  <TextInput
                    style={styles.input}
                    value={initialBalance}
                    onChangeText={setInitialBalance}
                    placeholder="0.00"
                    placeholderTextColor={styles._colors.textTertiary}
                    keyboardType="decimal-pad"
                  />
                </>
              )}
              <View style={styles.modalActions}>
                <TouchableOpacity style={styles.secondaryButton} onPress={closeForm}>
                  <Text style={styles.secondaryButtonText}>取消</Text>
                </TouchableOpacity>
                <TouchableOpacity style={styles.primaryButton} onPress={submitForm}>
                  <Text style={styles.primaryButtonText}>{editingAccount ? '保存' : '创建'}</Text>
                </TouchableOpacity>
              </View>
            </View>
          </ScrollView>
        </View>
      </Modal>

      <Modal
        visible={showBankPicker}
        transparent
        animationType="fade"
        onRequestClose={() => setShowBankPicker(false)}
      >
        <View style={styles.modalOverlay}>
          <View style={styles.modalCard}>
            <Text style={styles.modalTitle}>选择发卡银行</Text>
            <ScrollView style={styles.bankListScroll}>
              {BANKS.map(bank => (
                <TouchableOpacity
                  key={bank.code}
                  style={[
                    styles.bankListItem,
                    selectedBank?.code === bank.code && styles.bankListItemSelected,
                  ]}
                  onPress={() => selectBank(bank)}
                >
                  <AccountIcon icon={`bank:${bank.code}`} size={30} />
                  <View style={styles.bankListText}>
                    <Text style={styles.bankListName}>{bank.name}</Text>
                    {bank.shortName !== bank.name ? (
                      <Text style={styles.bankListShort}>{bank.shortName}</Text>
                    ) : null}
                  </View>
                  {selectedBank?.code === bank.code ? (
                    <Check size={18} color={styles._colors.primary} />
                  ) : null}
                </TouchableOpacity>
              ))}
            </ScrollView>
            <View style={styles.modalActions}>
              <TouchableOpacity
                style={styles.secondaryButton}
                onPress={() => setShowBankPicker(false)}
              >
                <Text style={styles.secondaryButtonText}>取消</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>

      <Modal
        visible={showIconPicker}
        transparent
        animationType="fade"
        onRequestClose={() => setShowIconPicker(false)}
      >
        <View style={styles.modalOverlay}>
          <View style={styles.modalCard}>
            <Text style={styles.modalTitle}>选择图标</Text>
            <TouchableOpacity
              style={[styles.iconPickerAuto, iconOverride === null && styles.iconPickerItemSelected]}
              onPress={() => {
                setIconOverride(null);
                setIconTouched(true);
                setShowIconPicker(false);
              }}
            >
              <Text style={styles.iconPickerEntryText}>自动识别（按账户名称）</Text>
            </TouchableOpacity>
            <ScrollView style={styles.iconPickerScroll} contentContainerStyle={styles.iconPickerGrid}>
              {[
                { code: 'wechat', label: '微信' },
                { code: 'alipay', label: '支付宝' },
              ].map(brand => (
                <TouchableOpacity
                  key={brand.code}
                  style={[
                    styles.iconPickerItem,
                    iconOverride === `brand:${brand.code}` && styles.iconPickerItemSelected,
                  ]}
                  onPress={() => {
                    setIconOverride(`brand:${brand.code}`);
                    setIconTouched(true);
                    setShowIconPicker(false);
                  }}
                >
                  <AccountIcon icon={`brand:${brand.code}`} size={28} />
                  <Text style={styles.iconPickerItemText} numberOfLines={1}>
                    {brand.label}
                  </Text>
                </TouchableOpacity>
              ))}
            </ScrollView>
            <TouchableOpacity
              style={styles.secondaryButton}
              onPress={() => setShowIconPicker(false)}
            >
              <Text style={styles.secondaryButtonText}>关闭</Text>
            </TouchableOpacity>
          </View>
        </View>
      </Modal>

      <Modal
        visible={showCalibration}
        transparent
        animationType="fade"
        onRequestClose={() => setShowCalibration(false)}
      >
        <View style={styles.modalOverlay}>
          <View style={styles.modalCard}>
            <Text style={styles.modalTitle}>校准余额</Text>
            <Text style={styles.modalHint}>{calibrationAccount?.name}</Text>
            <Text style={styles.inputLabel}>实际余额</Text>
            <TextInput
              style={styles.input}
              value={actualBalance}
              onChangeText={setActualBalance}
              keyboardType="decimal-pad"
              placeholder="0.00"
              placeholderTextColor={styles._colors.textTertiary}
            />
            <Text style={styles.inputLabel}>备注（可选）</Text>
            <TextInput
              style={styles.input}
              value={calibrationNote}
              onChangeText={setCalibrationNote}
              placeholder="例如：月末对账"
              placeholderTextColor={styles._colors.textTertiary}
            />
            <View style={styles.modalActions}>
              <TouchableOpacity style={styles.secondaryButton} onPress={() => setShowCalibration(false)}>
                <Text style={styles.secondaryButtonText}>取消</Text>
              </TouchableOpacity>
              <TouchableOpacity style={styles.primaryButton} onPress={submitCalibration}>
                <Text style={styles.primaryButtonText}>保存校准</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>
    </SafeAreaView>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    container: { flex: 1, backgroundColor: colors.background },
    header: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingHorizontal: spacing.md,
      paddingTop: spacing.xl,
      paddingBottom: spacing.md,
      backgroundColor: colors.surface,
      borderBottomWidth: shape.isHogwarts ? shape.borderWidth.subtle : borderWidth.medium,
      borderBottomColor: shape.isHogwarts ? colors.divider : colors.stroke,
    },
    iconButton: { width: 40, height: 40, alignItems: 'center', justifyContent: 'center' },
    headerTitle: {
      fontSize: 20,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
    },
    scrollView: { flex: 1 },
    content: { padding: spacing.lg, gap: spacing.md, paddingBottom: spacing.xl },
    summaryCard: {
      padding: spacing.lg,
      backgroundColor: colors.surface,
      borderWidth: shape.isHogwarts ? shape.borderWidth.card : borderWidth.medium,
      borderColor: shape.isHogwarts ? colors.accent : colors.stroke,
      borderRadius: shape.radius.card,
      ...(shape.isHogwarts ? shape.elevation.card : shadow.small),
    },
    summaryLabel: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    summaryAmount: {
      marginTop: spacing.xs,
      fontSize: 30,
      fontWeight: shape.isHogwarts ? undefined : '900',
      color: colors.textPrimary,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.numeric : typography.fontFamily.mono,
    },
    summaryRow: { flexDirection: 'row', justifyContent: 'space-between', marginTop: spacing.md },
    summaryMeta: {
      fontSize: 13,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    // B4：余额不一致提示横幅（沿用积木风卡片，语义色 error）
    warningCard: {
      padding: spacing.lg,
      backgroundColor: colors.surface,
      borderWidth: shape.isHogwarts ? shape.borderWidth.card : borderWidth.medium,
      borderColor: colors.error,
      borderRadius: shape.radius.card,
      ...(shape.isHogwarts ? shape.elevation.card : shadow.small),
    },
    warningTitle: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.error,
    },
    warningText: {
      marginTop: spacing.xs,
      fontSize: 13,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    warningHint: {
      marginTop: spacing.sm,
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    accountCard: {
      padding: spacing.md,
      backgroundColor: colors.surface,
      borderWidth: shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      borderRadius: shape.radius.card,
      ...(shape.isHogwarts ? shape.elevation.card : {}),
    },
    inactiveCard: { opacity: 0.6 },
    accountTopRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.md },
    accountIcon: {
      width: 44,
      height: 44,
      alignItems: 'center',
      justifyContent: 'center',
      backgroundColor: colors.primaryLight,
      borderWidth: shape.borderWidth.subtle,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      borderRadius: shape.isHogwarts ? borderRadius.large : borderRadius.small,
    },
    accountMain: { flex: 1 },
    accountName: {
      fontSize: 16,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    accountType: {
      marginTop: 3,
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    accountBalance: {
      fontSize: 16,
      fontWeight: shape.isHogwarts ? undefined : '800',
      color: colors.success,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.numeric : typography.fontFamily.mono,
    },
    liabilityText: { color: colors.error },
    accountActions: { flexDirection: 'row', gap: spacing.sm, marginTop: spacing.md },
    actionButton: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.xs,
      paddingVertical: spacing.sm,
      paddingHorizontal: spacing.sm,
      borderWidth: shape.borderWidth.subtle,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.divider,
      borderRadius: shape.radius.badge,
    },
    actionText: {
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    emptyState: { alignItems: 'center', paddingVertical: spacing.xl * 2, gap: spacing.sm },
    emptyTitle: {
      fontSize: 18,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
    },
    emptySubtitle: {
      fontSize: 13,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    primaryButton: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'center',
      gap: spacing.xs,
      paddingVertical: spacing.md,
      paddingHorizontal: spacing.lg,
      backgroundColor: colors.primary,
      borderWidth: shape.isHogwarts ? shape.borderWidth.card : borderWidth.medium,
      borderColor: shape.isHogwarts ? colors.accent : colors.stroke,
      borderRadius: shape.radius.button,
      ...(shape.isHogwarts ? shape.elevation.card : shadow.small),
    },
    primaryButtonText: {
      color: '#FFFFFF',
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
    },
    secondaryButton: {
      flex: 1,
      alignItems: 'center',
      justifyContent: 'center',
      paddingVertical: spacing.md,
      borderWidth: shape.borderWidth.card,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      borderRadius: shape.radius.button,
    },
    secondaryButtonText: {
      color: colors.textPrimary,
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
    },
    modalOverlay: { flex: 1, justifyContent: 'center', padding: spacing.lg, backgroundColor: 'rgba(0,0,0,0.45)' },
    modalScroll: { flexGrow: 1, justifyContent: 'center' },
    modalCard: {
      padding: spacing.lg,
      backgroundColor: colors.surface,
      borderWidth: shape.isHogwarts ? shape.borderWidth.strong : borderWidth.medium,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      borderRadius: shape.radius.modal,
      ...(shape.isHogwarts ? shape.elevation.floating : shadow.medium),
    },
    modalTitle: {
      fontSize: 20,
      fontWeight: shape.isHogwarts ? undefined : '900',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
      marginBottom: spacing.md,
    },
    modalHint: {
      fontSize: 13,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
      marginBottom: spacing.md,
    },
    inputLabel: {
      marginTop: spacing.sm,
      marginBottom: spacing.xs,
      fontSize: 13,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    input: {
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.md,
      fontSize: 15,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
      backgroundColor: colors.background,
      borderWidth: shape.borderWidth.subtle,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      borderRadius: shape.radius.input,
    },
    optionGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
    option: {
      width: '31%',
      minHeight: 58,
      alignItems: 'center',
      justifyContent: 'center',
      padding: spacing.xs,
      borderWidth: shape.borderWidth.subtle,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.divider,
      borderRadius: shape.radius.button,
    },
    optionSelected: {
      backgroundColor: colors.primaryLight,
      borderColor: shape.isHogwarts ? colors.accent : colors.stroke,
      borderWidth: shape.isHogwarts ? 1.5 : borderWidth.medium,
    },
    optionText: {
      fontSize: 13,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
      textAlign: 'center',
    },
    optionTextSelected: { color: colors.primary },
    optionKind: {
      marginTop: 3,
      fontSize: 11,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    modalActions: { flexDirection: 'row', gap: spacing.md, marginTop: spacing.lg },
    nameRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
    nameIconPreview: {
      width: 48,
      height: 48,
      alignItems: 'center',
      justifyContent: 'center',
      borderWidth: shape.borderWidth.subtle,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      borderRadius: shape.radius.input,
      backgroundColor: colors.surface,
    },
    nameInput: { flex: 1 },
    iconPickerEntry: { marginTop: spacing.xs, paddingVertical: spacing.xs },
    iconPickerEntryText: {
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.primary,
    },
    iconPickerAuto: {
      paddingVertical: spacing.sm,
      paddingHorizontal: spacing.md,
      marginTop: spacing.sm,
      borderWidth: shape.borderWidth.subtle,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      borderRadius: shape.radius.button,
    },
    iconPickerScroll: { maxHeight: 320, marginTop: spacing.sm },
    iconPickerGrid: {
      flexDirection: 'row',
      flexWrap: 'wrap',
      gap: spacing.sm,
      paddingBottom: spacing.sm,
    },
    iconPickerItem: {
      width: '22%',
      minHeight: 70,
      alignItems: 'center',
      justifyContent: 'center',
      gap: 4,
      paddingVertical: spacing.xs,
      borderWidth: shape.borderWidth.subtle,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.divider,
      borderRadius: shape.radius.button,
    },
    iconPickerItemSelected: {
      backgroundColor: colors.primaryLight,
      borderColor: shape.isHogwarts ? colors.accent : colors.stroke,
      borderWidth: shape.isHogwarts ? 1.5 : borderWidth.medium,
    },
    iconPickerItemText: {
      fontSize: 10,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
      textAlign: 'center',
    },
    bankEntry: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.sm,
      borderWidth: shape.borderWidth.subtle,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      borderRadius: shape.radius.input,
      backgroundColor: colors.surface,
    },
    bankEntryName: {
      flex: 1,
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    bankEntryAction: {
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.primary,
    },
    bankEntryPlaceholder: {
      flex: 1,
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
    bankListScroll: { maxHeight: 400, marginTop: spacing.sm },
    bankListItem: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.md,
      paddingVertical: spacing.sm,
      paddingHorizontal: spacing.xs,
      borderBottomWidth: shape.borderWidth.subtle,
      borderBottomColor: colors.divider,
    },
    bankListItemSelected: { backgroundColor: colors.primaryLight },
    bankListText: { flex: 1 },
    bankListName: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    bankListShort: {
      marginTop: 2,
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
  }),
  _colors: colors,
});

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
import { useNavigation } from '@react-navigation/native';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Check, ChevronLeft, CreditCard, Pencil, Plus, SlidersHorizontal } from 'lucide-react-native';
import { nativeData, type Account, type AccountInput, type AccountUpdateInput } from '../../native';
import { QUERY_KEYS } from '../../lib/queryClient';
import { borderRadius, borderWidth, shadow, spacing } from '../../theme/spacing';
import { ThemeColors } from '../../theme/colors';
import { useStyles } from '../../hooks';

interface AccountOption {
  label: string;
  subtype: string;
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
  return kind === 'liability' || amountMinor < 0 ? `-¥${amount}` : `¥${amount}`;
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
  const [subtype, setSubtype] = useState('cash');
  const [initialBalance, setInitialBalance] = useState('');
  const [actualBalance, setActualBalance] = useState('');
  const [calibrationNote, setCalibrationNote] = useState('');

  const accountsQuery = useQuery({
    queryKey: QUERY_KEYS.accountsList,
    queryFn: () => nativeData.getAccounts(),
  });
  const netWorthQuery = useQuery({
    queryKey: QUERY_KEYS.accountsBalance,
    queryFn: () => nativeData.getNetWorth(),
  });

  const selectedOption = useMemo(
    () => ACCOUNT_OPTIONS.find((option) => option.subtype === subtype) ?? ACCOUNT_OPTIONS[0],
    [subtype],
  );

  const invalidateAccounts = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: QUERY_KEYS.accountsList });
    queryClient.invalidateQueries({ queryKey: QUERY_KEYS.accountsBalance });
    queryClient.invalidateQueries({ queryKey: ['net-worth'] });
    queryClient.invalidateQueries({ queryKey: ['asset-trend'] });
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
    setShowForm(true);
  };

  const openEdit = (account: Account) => {
    setEditingAccount(account);
    setName(account.name);
    setSubtype(account.subtype);
    setInitialBalance('');
    setShowForm(true);
  };

  const closeForm = () => {
    setShowForm(false);
    setEditingAccount(null);
  };

  const submitForm = () => {
    if (!name.trim()) {
      Alert.alert('提示', '请输入账户名称');
      return;
    }
    if (editingAccount) {
      saveMutation.mutate({
        id: editingAccount.id,
        input: { name: name.trim(), subtype },
      });
      return;
    }
    const amount = Number(initialBalance || 0);
    if (!Number.isFinite(amount)) {
      Alert.alert('提示', '请输入有效的初始余额');
      return;
    }
    saveMutation.mutate({
      input: {
        name: name.trim(),
        subtype,
        kind: selectedOption.kind,
        initialBalanceMinor: Math.round(amount * 100),
        currency: 'CNY',
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
    const amount = Number(actualBalance);
    if (!Number.isFinite(amount)) {
      Alert.alert('提示', '请输入有效余额');
      return;
    }
    calibrationMutation.mutate({
      id: calibrationAccount.id,
      amountMinor: Math.round(amount * 100),
      note: calibrationNote.trim() || undefined,
    });
  };

  const onRefresh = useCallback(async () => {
    await Promise.all([accountsQuery.refetch(), netWorthQuery.refetch()]);
  }, [accountsQuery, netWorthQuery]);

  const accounts = accountsQuery.data ?? [];

  return (
    <View style={styles.container}>
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
                  <CreditCard size={22} color={styles._colors.primary} />
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
              <Text style={styles.inputLabel}>账户名称</Text>
              <TextInput
                style={styles.input}
                value={name}
                onChangeText={setName}
                placeholder="例如：日常银行卡"
                placeholderTextColor={styles._colors.textTertiary}
              />
              <Text style={styles.inputLabel}>账户类型</Text>
              <View style={styles.optionGrid}>
                {ACCOUNT_OPTIONS.map((option) => (
                  <TouchableOpacity
                    key={option.subtype}
                    style={[styles.option, subtype === option.subtype && styles.optionSelected]}
                    onPress={() => setSubtype(option.subtype)}
                  >
                    <Text style={[styles.optionText, subtype === option.subtype && styles.optionTextSelected]}>
                      {option.label}
                    </Text>
                    <Text style={styles.optionKind}>{option.kind === 'asset' ? '资产' : '负债'}</Text>
                  </TouchableOpacity>
                ))}
              </View>
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
    </View>
  );
}

const createStyles = (colors: ThemeColors) => ({
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
      borderBottomWidth: borderWidth.medium,
      borderBottomColor: colors.stroke,
    },
    iconButton: { width: 40, height: 40, alignItems: 'center', justifyContent: 'center' },
    headerTitle: { fontSize: 20, fontWeight: '800', color: colors.textPrimary },
    scrollView: { flex: 1 },
    content: { padding: spacing.lg, gap: spacing.md, paddingBottom: spacing.xl },
    summaryCard: {
      padding: spacing.lg,
      backgroundColor: colors.surface,
      borderWidth: borderWidth.medium,
      borderColor: colors.stroke,
      borderRadius: borderRadius.card,
      ...shadow.small,
    },
    summaryLabel: { fontSize: 14, fontWeight: '700', color: colors.textSecondary },
    summaryAmount: { marginTop: spacing.xs, fontSize: 30, fontWeight: '900', color: colors.textPrimary, fontFamily: 'Courier' },
    summaryRow: { flexDirection: 'row', justifyContent: 'space-between', marginTop: spacing.md },
    summaryMeta: { fontSize: 13, fontWeight: '700', color: colors.textSecondary },
    accountCard: {
      padding: spacing.md,
      backgroundColor: colors.surface,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      borderRadius: borderRadius.card,
    },
    inactiveCard: { opacity: 0.6 },
    accountTopRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.md },
    accountIcon: {
      width: 44,
      height: 44,
      alignItems: 'center',
      justifyContent: 'center',
      backgroundColor: colors.primaryLight,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      borderRadius: borderRadius.small,
    },
    accountMain: { flex: 1 },
    accountName: { fontSize: 16, fontWeight: '800', color: colors.textPrimary },
    accountType: { marginTop: 3, fontSize: 12, fontWeight: '600', color: colors.textSecondary },
    accountBalance: { fontSize: 16, fontWeight: '800', color: colors.success, fontFamily: 'Courier' },
    liabilityText: { color: colors.error },
    accountActions: { flexDirection: 'row', gap: spacing.sm, marginTop: spacing.md },
    actionButton: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.xs,
      paddingVertical: spacing.sm,
      paddingHorizontal: spacing.sm,
      borderWidth: borderWidth.thin,
      borderColor: colors.divider,
      borderRadius: borderRadius.small,
    },
    actionText: { fontSize: 12, fontWeight: '700', color: colors.textPrimary },
    emptyState: { alignItems: 'center', paddingVertical: spacing.xl * 2, gap: spacing.sm },
    emptyTitle: { fontSize: 18, fontWeight: '800', color: colors.textPrimary },
    emptySubtitle: { fontSize: 13, color: colors.textSecondary },
    primaryButton: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'center',
      gap: spacing.xs,
      paddingVertical: spacing.md,
      paddingHorizontal: spacing.lg,
      backgroundColor: colors.primary,
      borderWidth: borderWidth.medium,
      borderColor: colors.stroke,
      borderRadius: borderRadius.button,
      ...shadow.small,
    },
    primaryButtonText: { color: '#FFFFFF', fontSize: 14, fontWeight: '800' },
    secondaryButton: {
      flex: 1,
      alignItems: 'center',
      justifyContent: 'center',
      paddingVertical: spacing.md,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      borderRadius: borderRadius.button,
    },
    secondaryButtonText: { color: colors.textPrimary, fontSize: 14, fontWeight: '800' },
    modalOverlay: { flex: 1, justifyContent: 'center', padding: spacing.lg, backgroundColor: 'rgba(0,0,0,0.45)' },
    modalScroll: { flexGrow: 1, justifyContent: 'center' },
    modalCard: {
      padding: spacing.lg,
      backgroundColor: colors.surface,
      borderWidth: borderWidth.medium,
      borderColor: colors.stroke,
      borderRadius: borderRadius.card,
      ...shadow.medium,
    },
    modalTitle: { fontSize: 20, fontWeight: '900', color: colors.textPrimary, marginBottom: spacing.md },
    modalHint: { fontSize: 13, fontWeight: '700', color: colors.textSecondary, marginBottom: spacing.md },
    inputLabel: { marginTop: spacing.sm, marginBottom: spacing.xs, fontSize: 13, fontWeight: '800', color: colors.textPrimary },
    input: {
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.md,
      fontSize: 15,
      color: colors.textPrimary,
      backgroundColor: colors.background,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      borderRadius: borderRadius.small,
    },
    optionGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
    option: {
      width: '31%',
      minHeight: 58,
      alignItems: 'center',
      justifyContent: 'center',
      padding: spacing.xs,
      borderWidth: borderWidth.thin,
      borderColor: colors.divider,
      borderRadius: borderRadius.small,
    },
    optionSelected: { backgroundColor: colors.primaryLight, borderColor: colors.stroke, borderWidth: borderWidth.medium },
    optionText: { fontSize: 13, fontWeight: '800', color: colors.textPrimary, textAlign: 'center' },
    optionTextSelected: { color: colors.primary },
    optionKind: { marginTop: 3, fontSize: 11, color: colors.textSecondary },
    modalActions: { flexDirection: 'row', gap: spacing.md, marginTop: spacing.lg },
  }),
  _colors: colors,
});

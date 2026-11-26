/**
 * 规则管理页 - 阶段五 T7
 * 规则列表 + 创建/编辑/启停/删除 + "应用到历史"入口。
 * 命中次数（hitCount）契约未暴露，批准前不引用（契约缺口处理说明见提交信息）。
 */
import React, { useState } from 'react';
import {
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
import {
  CheckCircle2,
  ChevronLeft,
  ListFilter,
  Pencil,
  Play,
  Plus,
  Trash2,
} from 'lucide-react-native';
import CategoryIcon from '../../components/business/CategoryIcon';
import AccountIcon from '../../components/business/AccountIcon';
import { accountDisplayName, resolveAccountIcon } from '../../constants/accountIcons';
import { HOGWARTS_FONTS } from '../../constants/hogwartsAssets';
import { nativeData, type Account, type Rule } from '../../native';
import { borderRadius, borderWidth, spacing } from '../../theme/spacing';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { useStyles, useRefreshControlProps } from '../../hooks';
import { useAlert } from '../../providers';
import { SafeAreaView } from 'react-native-safe-area-context';
import { typography } from '../../theme';

/** 条件类型到中文标签的映射（与原生 RuleEngine 条件一致）。 */
const CONDITION_LABELS: Record<string, string> = {
  platform_equals: '平台等于',
  merchant_equals: '商户等于',
  merchant_contains: '商户包含',
  description_contains: '描述包含',
  source_equals: '来源等于',
  amount_range: '金额区间（分）',
};

/** 动作类型到中文标签的映射（与原生 RuleEngine 动作一致）。 */
const ACTION_LABELS: Record<string, string> = {
  set_platform: '设置平台',
  set_merchant: '设置商户',
  set_category: '设置分类',
  set_account: '设置账户',
  ignore: '忽略不入账',
  send_to_review: '转待确认',
};

/** 动作值需要选择分类/账户的动作类型。 */
const ACTION_SELECT_CATEGORY = 'set_category';
const ACTION_SELECT_ACCOUNT = 'set_account';

/** 条件值占位提示。 */
const CONDITION_PLACEHOLDERS: Record<string, string> = {
  platform_equals: '如 com.tencent.mm',
  merchant_equals: '商户全名',
  merchant_contains: '商户关键词',
  description_contains: '描述关键词',
  source_equals: '如 notification',
  amount_range: '如 1000-5000（分，可单边）',
};

export default function RulesScreen() {
  const navigation = useNavigation();
  const queryClient = useQueryClient();
  const styles = useStyles(createStyles);
  const refreshControlProps = useRefreshControlProps();
  const { alert, confirm } = useAlert();

  const [editingRule, setEditingRule] = useState<Rule | null>(null);
  const [formVisible, setFormVisible] = useState(false);

  const query = useQuery({ queryKey: ['rules'], queryFn: () => nativeData.getRules() });
  const rules = query.data ?? [];

  /** 规则变更后失效规则列表缓存。 */
  const invalidateRules = () => queryClient.invalidateQueries({ queryKey: ['rules'] });

  const toggleMutation = useMutation({
    mutationFn: ({ rule }: { rule: Rule }) =>
      nativeData.updateRule(rule.id, { isActive: !rule.isActive }),
    onSuccess: invalidateRules,
    onError: (error, { rule }) => {
      const message = error instanceof Error ? error.message : '操作失败';
      alert('启停失败', `${rule.name}：${message}`);
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (ruleId: string) => nativeData.deleteRule(ruleId),
    onSuccess: invalidateRules,
    onError: (error) => {
      const message = error instanceof Error ? error.message : '删除失败';
      alert('删除失败', message);
    },
  });

  const applyMutation = useMutation({
    mutationFn: (ruleId: string | undefined) => nativeData.applyRulesToExisting(ruleId),
    onSuccess: (result, ruleId) => {
      invalidateRules();
      const target = ruleId ? rules.find(rule => rule.id === ruleId)?.name : '全部规则';
      alert(
        '应用完成',
        `已按${target ?? '规则'}重算 ${result.affected} 笔历史账单。`,
      );
    },
    onError: (error) => {
      const message = error instanceof Error ? error.message : '应用失败';
      alert('应用到历史失败', message);
    },
  });

  /** 打开新建规则表单。 */
  const handleCreate = () => {
    setEditingRule(null);
    setFormVisible(true);
  };

  /** 打开编辑规则表单。 */
  const handleEdit = (rule: Rule) => {
    setEditingRule(rule);
    setFormVisible(true);
  };

  /** 删除前确认，不静默删除。 */
  const handleDelete = (rule: Rule) => {
    confirm(
      '删除规则',
      `确定要删除规则"${rule.name}"吗？此操作无法撤销。`,
      () => deleteMutation.mutate(rule.id),
      undefined,
      { confirmText: '删除', destructive: true },
    );
  };

  /** 应用到历史前确认，说明影响范围。 */
  const handleApply = (rule: Rule) => {
    confirm(
      '应用到历史',
      `将按规则"${rule.name}"重算现有账单的补全字段，可能影响多笔记录。继续？`,
      () => applyMutation.mutate(rule.id),
    );
  };

  /** 全部规则应用到历史。 */
  const handleApplyAll = () => {
    confirm(
      '应用到历史',
      '将按全部启用规则重算现有账单的补全字段。继续？',
      () => applyMutation.mutate(undefined),
    );
  };

  return (
    <SafeAreaView style={styles.container} edges={['top', 'bottom']}>
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} style={styles.iconButton}>
          <ChevronLeft size={24} color={styles._colors.textPrimary} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>规则</Text>
        <TouchableOpacity onPress={handleApplyAll} style={styles.iconButton}>
          <Play size={20} color={styles._colors.primary} />
        </TouchableOpacity>
      </View>
      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.content}
        refreshControl={<RefreshControl {...refreshControlProps} refreshing={query.isFetching} onRefresh={() => query.refetch()} />}
      >
        <View style={styles.notice}>
          <Text style={styles.noticeText}>
            规则按优先级（数字小者优先）在候选生成与入账前执行；冲突时展示将应用的规则。
          </Text>
        </View>
        {rules.length === 0 ? (
          <View style={styles.emptyState}>
            <ListFilter size={40} color={styles._colors.textTertiary} />
            <Text style={styles.emptyTitle}>暂无规则</Text>
            <Text style={styles.emptyText}>创建规则后，匹配的交易将自动补全字段。</Text>
          </View>
        ) : (
          rules.map(rule => (
            <RuleCard
              key={rule.id}
              rule={rule}
              styles={styles}
              onToggle={() => toggleMutation.mutate({ rule })}
              onEdit={() => handleEdit(rule)}
              onDelete={() => handleDelete(rule)}
              onApply={() => handleApply(rule)}
              isToggling={toggleMutation.isPending && toggleMutation.variables?.rule.id === rule.id}
              isDeleting={deleteMutation.isPending && deleteMutation.variables === rule.id}
              isApplying={applyMutation.isPending && applyMutation.variables === rule.id}
            />
          ))
        )}
        <TouchableOpacity style={styles.createButton} onPress={handleCreate} activeOpacity={0.85}>
          <Plus size={18} color={styles._primaryIconColor} />
          <Text style={styles.createButtonText}>新建规则</Text>
        </TouchableOpacity>
      </ScrollView>

      <RuleFormModal
        visible={formVisible}
        rule={editingRule}
        onClose={() => setFormVisible(false)}
        onSaved={() => {
          setFormVisible(false);
          invalidateRules();
        }}
      />
    </SafeAreaView>
  );
}

/** 单条规则卡片：展示条件/动作/优先级，提供启停/编辑/删除/应用操作。 */
interface RuleCardProps {
  rule: Rule;
  styles: ReturnType<typeof createStyles>;
  onToggle: () => void;
  onEdit: () => void;
  onDelete: () => void;
  onApply: () => void;
  isToggling: boolean;
  isDeleting: boolean;
  isApplying: boolean;
}

function RuleCard({ rule, styles, onToggle, onEdit, onDelete, onApply, isToggling, isDeleting, isApplying }: RuleCardProps) {
  return (
    <View style={[styles.ruleCard, !rule.isActive && styles.ruleCardInactive]}>
      <View style={styles.ruleHeader}>
        <TouchableOpacity style={styles.ruleToggle} onPress={onToggle} disabled={isToggling} activeOpacity={0.7}>
          <CheckCircle2
            size={18}
            color={rule.isActive ? styles._colors.success : styles._colors.textTertiary}
          />
          <Text style={styles.ruleStatus}>{rule.isActive ? '启用' : '停用'}</Text>
        </TouchableOpacity>
        <Text style={styles.rulePriority}>#{rule.priority}</Text>
      </View>
      <Text style={styles.ruleName} numberOfLines={1}>{rule.name}</Text>
      <Text style={styles.ruleText}>
        当{CONDITION_LABELS[rule.condition] ?? rule.condition}：{rule.conditionValue}
      </Text>
      <Text style={styles.ruleText}>
        则{ACTION_LABELS[rule.action] ?? rule.action}
        {rule.actionValue ? `：${rule.actionValue}` : ''}
      </Text>
      <View style={styles.ruleActions}>
        <TouchableOpacity style={styles.ruleActionButton} onPress={onApply} disabled={isApplying} activeOpacity={0.85}>
          <Play size={14} color={styles._colors.primary} />
          <Text style={styles.ruleActionText}>{isApplying ? '应用中…' : '应用到历史'}</Text>
        </TouchableOpacity>
        <TouchableOpacity style={styles.ruleActionButton} onPress={onEdit} activeOpacity={0.85}>
          <Pencil size={14} color={styles._colors.textPrimary} />
          <Text style={styles.ruleActionText}>编辑</Text>
        </TouchableOpacity>
        <TouchableOpacity style={styles.ruleActionButtonDanger} onPress={onDelete} disabled={isDeleting} activeOpacity={0.85}>
          <Trash2 size={14} color={styles._colors.error} />
          <Text style={styles.ruleActionTextDanger}>{isDeleting ? '删除中…' : '删除'}</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

/** 规则表单弹层：新建/编辑共用；set_category/set_account 动作值改为选择器。 */
interface RuleFormModalProps {
  visible: boolean;
  rule: Rule | null;
  onClose: () => void;
  onSaved: () => void;
}

function RuleFormModal({ visible, rule, onClose, onSaved }: RuleFormModalProps) {
  const styles = useStyles(createStyles);
  const { alert } = useAlert();
  const [name, setName] = useState(rule?.name ?? '');
  const [condition, setCondition] = useState(rule?.condition ?? 'merchant_contains');
  const [conditionValue, setConditionValue] = useState(rule?.conditionValue ?? '');
  const [action, setAction] = useState(rule?.action ?? 'set_category');
  const [actionValue, setActionValue] = useState(rule?.actionValue ?? '');
  const [priorityText, setPriorityText] = useState(String(rule?.priority ?? 100));

  // 打开时按传入规则重置表单（Modal 复用实例，必须显式同步）
  React.useEffect(() => {
    if (!visible) return;
    setName(rule?.name ?? '');
    setCondition(rule?.condition ?? 'merchant_contains');
    setConditionValue(rule?.conditionValue ?? '');
    setAction(rule?.action ?? 'set_category');
    setActionValue(rule?.actionValue ?? '');
    setPriorityText(String(rule?.priority ?? 100));
  }, [visible, rule]);

  const categoriesQuery = useQuery({
    queryKey: ['categories', 'all-for-rules'],
    queryFn: () => nativeData.getCategories(),
    enabled: visible && action === ACTION_SELECT_CATEGORY,
  });
  const accountsQuery = useQuery({
    queryKey: ['accounts', 'list'],
    queryFn: () => nativeData.getAccounts(),
    enabled: visible && action === ACTION_SELECT_ACCOUNT,
  });

  const saveMutation = useMutation({
    mutationFn: async () => {
      const input = {
        name: name.trim(),
        condition,
        conditionValue: conditionValue.trim(),
        action,
        ...(actionValue.trim() ? { actionValue: actionValue.trim() } : {}),
        priority: Math.max(0, Math.min(9999, Number.parseInt(priorityText, 10) || 100)),
      };
      if (rule) {
        return nativeData.updateRule(rule.id, input);
      }
      return nativeData.addRule(input);
    },
    onSuccess: () => {
      onSaved();
    },
    onError: (error) => {
      const message = error instanceof Error ? error.message : '保存失败';
      alert('保存失败', message);
    },
  });

  /** 提交前校验：名称/条件值必填，set_* 动作必须有动作值。 */
  const handleSubmit = () => {
    if (!name.trim()) {
      alert('表单不完整', '请填写规则名称。');
      return;
    }
    if (!conditionValue.trim()) {
      alert('表单不完整', '请填写条件值。');
      return;
    }
    if (action.startsWith('set_') && !actionValue.trim()) {
      alert('表单不完整', '该动作需要选择或填写动作值。');
      return;
    }
    saveMutation.mutate();
  };

  /** 动作值选择器：按动作类型渲染分类/账户选择或文本输入。 */
  const renderActionValueInput = () => {
    if (action === ACTION_SELECT_CATEGORY) {
      const categories = categoriesQuery.data ?? [];
      return (
        <View style={styles.pickerGrid}>
          {categories.map(category => (
            <TouchableOpacity
              key={category.id}
              style={[styles.pickerItem, actionValue === category.id && styles.pickerItemSelected]}
              onPress={() => setActionValue(category.id)}
            >
              <View style={styles.pickerItemRow}>
                {category.icon && <CategoryIcon icon={category.icon} size={12} emojiStyle={styles.pickerItemIcon} />}
                <Text style={styles.pickerItemText}>{category.name}</Text>
              </View>
            </TouchableOpacity>
          ))}
        </View>
      );
    }
    if (action === ACTION_SELECT_ACCOUNT) {
      const accounts: Account[] = (accountsQuery.data ?? []).filter(
        (account: Account) => account.isActive,
      );
      return (
        <View style={styles.pickerGrid}>
          {accounts.map(account => (
            <TouchableOpacity
              key={account.id}
              style={[
                styles.pickerItem,
                styles.pickerItemRow,
                actionValue === account.id && styles.pickerItemSelected,
              ]}
              onPress={() => setActionValue(account.id)}
            >
              <AccountIcon
                icon={account.icon}
                name={account.name}
                subtype={account.subtype}
                size={14}
              />
              <Text style={styles.pickerItemText}>
                {accountDisplayName(account.name, resolveAccountIcon(account.icon, account.name))}
              </Text>
            </TouchableOpacity>
          ))}
        </View>
      );
    }
    return (
      <TextInput
        style={styles.textInput}
        value={actionValue}
        onChangeText={setActionValue}
        placeholder="动作值"
        placeholderTextColor={styles._colors.textTertiary}
      />
    );
  };

  return (
    <Modal visible={visible} animationType="slide" transparent onRequestClose={onClose}>
      <View style={styles.modalOverlay}>
        <View style={styles.modalContent}>
          <Text style={styles.modalTitle}>{rule ? '编辑规则' : '新建规则'}</Text>
          <ScrollView contentContainerStyle={styles.modalForm}>
            <Text style={styles.formLabel}>规则名称</Text>
            <TextInput
              style={styles.textInput}
              value={name}
              onChangeText={setName}
              placeholder="如：外卖自动归类"
              placeholderTextColor={styles._colors.textTertiary}
            />
            <Text style={styles.formLabel}>条件类型</Text>
            <View style={styles.pickerGrid}>
              {Object.entries(CONDITION_LABELS).map(([value, label]) => (
                <TouchableOpacity
                  key={value}
                  style={[styles.pickerItem, condition === value && styles.pickerItemSelected]}
                  onPress={() => setCondition(value)}
                >
                  <Text style={styles.pickerItemText}>{label}</Text>
                </TouchableOpacity>
              ))}
            </View>
            <Text style={styles.formLabel}>条件值</Text>
            <TextInput
              style={styles.textInput}
              value={conditionValue}
              onChangeText={setConditionValue}
              placeholder={CONDITION_PLACEHOLDERS[condition] ?? '条件值'}
              placeholderTextColor={styles._colors.textTertiary}
            />
            <Text style={styles.formLabel}>动作</Text>
            <View style={styles.pickerGrid}>
              {Object.entries(ACTION_LABELS).map(([value, label]) => (
                <TouchableOpacity
                  key={value}
                  style={[styles.pickerItem, action === value && styles.pickerItemSelected]}
                  onPress={() => {
                    setAction(value);
                    setActionValue('');
                  }}
                >
                  <Text style={styles.pickerItemText}>{label}</Text>
                </TouchableOpacity>
              ))}
            </View>
            {action.startsWith('set_') ? (
              <>
                <Text style={styles.formLabel}>动作值</Text>
                {renderActionValueInput()}
              </>
            ) : null}
            <Text style={styles.formLabel}>优先级（数字小者优先）</Text>
            <TextInput
              style={styles.textInput}
              value={priorityText}
              onChangeText={setPriorityText}
              keyboardType="numeric"
              placeholder="100"
              placeholderTextColor={styles._colors.textTertiary}
            />
          </ScrollView>
          <View style={styles.modalActions}>
            <TouchableOpacity style={styles.modalCancelButton} onPress={onClose} activeOpacity={0.85}>
              <Text style={styles.modalCancelButtonText}>取消</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={[styles.modalSaveButton, saveMutation.isPending && styles.actionDisabled]}
              onPress={handleSubmit}
              disabled={saveMutation.isPending}
              activeOpacity={0.85}
            >
              <Text style={styles.modalSaveButtonText}>
                {saveMutation.isPending ? '保存中…' : '保存'}
              </Text>
            </TouchableOpacity>
          </View>
        </View>
      </View>
    </Modal>
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
      backgroundColor: shape.isSoft || shape.isHogwarts ? colors.background : colors.surface,
      borderBottomWidth: shape.isSoft ? 0 : shape.isHogwarts ? shape.borderWidth.card : borderWidth.medium,
      borderBottomColor: shape.isHogwarts ? colors.divider : colors.stroke,
    },
    iconButton: { width: 40, height: 40, alignItems: 'center', justifyContent: 'center' },
    headerTitle: {
      fontSize: shape.isSoft || shape.isHogwarts ? 18 : 20,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
    },
    scrollView: { flex: 1 },
    content: { padding: spacing.lg, gap: spacing.md },
    notice: {
      padding: spacing.md,
      backgroundColor: colors.primaryLight,
      borderWidth: shape.isSoft ? 0 : shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      borderRadius: shape.radius.card,
    },
    noticeText: {
      fontSize: 13,
      lineHeight: 19,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: shape.isSoft ? colors.primaryDark : colors.textPrimary,
    },
    emptyState: { alignItems: 'center', paddingVertical: spacing.xl * 2, gap: spacing.md },
    emptyTitle: {
      fontSize: 18,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
    },
    emptyText: {
      fontSize: 13,
      fontWeight: shape.isHogwarts ? undefined : '500',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
      textAlign: 'center',
    },
    ruleCard: {
      padding: spacing.md,
      backgroundColor: colors.surface,
      borderWidth: shape.isSoft || shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.cardBorder : colors.stroke,
      borderRadius: shape.radius.card,
      gap: spacing.xs,
      ...(shape.isSoft || shape.isHogwarts ? shape.elevation.card : {}),
    },
    ruleCardInactive: { opacity: 0.6 },
    ruleHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
    ruleToggle: { flexDirection: 'row', alignItems: 'center', gap: 6 },
    ruleStatus: {
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    rulePriority: {
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      color: colors.primary,
      fontFamily: shape.isHogwarts
        ? HOGWARTS_FONTS.numeric
        : shape.isSoft
          ? undefined
          : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
    },
    ruleName: {
      fontSize: 16,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
    },
    ruleText: {
      fontSize: 13,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    ruleActions: { flexDirection: 'row', gap: spacing.sm, marginTop: spacing.xs },
    ruleActionButton: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 4,
      paddingHorizontal: spacing.sm + 2,
      paddingVertical: spacing.xs + 2,
      borderRadius: shape.isSoft || shape.isHogwarts ? 10 : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      backgroundColor: shape.isSoft || shape.isHogwarts ? colors.surfaceMuted : colors.background,
    },
    ruleActionButtonDanger: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 4,
      paddingHorizontal: spacing.sm + 2,
      paddingVertical: spacing.xs + 2,
      borderRadius: shape.isSoft || shape.isHogwarts ? 10 : borderRadius.small,
      borderWidth: shape.isSoft ? 0 : shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: colors.error,
      backgroundColor: shape.isSoft || shape.isHogwarts ? colors.expenseLight : colors.background,
    },
    ruleActionText: {
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    ruleActionTextDanger: {
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: shape.isSoft || shape.isHogwarts ? colors.expenseStrong : colors.error,
    },
    createButton: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'center',
      gap: 6,
      paddingVertical: spacing.md,
      borderRadius: shape.radius.button,
      backgroundColor: colors.primary,
      borderWidth: shape.isSoft ? 0 : shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isHogwarts ? colors.accent : colors.stroke,
    },
    createButtonText: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: shape.isSoft || shape.isHogwarts ? colors.onPrimary : '#FFFFFF',
    },
    modalOverlay: { flex: 1, backgroundColor: 'rgba(0,0,0,0.5)', justifyContent: 'flex-end' },
    modalContent: {
      maxHeight: '85%',
      backgroundColor: shape.isSoft || shape.isHogwarts ? colors.surface : colors.background,
      borderTopLeftRadius: shape.radius.sheet,
      borderTopRightRadius: shape.radius.sheet,
      borderWidth: shape.isSoft ? shape.borderWidth.card : shape.isHogwarts ? shape.borderWidth.strong : borderWidth.medium,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.cardBorder : colors.stroke,
      padding: spacing.lg,
      gap: spacing.sm,
      ...(shape.isSoft || shape.isHogwarts ? shape.elevation.floating : {}),
    },
    modalTitle: {
      fontSize: 18,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
      marginBottom: spacing.sm,
    },
    modalForm: { gap: spacing.xs, paddingBottom: spacing.lg },
    formLabel: {
      fontSize: 13,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: shape.isSoft || shape.isHogwarts ? colors.textSecondary : colors.textPrimary,
      marginTop: spacing.sm,
    },
    textInput: {
      backgroundColor: shape.isSoft || shape.isHogwarts ? colors.surfaceMuted : colors.surface,
      borderWidth: shape.isSoft ? 0 : shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      borderRadius: shape.radius.input,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.sm + 2,
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    pickerGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
    pickerItem: {
      paddingHorizontal: spacing.sm + 2,
      paddingVertical: spacing.xs + 2,
      borderRadius: shape.isSoft || shape.isHogwarts ? shape.radius.pill : borderRadius.small,
      borderWidth: shape.isSoft ? 1 : shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isSoft ? 'transparent' : shape.isHogwarts ? colors.cardBorder : colors.stroke,
      backgroundColor: shape.isSoft || shape.isHogwarts ? colors.surfaceMuted : colors.surface,
    },
    pickerItemSelected: {
      backgroundColor: shape.isSoft || shape.isHogwarts ? colors.primaryLight : colors.accent,
      borderWidth: shape.isSoft || shape.isHogwarts ? 1.5 : borderWidth.medium,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.primary : colors.stroke,
    },
    pickerItemText: {
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    pickerItemRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs },
    pickerItemIcon: { fontSize: 12 },
    modalActions: {
      flexDirection: 'row',
      gap: spacing.md,
      paddingTop: spacing.sm,
      borderTopWidth: shape.isSoft || shape.isHogwarts ? StyleSheet.hairlineWidth : borderWidth.thin,
      borderTopColor: shape.isSoft || shape.isHogwarts ? colors.divider : colors.stroke,
    },
    modalCancelButton: {
      flex: 1,
      alignItems: 'center',
      justifyContent: 'center',
      paddingVertical: spacing.sm + 2,
      borderRadius: shape.radius.button,
      borderWidth: shape.isSoft ? 0 : shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      backgroundColor: shape.isSoft || shape.isHogwarts ? colors.surfaceMuted : colors.surface,
    },
    modalCancelButtonText: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: shape.isSoft || shape.isHogwarts ? colors.textSecondary : colors.textPrimary,
    },
    modalSaveButton: {
      flex: 1,
      alignItems: 'center',
      justifyContent: 'center',
      paddingVertical: spacing.sm + 2,
      borderRadius: shape.radius.button,
      borderWidth: shape.isSoft ? 0 : shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isHogwarts ? colors.accent : colors.stroke,
      backgroundColor: colors.primary,
    },
    modalSaveButtonText: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: shape.isSoft || shape.isHogwarts ? colors.onPrimary : '#FFFFFF',
    },
    actionDisabled: { opacity: 0.5 },
  }),
  _colors: colors,
  _primaryIconColor: shape.isSoft || shape.isHogwarts ? colors.onPrimary : '#FFFFFF',
});

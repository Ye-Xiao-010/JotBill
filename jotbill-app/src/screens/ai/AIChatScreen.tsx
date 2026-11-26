/**
 * AI 助手聊天屏幕
 * 支持拍照/上传图片、语音、文本输入识别账单
 */
import React, { useState, useRef, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  TextInput,
  FlatList,
  TouchableOpacity,
  KeyboardAvoidingView,
  Platform,
  StyleSheet,
  ActivityIndicator,
  Modal,
  ScrollView,
  Animated,
  Dimensions,
  PanResponder,
  GestureResponderEvent,
  PanResponderGestureState,
  Image,
  Keyboard as RNKeyboard,
  NativeScrollEvent,
  NativeSyntheticEvent,
} from 'react-native';
import { Camera, Mic, Send, Square, X, Check, Edit2, Trash2, Keyboard, Menu, SquarePen, ChevronLeft, ChevronRight, Pin, ChevronDown, Plus, Sparkles, Pencil, Settings, Wallet, Target, Eye } from 'lucide-react-native';
import { launchCamera, launchImageLibrary, ImagePickerResponse } from 'react-native-image-picker';
import ImageViewing from 'react-native-image-viewing';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { borderRadius, borderWidth, spacing, shadow } from '../../theme/spacing';
import { SoftGradient } from '../../components/ui';
import { useStyles } from '../../hooks';
import { useChatSessions } from '../../hooks/useChatSessions';
import { useFocusEffect, useRoute, useNavigation } from '@react-navigation/native';
import { useAlert, useTheme } from '../../providers';
import { HOGWARTS_FONTS, HOGWARTS_IMAGES } from '../../constants/hogwartsAssets';
import { aiService } from '../../services/ai';
import { audioRecorderService } from '../../services/audio/audioRecorderService';
import type { RecordingProgress } from '../../services/audio/audioRecorderService';
import { nativeData, type Account, type Category } from '../../native';
import { getCategoryIcon as getDefaultCategoryIcon } from '../../constants/app';
import { formatCurrency, formatSignedCurrency } from '../../utils/format';
import { Skeleton, SkeletonCircle } from '../../components/skeleton/Skeleton';
import type { ParsedBill, AIModelConfig, ChatSession } from '../../types/ai';
import { ProviderIcon } from '../../components/icons';
import CategoryIcon from '../../components/business/CategoryIcon';
import AccountBadge from '../../components/business/AccountBadge';
import AccountIcon from '../../components/business/AccountIcon';
import storage from '../../utils/storage';
import { invalidateCache } from '../../lib/queryClient';
import { findDuplicateBills } from '../../lib/billDuplicateCheck';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { typography } from '../../theme';

const SIDEBAR_WIDTH = Dimensions.get('window').width * 0.78;
const WELCOME_MESSAGE = '你好！我是你的 AI 助手，可以帮你：\n\n📝 整理待确认账单 — “微信付午饭花了35元”\n🔍 查账 — “查一下本月餐饮账单”\n📊 统计 — “这个月各类支出占比是多少”\n💳 查账户与预算 — “看看账户余额和本月预算进度”\n📷 识别账单图片\n\nAI 不会直接删除或修改账单。';
const HOGWARTS_WELCOME_MESSAGE = '欢迎回到古灵阁账房！我是你的招财巫师猫管家，随时为你施法理账：\n\n🪶 羽毛笔记账 — “微信买黄油啤酒花了35元”\n🔮 水晶球查账 — “查一下本月餐饮账卷”\n📜 账卷统计 — “这个月金加隆花哪儿了”\n🏦 金库与预算 — “看看各账户余额与本月预算进度”\n🦉 识别收据羊皮纸（拍照 / 传图）\n\n管家不会擅自删除或修改你的账卷记录。';

// 会话分组类型
interface SessionGroup {
  title: string;
  data: ChatSession[];
}

// 账单数据类型（带临时 ID 用于编辑）
interface BillItem extends ParsedBill {
  id: string;
  categoryIcon?: string;
  categoryId?: string;
  /**
   * 记账账户。
   *
   * `LedgerRepository.validateBillAccounts` 无条件要求 accountId
   * （缺失即抛 LedgerValidationException），而本路径此前**根本没传** ——
   * 导致 AI 对话提出的每一笔账单保存时都被原生拒绝，入口三 100% 不可用。
   * 用户可选；未选时由 [handleSaveAllBills] 回落到默认账户（与其他入口同口径）。
   */
  accountId?: string;
}

/**
 * 兜底分类的稳定 ID（来自 DefaultDataInitializer 的种子数据）。
 *
 * 不依赖本地分类名称 —— 用户可能把"其他"改名，甚至删掉。因此保存前还要
 * 再查一次本地是否存在，存在才用。
 */
const FALLBACK_CATEGORY_IDS: Record<'income' | 'expense', string> = {
  expense: 'default-category-expense-other',
  income: 'default-category-income-other',
};

// 流式步骤类型
interface StreamStep {
  id: string;
  type: 'thinking' | 'tool_call';
  label: string;
  startTime: number;
  endTime?: number;
  isActive: boolean;
  thinkingContent?: string;
}

// 工具名称映射
const TOOL_DISPLAY_NAMES: Record<string, string> = {
  create_bills: '记账',
  query_bills: '查询账单',
  delete_bills: '删除账单',
  get_statistics: '统计分析',
  get_accounts: '查询账户',
  get_budgets: '查询预算',
  get_goals: '查询财务目标',
};

/**
 * 按 AI 返回的账户名称或尾号匹配本地启用账户：
 * 精确名称 → 卡号后四位 → 唯一包含匹配；多候选时返回 undefined 避免误判。
 */
export function matchAccountIdByName(
  accountName: string | undefined,
  accounts: Account[],
): string | undefined {
  const needle = accountName?.trim().toLowerCase();
  if (!needle || accounts.length === 0) return undefined;

  const exact = accounts.find((a) => a.name.trim().toLowerCase() === needle);
  if (exact) return exact.id;

  const tailMatches = accounts.filter(
    (a) => Boolean(a.cardTail?.trim()) && needle.includes(a.cardTail!.trim().toLowerCase()),
  );
  if (tailMatches.length === 1) return tailMatches[0].id;

  const fuzzy = accounts.filter((a) => {
    const name = a.name.trim().toLowerCase();
    return name.includes(needle) || needle.includes(name);
  });
  if (fuzzy.length === 1) return fuzzy[0].id;

  return undefined;
}

/**
 * 多模态图片的两个硬上限，与原生 `AiChatModule` 的 companion object 保持一致：
 * - `MAX_IMAGES = 4`（AiChatModule.kt:1137，`readImages` 超限直接抛异常）
 * - `MAX_IMAGE_BASE64_LENGTH = 12 * 1024 * 1024`（:1140，含 `data:` 前缀）
 *
 * 前端镜像这两个值，是为了让"必然失败的请求"在按下发送**之前**就被拦下，
 * 而不是让用户选完图、点发送、被拒绝、附件已被清空（详见 pickImage 注释）。
 */
const MAX_IMAGES = 4;
const MAX_IMAGE_BASE64_LENGTH = 12 * 1024 * 1024;
/** 原生 `MAX_CONTENT_LENGTH`（AiChatModule.kt:1135），同样只在下发时才拒绝。 */
const MAX_CONTENT_LENGTH = 8000;

// 消息类型
interface Message {
  id: string;
  type: 'text' | 'bills' | 'bill_list' | 'statistics' | 'accounts' | 'budgets' | 'goals' | 'loading' | 'error' | 'streaming';
  content?: string;
  imageUrls?: string[];
  bills?: BillItem[];
  billListData?: any;
  statisticsData?: any;
  accountsData?: any;
  budgetsData?: any;
  goalsData?: any;
  isUser: boolean;
  isHistorical?: boolean;
  timestamp: Date;
  streamSteps?: StreamStep[];
  streamingText?: string;
  isStreamComplete?: boolean;
  totalThinkingMs?: number;
}

// 编辑账单弹窗
interface EditBillModalProps {
  visible: boolean;
  bill: BillItem | null;
  categories: Category[];
  accounts: Account[];
  defaultAccountId?: string;
  onClose: () => void;
  onSave: (bill: BillItem) => void;
  colors: ThemeColors;
}

function EditBillModal({
  visible,
  bill,
  categories,
  accounts,
  defaultAccountId,
  onClose,
  onSave,
  colors,
}: EditBillModalProps) {
  const { alert: showEditAlert } = useAlert();
  const { isSoft, isHogwarts } = useTheme();
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const [date, setDate] = useState('');
  const [type, setType] = useState<'income' | 'expense'>('expense');
  const [selectedCategoryId, setSelectedCategoryId] = useState<string | undefined>();
  const [selectedAccountId, setSelectedAccountId] = useState<string | undefined>();

  useEffect(() => {
    if (bill) {
      setAmount(bill.amount.toString());
      setDescription(bill.description);
      setDate(bill.date);
      setType(bill.type);
      setSelectedCategoryId(bill.categoryId);
      setSelectedAccountId(
        bill.accountId || matchAccountIdByName(bill.accountName, accounts) || defaultAccountId,
      );
    }
  }, [bill, accounts, defaultAccountId]);

  const handleSave = () => {
    if (!bill) return;
    const parsedAmount = parseFloat(amount);
    if (isNaN(parsedAmount) || parsedAmount <= 0) {
      showEditAlert('提示', '请输入有效金额');
      return;
    }

    const selectedCategory = categories.find(c => c.id === selectedCategoryId);
    const selectedAccount = accounts.find(a => a.id === selectedAccountId);
    onSave({
      ...bill,
      amount: parsedAmount,
      description,
      date,
      type,
      categoryId: selectedCategoryId,
      categoryName: selectedCategory?.name || bill.categoryName,
      categoryIcon: selectedCategory?.icon || bill.categoryIcon,
      accountId: selectedAccount?.id ?? selectedAccountId,
      accountName: selectedAccount?.name ?? bill.accountName,
    });
  };

  const filteredCategories = categories.filter(c => c.type === type);

  const modalStyles = createEditModalStyles(colors, isSoft, isHogwarts);

  return (
    <Modal visible={visible} animationType="slide" transparent onRequestClose={onClose}>
      <View style={modalStyles.overlay}>
        <View style={modalStyles.content}>
          <View style={modalStyles.header}>
            <Text style={modalStyles.title}>编辑账单</Text>
            <TouchableOpacity onPress={onClose} style={modalStyles.closeButton}>
              <X size={24} color={colors.textSecondary} />
            </TouchableOpacity>
          </View>

          <ScrollView style={modalStyles.body} showsVerticalScrollIndicator={false}>
            {/* 类型选择 */}
            <View style={modalStyles.field}>
              <Text style={modalStyles.label}>类型</Text>
              <View style={modalStyles.typeSelector}>
                <TouchableOpacity
                  style={[
                    modalStyles.typeButton,
                    type === 'expense' && modalStyles.typeButtonActive,
                  ]}
                  onPress={() => setType('expense')}
                >
                  <Text
                    style={[
                      modalStyles.typeButtonText,
                      type === 'expense' && modalStyles.typeButtonTextActive,
                    ]}
                  >
                    支出
                  </Text>
                </TouchableOpacity>
                <TouchableOpacity
                  style={[
                    modalStyles.typeButton,
                    type === 'income' && modalStyles.typeButtonActiveIncome,
                  ]}
                  onPress={() => setType('income')}
                >
                  <Text
                    style={[
                      modalStyles.typeButtonText,
                      type === 'income' && modalStyles.typeButtonTextActiveIncome,
                    ]}
                  >
                    收入
                  </Text>
                </TouchableOpacity>
              </View>
            </View>

            {/* 金额 */}
            <View style={modalStyles.field}>
              <Text style={modalStyles.label}>金额</Text>
              <TextInput
                style={modalStyles.input}
                value={amount}
                onChangeText={setAmount}
                keyboardType="decimal-pad"
                placeholder="0.00"
                placeholderTextColor={colors.textTertiary}
              />
            </View>

            {/* 账户 */}
            {accounts.length > 0 && (
              <View style={modalStyles.field}>
                <Text style={modalStyles.label}>记账账户</Text>
                <View style={modalStyles.accountGrid}>
                  {accounts.map(account => {
                    const isSelected = selectedAccountId === account.id;
                    return (
                      <TouchableOpacity
                        key={account.id}
                        testID={`edit-bill-account-${account.id}`}
                        style={[
                          modalStyles.accountChip,
                          isSelected && modalStyles.accountChipActive,
                        ]}
                        onPress={() => setSelectedAccountId(account.id)}
                      >
                        <AccountIcon
                          icon={account.icon}
                          name={account.name}
                          subtype={account.subtype}
                          size={16}
                          color={isSelected ? colors.primary : colors.textSecondary}
                        />
                        <Text
                          style={[
                            modalStyles.accountChipText,
                            isSelected && modalStyles.accountChipTextActive,
                          ]}
                          numberOfLines={1}
                        >
                          {account.name}
                          {account.cardTail ? ` · ${account.cardTail}` : ''}
                        </Text>
                      </TouchableOpacity>
                    );
                  })}
                </View>
              </View>
            )}

            {/* 分类 */}
            <View style={modalStyles.field}>
              <Text style={modalStyles.label}>分类</Text>
              <View style={modalStyles.categoryGrid}>
                {filteredCategories.map(category => (
                  <TouchableOpacity
                    key={category.id}
                    style={[
                      modalStyles.categoryItem,
                      selectedCategoryId === category.id && modalStyles.categoryItemActive,
                    ]}
                    onPress={() => setSelectedCategoryId(category.id)}
                  >
                    <CategoryIcon icon={category.icon} size={24} emojiStyle={modalStyles.categoryIcon} />
                    <Text
                      style={[
                        modalStyles.categoryName,
                        selectedCategoryId === category.id && modalStyles.categoryNameActive,
                      ]}
                    >
                      {category.name}
                    </Text>
                  </TouchableOpacity>
                ))}
              </View>
            </View>

            {/* 描述 */}
            <View style={modalStyles.field}>
              <Text style={modalStyles.label}>描述</Text>
              <TextInput
                style={modalStyles.input}
                value={description}
                onChangeText={setDescription}
                placeholder="账单描述"
                placeholderTextColor={colors.textTertiary}
              />
            </View>

            {/* 日期 */}
            <View style={modalStyles.field}>
              <Text style={modalStyles.label}>日期</Text>
              <TextInput
                style={modalStyles.input}
                value={date}
                onChangeText={setDate}
                placeholder="YYYY-MM-DD"
                placeholderTextColor={colors.textTertiary}
              />
            </View>
          </ScrollView>

          <View style={modalStyles.footer}>
            <TouchableOpacity style={modalStyles.cancelButton} onPress={onClose}>
              <Text style={modalStyles.cancelButtonText}>取消</Text>
            </TouchableOpacity>
            <TouchableOpacity
              testID="edit-bill-save-btn"
              style={modalStyles.saveButton}
              onPress={handleSave}
            >
              <Text style={modalStyles.saveButtonText}>保存</Text>
            </TouchableOpacity>
          </View>
        </View>
      </View>
    </Modal>
  );
}

const createEditModalStyles = (colors: ThemeColors, isSoft: boolean, isHogwarts: boolean) =>
  StyleSheet.create({
    overlay: {
      flex: 1,
      backgroundColor: 'rgba(0, 0, 0, 0.5)',
      justifyContent: 'flex-end',
    },
    content: {
      backgroundColor: colors.surface,
      borderTopLeftRadius: borderRadius.large,
      borderTopRightRadius: borderRadius.large,
      maxHeight: '88%',
    },
    header: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      paddingHorizontal: spacing.lg,
      paddingVertical: spacing.md,
      borderBottomWidth: 1,
      borderBottomColor: colors.divider,
    },
    title: {
      fontSize: 18,
      fontWeight: isHogwarts ? undefined : '800',
      fontFamily: isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
    },
    closeButton: {
      padding: spacing.xs,
    },
    body: {
      padding: spacing.lg,
    },
    field: {
      marginBottom: spacing.md,
    },
    label: {
      fontSize: 15,
      fontWeight: isHogwarts ? undefined : '700',
      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
      marginBottom: spacing.sm,
    },
    input: {
      borderWidth: 1,
      borderColor: colors.divider,
      borderRadius: borderRadius.medium,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.sm,
      fontSize: 15,
      color: colors.textPrimary,
      backgroundColor: colors.background,
    },
    typeSelector: {
      flexDirection: 'row',
      gap: spacing.sm,
    },
    typeButton: {
      flex: 1,
      paddingVertical: spacing.sm,
      borderRadius: borderRadius.medium,
      borderWidth: 1,
      borderColor: colors.divider,
      alignItems: 'center',
    },
    typeButtonActive: {
      backgroundColor: colors.expenseLight,
      borderColor: colors.expense,
    },
    typeButtonActiveIncome: {
      backgroundColor: colors.incomeLight,
      borderColor: colors.income,
    },
    typeButtonText: {
      fontSize: 15,
      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    typeButtonTextActive: {
      color: colors.expense,
      fontWeight: isHogwarts ? undefined : '700',
    },
    typeButtonTextActiveIncome: {
      color: colors.income,
      fontWeight: isHogwarts ? undefined : '700',
    },
    accountGrid: {
      flexDirection: 'row',
      flexWrap: 'wrap',
      gap: spacing.sm,
    },
    accountChip: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 6,
      paddingHorizontal: spacing.sm + 4,
      paddingVertical: spacing.sm,
      borderRadius: isSoft ? borderRadius.large : borderRadius.medium,
      backgroundColor: colors.background,
      borderWidth: 1,
      borderColor: colors.divider,
      maxWidth: '48%',
    },
    accountChipActive: {
      backgroundColor: colors.primaryLight,
      borderColor: colors.primary,
    },
    accountChipText: {
      flexShrink: 1,
      fontSize: 13,
      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    accountChipTextActive: {
      color: colors.primary,
      fontWeight: isHogwarts ? undefined : '700',
    },
    categoryGrid: {
      flexDirection: 'row',
      flexWrap: 'wrap',
      gap: spacing.sm,
    },
    categoryItem: {
      width: '22%',
      aspectRatio: 1,
      alignItems: 'center',
      justifyContent: 'center',
      borderRadius: borderRadius.medium,
      backgroundColor: colors.background,
      borderWidth: 1,
      borderColor: colors.divider,
    },
    categoryItemActive: {
      backgroundColor: colors.primaryLight,
      borderColor: colors.primary,
    },
    categoryIcon: {
      fontSize: 24,
      marginBottom: 4,
    },
    categoryName: {
      fontSize: 12,
      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    categoryNameActive: {
      color: colors.primary,
      fontWeight: isHogwarts ? undefined : '700',
    },
    footer: {
      flexDirection: 'row',
      gap: spacing.md,
      padding: spacing.lg,
      borderTopWidth: 1,
      borderTopColor: colors.divider,
    },
    cancelButton: {
      flex: 1,
      paddingVertical: spacing.sm + 2,
      borderRadius: borderRadius.medium,
      backgroundColor: colors.background,
      alignItems: 'center',
      borderWidth: 1,
      borderColor: colors.divider,
    },
    cancelButtonText: {
      fontSize: 15,
      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    saveButton: {
      flex: 1,
      paddingVertical: spacing.sm + 2,
      borderRadius: borderRadius.medium,
      backgroundColor: colors.primary,
      alignItems: 'center',
    },
    saveButtonText: {
      fontSize: 15,
      fontWeight: isHogwarts ? undefined : '700',
      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: isSoft || isHogwarts ? colors.onPrimary : '#FFFFFF',
    },
  });

// 账单卡片组件
interface BillCardProps {
  bill: BillItem;
  account?: Account;
  readOnly?: boolean;
  onEdit: (bill: BillItem) => void;
  onDelete: (id: string) => void;
  colors: ThemeColors;
}

function BillCard({ bill, account, readOnly = false, onEdit, onDelete, colors }: BillCardProps) {
  const { isSoft, isHogwarts, shape } = useTheme();
  const isExpense = bill.type === 'expense';
  const amountColor = isExpense ? colors.expense : colors.income;
  const iconBgColor = isExpense ? colors.expenseLight : colors.incomeLight;

  return (
    <View style={billCardStyles.container} testID={`ai-bill-card-${bill.id}`}>
      <View
        style={[
          billCardStyles.card,
          {
            backgroundColor: colors.surface,
            borderRadius: shape.radius.card,
            borderWidth: isSoft || isHogwarts ? shape.borderWidth.card : borderWidth.thin,
            borderColor: isSoft || isHogwarts ? colors.cardBorder : colors.stroke,
            ...(isSoft || isHogwarts ? shape.elevation.card : shadow.small),
          },
        ]}
      >
        <View style={billCardStyles.metaRow}>
          <Text
            style={[
              billCardStyles.date,
              {
                color: colors.textTertiary,
                fontFamily: isHogwarts ? HOGWARTS_FONTS.numeric : undefined,
              },
            ]}
          >
            {bill.date}
          </Text>
          {account ? (
            <AccountBadge account={account} compact testID={`ai-bill-account-${bill.id}`} />
          ) : null}
        </View>

        <View style={billCardStyles.content}>
          <View
            style={[
              billCardStyles.categoryIcon,
              isSoft ? billCardStyles.categoryIconSoft : billCardStyles.categoryIconDefault,
              { backgroundColor: iconBgColor },
            ]}
          >
            <CategoryIcon
              icon={bill.categoryIcon}
              size={22}
              color={amountColor}
              emojiStyle={billCardStyles.categoryIconText}
            />
          </View>
          <View style={billCardStyles.info}>
            <Text
              style={[
                billCardStyles.categoryName,
                !isHogwarts && billCardStyles.weightBold,
                {
                  color: colors.textPrimary,
                  fontFamily: isHogwarts ? HOGWARTS_FONTS.display : undefined,
                },
              ]}
              numberOfLines={1}
            >
              {bill.categoryName}
            </Text>
            <Text
              style={[
                billCardStyles.description,
                {
                  color: colors.textSecondary,
                  fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
                },
              ]}
              numberOfLines={2}
            >
              {bill.description}
            </Text>
          </View>
          <Text
            style={[
              billCardStyles.amount,
              !isHogwarts && billCardStyles.weightBold,
              {
                color: amountColor,
                fontFamily: isHogwarts ? HOGWARTS_FONTS.numeric : undefined,
              },
            ]}
          >
            {formatSignedCurrency(Number(bill.amount), !isExpense)}
          </Text>
        </View>

        <View
          style={[
            billCardStyles.actions,
            { borderTopColor: colors.divider },
          ]}
        >
          {readOnly ? (
            <Text
              testID={`ai-bill-historical-tag-${bill.id}`}
              style={[
                billCardStyles.historicalHint,
                {
                  color: colors.textTertiary,
                  fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
                },
              ]}
            >
              历史账单建议（仅供查看）
            </Text>
          ) : (
            <>
              <TouchableOpacity
                testID={`ai-bill-edit-${bill.id}`}
                style={[
                  billCardStyles.actionButton,
                  isSoft
                    ? [billCardStyles.actionButtonSoft, { backgroundColor: colors.primaryLight }]
                    : {
                        backgroundColor: colors.surfaceMuted,
                        borderColor: isHogwarts ? colors.cardBorder : colors.stroke,
                      },
                ]}
                onPress={() => onEdit(bill)}
              >
                <Edit2 size={14} color={isSoft ? colors.primaryDark : colors.primary} />
                <Text
                  style={[
                    billCardStyles.actionButtonText,
                    !isHogwarts && billCardStyles.weightSemiBold,
                    {
                      color: isSoft ? colors.primaryDark : colors.primary,
                      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
                    },
                  ]}
                >
                  修改
                </Text>
              </TouchableOpacity>
              <TouchableOpacity
                testID={`ai-bill-delete-${bill.id}`}
                style={[
                  billCardStyles.actionButton,
                  isSoft
                    ? [billCardStyles.actionButtonSoft, { backgroundColor: colors.expenseLight }]
                    : {
                        backgroundColor: colors.expenseLight,
                        borderColor: colors.error,
                      },
                ]}
                onPress={() => onDelete(bill.id)}
              >
                <Trash2 size={14} color={isSoft ? colors.expenseStrong : colors.error} />
                <Text
                  style={[
                    billCardStyles.actionButtonText,
                    !isHogwarts && billCardStyles.weightSemiBold,
                    {
                      color: isSoft ? colors.expenseStrong : colors.error,
                      fontFamily: isHogwarts ? HOGWARTS_FONTS.body : undefined,
                    },
                  ]}
                >
                  删除
                </Text>
              </TouchableOpacity>
            </>
          )}
        </View>
      </View>
    </View>
  );
}

const billCardStyles = StyleSheet.create({
  container: {
    marginBottom: spacing.md,
  },
  card: {
    borderRadius: borderRadius.large,
    padding: spacing.md,
    borderWidth: 1,
  },
  metaRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.sm,
    marginBottom: spacing.sm,
  },
  date: {
    fontSize: 12,
    fontVariant: ['tabular-nums'],
  },
  content: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  categoryIcon: {
    width: 44,
    height: 44,
    borderRadius: 22,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: spacing.md,
  },
  categoryIconSoft: {
    borderRadius: 14,
  },
  categoryIconDefault: {
    borderRadius: borderRadius.medium,
  },
  weightBold: {
    fontWeight: '700',
  },
  historicalHint: {
    fontSize: 12,
    paddingVertical: 2,
  },
  weightSemiBold: {
    fontWeight: '600',
  },
  actionButtonSoft: {
    borderWidth: 0,
    borderRadius: 12,
  },
  categoryIconText: {
    fontSize: 22,
  },
  info: {
    flex: 1,
    marginRight: spacing.sm,
  },
  categoryName: {
    fontSize: 16,
    marginBottom: 2,
  },
  description: {
    fontSize: 13,
  },
  amount: {
    fontSize: 18,
    fontVariant: ['tabular-nums'],
  },
  actions: {
    flexDirection: 'row',
    gap: spacing.sm,
    marginTop: 12,
    paddingTop: 10,
    borderTopWidth: StyleSheet.hairlineWidth,
  },
  actionButton: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    paddingVertical: spacing.xs + 2,
    paddingHorizontal: spacing.md,
    borderRadius: borderRadius.button,
    borderWidth: 1,
  },
  actionButtonText: {
    fontSize: 13,
  },
});

// 流式步骤行组件
function StreamStepRow({ step, colors }: { step: StreamStep; colors: ThemeColors }) {
  const [elapsed, setElapsed] = useState(0);

  useEffect(() => {
    if (!step.isActive) {
      if (step.endTime) setElapsed(step.endTime - step.startTime);
      return;
    }
    const timer = setInterval(() => setElapsed(Date.now() - step.startTime), 100);
    return () => clearInterval(timer);
  }, [step.isActive, step.startTime, step.endTime]);

  const durationText = elapsed > 100 ? ` ${(elapsed / 1000).toFixed(1)}s` : '';

  return (
    <View>
      <View style={stepStyles.row}>
        {step.isActive ? (
          <View style={stepStyles.spinnerWrap}>
            <ActivityIndicator size="small" color={colors.primary} style={stepStyles.spinner} />
          </View>
        ) : (
          <View style={stepStyles.dotWrap}>
            <View style={[stepStyles.dot, { backgroundColor: colors.textTertiary }]} />
          </View>
        )}
        <Text style={[stepStyles.label, { color: step.isActive ? colors.textSecondary : colors.textTertiary }]}>
          {step.label}{durationText}
        </Text>
      </View>
      {!!step.thinkingContent && (
        <Text
          style={{
            marginLeft: 22,
            fontSize: 13,
            color: colors.textTertiary,
            lineHeight: 18,
            marginTop: 2,
            marginBottom: 4,
          }}
        >
          {step.thinkingContent}
        </Text>
      )}
    </View>
  );
}

// 流式步骤时间线组件
function StreamStepsTimeline({
  steps,
  isComplete,
  totalMs,
  colors,
}: {
  steps: StreamStep[];
  isComplete: boolean;
  totalMs?: number;
  colors: ThemeColors;
}) {
  const [expanded, setExpanded] = useState(false);

  if (steps.length === 0) return null;

  // 流式进行中：直接展示所有步骤
  if (!isComplete) {
    return (
      <View style={stepStyles.container}>
        {steps.map((step) => (
          <StreamStepRow key={step.id} step={step} colors={colors} />
        ))}
      </View>
    );
  }

  // 流式完成后：可折叠
  const seconds = Math.max(1, Math.round((totalMs || 0) / 1000));

  return (
    <View style={stepStyles.container}>
      <TouchableOpacity
        onPress={() => setExpanded(!expanded)}
        style={stepStyles.collapseHeader}
        activeOpacity={0.6}
      >
        <Text style={[stepStyles.collapseText, { color: colors.textTertiary }]}>
          已深度思考 (用时 {seconds}秒)
        </Text>
        <ChevronDown
          size={14}
          color={colors.textTertiary}
          style={{ marginLeft: 4, transform: [{ rotate: expanded ? '180deg' : '0deg' }] }}
        />
      </TouchableOpacity>
      {expanded && (
        <View style={stepStyles.expandedSteps}>
          {steps.map((step) => (
            <StreamStepRow key={step.id} step={step} colors={colors} />
          ))}
        </View>
      )}
    </View>
  );
}

const stepStyles = StyleSheet.create({
  container: {
    marginBottom: 8,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 2,
  },
  spinnerWrap: {
    width: 16,
    height: 16,
    alignItems: 'center',
    justifyContent: 'center',
  },
  spinner: {
    transform: [{ scale: 0.6 }],
  },
  dotWrap: {
    width: 16,
    height: 16,
    alignItems: 'center',
    justifyContent: 'center',
  },
  dot: {
    width: 5,
    height: 5,
    borderRadius: 2.5,
  },
  label: {
    fontSize: 13,
    marginLeft: 6,
  },
  collapseHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 4,
  },
  collapseText: {
    fontSize: 13,
  },
  expandedSteps: {
    paddingTop: 4,
  },
});

// 聊天骨架屏组件
function ChatSkeleton() {
  return (
    <View style={chatSkeletonStyles.container}>
      {/* AI 消息骨架 */}
      <View style={chatSkeletonStyles.aiBubble}>
        <SkeletonCircle size={28} />
        <View style={chatSkeletonStyles.aiContent}>
          <Skeleton width="90%" height={14} />
          <Skeleton width="70%" height={14} style={{ marginTop: 8 }} />
          <Skeleton width="40%" height={14} style={{ marginTop: 8 }} />
        </View>
      </View>
      {/* 用户消息骨架 */}
      <View style={chatSkeletonStyles.userBubble}>
        <View style={chatSkeletonStyles.userContent}>
          <Skeleton width={120} height={14} />
        </View>
      </View>
      {/* AI 消息骨架 */}
      <View style={chatSkeletonStyles.aiBubble}>
        <SkeletonCircle size={28} />
        <View style={chatSkeletonStyles.aiContent}>
          <Skeleton width="80%" height={14} />
          <Skeleton width="60%" height={14} style={{ marginTop: 8 }} />
        </View>
      </View>
      {/* 用户消息骨架 */}
      <View style={chatSkeletonStyles.userBubble}>
        <View style={chatSkeletonStyles.userContent}>
          <Skeleton width={160} height={14} />
        </View>
      </View>
      {/* AI 消息骨架 */}
      <View style={chatSkeletonStyles.aiBubble}>
        <SkeletonCircle size={28} />
        <View style={chatSkeletonStyles.aiContent}>
          <Skeleton width="85%" height={14} />
          <Skeleton width="50%" height={14} style={{ marginTop: 8 }} />
          <Skeleton width="70%" height={14} style={{ marginTop: 8 }} />
        </View>
      </View>
    </View>
  );
}

const chatSkeletonStyles = StyleSheet.create({
  container: {
    flex: 1,
    padding: spacing.lg,
  },
  aiBubble: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    marginBottom: spacing.md,
    gap: spacing.sm,
  },
  aiContent: {
    flex: 1,
    maxWidth: '75%',
    backgroundColor: 'transparent',
    borderRadius: borderRadius.large,
    padding: spacing.md,
  },
  userBubble: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    marginBottom: spacing.md,
  },
  userContent: {
    padding: spacing.md,
  },
});

const SOFT_SUGGESTIONS = [
  { key: 's1', icon: 'sparkles' as const, prompt: '这个月各类支出占比是多少？' },
  { key: 's2', icon: 'pencil' as const, prompt: '帮我记一笔：微信付午饭 28 元' },
  { key: 's3', icon: 'wallet' as const, prompt: '看看我的账户余额和净资产' },
  { key: 's4', icon: 'target' as const, prompt: '本月预算还剩多少？' },
];

export default function AIChatScreen() {
  const styles = useStyles(createStyles);
  const { isSoft, isHogwarts, shape, colors } = useTheme();
  const themeColors = colors;
  const insets = useSafeAreaInsets();
  const { alert, confirm } = useAlert();
  const [softKeyboardVisible, setSoftKeyboardVisible] = useState(false);

  useEffect(() => {
    if (!isSoft) return;
    const showSub = RNKeyboard.addListener('keyboardDidShow', () => setSoftKeyboardVisible(true));
    const hideSub = RNKeyboard.addListener('keyboardDidHide', () => setSoftKeyboardVisible(false));
    return () => {
      showSub.remove();
      hideSub.remove();
    };
  }, [isSoft]);
  const route = useRoute();
  const navigation = useNavigation();
  const routeSessionId = (route.params as any)?.sessionId as string | undefined;
  // 是否从会话列表导航过来（stack 模式）
  const isStackMode = routeSessionId !== undefined;
  const [messages, setMessages] = useState<Message[]>([
    {
      id: 'welcome',
      type: 'text',
      content: WELCOME_MESSAGE,
      isUser: false,
      timestamp: new Date(),
    },
  ]);
  const [inputText, setInputText] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [categories, setCategories] = useState<Category[]>([]);
  /** 可选记账账户：AI 账单必须落到某个账户上（原生层硬性要求）。 */
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [editingBill, setEditingBill] = useState<BillItem | null>(null);
  const [showEditModal, setShowEditModal] = useState(false);
  const [pendingBills, setPendingBills] = useState<BillItem[]>([]);
  const [savingBills, setSavingBills] = useState(false);
  const [defaultAIConfig, setDefaultAIConfig] = useState<AIModelConfig | null>(null);
  const [allAIConfigs, setAllAIConfigs] = useState<AIModelConfig[]>([]);
  const [selectedConfigId, setSelectedConfigId] = useState<string | null>(null);
  const [showModelSwitcher, setShowModelSwitcher] = useState(false);
  const [configLoaded, setConfigLoaded] = useState(false);
  const activeAIConfig =
    allAIConfigs.find((c) => c.id === selectedConfigId) ?? defaultAIConfig;
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [sessionRestoring, setSessionRestoring] = useState(true);
  const [imageViewerVisible, setImageViewerVisible] = useState(false);
  const [imageViewerUrl, setImageViewerUrl] = useState<string>('');
  const [pendingImages, setPendingImages] = useState<string[]>([]);
  const flatListRef = useRef<FlatList>(null);

  // 流式连接引用（用于取消）
  const streamRef = useRef<{ close: () => void; stop: () => void } | null>(null);

  // 组件卸载时关闭流式连接
  useEffect(() => {
    return () => {
      streamRef.current?.close();
    };
  }, []);

  // 语音模式状态
  const [isVoiceMode, setIsVoiceMode] = useState(false); // 是否处于语音输入模式
  const [isRecording, setIsRecording] = useState(false); // 是否正在录音
  const [willCancel, setWillCancel] = useState(false); // 是否将要取消（上滑）

  // 侧边栏状态
  const [sidebarVisible, setSidebarVisible] = useState(false);
  // 会话列表改用共享 hook：分页（原生本就支持 page/limit，此前写死 50 导致
  // 第 51 条之后永久不可达）+ 显式错误态（此前 catch 只 console.error，
  // 侧边栏渲染成"暂无会话"，与"真的没有会话"无法区分）。
  // 置顶/重命名/删除的乐观更新仍通过 setSessions 命令式修改，行为不变。
  const {
    sessions,
    setSessions,
    isLoading: sessionsLoading,
    isLoadingMore: sessionsLoadingMore,
    error: sessionsError,
    hasMore: sessionsHasMore,
    loadMore: loadMoreSessions,
    reload: reloadSessions,
  } = useChatSessions({ autoLoad: false });
  const [renameModalVisible, setRenameModalVisible] = useState(false);
  const [renamingSession, setRenamingSession] = useState<ChatSession | null>(null);
  const [renameText, setRenameText] = useState('');
  const [contextMenu, setContextMenu] = useState<{
    session: ChatSession;
    pageY: number;
  } | null>(null);
  const sidebarAnim = useRef(new Animated.Value(-SIDEBAR_WIDTH)).current;
  const overlayAnim = useRef(new Animated.Value(0)).current;

  // 用于 PanResponder 的 ref（避免闭包问题）
  const willCancelRef = useRef(false);
  // 防止 onPanResponderRelease 和 onPanResponderTerminate 双触发
  const gestureHandledRef = useRef(false);

  // 声波动画
  const waveAnims = useRef(
    Array.from({ length: 20 }, () => new Animated.Value(0.3))
  ).current;

  // 取消阈值（上滑距离）
  const CANCEL_THRESHOLD = 80;

  // 停止声波动画
  const stopWaveAnimation = () => {
    waveAnims.forEach(anim => anim.setValue(0.3));
  };

  // 开始录音
  const startRecording = async () => {
    try {
      // 先设置 UI 状态，避免松手时 UI 还没反应
      setIsRecording(true);

      await audioRecorderService.startRecording((progress: RecordingProgress) => {
        // 用实际音量驱动波形动画
        const metering = progress.currentMetering ?? 0.3;
        waveAnims.forEach((anim) => {
          // 给每根波形条加些随机偏移，看起来更自然
          const variation = 0.3 + metering * (0.5 + Math.random() * 0.5);
          anim.setValue(Math.min(1, variation));
        });
      });
    } catch (err: any) {
      setIsRecording(false);
      console.error('[Voice] 录音失败:', err);
      alert('录音失败', err.message || '无法开始录音');
    }
  };

  // 停止录音并发送
  const stopRecordingAndSend = async () => {
    setIsRecording(false);
    stopWaveAnimation();

    // 松开瞬间立刻在聊天区显示"语音识别中"，给用户即时反馈
    const transcribingMsgId = addMessage({
      type: 'streaming',
      isUser: false,
      streamSteps: [{
        id: 'voice-transcribe',
        type: 'thinking',
        label: '语音识别中',
        startTime: Date.now(),
        isActive: true,
      }],
      streamingText: '',
      isStreamComplete: false,
    });

    const removeTranscribingMsg = () => {
      setMessages(prev => prev.filter(m => m.id !== transcribingMsgId));
    };

    try {
      const result = await audioRecorderService.stopRecording();
      if (!result || result.duration < 0.5) {
        removeTranscribingMsg();
        if (result) {
          audioRecorderService.deleteRecordingFile();
        }
        return;
      }

      // 调用 ASR 转写
      const response = await aiService.transcribeAudio(result.base64, result.mimeType);

      // 移除识别状态消息
      removeTranscribingMsg();

      if (response.data?.text) {
        // 转写成功，直接发送给 AI
        handleSend(response.data.text);
      } else {
        alert('识别失败', '未能识别语音内容，请重试');
      }

      // 清理临时文件
      audioRecorderService.deleteRecordingFile();
    } catch (err: any) {
      removeTranscribingMsg();
      console.error('[Voice] 语音处理失败:', err);
      alert('语音识别失败', err.message || '请检查网络后重试');
    }
  };

  // 取消录音
  const cancelRecording = async () => {
    setIsRecording(false);
    setWillCancel(false);
    willCancelRef.current = false;
    stopWaveAnimation();
    try {
      await audioRecorderService.cancelRecording();
    } catch (err) {
      console.error('[Voice] 取消录音失败:', err);
    }
  };

  // 开始新对话（重置状态）
  const handleNewChat = () => {
    const isFresh = sessionId === null && messages.length === 1 && messages[0].id === 'welcome';
    if (isFresh) {
      alert('提示', '当前已经是新对话了');
      return;
    }
    // 停掉进行中的流：否则旧会话的 text_delta/tool_result 会经 insertBeforeStream
    // 混进新会话的消息列表，误点"保存全部"还会把旧会话建议的账单写入账本
    streamRef.current?.stop();
    setSessionId(null);
    storage.removeItem(LAST_SESSION_ID_KEY);
    setPendingBills([]);
    setMessages([
      {
        id: 'welcome',
        type: 'text',
        content: WELCOME_MESSAGE,
        isUser: false,
        timestamp: new Date(),
      },
    ]);
    closeSidebar();
  };

  // 侧边栏：打开
  const openSidebar = () => {
    setSidebarVisible(true);
    loadSessions();
    Animated.parallel([
      Animated.timing(sidebarAnim, {
        toValue: 0,
        duration: 250,
        useNativeDriver: true,
      }),
      Animated.timing(overlayAnim, {
        toValue: 1,
        duration: 250,
        useNativeDriver: true,
      }),
    ]).start();
  };

  // 侧边栏：关闭
  const closeSidebar = () => {
    Animated.parallel([
      Animated.timing(sidebarAnim, {
        toValue: -SIDEBAR_WIDTH,
        duration: 200,
        useNativeDriver: true,
      }),
      Animated.timing(overlayAnim, {
        toValue: 0,
        duration: 200,
        useNativeDriver: true,
      }),
    ]).start(() => {
      setSidebarVisible(false);
    });
  };

  // 加载会话列表
  const loadSessions = async () => {
    await reloadSessions();
  };

  // 侧边栏滚到底时追加下一页。
  const loadMoreSessionsRef = useRef(loadMoreSessions);
  loadMoreSessionsRef.current = loadMoreSessions;
  const sessionsHasMoreRef = useRef(sessionsHasMore);
  sessionsHasMoreRef.current = sessionsHasMore;
  const handleSidebarScroll = useCallback((e: NativeSyntheticEvent<NativeScrollEvent>) => {
    const { layoutMeasurement, contentOffset, contentSize } = e.nativeEvent;
    const distanceToBottom =
      layoutMeasurement.height + contentOffset.y - contentSize.height;
    if (distanceToBottom < 120) loadMoreSessionsRef.current();
  }, []);

  // 按日期分组会话（置顶优先）
  const groupSessionsByDate = (list: ChatSession[]): SessionGroup[] => {
    const now = new Date();
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    const yesterday = new Date(today.getTime() - 86400000);
    const sevenDaysAgo = new Date(today.getTime() - 7 * 86400000);

    const pinned: ChatSession[] = [];
    const dateGroups: Record<string, ChatSession[]> = {
      '今天': [],
      '昨天': [],
      '7天内': [],
      '更早': [],
    };

    for (const s of list) {
      if (s.isPinned) {
        pinned.push(s);
        continue;
      }
      const d = new Date(s.updatedAt);
      if (d >= today) {
        dateGroups['今天'].push(s);
      } else if (d >= yesterday) {
        dateGroups['昨天'].push(s);
      } else if (d >= sevenDaysAgo) {
        dateGroups['7天内'].push(s);
      } else {
        dateGroups['更早'].push(s);
      }
    }

    const result: SessionGroup[] = [];
    if (pinned.length > 0) {
      result.push({ title: '置顶', data: pinned });
    }
    for (const [title, data] of Object.entries(dateGroups)) {
      if (data.length > 0) {
        result.push({ title, data });
      }
    }
    return result;
  };

  // 选择会话
  const handleSelectSession = (sid: string) => {
    closeSidebar();
    if (sid === sessionId) return;
    // 切换前停掉进行中的流并清空旧会话的待确认账单，避免跨会话状态污染
    streamRef.current?.stop();
    setPendingBills([]);
    loadSessionHistory(sid);
  };

  // 长按会话 — 显示上下文菜单
  const handleSessionLongPress = (session: ChatSession, event: any) => {
    const { pageY } = event.nativeEvent;
    setContextMenu({ session, pageY });
  };

  // 上下文菜单：重命名
  const handleActionRename = () => {
    if (!contextMenu) return;
    setRenamingSession(contextMenu.session);
    setRenameText(contextMenu.session.title || '');
    setContextMenu(null);
    setRenameModalVisible(true);
  };

  // 上下文菜单：置顶/取消置顶
  const handleActionTogglePin = async () => {
    if (!contextMenu) return;
    const sid = contextMenu.session.id;
    setContextMenu(null);
    try {
      const response = await aiService.togglePinChatSession(sid);
      if (response.success && response.data) {
        setSessions(prev =>
          prev.map(s => (s.id === sid ? { ...s, isPinned: response.data!.isPinned } : s)),
        );
      }
    } catch (error) {
      alert('错误', '操作失败，请重试');
    }
  };

  // 上下文菜单：删除
  const handleActionDelete = () => {
    if (!contextMenu) return;
    const sid = contextMenu.session.id;
    setContextMenu(null);
    handleDeleteSession(sid);
  };

  // 删除会话
  const handleDeleteSession = async (sid: string) => {
    try {
      await aiService.deleteChatSession(sid);
      setSessions(prev => prev.filter(s => s.id !== sid));
      // 如果删除的是当前会话，重置为新对话
      if (sid === sessionId) {
        handleNewChat();
      }
    } catch (error) {
      alert('错误', '删除失败，请重试');
    }
  };

  // 确认重命名
  const handleRenameConfirm = async () => {
    if (!renamingSession || !renameText.trim()) return;
    try {
      await aiService.renameChatSession(renamingSession.id, renameText.trim());
      setSessions(prev =>
        prev.map(s => (s.id === renamingSession.id ? { ...s, title: renameText.trim() } : s)),
      );
      setRenameModalVisible(false);
      setRenamingSession(null);
    } catch (error) {
      alert('错误', '重命名失败，请重试');
    }
  };

  // 返回上一页（stack 模式）
  const handleGoBack = () => {
    navigation.goBack();
  };

  // 按住说话的手势处理
  const panResponder = useRef(
    PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onMoveShouldSetPanResponder: () => true,
      onPanResponderGrant: () => {
        console.log('[Gesture] 按下');
        willCancelRef.current = false;
        gestureHandledRef.current = false;
        startRecording().catch(() => {});
      },
      onPanResponderMove: (e: GestureResponderEvent, gestureState: PanResponderGestureState) => {
        // 检测上滑距离
        if (gestureState.dy < -CANCEL_THRESHOLD) {
          if (!willCancelRef.current) {
            console.log('[Gesture] 上滑超过阈值，准备取消');
            willCancelRef.current = true;
            setWillCancel(true);
          }
        } else {
          if (willCancelRef.current) {
            willCancelRef.current = false;
            setWillCancel(false);
          }
        }
      },
      onPanResponderRelease: () => {
        if (gestureHandledRef.current) return;
        gestureHandledRef.current = true;
        console.log('[Gesture] 松开, willCancel:', willCancelRef.current);
        if (willCancelRef.current) {
          cancelRecording().catch(() => {});
        } else {
          stopRecordingAndSend().catch(() => {});
        }
        willCancelRef.current = false;
      },
      onPanResponderTerminate: () => {
        if (gestureHandledRef.current) return;
        gestureHandledRef.current = true;
        console.log('[Gesture] 手势终止');
        willCancelRef.current = false;
        cancelRecording().catch(() => {});
      },
    })
  ).current;

  // 切换语音/文字模式
  const toggleVoiceMode = () => {
    setIsVoiceMode(!isVoiceMode);
  };

  const AI_CONFIG_CACHE_KEY = 'ai_default_config';
  const LAST_SESSION_ID_KEY = 'ai_last_session_id';

  // sessionId 变化时持久化到 storage
  useEffect(() => {
    if (sessionId !== null) {
      storage.setItem(LAST_SESSION_ID_KEY, sessionId);
    }
  }, [sessionId]);

  // 加载分类、账户和默认AI配置
  useEffect(() => {
    loadCategories();
    loadAccounts();
    loadDefaultAIConfigWithCache();
    if (routeSessionId) {
      // 从会话列表导航过来，加载指定会话
      loadSessionHistory(routeSessionId).finally(() => setSessionRestoring(false));
    } else {
      // 普通进入：恢复上次会话
      restoreLastSession();
    }
    // 仅在首次进入页面时恢复会话。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 返回页面时静默刷新 AI 服务商配置、账户与分类（例如刚去设置页新增/切换了服务商）
  useFocusEffect(
    useCallback(() => {
      loadCategories();
      loadAccounts();
      loadDefaultAIConfigWithCache();
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []),
  );

  // 恢复上次会话
  const restoreLastSession = async () => {
    try {
      const lastSid = await storage.getItem<string | number>(LAST_SESSION_ID_KEY);
      if (typeof lastSid === 'string' && lastSid) {
        await loadSessionHistory(lastSid);
      } else if (lastSid !== null) {
        await storage.removeItem(LAST_SESSION_ID_KEY);
      }
    } catch (error) {
      console.error('恢复上次会话失败:', error);
    } finally {
      setSessionRestoring(false);
    }
  };

  // 加载历史会话消息
  const loadSessionHistory = async (sid: string) => {
    try {
      const response = await aiService.getChatSession(sid);
      if (response.success && response.data) {
        setSessionId(sid);
        const { messages: dbMessages } = response.data;
        const displayMessages: Message[] = [];
        for (const msg of dbMessages) {
          if (msg.role === 'user') {
            let imageUrls: string[] | undefined;
            if (msg.imageUrl) {
              try {
                const parsed = JSON.parse(msg.imageUrl);
                imageUrls = Array.isArray(parsed) ? parsed : [msg.imageUrl];
              } catch {
                imageUrls = [msg.imageUrl];
              }
            }
            displayMessages.push({
              id: `db-${msg.id}`,
              type: 'text',
              content: msg.content || '',
              imageUrls,
              isUser: true,
              timestamp: new Date(msg.createdAt),
            });
          } else if (msg.role === 'assistant') {
            if (msg.content) {
              displayMessages.push({
                id: `db-${msg.id}`,
                type: 'text',
                content: msg.content,
                isUser: false,
                timestamp: new Date(msg.createdAt),
              });
            }
          } else if (msg.role === 'tool' && msg.toolName) {
            const toolResult = msg.toolResult as
              | { success?: boolean; message?: string; data?: any }
              | null
              | undefined;
            if (!toolResult?.success) continue;
            const timestamp = new Date(msg.createdAt);
            const fallbackContent = toolResult.message || msg.content || '';
            if (msg.toolName === 'query_bills' && toolResult.data) {
              displayMessages.push({
                id: `db-${msg.id}`,
                type: 'bill_list',
                billListData: toolResult.data,
                content: fallbackContent,
                isUser: false,
                timestamp,
              });
            } else if (msg.toolName === 'get_statistics' && toolResult.data) {
              displayMessages.push({
                id: `db-${msg.id}`,
                type: 'statistics',
                statisticsData: toolResult.data,
                content: fallbackContent,
                isUser: false,
                timestamp,
              });
            } else if (msg.toolName === 'get_accounts' && toolResult.data) {
              displayMessages.push({
                id: `db-${msg.id}`,
                type: 'accounts',
                accountsData: toolResult.data,
                content: fallbackContent,
                isUser: false,
                timestamp,
              });
            } else if (msg.toolName === 'get_budgets' && toolResult.data) {
              displayMessages.push({
                id: `db-${msg.id}`,
                type: 'budgets',
                budgetsData: toolResult.data,
                content: fallbackContent,
                isUser: false,
                timestamp,
              });
            } else if (msg.toolName === 'get_goals' && toolResult.data) {
              displayMessages.push({
                id: `db-${msg.id}`,
                type: 'goals',
                goalsData: toolResult.data,
                content: fallbackContent,
                isUser: false,
                timestamp,
              });
            } else if (
              msg.toolName === 'create_bills' &&
              Array.isArray(toolResult.data?.bills)
            ) {
              const historicalBills: BillItem[] = toolResult.data.bills.map(
                (bill: any, index: number) => ({
                  amount: Number(bill.amount || 0),
                  type: bill.type === 'income' ? 'income' : 'expense',
                  description: bill.description || '',
                  categoryName: bill.categoryName || '',
                  accountName: bill.accountName || undefined,
                  date: bill.date || '',
                  id: `hist-${msg.id}-${index}`,
                  categoryIcon: bill.categoryIcon || undefined,
                  categoryId: bill.categoryId || undefined,
                }),
              );
              if (historicalBills.length > 0) {
                displayMessages.push({
                  id: `db-${msg.id}`,
                  type: 'bills',
                  bills: historicalBills,
                  content: fallbackContent,
                  isUser: false,
                  isHistorical: true,
                  timestamp,
                });
              }
            }
          }
        }
        if (displayMessages.length > 0) {
          setMessages(displayMessages);
        }
      }
    } catch (error) {
      console.error('加载会话历史失败:', error);
    }
  };

  const loadCategories = async () => {
    try {
      setCategories(await nativeData.getCategories());
    } catch (error) {
      console.error('加载分类失败:', error);
    }
  };

  /**
   * 加载可记账账户。
   *
   * 加载失败必须**告知用户**：账户是原生层的硬性入账前提，拿不到账户就等于
   * 这一轮所有账单都保存不了。静默失败会让用户面对"点了保存但什么都没发生"。
   */
  const loadAccounts = async () => {
    try {
      const all = await nativeData.getAccounts();
      setAccounts(all.filter((a) => a.isActive !== false));
    } catch (error) {
      console.error('加载账户失败:', error);
      alert('无法记账', '未能加载账户列表，AI 记账需要先选择一个账户。请稍后重试。');
    }
  };

  /**
   * 取本次保存要用的账户：用户在卡片上显式选的优先，否则用 sortOrder 最小的
   * 活跃账户兜底（与 DefaultDataInitializer 的默认账户顺序一致）。
   *
   * 取不到时返回 null —— 调用方必须据此中止并提示，**不能**继续调用 addBill：
   * 原生 validateBillAccounts 会抛 "Transaction requires an account"，
   * 那样每笔都失败且用户只看到"失败 N 条"，无从判断原因。
   */
  const resolveAccountId = (bill: BillItem): string | null => {
    if (bill.accountId) {
      const selected = accounts.find((a) => a.id === bill.accountId);
      if (selected) {
        return selected.id;
      }
    }
    if (bill.accountName) {
      const matched = matchAccountIdByName(bill.accountName, accounts);
      if (matched) {
        return matched;
      }
    }
    const fallback = [...accounts].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))[0];
    return fallback?.id ?? null;
  };

  // SWR 模式：先读缓存立即显示，再请求接口静默更新
  const loadDefaultAIConfigWithCache = async () => {
    // 1. 先从缓存读取，立即展示（避免闪烁）
    try {
      const cached = await storage.getItem<AIModelConfig>(AI_CONFIG_CACHE_KEY);
      if (cached) {
        setDefaultAIConfig(cached);
        setConfigLoaded(true);
      }
    } catch (error) {
      console.error('读取AI配置缓存失败:', error);
    }

    // 2. 后台请求最新数据（revalidate）
    try {
      const response = await aiService.getConfigs();
      if (response.success && response.data) {
        setAllAIConfigs(response.data);
        const latestConfig = response.data.find(c => c.isDefault) || response.data[0] || null;
        setDefaultAIConfig(latestConfig);
        setSelectedConfigId((prev) =>
          prev && response.data!.some((c) => c.id === prev) ? prev : null,
        );
        if (latestConfig) {
          await storage.setItem(AI_CONFIG_CACHE_KEY, latestConfig);
        } else {
          await storage.removeItem(AI_CONFIG_CACHE_KEY);
        }
      }
    } catch (error) {
      console.error('加载AI配置失败:', error);
    } finally {
      setConfigLoaded(true);
    }
  };

  const addMessage = (message: Omit<Message, 'id' | 'timestamp'>) => {
    const newMessage: Message = {
      ...message,
      id: Date.now().toString() + Math.random().toString(36).substr(2, 9),
      timestamp: new Date(),
    };
    setMessages(prev => [...prev, newMessage]);
    return newMessage.id;
  };

  const updateMessage = (id: string, updates: Partial<Message>) => {
    setMessages(prev => prev.map(m => (m.id === id ? { ...m, ...updates } : m)));
  };

  const handleSend = (overrideText?: string) => {
    const text = (overrideText || inputText).trim();
    const images = [...pendingImages];
    if ((!text && images.length === 0) || isLoading) return;

    // 发送前最后一道闸：pickImage 已按上限拦过，但 pendingImages 也可被
    // 恢复逻辑塞回（见 onError）。这里再确认一次，避免必然失败的请求。
    if (images.length > MAX_IMAGES) {
      alert('图片数量已达上限', `每次最多发送 ${MAX_IMAGES} 张图片。`);
      return;
    }

    // 同理：原生对文本也只在下发时才拒绝（AiChatModule.kt:61-63），
    // 此前用户要写满 8000 字、按下发送、被拒、输入框已被清空。
    if (text.length > MAX_CONTENT_LENGTH) {
      alert('消息过长', `消息内容不能超过 ${MAX_CONTENT_LENGTH} 个字符，当前 ${text.length} 个。`);
      return;
    }

    if (!overrideText) {
      setInputText('');
    }
    setPendingImages([]);

    // 添加用户消息
    addMessage({
      type: 'text',
      content: text || undefined,
      imageUrls: images.length > 0 ? images : undefined,
      isUser: true,
    });

    // 添加流式消息（直接带初始思考步骤，消除"连接中..."）
    const streamStartTime = Date.now();
    const initialStep: StreamStep = {
      id: 'think-1',
      type: 'thinking',
      label: '思考中',
      startTime: streamStartTime,
      isActive: true,
    };
    const streamMsgId = addMessage({
      type: 'streaming',
      isUser: false,
      streamSteps: [initialStep],
      streamingText: '',
      isStreamComplete: false,
    });

    setIsLoading(true);

    let accumulatedText = '';
    let firstTextDelta = true;
    let currentSteps: StreamStep[] = [initialStep];

    const finishActiveSteps = () => {
      currentSteps = currentSteps.map(s =>
        s.isActive ? { ...s, isActive: false, endTime: Date.now() } : s,
      );
    };

    // 在流式消息前面插入工具结果消息
    const insertBeforeStream = (message: Omit<Message, 'id' | 'timestamp'>) => {
      const newMessage: Message = {
        ...message,
        id: Date.now().toString() + Math.random().toString(36).substr(2, 9),
        timestamp: new Date(),
      };
      setMessages(prev => {
        const streamIdx = prev.findIndex(m => m.id === streamMsgId);
        if (streamIdx === -1) {
          return [...prev, newMessage];
        }
        const updated = [...prev];
        updated.splice(streamIdx, 0, newMessage);
        return updated;
      });
    };

    const stream = aiService.streamMessage(
      {
        content: text || '请描述这张图片',
        sessionId: sessionId || undefined,
        configId: activeAIConfig?.id,
        imageBase64List: images.length > 0 ? images : undefined,
      },
      {
        onSessionCreated: (data) => {
          if (!sessionId) {
            setSessionId(data.sessionId);
          }
        },
        onThinking: (data) => {
          // 第 1 轮已在创建消息时初始化，只处理后续轮次
          if (data.round <= 1) return;
          finishActiveSteps();
          const step: StreamStep = {
            id: `think-${data.round}`,
            type: 'thinking',
            label: '思考中',
            startTime: Date.now(),
            isActive: true,
          };
          currentSteps = [...currentSteps, step];
          updateMessage(streamMsgId, { streamSteps: [...currentSteps] });
        },
        onThinkingDelta: (data) => {
          // 将思维链内容追加到当前活跃的 thinking 步骤
          const activeIdx = currentSteps.findIndex(s => s.type === 'thinking' && s.isActive);
          if (activeIdx !== -1) {
            currentSteps = currentSteps.map((s, i) =>
              i === activeIdx
                ? { ...s, thinkingContent: (s.thinkingContent || '') + data.content }
                : s,
            );
            updateMessage(streamMsgId, { streamSteps: [...currentSteps] });
          }
        },
        onTextDelta: (data) => {
          if (firstTextDelta) {
            finishActiveSteps();
            firstTextDelta = false;
          }
          accumulatedText += data.content;
          updateMessage(streamMsgId, {
            streamSteps: [...currentSteps],
            streamingText: accumulatedText,
          });
        },
        onToolCallStart: (data) => {
          finishActiveSteps();
          const displayName = TOOL_DISPLAY_NAMES[data.toolName] || data.toolName;
          const step: StreamStep = {
            id: `tool-${data.toolCallId}`,
            type: 'tool_call',
            label: `调用 ${displayName}`,
            startTime: Date.now(),
            isActive: true,
          };
          currentSteps = [...currentSteps, step];
          updateMessage(streamMsgId, { streamSteps: [...currentSteps] });
        },
        onToolResult: (data) => {
          finishActiveSteps();
          updateMessage(streamMsgId, { streamSteps: [...currentSteps] });

          // 重置文本累积（工具结果后 AI 会继续回复）
          accumulatedText = '';
          firstTextDelta = true;

          // 根据工具类型添加对应消息（插入到流式消息前面）
          if (data.toolName === 'create_bills' && data.result.success) {
            const bills = (data.result.data?.bills || []) as any[];
            const billsWithIds: BillItem[] = bills.map((bill: any, index: number) => {
              const matchedCategoryId =
                bill.categoryId || getCategoryId(bill.categoryName, bill.type);
              const matchedCategory = categories.find((c) => c.id === matchedCategoryId);
              const matchedAccountId = matchAccountIdByName(bill.accountName, accounts);
              return {
                amount: bill.amount,
                type: bill.type,
                description: bill.description || '',
                categoryName: matchedCategory?.name || bill.categoryName || '',
                accountName: bill.accountName || undefined,
                date: bill.date || new Date().toISOString().split('T')[0],
                id: `${Date.now()}-${index}`,
                categoryIcon:
                  bill.categoryIcon || matchedCategory?.icon || getCategoryIcon(bill.categoryName),
                categoryId: matchedCategoryId,
                accountId: matchedAccountId,
              };
            });

            setPendingBills(prev => [...prev, ...billsWithIds]);
            insertBeforeStream({
              type: 'bills',
              bills: billsWithIds,
              content: data.result.message,
              isUser: false,
            });
          } else if (data.toolName === 'query_bills' && data.result.success) {
            insertBeforeStream({
              type: 'bill_list',
              billListData: data.result.data,
              content: data.result.message,
              isUser: false,
            });
          } else if (data.toolName === 'get_statistics' && data.result.success) {
            insertBeforeStream({
              type: 'statistics',
              statisticsData: data.result.data,
              content: data.result.message,
              isUser: false,
            });
          } else if (data.toolName === 'get_accounts' && data.result.success) {
            insertBeforeStream({
              type: 'accounts',
              accountsData: data.result.data,
              content: data.result.message,
              isUser: false,
            });
          } else if (data.toolName === 'get_budgets' && data.result.success) {
            insertBeforeStream({
              type: 'budgets',
              budgetsData: data.result.data,
              content: data.result.message,
              isUser: false,
            });
          } else if (data.toolName === 'get_goals' && data.result.success) {
            insertBeforeStream({
              type: 'goals',
              goalsData: data.result.data,
              content: data.result.message,
              isUser: false,
            });
          } else if (data.toolName === 'delete_bills') {
            insertBeforeStream({
              type: 'text',
              content: data.result.message,
              isUser: false,
            });
          } else if (!data.result.success) {
            insertBeforeStream({
              type: 'error',
              content: data.result.message,
              isUser: false,
            });
          }
        },
        onDone: (data) => {
          finishActiveSteps();
          const totalMs = data.thinkingTimeMs || (Date.now() - streamStartTime);

          // currentSteps 初始必含 thinking 占位步，length===0 永假；
          // 语义应为"既没有文本也没有工具调用"（模型空回复）才移除气泡
          if (!accumulatedText && !currentSteps.some(step => step.type === 'tool_call')) {
            setMessages(prev => prev.filter(m => m.id !== streamMsgId));
          } else {
            updateMessage(streamMsgId, {
              streamSteps: [...currentSteps],
              streamingText: accumulatedText || undefined,
              isStreamComplete: true,
              totalThinkingMs: totalMs,
            });
          }

          setIsLoading(false);
          streamRef.current = null;
        },
        onError: (data) => {
          updateMessage(streamMsgId, {
            type: 'error',
            content: data.message || '请求失败',
            streamSteps: undefined,
            streamingText: undefined,
          });
          // ⚠️ 失败时把附件还回输入区。
          // 发送前 setPendingImages([]) 已经清空过，用户若不重新选就得丢掉
          // 本轮的全部图片（一张单据可能需要重新翻相册去找）。
          // 仅在用户当前没有重新选图时归还，避免与用户的新操作打架。
          if (images.length > 0) {
            setPendingImages(prev => (prev.length === 0 ? images : prev));
          }
          setIsLoading(false);
          streamRef.current = null;
        },
      },
    );

    const stop = () => {
      stream.close();
      finishActiveSteps();
      updateMessage(streamMsgId, {
        streamSteps: [...currentSteps],
        streamingText: accumulatedText || undefined,
        isStreamComplete: true,
        totalThinkingMs: Date.now() - streamStartTime,
      });
      setIsLoading(false);
      streamRef.current = null;
    };

    streamRef.current = { close: stream.close, stop };
  };

  const handleImagePress = () => {
    alert('选择图片来源', '', [
      {
        text: '拍照',
        onPress: () => pickImage('camera'),
      },
      {
        text: '从相册选择',
        onPress: () => pickImage('library'),
      },
      { text: '取消', style: 'cancel' },
    ]);
  };

  const pickImage = async (source: 'camera' | 'library') => {
    const options = {
      mediaType: 'photo' as const,
      quality: 0.8 as const,
      includeBase64: true,
      maxWidth: 1024,
      maxHeight: 1024,
    };

    const callback = (response: ImagePickerResponse) => {
      if (response.didCancel || response.errorCode) return;
      const asset = response.assets?.[0];
      if (!asset?.base64) {
        alert('错误', '无法读取图片');
        return;
      }
      const base64Data = `data:image/jpeg;base64,${asset.base64}`;

      // ⚠️ 原生 readImages（AiChatModule.kt:1069-1086）只接受
      //    `data:image/...;base64,` 开头的字符串，且硬性拒绝超限条目。
      //    前端此前**一个前置校验都没有**：用户连点 5 次"+"凑满 5 张，
      //    点发送必被原生拒绝"每次最多发送 4 张图片"，
      //    而附件在 handleSend 里已被 setPendingImages([]) 清空 ——
      //    5 张图要一张张重新选（相册里那张单据还得重新找到）。
      //    两个上限都镜像到前端，让失败在按下发送之前就发生。
      if (pendingImages.length >= MAX_IMAGES) {
        alert('图片数量已达上限', `每次最多发送 ${MAX_IMAGES} 张图片。`);
        return;
      }
      if (base64Data.length > MAX_IMAGE_BASE64_LENGTH) {
        alert(
          '图片过大',
          `第 ${pendingImages.length + 1} 张图片编码后过大，请换一张更小的图片。`,
        );
        return;
      }
      setPendingImages(prev => [...prev, base64Data]);
    };

    if (source === 'camera') {
      launchCamera(options, callback);
    } else {
      launchImageLibrary(options, callback);
    }
  };

  /** 分类名 → 图标：统一走 constants/app.ts 的 DEFAULT_CATEGORIES 查找 */
  const getCategoryIcon = (categoryName: string): string =>
    getDefaultCategoryIcon(categoryName, '📦');

  /**
   * 把 LLM 给的分类名映射到本地分类 ID。
   *
   * 此前是**精确字符串相等**，模型输出"餐饮美食"/"外卖"/"打车"这类近义名
   * 一律匹配失败 ⇒ 账单带 `categoryId = null` 落库 —— 这是静默的脏数据，
   * 用户在账单列表里看到一条"无分类"记录却没有任何提示。
   *
   * 三级兜底（对齐 PendingConfirmOverlayManager.resolveDefaultCategoryId 的思路）：
   * 精确匹配 → 包含关系唯一命中 → "其他"分类（稳定 ID，不依赖本地名称）。
   * 都不中时返回 undefined，由调用方在保存前提示用户手动选分类。
   */
  const getCategoryId = (
    categoryName: string,
    type: 'income' | 'expense',
  ): string | undefined => {
    const candidates = categories.filter((c) => c.type === type);
    const exact = candidates.find((c) => c.name === categoryName);
    if (exact) {
      return exact.id;
    }
    const trimmed = categoryName.trim();
    if (trimmed) {
      const fuzzy = candidates.filter((c) => c.name.includes(trimmed) || trimmed.includes(c.name));
      // 只有唯一命中才算数：多个候选时说明歧义，宁可交给用户也不要猜错。
      if (fuzzy.length === 1) {
        return fuzzy[0].id;
      }
    }
    return categories.find((c) => c.id === FALLBACK_CATEGORY_IDS[type])?.id;
  };

  const handleEditBill = (bill: BillItem) => {
    setEditingBill(bill);
    setShowEditModal(true);
  };

  const handleSaveBill = (updatedBill: BillItem) => {
    setPendingBills(prev => prev.map(b => (b.id === updatedBill.id ? updatedBill : b)));
    setMessages(prev =>
      prev.map(m => {
        if (m.bills) {
          return {
            ...m,
            bills: m.bills.map(b => (b.id === updatedBill.id ? updatedBill : b)),
          };
        }
        return m;
      })
    );
    setShowEditModal(false);
    setEditingBill(null);
  };

  const handleDeleteBill = (id: string) => {
    confirm(
      '确认删除',
      '确定要删除这条账单吗？',
      () => {
        setPendingBills(prev => prev.filter(b => b.id !== id));
        setMessages(prev =>
          prev.map(m => {
            if (m.bills) {
              const newBills = m.bills.filter(b => b.id !== id);
              if (newBills.length === 0) {
                return { ...m, type: 'text' as const, content: '所有账单已删除', bills: undefined };
              }
              return { ...m, bills: newBills };
            }
            return m;
          })
        );
      },
      undefined,
      { confirmText: '删除', destructive: true },
    );
  };

  /**
   * P12：AI 提案保存前查重 —— 同日、同金额、同收支方向的既有账单。
   * AI 提案不带 orderId，原生 level-1 去重永不命中；同一笔既手动记过又
   * 确认 AI 提案时此前必然双记。命中时由用户显式确认后再入账。
   * 判据实现已收敛到共享工具 [billDuplicateCheck]（M8：手动录入屏复用同款）。
   */
  const handleSaveAllBills = async () => {
    if (pendingBills.length === 0) {
      alert('提示', '没有待保存的账单');
      return;
    }

    // 原生 validateBillAccounts 无条件要求 accountId。拿不到账户就整批中止，
    // 并明确告知 —— 否则每笔都会以 "Transaction requires an account" 失败，
    // 用户只看到"失败 N 条"，无从判断是自己没选账户还是系统问题。
    const fallbackAccountId = resolveAccountId(pendingBills[0]);
    if (!fallbackAccountId) {
      alert(
        '无法记账',
        '没有可用的账户。请先到「通用设置 → 账户管理」启用至少一个账户，再回到这里记账。',
      );
      return;
    }

    // P12：保存前查重提示；查重查询失败时保守放行（责任回到用户确认弹窗）。
    let duplicates: string[] = [];
    try {
      duplicates = await findDuplicateBills(pendingBills);
    } catch (error) {
      console.warn('AI 账单保存前查重失败，按无重复继续', error);
    }
    if (duplicates.length > 0) {
      confirm(
        '疑似重复',
        `以下 ${duplicates.length} 条与既有账单同日同金额：\n${duplicates.join('、')}\n\n仍要保存吗？`,
        () => { void doSaveAllBills(fallbackAccountId); },
        undefined,
        { confirmText: '仍要保存' },
      );
      return;
    }
    await doSaveAllBills(fallbackAccountId);
  };

  /** 实际批量入账（P12 查重通过/用户确认后进入）。 */
  const doSaveAllBills = async (fallbackAccountId: string) => {
    setSavingBills(true);
    let successCount = 0;
    let failCount = 0;
    const failedBillIds: string[] = [];
    const savedBillIds: string[] = [];
    const failureReasons: string[] = [];

    for (const bill of pendingBills) {
      try {
        const accountId = resolveAccountId(bill) ?? fallbackAccountId;
        // 保存时**重新解析**分类：提案那一刻算出的 categoryId 来自当时 render
        // 闭包里的 categories，而 categories 是 mount 时异步加载的；若加载失败
        // 或慢于提案到达，那批 bill 的 categoryId 会全是 undefined。重新解析
        // 才能保证"分类在保存瞬间是有效的"。
        const categoryId =
          bill.categoryId ?? getCategoryId(bill.categoryName ?? '', bill.type);
        await nativeData.addBill({
          amountMinor: Math.round(bill.amount * 100),
          type: bill.type,
          description: bill.description,
          occurredAt: new Date(`${bill.date}T12:00:00`).toISOString(),
          categoryId,
          accountId,
          sourceType: 'ai_chat',
          // P15：提案 id 即幂等键 —— 部分失败后的整批重试、重复点击
          // 「全部保存」由原生按确定性主键幂等，不再重复入账。
          clientRequestId: bill.id,
        });
        successCount++;
        savedBillIds.push(bill.id);
      } catch (error) {
        failCount++;
        failedBillIds.push(bill.id);
        // 失败原因必须呈现给用户：此前只 console.error，用户看到的是
        // "成功 0 条，失败 1 条"，无法判断是金额越界、账户失效还是分类非法。
        const reason = error instanceof Error ? error.message : String(error);
        failureReasons.push(`${bill.description || '未命名'}: ${reason}`);
        console.error('保存账单失败:', error);
      }
    }

    setSavingBills(false);

    // 已成功的必须从待保存列表移除：此前只有"全部成功"才 setPendingBills([])，
    // 部分失败时列表原样保留，用户再点一次"全部保存"会把已成功的那几条
    // **再插一遍**。而本路径不带 orderId，DuplicateGuard.checkLevel1 第一行
    // 就是 `if (orderId.isNullOrBlank()) return null` ⇒ 去重必然不命中，
    // 重试 = 重复账单。
    if (savedBillIds.length > 0) {
      setPendingBills((prev) => prev.filter((b) => !savedBillIds.includes(b.id)));
    }

    // 保存成功后必须刷新 RN 缓存：原生确实广播了 onBillCreated，但全仓
    // 没有任何监听器订阅它（grep 零命中），而 queryClient 的
    // refetchOnWindowFocus 为 false —— 用户切到账单页看到的仍是旧缓存。
    if (successCount > 0) {
      invalidateCache.bills();
    }

    if (failCount === 0) {
      alert('保存成功', `已成功保存 ${successCount} 条账单`);
      // 添加成功消息
      addMessage({
        type: 'text',
        content: `✅ 已成功保存 ${successCount} 条账单到账本`,
        isUser: false,
      });
    } else {
      const detail = failureReasons.length > 0 ? `\n\n${failureReasons.join('\n')}` : '';
      alert(
        '保存完成',
        `成功 ${successCount} 条，失败 ${failCount} 条。` +
          `失败项已保留在列表中，可修改后重试。${detail}`,
      );
    }
  };

  // 渲染 AI 头像
  const renderAIAvatar = () => {
    if (isHogwarts) {
      return (
        <View
          style={[
            styles.aiAvatarContainer,
            {
              overflow: 'hidden',
              borderColor: themeColors.accent,
              borderWidth: 1.5,
              borderRadius: 16,
            },
          ]}
        >
          <Image
            source={HOGWARTS_IMAGES.aiMascot}
            style={{ width: '100%', height: '100%' }}
            resizeMode="cover"
          />
        </View>
      );
    }
    if (activeAIConfig?.provider) {
      return (
        <View style={styles.aiAvatarContainer}>
          <ProviderIcon provider={activeAIConfig.provider} size={24} />
        </View>
      );
    }
    // 配置未加载完成时显示占位圆形，避免从🤖闪烁到供应商图标
    if (!configLoaded) {
      return <View style={styles.aiAvatarPlaceholder} />;
    }
    return <Text style={styles.aiAvatar}>🤖</Text>;
  };

  const renderMessage = ({ item }: { item: Message }) => {
    if (item.type === 'loading') {
      return (
        <View style={[styles.messageBubble, styles.aiBubble]}>
          {renderAIAvatar()}
          <View style={[styles.messageContent, styles.aiContent]}>
            <ActivityIndicator size="small" color={styles._colors.primary} />
            <Text style={styles.loadingText}>思考中...</Text>
          </View>
        </View>
      );
    }

    if (item.type === 'streaming') {
      const hasText = !!item.streamingText;
      const hasSteps = (item.streamSteps || []).length > 0;
      return (
        <View style={[styles.messageBubble, styles.aiBubble]}>
          {renderAIAvatar()}
          <View style={[styles.messageContent, styles.aiContent]}>
            {hasSteps && (
              <StreamStepsTimeline
                steps={item.streamSteps!}
                isComplete={item.isStreamComplete || false}
                totalMs={item.totalThinkingMs}
                colors={styles._colors}
              />
            )}
            {hasText && (
              <Text style={styles.messageText}>{item.streamingText}</Text>
            )}
            {!hasText && !hasSteps && (
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                <ActivityIndicator size="small" color={styles._colors.primary} />
                <Text style={{ fontSize: 14, color: styles._colors.textSecondary }}>连接中...</Text>
              </View>
            )}
          </View>
        </View>
      );
    }

    if (item.type === 'bills' && item.bills && item.bills.length > 0) {
      return (
        <View style={styles.billsContainer}>
          <View style={[styles.messageBubble, styles.aiBubble]}>
            {renderAIAvatar()}
            <View style={[styles.messageContent, styles.aiContent]}>
              <Text style={styles.messageText}>{item.content}</Text>
            </View>
          </View>
          {item.bills.map(bill => {
            const matchedCategory = categories.find(
              (c) =>
                (bill.categoryId && c.id === bill.categoryId) ||
                (c.type === bill.type && c.name === bill.categoryName),
            );
            const resolvedBill: BillItem = {
              ...bill,
              categoryIcon:
                bill.categoryIcon || matchedCategory?.icon || getCategoryIcon(bill.categoryName),
            };
            const matchedAccount = item.isHistorical
              ? accounts.find(
                  (a) =>
                    (bill.accountId && a.id === bill.accountId) ||
                    (bill.accountName && a.id === matchAccountIdByName(bill.accountName, accounts)),
                )
              : accounts.find((a) => a.id === resolveAccountId(bill));
            return (
              <BillCard
                key={bill.id}
                bill={resolvedBill}
                account={matchedAccount}
                readOnly={Boolean(item.isHistorical)}
                onEdit={handleEditBill}
                onDelete={handleDeleteBill}
                colors={styles._colors}
              />
            );
          })}
        </View>
      );
    }

    if (item.type === 'bill_list' && item.billListData) {
      const bills = item.billListData?.data || [];
      return (
        <View style={styles.billsContainer}>
          <View style={[styles.messageBubble, styles.aiBubble]}>
            {renderAIAvatar()}
            <View style={[styles.messageContent, styles.aiContent]}>
              <Text style={styles.messageText}>{item.content}</Text>
              {bills.length > 0 ? (
                <View style={styles.toolListWrap}>
                  {bills.map((bill: any) => {
                    const isExpenseRow = bill.type === 'expense' || bill.type === 'fee';
                    const metaText = [
                      bill.date?.substring(0, 10),
                      bill.categoryName,
                      bill.platform,
                      bill.accountName,
                    ]
                      .filter(Boolean)
                      .join(' · ');
                    return (
                      <View key={bill.id} style={styles.toolBillRow}>
                        <View style={styles.toolBillLeft}>
                          <Text style={styles.toolBillDesc} numberOfLines={1}>
                            {bill.description || bill.categoryName || '-'}
                          </Text>
                          {metaText ? (
                            <Text style={styles.toolBillMeta} numberOfLines={1}>
                              {metaText}
                            </Text>
                          ) : null}
                        </View>
                        <Text
                          style={[
                            styles.toolBillAmount,
                            { color: isExpenseRow ? styles._colors.expense : styles._colors.income },
                          ]}
                        >
                          {formatSignedCurrency(Number(bill.amount), !isExpenseRow)}
                        </Text>
                      </View>
                    );
                  })}
                </View>
              ) : (
                <Text style={[styles.messageText, styles.toolEmptyText]}>暂无账单记录</Text>
              )}
            </View>
          </View>
        </View>
      );
    }

    if (item.type === 'statistics' && item.statisticsData) {
      const stats = item.statisticsData;
      const expenseByCategory = Array.isArray(stats.expenseByCategory)
        ? stats.expenseByCategory.slice(0, 5)
        : [];
      const incomeByCategory = Array.isArray(stats.incomeByCategory)
        ? stats.incomeByCategory.slice(0, 5)
        : [];
      const breakdownList = expenseByCategory.length > 0 ? expenseByCategory : incomeByCategory;
      const breakdownIsExpense = expenseByCategory.length > 0;
      const platformBreakdown = Array.isArray(stats.platformBreakdown)
        ? stats.platformBreakdown.slice(0, 4)
        : [];
      const monthlyTrends = Array.isArray(stats.monthlyTrends)
        ? stats.monthlyTrends.slice(-4)
        : [];
      const rangeText =
        stats.startDate || stats.endDate
          ? `${stats.startDate || '起始'} ~ ${stats.endDate || '至今'}`
          : null;

      return (
        <View style={styles.billsContainer}>
          <View style={[styles.messageBubble, styles.aiBubble]}>
            {renderAIAvatar()}
            <View style={[styles.messageContent, styles.aiContent]}>
              <Text style={styles.messageText}>{item.content}</Text>
              <View style={styles.toolStatsContainer}>
                {rangeText ? <Text style={styles.toolCardCaption}>{rangeText}</Text> : null}
                <View style={styles.toolStatsRow}>
                  <Text style={styles.toolStatsLabel}>总支出</Text>
                  <Text style={[styles.toolStatsValue, { color: styles._colors.expense }]}>
                    {formatCurrency(Number(stats.totalExpense || 0))}
                  </Text>
                </View>
                <View style={styles.toolStatsRow}>
                  <Text style={styles.toolStatsLabel}>总收入</Text>
                  <Text style={[styles.toolStatsValue, { color: styles._colors.income }]}>
                    {formatCurrency(Number(stats.totalIncome || 0))}
                  </Text>
                </View>
                <View style={[styles.toolStatsRow, styles.toolStatsRowLast]}>
                  <Text style={styles.toolStatsLabel}>结余</Text>
                  <Text style={styles.toolStatsBalance}>
                    {formatCurrency(Number(stats.totalIncome || 0) - Number(stats.totalExpense || 0))}
                  </Text>
                </View>
                {breakdownList.length > 0 ? (
                  <View style={styles.toolBreakdownWrap}>
                    <Text style={styles.toolBreakdownTitle}>
                      {breakdownIsExpense ? '主要支出分类' : '主要收入分类'}
                    </Text>
                    {breakdownList.map((row: any, idx: number) => {
                      const pct = Math.max(0, Math.min(100, Number(row.percentage || 0)));
                      return (
                        <View key={`${row.categoryName}-${idx}`} style={styles.toolBreakdownRow}>
                          <View style={styles.toolBreakdownHeader}>
                            <Text style={styles.toolBreakdownName} numberOfLines={1}>
                              {row.categoryName} ({pct}%)
                            </Text>
                            <Text style={styles.toolBreakdownAmount}>
                              {formatCurrency(Number(row.amount || 0))}
                            </Text>
                          </View>
                          <View style={styles.toolProgressTrack}>
                            <View
                              style={[
                                styles.toolProgressFill,
                                {
                                  width: `${Math.max(4, pct)}%`,
                                  backgroundColor: breakdownIsExpense
                                    ? styles._colors.expense
                                    : styles._colors.income,
                                },
                              ]}
                            />
                          </View>
                        </View>
                      );
                    })}
                  </View>
                ) : null}
                {platformBreakdown.length > 0 ? (
                  <View style={styles.toolBreakdownWrap}>
                    <Text style={styles.toolBreakdownTitle}>消费平台分布</Text>
                    {platformBreakdown.map((row: any, idx: number) => (
                      <View key={`${row.platform}-${idx}`} style={styles.toolBreakdownHeader}>
                        <Text style={styles.toolBreakdownName} numberOfLines={1}>
                          {row.platform} ({Number(row.percentage || 0)}%)
                        </Text>
                        <Text style={styles.toolBreakdownAmount}>
                          {formatCurrency(Number(row.amount || 0))}
                        </Text>
                      </View>
                    ))}
                  </View>
                ) : null}
                {monthlyTrends.length > 1 ? (
                  <View style={styles.toolBreakdownWrap}>
                    <Text style={styles.toolBreakdownTitle}>按月收支趋势</Text>
                    {monthlyTrends.map((row: any, idx: number) => (
                      <View key={`${row.month}-${idx}`} style={styles.toolBreakdownHeader}>
                        <Text style={styles.toolBreakdownName}>{row.month}</Text>
                        <Text style={styles.toolBreakdownAmount}>
                          支 {formatCurrency(Number(row.expense || 0))} / 收{' '}
                          {formatCurrency(Number(row.income || 0))}
                        </Text>
                      </View>
                    ))}
                  </View>
                ) : null}
              </View>
            </View>
          </View>
        </View>
      );
    }

    if (item.type === 'accounts' && item.accountsData) {
      const accountRows = Array.isArray(item.accountsData.accounts)
        ? item.accountsData.accounts
        : [];
      return (
        <View style={styles.billsContainer} testID={`ai-accounts-card-${item.id}`}>
          <View style={[styles.messageBubble, styles.aiBubble]}>
            {renderAIAvatar()}
            <View style={[styles.messageContent, styles.aiContent]}>
              <Text style={styles.messageText}>{item.content}</Text>
              <View style={styles.toolStatsContainer}>
                <View style={styles.toolStatsRow}>
                  <Text style={styles.toolStatsLabel}>净资产</Text>
                  <Text style={styles.toolStatsBalance}>
                    {formatCurrency(Number(item.accountsData.netWorth || 0))}
                  </Text>
                </View>
                <View style={styles.toolStatsRow}>
                  <Text style={styles.toolStatsLabel}>总资产 / 总负债</Text>
                  <Text style={styles.toolSubAmount}>
                    {formatCurrency(Number(item.accountsData.totalAssets || 0))} /{' '}
                    {formatCurrency(Number(item.accountsData.totalLiabilities || 0))}
                  </Text>
                </View>
                {accountRows.map((acc: any, idx: number) => (
                  <View
                    key={acc.id || `${acc.name}-${idx}`}
                    style={[
                      styles.toolAccountRow,
                      idx === accountRows.length - 1 && styles.toolStatsRowLast,
                    ]}
                  >
                    <View style={styles.toolAccountLeft}>
                      <AccountIcon
                        name={acc.name}
                        subtype={acc.subtype}
                        size={16}
                        color={styles._colors.primary}
                      />
                      <Text style={styles.toolAccountName} numberOfLines={1}>
                        {acc.name}
                        {acc.cardTail ? ` · ${acc.cardTail}` : ''}
                      </Text>
                    </View>
                    <Text
                      style={[
                        styles.toolBillAmount,
                        {
                          color:
                            acc.kind === 'liability'
                              ? styles._colors.expense
                              : styles._colors.textPrimary,
                        },
                      ]}
                    >
                      {formatCurrency(Number(acc.balance || 0))}
                    </Text>
                  </View>
                ))}
              </View>
            </View>
          </View>
        </View>
      );
    }

    if (item.type === 'budgets' && item.budgetsData) {
      const budgetRows = Array.isArray(item.budgetsData.budgets)
        ? item.budgetsData.budgets
        : [];
      return (
        <View style={styles.billsContainer} testID={`ai-budgets-card-${item.id}`}>
          <View style={[styles.messageBubble, styles.aiBubble]}>
            {renderAIAvatar()}
            <View style={[styles.messageContent, styles.aiContent]}>
              <Text style={styles.messageText}>{item.content}</Text>
              {budgetRows.length > 0 ? (
                <View style={styles.toolStatsContainer}>
                  {budgetRows.map((budget: any, idx: number) => {
                    const pct = Math.max(0, Number(budget.progressPct || 0));
                    const barColor = budget.isOverBudget
                      ? styles._colors.error
                      : pct >= Number(budget.alertAt || 80)
                        ? styles._colors.warning
                        : styles._colors.primary;
                    return (
                      <View
                        key={budget.id || `${budget.name}-${idx}`}
                        style={[
                          styles.toolBudgetRow,
                          idx === budgetRows.length - 1 && styles.toolStatsRowLast,
                        ]}
                      >
                        <View style={styles.toolBreakdownHeader}>
                          <Text style={styles.toolBreakdownName} numberOfLines={1}>
                            {budget.name}
                            {budget.periodLabel ? ` · ${budget.periodLabel}` : ''}
                          </Text>
                          <Text style={[styles.toolBudgetPct, { color: barColor }]}>
                            {pct}%
                          </Text>
                        </View>
                        <View style={styles.toolProgressTrack}>
                          <View
                            style={[
                              styles.toolProgressFill,
                              {
                                width: `${Math.min(100, Math.max(pct > 0 ? 4 : 0, pct))}%`,
                                backgroundColor: barColor,
                              },
                            ]}
                          />
                        </View>
                        <View style={styles.toolBudgetFoot}>
                          <Text style={styles.toolBillMeta}>
                            已用 {formatCurrency(Number(budget.spent || 0))} /{' '}
                            {formatCurrency(Number(budget.limit || 0))}
                          </Text>
                          <Text style={[styles.toolBillMeta, budget.isOverBudget && { color: styles._colors.error }]}>
                            {budget.isOverBudget
                              ? `超支 ${formatCurrency(Math.abs(Number(budget.remaining || 0)))}`
                              : `剩余 ${formatCurrency(Number(budget.remaining || 0))}`}
                          </Text>
                        </View>
                      </View>
                    );
                  })}
                </View>
              ) : (
                <Text style={[styles.messageText, styles.toolEmptyText]}>暂无启用中的预算</Text>
              )}
            </View>
          </View>
        </View>
      );
    }

    if (item.type === 'goals' && item.goalsData) {
      const goalRows = Array.isArray(item.goalsData.goals) ? item.goalsData.goals : [];
      return (
        <View style={styles.billsContainer} testID={`ai-goals-card-${item.id}`}>
          <View style={[styles.messageBubble, styles.aiBubble]}>
            {renderAIAvatar()}
            <View style={[styles.messageContent, styles.aiContent]}>
              <Text style={styles.messageText}>{item.content}</Text>
              {goalRows.length > 0 ? (
                <View style={styles.toolStatsContainer}>
                  {goalRows.map((goal: any, idx: number) => {
                    const pct = Math.max(0, Number(goal.progressPct || 0));
                    const barColor = goal.isCompleted
                      ? styles._colors.income
                      : styles._colors.primary;
                    const deadlineText = goal.targetDate
                      ? typeof goal.daysLeft === 'number' && goal.daysLeft >= 0
                        ? ` · 剩${goal.daysLeft}天`
                        : ` · ${goal.targetDate}`
                      : '';
                    return (
                      <View
                        key={goal.id || `${goal.name}-${idx}`}
                        style={[
                          styles.toolBudgetRow,
                          idx === goalRows.length - 1 && styles.toolStatsRowLast,
                        ]}
                      >
                        <View style={styles.toolBreakdownHeader}>
                          <Text style={styles.toolBreakdownName} numberOfLines={1}>
                            {goal.name}
                            {deadlineText}
                          </Text>
                          <Text style={[styles.toolBudgetPct, { color: barColor }]}>
                            {pct}%
                          </Text>
                        </View>
                        <View style={styles.toolProgressTrack}>
                          <View
                            style={[
                              styles.toolProgressFill,
                              {
                                width: `${Math.min(100, Math.max(pct > 0 ? 4 : 0, pct))}%`,
                                backgroundColor: barColor,
                              },
                            ]}
                          />
                        </View>
                        <View style={styles.toolBudgetFoot}>
                          <Text style={styles.toolBillMeta}>
                            已存 {formatCurrency(Number(goal.current || 0))} /{' '}
                            {formatCurrency(Number(goal.target || 0))}
                          </Text>
                          <Text
                            style={[
                              styles.toolBillMeta,
                              goal.isCompleted && { color: styles._colors.income },
                            ]}
                          >
                            {goal.isCompleted
                              ? '已达成'
                              : `还差 ${formatCurrency(Number(goal.remaining || 0))}`}
                          </Text>
                        </View>
                      </View>
                    );
                  })}
                </View>
              ) : (
                <Text style={[styles.messageText, styles.toolEmptyText]}>当前未设置财务目标</Text>
              )}
            </View>
          </View>
        </View>
      );
    }

    if (item.type === 'error') {
      return (
        <View style={[styles.messageBubble, styles.aiBubble]}>
          {renderAIAvatar()}
          <View style={[styles.messageContent, styles.errorContent]}>
            <Text style={styles.errorText}>❌ {item.content}</Text>
          </View>
        </View>
      );
    }

    if (isSoft && !item.isUser && item.id === 'welcome' && item.content === WELCOME_MESSAGE) {
      return (
        <View style={styles.softWelcomeWrap}>
          <View style={styles.softWelcomeHead}>
            <View style={styles.softWelcomeAvatarWrap}>
              <SoftGradient
                colors={[colors.gradientPrimaryStart, colors.primary, colors.gradientPrimaryEnd]}
                style={styles.softWelcomeAvatar}
              >
                <Sparkles size={28} color="#FFFFFF" strokeWidth={1.8} />
              </SoftGradient>
              <View style={styles.softWelcomeDot} />
            </View>
            <Text style={styles.softWelcomeTitle}>嗨，我是招财猫猫</Text>
            <Text style={styles.softWelcomeDesc}>
              问我账单与花销，或者直接说「午饭 28」。我生成的账单都会先待你确认，不会直接改你的账本。
            </Text>
          </View>
          <View style={styles.softSuggestionList}>
            {SOFT_SUGGESTIONS.map(sug => (
              <TouchableOpacity
                key={sug.key}
                testID={`ai-suggestion-${sug.key}`}
                style={styles.softSuggestionCard}
                onPress={() => handleSend(sug.prompt)}
                disabled={isLoading}
                activeOpacity={0.78}
              >
                {sug.icon === 'pencil' ? (
                  <Pencil size={16} color={colors.primary} strokeWidth={2} />
                ) : sug.icon === 'wallet' ? (
                  <Wallet size={16} color={colors.primary} strokeWidth={2} />
                ) : sug.icon === 'target' ? (
                  <Target size={16} color={colors.primary} strokeWidth={2} />
                ) : (
                  <Sparkles size={16} color={colors.primary} strokeWidth={2} />
                )}
                <Text style={styles.softSuggestionText} numberOfLines={1}>
                  {sug.prompt}
                </Text>
                <ChevronRight size={16} color={colors.textQuaternary} strokeWidth={2} />
              </TouchableOpacity>
            ))}
          </View>
        </View>
      );
    }

    return (
      <View
        style={[styles.messageBubble, item.isUser ? styles.userBubble : styles.aiBubble]}
      >
        {!item.isUser && renderAIAvatar()}
        <View
          style={[
            styles.messageContent,
            item.isUser ? styles.userContent : styles.aiContent,
          ]}
        >
          {item.imageUrls && item.imageUrls.length > 0 && (
            <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.chatImageList}>
              {item.imageUrls.map((uri, idx) => (
                <TouchableOpacity
                  key={idx}
                  activeOpacity={0.8}
                  onPress={() => {
                    setImageViewerUrl(uri);
                    setImageViewerVisible(true);
                  }}
                >
                  <Image
                    source={{ uri }}
                    style={[styles.chatImage, idx > 0 && { marginLeft: spacing.xs }]}
                    resizeMode="cover"
                  />
                </TouchableOpacity>
              ))}
            </ScrollView>
          )}
          {item.content && (
            <Text style={[styles.messageText, item.isUser && styles.userText]}>
              {item.id === 'welcome' && isHogwarts
                ? HOGWARTS_WELCOME_MESSAGE
                : item.content}
            </Text>
          )}
        </View>
      </View>
    );
  };

  return (
    <KeyboardAvoidingView
      style={styles.container}
      behavior="padding"
      keyboardVerticalOffset={Platform.OS === 'ios' ? 90 : insets.bottom}
    >
      {/* 顶部导航栏 */}
      <View style={[styles.chatHeader, isStackMode && { paddingTop: spacing.sm + insets.top }]}>
        {isStackMode ? (
          <TouchableOpacity
            style={styles.headerButton}
            onPress={handleGoBack}
            activeOpacity={0.7}
          >
            <ChevronLeft size={22} color={styles._colors.textPrimary} />
          </TouchableOpacity>
        ) : (
          <TouchableOpacity
            style={styles.headerButton}
            onPress={openSidebar}
            activeOpacity={0.7}
          >
            <Menu size={22} color={styles._colors.textPrimary} />
          </TouchableOpacity>
        )}
        <View style={styles.headerCenterWrap}>
          <Text
            style={isSoft ? styles.softHeaderTitle : styles.headerTitleText}
            numberOfLines={1}
          >
            {isHogwarts ? '招财巫师猫' : 'AI 助手'}
          </Text>
          {activeAIConfig ? (
            <TouchableOpacity
              testID="ai-header-model-switcher"
              style={styles.headerProviderPill}
              onPress={() => setShowModelSwitcher(true)}
              activeOpacity={0.7}
            >
              <View style={styles.softProviderDot} />
              <Text style={styles.softProviderText} numberOfLines={1}>
                {activeAIConfig.name}
              </Text>
              <ChevronDown size={12} color={styles._colors.textTertiary} />
            </TouchableOpacity>
          ) : null}
        </View>
        <TouchableOpacity
          style={styles.headerButton}
          onPress={handleNewChat}
          activeOpacity={0.7}
        >
          <SquarePen size={20} color={styles._colors.primary} />
        </TouchableOpacity>
      </View>

      {/* 未配置 AI 服务商引导横幅 */}
      {configLoaded && !activeAIConfig ? (
        <View style={styles.unconfiguredCard} testID="ai-unconfigured-banner">
          <View style={styles.unconfiguredBody}>
            <Text style={styles.unconfiguredTitle}>尚未配置 AI 服务商</Text>
            <Text style={styles.unconfiguredDesc}>
              添加通义千问、DeepSeek、智谱 GLM 或自定义兼容接口后，即可开启智能记账与账务分析。
            </Text>
          </View>
          <TouchableOpacity
            testID="ai-setup-cta-btn"
            style={styles.unconfiguredButton}
            onPress={() => (navigation as any).navigate('AiProviders')}
            activeOpacity={0.8}
          >
            <Settings size={14} color={isSoft || isHogwarts ? styles._colors.onPrimary : '#FFFFFF'} />
            <Text style={styles.unconfiguredButtonText}>去配置</Text>
          </TouchableOpacity>
        </View>
      ) : null}

      {/* 消息列表 */}
      {sessionRestoring ? (
        <ChatSkeleton />
      ) : (
        <FlatList
          ref={flatListRef}
          style={styles.messageListContainer}
          data={messages}
          renderItem={renderMessage}
          keyExtractor={item => item.id}
          contentContainerStyle={styles.messageList}
          onContentSizeChange={() => flatListRef.current?.scrollToEnd()}
        />
      )}

      {/* 待保存账单提示 */}
      {pendingBills.length > 0 && (
        <View style={styles.pendingBar}>
          <Text style={styles.pendingText}>
            {pendingBills.length} 条账单待保存
          </Text>
          <TouchableOpacity
            style={styles.saveAllButton}
            onPress={handleSaveAllBills}
            disabled={savingBills}
          >
            {savingBills ? (
              <ActivityIndicator size="small" color={isSoft ? styles._colors.onPrimary : '#FFFFFF'} />
            ) : (
              <>
                <Check size={16} color={isSoft ? styles._colors.onPrimary : '#FFFFFF'} />
                <Text style={styles.saveAllButtonText}>全部保存</Text>
              </>
            )}
          </TouchableOpacity>
        </View>
      )}

      {/* 语音录音中的提示和声波 */}
      {isRecording && (
        <View style={styles.recordingOverlay}>
          <Text style={[styles.recordingHint, willCancel && styles.recordingHintCancel]}>
            {willCancel ? '松开取消' : '松开发送  上移取消'}
          </Text>
          <View style={styles.waveContainer}>
            {waveAnims.map((anim, index) => (
              <Animated.View
                key={index}
                style={[
                  styles.waveBar,
                  willCancel && styles.waveBarCancel,
                  { transform: [{ scaleY: anim }] },
                ]}
              />
            ))}
          </View>
        </View>
      )}

      {/* 待发送图片预览栏 */}
      {pendingImages.length > 0 && (
        <View style={styles.pendingImagesContainer}>
          {/* 让上限可见：不给计数的话，用户只能靠撞到第 5 张被原生拒绝才知道有上限 */}
          <Text style={styles.pendingImagesCounter}>
            {pendingImages.length}/{MAX_IMAGES}
          </Text>
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.pendingImagesScroll}>
            {pendingImages.map((uri, index) => (
              <View key={index} style={styles.pendingImageWrapper}>
                <TouchableOpacity
                  activeOpacity={0.8}
                  onPress={() => {
                    setImageViewerUrl(uri);
                    setImageViewerVisible(true);
                  }}
                >
                  <Image
                    source={{ uri }}
                    style={styles.pendingImageThumb}
                    resizeMode="cover"
                  />
                </TouchableOpacity>
                <TouchableOpacity
                  style={styles.pendingImageRemove}
                  onPress={() => setPendingImages(prev => prev.filter((_, i) => i !== index))}
                  hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
                >
                  <X size={12} color="#FFFFFF" strokeWidth={3} />
                </TouchableOpacity>
              </View>
            ))}
            <TouchableOpacity
              style={styles.pendingImageAdd}
              onPress={handleImagePress}
              activeOpacity={0.7}
            >
              <Plus size={24} color={styles._colors.textTertiary} strokeWidth={2} />
            </TouchableOpacity>
          </ScrollView>
        </View>
      )}

      {/* 输入区域 */}
      <View
        style={[
          styles.inputContainer,
          isSoft
            ? {
                marginBottom:
                  !isStackMode && !softKeyboardVisible
                    ? Math.max(insets.bottom, 12) + 80
                    : Math.max(insets.bottom, 10),
              }
            : { paddingBottom: spacing.lg + insets.bottom },
        ]}
      >
        {/* 图片上传按钮 */}
        <TouchableOpacity
          style={[styles.mediaButton, isRecording && styles.buttonDisabled]}
          onPress={handleImagePress}
          activeOpacity={0.7}
          disabled={isLoading || isRecording}
        >
          <Camera size={isSoft ? 20 : 24} color={isSoft ? styles._colors.textSecondary : styles._colors.primary} strokeWidth={2} />
        </TouchableOpacity>

        {/* 语音模式：按住说话按钮 */}
        {isVoiceMode ? (
          <View
            style={[
              styles.voiceInputWrapper,
              isRecording && styles.voiceInputWrapperActive,
              willCancel && styles.voiceInputWrapperCancel,
            ]}
            {...panResponder.panHandlers}
          >
            <Text style={[
              styles.voiceInputText,
              isRecording && styles.voiceInputTextActive,
            ]}>
              {isRecording ? (willCancel ? '松开取消' : '松开发送') : '按住说话'}
            </Text>
          </View>
        ) : (
          /* 文字模式：文本输入框 */
          <View style={styles.inputWrapper}>
            <TextInput
              style={styles.input}
              value={inputText}
              onChangeText={setInputText}
              placeholder={
                isSoft
                  ? '问问账单，或说「午饭 28」'
                  : isHogwarts
                    ? '问问招财巫师猫：这个月金加隆花哪儿了？'
                    : '点击输入文字'
              }
              placeholderTextColor={isSoft ? styles._colors.textQuaternary : styles._colors.textTertiary}
              multiline
              maxLength={500}
              editable={!isLoading}
            />
          </View>
        )}

        {/* 右侧按钮：停止/发送/语音切换 */}
        {isLoading ? (
          <TouchableOpacity
            style={styles.sendButton}
            onPress={() => streamRef.current?.stop()}
            activeOpacity={0.7}
          >
            <Square size={16} color={isSoft ? styles._colors.onPrimary : '#FFFFFF'} fill={isSoft ? styles._colors.onPrimary : '#FFFFFF'} strokeWidth={2} />
          </TouchableOpacity>
        ) : !isVoiceMode && (inputText.trim() || pendingImages.length > 0) ? (
          <TouchableOpacity
            style={styles.sendButton}
            onPress={() => handleSend()}
            activeOpacity={0.7}
          >
            <Send size={isSoft ? 17 : 20} color={isSoft ? styles._colors.onPrimary : '#FFFFFF'} strokeWidth={2.2} />
          </TouchableOpacity>
        ) : (
          <TouchableOpacity
            style={[styles.modeButton, isRecording && styles.buttonDisabled]}
            onPress={toggleVoiceMode}
            activeOpacity={0.7}
            disabled={isRecording}
          >
            {isVoiceMode ? (
              <Keyboard size={isSoft ? 20 : 22} color={styles._colors.textSecondary} strokeWidth={2} />
            ) : (
              <Mic size={isSoft ? 20 : 22} color={styles._colors.textSecondary} strokeWidth={2} />
            )}
          </TouchableOpacity>
        )}
      </View>

      {/* 编辑账单弹窗 */}
      <EditBillModal
        visible={showEditModal}
        bill={editingBill}
        categories={categories}
        accounts={accounts}
        defaultAccountId={
          [...accounts].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))[0]?.id
        }
        onClose={() => {
          setShowEditModal(false);
          setEditingBill(null);
        }}
        onSave={handleSaveBill}
        colors={styles._colors}
      />

      {/* 模型/服务商切换弹窗 */}
      <Modal
        visible={showModelSwitcher}
        transparent
        animationType="fade"
        onRequestClose={() => setShowModelSwitcher(false)}
      >
        <TouchableOpacity
          style={styles.switcherOverlay}
          activeOpacity={1}
          onPress={() => setShowModelSwitcher(false)}
        >
          <View style={styles.switcherCard}>
            <Text style={styles.switcherTitle}>切换对话模型</Text>
            {allAIConfigs.map((cfg) => {
              const selected = activeAIConfig?.id === cfg.id;
              return (
                <TouchableOpacity
                  key={cfg.id}
                  testID={`ai-switcher-item-${cfg.id}`}
                  style={[styles.switcherItem, selected && styles.switcherItemActive]}
                  onPress={() => {
                    setSelectedConfigId(cfg.id);
                    setShowModelSwitcher(false);
                  }}
                >
                  <ProviderIcon provider={cfg.provider} size={22} />
                  <View style={styles.switcherItemMain}>
                    <View style={styles.switcherItemNameRow}>
                      <Text style={styles.switcherItemName} numberOfLines={1}>
                        {cfg.name}
                      </Text>
                      {cfg.supportsVision ? (
                        <Eye size={12} color={styles._colors.primary} />
                      ) : null}
                    </View>
                    <Text style={styles.switcherItemModel} numberOfLines={1}>
                      {cfg.model}
                    </Text>
                  </View>
                  {selected ? <Check size={16} color={styles._colors.primary} /> : null}
                </TouchableOpacity>
              );
            })}
            <TouchableOpacity
              testID="ai-switcher-manage-btn"
              style={styles.switcherManageBtn}
              onPress={() => {
                setShowModelSwitcher(false);
                (navigation as any).navigate('AiProviders');
              }}
            >
              <Settings size={15} color={styles._colors.primary} />
              <Text style={styles.switcherManageText}>管理 AI 服务商</Text>
            </TouchableOpacity>
          </View>
        </TouchableOpacity>
      </Modal>

      {/* 侧边栏抽屉 */}
      {sidebarVisible && (
        <View style={sidebarStyles.overlay}>
          <Animated.View
            style={[
              sidebarStyles.overlayBg,
              { opacity: overlayAnim },
            ]}
          >
            <TouchableOpacity
              style={StyleSheet.absoluteFill}
              activeOpacity={1}
              onPress={closeSidebar}
            />
          </Animated.View>
          <Animated.View
            style={[
              sidebarStyles.panel,
              {
                width: SIDEBAR_WIDTH,
                backgroundColor: styles._colors.surface,
                borderRightWidth: isSoft ? shape.borderWidth.card : isHogwarts ? 1 : borderWidth.thick,
                borderRightColor: isSoft || isHogwarts ? styles._colors.cardBorder : styles._colors.stroke,
              },
              isSoft && shape.elevation.floating,
              { transform: [{ translateX: sidebarAnim }] },
            ]}
          >
            {/* 侧边栏头部 */}
            <View
              style={[
                sidebarStyles.panelHeader,
                {
                  borderBottomWidth: isSoft ? StyleSheet.hairlineWidth : borderWidth.thin,
                  borderBottomColor: isSoft || isHogwarts ? styles._colors.divider : styles._colors.stroke,
                },
              ]}
            >
              <Text
                style={[
                  sidebarStyles.panelTitle,
                  {
                    color: styles._colors.textPrimary,
                    fontFamily: isHogwarts ? HOGWARTS_FONTS.display : undefined,
                    fontWeight: isHogwarts ? undefined : '800',
                  },
                ]}
              >
                {isHogwarts ? '古灵阁卷宗' : '对话记录'}
              </Text>
              <TouchableOpacity
                onPress={handleNewChat}
                activeOpacity={0.7}
                style={[
                  sidebarStyles.newChatBtn,
                  isSoft
                    ? {
                        borderWidth: 0,
                        borderRadius: 12,
                        backgroundColor: styles._colors.primaryLight,
                      }
                    : {
                        borderColor: isHogwarts ? styles._colors.accent : styles._colors.stroke,
                        backgroundColor: isHogwarts ? styles._colors.primary : styles._colors.accent,
                      },
                ]}
              >
                <SquarePen
                  size={18}
                  color={
                    isSoft
                      ? styles._colors.primary
                      : isHogwarts
                        ? styles._colors.onPrimary
                        : styles._colors.textPrimary
                  }
                />
              </TouchableOpacity>
            </View>

            {/* 会话列表 */}
            {sessionsLoading ? (
              <View style={sidebarStyles.loadingContainer}>
                <ActivityIndicator size="small" color={styles._colors.primary} />
              </View>
            ) : sessionsError ? (
              /* ⚠️ 失败态必须与"暂无对话记录"区分。此前 catch 只 console.error，
                 sessions 停在 [] ⇒ 侧边栏显示"暂无对话记录"，用户会以为历史被清空了
                 （数据管理里"清除本地数据"确实会清 AI 对话历史，两者无法分辨）。 */
              <View style={sidebarStyles.emptyContainer}>
                <Text style={[sidebarStyles.emptyText, { color: styles._colors.error }]}>
                  会话列表加载失败
                </Text>
                <TouchableOpacity
                  onPress={loadSessions}
                  activeOpacity={0.7}
                  style={sidebarStyles.retryTextWrap}
                >
                  <Text style={[sidebarStyles.retryText, { color: styles._colors.primary }]}>
                    点此重试
                  </Text>
                </TouchableOpacity>
              </View>
            ) : sessions.length === 0 ? (
              <View style={sidebarStyles.emptyContainer}>
                <Text style={[sidebarStyles.emptyText, { color: styles._colors.textTertiary }]}>
                  暂无对话记录
                </Text>
              </View>
            ) : (
              <ScrollView
                style={sidebarStyles.sessionList}
                showsVerticalScrollIndicator={false}
                // ⚠️ 侧边栏此前只取首屏 50 条且无翻页入口，第 51 条之后
                // 永久不可达。滚到底自动追加下一页（阈值 120px）。
                onScroll={handleSidebarScroll}
                scrollEventThrottle={200}
              >
                {groupSessionsByDate(sessions).map(group => (
                  <View key={group.title}>
                    <Text style={[sidebarStyles.groupTitle, { color: styles._colors.textTertiary }]}>
                      {group.title}
                    </Text>
                    {group.data.map(s => (
                      <TouchableOpacity
                        key={s.id}
                        style={[
                          sidebarStyles.sessionItem,
                          isSoft && { borderRadius: 12, borderWidth: 0 },
                          s.id === sessionId && {
                            backgroundColor: styles._colors.primaryLight,
                            borderColor: isSoft ? 'transparent' : styles._colors.stroke,
                          },
                        ]}
                        onPress={() => handleSelectSession(s.id)}
                        onLongPress={(e) => handleSessionLongPress(s, e)}
                        activeOpacity={0.7}
                      >
                        <Text
                          style={[
                            sidebarStyles.sessionTitle,
                            { color: styles._colors.textPrimary },
                            s.id === sessionId && { color: styles._colors.primary, fontWeight: '700' },
                          ]}
                          numberOfLines={1}
                        >
                          {s.title || '新对话'}
                        </Text>
                      </TouchableOpacity>
                    ))}
                  </View>
                ))}
                {sessionsLoadingMore ? (
                  <View style={sidebarStyles.loadingContainer}>
                    <ActivityIndicator size="small" color={styles._colors.primary} />
                  </View>
                ) : null}
              </ScrollView>
            )}
          </Animated.View>
        </View>
      )}

      {/* 重命名弹窗 */}
      <Modal
        visible={renameModalVisible}
        transparent
        animationType="fade"
        onRequestClose={() => setRenameModalVisible(false)}
      >
        <View style={sidebarStyles.renameOverlay}>
          <View
            style={[
              sidebarStyles.renameCard,
              {
                backgroundColor: styles._colors.surface,
                borderColor: isSoft ? styles._colors.cardBorder : styles._colors.stroke,
              },
              isSoft && {
                borderRadius: 24,
                borderWidth: shape.borderWidth.card,
                ...shape.elevation.floating,
              },
            ]}
          >
            <Text style={[sidebarStyles.renameTitle, { color: styles._colors.textPrimary }]}>
              重命名会话
            </Text>
            <TextInput
              style={[
                sidebarStyles.renameInput,
                {
                  color: styles._colors.textPrimary,
                  borderColor: isSoft ? 'transparent' : styles._colors.stroke,
                  backgroundColor: isSoft ? styles._colors.surfaceMuted : styles._colors.background,
                },
                isSoft && {
                  borderWidth: 0,
                  borderRadius: 14,
                },
              ]}
              value={renameText}
              onChangeText={setRenameText}
              placeholder="输入新标题"
              placeholderTextColor={styles._colors.textTertiary}
              autoFocus
              maxLength={50}
            />
            <View style={sidebarStyles.renameActions}>
              <TouchableOpacity
                style={[
                  sidebarStyles.renameCancelBtn,
                  isSoft
                    ? {
                        borderWidth: 0,
                        borderRadius: 14,
                        backgroundColor: styles._colors.surfaceMuted,
                      }
                    : { borderColor: styles._colors.stroke },
                ]}
                onPress={() => setRenameModalVisible(false)}
              >
                <Text style={{ color: styles._colors.textSecondary, fontSize: 15, fontWeight: '700' }}>取消</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[
                  sidebarStyles.renameConfirmBtn,
                  {
                    backgroundColor: styles._colors.primary,
                    borderColor: isSoft ? 'transparent' : styles._colors.stroke,
                  },
                  isSoft && {
                    borderWidth: 0,
                    borderRadius: 14,
                    ...shape.elevation.none,
                  },
                ]}
                onPress={handleRenameConfirm}
              >
                <Text style={{ color: isSoft ? styles._colors.onPrimary : '#FFFFFF', fontSize: 15, fontWeight: '700' }}>确定</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>

      {/* 长按上下文菜单 */}
      <Modal
        visible={contextMenu !== null}
        transparent
        animationType="fade"
        onRequestClose={() => setContextMenu(null)}
      >
        <TouchableOpacity
          style={sidebarStyles.contextOverlay}
          activeOpacity={1}
          onPress={() => setContextMenu(null)}
        >
          <View
            style={[
              sidebarStyles.contextCard,
              {
                backgroundColor: styles._colors.surface,
                borderColor: isSoft ? styles._colors.cardBorder : styles._colors.stroke,
                top: Math.min(
                  (contextMenu?.pageY ?? 0) - 20,
                  Dimensions.get('window').height - 220,
                ),
              },
              isSoft && {
                borderRadius: 16,
                borderWidth: shape.borderWidth.card,
                ...shape.elevation.floating,
              },
            ]}
          >
            <TouchableOpacity
              style={[
                sidebarStyles.contextItem,
                {
                  borderBottomWidth: isSoft ? StyleSheet.hairlineWidth : borderWidth.thin,
                  borderBottomColor: isSoft ? styles._colors.divider : styles._colors.stroke,
                },
              ]}
              onPress={handleActionRename}
              activeOpacity={0.6}
            >
              <Edit2 size={17} color={styles._colors.textPrimary} />
              <Text style={[sidebarStyles.contextItemText, { color: styles._colors.textPrimary }]}>
                重命名
              </Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={[
                sidebarStyles.contextItem,
                {
                  borderBottomWidth: isSoft ? StyleSheet.hairlineWidth : borderWidth.thin,
                  borderBottomColor: isSoft ? styles._colors.divider : styles._colors.stroke,
                },
              ]}
              onPress={handleActionTogglePin}
              activeOpacity={0.6}
            >
              <Pin size={17} color={styles._colors.textPrimary} />
              <Text style={[sidebarStyles.contextItemText, { color: styles._colors.textPrimary }]}>
                {contextMenu?.session.isPinned ? '取消置顶' : '置顶'}
              </Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={[sidebarStyles.contextItem, isSoft && { borderBottomWidth: 0 }]}
              onPress={handleActionDelete}
              activeOpacity={0.6}
            >
              <Trash2 size={17} color={isSoft ? styles._colors.error : '#FF3B30'} />
              <Text style={[sidebarStyles.contextItemText, { color: isSoft ? styles._colors.error : '#FF3B30' }]}>
                删除
              </Text>
            </TouchableOpacity>
          </View>
        </TouchableOpacity>
      </Modal>
      <ImageViewing
        images={imageViewerUrl ? [{ uri: imageViewerUrl }] : []}
        imageIndex={0}
        visible={imageViewerVisible}
        onRequestClose={() => setImageViewerVisible(false)}
      />
    </KeyboardAvoidingView>
  );
}

const createStyles = (colors: ThemeColors, shape: ThemeShape) => ({
  ...StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: colors.background,
    },
    chatHeader: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingHorizontal: shape.isSoft ? spacing.md : spacing.sm,
      paddingVertical: spacing.sm,
      backgroundColor: shape.isSoft ? colors.background : colors.surface,
      borderBottomWidth: shape.isSoft ? 0 : shape.borderWidth.subtle,
      borderBottomColor: shape.isHogwarts ? colors.divider : colors.stroke,
    },
    headerCenterWrap: {
      flex: 1,
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'center',
      gap: 8,
    },
    softHeaderTitle: {
      fontSize: 18,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    headerTitleText: {
      fontSize: 18,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
    },
    headerProviderPill: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 4,
      backgroundColor: colors.surfaceMuted,
      paddingHorizontal: 8,
      paddingVertical: 3,
      borderRadius: shape.isSoft ? 999 : shape.radius.tag,
      borderWidth: shape.isSoft ? 0 : StyleSheet.hairlineWidth,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      maxWidth: 132,
    },
    softProviderDot: {
      width: 6,
      height: 6,
      borderRadius: 3,
      backgroundColor: colors.income,
    },
    softProviderText: {
      flexShrink: 1,
      fontSize: 11.5,
      fontWeight: shape.isHogwarts ? undefined : '500',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    unconfiguredCard: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.md,
      marginHorizontal: spacing.md,
      marginTop: spacing.sm,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.sm + 4,
      backgroundColor: colors.surface,
      borderRadius: shape.radius.card,
      borderWidth: shape.isSoft || shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.cardBorder : colors.stroke,
      ...(shape.isSoft || shape.isHogwarts ? shape.elevation.card : {}),
    },
    unconfiguredBody: {
      flex: 1,
      gap: 2,
    },
    unconfiguredTitle: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
    },
    unconfiguredDesc: {
      fontSize: 12,
      lineHeight: 17,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
    unconfiguredButton: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 4,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.sm,
      backgroundColor: colors.primary,
      borderRadius: shape.isSoft ? 12 : shape.radius.button,
    },
    unconfiguredButtonText: {
      fontSize: 12.5,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: shape.isSoft || shape.isHogwarts ? colors.onPrimary : '#FFFFFF',
    },
    toolListWrap: {
      marginTop: spacing.sm,
    },
    toolEmptyText: {
      marginTop: spacing.xs,
    },
    toolBillRow: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingVertical: 7,
      gap: spacing.sm,
      borderBottomWidth: StyleSheet.hairlineWidth,
      borderBottomColor: colors.divider,
    },
    toolBillLeft: {
      flex: 1,
      gap: 2,
    },
    toolBillDesc: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    toolBillMeta: {
      fontSize: 11.5,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
    toolBillAmount: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.numeric : undefined,
      fontVariant: ['tabular-nums'],
    },
    toolStatsContainer: {
      marginTop: spacing.sm,
      backgroundColor: colors.surfaceMuted,
      borderRadius: shape.radius.small,
      padding: spacing.sm + 2,
    },
    toolCardCaption: {
      fontSize: 11.5,
      color: colors.textTertiary,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.numeric : typography.fontFamily.mono,
      marginBottom: spacing.xs,
    },
    toolStatsRow: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      paddingVertical: 6,
      borderBottomWidth: StyleSheet.hairlineWidth,
      borderBottomColor: colors.divider,
    },
    toolStatsRowLast: {
      borderBottomWidth: 0,
    },
    toolStatsLabel: {
      fontSize: 13.5,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    toolStatsValue: {
      fontSize: 15,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.numeric : undefined,
      fontVariant: ['tabular-nums'],
    },
    toolStatsBalance: {
      fontSize: 15.5,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.numeric : undefined,
      fontVariant: ['tabular-nums'],
      color: colors.textPrimary,
    },
    toolSubAmount: {
      fontSize: 12.5,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.numeric : undefined,
      fontVariant: ['tabular-nums'],
      color: colors.textSecondary,
    },
    toolBreakdownWrap: {
      marginTop: spacing.sm,
      paddingTop: spacing.sm,
      borderTopWidth: StyleSheet.hairlineWidth,
      borderTopColor: colors.divider,
      gap: spacing.xs + 2,
    },
    toolBreakdownTitle: {
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
      marginBottom: 2,
    },
    toolBreakdownRow: {
      gap: 4,
    },
    toolBreakdownHeader: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      gap: spacing.sm,
    },
    toolBreakdownName: {
      flex: 1,
      fontSize: 12.5,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    toolBreakdownAmount: {
      fontSize: 12.5,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.numeric : undefined,
      fontVariant: ['tabular-nums'],
      color: colors.textSecondary,
    },
    toolProgressTrack: {
      height: 6,
      borderRadius: 3,
      backgroundColor: colors.divider,
      overflow: 'hidden',
    },
    toolProgressFill: {
      height: '100%',
      borderRadius: 3,
    },
    toolAccountRow: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingVertical: 7,
      gap: spacing.sm,
      borderBottomWidth: StyleSheet.hairlineWidth,
      borderBottomColor: colors.divider,
    },
    toolAccountLeft: {
      flex: 1,
      flexDirection: 'row',
      alignItems: 'center',
      gap: 6,
    },
    toolAccountName: {
      flex: 1,
      fontSize: 13,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    toolBudgetRow: {
      paddingVertical: 8,
      gap: 5,
      borderBottomWidth: StyleSheet.hairlineWidth,
      borderBottomColor: colors.divider,
    },
    toolBudgetPct: {
      fontSize: 12.5,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.numeric : undefined,
      fontVariant: ['tabular-nums'],
    },
    toolBudgetFoot: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      gap: spacing.sm,
    },
    switcherOverlay: {
      flex: 1,
      backgroundColor: 'rgba(0, 0, 0, 0.45)',
      alignItems: 'center',
      justifyContent: 'center',
      padding: spacing.lg,
    },
    switcherCard: {
      width: '100%',
      maxWidth: 380,
      backgroundColor: colors.surface,
      borderRadius: shape.radius.modal,
      borderWidth: shape.isSoft || shape.isHogwarts ? shape.borderWidth.card : borderWidth.medium,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.cardBorder : colors.stroke,
      padding: spacing.md,
      gap: spacing.xs,
      ...(shape.isSoft || shape.isHogwarts ? shape.elevation.floating : shadow.medium),
    },
    switcherTitle: {
      fontSize: 16,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
      paddingHorizontal: spacing.xs,
      paddingBottom: spacing.xs,
    },
    switcherItem: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm + 2,
      paddingHorizontal: spacing.sm + 2,
      paddingVertical: spacing.sm + 2,
      borderRadius: shape.isSoft ? 12 : borderRadius.small,
    },
    switcherItemActive: {
      backgroundColor: colors.primaryLight,
    },
    switcherItemMain: {
      flex: 1,
      gap: 2,
    },
    switcherItemNameRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 5,
    },
    switcherItemName: {
      flexShrink: 1,
      fontSize: 14.5,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    switcherItemModel: {
      fontSize: 12,
      color: colors.textTertiary,
      fontFamily: typography.fontFamily.mono,
    },
    switcherManageBtn: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'center',
      gap: 6,
      marginTop: spacing.xs,
      paddingVertical: spacing.sm + 2,
      borderTopWidth: StyleSheet.hairlineWidth,
      borderTopColor: colors.divider,
    },
    switcherManageText: {
      fontSize: 13.5,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.primary,
    },
    softWelcomeWrap: {
      paddingTop: 12,
      paddingBottom: 16,
    },
    softWelcomeHead: {
      alignItems: 'center',
      paddingHorizontal: 16,
      marginBottom: 18,
    },
    softWelcomeAvatarWrap: {
      position: 'relative',
      marginBottom: 12,
    },
    softWelcomeAvatar: {
      width: 64,
      height: 64,
      borderRadius: 24,
      alignItems: 'center',
      justifyContent: 'center',
      ...shape.elevation.hero,
    },
    softWelcomeDot: {
      position: 'absolute',
      right: -3,
      bottom: -3,
      width: 18,
      height: 18,
      borderRadius: 9,
      backgroundColor: colors.accent,
      borderWidth: 3,
      borderColor: colors.background,
    },
    softWelcomeTitle: {
      fontSize: 18,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    softWelcomeDesc: {
      fontSize: 13,
      color: colors.textTertiary,
      textAlign: 'center',
      lineHeight: 20,
      marginTop: 6,
    },
    softSuggestionList: {
      gap: 8,
    },
    softSuggestionCard: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 10,
      paddingHorizontal: 14,
      paddingVertical: 12,
      borderRadius: 16,
      backgroundColor: colors.surface,
      borderWidth: shape.borderWidth.card,
      borderColor: colors.cardBorder,
      ...shape.elevation.card,
    },
    softSuggestionText: {
      flex: 1,
      fontSize: 14,
      fontWeight: '500',
      color: colors.textPrimary,
    },
    messageListContainer: {
      flex: 1,
    },
    headerTitle: {
      fontSize: 18,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
      flex: 1,
      textAlign: 'center',
    },
    headerButton: {
      padding: spacing.sm,
    },
    messageList: {
      padding: spacing.lg,
      paddingBottom: spacing.md,
    },
    billsContainer: {
      marginBottom: spacing.md,
    },
    messageBubble: {
      flexDirection: 'row',
      marginBottom: spacing.md,
      alignItems: 'flex-start',
    },
    userBubble: {
      justifyContent: 'flex-end',
    },
    aiBubble: {
      justifyContent: 'flex-start',
    },
    aiAvatar: {
      fontSize: 24,
      marginRight: spacing.sm,
    },
    aiAvatarContainer: {
      width: 28,
      height: 28,
      marginRight: spacing.sm,
      alignItems: 'center',
      justifyContent: 'center',
    },
    aiAvatarPlaceholder: {
      width: 24,
      height: 24,
      borderRadius: 12,
      marginRight: spacing.sm,
      backgroundColor: colors.divider,
    },
    messageContent: {
      maxWidth: shape.isSoft ? '82%' : '80%',
      borderRadius: shape.radius.card,
      borderWidth: shape.isSoft || shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.cardBorder : colors.stroke,
      paddingHorizontal: shape.isSoft ? 14 : spacing.md,
      paddingVertical: shape.isSoft ? 10 : spacing.md,
      ...(shape.isHogwarts ? shape.elevation.card : {}),
    },
    userContent: {
      backgroundColor: colors.primary,
      borderWidth: shape.isSoft ? 0 : shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isHogwarts ? colors.accent : colors.stroke,
      borderBottomRightRadius: shape.isSoft ? 6 : spacing.xs,
    },
    aiContent: {
      backgroundColor: colors.surface,
      borderBottomLeftRadius: shape.isSoft ? 6 : spacing.xs,
      ...(shape.isSoft ? shape.elevation.card : {}),
    },
    errorContent: {
      backgroundColor: colors.expenseLight,
      borderColor: colors.error,
      borderBottomLeftRadius: shape.isSoft ? 6 : spacing.xs,
    },
    messageText: {
      fontSize: shape.isSoft ? 14.5 : 15,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '400' : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
      lineHeight: 22,
    },
    userText: {
      color: shape.isSoft ? colors.onPrimary : '#FFFFFF',
    },
    chatImage: {
      width: 200,
      height: 200,
      borderRadius: shape.radius.small,
      marginBottom: spacing.xs,
    },
    chatImageList: {
      flexDirection: 'row',
      marginBottom: spacing.xs,
    },
    errorText: {
      fontSize: 15,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.error,
      lineHeight: 22,
    },
    loadingText: {
      fontSize: 14,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
      marginLeft: spacing.sm,
    },
    pendingBar: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingHorizontal: spacing.lg,
      paddingVertical: spacing.sm,
      marginHorizontal: shape.isSoft ? 12 : 0,
      marginBottom: shape.isSoft ? 8 : 0,
      borderRadius: shape.isSoft ? 16 : 0,
      backgroundColor: shape.isSoft ? colors.primaryLight : colors.primary + '1A',
      borderTopWidth: shape.isSoft ? 0 : shape.borderWidth.subtle,
      borderTopColor: shape.isHogwarts ? colors.divider : colors.stroke,
    },
    pendingText: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: shape.isSoft ? colors.primaryDark : colors.primary,
    },
    saveAllButton: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 4,
      backgroundColor: colors.primary,
      paddingVertical: spacing.xs + 2,
      paddingHorizontal: spacing.md,
      borderRadius: shape.isSoft ? 12 : shape.radius.button,
      borderWidth: shape.isSoft ? 0 : shape.borderWidth.subtle,
      borderColor: shape.isHogwarts ? colors.accent : colors.stroke,
    },
    saveAllButtonText: {
      fontSize: 14,
      color: shape.isSoft ? colors.onPrimary : '#FFFFFF',
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
    },
    pendingImagesContainer: {
      backgroundColor: colors.surface,
      borderTopWidth: shape.borderWidth.subtle,
      borderTopColor: shape.isHogwarts ? colors.divider : colors.stroke,
      paddingVertical: spacing.sm,
      paddingHorizontal: spacing.md,
    },
    pendingImagesScroll: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
    },
    pendingImagesCounter: {
      alignSelf: 'flex-end',
      fontSize: 11,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.numeric : typography.fontFamily.mono,
      color: colors.textTertiary,
      marginBottom: 2,
    },
    pendingImageWrapper: {
      position: 'relative',
      padding: 6,
      paddingBottom: 0,
    },
    pendingImageThumb: {
      width: 72,
      height: 72,
      borderRadius: shape.radius.small,
    },
    pendingImageRemove: {
      position: 'absolute',
      top: 0,
      right: 0,
      width: 20,
      height: 20,
      borderRadius: 10,
      backgroundColor: 'rgba(0,0,0,0.6)',
      alignItems: 'center',
      justifyContent: 'center',
    },
    pendingImageAdd: {
      width: 72,
      height: 72,
      borderRadius: shape.radius.small,
      backgroundColor: colors.background,
      borderWidth: shape.borderWidth.subtle,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      alignItems: 'center',
      justifyContent: 'center',
    },
    inputContainer: {
      flexDirection: 'row',
      alignItems: 'center',
      flexGrow: 0,
      flexShrink: 0,
      padding: shape.isSoft ? 6 : spacing.md,
      paddingLeft: shape.isSoft ? 8 : spacing.md,
      paddingBottom: shape.isSoft ? 6 : spacing.lg,
      marginHorizontal: shape.isSoft ? 12 : 0,
      borderRadius: shape.isSoft ? 24 : 0,
      backgroundColor: colors.surface,
      borderTopWidth: shape.isSoft ? shape.borderWidth.card : shape.borderWidth.subtle,
      borderWidth: shape.isSoft ? shape.borderWidth.card : 0,
      borderColor: shape.isSoft ? colors.cardBorder : shape.isHogwarts ? colors.divider : colors.stroke,
      borderTopColor: shape.isSoft ? colors.cardBorder : shape.isHogwarts ? colors.divider : colors.stroke,
      gap: shape.isSoft ? 6 : spacing.sm,
      ...(shape.isSoft ? shape.elevation.floating : {}),
    },
    mediaButton: {
      width: shape.isSoft ? 36 : 48,
      height: shape.isSoft ? 36 : 48,
      borderRadius: shape.isSoft ? 12 : shape.isHogwarts ? borderRadius.large : borderRadius.medium,
      backgroundColor: shape.isSoft ? 'transparent' : colors.background,
      alignItems: 'center',
      justifyContent: 'center',
      borderWidth: shape.isSoft ? 0 : shape.borderWidth.subtle,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
    },
    inputWrapper: {
      flex: 1,
      backgroundColor: shape.isSoft ? 'transparent' : colors.background,
      borderRadius: shape.isSoft ? 16 : shape.radius.input,
      borderWidth: shape.isSoft ? 0 : shape.borderWidth.subtle,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      paddingHorizontal: shape.isSoft ? 6 : spacing.md,
      paddingVertical: shape.isSoft ? 0 : spacing.xs,
      minHeight: shape.isSoft ? 38 : 48,
      justifyContent: 'center',
    },
    input: {
      fontSize: shape.isSoft ? 14 : 15,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '400' : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
      textAlignVertical: 'center',
      maxHeight: 100,
      paddingVertical: shape.isSoft ? 6 : spacing.sm,
    },
    sendButton: {
      width: shape.isSoft ? 38 : 48,
      height: shape.isSoft ? 38 : 48,
      borderRadius: shape.isSoft ? 19 : shape.isHogwarts ? borderRadius.large : borderRadius.medium,
      backgroundColor: shape.isSoft || shape.isHogwarts ? colors.primary : colors.income,
      borderWidth: shape.isSoft ? 0 : shape.borderWidth.subtle,
      borderColor: shape.isHogwarts ? colors.accent : colors.stroke,
      alignItems: 'center',
      justifyContent: 'center',
    },
    voiceButton: {
      width: 48,
      height: 48,
      borderRadius: 24,
      backgroundColor: colors.primary,
      alignItems: 'center',
      justifyContent: 'center',
    },
    voiceButtonActive: {
      backgroundColor: '#FF3B30',
    },
    buttonDisabled: {
      opacity: 0.5,
    },
    listeningBar: {
      flexDirection: 'row',
      alignItems: 'center',
      paddingHorizontal: spacing.lg,
      paddingVertical: spacing.sm,
      backgroundColor: '#FF3B30' + '1A',
      borderTopWidth: 1,
      borderTopColor: '#FF3B30' + '33',
    },
    listeningDot: {
      width: 8,
      height: 8,
      borderRadius: 4,
      backgroundColor: '#FF3B30',
      marginRight: spacing.sm,
    },
    listeningText: {
      fontSize: 14,
      color: '#FF3B30',
      fontWeight: '500',
      flex: 1,
    },
    // 语音录音覆盖层
    recordingOverlay: {
      position: 'absolute',
      left: 0,
      right: 0,
      bottom: 100,
      alignItems: 'center',
      paddingVertical: spacing.lg,
      backgroundColor: 'rgba(0, 0, 0, 0.75)',
      borderRadius: borderRadius.large,
      marginHorizontal: spacing.lg,
    },
    recordingHint: {
      fontSize: 14,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: '#FFFFFF',
      marginBottom: spacing.md,
    },
    recordingHintCancel: {
      color: '#FF3B30',
    },
    waveContainer: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'center',
      height: 40,
      gap: 3,
    },
    waveBar: {
      width: 3,
      height: 30,
      backgroundColor: colors.primary,
      borderRadius: 2,
    },
    waveBarCancel: {
      backgroundColor: '#FF3B30',
    },
    voicePreviewText: {
      fontSize: 14,
      color: '#FFFFFF',
      marginTop: spacing.md,
      textAlign: 'center',
      paddingHorizontal: spacing.md,
    },
    // 按住说话按钮
    voiceInputWrapper: {
      flex: 1,
      height: shape.isSoft ? 38 : 48,
      backgroundColor: shape.isSoft ? colors.surfaceMuted : colors.background,
      borderRadius: shape.isSoft ? 999 : shape.radius.input,
      alignItems: 'center',
      justifyContent: 'center',
      borderWidth: shape.isSoft ? 0 : shape.borderWidth.subtle,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
    },
    voiceInputWrapperActive: {
      backgroundColor: colors.primary + '1A',
      borderColor: colors.primary,
    },
    voiceInputWrapperCancel: {
      backgroundColor: '#FF3B30' + '1A',
      borderColor: '#FF3B30',
    },
    voiceInputText: {
      fontSize: shape.isSoft ? 14 : 15,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    voiceInputTextActive: {
      color: colors.primary,
    },
    // 模式切换按钮
    modeButton: {
      width: shape.isSoft ? 38 : 48,
      height: shape.isSoft ? 38 : 48,
      borderRadius: shape.isSoft ? 19 : shape.isHogwarts ? borderRadius.large : borderRadius.medium,
      backgroundColor: shape.isSoft ? colors.surfaceMuted : colors.background,
      alignItems: 'center',
      justifyContent: 'center',
      borderWidth: shape.isSoft ? 0 : shape.borderWidth.subtle,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
    },
  }),
  _colors: colors,
});

const sidebarStyles = StyleSheet.create({
  overlay: {
    ...StyleSheet.absoluteFillObject,
    zIndex: 100,
  },
  overlayBg: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: 'rgba(0, 0, 0, 0.4)',
  },
  panel: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    left: 0,
    borderRightWidth: borderWidth.thick,
  },
  panelHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: spacing.lg,
    paddingTop: spacing.xl + spacing.lg,
    paddingBottom: spacing.md,
    borderBottomWidth: borderWidth.thin,
  },
  panelTitle: {
    fontSize: 18,
    fontWeight: '800',
  },
  newChatBtn: {
    width: 34,
    height: 34,
    borderRadius: borderRadius.small,
    borderWidth: borderWidth.thin,
    alignItems: 'center',
    justifyContent: 'center',
  },
  loadingContainer: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  emptyContainer: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  emptyText: {
    fontSize: 14,
    fontWeight: '700',
    textAlign: 'center',
  },
  retryTextWrap: {
    marginTop: spacing.sm,
  },
  retryText: {
    fontSize: 13,
    fontWeight: '800',
  },
  sessionList: {
    flex: 1,
    paddingHorizontal: spacing.md,
    paddingTop: spacing.sm,
  },
  groupTitle: {
    fontSize: 12,
    fontWeight: '700',
    fontFamily: typography.fontFamily.mono,
    paddingHorizontal: spacing.sm,
    paddingTop: spacing.md,
    paddingBottom: spacing.xs,
  },
  sessionItem: {
    paddingVertical: spacing.sm + 2,
    paddingHorizontal: spacing.sm + 2,
    borderRadius: borderRadius.small,
    borderWidth: borderWidth.thin,
    borderColor: 'transparent',
    marginBottom: spacing.xs,
  },
  sessionItemActive: {
    borderWidth: borderWidth.thin,
  },
  sessionTitle: {
    fontSize: 14,
    fontWeight: '600',
    lineHeight: 20,
  },
  // 重命名弹窗
  renameOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.5)',
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.xl,
  },
  renameCard: {
    width: '100%',
    borderRadius: borderRadius.card,
    borderWidth: borderWidth.thick,
    padding: spacing.lg,
    ...shadow.medium,
  },
  renameTitle: {
    fontSize: 18,
    fontWeight: '800',
    marginBottom: spacing.md,
  },
  renameInput: {
    borderWidth: borderWidth.thin,
    borderRadius: borderRadius.small,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    fontSize: 15,
    fontWeight: '600',
    marginBottom: spacing.lg,
  },
  renameActions: {
    flexDirection: 'row',
    gap: spacing.md,
  },
  renameCancelBtn: {
    flex: 1,
    paddingVertical: spacing.sm + 2,
    borderRadius: borderRadius.button,
    borderWidth: borderWidth.thin,
    alignItems: 'center',
  },
  renameConfirmBtn: {
    flex: 1,
    paddingVertical: spacing.sm + 2,
    borderRadius: borderRadius.button,
    borderWidth: borderWidth.thin,
    alignItems: 'center',
    ...shadow.small,
  },
  // 长按上下文菜单（浮层）
  contextOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.25)',
  },
  contextCard: {
    position: 'absolute',
    left: spacing.md,
    width: SIDEBAR_WIDTH * 0.45,
    borderRadius: borderRadius.small,
    borderWidth: borderWidth.medium,
    paddingVertical: spacing.xs,
    ...shadow.medium,
    elevation: 8,
  },
  contextItem: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: spacing.sm + 2,
    paddingHorizontal: spacing.lg,
    gap: spacing.md,
    borderBottomWidth: borderWidth.thin,
  },
  contextItemText: {
    fontSize: 15,
    fontWeight: '700',
  },
});

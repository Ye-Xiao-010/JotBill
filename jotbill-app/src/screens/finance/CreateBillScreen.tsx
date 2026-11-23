/**
 * 创建/编辑账单屏幕 - Neo-Brutalism 风格
 * 描边金额输入 + 糖果色类型切换 + 描边分类网格
 */
import React, { useEffect, useRef, useState } from 'react';
import {
  Animated,
  Dimensions,
  Easing,
  Modal,
  PanResponder,
  View,
  ScrollView,
  StyleSheet,
  TextInput,
  TouchableOpacity,
} from 'react-native';
import { useQueryClient } from '@tanstack/react-query';
import { Text } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  ArrowUpDown,
  Calendar,
  Check,
  ChevronDown,
  CreditCard,
  Pencil,
  Plus,
  X,
} from 'lucide-react-native';
import { Toast, SegmentedControl } from '../../components';
import { AddCategoryModal, ConfirmModal } from '../../components/modals';
import SoftBillKeypad from '../../components/finance/SoftBillKeypad';
import CategoryIcon from '../../components/business/CategoryIcon';
import AccountIcon from '../../components/business/AccountIcon';
import { accountDisplayName, resolveAccountIcon } from '../../constants/accountIcons';
import { DEFAULT_CATEGORIES, getCategoryColor } from '../../constants/app';
import { useToast, useCategories, useStyles } from '../../hooks';
import { useTheme } from '../../providers';
import { nativeData, type Account, type Bill, type Category } from '../../native';
import { invalidateCache, QUERY_KEYS, CACHE_TIME } from '../../lib/queryClient';
import { findDuplicateBills } from '../../lib/billDuplicateCheck';
import {
  applyKeypadInput,
  evaluateAmountExpression,
  formatExpressionPreview,
  hasExpressionOperator,
  type KeypadKey,
} from '../../utils/amountExpression';
import { formatTime } from '../../utils/format';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { spacing, borderRadius, borderWidth, shadow, typography } from '../../theme';


// 固定分类列表（图标统一出自 constants/app.ts 的 DEFAULT_CATEGORIES）
const FIXED_CATEGORIES = DEFAULT_CATEGORIES.EXPENSE.map(({ icon, name }) => ({
  icon,
  name,
}));

/** 阶段五 T7：手动表单支持的交易类型（转账/还款需要双账户，退款/手续费走待确认链路）。 */
type BillFormType = 'expense' | 'income' | 'transfer' | 'repayment';

/** 类型选项标签（阶段五 T7 双账户语义）。 */
const FORM_TYPE_OPTIONS: Array<{ value: BillFormType; label: string }> = [
  { value: 'expense', label: '支出' },
  { value: 'income', label: '收入' },
  { value: 'transfer', label: '转账' },
  { value: 'repayment', label: '还款' },
];

/**
 * P15：生成表单会话级幂等键（每次进入创建表单生成一次）。
 * 保存失败重试、双击、网络超时后重发都复用同一个键，原生侧按
 * `manual:<key>` 确定性主键幂等，杜绝此前「随机 UUID、重试必双记」的窗口。
 */
function generateClientRequestId(): string {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

/** 编辑既有账单时，回显支持的手动类型；不支持的类型（退款/手续费等）回退支出。 */
function resolveInitialFormType(billType: string | undefined, fallback?: 'income' | 'expense'): BillFormType {
  const supported: BillFormType[] = ['expense', 'income', 'transfer', 'repayment'];
  if (billType && (supported as string[]).includes(billType)) {
    return billType as BillFormType;
  }
  return fallback ?? 'expense';
}

interface CreateBillScreenProps {
  navigation: any;
  route?: {
    params?: {
      bill?: Bill;
      initialType?: 'income' | 'expense';
    };
  };
}

const CreateBillScreen: React.FC<CreateBillScreenProps> = ({ navigation, route }) => {
  const { toastState, showSuccess, showError, hideToast } = useToast();
  const styles = useStyles(createStyles);
  const { isSoft, isDark, colors } = useTheme();
  const insets = useSafeAreaInsets();
  const [showNoteEditor, setShowNoteEditor] = useState(false);

  const editingBill = route?.params?.bill;
  const initialType = route?.params?.initialType;
  const isEditing = !!editingBill;
  // 编辑退款/手续费/调整类账单时锁定类型：表单不支持这些类型，若随表单提交会把
  // refund/fee 静默改写成 expense，收支统计双向偏移（同笔金额从收入侧挪到支出侧）。
  const editingTypeLocked = !!editingBill &&
    !(['expense', 'income', 'transfer', 'repayment'] as string[]).includes(editingBill.type);

  const [amount, setAmount] = useState(
    editingBill ? (editingBill.amountMinor / 100).toFixed(2) : ''
  );
  const [type, setType] = useState<BillFormType>(
    resolveInitialFormType(editingBill?.type, initialType)
  );
  const [selectedCategory, setSelectedCategory] = useState<Category | null>(null);
  // 批 6:账单表单中展开子分类区域的顶级分类 ID(点顶级展开,点子分类选中)
  const [expandedBillCategoryId, setExpandedBillCategoryId] = useState<string | null>(null);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [selectedAccountId, setSelectedAccountId] = useState<string | undefined>(editingBill?.accountId);
  // 阶段五 T7：转账/还款目标账户（编辑回显）
  const [selectedTargetAccountId, setSelectedTargetAccountId] = useState<string | undefined>(
    editingBill?.targetAccountId
  );
  const [description, setDescription] = useState(editingBill?.description || '');
  const [loading, setLoading] = useState(false);
  // P15：表单会话级幂等键 —— 本组件实例生命周期内稳定，创建保存的重试/
  // 双击复用同一键；成功保存后页面返回、实例销毁，下次进入重新生成。
  const clientRequestIdRef = useRef(generateClientRequestId());
  // M8：查重命中的二次确认弹窗状态（暂存完整创建流程，确认后续跑）
  const [showDuplicateConfirm, setShowDuplicateConfirm] = useState(false);
  const [duplicateHint, setDuplicateHint] = useState('');
  const pendingDuplicateActionRef = useRef<(() => Promise<void>) | null>(null);

  // 转账/还款不计收支，无分类概念
  const isTransferLike = type === 'transfer' || type === 'repayment';
  const { categories } = useCategories(isTransferLike ? 'expense' : type);

  useEffect(() => {
    let mounted = true;
    nativeData.getAccounts()
      .then(nextAccounts => {
        if (mounted) setAccounts(nextAccounts);
      })
      .catch(() => {
        if (mounted) setAccounts([]);
      });
    return () => {
      mounted = false;
    };
  }, []);

  // 预取另一类型的分类：首次滑动切换时网格直接命中缓存，不会因为等桥接数据
  // 而在滑入后突然重排（分类数量不同的换页会让高度变化，是卡顿感来源之一）
  const prefetchQueryClient = useQueryClient();
  useEffect(() => {
    const otherType = isTransferLike ? 'income' : (type === 'income' ? 'expense' : 'income');
    void prefetchQueryClient.prefetchQuery({
      queryKey: QUERY_KEYS.categories.byType(otherType),
      queryFn: () => nativeData.getCategories(otherType),
      staleTime: CACHE_TIME.CATEGORIES,
    });
    // 仅挂载时预取一次；切换后的预取由 useCategories 自身缓存覆盖
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    setSelectedAccountId(editingBill?.accountId);
    setSelectedTargetAccountId(editingBill?.targetAccountId);
  }, [editingBill?.accountId, editingBill?.targetAccountId]);

  useEffect(() => {
    if (!editingBill?.categoryId) return;
    const matched = categories.find(category => category.id === editingBill.categoryId) || null;
    setSelectedCategory(matched);
    // 批 6 缺陷修复:编辑回显时若分类为子分类,自动展开其父级,便于用户看到当前选中项
    setExpandedBillCategoryId(matched?.parentId ?? null);
  }, [categories, editingBill?.categoryId]);

  const [showAddCategoryModal, setShowAddCategoryModal] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [categoryToDelete, setCategoryToDelete] = useState<Category | null>(null);
  /** 账户选择弹窗：'source' = 主账户 / 'target' = 转账目标账户；null = 关闭 */
  const [accountPickerTarget, setAccountPickerTarget] = useState<'source' | 'target' | null>(null);

  /** 切换类型时清空分类与目标账户（不同类型的字段语义不同）。 */
  const handleTypeChange = (newType: BillFormType) => {
    setType(newType);
    setSelectedCategory(null);
    setExpandedBillCategoryId(null);
    setSelectedTargetAccountId(undefined);
  };

  // 左右滑动切换类型：内容跟手位移 + 松手滑动过渡/回弹，避免"瞬切"的生硬感。
  // 顶部文字栏固定不动，只有其中的色块指示器跟手滑动。
  // PanResponder 挂在整页手势区（含空白处），只在水平位移显著时接管，垂直滚动不受影响；
  // responder 只创建一次，内部经 ref 读最新状态，避免闭包捕获旧值。
  const typeRef = useRef(type);
  typeRef.current = type;
  const typeLockedRef = useRef(editingTypeLocked);
  typeLockedRef.current = editingTypeLocked;
  const handleTypeChangeRef = useRef(handleTypeChange);
  handleTypeChangeRef.current = handleTypeChange;

  /**
   * 类型栏内层宽度（onLayout 测得的是纯按钮行的宽度，不含边框/内边距，
   * 避免宽度算术误差导致色块与文字错位）。
   */
  const [typeRowWidth, setTypeRowWidth] = useState(0);
  const typeButtonWidth =
    typeRowWidth > 0 ? typeRowWidth / FORM_TYPE_OPTIONS.length : 0;
  const typeButtonWidthRef = useRef(typeButtonWidth);
  typeButtonWidthRef.current = typeButtonWidth;

  /** 内容跟手位移（px），只做视觉反馈，不参与布局 */
  const swipeTranslateX = useRef(new Animated.Value(0)).current;
  /** 内容透明度：切换时淡出再淡入，掩盖内容换树的瞬间（跳变感的主要来源） */
  const swipeOpacity = useRef(new Animated.Value(1)).current;
  /** 类型栏色块指示器位移（px） */
  const indicatorTranslateX = useRef(new Animated.Value(0)).current;
  /** 滑动切换中：过渡动画期间拒绝第二次手势，避免动画叠动画 */
  const swipeAnimatingRef = useRef(false);

  /**
   * 色块位置的驱动之一：**点 Tab** 切换时滑到新位置。
   * 滑动切换走 slideSwitchType 内同步驱动的动画，此时跳过本效果
   * （swipeAnimatingRef 为 true），避免两段动画抢同一个值。
   */
  useEffect(() => {
    if (typeButtonWidth <= 0 || swipeAnimatingRef.current) return;
    const index = FORM_TYPE_OPTIONS.findIndex(option => option.value === type);
    Animated.timing(indicatorTranslateX, {
      toValue: index * typeButtonWidth,
      duration: 160,
      easing: Easing.out(Easing.cubic),
      useNativeDriver: true,
    }).start();
  }, [type, typeButtonWidth, indicatorTranslateX]);

  /**
   * 松手切换：滑出 + 淡出 → 换类型 → 反向滑入 + 淡入。
   *
   * 卡顿/跳变的两个根因分别处理：
   * 1. **换树瞬间的视觉跳变**：内容在滑出终点（30% 屏宽处）从旧树换到新树，
   *    即使动画连续，也会看到"内容突然变了"的硬切感。用透明度淡出/淡入
   *    掩盖换树瞬间——这就是之前"卡顿感"的主要来源。
   * 2. **分类数据重排**：useCategories keepPreviousData + 挂载预取，
   *    换树时网格数据已就位，不会塌陷再重排。
   * 色块动画同一回调同步启动，不等 useEffect（commit 后才跑会被拖晚）。
   */
  const slideSwitchType = (direction: 1 | -1) => {
    if (swipeAnimatingRef.current) return;
    swipeAnimatingRef.current = true;
    const screenWidth = Dimensions.get('window').width;
    const index = FORM_TYPE_OPTIONS.findIndex(option => option.value === typeRef.current);
    const nextIndex = index - direction; // 左滑(direction=-1) => index+1
    const next = FORM_TYPE_OPTIONS[nextIndex];
    if (typeButtonWidthRef.current > 0) {
      Animated.timing(indicatorTranslateX, {
        toValue: nextIndex * typeButtonWidthRef.current,
        duration: 160,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }).start();
    }
    const exitDistance = screenWidth * 0.12;
    Animated.parallel([
      Animated.timing(swipeTranslateX, {
        toValue: direction * exitDistance,
        duration: 120,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }),
      Animated.timing(swipeOpacity, {
        toValue: 0,
        duration: 110,
        useNativeDriver: true,
      }),
    ]).start(({ finished }) => {
      // 动画被新的手势/回弹打断：不换类型，直接回弹归位
      if (!finished) {
        swipeAnimatingRef.current = false;
        springBackSwipe();
        return;
      }
      // 换树瞬间内容完全透明，用户看不到硬切
      if (next) handleTypeChangeRef.current(next.value);
      swipeTranslateX.setValue(-direction * exitDistance);
      Animated.parallel([
        Animated.timing(swipeTranslateX, {
          toValue: 0,
          duration: 170,
          easing: Easing.out(Easing.cubic),
          useNativeDriver: true,
        }),
        Animated.timing(swipeOpacity, {
          toValue: 1,
          duration: 170,
          useNativeDriver: true,
        }),
      ]).start(() => {
        swipeAnimatingRef.current = false;
      });
    });
  };

  /** 未达切换阈值：弹性回弹归位（先停掉可能有残留的动画，避免位置打架） */
  const springBackSwipe = () => {
    swipeTranslateX.stopAnimation();
    indicatorTranslateX.stopAnimation();
    swipeOpacity.stopAnimation();
    Animated.parallel([
      Animated.spring(swipeTranslateX, {
        toValue: 0,
        speed: 18,
        bounciness: 6,
        useNativeDriver: true,
      }),
      Animated.timing(swipeOpacity, {
        toValue: 1,
        duration: 120,
        useNativeDriver: true,
      }),
    ]).start();
    if (typeButtonWidthRef.current > 0) {
      const index = FORM_TYPE_OPTIONS.findIndex(option => option.value === typeRef.current);
      Animated.spring(indicatorTranslateX, {
        toValue: index * typeButtonWidthRef.current,
        speed: 18,
        bounciness: 6,
        useNativeDriver: true,
      }).start();
    }
  };

  const typeSwipeResponder = useRef(
    PanResponder.create({
      onMoveShouldSetPanResponder: (_evt, gesture) =>
        Math.abs(gesture.dx) > 16 && Math.abs(gesture.dx) > Math.abs(gesture.dy) * 1.2,
      onPanResponderMove: (_evt, gesture) => {
        if (typeLockedRef.current || swipeAnimatingRef.current) return;
        const index = FORM_TYPE_OPTIONS.findIndex(option => option.value === typeRef.current);
        const atStart = index <= 0 && gesture.dx > 0;
        const atEnd = index >= FORM_TYPE_OPTIONS.length - 1 && gesture.dx < 0;
        // 首尾边界阻尼：只给 25% 位移并快速衰减，明确"到头了"
        const raw = atStart || atEnd ? gesture.dx * 0.25 : gesture.dx;
        swipeTranslateX.setValue(raw * 0.6);
        // 色块跟手：左滑（dx<0）往右移到下一个 Tab
        const btnWidth = typeButtonWidthRef.current;
        if (btnWidth > 0) {
          const follow = index * btnWidth - gesture.dx * 0.6;
          const maxX = (FORM_TYPE_OPTIONS.length - 1) * btnWidth;
          indicatorTranslateX.setValue(Math.max(0, Math.min(maxX, follow)));
        }
      },
      onPanResponderRelease: (_evt, gesture) => {
        if (typeLockedRef.current || swipeAnimatingRef.current) {
          springBackSwipe();
          return;
        }
        const index = FORM_TYPE_OPTIONS.findIndex(option => option.value === typeRef.current);
        const canNext = index < FORM_TYPE_OPTIONS.length - 1;
        const canPrev = index > 0;
        const crossed = Math.abs(gesture.dx) >= 48 || Math.abs(gesture.vx) > 0.5;
        if (gesture.dx < 0 && canNext && crossed) {
          slideSwitchType(-1);
        } else if (gesture.dx > 0 && canPrev && crossed) {
          slideSwitchType(1);
        } else {
          springBackSwipe();
        }
      },
      onPanResponderTerminate: () => {
        springBackSwipe();
      },
    }),
  ).current;

  const handleCategoryLongPress = (category: Category) => {
    if (category.isDefault) {
      showError('系统默认分类不能删除');
      return;
    }
    setCategoryToDelete(category);
    setShowDeleteConfirm(true);
  };

  const handleConfirmDelete = async () => {
    if (!categoryToDelete) return;
    try {
      await nativeData.deleteCategory(categoryToDelete.id);
      showSuccess('分类删除成功');
      invalidateCache.categories();
      if (selectedCategory?.id === categoryToDelete.id) {
        setSelectedCategory(null);
      }
    } catch (error: any) {
      showError(error.message || '删除分类失败');
    } finally {
      setShowDeleteConfirm(false);
      setCategoryToDelete(null);
    }
  };

  const handleOtherClick = () => {
    setShowAddCategoryModal(true);
  };

  const handleAddCategorySuccess = () => {
    invalidateCache.categories();
  };

  const getCategoryList = () => {
    const mergedCategories: Category[] = [];
    FIXED_CATEGORIES.forEach(fixedCat => {
      const matchedCategory = categories.find(cat => cat.name === fixedCat.name);
      if (matchedCategory) {
        mergedCategories.push(matchedCategory);
      }
    });
    const customCategories = categories.filter(
      cat => !FIXED_CATEGORIES.some(fixed => fixed.name === cat.name)
    );
    return [...mergedCategories, ...customCategories];
  };

  /** 子分类展示排序:预设子分类在前,自定义在后;同组内按 sortOrder 升序 */
  const sortChildren = (children: Category[]) =>
    [...children].sort(
      (a, b) =>
        Number(b.isDefault) - Number(a.isDefault) || a.sortOrder - b.sortOrder,
    );

  const selectableAccounts = accounts.filter(
    account => account.isActive || account.id === editingBill?.accountId,
  );

  /** 转账/还款的目标账户候选：转账为全部启用账户，还款限定负债账户。 */
  const selectableTargetAccounts = selectableAccounts.filter(account =>
    type === 'repayment' ? account.kind === 'liability' : true,
  );

  /** 解析表单金额（柔光主题支持 + / − 计算表达式） */
  const resolveNumericAmount = (): { valid: boolean; value: number; error?: string } => {
    const trimmedAmount = amount.trim();
    if (!trimmedAmount) {
      return { valid: false, value: 0, error: '请输入有效的金额' };
    }
    if (isSoft && hasExpressionOperator(trimmedAmount)) {
      const evaluated = evaluateAmountExpression(trimmedAmount);
      if (!Number.isFinite(evaluated) || evaluated <= 0) {
        return { valid: false, value: 0, error: '请输入有效的金额' };
      }
      if (evaluated > 99_999_999.99) {
        return { valid: false, value: 0, error: '金额过大（上限 9999 万）' };
      }
      return { valid: true, value: evaluated };
    }
    const cleaned = isSoft ? trimmedAmount.replace(/[+-]$/, '') : trimmedAmount;
    const parsedAmount = Number(cleaned);
    if (!cleaned || !Number.isFinite(parsedAmount) || parsedAmount <= 0) {
      return { valid: false, value: 0, error: '请输入有效的金额' };
    }
    if (!/^\d+(\.\d{1,2})?$/.test(cleaned)) {
      return { valid: false, value: 0, error: '金额最多两位小数' };
    }
    if (parsedAmount > 99_999_999.99) {
      return { valid: false, value: 0, error: '金额过大（上限 9999 万）' };
    }
    return { valid: true, value: parsedAmount };
  };

  /** 表单校验：金额合法且有上限；账户必填；非转账类必选分类；转账/还款必选双账户且源≠目标、还款目标为负债。 */
  const validateForm = () => {
    const resolved = resolveNumericAmount();
    if (!resolved.valid) {
      showError(resolved.error || '请输入有效的金额');
      return false;
    }
    if (isTransferLike) {
      if (!selectedAccountId) {
        showError('请选择源账户');
        return false;
      }
      if (!selectedTargetAccountId) {
        showError(type === 'repayment' ? '请选择还款的负债账户' : '请选择目标账户');
        return false;
      }
      if (selectedAccountId === selectedTargetAccountId) {
        showError('源账户与目标账户不能相同');
        return false;
      }
      const target = accounts.find(account => account.id === selectedTargetAccountId);
      if (type === 'repayment' && target && target.kind !== 'liability') {
        showError('还款的目标账户必须是负债账户（信用卡/花呗等）');
        return false;
      }
      return true;
    }
    // ⚠️ 账户是原生层的硬性入账前提：LedgerRepository.validateBillAccounts
    // 无条件要求 accountId（缺失即抛 "Transaction requires an account"）。
    // 此前只有转账分支校验账户，非转账分支"不选就不传"，于是每一笔普通支出
    // 都要先失败一次才能看到错误 —— 而错误提示来自 Toast，恰好在本页可见，
    // 用户看到的是一句英文。这里补上前端校验，把问题拦在提交之前。
    if (!selectedAccountId) {
      showError('请选择账户');
      return false;
    }
    if (!selectedCategory) {
      showError('请选择分类');
      return false;
    }
    return true;
  };

  const handleSave = async (continueAfterSave: boolean = false) => {
    if (!validateForm()) return;
    const numericAmount = resolveNumericAmount().value;
    try {
      setLoading(true);
      const amountMinor = Math.round(numericAmount * 100);
      const billData = {
        amountMinor,
        // 类型锁定的编辑（退款/手续费等）保留原类型，仅允许修改金额/备注/分类/账户
        type: editingTypeLocked ? editingBill!.type : type,
        description: description.trim() || null,
        occurredAt: editingBill?.occurredAt || new Date().toISOString(),
        // 转账/还款不计收支，不写分类
        ...(isTransferLike ? {} : { categoryId: selectedCategory!.id }),
        sourceType: editingBill?.sourceType || 'manual' as const,
        ...(isTransferLike
          ? { accountId: selectedAccountId, targetAccountId: selectedTargetAccountId }
          : selectedAccountId
            ? { accountId: selectedAccountId }
            : {}),
      };

      if (isEditing) {
        await nativeData.updateBill(editingBill!.id, {
          ...billData,
          version: editingBill!.version,
        });
        invalidateCache.bills();
        showSuccess('账单更新成功');
        navigation.goBack();
        return;
      }

      // 创建路径的完整收尾（P15 幂等键 + 缓存失效 + 提示 + 返回/再记）——
      // M8 查重命中后的弹窗确认路径复用同一份，避免两处漂移。
      const completeCreate = async () => {
        await nativeData.addBill({
          ...billData,
          clientRequestId: clientRequestIdRef.current,
        });
        invalidateCache.bills();
        showSuccess('账单创建成功');
        if (continueAfterSave) {
          // 再记一笔：清空金额与备注，并刷新 P15 幂等键，避免下一笔误撞同键
          clientRequestIdRef.current = generateClientRequestId();
          setAmount('');
          setDescription('');
          setShowNoteEditor(false);
        } else {
          navigation.goBack();
        }
      };

      // M8：手动录入保存前查重（与 AI 提案同款「同日同额同向」判据，共享实现）——
      // 命中先弹确认，用户明确后再入账；查重失败保守放行（不阻塞保存）。
      // 转账/还款无收支方向语义，不参与查重。
      if (!isTransferLike) {
        let duplicates: string[] = [];
        try {
          const today = new Date();
          const dateText = `${today.getFullYear()}-${
            String(today.getMonth() + 1).padStart(2, '0')
          }-${String(today.getDate()).padStart(2, '0')}`;
          duplicates = await findDuplicateBills([{
            amount: numericAmount,
            type: type as 'income' | 'expense',
            date: dateText,
            description: description.trim(),
          }]);
        } catch (error) {
          console.warn('手动录入保存前查重失败，按无重复继续', error);
        }
        if (duplicates.length > 0) {
          pendingDuplicateActionRef.current = completeCreate;
          setDuplicateHint(duplicates.join('、'));
          setShowDuplicateConfirm(true);
          setLoading(false);
          return;
        }
      }
      await completeCreate();
    } catch (error: any) {
      showError(error.message || '操作失败');
    } finally {
      setLoading(false);
    }
  };

  /** M8 弹窗确认：执行暂存的完整创建流程（与直接保存同一错误处理）。 */
  const handleDuplicateConfirm = async () => {
    setShowDuplicateConfirm(false);
    const action = pendingDuplicateActionRef.current;
    pendingDuplicateActionRef.current = null;
    if (!action) return;
    setLoading(true);
    try {
      await action();
    } catch (error: any) {
      showError(error.message || '操作失败');
    } finally {
      setLoading(false);
    }
  };


  const handleSoftKeyPress = (key: KeypadKey) => {
    setShowNoteEditor(false);
    setAmount(prev => applyKeypadInput(prev, key));
  };

  const handleSwapTransferAccounts = () => {
    if (type === 'repayment') {
      const source = selectableAccounts.find(a => a.id === selectedAccountId);
      if (source && source.kind !== 'liability') {
        showError('还款的目标账户必须是负债账户（信用卡/花呗等）');
        return;
      }
    }
    const prevSource = selectedAccountId;
    setSelectedAccountId(selectedTargetAccountId);
    setSelectedTargetAccountId(prevSource);
  };

  const renderSharedModals = () => (
    <>
      {/* 账户选择弹窗：主账户 / 转账目标账户共用，按目标过滤候选 */}
      <Modal
        visible={accountPickerTarget !== null}
        transparent
        animationType="fade"
        onRequestClose={() => setAccountPickerTarget(null)}
      >
        <TouchableOpacity
          style={styles.accountModalOverlay}
          activeOpacity={1}
          onPress={() => setAccountPickerTarget(null)}
        >
          <View style={styles.accountModalCard}>
            <Text style={styles.accountModalTitle}>
              {accountPickerTarget === 'target'
                ? (type === 'repayment' ? '选择还款目标（限负债账户）' : '选择转入账户')
                : (isTransferLike ? '选择转出账户' : '选择账户')}
            </Text>
            <ScrollView style={styles.accountModalScroll}>
              {(accountPickerTarget === 'target'
                ? selectableTargetAccounts
                : selectableAccounts
              ).map(account => {
                const isSelected =
                  accountPickerTarget === 'target'
                    ? selectedTargetAccountId === account.id
                    : selectedAccountId === account.id;
                return (
                  <TouchableOpacity
                    key={account.id}
                    style={[
                      styles.accountModalItem,
                      isSelected && styles.accountModalItemSelected,
                    ]}
                    onPress={() => {
                      if (accountPickerTarget === 'target') {
                        setSelectedTargetAccountId(account.id);
                      } else {
                        setSelectedAccountId(account.id);
                      }
                      setAccountPickerTarget(null);
                    }}
                  >
                    <AccountIcon
                      icon={account.icon}
                      name={account.name}
                      subtype={account.subtype}
                      size={26}
                    />
                    <View style={styles.accountModalItemText}>
                      <Text style={styles.accountModalItemName} numberOfLines={1}>
                        {account.name}
                      </Text>
                      <Text style={styles.accountModalItemKind}>
                        {account.kind === 'liability' ? '负债' : '资产'}
                      </Text>
                    </View>
                    {isSelected ? (
                      <Check size={18} color={styles._colors.primary} />
                    ) : null}
                  </TouchableOpacity>
                );
              })}
              {accountPickerTarget === 'target' && selectableTargetAccounts.length === 0 ? (
                <Text style={styles.accountModalEmpty}>
                  没有可用的负债账户。请先在「账户管理」添加信用卡或消费信贷账户。
                </Text>
              ) : null}
            </ScrollView>
            <TouchableOpacity
              style={styles.accountModalCancel}
              onPress={() => setAccountPickerTarget(null)}
            >
              <Text style={styles.accountModalCancelText}>取消</Text>
            </TouchableOpacity>
          </View>
        </TouchableOpacity>
      </Modal>

      <AddCategoryModal
        visible={showAddCategoryModal}
        onClose={() => setShowAddCategoryModal(false)}
        onSuccess={handleAddCategorySuccess}
        billType={type === 'income' ? 'income' : 'expense'}
      />

      <ConfirmModal
        visible={showDeleteConfirm}
        title="删除分类"
        message={`确定要删除分类"${categoryToDelete?.name}"吗？`}
        confirmText="删除"
        cancelText="取消"
        onConfirm={handleConfirmDelete}
        onCancel={() => { setShowDeleteConfirm(false); setCategoryToDelete(null); }}
      />
      {/* M8：疑似重复二次确认（命中"同日同额同向"既有账单时先弹） */}
      <ConfirmModal
        visible={showDuplicateConfirm}
        title="疑似重复"
        message={`当日已有同金额同方向的账单：${duplicateHint}，确定仍要入账吗？`}
        confirmText="仍要入账"
        cancelText="取消"
        onConfirm={handleDuplicateConfirm}
        onCancel={() => {
          setShowDuplicateConfirm(false);
          pendingDuplicateActionRef.current = null;
        }}
      />
    </>
  );

  if (isSoft) {
    const hasOp = hasExpressionOperator(amount);
    const evaluated = evaluateAmountExpression(amount);
    const displayAmount = !amount
      ? '0.00'
      : hasOp
        ? `${evaluated < 0 ? '-' : ''}${Math.abs(evaluated).toLocaleString('zh-CN', {
            minimumFractionDigits: 2,
            maximumFractionDigits: 2,
          })}`
        : amount;
    const exprPreview = formatExpressionPreview(amount);
    const selectedSourceAccount = selectableAccounts.find(a => a.id === selectedAccountId);
    const selectedTargetAccount = selectableAccounts.find(a => a.id === selectedTargetAccountId);
    const timeLabel = editingBill
      ? formatTime(editingBill.occurredAt) || '已记录'
      : `今天 ${formatTime(new Date().toISOString())}`;

    return (
      <View style={styles.softModalRoot}>
        <TouchableOpacity
          style={styles.softBackdrop}
          activeOpacity={1}
          onPress={() => navigation.goBack()}
          accessibilityLabel="关闭记账弹层"
        />
        <View
          style={[
            styles.softSheet,
            { paddingBottom: Math.max(insets.bottom, 14) },
          ]}
          {...typeSwipeResponder.panHandlers}
        >
          <View style={styles.softGrabber} />
          <View style={styles.softSheetHeader}>
            <TouchableOpacity
              style={styles.softCloseBtn}
              onPress={() => navigation.goBack()}
              activeOpacity={0.75}
              accessibilityLabel="关闭"
              accessibilityRole="button"
            >
              <X size={18} color={colors.textSecondary} strokeWidth={2} />
            </TouchableOpacity>
            <View style={styles.softSegmentWrap}>
              <SegmentedControl
                options={FORM_TYPE_OPTIONS.map(o => ({ key: o.value, label: o.label }))}
                selectedKey={type}
                onSelect={(key) => {
                  if (!editingTypeLocked) handleTypeChange(key as BillFormType);
                }}
              />
            </View>
            <View style={styles.softHeaderSpacer} />
          </View>

          {editingTypeLocked ? (
            <Text style={styles.softLockHint}>
              退款/手续费类型不可更改（仅可修改金额、备注、分类、账户）。
            </Text>
          ) : null}

          {/* 金额主显示区（右对齐等宽大字 + 算式回显） */}
          <View style={styles.softAmountBlock}>
            <Text style={styles.softAmountExpr} numberOfLines={1}>
              {exprPreview || ' '}
            </Text>
            <View style={styles.softAmountLine}>
              <Text style={styles.softCurrency}>¥</Text>
              <Text
                style={[
                  styles.softAmountValue,
                  !amount && styles.softAmountEmpty,
                  amount && type === 'expense' && styles.softAmountExpense,
                  amount && type === 'income' && styles.softAmountIncome,
                ]}
                numberOfLines={1}
                adjustsFontSizeToFit
                minimumFontScale={0.6}
              >
                {displayAmount}
              </Text>
            </View>
          </View>

          {/* 元信息胶囊条 */}
          <ScrollView
            horizontal
            showsHorizontalScrollIndicator={false}
            style={styles.softChipScroll}
            contentContainerStyle={styles.softChipRow}
          >
            <View style={styles.softMetaChip}>
              <Calendar size={14} color={colors.textTertiary} strokeWidth={2} />
              <Text style={styles.softMetaChipText}>{timeLabel}</Text>
            </View>
            {!isTransferLike ? (
              <TouchableOpacity
                style={[
                  styles.softMetaChip,
                  !selectedSourceAccount && styles.softMetaChipWarn,
                ]}
                onPress={() =>
                  selectableAccounts.length === 0
                    ? navigation.navigate('Accounts')
                    : setAccountPickerTarget('source')
                }
                activeOpacity={0.75}
              >
                {selectedSourceAccount ? (
                  <AccountIcon
                    icon={selectedSourceAccount.icon}
                    name={selectedSourceAccount.name}
                    subtype={selectedSourceAccount.subtype}
                    size={15}
                  />
                ) : (
                  <CreditCard size={14} color={colors.textTertiary} strokeWidth={2} />
                )}
                <Text style={styles.softMetaChipText} numberOfLines={1}>
                  {selectableAccounts.length === 0
                    ? '去添加账户'
                    : selectedSourceAccount
                      ? accountDisplayName(
                          selectedSourceAccount.name,
                          resolveAccountIcon(selectedSourceAccount.icon, selectedSourceAccount.name),
                        )
                      : '选择账户'}
                </Text>
              </TouchableOpacity>
            ) : null}
            <TouchableOpacity
              style={[styles.softMetaChip, (showNoteEditor || !!description) && styles.softMetaChipActive]}
              onPress={() => setShowNoteEditor(v => !v)}
              activeOpacity={0.75}
            >
              <Pencil size={14} color={description ? colors.primary : colors.textTertiary} strokeWidth={2} />
              <Text
                style={[
                  styles.softMetaChipText,
                  description && { color: colors.primaryDark },
                ]}
                numberOfLines={1}
              >
                {description.trim() || '备注'}
              </Text>
            </TouchableOpacity>
          </ScrollView>

          {showNoteEditor ? (
            <View style={styles.softNoteInputRow}>
              <TextInput
                style={styles.softNoteInput}
                value={description}
                onChangeText={setDescription}
                placeholder="写点备注（商户、商品等）..."
                placeholderTextColor={colors.textQuaternary}
                autoFocus
                returnKeyType="done"
                onSubmitEditing={() => setShowNoteEditor(false)}
              />
              <TouchableOpacity
                style={styles.softNoteDoneBtn}
                onPress={() => setShowNoteEditor(false)}
              >
                <Text style={styles.softNoteDoneText}>完成</Text>
              </TouchableOpacity>
            </View>
          ) : null}

          {/* 中部内容区：分类网格 或 转账/还款账户卡片 */}
          <ScrollView
            style={styles.softBodyScroll}
            contentContainerStyle={styles.softBodyContent}
            showsVerticalScrollIndicator={false}
            keyboardShouldPersistTaps="handled"
          >
            <Animated.View
              style={{
                opacity: swipeOpacity,
                transform: [{ translateX: swipeTranslateX }],
              }}
            >
              {isTransferLike ? (
                <View style={styles.softXferWrap}>
                  <TouchableOpacity
                    style={styles.softXferRow}
                    onPress={() =>
                      selectableAccounts.length === 0
                        ? navigation.navigate('Accounts')
                        : setAccountPickerTarget('source')
                    }
                    activeOpacity={0.78}
                  >
                    <View style={styles.softXferIconWrap}>
                      {selectedSourceAccount ? (
                        <AccountIcon
                          icon={selectedSourceAccount.icon}
                          name={selectedSourceAccount.name}
                          subtype={selectedSourceAccount.subtype}
                          size={24}
                        />
                      ) : (
                        <CreditCard size={20} color={colors.textTertiary} />
                      )}
                    </View>
                    <View style={styles.softXferText}>
                      <Text style={styles.softXferLabel}>转出账户</Text>
                      <Text style={styles.softXferValue} numberOfLines={1}>
                        {selectedSourceAccount ? selectedSourceAccount.name : '点击选择转出账户'}
                      </Text>
                    </View>
                    <ChevronDown size={18} color={colors.textTertiary} />
                  </TouchableOpacity>

                  <TouchableOpacity
                    style={styles.softXferSwap}
                    onPress={handleSwapTransferAccounts}
                    activeOpacity={0.8}
                    accessibilityLabel="交换转出与转入账户"
                  >
                    <ArrowUpDown size={16} color={colors.primary} strokeWidth={2.2} />
                  </TouchableOpacity>

                  <TouchableOpacity
                    style={styles.softXferRow}
                    onPress={() => setAccountPickerTarget('target')}
                    activeOpacity={0.78}
                  >
                    <View style={styles.softXferIconWrap}>
                      {selectedTargetAccount ? (
                        <AccountIcon
                          icon={selectedTargetAccount.icon}
                          name={selectedTargetAccount.name}
                          subtype={selectedTargetAccount.subtype}
                          size={24}
                        />
                      ) : (
                        <CreditCard size={20} color={colors.textTertiary} />
                      )}
                    </View>
                    <View style={styles.softXferText}>
                      <Text style={styles.softXferLabel}>
                        {type === 'repayment' ? '还款到（限负债账户）' : '转入账户'}
                      </Text>
                      <Text style={styles.softXferValue} numberOfLines={1}>
                        {selectedTargetAccount
                          ? selectedTargetAccount.name
                          : type === 'repayment'
                            ? '选择信用卡 / 负债账户'
                            : '点击选择转入账户'}
                      </Text>
                    </View>
                    <ChevronDown size={18} color={colors.textTertiary} />
                  </TouchableOpacity>

                  <Text style={styles.softXferTip}>
                    {type === 'repayment'
                      ? '还款会同时减少转出账户余额与负债账户欠款。'
                      : '转账不计入收支统计，只调整两个账户余额。'}
                  </Text>
                </View>
              ) : (
                (() => {
                  const allCats = getCategoryList();
                  const topLevel = allCats.filter(cat => !cat.parentId);
                  const rows: Category[][] = [];
                  for (let i = 0; i < topLevel.length; i += 5) {
                    rows.push(topLevel.slice(i, i + 5));
                  }
                  if (rows.length === 0) rows.push([]);
                  return rows.map((rowCats, rowIndex) => (
                    <View key={`soft-cat-row-${rowIndex}`} style={styles.softCatRowWrap}>
                      <View style={styles.softCatRow}>
                        {rowCats.map(category => {
                          const children = allCats.filter(cat => cat.parentId === category.id);
                          const childSelected = children.some(cat => cat.id === selectedCategory?.id);
                          const topLevelSelected =
                            selectedCategory?.id === category.id || childSelected;
                          const expanded = expandedBillCategoryId === category.id;
                          const baseColor = category.color || getCategoryColor(category.name, '#4F46E5');
                          const tintBg = /^#[0-9a-fA-F]{6}$/.test(baseColor)
                            ? `${baseColor}${isDark ? '33' : '22'}`
                            : colors.surfaceMuted;

                          return (
                            <TouchableOpacity
                              key={category.id}
                              style={styles.softCatItem}
                              onPress={() => {
                                setSelectedCategory(category);
                                if (children.length > 0) {
                                  if (expanded || childSelected) {
                                    setExpandedBillCategoryId(null);
                                  } else {
                                    setExpandedBillCategoryId(category.id);
                                  }
                                } else {
                                  setExpandedBillCategoryId(null);
                                }
                              }}
                              onLongPress={() => handleCategoryLongPress(category)}
                              delayLongPress={500}
                              activeOpacity={0.75}
                            >
                              <View
                                style={[
                                  styles.softCatTile,
                                  {
                                    backgroundColor: topLevelSelected ? baseColor : tintBg,
                                  },
                                ]}
                              >
                                <CategoryIcon
                                  icon={category.icon}
                                  size={22}
                                  emojiStyle={styles.softCatEmoji}
                                />
                              </View>
                              <Text
                                style={[
                                  styles.softCatName,
                                  topLevelSelected && styles.softCatNameSelected,
                                ]}
                                numberOfLines={1}
                              >
                                {category.name}
                              </Text>
                            </TouchableOpacity>
                          );
                        })}
                        {rowIndex === rows.length - 1 ? (
                          <TouchableOpacity
                            style={styles.softCatItem}
                            onPress={handleOtherClick}
                            activeOpacity={0.75}
                          >
                            <View style={[styles.softCatTile, { backgroundColor: colors.surfaceMuted }]}>
                              <Plus size={20} color={colors.textSecondary} strokeWidth={2} />
                            </View>
                            <Text style={styles.softCatName} numberOfLines={1}>其他</Text>
                          </TouchableOpacity>
                        ) : null}
                      </View>

                      {expandedBillCategoryId &&
                      rowCats.some(cat => cat.id === expandedBillCategoryId) ? (
                        <View style={styles.softChildChipArea}>
                          {sortChildren(
                            allCats.filter(cat => cat.parentId === expandedBillCategoryId),
                          ).map(child => {
                            const active = selectedCategory?.id === child.id;
                            return (
                              <TouchableOpacity
                                key={child.id}
                                style={[
                                  styles.softChildChip,
                                  active && styles.softChildChipSelected,
                                ]}
                                onPress={() => setSelectedCategory(child)}
                                activeOpacity={0.75}
                              >
                                <Text
                                  style={[
                                    styles.softChildChipText,
                                    active && styles.softChildChipTextSelected,
                                  ]}
                                >
                                  {child.name}
                                </Text>
                              </TouchableOpacity>
                            );
                          })}
                        </View>
                      ) : null}
                    </View>
                  ));
                })()
              )}
            </Animated.View>
          </ScrollView>

          {/* 底部内置 4×4 计算键盘 */}
          {!showNoteEditor ? (
            <SoftBillKeypad
              onKey={handleSoftKeyPress}
              onSave={() => { void handleSave(false); }}
              onSaveAndContinue={() => { void handleSave(true); }}
              saveLabel={isEditing ? '更新' : '保存'}
              showContinue={!isEditing}
              disabled={loading}
            />
          ) : null}
        </View>

        {renderSharedModals()}
      </View>
    );
  }

  return (
    <View style={styles.container} {...typeSwipeResponder.panHandlers}>
      {/* 类型栏固定不随滑动移动；内部色块指示器跟手滑动 */}
      <View style={styles.typeSelector}>
        {/* 内层按钮行：onLayout 测到的是纯按钮区宽度，色块定位与之一一对应 */}
        <View
          style={styles.typeRow}
          onLayout={event => setTypeRowWidth(event.nativeEvent.layout.width)}
        >
          {typeButtonWidth > 0 ? (
            <Animated.View
              style={[
                styles.typeIndicator,
                {
                  width: typeButtonWidth,
                  transform: [{ translateX: indicatorTranslateX }],
                },
                type === 'expense' && styles.typeIndicatorExpense,
                type === 'income' && styles.typeIndicatorIncome,
                type === 'transfer' && styles.typeIndicatorTransfer,
                type === 'repayment' && styles.typeIndicatorRepayment,
              ]}
            />
          ) : null}
          {FORM_TYPE_OPTIONS.map(option => (
            <TouchableOpacity
              key={option.value}
              disabled={editingTypeLocked}
              style={styles.typeButton}
              onPress={() => handleTypeChange(option.value)}
            >
              <Text style={[styles.typeButtonText, type === option.value && styles.typeButtonTextActive]}>
                {option.label}
              </Text>
            </TouchableOpacity>
          ))}
        </View>
      </View>
      {editingTypeLocked && (
        <Text style={styles.typeLockHint}>
          退款/手续费类型不可更改（仅可修改金额、备注、分类、账户）；如需变更类型请删除后重新记账。
        </Text>
      )}

      <ScrollView style={styles.scrollView} showsVerticalScrollIndicator={false}>
        <Animated.View
          style={[
            styles.content,
            {
              opacity: swipeOpacity,
              transform: [{ translateX: swipeTranslateX }],
            },
          ]}
        >

          {/* 金额 + 备注合并卡片：金额大字在上，备注输入紧随其下 */}
          <View style={styles.amountCard}>
            <View style={styles.amountRow}>
              <Text style={styles.currencySymbol}>¥</Text>
              <TextInput
                style={styles.amountInput}
                value={amount}
                onChangeText={setAmount}
                placeholder="0.00"
                keyboardType="decimal-pad"
                placeholderTextColor={styles._colors.textTertiary}
              />
            </View>
            <View style={styles.amountDivider} />
            <TextInput
              style={styles.noteInput}
              value={description}
              onChangeText={setDescription}
              placeholder="添加备注..."
              placeholderTextColor={styles._colors.textTertiary}
            />
          </View>

          {/* 账户选择：弹窗选择（不再平铺全部账户） */}
          {selectableAccounts.length === 0 ? (
            <TouchableOpacity
              style={styles.accountPickerRow}
              onPress={() => navigation.navigate('Accounts')}
            >
              <Text style={styles.accountPickerPlaceholder}>尚未创建账户，点此去添加</Text>
            </TouchableOpacity>
          ) : (
            <TouchableOpacity
              style={styles.accountPickerRow}
              onPress={() => setAccountPickerTarget('source')}
            >
              {(() => {
                const selected = selectableAccounts.find(a => a.id === selectedAccountId);
                return selected ? (
                  <>
                    <AccountIcon
                      icon={selected.icon}
                      name={selected.name}
                      subtype={selected.subtype}
                      size={22}
                    />
                    <Text style={styles.accountPickerValue} numberOfLines={1}>
                      {accountDisplayName(selected.name, resolveAccountIcon(selected.icon, selected.name))}
                    </Text>
                  </>
                ) : (
                  <Text style={styles.accountPickerPlaceholder}>
                    {isTransferLike ? '选择转出账户' : '选择账户'}
                  </Text>
                );
              })()}
              <ChevronDown size={18} color={styles._colors.textSecondary} />
            </TouchableOpacity>
          )}

          {/* 目标账户（转账/还款双账户，还款限负债账户） */}
          {isTransferLike ? (
            <TouchableOpacity
              style={styles.accountPickerRow}
              onPress={() => setAccountPickerTarget('target')}
            >
              {(() => {
                const selected = selectableAccounts.find(a => a.id === selectedTargetAccountId);
                return selected ? (
                  <>
                    <AccountIcon
                      icon={selected.icon}
                      name={selected.name}
                      subtype={selected.subtype}
                      size={22}
                    />
                    <Text style={styles.accountPickerValue} numberOfLines={1}>
                      {accountDisplayName(selected.name, resolveAccountIcon(selected.icon, selected.name))}
                    </Text>
                  </>
                ) : (
                  <Text style={styles.accountPickerPlaceholder}>
                    {type === 'repayment' ? '选择还款目标（限负债账户）' : '选择转入账户'}
                  </Text>
                );
              })()}
              <ChevronDown size={18} color={styles._colors.textSecondary} />
            </TouchableOpacity>
          ) : null}

          {/* 分类选择（转账/还款不计收支，无分类） */}
          {!isTransferLike && (
          <View style={styles.formGroup}>
            {(() => {
              const allCats = getCategoryList();
              const topLevel = allCats.filter(cat => !cat.parentId);
              const rows = [];
              for (let i = 0; i < topLevel.length; i += 4) {
                rows.push(topLevel.slice(i, i + 4));
              }
              return rows.map((rowCats, rowIndex) => (
                <View key={`cat-row-${rowIndex}`} style={styles.categoryRowGrid}>
                  {rowCats.map((category) => {
                    const children = allCats.filter(cat => cat.parentId === category.id);
                    const childSelected = children.some(cat => cat.id === selectedCategory?.id);
                    const topLevelSelected =
                      selectedCategory?.id === category.id || childSelected;
                    const expanded = expandedBillCategoryId === category.id;
                    return (
                      <TouchableOpacity
                        key={category.id}
                        style={[styles.categoryItem, topLevelSelected && styles.categoryItemSelected]}
                        onPress={() => {
                          // 点击顶级分类:若其子分类已被选中,再次点击顶级会取消子分类
                          // 并使用顶级分类;否则按原逻辑展开/收起子分类面板。
                          setSelectedCategory(category);
                          if (children.length > 0) {
                            if (expanded) {
                              setExpandedBillCategoryId(null);
                            } else if (childSelected) {
                              // 取消子分类,使用顶级分类;保持面板关闭
                              setExpandedBillCategoryId(null);
                            } else {
                              setExpandedBillCategoryId(category.id);
                            }
                          } else {
                            setExpandedBillCategoryId(null);
                          }
                        }}
                        onLongPress={() => handleCategoryLongPress(category)}
                        delayLongPress={500}
                      >
                        <CategoryIcon icon={category.icon} size={24} emojiStyle={styles.categoryIcon} />
                        <Text
                          style={[styles.categoryName, topLevelSelected && styles.categoryNameSelected]}
                        >
                          {category.name}
                          {children.length > 0 ? (expanded ? ' ▾' : ' ›') : ''}
                        </Text>
                      </TouchableOpacity>
                    );
                  })}
                  {rowIndex === rows.length - 1 ? (
                    <TouchableOpacity
                      style={styles.categoryItem}
                      onPress={handleOtherClick}
                    >
                      <Text style={styles.categoryIcon}>➕</Text>
                      <Text style={styles.categoryName}>其他</Text>
                    </TouchableOpacity>
                  ) : null}
                  {expandedBillCategoryId &&
                  rowCats.some(cat => cat.id === expandedBillCategoryId) ? (
                    <View style={styles.childChipArea}>
                      {sortChildren(
                        allCats.filter(cat => cat.parentId === expandedBillCategoryId),
                      ).map((child) => (
                          <TouchableOpacity
                            key={child.id}
                            style={[
                              styles.childChip,
                              selectedCategory?.id === child.id && styles.childChipSelected,
                            ]}
                            onPress={() => {
                              // 选中子分类后保持子分类面板展开,只有点击顶级分类才收起
                              setSelectedCategory(child);
                            }}
                          >
                            <Text style={styles.childChipText}>{child.name}</Text>
                          </TouchableOpacity>
                        ))}
                    </View>
                  ) : null}
                </View>
              ));
            })()}
          </View>
          )}
        </Animated.View>
      </ScrollView>

      {/* 底部操作按钮 */}
      <View style={styles.bottomActions}>
        <TouchableOpacity style={styles.cancelButton} onPress={() => navigation.goBack()} activeOpacity={0.8}>
          <Text style={styles.cancelButtonText}>取消</Text>
        </TouchableOpacity>
        <TouchableOpacity style={styles.saveButton} onPress={() => { void handleSave(false); }} disabled={loading} activeOpacity={0.8}>
          {/* 文案此前写"下一步"，但 handleSave 没有任何后续步骤：直接提交并 goBack。
            与行为不符的按钮会让人以为还有一屏内容。 */}
          <Text style={styles.saveButtonText}>{isEditing ? '更新账单' : '保存'}</Text>
        </TouchableOpacity>
      </View>

      {/* Toast 已提升为全局单例（ToastProvider），此处不再重复渲染 ——
          否则本页面与全局 Toast 会同时显示同一条提示。 */}

      {renderSharedModals()}
    </View>
  );
};

const createStyles = (colors: ThemeColors, shape: ThemeShape, isDark: boolean) => ({
  ...StyleSheet.create({
    softModalRoot: {
      flex: 1,
      justifyContent: 'flex-end',
    },
    softBackdrop: {
      ...StyleSheet.absoluteFillObject,
      backgroundColor: colors.overlay,
    },
    softSheet: {
      height: '92%',
      backgroundColor: isDark ? '#151820' : colors.surface,
      borderTopLeftRadius: shape.radius.sheet,
      borderTopRightRadius: shape.radius.sheet,
      paddingHorizontal: 16,
      paddingTop: 8,
      ...shape.elevation.floating,
    },
    softGrabber: {
      width: 40,
      height: 5,
      borderRadius: 999,
      backgroundColor: colors.stroke,
      alignSelf: 'center',
      marginBottom: 10,
    },
    softSheetHeader: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 8,
    },
    softCloseBtn: {
      width: 34,
      height: 34,
      borderRadius: 12,
      alignItems: 'center',
      justifyContent: 'center',
    },
    softSegmentWrap: {
      flex: 1,
    },
    softHeaderSpacer: {
      width: 34,
    },
    softLockHint: {
      fontSize: 12,
      color: colors.textTertiary,
      marginTop: 8,
      textAlign: 'center',
    },
    softAmountBlock: {
      paddingTop: 18,
      paddingBottom: 12,
      paddingHorizontal: 4,
      alignItems: 'flex-end',
    },
    softAmountExpr: {
      fontSize: 13,
      color: colors.textTertiary,
      fontVariant: ['tabular-nums'],
      minHeight: 18,
    },
    softAmountLine: {
      flexDirection: 'row',
      alignItems: 'baseline',
      justifyContent: 'flex-end',
    },
    softCurrency: {
      fontSize: 24,
      fontWeight: '600',
      color: colors.textTertiary,
      marginRight: 4,
    },
    softAmountValue: {
      fontSize: 44,
      fontWeight: '700',
      letterSpacing: -0.8,
      color: colors.textPrimary,
      fontVariant: ['tabular-nums'],
    },
    softAmountEmpty: {
      color: colors.textQuaternary,
    },
    softAmountExpense: {
      color: colors.expense,
    },
    softAmountIncome: {
      color: colors.income,
    },
    softChipScroll: {
      flexGrow: 0,
      borderBottomWidth: StyleSheet.hairlineWidth,
      borderBottomColor: colors.divider,
    },
    softChipRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 8,
      paddingBottom: 12,
    },
    softMetaChip: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 6,
      height: 32,
      paddingHorizontal: 12,
      borderRadius: 999,
      backgroundColor: colors.surfaceMuted,
      maxWidth: 168,
    },
    softMetaChipActive: {
      backgroundColor: colors.primaryLight,
    },
    softMetaChipWarn: {
      backgroundColor: isDark ? '#2E2410' : '#FFF5E6',
    },
    softMetaChipText: {
      fontSize: 13,
      fontWeight: '500',
      color: colors.textSecondary,
      flexShrink: 1,
    },
    softNoteInputRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 8,
      paddingVertical: 10,
      borderBottomWidth: StyleSheet.hairlineWidth,
      borderBottomColor: colors.divider,
    },
    softNoteInput: {
      flex: 1,
      height: 40,
      paddingHorizontal: 12,
      borderRadius: 12,
      backgroundColor: colors.surfaceMuted,
      fontSize: 14,
      color: colors.textPrimary,
    },
    softNoteDoneBtn: {
      paddingHorizontal: 14,
      height: 40,
      borderRadius: 12,
      backgroundColor: colors.primary,
      alignItems: 'center',
      justifyContent: 'center',
    },
    softNoteDoneText: {
      fontSize: 14,
      fontWeight: '600',
      color: colors.onPrimary,
    },
    softBodyScroll: {
      flex: 1,
    },
    softBodyContent: {
      paddingVertical: 14,
    },
    softCatRowWrap: {
      marginBottom: 12,
    },
    softCatRow: {
      flexDirection: 'row',
    },
    softCatItem: {
      width: '20%',
      alignItems: 'center',
      gap: 6,
    },
    softCatTile: {
      width: 48,
      height: 48,
      borderRadius: 16,
      alignItems: 'center',
      justifyContent: 'center',
    },
    softCatEmoji: {
      fontSize: 22,
    },
    softCatName: {
      fontSize: 12,
      fontWeight: '400',
      color: colors.textSecondary,
      textAlign: 'center',
    },
    softCatNameSelected: {
      color: colors.textPrimary,
      fontWeight: '600',
    },
    softChildChipArea: {
      flexDirection: 'row',
      flexWrap: 'wrap',
      gap: 8,
      marginTop: 10,
      padding: 10,
      borderRadius: 14,
      backgroundColor: colors.surfaceMuted,
    },
    softChildChip: {
      paddingHorizontal: 12,
      paddingVertical: 6,
      borderRadius: 999,
      backgroundColor: colors.surface,
    },
    softChildChipSelected: {
      backgroundColor: colors.primary,
    },
    softChildChipText: {
      fontSize: 12.5,
      fontWeight: '500',
      color: colors.textSecondary,
    },
    softChildChipTextSelected: {
      color: colors.onPrimary,
      fontWeight: '600',
    },
    softXferWrap: {
      gap: 10,
      position: 'relative',
    },
    softXferRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 12,
      padding: 14,
      borderRadius: 18,
      backgroundColor: colors.surfaceMuted,
    },
    softXferIconWrap: {
      width: 40,
      height: 40,
      borderRadius: 14,
      backgroundColor: colors.surface,
      alignItems: 'center',
      justifyContent: 'center',
    },
    softXferText: {
      flex: 1,
      gap: 2,
    },
    softXferLabel: {
      fontSize: 12,
      color: colors.textTertiary,
    },
    softXferValue: {
      fontSize: 15,
      fontWeight: '600',
      color: colors.textPrimary,
    },
    softXferSwap: {
      position: 'absolute',
      right: 18,
      top: 52,
      width: 34,
      height: 34,
      borderRadius: 17,
      backgroundColor: colors.surface,
      alignItems: 'center',
      justifyContent: 'center',
      zIndex: 2,
      ...shape.elevation.floating,
    },
    softXferTip: {
      fontSize: 12,
      color: colors.textTertiary,
      paddingHorizontal: 4,
      marginTop: 2,
    },
    container: { flex: 1, backgroundColor: colors.background },
    scrollView: { flex: 1 },
    content: { padding: spacing.lg, paddingBottom: 100 },
    typeSelector: {
      backgroundColor: colors.surface,
      borderRadius: borderRadius.button,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      padding: 4,
      marginBottom: spacing.md,
      marginTop: spacing.md,
      marginHorizontal: spacing.lg,
    },
    // 纯按钮行：宽度即 4 个 Tab 的总宽，色块按它等分定位
    typeRow: {
      flexDirection: 'row',
    },
    // 滑动色块：绝对定位在 Tab 文字之下，靠 translateX 滑动
    typeIndicator: {
      position: 'absolute',
      top: 0,
      bottom: 0,
      left: 0,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
    },
    typeIndicatorExpense: { backgroundColor: colors.expense },
    typeIndicatorIncome: { backgroundColor: colors.income },
    typeIndicatorTransfer: { backgroundColor: colors.primary },
    typeIndicatorRepayment: { backgroundColor: colors.warning },
    typeButton: {
      flex: 1,
      paddingVertical: spacing.sm,
      alignItems: 'center',
      borderRadius: borderRadius.small,
    },
    typeButtonText: { fontSize: 16, fontWeight: '700', color: colors.textSecondary },
    typeButtonTextActive: { color: '#FFFFFF', fontWeight: '800' },
    typeLockHint: {
      fontSize: 12,
      color: colors.textSecondary,
      marginTop: spacing.xs,
      marginHorizontal: spacing.lg,
    },
    formGroup: { marginBottom: spacing.lg },
    // 金额 + 备注合并卡片：大字金额在上，备注行在下，用细分割线区隔
    amountCard: {
      marginTop: spacing.lg,
      marginBottom: spacing.lg,
      backgroundColor: colors.surface,
      borderRadius: borderRadius.card,
      borderWidth: borderWidth.medium,
      borderColor: colors.stroke,
      paddingHorizontal: spacing.lg,
      paddingTop: spacing.md,
      paddingBottom: spacing.sm,
    },
    amountRow: {
      flexDirection: 'row',
      alignItems: 'center',
    },
    amountDivider: {
      height: StyleSheet.hairlineWidth * 2,
      backgroundColor: colors.divider,
      marginVertical: spacing.sm,
    },
    noteInput: {
      fontSize: 14,
      fontWeight: '600',
      color: colors.textPrimary,
      padding: 0,
      paddingVertical: spacing.xs,
    },
    currencySymbol: {
      fontSize: 28,
      fontWeight: '800',
      fontFamily: typography.fontFamily.mono,
      color: colors.textPrimary,
      marginRight: spacing.sm,
    },
    amountInput: {
      flex: 1,
      fontSize: 28,
      fontWeight: '800',
      fontFamily: typography.fontFamily.mono,
      color: colors.textPrimary,
      padding: 0,
    },
    // 账户下拉选择行：图标 + 名称 + 下箭头
    accountPickerRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
      minHeight: 52,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.sm,
      marginBottom: spacing.md,
      backgroundColor: colors.surface,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
    },
    accountPickerValue: {
      flex: 1,
      fontSize: 15,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    accountPickerPlaceholder: {
      flex: 1,
      fontSize: 14,
      fontWeight: '600',
      color: colors.textTertiary,
    },
    // 账户选择弹窗
    accountModalOverlay: {
      flex: 1,
      backgroundColor: colors.overlay,
      justifyContent: 'flex-end',
    },
    accountModalCard: {
      backgroundColor: shape.isSoft ? colors.surface : colors.background,
      borderTopLeftRadius: shape.isSoft ? shape.radius.sheet : borderRadius.card,
      borderTopRightRadius: shape.isSoft ? shape.radius.sheet : borderRadius.card,
      borderWidth: shape.isSoft ? shape.borderWidth.card : borderWidth.medium,
      borderColor: shape.isSoft ? colors.cardBorder : colors.stroke,
      padding: spacing.lg,
      paddingBottom: spacing.xl,
      maxHeight: '70%',
    },
    accountModalTitle: {
      fontSize: 16,
      fontWeight: shape.isSoft ? '700' : '800',
      color: colors.textPrimary,
      marginBottom: spacing.md,
      textAlign: 'center',
    },
    accountModalScroll: { maxHeight: 420 },
    accountModalItem: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.md,
      paddingVertical: spacing.md,
      paddingHorizontal: spacing.sm,
      borderRadius: shape.isSoft ? 14 : 0,
      borderBottomWidth: shape.isSoft ? StyleSheet.hairlineWidth : borderWidth.thin,
      borderBottomColor: colors.divider,
    },
    accountModalItemSelected: { backgroundColor: colors.primaryLight },
    accountModalItemText: { flex: 1 },
    accountModalItemName: { fontSize: 15, fontWeight: shape.isSoft ? '600' : '700', color: colors.textPrimary },
    accountModalItemKind: { marginTop: 2, fontSize: 12, fontWeight: shape.isSoft ? '400' : '600', color: colors.textTertiary },
    accountModalEmpty: {
      paddingVertical: spacing.lg,
      fontSize: 13,
      fontWeight: shape.isSoft ? '500' : '600',
      color: colors.textSecondary,
      textAlign: 'center',
    },
    accountModalCancel: {
      marginTop: spacing.md,
      paddingVertical: shape.isSoft ? 12 : spacing.sm,
      alignItems: 'center',
      borderRadius: shape.isSoft ? shape.radius.button : borderRadius.button,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: shape.isSoft ? colors.surfaceMuted : colors.surface,
    },
    accountModalCancelText: { fontSize: 14, fontWeight: shape.isSoft ? '600' : '700', color: colors.textPrimary },
      categoryGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.md },
      // 批 6:按行分块的容器,行内 4 个正方形瓷贴;展开的子分类区插在行后
      categoryRowGrid: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        width: '100%',
        gap: spacing.sm,
        marginBottom: spacing.xs,
      },
      categoryItem: {
        width: '22%',
        aspectRatio: 1,
        justifyContent: 'center',
        alignItems: 'center',
        borderRadius: borderRadius.small,
        borderWidth: borderWidth.thin,
        borderColor: colors.stroke,
        backgroundColor: colors.surface,
        padding: spacing.sm,
      },
    categoryItemSelected: {
      backgroundColor: colors.accent,
      borderWidth: borderWidth.medium,
      borderColor: colors.stroke,
    },
    categoryIcon: { fontSize: 24, marginBottom: 4 },
    categoryName: { fontSize: 12, color: colors.textPrimary, textAlign: 'center', fontWeight: '600' },
    categoryNameSelected: { color: colors.textPrimary, fontWeight: '800' },
    // 批 6:子分类动态区(点顶级后展开,芯片自动换行,区域高度动态)
    childChipArea: {
      width: '100%',
      flexDirection: 'row',
      flexWrap: 'wrap',
      gap: spacing.sm,
      marginTop: spacing.sm,
      marginBottom: spacing.sm,
      paddingHorizontal: spacing.xs,
    },
    childChip: {
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.sm,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: colors.surface,
    },
    childChipSelected: {
      backgroundColor: colors.accent,
      borderWidth: borderWidth.medium,
    },
    childChipText: {
      fontSize: 13,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    bottomActions: {
      flexDirection: 'row',
      gap: spacing.md,
      padding: spacing.lg,
      paddingBottom: spacing.xxl,
      backgroundColor: colors.background,
      borderTopWidth: borderWidth.thin,
      borderTopColor: colors.stroke,
    },
    cancelButton: {
      flex: 1,
      height: 52,
      borderRadius: borderRadius.button,
      backgroundColor: colors.surface,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      alignItems: 'center',
      justifyContent: 'center',
    },
    cancelButtonText: { fontSize: 16, fontWeight: '700', color: colors.textPrimary },
    saveButton: {
      flex: 1,
      height: 52,
      borderRadius: borderRadius.button,
      backgroundColor: colors.primary,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      alignItems: 'center',
      justifyContent: 'center',
      ...shadow.small,
    },
    saveButtonText: { fontSize: 16, fontWeight: '800', color: '#FFFFFF' },
  }),
  _colors: colors,
});

export default CreateBillScreen;

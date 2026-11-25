import React, { useMemo, useState } from 'react';
import {
  ActivityIndicator,
  Modal,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Activity,
  Bot,
  Check,
  ChevronLeft,
  Eye,
  Pencil,
  Plus,
  Trash2,
  Wifi,
} from 'lucide-react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import {
  nativeAi,
  type AiProvider,
  type AiProviderConfig,
  type AiProviderInput,
  type AiProviderUpdateInput,
  type TestConnectionResult,
} from '../../native';
import { borderRadius, borderWidth, shadow, spacing } from '../../theme/spacing';
import { ThemeColors } from '../../theme/colors';
import { ThemeShape } from '../../theme/shape';
import { typography } from '../../theme';
import { useStyles } from '../../hooks';
import { useAlert, useTheme } from '../../providers';
import { AI_PROVIDERS } from '../../types/ai';
import { ProviderIcon } from '../../components/icons';
import { HOGWARTS_FONTS } from '../../constants/hogwartsAssets';

interface ProviderOption {
  value: AiProvider;
  label: string;
  baseUrl: string;
  models: string[];
  supportsVision: boolean;
}

const PROVIDER_ORDER: AiProvider[] = ['qwen', 'deepseek', 'glm', 'siliconflow', 'custom'];

const PROVIDER_OPTIONS: ProviderOption[] = PROVIDER_ORDER.map((value) => ({
  value,
  label: AI_PROVIDERS[value].name,
  baseUrl: AI_PROVIDERS[value].defaultUrl,
  models: AI_PROVIDERS[value].models,
  supportsVision: AI_PROVIDERS[value].supportsVision,
}));

const PROVIDER_QUERY_KEY = ['ai-providers'] as const;
const AI_BUDGET_QUERY_KEY = ['ai-budget'] as const;
const AI_COST_RECORDS_QUERY_KEY = ['ai-cost-records'] as const;

const VISION_MODEL_PATTERN = /(vl|4v|4\.6v|omni|vision)/i;

function formatCompactTokens(value: number): string {
  if (value >= 1_000_000) {
    return `${(value / 1_000_000).toFixed(1)}M`;
  }
  if (value >= 1_000) {
    return `${(value / 1_000).toFixed(1)}k`;
  }
  return String(value);
}

function getProviderLabel(provider: AiProvider): string {
  return AI_PROVIDERS[provider]?.name ?? provider;
}

export default function AiProviderScreen() {
  const navigation = useNavigation();
  const queryClient = useQueryClient();
  const styles = useStyles(createStyles);
  const { isHogwarts } = useTheme();
  const { alert, confirm } = useAlert();

  const [showForm, setShowForm] = useState(false);
  const [editingProvider, setEditingProvider] = useState<AiProviderConfig | null>(null);
  const [name, setName] = useState('');
  const [provider, setProvider] = useState<AiProvider>('qwen');
  const [model, setModel] = useState(PROVIDER_OPTIONS[0].models[0]);
  const [apiKey, setApiKey] = useState('');
  const [baseUrl, setBaseUrl] = useState(PROVIDER_OPTIONS[0].baseUrl);
  const [supportsVision, setSupportsVision] = useState(true);
  const [isDefault, setIsDefault] = useState(false);
  const [testResults, setTestResults] = useState<Record<string, TestConnectionResult>>({});

  const providersQuery = useQuery({
    queryKey: PROVIDER_QUERY_KEY,
    queryFn: () => nativeAi.getAiProviders(),
  });

  const budgetQuery = useQuery({
    queryKey: AI_BUDGET_QUERY_KEY,
    queryFn: () => nativeAi.getAiBudget(),
  });

  const costRecordsQuery = useQuery({
    queryKey: AI_COST_RECORDS_QUERY_KEY,
    queryFn: () => nativeAi.getAiCostRecords(),
  });

  const usageSummary = useMemo(() => {
    const records = costRecordsQuery.data ?? [];
    const tokensIn = records.reduce((sum, item) => sum + (item.tokensIn ?? 0), 0);
    const tokensOut = records.reduce((sum, item) => sum + (item.tokensOut ?? 0), 0);
    const requestCount = budgetQuery.data?.requestCount ?? records.filter((item) => item.success).length;
    const month = budgetQuery.data?.month ?? new Date().toISOString().slice(0, 7);
    return {
      month,
      requestCount,
      tokensIn,
      tokensOut,
    };
  }, [budgetQuery.data, costRecordsQuery.data]);

  const refreshProviders = () => {
    queryClient.invalidateQueries({ queryKey: PROVIDER_QUERY_KEY });
    queryClient.invalidateQueries({ queryKey: ['runtime-status'] });
  };

  const saveMutation = useMutation({
    mutationFn: ({ id, input }: { id?: string; input: AiProviderInput | AiProviderUpdateInput }) =>
      id ? nativeAi.updateAiProvider(id, input) : nativeAi.addAiProvider(input as AiProviderInput),
    onSuccess: () => {
      refreshProviders();
      closeForm();
    },
    onError: (error: Error) => alert('保存失败', error.message),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => nativeAi.deleteAiProvider(id),
    onSuccess: refreshProviders,
    onError: (error: Error) => alert('删除失败', error.message),
  });

  const defaultMutation = useMutation({
    mutationFn: (id: string) => nativeAi.setDefaultAiProvider(id),
    onSuccess: refreshProviders,
    onError: (error: Error) => alert('设置失败', error.message),
  });

  const testMutation = useMutation({
    mutationFn: (id: string) => nativeAi.testAiProvider(id),
    onSuccess: (result, id) => {
      setTestResults((prev) => ({ ...prev, [id]: result }));
      const latencySuffix =
        typeof result.latencyMs === 'number' ? `\n耗时 ${result.latencyMs}ms` : '';
      alert(
        result.success ? '连接成功' : '连接失败',
        `${result.message}${latencySuffix}`,
      );
    },
    onError: (error: Error) => alert('测试失败', error.message),
  });

  const selectedProvider =
    PROVIDER_OPTIONS.find((option) => option.value === provider) ?? PROVIDER_OPTIONS[0];

  const openCreate = () => {
    setEditingProvider(null);
    setName('');
    setProvider('qwen');
    setModel(PROVIDER_OPTIONS[0].models[0]);
    setApiKey('');
    setBaseUrl(PROVIDER_OPTIONS[0].baseUrl);
    setSupportsVision(true);
    setIsDefault((providersQuery.data ?? []).length === 0);
    setShowForm(true);
  };

  const openEdit = (config: AiProviderConfig) => {
    const option = PROVIDER_OPTIONS.find((item) => item.value === config.provider);
    setEditingProvider(config);
    setName(config.name);
    setProvider(config.provider);
    setModel(config.model);
    setApiKey('');
    setBaseUrl(config.apiBaseUrl ?? option?.baseUrl ?? '');
    setSupportsVision(config.supportsVision);
    setIsDefault(config.isDefault);
    setShowForm(true);
  };

  const closeForm = () => {
    setShowForm(false);
    setEditingProvider(null);
  };

  const changeProvider = (value: AiProvider) => {
    const option = PROVIDER_OPTIONS.find((item) => item.value === value) ?? PROVIDER_OPTIONS[0];
    setProvider(value);
    if (!editingProvider || value !== provider) {
      setModel(option.models[0] ?? '');
    }
    setBaseUrl(option.baseUrl);
    setSupportsVision(option.supportsVision);
  };

  const selectPresetModel = (presetModel: string) => {
    setModel(presetModel);
    if (VISION_MODEL_PATTERN.test(presetModel)) {
      setSupportsVision(true);
    }
  };

  const submitForm = () => {
    if (!name.trim() || !model.trim()) {
      alert('提示', '请填写名称和模型');
      return;
    }
    if (!editingProvider && !apiKey.trim()) {
      alert('提示', '请输入 API Key');
      return;
    }
    if (provider === 'custom' && !baseUrl.trim()) {
      alert('提示', '自定义服务商必须填写 API 地址');
      return;
    }
    const common = {
      name: name.trim(),
      provider,
      model: model.trim(),
      apiBaseUrl: baseUrl.trim() || undefined,
      supportsVision,
      isDefault,
    };
    if (editingProvider) {
      saveMutation.mutate({
        id: editingProvider.id,
        input: { ...common, apiKey: apiKey.trim() || '********' },
      });
    } else {
      saveMutation.mutate({ input: { ...common, apiKey: apiKey.trim() } });
    }
  };

  const handleDelete = (config: AiProviderConfig) => {
    confirm(
      '删除服务商',
      `确认删除「${config.name}」？`,
      () => deleteMutation.mutate(config.id),
      undefined,
      { confirmText: '删除', destructive: true },
    );
  };

  const providers = providersQuery.data ?? [];

  return (
    <SafeAreaView style={styles.container} edges={['top', 'bottom']}>
      <View style={styles.header}>
        <TouchableOpacity
          testID="ai-providers-back-btn"
          onPress={() => navigation.goBack()}
          style={styles.iconButton}
        >
          <ChevronLeft size={24} color={styles._colors.textPrimary} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>
          {isHogwarts ? '魔导契约' : 'AI 服务商'}
        </Text>
        <TouchableOpacity
          testID="ai-providers-add-btn"
          onPress={openCreate}
          style={styles.iconButton}
        >
          <Plus size={22} color={styles._colors.textPrimary} strokeWidth={2.5} />
        </TouchableOpacity>
      </View>

      <ScrollView style={styles.scrollView} contentContainerStyle={styles.content}>
        <View style={styles.notice}>
          <Bot size={20} color={styles._colors.primary} />
          <Text style={styles.noticeText}>
            配置仅保存在本机，API Key 由 Android Keystore 硬件级加密；测试连接会发起真实模型探针。
          </Text>
        </View>

        <View style={styles.usageCard} testID="ai-usage-card">
          <View style={styles.usageHeader}>
            <View style={styles.usageTitleRow}>
              <Activity size={16} color={styles._colors.primary} />
              <Text style={styles.usageTitle}>本月用量统计</Text>
            </View>
            <Text style={styles.usageMonth}>{usageSummary.month}</Text>
          </View>
          <View style={styles.usageGrid}>
            <View style={styles.usageItem}>
              <Text style={styles.usageValue} testID="ai-usage-calls">
                {usageSummary.requestCount}
              </Text>
              <Text style={styles.usageLabel}>成功调用</Text>
            </View>
            <View style={styles.usageDivider} />
            <View style={styles.usageItem}>
              <Text style={styles.usageValue} testID="ai-usage-tokens-in">
                {formatCompactTokens(usageSummary.tokensIn)}
              </Text>
              <Text style={styles.usageLabel}>输入 Tokens</Text>
            </View>
            <View style={styles.usageDivider} />
            <View style={styles.usageItem}>
              <Text style={styles.usageValue} testID="ai-usage-tokens-out">
                {formatCompactTokens(usageSummary.tokensOut)}
              </Text>
              <Text style={styles.usageLabel}>输出 Tokens</Text>
            </View>
          </View>
        </View>

        {providers.length === 0 ? (
          <View style={styles.emptyState}>
            <Bot size={42} color={styles._colors.textTertiary} />
            <Text style={styles.emptyTitle}>还没有配置 AI 服务商</Text>
            <Text style={styles.emptyDesc}>
              支持通义千问、DeepSeek、智谱 GLM、SiliconFlow 与任意 OpenAI 兼容接口
            </Text>
            <TouchableOpacity
              testID="ai-providers-empty-add-btn"
              style={styles.primaryButton}
              onPress={openCreate}
            >
              <Plus size={18} color={styles._primaryIconColor} />
              <Text style={styles.primaryButtonText}>添加服务商</Text>
            </TouchableOpacity>
          </View>
        ) : (
          providers.map((config) => {
            const lastTest = testResults[config.id];
            const isTesting = testMutation.isPending && testMutation.variables === config.id;
            return (
              <View key={config.id} style={styles.providerCard}>
                <View style={styles.providerTopRow}>
                  <View style={styles.providerIcon}>
                    <ProviderIcon provider={config.provider} size={24} />
                  </View>
                  <View style={styles.providerMain}>
                    <View style={styles.providerNameRow}>
                      <Text style={styles.providerName} numberOfLines={1}>
                        {config.name}
                      </Text>
                      {config.isDefault && (
                        <View style={styles.defaultBadge}>
                          <Text style={styles.defaultBadgeText}>默认</Text>
                        </View>
                      )}
                      {config.supportsVision && (
                        <View style={styles.visionBadge}>
                          <Eye size={10} color={styles._colors.primary} />
                          <Text style={styles.visionBadgeText}>识图</Text>
                        </View>
                      )}
                    </View>
                    <Text style={styles.providerMeta} numberOfLines={1}>
                      {getProviderLabel(config.provider)} · {config.model}
                    </Text>
                    <Text style={styles.providerKey}>
                      API Key: {config.apiKey ?? '未配置'}
                    </Text>
                  </View>
                </View>

                {lastTest ? (
                  <View
                    style={[
                      styles.testStatusBanner,
                      lastTest.success ? styles.testStatusSuccess : styles.testStatusError,
                    ]}
                  >
                    <Text
                      style={[
                        styles.testStatusText,
                        lastTest.success ? styles.testStatusTextSuccess : styles.testStatusTextError,
                      ]}
                      numberOfLines={2}
                    >
                      {lastTest.message}
                      {typeof lastTest.latencyMs === 'number' ? ` (${lastTest.latencyMs}ms)` : ''}
                    </Text>
                  </View>
                ) : null}

                <View style={styles.providerActions}>
                  <TouchableOpacity
                    testID={`ai-provider-test-${config.id}`}
                    style={styles.actionButton}
                    onPress={() => testMutation.mutate(config.id)}
                    disabled={isTesting}
                  >
                    {isTesting ? (
                      <ActivityIndicator size="small" color={styles._colors.primary} />
                    ) : (
                      <Wifi size={15} color={styles._colors.primary} />
                    )}
                    <Text style={styles.actionText}>测试</Text>
                  </TouchableOpacity>
                  {!config.isDefault && (
                    <TouchableOpacity
                      style={styles.actionButton}
                      onPress={() => defaultMutation.mutate(config.id)}
                    >
                      <Check size={15} color={styles._colors.success} />
                      <Text style={styles.actionText}>设为默认</Text>
                    </TouchableOpacity>
                  )}
                  <TouchableOpacity
                    testID={`ai-provider-edit-${config.id}`}
                    style={styles.actionButton}
                    onPress={() => openEdit(config)}
                  >
                    <Pencil size={15} color={styles._colors.textPrimary} />
                    <Text style={styles.actionText}>编辑</Text>
                  </TouchableOpacity>
                  <TouchableOpacity
                    style={styles.iconAction}
                    onPress={() => handleDelete(config)}
                  >
                    <Trash2 size={17} color={styles._colors.error} />
                  </TouchableOpacity>
                </View>
              </View>
            );
          })
        )}
      </ScrollView>

      <Modal visible={showForm} transparent animationType="fade" onRequestClose={closeForm}>
        <View style={styles.modalOverlay}>
          <ScrollView contentContainerStyle={styles.modalScroll} keyboardShouldPersistTaps="handled">
            <View style={styles.modalCard}>
              <Text style={styles.modalTitle}>
                {editingProvider ? '编辑服务商' : '添加服务商'}
              </Text>

              <Text style={styles.inputLabel}>名称</Text>
              <TextInput
                testID="ai-name-input"
                style={styles.input}
                value={name}
                onChangeText={setName}
                placeholder="例如：我的 DeepSeek"
                placeholderTextColor={styles._colors.textTertiary}
              />

              <Text style={styles.inputLabel}>服务商</Text>
              <View style={styles.providerOptions}>
                {PROVIDER_OPTIONS.map((option) => (
                  <TouchableOpacity
                    key={option.value}
                    testID={`ai-provider-option-${option.value}`}
                    style={[
                      styles.providerOption,
                      provider === option.value && styles.providerOptionSelected,
                    ]}
                    onPress={() => changeProvider(option.value)}
                  >
                    <ProviderIcon provider={option.value} size={14} />
                    <Text
                      style={[
                        styles.providerOptionText,
                        provider === option.value && styles.providerOptionTextSelected,
                      ]}
                    >
                      {option.label}
                    </Text>
                  </TouchableOpacity>
                ))}
              </View>

              <Text style={styles.inputLabel}>API Key</Text>
              <TextInput
                testID="ai-key-input"
                style={styles.input}
                value={apiKey}
                onChangeText={setApiKey}
                placeholder={editingProvider ? '留空保持不变' : '请输入 API Key'}
                placeholderTextColor={styles._colors.textTertiary}
                secureTextEntry
                autoCapitalize="none"
                autoCorrect={false}
              />

              <Text style={styles.inputLabel}>API 地址</Text>
              <TextInput
                testID="ai-url-input"
                style={styles.input}
                value={baseUrl}
                onChangeText={setBaseUrl}
                placeholder="https://"
                placeholderTextColor={styles._colors.textTertiary}
                autoCapitalize="none"
                autoCorrect={false}
              />

              <Text style={styles.inputLabel}>模型名称（可点选或自由输入）</Text>
              <TextInput
                testID="ai-model-input"
                style={styles.input}
                value={model}
                onChangeText={setModel}
                placeholder="输入任意兼容模型名称"
                placeholderTextColor={styles._colors.textTertiary}
                autoCapitalize="none"
                autoCorrect={false}
              />
              {selectedProvider.models.length > 0 ? (
                <View style={styles.modelChipsWrap}>
                  {selectedProvider.models.map((presetModel) => {
                    const active = model.trim() === presetModel;
                    return (
                      <TouchableOpacity
                        key={presetModel}
                        testID={`ai-model-chip-${presetModel}`}
                        style={[styles.modelChip, active && styles.modelChipSelected]}
                        onPress={() => selectPresetModel(presetModel)}
                      >
                        <Text
                          style={[
                            styles.modelChipText,
                            active && styles.modelChipTextSelected,
                          ]}
                          numberOfLines={1}
                        >
                          {presetModel}
                        </Text>
                      </TouchableOpacity>
                    );
                  })}
                </View>
              ) : null}

              <View style={styles.switchRow}>
                <View style={styles.switchLabelWrap}>
                  <Text style={styles.switchText}>支持视觉识别（识图记账）</Text>
                  <Text style={styles.switchHint}>
                    使用多模态模型（如 VL / Omni）时可开启
                  </Text>
                </View>
                <Switch
                  testID="ai-vision-switch"
                  value={supportsVision}
                  onValueChange={setSupportsVision}
                  trackColor={{ false: styles._colors.divider, true: styles._colors.primary }}
                />
              </View>

              <View style={styles.switchRow}>
                <Text style={styles.switchText}>设为默认</Text>
                <Switch
                  testID="ai-default-switch"
                  value={isDefault}
                  onValueChange={setIsDefault}
                  trackColor={{ false: styles._colors.divider, true: styles._colors.primary }}
                />
              </View>

              <View style={styles.modalActions}>
                <TouchableOpacity style={styles.secondaryButton} onPress={closeForm}>
                  <Text style={styles.secondaryButtonText}>取消</Text>
                </TouchableOpacity>
                <TouchableOpacity
                  testID="ai-form-submit-btn"
                  style={styles.primaryButton}
                  onPress={submitForm}
                  disabled={saveMutation.isPending}
                >
                  <Text style={styles.primaryButtonText}>
                    {saveMutation.isPending ? '保存中' : '保存'}
                  </Text>
                </TouchableOpacity>
              </View>
            </View>
          </ScrollView>
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
      backgroundColor: shape.isSoft ? colors.background : colors.surface,
      borderBottomWidth: shape.isSoft ? 0 : shape.borderWidth.subtle,
      borderBottomColor: shape.isHogwarts ? colors.divider : colors.stroke,
    },
    iconButton: { width: 40, height: 40, alignItems: 'center', justifyContent: 'center' },
    headerTitle: {
      fontSize: shape.isSoft ? 18 : 20,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
    },
    scrollView: { flex: 1 },
    content: { padding: spacing.lg, gap: spacing.md, paddingBottom: spacing.xl },
    notice: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
      padding: spacing.md,
      backgroundColor: colors.primaryLight,
      borderWidth: shape.isSoft ? 0 : shape.borderWidth.card,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      borderRadius: shape.radius.card,
    },
    noticeText: {
      flex: 1,
      fontSize: 12.5,
      lineHeight: 18,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: shape.isSoft ? colors.primaryDark : colors.textPrimary,
    },
    usageCard: {
      padding: spacing.md,
      backgroundColor: colors.surface,
      borderWidth: shape.isSoft || shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.cardBorder : colors.stroke,
      borderRadius: shape.radius.card,
      ...(shape.isSoft || shape.isHogwarts ? shape.elevation.card : {}),
    },
    usageHeader: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      marginBottom: spacing.sm,
    },
    usageTitleRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 6,
    },
    usageTitle: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
    },
    usageMonth: {
      fontSize: 12,
      color: colors.textTertiary,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.numeric : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
    },
    usageGrid: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingTop: spacing.xs,
    },
    usageItem: {
      flex: 1,
      alignItems: 'center',
      gap: 2,
    },
    usageDivider: {
      width: StyleSheet.hairlineWidth,
      height: 28,
      backgroundColor: colors.divider,
    },
    usageValue: {
      fontSize: 18,
      fontWeight: shape.isHogwarts ? undefined : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.numeric : undefined,
      fontVariant: ['tabular-nums'],
      color: colors.textPrimary,
    },
    usageLabel: {
      fontSize: 11.5,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textTertiary,
    },
    providerCard: {
      padding: spacing.md,
      backgroundColor: colors.surface,
      borderWidth: shape.isSoft || shape.isHogwarts ? shape.borderWidth.card : borderWidth.thin,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.cardBorder : colors.stroke,
      borderRadius: shape.radius.card,
      ...(shape.isSoft || shape.isHogwarts ? shape.elevation.card : {}),
    },
    providerTopRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.md },
    providerIcon: {
      width: 44,
      height: 44,
      alignItems: 'center',
      justifyContent: 'center',
      backgroundColor: colors.surfaceMuted,
      borderWidth: shape.isSoft ? 0 : shape.borderWidth.subtle,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      borderRadius: shape.isSoft ? 14 : borderRadius.small,
    },
    providerMain: { flex: 1 },
    providerNameRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs + 2, flexWrap: 'wrap' },
    providerName: {
      flexShrink: 1,
      fontSize: 16,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
    },
    providerMeta: {
      marginTop: 3,
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '500' : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textSecondary,
    },
    providerKey: {
      marginTop: 3,
      fontSize: 12,
      color: colors.textTertiary,
      fontFamily: shape.isHogwarts
        ? HOGWARTS_FONTS.numeric
        : shape.isSoft
          ? undefined
          : typography.fontFamily.mono,
      fontVariant: ['tabular-nums'],
    },
    defaultBadge: {
      paddingHorizontal: shape.isSoft ? spacing.sm : spacing.xs + 2,
      paddingVertical: 2,
      backgroundColor: shape.isSoft ? colors.primaryLight : colors.accent,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      borderRadius: shape.isSoft ? shape.radius.pill : borderRadius.small,
    },
    defaultBadgeText: {
      fontSize: 10,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: shape.isSoft ? colors.primaryDark : colors.textPrimary,
    },
    visionBadge: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 3,
      paddingHorizontal: 6,
      paddingVertical: 2,
      backgroundColor: colors.primaryLight,
      borderRadius: shape.radius.pill,
    },
    visionBadgeText: {
      fontSize: 10,
      fontWeight: shape.isHogwarts ? undefined : '600',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.primary,
    },
    testStatusBanner: {
      marginTop: spacing.sm,
      paddingHorizontal: spacing.sm + 2,
      paddingVertical: spacing.xs + 2,
      borderRadius: shape.radius.small,
    },
    testStatusSuccess: {
      backgroundColor: colors.incomeLight,
    },
    testStatusError: {
      backgroundColor: colors.expenseLight,
    },
    testStatusText: {
      fontSize: 12,
      lineHeight: 17,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
    },
    testStatusTextSuccess: {
      color: colors.income,
    },
    testStatusTextError: {
      color: colors.expense,
    },
    providerActions: {
      flexDirection: 'row',
      alignItems: 'center',
      flexWrap: 'wrap',
      gap: spacing.sm,
      marginTop: spacing.md,
    },
    actionButton: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.xs,
      paddingVertical: spacing.sm,
      paddingHorizontal: spacing.md,
      backgroundColor: shape.isSoft ? colors.surfaceMuted : 'transparent',
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: colors.divider,
      borderRadius: shape.isSoft ? 12 : borderRadius.small,
    },
    iconAction: { width: 34, height: 34, alignItems: 'center', justifyContent: 'center' },
    actionText: {
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    emptyState: {
      alignItems: 'center',
      paddingVertical: spacing.xl * 1.5,
      paddingHorizontal: spacing.lg,
      gap: spacing.sm,
    },
    emptyTitle: {
      fontSize: 17,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
    },
    emptyDesc: {
      fontSize: 13,
      lineHeight: 19,
      textAlign: 'center',
      color: colors.textTertiary,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      marginBottom: spacing.sm,
    },
    primaryButton: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'center',
      gap: spacing.xs,
      paddingVertical: spacing.md,
      paddingHorizontal: spacing.lg,
      backgroundColor: colors.primary,
      borderWidth: shape.isSoft ? 0 : borderWidth.medium,
      borderColor: shape.isHogwarts ? colors.accent : colors.stroke,
      borderRadius: shape.radius.button,
      ...(shape.isSoft ? shape.elevation.none : shadow.small),
    },
    primaryButtonText: {
      color: shape.isSoft || shape.isHogwarts ? colors.onPrimary : '#FFFFFF',
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
    },
    secondaryButton: {
      flex: 1,
      alignItems: 'center',
      justifyContent: 'center',
      paddingVertical: spacing.md,
      backgroundColor: shape.isSoft ? colors.surfaceMuted : 'transparent',
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      borderRadius: shape.radius.button,
    },
    secondaryButtonText: {
      color: shape.isSoft ? colors.textSecondary : colors.textPrimary,
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
    },
    modalOverlay: {
      flex: 1,
      justifyContent: 'center',
      padding: spacing.lg,
      backgroundColor: 'rgba(0,0,0,0.45)',
    },
    modalScroll: { flexGrow: 1, justifyContent: 'center' },
    modalCard: {
      padding: spacing.lg,
      backgroundColor: colors.surface,
      borderWidth: shape.isSoft || shape.isHogwarts ? shape.borderWidth.card : borderWidth.medium,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.cardBorder : colors.stroke,
      borderRadius: shape.radius.modal,
      ...(shape.isSoft || shape.isHogwarts ? shape.elevation.floating : shadow.medium),
    },
    modalTitle: {
      fontSize: shape.isSoft ? 18 : 20,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '700' : '900',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.display : undefined,
      color: colors.textPrimary,
      marginBottom: spacing.md,
    },
    inputLabel: {
      marginTop: spacing.sm,
      marginBottom: spacing.xs,
      fontSize: 13,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '800',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: shape.isSoft ? colors.textSecondary : colors.textPrimary,
    },
    input: {
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.md,
      fontSize: 15,
      color: colors.textPrimary,
      backgroundColor: shape.isSoft ? colors.surfaceMuted : colors.background,
      borderWidth: shape.isSoft ? 0 : borderWidth.thin,
      borderColor: shape.isHogwarts ? colors.cardBorder : colors.stroke,
      borderRadius: shape.radius.input,
    },
    providerOptions: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
    providerOption: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 6,
      paddingVertical: spacing.sm,
      paddingHorizontal: spacing.sm + 2,
      backgroundColor: shape.isSoft ? colors.surfaceMuted : 'transparent',
      borderWidth: shape.isSoft ? 1 : borderWidth.thin,
      borderColor: shape.isSoft ? 'transparent' : colors.divider,
      borderRadius: shape.isSoft ? shape.radius.pill : borderRadius.small,
    },
    providerOptionSelected: {
      backgroundColor: colors.primaryLight,
      borderColor: shape.isSoft || shape.isHogwarts ? colors.primary : colors.stroke,
      borderWidth: shape.isSoft ? 1.5 : borderWidth.medium,
    },
    providerOptionText: {
      fontSize: 12,
      fontWeight: shape.isHogwarts ? undefined : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    providerOptionTextSelected: { color: colors.primary },
    modelChipsWrap: {
      flexDirection: 'row',
      flexWrap: 'wrap',
      gap: spacing.xs + 2,
      marginTop: spacing.sm,
    },
    modelChip: {
      paddingHorizontal: spacing.sm + 2,
      paddingVertical: spacing.xs + 2,
      borderRadius: shape.isSoft ? shape.radius.pill : borderRadius.small,
      backgroundColor: colors.surfaceMuted,
      borderWidth: 1,
      borderColor: 'transparent',
    },
    modelChipSelected: {
      backgroundColor: colors.primaryLight,
      borderColor: colors.primary,
    },
    modelChipText: {
      fontSize: 11.5,
      color: colors.textSecondary,
      fontFamily: typography.fontFamily.mono,
    },
    modelChipTextSelected: {
      color: colors.primary,
      fontWeight: '700',
    },
    switchRow: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      marginTop: spacing.md,
      gap: spacing.sm,
    },
    switchLabelWrap: {
      flex: 1,
    },
    switchText: {
      fontSize: 14,
      fontWeight: shape.isHogwarts ? undefined : shape.isSoft ? '600' : '700',
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
      color: colors.textPrimary,
    },
    switchHint: {
      fontSize: 11.5,
      color: colors.textTertiary,
      marginTop: 2,
      fontFamily: shape.isHogwarts ? HOGWARTS_FONTS.body : undefined,
    },
    modalActions: { flexDirection: 'row', gap: spacing.md, marginTop: spacing.lg },
  }),
  _colors: colors,
  _primaryIconColor: shape.isSoft || shape.isHogwarts ? colors.onPrimary : '#FFFFFF',
});

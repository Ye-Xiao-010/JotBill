import React, { useState } from 'react';
import {
  ActivityIndicator,
  Alert,
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
import { Bot, Check, ChevronDown, ChevronLeft, Pencil, Plus, Trash2, Wifi } from 'lucide-react-native';
import { nativeAi, type AiProvider, type AiProviderConfig, type AiProviderInput, type AiProviderUpdateInput } from '../../native';
import { borderRadius, borderWidth, shadow, spacing } from '../../theme/spacing';
import { ThemeColors } from '../../theme/colors';
import { useStyles } from '../../hooks';
import { GLM_MODEL_OPTIONS } from '../../types/ai';

interface ProviderOption {
  value: AiProvider;
  label: string;
  baseUrl: string;
  models: string[];
  supportsVision: boolean;
}

const PROVIDER_OPTIONS: ProviderOption[] = [
  {
    value: 'qwen',
    label: '通义千问',
    baseUrl: 'https://dashscope.aliyuncs.com',
    models: ['qwen-plus', 'qwen-turbo', 'qwen-vl-plus'],
    supportsVision: true,
  },
  {
    value: 'glm',
    label: '智谱 GLM',
    baseUrl: 'https://open.bigmodel.cn',
    models: GLM_MODEL_OPTIONS,
    supportsVision: true,
  },
  {
    value: 'siliconflow',
    label: 'SiliconFlow',
    baseUrl: 'https://api.siliconflow.cn',
    models: ['Qwen/Qwen2.5-7B-Instruct', 'deepseek-ai/DeepSeek-V3'],
    supportsVision: false,
  },
  {
    value: 'custom',
    label: '自定义服务商',
    baseUrl: '',
    models: [],
    supportsVision: false,
  },
];

const PROVIDER_QUERY_KEY = ['ai-providers'] as const;

export default function AiProviderScreen() {
  const navigation = useNavigation();
  const queryClient = useQueryClient();
  const styles = useStyles(createStyles);
  const [showForm, setShowForm] = useState(false);
  const [showModelPicker, setShowModelPicker] = useState(false);
  const [editingProvider, setEditingProvider] = useState<AiProviderConfig | null>(null);
  const [name, setName] = useState('');
  const [provider, setProvider] = useState<AiProvider>('qwen');
  const [model, setModel] = useState(PROVIDER_OPTIONS[0].models[0]);
  const [apiKey, setApiKey] = useState('');
  const [baseUrl, setBaseUrl] = useState(PROVIDER_OPTIONS[0].baseUrl);
  const [supportsVision, setSupportsVision] = useState(true);
  const [isDefault, setIsDefault] = useState(false);

  const providersQuery = useQuery({
    queryKey: PROVIDER_QUERY_KEY,
    queryFn: () => nativeAi.getAiProviders(),
  });

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
    onError: (error: Error) => Alert.alert('保存失败', error.message),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => nativeAi.deleteAiProvider(id),
    onSuccess: refreshProviders,
    onError: (error: Error) => Alert.alert('删除失败', error.message),
  });

  const defaultMutation = useMutation({
    mutationFn: (id: string) => nativeAi.setDefaultAiProvider(id),
    onSuccess: refreshProviders,
    onError: (error: Error) => Alert.alert('设置失败', error.message),
  });

  const testMutation = useMutation({
    mutationFn: (id: string) => nativeAi.testAiProvider(id),
    onSuccess: (result) => Alert.alert(result.success ? '连接成功' : '连接失败', result.message),
    onError: (error: Error) => Alert.alert('测试失败', error.message),
  });

  const selectedProvider = PROVIDER_OPTIONS.find((option) => option.value === provider) ?? PROVIDER_OPTIONS[0];
  const modelOptions = selectedProvider.models.includes(model) || !model
    ? selectedProvider.models
    : [model, ...selectedProvider.models];

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
    setShowModelPicker(false);
    setEditingProvider(null);
  };

  const changeProvider = (value: AiProvider) => {
    const option = PROVIDER_OPTIONS.find((item) => item.value === value) ?? PROVIDER_OPTIONS[0];
    setProvider(value);
    if (!editingProvider || value !== provider) setModel(option.models[0] ?? '');
    setBaseUrl(option.baseUrl);
    setSupportsVision(option.supportsVision);
    setShowModelPicker(false);
  };

  const selectModel = (value: string) => {
    setModel(value);
    setShowModelPicker(false);
  };

  const submitForm = () => {
    if (!name.trim() || !model.trim()) {
      Alert.alert('提示', '请填写名称和模型');
      return;
    }
    if (!editingProvider && !apiKey.trim()) {
      Alert.alert('提示', '请输入 API Key');
      return;
    }
    if (provider === 'custom' && !baseUrl.trim()) {
      Alert.alert('提示', '自定义服务商必须填写 API 地址');
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

  const providers = providersQuery.data ?? [];

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} style={styles.iconButton}>
          <ChevronLeft size={24} color={styles._colors.textPrimary} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>AI 服务商</Text>
        <TouchableOpacity onPress={openCreate} style={styles.iconButton}>
          <Plus size={22} color={styles._colors.textPrimary} strokeWidth={3} />
        </TouchableOpacity>
      </View>

      <ScrollView style={styles.scrollView} contentContainerStyle={styles.content}>
        <View style={styles.notice}>
          <Bot size={22} color={styles._colors.primary} />
          <Text style={styles.noticeText}>配置保存在本机，API Key 由 Android Keystore 加密。</Text>
        </View>
        {providers.length === 0 ? (
          <View style={styles.emptyState}>
            <Bot size={42} color={styles._colors.textTertiary} />
            <Text style={styles.emptyTitle}>还没有 AI 服务商</Text>
            <TouchableOpacity style={styles.primaryButton} onPress={openCreate}>
              <Plus size={18} color="#FFFFFF" />
              <Text style={styles.primaryButtonText}>添加服务商</Text>
            </TouchableOpacity>
          </View>
        ) : (
          providers.map((config) => (
            <View key={config.id} style={styles.providerCard}>
              <View style={styles.providerTopRow}>
                <View style={styles.providerIcon}>
                  <Bot size={22} color={styles._colors.primary} />
                </View>
                <View style={styles.providerMain}>
                  <View style={styles.providerNameRow}>
                    <Text style={styles.providerName} numberOfLines={1}>{config.name}</Text>
                    {config.isDefault && <View style={styles.defaultBadge}><Text style={styles.defaultBadgeText}>默认</Text></View>}
                  </View>
                  <Text style={styles.providerMeta} numberOfLines={1}>{config.provider} · {config.model}</Text>
                  <Text style={styles.providerKey}>API Key: {config.apiKey ?? '未配置'}</Text>
                </View>
              </View>
              <View style={styles.providerActions}>
                <TouchableOpacity style={styles.actionButton} onPress={() => testMutation.mutate(config.id)}>
                  {testMutation.isPending && testMutation.variables === config.id ? <ActivityIndicator size="small" color={styles._colors.primary} /> : <Wifi size={16} color={styles._colors.primary} />}
                  <Text style={styles.actionText}>测试</Text>
                </TouchableOpacity>
                {!config.isDefault && (
                  <TouchableOpacity style={styles.actionButton} onPress={() => defaultMutation.mutate(config.id)}>
                    <Check size={16} color={styles._colors.success} />
                    <Text style={styles.actionText}>设为默认</Text>
                  </TouchableOpacity>
                )}
                <TouchableOpacity style={styles.actionButton} onPress={() => openEdit(config)}>
                  <Pencil size={16} color={styles._colors.textPrimary} />
                  <Text style={styles.actionText}>编辑</Text>
                </TouchableOpacity>
                <TouchableOpacity
                  style={styles.iconAction}
                  onPress={() => Alert.alert('删除服务商', `确认删除「${config.name}」？`, [
                    { text: '取消', style: 'cancel' },
                    { text: '删除', style: 'destructive', onPress: () => deleteMutation.mutate(config.id) },
                  ])}
                >
                  <Trash2 size={17} color={styles._colors.error} />
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
              <Text style={styles.modalTitle}>{editingProvider ? '编辑服务商' : '添加服务商'}</Text>
              <Text style={styles.inputLabel}>名称</Text>
              <TextInput style={styles.input} value={name} onChangeText={setName} placeholder="例如：我的千问" placeholderTextColor={styles._colors.textTertiary} />
              <Text style={styles.inputLabel}>服务商</Text>
              <View style={styles.providerOptions}>
                {PROVIDER_OPTIONS.map((option) => (
                  <TouchableOpacity key={option.value} style={[styles.providerOption, provider === option.value && styles.providerOptionSelected]} onPress={() => changeProvider(option.value)}>
                    <Text style={[styles.providerOptionText, provider === option.value && styles.providerOptionTextSelected]}>{option.label}</Text>
                  </TouchableOpacity>
                ))}
              </View>
              <Text style={styles.inputLabel}>API Key</Text>
              <TextInput
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
              <TextInput style={styles.input} value={baseUrl} onChangeText={setBaseUrl} placeholder="https://" placeholderTextColor={styles._colors.textTertiary} autoCapitalize="none" autoCorrect={false} />
              <Text style={styles.inputLabel}>模型</Text>
              {selectedProvider.models.length > 0 ? (
                <TouchableOpacity style={styles.modelPicker} onPress={() => setShowModelPicker(true)}>
                  <Text style={styles.modelPickerText}>{model || '请选择模型'}</Text>
                  <ChevronDown size={18} color={styles._colors.textSecondary} />
                </TouchableOpacity>
              ) : (
                <TextInput style={styles.input} value={model} onChangeText={setModel} placeholder="请输入自定义模型名称" placeholderTextColor={styles._colors.textTertiary} autoCapitalize="none" autoCorrect={false} />
              )}
              <View style={styles.switchRow}>
                <Text style={styles.switchText}>支持视觉识别</Text>
                <Switch value={supportsVision} onValueChange={setSupportsVision} trackColor={{ false: styles._colors.divider, true: styles._colors.primary }} disabled={!selectedProvider.supportsVision && provider !== 'custom'} />
              </View>
              <View style={styles.switchRow}>
                <Text style={styles.switchText}>设为默认</Text>
                <Switch value={isDefault} onValueChange={setIsDefault} trackColor={{ false: styles._colors.divider, true: styles._colors.primary }} />
              </View>
              <View style={styles.modalActions}>
                <TouchableOpacity style={styles.secondaryButton} onPress={closeForm}><Text style={styles.secondaryButtonText}>取消</Text></TouchableOpacity>
                <TouchableOpacity style={styles.primaryButton} onPress={submitForm} disabled={saveMutation.isPending}><Text style={styles.primaryButtonText}>{saveMutation.isPending ? '保存中' : '保存'}</Text></TouchableOpacity>
              </View>
            </View>
          </ScrollView>
        </View>
      </Modal>

      <Modal visible={showModelPicker} transparent animationType="fade" onRequestClose={() => setShowModelPicker(false)}>
        <TouchableOpacity style={styles.modelPickerOverlay} activeOpacity={1} onPress={() => setShowModelPicker(false)}>
          <View style={styles.modelPickerList}>
            <Text style={styles.modelPickerTitle}>选择模型</Text>
            {modelOptions.map((option) => (
              <TouchableOpacity key={option} style={[styles.modelPickerItem, model === option && styles.modelPickerItemSelected]} onPress={() => selectModel(option)}>
                <Text style={[styles.modelPickerItemText, model === option && styles.modelPickerItemTextSelected]}>{option}</Text>
              </TouchableOpacity>
            ))}
          </View>
        </TouchableOpacity>
      </Modal>
    </View>
  );
}

const createStyles = (colors: ThemeColors) => ({
  ...StyleSheet.create({
    container: { flex: 1, backgroundColor: colors.background },
    header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: spacing.md, paddingTop: spacing.xl, paddingBottom: spacing.md, backgroundColor: colors.surface, borderBottomWidth: borderWidth.medium, borderBottomColor: colors.stroke },
    iconButton: { width: 40, height: 40, alignItems: 'center', justifyContent: 'center' },
    headerTitle: { fontSize: 20, fontWeight: '800', color: colors.textPrimary },
    scrollView: { flex: 1 },
    content: { padding: spacing.lg, gap: spacing.md, paddingBottom: spacing.xl },
    notice: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, padding: spacing.md, backgroundColor: colors.primaryLight, borderWidth: borderWidth.thin, borderColor: colors.stroke, borderRadius: borderRadius.card },
    noticeText: { flex: 1, fontSize: 13, fontWeight: '700', color: colors.textPrimary },
    providerCard: { padding: spacing.md, backgroundColor: colors.surface, borderWidth: borderWidth.thin, borderColor: colors.stroke, borderRadius: borderRadius.card },
    providerTopRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.md },
    providerIcon: { width: 44, height: 44, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.primaryLight, borderWidth: borderWidth.thin, borderColor: colors.stroke, borderRadius: borderRadius.small },
    providerMain: { flex: 1 },
    providerNameRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
    providerName: { flexShrink: 1, fontSize: 16, fontWeight: '800', color: colors.textPrimary },
    providerMeta: { marginTop: 3, fontSize: 12, fontWeight: '600', color: colors.textSecondary },
    providerKey: { marginTop: 3, fontSize: 12, color: colors.textTertiary, fontFamily: 'Courier' },
    defaultBadge: { paddingHorizontal: spacing.xs, paddingVertical: 2, backgroundColor: colors.accent, borderWidth: borderWidth.thin, borderColor: colors.stroke, borderRadius: borderRadius.small },
    defaultBadgeText: { fontSize: 10, fontWeight: '800', color: colors.textPrimary },
    providerActions: { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: spacing.sm, marginTop: spacing.md },
    actionButton: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs, paddingVertical: spacing.sm, paddingHorizontal: spacing.sm, borderWidth: borderWidth.thin, borderColor: colors.divider, borderRadius: borderRadius.small },
    iconAction: { width: 34, height: 34, alignItems: 'center', justifyContent: 'center' },
    actionText: { fontSize: 12, fontWeight: '700', color: colors.textPrimary },
    emptyState: { alignItems: 'center', paddingVertical: spacing.xl * 2, gap: spacing.md },
    emptyTitle: { fontSize: 18, fontWeight: '800', color: colors.textPrimary },
    primaryButton: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: spacing.xs, paddingVertical: spacing.md, paddingHorizontal: spacing.lg, backgroundColor: colors.primary, borderWidth: borderWidth.medium, borderColor: colors.stroke, borderRadius: borderRadius.button, ...shadow.small },
    primaryButtonText: { color: '#FFFFFF', fontSize: 14, fontWeight: '800' },
    secondaryButton: { flex: 1, alignItems: 'center', justifyContent: 'center', paddingVertical: spacing.md, borderWidth: borderWidth.thin, borderColor: colors.stroke, borderRadius: borderRadius.button },
    secondaryButtonText: { color: colors.textPrimary, fontSize: 14, fontWeight: '800' },
    modalOverlay: { flex: 1, justifyContent: 'center', padding: spacing.lg, backgroundColor: 'rgba(0,0,0,0.45)' },
    modalScroll: { flexGrow: 1, justifyContent: 'center' },
    modalCard: { padding: spacing.lg, backgroundColor: colors.surface, borderWidth: borderWidth.medium, borderColor: colors.stroke, borderRadius: borderRadius.card, ...shadow.medium },
    modalTitle: { fontSize: 20, fontWeight: '900', color: colors.textPrimary, marginBottom: spacing.md },
    inputLabel: { marginTop: spacing.sm, marginBottom: spacing.xs, fontSize: 13, fontWeight: '800', color: colors.textPrimary },
    input: { paddingHorizontal: spacing.md, paddingVertical: spacing.md, fontSize: 15, color: colors.textPrimary, backgroundColor: colors.background, borderWidth: borderWidth.thin, borderColor: colors.stroke, borderRadius: borderRadius.small },
    modelPicker: { minHeight: 52, paddingHorizontal: spacing.md, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', backgroundColor: colors.background, borderWidth: borderWidth.thin, borderColor: colors.stroke, borderRadius: borderRadius.small },
    modelPickerText: { flex: 1, fontSize: 15, color: colors.textPrimary },
    modelPickerOverlay: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: spacing.lg, backgroundColor: 'rgba(0,0,0,0.45)' },
    modelPickerList: { width: '100%', maxWidth: 420, padding: spacing.sm, backgroundColor: colors.surface, borderWidth: borderWidth.medium, borderColor: colors.stroke, borderRadius: borderRadius.card, ...shadow.medium },
    modelPickerTitle: { paddingHorizontal: spacing.sm, paddingVertical: spacing.sm, fontSize: 16, fontWeight: '800', color: colors.textPrimary },
    modelPickerItem: { paddingHorizontal: spacing.md, paddingVertical: spacing.md, borderRadius: borderRadius.small },
    modelPickerItemSelected: { backgroundColor: colors.accent, borderWidth: borderWidth.thin, borderColor: colors.stroke },
    modelPickerItemText: { fontSize: 15, fontWeight: '600', color: colors.textPrimary },
    modelPickerItemTextSelected: { fontWeight: '800', color: colors.primary },
    providerOptions: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
    providerOption: { paddingVertical: spacing.sm, paddingHorizontal: spacing.sm, borderWidth: borderWidth.thin, borderColor: colors.divider, borderRadius: borderRadius.small },
    providerOptionSelected: { backgroundColor: colors.primaryLight, borderColor: colors.stroke, borderWidth: borderWidth.medium },
    providerOptionText: { fontSize: 12, fontWeight: '700', color: colors.textPrimary },
    providerOptionTextSelected: { color: colors.primary },
    switchRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginTop: spacing.md },
    switchText: { fontSize: 14, fontWeight: '700', color: colors.textPrimary },
    modalActions: { flexDirection: 'row', gap: spacing.md, marginTop: spacing.lg },
  }),
  _colors: colors,
});

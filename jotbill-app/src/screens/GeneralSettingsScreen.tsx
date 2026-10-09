/**
 * 通用配置屏幕 - Neo-Brutalism 风格
 * 描边配置卡片 + 粗标签 + 糖果色强调
 */
import React, { useState, useEffect } from 'react';
import {
  View,
  Text,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  TextInput,
  Modal,
  FlatList,
  ActivityIndicator,
  Switch,
} from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { ThemeColors } from '../theme/colors';
import { spacing, borderRadius, borderWidth, shadow } from '../theme';
import { useStyles } from '../hooks';
import { useAlert } from '../providers';
import { aiService } from '../services/ai';
import { nativePendingReminder } from '../native/pendingReminder';
import { nativeBudgetReminder } from '../native/budgetReminder';
import type {
  AIModelConfig,
  AIProvider,
  CreateAIConfigDto,
  UpdateAIConfigDto,
} from '../types/ai';
import { AI_PROVIDERS } from '../types/ai';
import { ProviderIcon } from '../components/icons';
import { ChevronDown } from 'lucide-react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

const NATIVE_AI_PROVIDERS: AIProvider[] = ['qwen', 'glm', 'siliconflow', 'custom'];

interface GeneralSettingsScreenProps {
  navigation?: any;
}

interface MonitoredApp {
  id: string;
  packageName: string;
  appName: string;
  enabled: boolean;
}

/**
 * 通用配置持久化载体。
 *
 * `monitoredApps` / `filterKeywords` 只服务于"监听哪几个 App 的支付通知、
 * 用哪些关键词过滤"，也就是已随精简版删除的**支付通知自动记账**。
 */
interface AppConfig {
  monitoredApps?: MonitoredApp[];
  filterKeywords?: string[];
}

// AI 模型配置编辑弹窗
interface AIConfigModalProps {
  visible: boolean;
  config: AIModelConfig | null;
  onClose: () => void;
  onSave: (data: CreateAIConfigDto | UpdateAIConfigDto) => void;
  isSaving: boolean;
  colors: ThemeColors;
}

function AIConfigModal({
  visible,
  config,
  onClose,
  onSave,
  isSaving,
  colors,
}: AIConfigModalProps) {
  const { alert: showConfigAlert } = useAlert();
  const [name, setName] = useState('');
  const [provider, setProvider] = useState<AIProvider>('qwen');
  const [apiKey, setApiKey] = useState('');
  const [apiBaseUrl, setApiBaseUrl] = useState('');
  const [model, setModel] = useState('');
  const [supportsVision, setSupportsVision] = useState(true);
  const [isDefault, setIsDefault] = useState(false);
  const [showProviderPicker, setShowProviderPicker] = useState(false);
  const [showModelPicker, setShowModelPicker] = useState(false);

  useEffect(() => {
    if (config) {
      setName(config.name);
      setProvider(config.provider);
      setApiKey(config.apiKey || '');
      setApiBaseUrl(config.apiBaseUrl || '');
      setModel(config.model);
      setSupportsVision(config.supportsVision);
      setIsDefault(config.isDefault);
    } else {
      setName('');
      setProvider('qwen');
      setApiKey('');
      setApiBaseUrl('');
      setModel(AI_PROVIDERS.qwen.models[0]);
      setSupportsVision(true);
      setIsDefault(false);
    }
    setShowProviderPicker(false);
    setShowModelPicker(false);
  }, [config, visible]);

  const handleProviderChange = (newProvider: AIProvider) => {
    setProvider(newProvider);
    if (!config || newProvider !== provider) {
      setModel(AI_PROVIDERS[newProvider].models[0] ?? '');
    }
    setSupportsVision(AI_PROVIDERS[newProvider].supportsVision);
    setShowProviderPicker(false);
    setShowModelPicker(false);
  };

  const handleModelChange = (newModel: string) => {
    setModel(newModel);
    setShowModelPicker(false);
  };

  const handleSave = () => {
    if (!name.trim()) {
      showConfigAlert('提示', '请输入配置名称');
      return;
    }
    if (!apiKey.trim()) {
      showConfigAlert('提示', '请输入 API Key');
      return;
    }
    if (!model.trim()) {
      showConfigAlert('提示', '请输入模型名称');
      return;
    }

    const data: CreateAIConfigDto | UpdateAIConfigDto = {
      name: name.trim(),
      provider,
      apiKey: apiKey.trim(),
      model: model.trim(),
      supportsVision,
      isDefault,
    };

    if (apiBaseUrl.trim()) {
      data.apiBaseUrl = apiBaseUrl.trim();
    }

    onSave(data);
  };

  const modelOptions = AI_PROVIDERS[provider].models.includes(model) || !model
    ? AI_PROVIDERS[provider].models
    : [model, ...AI_PROVIDERS[provider].models];
  const modalStyles = createModalStyles(colors);

  return (
    <Modal visible={visible} animationType="slide" transparent onRequestClose={onClose}>
      <View style={modalStyles.overlay}>
        <View style={modalStyles.content}>
          <View style={modalStyles.header}>
            <Text style={modalStyles.title}>{config ? '编辑模型配置' : '添加模型配置'}</Text>
            <TouchableOpacity onPress={onClose} style={modalStyles.closeButton}>
              <Text style={modalStyles.closeIcon}>×</Text>
            </TouchableOpacity>
          </View>

          <ScrollView style={modalStyles.body} showsVerticalScrollIndicator={false}>
            {/* 名称 */}
            <View style={modalStyles.field}>
              <Text style={modalStyles.label}>名称</Text>
              <TextInput
                style={modalStyles.input}
                value={name}
                onChangeText={setName}
                placeholder="如: 我的 Claude"
                placeholderTextColor={colors.textTertiary}
              />
            </View>

            {/* 服务商 */}
            <View style={modalStyles.field}>
              <Text style={modalStyles.label}>服务商</Text>
              <TouchableOpacity
                style={modalStyles.picker}
                onPress={() => setShowProviderPicker(true)}
              >
                <View style={modalStyles.pickerContent}>
                  <ProviderIcon provider={provider} size={18} />
                  <Text style={modalStyles.pickerText}>{AI_PROVIDERS[provider].name}</Text>
                </View>
                <Text style={modalStyles.pickerArrow}>▼</Text>
              </TouchableOpacity>
            </View>

            {/* API Key */}
            <View style={modalStyles.field}>
              <Text style={modalStyles.label}>API Key</Text>
              <TextInput
                style={modalStyles.input}
                value={apiKey}
                onChangeText={setApiKey}
                placeholder="请输入 API Key"
                placeholderTextColor={colors.textTertiary}
                autoCapitalize="none"
                autoCorrect={false}
              />
            </View>

            {/* API 地址 */}
            <View style={modalStyles.field}>
              <Text style={modalStyles.label}>API 地址（可选）</Text>
              <TextInput
                style={modalStyles.input}
                value={apiBaseUrl}
                onChangeText={setApiBaseUrl}
                placeholder={`默认: ${AI_PROVIDERS[provider].defaultUrl}`}
                placeholderTextColor={colors.textTertiary}
              />
            </View>

            {/* 模型 */}
            <View style={modalStyles.field}>
              <Text style={modalStyles.label}>模型</Text>
              {AI_PROVIDERS[provider].models.length > 0 ? (
                <TouchableOpacity
                  style={modalStyles.picker}
                  onPress={() => setShowModelPicker(true)}
                >
                  <View style={modalStyles.pickerContent}>
                    <Text style={modalStyles.pickerText}>{model || '请选择模型'}</Text>
                  </View>
                  <ChevronDown size={18} color={colors.textSecondary} />
                </TouchableOpacity>
              ) : (
                <TextInput
                  style={modalStyles.input}
                  value={model}
                  onChangeText={setModel}
                  placeholder="请输入自定义模型名称"
                  placeholderTextColor={colors.textTertiary}
                  autoCapitalize="none"
                  autoCorrect={false}
                />
              )}
            </View>

            {/* 支持图片 */}
            <View style={modalStyles.switchRow}>
              <View style={modalStyles.switchLeft}>
                <Text style={modalStyles.switchLabel}>支持图片识别</Text>
                <Text style={modalStyles.switchHint}>
                  {AI_PROVIDERS[provider].supportsVision
                    ? '此服务商支持图片'
                    : '此服务商不支持图片'}
                </Text>
              </View>
              <Switch
                value={supportsVision}
                onValueChange={setSupportsVision}
                trackColor={{ false: colors.divider, true: colors.primary }}
                thumbColor="#FFFFFF"
                disabled={!AI_PROVIDERS[provider].supportsVision}
              />
            </View>

            {/* 设为默认 */}
            <View style={modalStyles.switchRow}>
              <View style={modalStyles.switchLeft}>
                <Text style={modalStyles.switchLabel}>设为默认</Text>
                <Text style={modalStyles.switchHint}>AI 助手将优先使用此配置</Text>
              </View>
              <Switch
                value={isDefault}
                onValueChange={setIsDefault}
                trackColor={{ false: colors.divider, true: colors.primary }}
                thumbColor="#FFFFFF"
              />
            </View>
          </ScrollView>

          <View style={modalStyles.footer}>
            <TouchableOpacity
              style={[modalStyles.button, modalStyles.cancelButton]}
              onPress={onClose}
            >
              <Text style={modalStyles.cancelButtonText}>取消</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={[modalStyles.button, modalStyles.saveButton]}
              onPress={handleSave}
              disabled={isSaving}
            >
              {isSaving ? (
                <ActivityIndicator size="small" color="#FFFFFF" />
              ) : (
                <Text style={modalStyles.saveButtonText}>保存</Text>
              )}
            </TouchableOpacity>
          </View>
        </View>

        {/* 服务商选择器 */}
        <Modal
          visible={showProviderPicker}
          transparent
          animationType="fade"
          onRequestClose={() => setShowProviderPicker(false)}
        >
          <TouchableOpacity
            style={modalStyles.pickerOverlay}
            activeOpacity={1}
            onPress={() => setShowProviderPicker(false)}
          >
            <View style={modalStyles.pickerList}>
              {NATIVE_AI_PROVIDERS.map((p) => (
                <TouchableOpacity
                  key={p}
                  style={[modalStyles.pickerItem, provider === p && modalStyles.pickerItemActive]}
                  onPress={() => handleProviderChange(p)}
                >
                  <View style={modalStyles.pickerItemRow}>
                    <ProviderIcon provider={p} size={18} />
                    <Text
                      style={[
                        modalStyles.pickerItemText,
                        provider === p && modalStyles.pickerItemTextActive,
                      ]}
                    >
                      {AI_PROVIDERS[p].name}
                    </Text>
                  </View>
                </TouchableOpacity>
              ))}
            </View>
          </TouchableOpacity>
        </Modal>

        {/* 模型选择器 */}
        <Modal
          visible={showModelPicker}
          transparent
          animationType="fade"
          onRequestClose={() => setShowModelPicker(false)}
        >
          <TouchableOpacity
            style={modalStyles.pickerOverlay}
            activeOpacity={1}
            onPress={() => setShowModelPicker(false)}
          >
            <View style={modalStyles.pickerList}>
              <Text style={modalStyles.pickerListTitle}>选择模型</Text>
              {modelOptions.map((option) => (
                <TouchableOpacity
                  key={option}
                  style={[modalStyles.pickerItem, model === option && modalStyles.pickerItemActive]}
                  onPress={() => handleModelChange(option)}
                >
                  <Text
                    style={[
                      modalStyles.pickerItemText,
                      model === option && modalStyles.pickerItemTextActive,
                    ]}
                  >
                    {option}
                  </Text>
                </TouchableOpacity>
              ))}
            </View>
          </TouchableOpacity>
        </Modal>
      </View>
    </Modal>
  );
}

const createModalStyles = (colors: ThemeColors) =>
  StyleSheet.create({
    overlay: {
      flex: 1,
      backgroundColor: colors.overlay,
      justifyContent: 'flex-end',
    },
    content: {
      backgroundColor: colors.surface,
      borderTopLeftRadius: borderRadius.card,
      borderTopRightRadius: borderRadius.card,
      borderTopWidth: borderWidth.thick,
      borderLeftWidth: borderWidth.thick,
      borderRightWidth: borderWidth.thick,
      borderColor: colors.stroke,
      maxHeight: '90%',
    },
    header: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      paddingHorizontal: spacing.lg,
      paddingVertical: spacing.md,
      borderBottomWidth: borderWidth.thin,
      borderBottomColor: colors.stroke,
    },
    title: {
      fontSize: 18,
      fontWeight: '800',
      color: colors.textPrimary,
    },
    closeButton: {
      width: 36,
      height: 36,
      alignItems: 'center',
      justifyContent: 'center',
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: colors.background,
    },
    closeIcon: {
      fontSize: 22,
      fontWeight: '800',
      color: colors.textPrimary,
    },
    body: {
      paddingHorizontal: spacing.lg,
      paddingVertical: spacing.md,
    },
    field: {
      marginBottom: spacing.md,
    },
    label: {
      fontSize: 14,
      fontWeight: '700',
      color: colors.textPrimary,
      marginBottom: spacing.xs,
    },
    input: {
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      borderRadius: borderRadius.input,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.sm,
      fontSize: 15,
      fontWeight: '600',
      color: colors.textPrimary,
      backgroundColor: colors.background,
    },
    picker: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      borderRadius: borderRadius.input,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.sm + 2,
      backgroundColor: colors.background,
    },
    pickerContent: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
    },
    pickerText: {
      fontSize: 15,
      fontWeight: '600',
      color: colors.textPrimary,
    },
    pickerArrow: {
      fontSize: 12,
      fontWeight: '800',
      color: colors.textSecondary,
    },
    pickerListTitle: {
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.sm,
      fontSize: 16,
      fontWeight: '800',
      color: colors.textPrimary,
    },
    switchRow: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      paddingVertical: spacing.sm,
      marginBottom: spacing.sm,
    },
    switchLeft: {
      flex: 1,
      marginRight: spacing.md,
    },
    switchLabel: {
      fontSize: 15,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    switchHint: {
      fontSize: 12,
      fontWeight: '600',
      color: colors.textSecondary,
      marginTop: 2,
    },
    footer: {
      flexDirection: 'row',
      gap: spacing.md,
      paddingHorizontal: spacing.lg,
      paddingVertical: spacing.md,
      borderTopWidth: borderWidth.thin,
      borderTopColor: colors.stroke,
    },
    button: {
      flex: 1,
      paddingVertical: spacing.md,
      borderRadius: borderRadius.button,
      alignItems: 'center',
      justifyContent: 'center',
      minHeight: 48,
    },
    cancelButton: {
      backgroundColor: colors.surface,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
    },
    cancelButtonText: {
      fontSize: 15,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    saveButton: {
      backgroundColor: colors.primary,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      ...shadow.small,
    },
    saveButtonText: {
      fontSize: 15,
      fontWeight: '800',
      color: '#FFFFFF',
    },
    pickerOverlay: {
      flex: 1,
      backgroundColor: colors.overlay,
      justifyContent: 'center',
      alignItems: 'center',
    },
    pickerList: {
      backgroundColor: colors.surface,
      borderRadius: borderRadius.card,
      borderWidth: borderWidth.medium,
      borderColor: colors.stroke,
      padding: spacing.sm,
      minWidth: 200,
      ...shadow.medium,
    },
    pickerItem: {
      paddingVertical: spacing.sm + 2,
      paddingHorizontal: spacing.md,
      borderRadius: borderRadius.small,
    },
    pickerItemActive: {
      backgroundColor: colors.accent,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
    },
    pickerItemRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
    },
    pickerItemText: {
      fontSize: 15,
      fontWeight: '600',
      color: colors.textPrimary,
    },
    pickerItemTextActive: {
      color: colors.textPrimary,
      fontWeight: '800',
    },
  });

export default function GeneralSettingsScreen({ navigation: _navigation }: GeneralSettingsScreenProps) {
  const styles = useStyles(createStyles);
  const { alert, confirm } = useAlert();

  // 基础配置
  const [config, setConfig] = useState<AppConfig>({
    monitoredApps: [
      { id: '1', packageName: 'com.tencent.mm', appName: '微信', enabled: true },
      { id: '2', packageName: 'com.eg.android.AlipayGphone', appName: '支付宝', enabled: true },
    ],
    filterKeywords: ['支付', '付款', '消费', '扣款', '支出', '交易提醒', '花呗', '余额宝', '已支付'],
  });

  // AI 模型配置
  const [aiConfigs, setAiConfigs] = useState<AIModelConfig[]>([]);
  const [loadingAiConfigs, setLoadingAiConfigs] = useState(false);
  const [showAiConfigModal, setShowAiConfigModal] = useState(false);
  const [editingAiConfig, setEditingAiConfig] = useState<AIModelConfig | null>(null);
  const [savingAiConfig, setSavingAiConfig] = useState(false);
  const [testingConfigId, setTestingConfigId] = useState<string | null>(null);

  // 其他状态

  // 待确认提醒开关(仅控制系统通知;数据落库与 JS 事件不受影响,默认开启)
  const [pendingReminderEnabled, setPendingReminderEnabled] = useState(true);
  // 预算超支提醒开关(仅控制系统通知;进度计算与 UI 变色不受影响,默认开启)
  const [budgetReminderEnabled, setBudgetReminderEnabled] = useState(true);

  // 结算页截图识别链路开关(精简版;默认开启以保持与完整版一致的行为)
  const [checkoutCaptureEnabled, setCheckoutCaptureEnabled] = useState(true);
  // 写盘失败时需要把开关拨回真实值,否则页面显示与实际行为会长期不一致
  const [checkoutCaptureBusy, setCheckoutCaptureBusy] = useState(false);

  // 加载配置
  useEffect(() => {
    loadConfig();
    loadAiConfigs();
  }, []);

  const loadConfig = async () => {
    try {
      const savedConfig = await AsyncStorage.getItem('appGeneralConfig');
      if (savedConfig) {
        setConfig(JSON.parse(savedConfig));
      }
    } catch (error) {
      console.error('加载配置失败:', error);
    }
  };

  const loadAiConfigs = async () => {
    setLoadingAiConfigs(true);
    try {
      const response = await aiService.getConfigs();
      if (response.success && response.data) {
        setAiConfigs(response.data);
      }
    } catch (error) {
      console.error('加载 AI 配置失败:', error);
    } finally {
      setLoadingAiConfigs(false);
    }
  };

  // 加载提醒开关初始状态
  useEffect(() => {
    nativePendingReminder
      .isReminderEnabled()
      .then(setPendingReminderEnabled)
      .catch(() => undefined);
    nativeBudgetReminder
      .isReminderEnabled()
      .then(setBudgetReminderEnabled)
      .catch(() => undefined);
  }, []);

  const handleTogglePendingReminder = async (enabled: boolean) => {
    setPendingReminderEnabled(enabled);
    try {
      if (enabled) {
        await nativePendingReminder.requestNotificationPermissionIfNeeded();
      }
      await nativePendingReminder.setReminderEnabled(enabled);
    } catch (error) {
      console.error('切换待确认提醒失败:', error);
    }
  };

  const handleToggleBudgetReminder = async (enabled: boolean) => {
    setBudgetReminderEnabled(enabled);
    try {
      await nativeBudgetReminder.setReminderEnabled(enabled);
    } catch (error) {
      console.error('切换预算提醒失败:', error);
    }
  };

  const handleSaveConfig = async () => {
    try {
      await AsyncStorage.setItem('appGeneralConfig', JSON.stringify(config));
      console.log('配置已保存');
    } catch (error) {
      console.error('保存配置失败:', error);
    }
  };

  // AI 配置相关操作
  const handleAddAiConfig = () => {
    setEditingAiConfig(null);
    setShowAiConfigModal(true);
  };

  const handleEditAiConfig = async (cfg: AIModelConfig) => {
    try {
      const response = await aiService.getConfig(cfg.id);
      if (response.success && response.data) {
        setEditingAiConfig(response.data);
      } else {
        setEditingAiConfig(cfg);
      }
    } catch {
      setEditingAiConfig(cfg);
    }
    setShowAiConfigModal(true);
  };

  const handleDeleteAiConfig = (cfg: AIModelConfig) => {
    confirm(
      '确认删除',
      `确定要删除配置 "${cfg.name}" 吗？`,
      async () => {
        try {
          await aiService.deleteConfig(cfg.id);
          loadAiConfigs();
        } catch (error) {
          alert('错误', '删除失败');
        }
      },
      undefined,
      { confirmText: '删除', destructive: true },
    );
  };

  const handleSaveAiConfig = async (data: CreateAIConfigDto | UpdateAIConfigDto) => {
    setSavingAiConfig(true);
    try {
      if (editingAiConfig) {
        await aiService.updateConfig(editingAiConfig.id, data);
      } else {
        await aiService.createConfig(data as CreateAIConfigDto);
      }
      setShowAiConfigModal(false);
      loadAiConfigs();
    } catch (error: any) {
      alert('错误', error.message || '保存失败');
    } finally {
      setSavingAiConfig(false);
    }
  };

  const handleTestAiConfig = async (cfg: AIModelConfig) => {
    setTestingConfigId(cfg.id);
    try {
      const response = await aiService.testConfig(cfg.id);
      if (response.success && response.data) {
        alert(
          response.data.success ? '连接成功' : '连接失败',
          response.data.message
        );
      }
    } catch (error: any) {
      alert('测试失败', error.message || '无法连接');
    } finally {
      setTestingConfigId(null);
    }
  };

  const handleSetDefaultAiConfig = async (cfg: AIModelConfig) => {
    try {
      await aiService.setDefaultConfig(cfg.id);
      loadAiConfigs();
    } catch (error) {
      alert('错误', '设置默认配置失败');
    }
  };

  return (
    <SafeAreaView style={styles.container} edges={['top', 'bottom']}>
      <ScrollView style={styles.scrollView} showsVerticalScrollIndicator={false}>
        {/* 待确认提醒 */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>🔔 待确认提醒</Text>
          <View style={styles.appItem}>
            <View style={styles.appInfo}>
              <Text style={styles.appName}>系统通知提醒</Text>
              <Text style={styles.appPackage}>有新的待确认交易时发送系统通知</Text>
            </View>
            <Switch
              value={pendingReminderEnabled}
              onValueChange={handleTogglePendingReminder}
              trackColor={{ false: styles._colors.divider, true: styles._colors.primary }}
              thumbColor="#FFFFFF"
            />
          </View>
        </View>

        {/* 预算超支提醒 */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>💰 预算提醒</Text>
          <View style={styles.appItem}>
            <View style={styles.appInfo}>
              <Text style={styles.appName}>系统通知提醒</Text>
              <Text style={styles.appPackage}>预算使用达到预警阈值(如 80%/100%)时发送系统通知</Text>
            </View>
            <Switch
              value={budgetReminderEnabled}
              onValueChange={handleToggleBudgetReminder}
              trackColor={{ false: styles._colors.divider, true: styles._colors.primary }}
              thumbColor="#FFFFFF"
            />
          </View>
        </View>

        {/* AI 模型配置 */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>🤖 AI 模型配置</Text>

          {loadingAiConfigs ? (
            <ActivityIndicator size="small" color={styles._colors.primary} />
          ) : aiConfigs.length === 0 ? (
            <Text style={styles.emptyText}>暂无配置，点击下方按钮添加</Text>
          ) : (
            aiConfigs.map((cfg) => (
              <View key={cfg.id} style={styles.aiConfigItem}>
                <View style={styles.aiConfigHeader}>
                  <View style={styles.aiConfigInfo}>
                    <View style={styles.aiConfigNameRow}>
                      <ProviderIcon provider={cfg.provider} size={18} />
                      <Text style={styles.aiConfigName}>
                        {cfg.name}
                        {cfg.isDefault && (
                          <Text style={styles.defaultBadge}> 默认</Text>
                        )}
                      </Text>
                    </View>
                    <Text style={styles.aiConfigModel}>{cfg.model}</Text>
                  </View>
                </View>
                <View style={styles.aiConfigActions}>
                  {!cfg.isDefault && (
                    <TouchableOpacity
                      style={styles.aiConfigActionBtn}
                      onPress={() => handleSetDefaultAiConfig(cfg)}
                    >
                      <Text style={[styles.aiConfigActionText, { color: styles._colors.primary }]}>
                        设为默认
                      </Text>
                    </TouchableOpacity>
                  )}
                  <TouchableOpacity
                    style={styles.aiConfigActionBtn}
                    onPress={() => handleTestAiConfig(cfg)}
                    disabled={testingConfigId === cfg.id}
                  >
                    {testingConfigId === cfg.id ? (
                      <ActivityIndicator size="small" color={styles._colors.primary} />
                    ) : (
                      <Text style={[styles.aiConfigActionText, { color: styles._colors.primary }]}>
                        测试
                      </Text>
                    )}
                  </TouchableOpacity>
                  <TouchableOpacity
                    style={styles.aiConfigActionBtn}
                    onPress={() => handleEditAiConfig(cfg)}
                  >
                    <Text style={[styles.aiConfigActionText, { color: styles._colors.primary }]}>
                      编辑
                    </Text>
                  </TouchableOpacity>
                  <TouchableOpacity
                    style={styles.aiConfigActionBtn}
                    onPress={() => handleDeleteAiConfig(cfg)}
                  >
                    <Text style={[styles.aiConfigActionText, { color: styles._colors.error }]}>删除</Text>
                  </TouchableOpacity>
                </View>
              </View>
            ))
          )}

          <TouchableOpacity style={styles.addAiConfigButton} onPress={handleAddAiConfig}>
            <Text style={styles.addAiConfigButtonText}>+ 添加模型配置</Text>
          </TouchableOpacity>
        </View>


        <View style={styles.descriptionBox}>
          <Text style={styles.descriptionTitle}>📋 配置说明</Text>
          <View style={styles.descriptionList}>
            <Text style={styles.descriptionItem}>• AI 模型配置：管理 AI 智能记账所需的模型</Text>
          </View>
        </View>
      </ScrollView>

      {/* AI 配置弹窗 */}
      <AIConfigModal
        visible={showAiConfigModal}
        config={editingAiConfig}
        onClose={() => setShowAiConfigModal(false)}
        onSave={handleSaveAiConfig}
        isSaving={savingAiConfig}
        colors={styles._colors}
      />
    </SafeAreaView>
  );
}

const createStyles = (colors: ThemeColors) => ({
  ...StyleSheet.create({
    container: {
      flex: 1,
      backgroundColor: colors.background,
    },
    scrollView: {
      flex: 1,
    },
    card: {
      marginHorizontal: spacing.lg,
      marginTop: spacing.md,
      backgroundColor: colors.surface,
      borderRadius: borderRadius.card,
      padding: spacing.lg,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
    },
    cardTitle: {
      fontSize: 16,
      fontWeight: '800',
      color: colors.textPrimary,
      marginBottom: spacing.md,
    },
    emptyText: {
      fontSize: 14,
      fontWeight: '600',
      color: colors.textSecondary,
      textAlign: 'center',
      paddingVertical: spacing.md,
    },
    // AI Config styles
    aiConfigItem: {
      backgroundColor: colors.background,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      padding: spacing.md,
      marginBottom: spacing.sm,
    },
    aiConfigHeader: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'flex-start',
    },
    aiConfigInfo: {
      flex: 1,
    },
    aiConfigNameRow: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
    },
    aiConfigName: {
      fontSize: 15,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    defaultBadge: {
      fontSize: 12,
      color: colors.primary,
      fontWeight: '800',
    },
    aiConfigModel: {
      fontSize: 12,
      fontWeight: '600',
      fontFamily: 'Courier',
      color: colors.textSecondary,
      marginTop: 2,
    },
    aiConfigActions: {
      flexDirection: 'row',
      marginTop: spacing.sm,
      gap: spacing.xs,
    },
    aiConfigActionBtn: {
      paddingVertical: spacing.xs,
      paddingHorizontal: spacing.sm,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: colors.surface,
    },
    aiConfigActionText: {
      fontSize: 13,
      fontWeight: '700',
    },
    addAiConfigButton: {
      backgroundColor: colors.accent,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      borderRadius: borderRadius.button,
      paddingVertical: spacing.md,
      alignItems: 'center',
      marginTop: spacing.sm,
    },
    addAiConfigButtonText: {
      fontSize: 15,
      color: colors.stroke,
      fontWeight: '800',
    },
    // Input styles
    input: {
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      borderRadius: borderRadius.input,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.sm,
      fontSize: 15,
      fontWeight: '600',
      color: colors.textPrimary,
      backgroundColor: colors.background,
    },
    inputMargin: {
      marginTop: spacing.sm,
    },
    // App item styles
    appItem: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      backgroundColor: colors.background,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      padding: spacing.md,
      marginBottom: spacing.sm,
    },
    appInfo: {
      flex: 1,
    },
    appName: {
      fontSize: 15,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    appPackage: {
      fontSize: 12,
      fontWeight: '600',
      fontFamily: 'Courier',
      color: colors.textSecondary,
      marginTop: 2,
    },
    appActions: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
    },
    deleteButton: {
      paddingVertical: spacing.xs,
      paddingHorizontal: spacing.sm,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.error,
      backgroundColor: colors.error + '15',
    },
    deleteText: {
      fontSize: 13,
      color: colors.error,
      fontWeight: '700',
    },
    addForm: {
      marginTop: spacing.md,
      paddingTop: spacing.md,
      borderTopWidth: borderWidth.thin,
      borderTopColor: colors.stroke,
    },
    addFormTitle: {
      fontSize: 15,
      fontWeight: '700',
      color: colors.textPrimary,
      marginBottom: spacing.sm,
    },
    selectAppButton: {
      backgroundColor: colors.accent,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      borderRadius: borderRadius.button,
      paddingVertical: spacing.sm,
      paddingHorizontal: spacing.md,
      marginBottom: spacing.sm,
      alignItems: 'center',
      minHeight: 44,
      justifyContent: 'center',
    },
    selectAppButtonDisabled: {
      opacity: 0.7,
    },
    selectAppButtonContent: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: spacing.sm,
    },
    selectAppButtonText: {
      fontSize: 15,
      color: colors.stroke,
      fontWeight: '700',
    },
    addButton: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'center',
      gap: spacing.xs,
      marginTop: spacing.sm,
      paddingVertical: spacing.md,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      borderRadius: borderRadius.button,
      backgroundColor: colors.surface,
      minHeight: 48,
    },
    addButtonIcon: {
      fontSize: 16,
      fontWeight: '800',
      color: colors.textPrimary,
    },
    addButtonText: {
      fontSize: 15,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    // Keyword styles
    keywordList: {
      flexDirection: 'row',
      flexWrap: 'wrap',
      gap: spacing.sm,
      marginBottom: spacing.md,
    },
    keywordTag: {
      flexDirection: 'row',
      alignItems: 'center',
      backgroundColor: colors.primaryLight,
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.primary,
      paddingHorizontal: spacing.sm,
      paddingVertical: spacing.xs,
    },
    keywordText: {
      fontSize: 13,
      fontWeight: '700',
      color: colors.primary,
      marginRight: spacing.xs,
    },
    keywordDelete: {
      width: 18,
      height: 18,
      alignItems: 'center',
      justifyContent: 'center',
    },
    keywordDeleteIcon: {
      fontSize: 16,
      fontWeight: '800',
      color: colors.primary,
    },
    keywordForm: {
      flexDirection: 'row',
      gap: spacing.sm,
    },
    keywordInput: {
      flex: 1,
    },
    keywordAddButton: {
      width: 48,
      height: 48,
      alignItems: 'center',
      justifyContent: 'center',
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      borderRadius: borderRadius.button,
      backgroundColor: colors.accent,
    },
    keywordAddIcon: {
      fontSize: 20,
      fontWeight: '800',
      color: colors.stroke,
    },
    // Save button
    saveButton: {
      marginHorizontal: spacing.lg,
      marginTop: spacing.lg,
      paddingVertical: spacing.md,
      backgroundColor: colors.primary,
      borderRadius: borderRadius.button,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      alignItems: 'center',
      justifyContent: 'center',
      minHeight: 48,
      ...shadow.small,
    },
    saveButtonText: {
      fontSize: 15,
      fontWeight: '800',
      color: '#FFFFFF',
    },
    // Description box
    descriptionBox: {
      backgroundColor: colors.surface,
      borderRadius: borderRadius.card,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      padding: spacing.lg,
      marginHorizontal: spacing.lg,
      marginTop: spacing.md,
      marginBottom: spacing.xl,
    },
    descriptionTitle: {
      fontSize: 14,
      fontWeight: '800',
      color: colors.textPrimary,
      marginBottom: spacing.sm,
    },
    descriptionList: {
      gap: spacing.xs,
    },
    descriptionItem: {
      fontSize: 13,
      fontWeight: '600',
      color: colors.textSecondary,
      lineHeight: 20,
    },
    // App picker modal
    modalOverlay: {
      flex: 1,
      backgroundColor: colors.overlay,
      justifyContent: 'flex-end',
    },
    modalContent: {
      backgroundColor: colors.surface,
      borderTopLeftRadius: borderRadius.card,
      borderTopRightRadius: borderRadius.card,
      borderTopWidth: borderWidth.thick,
      borderLeftWidth: borderWidth.thick,
      borderRightWidth: borderWidth.thick,
      borderColor: colors.stroke,
      height: '80%',
    },
    modalHeader: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      paddingHorizontal: spacing.lg,
      paddingVertical: spacing.md,
      borderBottomWidth: borderWidth.thin,
      borderBottomColor: colors.stroke,
    },
    modalTitle: {
      fontSize: 18,
      fontWeight: '800',
      color: colors.textPrimary,
    },
    modalCloseButton: {
      width: 36,
      height: 36,
      alignItems: 'center',
      justifyContent: 'center',
      borderRadius: borderRadius.small,
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      backgroundColor: colors.background,
    },
    modalCloseIcon: {
      fontSize: 22,
      fontWeight: '800',
      color: colors.textPrimary,
    },
    searchContainer: {
      paddingHorizontal: spacing.lg,
      paddingVertical: spacing.sm,
      borderBottomWidth: borderWidth.thin,
      borderBottomColor: colors.stroke,
    },
    searchInput: {
      borderWidth: borderWidth.thin,
      borderColor: colors.stroke,
      borderRadius: borderRadius.input,
      paddingHorizontal: spacing.md,
      paddingVertical: spacing.sm,
      fontSize: 15,
      fontWeight: '600',
      color: colors.textPrimary,
      backgroundColor: colors.background,
    },
    modalLoading: {
      padding: spacing.xl,
      alignItems: 'center',
    },
    modalLoadingText: {
      fontSize: 15,
      fontWeight: '600',
      color: colors.textSecondary,
    },
    appPickerList: {
      flex: 1,
    },
    appPickerItem: {
      paddingHorizontal: spacing.lg,
      paddingVertical: spacing.md,
      borderBottomWidth: borderWidth.thin,
      borderBottomColor: colors.stroke,
    },
    appPickerInfo: {
      flex: 1,
    },
    appPickerName: {
      fontSize: 15,
      fontWeight: '700',
      color: colors.textPrimary,
      marginBottom: 4,
    },
    appPickerPackage: {
      fontSize: 12,
      fontWeight: '600',
      fontFamily: 'Courier',
      color: colors.textSecondary,
    },
  }),
  _colors: colors,
});

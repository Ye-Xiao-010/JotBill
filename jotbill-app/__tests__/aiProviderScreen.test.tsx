import React from 'react';
import { Platform, Switch, Text, TextInput } from 'react-native';
import ReactTestRenderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { AlertProvider, ThemeProvider } from '../src/providers';
import AiProviderScreen from '../src/screens/settings/AiProviderScreen';

const mockGoBack = jest.fn();

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ goBack: mockGoBack }),
}));

const mockGetAiProviders = jest.fn();
const mockAddAiProvider = jest.fn();
const mockUpdateAiProvider = jest.fn();
const mockDeleteAiProvider = jest.fn();
const mockSetDefaultAiProvider = jest.fn();
const mockTestAiProvider = jest.fn();
const mockGetAiBudget = jest.fn();
const mockGetAiCostRecords = jest.fn();

jest.mock('../src/native', () => ({
  nativeAi: {
    getAiProviders: (...args: unknown[]) => mockGetAiProviders(...args),
    addAiProvider: (...args: unknown[]) => mockAddAiProvider(...args),
    updateAiProvider: (...args: unknown[]) => mockUpdateAiProvider(...args),
    deleteAiProvider: (...args: unknown[]) => mockDeleteAiProvider(...args),
    setDefaultAiProvider: (...args: unknown[]) => mockSetDefaultAiProvider(...args),
    testAiProvider: (...args: unknown[]) => mockTestAiProvider(...args),
    getAiBudget: (...args: unknown[]) => mockGetAiBudget(...args),
    getAiCostRecords: (...args: unknown[]) => mockGetAiCostRecords(...args),
  },
}));

function collectTexts(node: ReactTestRenderer.ReactTestInstance): string[] {
  return node.findAllByType(Text).map((t) => {
    const children = t.props.children;
    if (Array.isArray(children)) {
      return children.map((c) => (c == null ? '' : String(c))).join('');
    }
    return children == null ? '' : String(children);
  });
}

async function flushMicrotasks() {
  for (let i = 0; i < 4; i += 1) {
    await ReactTestRenderer.act(async () => {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    });
  }
}

async function renderScreen(
  themeName: 'soft' | 'blocks' | 'hogwarts' = 'soft',
  mode: 'light' | 'dark' = 'light',
) {
  await AsyncStorage.clear();
  await AsyncStorage.setItem('@app_theme_name', themeName);
  await AsyncStorage.setItem('@app_theme_mode', mode);
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
  });
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    renderer = ReactTestRenderer.create(
      <QueryClientProvider client={client}>
        <ThemeProvider>
          <AlertProvider>
            <AiProviderScreen />
          </AlertProvider>
        </ThemeProvider>
      </QueryClientProvider>,
    );
  });
  await flushMicrotasks();
  const unmount = async () => {
    await ReactTestRenderer.act(async () => {
      renderer.unmount();
    });
    client.clear();
  };
  return { renderer, unmount };
}

describe('AiProviderScreen 升级回归测试', () => {
  const originalPlatformOs = Platform.OS;

  beforeEach(() => {
    jest.clearAllMocks();
    Object.defineProperty(Platform, 'OS', {
      configurable: true,
      value: 'android',
    });
    mockGetAiProviders.mockResolvedValue([
      {
        id: 'cfg-ds',
        name: '我的 DeepSeek',
        provider: 'deepseek',
        model: 'deepseek-chat',
        apiKey: '********',
        apiBaseUrl: 'https://api.deepseek.com',
        isDefault: true,
        supportsVision: false,
        createdAt: '2026-11-01T00:00:00Z',
        updatedAt: '2026-11-01T00:00:00Z',
      },
    ]);
    mockGetAiBudget.mockResolvedValue({
      monthlyBudgetMinor: 1000,
      monthlySpentMinor: 0,
      remainingMinor: 1000,
      requestCount: 18,
      month: '2026-11',
    });
    mockGetAiCostRecords.mockResolvedValue([
      {
        id: 'r-1',
        provider: 'deepseek',
        model: 'deepseek-chat',
        requestType: 'chat',
        tokensIn: 12500,
        tokensOut: 3200,
        costMinor: 0,
        success: true,
        createdAt: '2026-11-20T00:00:00Z',
      },
    ]);
    mockAddAiProvider.mockResolvedValue({
      id: 'cfg-new',
      name: '硅基视觉',
      provider: 'siliconflow',
      model: 'Qwen/Qwen2.5-VL-72B-Instruct-Custom',
      apiKey: '********',
      apiBaseUrl: 'https://api.siliconflow.cn',
      isDefault: false,
      supportsVision: true,
      createdAt: '2026-11-25T00:00:00Z',
      updatedAt: '2026-11-25T00:00:00Z',
    });
    mockTestAiProvider.mockResolvedValue({
      success: true,
      message: '连接成功，模型 deepseek-chat 可用',
      latencyMs: 312,
    });
  });

  afterAll(() => {
    Object.defineProperty(Platform, 'OS', {
      configurable: true,
      value: originalPlatformOs,
    });
  });

  test('展示月度 AI 调用次数与输入输出 Token 用量统计，且测试连接展示耗时与真实结果', async () => {
    const { renderer, unmount } = await renderScreen('soft', 'light');
    try {
      expect(renderer.root.findByProps({ testID: 'ai-usage-calls' }).props.children).toBe(18);
      expect(renderer.root.findByProps({ testID: 'ai-usage-tokens-in' }).props.children).toBe('12.5k');
      expect(renderer.root.findByProps({ testID: 'ai-usage-tokens-out' }).props.children).toBe('3.2k');

      const testBtn = renderer.root.findByProps({ testID: 'ai-provider-test-cfg-ds' });
      await ReactTestRenderer.act(async () => {
        testBtn.props.onPress();
      });
      await flushMicrotasks();

      expect(mockTestAiProvider).toHaveBeenCalledWith('cfg-ds');
      const allTexts = collectTexts(renderer.root).join(' ');
      expect(allTexts).toContain('连接成功，模型 deepseek-chat 可用');
      expect(allTexts).toContain('312ms');
    } finally {
      await unmount();
    }
  });

  test('支持 DeepSeek 预设、任意服务商自定义模型输入以及自由开启视觉开关', async () => {
    const { renderer, unmount } = await renderScreen('soft', 'light');
    try {
      const addBtn = renderer.root.findByProps({ testID: 'ai-providers-add-btn' });
      await ReactTestRenderer.act(async () => {
        addBtn.props.onPress();
      });

      const deepseekOption = renderer.root.findByProps({ testID: 'ai-provider-option-deepseek' });
      await ReactTestRenderer.act(async () => {
        deepseekOption.props.onPress();
      });
      expect(renderer.root.findByProps({ testID: 'ai-url-input' }).props.value).toBe(
        'https://api.deepseek.com',
      );
      expect(renderer.root.findByProps({ testID: 'ai-model-input' }).props.value).toBe(
        'deepseek-chat',
      );

      // 切到 SiliconFlow，自由输入自定义模型名并开启视觉识别（不再被 disabled 锁死）
      const sfOption = renderer.root.findByProps({ testID: 'ai-provider-option-siliconflow' });
      await ReactTestRenderer.act(async () => {
        sfOption.props.onPress();
      });

      const visionSwitch = renderer.root.findByProps({ testID: 'ai-vision-switch' });
      expect(visionSwitch.type).toBe(Switch);
      expect(visionSwitch.props.disabled).toBeFalsy();

      await ReactTestRenderer.act(async () => {
        renderer.root.findByProps({ testID: 'ai-name-input' }).props.onChangeText('硅基视觉');
        renderer.root.findByProps({ testID: 'ai-key-input' }).props.onChangeText('sk-test-123');
        const modelInput = renderer.root.findByProps({ testID: 'ai-model-input' });
        expect(modelInput.type).toBe(TextInput);
        modelInput.props.onChangeText('Qwen/Qwen2.5-VL-72B-Instruct-Custom');
        visionSwitch.props.onValueChange(true);
      });

      await ReactTestRenderer.act(async () => {
        renderer.root.findByProps({ testID: 'ai-form-submit-btn' }).props.onPress();
      });
      await flushMicrotasks();

      expect(mockAddAiProvider).toHaveBeenCalledWith(
        expect.objectContaining({
          name: '硅基视觉',
          provider: 'siliconflow',
          model: 'Qwen/Qwen2.5-VL-72B-Instruct-Custom',
          apiKey: 'sk-test-123',
          supportsVision: true,
        }),
      );
    } finally {
      await unmount();
    }
  });

  test.each([
    ['soft', 'light'],
    ['soft', 'dark'],
    ['blocks', 'light'],
    ['blocks', 'dark'],
    ['hogwarts', 'light'],
    ['hogwarts', 'dark'],
  ] as const)('在 %s (%s) 主题下正常渲染无报错', async (themeName, mode) => {
    const { renderer, unmount } = await renderScreen(themeName, mode);
    try {
      const texts = collectTexts(renderer.root).join(' ');
      expect(texts).toContain('我的 DeepSeek');
      expect(texts).toContain('本月用量统计');
    } finally {
      await unmount();
    }
  });
});

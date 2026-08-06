import { NativeModules } from 'react-native';

export type AiProvider = 'qwen' | 'glm' | 'siliconflow' | 'custom';

export interface AiProviderConfig {
  id: string;
  name: string;
  provider: AiProvider;
  model: string;
  apiKey?: string;
  apiBaseUrl?: string;
  isDefault: boolean;
  supportsVision: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface AiProviderInput {
  name: string;
  provider: AiProvider;
  model: string;
  apiKey: string;
  apiBaseUrl?: string;
  isDefault?: boolean;
  supportsVision: boolean;
}

export type AiProviderUpdateInput = Partial<AiProviderInput>;

export interface TestConnectionResult {
  success: boolean;
  message: string;
  latencyMs?: number;
}

interface RawAiModule {
  getAiProviders(): Promise<string>;
  addAiProvider(inputJson: string): Promise<string>;
  updateAiProvider(id: string, inputJson: string): Promise<string>;
  deleteAiProvider(id: string): Promise<null>;
  setDefaultAiProvider(id: string): Promise<string>;
  testAiProvider(id: string): Promise<string>;
}

function getRawAiModule(): RawAiModule {
  const module = NativeModules.Ai as RawAiModule | undefined;
  if (!module) {
    throw new Error('Native Ai module is unavailable on this platform');
  }
  return module;
}

function parseJson<T>(value: string, operation: string): T {
  try {
    return JSON.parse(value) as T;
  } catch {
    throw new Error(`Native Ai.${operation} returned invalid JSON`);
  }
}

export const nativeAi = {
  async getAiProviders(): Promise<AiProviderConfig[]> {
    return parseJson(await getRawAiModule().getAiProviders(), 'getAiProviders');
  },

  async addAiProvider(input: AiProviderInput): Promise<AiProviderConfig> {
    return parseJson(
      await getRawAiModule().addAiProvider(JSON.stringify(input)),
      'addAiProvider',
    );
  },

  async updateAiProvider(
    id: string,
    input: AiProviderUpdateInput,
  ): Promise<AiProviderConfig> {
    return parseJson(
      await getRawAiModule().updateAiProvider(id, JSON.stringify(input)),
      'updateAiProvider',
    );
  },

  async deleteAiProvider(id: string): Promise<void> {
    await getRawAiModule().deleteAiProvider(id);
  },

  async setDefaultAiProvider(id: string): Promise<AiProviderConfig> {
    return parseJson(
      await getRawAiModule().setDefaultAiProvider(id),
      'setDefaultAiProvider',
    );
  },

  async testAiProvider(id: string): Promise<TestConnectionResult> {
    return parseJson(
      await getRawAiModule().testAiProvider(id),
      'testAiProvider',
    );
  },
};

export type NativeAi = typeof nativeAi;

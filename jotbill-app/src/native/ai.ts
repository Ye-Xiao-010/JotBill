import { NativeEventEmitter, NativeModules } from 'react-native';
import type { ChatMessageData, ChatSession } from '../types/ai';

export type AiProvider = 'qwen' | 'glm' | 'siliconflow' | 'deepseek' | 'custom';

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

export interface AiCostRecord {
  id: string;
  provider: AiProvider;
  model: string;
  requestType: 'vision' | 'chat' | 'asr';
  tokensIn?: number;
  tokensOut?: number;
  costMinor: number;
  success: boolean;
  createdAt: string;
}

export interface AiBudgetSummary {
  monthlyBudgetMinor: number;
  monthlySpentMinor: number;
  remainingMinor: number;
  requestCount: number;
  month: string;
}

export interface NativeChatRequest {
  requestId: string;
  content: string;
  sessionId?: string;
  configId?: string;
  imageBase64List?: string[];
}

export interface NativeChatStartResult {
  requestId: string;
  sessionId: string;
}

export interface NativeChatSessionDetail {
  session: ChatSession;
  messages: ChatMessageData[];
}

export interface NativeAiStreamChunk {
  requestId: string;
  sessionId: string;
  type:
    | 'session_created'
    | 'thinking'
    | 'thinking_delta'
    | 'text_delta'
    | 'tool_call_start'
    | 'tool_result'
    | 'done'
    | 'error';
  content?: string;
  round?: number;
  thinkingTimeMs?: number;
  cancelled?: boolean;
  message?: string;
  toolCallId?: string;
  toolName?: string;
  resultJson?: string;
}

interface RawAiModule {
  getAiProviders(): Promise<string>;
  addAiProvider(inputJson: string): Promise<string>;
  updateAiProvider(id: string, inputJson: string): Promise<string>;
  deleteAiProvider(id: string): Promise<null>;
  setDefaultAiProvider(id: string): Promise<string>;
  testAiProvider(id: string): Promise<string>;
  getAiBudget(): Promise<string>;
  setAiBudget(monthlyBudgetMinor: number): Promise<null>;
  getAiCostRecords(month?: string | null): Promise<string>;
  sendMessage(inputJson: string): Promise<string>;
  cancelMessage(sessionId: string): Promise<boolean>;
  getChatSessions(page: number, limit: number): Promise<string>;
  getChatSession(sessionId: string): Promise<string>;
  deleteChatSession(sessionId: string): Promise<null>;
  renameChatSession(sessionId: string, title: string): Promise<null>;
  togglePinChatSession(sessionId: string): Promise<boolean>;
  transcribeAudio(audioBase64: string, mimeType?: string | null): Promise<string>;
  addListener(eventName: string): void;
  removeListeners(count: number): void;
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

  async getAiBudget(): Promise<AiBudgetSummary> {
    return parseJson(await getRawAiModule().getAiBudget(), 'getAiBudget');
  },

  async setAiBudget(monthlyBudgetMinor: number): Promise<void> {
    await getRawAiModule().setAiBudget(monthlyBudgetMinor);
  },

  async getAiCostRecords(month?: string): Promise<AiCostRecord[]> {
    return parseJson(
      await getRawAiModule().getAiCostRecords(month ?? null),
      'getAiCostRecords',
    );
  },

  async sendMessage(input: NativeChatRequest): Promise<NativeChatStartResult> {
    return parseJson(
      await getRawAiModule().sendMessage(JSON.stringify(input)),
      'sendMessage',
    );
  },

  async cancelMessage(sessionId: string): Promise<boolean> {
    return getRawAiModule().cancelMessage(sessionId);
  },

  async getChatSessions(page = 1, limit = 50): Promise<ChatSession[]> {
    return parseJson(
      await getRawAiModule().getChatSessions(page, limit),
      'getChatSessions',
    );
  },

  async getChatSession(sessionId: string): Promise<NativeChatSessionDetail> {
    return parseJson(
      await getRawAiModule().getChatSession(sessionId),
      'getChatSession',
    );
  },

  async deleteChatSession(sessionId: string): Promise<void> {
    await getRawAiModule().deleteChatSession(sessionId);
  },

  async renameChatSession(sessionId: string, title: string): Promise<void> {
    await getRawAiModule().renameChatSession(sessionId, title);
  },

  async togglePinChatSession(sessionId: string): Promise<boolean> {
    return getRawAiModule().togglePinChatSession(sessionId);
  },

  async transcribeAudio(
    audioBase64: string,
    mimeType?: string,
  ): Promise<{ text: string; latencyMs: number }> {
    return parseJson(
      await getRawAiModule().transcribeAudio(audioBase64, mimeType ?? null),
      'transcribeAudio',
    );
  },
};

export function addAiStreamListener(
  listener: (chunk: NativeAiStreamChunk) => void,
): { remove: () => void } {
  const emitter = new NativeEventEmitter(getRawAiModule() as never);
  return emitter.addListener('onAiStreamChunk', listener);
}

export type NativeAi = typeof nativeAi;

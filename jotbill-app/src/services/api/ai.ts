/**
 * AI Native Module 包装层
 */
import {
  addAiStreamListener,
  nativeAi,
  type AiProvider,
  type AiProviderConfig,
} from '../../native/ai';
import type { ApiResponse } from '../../types/api';
import type {
  AIModelConfig,
  AIProvider,
  CreateAIConfigDto,
  UpdateAIConfigDto,
  TestConnectionResult,
  ChatRequest,
  ChatSession,
  ChatMessageData,
  StreamCallbacks,
} from '../../types/ai';

const NATIVE_PROVIDERS = new Set<AIProvider>([
  'qwen',
  'glm',
  'siliconflow',
  'custom',
]);

function success<T>(data: T, message = '操作成功'): ApiResponse<T> {
  return { success: true, data, message };
}

function toModelConfig(config: AiProviderConfig): AIModelConfig {
  return config as AIModelConfig;
}

function toNativeProvider(provider: AIProvider): AiProvider {
  if (!NATIVE_PROVIDERS.has(provider)) {
    throw new Error(`当前本地版本不支持 AI 服务商: ${provider}`);
  }
  return provider as AiProvider;
}

class AIService {
  async getConfigs(): Promise<ApiResponse<AIModelConfig[]>> {
    const configs = await nativeAi.getAiProviders();
    return success(configs.map(toModelConfig));
  }

  async getConfig(id: string): Promise<ApiResponse<AIModelConfig>> {
    const config = (await nativeAi.getAiProviders()).find(item => item.id === id);
    if (!config) throw new Error('AI 服务商配置不存在');
    return success(toModelConfig(config));
  }

  async createConfig(data: CreateAIConfigDto): Promise<ApiResponse<AIModelConfig>> {
    const config = await nativeAi.addAiProvider({
      ...data,
      provider: toNativeProvider(data.provider),
      supportsVision: data.supportsVision ?? false,
    });
    return success(toModelConfig(config), 'AI 服务商配置已创建');
  }

  async updateConfig(
    id: string,
    data: UpdateAIConfigDto,
  ): Promise<ApiResponse<AIModelConfig>> {
    const config = await nativeAi.updateAiProvider(id, {
      ...data,
      provider: data.provider ? toNativeProvider(data.provider) : undefined,
    });
    return success(toModelConfig(config), 'AI 服务商配置已更新');
  }

  async deleteConfig(id: string): Promise<ApiResponse> {
    await nativeAi.deleteAiProvider(id);
    return success(null, 'AI 服务商配置已删除');
  }

  async setDefaultConfig(id: string): Promise<ApiResponse<AIModelConfig>> {
    return success(toModelConfig(await nativeAi.setDefaultAiProvider(id)));
  }

  async testConfig(id: string): Promise<ApiResponse<TestConnectionResult>> {
    return success(await nativeAi.testAiProvider(id));
  }

  async getChatSessions(params?: { page?: number; limit?: number }): Promise<
    ApiResponse<ChatSession[]>
  > {
    return success(
      await nativeAi.getChatSessions(params?.page ?? 1, params?.limit ?? 50),
    );
  }

  async getChatSession(
    sessionId: string,
  ): Promise<ApiResponse<{ session: ChatSession; messages: ChatMessageData[] }>> {
    return success(await nativeAi.getChatSession(sessionId));
  }

  async deleteChatSession(sessionId: string): Promise<ApiResponse> {
    await nativeAi.deleteChatSession(sessionId);
    return success(null, '会话已删除');
  }

  async renameChatSession(sessionId: string, title: string): Promise<ApiResponse> {
    await nativeAi.renameChatSession(sessionId, title);
    return success(null, '会话已重命名');
  }

  async togglePinChatSession(
    sessionId: string,
  ): Promise<ApiResponse<{ isPinned: boolean }>> {
    return success({ isPinned: await nativeAi.togglePinChatSession(sessionId) });
  }

  /**
   * 语音转文字（ASR）
   */
  async transcribeAudio(
    audioBase64: string,
    mimeType?: string,
  ): Promise<ApiResponse<{ text: string }>> {
    const result = await nativeAi.transcribeAudio(audioBase64, mimeType);
    return success({ text: result.text });
  }

  /**
   * 订阅原生流式事件，close 会同时移除监听并中止原生请求。
   */
  streamMessage(
    data: ChatRequest,
    callbacks: StreamCallbacks,
  ): { close: () => void } {
    const requestId = `chat-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
    let sessionId = data.sessionId;
    let closed = false;
    let sessionNotified = false;

    const subscription = addAiStreamListener(chunk => {
      if (closed || chunk.requestId !== requestId) return;
      sessionId = chunk.sessionId;
      switch (chunk.type) {
        case 'session_created':
          sessionNotified = true;
          callbacks.onSessionCreated?.({ sessionId: chunk.sessionId });
          break;
        case 'thinking':
          callbacks.onThinking?.({ round: chunk.round ?? 1 });
          break;
        case 'thinking_delta':
          callbacks.onThinkingDelta?.({ content: chunk.content ?? '' });
          break;
        case 'text_delta':
          callbacks.onTextDelta?.({ content: chunk.content ?? '' });
          break;
        case 'tool_call_start':
          callbacks.onToolCallStart?.({
            toolCallId: chunk.toolCallId ?? 'unknown',
            toolName: chunk.toolName ?? 'unknown',
          });
          break;
        case 'tool_result':
          try {
            callbacks.onToolResult?.({
              toolCallId: chunk.toolCallId ?? 'unknown',
              toolName: chunk.toolName ?? 'unknown',
              result: JSON.parse(chunk.resultJson ?? '{}'),
            });
          } catch {
            callbacks.onError?.({ message: 'AI 返回的工具结果格式无效' });
          }
          break;
        case 'done':
          subscription.remove();
          callbacks.onDone?.({
            sessionId: chunk.sessionId,
            thinkingTimeMs: chunk.thinkingTimeMs ?? 0,
            cancelled: chunk.cancelled,
          });
          break;
        case 'error':
          subscription.remove();
          callbacks.onError?.({ message: chunk.message ?? 'AI 请求失败' });
          break;
      }
    });

    const started = nativeAi
      .sendMessage({ ...data, requestId })
      .then(result => {
        sessionId = result.sessionId;
        if (!closed && !sessionNotified) {
          sessionNotified = true;
          callbacks.onSessionCreated?.({ sessionId: result.sessionId });
        }
        if (closed) return nativeAi.cancelMessage(result.sessionId);
      })
      .catch(error => {
        if (closed) return;
        subscription.remove();
        callbacks.onError?.({ message: error?.message ?? 'AI 请求失败' });
      });

    return {
      close: () => {
        if (closed) return;
        closed = true;
        subscription.remove();
        if (sessionId) {
          nativeAi.cancelMessage(sessionId).catch(() => {});
        } else {
          started.catch(() => {});
        }
      },
    };
  }
}

export const aiService = new AIService();

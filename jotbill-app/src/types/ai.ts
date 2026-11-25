/**
 * AI 相关类型定义
 */

// 支持的 AI 服务商（与原生 AiModule / AiEndpointResolver 保持一致）
export type AIProvider =
  | 'qwen'
  | 'glm'
  | 'siliconflow'
  | 'deepseek'
  | 'custom';

// AI 模型配置
export interface AIModelConfig {
  id: string;
  name: string;
  provider: AIProvider;
  model: string;
  apiKey?: string;
  apiBaseUrl?: string;
  isDefault: boolean;
  supportsVision: boolean;
  createdAt: string;
  updatedAt: string;
}

// 创建 AI 配置请求
export interface CreateAIConfigDto {
  name: string;
  provider: AIProvider;
  apiKey: string;
  apiBaseUrl?: string;
  model: string;
  supportsVision?: boolean;
  isDefault?: boolean;
}

// 更新 AI 配置请求
export interface UpdateAIConfigDto {
  name?: string;
  provider?: AIProvider;
  apiKey?: string;
  apiBaseUrl?: string;
  model?: string;
  supportsVision?: boolean;
  isDefault?: boolean;
}

export const GLM_DEFAULT_MODEL = 'glm-4v-flash';
// 智谱视觉模型列表，保留新旧配置可选项。
export const GLM_MODEL_OPTIONS = [GLM_DEFAULT_MODEL, 'glm-4.6v-flash'];

// 解析账单请求
export interface ParseBillRequest {
  type: 'image' | 'text';
  content: string;
  configId?: number;
}

// 解析后的账单
export interface ParsedBill {
  amount: number;
  type: 'income' | 'expense';
  categoryName: string;
  accountName?: string;
  description: string;
  date: string;
}

// 测试连接结果
export interface TestConnectionResult {
  success: boolean;
  message: string;
  latencyMs?: number;
}

// ========== 聊天会话相关类型 ==========

// 聊天会话
export interface ChatSession {
  id: string;
  userId?: string;
  title: string | null;
  aiConfigId: string | null;
  isActive: boolean;
  isPinned: boolean;
  createdAt: string;
  updatedAt: string;
  _count?: { messages: number };
}

// 聊天消息（服务端）
export interface ChatMessageData {
  id: string;
  sessionId: string;
  seqNum: number;
  role: 'system' | 'user' | 'assistant' | 'tool';
  content: string | null;
  toolCalls: ToolCallData[] | null;
  toolCallId: string | null;
  toolName: string | null;
  toolResult: ToolResultData | null;
  imageUrl: string | null;
  createdAt: string;
}

// 工具调用
export interface ToolCallData {
  id: string;
  name: string;
  arguments: Record<string, any>;
}

// 工具执行结果
export interface ToolResultData {
  success: boolean;
  data: any;
  message: string;
}

// 聊天请求
export interface ChatRequest {
  content: string;
  sessionId?: string;
  configId?: string;
  imageBase64List?: string[];
}

// 聊天响应
export interface ChatResponseData {
  sessionId: string;
  content: string;
  toolResults: Array<{
    toolCallId: string;
    toolName: string;
    result: ToolResultData;
  }>;
}

// ========== SSE 流式回调 ==========

export interface StreamCallbacks {
  onSessionCreated?: (data: { sessionId: string }) => void;
  onThinking?: (data: { round: number }) => void;
  onThinkingDelta?: (data: { content: string }) => void;
  onTextDelta?: (data: { content: string }) => void;
  onToolCallStart?: (data: { toolCallId: string; toolName: string }) => void;
  onToolResult?: (data: {
    toolCallId: string;
    toolName: string;
    result: ToolResultData;
  }) => void;
  onDone?: (data: { sessionId: string; thinkingTimeMs: number; cancelled?: boolean }) => void;
  onError?: (data: { message: string }) => void;
}

// 服务商预设配置
export interface ProviderPreset {
  name: string;
  defaultUrl: string;
  models: string[];
  supportsVision: boolean;
}

// 服务商预设
export const AI_PROVIDERS: Record<AIProvider, ProviderPreset> = {
  qwen: {
    name: '通义千问',
    defaultUrl: 'https://dashscope.aliyuncs.com',
    models: ['qwen-plus', 'qwen-turbo', 'qwen-max', 'qwen-vl-plus', 'qwen3-omni-flash'],
    supportsVision: true,
  },
  deepseek: {
    name: 'DeepSeek',
    defaultUrl: 'https://api.deepseek.com',
    models: ['deepseek-chat', 'deepseek-reasoner'],
    supportsVision: false,
  },
  glm: {
    name: '智谱 GLM',
    defaultUrl: 'https://open.bigmodel.cn',
    models: [...GLM_MODEL_OPTIONS, 'glm-4-flash', 'glm-4-voice'],
    supportsVision: true,
  },
  siliconflow: {
    name: 'SiliconFlow',
    defaultUrl: 'https://api.siliconflow.cn',
    models: [
      'deepseek-ai/DeepSeek-V3',
      'deepseek-ai/DeepSeek-R1',
      'Qwen/Qwen2.5-7B-Instruct',
      'Qwen/Qwen2.5-VL-72B-Instruct',
    ],
    supportsVision: false,
  },
  custom: {
    name: '自定义服务商',
    defaultUrl: '',
    models: [],
    supportsVision: false,
  },
};

// getProviderIcon 已移除，请使用 ProviderIcon 组件代替
// import { ProviderIcon } from '../components/icons/AIProviderIcons';

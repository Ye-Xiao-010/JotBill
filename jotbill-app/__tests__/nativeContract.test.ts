import { NativeModules } from 'react-native';
import { nativeAi, type AiBudgetSummary, type AiCostRecord } from '../src/native/ai';
import { nativeBackup } from '../src/native/backup';
import {
  nativeData,
  type Bill,
  type BillInput,
  type FinancialGoalUpdateInput,
  type RuleUpdateInput,
} from '../src/native/data';
import { nativeRuntime, type RuntimeHealth } from '../src/native/runtime';
import type { ChatMessageData, ChatSession } from '../src/types/ai';
import type {
  AiChatMessage as DeclaredAiChatMessage,
  AiChatSession as DeclaredAiChatSession,
  AiBudgetSummary as DeclaredAiBudgetSummary,
  BillInput as DeclaredBillInput,
  FinancialGoalUpdateInput as DeclaredFinancialGoalUpdateInput,
  RuleUpdateInput as DeclaredRuleUpdateInput,
  RuntimeHealth as DeclaredRuntimeHealth,
} from '../../docs/types/Native';

const modules = NativeModules as unknown as Record<string, unknown>;

const bill: Bill = {
  id: 'bill-1',
  amountMinor: 1234,
  type: 'expense',
  occurredAt: '2026-08-08T00:00:00Z',
  sourceType: 'manual',
  createdAt: '2026-08-08T00:00:00Z',
  updatedAt: '2026-08-08T00:00:00Z',
  version: 1,
};

const billInput: BillInput = {
  amountMinor: bill.amountMinor,
  type: bill.type,
  occurredAt: bill.occurredAt,
  sourceType: bill.sourceType,
  description: null,
};

const declaredBillInput = (value: DeclaredBillInput): BillInput => value;
const declaredBudget = (value: DeclaredAiBudgetSummary): AiBudgetSummary => value;
const declaredRuntime = (value: DeclaredRuntimeHealth): RuntimeHealth => value;
const declaredFinancialGoalUpdate = (
  value: DeclaredFinancialGoalUpdateInput,
): FinancialGoalUpdateInput => value;
const declaredRuleUpdate = (value: DeclaredRuleUpdateInput): RuleUpdateInput => value;
const declaredChatSession = (value: ChatSession): DeclaredAiChatSession => value;
const declaredChatMessage = (value: ChatMessageData): DeclaredAiChatMessage => value;

test('Data 模块保留 sourceType 并使用 JSON 边界', async () => {
  const addBill = jest.fn().mockResolvedValue(JSON.stringify(bill));
  modules.Data = { addBill };

  const result = await nativeData.addBill(declaredBillInput(billInput));

  expect(addBill).toHaveBeenCalledWith(JSON.stringify(billInput));
  expect(result).toEqual(bill);
});

test('Ai 模块暴露预算和成本记录 DTO', async () => {
  const budget = {
    monthlyBudgetMinor: 1000,
    monthlySpentMinor: 200,
    remainingMinor: 800,
    requestCount: 2,
    month: '2026-08',
  } satisfies AiBudgetSummary;
  const records: AiCostRecord[] = [];
  const getAiBudget = jest.fn().mockResolvedValue(JSON.stringify(budget));
  const setAiBudget = jest.fn().mockResolvedValue(null);
  const getAiCostRecords = jest.fn().mockResolvedValue(JSON.stringify(records));
  modules.Ai = { getAiBudget, setAiBudget, getAiCostRecords };

  expect(await nativeAi.getAiBudget()).toEqual(declaredBudget(budget));
  await nativeAi.setAiBudget(2000);
  expect(await nativeAi.getAiCostRecords('2026-08')).toEqual(records);
  expect(setAiBudget).toHaveBeenCalledWith(2000);
  expect(getAiCostRecords).toHaveBeenCalledWith('2026-08');
});

test('Data 和 Ai 更新及会话 DTO 与 Native.d.ts 一致', () => {
  expect(declaredFinancialGoalUpdate({ currentAmountMinor: 300 })).toEqual({
    currentAmountMinor: 300,
  });
  expect(declaredRuleUpdate({ isActive: false })).toEqual({ isActive: false });

  const session: ChatSession = {
    id: 'session-1',
    title: null,
    aiConfigId: null,
    isActive: true,
    isPinned: false,
    createdAt: '2026-08-08T00:00:00Z',
    updatedAt: '2026-08-08T00:00:00Z',
  };
  const message: ChatMessageData = {
    id: 'message-1',
    sessionId: session.id,
    seqNum: 1,
    role: 'user',
    content: null,
    toolCalls: null,
    toolCallId: null,
    toolName: null,
    toolResult: null,
    imageUrl: null,
    createdAt: session.createdAt,
  };

  expect(declaredChatSession(session)).toEqual(session);
  expect(declaredChatMessage(message)).toEqual(message);
});

test('Runtime 模块返回阶段二基础状态摘要', async () => {
  const status = {
    databaseVersion: 4,
    billCount: 4,
    categoryCount: 2,
    accountCount: 1,
    lastLaunchAt: '2026-08-08T00:00:00Z',
    checkedAt: '2026-08-08T00:00:01Z',
    aiProviderConfigured: false,
    accessibilityEnabled: false,
    notificationEnabled: false,
    overlayPermissionGranted: false,
  } satisfies RuntimeHealth;
  const getRuntimeHealth = jest.fn().mockResolvedValue(JSON.stringify(status));
  modules.Runtime = { getRuntimeHealth };

  expect(await nativeRuntime.getRuntimeHealth()).toEqual(declaredRuntime(status));
});

test('Backup 模块只通过 CSV 导出和清库边界', async () => {
  const exportData = jest.fn().mockResolvedValue(JSON.stringify({ filePath: '/tmp/jotbill.csv' }));
  const clearData = jest.fn().mockResolvedValue(null);
  modules.Backup = { exportData, clearData };

  await nativeBackup.exportData('csv');
  await nativeBackup.clearData();

  expect(exportData).toHaveBeenCalledWith('csv');
  expect(clearData).toHaveBeenCalledTimes(1);
});

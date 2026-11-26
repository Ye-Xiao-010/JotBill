import { nativeData } from '../native';
import { isValidDateString } from '../utils/date';

/**
 * P12/M8 共享查重：「同日 + 同金额 + 同收支方向」既有账单检测。
 *
 * 唯一实现出处 —— AI 提案保存前查重（AIChatScreen）与手动录入保存前查重
 * （CreateBillScreen）共用，禁止在各屏复制第二份查重逻辑，避免判据漂移。
 * 命中时不阻断入账，由调用方弹确认让用户显式决策（查重失败不阻塞保存）。
 */

/** 查重输入条目（AI 提案 BillItem / 手动表单值的最小公共形状）。 */
export interface DuplicateCheckItem {
  /** 金额（元，正数） */
  amount: number;
  /** 收支方向 */
  type: 'income' | 'expense';
  /** 发生日期（YYYY-MM-DD，本地日） */
  date: string;
  /** 描述（用于提示文案，可空） */
  description?: string | null;
}

/**
 * 逐条查重：对每个条目查「当日同金额同方向」的既有账单。
 *
 * @param items 待入账条目列表
 * @returns 命中重复的提示文案列表（最多 5 条，防弹窗文案超长）
 */
export async function findDuplicateBills(items: DuplicateCheckItem[]): Promise<string[]> {
  const hits: string[] = [];
  for (const item of items) {
    if (!item.date || !isValidDateString(item.date)) {
      continue;
    }
    const dayStart = new Date(`${item.date.trim()}T00:00:00`).toISOString();
    const dayEnd = new Date(`${item.date.trim()}T23:59:59.999`).toISOString();
    const existing = await nativeData.getBills({
      startDate: dayStart,
      endDate: dayEnd,
      limit: 500,
    });
    const amountMinor = Math.round(item.amount * 100);
    const duplicated = existing.items.some(
      bill => bill.amountMinor === amountMinor && bill.type === item.type
    );
    if (duplicated) {
      hits.push(`${item.description || '未命名'} ¥${item.amount.toFixed(2)}`);
      if (hits.length >= 5) break;
    }
  }
  return hits;
}

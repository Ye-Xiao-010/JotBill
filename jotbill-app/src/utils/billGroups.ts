/**
 * 首页「近期交易」按天分组
 *
 * - 分组键使用**本地日期**（occurredAt 是 ISO-8601，直接取 UTC 日期会在东八区
 *   凌晨归错天，因此必须先转 Date 再按本地年月日拼 key）。
 * - 当日合计口径与原生统计 / 全部账单一致：income + refund 计入收入，
 *   expense + fee 计入支出；转账、还款、调整不参与收支小计。
 * - 首页只展示最近若干个完整日组，避免把首页变成第二个全部账单页。
 */
import type { Bill } from '../native';
import { formatDate } from './date';

export interface BillDayGroup {
  /** 本地日期 YYYY-MM-DD；日期无法解析时为 'unknown' */
  dateKey: string;
  /** 本地日期；无法解析时为 null（展示层降级为「日期未知」） */
  date: Date | null;
  bills: Bill[];
  /** 当日收入合计（分，含退款） */
  incomeMinor: number;
  /** 当日支出合计（分，含手续费） */
  expenseMinor: number;
}

export interface LimitedDayGroups {
  groups: BillDayGroup[];
  /** 是否还有未展示的更早交易 */
  hasMore: boolean;
}

/** occurredAt 优先，解析失败时回退 createdAt；都失败返回 null */
function resolveBillDate(bill: Bill): Date | null {
  for (const raw of [bill.occurredAt, bill.createdAt]) {
    if (!raw) {
      continue;
    }
    const parsed = new Date(raw);
    if (!Number.isNaN(parsed.getTime())) {
      return parsed;
    }
  }
  return null;
}

function billTimestamp(bill: Bill): number {
  return resolveBillDate(bill)?.getTime() ?? 0;
}

/** 把账单按本地日期分组，日组与组内交易均按时间倒序。 */
export function groupBillsByDay(bills: Bill[]): BillDayGroup[] {
  const groups = new Map<string, BillDayGroup>();

  for (const bill of bills) {
    const date = resolveBillDate(bill);
    const dateKey = date ? formatDate(date) : 'unknown';

    let group = groups.get(dateKey);
    if (!group) {
      group = {
        dateKey,
        date,
        bills: [],
        incomeMinor: 0,
        expenseMinor: 0,
      };
      groups.set(dateKey, group);
    }

    group.bills.push(bill);
    if (bill.type === 'income' || bill.type === 'refund') {
      group.incomeMinor += bill.amountMinor;
    } else if (bill.type === 'expense' || bill.type === 'fee') {
      group.expenseMinor += bill.amountMinor;
    }
  }

  const result = Array.from(groups.values());

  // 未知日期排到最后，其余按日期倒序
  result.sort((a, b) => {
    if (!a.date && !b.date) {
      return 0;
    }
    if (!a.date) {
      return 1;
    }
    if (!b.date) {
      return -1;
    }
    return b.date.getTime() - a.date.getTime();
  });

  for (const group of result) {
    group.bills.sort((a, b) => billTimestamp(b) - billTimestamp(a));
  }

  return result;
}

/**
 * 截取首页要展示的日组。
 * 以完整日组为单位截断：不把某一天拆成两半；第一组始终完整展示，
 * 避免「今天交易多」时首页连一组都显示不出来。
 */
export function limitBillDayGroups(
  groups: BillDayGroup[],
  maxDays: number = 4,
  maxBills: number = 12,
): LimitedDayGroups {
  const shown: BillDayGroup[] = [];
  let billCount = 0;

  for (const group of groups) {
    if (shown.length >= maxDays) {
      break;
    }
    if (shown.length > 0 && billCount + group.bills.length > maxBills) {
      break;
    }
    shown.push(group);
    billCount += group.bills.length;
  }

  return { groups: shown, hasMore: shown.length < groups.length };
}

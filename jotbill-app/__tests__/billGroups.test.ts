/**
 * 首页按天分组逻辑测试
 *
 * 覆盖：本地日期分组、组内倒序、当日收支口径（退款计入收入 / 手续费计入支出 /
 * 转账不参与）、未知日期兜底、首页截断规则（完整日组、首组不拆）。
 */
import type { Bill, TransactionType } from '../src/native';
import { groupBillsByDay, limitBillDayGroups } from '../src/utils/billGroups';

function makeBill(
  id: string,
  occurredAt: string,
  amountMinor: number,
  type: TransactionType = 'expense',
): Bill {
  return {
    id,
    amountMinor,
    type,
    occurredAt,
    sourceType: 'manual',
    createdAt: occurredAt,
    updatedAt: occurredAt,
    version: 1,
  };
}

describe('groupBillsByDay', () => {
  it('按本地日期分组，日组与组内交易均按时间倒序', () => {
    const groups = groupBillsByDay([
      makeBill('1', '2026-11-13T09:00:00', 2500, 'expense'),
      makeBill('2', '2026-11-13T12:30:00', 10000, 'income'),
      makeBill('3', '2026-11-12T20:00:00', 500, 'fee'),
      makeBill('4', '2026-11-12T08:00:00', 9000, 'transfer'),
    ]);

    expect(groups.map(group => group.dateKey)).toEqual([
      '2026-11-13',
      '2026-11-12',
    ]);
    expect(groups[0].bills.map(bill => bill.id)).toEqual(['2', '1']);
    expect(groups[1].bills.map(bill => bill.id)).toEqual(['3', '4']);
  });

  it('当日小计口径：收入 + 退款计入收入，支出 + 手续费计入支出，转账忽略', () => {
    const [group] = groupBillsByDay([
      makeBill('1', '2026-11-13T09:00:00', 2500, 'expense'),
      makeBill('2', '2026-11-13T10:00:00', 1000, 'fee'),
      makeBill('3', '2026-11-13T11:00:00', 10000, 'income'),
      makeBill('4', '2026-11-13T12:00:00', 300, 'refund'),
      makeBill('5', '2026-11-13T13:00:00', 9000, 'transfer'),
      makeBill('6', '2026-11-13T14:00:00', 2000, 'repayment'),
    ]);

    expect(group.incomeMinor).toBe(10300);
    expect(group.expenseMinor).toBe(3500);
  });

  it('日期无法解析时归入 unknown，并排在有效日组之后', () => {
    const groups = groupBillsByDay([
      makeBill('bad', 'not-a-date', 100),
      makeBill('good', '2026-11-13T09:00:00', 200),
    ]);

    expect(groups.map(group => group.dateKey)).toEqual(['2026-11-13', 'unknown']);
    expect(groups[1].date).toBeNull();
    expect(groups[1].expenseMinor).toBe(100);
  });
});

describe('limitBillDayGroups', () => {
  /** 构造 counts 指定的每日账单数（日期依次向前推） */
  function buildGroups(counts: number[]) {
    const bills: Bill[] = [];
    counts.forEach((count, dayIndex) => {
      for (let i = 0; i < count; i += 1) {
        const day = String(13 - dayIndex).padStart(2, '0');
        const hour = String(9 + i).padStart(2, '0');
        bills.push(
          makeBill(`${dayIndex}-${i}`, `2026-11-${day}T${hour}:00:00`, 100),
        );
      }
    });
    return groupBillsByDay(bills);
  }

  it('默认最多 4 个日组 / 12 笔，超出时提示还有更早交易', () => {
    const groups = buildGroups([6, 6, 2]);
    const result = limitBillDayGroups(groups);

    expect(result.groups).toHaveLength(2);
    expect(result.groups.reduce((sum, g) => sum + g.bills.length, 0)).toBe(12);
    expect(result.hasMore).toBe(true);
  });

  it('第一组超过笔数上限时也完整展示，不拆日组', () => {
    const result = limitBillDayGroups(buildGroups([13, 2]));

    expect(result.groups).toHaveLength(1);
    expect(result.groups[0].bills).toHaveLength(13);
    expect(result.hasMore).toBe(true);
  });

  it('达到日组上限即停止', () => {
    const result = limitBillDayGroups(buildGroups([1, 1, 1, 1, 1]));

    expect(result.groups).toHaveLength(4);
    expect(result.hasMore).toBe(true);
  });

  it('全部可容纳时 hasMore=false', () => {
    const result = limitBillDayGroups(buildGroups([2, 3]));

    expect(result.groups).toHaveLength(2);
    expect(result.hasMore).toBe(false);
  });
});

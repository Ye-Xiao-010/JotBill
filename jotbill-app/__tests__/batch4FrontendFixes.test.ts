import {
  formatDate,
  formatMonth,
  formatMonthDisplay,
  isValidDateString,
} from '../src/utils/date';
import { findDuplicateBills } from '../src/lib/billDuplicateCheck';
import { nativeData } from '../src/native';
import {
  formatAccountMoney,
  partitionAccounts,
} from '../src/screens/settings/accounts/accountUiHelpers';
import type { Account } from '../src/native';

jest.mock('../src/native', () => ({
  nativeData: {
    getBills: jest.fn(),
  },
}));

describe('Batch 4 frontend date, duplicate check & account helpers', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  test('isValidDateString accepts real YYYY-MM-DD dates and rejects invalid calendar dates', () => {
    expect(isValidDateString('2026-11-26')).toBe(true);
    expect(isValidDateString('2024-02-29')).toBe(true);
    expect(isValidDateString('2026-02-29')).toBe(false);
    expect(isValidDateString('2026-02-30')).toBe(false);
    expect(isValidDateString('2026/11/26')).toBe(false);
    expect(isValidDateString('')).toBe(false);
    expect(isValidDateString('invalid')).toBe(false);
  });

  test('formatMonthDisplay parses YYYY-MM without UTC timezone shift', () => {
    expect(formatMonthDisplay('2026-11')).toBe('2026年11月');
    expect(formatMonthDisplay('2026-01')).toBe('2026年1月');
    expect(formatMonthDisplay('2026-11-01')).toBe('2026年11月');
  });

  test('formatDate and formatMonth use local calendar fields', () => {
    const localDate = new Date(2026, 0, 1, 0, 30, 0);
    expect(formatDate(localDate)).toBe('2026-01-01');
    expect(formatMonth(localDate)).toBe('2026-01');
  });

  test('findDuplicateBills safely skips malformed dates without throwing RangeError', async () => {
    (nativeData.getBills as jest.Mock).mockResolvedValue({ items: [], total: 0 });
    await expect(
      findDuplicateBills([
        { amount: 25, type: 'expense', date: '2026-02-30', description: '非法日期' },
        { amount: 25, type: 'expense', date: 'not-a-date', description: '坏格式' },
      ]),
    ).resolves.toEqual([]);
    expect(nativeData.getBills).not.toHaveBeenCalled();
  });

  test('liability overpayment formats as positive and is excluded from debt subtotal', () => {
    expect(formatAccountMoney(10000, 'liability')).toBe('¥100.00');
    expect(formatAccountMoney(-50000, 'liability')).toBe('-¥500.00');
    const accounts: Account[] = [
      {
        id: 'cc-overpaid',
        name: '招行信用卡',
        subtype: 'credit_card',
        kind: 'liability',
        initialBalanceMinor: 0,
        currentBalanceMinor: 10000,
        currency: 'CNY',
        sortOrder: 0,
        isActive: true,
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
      },
      {
        id: 'cc-debt',
        name: '建行信用卡',
        subtype: 'credit_card',
        kind: 'liability',
        initialBalanceMinor: -50000,
        currentBalanceMinor: -50000,
        currency: 'CNY',
        sortOrder: 1,
        isActive: true,
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
      },
    ];
    const partitioned = partitionAccounts(accounts);
    expect(partitioned.liabilitiesSubtotalMinor).toBe(50000);
  });
});

/**
 * 柔光记账计算键盘纯函数工具
 * 支持数字、两位小数限制及连续 + / - 算式求值。
 */

export type KeypadKey =
  | '0'
  | '1'
  | '2'
  | '3'
  | '4'
  | '5'
  | '6'
  | '7'
  | '8'
  | '9'
  | '.'
  | '+'
  | '-'
  | 'del';

/** 响应一次按键输入，返回更新后的表达式字符串 */
export function applyKeypadInput(expr: string, key: KeypadKey): string {
  if (key === 'del') {
    return expr.slice(0, -1);
  }

  if (key === '+' || key === '-') {
    if (!expr) return expr;
    const trimmed = /[+-]$/.test(expr) ? expr.slice(0, -1) : expr;
    if (!trimmed) return '';
    return `${trimmed}${key}`;
  }

  const currentOperand = expr.split(/[+-]/).pop() ?? '';

  if (key === '.') {
    if (currentOperand.includes('.')) return expr;
    return `${expr}${currentOperand === '' ? '0.' : '.'}`;
  }

  // 限制最多两位小数
  if (currentOperand.includes('.')) {
    const [, decimals = ''] = currentOperand.split('.');
    if (decimals.length >= 2) return expr;
  } else {
    // 前导 0 替换，避免出现 012
    if (currentOperand === '0') {
      return `${expr.slice(0, -1)}${key}`;
    }
    // 整数部分上限 8 位（与 9999 万校验一致）
    if (currentOperand.length >= 8) return expr;
  }

  return `${expr}${key}`;
}

/** 是否包含二元加减运算符 */
export function hasExpressionOperator(expr: string): boolean {
  return /[+-]/.test(expr.slice(1));
}

/** 计算加减表达式结果（精确到分 / 两位小数） */
export function evaluateAmountExpression(expr: string): number {
  if (!expr) return 0;
  const parts = expr.match(/[+-]?[^+-]+/g);
  if (!parts) return 0;
  const total = parts.reduce((sum, token) => {
    const value = Number.parseFloat(token);
    return Number.isFinite(value) ? sum + value : sum;
  }, 0);
  return Math.round(total * 100) / 100;
}

/** 算式回显文案（仅存在运算符时返回） */
export function formatExpressionPreview(expr: string): string {
  if (!hasExpressionOperator(expr)) return '';
  return `${expr.replace(/([+-])/g, ' $1 ').replace(/-/g, '−')} =`;
}

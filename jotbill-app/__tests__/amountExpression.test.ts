import {
  applyKeypadInput,
  evaluateAmountExpression,
  formatExpressionPreview,
  hasExpressionOperator,
} from '../src/utils/amountExpression';

describe('amountExpression 计算键盘工具', () => {
  it('正常输入数字与小数，限制最多两位小数', () => {
    let expr = '';
    for (const k of ['3', '6', '.', '5', '8', '9'] as const) {
      expr = applyKeypadInput(expr, k);
    }
    expect(expr).toBe('36.58');
    expect(evaluateAmountExpression(expr)).toBe(36.58);
  });

  it('前导小数点自动补 0，前导 0 被非零数字替换', () => {
    expect(applyKeypadInput('', '.')).toBe('0.');
    expect(applyKeypadInput('0', '5')).toBe('5');
    expect(applyKeypadInput('12+', '.')).toBe('12+0.');
    expect(applyKeypadInput('12.3', '.')).toBe('12.3');
  });

  it('支持连续加减表达式求值与尾随运算符替换', () => {
    let expr = '36';
    expr = applyKeypadInput(expr, '+');
    expr = applyKeypadInput(expr, '-'); // 替换尾随运算符
    expect(expr).toBe('36-');
    expr = applyKeypadInput(expr, '+');
    expr = applyKeypadInput(expr, '1');
    expr = applyKeypadInput(expr, '4');
    expr = applyKeypadInput(expr, '.');
    expr = applyKeypadInput(expr, '5');
    expect(expr).toBe('36+14.5');
    expect(hasExpressionOperator(expr)).toBe(true);
    expect(evaluateAmountExpression(expr)).toBe(50.5);
    expect(formatExpressionPreview(expr)).toBe('36 + 14.5 =');
  });

  it('退格、空输入和减法负数结果安全处理', () => {
    expect(applyKeypadInput('', '+')).toBe('');
    expect(applyKeypadInput('12+', 'del')).toBe('12');
    expect(evaluateAmountExpression('12-50')).toBe(-38);
    expect(evaluateAmountExpression('36+')).toBe(36);
    expect(evaluateAmountExpression('')).toBe(0);
  });

  it('整数部分最多 8 位，避免超限输入', () => {
    expect(applyKeypadInput('12345678', '9')).toBe('12345678');
    expect(applyKeypadInput('12345678', '.')).toBe('12345678.');
  });
});

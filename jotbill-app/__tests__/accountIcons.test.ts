import {
  accountDisplayName,
  resolveAccountIcon,
} from '../src/constants/accountIcons';

/**
 * 账户图标解析：显式指定优先，其次名称别名匹配，最后 null（回退 lucide）。
 * 匹配口径与 icons/banks/banks.json 的 aliases 同源。
 */
describe('resolveAccountIcon', () => {
  it('显式 bank:<code> 优先于名称', () => {
    const match = resolveAccountIcon('bank:cmbchina', '随便什么名字');
    expect(match?.kind).toBe('bank');
    expect(match && 'code' in match ? match.code : '').toBe('cmbchina');
  });

  it('显式 brand:<code> 命中品牌图标', () => {
    const match = resolveAccountIcon('brand:wechat', null);
    expect(match?.kind).toBe('brand');
    expect(match && 'code' in match ? match.code : '').toBe('wechat');
  });

  it('名称含银行全称时自动匹配', () => {
    const match = resolveAccountIcon(null, '招商银行储蓄卡');
    expect(match?.kind).toBe('bank');
    expect(match && 'code' in match ? match.code : '').toBe('cmbchina');
  });

  it('名称含别名（工行/工商）时自动匹配', () => {
    expect(
      resolveAccountIcon(undefined, '工行工资卡')?.kind === 'bank'
        ? (resolveAccountIcon(undefined, '工行工资卡') as { code: string }).code
        : '',
    ).toBe('icbc');
  });

  it('名称含微信/支付宝时匹配品牌图标', () => {
    expect(resolveAccountIcon(null, '微信零钱')?.kind).toBe('brand');
    expect(resolveAccountIcon(null, '支付宝余额')?.kind).toBe('brand');
  });

  it('无法识别时返回 null（调用方回退 lucide）', () => {
    expect(resolveAccountIcon(null, '日常零花钱')).toBeNull();
    expect(resolveAccountIcon('💵', '现金')).toBeNull();
  });

  it('空间紧张处：银行全称换简称，自定义命名保留', () => {
    const icbc = resolveAccountIcon(null, '中国工商银行');
    expect(accountDisplayName('中国工商银行', icbc)).toBe('工商银行');
    expect(accountDisplayName('招行工资卡', resolveAccountIcon(null, '招行工资卡'))).toBe('招行工资卡');
    expect(accountDisplayName('日常零花钱', null)).toBe('日常零花钱');
  });

  it('「上海」别名不会抢「上海浦东发展银行」', () => {
    const spdb = resolveAccountIcon(null, '上海浦东发展银行');
    expect(spdb?.kind).toBe('bank');
    expect(spdb && 'code' in spdb ? spdb.code : '').toBe('spdb');
  });
});

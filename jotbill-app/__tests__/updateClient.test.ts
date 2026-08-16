import { getSafeUpdateUrl } from '../src/services/update/client';

describe('getSafeUpdateUrl', () => {
  it('只接受 HTTPS 更新地址', () => {
    expect(() => getSafeUpdateUrl('http://example.com/app.apk')).toThrow();
    expect(getSafeUpdateUrl('https://example.com/app.apk')).toBe('https://example.com/app.apk');
  });

  it('拒绝用户名密码、查询参数和片段', () => {
    expect(() => getSafeUpdateUrl('https://user:pass@example.com/app.apk')).toThrow();
    expect(() => getSafeUpdateUrl('https://example.com/app.apk?token=x')).toThrow();
    expect(() => getSafeUpdateUrl('https://example.com/app.apk#fragment')).toThrow();
  });
});

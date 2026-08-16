import { redactSensitive } from '../src/utils/logger';

describe('logger redactSensitive', () => {
  it('整值移除密码、密钥、Token、卡号与 Base64', () => {
    const value = {
      password: 'hunter2',
      headers: { Authorization: 'Bearer token123', 'X-Api-Key': 'sk-abc' },
      card: '6222021234567890',
      image: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==',
    };
    const json = JSON.stringify(redactSensitive(value));
    expect(json).not.toContain('hunter2');
    expect(json).not.toContain('token123');
    expect(json).not.toContain('sk-abc');
    expect(json).not.toContain('6222021234567890');
    expect(json).not.toContain('iVBORw0KGgoAAAANSUhEUg==');
  });
});

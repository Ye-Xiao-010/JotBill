import env from '../../config/env';

/**
 * 更新服务专用的只读 HTTP 客户端，不携带账本或用户认证信息。
 */
export async function getUpdateJson<T>(
  path: string,
  params: Record<string, string | number>,
): Promise<T> {
  const baseUrl = env.getApiBaseUrl().replace(/\/+$/, '');
  const query = Object.entries(params)
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
    .join('&');
  const response = await fetch(`${baseUrl}${path}?${query}`, {
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    throw new Error(`更新服务请求失败: HTTP ${response.status}`);
  }

  const body = await response.json() as { data?: T } | T;
  if (body && typeof body === 'object' && 'data' in body && body.data !== undefined) {
    return body.data;
  }
  return body as T;
}

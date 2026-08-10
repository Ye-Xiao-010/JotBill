import env from '../../config/env';

/**
 * 更新服务专用的只读 HTTP 客户端，不携带账本或用户认证信息。
 */
function isLoopbackHost(hostname: string): boolean {
  const normalized = hostname.toLowerCase().replace(/^\[|\]$/g, '');
  return normalized === 'localhost' || normalized === '127.0.0.1' || normalized === '::1';
}

/** 只允许更新通道使用 HTTPS，开发环境仅额外允许回环地址的 HTTP。 */
export function getSafeUpdateUrl(rawUrl: string): string {
  const value = rawUrl.trim();
  if (!value || value.length > 2048) {
    throw new Error('更新地址无效');
  }

  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new Error('更新地址格式无效');
  }

  if (!url.hostname || url.username || url.password || url.hash) {
    throw new Error('更新地址包含不安全的凭据或片段');
  }

  const isHttps = url.protocol === 'https:';
  const isDevelopmentHttp =
    env.isDevelopment() && url.protocol === 'http:' && isLoopbackHost(url.hostname);
  if (!isHttps && !isDevelopmentHttp) {
    throw new Error('更新地址必须使用 HTTPS');
  }

  return url.toString();
}

export async function getUpdateJson<T>(
  path: string,
  params: Record<string, string | number>,
): Promise<T> {
  const baseUrl = getSafeUpdateUrl(env.getApiBaseUrl());
  const normalizedPath = path.trim();
  if (!normalizedPath.startsWith('/') || normalizedPath.includes('://') || normalizedPath.includes('#')) {
    throw new Error('更新接口路径无效');
  }

  const requestUrl = new URL(
    normalizedPath.replace(/^\/+/, ''),
    baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`,
  );
  Object.entries(params).forEach(([key, value]) => {
    requestUrl.searchParams.set(key, String(value));
  });

  const controller = new AbortController();
  const timeoutMs = Math.max(1000, env.getApiTimeout());
  const timeoutId = setTimeout(() => controller.abort(), timeoutMs);

  let response: Response;
  try {
    response = await fetch(requestUrl.toString(), {
      headers: { Accept: 'application/json' },
      signal: controller.signal,
    });
  } catch {
    if (controller.signal.aborted) {
      throw new Error('更新服务请求超时，请稍后重试');
    }
    throw new Error('更新服务暂时不可用，请检查网络后重试');
  } finally {
    clearTimeout(timeoutId);
  }

  if (!response.ok) {
    throw new Error(`更新服务请求失败: HTTP ${response.status}`);
  }

  let body: unknown;
  try {
    body = await response.json();
  } catch {
    throw new Error('更新服务返回格式无效');
  }

  if (body && typeof body === 'object' && 'data' in body && body.data !== undefined) {
    return body.data as T;
  }
  return body as T;
}

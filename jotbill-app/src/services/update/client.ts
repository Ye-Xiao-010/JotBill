import env from '../../config/env';
import RNFS from 'react-native-fs';

/**
 * 更新服务专用的只读 HTTP 客户端，不携带账本或用户认证信息。
 */
function isLoopbackHost(hostname: string): boolean {
  const normalized = hostname.toLowerCase().replace(/^\[|\]$/g, '');
  return normalized === 'localhost' || normalized === '127.0.0.1' || normalized === '::1';
}

function decodePath(path: string): string {
  let decoded = path;
  try {
    for (let index = 0; index < 3; index += 1) {
      const next = decodeURIComponent(decoded);
      if (next === decoded) return decoded;
      decoded = next;
    }
    return decoded;
  } catch {
    throw new Error('更新地址路径编码无效');
  }
}

function hasUnsafePath(path: string): boolean {
  if (/[\u0000-\u001f\\?#]/.test(path)) return true;

  const credentialSegment = /(?:^|[-_.])(?:api[-_]?key|apikey|access[-_]?token|authorization|auth|token|secret|password|credential|signature|sig)(?:$|[-_.])/i;
  return path.split('/').some(segment =>
    segment === '.' || segment === '..' || credentialSegment.test(segment),
  );
}

const BASE64_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

function encodeBase64(bytes: Uint8Array): string {
  let encoded = '';
  for (let index = 0; index < bytes.length; index += 3) {
    const first = bytes[index];
    const second = index + 1 < bytes.length ? bytes[index + 1] : 0;
    const third = index + 2 < bytes.length ? bytes[index + 2] : 0;
    const remaining = bytes.length - index;

    encoded += BASE64_ALPHABET[first >> 2];
    encoded += BASE64_ALPHABET[((first & 0x03) << 4) | (second >> 4)];
    encoded += remaining > 1
      ? BASE64_ALPHABET[((second & 0x0f) << 2) | (third >> 6)]
      : '=';
    encoded += remaining > 2 ? BASE64_ALPHABET[third & 0x3f] : '=';
  }
  return encoded;
}

let stagingSequence = 0;

function createStagingPath(filePath: string): string {
  stagingSequence = (stagingSequence + 1) % 1000000;
  return `${filePath}.part-${Date.now()}-${stagingSequence}`;
}

async function cleanupStagingFile(filePath: string): Promise<void> {
  try {
    if (await RNFS.exists(filePath)) {
      await RNFS.unlink(filePath);
    }
  } catch {
    console.warn('[Update] 下载临时文件清理失败');
  }
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

  if (!url.hostname || url.username || url.password || url.hash || url.search || value.includes('#')) {
    throw new Error('更新地址包含不安全的凭据或片段');
  }
  if (value.includes('?') || hasUnsafePath(decodePath(url.pathname))) {
    throw new Error('更新地址包含不安全的查询或路径');
  }

  const isHttps = url.protocol === 'https:';
  const isDevelopmentHttp =
    env.isDevelopment() && url.protocol === 'http:' && isLoopbackHost(url.hostname);
  if (!isHttps && !isDevelopmentHttp) {
    throw new Error('更新地址必须使用 HTTPS');
  }

  return url.toString();
}

/** 使用拒绝重定向的响应下载更新文件，并在写入前校验最终地址。 */
export async function downloadUpdateFile(
  rawUrl: string,
  filePath: string,
  onProgress?: (progress: number) => void,
  timeoutMs = 60000,
): Promise<void> {
  const safeUrl = getSafeUpdateUrl(rawUrl);
  const controller = new AbortController();
  let didTimeout = false;
  let rejectTimeout: () => void = () => undefined;
  const timeoutPromise = new Promise<never>((_, reject) => {
    rejectTimeout = () => reject(new Error('更新下载超时，请稍后重试'));
  });
  const timeoutId = setTimeout(() => {
    didTimeout = true;
    controller.abort();
    rejectTimeout();
  }, Math.max(1000, timeoutMs));
  let stagingPath: string | null = null;

  try {
    let response: Response;
    try {
      response = await Promise.race([
        fetch(safeUrl, {
          redirect: 'error',
          signal: controller.signal,
        }),
        timeoutPromise,
      ]);
    } catch {
      if (didTimeout) {
        throw new Error('更新下载超时，请稍后重试');
      }
      throw new Error('更新下载暂时不可用，请检查网络后重试');
    }

    if (response.status >= 300 && response.status < 400) {
      throw new Error('更新下载不允许重定向');
    }
    if (!response.ok) {
      throw new Error(`更新下载失败: HTTP ${response.status}`);
    }

    // redirect:error 已阻止自动跳转；仍校验运行时暴露的最终地址，防止平台实现绕过策略。
    if (response.url) {
      const finalUrl = getSafeUpdateUrl(response.url);
      if (finalUrl !== safeUrl) {
        throw new Error('更新下载不允许重定向');
      }
    }

    onProgress?.(0);
    let body: ArrayBuffer;
    try {
      body = await Promise.race([response.arrayBuffer(), timeoutPromise]);
    } catch {
      if (didTimeout) {
        throw new Error('更新下载超时，请稍后重试');
      }
      throw new Error('更新下载内容无效');
    }

    stagingPath = createStagingPath(filePath);
    try {
      // 写入必须等到底层 Promise settle，超时只记录状态，不能提前释放调用方的互斥。
      await RNFS.writeFile(stagingPath, encodeBase64(new Uint8Array(body)), 'base64');
    } catch {
      if (didTimeout) {
        throw new Error('更新下载超时，请稍后重试');
      }
      throw new Error('更新下载文件写入失败');
    }

    if (didTimeout) {
      throw new Error('更新下载超时，请稍后重试');
    }

    // 写入完成后才提交到目标路径，避免清理目录时碰到后台写入。
    clearTimeout(timeoutId);
    try {
      await RNFS.moveFile(stagingPath, filePath);
    } catch {
      throw new Error('更新下载文件提交失败');
    }
    if (await RNFS.exists(stagingPath)) {
      throw new Error('更新下载临时文件提交不完整');
    }
    stagingPath = null;
    onProgress?.(100);
  } finally {
    clearTimeout(timeoutId);
    if (stagingPath) {
      await cleanupStagingFile(stagingPath);
    }
  }
}

export async function getUpdateJson<T>(
  path: string,
  params: Record<string, string | number>,
): Promise<T> {
  const baseUrl = getSafeUpdateUrl(env.getApiBaseUrl());
  const normalizedPath = path.trim();
  if (
    !normalizedPath.startsWith('/') ||
    normalizedPath.includes('://') ||
    normalizedPath.includes('#') ||
    normalizedPath.includes('?') ||
    normalizedPath.includes('\\')
  ) {
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
  let didTimeout = false;
  let rejectTimeout: () => void = () => undefined;
  const timeoutPromise = new Promise<never>((_, reject) => {
    rejectTimeout = () => reject(new Error('更新服务请求超时，请稍后重试'));
  });
  const timeoutId = setTimeout(() => {
    didTimeout = true;
    controller.abort();
    rejectTimeout();
  }, timeoutMs);

  try {
    let response: Response;
    try {
      response = await Promise.race([
        fetch(requestUrl.toString(), {
          headers: { Accept: 'application/json' },
          redirect: 'error',
          signal: controller.signal,
        }),
        timeoutPromise,
      ]);
    } catch {
      if (didTimeout) {
        throw new Error('更新服务请求超时，请稍后重试');
      }
      throw new Error('更新服务暂时不可用，请检查网络后重试');
    }

    if (!response.ok) {
      if (response.status >= 300 && response.status < 400) {
        throw new Error('更新服务不允许重定向');
      }
      throw new Error(`更新服务请求失败: HTTP ${response.status}`);
    }

    if (response.url) {
      const finalUrl = new URL(response.url);
      const requestedUrl = new URL(requestUrl.toString());
      if (
        finalUrl.origin !== requestedUrl.origin ||
        finalUrl.pathname !== requestedUrl.pathname ||
        finalUrl.search !== requestedUrl.search ||
        finalUrl.hash !== requestedUrl.hash
      ) {
        throw new Error('更新服务不允许重定向');
      }
    }

    let body: unknown;
    try {
      body = await Promise.race([response.json(), timeoutPromise]);
    } catch {
      if (didTimeout) {
        throw new Error('更新服务请求超时，请稍后重试');
      }
      throw new Error('更新服务返回格式无效');
    }

    if (body && typeof body === 'object' && 'data' in body && body.data !== undefined) {
      return body.data as T;
    }
    return body as T;
  } finally {
    clearTimeout(timeoutId);
  }
}

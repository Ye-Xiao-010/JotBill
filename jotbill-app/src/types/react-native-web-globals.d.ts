/**
 * RN 0.81 的全局声明（node_modules/react-native/src/types/globals.d.ts）
 * 只提供了 URL / URLSearchParams / RequestInit 的最小类型，
 * 但运行时 whatwg URL polyfill 支持标准浏览器 API。
 * 本文件只补齐类型，不引入运行时代码。
 */
declare global {
  interface URL {
    hostname: string;
    username: string;
    password: string;
    hash: string;
    search: string;
    pathname: string;
    protocol: string;
    origin: string;
  }

  interface URLSearchParams {
    set(key: string, value: string): void;
  }

  interface RequestInit {
    redirect?: 'error' | 'follow' | 'manual';
  }
}

export {};

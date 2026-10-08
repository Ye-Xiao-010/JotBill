/**
 * useChatSessions - AI 对话历史列表
 *
 * ⚠️ 此前有两处各自实现 `getChatSessions({ limit: 50 })`，且都有同一个缺陷：
 *
 * 1. **第 51 条之后的会话永久不可达。** 原生 `AiChatModule.getChatSessions`
 *    本来就支持 `page/limit`（limit 上限 100），但两个调用点都写死单页 50，
 *    且不读 total、不分页、不提示。对话会话只增不减，一个用了半年的用户
 *    最早的那些对话就再也找不到了 —— 数据还在，但界面无法抵达。
 * 2. **加载失败被渲染成"还没有对话记录"。** 两处 `catch` 都只
 *    `console.error`，`sessions` 停在 `[]` ⇒ 空态文案是"还没有对话记录，
 *    点击右上角开始新对话"。用户会以为聊天记录丢了。
 *    这条尤其恶劣：数据管理的"清除本地数据"文案里明确写了会清掉 AI 对话
 *    历史，于是"读失败"和"真被清了"在界面上是同一句话，无法区分。
 *
 * 修：分页（按需加载）+ 显式错误态 + 保留 `setSessions` 供本地增量修改
 * （置顶/重命名/删除后的乐观更新在 AIChatScreen 侧是命令式的，不走缓存）。
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { aiService } from '../services/ai';
import type { ChatSession } from '../types/ai';

/** 与原生 `limit.coerceIn(1, 100)` 的上限保持一致。 */
const PAGE_SIZE = 50;

export interface UseChatSessionsResult {
  sessions: ChatSession[];
  /** 首屏加载中（用于整页 loading 态）。 */
  isLoading: boolean;
  /** 正在加载下一页。 */
  isLoadingMore: boolean;
  /** 是否有下一页（上一页返回条数不足 PAGE_SIZE 即为末页）。 */
  hasMore: boolean;
  /** 首屏加载失败。null 表示成功。 */
  error: unknown;
  /** 加载下一页（滚动到底触发）。 */
  loadMore: () => void;
  /** 重新拉首屏（刷新/重试）。 */
  reload: () => void;
  /** 本地增量修改入口（置顶/重命名/删除后的乐观更新）。 */
  setSessions: React.Dispatch<React.SetStateAction<ChatSession[]>>;
}

export function useChatSessions(options?: {
  /**
   * 是否在挂载时自动拉首屏。
   * false = 只在调用方显式 `reload()` 时拉（AIChatScreen 的侧边栏按需打开才加载，
   * 不该在聊天页一进来就发一次请求）。
   *
   * 注意：只影响**自动**加载，`reload()` / `loadMore()` 任何时候都能用。
   */
  autoLoad?: boolean;
}): UseChatSessionsResult {
  const autoLoad = options?.autoLoad ?? true;

  const [sessions, setSessions] = useState<ChatSession[]>([]);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(true);
  const [isLoading, setIsLoading] = useState(false);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [error, setError] = useState<unknown>(null);

  // 并发守卫：FlatList 的 onEndReached 可能连发，快速翻页会并发请求同一页
  const loadingRef = useRef(false);
  // 组件卸载后不再 setState（AIChatScreen 侧边栏可随时关闭）
  const aliveRef = useRef(true);
  useEffect(() => {
    aliveRef.current = true;
    return () => {
      aliveRef.current = false;
    };
  }, []);

  /** 拉取第 1 页并整体替换。 */
  const reload = useCallback(async () => {
    if (loadingRef.current) return;
    loadingRef.current = true;
    setIsLoading(true);
    setError(null);
    try {
      const response = await aiService.getChatSessions({ page: 1, limit: PAGE_SIZE });
      if (!aliveRef.current) return;
      if (response.success && response.data) {
        setSessions(response.data);
        setPage(1);
        // 不足一页即末页。原生不返回 total，用"短页"判定——
        // 少拉一次请求，换来不必改 Native.d.ts 契约。
        setHasMore(response.data.length >= PAGE_SIZE);
      } else {
        setHasMore(false);
      }
    } catch (e) {
      if (!aliveRef.current) return;
      setError(e);
      setHasMore(false);
    } finally {
      loadingRef.current = false;
      if (aliveRef.current) setIsLoading(false);
    }
  }, []);

  /** 追加下一页。 */
  const loadMore = useCallback(async () => {
    if (!hasMore || loadingRef.current) return;
    loadingRef.current = true;
    setIsLoadingMore(true);
    const nextPage = page + 1;
    try {
      const response = await aiService.getChatSessions({ page: nextPage, limit: PAGE_SIZE });
      if (!aliveRef.current) return;
      if (response.success && response.data) {
        // 去重：排序/置顶在翻页期间可能变化，同一条可能跨页重复
        setSessions(prev => {
          const seen = new Set(prev.map(s => s.id));
          const fresh = response.data!.filter(s => !seen.has(s.id));
          return fresh.length > 0 ? [...prev, ...fresh] : prev;
        });
        setPage(nextPage);
        if (response.data.length < PAGE_SIZE) setHasMore(false);
      } else {
        setHasMore(false);
      }
    } catch (e) {
      // 翻页失败**不置 error**：首屏数据仍然有效可读，只是没有更多。
      // 用 hasMore=false 停止继续尝试，让用户手动下拉刷新重来。
      if (aliveRef.current) setHasMore(false);
    } finally {
      loadingRef.current = false;
      if (aliveRef.current) setIsLoadingMore(false);
    }
  }, [hasMore, page]);

  useEffect(() => {
    if (autoLoad) {
      void reload();
    }
  }, [autoLoad, reload]);

  return {
    sessions,
    isLoading,
    isLoadingMore,
    hasMore,
    error,
    loadMore: () => {
      void loadMore();
    },
    reload: () => {
      void reload();
    },
    setSessions,
  };
}
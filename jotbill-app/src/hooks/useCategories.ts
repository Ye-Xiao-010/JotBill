/**
 * 分类数据 Hook
 * 使用 React Query 管理分类数据的获取和缓存
 */
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { nativeData, type Category } from '../native';
import { CACHE_TIME, QUERY_KEYS, GC_TIME } from '../lib/queryClient';

/**
 * 获取分类列表
 * @param type 分类类型（income/expense）
 * @param enabled 是否启用查询
 */
export function useCategories(type: Category['type'], enabled: boolean = true) {
  const query = useQuery({
    queryKey: QUERY_KEYS.categories.byType(type),
    queryFn: (): Promise<Category[]> => nativeData.getCategories(type),
    enabled,
    staleTime: CACHE_TIME.CATEGORIES,
    gcTime: GC_TIME.DEFAULT,
    // 切换收入/支出时保留上一批分类直到新数据到达：
    // 否则 categories 瞬间变空，分类网格塌陷再重排，页面切换会明显卡一下
    placeholderData: keepPreviousData,
  });

  return {
    categories: query.data || [],
    isLoading: query.isLoading,
    isFetching: query.isFetching,
    isError: query.isError,
    error: query.error,
    refetch: query.refetch,
  };
}

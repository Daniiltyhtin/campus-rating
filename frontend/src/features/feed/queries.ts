import { useTimedInfiniteQuery } from '../../app/infiniteTiming';
import { useQuery } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import type { LoadState } from '../../app/layouts/PageLayouts';
import { parseAwardsPage, parseGroupRating, parsePersonalPage } from './contracts';
import type { Currency } from './contracts';

export const awardsKey = (viewerId: number, mine = false) => (mine ? ['awards', viewerId, 'list', 'mine'] : ['awards', viewerId, 'list']) as readonly unknown[];
async function read<T>(pending: Promise<{ response: Response; data?: unknown }>, parse: (value: unknown) => T) {
  const { response, data } = await pending;
  if (!response.ok) throw new Error('Не удалось загрузить данные');
  return parse(data);
}
export function feedError(error: unknown) {
  if (error instanceof ApiError) {
    if (error.code === 'FORBIDDEN' || error.code === 'GROUP_NOT_ACCESSIBLE') return 'Нет доступа';
    if (error.code === 'NOT_FOUND') return 'Группа не найдена';
  }
  return 'Не удалось загрузить данные. Проверьте соединение и повторите.';
}
export function feedState(query: { isPending: boolean; isError: boolean; error: unknown; refetch: () => Promise<unknown> }, empty: boolean, message: string): LoadState {
  if (query.isPending) return { status: 'loading' };
  if (query.isError) return { status: 'error', message: feedError(query.error), retry: () => { void query.refetch(); } };
  return empty ? { status: 'empty', message } : { status: 'ready' };
}
function readAwards(signal: AbortSignal, cursor?: string, mine = false) {
  return read(api.GET('/api/v1/feed/awards', { params: { query: { cursor, mine: mine || undefined } }, signal }), parseAwardsPage);
}
export function useAwards(viewerId: number, mine = false) {
  return useTimedInfiniteQuery({ queryKey: awardsKey(viewerId, mine), initialPageParam: null as string | null, retry: false,
    staleTime: Infinity, refetchOnWindowFocus: false, refetchOnReconnect: false,
    queryFn: ({ signal, pageParam }) => readAwards(signal, pageParam ?? undefined, mine),
    getNextPageParam: (last, _pages, _param, params) => last.nextCursor && !params.includes(last.nextCursor) ? last.nextCursor : undefined });
}
export function useAwardsPoll(viewerId: number, enabled: boolean, mine = false) {
  return useQuery({ queryKey: mine ? ['awards', viewerId, 'poll', 'mine'] : ['awards', viewerId, 'poll'], enabled, retry: false, refetchInterval: 30_000,
    refetchOnWindowFocus: true, queryFn: ({ signal }) => readAwards(signal, undefined, mine) });
}
export function useGroupRating(viewerId: number, groupId: number, sort: Currency) {
  return useQuery({ queryKey: ['group-rating', viewerId, groupId, sort], retry: false, queryFn: async ({ signal }) => {
    const rating = await read(api.GET('/api/v1/groups/{groupId}/ratings', { params: { path: { groupId }, query: { sort } }, signal }), parseGroupRating);
    if (rating.group.id !== groupId || rating.sort !== sort) throw new Error('Ответ рейтинга не соответствует запросу');
    return rating;
  } });
}
export function usePersonalFeed(viewerId: number) {
  return useTimedInfiniteQuery({ queryKey: ['personal-feed', viewerId], initialPageParam: null as string | null, retry: false,
    queryFn: ({ signal, pageParam }) => read(api.GET('/api/v1/feed/personal', { params: { query: { cursor: pageParam ?? undefined } }, signal }), parsePersonalPage),
    getNextPageParam: (last, _pages, _param, params) => last.nextCursor && !params.includes(last.nextCursor) ? last.nextCursor : undefined });
}

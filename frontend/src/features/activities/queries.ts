import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { api } from '../../api/client';
import { parseActivity, parseActivityList, parseAwardPage, parseBudgets, parseGroups, parseParticipants, parsePreview, parseSummary } from './contracts';
import type { ParticipationRequest } from './contracts';
import type { LoadState } from '../../app/layouts/PageLayouts';
import { activityError } from './errors';

export const activityKeys = { root: ['activities'] as const };
export async function read<T>(pending: Promise<{ response: Response; data?: unknown }>, parse: (value: unknown) => T): Promise<T> {
  const { response, data } = await pending;
  if (!response.ok) throw new Error('Не удалось выполнить запрос активностей');
  return parse(data);
}
export function useActivities(userId: number) {
  return useQuery({ queryKey: ['activities', userId, 'list'], queryFn: ({ signal }) => read(api.GET('/api/v1/activities', { signal }), parseActivityList), retry: false });
}
export function useActivity(userId: number, activityId: number) {
  return useQuery({ queryKey: ['activities', userId, 'detail', activityId], queryFn: ({ signal }) => read(api.GET('/api/v1/activities/{activityId}', { params: { path: { activityId } }, signal }), parseActivity), retry: false });
}
export function useAccessibleGroups(userId: number) {
  return useQuery({ queryKey: ['activities', userId, 'groups'], queryFn: ({ signal }) => read(api.GET('/api/v1/me/accessible-groups', { signal }), parseGroups), retry: false });
}
export function useParticipants(userId: number, activityId: number) {
  return useQuery({ queryKey: ['activities', userId, 'participants', activityId], queryFn: ({ signal }) => read(api.GET('/api/v1/activities/{activityId}/participants', { params: { path: { activityId } }, signal }), parseParticipants), retry: false });
}
export function useParticipationPreview(userId: number, activityId: number, body: ParticipationRequest, enabled: boolean) {
  return useQuery({ queryKey: ['activities', userId, 'preview', activityId, body], enabled,
    queryFn: ({ signal }) => read(api.POST('/api/v1/activities/{activityId}/participation/preview', { params: { path: { activityId } }, body, signal }), parsePreview), retry: false });
}
export function useBudgets(userId: number, month: string) {
  return useQuery({ queryKey: ['activities', userId, 'budgets', month], queryFn: ({ signal }) => read(api.GET('/api/v1/budgets', { params: { query: { month: month || undefined } }, signal }), parseBudgets), retry: false });
}
export function useAwardSummary(userId: number, month: string) {
  return useQuery({ queryKey: ['activities', userId, 'summary', month], enabled: month !== '', queryFn: ({ signal }) => read(api.GET('/api/v1/me/awards/summary', { params: { query: { month } }, signal }), parseSummary), retry: false });
}
export function useAwardHistory(userId: number, month: string, groupId: string) {
  return useInfiniteQuery({ queryKey: ['activities', userId, 'awards', month, groupId], enabled: month !== '', initialPageParam: null as string | null,
    queryFn: ({ pageParam, signal }) => read(api.GET('/api/v1/me/awards', { params: { query: { month, groupId: groupId ? Number(groupId) : undefined, cursor: pageParam ?? undefined } }, signal }), parseAwardPage),
    getNextPageParam: (last) => last.nextCursor ?? undefined, retry: false });
}
export function loadState(query: { isPending: boolean; isError: boolean; error: unknown; refetch: () => Promise<unknown> }, empty = false, message = 'Данных пока нет'): LoadState {
  if (query.isError) return { status: 'error', message: activityError(query.error), retry: () => { void query.refetch(); } };
  if (query.isPending) return { status: 'loading' };
  return empty ? { status: 'empty', message } : { status: 'ready' };
}

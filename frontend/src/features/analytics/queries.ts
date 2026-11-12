import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { api } from '../../api/client';
import { parseAnalytics, parseBudget } from './contracts';

export function useAnalytics(viewerId: number, groupId: number, from: string, to: string) {
  return useQuery({ queryKey: ['analytics', viewerId, groupId, from, to], retry: false, placeholderData: keepPreviousData,
    queryFn: async ({ signal }) => {
      const { data } = await api.GET('/api/v1/groups/{groupId}/analytics', { params: { path: { groupId }, query: { from, to } }, signal });
      const result = parseAnalytics(data);
      if (result.group.id !== groupId) throw new Error('Ответ аналитики не соответствует запросу');
      return result;
    } });
}
export function useAnalyticsBudget(viewerId: number, groupId: number) {
  return useQuery({ queryKey: ['analytics-budget', viewerId, groupId], retry: false,
    queryFn: async ({ signal }) => {
      const { data } = await api.GET('/api/v1/groups/{groupId}/analytics/budget', { params: { path: { groupId } }, signal });
      return parseBudget(data);
    } });
}

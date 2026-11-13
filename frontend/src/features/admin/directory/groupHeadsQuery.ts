import { useQuery } from '@tanstack/react-query';
import { api } from '../../../api/client';
import { dataOf } from '../../tasks/helpers';

export const headsKey = ['admin', 'group-heads'] as const;

export function useGroupHeads(enabled: boolean) {
  return useQuery({ queryKey: headsKey, enabled, queryFn: () => dataOf(api.GET('/api/v1/admin/group-heads')) });
}

import { useTimedMutation } from '../../app/actionTiming';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import { dataOf } from '../tasks/helpers';

export const membershipKeys = { pending: ['memberships', 'pending'] as const };

export function usePendingMemberships(enabled = true) {
  return useQuery({ queryKey: membershipKeys.pending, enabled, queryFn: () => dataOf(api.GET('/api/v1/memberships/pending')) });
}

async function decide(studentId: number, decision: 'approval' | 'rejection') {
  const result = decision === 'approval'
    ? await api.POST('/api/v1/memberships/{studentId}/approval', { params: { path: { studentId } } })
    : await api.POST('/api/v1/memberships/{studentId}/rejection', { params: { path: { studentId } } });
  if (result.error) throw result.error;
  if (!result.response.ok) throw new Error('Не удалось сохранить решение');
}

export function useMembershipDecision() {
  const client = useQueryClient();
  return useTimedMutation({
    mutationFn: ({ studentId, decision }: { studentId: number; decision: 'approval' | 'rejection' }) => decide(studentId, decision),
    onSettled: () => client.invalidateQueries({ queryKey: membershipKeys.pending }),
  });
}

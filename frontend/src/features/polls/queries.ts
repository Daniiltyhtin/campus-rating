import { useQuery } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import type { components } from '../../api/schema';
import { read } from '../activities/queries';

type Schemas = components['schemas'];
export type Poll = Schemas['Poll'];
export type PollSummary = Schemas['PollSummary'];
export type PollResults = Schemas['PollResults'];
export type PollResponse = Schemas['PollResponse'];
export type PollCreate = Schemas['PollCreate'];

export const pollKeys = { root: ['polls'] as const };
const as = <T,>(value: unknown) => value as T;

export function usePolls(userId: number) {
  return useQuery({ queryKey: ['polls', userId, 'list'], retry: false,
    queryFn: ({ signal }) => read(api.GET('/api/v1/polls', { signal }), as<Schemas['PollList']>) });
}
export function usePoll(userId: number, pollId: number) {
  return useQuery({ queryKey: ['polls', userId, 'detail', pollId], retry: false,
    queryFn: ({ signal }) => read(api.GET('/api/v1/polls/{pollId}', { params: { path: { pollId } }, signal }), as<Poll>) });
}
export function usePollResponses(userId: number, pollId: number, enabled: boolean) {
  return useQuery({ queryKey: ['polls', userId, 'responses', pollId], enabled, retry: false,
    queryFn: ({ signal }) => read(api.GET('/api/v1/polls/{pollId}/responses', { params: { path: { pollId } }, signal }), as<Schemas['PollResponseList']>) });
}
export function createPoll(body: PollCreate) {
  return read(api.POST('/api/v1/polls', { body }), as<Poll>);
}
export function votePoll(pollId: number, body: Schemas['PollVote']) {
  return read(api.POST('/api/v1/polls/{pollId}/vote', { params: { path: { pollId } }, body }), as<PollResults>);
}

export function pollError(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Не удалось загрузить данные. Проверьте соединение и повторите.';
  switch (error.code) {
    case 'ALREADY_VOTED': return 'Вы уже проголосовали в этом опросе.';
    case 'POLL_CLOSED': return 'Опрос завершён: голосовать больше нельзя.';
    case 'MEMBERSHIP_NOT_ACTIVE': return 'Голосовать можно после подтверждения членства.';
    case 'GROUP_NOT_ACCESSIBLE': return 'Одна из групп больше недоступна. Обновите список групп.';
    case 'FORBIDDEN': return 'Нет доступа';
    case 'NOT_FOUND': return 'Опрос не найден';
    case 'VALIDATION_FAILED': return 'Проверьте поля формы';
    case 'CSRF_INVALID': return 'Защита формы устарела. Обновите страницу и повторите действие.';
    default: return 'Не удалось выполнить действие. Попробуйте снова.';
  }
}

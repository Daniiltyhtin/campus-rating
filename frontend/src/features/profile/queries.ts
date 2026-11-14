import { useTimedInfiniteQuery } from '../../app/infiniteTiming';
import { useQuery } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import { parseLedgerPage, parseProfile } from './contracts';
import type { Currency } from './contracts';

export async function readProfileResponse<T>(pending: Promise<{ response: Response; data?: unknown }>, parse: (data: unknown) => T): Promise<T> {
  const result = await pending;
  if (!result.response.ok) throw new Error('Не удалось загрузить профиль или историю');
  return parse(result.data);
}
export function profileError(error: unknown) {
  if (error instanceof ApiError) {
    if (error.code === 'NOT_FOUND') return 'Пользователь не найден';
    if (error.code === 'FORBIDDEN') return 'Нет доступа';
  }
  return 'Не удалось загрузить данные. Проверьте соединение и повторите.';
}
export function useProfile(viewerId: number, userId: number) {
  return useQuery({ queryKey: ['profile', viewerId, userId], retry: false, queryFn: async ({ signal }) => {
    const profile = await readProfileResponse(api.GET('/api/v1/users/{userId}/profile', { params: { path: { userId } }, signal }), parseProfile);
    if (profile.id !== userId) throw new Error('Профиль не соответствует адресу');
    return profile;
  } });
}
export function useLedger(viewerId: number, userId: number, currency: Currency | '') {
  return useTimedInfiniteQuery({ queryKey: ['profile-ledger', viewerId, userId, currency], retry: false, initialPageParam: null as string | null,
    queryFn: ({ signal, pageParam }) => readProfileResponse(api.GET('/api/v1/ledger/{userId}/entries', { params: { path: { userId }, query: { currency: currency || undefined, cursor: pageParam ?? undefined } }, signal }), parseLedgerPage),
    getNextPageParam: (last) => last.nextCursor ?? undefined });
}

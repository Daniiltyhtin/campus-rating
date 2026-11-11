import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import type { QueryClient } from '@tanstack/react-query';
import { authConfig, currentUser, options } from './contracts';

export const authKeys = { user: ['auth', 'me'], csrf: ['auth', 'csrf'] };

const generations = new WeakMap<QueryClient, number>();
export function sessionGeneration(client: QueryClient) { return generations.get(client) ?? 0; }
export function advanceSession(client: QueryClient) {
  const generation = sessionGeneration(client) + 1;
  generations.set(client, generation);
  return generation;
}
export async function clearPrivateQueries(client: QueryClient) {
  const filter = { predicate: (query: { queryKey: readonly unknown[] }) => query.queryKey[0] !== 'auth' };
  await client.cancelQueries(filter);
  client.removeQueries(filter);
}
export async function csrfToken() {
  const { response } = await api.GET('/api/v1/auth/csrf');
  if (!response.ok) throw new Error('Не удалось обновить защиту формы');
  return true;
}
export function useAuthSession() {
  const client = useQueryClient();
  const csrf = useQuery({ queryKey: authKeys.csrf, queryFn: csrfToken, staleTime: Infinity, retry: false });
  const session = useQuery({ queryKey: authKeys.user, queryFn: async ({ signal }) => {
    const generation = sessionGeneration(client);
    try {
      const { data, response } = await api.GET('/api/v1/auth/me', { signal });
      if (!response.ok) throw new Error('Не удалось проверить сессию');
      const user = currentUser(data);
      if (signal.aborted || generation !== sessionGeneration(client)) return client.getQueryData<ReturnType<typeof currentUser> | null>(authKeys.user) ?? null;
      const previous = client.getQueryData<ReturnType<typeof currentUser> | null>(authKeys.user);
      if (previous && previous.id !== user.id) await clearPrivateQueries(client);
      return user;
    } catch (error) {
      if (error instanceof ApiError && error.code === 'UNAUTHENTICATED') {
        if (signal.aborted || generation !== sessionGeneration(client)) return client.getQueryData<ReturnType<typeof currentUser> | null>(authKeys.user) ?? null;
        await clearPrivateQueries(client);
        return null;
      }
      throw error;
    }
  }, staleTime: 30_000, retry: false });
  return { csrf, session };
}
export function useAuthConfig() {
  return useQuery({ queryKey: ['auth', 'config'], queryFn: async () => {
    const { data, response } = await api.GET('/api/v1/auth/config');
    if (!response.ok) throw new Error('Не удалось загрузить настройки');
    return authConfig(data);
  }, staleTime: 30_000, retry: false });
}
export function useFaculties() {
  return useQuery({ queryKey: ['directory', 'faculties'], queryFn: async () => {
    const { data, response } = await api.GET('/api/v1/directory/faculties');
    if (!response.ok) throw new Error('Не удалось загрузить факультеты');
    return options(data);
  }, retry: false });
}
export function useFacultyOptions(facultyId: string, role: 'STUDENT' | 'TEACHER') {
  return useQuery({ queryKey: ['directory', facultyId, role], enabled: facultyId !== '', queryFn: async () => {
    const params = { path: { facultyId: Number(facultyId) } };
    const result = role === 'STUDENT'
      ? await api.GET('/api/v1/directory/faculties/{facultyId}/groups', { params })
      : await api.GET('/api/v1/directory/faculties/{facultyId}/departments', { params });
    if (!result.response.ok) throw new Error('Не удалось загрузить справочник');
    return options(result.data);
  }, retry: false });
}

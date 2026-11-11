import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import { authConfig, currentUser, options } from './contracts';

export const authKeys = { user: ['auth', 'me'], csrf: ['auth', 'csrf'] };

export async function csrfToken() {
  const { response } = await api.GET('/api/v1/auth/csrf');
  if (!response.ok) throw new Error('Не удалось обновить защиту формы');
  return true;
}
export function useAuthSession() {
  const client = useQueryClient();
  const csrf = useQuery({ queryKey: authKeys.csrf, queryFn: csrfToken, staleTime: Infinity, retry: false });
  const session = useQuery({ queryKey: authKeys.user, queryFn: async () => {
    try {
      const { data, response } = await api.GET('/api/v1/auth/me');
      if (!response.ok) throw new Error('Не удалось проверить сессию');
      return currentUser(data);
    } catch (error) {
      if (error instanceof ApiError && error.code === 'UNAUTHENTICATED') {
        client.removeQueries({ predicate: (query) => query.queryKey[0] !== 'auth' });
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

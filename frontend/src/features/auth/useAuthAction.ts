import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../../api/client';
import { authKeys, csrfToken } from './authQueries';
import { currentUser } from './contracts';
import { fieldErrors } from './formErrors';
import type { FormErrors } from './formErrors';

export function useAuthAction<T>(submit: (value: T) => Promise<void>, kind: 'login' | 'password' | 'logout') {
  const client = useQueryClient();
  const [needsRefresh, setNeedsRefresh] = useState(false);
  const finish = async () => {
    const ready = kind === 'logout'
      ? await client.fetchQuery({ queryKey: authKeys.csrf, queryFn: csrfToken, staleTime: 0, retry: false })
      : await csrfToken();
    client.setQueryData(authKeys.csrf, ready);
    if (kind !== 'logout') {
      const { data, response } = await api.GET('/api/v1/auth/me');
      if (!response.ok) throw new Error('Не удалось проверить сессию');
      client.setQueryData(authKeys.user, currentUser(data));
    }
    setNeedsRefresh(false);
  };
  const mutation = useMutation({ retry: false, mutationFn: async (value: T) => {
    await submit(value);
    setNeedsRefresh(true);
    if (kind === 'logout') {
      await client.cancelQueries();
      client.removeQueries({ predicate: (query) => query.queryKey[0] !== 'auth' });
      client.setQueryData(authKeys.csrf, false);
      client.setQueryData(authKeys.user, null);
    } else if (kind === 'login') {
      client.removeQueries({ predicate: (query) => query.queryKey[0] !== 'auth' && query.queryKey[0] !== 'directory' });
    }
    await finish();
  } });
  const refresh = useMutation({ mutationFn: finish, retry: false });
  return { ...mutation, needsRefresh, refresh: () => refresh.mutate(), refreshSuccess: refresh.isSuccess,
    refreshing: refresh.isPending, error: refresh.error ?? mutation.error };
}

export function useFormErrors() {
  const [errors, setErrors] = useState<FormErrors>({});
  return { errors, setErrors, clear: (field: string) => setErrors((previous) => {
    const next = { ...previous }; delete next[field]; return next;
  }), fromServer: (error: unknown) => setErrors(fieldErrors(error)) };
}

export async function requireSuccess(result: { response: Response }) {
  if (!result.response.ok) throw new Error('Не удалось выполнить действие');
}

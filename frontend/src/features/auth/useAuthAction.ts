import { waitForLoaderResult, useLoaderTiming } from '../../app/loaderTiming';
import { useTimedMutation } from '../../app/actionTiming';
import { useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { api } from '../../api/client';
import { advanceSession, authKeys, clearPrivateQueries, csrfToken, sessionGeneration } from './authQueries';
import { currentUser } from './contracts';
import { fieldErrors } from './formErrors';
import type { FormErrors } from './formErrors';

export function useAuthAction<T = void>(submit: (value: T) => Promise<void>, kind: 'login' | 'password' | 'logout') {
  const client = useQueryClient();
  const [needsRefresh, setNeedsRefresh] = useState(false);
  const [refreshState, setRefreshState] = useState<'idle' | 'pending' | 'error'>('idle');
  const startedAt = useRef(0);
  const refreshTiming = useLoaderTiming(refreshState === 'pending');
  const actionGeneration = useRef(0);
  const finish = async (generation: number) => {
    if (generation !== sessionGeneration(client)) return;
    const refreshStartedAt = Date.now();
    const waitForPublication = () => Promise.all([waitForLoaderResult(startedAt.current), waitForLoaderResult(refreshStartedAt)]);
    setRefreshState('pending');
    try {
      const ready = kind === 'logout'
        ? await client.fetchQuery({ queryKey: authKeys.csrf, queryFn: csrfToken, staleTime: 0, retry: false })
        : await csrfToken();
      if (generation !== sessionGeneration(client)) return;
      client.setQueryData(authKeys.csrf, ready);
      let user: ReturnType<typeof currentUser> | undefined;
      if (kind !== 'logout') {
        const { data, response } = await api.GET('/api/v1/auth/me');
        if (generation !== sessionGeneration(client)) return;
        if (!response.ok) throw new Error('Не удалось проверить сессию');
        user = currentUser(data);
      }
      await waitForPublication();
      if (generation !== sessionGeneration(client)) return;
      if (user) client.setQueryData(authKeys.user, user);
      setNeedsRefresh(false);
      setRefreshState('idle');
    } catch (error) {
      if (generation !== sessionGeneration(client)) return;
      await waitForPublication();
      if (generation !== sessionGeneration(client)) return;
      setRefreshState('error');
      throw error;
    }
  };
  const mutation = useTimedMutation({ retry: false, mutationFn: async (value: T) => {
    startedAt.current = Date.now();
    let generation = advanceSession(client);
    actionGeneration.current = generation;
    await client.cancelQueries({ queryKey: authKeys.user });
    await submit(value);
    if (generation !== sessionGeneration(client)) return;
    generation = advanceSession(client);
    actionGeneration.current = generation;
    await client.cancelQueries({ queryKey: authKeys.user });
    setNeedsRefresh(true);
    await clearPrivateQueries(client);
    if (generation !== sessionGeneration(client)) return;
    if (kind === 'logout') {
      await waitForLoaderResult(startedAt.current);
      if (generation !== sessionGeneration(client)) return;
      client.setQueryData(authKeys.csrf, false);
      client.setQueryData(authKeys.user, null);
    }
    await finish(generation);
  } });
  const refresh = useTimedMutation({ mutationFn: () => { startedAt.current = Date.now(); return finish(actionGeneration.current); }, retry: false });
  return { ...mutation, needsRefresh, refreshFailed: refreshState === 'error',
    refresh: () => { if (refreshState === 'error') refresh.mutate(); }, refreshSuccess: refresh.isSuccess,
    refreshing: refreshTiming.indicatorVisible, error: refresh.error ?? mutation.error };
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

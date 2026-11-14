import { useCallback, useRef } from 'react';
import { useMutation, type DefaultError, type QueryClient, type UseMutationOptions, type MutateOptions } from '@tanstack/react-query';
import { waitForLoaderResult } from './loaderTiming';

export function useTimedMutation<TData = unknown, TError = DefaultError, TVariables = void, TContext = unknown>(
  options: UseMutationOptions<TData, TError, TVariables, TContext>, client?: QueryClient,
) {
  const running = useRef<Promise<TData> | null>(null);
  const startedAt = useRef(0);
  const mutation = useMutation({ ...options, mutationFn: async (variables, context) => {
    try {
      return await options.mutationFn!(variables, context);
    } finally {
      await waitForLoaderResult(startedAt.current);
    }
  } }, client);
  const execute = mutation.mutateAsync;
  const mutateAsync = useCallback((variables: TVariables, callbacks?: MutateOptions<TData, TError, TVariables, TContext>) => {
    if (running.current) return running.current;
    startedAt.current = Date.now();
    const promise = execute(variables, callbacks).finally(() => { running.current = null; });
    running.current = promise;
    return promise;
  }, [execute]);
  const mutate = useCallback((variables: TVariables, callbacks?: MutateOptions<TData, TError, TVariables, TContext>) => {
    void mutateAsync(variables, callbacks).catch(() => undefined);
  }, [mutateAsync]);
  return { ...mutation, mutate, mutateAsync };
}

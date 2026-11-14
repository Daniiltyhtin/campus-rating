import { useCallback, useRef, useState } from 'react';
import { useInfiniteQuery, type DefaultError, type InfiniteData, type QueryKey, type UndefinedInitialDataInfiniteOptions } from '@tanstack/react-query';
import { useLoaderTiming } from './loaderTiming';

export function useTimedInfiniteQuery<TQueryFnData, TError = DefaultError, TData = InfiniteData<TQueryFnData>, TQueryKey extends QueryKey = QueryKey, TPageParam = unknown>(
  options: UndefinedInitialDataInfiniteOptions<TQueryFnData, TError, TData, TQueryKey, TPageParam>,
) {
  const query = useInfiniteQuery(options);
  const timing = useLoaderTiming(query.isFetchingNextPage);
  const [snapshot, setSnapshot] = useState(query);
  if (timing.resultReady && (snapshot.data !== query.data || snapshot.error !== query.error || snapshot.status !== query.status || snapshot.hasNextPage !== query.hasNextPage)) setSnapshot(query);
  const displayed = timing.resultReady ? query : snapshot;
  const running = useRef(false);
  const fetch = query.fetchNextPage;
  const busy = !timing.resultReady;
  const fetchNextPage = useCallback<typeof fetch>((fetchOptions) => {
    if (running.current || busy) return Promise.resolve(query);
    running.current = true;
    return fetch(fetchOptions).finally(() => { running.current = false; });
  }, [fetch, busy, query]);
  return { ...query, data: displayed.data, error: displayed.error, hasNextPage: displayed.hasNextPage,
    isError: displayed.isError, isFetchNextPageError: displayed.isFetchNextPageError,
    isFetching: query.isFetching || busy, isFetchingNextPage: query.isFetchingNextPage || busy, fetchNextPage };
}

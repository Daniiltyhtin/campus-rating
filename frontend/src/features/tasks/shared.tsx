import { Button, Text } from '@mantine/core';
import { useEffect, useRef } from 'react';
import { errorText, statuses, type Schemas } from './helpers';

export function Status({ status }: { status: Schemas['AssignmentStatus'] }) {
  return <span className={`cr-status cr-status-${status.toLowerCase().replaceAll('_', '-')}`}>{statuses[status]}</span>;
}

export function ErrorMessage({ error }: { error: unknown }) {
  return error ? <Text role="alert">{errorText(error)}</Text> : null;
}

export function More({
  hasNextPage,
  isFetchingNextPage,
  fetchNextPage,
}: {
  hasNextPage: boolean;
  isFetchingNextPage: boolean;
  fetchNextPage: () => unknown;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!hasNextPage || isFetchingNextPage || typeof IntersectionObserver === 'undefined') return;
    const observer = new IntersectionObserver(([entry]) => {
      if (entry?.isIntersecting) fetchNextPage();
    });
    if (ref.current) observer.observe(ref.current);
    return () => observer.disconnect();
  }, [hasNextPage, isFetchingNextPage, fetchNextPage]);
  return hasNextPage ? (
    <div ref={ref}>
      <Button variant="default" loading={isFetchingNextPage} onClick={() => fetchNextPage()}>
        Загрузить ещё
      </Button>
    </div>
  ) : null;
}

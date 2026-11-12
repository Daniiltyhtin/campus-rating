import { Button, Text, type ButtonProps } from '@mantine/core';
import { useMediaQuery } from '@mantine/hooks';
import type { ComponentPropsWithoutRef } from 'react';
import { ApiError } from '../../api/client';
import { PageLayout } from '../../app/layouts/PageLayouts';
import { useEffect, useRef } from 'react';
import { errorText, statuses, type Schemas } from './helpers';

export function Status({ status }: { status: Schemas['AssignmentStatus'] }) {
  return <span style={{ alignSelf: 'flex-start' }} className={`cr-status cr-status-${status.toLowerCase().replaceAll('_', '-')}`}>{statuses[status]}</span>;
}

export function ErrorMessage({ error }: { error: unknown }) {
  return error ? <Text role="alert" c="var(--cr-status-overdue)">{errorText(error)}</Text> : null;
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

export function TaskButton(props: ButtonProps & ComponentPropsWithoutRef<'button'>) {
  const desktop = useMediaQuery('(min-width: 48em)', true, { getInitialValueInEffect: false });
  return <Button {...props} style={{ alignSelf: 'flex-start', width: desktop ? 'auto' : '100%' }} />;
}

export function TaskFailure({ error, forbidden = false }: { error?: unknown; forbidden?: boolean }) {
  const code = error instanceof ApiError ? error.code : undefined;
  if (forbidden || code === 'FORBIDDEN') return <PageLayout title="Нет доступа"><Text>Этот раздел недоступен для вашей роли.</Text></PageLayout>;
  if (code === 'NOT_FOUND') return <PageLayout title="Не найдено"><Text>Проверьте адрес страницы.</Text></PageLayout>;
  return null;
}

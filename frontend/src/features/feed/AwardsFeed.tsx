import { Button, Group, SegmentedControl, Stack, Text } from '@mantine/core';
import { CurrencyIcon } from '../../app/CurrencyMark';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { useSearchParams } from 'react-router';
import { AsyncContent } from '../../app/layouts/PageLayouts';
import { useAuthSession } from '../auth/authQueries';
import { formatMoscowTime } from '../activities/time';
import { signedAmount } from '../profile/presentation';
import type { Award } from './contracts';
import { FeedLink, FeedPage } from './FeedShared';
import { newerAwards, uniqueAwards } from './presentation';
import { awardsKey, feedError, feedState, useAwards, useAwardsPoll } from './queries';

function AwardEntry({ item }: { item: Award }) {
  return <article className="cr-article"><Stack gap="xs"><Group justify="space-between" align="start">
    <FeedLink to={`/users/${item.student.id}`}>{item.student.fullName}</FeedLink>
    <strong className={`cr-number cr-currency-mark cr-${item.currency.toLowerCase()}`}><CurrencyIcon currency={item.currency} />{signedAmount(item.amount, item.currency)}</strong>
  </Group><Text>«<span>{item.activity.title}</span>»</Text><Group gap="xs"><Text className="cr-caption">от</Text>
    <FeedLink to={`/users/${item.teacher.id}`}>{item.teacher.fullName}</FeedLink>
    <time className="cr-caption" dateTime={item.createdAt}>{formatMoscowTime(item.createdAt)}</time>
  </Group></Stack></article>;
}
function Awards({ viewerId, mine, onMine }: { viewerId: number; mine: boolean; onMine?: (mine: boolean) => void }) {
  const client = useQueryClient(), query = useAwards(viewerId, mine);
  const poll = useAwardsPoll(viewerId, !!query.data, mine);
  const marker = useRef<HTMLDivElement>(null);
  const { hasNextPage, isFetching, isFetchingNextPage, isFetchNextPageError, fetchNextPage } = query;
  useEffect(() => {
    if (!marker.current || !hasNextPage || isFetching || isFetchNextPageError || typeof IntersectionObserver === 'undefined') return;
    const observer = new IntersectionObserver((entries) => { if (entries.some((entry) => entry.isIntersecting)) void fetchNextPage({ cancelRefetch: false }); });
    observer.observe(marker.current); return () => observer.disconnect();
  }, [hasNextPage, isFetching, isFetchNextPageError, fetchNextPage]);
  const items = uniqueAwards(query.data?.pages.flatMap((page) => page.items) ?? []);
  const fresh = newerAwards(poll.data?.items ?? [], items);
  async function showNew() {
    if (!poll.data) return;
    const head = poll.data;
    await client.cancelQueries({ queryKey: awardsKey(viewerId, mine) });
    client.setQueryData(awardsKey(viewerId, mine), { pages: [head], pageParams: [null] });
  }
  return <FeedPage title="Лента наград" description="Достижения студентов: кто, сколько, за что и от кого. Время — по Москве."><Stack gap="lg">
    {onMine && <SegmentedControl aria-label="Какие награды показать" value={mine ? 'mine' : 'all'} onChange={(value) => onMine(value === 'mine')}
      data={[{ value: 'all', label: 'Все' }, { value: 'mine', label: 'Мои' }]} className="feed-awards-filter" />}
    {fresh.length > 0 && <div role="status"><Button variant="default" className="feed-new" onClick={() => { void showNew(); }}>Новые награды: {fresh.length}{fresh.length === poll.data?.items.length && poll.data.nextCursor ? '+' : ''} — показать</Button></div>}
    {poll.isError && <Stack role="alert"><Text>{feedError(poll.error)}</Text><Button variant="default" onClick={() => { void poll.refetch(); }}>Повторить опрос</Button></Stack>}
    {query.data && query.isError && !isFetchNextPageError && <Stack role="alert"><Text>{feedError(query.error)}</Text><Button variant="default" disabled={isFetching} onClick={() => { void query.refetch(); }}>Повторить обновление наград</Button></Stack>}
    <AsyncContent state={query.data ? (items.length ? { status: 'ready' } : { status: 'empty', message: mine ? 'Своих наград пока нет.' : 'Наград пока нет. Здесь появятся достижения студентов.' }) : feedState(query, false, '')}>
      <div>{items.map((item) => <AwardEntry key={item.entryId} item={item} />)}</div>
    </AsyncContent>
    <div ref={marker}>{isFetchNextPageError ? <Stack role="alert"><Text>{feedError(query.error)}</Text><Button variant="default" disabled={isFetching} onClick={() => { void fetchNextPage({ cancelRefetch: false }); }}>Повторить загрузку наград</Button></Stack>
      : hasNextPage && <Button variant="default" loading={isFetchingNextPage} disabled={isFetching} onClick={() => { void fetchNextPage({ cancelRefetch: false }); }}>Показать ещё</Button>}</div>
  </Stack></FeedPage>;
}
export function AwardsFeed() {
  const { session } = useAuthSession();
  const [params, setParams] = useSearchParams();
  const mine = params.get('mine') === '1';
  const onMine = (value: boolean) => setParams(value ? { mine: '1' } : {}, { replace: true });
  return session.data ? <Awards viewerId={session.data.id} key={`${session.data.id}-${mine}`} mine={mine && session.data.role !== 'ADMIN'}
    onMine={session.data.role === 'ADMIN' ? undefined : onMine} /> : null;
}

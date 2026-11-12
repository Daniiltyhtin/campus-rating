import { Stack, Text } from '@mantine/core';
import { useSearchParams } from 'react-router';
import { AsyncContent } from '../../app/layouts/PageLayouts';
import { FeedPage } from '../feed/FeedShared';
import { feedState } from '../feed/queries';
import { AverageArChart } from './AverageArChart';
import { BudgetMonths } from './BudgetMonths';
import { minusDays, todayMoscow, validPeriod } from './contracts';
import { PeriodPicker } from './PeriodPicker';
import { PeriodPoints } from './PeriodPoints';
import { useAnalytics } from './queries';

export function GroupAnalytics({ viewerId, groupId, admin }: { viewerId: number; groupId: number; admin: boolean }) {
  const [params, setParams] = useSearchParams();
  const today = todayMoscow(), period = params.get('period') ?? '30';
  const fallback = { from: minusDays(today, period === '90' ? 89 : 29), to: today };
  const from = params.get('from') ?? fallback.from, to = params.get('to') ?? fallback.to;
  const selected = validPeriod(from, to, today) ? { from, to } : fallback;
  const query = useAnalytics(viewerId, groupId, selected.from, selected.to);
  const update = (period: string, first: string, last: string) => {
    const next = new URLSearchParams(params); next.set('period', period); next.set('from', first); next.set('to', last); setParams(next);
  };
  return <FeedPage title={query.data ? `Аналитика группы ${query.data.group.name}` : 'Аналитика группы'}><Stack gap="xl">
    <PeriodPicker key={`${groupId}-${period}`} period={period} from={from} to={to} today={today} semesterStart={query.data?.semesterStart ?? null}
      dates={(first, last) => update('custom', first, last)} change={(value) => {
        const first = value === 'semester' ? query.data?.semesterStart ?? fallback.from : minusDays(today, value === '90' ? 89 : 29);
        update(value, value === 'custom' ? selected.from : first, value === 'custom' ? selected.to : today);
      }} />
    <AsyncContent state={feedState(query, false, '')}>{query.data && (query.data.items.length === 0
      ? <Text>В группе нет студентов с подтверждённым членством</Text>
      : <><AverageArChart days={query.data.days} /><PeriodPoints items={query.data.items} /></>)}</AsyncContent>
    {query.data && <BudgetMonths viewerId={viewerId} groupId={groupId} admin={admin} />}
  </Stack></FeedPage>;
}

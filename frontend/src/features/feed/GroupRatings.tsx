import { SegmentedControl, Stack, Tabs, Text, Title } from '@mantine/core';
import { useState } from 'react';
import { useParams, useSearchParams } from 'react-router';
import { GroupAnalytics } from '../analytics/GroupAnalytics';
import { TeacherTableLayout } from '../../app/layouts/PageLayouts';
import { useAuthSession } from '../auth/authQueries';
import { currencyNames } from '../profile/presentation';
import { parseCurrency } from './contracts';
import type { Currency } from './contracts';
import { FeedLink, FeedPage } from './FeedShared';
import { feedState, useGroupRating } from './queries';

function Rating({ viewerId, groupId }: { viewerId: number; groupId: number }) {
  const [sort, setSort] = useState<Currency>('AR');
  const query = useGroupRating(viewerId, groupId, sort);
  return <FeedPage title={query.data ? `Рейтинг группы ${query.data.group.name}` : 'Рейтинг группы'}><Stack gap="lg">
    <FeedLink to="/groups">Группы и бюджет</FeedLink>
    {query.data && <Text>{query.data.group.faculty.name}</Text>}
    <div><Title order={2} mb="sm">Сортировка</Title><SegmentedControl aria-label="Сортировка рейтинга" className="feed-currency" value={sort} onChange={(value) => setSort(parseCurrency(value))}
      data={(['AR', 'BUA', 'BOA'] as const).map((value) => ({ value, label: currencyNames[value] }))} /></div>
    <TeacherTableLayout caption="Рейтинг студентов" rows={query.data?.items ?? []} keyForRow={(row) => row.student.id}
      state={feedState(query, query.data?.items.length === 0, 'Студентов пока нет. Рейтинг появится после подтверждения членства в группе.')}
      columns={[{ key: 'student', title: 'Студент', render: (row) => <FeedLink to={`/users/${row.student.id}`}>{row.student.fullName}</FeedLink> },
        ...(['AR', 'BUA', 'BOA'] as const).map((currency) => ({ key: currency, title: currencyNames[currency], render: (row: { balances: Record<Currency, number> }) => <span className={`cr-number cr-${currency.toLowerCase()}`}>{row.balances[currency]}</span> }))]} />
  </Stack></FeedPage>;
}
export function GroupRatings() {
  const { session } = useAuthSession(), { id } = useParams();
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') === 'analytics' ? 'analytics' : 'rating';
  if (session.data?.role !== 'TEACHER' && session.data?.role !== 'ADMIN') return <FeedPage title="Нет доступа"><Text>Рейтинг доступен преподавателям.</Text></FeedPage>;
  const groupId = Number(id);
  if (!id || !/^\d+$/.test(id) || !Number.isSafeInteger(groupId) || groupId <= 0) return <FeedPage title="Группа не найдена"><Text>Проверьте адрес группы.</Text></FeedPage>;
  return <Tabs value={tab} onChange={(value) => { const next = new URLSearchParams(params); if (value === 'analytics') next.set('tab', 'analytics'); else next.delete('tab'); setParams(next); }}>
    <Tabs.List><Tabs.Tab value="rating">Рейтинг</Tabs.Tab><Tabs.Tab value="analytics">Аналитика</Tabs.Tab></Tabs.List>
    {tab === 'analytics' ? <GroupAnalytics key={`${session.data.id}-${groupId}`} viewerId={session.data.id} groupId={groupId} admin={session.data.role === 'ADMIN'} /> : <Rating key={`${session.data.id}-${groupId}`} viewerId={session.data.id} groupId={groupId} />}</Tabs>;
}

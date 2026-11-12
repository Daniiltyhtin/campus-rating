import { Button, Group as MantineGroup, NativeSelect, Stack, Text, TextInput, Title } from '@mantine/core';
import { useEffect, useRef, useState } from 'react';
import { AsyncContent, TeacherTableLayout } from '../../app/layouts/PageLayouts';
import { ActivityAccess, ActivityLink, ActivityPage, Points } from './ActivityShared';
import type { Group } from './contracts';
import { loadState, useAccessibleGroups, useAwardHistory, useAwardSummary, useBudgets } from './queries';
import { activityError } from './errors';
import { formatMoscowTime } from './time';
import { TeacherRequestsLink } from '../groups/PendingMembers';

function History({ userId, month, groupId }: { userId: number; month: string; groupId: string }) {
  const query = useAwardHistory(userId, month, groupId);
  const sentinel = useRef<HTMLDivElement>(null);
  const { hasNextPage, isFetching, isFetchingNextPage, isFetchNextPageError, fetchNextPage } = query;
  useEffect(() => {
    if (!sentinel.current || !hasNextPage || isFetching || isFetchNextPageError || typeof IntersectionObserver === 'undefined') return;
    const observer = new IntersectionObserver((entries) => { if (entries.some((entry) => entry.isIntersecting)) void fetchNextPage({ cancelRefetch: false }); });
    observer.observe(sentinel.current);
    return () => observer.disconnect();
  }, [hasNextPage, isFetching, isFetchNextPageError, fetchNextPage]);
  const items = [...new Map((query.data?.pages.flatMap((page) => page.items) ?? []).map((item) => [item.id, item])).values()];
  return <section><Title order={2} mb="md">История начислений</Title>
    {query.data && query.isError && !isFetchNextPageError && <Stack role="alert"><Text>{activityError(query.error)}</Text><Button variant="default" disabled={isFetching} onClick={() => { void query.refetch(); }}>Повторить обновление истории</Button></Stack>}
    <AsyncContent state={query.data ? (items.length ? { status: 'ready' } : { status: 'empty', message: 'Начислений пока нет. Здесь появится история за выбранный месяц и группу.' }) : loadState(query)}>
      <Stack gap={0}>{items.map((award) => <article className="cr-article" key={award.id}><Stack gap="xs">
        <Title order={3}><ActivityLink to={`/activities/${award.activity.id}/confirm`}>{award.activity.title}</ActivityLink></Title>
        <MantineGroup justify="space-between"><ActivityLink to={`/users/${award.student.id}`}>{award.student.fullName}</ActivityLink><Points amount={award.amount} currency={award.currency} /></MantineGroup>
        <Text className="cr-caption">{award.group.name} · {award.level} % · <time dateTime={award.confirmedAt}>{formatMoscowTime(award.confirmedAt)}</time></Text>
      </Stack></article>)}</Stack>
    </AsyncContent>
    <div ref={sentinel}>{isFetchNextPageError ? <Stack role="alert"><Text>{activityError(query.error)}</Text><Button variant="default" disabled={isFetching} onClick={() => { void fetchNextPage({ cancelRefetch: false }); }}>Повторить загрузку истории</Button></Stack> : hasNextPage && <Button variant="default" loading={isFetchingNextPage} disabled={isFetching} onClick={() => { void fetchNextPage({ cancelRefetch: false }); }}>Показать ещё</Button>}</div>
  </section>;
}
function Budget({ userId }: { userId: number }) {
  const [month, setMonth] = useState('');
  const [groupId, setGroupId] = useState('');
  const budgets = useBudgets(userId, month);
  const accessibleGroups = useAccessibleGroups(userId);
  const currentMonth = month || budgets.data?.month || '';
  const summary = useAwardSummary(userId, currentMonth);
  const groups = new Map<number, Group>();
  for (const item of budgets.data?.items ?? []) groups.set(item.group.id, item.group);
  for (const item of summary.data?.items ?? []) groups.set(item.group.id, item.group);
  return <ActivityPage title="Группы и бюджет" description="Общий бюджет БУА и БОА по вашим группам. Месяц и время начислений — по Москве.">
    <section><Title order={2} mb="md">Рейтинги доступных групп</Title><AsyncContent state={loadState(accessibleGroups, accessibleGroups.data?.items.length === 0, 'Доступных групп пока нет. Они появятся после допуска или выдачи доступа.')}>
      <Stack gap="xs">{accessibleGroups.data?.items.map((group) => <Stack gap="xs" key={group.id}><ActivityLink to={`/groups/${group.id}`}>{group.name} · {group.faculty.code}</ActivityLink><ActivityLink to={`/groups/${group.id}/students`}>Студенты {group.name}</ActivityLink></Stack>)}</Stack>
    </AsyncContent><Stack gap="xs" mt="md"><TeacherRequestsLink /><ActivityLink to="/awards">Лента наград</ActivityLink></Stack></section>
    <div className="activity-toolbar"><TextInput type="month" label="Месяц бюджета" value={currentMonth} onChange={(event) => {
      const value = event.currentTarget.value;
      if (value && !/^\d{4}-(0[1-9]|1[0-2])$/.test(value)) return;
      setMonth(value); setGroupId('');
    }} /><NativeSelect label="Группа" value={groupId} disabled={groups.size === 0} onChange={(event) => setGroupId(event.currentTarget.value)}
      data={[{ value: '', label: 'Все группы' }, ...[...groups.values()].map((group) => ({ value: String(group.id), label: group.name }))]} /></div>
    <TeacherTableLayout caption="Бюджет групп" rows={budgets.data?.items ?? []} keyForRow={(row) => row.group.id}
      state={loadState(budgets, budgets.data?.items.length === 0, 'Групп с бюджетом пока нет. Они появятся после выдачи доступа или создания активности.')}
      columns={[{ key: 'group', title: 'Группа', render: (row) => row.group.name }, { key: 'limit', title: 'Лимит', render: (row) => row.limit },
        { key: 'spent', title: 'Расход', render: (row) => row.spent }, { key: 'remaining', title: 'Остаток', render: (row) => <strong className="cr-number">{row.remaining}</strong> }]} />
    {currentMonth && <><section className="activity-panel"><Title order={2} mb="md">Начисления за месяц</Title>
      <AsyncContent state={loadState(summary, summary.data?.items.length === 0, 'В этом месяце начислений пока нет.')}>
        {summary.data && <Stack><Text>Всего: <strong className="cr-number">{summary.data.total}</strong> баллов · {summary.data.count} начислений</Text>
          {summary.data.items.map((item) => <MantineGroup justify="space-between" key={`${item.group.id}-${item.currency}`}><Text>{item.group.name} · {item.count} начислений</Text>
            <Points amount={item.amount} currency={item.currency} /></MantineGroup>)}
        </Stack>}
      </AsyncContent>
    </section><History userId={userId} month={currentMonth} groupId={groupId} /></>}
  </ActivityPage>;
}
export function BudgetPage() { return <ActivityAccess teacherOnly>{(user) => <Budget userId={user.id} />}</ActivityAccess>; }

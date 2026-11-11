import { Anchor, Button, Group, SegmentedControl, Stack, Tabs, Text, Title } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Link, useParams } from 'react-router';
import { AsyncContent, PageLayout } from '../../app/layouts/PageLayouts';
import { useAuthSession } from '../auth/authQueries';
import type { CurrentUser } from '../auth/contracts';
import { formatMoscowTime } from '../activities/time';
import type { Currency, LedgerEntry, ProfileTask, UserProfile } from './contracts';
import { currencyNames, signedAmount, taskDestination } from './presentation';
import { profileError, useLedger, useProfile } from './queries';
import './profile.css';

function ProfileLink({ to, children }: { to: string; children: ReactNode }) {
  return <Anchor component={Link} to={to} c="var(--cr-link)" className="profile-link">{children}</Anchor>;
}
function ProfileLayout({ title, children }: { title: string; children: ReactNode }) {
  return <div className="profile-page"><PageLayout title={title}>{children}</PageLayout></div>;
}
const statusLabels = { IN_PROGRESS: ['В процессе', 'in-progress'], OVERDUE: ['Просрочено', 'overdue'], SUBMITTED: ['Сдано на проверку', 'submitted'], ACCEPTED: ['Принято', 'accepted'] } as const;
function TaskList({ title, tasks, viewer, ownerId, empty }: { title: string; tasks: ProfileTask[]; viewer: CurrentUser; ownerId: number; empty: string }) {
  return <section><Title order={2} mb="md">{title}</Title>{tasks.length === 0 ? <Text className="cr-caption">{empty}</Text> : tasks.map((task, index) => {
    const to = taskDestination(task, viewer, ownerId), status = statusLabels[task.status];
    return <article className="cr-article" key={`${task.title}-${index}`}><Stack gap="sm">
      <Title order={3}>{to && task.view === 'OWN' ? <ProfileLink to={to}>{task.title}</ProfileLink> : task.title}</Title>
      <Group justify="space-between"><span className={`cr-status cr-status-${status[1]}`}>{status[0]}</span><Text>Списано {task.charged} АР</Text></Group>
      <Text className="cr-caption">Дедлайн: <time dateTime={task.deadline}>{formatMoscowTime(task.deadline)}</time></Text>
      {to && task.view === 'ADMIN' && <ProfileLink to={to}>Материалы сдачи</ProfileLink>}
    </Stack></article>;
  })}</section>;
}
function HistoryEntry({ entry }: { entry: LedgerEntry }) {
  return <article className="cr-article"><Stack gap="xs"><Group justify="space-between" align="start">
    <time className="cr-caption" dateTime={entry.createdAt}>{formatMoscowTime(entry.createdAt)}</time>
    <strong className={`cr-number cr-${entry.currency.toLowerCase()}`}>{signedAmount(entry.amount, entry.currency)}</strong>
  </Group><Text>{entry.kind === 'PENALTY' ? 'Просрочка · ' : entry.kind === 'SEMESTER_ACCRUAL' ? 'Начисление семестра · ' : ''}«<span>{entry.source.title}</span>»</Text>
    <Group gap="xs">{entry.author ? <><Text className="cr-caption">от</Text><ProfileLink to={`/users/${entry.author.id}`}>{entry.author.fullName}</ProfileLink></> : <Text className="cr-caption">Система</Text>}</Group>
  </Stack></article>;
}
function History({ viewerId, userId }: { viewerId: number; userId: number }) {
  const client = useQueryClient();
  const [currency, setCurrency] = useState<Currency | ''>('');
  const query = useLedger(viewerId, userId, currency);
  const marker = useRef<HTMLDivElement>(null);
  const { hasNextPage, isFetching, isFetchingNextPage, isFetchNextPageError, fetchNextPage } = query;
  useEffect(() => {
    if (!marker.current || !hasNextPage || isFetching || isFetchNextPageError || typeof IntersectionObserver === 'undefined') return;
    const observer = new IntersectionObserver((entries) => { if (entries.some((entry) => entry.isIntersecting)) void fetchNextPage({ cancelRefetch: false }); });
    observer.observe(marker.current); return () => observer.disconnect();
  }, [hasNextPage, isFetching, isFetchNextPageError, fetchNextPage]);
  const entries = [...new Map((query.data?.pages.flatMap((page) => page.items) ?? []).map((entry) => [entry.id, entry])).values()];
  const state = query.isPending ? { status: 'loading' as const }
    : query.isError && !query.data ? { status: 'error' as const, message: profileError(query.error), retry: () => { void query.refetch(); } }
      : entries.length === 0 ? { status: 'empty' as const, message: 'Проводок пока нет. Здесь появится история выбранной валюты.' } : { status: 'ready' as const };
  return <Stack gap="lg"><SegmentedControl aria-label="Валюта истории" className="profile-currency" value={currency}
    onChange={(value) => { if (value !== currency && (value === '' || value === 'AR' || value === 'BUA' || value === 'BOA')) { client.removeQueries({ queryKey: ['profile-ledger', viewerId, userId, value], exact: true }); setCurrency(value); } }}
    data={[{ value: '', label: 'Все' }, ...(['AR', 'BUA', 'BOA'] as const).map((value) => ({ value, label: currencyNames[value] }))]} />
    {query.data && query.isError && !isFetchNextPageError && <Stack role="alert"><Text>{profileError(query.error)}</Text><Button variant="default" disabled={isFetching} onClick={() => { void query.refetch(); }}>Повторить обновление истории</Button></Stack>}
    <AsyncContent state={state}><div>{entries.map((entry) => <HistoryEntry key={entry.id} entry={entry} />)}</div></AsyncContent>
    <div ref={marker}>{query.isFetchNextPageError ? <Stack role="alert"><Text>{profileError(query.error)}</Text><Button variant="default" disabled={isFetching} onClick={() => { void query.fetchNextPage({ cancelRefetch: false }); }}>Повторить загрузку истории</Button></Stack>
      : hasNextPage && <Button variant="default" loading={isFetchingNextPage} disabled={isFetching} onClick={() => { void fetchNextPage({ cancelRefetch: false }); }}>Показать ещё</Button>}</div>
  </Stack>;
}
function ProfileContent({ profile, viewer }: { profile: UserProfile; viewer: CurrentUser }) {
  if (profile.role === 'ADMIN') return <Text>Администратор</Text>;
  if (profile.role === 'TEACHER') return <Stack gap="md"><Text>Преподаватель</Text><Text>{profile.department.name}</Text><Text>{profile.department.faculty.name}</Text><ProfileLink to="/awards">Лента наград</ProfileLink></Stack>;
  return <Stack gap="xl"><Stack gap="xs"><Text>{profile.group.faculty.name}</Text><Text className="cr-caption">{profile.group.faculty.code} · {profile.group.name}</Text></Stack>
    <div className="profile-balances" aria-label="Балансы">{(['AR', 'BUA', 'BOA'] as const).map((currency) => <div className="cr-balances" key={currency}><Text className="cr-caption">{currencyNames[currency]}</Text>
      <strong className={`cr-number profile-balance cr-${currency.toLowerCase()}`}>{profile.balances[currency]}</strong>{currency === 'AR' && <Text className="cr-caption">из 1000</Text>}</div>)}</div>
    <Tabs defaultValue="history" keepMounted={false}><Tabs.List mb="lg"><Tabs.Tab value="history">История</Tabs.Tab><Tabs.Tab value="tasks">Задачи</Tabs.Tab></Tabs.List>
      <Tabs.Panel value="history"><History viewerId={viewer.id} userId={profile.id} /></Tabs.Panel>
      <Tabs.Panel value="tasks"><Stack gap="xl"><TaskList title="Активные задачи" tasks={profile.activeTasks} viewer={viewer} ownerId={profile.id} empty="Активных задач пока нет." />
        <TaskList title="Завершённые задачи" tasks={profile.completedTasks} viewer={viewer} ownerId={profile.id} empty="Завершённых задач пока нет." /></Stack></Tabs.Panel>
    </Tabs>
  </Stack>;
}
function LoadedProfile({ viewer, userId }: { viewer: CurrentUser; userId: number }) {
  const query = useProfile(viewer.id, userId);
  if (query.isError) {
    const message = profileError(query.error);
    return <ProfileLayout title={message}><Text>{message === 'Пользователь не найден' || message === 'Нет доступа' ? 'Проверьте адрес профиля.' : 'Попробуйте загрузить данные ещё раз.'}</Text>
      <Button variant="default" onClick={() => { void query.refetch(); }}>Повторить</Button></ProfileLayout>;
  }
  return <ProfileLayout title={query.data?.fullName ?? 'Профиль'}><AsyncContent state={query.isPending ? { status: 'loading' } : { status: 'ready' }}>
    {query.data && <ProfileContent key={`${viewer.id}-${query.data.id}`} profile={query.data} viewer={viewer} />}
  </AsyncContent></ProfileLayout>;
}
export function ProfilePage() {
  const { id } = useParams(), { session } = useAuthSession();
  const userId = id && /^\d+$/.test(id) ? Number(id) : NaN;
  if (!Number.isSafeInteger(userId) || userId <= 0) return <ProfileLayout title="Пользователь не найден"><Text>Проверьте адрес профиля.</Text></ProfileLayout>;
  return session.data ? <LoadedProfile viewer={session.data} userId={userId} /> : null;
}

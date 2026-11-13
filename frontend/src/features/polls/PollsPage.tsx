import { Stack } from '@mantine/core';
import { AsyncContent } from '../../app/layouts/PageLayouts';
import { ActivityAccess, ActivityLink, ActivityPage } from '../activities/ActivityShared';
import { PollLink } from './PollLink';
import { pollError, usePolls } from './queries';
import './polls.css';

function Polls({ userId, teacher }: { userId: number; teacher: boolean }) {
  const polls = usePolls(userId);
  const items = polls.data?.items ?? [];
  const state = polls.isError ? { status: 'error' as const, message: pollError(polls.error), retry: () => { void polls.refetch(); } }
    : polls.isPending ? { status: 'loading' as const } : items.length === 0 ? { status: 'empty' as const, message: 'Опросов пока нет' } : { status: 'ready' as const };
  return <ActivityPage title="Опросы" description={teacher ? 'Ваши опросы. Время — по Москве.' : 'Опросы вашей группы. Время — по Москве.'}
    actions={teacher ? <ActivityLink to="/polls/new">Новый опрос</ActivityLink> : undefined}>
    <AsyncContent state={state}><Stack className="poll-list">{items.map((poll) => <PollLink key={poll.id} poll={poll} />)}</Stack></AsyncContent>
  </ActivityPage>;
}

export function PollsPage() { return <ActivityAccess>{(user) => <Polls userId={user.id} teacher={user.role === 'TEACHER'} />}</ActivityAccess>; }

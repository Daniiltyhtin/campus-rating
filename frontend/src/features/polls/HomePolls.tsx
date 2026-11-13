import { Stack, Title } from '@mantine/core';
import { useId } from 'react';
import { AsyncContent } from '../../app/layouts/PageLayouts';
import { ActivityLink } from '../activities/ActivityShared';
import { PollLink } from './PollLink';
import { pollError, usePolls } from './queries';
import './polls.css';

export function HomePolls({ userId, teacher }: { userId: number; teacher: boolean }) {
  const heading = useId();
  const polls = usePolls(userId);
  const items = (polls.data?.items ?? []).filter((poll) => poll.open && (teacher || !poll.voted));
  const title = teacher ? 'Мои опросы' : 'Новые опросы';
  const state = polls.isError ? { status: 'error' as const, message: pollError(polls.error), retry: () => { void polls.refetch(); } }
    : polls.isPending ? { status: 'loading' as const }
      : items.length === 0 ? { status: 'empty' as const, message: teacher ? 'Открытых опросов нет' : 'Новых опросов нет' } : { status: 'ready' as const };
  return <section aria-labelledby={heading} className="poll-home">
    <Title order={2} id={heading}>{title}</Title>
    <AsyncContent state={state}><Stack gap="xs">{items.map((poll) => <PollLink key={poll.id} poll={poll} />)}</Stack></AsyncContent>
    {teacher && <ActivityLink to="/polls/new">Новый опрос</ActivityLink>}
  </section>;
}

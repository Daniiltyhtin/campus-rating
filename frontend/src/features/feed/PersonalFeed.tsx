import { Button, Stack, Text, Title } from '@mantine/core';
import type { ReactNode } from 'react';
import { AsyncContent } from '../../app/layouts/PageLayouts';
import { SectionPlaceholder } from '../../app/routeElements';
import { useAuthSession } from '../auth/authQueries';
import { RequestMembership } from '../auth/RequestMembership';
import { HeadRequestsNotice, TeacherRequestsNotice } from '../groups/PendingMembers';
import { HomePolls } from '../polls/HomePolls';
import type { PersonalEvent } from './contracts';
import { FeedIcon, FeedLink, FeedPage } from './FeedShared';
import { formatFeedTime, personalEventView, uniqueEvents } from './presentation';
import { feedError, feedState, usePersonalFeed } from './queries';

function EventEntry({ event, viewerId }: { event: PersonalEvent; viewerId: number }) {
  const view = personalEventView(event, viewerId);
  return <article className="cr-article personal-event"><FeedIcon kind={event.kind} /><Stack gap={0}>
    <FeedLink to={view.to}>{view.text}</FeedLink>
    <time className="cr-caption" dateTime={event.createdAt}>{formatFeedTime(event.createdAt)}</time>
  </Stack></article>;
}
function Events({ viewerId, notice, extra, polls }: { viewerId: number; notice?: string; extra?: ReactNode; polls: ReactNode }) {
  const query = usePersonalFeed(viewerId);
  const { hasNextPage, isFetching, isFetchingNextPage, isFetchNextPageError, fetchNextPage } = query;
  const items = uniqueEvents(query.data?.pages.flatMap((page) => page.items) ?? []);
  return <FeedPage title="Главная" description="Ваши события: задачи, проверки и начисления. Время — по Москве."><Stack gap="lg">
    {notice && <Text>{notice}</Text>}
    {extra}
    {polls}
    <Title order={2}>События</Title>
    <AsyncContent state={query.data && (!query.isError || isFetchNextPageError) ? (items.length ? { status: 'ready' } : { status: 'empty', message: 'Событий пока нет' })
      : feedState(query, false, '')}>
      <div>{items.map((event) => <EventEntry key={event.id} event={event} viewerId={viewerId} />)}</div>
    </AsyncContent>
    {isFetchNextPageError ? <Stack role="alert"><Text>{feedError(query.error)}</Text><Button variant="default" disabled={isFetching} onClick={() => { void fetchNextPage({ cancelRefetch: false }); }}>Повторить загрузку событий</Button></Stack>
      : hasNextPage && <div><Button variant="default" loading={isFetchingNextPage} disabled={isFetching} onClick={() => { void fetchNextPage({ cancelRefetch: false }); }}>Загрузить ещё</Button></div>}
  </Stack></FeedPage>;
}
export function HomePage() {
  const user = useAuthSession().session.data;
  if (!user) return null;
  if (user.role === 'ADMIN') return <SectionPlaceholder />;
  const notice = user.role === 'TEACHER' && !user.teacherApproved ? 'Заявка ждёт подтверждения администратора'
    : user.role === 'STUDENT' && user.membershipState === 'PENDING' ? `Членство в ${user.group.name} ждёт подтверждения старосты или преподавателя` : undefined;
  const extra = user.role === 'TEACHER' ? (user.teacherApproved ? <TeacherRequestsNotice /> : undefined) : user.membershipState === 'REJECTED' ? <RequestMembership groupName={user.group.name} />
    : user.groupHead ? <HeadRequestsNotice groupName={user.group.name} /> : undefined;
  return <Events viewerId={user.id} key={user.id} notice={notice} extra={extra} polls={<HomePolls userId={user.id} teacher={user.role === 'TEACHER'} />} />;
}

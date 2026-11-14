import { ActionButton } from '../../app/ActionButton';
import { useTimedMutation } from '../../app/actionTiming';
import { Button, Group, SegmentedControl, Stack, Text, Title } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { api } from '../../api/client';
import { AsyncContent } from '../../app/layouts/PageLayouts';
import type { CurrentUser } from '../auth/contracts';
import { ActivityAccess, ActivityFailure, ActivityLink, ActivityPage, Points } from './ActivityShared';
import { parseActivity } from './contracts';
import type { Activity } from './contracts';
import { activityKeys, loadState, read, useActivities } from './queries';
import { formatMoscowTime } from './time';

type User = Exclude<CurrentUser, { role: 'ADMIN' }>;
function ActivityItem({ activity, user }: { activity: Activity; user: User }) {
  const client = useQueryClient();
  const enrollment = useTimedMutation({ retry: false, mutationFn: (join: boolean) => {
    const params = { path: { activityId: activity.id } };
    return read(join ? api.PUT('/api/v1/activities/{activityId}/enrollment', { params }) : api.DELETE('/api/v1/activities/{activityId}/enrollment', { params }), parseActivity);
  }, onSettled: () => { void client.invalidateQueries({ queryKey: activityKeys.root }); } });
  const student = user.role === 'STUDENT';
  const canChange = student && activity.type === 'SOCIAL' && activity.enrollmentOpen && activity.enrolled !== null;
  const pendingMember = user.role === 'STUDENT' && user.membershipState !== 'ACTIVE';
  return <article className="cr-article" aria-label={activity.title}><Stack gap="sm">
    <Group justify="space-between" align="start"><Text className="cr-caption">{activity.type === 'ACADEMIC' ? 'Учебная · БУА' : 'Общественная · БОА'}</Text>
      <Text>До <Points amount={activity.maxPoints} currency={activity.currency} /></Text></Group>
    <Title order={2}>{activity.title}</Title><Text className="activity-description">{activity.description}</Text>
    <div className="activity-meta"><time dateTime={activity.startsAt}>{formatMoscowTime(activity.startsAt)}</time>
      <ActivityLink to={`/users/${activity.author.id}`}>{activity.author.fullName}</ActivityLink><Text className="cr-caption">{activity.groups.map((group) => group.name).join(', ')}</Text></div>
    {student && activity.myAward && <Text>Начислено <Points amount={activity.myAward.amount} currency={activity.currency} /> · {activity.myAward.level} %</Text>}
    {student && activity.type === 'ACADEMIC' && <Text className="cr-caption">Запись не нужна: участников отметит преподаватель.</Text>}
    {student && activity.type === 'SOCIAL' && (activity.enrollmentOpen || !enrollment.error) && <Text className="cr-caption">{activity.enrolled ? 'Вы записаны. ' : ''}{activity.enrollmentOpen ? 'Запись и отмена доступны до начала. Баллы получат только записавшиеся.' : 'Запись закрыта.'}</Text>}
    {canChange && <ActionButton variant={activity.enrolled ? 'default' : 'filled'}
      aria-label={`${activity.enrolled ? 'Отменить запись' : 'Записаться'}: ${activity.title}`}
      disabled={(pendingMember && !activity.enrolled)} pending={enrollment.isPending}
      onClick={() => enrollment.mutate(!activity.enrolled)}>{activity.enrolled ? 'Отменить запись' : 'Записаться'}</ActionButton>}
    {!student && <Group><Text className="cr-caption">Записаны: {activity.enrolledCount} · Подтверждены: {activity.confirmedCount}</Text>
      {activity.confirmationOpen ? <ActivityLink to={`/activities/${activity.id}/confirm`}>Подтвердить участие: {activity.title}</ActivityLink>
        : <Text className="cr-caption">Подтверждение участия доступно после начала.</Text>}</Group>}
    {enrollment.error && <ActivityFailure error={enrollment.error} />}
    {enrollment.isSuccess && <Text role="status" className="cr-success">{enrollment.variables ? 'Вы записались на активность' : 'Запись отменена'}</Text>}
  </Stack></article>;
}
function ActivityList({ user }: { user: User }) {
  const query = useActivities(user.id);
  const [filter, setFilter] = useState('ALL');
  const items = query.data?.items.filter((activity) => filter === 'ALL' || activity.type === filter) ?? [];
  return <ActivityPage title="Активности" description={user.role === 'STUDENT' ? 'Выбирайте события, участвуйте и получайте баллы. Всё время указано по Москве.' : 'Ваши активности: создание, участники и начисления. Всё время указано по Москве.'}
    actions={user.role === 'TEACHER' ? <Button component={Link} to="/activities/new">Создать активность</Button> : undefined}>
    <SegmentedControl className="activity-filter" aria-label="Тип активности" value={filter} onChange={setFilter} data={[{ value: 'ALL', label: 'Все' }, { value: 'ACADEMIC', label: 'Учебные · БУА' }, { value: 'SOCIAL', label: 'Общественные · БОА' }]} />
    {user.role === 'STUDENT' && user.membershipState !== 'ACTIVE' && <Text>Членство в группе ждёт подтверждения. Запись станет доступна после подтверждения; прежнюю запись можно отменить до начала.</Text>}
    <AsyncContent state={loadState(query, items.length === 0, 'Активностей пока нет. Здесь появятся доступные события выбранного типа.')}>
      <Stack gap={0}>{items.map((activity) => <ActivityItem key={activity.id} activity={activity} user={user} />)}</Stack>
    </AsyncContent>
  </ActivityPage>;
}
export function ActivityListPage() { return <ActivityAccess>{(user) => <ActivityList user={user} />}</ActivityAccess>; }

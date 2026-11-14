import { ActionButton } from '../../app/ActionButton';
import { useTimedMutation } from '../../app/actionTiming';
import { Button, Checkbox, Group, SegmentedControl, Stack, Text, Title } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useParams } from 'react-router';
import { api, ApiError } from '../../api/client';
import { AsyncContent, TeacherTableLayout } from '../../app/layouts/PageLayouts';
import { ActivityAccess, ActivityFailure, ActivityLink, ActivityPage, BudgetChecks, Points } from './ActivityShared';
import { levels, parseResult, participationLimit } from './contracts';
import type { Activity, Level, Participant, ParticipationRequest, Preview } from './contracts';
import { activityKeys, loadState, read, useActivity, useParticipants, useParticipationPreview } from './queries';
import { formatMoscowTime } from './time';
import { updateSelection } from './selection';

function participantChoice({ participant, selected, disabled, preview, currency, choose, changeLevel }: {
  participant: Participant; selected: Level | undefined; disabled: boolean; preview: Preview | undefined; currency: string;
  choose: (checked: boolean) => void; changeLevel: (value: Level) => void;
}) {
  const known = participant.confirmed ?? preview?.alreadyConfirmed.find((line) => line.studentId === participant.student.id);
  const amount = preview?.awards.find((line) => line.studentId === participant.student.id)?.amount;
  return { person: <Group gap="sm" align="start" className="activity-person">
    {!known && <Checkbox label="Выбрать" aria-label={`Выбрать ${participant.student.fullName}`} checked={selected !== undefined} disabled={disabled} onChange={(event) => choose(event.currentTarget.checked)} />}
    <ActivityLink to={`/users/${participant.student.id}`}>{participant.student.fullName}</ActivityLink>
  </Group>, participation: known ? <Stack gap="xs"><Text>Подтверждено · {known.level} % · только просмотр</Text>
    {'confirmedAt' in known && <time dateTime={known.confirmedAt}>{formatMoscowTime(known.confirmedAt)}</time>}</Stack>
    : <SegmentedControl aria-label={`Уровень участия ${participant.student.fullName}`} value={String(selected ?? 100)} disabled={disabled || selected === undefined}
      onChange={(value) => { const next = levels.find((item) => String(item) === value); if (next) changeLevel(next); }}
      data={levels.map((value) => ({ value: String(value), label: `${value} %` }))} />,
    amount: known ? <Points amount={known.amount} currency={currency} /> : selected === undefined ? <Text className="cr-caption">Не выбран</Text>
      : amount === undefined ? <Text className="cr-caption">Расчёт…</Text> : <Points amount={amount} currency={currency} /> };
}
function Confirmation({ activity, userId }: { activity: Activity; userId: number }) {
  const participants = useParticipants(userId, activity.id);
  const [selection, setSelection] = useState<Record<number, Level>>({});
  const client = useQueryClient();
  const items = (participants.data?.items ?? []).flatMap((participant) => {
    const level = selection[participant.student.id];
    return participant.confirmed || (activity.type === 'SOCIAL' && participant.enrolled !== true) || level === undefined ? [] : [{ studentId: participant.student.id, level }];
  });
  const body = { items };
  const preview = useParticipationPreview(userId, activity.id, body, items.length > 0);
  useEffect(() => {
    if (!(preview.error instanceof ApiError) || !['ALREADY_CONFIRMED', 'NOT_ENROLLED', 'STUDENT_NOT_ELIGIBLE', 'INVALID_STATE'].includes(preview.error.code)) return;
    void Promise.all([
      client.invalidateQueries({ queryKey: ['activities', userId, 'participants', activity.id] }),
      client.invalidateQueries({ queryKey: ['activities', userId, 'detail', activity.id] }),
    ]).then(() => {
      const roster = client.getQueryData<{ items: Participant[] }>(['activities', userId, 'participants', activity.id]);
      setSelection((old) => Object.fromEntries(Object.entries(old).filter(([id]) => roster?.items.some((row) => row.student.id === Number(id) && !row.confirmed && (activity.type !== 'SOCIAL' || row.enrolled === true)))));
    });
  }, [preview.error, preview.errorUpdatedAt, client, userId, activity.id, activity.type]);
  const confirm = useTimedMutation({ retry: false,
    mutationFn: (body: ParticipationRequest) => read(api.POST('/api/v1/activities/{activityId}/participation', { params: { path: { activityId: activity.id } }, body }), parseResult),
    onSuccess: () => { setSelection({}); }, onSettled: () => { void client.invalidateQueries({ queryKey: activityKeys.root }); } });
  const change = (id: number, next: Level | undefined) => {
    confirm.reset();
    setSelection((old) => updateSelection(old, id, next));
  };
  const rows = participants.data?.items ?? [];
  const views = new Map(rows.map((participant) => [participant.student.id, participantChoice({ participant,
    selected: selection[participant.student.id], preview: preview.data, currency: activity.currency,
    disabled: confirm.isPending || participants.isFetching || (activity.type === 'SOCIAL' && participant.enrolled !== true)
      || (items.length >= participationLimit && selection[participant.student.id] === undefined),
    choose: (checked) => change(participant.student.id, checked ? 100 : undefined), changeLevel: (value) => change(participant.student.id, value) })]));
  const total = preview.data?.total;
  const canConfirm = preview.isSuccess && !preview.isFetching && preview.data.withinBudget && !participants.isFetching && items.length > 0;
  return <Stack gap="lg">
    {activity.type === 'SOCIAL' && <Text className="cr-caption">Общественная активность: подтверждать можно только записавшихся участников.</Text>}
    <div className="activity-participants"><TeacherTableLayout caption="Участники" rows={rows} keyForRow={(row) => row.student.id}
      state={loadState(participants, rows.length === 0, 'Участников для подтверждения пока нет.')} isSelected={(row) => selection[row.student.id] !== undefined}
      columns={[{ key: 'student', title: 'Участник', render: (row) => views.get(row.student.id)?.person },
        { key: 'group', title: 'Группа', render: (row) => row.group.name },
        { key: 'level', title: 'Уровень участия', render: (row) => views.get(row.student.id)?.participation },
        { key: 'amount', title: 'Баллы', render: (row) => views.get(row.student.id)?.amount }]} /></div>
    {items.length >= participationLimit && <Text>За раз можно подтвердить не больше 500 участников.</Text>}
    {items.length === 0 ? <Text className="cr-caption">Выберите участников и уровень участия, чтобы проверить суммы и бюджет.</Text>
      : <section className="activity-panel"><Title order={2}>Проверка начислений</Title>
        {preview.isFetching && <Text role="status">Расчёт начислений…</Text>}
        {preview.isError && <Stack><ActivityFailure error={preview.error} participants={rows} /><Button variant="default" onClick={() => { void preview.refetch(); }}>Повторить расчёт</Button></Stack>}
        {preview.data && <Stack gap="sm"><Text>Выбрано участников: {items.length} · К начислению <Points amount={preview.data.total} currency={preview.data.currency} /></Text>
          <BudgetChecks budgets={preview.data.budgets} />
          {!preview.data.withinBudget && <Text className="activity-danger">Бюджет не позволяет подтвердить участие целиком. Измените уровень или состав участников.</Text>}
          {preview.data.alreadyConfirmed.length > 0 && <Text>Уже подтверждено: {preview.data.alreadyConfirmed.length}. Повторного начисления нет.</Text>}
        </Stack>}
      </section>}
    {confirm.error && <ActivityFailure error={confirm.error} participants={rows} />}
    {confirm.data && <div className="activity-result" role="status">{confirm.data.total > 0
      ? <Text className="cr-success">Начислено {confirm.data.total} {confirm.data.currency === 'BUA' ? 'БУА' : 'БОА'}</Text>
      : <Text>Новых начислений нет — участие уже подтверждено.</Text>}
      {confirm.data.total > 0 && confirm.data.alreadyConfirmed.length > 0 && <Text>Ранее подтверждены: {confirm.data.alreadyConfirmed.length}, без повторного начисления.</Text>}</div>}
    <ActionButton disabled={!canConfirm} pending={confirm.isPending} onClick={() => confirm.mutate(body)}>{total === undefined ? 'Начислить' : `Начислить ${total} ${activity.currency === 'BUA' ? 'БУА' : 'БОА'}`}</ActionButton>
  </Stack>;
}
function ConfirmationActivity({ userId, activityId }: { userId: number; activityId: number }) {
  const query = useActivity(userId, activityId);
  const activity = query.data;
  if (activity && activity.author.id !== userId) return <ActivityPage title="Нет доступа"><Text>Подтверждать участие может только автор активности.</Text></ActivityPage>;
  return <ActivityPage title="Подтверждение участия" description="Сначала проверьте состав, суммы и остаток бюджета. Время — по Москве." actions={<ActivityLink to="/activities">К активностям</ActivityLink>}>
    <AsyncContent state={loadState(query)}>{activity && <Stack gap="lg"><Title order={2}>{activity.title}</Title>
      <Text>Максимум <Points amount={activity.maxPoints} currency={activity.currency} /> · <time dateTime={activity.startsAt}>{formatMoscowTime(activity.startsAt)}</time></Text>
      {activity.confirmationOpen ? <Confirmation key={activity.id} activity={activity} userId={userId} /> : <Text>Подтверждение участия доступно после начала активности.</Text>}
    </Stack>}</AsyncContent>
  </ActivityPage>;
}
function ConfirmationRoute({ userId }: { userId: number }) {
  const { id } = useParams();
  const activityId = id && /^\d+$/.test(id) ? Number(id) : NaN;
  return Number.isSafeInteger(activityId) && activityId > 0 ? <ConfirmationActivity userId={userId} activityId={activityId} />
    : <ActivityPage title="Активность не найдена"><Text>Проверьте адрес активности.</Text></ActivityPage>;
}
export function ConfirmParticipationPage() { return <ActivityAccess teacherOnly>{(user) => <ConfirmationRoute userId={user.id} />}</ActivityAccess>; }

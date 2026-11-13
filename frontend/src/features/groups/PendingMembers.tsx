import { Anchor, Button, Group, Modal, Stack, Text, Title } from '@mantine/core';
import { useState } from 'react';
import { Link } from 'react-router';
import type { components } from '../../api/schema';
import { PageLayout, TeacherTableLayout } from '../../app/layouts/PageLayouts';
import { useAuthSession } from '../auth/authQueries';
import { dateTime, errorText, loadState } from '../tasks/helpers';
import { TaskFailure } from '../tasks/shared';
import { useMembershipDecision, usePendingMemberships } from './memberships';
import './memberships.css';

type Pending = components['schemas']['PendingMembership'];

function RejectDialog({ request, close }: { request: Pending; close: () => void }) {
  const decision = useMembershipDecision();
  return <Modal opened onClose={() => { if (!decision.isPending) close(); }} title="Отклонить заявку" centered closeButtonProps={{ 'aria-label': 'Закрыть' }}>
    <Stack><Text>Отклонить заявку {request.fullName} в {request.group.name}? Студент сможет выбрать группу заново.</Text>
      {decision.error && <Text role="alert">{errorText(decision.error)}</Text>}
      <Group justify="end"><Button variant="default" disabled={decision.isPending} onClick={close}>Отмена</Button>
        <Button loading={decision.isPending} onClick={() => decision.mutate({ studentId: request.studentId, decision: 'rejection' }, { onSuccess: close })}>Отклонить</Button></Group>
    </Stack>
  </Modal>;
}

function Requests() {
  const query = usePendingMemberships(), decision = useMembershipDecision();
  const [rejecting, setRejecting] = useState<Pending | null>(null);
  const items = query.data?.items ?? [];
  return <PageLayout title="Заявки в группы" description="Студенты ждут подтверждения членства. Время — по Москве.">
    {decision.error && <Text role="alert">{errorText(decision.error)}</Text>}
    <TeacherTableLayout caption="Заявки" rows={items} keyForRow={(row) => row.studentId} state={loadState(query, items.length === 0, 'Новых заявок нет')}
      columns={[{ key: 'name', title: 'ФИО', render: (row) => row.fullName }, { key: 'email', title: 'Email', render: (row) => row.email },
        { key: 'group', title: 'Группа', render: (row) => row.group.name }, { key: 'requested', title: 'Заявка', render: (row) => dateTime(row.requestedAt) },
        { key: 'actions', title: 'Действия', render: (row) => <Group gap="xs">
          <Button aria-label={`Подтвердить ${row.fullName}`} disabled={decision.isPending} onClick={() => decision.mutate({ studentId: row.studentId, decision: 'approval' })}>Подтвердить</Button>
          <Button variant="default" aria-label={`Отклонить ${row.fullName}`} disabled={decision.isPending} onClick={() => setRejecting(row)}>Отклонить</Button></Group> }]} />
    {rejecting && <RejectDialog request={rejecting} close={() => setRejecting(null)} />}
  </PageLayout>;
}

export function PendingMembersPage() {
  const user = useAuthSession().session.data;
  const allowed = user?.role === 'TEACHER' || (user?.role === 'STUDENT' && user.groupHead);
  return allowed ? <Requests /> : <TaskFailure forbidden />;
}

export function TeacherRequestsLink() {
  const query = usePendingMemberships();
  if (!query.data) return null;
  return <Anchor component={Link} to="/memberships" c="var(--cr-link)">Заявки: {query.data.items.length}</Anchor>;
}

function students(count: number) {
  const last = count % 10, tens = count % 100;
  if (last === 1 && tens !== 11) return `${count} студент ждёт`;
  if (last >= 2 && last <= 4 && (tens < 12 || tens > 14)) return `${count} студента ждут`;
  return `${count} студентов ждут`;
}

export function HeadRequestsNotice({ groupName }: { groupName: string }) {
  const query = usePendingMemberships();
  const count = query.data?.items.length ?? 0;
  if (count === 0) return null;
  return <section className="membership-notice" aria-label={`Заявки в группу ${groupName}`}><Stack gap="xs">
    <Title order={2}>Заявки в группу {groupName}</Title>
    <Text>{students(count)} подтверждения членства. Вы староста группы: проверьте, что это ваши одногруппники, и подтвердите или отклоните заявки.</Text>
    <div><Button component={Link} to="/memberships">Проверить заявки</Button></div>
  </Stack></section>;
}

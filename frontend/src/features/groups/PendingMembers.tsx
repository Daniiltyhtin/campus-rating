import { Anchor, Button, Group, Modal, Stack, Text } from '@mantine/core';
import { useState } from 'react';
import { Link } from 'react-router';
import type { components } from '../../api/schema';
import { PageLayout, TeacherTableLayout } from '../../app/layouts/PageLayouts';
import { useAuthSession } from '../auth/authQueries';
import { dateTime, errorText, loadState } from '../tasks/helpers';
import { TaskFailure } from '../tasks/shared';
import { useMembershipDecision, usePendingMemberships } from './memberships';

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

export function HeadRequestsNotice() {
  const query = usePendingMemberships();
  const count = query.data?.items.length ?? 0;
  if (count === 0) return null;
  return <Anchor component={Link} to="/memberships" c="var(--cr-link)">Ждут подтверждения: {count}</Anchor>;
}

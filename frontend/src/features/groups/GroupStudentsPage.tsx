import { useLoaderTiming } from '../../app/loaderTiming';
import { BrandLoader } from '../../theme/Brand';
import { Anchor } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router';
import { api } from '../../api/client';
import { PageLayout, TeacherTableLayout } from '../../app/layouts/PageLayouts';
import { useAuthSession } from '../auth/authQueries';
import { dataOf, dateTime, isAccessFailure, loadState } from '../tasks/helpers';
import { TaskFailure } from '../tasks/shared';
import { SetPasswordDialog } from './SetPasswordDialog';

function Students({ groupId }: { groupId: number }) {
  const query = useQuery({ queryKey: ['group-students', groupId], queryFn: () => dataOf(api.GET('/api/v1/groups/{groupId}/students', { params: { path: { groupId } } })) });
  const timing = useLoaderTiming(query.isPending);
  if (!timing.resultReady) return <PageLayout title="Студенты группы">{timing.indicatorVisible && <BrandLoader />}</PageLayout>;
  if (isAccessFailure(query.error)) return <TaskFailure error={query.error} />;
  return <PageLayout title="Студенты группы"><TeacherTableLayout caption="Студенты" rows={query.data?.items ?? []} keyForRow={(r) => r.id} state={loadState(query, query.data?.items.length === 0, 'В группе пока нет студентов')}
    columns={[{ key: 'name', title: 'ФИО', render: (r) => <Anchor component={Link} to={`/users/${r.id}`}>{r.fullName}</Anchor> }, { key: 'email', title: 'Email', render: (r) => r.email },
      { key: 'membership', title: 'Членство', render: (r) => r.membershipState === 'ACTIVE' ? 'подтверждено' : 'ждёт подтверждения' },
      { key: 'audit', title: 'Пароль выставлен', render: (r) => r.passwordSetAt ? dateTime(r.passwordSetAt) : '—' },
      { key: 'actions', title: 'Действия', render: (r) => <SetPasswordDialog user={{ ...r, role: 'STUDENT' }} /> }]} /></PageLayout>;
}
export function GroupStudentsPage() {
  const user = useAuthSession().session.data, { id } = useParams();
  return user?.role === 'TEACHER' ? <Students groupId={Number(id)} /> : <TaskFailure forbidden />;
}

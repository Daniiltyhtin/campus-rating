import { Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router';
import { api } from '../../../api/client';
import { PageLayout } from '../../../app/layouts/PageLayouts';
import { AssignmentDetails } from '../../tasks/TaskPage';
import { dataOf, isAccessFailure } from '../../tasks/helpers';
import { TaskFailure } from '../../tasks/shared';
import { AdminOnly } from '../shared';

function Submission({ id }: { id: number }) {
  const query = useQuery({ queryKey: ['assignment', id], queryFn: () => dataOf(api.GET('/api/v1/assignments/{assignmentId}', { params: { path: { assignmentId: id } } })) });
  if (isAccessFailure(query.error)) return <TaskFailure error={query.error} />;
  return <PageLayout title={query.data?.checkpoint.title ?? 'Материалы сдачи'}>
    {query.data && <Text>{query.data.student.fullName} · {query.data.group.name}</Text>}
    <AssignmentDetails assignmentId={id} readOnly />
  </PageLayout>;
}
export function AdminSubmissionPage() { const { assignmentId } = useParams(); return <AdminOnly><Submission id={Number(assignmentId)} /></AdminOnly>; }

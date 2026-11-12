import { Stack, Tabs } from '@mantine/core';
import { useEffect, useRef, useState } from 'react';
import { useLocation } from 'react-router';
import { TeacherTableLayout } from '../../../app/layouts/PageLayouts';
import { useAuthSession } from '../../auth/authQueries';
import { TeacherLayout, TeacherLink, TeacherStatus } from './TeacherShared';
import { useTeachers, teacherState } from './queries';

function TeacherList({ viewerId }: { viewerId: number }) {
  const [tab, setTab] = useState('pending'), query = useTeachers(viewerId), location = useLocation(), root = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (location.state?.focusList) { const heading = root.current?.querySelector('h1'); if (heading) { heading.tabIndex = -1; heading.focus(); } }
  }, [location.key, location.state]);
  const items = query.data?.items.filter((teacher) => tab === 'all' || !teacher.teacherApproved) ?? [];
  return <div ref={root}><TeacherLayout title="Преподаватели"><Stack gap="lg"><TeacherLink to="/admin/directory">Справочники</TeacherLink>
    <Tabs value={tab} onChange={(value) => { if (value === 'all' || value === 'pending') setTab(value); }}><Tabs.List>
      <Tabs.Tab value="pending">Заявки</Tabs.Tab><Tabs.Tab value="all">Все преподаватели</Tabs.Tab>
    </Tabs.List></Tabs>
    <TeacherTableLayout caption={tab === 'pending' ? 'Заявки преподавателей' : 'Все преподаватели'} rows={items} keyForRow={(row) => row.id}
      state={teacherState(query, items.length === 0, tab === 'pending' ? 'Заявок пока нет.' : 'Преподавателей пока нет.')}
      columns={[{ key: 'name', title: 'ФИО', render: (row) => <TeacherLink to={`/admin/directory/teachers/${row.id}`}>{row.fullName}</TeacherLink> },
        { key: 'email', title: 'Email', render: (row) => row.email }, { key: 'department', title: 'Кафедра и факультет', render: (row) => <Stack gap="xs"><span>{row.department.name}</span><span className="cr-caption">{row.department.faculty.name}</span></Stack> },
        { key: 'status', title: 'Статус', render: (row) => <TeacherStatus approved={row.teacherApproved} /> }]} />
  </Stack></TeacherLayout></div>;
}
export function TeacherListPage() {
  const { session } = useAuthSession();
  return session.data?.role === 'ADMIN' ? <TeacherList viewerId={session.data.id} /> : <TeacherLayout title="Нет доступа"><p>Раздел доступен администратору.</p></TeacherLayout>;
}

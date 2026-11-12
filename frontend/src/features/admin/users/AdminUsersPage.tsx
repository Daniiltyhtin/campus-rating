import { Anchor, Button, NativeSelect, TextInput } from '@mantine/core';
import { useInfiniteQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { api } from '../../../api/client';
import { PageLayout, TeacherTableLayout } from '../../../app/layouts/PageLayouts';
import { dataOf, dateTime, loadState } from '../../tasks/helpers';
import { AdminOnly } from '../shared';
import { SetPasswordDialog } from '../../groups/SetPasswordDialog';

const roleNames = { STUDENT: 'Студент', TEACHER: 'Преподаватель', ADMIN: 'Администратор' };
function Users() {
  const [search, setSearch] = useState(''), [role, setRole] = useState('');
  const query = useInfiniteQuery({ queryKey: ['admin-users', search.trim(), role], initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => dataOf(api.GET('/api/v1/admin/users', { params: { query: { search, role: role || undefined, cursor: pageParam } } })),
    getNextPageParam: (page) => page.nextCursor ?? undefined });
  const items = query.data?.pages.flatMap((p) => p.items) ?? [];
  return <PageLayout title="Пользователи"><TextInput label="Поиск по ФИО или email" value={search} onChange={(e) => setSearch(e.currentTarget.value)} />
    <NativeSelect label="Роль" value={role} onChange={(e) => setRole(e.currentTarget.value)} data={[{ value: '', label: 'Все' }, { value: 'STUDENT', label: 'Студенты' }, { value: 'TEACHER', label: 'Преподаватели' }, { value: 'ADMIN', label: 'Администраторы' }]} />
    <TeacherTableLayout caption="Пользователи" rows={items} keyForRow={(r) => r.id} state={loadState(query, items.length === 0, 'Никого не нашли')}
      columns={[{ key: 'name', title: 'ФИО', render: (r) => <Anchor component={Link} to={`/users/${r.id}`}>{r.fullName}</Anchor> },
        { key: 'email', title: 'Email', render: (r) => r.email }, { key: 'role', title: 'Роль', render: (r) => roleNames[r.role] },
        { key: 'group', title: 'Группа / кафедра', render: (r) => r.group?.name ?? r.department?.name ?? '—' },
        { key: 'audit', title: 'Пароль выставлен', render: (r) => r.passwordSetAt ? dateTime(r.passwordSetAt) : '—' },
        { key: 'actions', title: 'Действия', render: (r) => r.role !== 'ADMIN' && <SetPasswordDialog user={{ ...r, role: r.role }} /> }]} />
    {query.hasNextPage && <Button variant="default" loading={query.isFetchingNextPage} onClick={() => { void query.fetchNextPage(); }}>Загрузить ещё</Button>}
  </PageLayout>;
}
export function AdminUsersPage() { return <AdminOnly><Users /></AdminOnly>; }

import { Anchor, Button, NativeSelect, TextInput } from '@mantine/core';
import { useInfiniteQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import { api } from '../../../api/client';
import { PageLayout, TeacherTableLayout } from '../../../app/layouts/PageLayouts';
import { dataOf, loadState } from '../../tasks/helpers';
import { useAuthSession } from '../../auth/authQueries';
import { AdminOnly } from '../shared';
import { SetPasswordDialog } from '../../groups/SetPasswordDialog';
import { useTeachers } from '../teachers/queries';
import { statusNames, userFilters, userTime } from './contracts';

const roleNames = { STUDENT: 'Студент', TEACHER: 'Преподаватель', ADMIN: 'Администратор' };
function Users() {
  const [params, setParams] = useSearchParams(), filters = userFilters(params);
  const viewerId = useAuthSession().session.data!.id;
  const teachers = useTeachers(viewerId), count = teachers.data?.items.filter((teacher) => !teacher.teacherApproved).length ?? 0;
  const query = useInfiniteQuery({ queryKey: ['admin-users', viewerId, filters], initialPageParam: undefined as string | undefined, gcTime: 0,
    queryFn: ({ pageParam, signal }) => dataOf(api.GET('/api/v1/admin/users', { signal, params: { query: { ...filters, cursor: pageParam } } })),
    getNextPageParam: (page) => page.nextCursor ?? undefined });
  function change(key: string, value: string) {
    setParams((previous) => { const next = new URLSearchParams(previous); if (value) next.set(key, value); else next.delete(key); return next; }, { replace: true });
  }
  const items = query.data?.pages.flatMap((p) => p.items) ?? [];
  return <PageLayout title="Пользователи">
    {count > 0 && !teachers.isError && <Button variant="default" onClick={() => setParams({ role: 'TEACHER', status: 'PENDING', ...(filters.sort === 'name' ? {} : { sort: filters.sort! }) })}>Заявки преподавателей ({count})</Button>}
    <TextInput label="Поиск по ФИО или email" value={filters.search} onChange={(e) => change('search', e.currentTarget.value)} />
    <NativeSelect label="Роль" value={filters.role ?? ''} onChange={(e) => change('role', e.currentTarget.value)} data={[{ value: '', label: 'Все' }, { value: 'STUDENT', label: 'Студенты' }, { value: 'TEACHER', label: 'Преподаватели' }, { value: 'ADMIN', label: 'Администраторы' }]} />
    <NativeSelect label="Статус" value={filters.status ?? ''} onChange={(e) => change('status', e.currentTarget.value)} data={[{ value: '', label: 'Все' }, ...Object.entries(statusNames).map(([value, label]) => ({ value, label }))]} />
    <NativeSelect label="Сортировка" value={filters.sort} onChange={(e) => change('sort', e.currentTarget.value)} data={[{ value: 'name', label: 'ФИО' }, { value: 'created', label: 'Создан' }, { value: 'lastLogin', label: 'Последний вход' }]} />
    <TeacherTableLayout caption="Пользователи" rows={items} keyForRow={(r) => r.id} state={loadState(query, items.length === 0, 'Никого не нашли')}
      columns={[{ key: 'name', title: 'ФИО', render: (r) => <Anchor component={Link} to={r.role === 'TEACHER' ? `/admin/users/teachers/${r.id}` : `/users/${r.id}`}>{r.fullName}</Anchor> },
        { key: 'email', title: 'Email', render: (r) => r.email }, { key: 'role', title: 'Роль', render: (r) => roleNames[r.role] },
        { key: 'group', title: 'Группа / кафедра', render: (r) => r.group?.name ?? (r.department ? `${r.department.name} · ${r.department.faculty.name}` : '—') },
        { key: 'status', title: 'Статус', render: (r) => r.status ? <span className={`cr-status cr-status-${['APPROVED', 'MEMBERSHIP_ACTIVE'].includes(r.status) ? 'accepted' : 'in-progress'}`}>{statusNames[r.status]}</span> : '—' },
        { key: 'created', title: 'Создан', render: (r) => userTime(r.createdAt) },
        { key: 'login', title: 'Последний вход', render: (r) => userTime(r.lastLoginAt) },
        { key: 'audit', title: 'Пароль выставлен', render: (r) => userTime(r.passwordSetAt) },
        { key: 'actions', title: 'Действия', render: (r) => r.role !== 'ADMIN' && <SetPasswordDialog user={{ ...r, role: r.role }} /> }]} />
    {query.hasNextPage && <Button variant="default" loading={query.isFetchingNextPage} onClick={() => { void query.fetchNextPage(); }}>Загрузить ещё</Button>}
  </PageLayout>;
}
export function AdminUsersPage() { return <AdminOnly><Users /></AdminOnly>; }

import { Anchor, Button, NativeSelect, Text, TextInput, UnstyledButton } from '@mantine/core';
import { useInfiniteQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import { api } from '../../../api/client';
import { PageLayout, TeacherTableLayout } from '../../../app/layouts/PageLayouts';
import { dataOf, loadState } from '../../tasks/helpers';
import { useAuthSession } from '../../auth/authQueries';
import { AdminOnly } from '../shared';
import { SetPasswordDialog } from '../../groups/SetPasswordDialog';
import { useTeachers } from '../teachers/queries';
import { requestsTitle, statusNames, userFilters, userTime } from './contracts';
import './users.css';

const roleNames = { STUDENT: 'Студент', TEACHER: 'Преподаватель', ADMIN: 'Администратор' };
const warning = <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"
  strokeLinejoin="round" aria-hidden="true"><path d="M12 3 2.5 20h19z" /><path d="M12 10v4M12 17h.01" /></svg>;
const magnifier = <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"
  aria-hidden="true"><circle cx="11" cy="11" r="6" /><path d="m20 20-4.5-4.5" /></svg>;

function Users() {
  const [params, setParams] = useSearchParams(), filters = userFilters(params);
  const viewerId = useAuthSession().session.data!.id;
  const teachers = useTeachers(viewerId), pending = teachers.data?.items.filter((teacher) => !teacher.teacherApproved) ?? [];
  const query = useInfiniteQuery({ queryKey: ['admin-users', viewerId, filters], initialPageParam: undefined as string | undefined, gcTime: 0,
    queryFn: ({ pageParam, signal }) => dataOf(api.GET('/api/v1/admin/users', { signal, params: { query: { ...filters, cursor: pageParam } } })),
    getNextPageParam: (page) => page.nextCursor ?? undefined });
  function change(key: string, value: string) {
    setParams((previous) => { const next = new URLSearchParams(previous); if (value) next.set(key, value); else next.delete(key); return next; }, { replace: true });
  }
  const items = query.data?.pages.flatMap((p) => p.items) ?? [];
  const filtered = Boolean(filters.search || filters.role || filters.status || filters.sort !== 'name');
  const first = pending[0];
  return <PageLayout title="Пользователи" description="Студенты, преподаватели и администраторы стенда">
    {first && !teachers.isError && <div className="admin-users-requests" role="status">
      <span className="admin-users-requests-icon">{warning}</span>
      <div className="admin-users-requests-text">
        <strong>{requestsTitle(pending.length)}</strong>
        <span>{pending.length === 1 ? `${first.fullName} · ${first.department.name}` : `${first.fullName} и ещё ${pending.length - 1}`}</span>
      </div>
      <Button onClick={() => setParams({ role: 'TEACHER', status: 'PENDING', ...(filters.sort === 'name' ? {} : { sort: filters.sort! }) })}>Показать заявки</Button>
    </div>}
    <form className="admin-users-filters" role="search" onSubmit={(event) => event.preventDefault()}>
      <TextInput label="Поиск" aria-label="Поиск по ФИО или email" placeholder="ФИО или email" leftSection={magnifier}
        value={filters.search} onChange={(e) => change('search', e.currentTarget.value)} />
      <NativeSelect label="Роль" value={filters.role ?? ''} onChange={(e) => change('role', e.currentTarget.value)} data={[{ value: '', label: 'Все' }, { value: 'STUDENT', label: 'Студенты' }, { value: 'TEACHER', label: 'Преподаватели' }, { value: 'ADMIN', label: 'Администраторы' }]} />
      <NativeSelect label="Статус" value={filters.status ?? ''} onChange={(e) => change('status', e.currentTarget.value)} data={[{ value: '', label: 'Все' }, ...Object.entries(statusNames).map(([value, label]) => ({ value, label }))]} />
      <NativeSelect label="Сортировка" value={filters.sort} onChange={(e) => change('sort', e.currentTarget.value)} data={[{ value: 'name', label: 'ФИО' }, { value: 'created', label: 'Создан' }, { value: 'lastLogin', label: 'Последний вход' }]} />
    </form>
    <div className="admin-users-summary">
      <Text c="dimmed" size="sm">{query.data ? `Показано: ${items.length}${query.hasNextPage ? ', есть ещё' : ''}` : ''}</Text>
      {filtered && <UnstyledButton className="admin-users-reset" onClick={() => setParams({}, { replace: true })}>Сбросить фильтры</UnstyledButton>}
    </div>
    <div className="admin-users-table"><TeacherTableLayout caption="Пользователи" rows={items} keyForRow={(r) => r.id} state={loadState(query, items.length === 0, 'Никого не нашли')}
      columns={[{ key: 'name', title: 'ФИО', render: (r) => <Anchor component={Link} fw={700} to={r.role === 'TEACHER' ? `/admin/users/teachers/${r.id}` : `/users/${r.id}`}>{r.fullName}</Anchor> },
        { key: 'email', title: 'Email', render: (r) => <span className="admin-users-muted">{r.email}</span> },
        { key: 'role', title: 'Роль', render: (r) => roleNames[r.role] },
        { key: 'group', title: 'Группа / кафедра', render: (r) => r.group?.name ?? (r.department ? `${r.department.name} · ${r.department.faculty.name}` : '—') },
        { key: 'status', title: 'Статус', render: (r) => r.status ? <span className={`cr-status cr-status-${['APPROVED', 'MEMBERSHIP_ACTIVE'].includes(r.status) ? 'accepted' : 'submitted'}`}>{statusNames[r.status]}</span> : '—' },
        { key: 'created', title: 'Создан', render: (r) => <span className="admin-users-muted">{userTime(r.createdAt)}</span> },
        { key: 'login', title: 'Последний вход', render: (r) => <span className="admin-users-muted">{userTime(r.lastLoginAt)}</span> },
        { key: 'actions', title: 'Действия', render: (r) => r.role !== 'ADMIN' && <div className="admin-users-actions">
          <SetPasswordDialog user={{ ...r, role: r.role }} />
          {r.passwordSetAt && <span className="admin-users-muted admin-users-audit">Пароль выставлен <time dateTime={r.passwordSetAt}>{userTime(r.passwordSetAt)}</time></span>}
        </div> }]} /></div>
    {query.hasNextPage && <Button variant="default" loading={query.isFetchingNextPage} onClick={() => { void query.fetchNextPage(); }}>Загрузить ещё</Button>}
  </PageLayout>;
}
export function AdminUsersPage() { return <AdminOnly><Users /></AdminOnly>; }

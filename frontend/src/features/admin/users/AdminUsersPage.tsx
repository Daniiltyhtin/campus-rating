import { ActionButton } from '../../../app/ActionButton';
import { useTimedInfiniteQuery } from '../../../app/infiniteTiming';
import { Button, Anchor, NativeSelect, Text, TextInput, UnstyledButton } from '@mantine/core';
import { useCallback, useLayoutEffect, useRef } from 'react';
import { Link, useSearchParams } from 'react-router';
import { api } from '../../../api/client';
import { PageLayout, TeacherTableLayout } from '../../../app/layouts/PageLayouts';
import { dataOf, loadState } from '../../tasks/helpers';
import { useAuthSession } from '../../auth/authQueries';
import { AdminOnly } from '../shared';
import { SetPasswordDialog } from '../../groups/SetPasswordDialog';
import { useTeachers } from '../teachers/queries';
import { defaultDirection, requestsTitle, statusNames, userFilters, userTime } from './contracts';
import type { SortKey } from './contracts';
import './users.css';

const roleNames = { STUDENT: 'Студент', TEACHER: 'Преподаватель', ADMIN: 'Администратор' };
const warning = <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"
  strokeLinejoin="round" aria-hidden="true"><path d="M12 3 2.5 20h19z" /><path d="M12 10v4M12 17h.01" /></svg>;
const magnifier = <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"
  aria-hidden="true"><circle cx="11" cy="11" r="6" /><path d="m20 20-4.5-4.5" /></svg>;

function useUserSort(setParams: ReturnType<typeof useSearchParams>[1]) {
  const currentSetParams = useRef(setParams);
  useLayoutEffect(() => { currentSetParams.current = setParams; }, [setParams]);
  return useCallback((key: SortKey) => {
    currentSetParams.current((previous) => {
      const next = new URLSearchParams(previous);
      const current = userFilters(previous);
      const currentDirection = current.direction ?? defaultDirection(current.sort!);
      const flip = current.sort === key ? (currentDirection === 'asc' ? 'desc' : 'asc') : defaultDirection(key);
      if (key === 'lastName') next.delete('sort'); else next.set('sort', key);
      if (flip === defaultDirection(key)) next.delete('direction'); else next.set('direction', flip);
      return next;
    }, { replace: true });
  }, []);
}

function Users() {
  const [params, setParams] = useSearchParams(), filters = userFilters(params);
  const viewerId = useAuthSession().session.data!.id;
  const teachers = useTeachers(viewerId), pending = teachers.data?.items.filter((teacher) => !teacher.teacherApproved) ?? [];
  const query = useTimedInfiniteQuery({ queryKey: ['admin-users', viewerId, filters], initialPageParam: undefined as string | undefined, gcTime: 0,
    queryFn: ({ pageParam, signal }) => dataOf(api.GET('/api/v1/admin/users', { signal, params: { query: { ...filters, cursor: pageParam } } })),
    getNextPageParam: (page) => page.nextCursor ?? undefined });
  function change(key: string, value: string) {
    setParams((previous) => { const next = new URLSearchParams(previous); if (value) next.set(key, value); else next.delete(key); return next; }, { replace: true });
  }
  const items = query.data?.pages.flatMap((p) => p.items) ?? [];
  const filtered = Boolean(filters.search || filters.role || filters.status || filters.sort !== 'lastName' || filters.direction);
  const direction = filters.direction ?? defaultDirection(filters.sort!);
  const sortBy = useUserSort(setParams);
  const sortable = (key: SortKey) => ({ direction: filters.sort === key ? direction : null, onSort: () => sortBy(key) });
  const first = pending[0];
  return <PageLayout title="Пользователи" description="Студенты, преподаватели и администраторы стенда" wide>
    {first && !teachers.isError && <div className="admin-users-requests" role="status">
      <span className="admin-users-requests-icon">{warning}</span>
      <div className="admin-users-requests-text">
        <strong>{requestsTitle(pending.length)}</strong>
        <span>{pending.length === 1 ? `${first.fullName} · ${first.department.name}` : `${first.fullName} и ещё ${pending.length - 1}`}</span>
      </div>
      <Button onClick={() => setParams({ role: 'TEACHER', status: 'PENDING', ...(filters.sort === 'lastName' ? {} : { sort: filters.sort! }) })}>Показать заявки</Button>
    </div>}
    <form className="admin-users-filters" role="search" onSubmit={(event) => event.preventDefault()}>
      <TextInput label="Поиск" aria-label="Поиск по ФИО или email" placeholder="ФИО или email" leftSection={magnifier}
        value={filters.search} onChange={(e) => change('search', e.currentTarget.value)} />
      <NativeSelect label="Роль" value={filters.role ?? ''} onChange={(e) => change('role', e.currentTarget.value)} data={[{ value: '', label: 'Все' }, { value: 'STUDENT', label: 'Студенты' }, { value: 'TEACHER', label: 'Преподаватели' }, { value: 'ADMIN', label: 'Администраторы' }]} />
      <NativeSelect label="Статус" value={filters.status ?? ''} onChange={(e) => change('status', e.currentTarget.value)} data={[{ value: '', label: 'Все' }, ...Object.entries(statusNames).map(([value, label]) => ({ value, label }))]} />
      <NativeSelect className="admin-users-sort" label="Сортировка" value={filters.sort} onChange={(e) => { setParams((previous) => { const next = new URLSearchParams(previous); next.delete('direction'); if (e.currentTarget.value === 'lastName') next.delete('sort'); else next.set('sort', e.currentTarget.value); return next; }, { replace: true }); }} data={[{ value: 'lastName', label: 'Фамилия' }, { value: 'firstName', label: 'Имя' }, { value: 'middleName', label: 'Отчество' }, { value: 'email', label: 'Email' }, { value: 'role', label: 'Роль' }, { value: 'unit', label: 'Группа / кафедра' }, { value: 'status', label: 'Статус' }, { value: 'created', label: 'Создан' }, { value: 'lastLogin', label: 'Последний вход' }]} />
    </form>
    <div className="admin-users-summary">
      <Text c="dimmed" size="sm">{query.data ? `Показано: ${items.length}${query.hasNextPage ? ', есть ещё' : ''}` : ''}</Text>
      {filtered && <UnstyledButton className="admin-users-reset" onClick={() => setParams({}, { replace: true })}>Сбросить фильтры</UnstyledButton>}
    </div>
    <div className="admin-users-table"><TeacherTableLayout caption="Пользователи" rows={items} keyForRow={(r) => r.id} state={loadState(query, items.length === 0, 'Никого не нашли')}
      columns={[{ key: 'lastName', title: 'Фамилия', className: 'admin-users-name-part', sort: sortable('lastName'), render: (r) => <Anchor component={Link} fw={700} to={r.role === 'TEACHER' ? `/admin/users/teachers/${r.id}` : `/users/${r.id}`}>{r.lastName}</Anchor> },
        { key: 'firstName', title: 'Имя', className: 'admin-users-name-part', sort: sortable('firstName'), render: (r) => r.firstName },
        { key: 'middleName', title: 'Отчество', className: 'admin-users-name-part', sort: sortable('middleName'), render: (r) => r.middleName ?? '—' },
        { key: 'name', title: 'ФИО', className: 'admin-users-full-name', render: (r) => <Anchor component={Link} fw={700} to={r.role === 'TEACHER' ? `/admin/users/teachers/${r.id}` : `/users/${r.id}`}>{r.fullName}</Anchor> },
        { key: 'email', title: 'Email', sort: sortable('email'), render: (r) => <span className="admin-users-muted admin-users-keep">{r.email}</span> },
        { key: 'role', title: 'Роль', sort: sortable('role'), render: (r) => <span className="admin-users-keep">{roleNames[r.role]}</span> },
        { key: 'group', title: 'Группа / кафедра', sort: sortable('unit'), render: (r) => r.group ? <span className="admin-users-keep">{r.group.name}</span> : r.department ? <span className="admin-users-unit"><span>{r.department.name}</span><span className="admin-users-muted admin-users-small">{r.department.faculty.name}</span></span> : '—' },
        { key: 'status', title: 'Статус', sort: sortable('status'), className: 'admin-users-status', render: (r) => r.status ? <span className={`cr-status cr-status-${['APPROVED', 'MEMBERSHIP_ACTIVE'].includes(r.status) ? 'accepted' : 'submitted'}`}>{statusNames[r.status]}</span> : '—' },
        { key: 'created', title: 'Создан', sort: sortable('created'), render: (r) => <span className="admin-users-muted admin-users-keep">{userTime(r.createdAt)}</span> },
        { key: 'login', title: 'Последний вход', sort: sortable('lastLogin'), render: (r) => <span className="admin-users-muted admin-users-keep">{userTime(r.lastLoginAt)}</span> },
        { key: 'actions', title: 'Действия', render: (r) => r.role !== 'ADMIN' && <div className="admin-users-actions">
          <SetPasswordDialog user={{ ...r, role: r.role }} />
          {r.passwordSetAt && <span className="admin-users-muted admin-users-audit">Пароль выставлен <time dateTime={r.passwordSetAt}>{userTime(r.passwordSetAt)}</time></span>}
        </div> }]} /></div>
    {query.hasNextPage && <ActionButton variant="default" pending={query.isFetchingNextPage} onClick={() => { void query.fetchNextPage(); }}>Загрузить ещё</ActionButton>}
  </PageLayout>;
}
export function AdminUsersPage() { return <AdminOnly><Users /></AdminOnly>; }

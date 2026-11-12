import { http, HttpResponse } from 'msw';
import { currentUser } from '../features/auth/contracts';
import type { Teacher } from '../features/admin/teachers/contracts';
import type { UserRow } from '../features/admin/users/contracts';
import { userFilters } from '../features/admin/users/contracts';
import type { Clock } from '../features/activities/time';
import { readUser, validCsrf } from './auth';
import { examples } from './examples.generated';

export function createUsersMockHandlers(teachers: () => Teacher[], group: () => UserRow['group'], clock: Clock) {
  const createdAt = clock.now().toISOString(), audit = new Map<number, string>();
  const accounts = examples.find((item) => item.operationId === 'getCurrentUser')!.responses
    .filter((item) => item.status === 200).map((item) => currentUser(structuredClone(item.body)));
  function rows(): UserRow[] {
    const others: UserRow[] = accounts.filter((user) => user.role !== 'TEACHER').map((user) => ({
      id: user.id, fullName: user.fullName, email: user.email, role: user.role,
      group: user.role === 'STUDENT' ? group() : null, department: null,
      status: user.role === 'STUDENT' ? 'MEMBERSHIP_ACTIVE' : null, createdAt, lastLoginAt: null, passwordSetAt: audit.get(user.id) ?? null,
    }));
    return [...others, ...teachers().map((teacher): UserRow => ({ ...teacher, role: 'TEACHER', group: null,
      status: teacher.teacherApproved ? 'APPROVED' : 'PENDING', createdAt, lastLoginAt: null, passwordSetAt: audit.get(teacher.id) ?? null }))];
  }
  function access() { const user = readUser(); return !user ? problem('UNAUTHENTICATED', 401) : user.role !== 'ADMIN' ? problem('FORBIDDEN', 403) : null; }
  return [
    http.get('*/api/v1/admin/users', ({ request }) => {
      const denied = access(); if (denied) return denied;
      const params = new URL(request.url).searchParams, filters = userFilters(params), signature = JSON.stringify(filters);
      let offset = 0;
      if (params.has('cursor')) {
        try {
          const cursor: unknown = JSON.parse(atob(params.get('cursor')!));
          if (!Array.isArray(cursor) || cursor[1] !== signature || !Number.isSafeInteger(cursor[0]) || cursor[0] < 0) return problem('VALIDATION_FAILED', 400);
          offset = Number(cursor[0]);
        } catch { return problem('VALIDATION_FAILED', 400); }
      }
      const search = filters.search!.trim().toLocaleLowerCase('ru');
      const items = rows().filter((row) => (!filters.role || row.role === filters.role) && (!filters.status || row.status === filters.status)
        && (search.length < 2 || row.fullName.toLocaleLowerCase('ru').includes(search) || row.email.toLocaleLowerCase('ru').includes(search)));
      items.sort((a, b) => {
        const byName = a.fullName.localeCompare(b.fullName, 'ru') || a.id - b.id;
        if (filters.sort === 'created') return Date.parse(b.createdAt) - Date.parse(a.createdAt) || byName;
        if (filters.sort === 'lastLogin') return (a.lastLoginAt === null ? b.lastLoginAt === null ? 0 : -1 : b.lastLoginAt === null ? 1 : Date.parse(a.lastLoginAt) - Date.parse(b.lastLoginAt)) || byName;
        return byName;
      });
      return HttpResponse.json({ items: items.slice(offset, offset + 50), nextCursor: offset + 50 < items.length ? btoa(JSON.stringify([offset + 50, signature])) : null });
    }),
    http.post('*/api/v1/users/:userId/temporary-password', ({ request, params }) => {
      const denied = access(); if (denied) return denied;
      if (!validCsrf(request)) return problem('CSRF_INVALID', 403);
      const target = rows().find((row) => row.id === Number(params.userId));
      if (!target) return problem('NOT_FOUND', 404);
      if (target.role === 'ADMIN') return problem('FORBIDDEN', 403);
      audit.set(target.id, clock.now().toISOString());
      return HttpResponse.json({ password: 'Demo23456789' }, { headers: { 'Cache-Control': 'no-store' } });
    }),
  ];
}
function problem(code: string, status: number) { return HttpResponse.json({ type: 'about:blank', title: 'Ошибка', code, status }, { status }); }

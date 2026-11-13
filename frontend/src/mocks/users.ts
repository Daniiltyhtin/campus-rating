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
      ...nameParts(user.fullName), id: user.id, fullName: user.fullName, email: user.email, role: user.role,
      group: user.role === 'STUDENT' ? group() : null, department: null,
      status: user.role === 'STUDENT' ? 'MEMBERSHIP_ACTIVE' : null, createdAt, lastLoginAt: null, passwordSetAt: audit.get(user.id) ?? null,
    }));
    return [...others, ...teachers().map((teacher): UserRow => ({ ...teacher, ...nameParts(teacher.fullName), role: 'TEACHER', group: null,
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
        const compare = (left: string | number | null, right: string | number | null, nullsLast = false) => {
          if (left === null || right === null) return left === right ? 0 : (left === null ? 1 : -1) * (nullsLast ? 1 : -1);
          return typeof left === 'number' && typeof right === 'number' ? left - right : String(left).localeCompare(String(right), 'ru');
        };
        const key = filters.sort ?? 'lastName';
        const direction = filters.direction ?? (key === 'created' ? 'desc' : 'asc');
        const roleRank = { ADMIN: 1, TEACHER: 2, STUDENT: 3 };
        const statusRank = { PENDING: 1, MEMBERSHIP_PENDING: 2, MEMBERSHIP_REJECTED: 3, APPROVED: 4, MEMBERSHIP_ACTIVE: 5 };
        const value = (row: UserRow) => {
          switch (key) {
            case 'created': return Date.parse(row.createdAt);
            case 'lastLogin': return row.lastLoginAt === null ? null : Date.parse(row.lastLoginAt);
            case 'role': return roleRank[row.role];
            case 'status': return row.status === null ? null : statusRank[row.status];
            case 'unit': return row.group?.name ?? row.department?.name ?? null;
            case 'email': return row.email.toLocaleLowerCase('ru');
            default: return row[key];
          }
        };
        const left = value(a), right = value(b);
        const primary = left === null || right === null
          ? compare(left, right, key !== 'lastLogin' || direction !== 'asc')
          : compare(left, right) * (direction === 'asc' ? 1 : -1);
        const ties = key === 'firstName' ? ['lastName', 'middleName'] as const
          : key === 'middleName' ? ['lastName', 'firstName'] as const
          : key === 'lastName' ? ['firstName', 'middleName'] as const
          : ['lastName', 'firstName', 'middleName'] as const;
        return primary || ties.reduce((result, field) => result || compare(a[field], b[field]), 0) || a.id - b.id;
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

function nameParts(fullName: string) {
  const [lastName = '', firstName, ...middle] = fullName.trim().split(/\s+/);
  return { lastName, firstName: firstName ?? lastName, middleName: middle.length ? middle.join(' ') : null };
}

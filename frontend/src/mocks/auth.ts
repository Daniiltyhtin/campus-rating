import { http, HttpResponse } from 'msw';
import { currentUser } from '../features/auth/contracts';
import type { CurrentUser } from '../features/auth/contracts';
import { examples } from './examples.generated';

function cookie(name: string) {
  return document.cookie.split('; ').find((part) => part.startsWith(`${name}=`))?.slice(name.length + 1);
}
function problem(code: string, status: number) {
  return HttpResponse.json({ type: 'about:blank', title: 'Ошибка', status, code }, { status, headers: { 'Content-Type': 'application/problem+json' } });
}
export function readUser() {
  try { const value = cookie('campus-mock-user'); return value ? currentUser(JSON.parse(decodeURIComponent(value))) : null; }
  catch { return null; }
}
function invalidateCsrf() { document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'; }
function saveUser(user: CurrentUser | null) {
  document.cookie = user ? `campus-mock-user=${encodeURIComponent(JSON.stringify(user))}; Path=/; SameSite=Lax`
    : 'campus-mock-user=; Max-Age=0; Path=/';
}
export function validCsrf(request: Request) {
  const token = cookie('XSRF-TOKEN');
  return Boolean(token && request.headers.get('X-XSRF-TOKEN') === decodeURIComponent(token));
}
function credentials(value: unknown): value is { email: string; password: string } {
  return typeof value === 'object' && value !== null && 'email' in value && typeof value.email === 'string'
    && 'password' in value && typeof value.password === 'string';
}
function directoryItems(operationId: string): Record<string, unknown>[] {
  const body = examples.find((item) => item.operationId === operationId)?.responses.find((item) => item.status === 200)?.body;
  if (!body || typeof body !== 'object' || !('items' in body) || !Array.isArray(body.items)) throw new Error('Отсутствует пример справочника');
  return body.items.filter((item: unknown): item is Record<string, unknown> => typeof item === 'object' && item !== null);
}
function facultyItems(operationId: string, facultyId: unknown) {
  return directoryItems(operationId).filter((item) => {
    const value = item.faculty;
    return value && typeof value === 'object' && 'id' in value && value.id === Number(facultyId);
  });
}
export function createAuthMockHandlers() {
  const users = examples.find((item) => item.operationId === 'getCurrentUser')!.responses
    .filter((item) => item.status === 200).map((item) => currentUser(structuredClone(item.body)));
  const accounts = new Map(users.map((user) => [user.email.toLowerCase(), { user, password: `demo-${user.role.toLowerCase()}-1` }]));
  return [
    http.get('*/api/v1/directory/faculties/:facultyId/groups', ({ params }) => HttpResponse.json({ items: facultyItems('listFacultyGroups', params.facultyId) })),
    http.get('*/api/v1/directory/faculties/:facultyId/departments', ({ params }) => HttpResponse.json({ items: facultyItems('listFacultyDepartments', params.facultyId) })),
    http.get('*/api/v1/auth/me', () => { const user = readUser(); return user ? HttpResponse.json(user) : problem('UNAUTHENTICATED', 401); }),
    http.get('*/api/v1/auth/csrf', () => {
      document.cookie = `XSRF-TOKEN=${crypto.randomUUID()}; Path=/; SameSite=Lax`;
      return new HttpResponse(null, { status: 204 });
    }),
    http.post('*/api/v1/auth/login', async ({ request }) => {
      if (!validCsrf(request)) return problem('CSRF_INVALID', 403);
      const body: unknown = await request.json();
      const account = credentials(body) ? accounts.get(body.email.toLowerCase()) : undefined;
      if (!credentials(body) || !account || account.password !== body.password) return problem('INVALID_CREDENTIALS', 401);
      saveUser(account.user); invalidateCsrf();
      return HttpResponse.json(account.user);
    }),
    http.post('*/api/v1/auth/logout', ({ request }) => {
      if (!validCsrf(request)) return problem('CSRF_INVALID', 403);
      if (!readUser()) return problem('UNAUTHENTICATED', 401);
      saveUser(null); invalidateCsrf();
      return new HttpResponse(null, { status: 204 });
    }),
    http.post('*/api/v1/auth/password', async ({ request }) => {
      if (!validCsrf(request)) return problem('CSRF_INVALID', 403);
      const user = readUser();
      if (!user) return problem('UNAUTHENTICATED', 401);
      const body: unknown = await request.json();
      if (!body || typeof body !== 'object' || !('currentPassword' in body) || !('newPassword' in body) || typeof body.newPassword !== 'string') return problem('INTERNAL_ERROR', 500);
      const account = accounts.get(user.email.toLowerCase());
      if (!account || body.currentPassword !== account.password) return HttpResponse.json({ type: 'about:blank', title: 'Ошибка', status: 400, code: 'VALIDATION_FAILED',
        fieldErrors: [{ field: 'currentPassword', code: 'MISMATCH', message: 'Текущий пароль указан неверно.' }] }, { status: 400, headers: { 'Content-Type': 'application/problem+json' } });
      if (body.newPassword === account.password) return problem('PASSWORD_REUSED', 400);
      account.password = body.newPassword; account.user = { ...user, mustChangePassword: false }; saveUser(account.user); invalidateCsrf();
      return new HttpResponse(null, { status: 204 });
    }),
    http.post('*/api/v1/auth/register/:role', async ({ request, params }) => {
      if (!validCsrf(request)) return problem('CSRF_INVALID', 403);
      const body: unknown = await request.json();
      if (!credentials(body) || !('fullName' in body) || typeof body.fullName !== 'string') return problem('INTERNAL_ERROR', 500);
      const teacherEmail = users.some((user) => user.role === 'TEACHER' && user.email.toLowerCase() === body.email.toLowerCase());
      if (params.role === 'student' && teacherEmail) return problem('EMAIL_RESERVED_FOR_TEACHER', 409);
      if (accounts.has(body.email.toLowerCase())) return problem('EMAIL_TAKEN', 409);
      const template = users.find((user) => user.role === (params.role === 'student' ? 'STUDENT' : 'TEACHER'))!;
      const target = template.role === 'STUDENT'
        ? facultyItems('listFacultyGroups', 'facultyId' in body ? body.facultyId : undefined).find((item) => 'groupId' in body && item.id === body.groupId)
        : directoryItems('listFacultyDepartments').find((item) => 'departmentId' in body && item.id === body.departmentId);
      if (!target) return HttpResponse.json({ type: 'about:blank', title: 'Ошибка', status: 400, code: 'VALIDATION_FAILED', fieldErrors: [{ field: template.role === 'STUDENT' ? 'groupId' : 'departmentId', code: 'MISMATCH', message: 'Выбор не соответствует факультету.' }] }, { status: 400, headers: { 'Content-Type': 'application/problem+json' } });
      const user = currentUser({ ...template, id: 10_000 + accounts.size, fullName: body.fullName, email: body.email, mustChangePassword: false,
        ...(template.role === 'TEACHER' ? { teacherApproved: false, department: target } : { group: target }) });
      accounts.set(user.email.toLowerCase(), { user, password: body.password });
      return HttpResponse.json({ userId: user.id, nextStep: 'LOGIN' }, { status: 201 });
    }),
  ];
}

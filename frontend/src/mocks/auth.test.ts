import { api, ApiError } from '../api/client';
import { server } from './server';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => { server.resetHandlers(); document.cookie = 'campus-mock-user=; Max-Age=0; Path=/'; document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'; });
afterAll(() => server.close());

it('starts as a guest and rotates CSRF around a mock cookie session', async () => {
  await expect(api.GET('/api/v1/auth/me')).rejects.toMatchObject({ code: 'UNAUTHENTICATED' });
  await api.GET('/api/v1/auth/csrf');
  const token = document.cookie;
  expect(token).toContain('XSRF-TOKEN=');
  const login = await api.POST('/api/v1/auth/login', { body: { email: 'smirnova@student.campus.test', password: 'demo-student-1' } });
  expect(login.data).toMatchObject({ id: 1001, role: 'STUDENT' });
  await api.GET('/api/v1/auth/csrf');
  expect(document.cookie).not.toBe(token);
  expect((await api.GET('/api/v1/auth/me')).data).toMatchObject({ id: 1001 });
  await api.POST('/api/v1/auth/logout');
  await expect(api.GET('/api/v1/auth/me')).rejects.toBeInstanceOf(ApiError);
});

it('rejects a mock mutation without CSRF and an incorrect login password', async () => {
  await expect(api.POST('/api/v1/auth/login', { body: { email: 'smirnova@student.campus.test', password: 'wrong' } })).rejects.toMatchObject({ code: 'CSRF_INVALID' });
  await api.GET('/api/v1/auth/csrf');
  await expect(api.POST('/api/v1/auth/login', { body: { email: 'smirnova@student.campus.test', password: 'wrong' } })).rejects.toMatchObject({ code: 'INVALID_CREDENTIALS' });
});

it('keeps the selected group when a registered mock user signs in', async () => {
  await api.GET('/api/v1/auth/csrf');
  await api.POST('/api/v1/auth/register/student', { body: { lastName: 'Тестов', firstName: 'Алексей', email: 'selected-group@campus.test', password: 'new-password-1', facultyId: 1, groupId: 12 } });
  const result = await api.POST('/api/v1/auth/login', { body: { email: 'selected-group@campus.test', password: 'new-password-1' } });
  expect(result.data).toMatchObject({ role: 'STUDENT', group: { id: 12 } });
});

it('33-5 rejects a group from another faculty as a field mismatch', async () => {
  await api.GET('/api/v1/auth/csrf');
  await expect(api.POST('/api/v1/auth/register/student', { body: { lastName: 'Тест', firstName: 'Тест', email: 'mismatch@campus.test', password: 'new-password-1', facultyId: 2, groupId: 11 } })).rejects.toMatchObject({ code: 'VALIDATION_FAILED', problem: { status: 400, fieldErrors: [{ field: 'groupId', code: 'MISMATCH' }] } });
});
it('33-3 invalidates the previous token after every successful auth mutation', async () => {
  const post = (path: string, token: string, body?: unknown) => fetch(`http://localhost/api/v1/auth/${path}`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token }, body: body === undefined ? undefined : JSON.stringify(body) });
  const token = async () => { await api.GET('/api/v1/auth/csrf'); return document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))!.slice(11); };
  const login = { email: 'petrov@campus.test', password: 'demo-teacher-1' };
  let old = await token();
  expect((await post('login', old, login)).status).toBe(200);
  expect(await (await post('password', old, { currentPassword: login.password, newPassword: 'rotated-password-1' })).json()).toMatchObject({ code: 'CSRF_INVALID', status: 403 });
  old = await token();
  expect((await post('password', old, { currentPassword: login.password, newPassword: 'rotated-password-1' })).status).toBe(204);
  expect(await (await post('logout', old)).json()).toMatchObject({ code: 'CSRF_INVALID', status: 403 });
  old = await token();
  expect((await post('logout', old)).status).toBe(204);
  expect(await (await post('login', old, { ...login, password: 'rotated-password-1' })).json()).toMatchObject({ code: 'CSRF_INVALID', status: 403 });
  expect((await post('login', await token(), { ...login, password: 'rotated-password-1' })).status).toBe(200);
});


it.each([undefined, '', '   '])('Covers AE1, AE41: mock student optional middleName %s composes fullName', async (middleName) => {
  const email = `u24-${String(middleName)}@campus.test`;
  await api.GET('/api/v1/auth/csrf');
  await api.POST('/api/v1/auth/register/student', { body: {
    lastName: 'Иванова', firstName: 'Мария', ...(middleName === undefined ? {} : { middleName }), email, password: 'new-password-1', facultyId: 1, groupId: 11,
  } });
  await api.POST('/api/v1/auth/login', { body: { email, password: 'new-password-1' } });
  const me = (await api.GET('/api/v1/auth/me')).data;
  expect(me).toMatchObject({ fullName: 'Иванова Мария' });
  for (const field of ['lastName', 'firstName', 'middleName']) expect(me).not.toHaveProperty(field);
});

it('Covers AE2: mock teacher composes three name parts', async () => {
  await api.GET('/api/v1/auth/csrf');
  await api.POST('/api/v1/auth/register/teacher', { body: {
    lastName: 'Петров', firstName: 'Павел', middleName: 'Петрович', email: 'u24-teacher@campus.test', password: 'new-password-1', departmentId: 101,
  } });
  const login = await api.POST('/api/v1/auth/login', { body: { email: 'u24-teacher@campus.test', password: 'new-password-1' } });
  expect(login.data).toMatchObject({ fullName: 'Петров Павел Петрович' });
});

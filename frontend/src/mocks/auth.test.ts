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
  await api.POST('/api/v1/auth/register/student', { body: { fullName: 'Тестов Алексей', email: 'selected-group@campus.test', password: 'new-password-1', facultyId: 1, groupId: 12 } });
  const result = await api.POST('/api/v1/auth/login', { body: { email: 'selected-group@campus.test', password: 'new-password-1' } });
  expect(result.data).toMatchObject({ role: 'STUDENT', group: { id: 12 } });
});

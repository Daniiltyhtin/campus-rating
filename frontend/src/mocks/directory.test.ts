import { afterAll, afterEach, beforeAll, beforeEach, expect, it } from 'vitest';
import { setupServer } from 'msw/node';
import { createApiClient } from '../api/client';
import { createAuthMockHandlers } from './auth';
import { createDirectoryMockHandlers } from './directory';

const server = setupServer();
const api = createApiClient('http://localhost');
beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
beforeEach(() => server.use(...createDirectoryMockHandlers(), ...createAuthMockHandlers()));
afterEach(() => {
  server.resetHandlers();
  document.cookie = 'campus-mock-user=; Max-Age=0; Path=/';
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/';
});
afterAll(() => server.close());
async function login() {
  await api.GET('/api/v1/auth/csrf');
  await api.POST('/api/v1/auth/login', { body: { email: 'admin@campus.test', password: 'demo-admin-1' } });
  await api.POST('/api/v1/auth/password', { body: { currentPassword: 'demo-admin-1', newPassword: 'changed-admin-1' } });
}
it('guards admin operations by session, password and role', async () => {
  await expect(api.GET('/api/v1/admin/directory/faculties')).rejects.toMatchObject({ code: 'UNAUTHENTICATED' });
  await api.GET('/api/v1/auth/csrf');
  await api.POST('/api/v1/auth/login', { body: { email: 'admin@campus.test', password: 'demo-admin-1' } });
  await expect(api.GET('/api/v1/admin/directory/faculties')).rejects.toMatchObject({ code: 'PASSWORD_CHANGE_REQUIRED' });
  await api.POST('/api/v1/auth/login', { body: { email: 'petrov@campus.test', password: 'demo-teacher-1' } });
  await expect(api.GET('/api/v1/admin/directory/groups')).rejects.toMatchObject({ code: 'FORBIDDEN' });
});
it('faculty create/update/delete is reflected by new public reads', async () => {
  await login();
  const created = (await api.POST('/api/v1/admin/directory/faculties', { body: { code: 'NEW', name: 'Новый факультет' } })).data!;
  expect((await api.GET('/api/v1/directory/faculties')).data!.items.some((row) => row.id === created.id)).toBe(true);
  await api.PUT('/api/v1/admin/directory/faculties/{id}', { params: { path: { id: created.id } }, body: { code: 'NEW2', name: 'Переименованный факультет' } });
  expect((await api.GET('/api/v1/directory/faculties')).data!.items.find((row) => row.id === created.id)!.code).toBe('NEW2');
  await api.DELETE('/api/v1/admin/directory/faculties/{id}', { params: { path: { id: created.id } } });
  expect((await api.GET('/api/v1/directory/faculties')).data!.items.some((row) => row.id === created.id)).toBe(false);
});
it('rejects duplicate codes, invalid faculty and deletion of occupied records', async () => {
  await login();
  await expect(api.POST('/api/v1/admin/directory/faculties', { body: { code: 'ФИТ', name: 'Дубликат' } })).rejects.toMatchObject({ code: 'DIRECTORY_CODE_TAKEN' });
  await expect(api.POST('/api/v1/admin/directory/groups', { body: { code: 'NEW', name: 'Группа', facultyId: 9999, admissionYear: 2026 } })).rejects.toMatchObject({ code: 'VALIDATION_FAILED' });
  await expect(api.DELETE('/api/v1/admin/directory/groups/{id}', { params: { path: { id: 11 } } })).rejects.toMatchObject({ code: 'DIRECTORY_IN_USE' });
  await expect(api.DELETE('/api/v1/admin/directory/departments/{id}', { params: { path: { id: 101 } } })).rejects.toMatchObject({ code: 'DIRECTORY_IN_USE' });
  await expect(api.DELETE('/api/v1/admin/directory/faculties/{id}', { params: { path: { id: 1 } } })).rejects.toMatchObject({ code: 'DIRECTORY_IN_USE' });
});
it('creates related rows, forbids CSRF-less mutations and handles stale ids', async () => {
  await login();
  const created = (await api.POST('/api/v1/admin/directory/groups', { body: { code: 'NEW', name: 'Новая группа', facultyId: 1, admissionYear: 9999 } })).data!;
  expect((await api.GET('/api/v1/directory/faculties/{facultyId}/groups', { params: { path: { facultyId: 1 } } })).data!.items.some((row) => row.id === created.id)).toBe(true);
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/';
  await expect(api.DELETE('/api/v1/admin/directory/groups/{id}', { params: { path: { id: created.id } } })).rejects.toMatchObject({ code: 'CSRF_INVALID' });
  await api.GET('/api/v1/auth/csrf');
  await api.DELETE('/api/v1/admin/directory/groups/{id}', { params: { path: { id: created.id } } });
  await expect(api.DELETE('/api/v1/admin/directory/groups/{id}', { params: { path: { id: created.id } } })).rejects.toMatchObject({ code: 'NOT_FOUND' });
});

import { afterAll, afterEach, beforeAll, beforeEach, expect, it } from 'vitest';
import { setupServer } from 'msw/node';
import { createApiClient } from '../api/client';
import { createAuthMockHandlers } from './auth';
import { createDirectoryMockHandlers, createDirectoryMockState } from './directory';
import { createTeacherMockHandlers } from './teachers';

const server = setupServer(), api = createApiClient('http://localhost');
beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
beforeEach(() => {
  const state = createDirectoryMockState();
  const faculty = { id: 2, code: 'ЭФ', name: 'Экономический факультет' };
  state.groups.push({ kind: 'groups', id: 21, code: 'EK-26', name: 'ЭК-26', faculty, admissionYear: 2026 });
  state.departments.push({ kind: 'departments', id: 201, code: 'ЭК', name: 'Кафедра экономики', faculty });
  server.use(...createTeacherMockHandlers(state, { now: () => new Date('2026-11-11T09:00:00Z') }), ...createDirectoryMockHandlers(state), ...createAuthMockHandlers());
});
afterEach(() => { server.resetHandlers(); document.cookie = 'campus-mock-user=; Max-Age=0; Path=/'; document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'; });
afterAll(() => server.close());
async function login() {
  await api.GET('/api/v1/auth/csrf');
  await api.POST('/api/v1/auth/login', { body: { email: 'admin@campus.test', password: 'demo-admin-1' } });
  await api.GET('/api/v1/auth/csrf');
  await api.POST('/api/v1/auth/password', { body: { currentPassword: 'demo-admin-1', newPassword: 'changed-admin-1' } });
  await api.GET('/api/v1/auth/csrf');
}
const path = (teacherId: number) => ({ path: { teacherId } });
it('requires a session, ADMIN role and changed password', async () => {
  await expect(api.GET('/api/v1/admin/teachers')).rejects.toMatchObject({ code: 'UNAUTHENTICATED' });
  await api.GET('/api/v1/auth/csrf');
  await api.POST('/api/v1/auth/login', { body: { email: 'admin@campus.test', password: 'demo-admin-1' } });
  await api.GET('/api/v1/auth/csrf');
  await expect(api.GET('/api/v1/admin/teachers')).rejects.toMatchObject({ code: 'PASSWORD_CHANGE_REQUIRED' });
  await api.POST('/api/v1/auth/login', { body: { email: 'petrov@campus.test', password: 'demo-teacher-1' } });
  await expect(api.GET('/api/v1/admin/teachers')).rejects.toMatchObject({ code: 'FORBIDDEN' });
});
it('approves using the selected department and returns its faculty scope', async () => {
  await login();
  expect((await api.GET('/api/v1/admin/teachers/{teacherId}/accessible-groups', { params: path(2002) })).data!.items).toEqual([]);
  const approved = (await api.POST('/api/v1/admin/teachers/{teacherId}/approval', { params: path(2002), body: { departmentId: 201 } })).data!;
  expect(approved.teacherApproved).toBe(true); expect(approved.department.id).toBe(201);
  expect((await api.GET('/api/v1/admin/teachers/{teacherId}/accessible-groups', { params: path(2002) })).data!.items.map((row) => row.id)).toEqual([21]);
  await api.POST('/api/v1/admin/teachers/{teacherId}/approval', { params: path(2002), body: { departmentId: 201 } });
  await expect(api.POST('/api/v1/admin/teachers/{teacherId}/approval', { params: path(2002), body: { departmentId: 101 } })).rejects.toMatchObject({ code: 'INVALID_STATE' });
  await expect(api.DELETE('/api/v1/admin/directory/departments/{id}', { params: { path: { id: 201 } } })).rejects.toMatchObject({ code: 'DIRECTORY_IN_USE' });
});
it('rejects only a pending teacher and removes its admin records', async () => {
  await login();
  await expect(api.DELETE('/api/v1/admin/teachers/{teacherId}/application', { params: path(2001) })).rejects.toMatchObject({ code: 'INVALID_STATE' });
  await api.DELETE('/api/v1/admin/teachers/{teacherId}/application', { params: path(2002) });
  await expect(api.GET('/api/v1/admin/teachers/{teacherId}', { params: path(2002) })).rejects.toMatchObject({ code: 'NOT_FOUND' });
  expect((await api.GET('/api/v1/admin/teachers')).data!.items.map((row) => row.id)).toEqual([2001]);
});
it('creates each strict grant idempotently and binds removal to teacherId', async () => {
  await login();
  const body = { kind: 'GROUP' as const, groupId: 21 };
  const first = await api.POST('/api/v1/admin/teachers/{teacherId}/grants', { params: path(2002), body });
  const again = await api.POST('/api/v1/admin/teachers/{teacherId}/grants', { params: path(2002), body });
  expect(first.response.status).toBe(201); expect(again.response.status).toBe(200); expect(again.data!.id).toBe(first.data!.id);
  const grantId = first.data!.id;
  await expect(api.DELETE('/api/v1/admin/teachers/{teacherId}/grants/{grantId}', { params: { path: { teacherId: 2001, grantId } } })).rejects.toMatchObject({ code: 'NOT_FOUND' });
  await api.DELETE('/api/v1/admin/teachers/{teacherId}/grants/{grantId}', { params: { path: { teacherId: 2002, grantId } } });
  expect((await api.GET('/api/v1/admin/teachers/{teacherId}/grants', { params: path(2002) })).data!.items).toEqual([]);
});
it('rejects invalid variants/references and CSRF-less writes', async () => {
  await login();
  const mixed = { kind: 'GROUP' as const, groupId: 11, facultyId: 2 };
  await expect(api.POST('/api/v1/admin/teachers/{teacherId}/grants', { params: path(2002), body: mixed })).rejects.toMatchObject({ code: 'VALIDATION_FAILED' });
  await expect(api.POST('/api/v1/admin/teachers/{teacherId}/grants', { params: path(2002), body: { kind: 'COURSE', course: 7 } })).rejects.toMatchObject({ code: 'VALIDATION_FAILED' });
  await expect(api.POST('/api/v1/admin/teachers/{teacherId}/approval', { params: path(2002), body: { departmentId: 9999 } })).rejects.toMatchObject({ code: 'VALIDATION_FAILED' });
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/';
  await expect(api.DELETE('/api/v1/admin/teachers/{teacherId}/application', { params: path(2002) })).rejects.toMatchObject({ code: 'CSRF_INVALID' });
});

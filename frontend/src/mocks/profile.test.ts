import { api } from '../api/client';
import { server } from './server';
beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => { server.resetHandlers(); document.cookie = 'campus-mock-user=; Max-Age=0; Path=/'; document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'; });
afterAll(() => server.close());
async function signIn(role: 'student' | 'teacher') {
  await api.GET('/api/v1/auth/csrf');
  await api.POST('/api/v1/auth/login', { body: { email: role === 'student' ? 'smirnova@student.campus.test' : 'petrov@campus.test', password: `demo-${role}-1` } });
}
it('denies a mock guest and reports an unknown profile', async () => {
  await expect(api.GET('/api/v1/users/{userId}/profile', { params: { path: { userId: 1001 } } })).rejects.toMatchObject({ code: 'UNAUTHENTICATED' });
  await signIn('student');
  await expect(api.GET('/api/v1/users/{userId}/profile', { params: { path: { userId: 9999 } } })).rejects.toMatchObject({ code: 'NOT_FOUND' });
});
it('returns own tasks for the owner and the correct target role for teacher', async () => {
  await signIn('student');
  const own = (await api.GET('/api/v1/users/{userId}/profile', { params: { path: { userId: 1001 } } })).data;
  expect(own).toMatchObject({ role: 'STUDENT', activeTasks: [{ view: 'OWN', assignmentId: 7001 }] });
  expect((await api.GET('/api/v1/users/{userId}/profile', { params: { path: { userId: 2001 } } })).data).toMatchObject({ role: 'TEACHER', id: 2001 });
});
it('returns public tasks to a foreign viewer and supports student1002 links', async () => {
  await signIn('teacher');
  const profile = (await api.GET('/api/v1/users/{userId}/profile', { params: { path: { userId: 1001 } } })).data;
  if (profile?.role !== 'STUDENT') throw new Error('Expected student');
  expect(profile.activeTasks[0]).toMatchObject({ view: 'PUBLIC' });
  expect(profile.activeTasks[0]).not.toHaveProperty('assignmentId');
  expect((await api.GET('/api/v1/users/{userId}/profile', { params: { path: { userId: 1002 } } })).data).toMatchObject({ id: 1002, fullName: 'Кузнецов Дмитрий Андреевич' });
});
it('filters ledger at the mock API and ends the cursor without duplicates', async () => {
  await signIn('student');
  const first = (await api.GET('/api/v1/ledger/{userId}/entries', { params: { path: { userId: 1001 } } })).data!;
  expect(first.nextCursor).not.toBeNull();
  const next = (await api.GET('/api/v1/ledger/{userId}/entries', { params: { path: { userId: 1001 }, query: { cursor: first.nextCursor! } } })).data!;
  expect(next.nextCursor).toBeNull();
  expect(new Set([...first.items, ...next.items].map((item) => item.id)).size).toBe(first.items.length + next.items.length);
  const boa = (await api.GET('/api/v1/ledger/{userId}/entries', { params: { path: { userId: 1001 }, query: { currency: 'BOA' } } })).data!;
  expect(boa.items.length).toBeGreaterThan(0);
  expect(boa.items.every((item) => item.currency === 'BOA')).toBe(true);
  expect((await api.GET('/api/v1/ledger/{userId}/entries', { params: { path: { userId: 2001 } } })).data).toEqual({ items: [], nextCursor: null });
});

it('35-4 rejects a cursor belonging to another currency', async () => {
  await signIn('student');
  const first = (await api.GET('/api/v1/ledger/{userId}/entries', { params: { path: { userId: 1001 } } })).data!;
  await expect(api.GET('/api/v1/ledger/{userId}/entries', { params: { path: { userId: 1001 }, query: { currency: 'BOA', cursor: first.nextCursor! } } })).rejects.toMatchObject({ code: 'VALIDATION_FAILED', problem: { fieldErrors: [{ field: 'cursor' }] } });
});
it.each([['currency', 'WRONG'], ['cursor', 'garbage']])('35-5 returns a ValidationProblem for invalid %s', async (field, value) => {
  await signIn('student');
  const result = await fetch(`http://localhost/api/v1/ledger/1001/entries?${field}=${value}`);
  expect(result.status).toBe(400);
  expect(await result.json()).toMatchObject({ code: 'VALIDATION_FAILED', fieldErrors: [{ field, code: 'INVALID', message: expect.any(String) }] });
});

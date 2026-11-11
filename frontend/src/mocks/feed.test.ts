import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { setupServer } from 'msw/node';
import { ApiError, createApiClient } from '../api/client';
import { createAuthMockHandlers } from './auth';
import { createFeedMockHandlers } from './feed';
import { examples } from './examples.generated';

const server = setupServer(...createAuthMockHandlers(), ...createFeedMockHandlers());
const api = createApiClient('http://localhost');
beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => {
  server.resetHandlers();
  document.cookie = 'campus-mock-user=; Max-Age=0; Path=/';
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/';
});
afterAll(() => server.close());
async function login(role = 'teacher') {
  await api.GET('/api/v1/auth/csrf');
  const users = examples.find((item) => item.operationId === 'getCurrentUser')!.responses.filter((item) => item.status === 200);
  const user = users[role === 'teacher' ? 2 : 0]!.body as { email: string };
  const email = user.email;
  await api.POST('/api/v1/auth/login', { body: { email, password: `demo-${role}-1` } });
}
it('requires a session for awards and teacher role for ratings', async () => {
  await expect(api.GET('/api/v1/feed/awards')).rejects.toMatchObject({ code: 'UNAUTHENTICATED' });
  await login('student');
  await expect(api.GET('/api/v1/groups/{groupId}/ratings', { params: { path: { groupId: 11 } } })).rejects.toMatchObject({ code: 'FORBIDDEN' });
  expect((await api.GET('/api/v1/feed/awards')).data!.items.length).toBeGreaterThan(0);
});
it('returns deterministic cursor pages and rejects an unknown cursor', async () => {
  await login();
  const first = (await api.GET('/api/v1/feed/awards')).data!;
  const second = (await api.GET('/api/v1/feed/awards', { params: { query: { cursor: first.nextCursor! } } })).data!;
  expect(first.nextCursor).toBeTruthy();
  expect(second.nextCursor).toBeNull();
  expect(new Set([...first.items, ...second.items].map((item) => item.entryId)).size).toBe(first.items.length + second.items.length);
  await expect(api.GET('/api/v1/feed/awards', { params: { query: { cursor: 'unknown' } } })).rejects.toBeInstanceOf(ApiError);
});
it('sorts snapshot balances with stable names and enforces scope', async () => {
  await login();
  for (const sort of ['AR', 'BUA', 'BOA'] as const) {
    const rating = (await api.GET('/api/v1/groups/{groupId}/ratings', { params: { path: { groupId: 11 }, query: { sort } } })).data!;
    expect(rating.sort).toBe(sort);
    expect(rating.items.map((item) => item.balances[sort])).toEqual(rating.items.map((item) => item.balances[sort]).sort((a, b) => b - a));
    if (sort === 'AR') expect(rating.items.map((item) => item.student.id)).toEqual([1003, 1002, 1001]);
  }
  await expect(api.GET('/api/v1/groups/{groupId}/ratings', { params: { path: { groupId: 13 } } })).rejects.toMatchObject({ code: 'GROUP_NOT_ACCESSIBLE' });
  await expect(api.GET('/api/v1/groups/{groupId}/ratings', { params: { path: { groupId: 9999 } } })).rejects.toMatchObject({ code: 'NOT_FOUND' });
});
it('denies direct ratings to a teacher awaiting approval', async () => {
  await api.GET('/api/v1/auth/csrf');
  await api.POST('/api/v1/auth/login', { body: { email: 'volkov@campus.test', password: 'demo-teacher-1' } });
  await expect(api.GET('/api/v1/groups/{groupId}/ratings', { params: { path: { groupId: 11 } } })).rejects.toMatchObject({ code: 'GROUP_NOT_ACCESSIBLE' });
});

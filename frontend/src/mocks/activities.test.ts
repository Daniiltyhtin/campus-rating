import { api } from '../api/client';
import { server } from './server';
import { createActivityMockHandlers } from './activities';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
beforeEach(() => server.use(...createActivityMockHandlers({ now: () => new Date('2026-11-11T09:00:00Z') })));
afterEach(() => {
  server.resetHandlers();
  document.cookie = 'campus-mock-user=; Max-Age=0; Path=/';
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/';
});
afterAll(() => server.close());
async function signIn(role: 'student' | 'teacher') {
  await api.GET('/api/v1/auth/csrf');
  await api.POST('/api/v1/auth/login', { body: { email: role === 'student' ? 'smirnova@student.campus.test' : 'petrov@campus.test', password: `demo-${role}-1` } });
  await api.GET('/api/v1/auth/csrf');
}
it('does not return the activity list to a mock guest', async () => {
  await expect(api.GET('/api/v1/activities')).rejects.toMatchObject({ code: 'UNAUTHENTICATED' });
});
it('keeps a created mock activity in the list and detail', async () => {
  await signIn('teacher');
  const body = { title: 'Новая тестовая активность', description: 'Синтетический сценарий', type: 'ACADEMIC' as const, groupIds: [11], startsAt: '2100-06-15T11:00:00Z', maxPoints: 75 };
  const created = (await api.POST('/api/v1/activities', { body })).data;
  expect(created).toMatchObject({ title: body.title, startsAt: body.startsAt, maxPoints: 75 });
  if (!created) throw new Error('Missing created activity');
  expect((await api.GET('/api/v1/activities', {})).data?.items.some((item) => item.id === created.id && item.title === body.title)).toBe(true);
  expect((await api.GET('/api/v1/activities/{activityId}', { params: { path: { activityId: created.id } } })).data?.title).toBe(body.title);
});
it('persists enrollment and makes a repeat idempotent in the mock API', async () => {
  await signIn('student');
  const params = { path: { activityId: 603 } };
  const before = (await api.GET('/api/v1/activities/{activityId}', { params })).data;
  if (!before) throw new Error('Missing activity');
  const enrolled = before.enrolled !== true;
  const mutate = () => enrolled ? api.PUT('/api/v1/activities/{activityId}/enrollment', { params }) : api.DELETE('/api/v1/activities/{activityId}/enrollment', { params });
  const first = (await mutate()).data;
  const second = (await mutate()).data;
  expect(first?.enrolled).toBe(enrolled);
  expect(second?.enrolledCount).toBe(first?.enrolledCount);
  expect((await api.GET('/api/v1/activities/{activityId}', { params })).data?.enrolled).toBe(enrolled);
});
it('uses the preview and budget atomically when confirming mock participation', async () => {
  await signIn('teacher');
  const params = { path: { activityId: 602 } };
  const body = { items: [{ studentId: 1002, level: 50 as const }] };
  const preview = (await api.POST('/api/v1/activities/{activityId}/participation/preview', { params, body })).data;
  expect(preview).toMatchObject({ currency: 'BUA', total: 38, withinBudget: true, awards: [{ studentId: 1002, amount: 38 }] });
  await expect(api.POST('/api/v1/activities/{activityId}/participation', { params, body: { items: [{ studentId: 1002, level: 100 }] } })).rejects.toMatchObject({ code: 'BUDGET_EXCEEDED' });
  const result = (await api.POST('/api/v1/activities/{activityId}/participation', { params, body })).data;
  expect(result).toMatchObject({ total: 38 });
  const repeat = (await api.POST('/api/v1/activities/{activityId}/participation', { params, body })).data;
  expect(repeat).toMatchObject({ total: 0, awards: [], alreadyConfirmed: [{ studentId: 1002, amount: 38 }] });
});

it.each(['preview', 'confirm'])('34-3 rejects the entire conflicting %s request', async (kind) => {
  await signIn('teacher');
  const params = { path: { activityId: 602 } };
  await api.POST('/api/v1/activities/{activityId}/participation', { params, body: { items: [{ studentId: 1002, level: 25 }] } });
  const path = kind === 'preview' ? '/api/v1/activities/{activityId}/participation/preview' : '/api/v1/activities/{activityId}/participation';
  await expect(api.POST(path, { params, body: { items: [{ studentId: 1002, level: 50 }, { studentId: 1001, level: 25 }] } })).rejects.toMatchObject({ code: 'ALREADY_CONFIRMED', problem: { status: 409, studentIds: [1002] } });
});
it('34-4 isolates teacher budgets, history and summary including newly awarded points', async () => {
  await signIn('teacher');
  await api.POST('/api/v1/activities/{activityId}/participation', { params: { path: { activityId: 602 } }, body: { items: [{ studentId: 1002, level: 25 }] } });
  const cookie = document.cookie.split('; ').find((part) => part.startsWith('campus-mock-user='))!.slice('campus-mock-user='.length);
  const teacher = JSON.parse(decodeURIComponent(cookie));
  document.cookie = `campus-mock-user=${encodeURIComponent(JSON.stringify({ ...teacher, id: 2999 }))}; Path=/`;
  const budgets = (await api.GET('/api/v1/budgets')).data!;
  expect(budgets.items.every((row) => row.spent === 0 && row.remaining === row.limit)).toBe(true);
  expect((await api.GET('/api/v1/me/awards')).data!.items).toEqual([]);
  expect((await api.GET('/api/v1/me/awards/summary')).data).toMatchObject({ items: [], total: 0, count: 0 });
  document.cookie = `campus-mock-user=${cookie}; Path=/`;
  expect((await api.GET('/api/v1/me/awards/summary')).data!.total).toBeGreaterThan(0);
  expect((await api.GET('/api/v1/budgets', { params: { query: { month: '2027-01' } } })).data!.items.every((row) => row.spent === 0)).toBe(true);
});
it('34-5 rejects enrollment at start with the contract status', async () => {
  await signIn('student');
  await expect(api.PUT('/api/v1/activities/{activityId}/enrollment', { params: { path: { activityId: 601 } } })).rejects.toMatchObject({ code: 'ENROLLMENT_CLOSED', problem: { status: 422 } });
});
it('34-5 rejects pending membership using a known problem code', async () => {
  await signIn('student');
  const cookie = document.cookie.split('; ').find((part) => part.startsWith('campus-mock-user='))!.slice('campus-mock-user='.length);
  document.cookie = `campus-mock-user=${encodeURIComponent(JSON.stringify({ ...JSON.parse(decodeURIComponent(cookie)), membershipState: 'PENDING' }))}; Path=/`;
  await expect(api.PUT('/api/v1/activities/{activityId}/enrollment', { params: { path: { activityId: 603 } } })).rejects.toMatchObject({ code: 'MEMBERSHIP_NOT_ACTIVE', problem: { status: 422 } });
});

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

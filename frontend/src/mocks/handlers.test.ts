import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { createApiClient } from '../api/client';
import { server } from './server';
import { examples } from './examples.generated';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

it('serves two cursor pages without duplicate awards', async () => {
  const api = createApiClient('http://localhost');
  const first = (await api.GET('/api/v1/feed/awards')).data!;
  expect(first.items.length).toBeGreaterThan(0);
  expect(first.nextCursor).toBeTruthy();
  const second = (await api.GET('/api/v1/feed/awards', { params: { query: { cursor: first.nextCursor! } } })).data!;
  expect(second.items.length).toBeGreaterThan(0);
  expect(second.nextCursor).toBeNull();
  const ids = [...first.items, ...second.items].map((item) => item.entryId);
  expect(new Set(ids).size).toBe(ids.length);
});

it('serves a dereferenced contract profile example', async () => {
  const response = await createApiClient('http://localhost').GET('/api/v1/users/{userId}/profile', { params: { path: { userId: 1001 } } });
  expect(response.data).toEqual(examples.find((entry) => entry.operationId === 'getUserProfile')!.responses[0]!.body);
});

it('a mutation returns the contract state after the action', async () => {
  const response = await createApiClient('http://localhost').POST('/api/v1/checkpoints/{checkpointId}/assignments/accept', { params: { path: { checkpointId: 1 } }, body: { assignmentIds: [7001, 7002, 7003] } });
  expect(response.data).toEqual(examples.find((entry) => entry.operationId === 'acceptAssignments')!.responses[0]!.body);
});

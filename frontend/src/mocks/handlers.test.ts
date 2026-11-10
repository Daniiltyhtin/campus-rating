import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { createApiClient } from '../api/client';
import { server } from './server';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('contract example mocks', () => {
  it('serves two pages of awards with no duplicates', async () => {
    const api = createApiClient({ baseUrl: 'http://localhost' });
    const first = await api.GET('/api/v1/feed/awards');
    expect(first.data?.items.length).toBeGreaterThan(0);
    expect(first.data?.nextCursor).toBeTruthy();
    const second = await api.GET('/api/v1/feed/awards', {
      params: { query: { cursor: first.data!.nextCursor! } },
    });
    expect(second.data?.items.length).toBeGreaterThan(0);
    expect(second.data?.nextCursor).toBeNull();
    const ids = [...first.data!.items, ...second.data!.items].map((item) => item.entryId);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('serves the profile example from the contract', async () => {
    const api = createApiClient({ baseUrl: 'http://localhost' });
    const profile = await api.GET('/api/v1/users/{userId}/profile', { params: { path: { userId: 1001 } } });
    expect(profile.data).toMatchObject({ id: 1001, role: 'STUDENT' });
    expect(profile.data?.fullName).toBeTruthy();
  });
});

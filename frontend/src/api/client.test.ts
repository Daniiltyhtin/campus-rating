import { File as NodeFile } from 'node:buffer';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { ApiError, createApiClient, csrfMiddleware, submitAssignment } from './client';

beforeAll(() => { vi.stubGlobal('File', NodeFile); server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { server.resetHandlers(); document.cookie = 'XSRF-TOKEN=; Max-Age=0'; });
afterAll(() => { server.close(); vi.unstubAllGlobals(); });

it('rejects malformed validation details rather than trusting external field messages', async () => {
  server.use(http.get('http://localhost/api/v1/auth/me', () => HttpResponse.json({
    type: 'about:blank', title: 'Bad Request', status: 400, code: 'VALIDATION_FAILED',
    fieldErrors: [{ field: 'email', code: 'INVALID', message: { unexpected: true } }],
  }, { status: 400, headers: { 'Content-Type': 'application/problem+json' } })));
  const error = await createApiClient('http://localhost').GET('/api/v1/auth/me').catch((error: unknown) => error);
  expect(error).toBeInstanceOf(Error);
  expect(error).not.toBeInstanceOf(ApiError);
});

describe('CSRF', () => {
  it.each(['POST', 'PUT', 'PATCH', 'DELETE', 'GET'])('%s uses the cookie only for mutations', async (method) => {
    document.cookie = 'XSRF-TOKEN=token%2Bvalue';
    const request = new Request('http://localhost/api/v1/test', { method });
    await csrfMiddleware.onRequest!({ request } as Parameters<NonNullable<typeof csrfMiddleware.onRequest>>[0]);
    expect(request.headers.get('X-XSRF-TOKEN')).toBe(method === 'GET' ? null : 'token+value');
  });
  it('does not invent a token when the cookie is absent', async () => {
    const request = new Request('http://localhost/api/v1/test', { method: 'POST' });
    await csrfMiddleware.onRequest!({ request } as Parameters<NonNullable<typeof csrfMiddleware.onRequest>>[0]);
    expect(request.headers.has('X-XSRF-TOKEN')).toBe(false);
  });
});

it('sends a file as multipart with an automatic boundary and CSRF', async () => {
  document.cookie = 'XSRF-TOKEN=upload-token';
  server.use(http.post('http://localhost/api/v1/assignments/1/submission', async ({ request }) => {
    expect(request.headers.get('X-XSRF-TOKEN')).toBe('upload-token');
    expect(request.headers.get('content-type')).toMatch(/^multipart\/form-data; boundary=/);
    const file = (await request.formData()).get('file') as File;
    expect(file.name).toBe('answer.txt');
    expect(await file.text()).toBe('answer');
    return HttpResponse.json({ id: 1 });
  }));
  await submitAssignment(createApiClient('http://localhost'), 1, { file: new File(['answer'], 'answer.txt') });
});

it('throws a typed budget problem with its budgets', async () => {
  const budgets = [{ groupId: 11, groupName: 'ИВТ-21', needed: 200, remaining: 100, shortfall: 100 }];
  server.use(http.get('http://localhost/api/v1/feed/awards', () => HttpResponse.json(
    { type: 'about:blank', status: 422, title: 'Бюджет исчерпан', code: 'BUDGET_EXCEEDED', budgets },
    { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
  )));
  const error = await createApiClient('http://localhost').GET('/api/v1/feed/awards').catch((error: unknown) => error);
  expect(error).toBeInstanceOf(ApiError);
  if (!(error instanceof ApiError)) throw new Error('Expected ApiError');
  expect(error.code).toBe('BUDGET_EXCEEDED');
  if (error.problem.code === 'BUDGET_EXCEEDED') expect(error.problem.budgets).toEqual(budgets);
});

it('rejects a budget problem without budgets instead of typing it', async () => {
  server.use(http.get('http://localhost/api/v1/feed/awards', () => HttpResponse.json(
    { type: 'about:blank', status: 422, title: 'Бюджет исчерпан', code: 'BUDGET_EXCEEDED' },
    { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
  )));
  const error = await createApiClient('http://localhost').GET('/api/v1/feed/awards').catch((error: unknown) => error);
  expect(error).toBeInstanceOf(Error);
  expect(error).not.toBeInstanceOf(ApiError);
});

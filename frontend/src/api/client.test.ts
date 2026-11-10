// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { ApiError, createApiClient, createSecureFetch, multipartBody } from './client';

vi.hoisted(() => {
  vi.stubGlobal('document', { cookie: '' });
  vi.stubGlobal('window', { location: { origin: 'http://localhost' } });
});

describe('API transport', () => {
  it.each(['POST', 'PUT', 'PATCH', 'DELETE'])('%s sends the current decoded CSRF cookie', async (method) => {
    document.cookie = 'XSRF-TOKEN=token%2Bvalue; path=/';
    const transport = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 204 }));
    await createSecureFetch(transport)(new Request('http://localhost/api/v1/example', { method }));
    const request = transport.mock.calls[0]?.[0] as Request;
    expect(request.headers.get('X-XSRF-TOKEN')).toBe('token+value');
    expect(request.credentials).toBe('include');
  });

  it('GET does not send a CSRF header', async () => {
    document.cookie = 'XSRF-TOKEN=token; path=/';
    const transport = vi.fn<typeof fetch>().mockResolvedValue(new Response('{}'));
    await createSecureFetch(transport)(new Request('http://localhost/api/v1/example'));
    expect((transport.mock.calls[0]?.[0] as Request).headers.has('X-XSRF-TOKEN')).toBe(false);
  });

  it('loads CSRF before the first mutation and refreshes it after login', async () => {
    document.cookie = 'XSRF-TOKEN=; max-age=0; path=/';
    const urls: string[] = [];
    const transport = vi.fn<typeof fetch>().mockImplementation(async (input) => {
      const request = input as Request;
      urls.push(new URL(request.url).pathname);
      if (request.url.endsWith('/csrf')) document.cookie = 'XSRF-TOKEN=fresh; path=/';
      else expect(request.headers.get('X-XSRF-TOKEN')).toBe('fresh');
      return new Response(null, { status: 204 });
    });
    await createSecureFetch(transport)(new Request('http://localhost/api/v1/auth/login', { method: 'POST' }));
    expect(urls).toEqual(['/api/v1/auth/csrf', '/api/v1/auth/login', '/api/v1/auth/csrf']);
  });

  it('preserves the budget details in a typed domain error', async () => {
    const problem = { type: 'about:blank', title: 'Unprocessable Content', status: 422,
      code: 'BUDGET_EXCEEDED', budgets: [{ groupId: 11, groupName: 'ИВТ-21', needed: 100, remaining: 50, shortfall: 50 }] };
    const transport = vi.fn<typeof fetch>().mockResolvedValue(Response.json(problem, {
      status: 422, headers: { 'Content-Type': 'application/problem+json' },
    }));
    const api = createApiClient({ baseUrl: 'http://localhost', fetch: transport });
    const error = await api.GET('/api/v1/feed/awards').catch((value: unknown) => value);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 422, problem });
  });

  it('sends a real multipart file with an automatically generated boundary', async () => {
    document.cookie = 'XSRF-TOKEN=upload-token; path=/';
    const transport = vi.fn<typeof fetch>().mockResolvedValue(Response.json({}));
    const api = createApiClient({ baseUrl: 'http://localhost', fetch: transport });
    await api.POST('/api/v1/assignments/{assignmentId}/submission', {
      params: { path: { assignmentId: 101 } },
      body: { file: new File(['report'], 'report.pdf', { type: 'application/pdf' }) },
      bodySerializer: multipartBody,
    });
    const request = transport.mock.calls[0]?.[0] as Request;
    expect(request.headers.get('Content-Type')).toMatch(/^multipart\/form-data; boundary=/);
    expect(request.headers.get('X-XSRF-TOKEN')).toBe('upload-token');
    expect(await request.text()).toContain('filename="report.pdf"');
  });
});

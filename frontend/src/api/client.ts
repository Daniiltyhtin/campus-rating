import createClient from 'openapi-fetch';
import type { components, paths } from './schema';

export type ApiProblem = components['schemas']['Problem'] & {
  budgets?: components['schemas']['BudgetCheck'][];
  fieldErrors?: components['schemas']['FieldError'][];
  studentIds?: number[];
};

export class ApiError extends Error {
  constructor(public readonly status: number, public readonly problem?: ApiProblem) {
    super(problem?.detail ?? `Ошибка запроса (${status})`);
    this.name = 'ApiError';
  }
}

function csrfCookie() {
  const value = document.cookie.split(';').map((item) => item.trim()).find((item) => item.startsWith('XSRF-TOKEN='));
  return value ? decodeURIComponent(value.slice('XSRF-TOKEN='.length)) : undefined;
}

async function requireSuccess(response: Response) {
  if (!response.ok) {
    let problem: ApiProblem | undefined;
    if (response.headers.get('Content-Type')?.split(';')[0] === 'application/problem+json') {
      problem = await response.clone().json() as ApiProblem;
    }
    throw new ApiError(response.status, problem);
  }
  return response;
}

export function createSecureFetch(transport: typeof fetch = globalThis.fetch): typeof fetch {
  return async (input, init) => {
    const request = new Request(input, { ...init, credentials: 'include' });
    const url = new URL(request.url);
    const refresh = () => transport(new Request(new URL('/api/v1/auth/csrf', url), { credentials: 'include' })).then(requireSuccess);
    if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(request.method)) {
      if (!csrfCookie()) await refresh();
      const token = csrfCookie();
      if (!token) throw new Error('Сервер не выдал CSRF-токен');
      request.headers.set('X-XSRF-TOKEN', token);
    } else {
      request.headers.delete('X-XSRF-TOKEN');
    }
    const response = await requireSuccess(await transport(request));
    if (request.method === 'POST' && /^\/api\/v1\/auth\/(login|logout|password)$/.test(url.pathname)) await refresh();
    return response;
  };
}

export function createApiClient(options: { baseUrl?: string; fetch?: typeof fetch } = {}) {
  return createClient<paths>({ baseUrl: options.baseUrl ?? window.location.origin, fetch: createSecureFetch(options.fetch) });
}

export function multipartBody(body: { link?: string; file?: Blob | string }) {
  const data = new FormData();
  if (body.link !== undefined) data.set('link', body.link);
  if (body.file instanceof Blob) data.set('file', body.file);
  else if (body.file !== undefined) throw new TypeError('Для загрузки нужен File или Blob');
  return data;
}

export const api = createApiClient();

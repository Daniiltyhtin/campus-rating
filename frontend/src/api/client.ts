import createClient, { type Middleware } from 'openapi-fetch';
import type { components, paths } from './schema';
import { problemCodes } from './problemCodes.generated';

type Problem = components['schemas']['Problem'];
type StudentProblem = Omit<components['schemas']['StudentsProblem'], 'code'> & { code: 'NOT_ENROLLED' | 'STUDENT_NOT_ELIGIBLE' | 'ALREADY_CONFIRMED' };
type ApiProblem = components['schemas']['BudgetProblem'] | StudentProblem | (Omit<components['schemas']['ValidationProblem'], 'code'> & { code: 'VALIDATION_FAILED' }) | (Omit<Problem, 'code'> & {
  code: Exclude<Problem['code'], 'BUDGET_EXCEEDED' | 'VALIDATION_FAILED' | StudentProblem['code']>;
});

export class ApiError extends Error {
  readonly code: ApiProblem['code'];
  constructor(readonly problem: ApiProblem) {
    super(problem.detail ?? problem.title);
    this.name = 'ApiError';
    this.code = problem.code;
  }
}

function isProblem(value: unknown): value is ApiProblem {
  if (!value || typeof value !== 'object') return false;
  const p = value as Record<string, unknown>;
  if (typeof p.type !== 'string' || typeof p.title !== 'string' || typeof p.status !== 'number' || typeof p.code !== 'string' || !problemCodes.some((code) => code === p.code)) return false;
  if (p.detail != null && typeof p.detail !== 'string') return false;
  if (['NOT_ENROLLED', 'STUDENT_NOT_ELIGIBLE', 'ALREADY_CONFIRMED'].includes(p.code)) return Array.isArray(p.studentIds) && p.studentIds.length > 0
    && p.studentIds.every((id: unknown) => typeof id === 'number' && Number.isSafeInteger(id) && id > 0);
  if (p.code === 'VALIDATION_FAILED') return Array.isArray(p.fieldErrors) && p.fieldErrors.every((field: unknown) => {
    if (!field || typeof field !== 'object') return false;
    const item = field as Record<string, unknown>;
    return typeof item.field === 'string' && typeof item.code === 'string' && typeof item.message === 'string';
  });
  if (p.code !== 'BUDGET_EXCEEDED') return true;
  return Array.isArray(p.budgets) && p.budgets.length > 0 && p.budgets.every((budget: unknown) => {
    if (!budget || typeof budget !== 'object') return false;
    const b = budget as Record<string, unknown>;
    return typeof b.groupId === 'number' && typeof b.groupName === 'string'
      && ['needed', 'remaining', 'shortfall'].every((key) => typeof b[key] === 'number');
  });
}

export const csrfMiddleware: Middleware = {
  onRequest({ request }) {
    if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(request.method)) {
      const cookie = document.cookie.split('; ').find((part) => part.startsWith('XSRF-TOKEN='));
      if (cookie) request.headers.set('X-XSRF-TOKEN', decodeURIComponent(cookie.slice('XSRF-TOKEN='.length)));
    }
    return request;
  },
  async onResponse({ request, response }) {
    if (!response.ok && response.headers.get('content-type')?.split(';')[0] === 'application/problem+json') {
      const problem: unknown = await response.clone().json().catch(() => undefined);
      if (isProblem(problem)) {
        if (problem.code === 'UNAUTHENTICATED' && !request.url.endsWith('/auth/me')) window.dispatchEvent(new Event('campus:session-expired'));
        if (problem.code === 'PASSWORD_CHANGE_REQUIRED') window.dispatchEvent(new Event('campus:password-required'));
        throw new ApiError(problem);
      }
    }
    if (request.method === 'GET' && [403, 404].includes(response.status) && /\/(assignments|checkpoints)\//.test(new URL(request.url).pathname)) {
      throw new ApiError({ type: 'about:blank', title: 'Error', status: response.status, code: response.status === 403 ? 'FORBIDDEN' : 'NOT_FOUND' });
    }
    if (response.status === 429) {
      throw new ApiError({ type: 'about:blank', title: 'Too Many Requests', status: 429, code: 'RATE_LIMITED' });
    }
    if (response.status === 413 && /\/assignments\/[^/]+\/submission$/.test(new URL(request.url).pathname)) {
      throw new ApiError({ type: 'about:blank', title: 'Payload Too Large', status: 413, code: 'FILE_TOO_LARGE' });
    }
    if (!response.ok && response.headers.get('content-type')?.split(';')[0] === 'application/problem+json') {
      throw new Error('Некорректный ответ application/problem+json');
    }
    return response;
  },
};

export function createApiClient(baseUrl = window.location.origin) {
  const client = createClient<paths>({ baseUrl, credentials: 'same-origin', fetch: (request) => globalThis.fetch(request) });
  client.use(csrfMiddleware);
  return client;
}

export const api = createApiClient();

export function submitAssignment(client: ReturnType<typeof createApiClient>, assignmentId: number,
  body: components['schemas']['SubmissionUpload']) {
  return client.POST('/api/v1/assignments/{assignmentId}/submission', {
    params: { path: { assignmentId } },
    body,
    bodySerializer(value) {
      const form = new FormData();
      if (value.file !== undefined) form.append('file', value.file);
      else form.append('link', value.link);
      return form;
    },
  });
}

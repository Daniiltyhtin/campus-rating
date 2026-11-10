import { http, HttpResponse } from 'msw';
import { examples } from './examples.generated';

function isPage(value: unknown): value is { items: unknown[]; nextCursor: string | null } {
  return typeof value === 'object' && value !== null && 'nextCursor' in value && 'items' in value;
}

export const handlers = examples.map((operation) => http.all(
  `*${operation.path.replace(/\{([^}]+)\}/g, ':$1')}`,
  ({ request }) => {
    if (request.method !== operation.method) return;
    const response = operation.responses[0];
    if (!response) throw new Error(`No success response for ${operation.operationId}`);
    const values = Object.values(response.examples);
    const url = new URL(request.url);
    let value = values[0];
    const cursor = url.searchParams.get('cursor');
    if (cursor && isPage(value)) {
      const pageIndex = values.findIndex((page) => isPage(page) && page.nextCursor === cursor);
      if (pageIndex < 0 || values[pageIndex + 1] === undefined) {
        return HttpResponse.json({ type: 'about:blank', title: 'Bad Request', status: 400, code: 'VALIDATION_FAILED',
          fieldErrors: [{ field: 'cursor', code: 'INVALID', message: 'Неверный курсор' }] },
        { status: 400, headers: { 'Content-Type': 'application/problem+json' } });
      }
      value = values[pageIndex + 1];
    }
    const headers = operation.operationId === 'getCsrfToken' || url.pathname === '/api/v1/auth/csrf'
      ? { 'Set-Cookie': 'XSRF-TOKEN=mock-csrf; Path=/; SameSite=Lax' } : undefined;
    if (response.status === 204) return new HttpResponse(null, { status: 204, headers });
    if (response.mediaType === 'application/octet-stream') {
      return new HttpResponse(new Blob(['Mock submission']), { status: response.status,
        headers: { 'Content-Type': response.mediaType } });
    }
    return new HttpResponse(JSON.stringify(value), { status: response.status,
      headers: { ...headers, 'Content-Type': response.mediaType ?? 'application/json' } });
  },
));

import { http, HttpResponse } from 'msw';
import { examples } from './examples.generated';
import { createAuthMockHandlers } from './auth';
import { createActivityMockHandlers } from './activities';
import { createProfileMockHandlers } from './profile';

function cursorPage(body: unknown): body is { items: unknown[]; nextCursor: string | null } {
  return !!body && typeof body === 'object' && 'items' in body && Array.isArray(body.items) && 'nextCursor' in body;
}

export const handlers = [...createAuthMockHandlers(), ...createActivityMockHandlers(), ...createProfileMockHandlers(), ...examples.map((operation) => {
  const example = operation.responses.find(({ status }) => status >= 200 && status < 300)!;
  const path = operation.path.replaceAll(/\{([^}]+)\}/g, ':$1');
  return http.all(`*${path}`, ({ request }) => {
    if (request.method.toLowerCase() !== operation.method) return;
    let body = structuredClone(example.body);
    if (cursorPage(body)) {
      const split = Math.ceil(body.items.length / 2);
      const secondPage = new URL(request.url).searchParams.has('cursor');
      body = { ...body, items: secondPage ? body.items.slice(split) : body.items.slice(0, split),
        nextCursor: secondPage ? null : (body.nextCursor ?? 'mock-page-2') };
    }
    if (example.status === 204) return new HttpResponse(null, { status: 204 });
    if (example.contentType === 'application/octet-stream') return new HttpResponse('', { headers: { 'Content-Type': example.contentType } });
    return new HttpResponse(JSON.stringify(body), { status: example.status, headers: { 'Content-Type': example.contentType } });
  });
})];

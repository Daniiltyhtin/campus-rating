import { http, HttpResponse } from 'msw';
import type { components } from '../api/schema';
import { examples } from './examples.generated';

function cursorPage(body: unknown): body is { items: unknown[]; nextCursor: string | null } {
  return !!body && typeof body === 'object' && 'items' in body && Array.isArray(body.items) && 'nextCursor' in body;
}

export const handlers = examples.map((operation) => {
  const example = operation.responses.find(({ status }) => status >= 200 && status < 300)!;
  const path = operation.path.replaceAll(/\{([^}]+)\}/g, ':$1');
  return http.all(`*${path}`, ({ request }) => {
    if (request.method.toLowerCase() !== operation.method) return;
    let body = structuredClone(example.body);
    const url = new URL(request.url);
    if (operation.operationId === 'listMyAssignments' && url.searchParams.get('view') === 'completed' && cursorPage(body)) {
      body = { ...body, items: body.items.filter((item) => !!item && typeof item === 'object' && 'state' in item && item.state === 'ACCEPTED'), nextCursor: null };
    }
    if (operation.operationId === 'listCheckpointAssignments') {
      const page = body as components['schemas']['CheckpointAssignmentPage'];
      const groupId = url.searchParams.get('groupId');
      const status = url.searchParams.get('status');
      const groupRows = page.items.filter((item) => !groupId || String(item.group.id) === groupId);
      if (groupId) page.statusCounts = {
        IN_PROGRESS: groupRows.filter((item) => item.status === 'IN_PROGRESS').length,
        OVERDUE: groupRows.filter((item) => item.status === 'OVERDUE').length,
        SUBMITTED: groupRows.filter((item) => item.status === 'SUBMITTED').length,
        ACCEPTED: groupRows.filter((item) => item.status === 'ACCEPTED').length,
      };
      page.items = groupRows.filter((item) => !status || item.status === status);
    }
    const activeTasks = operation.operationId === 'listMyAssignments' && url.searchParams.get('view') === 'active';
    if (cursorPage(body) && !activeTasks) {
      const split = Math.ceil(body.items.length / 2);
      const secondPage = new URL(request.url).searchParams.has('cursor');
      body = { ...body, items: secondPage ? body.items.slice(split) : body.items.slice(0, split),
        nextCursor: secondPage || split === body.items.length ? null : (body.nextCursor ?? 'mock-page-2') };
    }
    if (example.status === 204) return new HttpResponse(null, { status: 204 });
    if (example.contentType === 'application/octet-stream') return new HttpResponse('', { headers: { 'Content-Type': example.contentType } });
    return new HttpResponse(JSON.stringify(body), { status: example.status, headers: { 'Content-Type': example.contentType } });
  });
});

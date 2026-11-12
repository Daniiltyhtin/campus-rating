import { http, HttpResponse } from 'msw';
import { parseGroups } from '../features/activities/contracts';
import { parseAwardsPage, parseGroupRating, parsePersonalPage } from '../features/feed/contracts';
import { readUser } from './auth';
import { examples } from './examples.generated';

function example(operationId: string): unknown {
  const response = examples.find((item) => item.operationId === operationId)?.responses.find((item) => item.status === 200);
  if (!response) throw new Error(`Missing feed mock example: ${operationId}`);
  return structuredClone(response.body);
}
function problem(code: string, status = 403) {
  return HttpResponse.json({ type: 'about:blank', title: 'Ошибка', code, status, ...(code === 'VALIDATION_FAILED' ? { fieldErrors: [] } : {}) }, { status, headers: { 'Content-Type': 'application/problem+json' } });
}
export function createFeedMockHandlers() {
  const awards = parseAwardsPage(example('listAwardsFeed'));
  const rating = parseGroupRating(example('getGroupRating'));
  const groups = parseGroups(example('listAccessibleGroups')).items;
  const personal = parsePersonalPage(example('listPersonalFeed'));
  return [
    http.get('*/api/v1/feed/personal', () => {
      const viewer = readUser();
      if (!viewer) return problem('UNAUTHENTICATED', 401);
      return HttpResponse.json(viewer.role === 'ADMIN' ? { items: [], nextCursor: null } : personal);
    }),
    http.get('*/api/v1/feed/awards', ({ request }) => {
      if (!readUser()) return problem('UNAUTHENTICATED', 401);
      const query = new URL(request.url).searchParams;
      const cursor = query.get('cursor');
      if (cursor && cursor !== 'awards-page-2') return problem('VALIDATION_FAILED', 400);
      const split = Math.ceil(awards.items.length / 2);
      return HttpResponse.json({ items: cursor ? awards.items.slice(split) : awards.items.slice(0, split), nextCursor: cursor ? null : 'awards-page-2' });
    }),
    http.get('*/api/v1/groups/:groupId/ratings', ({ request, params }) => {
      const viewer = readUser();
      if (!viewer) return problem('UNAUTHENTICATED', 401);
      if (viewer.role !== 'TEACHER') return problem('FORBIDDEN');
      if (!viewer.teacherApproved) return problem('GROUP_NOT_ACCESSIBLE');
      const groupId = Number(params.groupId);
      const group = groups.find((item) => item.id === groupId);
      if (!group) return problem(groupId === 13 ? 'GROUP_NOT_ACCESSIBLE' : 'NOT_FOUND', groupId === 13 ? 403 : 404);
      const sort = new URL(request.url).searchParams.get('sort') ?? 'AR';
      if (sort !== 'AR' && sort !== 'BUA' && sort !== 'BOA') return problem('VALIDATION_FAILED', 400);
      const items = groupId === rating.group.id ? [...rating.items].sort((a, b) => b.balances[sort] - a.balances[sort]
        || a.student.fullName.localeCompare(b.student.fullName, 'ru') || a.student.id - b.student.id) : [];
      return HttpResponse.json({ group, sort, items });
    }),
  ];
}

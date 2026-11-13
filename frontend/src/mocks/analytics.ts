import { http, HttpResponse } from 'msw';
import { minusDays, todayMoscow, validPeriod, parseAnalytics, parseBudget } from '../features/analytics/contracts';
import { parseGroupRating } from '../features/feed/contracts';
import { readUser } from './auth';
import { examples } from './examples.generated';

function example(operationId: string) {
  return structuredClone(examples.find((row) => row.operationId === operationId)!.responses.find((row) => row.status === 200)!.body);
}
function problem(code: string, status: number) {
  return HttpResponse.json({ type: 'about:blank', title: 'Ошибка', code, status, ...(code === 'VALIDATION_FAILED' ? { fieldErrors: [] } : {}) }, { status });
}
export function createAnalyticsMockHandlers() {
  return [
    http.get('*/api/v1/groups/:groupId/analytics', ({ params, request }) => {
      const user = readUser();
      if (!user) return problem('UNAUTHENTICATED', 401);
      if (user.role !== 'ADMIN' && user.role !== 'TEACHER') return problem('FORBIDDEN', 403);
      if (user.role === 'TEACHER' && (!user.teacherApproved || Number(params.groupId) === 13)) return problem('GROUP_NOT_ACCESSIBLE', 403);
      const today = todayMoscow(), query = new URL(request.url).searchParams;
      const from = query.get('from') ?? minusDays(today, 29), to = query.get('to') ?? today;
      if (!validPeriod(from, to, today)) return problem('VALIDATION_FAILED', 400);
      const body = parseAnalytics(example('getGroupAnalytics'));
      return HttpResponse.json({ ...body, group: { ...body.group, id: Number(params.groupId) },
        days: Array.from({ length: (Date.parse(to) - Date.parse(from)) / 86400000 + 1 }, (_, i) => ({ date: minusDays(from, -i), averageAr: i % 2 ? 980 : 990, students: 2 })) });
    }),
    http.get('*/api/v1/groups/:groupId/analytics/budget', ({ params }) => {
      const user = readUser();
      if (!user) return problem('UNAUTHENTICATED', 401);
      if (user.role !== 'ADMIN' && user.role !== 'TEACHER') return problem('FORBIDDEN', 403);
      if (user.role === 'TEACHER' && (!user.teacherApproved || Number(params.groupId) === 13)) return problem('GROUP_NOT_ACCESSIBLE', 403);
      const body = parseBudget(example('getGroupAnalyticsBudget')), today = new Date(`${todayMoscow()}T12:00:00Z`);
      return HttpResponse.json({ items: Array.from({ length: 6 }, (_, i) => {
        const date = new Date(today); date.setUTCDate(1); date.setUTCMonth(date.getUTCMonth() - i);
        return { ...body.items[0], month: date.toISOString().slice(0, 7) };
      }) });
    }),
    http.get('*/api/v1/groups/:groupId/ratings', ({ params }) => {
      if (readUser()?.role !== 'ADMIN') return;
      const body = parseGroupRating(example('getGroupRating'));
      return HttpResponse.json({ ...body, group: { ...body.group, id: Number(params.groupId) } });
    }),
  ];
}

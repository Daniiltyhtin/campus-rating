import { http } from 'msw';
import type { components } from '../api/schema';
import { examples } from '../mocks/examples.generated';
import { server } from '../mocks/server';
import { authUsers, createAuthScenario, mountAuth, problem } from './authAcceptance';

type Schemas = components['schemas'];
export type Award = Schemas['AwardFeedItem'];
export type AwardPage = Schemas['AwardFeedPage'];
export type Rating = Schemas['GroupRating'];
type RequestRecord = { path: string; query: Record<string, string>; signal: AbortSignal };

function example<T>(operationId: string): T {
  const value = examples.find((item) => item.operationId === operationId)?.responses.find((item) => item.status === 200)?.body;
  if (!value) throw new Error(`Missing ${operationId} contract example`);
  return structuredClone(value) as T;
}
export const feedFixtures = {
  page: () => example<AwardPage>('listAwardsFeed'),
  rating: () => example<Rating>('getGroupRating'),
  groups: () => example<Schemas['StudyGroupList']>('listAccessibleGroups'),
};
export { authUsers, problem };
const mounted = new Set<ReturnType<typeof mountAuth>>();
export function mountFeed(path = '/awards') {
  const view = mountAuth(path);
  mounted.add(view);
  return view;
}
export function disposeFeedTests() {
  for (const view of mounted) { view.router.dispose(); view.queryClient.clear(); }
  mounted.clear();
}
export function feedJson(body: unknown) { return new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } }); }
export function deferredFeed() {
  let resolve!: () => void;
  const promise = new Promise<void>((done) => { resolve = done; });
  return { promise, resolve };
}
export function awardRows(count: number): Award[] {
  const base = feedFixtures.page().items[0]!;
  return Array.from({ length: count }, (_, index) => ({ ...base, entryId: 90_000 - index,
    createdAt: new Date(Date.UTC(2026, 10, 11, 9, 0, -index)).toISOString(),
    activity: { id: 900 + index, title: `Награда №${index + 1}` },
  }));
}
export function createFeedScenario(viewer: Schemas['CurrentUser'] | null = authUsers.student) {
  createAuthScenario(viewer);
  const first = feedFixtures.page();
  first.nextCursor = null;
  const rating = feedFixtures.rating();
  const state = {
    pages: new Map<string, AwardPage>([['', first]]),
    ratings: new Map<number, Rating>([[rating.group.id, rating]]),
    groups: { items: [rating.group] },
    requests: [] as RequestRecord[],
  };
  function record(request: Request) {
    const url = new URL(request.url);
    state.requests.push({ path: url.pathname, query: Object.fromEntries(url.searchParams), signal: request.signal });
    return url;
  }
  server.use(
    http.get('*/api/v1/feed/awards', ({ request }) => {
      const url = record(request);
      return feedJson(state.pages.get(url.searchParams.get('cursor') ?? '') ?? { items: [], nextCursor: null });
    }),
    http.get('*/api/v1/groups/:groupId/ratings', ({ request, params }) => {
      const url = record(request);
      const value = state.ratings.get(Number(params.groupId));
      const sort = url.searchParams.get('sort') ?? 'AR';
      if (!value) return problem('GROUP_NOT_ACCESSIBLE', 403);
      if (sort !== 'AR' && sort !== 'BUA' && sort !== 'BOA') return problem('VALIDATION_FAILED', 400);
      const items = [...value.items].sort((a, b) => b.balances[sort] - a.balances[sort]
        || a.student.fullName.localeCompare(b.student.fullName, 'ru') || a.student.id - b.student.id);
      return feedJson({ ...value, sort, items });
    }),
    http.get('*/api/v1/me/accessible-groups', ({ request }) => { record(request); return feedJson(state.groups); }),
    http.get('*/api/v1/budgets', ({ request }) => {
      const url = record(request), budgets = example<Schemas['BudgetList']>('listBudgets');
      return feedJson({ ...budgets, month: url.searchParams.get('month') ?? budgets.month });
    }),
    http.get('*/api/v1/me/awards/summary', ({ request }) => {
      const url = record(request), summary = example<Schemas['TeacherAwardSummary']>('getMyAwardsSummary');
      return feedJson({ ...summary, month: url.searchParams.get('month') ?? summary.month });
    }),
    http.get('*/api/v1/me/awards', ({ request }) => {
      record(request);
      return feedJson({ ...example<Schemas['TeacherAwardPage']>('listMyAwards'), nextCursor: null });
    }),
  );
  return state;
}

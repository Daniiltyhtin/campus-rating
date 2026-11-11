import { act, cleanup, configure, screen, waitFor } from '@testing-library/react';
import { http } from 'msw';
import { server } from '../../mocks/server';
import { createFeedScenario, awardRows, mountFeed, feedJson, deferredFeed, authUsers, problem } from '../../test/feedAcceptance';
import { createProfileScenario, mountProfile, ledgerRows } from '../../test/profileAcceptance';
import { createActivitiesScenario, mountActivities, participantNames } from '../../test/activitiesAcceptance';

beforeAll(() => { configure({ asyncUtilTimeout: 3000 }); server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { cleanup(); server.resetHandlers(); vi.unstubAllGlobals(); });
afterAll(() => server.close());
function observer() {
  const callbacks = new Set<IntersectionObserverCallback>();
  vi.stubGlobal('IntersectionObserver', class {
    callback: IntersectionObserverCallback;
    constructor(callback: IntersectionObserverCallback) { this.callback = callback; }
    observe() { callbacks.add(this.callback); }
    disconnect() { callbacks.delete(this.callback); }
    unobserve() {}
  });
  const fire = (entries: IntersectionObserverCallback[]) => { for (const callback of entries) callback([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver); };
  return Object.assign(() => fire([...callbacks]), { queued: () => { const entries = [...callbacks]; return () => fire(entries); } });
}
function listScenario(kind: 'profile' | 'budget' | 'awards') {
  if (kind === 'profile') {
    const state = createProfileScenario(); const rows = ledgerRows(2);
    state.pages.set('', { items: [rows[0]!], nextCursor: 'next' }); state.pages.set('next', { items: [rows[1]!], nextCursor: null });
    return { view: mountProfile(), url: '*/api/v1/ledger/:userId/entries', key: ['profile-ledger'], text: 'Историческая запись 1', first: { items: [rows[0]!], nextCursor: 'next' } };
  }
  if (kind === 'budget') {
    const state = createActivitiesScenario(authUsers.teacher);
    return { view: mountActivities('/groups'), url: '*/api/v1/me/awards', key: ['activities', authUsers.teacher.id, 'awards'], text: state.firstPage.items[0]!.activity.title, first: state.firstPage };
  }
  const state = createFeedScenario(); const rows = awardRows(2);
  state.pages.set('', { items: [rows[0]!], nextCursor: 'next' }); state.pages.set('next', { items: [rows[1]!], nextCursor: null });
  return { view: mountFeed(), url: '*/api/v1/feed/awards', key: ['awards', authUsers.student.id, 'list'], text: 'Награда №1', first: { items: [rows[0]!], nextCursor: 'next' } };
}
it.each(['profile', 'budget', 'awards'] as const)('35-1 %s cannot fetch a next page during background refresh, via button or observer', async (kind) => {
  const intersect = observer(); const { view, url, key, text, first } = listScenario(kind);
  await screen.findByText(text, { exact: true }); const gate = deferredFeed(); let nextCalls = 0; let started = false;
  server.use(http.get(url, async ({ request }) => {
    if (new URL(request.url).searchParams.has('cursor')) { nextCalls++; return feedJson({ items: [], nextCursor: null }); }
    started = true; await gate.promise; return feedJson(first);
  }));
  const queuedIntersection = intersect.queued();
  let pending!: Promise<void>;
  act(() => { pending = view.queryClient.refetchQueries({ queryKey: key }); });
  await waitFor(() => expect(started).toBe(true));
  expect(screen.getByRole('button', { name: 'Показать ещё' })).toBeDisabled();
  await act(async () => { intersect(); queuedIntersection(); });
  expect(nextCalls).toBe(0);
  await act(async () => { gate.resolve(); await pending; });
  await view.user.click(screen.getByRole('button', { name: 'Показать ещё' }));
  await waitFor(() => expect(nextCalls).toBe(1));
  view.router.dispose(); view.queryClient.clear();
});
it.each(['profile', 'budget', 'awards'] as const)('35-2 %s preserves rows and offers refetch after a background error', async (kind) => {
  const { view, url, key, text, first } = listScenario(kind);
  await screen.findByText(text, { exact: true });
  server.use(http.get(url, () => problem('INTERNAL_ERROR', 500)));
  await act(async () => { await view.queryClient.refetchQueries({ queryKey: key }); });
  expect(screen.getByText(text, { exact: true })).toBeInTheDocument();
  expect(await screen.findByRole('alert')).toHaveTextContent(/Не удалось/);
  const retry = screen.getByRole('button', { name: /Повторить обновление/ });
  server.use(http.get(url, () => feedJson(first)));
  await view.user.click(retry);
  await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
  view.router.dispose(); view.queryClient.clear();
});
it('34-1 budget observer stops after one failed next page until explicit retry', async () => {
  const intersect = observer(); const { view, url, text } = listScenario('budget');
  await screen.findByText(text, { exact: true }); let calls = 0;
  server.use(http.get(url, ({ request }) => { if (new URL(request.url).searchParams.has('cursor')) { calls++; return calls === 1 ? problem('INTERNAL_ERROR', 500) : feedJson({ items: [], nextCursor: null }); } }));
  await act(async () => { intersect(); });
  await waitFor(() => expect(calls).toBe(1));
  await screen.findByRole('button', { name: /Повторить загрузку истории/ });
  await act(async () => { intersect(); intersect(); }); expect(calls).toBe(1);
  expect(screen.getByText(text, { exact: true })).toBeInTheDocument();
  await view.user.click(screen.getByRole('button', { name: /Повторить загрузку истории/ }));
  await waitFor(() => expect(calls).toBe(2)); view.router.dispose(); view.queryClient.clear();
});
it('35-3 returning to a cached currency restarts at its first page', async () => {
  const state = createProfileScenario(); const rows = ledgerRows(2);
  state.pages.set('', { items: [rows[0]!], nextCursor: 'next' }); state.pages.set('next', { items: [rows[1]!], nextCursor: null });
  const view = mountProfile();
  await view.user.click(await screen.findByRole('button', { name: 'Показать ещё' }));
  await screen.findByText('Историческая запись 2', { exact: true });
  await view.user.click(screen.getByRole('radio', { name: 'БОА' }));
  await waitFor(() => expect(state.requests.some((r) => r.query.currency === 'BOA')).toBe(true));
  const before = state.requests.length;
  await view.user.click(screen.getByRole('radio', { name: 'Все' }));
  await waitFor(() => expect(state.requests.length).toBeGreaterThan(before));
  expect(screen.queryByText('Историческая запись 2', { exact: true })).not.toBeInTheDocument();
  expect(state.requests.slice(before).some((r) => r.query.cursor)).toBe(false);
  view.router.dispose(); view.queryClient.clear();
});
it.each(['ALREADY_CONFIRMED', 'NOT_ENROLLED', 'STUDENT_NOT_ELIGIBLE', 'INVALID_STATE'])('34-2 preview %s refreshes activity and roster and reconciles selection', async (code) => {
  const state = createActivitiesScenario(authUsers.teacher); let calls = 0;
  if (code === 'NOT_ENROLLED') {
    state.activity.type = 'SOCIAL'; state.activity.currency = 'BOA'; state.participants.type = 'SOCIAL';
    state.participants.items = state.participants.items.map((row) => ({ ...row, enrolled: true }));
  }
  server.use(http.post('*/api/v1/activities/:activityId/participation/preview', () => {
    calls++;
    if (code === 'ALREADY_CONFIRMED') state.participants.items = state.participants.items.map((row) => row.student.id === 1002 ? { ...row, confirmed: { level: 50, amount: 38, confirmedAt: '2026-11-11T09:00:00Z' } } : row);
    else if (code === 'NOT_ENROLLED') state.participants.items = state.participants.items.map((row) => row.student.id === 1002 ? { ...row, enrolled: false } : row);
    else if (code === 'INVALID_STATE') state.activity.confirmationOpen = false;
    else state.participants.items = state.participants.items.filter((row) => row.student.id !== 1002);
    return problem(code, code === 'ALREADY_CONFIRMED' || code === 'INVALID_STATE' ? 409 : 422, code === 'INVALID_STATE' ? {} : { studentIds: [1002] });
  }));
  const view = mountActivities('/activities/603/confirm');
  await view.user.click(await screen.findByRole('checkbox', { name: `Выбрать ${participantNames.open}` }));
  await waitFor(() => expect(calls).toBe(1));
  await waitFor(() => expect(state.requests.filter((r) => r.path.endsWith('/participants')).length).toBe(2));
  expect(state.requests.filter((r) => r.path.endsWith('/603')).length).toBe(2);
  if (code === 'NOT_ENROLLED') {
    await waitFor(() => expect(screen.getByRole('checkbox', { name: `Выбрать ${participantNames.open}` })).toBeDisabled());
    await waitFor(() => expect(screen.getByRole('checkbox', { name: `Выбрать ${participantNames.open}` })).not.toBeChecked());
  } else await waitFor(() => expect(screen.queryByRole('checkbox', { name: `Выбрать ${participantNames.open}` })).not.toBeInTheDocument());
  expect(calls).toBe(1); view.router.dispose(); view.queryClient.clear();
});
it('36-3 indicates a lower bound when the entire poll page is new', async () => {
  const state = createFeedScenario(); const rows = awardRows(21);
  state.pages.set('', { items: [rows[20]!], nextCursor: null });
  const view = mountFeed(); await screen.findByText('Награда №21', { exact: true });
  state.pages.set('', { items: rows.slice(0, 20), nextCursor: 'more-new' });
  await act(async () => { await view.queryClient.refetchQueries({ queryKey: ['awards', authUsers.student.id, 'poll'] }); });
  expect(await screen.findByRole('button', { name: 'Новые награды: 20+ — показать' })).toBeInTheDocument();
  expect(screen.queryByText('Награда №1', { exact: true })).not.toBeInTheDocument(); view.router.dispose(); view.queryClient.clear();
});
it.each(['/groups/11', '/groups/11/ratings'])('36-4 opens rating at the canonical route from %s', async (path) => {
  createFeedScenario(authUsers.teacher); const view = mountFeed(path);
  await screen.findByRole('table'); expect(view.router.state.location.pathname).toBe('/groups/11'); view.router.dispose(); view.queryClient.clear();
});
it('36-4 group links use the screen map route', async () => {
  createFeedScenario(authUsers.teacher); const view = mountFeed('/groups');
  await waitFor(() => expect(screen.getAllByRole('link').some((link) => link.getAttribute('href') === '/groups/11')).toBe(true));
  view.router.dispose(); view.queryClient.clear();
});

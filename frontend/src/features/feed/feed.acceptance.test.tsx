import { act, cleanup, configure, getConfig, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../mocks/server';
import {
  authUsers, awardRows, createFeedScenario, deferredFeed, disposeFeedTests, feedFixtures, feedJson, mountFeed, problem,
} from '../../test/feedAcceptance';

const originalTimeout = getConfig().asyncUtilTimeout;
beforeAll(() => { configure({ asyncUtilTimeout: 3_000 }); server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => {
  cleanup(); disposeFeedTests(); server.resetHandlers(); vi.unstubAllGlobals(); localStorage.clear();
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/';
  document.cookie = 'campus-mock-user=; Max-Age=0; Path=/';
});
afterAll(() => { server.close(); configure({ asyncUtilTimeout: originalTimeout }); });

function feedRequests(state: ReturnType<typeof createFeedScenario>) { return state.requests.filter((item) => item.path.endsWith('/feed/awards')); }
async function poll(view: ReturnType<typeof mountFeed>) {
  await waitFor(() => expect(view.queryClient.getQueryCache().findAll({ predicate: (query) => query.queryKey.includes('awards') && query.queryKey.includes('poll') }).length).toBe(1));
  await act(async () => { await view.queryClient.refetchQueries({ predicate: (query) => query.queryKey.includes('awards') && query.queryKey.includes('poll') }); });
}
function studentOrder() {
  return within(screen.getByRole('table')).getAllByRole('link').map((link) => link.textContent);
}
async function chooseSort(view: ReturnType<typeof mountFeed>, name: 'АР' | 'БУА' | 'БОА') {
  await view.user.click(await screen.findByRole('radio', { name }));
}

describe('U11 лента — R3/R23/R5/R41/KTD18', { timeout: 12_000 }, () => {
  it.each([
    ['студент', authUsers.student], ['преподаватель', authUsers.teacher], ['администратор', { ...authUsers.admin, mustChangePassword: false }],
  ] as const)('доступна роли %s и показывает снимок награды с московским временем', async (_role, viewer) => {
    const state = createFeedScenario(viewer);
    mountFeed('/awards');
    expect(await screen.findByRole('heading', { name: 'Лента наград' })).toBeInTheDocument();
    expect(await screen.findByText('Олимпиада по программированию', { exact: false })).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Кузнецов Дмитрий Андреевич' })[0]).toHaveAttribute('href', '/users/1002');
    expect(screen.getAllByRole('link', { name: 'Петров Павел Петрович' })[0]).toHaveAttribute('href', '/users/2001');
    expect(screen.getByText(/\+38\s*БУА/)).toBeInTheDocument();
    expect(screen.getByText(/\+50\s*БОА/)).toBeInTheDocument();
    expect(screen.getAllByText(/17:05.*МСК/).length).toBeGreaterThan(0);
    expect(screen.getByText(/Олимпиада/).compareDocumentPosition(screen.getByText(/Помощь в организации/)) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(state.requests.every((request) => request.path.endsWith('/feed/awards'))).toBe(true);
    expect(screen.queryByRole('link', { name: /Олимпиада/ })).not.toBeInTheDocument();
  });

  it.each(['/awards', '/groups/11/ratings'])('гость уходит на вход без feature-запросов: %s', async (path) => {
    const state = createFeedScenario(null);
    const { router } = mountFeed(path);
    expect(await screen.findByRole('heading', { name: 'Вход' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
    expect(state.requests).toHaveLength(0);
  });

  it('временный пароль блокирует ленту до смены без feature-запросов', async () => {
    const state = createFeedScenario({ ...authUsers.student, mustChangePassword: true });
    const { router } = mountFeed('/awards');
    await waitFor(() => expect(router.state.location.pathname).toBe('/password/change'));
    expect(state.requests).toHaveLength(0);
  });

  it('загружает 120 уникальных наград по курсору, сохраняет первое появление дубля', async () => {
    const state = createFeedScenario();
    const rows = awardRows(120);
    state.pages.set('', { items: rows.slice(0, 60), nextCursor: 'opaque-next' });
    state.pages.set('opaque-next', { items: [{ ...rows[59]!, activity: { id: 1, title: 'Дубликат не должен заменить оригинал' } }, ...rows.slice(60)], nextCursor: null });
    const view = mountFeed('/awards');
    expect(await screen.findByText('Награда №1', { exact: true })).toBeInTheDocument();
    await view.user.click(await screen.findByRole('button', { name: 'Показать ещё' }));
    expect(await screen.findByText('Награда №120', { exact: false })).toBeInTheDocument();
    for (let index = 1; index <= 120; index++) expect(screen.getAllByText(`Награда №${index}`, { exact: true })).toHaveLength(1);
    expect(screen.queryByText(/Дубликат не должен/)).not.toBeInTheDocument();
    expect(feedRequests(state).some((request) => request.query.cursor === 'opaque-next')).toBe(true);
    expect(feedRequests(state).every((request) => !('offset' in request.query))).toBe(true);
    expect(screen.queryByRole('button', { name: 'Показать ещё' })).not.toBeInTheDocument();
  });

  it('IntersectionObserver грузит следующую страницу и останавливается в конце', async () => {
    let notify: IntersectionObserverCallback | undefined;
    const observe = vi.fn();
    vi.stubGlobal('IntersectionObserver', class { constructor(callback: IntersectionObserverCallback) { notify = callback; } observe = observe; disconnect() {} unobserve() {} });
    const state = createFeedScenario();
    const rows = awardRows(2);
    state.pages.set('', { items: [rows[0]!], nextCursor: 'observer-next' });
    state.pages.set('observer-next', { items: [rows[1]!], nextCursor: null });
    mountFeed('/awards');
    await screen.findByText('Награда №1', { exact: false });
    await waitFor(() => expect(observe).toHaveBeenCalled());
    await act(async () => { notify?.([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver); });
    expect(await screen.findByText('Награда №2', { exact: false })).toBeInTheDocument();
    expect(feedRequests(state).filter((request) => request.query.cursor === 'observer-next')).toHaveLength(1);
    expect(screen.queryByRole('button', { name: 'Показать ещё' })).not.toBeInTheDocument();
  });

  it('ошибка следующей страницы сохраняет строки, повторяет тот же cursor', async () => {
    const state = createFeedScenario();
    const rows = awardRows(2);
    state.pages.set('', { items: [rows[0]!], nextCursor: 'retry-next' });
    let calls = 0;
    server.use(http.get('*/api/v1/feed/awards', ({ request }) => {
      if (new URL(request.url).searchParams.get('cursor') !== 'retry-next') return;
      return ++calls === 1 ? problem('INTERNAL_ERROR', 500) : feedJson({ items: [rows[1]!], nextCursor: null });
    }));
    const view = mountFeed('/awards');
    await view.user.click(await screen.findByRole('button', { name: 'Показать ещё' }));
    expect(await screen.findByRole('button', { name: /Повторить/ })).toBeInTheDocument();
    expect(screen.getByText('Награда №1', { exact: false })).toBeInTheDocument();
    expect(calls).toBe(1);
    await view.user.click(screen.getByRole('button', { name: /Повторить/ }));
    expect(await screen.findByText('Награда №2', { exact: false })).toBeInTheDocument();
    expect(calls).toBe(2);
  });

  it('поллинг показывает 2 новых без изменения списка до нажатия и сбрасывает старые cursors', async () => {
    const state = createFeedScenario();
    const rows = awardRows(4);
    state.pages.set('', { items: [rows[2]!], nextCursor: 'old-next' });
    state.pages.set('old-next', { items: [rows[3]!], nextCursor: null });
    const view = mountFeed('/awards');
    await view.user.click(await screen.findByRole('button', { name: 'Показать ещё' }));
    await screen.findByText('Награда №4', { exact: false });
    const oldNode = screen.getByText('Награда №3', { exact: false });
    state.pages.set('', { items: rows.slice(0, 3), nextCursor: 'fresh-next' });
    state.pages.set('fresh-next', { items: [rows[3]!], nextCursor: null });
    await poll(view);
    const banner = await screen.findByRole('button', { name: /Новые награды: 2.*показать/i });
    expect(screen.queryByText('Награда №1', { exact: false })).not.toBeInTheDocument();
    expect(screen.getByText('Награда №3', { exact: false })).toBe(oldNode);
    expect(screen.getByText('Награда №4', { exact: false })).toBeInTheDocument();
    await view.user.click(banner);
    expect(await screen.findByText('Награда №1', { exact: false })).toBeInTheDocument();
    expect(screen.queryByText('Награда №4', { exact: false })).not.toBeInTheDocument();
    await view.user.click(await screen.findByRole('button', { name: 'Показать ещё' }));
    expect(await screen.findByText('Награда №4', { exact: false })).toBeInTheDocument();
    expect(feedRequests(state).filter((request) => request.query.cursor === 'old-next')).toHaveLength(1);
    expect(feedRequests(state).some((request) => request.query.cursor === 'fresh-next')).toBe(true);
    expect(screen.queryByRole('button', { name: /Новые награды:/ })).not.toBeInTheDocument();
  });

  it('ошибка фонового опроса оставляет награды и даёт повторить опрос', async () => {
    createFeedScenario();
    const view = mountFeed('/awards');
    await screen.findByText(/Олимпиада по программированию/);
    let fail = true;
    server.use(http.get('*/api/v1/feed/awards', () => fail ? problem('INTERNAL_ERROR', 500) : feedJson({ ...feedFixtures.page(), nextCursor: null })));
    await poll(view);
    expect(screen.getByText(/Олимпиада по программированию/)).toBeInTheDocument();
    const retry = await screen.findByRole('button', { name: /Повторить/ });
    fail = false;
    await view.user.click(retry);
    await waitFor(() => expect(screen.queryByRole('button', { name: /Повторить/ })).not.toBeInTheDocument());
  });

  it('отдельно показывает загрузку, затем пустую ленту', async () => {
    createFeedScenario();
    const gate = deferredFeed();
    server.use(http.get('*/api/v1/feed/awards', async () => { await gate.promise; return feedJson({ items: [], nextCursor: null }); }));
    mountFeed('/awards');
    expect(await screen.findByRole('status')).toBeInTheDocument();
    await act(async () => { gate.resolve(); });
    expect(await screen.findByText(/наград.*нет|наград.*пока нет|награды.*появятся/i)).toBeInTheDocument();
  });

  it.each(['500', 'network', 'malformed'] as const)('первая ошибка %s не становится пустотой и исправляется retry', async (kind) => {
    createFeedScenario();
    let fail = true;
    server.use(http.get('*/api/v1/feed/awards', () => {
      if (!fail) return feedJson({ ...feedFixtures.page(), nextCursor: null });
      if (kind === 'network') return HttpResponse.error();
      if (kind === 'malformed') return feedJson({ items: [{ ...feedFixtures.page().items[0]!, amount: -20 }], nextCursor: null });
      return problem('INTERNAL_ERROR', 500);
    }));
    const view = mountFeed('/awards');
    expect(await screen.findByRole('button', { name: /Повторить/ })).toBeInTheDocument();
    expect(screen.queryByText(/наград пока нет/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/−20|-20/)).not.toBeInTheDocument();
    fail = false;
    await view.user.click(screen.getByRole('button', { name: /Повторить/ }));
    expect(await screen.findByText(/Олимпиада по программированию/)).toBeInTheDocument();
  });
});

describe('U11 рейтинги — R3/R22/KTD23', { timeout: 12_000 }, () => {
  it('показывает snapshot balances, сортировки API и ссылки на профили', async () => {
    const state = createFeedScenario(authUsers.teacher);
    const view = mountFeed('/groups/11/ratings');
    expect(await screen.findByRole('table')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /ИВТ-21/ })).toBeInTheDocument();
    expect(screen.getByText('Факультет информационных технологий')).toBeInTheDocument();
    for (const name of ['Студент', 'АР', 'БУА', 'БОА']) expect(within(screen.getByRole('table')).getByRole('columnheader', { name })).toBeInTheDocument();
    expect(studentOrder()).toEqual(['Иванова Ирина Игоревна', 'Кузнецов Дмитрий Андреевич', 'Смирнова Анна Олеговна']);
    expect(within(screen.getByRole('table')).getByRole('link', { name: 'Смирнова Анна Олеговна' })).toHaveAttribute('href', '/users/1001');
    const row = screen.getByRole('link', { name: 'Смирнова Анна Олеговна' }).closest('tr')!;
    expect(within(row).getByText('940')).toBeInTheDocument(); expect(within(row).getByText('120')).toBeInTheDocument(); expect(within(row).getByText('50')).toBeInTheDocument();
    await chooseSort(view, 'БУА');
    await waitFor(() => expect(studentOrder()).toEqual(['Смирнова Анна Олеговна', 'Иванова Ирина Игоревна', 'Кузнецов Дмитрий Андреевич']));
    expect(state.requests.some((request) => request.query.sort === 'BUA')).toBe(true);
    await chooseSort(view, 'БОА');
    await waitFor(() => expect(studentOrder()).toEqual(['Смирнова Анна Олеговна', 'Иванова Ирина Игоревна', 'Кузнецов Дмитрий Андреевич']));
    expect(state.requests.some((request) => request.query.sort === 'BOA')).toBe(true);
    expect(state.requests.every((request) => !request.path.includes('ledger'))).toBe(true);
  });

  it('сохраняет порядок ответа сервера при равных балансах, не пересчитывает его', async () => {
    createFeedScenario(authUsers.teacher);
    const rating = feedFixtures.rating();
    rating.items = rating.items.reverse().map((row) => ({ ...row, balances: { AR: 940, BUA: 38, BOA: 0 } }));
    server.use(http.get('*/api/v1/groups/:groupId/ratings', () => feedJson(rating)));
    mountFeed('/groups/11/ratings');
    await screen.findByRole('table');
    expect(studentOrder()).toEqual(rating.items.map((row) => row.student.fullName));
  });

  it('студент не запрашивает рейтинг и доступные группы', async () => {
    const state = createFeedScenario(authUsers.student);
    mountFeed('/groups/11/ratings');
    expect(await screen.findByText('Нет доступа', { exact: true })).toBeInTheDocument();
    expect(state.requests).toHaveLength(0);
  });

  it('администратор видит рейтинг любой группы (U17 R2)', async () => {
    createFeedScenario({ ...authUsers.admin, mustChangePassword: false });
    mountFeed('/groups/11/ratings');
    expect(await screen.findByRole('table')).toBeInTheDocument();
    expect(screen.queryByText('Нет доступа', { exact: true })).not.toBeInTheDocument();
  });

  it.each(['abc', '0', '-1', '9007199254740992'])('id %s отклоняется без rating-запроса', async (id) => {
    const state = createFeedScenario(authUsers.teacher);
    mountFeed(`/groups/${id}/ratings`);
    expect(await screen.findByText('Группа не найдена', { exact: true })).toBeInTheDocument();
    expect(state.requests.some((request) => request.path.endsWith('/ratings'))).toBe(false);
  });

  it.each([[403, 'GROUP_NOT_ACCESSIBLE', 'Нет доступа'], [404, 'NOT_FOUND', 'Группа не найдена']] as const)('обрабатывает HTTP %s прямой ссылки', async (status, code, message) => {
    createFeedScenario(authUsers.teacher);
    server.use(http.get('*/api/v1/groups/:groupId/ratings', () => problem(code, status)));
    mountFeed('/groups/999/ratings');
    expect(await screen.findByText(message, { exact: true })).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('группы с доступом другого факультета берутся из scope, а не из бюджета', async () => {
    const state = createFeedScenario(authUsers.teacher);
    const foreign = { id: 77, name: 'МАТ-22', faculty: { id: 7, code: 'МАТ', name: 'Математический факультет' } };
    state.groups.items.push(foreign);
    state.ratings.set(77, { ...feedFixtures.rating(), group: foreign });
    const view = mountFeed('/groups');
    await waitFor(() => expect(screen.getAllByRole('link').some((link) => link.getAttribute('href') === '/groups/77')).toBe(true));
    expect(state.requests.some((request) => request.path.endsWith('/me/accessible-groups'))).toBe(true);
    expect(screen.getByRole('link', { name: 'Лента наград' })).toHaveAttribute('href', '/awards');
    const target = screen.getAllByRole('link').find((link) => link.getAttribute('href') === '/groups/77')!;
    await view.user.click(target);
    expect(await screen.findByRole('heading', { name: /МАТ-22/ })).toBeInTheDocument();
    expect(screen.getByText('Математический факультет')).toBeInTheDocument();
  });

  it('поздний ответ старой сортировки отменяется и не заменяет новый', async () => {
    const state = createFeedScenario(authUsers.teacher);
    const gate = deferredFeed();
    let started = false, aborted = false;
    server.use(http.get('*/api/v1/groups/:groupId/ratings', async ({ request }) => {
      if (new URL(request.url).searchParams.get('sort') !== 'BUA') return;
      started = true; request.signal.addEventListener('abort', () => { aborted = true; });
      await gate.promise;
      const rating = feedFixtures.rating();
      return feedJson({ ...rating, sort: 'BUA', items: [{ ...rating.items[0]!, student: { id: 1999, fullName: 'Устаревшая сортировка' } }] });
    }));
    const view = mountFeed('/groups/11/ratings');
    await screen.findByRole('table');
    await chooseSort(view, 'БУА');
    await waitFor(() => expect(started).toBe(true));
    await chooseSort(view, 'БОА');
    await waitFor(() => expect(state.requests.some((request) => request.query.sort === 'BOA')).toBe(true));
    await screen.findByRole('table');
    await act(async () => { gate.resolve(); });
    await waitFor(() => expect(aborted).toBe(true));
    expect(screen.queryByText('Устаревшая сортировка')).not.toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'БОА' })).toBeChecked();
  });

  it('переход в другую группу отменяет прежний ответ и не смешивает студентов', async () => {
    createFeedScenario(authUsers.teacher);
    const gate = deferredFeed();
    const rating = feedFixtures.rating();
    let started = false, aborted = false;
    server.use(http.get('*/api/v1/groups/:groupId/ratings', async ({ request, params }) => {
      if (params.groupId === '11') {
        started = true; request.signal.addEventListener('abort', () => { aborted = true; });
        await gate.promise;
        return feedJson({ ...rating, items: [{ ...rating.items[0]!, student: { id: 1888, fullName: 'Старая группа' } }] });
      }
      return feedJson({ ...rating, group: { ...rating.group, id: 12, name: 'Новая группа' }, items: [] });
    }));
    const view = mountFeed('/groups/11/ratings');
    await waitFor(() => expect(started).toBe(true));
    await act(async () => { await view.router.navigate('/groups/12/ratings'); });
    expect(await screen.findByRole('heading', { name: /Новая группа/ })).toBeInTheDocument();
    await act(async () => { gate.resolve(); });
    await waitFor(() => expect(aborted).toBe(true));
    expect(screen.queryByText('Старая группа')).not.toBeInTheDocument();
  });

  it.each(['500', 'malformed', 'wrong-group', 'wrong-sort'] as const)('ошибка %s рейтинга требует retry и не показывает неверные данные', async (kind) => {
    createFeedScenario(authUsers.teacher);
    let fail = true;
    server.use(http.get('*/api/v1/groups/:groupId/ratings', () => {
      const rating = feedFixtures.rating();
      if (!fail) return feedJson(rating);
      if (kind === '500') return problem('INTERNAL_ERROR', 500);
      if (kind === 'wrong-group') return feedJson({ ...rating, group: { ...rating.group, id: 22 } });
      if (kind === 'wrong-sort') return feedJson({ ...rating, sort: 'BOA' });
      return feedJson({ ...rating, items: [{ ...rating.items[0]!, balances: { AR: 1001, BUA: 38, BOA: 0 } }] });
    }));
    const view = mountFeed('/groups/11/ratings');
    expect(await screen.findByRole('button', { name: /Повторить/ })).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    fail = false;
    await view.user.click(screen.getByRole('button', { name: /Повторить/ }));
    expect(await screen.findByRole('table')).toBeInTheDocument();
  });

  it('загрузка рейтинга сменяется объяснением пустого списка', async () => {
    createFeedScenario(authUsers.teacher);
    const gate = deferredFeed();
    server.use(http.get('*/api/v1/groups/:groupId/ratings', async () => { await gate.promise; return feedJson({ ...feedFixtures.rating(), items: [] }); }));
    mountFeed('/groups/11/ratings');
    expect(await screen.findByRole('status')).toBeInTheDocument();
    await act(async () => { gate.resolve(); });
    expect(await screen.findByText(/студент.*нет|студент.*пока нет|рейтинг.*пуст/i)).toBeInTheDocument();
  });

  it('ошибка новой сортировки не выдаёт старую таблицу за новый результат', async () => {
    createFeedScenario(authUsers.teacher);
    server.use(http.get('*/api/v1/groups/:groupId/ratings', ({ request }) => new URL(request.url).searchParams.get('sort') === 'BUA' ? problem('INTERNAL_ERROR', 500) : undefined));
    const view = mountFeed('/groups/11/ratings');
    await screen.findByRole('table');
    await chooseSort(view, 'БУА');
    expect(await screen.findByRole('button', { name: /Повторить/ })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'БУА' })).toBeChecked();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('пустой scope объясняется отдельно от истории и бюджета', async () => {
    const state = createFeedScenario(authUsers.teacher);
    state.groups.items = [];
    mountFeed('/groups');
    expect(await screen.findByText(/доступных групп.*нет|нет доступных групп|доступные группы.*появятся/i)).toBeInTheDocument();
    expect(screen.queryAllByRole('link').filter((link) => /^\/groups\/\d+$/.test(link.getAttribute('href') ?? ''))).toHaveLength(0);
  });

  it('ошибка доступных групп имеет независимый retry и не скрывает бюджет', async () => {
    createFeedScenario(authUsers.teacher);
    let fail = true;
    server.use(http.get('*/api/v1/me/accessible-groups', () => fail ? problem('INTERNAL_ERROR', 500) : feedJson({ items: [feedFixtures.rating().group] })));
    const view = mountFeed('/groups');
    expect(await screen.findByRole('button', { name: /Повторить/ })).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Бюджет групп' })).toBeInTheDocument();
    fail = false;
    await view.user.click(screen.getByRole('button', { name: /Повторить/ }));
    await waitFor(() => expect(screen.getAllByRole('link').some((link) => link.getAttribute('href') === '/groups/11')).toBe(true));
  });
});

import { act, cleanup, configure, getConfig, screen, waitFor, within } from '@testing-library/react';
import { http } from 'msw';
import { server } from '../../mocks/server';
import {
  authUsers, createProfileScenario, ledgerRows, mountProfile, problem, profileDeferred, profileFixtures, profileJson,
} from '../../test/profileAcceptance';

const originalAsyncTimeout = getConfig().asyncUtilTimeout;
// Lazy-маршрут, auth и профиль проходят несколько async-слоёв; на загруженном хосте одной секунды недостаточно.
beforeAll(() => { configure({ asyncUtilTimeout: 5_000 }); server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { cleanup(); server.resetHandlers(); localStorage.clear(); document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'; });
afterAll(() => { server.close(); configure({ asyncUtilTimeout: originalAsyncTimeout }); });

describe('U10 профиль и задачи — R3/R5', { timeout: 15_000 }, () => {
  it('показывает балансы API 940/120/50 и ссылки только на свои назначения', async () => {
    const state = createProfileScenario();
    state.pages.set('', { items: [], nextCursor: null });
    const { user } = mountProfile();
    expect(await screen.findByRole('heading', { name: authUsers.student.fullName })).toBeInTheDocument();
    expect(screen.getAllByText(/ИВТ-21/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/Факультет информационных технологий/).length).toBeGreaterThan(0);
    expect(screen.getByText(/\b940\b/)).toBeInTheDocument();
    expect(screen.getByText(/\b120\b/)).toBeInTheDocument();
    expect(screen.getByText(/\b50\b/)).toBeInTheDocument();
    expect(screen.getAllByText(/из 1000/).length).toBeGreaterThan(0);
    expect(screen.getByRole('tab', { name: 'История' })).toHaveAttribute('aria-selected', 'true');
    await user.click(screen.getByRole('tab', { name: 'Задачи' }));
    expect(await screen.findByRole('link', { name: 'Лабораторная 3. Деревья поиска' })).toHaveAttribute('href', '/tasks/7001');
    expect(screen.getByRole('link', { name: 'Лабораторная 2. Сортировки' })).toHaveAttribute('href', '/tasks/6990');
  });

  it('PUBLIC не создаёт переходов и не показывает лишние приватные поля', async () => {
    const state = createProfileScenario(authUsers.teacher);
    const profile = profileFixtures.publicStudent();
    profile.activeTasks = profile.activeTasks.map((task) => ({ ...task, assignmentId: 7001,
      file: 'private-file.pdf', link: 'https://private.example/submission', returnComment: 'Скрытый комментарий' }));
    state.profiles.set(1001, profile);
    const { user } = mountProfile();
    await user.click(await screen.findByRole('tab', { name: 'Задачи' }));
    const tasks = within(await screen.findByRole('tabpanel', { name: 'Задачи' }));
    expect(tasks.getByText('Лабораторная 3. Деревья поиска')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Лабораторная 3. Деревья поиска' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Материалы сдачи' })).not.toBeInTheDocument();
    expect(screen.queryByText(/private-file|private\.example|Скрытый комментарий/)).not.toBeInTheDocument();
    expect(screen.queryAllByRole('link').filter((link) => /\/tasks\/\d|\/admin\/submissions\/|private\.example|private-file/.test(link.getAttribute('href') ?? ''))).toHaveLength(0);
  });

  it('профиль преподавателя показывает кафедру/факультет/ленту без запроса ledger', async () => {
    const state = createProfileScenario();
    mountProfile('/users/2001');
    expect(await screen.findByRole('heading', { name: authUsers.teacher.fullName })).toBeInTheDocument();
    expect(screen.getByText('Кафедра программной инженерии')).toBeInTheDocument();
    expect(screen.getByText('Факультет информационных технологий')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Лента наград' })).toHaveAttribute('href', '/awards');
    expect(screen.queryByRole('tab', { name: 'Задачи' })).not.toBeInTheDocument();
    expect(state.requests.some((request) => request.path.includes('/ledger/'))).toBe(false);
  });

  it('профиль администратора не превращается в студенческий по роли зрителя', async () => {
    const state = createProfileScenario();
    mountProfile('/users/3001');
    expect(await screen.findByRole('heading', { name: 'Администратор стенда' })).toBeInTheDocument();
    expect(screen.getByText('Администратор', { exact: true })).toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: 'История' })).not.toBeInTheDocument();
    expect(screen.queryByText(/из 1000/)).not.toBeInTheDocument();
    expect(state.requests.some((request) => request.path.includes('/ledger/'))).toBe(false);
  });

  it('ADMIN-зритель получает предусмотренную ссылку на материалы сдачи', async () => {
    createProfileScenario({ ...authUsers.admin, mustChangePassword: false });
    const { user } = mountProfile();
    await user.click(await screen.findByRole('tab', { name: 'Задачи' }));
    expect(await screen.findByRole('link', { name: 'Материалы сдачи' })).toHaveAttribute('href', '/admin/submissions/7001');
    expect(screen.queryByRole('link', { name: 'Лабораторная 3. Деревья поиска' })).not.toBeInTheDocument();
  });

  it.each(['OWN', 'ADMIN'] as const)('ошибочный %s в чужом профиле не повышает права зрителя', async (view) => {
    const state = createProfileScenario(authUsers.teacher);
    const profile = view === 'OWN' ? profileFixtures.ownStudent() : profileFixtures.adminStudent();
    state.profiles.set(1001, profile);
    const { user } = mountProfile();
    await user.click(await screen.findByRole('tab', { name: 'Задачи' }));
    const tasks = within(await screen.findByRole('tabpanel', { name: 'Задачи' }));
    expect(tasks.getByText('Лабораторная 3. Деревья поиска')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Лабораторная 3. Деревья поиска' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Материалы сдачи' })).not.toBeInTheDocument();
    expect(screen.queryAllByRole('link').filter((link) => /\/tasks\/\d|\/admin\/submissions\//.test(link.getAttribute('href') ?? ''))).toHaveLength(0);
  });

  it('статусы и порядок активных/завершённых задач берутся из серверных массивов', async () => {
    const state = createProfileScenario();
    const profile = profileFixtures.ownStudent();
    profile.activeTasks.push({ view: 'OWN', assignmentId: 7002, title: 'Отчёт отправлен', deadline: '2100-01-01T09:00:00Z', status: 'SUBMITTED', charged: 0 });
    state.profiles.set(1001, profile);
    const { user } = mountProfile();
    await user.click(await screen.findByRole('tab', { name: 'Задачи' }));
    expect(await screen.findByRole('heading', { name: 'Активные задачи' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Завершённые задачи' })).toBeInTheDocument();
    expect(screen.getByText('Просрочено')).toBeInTheDocument();
    expect(screen.getByText('Сдано на проверку')).toBeInTheDocument();
    expect(screen.getByText('Принято')).toBeInTheDocument();
    expect(screen.getByText(/Списано 40 АР/)).toBeInTheDocument();
    expect(screen.getAllByText(/12:00/).length).toBeGreaterThan(0);
    const tasks = within(screen.getByRole('tabpanel', { name: 'Задачи' }));
    expect(tasks.getByText('Лабораторная 3. Деревья поиска').compareDocumentPosition(tasks.getByText('Отчёт отправлен')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('пустота активных и завершённых задач объясняется отдельно', async () => {
    const state = createProfileScenario();
    state.profiles.set(1001, { ...profileFixtures.ownStudent(), activeTasks: [], completedTasks: [] });
    const { user } = mountProfile();
    await user.click(await screen.findByRole('tab', { name: 'Задачи' }));
    expect(await screen.findByText(/нет активных задач|активных задач.*нет/i)).toBeInTheDocument();
    expect(screen.getByText(/нет завершённых задач|завершённых задач.*нет|завершённые задачи.*появятся/i)).toBeInTheDocument();
  });
});

describe('U10 история — R6/R41/KTD18', { timeout: 15_000 }, () => {
  it('сохраняет знаки, исторические снимки, системного автора и московскую дату', async () => {
    const state = createProfileScenario();
    const page = state.pages.get('');
    if (!page) throw new Error('Missing ledger fixture');
    page.items = page.items.map((item) => item.kind === 'ACTIVITY_AWARD' ? { ...item,
      source: { ...item.source, title: 'Конференция до переименования' }, author: { id: 2001, fullName: 'Историческое ФИО преподавателя' } } : item);
    page.items.push({ id: 79000, createdAt: '2026-09-01T08:00:00Z', currency: 'AR', amount: 20,
      kind: 'SEMESTER_ACCRUAL', source: { type: 'SEMESTER', id: 1, title: 'Осенний семестр' }, author: null });
    mountProfile();
    expect(await screen.findByText(/\+50\s*БОА/)).toBeInTheDocument();
    expect(screen.getByText(/[−-]20\s*АР/)).toBeInTheDocument();
    expect(screen.getByText(/\+1000\s*АР/)).toBeInTheDocument();
    expect(screen.getByText('Стартовые баллы')).toBeInTheDocument();
    expect(screen.getByText(/Просрочка/)).toBeInTheDocument();
    expect(screen.getByText('Осенний семестр')).toBeInTheDocument();
    expect(screen.getAllByText('Система').length).toBeGreaterThan(0);
    expect(screen.getByText(/Конференция до переименования/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Конференция до переименования/ })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Историческое ФИО преподавателя' })).toHaveAttribute('href', '/users/2001');
    expect(screen.getAllByText(/17:00/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/МСК/).length).toBeGreaterThan(0);
    expect(state.requests.some((request) => request.path === '/api/v1/users/2001/profile' || request.path.startsWith('/api/v1/activities/'))).toBe(false);
  });

  it('фильтр валюты отправляет currency и начинает курсор с первой страницы', async () => {
    const state = createProfileScenario();
    const { user } = mountProfile();
    await screen.findByText(/\+50\s*БОА/);
    await user.click(screen.getByRole('radio', { name: 'БОА' }));
    await waitFor(() => expect(state.requests.some((request) => request.path.includes('/ledger/') && request.query.currency === 'BOA' && !request.query.cursor)).toBe(true));
    expect(screen.queryByText(/[−-]20\s*АР/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('radio', { name: 'Все' }));
    expect(await screen.findByText(/[−-]20\s*АР/)).toBeInTheDocument();
    await waitFor(() => expect(state.requests.filter((request) => request.path.includes('/ledger/')).at(-1)?.query.currency).toBeUndefined());
  });

  it('склеивает120 уникальных записей и не выводит баланс суммой загруженных страниц', async () => {
    const state = createProfileScenario();
    const rows = ledgerRows(120);
    state.pages.set('', { items: rows.slice(0, 60), nextCursor: 'profile-page-2' });
    state.pages.set('profile-page-2', { items: rows.slice(59), nextCursor: null });
    const { user } = mountProfile();
    await user.click(await screen.findByRole('button', { name: 'Показать ещё' }));
    expect(await screen.findByText(/^«?Историческая запись 120»?$/)).toBeInTheDocument();
    expect(screen.getAllByText(/^«?Историческая запись \d+»?$/)).toHaveLength(120);
    expect(screen.getAllByText(/^«?Историческая запись 60»?$/)).toHaveLength(1);
    expect(screen.getByText(/^940$/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Показать ещё' })).not.toBeInTheDocument();
    expect(state.requests.some((request) => request.query.cursor === 'profile-page-2')).toBe(true);
  });

  it('при смене валюты отменяет старый запрос и не принимает позднюю страницу', async () => {
    createProfileScenario();
    const old = profileDeferred();
    let oldStarted = false;
    let oldSignal: AbortSignal | undefined;
    server.use(http.get('*/api/v1/ledger/1001/entries', async ({ request }) => {
      const currency = new URL(request.url).searchParams.get('currency');
      const entry = ledgerRows(1)[0];
      if (!entry) throw new Error('Missing synthetic ledger entry');
      if (currency === 'BUA') { oldStarted = true; oldSignal = request.signal; await old.promise; }
      return profileJson({ items: [{ ...entry, currency: currency ?? 'BOA', source: { ...entry.source,
        title: currency === 'BUA' ? 'Позднее учебное начисление' : 'Актуальное общественное начисление' } }], nextCursor: null });
    }));
    const { user } = mountProfile();
    try {
      await screen.findByText('Актуальное общественное начисление');
      await user.click(screen.getByRole('radio', { name: 'БУА' }));
      await waitFor(() => expect(oldStarted).toBe(true));
      await user.click(screen.getByRole('radio', { name: 'БОА' }));
      expect(await screen.findByText('Актуальное общественное начисление')).toBeInTheDocument();
      expect(oldSignal?.aborted).toBe(true);
      await act(async () => { old.resolve(); });
      expect(screen.queryByText('Позднее учебное начисление')).not.toBeInTheDocument();
    } finally { old.resolve(); }
  });

  it('смена id отменяет историю прежнего пользователя и не смешивает строки', async () => {
    createProfileScenario();
    const old = profileDeferred();
    let oldSignal: AbortSignal | undefined;
    let oldStarted = false;
    server.use(http.get('*/api/v1/ledger/:userId/entries', async ({ request, params }) => {
      if (params.userId === '1001') { oldSignal = request.signal; oldStarted = true; await old.promise; }
      const entry = ledgerRows(1)[0];
      if (!entry) throw new Error('Missing synthetic ledger entry');
      return profileJson({ items: [{ ...entry, source: { ...entry.source, title: `История пользователя ${params.userId}` } }], nextCursor: null });
    }));
    const { router } = mountProfile();
    try {
      await screen.findByRole('heading', { name: authUsers.student.fullName });
      await waitFor(() => expect(oldStarted).toBe(true));
      await act(async () => { await router.navigate('/users/1002'); });
      expect(await screen.findByRole('heading', { name: 'Кузнецов Дмитрий Андреевич' })).toBeInTheDocument();
      expect(await screen.findByText('История пользователя 1002')).toBeInTheDocument();
      expect(oldSignal?.aborted).toBe(true);
      await act(async () => { old.resolve(); });
      expect(screen.queryByText('История пользователя 1001')).not.toBeInTheDocument();
    } finally { old.resolve(); }
  });
});

describe('U10 раздельные состояния и отказы', { timeout: 15_000 }, () => {
  it('загрузка профиля не показывает фиктивные данные и не запускает ledger заранее', async () => {
    const state = createProfileScenario();
    const loading = profileDeferred();
    let requested = false;
    server.use(http.get('*/api/v1/users/1001/profile', async () => { requested = true; await loading.promise; return profileJson(profileFixtures.ownStudent()); }));
    mountProfile();
    try {
      await waitFor(() => expect(requested).toBe(true));
      expect(screen.getByRole('status')).toBeInTheDocument();
      expect(screen.queryByText(/^940$/)).not.toBeInTheDocument();
      expect(state.requests.some((request) => request.path.includes('/ledger/'))).toBe(false);
      loading.resolve();
      expect(await screen.findByRole('heading', { name: authUsers.student.fullName })).toBeInTheDocument();
    } finally { loading.resolve(); }
  });

  it('ошибка профиля имеет рабочий повтор', async () => {
    createProfileScenario();
    let fail = true;
    server.use(http.get('*/api/v1/users/1001/profile', () => fail ? problem('INTERNAL_ERROR', 500) : profileJson(profileFixtures.ownStudent())));
    const { user } = mountProfile();
    const retry = await screen.findByRole('button', { name: /Повторить/i });
    fail = false;
    await user.click(retry);
    expect(await screen.findByRole('heading', { name: authUsers.student.fullName })).toBeInTheDocument();
  });

  it.each([[404, 'NOT_FOUND', 'Пользователь не найден'], [403, 'FORBIDDEN', 'Нет доступа']] as const)('%s профиля даёт понятный отказ', async (status, code, text) => {
    const state = createProfileScenario();
    server.use(http.get('*/api/v1/users/1001/profile', () => problem(code, status)));
    mountProfile();
    expect(await screen.findByText(text)).toBeInTheDocument();
    expect(state.requests.some((request) => request.path.includes('/ledger/'))).toBe(false);
  });

  it('загрузка и пустота истории не убирают уже загруженный профиль', async () => {
    createProfileScenario();
    const loading = profileDeferred();
    server.use(http.get('*/api/v1/ledger/1001/entries', async () => { await loading.promise; return profileJson({ items: [], nextCursor: null }); }));
    mountProfile();
    try {
      expect(await screen.findByRole('heading', { name: authUsers.student.fullName })).toBeInTheDocument();
      expect(screen.getByText(/^940$/)).toBeInTheDocument();
      expect(screen.getByRole('status')).toBeInTheDocument();
      loading.resolve();
      expect(await screen.findByText(/история.*пуста|проводок.*нет|нет.*проводок|история.*появится/i)).toBeInTheDocument();
      expect(screen.getByText(/^940$/)).toBeInTheDocument();
    } finally { loading.resolve(); }
  });

  it('ошибка истории повторяется независимо от профиля', async () => {
    const state = createProfileScenario();
    let fail = true;
    server.use(http.get('*/api/v1/ledger/1001/entries', () => fail ? problem('INTERNAL_ERROR', 500) : profileJson({ items: [], nextCursor: null })));
    const { user } = mountProfile();
    const retry = await screen.findByRole('button', { name: /Повторить/i });
    expect(screen.getByRole('heading', { name: authUsers.student.fullName })).toBeInTheDocument();
    fail = false;
    await user.click(retry);
    expect(await screen.findByText(/история.*пуста|проводок.*нет|нет.*проводок|история.*появится/i)).toBeInTheDocument();
    expect(state.requests.filter((request) => request.path.endsWith('/profile'))).toHaveLength(1);
  });

  it('ошибка следующей страницы сохраняет старые строки и повторяет именно её cursor', async () => {
    const state = createProfileScenario();
    state.pages.set('', { items: ledgerRows(1), nextCursor: 'profile-page-2' });
    let fail = true;
    const cursors: (string | null)[] = [];
    server.use(http.get('*/api/v1/ledger/1001/entries', ({ request }) => {
      const cursor = new URL(request.url).searchParams.get('cursor');
      cursors.push(cursor);
      if (!cursor) return profileJson(state.pages.get(''));
      return fail ? problem('INTERNAL_ERROR', 500) : profileJson({ items: ledgerRows(2).slice(1), nextCursor: null });
    }));
    const { user } = mountProfile();
    await user.click(await screen.findByRole('button', { name: 'Показать ещё' }));
    const retry = await screen.findByRole('button', { name: /Повторить/i });
    expect(screen.getByText('Историческая запись 1')).toBeInTheDocument();
    fail = false;
    await user.click(retry);
    expect(await screen.findByText('Историческая запись 2')).toBeInTheDocument();
    expect(screen.getByText('Историческая запись 1')).toBeInTheDocument();
    expect(cursors.filter((cursor) => cursor === 'profile-page-2')).toHaveLength(2);
  });

  it('malformed-профиль не подменяется нулевыми балансами', async () => {
    const state = createProfileScenario();
    server.use(http.get('*/api/v1/users/1001/profile', () => profileJson({ ...profileFixtures.ownStudent(), balances: { AR: 1001, BUA: 120, BOA: 50 } })));
    mountProfile();
    expect(await screen.findByRole('button', { name: /Повторить/i })).toBeInTheDocument();
    expect(screen.queryByText(/^0$/)).not.toBeInTheDocument();
    expect(state.requests.some((request) => request.path.includes('/ledger/'))).toBe(false);
  });

  it('нулевая malformed-проводка не отображается как законная история', async () => {
    createProfileScenario();
    const entry = ledgerRows(1)[0];
    if (!entry) throw new Error('Missing synthetic ledger entry');
    server.use(http.get('*/api/v1/ledger/1001/entries', () => profileJson({ items: [{ ...entry, amount: 0 }], nextCursor: null })));
    mountProfile();
    expect(await screen.findByRole('button', { name: /Повторить/i })).toBeInTheDocument();
    expect(screen.queryByText('Историческая запись 1')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: authUsers.student.fullName })).toBeInTheDocument();
  });

  it('abc/0/unsafe id не вызывают feature API', async () => {
    for (const id of ['abc', '0', '9007199254740992']) {
      const state = createProfileScenario();
      const view = mountProfile(`/users/${id}`);
      expect(await screen.findByText('Пользователь не найден')).toBeInTheDocument();
      expect(state.requests).toHaveLength(0);
      view.unmount();
      view.queryClient.clear();
      server.resetHandlers();
    }
  });

  it('гость и временный пароль проходят auth guard без feature API', async () => {
    for (const viewer of [null, authUsers.admin]) {
      const state = createProfileScenario(viewer);
      const view = mountProfile();
      if (!viewer) {
        expect(await screen.findByRole('button', { name: 'Войти' })).toBeInTheDocument();
        expect(view.router.state.location.pathname).toBe('/login');
      } else {
        expect(await screen.findByLabelText('Текущий пароль')).toBeInTheDocument();
        expect(view.router.state.location.pathname).toBe('/password/change');
      }
      expect(state.requests).toHaveLength(0);
      view.unmount();
      view.queryClient.clear();
      server.resetHandlers();
    }
  });
});

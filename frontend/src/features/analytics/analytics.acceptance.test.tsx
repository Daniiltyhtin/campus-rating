import { cleanup, configure, getConfig, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { vi } from 'vitest';
import { server } from '../../mocks/server';
import { authUsers, choose, createAuthScenario, mountAuth, problem } from '../../test/authAcceptance';
import { feedFixtures } from '../../test/feedAcceptance';

const timeout = getConfig().asyncUtilTimeout;
const views = new Set<ReturnType<typeof mountAuth>>();
const group = { ...feedFixtures.rating().group, id: 1, name: 'Т-1' };
const a = { id: 101, fullName: 'Антонов Антон' };
const b = { id: 102, fullName: 'Борисов Борис' };
const e = { id: 103, fullName: 'Егоров Егор' };
const petrov = { id: 201, fullName: 'Петров' };
const volkov = { id: 202, fullName: 'Волков' };
const points = [
  { student: e, bua: 0, boa: 0, ar: 990 },
  { student: b, bua: 10, boa: 50, ar: 1000 },
  { student: a, bua: 30, boa: 0, ar: 980 },
];
const days: Array<{ date: string; averageAr: number | null; students: number }> = [1000, 980, 990, 990].map((averageAr, i) => ({ date: `2026-11-0${i + 1}`, averageAr, students: i < 2 ? 1 : 2 }));
const budgetRow = { month: '2026-11', teacher: petrov, limit: 1000, spent: 950, remaining: 50, awards: 3, bua: 800, boa: 150 };
const validationMessage = 'Допустимо не больше 366 дней, «С» не позже «По» и «По» не позже сегодня';

beforeAll(() => { configure({ asyncUtilTimeout: 3_000 }); server.listen({ onUnhandledFrame: 'error' }); });
beforeEach(() => { vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2026-11-13T09:00:00Z')); });
afterEach(() => {
  cleanup();
  for (const view of views) { view.router.dispose(); view.queryClient.clear(); }
  views.clear(); server.resetHandlers(); vi.useRealTimers(); vi.restoreAllMocks(); localStorage.clear();
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/';
});
afterAll(() => { server.close(); configure({ asyncUtilTimeout: timeout }); });

function scenario(viewer = authUsers.teacher as typeof authUsers.teacher | typeof authUsers.admin | typeof authUsers.student) {
  createAuthScenario({ ...viewer, mustChangePassword: false });
  const state = {
    requests: [] as URL[], budgetRequests: [] as URL[],
    response: { group, semesterStart: '2026-09-01' as string | null, days, items: points },
    budget: { items: Array.from({ length: 6 }, (_, i) => i === 0 ? budgetRow : {
      ...budgetRow, month: `2026-${String(11 - i).padStart(2, '0')}`, spent: 0, remaining: 1000, awards: 0, bua: 0, boa: 0,
    }) as Array<Omit<typeof budgetRow, 'teacher' | 'remaining'> & { teacher: typeof petrov | null; remaining: number | null }> },
    forbidden: false,
  };
  server.use(
    http.get('*/api/v1/groups/:id/ratings', () => HttpResponse.json({ ...feedFixtures.rating(), group })),
    http.get('*/api/v1/groups/:id/analytics', ({ request }) => {
      state.requests.push(new URL(request.url));
      return state.forbidden ? problem('GROUP_NOT_ACCESSIBLE', 403) : HttpResponse.json(state.response);
    }),
    http.get('*/api/v1/groups/:id/analytics/budget', ({ request }) => {
      state.budgetRequests.push(new URL(request.url));
      return state.forbidden ? problem('GROUP_NOT_ACCESSIBLE', 403) : HttpResponse.json(state.budget);
    }),
  );
  return state;
}
function mount(path = '/groups/1?tab=analytics') {
  const view = mountAuth(path); views.add(view); return view;
}
async function table(name: string) { return within(await screen.findByRole('table', { name })); }
async function selectPeriod(view: ReturnType<typeof mount>, value: string) { await choose(view.user, 'Период', value); }
async function custom(view: ReturnType<typeof mount>, from: string, to: string) {
  await selectPeriod(view, 'Свои даты');
  for (const [label, value] of [['С', from], ['По', to]]) {
    const input = screen.getByLabelText(label!, { exact: true });
    await view.user.clear(input);
    const text = input instanceof HTMLInputElement && input.type === 'date' ? value!.split('.').reverse().join('-') : value!;
    await view.user.type(input, text); await view.user.tab();
  }
}
function dates(state: ReturnType<typeof scenario>) {
  const params = state.requests.at(-1)!.searchParams;
  return [params.get('from'), params.get('to')];
}

describe('U17 analytics acceptance AE1–AE9', { timeout: 15_000 }, () => {
  it('AE8 opens rating by default and preserves tab in the address', async () => {
    scenario(); const view = mount('/groups/1');
    expect(await screen.findByRole('tab', { name: 'Рейтинг', selected: true })).toBeInTheDocument();
    expect(await screen.findByRole('table', { name: 'Рейтинг студентов' })).toBeInTheDocument();
    await view.user.click(screen.getByRole('tab', { name: 'Аналитика' }));
    await waitFor(() => expect(new URLSearchParams(view.router.state.location.search).get('tab')).toBe('analytics'));
    expect(await screen.findByRole('tab', { name: 'Аналитика', selected: true })).toBeInTheDocument();
    await view.user.click(screen.getByRole('tab', { name: 'Рейтинг' }));
    await waitFor(() => expect(new URLSearchParams(view.router.state.location.search).get('tab')).not.toBe('analytics'));
  });

  it('AE1 renders four daily averages and counts from the response', async () => {
    const state = scenario(); mount('/groups/1?tab=analytics&period=custom&from=2026-11-01&to=2026-11-04');
    const daily = await table('Средний АР группы');
    expect(dates(state)).toEqual(['2026-11-01', '2026-11-04']);
    expect(screen.getByRole('combobox', { name: 'Период' })).toHaveDisplayValue('Свои даты');
    expect(screen.getByRole('application')).toBeInTheDocument();
    const rows = daily.getAllByRole('row').slice(1);
    expect(rows).toHaveLength(4);
    for (let i = 0; i < 4; i++) {
      const cells = within(rows[i]!).getAllByRole('cell');
      expect(cells[0]).toHaveTextContent(`0${i + 1}.11.2026`);
      expect(cells[1]).toHaveTextContent(['1000,0', '980,0', '990,0', '990,0'][i]!);
      expect(cells[2]).toHaveTextContent(i < 2 ? '1' : '2');
    }
  });

  it('AE2 shows absent daily values as a dash and zero', async () => {
    const state = scenario(); state.response.days = [1, 2, 3, 4, 5, 6].map((day) => ({
      date: `2026-11-0${day}`, averageAr: day < 5 ? null : 1000, students: day < 5 ? 0 : 1,
    }));
    mount(); const daily = await table('Средний АР группы');
    for (const row of daily.getAllByRole('row').slice(1, 5)) {
      const cells = within(row).getAllByRole('cell');
      expect(cells[1]).toHaveTextContent('—'); expect(cells[2]).toHaveTextContent('0');
    }
  });

  it('AE2 empty group replaces daily and points tables but keeps budget', async () => {
    const state = scenario(); state.response.days = []; state.response.items = []; mount();
    expect(await screen.findByText('В группе нет студентов с подтверждённым членством')).toBeInTheDocument();
    expect(screen.queryByRole('table', { name: 'Средний АР группы' })).not.toBeInTheDocument();
    expect(screen.queryByRole('table', { name: 'Баллы за период' })).not.toBeInTheDocument();
    expect(screen.queryByRole('application')).not.toBeInTheDocument();
    expect(await screen.findByRole('table', { name: 'Расход бюджета по месяцам' })).toBeInTheDocument();
  });

  it('AE3 displays the server half-up rounded average', async () => {
    const state = scenario(); state.response.days = [{ date: '2026-11-03', averageAr: 1000.3, students: 4 }]; mount();
    expect((await table('Средний АР группы')).getByText('1000,3', { exact: true })).toBeInTheDocument();
  });

  it('AE4 sorts locally by BUA BOA AR and links all students including zero points', async () => {
    const state = scenario(); const view = mount(); await table('Баллы за период');
    for (const [currency, expected] of [['БУА', [a, b, e]], ['БОА', [b, a, e]], ['АР', [b, e, a]]] as const) {
      if (currency !== 'БУА') await view.user.click(screen.getByRole('radio', { name: currency }));
      const links = (await table('Баллы за период')).getAllByRole('link');
      expect(links.map((link) => link.textContent)).toEqual(expected.map((student) => student.fullName));
      links.forEach((link, i) => {
        expect(link).toHaveAttribute('href', `/users/${expected[i]!.id}`);
        const values = points.find((row) => row.student.id === expected[i]!.id)!;
        expect(within(link.closest('tr')!).getAllByRole('cell').slice(1).map((cell) => cell.textContent))
          .toEqual([String(values.bua), String(values.boa), String(values.ar)]);
      });
    }
    expect(state.requests).toHaveLength(1);
  });

  it('AE5 default 30 days, 90 days and latest semester use Moscow dates and update URL', async () => {
    const state = scenario(); const view = mount(); await table('Средний АР группы');
    expect(screen.getByRole('combobox', { name: 'Период' })).toHaveDisplayValue('30 дней');
    await waitFor(() => expect(dates(state)).toEqual(['2026-10-15', '2026-11-13']));
    await selectPeriod(view, '90 дней');
    await waitFor(() => expect(dates(state)).toEqual(['2026-08-16', '2026-11-13']));
    await selectPeriod(view, 'С начала семестра');
    await waitFor(() => expect(dates(state)).toEqual(['2026-09-01', '2026-11-13']));
    expect(new URLSearchParams(view.router.state.location.search).get('period')).toBe('semester');
  });

  it('AE5 semester choice is disabled with an explanation when no semester started', async () => {
    const state = scenario(); state.response.semesterStart = null; const view = mount(); await table('Средний АР группы');
    const picker = screen.getByRole('combobox', { name: 'Период' });
    if (!(picker instanceof HTMLSelectElement)) await view.user.click(picker);
    const option = screen.getByRole('option', { name: 'С начала семестра', hidden: picker instanceof HTMLSelectElement });
    if (option instanceof HTMLOptionElement) expect(option).toBeDisabled();
    else expect(option).toHaveAttribute('aria-disabled', 'true');
    await view.user.hover(option);
    expect(await screen.findByText('Семестр не начат')).toBeInTheDocument();
  });

  it('AE5 accepts the inclusive 366 day boundary', async () => {
    const state = scenario(); const view = mount(); await table('Средний АР группы');
    await custom(view, '13.11.2025', '13.11.2026');
    await waitFor(() => expect(dates(state)).toEqual(['2025-11-13', '2026-11-13']));
    expect(screen.queryByText(validationMessage)).not.toBeInTheDocument();
  });

  it.each([['12.11.2025', '13.11.2026'], ['10.11.2026', '14.11.2026'], ['13.11.2026', '01.11.2026']])(
    'AE5 rejects invalid custom dates %s–%s and retains data', async (from, to) => {
      const state = scenario(); const view = mount(); const before = (await table('Средний АР группы')).getAllByRole('row').map((row) => row.textContent);
      await custom(view, from, to);
      expect(await screen.findByText(validationMessage)).toBeInTheDocument();
      const count = state.requests.length;
      await view.user.tab();
      expect(state.requests).toHaveLength(count);
      expect(state.requests.some((url) => url.searchParams.get('from') === from.split('.').reverse().join('-')
        && url.searchParams.get('to') === to.split('.').reverse().join('-'))).toBe(false);
      expect((await table('Средний АР группы')).getAllByRole('row').map((row) => row.textContent)).toEqual(before);
    },
  );

  it.each([authUsers.teacher, authUsers.admin])('AE6 budget columns and empty months for $role', async (viewer) => {
    const state = scenario(viewer);
    if (viewer.role === 'ADMIN') state.budget.items = [
      { ...budgetRow, teacher: volkov, spent: 100, remaining: 900, awards: 1, bua: 100, boa: 0 }, budgetRow,
      ...state.budget.items.slice(1).map((row) => ({ ...row, teacher: null, remaining: null })),
    ];
    mount(); const budget = await table('Расход бюджета по месяцам');
    if (viewer.role === 'ADMIN') {
      expect(budget.getByRole('columnheader', { name: 'Преподаватель' })).toBeInTheDocument();
      const rows = budget.getAllByRole('row').slice(1);
      expect(rows[0]).toHaveTextContent('Волков'); expect(rows[1]).toHaveTextContent('Петров');
      expect(within(rows[2]!).getAllByRole('cell').map((cell) => cell.textContent)).toEqual(['октябрь 2026', '—', '1000', '0', '—', '0', '0', '0']);
    } else {
      expect(budget.queryByRole('columnheader', { name: 'Преподаватель' })).not.toBeInTheDocument();
      expect(budget.getAllByRole('row')).toHaveLength(7);
      expect(within(budget.getAllByRole('row')[1]!).getAllByRole('cell').map((cell) => cell.textContent)).toEqual(['ноябрь 2026', '1000', '950', '50', '3', '800', '150']);
    }
    expect(budget.queryByText('май 2026')).not.toBeInTheDocument();
  });

  it('AE7 administrator sees both tabs', async () => {
    scenario(authUsers.admin); mount(); await table('Баллы за период');
    expect(screen.getByRole('tab', { name: 'Рейтинг' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Аналитика', selected: true })).toBeInTheDocument();
  });
  it('AE7 student sees no access', async () => {
    const state = scenario(authUsers.student); mount();
    expect(await screen.findByRole('heading', { name: 'Нет доступа' })).toBeInTheDocument();
    expect(state.requests).toHaveLength(0);
  });
  it('AE7 inaccessible teacher group shows no access', async () => {
    const state = scenario(); state.forbidden = true; mount();
    expect(await screen.findByText('Нет доступа', { exact: true })).toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.getAllByText('Нет доступа', { exact: true })).toHaveLength(1);
  });

  it('AE9 changing period refreshes days and points and preserves budget', async () => {
    const state = scenario();
    state.response.days = Array.from({ length: 30 }, (_, i) => ({
      date: new Date(Date.UTC(2026, 9, 15 + i)).toISOString().slice(0, 10), averageAr: 990, students: 2,
    }));
    const view = mount();
    expect((await table('Средний АР группы')).getAllByRole('row')).toHaveLength(31);
    const budget = (await table('Расход бюджета по месяцам')).getAllByRole('row').map((row) => row.textContent);
    state.response = { ...state.response, days, items: points.map((row) => row.student.id === b.id ? { ...row, bua: 0, boa: 0 } : row) };
    await custom(view, '01.11.2026', '04.11.2026');
    await waitFor(() => expect(dates(state)).toEqual(['2026-11-01', '2026-11-04']));
    expect((await table('Средний АР группы')).getAllByRole('row')).toHaveLength(5);
    const borisov = (await table('Баллы за период')).getByRole('link', { name: b.fullName }).closest('tr')!;
    expect(within(borisov).getAllByRole('cell').slice(1).map((cell) => cell.textContent)).toEqual(['0', '0', '1000']);
    expect((await table('Расход бюджета по месяцам')).getAllByRole('row').map((row) => row.textContent)).toEqual(budget);
  });
});

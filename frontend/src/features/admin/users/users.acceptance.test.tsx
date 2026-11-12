import { act, cleanup, configure, getConfig, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../mocks/server';
import { choose } from '../../../test/authAcceptance';
import { createDirectoryScenario } from '../../../test/directoryAcceptance';
import { createTeachersScenario, disposeTeacherTests, mountTeachers, teacherFixtures } from '../../../test/teachersAcceptance';
import { setViewportWidth } from '../../../test/setup';

const createdAt = '2026-11-12T15:00:00Z';
const department = teacherFixtures.pending().department;
const group = teacherFixtures.groups().items[0]!;
type UserRow = {
  id: number; fullName: string; email: string; role: 'STUDENT' | 'TEACHER' | 'ADMIN';
  group: typeof group | null; department: typeof department | null;
  status: 'PENDING' | 'APPROVED' | 'MEMBERSHIP_PENDING' | 'MEMBERSHIP_ACTIVE' | null;
  createdAt: string; lastLoginAt: string | null; passwordSetAt: string | null;
};
const fixtures = (): UserRow[] => [
  { id: 1001, fullName: 'Смирнова Анна Олеговна', email: 'smirnova@student.campus.test', role: 'STUDENT', group, department: null, status: 'MEMBERSHIP_ACTIVE', createdAt, lastLoginAt: null, passwordSetAt: null },
  { id: 1004, fullName: 'Орлов Никита Сергеевич', email: 'orlov@student.campus.test', role: 'STUDENT', group, department: null, status: 'MEMBERSHIP_PENDING', createdAt, lastLoginAt: null, passwordSetAt: null },
  { id: 2001, fullName: 'Петров Павел Петрович', email: 'petrov@campus.test', role: 'TEACHER', group: null, department, status: 'APPROVED', createdAt, lastLoginAt: null, passwordSetAt: null },
  { id: 2002, fullName: 'Волков Максим Игоревич', email: teacherFixtures.pending().email, role: 'TEACHER', group: null, department, status: 'PENDING', createdAt, lastLoginAt: null, passwordSetAt: null },
  { id: 3001, fullName: 'Администратор Демо', email: 'admin@campus.test', role: 'ADMIN', group: null, department: null, status: null, createdAt, lastLoginAt: null, passwordSetAt: null },
];
function scenario(rows = fixtures()) {
  const teachers = createTeachersScenario();
  const requests: URLSearchParams[] = [];
  const writes: number[] = [];
  server.use(
    http.get('*/api/v1/admin/users', ({ request }) => {
      const params = new URL(request.url).searchParams;
      requests.push(params);
      const search = (params.get('search') ?? '').trim().toLocaleLowerCase();
      for (const row of rows) {
        if (row.role === 'TEACHER' && teachers.teachers.has(row.id)) row.status = teachers.teachers.get(row.id)!.teacherApproved ? 'APPROVED' : 'PENDING';
      }
      const filtered = rows.filter((row) => (!params.get('role') || row.role === params.get('role'))
        && (!params.get('status') || row.status === params.get('status'))
        && (search.length < 2 || `${row.fullName} ${row.email}`.toLocaleLowerCase().includes(search)));
      const nameOrder = (a: UserRow, b: UserRow) => a.fullName.localeCompare(b.fullName, 'ru') || a.id - b.id;
      filtered.sort((a, b) => {
        if (params.get('sort') === 'created') return Date.parse(b.createdAt) - Date.parse(a.createdAt) || nameOrder(a, b);
        if (params.get('sort') === 'lastLogin') {
          if (a.lastLoginAt === null && b.lastLoginAt !== null) return -1;
          if (b.lastLoginAt === null && a.lastLoginAt !== null) return 1;
          return (a.lastLoginAt && b.lastLoginAt ? Date.parse(a.lastLoginAt) - Date.parse(b.lastLoginAt) : 0) || nameOrder(a, b);
        }
        return nameOrder(a, b);
      });
      const offset = Number(params.get('cursor') ?? 0);
      return HttpResponse.json({ items: filtered.slice(offset, offset + 50), nextCursor: offset + 50 < filtered.length ? String(offset + 50) : null });
    }),
    http.post('*/api/v1/users/:id/temporary-password', ({ params }) => {
      const id = Number(params.id); writes.push(id);
      rows.find((row) => row.id === id)!.passwordSetAt = '2026-11-13T07:00:00Z';
      return HttpResponse.json({ password: 'Synthetic234' });
    }),
  );
  return { rows, teachers, requests, writes };
}
const originalTimeout = getConfig().asyncUtilTimeout;
beforeAll(() => { configure({ asyncUtilTimeout: 3000 }); server.listen(); });
afterEach(() => { cleanup(); disposeTeacherTests(); server.resetHandlers(); localStorage.clear(); });
afterAll(() => { server.close(); configure({ asyncUtilTimeout: originalTimeout }); });
async function table() { return within(await screen.findByRole('table', { name: 'Пользователи' })); }
async function row(name: string) { return (await table()).getByRole('row', { name: new RegExp(name) }); }
async function names() { return (await table()).getAllByRole('link').map((link) => link.textContent); }
async function search(view: ReturnType<typeof mountTeachers>, text: string) {
  const input = await screen.findByRole('textbox', { name: 'Поиск по ФИО или email' });
  await view.user.clear(input);
  if (text) await view.user.type(input, text);
}
async function select(view: ReturnType<typeof mountTeachers>, label: string, value: string) { await choose(view.user, label, value); }

describe('users-node AE1–AE10', { timeout: 20000 }, () => {
  it('AE2: all nine columns, statuses, affiliations and Moscow times', async () => {
    scenario(); mountTeachers('/admin/users');
    const users = await table();
    expect(users.getAllByRole('columnheader').map((cell) => cell.textContent)).toEqual(['ФИО', 'Email', 'Роль', 'Группа / кафедра', 'Статус', 'Создан', 'Последний вход', 'Пароль выставлен', 'Действия']);
    for (const [name, status] of [['Смирнова', 'Членство подтверждено'], ['Орлов', 'Ждёт подтверждения'], ['Петров', 'Допущен'], ['Волков', 'Заявка']]) {
      const item = within(await row(name!));
      expect(item.getByText(status!, { exact: true })).toBeInTheDocument();
      expect(item.getByText('12.11.2026 18:00', { exact: true })).toBeInTheDocument();
      expect(item.getAllByText('—', { exact: true })).toHaveLength(2);
      expect(item.getByRole('button', { name: 'Выставить временный пароль' })).toHaveTextContent('Временный пароль');
    }
    expect(within(await row('Смирнова')).getByText('ИВТ-21')).toBeInTheDocument();
    expect(within(await row('Орлов')).getByText('ИВТ-21')).toBeInTheDocument();
    expect(within(await row('Петров')).getByText('Кафедра программной инженерии · Факультет информационных технологий')).toBeInTheDocument();
    const admin = within(await row('Администратор Демо'));
    expect(admin.getAllByText('—', { exact: true })).toHaveLength(4);
    expect(admin.queryByRole('button')).not.toBeInTheDocument();
  });

  it('AE3: defaults, case insensitive email and name search, short search and combined filters', async () => {
    const state = scenario(), view = mountTeachers('/admin/users');
    expect(await screen.findByRole('combobox', { name: 'Роль' })).toHaveDisplayValue('Все');
    expect(screen.getByRole('combobox', { name: 'Статус' })).toHaveDisplayValue('Все');
    await search(view, 'PETROV@');
    await waitFor(async () => expect(await names()).toEqual(['Петров Павел Петрович']));
    await search(view, 'смир');
    await waitFor(async () => expect(await names()).toEqual(['Смирнова Анна Олеговна']));
    await search(view, 'с');
    await waitFor(async () => expect(await names()).toHaveLength(5));
    await search(view, ''); await select(view, 'Роль', 'Студенты'); await select(view, 'Статус', 'Заявка');
    expect(await screen.findByText('Никого не нашли')).toBeInTheDocument();
    await select(view, 'Статус', 'Ждёт подтверждения');
    await waitFor(async () => expect(await names()).toEqual(['Орлов Никита Сергеевич']));
    await select(view, 'Статус', 'Все'); await search(view, 'смир'); await select(view, 'Роль', 'Преподаватели');
    expect(await screen.findByText('Никого не нашли')).toBeInTheDocument();
    await waitFor(() => expect(state.requests.at(-1)?.get('role')).toBe('TEACHER'));
    expect(state.requests.at(-1)?.get('search')).toBe('смир');
  });

  it('AE4: global application count ignores search and button clears it and selects pending teachers', async () => {
    const state = scenario(), view = mountTeachers('/admin/users');
    await search(view, 'смир');
    await waitFor(async () => expect(await names()).toEqual(['Смирнова Анна Олеговна']));
    await view.user.click(await screen.findByRole('button', { name: 'Заявки преподавателей (1)' }));
    expect(screen.getByRole('textbox', { name: 'Поиск по ФИО или email' })).toHaveValue('');
    expect(screen.getByRole('combobox', { name: 'Роль' })).toHaveDisplayValue('Преподаватели');
    expect(screen.getByRole('combobox', { name: 'Статус' })).toHaveDisplayValue('Заявка');
    await waitFor(async () => expect(await names()).toEqual(['Волков Максим Игоревич']));
    expect(state.requests.at(-1)?.get('role')).toBe('TEACHER');
    expect(state.requests.at(-1)?.get('status')).toBe('PENDING');
    expect(state.requests.at(-1)?.get('search') ?? '').toBe('');
    expect(new URLSearchParams(view.router.state.location.search).get('status')).toBe('PENDING');
  });

  it('AE4: zero applications hides both button and badge', async () => {
    const state = scenario();
    state.teachers.teachers.set(2002, { ...state.teachers.teachers.get(2002)!, teacherApproved: true });
    mountTeachers('/admin/users'); await table();
    await waitFor(() => expect(state.teachers.requests.some((request) => request.path === '/api/v1/admin/teachers')).toBe(true));
    expect(screen.queryByRole('button', { name: /Заявки преподавателей/ })).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Заявки преподавателей:/)).not.toBeInTheDocument();
  });

  it('AE5: server order and Moscow login and creation times', async () => {
    const rows = fixtures();
    rows.find((item) => item.id === 1001)!.lastLoginAt = '2026-11-13T07:15:00Z';
    rows.find((item) => item.id === 2001)!.lastLoginAt = '2026-11-13T08:00:00Z';
    rows.push({ ...rows[0]!, id: 4001, fullName: 'Тест Новый', email: 'new@student.campus.test', status: 'MEMBERSHIP_PENDING', createdAt: '2026-11-13T08:30:00Z', lastLoginAt: null });
    const state = scenario(rows), view = mountTeachers('/admin/users');
    await select(view, 'Сортировка', 'Последний вход');
    await waitFor(async () => expect(await names()).toEqual(['Администратор Демо', 'Волков Максим Игоревич', 'Орлов Никита Сергеевич', 'Тест Новый', 'Смирнова Анна Олеговна', 'Петров Павел Петрович']));
    expect(within(await row('Смирнова')).getByText('13.11.2026 10:15')).toBeInTheDocument();
    expect(within(await row('Петров')).getByText('13.11.2026 11:00')).toBeInTheDocument();
    expect(state.requests.at(-1)?.get('sort')).toBe('lastLogin');
    await select(view, 'Сортировка', 'Создан');
    await waitFor(async () => expect(await names()).toEqual(['Тест Новый', 'Администратор Демо', 'Волков Максим Игоревич', 'Орлов Никита Сергеевич', 'Петров Павел Петрович', 'Смирнова Анна Олеговна']));
    expect(within(await row('Тест Новый')).getByText('13.11.2026 11:30')).toBeInTheDocument();
    expect(state.requests.at(-1)?.get('sort')).toBe('created');
  });

  it.each(['Сортировка', 'Роль', 'Статус', 'Поиск'] as const)('AE6: 50 → 100 → 120 and %s resets pagination', async (control) => {
    const rows = Array.from({ length: 120 }, (_, index) => ({ ...fixtures()[0]!, id: index + 4001, fullName: `Тест ${String(index + 1).padStart(3, '0')}`, email: `test${index + 1}@student.campus.test` }));
    const state = scenario(rows), view = mountTeachers('/admin/users?search=тест');
    await waitFor(async () => expect(await names()).toHaveLength(50));
    await view.user.click(screen.getByRole('button', { name: 'Загрузить ещё' }));
    await waitFor(async () => expect(await names()).toHaveLength(100));
    await view.user.click(screen.getByRole('button', { name: 'Загрузить ещё' }));
    await waitFor(async () => expect(await names()).toHaveLength(120));
    expect(screen.queryByRole('button', { name: 'Загрузить ещё' })).not.toBeInTheDocument();
    if (control === 'Поиск') await search(view, 'ТЕСТ');
    else await select(view, control, control === 'Сортировка' ? 'Создан' : control === 'Роль' ? 'Студенты' : 'Членство подтверждено');
    await waitFor(async () => expect(await names()).toHaveLength(50));
    expect(state.requests.at(-1)?.get('cursor')).toBeNull();
    expect(screen.getByRole('button', { name: 'Загрузить ещё' })).toBeInTheDocument();
  });

  it('AE7: teacher name links to admission card, other roles to profile', async () => {
    scenario(); mountTeachers('/admin/users');
    const users = await table();
    expect(users.getByRole('link', { name: 'Волков Максим Игоревич' })).toHaveAttribute('href', '/admin/users/teachers/2002');
    expect(users.getByRole('link', { name: 'Петров Павел Петрович' })).toHaveAttribute('href', '/admin/users/teachers/2001');
    expect(users.getByRole('link', { name: 'Смирнова Анна Олеговна' })).toHaveAttribute('href', '/users/1001');
    expect(users.getByRole('link', { name: 'Администратор Демо' })).toHaveAttribute('href', '/users/3001');
  });

  it('AE8: direct URL restores filters, search and sort and sends all to API', async () => {
    const state = scenario(); mountTeachers('/admin/users?role=TEACHER&status=APPROVED&search=пет&sort=created');
    expect(await screen.findByRole('combobox', { name: 'Роль' })).toHaveDisplayValue('Преподаватели');
    expect(screen.getByRole('combobox', { name: 'Статус' })).toHaveDisplayValue('Допущен');
    expect(screen.getByRole('textbox', { name: 'Поиск по ФИО или email' })).toHaveValue('пет');
    expect(screen.getByRole('combobox', { name: 'Сортировка' })).toHaveDisplayValue('Создан');
    await waitFor(async () => expect(await names()).toEqual(['Петров Павел Петрович']));
    expect(Object.fromEntries(state.requests.at(-1)!)).toMatchObject({ role: 'TEACHER', status: 'APPROVED', search: 'пет', sort: 'created' });
  });

  it('AE8: reopening URL after loading more restores first page only', async () => {
    const rows = Array.from({ length: 120 }, (_, index) => ({ ...fixtures()[2]!, id: index + 4001, fullName: `Тест ${String(index + 1).padStart(3, '0')}` }));
    scenario(rows); const view = mountTeachers('/admin/users?role=TEACHER&status=APPROVED&search=тест&sort=created');
    await waitFor(async () => expect(await names()).toHaveLength(50));
    await view.user.click(screen.getByRole('button', { name: 'Загрузить ещё' }));
    await waitFor(async () => expect(await names()).toHaveLength(100));
    const url = view.router.state.location.pathname + view.router.state.location.search;
    view.unmount(); disposeTeacherTests();
    mountTeachers(url);
    await waitFor(async () => expect(await names()).toHaveLength(50));
    expect(screen.getByRole('combobox', { name: 'Роль' })).toHaveDisplayValue('Преподаватели');
    expect(screen.getByRole('combobox', { name: 'Статус' })).toHaveDisplayValue('Допущен');
    expect(screen.getByRole('combobox', { name: 'Сортировка' })).toHaveDisplayValue('Создан');
    expect(screen.getByRole('textbox', { name: 'Поиск по ФИО или email' })).toHaveValue('тест');
  });

  it.each(['Смирнова', 'Петров'])('AE2/AE10: %s temporary password requires confirmation and updates audit time', async (name) => {
    const state = scenario(), view = mountTeachers('/admin/users');
    await view.user.click(within(await row(name)).getByRole('button', { name: 'Выставить временный пароль' }));
    const dialog = within(await screen.findByRole('dialog'));
    expect(state.writes).toEqual([]);
    await view.user.click(dialog.getByRole('button', { name: 'Выставить' }));
    expect(await dialog.findByText('Synthetic234')).toBeInTheDocument();
    expect(state.writes).toEqual([name === 'Смирнова' ? 1001 : 2001]);
    await view.user.click(dialog.getByRole('button', { name: 'Закрыть' }));
    await waitFor(async () => expect(within(await row(name)).getByText('13.11.2026 10:00')).toBeInTheDocument());
  });

  it.each([360, 1280])('AE1: menu badge belongs to Users and directories contain only reference data at %i px', async (width) => {
    setViewportWidth(width); createDirectoryScenario(); scenario(); const view = mountTeachers('/admin/directory');
    const navigation = within(await screen.findByRole('navigation', { name: width === 360 ? 'Нижнее меню' : 'Боковое меню' }));
    expect(navigation.getAllByRole('link').map((link) => link.getAttribute('href'))).toEqual(['/admin/directory', '/admin/users', '/admin/import', '/admin/semesters', '/admin/settings', '/admin/budgets']);
    expect(await within(navigation.getByRole('link', { name: /Пользователи/ })).findByLabelText('Заявки преподавателей: 1')).toHaveTextContent('1');
    expect(within(navigation.getByRole('link', { name: 'Справочники' })).queryByLabelText(/Заявки преподавателей/)).not.toBeInTheDocument();
    for (const label of ['Факультеты', 'Учебные группы', 'Кафедры']) expect(await screen.findByRole('tab', { name: label })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Преподаватели' })).not.toBeInTheDocument();
    await view.user.click(screen.getByRole('tab', { name: 'Учебные группы' }));
    expect((await screen.findAllByRole('link', { name: 'Аналитика' })).length).toBeGreaterThan(0);
  });

  it.each(['/admin/users/teachers/2002', '/admin/directory/teachers/2002'])('AE7: %s opens unchanged card with profile link', async (path) => {
    scenario(); const view = mountTeachers(path);
    expect(await screen.findByRole('heading', { name: 'Волков Максим Игоревич' })).toBeInTheDocument();
    expect(view.router.state.location.pathname).toBe('/admin/users/teachers/2002');
    expect(screen.getByRole('button', { name: 'Допустить' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Выдать доступ' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Профиль' })).toHaveAttribute('href', '/users/2002');
  });

  it('AE7: old teacher list redirects to pending teachers filter', async () => {
    scenario(); const view = mountTeachers('/admin/directory/teachers');
    await waitFor(() => expect(view.router.state.location.pathname).toBe('/admin/users'));
    expect(new URLSearchParams(view.router.state.location.search).get('role')).toBe('TEACHER');
    expect(new URLSearchParams(view.router.state.location.search).get('status')).toBe('PENDING');
    await waitFor(async () => expect(await names()).toEqual(['Волков Максим Игоревич']));
  });

  it('AE4: approval refreshes global application count on returning to Users', async () => {
    scenario(); const view = mountTeachers('/admin/users/teachers/2002');
    await view.user.click(await screen.findByRole('button', { name: 'Допустить' }));
    await view.user.click(within(await screen.findByRole('dialog', { name: 'Допустить преподавателя' })).getByRole('button', { name: 'Допустить' }));
    expect(await screen.findByText('Преподаватель допущен', { exact: true })).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByLabelText('Заявки преподавателей: 1')).not.toBeInTheDocument());
    await act(async () => { await view.router.navigate('/admin/users'); });
    await table(); expect(screen.queryByRole('button', { name: /Заявки преподавателей/ })).not.toBeInTheDocument();
  });

  it('AE10: teacher allowlist stays in Import and unregistered record can be deleted', async () => {
    scenario(); let present = true; const deleted: string[] = [];
    server.use(http.get('*/api/v1/admin/teacher-allowlist', () => HttpResponse.json({ items: present ? [{ email: 'petrov@campus.test', department, registered: false }] : [] })),
      http.delete('*/api/v1/admin/teacher-allowlist', ({ request }) => { deleted.push(new URL(request.url).searchParams.get('email')!); present = false; return new HttpResponse(null, { status: 204 }); }));
    const view = mountTeachers('/admin/import');
    await select(view, 'Вид импорта', 'Список преподавателей');
    const list = within(await screen.findByRole('table', { name: 'Список преподавателей' }));
    expect(list.getByText('Кафедра программной инженерии')).toBeInTheDocument();
    expect(list.getByText('нет', { exact: true })).toBeInTheDocument();
    await view.user.click(list.getByRole('button', { name: 'Удалить petrov@campus.test' }));
    await view.user.click(within(await screen.findByRole('dialog', { name: 'Удалить из списка преподавателей' })).getByRole('button', { name: 'Удалить' }));
    await waitFor(() => expect(screen.queryByRole('table', { name: 'Список преподавателей' })).not.toBeInTheDocument());
    expect(deleted).toEqual(['petrov@campus.test']);
    expect(await screen.findByText('Список преподавателей пуст', { exact: true })).toBeInTheDocument();
  });
});

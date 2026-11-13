import { cleanup, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server } from '../../mocks/server';
import { setViewportWidth } from '../../test/setup';
import { renderApp } from '../../test/render';

beforeAll(() => server.listen());
afterEach(() => { cleanup(); server.resetHandlers(); });
afterAll(() => server.close());
it.each(['semesters', 'settings', 'budgets', 'users', 'import', 'submissions/7001'])('защищает /admin/%s до чтения ресурса', async (path) => {
  renderApp(`/admin/${path}`, { userId: '1001', role: 'STUDENT' });
  expect(await screen.findByRole('heading', { name: 'Нет доступа' })).toBeInTheDocument();
});
it('показывает фиксированное N начатого семестра', async () => {
  server.use(http.get('*/api/v1/admin/semesters', () => HttpResponse.json({ items: [
    { id: 1, title: 'Осень 2026', startsAt: '2026-08-31T21:00:00Z', started: true, accrual: 200 },
  ] })));
  renderApp('/admin/semesters', { userId: '9001', role: 'ADMIN' });
  expect(await screen.findByText('Осень 2026')).toBeInTheDocument();
  expect(screen.getByText('200', { exact: true })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Начать сейчас' })).not.toBeInTheDocument();
});
it('действия семестра собраны в меню без названия в подписях', async () => {
  server.use(http.get('*/api/v1/admin/semesters', () => HttpResponse.json({ items: [
    { id: 1, title: 'Осень 2026', startsAt: '2026-08-31T21:00:00Z', started: true, accrual: 200 },
    { id: 2, title: 'Весна 2027', startsAt: '2027-02-01T21:00:00Z', started: false, accrual: null },
    { id: 3, title: 'Осень 2027', startsAt: '2027-08-31T21:00:00Z', started: false, accrual: null },
  ] })));
  const user = userEvent.setup();
  renderApp('/admin/semesters', { userId: '9001', role: 'ADMIN' });
  expect(await screen.findByText('Весна 2027')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Действия: Осень 2026' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /^Изменить|^Удалить/ })).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Действия: Весна 2027' }));
  expect((await screen.findAllByRole('menuitem')).map((item) => item.textContent)).toEqual(['Изменить', 'Удалить', 'Начать сейчас']);
  await user.keyboard('{Escape}');
  await user.click(screen.getByRole('button', { name: 'Действия: Осень 2027' }));
  await waitFor(() => expect(screen.getAllByRole('menuitem').map((item) => item.textContent)).toEqual(['Изменить', 'Удалить']));
  await user.click(screen.getByRole('menuitem', { name: 'Удалить' }));
  expect(await screen.findByText('Удалить семестр «Осень 2027»?')).toBeInTheDocument();
});
const settings = { penaltyStep: 4, penaltyIntervalSeconds: 60, minPenaltyIntervalSeconds: 2, semesterAccrual: 200, monthlyBudgetLimit: 1000 };
it('отмена снижения лимита сохраняет введённые поля и не пишет настройки', async () => {
  let writes = 0;
  server.use(http.get('*/api/v1/admin/settings', () => HttpResponse.json(settings)),
    http.get('*/api/v1/admin/budgets/impact', () => HttpResponse.json({ count: 2 })),
    http.put('*/api/v1/admin/settings', async ({ request }) => { writes++; return HttpResponse.json({ ...settings, ...await request.json() as object }); }));
  const user = userEvent.setup(); renderApp('/admin/settings', { userId: '9001', role: 'ADMIN' });
  const field = await screen.findByLabelText('Лимит бюджета в месяц');
  await user.clear(field); await user.type(field, '500'); await user.click(screen.getByRole('button', { name: 'Сохранить' }));
  const dialog = within(await screen.findByRole('dialog', { name: 'Снизить лимит бюджета?' }));
  expect(dialog.getByText(/уже больше нового лимита: 2/)).toBeInTheDocument();
  await user.click(dialog.getByRole('button', { name: 'Отмена' }));
  expect(writes).toBe(0); expect(field).toHaveValue(500);
  await user.click(screen.getByRole('button', { name: 'Сохранить' }));
  await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Сохранить' }));
  expect(await screen.findByText('Настройки сохранены')).toBeInTheDocument(); expect(writes).toBe(1);
});
it('не отправляет дробные или выходящие за границы настройки', async () => {
  let writes = 0;
  server.use(http.get('*/api/v1/admin/settings', () => HttpResponse.json(settings)), http.put('*/api/v1/admin/settings', () => { writes++; return HttpResponse.json(settings); }));
  const user = userEvent.setup(); renderApp('/admin/settings', { userId: '9001', role: 'ADMIN' });
  const field = await screen.findByLabelText('Шаг штрафа, АР'); await user.clear(field); await user.type(field, '2.5');
  await user.click(screen.getByRole('button', { name: 'Сохранить' }));
  expect(screen.getByText('Допустимо от 1 до 1000')).toBeInTheDocument(); expect(writes).toBe(0);
});
it('показывает временный пароль только после подтверждения и удаляет при закрытии', async () => {
  let writes = 0;
  server.use(http.get('*/api/v1/admin/users', () => HttpResponse.json({ items: [{ id: 1001, lastName: 'Студент', firstName: 'Тест', middleName: null, fullName: 'Студент Тест', email: 'student@campus.test', role: 'STUDENT', group: null, department: null, passwordSetAt: null }], nextCursor: null })),
    http.post('*/api/v1/users/1001/temporary-password', () => { writes++; return HttpResponse.json({ password: 'Synthetic234' }); }));
  const user = userEvent.setup(); renderApp('/admin/users', { userId: '9001', role: 'ADMIN' });
  const trigger = await screen.findByRole('button', { name: 'Выставить временный пароль' });
  expect(trigger).toHaveTextContent(/^Временный пароль$/); expect(trigger).toHaveClass('cr-nowrap');
  await user.click(trigger);
  expect(writes).toBe(0); const dialog = within(await screen.findByRole('dialog'));
  await user.click(dialog.getByRole('button', { name: 'Выставить' }));
  expect(await dialog.findByText('Synthetic234')).toBeInTheDocument(); expect(writes).toBe(1);
  await user.click(dialog.getByRole('button', { name: 'Закрыть' }));
  await waitFor(() => expect(screen.queryByText('Synthetic234')).not.toBeInTheDocument());
});
it('показывает отчёт импорта с номером и причиной отклонённой строки', async () => {
  server.use(http.get('*/api/v1/admin/teacher-allowlist', () => HttpResponse.json({ items: [] })), http.post('*/api/v1/admin/import/GROUPS', () => HttpResponse.json({ created: 1, skipped: 1, rejected: [{ row: 4, key: 'ИВТ-21', reason: 'код группы занят' }] })));
  const user = userEvent.setup(); renderApp('/admin/import', { userId: '9001', role: 'ADMIN' });
  await screen.findByLabelText('Файл импорта');
  await user.upload(document.querySelector<HTMLInputElement>('input[type="file"]')!, new File(['faculty_code,group_code,group_name,admission_year'], 'groups.csv', { type: 'text/csv' }));
  await user.click(screen.getByRole('button', { name: 'Импортировать' }));
  expect(await screen.findByText('Создано: 1 · Пропущено (уже есть): 1 · Отклонено: 1')).toBeInTheDocument();
  expect(screen.getByText('код группы занят')).toBeInTheDocument();
});

const nameUser = { id: 1001, lastName: 'Иванова', firstName: 'Мария', middleName: null, fullName: 'Иванова Мария',
  email: 'u24@campus.test', role: 'STUDENT', group: null, department: null, status: 'MEMBERSHIP_PENDING',
  createdAt: '2026-11-13T09:00:00Z', lastLoginAt: null, passwordSetAt: null };
function nameScenario() {
  const requests: URLSearchParams[] = [];
  server.use(http.get('*/api/v1/admin/users', ({ request }) => {
    requests.push(new URL(request.url).searchParams);
    return HttpResponse.json({ items: [nameUser], nextCursor: null });
  }), http.get('*/api/v1/admin/teachers', () => HttpResponse.json({ items: [] })));
  return requests;
}

it('Covers AE21: 1280 px name columns sort and surname links to profile; empty middle is dash', async () => {
  setViewportWidth(1280);
  const requests = nameScenario();
  const user = userEvent.setup();
  renderApp('/admin/users', { userId: '9001', role: 'ADMIN' });
  const table = within(await screen.findByRole('table', { name: 'Пользователи' }));
  const headers = table.getAllByRole('columnheader');
  expect(headers.map((cell) => cell.textContent).filter((text) => text !== 'ФИО')).toEqual(['Фамилия', 'Имя', 'Отчество', 'Email', 'Роль', 'Группа / кафедра', 'Статус', 'Создан', 'Последний вход', 'Действия']);
  expect(table.getByRole('link', { name: 'Иванова' })).toHaveAttribute('href', '/users/1001');
  const row = within(table.getByRole('row', { name: /Иванова/ }));
  expect(row.getAllByRole('cell')[2]).toHaveTextContent(/^—$/);
  for (const [label, key] of [['Фамилия', 'lastName'], ['Имя', 'firstName'], ['Отчество', 'middleName']] as const) {
    const header = within(await screen.findByRole('table', { name: 'Пользователи' })).getByRole('columnheader', { name: label });
    const previous = requests.length;
    await user.click(within(header).getByRole('button', { name: label }));
    await waitFor(() => expect(requests.length).toBeGreaterThan(previous));
    expect(requests.at(-1)?.get('sort')).toBe(key);
    await waitFor(() => expect(screen.getByRole('columnheader', { name: label })).toHaveAttribute('aria-sort', key === 'lastName' ? 'descending' : 'ascending'));
  }
});

it('Covers AE22: 360 px card keeps full name and sorting offers three name parts', async () => {
  setViewportWidth(360);
  const requests = nameScenario();
  const user = userEvent.setup();
  renderApp('/admin/users', { userId: '9001', role: 'ADMIN' });
  const link = await screen.findByRole('link', { name: 'Иванова Мария' });
  expect(link).toHaveAttribute('href', '/users/1001');
  const sort = screen.getByRole('combobox', { name: 'Сортировка' });
  const options = within(sort);
  for (const label of ['Фамилия', 'Имя', 'Отчество']) expect(options.getByRole('option', { name: label })).toBeInTheDocument();
  expect(options.queryByRole('option', { name: 'ФИО' })).not.toBeInTheDocument();
  await user.selectOptions(sort, 'firstName');
  await waitFor(() => expect(requests.at(-1)?.get('sort')).toBe('firstName'));
});

it('Covers AE23: legacy URL sort=name opens surname sorting', async () => {
  const requests = nameScenario();
  renderApp('/admin/users?sort=name', { userId: '9001', role: 'ADMIN' });
  await screen.findByRole('table', { name: 'Пользователи' });
  expect(screen.getByRole('combobox', { name: 'Сортировка' })).toHaveDisplayValue('Фамилия');
  expect(requests.at(-1)?.get('sort')).toBe('lastName');
});

it('Covers AE12, AE13: import help shows exact six-column header', async () => {
  const user = userEvent.setup();
  renderApp('/admin/import', { userId: '9001', role: 'ADMIN' });
  await user.selectOptions(await screen.findByRole('combobox', { name: 'Вид импорта' }), 'STUDENTS');
  expect(screen.getByText('Заголовок: email,last_name,first_name,middle_name,faculty_code,group_code', { exact: true })).toBeInTheDocument();
});

import { act, cleanup, configure, getConfig, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../mocks/server';
import { authUsers, createDirectoryScenario, directoryDeferred, directoryFixtures, directoryJson, disposeDirectoryTests, mountDirectory, problem } from '../../../test/directoryAcceptance';
import type { DirectoryKind } from '../../../test/directoryAcceptance';

const originalTimeout = getConfig().asyncUtilTimeout;
beforeAll(() => { configure({ asyncUtilTimeout: 3_000 }); server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => {
  cleanup(); disposeDirectoryTests(); server.resetHandlers(); localStorage.clear();
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/';
  document.cookie = 'campus-mock-user=; Max-Age=0; Path=/';
});
afterAll(() => { server.close(); configure({ asyncUtilTimeout: originalTimeout }); });
const kinds = [
  { kind: 'faculties', tab: 'Факультеты', noun: 'факультет', name: 'Факультет информационных технологий', id: 1 },
  { kind: 'groups', tab: 'Учебные группы', noun: 'группу', name: 'ИВТ-21', id: 11 },
  { kind: 'departments', tab: 'Кафедры', noun: 'кафедру', name: 'Кафедра программной инженерии', id: 101 },
] as const;
type View = ReturnType<typeof mountDirectory>;
type Scenario = ReturnType<typeof createDirectoryScenario>;
function mutations(state: Scenario) { return state.requests.filter((request) => request.method !== 'GET'); }
async function tab(view: View, label: string) { await view.user.click(await screen.findByRole('tab', { name: label })); }
async function openAdd(view: View, noun = 'факультет') {
  await view.user.click(await screen.findByRole('button', { name: `Добавить ${noun}` }));
  return within(await screen.findByRole('dialog', { name: `Добавить ${noun}` }));
}
async function fill(view: View, dialog: ReturnType<typeof within>, kind: DirectoryKind, code = 'NEW', name = 'Новая запись') {
  await view.user.clear(dialog.getByRole('textbox', { name: 'Код' }));
  await view.user.paste(code);
  await view.user.clear(dialog.getByRole('textbox', { name: 'Название' }));
  await view.user.paste(name);
  if (kind !== 'faculties') await view.user.selectOptions(dialog.getByRole('combobox', { name: 'Факультет' }), '1');
  if (kind === 'groups') {
    const input = dialog.getByRole('spinbutton', { name: 'Год набора' });
    await view.user.clear(input); await view.user.type(input, '2026');
  }
}
async function save(view: View, dialog: ReturnType<typeof within>) { await view.user.click(dialog.getByRole('button', { name: 'Сохранить' })); }
async function edit(view: View, name: string, noun: string) {
  await view.user.click(await screen.findByRole('button', { name: `Редактировать ${name}` }));
  return within(await screen.findByRole('dialog', { name: `Редактировать ${noun}` }));
}
async function remove(view: View, name: string, noun: string) {
  await view.user.click(await screen.findByRole('button', { name: `Удалить ${name}` }));
  return within(await screen.findByRole('dialog', { name: `Удалить ${noun}` }));
}

describe('U13 directory access and lists — R3/R24', { timeout: 12_000 }, () => {
  it('admin sees all three directory tabs and complete faculty data', async () => {
    const state = createDirectoryScenario(); mountDirectory();
    expect(await screen.findByRole('heading', { name: 'Справочники' })).toBeInTheDocument();
    const table = await screen.findByRole('table', { name: 'Факультеты' });
    expect(within(table).getByText('ФИТ', { exact: true })).toBeInTheDocument();
    expect(within(table).getByText(kinds[0].name, { exact: true })).toBeInTheDocument();
    for (const value of kinds) expect(screen.getByRole('tab', { name: value.tab })).toBeInTheDocument();
    expect(state.requests.some((request) => request.path.endsWith('/faculties'))).toBe(true);
  });
  it.each([authUsers.student, authUsers.teacher])('role $role cannot read admin APIs', async (viewer) => {
    const state = createDirectoryScenario(viewer); mountDirectory();
    expect(await screen.findByText('Нет доступа', { exact: true })).toBeInTheDocument();
    expect(state.requests).toHaveLength(0);
  });
  it.each([null, { ...authUsers.admin, mustChangePassword: true }])('session guard runs before directory calls %#', async (viewer) => {
    const state = createDirectoryScenario(viewer), view = mountDirectory();
    await waitFor(() => expect(view.router.state.location.pathname).toBe(viewer ? '/password/change' : '/login'));
    expect(state.requests).toHaveLength(0);
  });
  it.each(kinds)('reads $tab in server order using full admin DTO', async ({ kind, tab: label }) => {
    const state = createDirectoryScenario();
    const original = state.lists[kind][0]!;
    state.lists[kind] = [{ ...original, id: 202, code: 'ZZZ', name: 'Я — серверная первая запись' }, { ...original, id: 203, code: 'AAA', name: 'А — серверная вторая запись' }];
    const view = mountDirectory(); await tab(view, label);
    const table = await screen.findByRole('table', { name: label });
    expect(within(table).getByText('Я — серверная первая запись').compareDocumentPosition(within(table).getByText('А — серверная вторая запись')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    if (kind === 'groups') expect(within(table).getAllByText('2025')).toHaveLength(2);
  });
  it('shows loading then an empty list without converting it into error', async () => {
    createDirectoryScenario(); const gate = directoryDeferred();
    server.use(http.get('*/api/v1/admin/directory/faculties', async () => { await gate.promise; return directoryJson({ items: [] }); }));
    mountDirectory(); expect(await screen.findByRole('status')).toBeInTheDocument();
    await act(async () => { gate.resolve(); });
    expect(await screen.findByText(/факультет.*пока нет|факультет.*не найден|нет факультет/i)).toBeInTheDocument();
  });
  it.each(['500', 'network', 'malformed'] as const)('list %s error has retry and does not become empty', async (kind) => {
    createDirectoryScenario(); let fail = true;
    server.use(http.get('*/api/v1/admin/directory/faculties', () => !fail ? directoryJson(directoryFixtures.faculties()) : kind === 'network' ? HttpResponse.error() : kind === 'malformed' ? directoryJson({ items: [{ id: 0, code: 'X', name: 'Неверные данные' }] }) : problem('INTERNAL_ERROR', 500)));
    const view = mountDirectory(), retry = await screen.findByRole('button', { name: /Повторить/ });
    fail = false; await view.user.click(retry);
    expect(await screen.findByRole('table', { name: 'Факультеты' })).toBeInTheDocument();
    expect(screen.queryByText('Неверные данные')).not.toBeInTheDocument();
  });
});

describe('U13 directory writes — R24/KTD17', { timeout: 15_000 }, () => {
  it.each([
    { opener: 'Добавить факультет', dialog: 'Добавить факультет', dismiss: 'escape' },
    { opener: `Редактировать ${kinds[0].name}`, dialog: 'Редактировать факультет', dismiss: 'cancel' },
  ] as const)('returns keyboard focus to $opener after dismissing its dialog', async ({ opener: label, dialog: title, dismiss }) => {
    const state = createDirectoryScenario(), view = mountDirectory();
    const opener = await screen.findByRole('button', { name: label });
    await view.user.click(opener);
    const dialog = await screen.findByRole('dialog', { name: title });
    await waitFor(() => expect(within(dialog).getByRole('textbox', { name: 'Код' })).toHaveFocus());
    if (dismiss === 'escape') await view.user.keyboard('{Escape}');
    else await view.user.click(within(dialog).getByRole('button', { name: 'Отмена' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(opener).toHaveFocus());
    expect(mutations(state)).toHaveLength(0);
  });
  it.each(kinds)('creates $kind with typed HTTP fields and CSRF', async ({ kind, tab: label, noun }) => {
    const state = createDirectoryScenario(), view = mountDirectory(); await tab(view, label);
    const dialog = await openAdd(view, noun); await fill(view, dialog, kind, '  NEW  ', '  Новая запись  '); await save(view, dialog);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const request = mutations(state).find((item) => item.method === 'POST')!;
    expect(request.path).toBe(`/api/v1/admin/directory/${kind}`);
    expect(request.csrf).toBe(state.auth.token);
    expect(request.body).toEqual({ code: 'NEW', name: 'Новая запись', ...(kind === 'faculties' ? {} : { facultyId: 1 }), ...(kind === 'groups' ? { admissionYear: 2026 } : {}) });
    expect(await screen.findByText('Новая запись', { exact: true })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent(/создан|сохран|добавлен/i);
    expect(state.requests.filter((item) => item.method === 'GET' && item.path.endsWith(`/${kind}`)).length).toBeGreaterThan(1);
  });
  it.each(kinds)('updates $kind through PUT to the selected id', async ({ kind, tab: label, noun, name, id }) => {
    const state = createDirectoryScenario(), view = mountDirectory(); await tab(view, label);
    const dialog = await edit(view, name, noun);
    expect(dialog.getByRole('textbox', { name: 'Название' })).toHaveValue(name);
    await fill(view, dialog, kind, 'EDIT', 'Обновлённая запись'); await save(view, dialog);
    expect(await screen.findByText('Обновлённая запись', { exact: true })).toBeInTheDocument();
    expect(mutations(state).filter((item) => item.method === 'PUT').map((item) => item.path)).toEqual([`/api/v1/admin/directory/${kind}/${id}`]);
  });
  it('cancel and reopening another row do not leak unsaved fields or errors', async () => {
    const state = createDirectoryScenario(); state.lists.faculties.push({ id: 2, code: 'МАТ', name: 'Математический факультет' });
    const view = mountDirectory(), first = await edit(view, kinds[0].name, 'факультет');
    await view.user.clear(first.getByRole('textbox', { name: 'Название' }));
    await view.user.type(first.getByRole('textbox', { name: 'Название' }), 'Не сохранять');
    await view.user.click(first.getByRole('button', { name: 'Отмена' }));
    const second = await edit(view, 'Математический факультет', 'факультет');
    expect(second.getByRole('textbox', { name: 'Название' })).toHaveValue('Математический факультет');
    expect(second.getByRole('textbox', { name: 'Код' })).toHaveValue('МАТ');
    expect(mutations(state)).toHaveLength(0);
  });
  it.each([{ code: '   ', name: 'Название' }, { code: 'X', name: '   ' }, { code: 'X'.repeat(65), name: 'Название' }, { code: 'X', name: 'Я'.repeat(256) }])('rejects invalid text before HTTP %#', async ({ code, name }) => {
    const state = createDirectoryScenario(), view = mountDirectory(), dialog = await openAdd(view);
    await fill(view, dialog, 'faculties', code, name); await save(view, dialog);
    expect(screen.getByRole('dialog')).toBeInTheDocument(); expect(mutations(state)).toHaveLength(0);
    const invalid = [dialog.getByRole('textbox', { name: 'Код' }), dialog.getByRole('textbox', { name: 'Название' })].filter((input) => input.getAttribute('aria-invalid') === 'true');
    expect(invalid.length).toBeGreaterThan(0);
  });
  it('accepts code/name limits measured as Unicode code points', async () => {
    const state = createDirectoryScenario(), view = mountDirectory(), dialog = await openAdd(view);
    await fill(view, dialog, 'faculties', '🧪'.repeat(64), '🎓'.repeat(255)); await save(view, dialog);
    await waitFor(() => expect(mutations(state)).toHaveLength(1));
    expect(mutations(state)[0]!.body).toEqual({ code: '🧪'.repeat(64), name: '🎓'.repeat(255) });
  });
  it.each(['0', '10000', '2026.5'])('rejects invalid admission year %s before HTTP', async (year) => {
    const state = createDirectoryScenario(), view = mountDirectory(); await tab(view, 'Учебные группы');
    const dialog = await openAdd(view, 'группу'); await fill(view, dialog, 'groups');
    const input = dialog.getByRole('spinbutton', { name: 'Год набора' });
    await view.user.clear(input); await view.user.type(input, year); await save(view, dialog);
    expect(input).toHaveAttribute('aria-invalid', 'true'); expect(mutations(state)).toHaveLength(0);
  });
  it('requires a faculty for group creation when the faculty directory is empty', async () => {
    const state = createDirectoryScenario(); state.lists.faculties = [];
    const view = mountDirectory(); await tab(view, 'Учебные группы');
    const dialog = await openAdd(view, 'группу');
    expect(dialog.getByText(/сначала.*факультет|создайте.*факультет/i)).toBeInTheDocument();
    expect(dialog.getByRole('button', { name: 'Сохранить' })).toBeDisabled();
    expect(mutations(state)).toHaveLength(0);
  });
  it('faculty lookup failure in a group form is retryable instead of an empty selector', async () => {
    createDirectoryScenario(); let fail = true;
    server.use(http.get('*/api/v1/admin/directory/faculties', () => fail ? problem('INTERNAL_ERROR', 500) : directoryJson(directoryFixtures.faculties())));
    const view = mountDirectory(); await tab(view, 'Учебные группы');
    const dialog = await openAdd(view, 'группу');
    const retry = await dialog.findByRole('button', { name: /Повторить/ });
    fail = false; await view.user.click(retry);
    const selector = await dialog.findByRole('combobox', { name: 'Факультет' });
    await waitFor(() => expect(within(selector).getByRole('option', { name: new RegExp(kinds[0].name) })).toBeInTheDocument());
    expect(dialog.queryByText(/сначала.*факультет|создайте.*факультет/i)).not.toBeInTheDocument();
  });
  it('duplicate code keeps fields and marks the code input', async () => {
    const state = createDirectoryScenario(), view = mountDirectory(), dialog = await openAdd(view);
    await fill(view, dialog, 'faculties', 'ФИТ', 'Другое название'); await save(view, dialog);
    await waitFor(() => expect(dialog.getByRole('textbox', { name: 'Код' })).toHaveAttribute('aria-invalid', 'true'));
    expect(dialog.getByRole('textbox', { name: 'Код' })).toHaveAccessibleDescription(/Код уже используется/i);
    expect(dialog.getByRole('textbox', { name: 'Код' })).toHaveValue('ФИТ');
    expect(dialog.getByRole('textbox', { name: 'Название' })).toHaveValue('Другое название');
    expect(mutations(state)).toHaveLength(1);
  });
  it('server fieldErrors are associated with fields and unknown fields keep a general failure', async () => {
    createDirectoryScenario();
    server.use(http.post('*/api/v1/admin/directory/faculties', () => problem('VALIDATION_FAILED', 400, { fieldErrors: [{ field: 'name', code: 'INVALID', message: 'Название отклонено' }, { field: 'unknown', code: 'INVALID', message: 'Внутреннее поле' }] })));
    const view = mountDirectory(), dialog = await openAdd(view); await fill(view, dialog, 'faculties'); await save(view, dialog);
    expect(await dialog.findByText('Название отклонено', { exact: true })).toBeInTheDocument();
    expect(dialog.getByRole('textbox', { name: 'Название' })).toHaveAttribute('aria-invalid', 'true');
    expect(dialog.getByRole('alert')).toBeInTheDocument();
  });
  it.each(['403', '500', 'network', 'malformed'] as const)('write %s error preserves form and does not announce success', async (kind) => {
    const state = createDirectoryScenario();
    server.use(http.post('*/api/v1/admin/directory/faculties', async ({ request }) => {
      await state.record(request);
      return kind === 'network' ? HttpResponse.error() : kind === 'malformed' ? directoryJson({ id: 0, code: 'NEW', name: 'Новая запись' }, 201) : problem(kind === '403' ? 'FORBIDDEN' : 'INTERNAL_ERROR', kind === '403' ? 403 : 500);
    }));
    const view = mountDirectory(), dialog = await openAdd(view); await fill(view, dialog, 'faculties'); await save(view, dialog);
    expect(await dialog.findByRole('alert')).toBeInTheDocument();
    expect(dialog.getByRole('textbox', { name: 'Название' })).toHaveValue('Новая запись');
    expect(mutations(state)).toHaveLength(1);
    expect(screen.queryByRole('status')?.textContent ?? '').not.toMatch(/создан|сохран|добавлен/i);
    if (kind === 'malformed') expect(dialog.getByText(/обнов.*список/i)).toBeInTheDocument();
  });
  it('CSRF refresh updates protection without automatically replaying the mutation', async () => {
    const state = createDirectoryScenario(); let fail = true;
    server.use(http.post('*/api/v1/admin/directory/faculties', async ({ request }) => {
      const event = await state.record(request);
      return fail ? problem('CSRF_INVALID', 403) : directoryJson({ id: 700, ...(event.body as { code: string; name: string }) }, 201);
    }));
    const view = mountDirectory(), dialog = await openAdd(view); await fill(view, dialog, 'faculties'); await save(view, dialog);
    const before = state.auth.csrfCount;
    await view.user.click(await dialog.findByRole('button', { name: /Обновить.*защит|Обновить.*CSRF/i }));
    await waitFor(() => expect(state.auth.csrfCount).toBeGreaterThan(before));
    expect(mutations(state)).toHaveLength(1); fail = false;
    await save(view, dialog); await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(mutations(state)).toHaveLength(2);
    expect(mutations(state)[1]!.csrf).toBe(state.auth.token);
  });
  it('pending save is single-flight and cannot be cancelled', async () => {
    const state = createDirectoryScenario(), gate = directoryDeferred();
    server.use(http.post('*/api/v1/admin/directory/faculties', async ({ request }) => { const event = await state.record(request); await gate.promise; return directoryJson({ id: 700, ...(event.body as { code: string; name: string }) }, 201); }));
    const view = mountDirectory(), dialog = await openAdd(view); await fill(view, dialog, 'faculties');
    await view.user.dblClick(dialog.getByRole('button', { name: 'Сохранить' }));
    await waitFor(() => expect(mutations(state)).toHaveLength(1));
    expect(dialog.getByRole('button', { name: 'Сохранить' })).toBeDisabled();
    expect(dialog.getByRole('button', { name: 'Отмена' })).toBeDisabled();
    await view.user.keyboard('{Escape}'); expect(screen.getByRole('dialog')).toBeInTheDocument();
    await act(async () => { gate.resolve(); }); await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });
  it('successful write followed by failed refresh retries only the GET', async () => {
    const state = createDirectoryScenario(); let failRefresh = false;
    server.use(http.get('*/api/v1/admin/directory/faculties', async ({ request }) => { await state.record(request); return failRefresh ? problem('INTERNAL_ERROR', 500) : directoryJson({ items: state.lists.faculties }); }));
    const view = mountDirectory(), dialog = await openAdd(view); await fill(view, dialog, 'faculties');
    failRefresh = true; await save(view, dialog);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const retry = await screen.findByRole('button', { name: /Повторить/ }); expect(mutations(state)).toHaveLength(1);
    failRefresh = false; await view.user.click(retry);
    expect(await screen.findByText('Новая запись', { exact: true })).toBeInTheDocument(); expect(mutations(state)).toHaveLength(1);
  });
  it('stale editor 404 closes, refreshes, and explains the removed record', async () => {
    const state = createDirectoryScenario();
    server.use(http.put('*/api/v1/admin/directory/faculties/1', async ({ request }) => { await state.record(request); state.lists.faculties = []; return problem('NOT_FOUND', 404); }));
    const view = mountDirectory(), dialog = await edit(view, kinds[0].name, 'факультет');
    await save(view, dialog); await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText(/уже удал|больше не существует|не найден/i)).toBeInTheDocument();
  });
});

describe('U13 directory deletion — R24', { timeout: 12_000 }, () => {
  it.each(kinds)('used $kind cannot be deleted, and cancelling does not call DELETE', async ({ kind, tab: label, noun, name }) => {
    const state = createDirectoryScenario(), view = mountDirectory(); await tab(view, label);
    let dialog = await remove(view, name, noun);
    expect(dialog.getByText(name, { exact: false })).toBeInTheDocument();
    await view.user.click(dialog.getByRole('button', { name: 'Отмена' })); expect(mutations(state)).toHaveLength(0);
    dialog = await remove(view, name, noun); await view.user.click(dialog.getByRole('button', { name: 'Удалить' }));
    const alert = await dialog.findByRole('alert');
    expect(within(alert).getByText(/используется|связан|студент/i)).toBeInTheDocument();
    expect(state.lists[kind].some((item) => item.name === name)).toBe(true);
    expect(mutations(state)).toHaveLength(1);
  });
  it.each(kinds)('new unused $kind is deleted after confirmation', async ({ kind, tab: label, noun }) => {
    const state = createDirectoryScenario(); const sample = state.lists[kind][0]!;
    state.lists[kind].push({ ...sample, id: 700, code: 'TEMP', name: 'Временная запись' });
    const view = mountDirectory(); await tab(view, label);
    const dialog = await remove(view, 'Временная запись', noun); await view.user.click(dialog.getByRole('button', { name: 'Удалить' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(screen.queryByText('Временная запись', { exact: true })).not.toBeInTheDocument());
    expect(mutations(state).map((item) => [item.method, item.path])).toEqual([['DELETE', `/api/v1/admin/directory/${kind}/700`]]);
  });
  it('stale delete 404 refreshes the list and does not keep a dead confirmation', async () => {
    const state = createDirectoryScenario();
    server.use(http.delete('*/api/v1/admin/directory/faculties/1', async ({ request }) => { await state.record(request); state.lists.faculties = []; return problem('NOT_FOUND', 404); }));
    const view = mountDirectory(), dialog = await remove(view, kinds[0].name, 'факультет');
    await view.user.click(dialog.getByRole('button', { name: 'Удалить' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText(/уже удал|больше не существует|не найден/i)).toBeInTheDocument();
  });
  it('pending delete is single-flight and keeps its confirmation open', async () => {
    const state = createDirectoryScenario(), gate = directoryDeferred();
    state.lists.faculties.push({ id: 700, code: 'TEMP', name: 'Временная запись' });
    server.use(http.delete('*/api/v1/admin/directory/faculties/700', async ({ request }) => {
      await state.record(request); await gate.promise;
      state.lists.faculties = state.lists.faculties.filter((item) => item.id !== 700);
      return new HttpResponse(null, { status: 204 });
    }));
    const view = mountDirectory(), dialog = await remove(view, 'Временная запись', 'факультет');
    await view.user.dblClick(dialog.getByRole('button', { name: 'Удалить' }));
    await waitFor(() => expect(mutations(state)).toHaveLength(1));
    expect(dialog.getByRole('button', { name: 'Отмена' })).toBeDisabled();
    await view.user.keyboard('{Escape}'); expect(screen.getByRole('dialog')).toBeInTheDocument();
    await act(async () => { gate.resolve(); });
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(mutations(state)).toHaveLength(1);
  });
});

import { act, cleanup, configure, getConfig, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../mocks/server';
import { authUsers, createTeachersScenario, disposeTeacherTests, foreignDepartment, foreignGroup, mountTeachers, problem, teacherDeferred, teacherFixtures, teacherJson } from '../../../test/teachersAcceptance';
import type { GrantCreate } from '../../../test/teachersAcceptance';

const originalTimeout = getConfig().asyncUtilTimeout;
beforeAll(() => { configure({ asyncUtilTimeout: 3_000 }); server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => {
  cleanup(); disposeTeacherTests(); server.resetHandlers(); localStorage.clear();
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'; document.cookie = 'campus-mock-user=; Max-Age=0; Path=/';
});

describe('R24/KTD23: grants and reliable mutations', { timeout: 15_000 }, () => {
const grantInputs: GrantCreate[] = [
  { kind: 'GROUP', groupId: 21 }, { kind: 'FACULTY', facultyId: 2 },
  { kind: 'FACULTY_COURSE', facultyId: 2, course: 1 }, { kind: 'COURSE', course: 6 },
];
it.each(grantInputs)('creates $kind with exactly its own fields, foreign targets are selectable', async (body) => {
  const state = createTeachersScenario(), view = mountTeachers('/admin/users/teachers/2002'), dialog = await grantDialog(view);
  await chooseGrant(view, dialog, body); await view.user.click(dialog.getByRole('button', { name: 'Выдать доступ' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  const request = writes(state).find((item) => item.path.endsWith('/grants'))!;
  expect(request.path).toBe('/api/v1/admin/teachers/2002/grants'); expect(request.body).toEqual(body); expect(request.csrf).toBe(state.auth.token);
  expect(screen.getByRole('status')).toHaveTextContent(/выдан|добавлен|сохран/i);
});
it.each(['0', '7', '1.5'])('invalid course %s cannot be sent', async (course) => {
  const state = createTeachersScenario(), view = mountTeachers('/admin/users/teachers/2002'), dialog = await grantDialog(view);
  await view.user.selectOptions(dialog.getByRole('combobox', { name: 'Вид доступа' }), 'COURSE');
  const input = dialog.getByRole('spinbutton', { name: 'Курс' }); await view.user.clear(input); await view.user.type(input, course);
  await view.user.click(dialog.getByRole('button', { name: 'Выдать доступ' })); expect(input).toHaveAttribute('aria-invalid', 'true'); expect(writes(state)).toHaveLength(0);
});
it('changing kind clears hidden fields and reopening clears abandoned state', async () => {
  const state = createTeachersScenario(), view = mountTeachers('/admin/users/teachers/2002'), dialog = await grantDialog(view);
  await chooseGrant(view, dialog, { kind: 'FACULTY_COURSE', facultyId: 2, course: 4 });
  await view.user.selectOptions(dialog.getByRole('combobox', { name: 'Вид доступа' }), 'GROUP');
  expect(dialog.queryByRole('combobox', { name: 'Факультет' })).not.toBeInTheDocument(); expect(dialog.queryByRole('spinbutton', { name: 'Курс' })).not.toBeInTheDocument();
  await view.user.selectOptions(dialog.getByRole('combobox', { name: 'Группа' }), '21');
  await view.user.click(dialog.getByRole('button', { name: 'Отмена' })); expect(writes(state)).toHaveLength(0);
  const reopened = await grantDialog(view);
  await chooseGrant(view, reopened, { kind: 'COURSE', course: 1 }); await view.user.click(reopened.getByRole('button', { name: 'Выдать доступ' }));
  await waitFor(() => expect(writes(state)).toHaveLength(1)); expect(writes(state)[0]!.body).toEqual({ kind: 'COURSE', course: 1 });
});
it('existing grant returned with 200 is accepted without a duplicate local row', async () => {
  const state = createTeachersScenario(); state.grants.set(2002, [{ id: 9100, kind: 'COURSE', course: 2 }]);
  const view = mountTeachers('/admin/users/teachers/2002'), dialog = await grantDialog(view);
  await chooseGrant(view, dialog, { kind: 'COURSE', course: 2 });
  await view.user.click(dialog.getByRole('button', { name: 'Выдать доступ' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(writes(state)).toHaveLength(1);
  expect(await screen.findAllByRole('button', { name: /Снять доступ/ })).toHaveLength(1);
  expect(state.grants.get(2002)).toHaveLength(1);
});
it('valid grant response for another target cannot confirm the requested grant', async () => {
  const state = createTeachersScenario(), original = state.groups.find((item) => item.id === 11)!;
  server.use(http.post('*/api/v1/admin/teachers/2002/grants', async ({ request }) => {
    state.requests.push({ method: request.method, path: new URL(request.url).pathname, body: await request.clone().json() as unknown,
      csrf: request.headers.get('X-XSRF-TOKEN'), signal: request.signal });
    return teacherJson({ id: 9200, kind: 'GROUP', group: { id: original.id, name: original.name, faculty: original.faculty } }, 201);
  }));
  const view = mountTeachers('/admin/users/teachers/2002'), dialog = await grantDialog(view);
  await chooseGrant(view, dialog, { kind: 'GROUP', groupId: 21 });
  await view.user.click(dialog.getByRole('button', { name: 'Выдать доступ' }));
  expect(await dialog.findByRole('alert')).toBeInTheDocument();
  expect(dialog.getByRole('combobox', { name: 'Группа' })).toHaveValue('21');
  expect(writes(state).map((item) => item.body)).toEqual([{ kind: 'GROUP', groupId: 21 }]);
  expect(screen.queryByText('Доступ выдан', { exact: true })).not.toBeInTheDocument();
});
it('grant catalog error retries without manufacturing a selectable id', async () => {
  const state = createTeachersScenario(); let fail = true;
  server.use(http.get('*/api/v1/admin/directory/groups', () => fail ? problem('INTERNAL_ERROR', 500) : teacherJson({ items: state.groups })));
  const view = mountTeachers('/admin/users/teachers/2002'), dialog = await grantDialog(view);
  await view.user.selectOptions(dialog.getByRole('combobox', { name: 'Вид доступа' }), 'GROUP');
  const retry = await dialog.findByRole('button', { name: /Повторить/ }); expect(dialog.getByRole('button', { name: 'Выдать доступ' })).toBeDisabled();
  fail = false; await view.user.click(retry);
  await waitFor(() => expect(dialog.getByRole('combobox', { name: 'Группа' }).querySelector('option[value="21"]')).not.toBeNull()); expect(writes(state)).toHaveLength(0);
});
it('pending teacher scope is empty even with explicit grants and uses its own explanation', async () => {
  const state = createTeachersScenario(); state.grants.set(2002, [teacherFixtures.grants().items[0]!]);
  mountTeachers('/admin/users/teachers/2002');
  expect(await screen.findByText(/до допуска.*доступных групп нет/i)).toBeInTheDocument();
  expect(screen.queryByText(foreignGroup.name, { exact: true })).not.toBeInTheDocument();
});
it('scope error does not hide teacher or grants and retries independently', async () => {
  createTeachersScenario(); let fail = true;
  server.use(http.get('*/api/v1/admin/teachers/2001/accessible-groups', () => fail ? problem('INTERNAL_ERROR', 500) : teacherJson({ items: [foreignGroup] })));
  const view = mountTeachers('/admin/users/teachers/2001');
  expect(await screen.findByRole('heading', { name: approvedName })).toBeInTheDocument();
  expect(await screen.findByRole('heading', { name: 'Выдачи доступа' })).toBeInTheDocument();
  const retry = await screen.findByRole('button', { name: /Повторить/ }); fail = false; await view.user.click(retry);
  expect(await screen.findByText(foreignGroup.name, { exact: true })).toBeInTheDocument();
});
it('removes only the confirmed teacher/grant pair, cancel does nothing', async () => {
  const state = createTeachersScenario(); state.grants.set(2001, [teacherFixtures.grants().items[0]!]);
  const view = mountTeachers('/admin/users/teachers/2001');
  await view.user.click(await screen.findByRole('button', { name: /Снять доступ/ }));
  let dialog = within(await screen.findByRole('dialog', { name: 'Снять доступ' }));
  await view.user.click(dialog.getByRole('button', { name: 'Отмена' })); expect(writes(state)).toHaveLength(0);
  await view.user.click(screen.getByRole('button', { name: /Снять доступ/ })); dialog = within(await screen.findByRole('dialog', { name: 'Снять доступ' }));
  await view.user.click(dialog.getByRole('button', { name: 'Снять доступ' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(writes(state).map((item) => `${item.method} ${item.path}`)).toEqual(['DELETE /api/v1/admin/teachers/2001/grants/7001']);
  await waitFor(() => expect(screen.queryByRole('button', { name: /Снять доступ/ })).not.toBeInTheDocument());
});
it('pending grant creation blocks duplicate submit', async () => {
  createTeachersScenario(); const gate = teacherDeferred(); let count = 0;
  server.use(http.post('*/api/v1/admin/teachers/:id/grants', async () => { count++; await gate.promise; return teacherJson({ id: 9000, kind: 'COURSE', course: 1 }, 201); }));
  const view = mountTeachers('/admin/users/teachers/2002'), dialog = await grantDialog(view);
  await chooseGrant(view, dialog, { kind: 'COURSE', course: 1 }); await view.user.dblClick(dialog.getByRole('button', { name: 'Выдать доступ' }));
  expect(count).toBe(1); expect(dialog.getByRole('button', { name: 'Отмена' })).toBeDisabled();
  await act(async () => { gate.resolve(); }); await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
});
it('pending grant removal sends one DELETE and cannot be cancelled mid-flight', async () => {
  const state = createTeachersScenario(); state.grants.set(2001, [teacherFixtures.grants().items[0]!]);
  const gate = teacherDeferred(); let count = 0;
  server.use(http.delete('*/api/v1/admin/teachers/2001/grants/7001', async () => { count++; await gate.promise; state.grants.set(2001, []); return new HttpResponse(null, { status: 204 }); }));
  const view = mountTeachers('/admin/users/teachers/2001');
  await view.user.click(await screen.findByRole('button', { name: /Снять доступ/ }));
  const dialog = within(await screen.findByRole('dialog', { name: 'Снять доступ' }));
  await view.user.dblClick(dialog.getByRole('button', { name: 'Снять доступ' }));
  expect(count).toBe(1); expect(dialog.getByRole('button', { name: 'Отмена' })).toBeDisabled();
  await act(async () => { gate.resolve(); }); await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
});
it('CSRF recovery is explicit and never replays the failed write', async () => {
  const state = createTeachersScenario(); let count = 0;
  server.use(http.post('*/api/v1/admin/teachers/:id/grants', () => ++count === 1 ? problem('CSRF_INVALID', 403) : teacherJson({ id: 9000, kind: 'COURSE', course: 1 }, 201)));
  const view = mountTeachers('/admin/users/teachers/2002'), dialog = await grantDialog(view);
  await chooseGrant(view, dialog, { kind: 'COURSE', course: 1 }); await view.user.click(dialog.getByRole('button', { name: 'Выдать доступ' }));
  const tokenCount = state.auth.csrfCount;
  await view.user.click(await dialog.findByRole('button', { name: /Обновить защиту/ }));
  await waitFor(() => expect(state.auth.csrfCount).toBeGreaterThan(tokenCount)); expect(count).toBe(1);
  expect(dialog.getByRole('spinbutton', { name: 'Курс' })).toHaveValue(1);
  await view.user.click(dialog.getByRole('button', { name: 'Выдать доступ' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument()); expect(count).toBe(2);
});
it.each(['403', 'network', '500', 'malformed'] as const)('grant write %s preserves entered data and never shows success', async (kind) => {
  createTeachersScenario(); let count = 0;
  server.use(http.post('*/api/v1/admin/teachers/:id/grants', () => {
    count++; return kind === '403' ? problem('FORBIDDEN', 403) : kind === 'network' ? HttpResponse.error() : kind === 'malformed' ? teacherJson({ id: 1, kind: 'COURSE', course: 7 }, 201) : problem('INTERNAL_ERROR', 500);
  }));
  const view = mountTeachers('/admin/users/teachers/2002'), dialog = await grantDialog(view);
  await chooseGrant(view, dialog, { kind: 'COURSE', course: 1 }); await view.user.click(dialog.getByRole('button', { name: 'Выдать доступ' }));
  expect(await dialog.findByRole('alert')).toBeInTheDocument(); expect(dialog.getByRole('spinbutton', { name: 'Курс' })).toHaveValue(1); expect(count).toBe(1);
});
it('successful grant plus failed read retries only GET, not POST', async () => {
  const state = createTeachersScenario(); let failRead = false, postCount = 0;
  server.use(
    http.post('*/api/v1/admin/teachers/2002/grants', () => { postCount++; failRead = true; return teacherJson({ id: 9000, kind: 'COURSE', course: 1 }, 201); }),
    http.get('*/api/v1/admin/teachers/2002/grants', () => failRead ? problem('INTERNAL_ERROR', 500) : teacherJson({ items: state.grants.get(2002) ?? [] })),
  );
  const view = mountTeachers('/admin/users/teachers/2002'), dialog = await grantDialog(view);
  await chooseGrant(view, dialog, { kind: 'COURSE', course: 1 }); await view.user.click(dialog.getByRole('button', { name: 'Выдать доступ' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  const retry = await screen.findByRole('button', { name: /Повторить/ }); failRead = false; await view.user.click(retry);
  await waitFor(() => expect(screen.queryByRole('button', { name: /Повторить/ })).not.toBeInTheDocument()); expect(postCount).toBe(1);
});
it('late detail response is aborted when navigating to another teacher', async () => {
  createTeachersScenario(); const gate = teacherDeferred(); let started = false, aborted = false;
  server.use(http.get('*/api/v1/admin/teachers/2002', async ({ request }) => { started = true; request.signal.addEventListener('abort', () => { aborted = true; }); await gate.promise; return teacherJson({ ...teacherFixtures.pending(), fullName: 'Устаревшая карточка' }); }));
  const view = mountTeachers('/admin/users/teachers/2002'); await waitFor(() => expect(started).toBe(true));
  await act(async () => { await view.router.navigate('/admin/users/teachers/2001'); });
  expect(await screen.findByRole('heading', { name: approvedName })).toBeInTheDocument();
  await act(async () => { gate.resolve(); }); await waitFor(() => expect(aborted).toBe(true)); expect(screen.queryByText('Устаревшая карточка')).not.toBeInTheDocument();
});
});
afterAll(() => { server.close(); configure({ asyncUtilTimeout: originalTimeout }); });

const approvedName = teacherFixtures.list().items.find((item) => item.teacherApproved)!.fullName;
type View = ReturnType<typeof mountTeachers>;
type State = ReturnType<typeof createTeachersScenario>;
function writes(state: State) { return state.requests.filter((item) => item.method !== 'GET'); }
async function approval(view: View) {
  await view.user.click(await screen.findByRole('button', { name: 'Допустить' }));
  return within(await screen.findByRole('dialog', { name: 'Допустить преподавателя' }));
}
async function grantDialog(view: View) {
  await view.user.click(await screen.findByRole('button', { name: 'Выдать доступ' }));
  return within(await screen.findByRole('dialog', { name: 'Выдать доступ' }));
}
async function chooseGrant(view: View, dialog: ReturnType<typeof within>, body: GrantCreate) {
  await view.user.selectOptions(dialog.getByRole('combobox', { name: 'Вид доступа' }), body.kind);
  if (body.kind === 'GROUP') await view.user.selectOptions(dialog.getByRole('combobox', { name: 'Группа' }), String(body.groupId));
  if (body.kind === 'FACULTY' || body.kind === 'FACULTY_COURSE') await view.user.selectOptions(dialog.getByRole('combobox', { name: 'Факультет' }), String(body.facultyId));
  if (body.kind === 'COURSE' || body.kind === 'FACULTY_COURSE') {
    const input = dialog.getByRole('spinbutton', { name: 'Курс' }); await view.user.clear(input); await view.user.type(input, String(body.course));
  }
}

describe('R2/R3/R24: teacher access, list and detail', { timeout: 12_000 }, () => {
it('old teacher list address redirects to the users table filtered by applications', async () => {
  createTeachersScenario(); const view = mountTeachers();
  await waitFor(() => expect(view.router.state.location.pathname).toBe('/admin/users'));
  expect(new URLSearchParams(view.router.state.location.search).get('role')).toBe('TEACHER');
  expect(new URLSearchParams(view.router.state.location.search).get('status')).toBe('PENDING');
});
it.each([authUsers.student, authUsers.teacher])('role $role has no teacher API calls', async (viewer) => {
  const state = createTeachersScenario(viewer); mountTeachers('/admin/users/teachers/2002');
  expect(await screen.findByText('Нет доступа', { exact: true })).toBeInTheDocument(); expect(state.requests).toHaveLength(0);
});
it.each([null, { ...authUsers.admin, mustChangePassword: true }])('guard runs before admin calls %#', async (viewer) => {
  const state = createTeachersScenario(viewer), view = mountTeachers();
  await waitFor(() => expect(view.router.state.location.pathname).toBe(viewer ? '/password/change' : '/login')); expect(state.requests).toHaveLength(0);
});
it.each(['abc', '0', '-1', '9007199254740992'])('bad id %s cannot request detail, grants or scope', async (id) => {
  const state = createTeachersScenario(); mountTeachers(`/admin/users/teachers/${id}`);
  expect(await screen.findByText('Преподаватель не найден', { exact: true })).toBeInTheDocument();
  expect(state.requests.filter((item) => item.path !== '/api/v1/admin/teachers')).toHaveLength(0);
});
it.each([[403, 'FORBIDDEN', 'Нет доступа'], [404, 'NOT_FOUND', 'Преподаватель не найден']] as const)('detail handles %s', async (status, code, message) => {
  createTeachersScenario(); server.use(http.get('*/api/v1/admin/teachers/2002', () => problem(code, status))); mountTeachers('/admin/users/teachers/2002');
  expect(await screen.findByText(message, { exact: true })).toBeInTheDocument();
});
it('directory has no teachers entry', async () => {
  createTeachersScenario(); mountTeachers('/admin/directory');
  expect(await screen.findByRole('heading', { name: 'Справочники' })).toBeInTheDocument();
  expect(screen.queryByRole('link', { name: 'Преподаватели' })).not.toBeInTheDocument();
});
it('approved detail hides application decisions and displays the server scope without computing it', async () => {
  const state = createTeachersScenario(); const onlyServer = { ...foreignGroup, id: 999, name: 'Группа только из ответа сервера' };
  state.scopes.set(2001, [onlyServer]); mountTeachers('/admin/users/teachers/2001');
  expect(await screen.findByRole('heading', { name: approvedName })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Допустить' })).not.toBeInTheDocument(); expect(screen.queryByRole('button', { name: 'Отклонить заявку' })).not.toBeInTheDocument();
  expect(await screen.findByText(onlyServer.name, { exact: true })).toBeInTheDocument();
});
});

describe('R2/R24/KTD23: application decisions', { timeout: 15_000 }, () => {
it('approval uses the checked department only and refreshes pending badge and server scope', async () => {
  const state = createTeachersScenario(), view = mountTeachers('/admin/users/teachers/2002'), dialog = await approval(view);
  expect(dialog.getByRole('combobox', { name: 'Кафедра' })).toHaveValue('101');
  expect(dialog.getByText(/факультет.*доступные группы/i)).toBeInTheDocument();
  await view.user.selectOptions(dialog.getByRole('combobox', { name: 'Кафедра' }), '202');
  await view.user.click(dialog.getByRole('button', { name: 'Допустить' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(writes(state)).toMatchObject([{ method: 'POST', path: '/api/v1/admin/teachers/2002/approval', body: { departmentId: 202 }, csrf: state.auth.token }]);
  expect(writes(state)[0]!.body).toEqual({ departmentId: 202 });
  expect(await screen.findByText(foreignDepartment.name, { exact: true })).toBeInTheDocument();
  expect(await screen.findByText(foreignGroup.name, { exact: true })).toBeInTheDocument();
  await waitFor(() => expect(screen.queryByLabelText('Заявки преподавателей: 1')).not.toBeInTheDocument());
});
it('approval cancel sends nothing and returns focus to its opener', async () => {
  const state = createTeachersScenario(), view = mountTeachers('/admin/users/teachers/2002'), dialog = await approval(view);
  await view.user.selectOptions(dialog.getByRole('combobox', { name: 'Кафедра' }), '202');
  await view.user.click(dialog.getByRole('button', { name: 'Отмена' }));
  await waitFor(() => expect(screen.getByRole('button', { name: 'Допустить' })).toHaveFocus()); expect(writes(state)).toHaveLength(0);
  const reopened = await approval(view); expect(reopened.getByRole('combobox', { name: 'Кафедра' })).toHaveValue('101');
});
it('empty departments explain why approval cannot be sent', async () => {
  const state = createTeachersScenario(); state.departments = [];
  const view = mountTeachers('/admin/users/teachers/2002'), dialog = await approval(view);
  expect(await dialog.findByText(/кафедр.*нет|создайте.*кафедр/i)).toBeInTheDocument(); expect(dialog.getByRole('button', { name: 'Допустить' })).toBeDisabled(); expect(writes(state)).toHaveLength(0);
});
it('department lookup has retry within the approval dialog', async () => {
  createTeachersScenario(); let fail = true;
  server.use(http.get('*/api/v1/admin/directory/departments', () => fail ? problem('INTERNAL_ERROR', 500) : teacherJson({ items: [foreignDepartment] })));
  const view = mountTeachers('/admin/users/teachers/2002'), dialog = await approval(view);
  const retry = await dialog.findByRole('button', { name: /Повторить/ }); fail = false; await view.user.click(retry);
  await waitFor(() => expect(dialog.getByRole('combobox', { name: 'Кафедра' }).querySelector('option[value="202"]')).not.toBeNull());
});
it('reject explicitly warns about account deletion, cancel does not delete, confirmation removes the application', async () => {
  const state = createTeachersScenario(), view = mountTeachers('/admin/users/teachers/2002');
  await view.user.click(await screen.findByRole('button', { name: 'Отклонить заявку' }));
  let dialog = within(await screen.findByRole('dialog', { name: 'Отклонить заявку' }));
  expect(dialog.getByText(/аккаунт.*удалён/i)).toBeInTheDocument(); expect(dialog.getByText(/зарегистрироваться заново/i)).toBeInTheDocument();
  await view.user.click(dialog.getByRole('button', { name: 'Отмена' })); expect(writes(state)).toHaveLength(0);
  await view.user.click(screen.getByRole('button', { name: 'Отклонить заявку' })); dialog = within(await screen.findByRole('dialog', { name: 'Отклонить заявку' }));
  await view.user.click(dialog.getByRole('button', { name: 'Отклонить' }));
  await waitFor(() => expect(view.router.state.location.pathname).toBe('/admin/users'));
  expect(writes(state).map((item) => `${item.method} ${item.path}`)).toEqual(['DELETE /api/v1/admin/teachers/2002/application']);
  await waitFor(() => expect(screen.queryByLabelText('Заявки преподавателей: 1')).not.toBeInTheDocument());
});
it('pending approval blocks double confirmation and dialog cancellation', async () => {
  createTeachersScenario(); const gate = teacherDeferred(); let count = 0;
  server.use(http.post('*/api/v1/admin/teachers/:id/approval', async () => { count++; await gate.promise; return teacherJson({ ...teacherFixtures.pending(), teacherApproved: true }); }));
  const view = mountTeachers('/admin/users/teachers/2002'), dialog = await approval(view);
  await view.user.dblClick(dialog.getByRole('button', { name: 'Допустить' }));
  expect(dialog.getByRole('button', { name: 'Отмена' })).toBeDisabled(); expect(count).toBe(1);
  await act(async () => { gate.resolve(); }); await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
});
it('stale approval state explains refresh and never automatically replays the write', async () => {
  createTeachersScenario(); let count = 0;
  server.use(http.post('*/api/v1/admin/teachers/:id/approval', () => { count++; return problem('INVALID_STATE', 409); }));
  const view = mountTeachers('/admin/users/teachers/2002'), dialog = await approval(view);
  await view.user.click(dialog.getByRole('button', { name: 'Допустить' }));
  expect(await dialog.findByText(/данные.*измен|состояние.*измен/i)).toBeInTheDocument();
  await view.user.click(dialog.getByRole('button', { name: /Обновить карточку/ })); expect(count).toBe(1);
});
it('server field error stays connected to the department control', async () => {
  createTeachersScenario(); server.use(http.post('*/api/v1/admin/teachers/:id/approval', () => problem('VALIDATION_FAILED', 400, { fieldErrors: [{ field: 'departmentId', code: 'NOT_FOUND', message: 'Кафедра больше не существует' }] })));
  const view = mountTeachers('/admin/users/teachers/2002'), dialog = await approval(view);
  await view.user.click(dialog.getByRole('button', { name: 'Допустить' }));
  await waitFor(() => expect(dialog.getByRole('combobox', { name: 'Кафедра' })).toHaveAttribute('aria-invalid', 'true'));
  expect(dialog.getByRole('combobox', { name: 'Кафедра' })).toHaveAccessibleDescription('Кафедра больше не существует');
});
it('reject of an already removed application refreshes the list without retrying DELETE', async () => {
  const state = createTeachersScenario(); let count = 0;
  server.use(http.delete('*/api/v1/admin/teachers/2002/application', () => { count++; state.teachers.delete(2002); return problem('NOT_FOUND', 404); }));
  const view = mountTeachers('/admin/users/teachers/2002');
  await view.user.click(await screen.findByRole('button', { name: 'Отклонить заявку' }));
  const dialog = within(await screen.findByRole('dialog', { name: 'Отклонить заявку' }));
  await view.user.click(dialog.getByRole('button', { name: 'Отклонить' }));
  expect(await screen.findByText(/уже.*отсутств|уже.*удал|не найден/i)).toBeInTheDocument();
  expect(count).toBe(1);
});
});

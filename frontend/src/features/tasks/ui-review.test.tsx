import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { taskClock } from './clock';
import { server } from '../../mocks/server';
import { renderApp } from '../../test/render';
import { setViewportWidth } from '../../test/setup';
import { example, type Schemas } from './fixtures';

beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
const student = { userId: '1001', role: 'STUDENT' as const };
const teacher = { userId: '2001', role: 'TEACHER' as const };
const serverNow = '2026-11-11T20:00:00Z';
function assignment(state: Schemas['AssignmentState'] = 'OPEN') {
  const a = example<Schemas['Assignment']>('getAssignment');
  a.state = state;
  a.status = state === 'OPEN' ? 'IN_PROGRESS' : state;
  a.checkpoint.deadline = '2026-11-12T11:00:00Z';
  a.checkpoint.author.fullName = 'Петров Павел Петрович';
  a.penalty = {
    ...a.penalty, charged: 0, phase: state === 'OPEN' ? 'BEFORE_DEADLINE' : 'STOPPED',
    nextAt: state === 'OPEN' ? a.checkpoint.deadline : null,
    nextAmount: state === 'OPEN' ? a.penalty.step : null,
  };
  if (state !== 'OPEN') {
    a.submission = { id: 1, kind: 'LINK', url: 'https://example.com', submittedAt: serverNow, returnedAt: null, returnComment: null, withdrawnAt: null };
    a.submissions = [a.submission];
    a.submittedAt = serverNow;
    a.acceptedAt = state === 'ACCEPTED' ? serverNow : null;
  }
  return a;
}
function serveAssignment(a: Schemas['Assignment']) {
  server.use(http.get('*/api/v1/assignments/:id', () => HttpResponse.json(a, { headers: { Date: serverNow } })));
}
function problem(status: number, code: string, detail?: string) {
  return HttpResponse.json({ type: 'about:blank', title: 'Error', status, code, detail }, {
    status, headers: { 'Content-Type': 'application/problem+json' },
  });
}
async function fileForm() {
  serveAssignment(assignment());
  renderApp('/tasks/7001', student);
  const user = userEvent.setup({ applyAccept: false });
  await user.click(await screen.findByRole('tab', { name: 'Файл' }));
  return user;
}
function file(name: string, size: number) { return new File([new Uint8Array(size)], name); }

it('R4–R6: подтверждение, отмена, успешный отзыв и инвалидация', async () => {
  const a = assignment('SUBMITTED');
  a.submissions = [{ id: 1, kind: 'LINK', url: 'https://example.com', submittedAt: serverNow, returnedAt: null, returnComment: null, withdrawnAt: null }];
  a.submission = a.submissions[0]!;
  let reads = 0;
  server.use(
    http.get('*/api/v1/assignments/:id', () => { reads++; return HttpResponse.json(a, { headers: { Date: serverNow } }); }),
    http.post('*/api/v1/assignments/:id/submission/withdrawal', () => {
      a.state = 'OPEN'; a.status = 'IN_PROGRESS'; a.penalty.phase = 'BEFORE_DEADLINE';
      a.submissions[0]!.withdrawnAt = serverNow;
      a.submittedAt = null; a.penalty.nextAt = a.checkpoint.deadline; a.penalty.nextAmount = a.penalty.step;
      return HttpResponse.json(a);
    }),
  );
  renderApp('/tasks/7001', student);
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Отозвать сдачу' }));
  expect(screen.getByRole('dialog', { name: 'Отозвать работу?' })).toHaveTextContent('Вы сможете сдать её заново до дедлайна');
  await user.click(screen.getByRole('button', { name: 'Отмена' }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(screen.queryByText(/Отозвана студентом/)).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Отозвать сдачу' }));
  await user.click(screen.getByRole('button', { name: 'Отозвать' }));
  expect(await screen.findByRole('button', { name: 'Сдать работу' })).toBeEnabled();
  expect(screen.getByText('В процессе')).toBeInTheDocument();
  expect(screen.getByText('Списано 0 из 60 АР')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Отозвать сдачу' })).not.toBeInTheDocument();
  expect(reads).toBeGreaterThan(1);
});
it.each([
  ['TEACHER', '2001', '2026-11-12T11:00:00Z'],
  ['ADMIN', '1', '2026-11-12T11:00:00Z'],
  ['STUDENT', '1002', '2026-11-12T11:00:00Z'],
  ['STUDENT', '1001', serverNow],
  ['STUDENT', '1001', '2026-11-11T19:00:00Z'],
] as const)('R4: нет отзыва для %s/%s при дедлайне %s', async (role, userId, deadline) => {
  const a = assignment('SUBMITTED'); a.checkpoint.deadline = deadline; serveAssignment(a);
  renderApp('/tasks/7001', { role, userId });
  await screen.findByRole('region', { name: 'История сдач' });
  expect(screen.queryByRole('button', { name: 'Отозвать сдачу' })).not.toBeInTheDocument();
});
it.each([
  ['Срок сдачи прошёл — отозвать работу нельзя', 'SUBMITTED'],
  ['Работу уже приняли — отозвать нельзя', 'ACCEPTED'],
] as const)('R7/R8: 409 %s и перечитывание состояния', async (detail, state) => {
  const a = assignment('SUBMITTED'); serveAssignment(a);
  server.use(http.post('*/api/v1/assignments/:id/submission/withdrawal', () => {
    a.state = state; a.status = state;
    if (state === 'ACCEPTED') a.acceptedAt = serverNow;
    if (state === 'SUBMITTED') a.checkpoint.deadline = serverNow;
    return problem(409, 'INVALID_STATE', detail);
  }));
  renderApp('/tasks/7001', student);
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Отозвать сдачу' }));
  await user.click(screen.getByRole('button', { name: 'Отозвать' }));
  expect(await within(screen.getByRole('dialog')).findByText(detail)).toBeInTheDocument();
  expect(screen.getByRole('alert')).toHaveStyle({ color: 'var(--cr-status-overdue)' });
  await user.click(screen.getByRole('button', { name: 'Отмена' }));
  await waitFor(() => expect(screen.queryByRole('button', { name: 'Отозвать сдачу' })).not.toBeInTheDocument());
  expect(screen.getByText(state === 'ACCEPTED' ? 'Принято' : 'Сдано на проверку')).toBeInTheDocument();
});
it.each([student, teacher, { userId: '1', role: 'ADMIN' as const }])('R9: пометка и материалы сохранены для $role', async (session) => {
  const a = assignment();
  a.submissions = [
    { id: 1, kind: 'LINK', url: 'https://example.com', submittedAt: serverNow, returnedAt: null, returnComment: null, withdrawnAt: serverNow },
    { id: 2, kind: 'FILE', fileName: 'report.pdf', contentType: 'application/pdf', sizeBytes: 20, submittedAt: serverNow, returnedAt: null, returnComment: null, withdrawnAt: serverNow },
  ];
  serveAssignment(a); renderApp('/tasks/7001', session);
  const history = await screen.findByRole('region', { name: 'История сдач' });
  expect(within(history).getAllByText('Отозвана студентом · 11 нояб. 2026 г., 23:00 (МСК)')).toHaveLength(2);
  expect(within(history).getByRole('link', { name: 'report.pdf' })).toHaveAttribute('href', '/api/v1/assignments/7001/submissions/2/file');
  expect(within(history).getByRole('link', { name: 'https://example.com' })).toHaveAttribute('href', 'https://example.com');
});
it.each([['report.pdf', 2516582, '2,4 МБ'], ['scan.png', 87040, '85 КБ'], ['scan.jpg', 1025, '2 КБ'], ['work.zip', 1048576, '1,0 МБ']])('R11–R13: выбор %s, размер и удаление', async (name, size, formatted) => {
  const user = await fileForm();
  expect(screen.getByRole('button', { name: 'Выбрать файл' })).toBeInTheDocument();
  expect(screen.getByLabelText(/Файл работы/)).not.toBeVisible();
  expect(screen.getByText('До 10 МБ: pdf, docx, zip, png или jpg')).toBeVisible();
  expect(screen.getByRole('button', { name: 'Выбрать файл' })).toHaveAttribute('data-variant', 'default');
  expect(screen.getByText('*')).toBeVisible();
  await user.upload(screen.getByLabelText(/Файл работы/), file(String(name), Number(size)));
  expect(screen.getByText(`${name} · ${formatted}`)).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Убрать' }));
  expect(screen.queryByText(`${name} · ${formatted}`)).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Сдать работу' })).toBeEnabled();
  await user.click(screen.getByRole('button', { name: 'Сдать работу' }));
  expect(screen.getByText('Выберите файл')).toBeInTheDocument();
});
it.each([
  ['big.pdf', 13000000, 'Файл больше 10 МБ'],
  ['limit.pdf', 10485760, null], ['over.pdf', 10485761, 'Файл больше 10 МБ'],
  ['setup.exe', 1048576, 'Можно загрузить pdf, docx, zip, png или jpg'],
  ['REPORT.PDF', 1048576, null], ['movie.mov', 50000000, 'Можно загрузить pdf, docx, zip, png или jpg'],
])('R14/R15: граница/тип %s', async (name, size, error) => {
  let requests = 0;
  server.use(http.post('*/api/v1/assignments/:id/submission', () => { requests++; return HttpResponse.json({}); }));
  const user = await fileForm();
  await user.upload(screen.getByLabelText(/Файл работы/), file(name, size));
  if (error) {
    expect(screen.getByText(error)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Сдать работу' })).toBeDisabled();
    expect(screen.getByLabelText(/Файл работы/)).toHaveAttribute('aria-invalid', 'true');
    expect(requests).toBe(0);
    await user.click(screen.getByRole('button', { name: 'Убрать' }));
    expect(screen.queryByText(error)).not.toBeInTheDocument();
    await user.upload(screen.getByLabelText(/Файл работы/), file('good.docx', 1));
  }
  expect(screen.getByRole('button', { name: 'Сдать работу' })).toBeEnabled();
});
it.each(['html', 'problem', 'broken-json'])('R16: 413 %s под полем', async (body) => {
  server.use(http.post('*/api/v1/assignments/:id/submission', () => body === 'problem'
    ? problem(413, 'FILE_TOO_LARGE')
    : new HttpResponse(body === 'html' ? '<html>Too large</html>' : '{', { status: 413, headers: { 'Content-Type': body === 'html' ? 'text/html' : 'application/problem+json' } })));
  const user = await fileForm();
  await user.upload(screen.getByLabelText(/Файл работы/), file('report.pdf', 1));
  await user.click(screen.getByRole('button', { name: 'Сдать работу' }));
  expect(await screen.findByText('Файл больше 10 МБ')).toBeInTheDocument();
  expect(screen.queryByText(/Проверьте соединение/)).not.toBeInTheDocument();
  expect(screen.getByLabelText(/Файл работы/)).toHaveAttribute('aria-invalid', 'true');
});
it.each([['', 'Укажите ссылку на работу'], ['ftp://example.com/work', 'Укажите ссылку http или https длиной до 2000 символов']])('R18–R20: ссылка %s', async (link, message) => {
  serveAssignment(assignment()); renderApp('/tasks/7001', student);
  const user = userEvent.setup();
  const input = await screen.findByRole('textbox', { name: /Ссылка на работу/ });
  expect(input.closest('form')).toHaveAttribute('novalidate');
  if (link) await user.type(input, link);
  await user.click(screen.getByRole('button', { name: 'Сдать работу' }));
  expect(await screen.findByText(message)).toBeInTheDocument();
  expect(input).toHaveAttribute('aria-invalid', 'true');
});
it('R18: ошибка сети над кнопкой, не под полем', async () => {
  serveAssignment(assignment()); server.use(http.post('*/api/v1/assignments/:id/submission', () => HttpResponse.error()));
  renderApp('/tasks/7001', student); const user = userEvent.setup();
  const input = await screen.findByRole('textbox', { name: /Ссылка на работу/ });
  await user.type(input, 'https://example.com'); await user.click(screen.getByRole('button', { name: 'Сдать работу' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Не удалось выполнить действие. Проверьте соединение и попробуйте снова.');
  expect(input).not.toHaveAttribute('aria-invalid', 'true');
});
it('R21: одно замечание в задаче, раскрытие в истории и после новой сдачи', async () => {
  const a = assignment(); a.returnComment = 'Нет тестов';
  a.submissions = [{ id: 1, kind: 'LINK', url: 'https://example.com/old', submittedAt: serverNow, returnComment: 'Нет тестов', returnedAt: '2026-11-11T19:31:00Z', withdrawnAt: null }];
  a.submission = a.submissions[0]!;
  serveAssignment(a);
  server.use(http.post('*/api/v1/assignments/:id/submission', () => {
    a.state = 'SUBMITTED'; a.status = 'SUBMITTED'; a.returnComment = null;
    a.submissions.unshift({ id: 2, kind: 'LINK', url: 'https://example.com/new', submittedAt: serverNow, returnComment: null, returnedAt: null, withdrawnAt: null });
    a.submission = a.submissions[0]!; a.submittedAt = serverNow;
    a.penalty = { ...a.penalty, phase: 'STOPPED', nextAt: null, nextAmount: null };
    return HttpResponse.json(a);
  }));
  renderApp('/tasks/7001', student); const user = userEvent.setup();
  await screen.findByRole('region', { name: 'История сдач' });
  const visibleComments = () => screen.getAllByText('Нет тестов').filter((node) => !node.closest('details:not([open])'));
  expect(visibleComments()).toHaveLength(1);
  expect(visibleComments()[0]!.parentElement).toHaveStyle({ fontStyle: 'normal', fontFamily: 'var(--mantine-font-family)' });
  expect(screen.getAllByText('Замечание преподавателя · Петров П. П. · 11 нояб., 22:31')[0]).toBeVisible();
  await user.click(screen.getByText('Возвращена с замечанием'));
  expect(visibleComments()).toHaveLength(2);
  await user.type(screen.getByRole('textbox', { name: /Ссылка на работу/ }), 'https://example.com/new');
  await user.click(screen.getByRole('button', { name: 'Сдать работу' }));
  await waitFor(() => expect(screen.queryByRole('button', { name: 'Сдать работу' })).not.toBeInTheDocument());
  expect(screen.getAllByText('Нет тестов')).toHaveLength(1);
  expect(within(screen.getByRole('region', { name: 'История сдач' })).getByText('Нет тестов')).toBeVisible();
});
it.each([['/tasks/999999', 404, 'Не найдено', 'Проверьте адрес страницы.'], ['/tasks/7001', 403, 'Нет доступа', 'Этот раздел недоступен для вашей роли.'], ['/checkpoints/999999', 404, 'Не найдено', 'Проверьте адрес страницы.'], ['/checkpoints/501', 403, 'Нет доступа', 'Этот раздел недоступен для вашей роли.']] as const)('R37: %s → %s', async (path, status, title, text) => {
  server.use(http.get(`*/api/v1/${path.startsWith('/tasks') ? 'assignments' : 'checkpoints'}/:id`, () => problem(status, status === 404 ? 'NOT_FOUND' : 'FORBIDDEN')));
  renderApp(path, teacher);
  expect(await screen.findByRole('heading', { name: title })).toBeInTheDocument();
  expect(screen.getByText(text)).toBeInTheDocument();
});
it.each(['/checkpoints/new', '/checkpoints/501'])('R37: студенту закрыт экран %s', async (path) => {
  renderApp(path, student);
  expect(await screen.findByRole('heading', { name: 'Нет доступа' })).toBeInTheDocument();
});
it('R18/R20/R35: новая точка без групп и обязательных полей', async () => {
  server.use(http.get('*/api/v1/me/accessible-groups', () => HttpResponse.json(example('listAccessibleGroups'))));
  renderApp('/checkpoints/new', teacher); const user = userEvent.setup();
  const title = await screen.findByRole('textbox', { name: /Название/ });
  expect(title.closest('form')).toHaveAttribute('novalidate');
  await user.click(screen.getByRole('button', { name: 'Создать точку' }));
  expect(await screen.findByText('Выберите хотя бы одну группу')).toBeInTheDocument();
  expect(title).toHaveAttribute('aria-invalid', 'true');
  expect(screen.getByRole('group', { name: /Группы/ })).toHaveAttribute('aria-invalid', 'true');
});
it('R36: счётчики появляются после загрузки строк', async () => {
  let release!: () => void;
  const pending = new Promise<void>((resolve) => { release = resolve; });
  server.use(http.get('*/api/v1/checkpoints/:id/assignments', async () => { await pending; return HttpResponse.json(example('listCheckpointAssignments')); }));
  renderApp('/checkpoints/501', teacher);
  expect(await screen.findByRole('tab', { name: 'Просрочено' })).toBeInTheDocument();
  release();
  expect(await screen.findByRole('tab', { name: /Просрочено · \d/ })).toBeInTheDocument();
});
it.each([360, 1280])('R22–R24/R32/R39: меню, выбор и панель, %s px', async (width) => {
  setViewportWidth(width);
  const page = example<Schemas['CheckpointAssignmentPage']>('listCheckpointAssignments');
  page.nextCursor = null;
  page.items = ['SUBMITTED', 'OPEN', 'ACCEPTED', 'SUBMITTED', 'SUBMITTED', 'OPEN'].map((state, i) => ({ ...page.items[0]!, assignmentId: i + 1, student: { id: i + 1, fullName: `Студент ${i + 1}` }, state: state as Schemas['AssignmentState'], status: state === 'OPEN' ? (i === 1 ? 'OVERDUE' : 'IN_PROGRESS') : state as Schemas['AssignmentStatus'], submittedAt: state === 'OPEN' ? null : serverNow }));
  const empty = assignment(); empty.submissions = [];
  server.use(http.get('*/api/v1/checkpoints/:id/assignments', () => HttpResponse.json(page)), http.get('*/api/v1/assignments/:id', () => HttpResponse.json(empty)));
  renderApp('/checkpoints/501', teacher); const user = userEvent.setup();
  expect(await screen.findByRole('checkbox', { name: 'Студент 2' })).toBeDisabled();
  expect(screen.getByRole('checkbox', { name: 'Студент 3' })).toBeDisabled();
  expect(screen.getByRole('checkbox', { name: 'Студент 6' })).toBeDisabled();
  expect(screen.getByText('Студент 3')).toHaveStyle({ color: 'var(--cr-text)' });
  expect(within(screen.getAllByRole('cell', { name: 'Ещё не сдано' })[0]!).queryByRole('button')).not.toBeInTheDocument();
  expect(screen.getByText('Студент 2')).toHaveStyle({ color: 'var(--cr-text)' });
  expect(screen.queryByRole('button', { name: 'Действия: Студент 2' })).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Действия: Студент 1' }));
  expect(await screen.findByRole('menuitem', { name: 'Открыть сдачу' })).toBeVisible();
  expect(screen.getByRole('menuitem', { name: 'Вернуть…' })).toBeVisible();
  await user.keyboard('{Escape}');
  await user.click(screen.getByRole('button', { name: 'Действия: Студент 3' }));
  expect(await screen.findByRole('menuitem', { name: 'Открыть сдачу' })).toBeVisible();
  expect(screen.queryByRole('menuitem', { name: 'Вернуть…' })).not.toBeInTheDocument();
  await user.keyboard('{Escape}');
  await user.click(screen.getByRole('checkbox', { name: 'Студент 1' }));
  expect(screen.getByText('Выбрано 1')).toBeInTheDocument();
  expect(screen.getByRole('region', { name: 'Выбор работ' })).toHaveStyle({ position: width < 768 ? 'fixed' : 'static' });
  await user.click(screen.getByRole('button', { name: 'Снять выбор' }));
  expect(screen.queryByText('Выбрано 1')).not.toBeInTheDocument();
});
it('R22/R23: меню у отозванной версии без даты сдачи', async () => {
  const page = example<Schemas['CheckpointAssignmentPage']>('listCheckpointAssignments');
  page.items = [{ ...page.items[0]!, assignmentId: 7001, state: 'OPEN', status: 'IN_PROGRESS', submittedAt: null, submissionKind: null }]; page.nextCursor = null;
  const a = assignment(); a.submissions = [{ id: 1, kind: 'LINK', url: 'https://example.com', submittedAt: serverNow, returnComment: null, returnedAt: null, withdrawnAt: serverNow }];
  serveAssignment(a); server.use(http.get('*/api/v1/checkpoints/:id/assignments', () => HttpResponse.json(page)));
  renderApp('/checkpoints/501', teacher); const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: `Действия: ${page.items[0]!.student.fullName}` }));
  expect(await screen.findByRole('menuitem', { name: 'Открыть сдачу' })).toBeVisible();
  expect(screen.queryByRole('menuitem', { name: 'Вернуть…' })).not.toBeInTheDocument();
  await user.click(screen.getByRole('menuitem', { name: 'Открыть сдачу' }));
  const dialog = await screen.findByRole('dialog', { name: 'Материалы сдачи' });
  expect(await within(dialog).findByText('Отозвана студентом · 11 нояб. 2026 г., 23:00 (МСК)')).toBeVisible();
});
it.each([4, 20])('R3: шаг/интервал %s из точки', async (step) => {
  const cp = example<Schemas['Checkpoint']>('getCheckpoint'); cp.penaltyStep = step; cp.penaltyIntervalSeconds = step === 4 ? 4 : 60;
  server.use(http.get('*/api/v1/checkpoints/:id', () => HttpResponse.json(cp)));
  renderApp('/checkpoints/501', teacher);
  expect(await screen.findByText(`Штраф: ${step} АР каждые ${cp.penaltyIntervalSeconds} с`)).toBeInTheDocument();
});
it('R18/R20: правка названия без браузерной валидации', async () => {
  renderApp('/checkpoints/501', teacher); const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Редактировать описание' }));
  const title = screen.getByRole('textbox', { name: /Название/ });
  expect(title.closest('form')).toHaveAttribute('novalidate');
  await user.clear(title); await user.click(screen.getByRole('button', { name: 'Сохранить' }));
  expect(title).toHaveAttribute('aria-invalid', 'true');
});

it.each([360, 768, 1280])('R33–R35: пилюля, ширина кнопки и обязательность, %s px', async (width) => {
  setViewportWidth(width); serveAssignment(assignment()); renderApp('/tasks/7001', student);
  const input = await screen.findByRole('textbox', { name: /Ссылка на работу/ });
  expect(input).toBeRequired();
  expect(screen.getByText('В процессе')).toHaveStyle({ alignSelf: 'flex-start' });
  expect(screen.getByRole('button', { name: 'Сдать работу' })).toHaveStyle({ width: width < 768 ? '100%' : 'auto' });
});
it.each([360, 768, 1280])('R35: ширина и обязательные поля новой точки, %s px', async (width) => {
  setViewportWidth(width);
  server.use(http.get('*/api/v1/me/accessible-groups', () => HttpResponse.json(example('listAccessibleGroups'))));
  renderApp('/checkpoints/new', teacher);
  const title = await screen.findByRole('textbox', { name: /Название/ });
  expect(title.closest('form')?.parentElement).toHaveStyle({ maxWidth: '44rem' });
  expect(screen.getByRole('group', { name: /Группы/ })).toHaveAttribute('aria-required', 'true');
  expect(screen.getByLabelText(/Дедлайн/)).toBeRequired();
  expect(screen.getByRole('spinbutton', { name: /Стоимость/ })).toBeRequired();
  expect(screen.getAllByText('*')).toHaveLength(4);
  expect(screen.getByRole('button', { name: 'Создать точку' })).toHaveStyle({ width: width < 768 ? '100%' : 'auto' });
});
it('R4: время сервера определяет доступность при неверных часах клиента', async () => {
  const a = assignment('SUBMITTED');
  a.checkpoint.deadline = '2000-01-02T00:00:00Z';
  server.use(http.get('*/api/v1/assignments/:id', () => HttpResponse.json(a, { headers: { Date: '2000-01-01T00:00:00Z' } })));
  renderApp('/tasks/7001', student);
  expect(await screen.findByRole('button', { name: 'Отозвать сдачу' })).toBeEnabled();
});
it.each([403, 404])('R37: ответ %s без ProblemDetail', async (status) => {
  server.use(http.get('*/api/v1/assignments/:id', () => new HttpResponse('<html>Error</html>', { status, headers: { 'Content-Type': 'text/html' } })));
  renderApp('/tasks/7001', student);
  expect(await screen.findByRole('heading', { name: status === 403 ? 'Нет доступа' : 'Не найдено' })).toBeInTheDocument();
});
it.each([360, 768, 1280])('R34: кнопки точки и форм, %s px', async (width) => {
  setViewportWidth(width);
  renderApp('/checkpoints/501', teacher); const user = userEvent.setup();
  const edit = await screen.findByRole('button', { name: 'Редактировать описание' });
  const expected = { width: width < 768 ? '100%' : 'auto' };
  expect(edit).toHaveStyle(expected);
  await user.click(edit);
  expect(screen.getByRole('button', { name: 'Сохранить' })).toHaveStyle(expected);
  await user.click(screen.getByRole('button', { name: 'Отмена' }));
  await user.click((await screen.findAllByRole('button', { name: /Действия:/ }))[0]!);
  await user.click(await screen.findByRole('menuitem', { name: 'Вернуть…' }));
  expect(await screen.findByRole('button', { name: 'Вернуть работу' })).toHaveStyle(expected);
});
it('R20: серверная ошибка ссылки остаётся под полем', async () => {
  serveAssignment(assignment());
  server.use(http.post('*/api/v1/assignments/:id/submission', () => HttpResponse.json({ type: 'about:blank', title: 'Validation', status: 400, code: 'VALIDATION_FAILED', fieldErrors: [{ field: 'link', code: 'INVALID', message: 'Укажите ссылку http или https длиной до 2000 символов' }] }, { status: 400, headers: { 'Content-Type': 'application/problem+json' } })));
  renderApp('/tasks/7001', student); const user = userEvent.setup();
  await user.type(await screen.findByRole('textbox', { name: /Ссылка на работу/ }), 'https://example.com');
  await user.click(screen.getByRole('button', { name: 'Сдать работу' }));
  expect(await screen.findByText('Укажите ссылку http или https длиной до 2000 символов')).toBeInTheDocument();
  expect(screen.getByRole('textbox', { name: /Ссылка на работу/ })).toHaveAttribute('aria-invalid', 'true');
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
});
it('R4: кнопка исчезает ровно на дедлайне по отсчёту сервера', async () => {
  const clock = vi.spyOn(taskClock, 'monotonicNow').mockReturnValue(0);
  try {
    const a = assignment('SUBMITTED');
    a.checkpoint.deadline = '2026-11-11T20:00:01Z';
    serveAssignment(a);
    renderApp('/tasks/7001', student);
    expect(await screen.findByRole('button', { name: 'Отозвать сдачу' })).toBeEnabled();
    clock.mockReturnValue(1000);
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Отозвать сдачу' })).not.toBeInTheDocument());
  } finally {
    clock.mockRestore();
  }
});

it.each(['Петров Павел Петрович', 'Петров Павел'])('R21: после приёма замечание видно только в истории; автор %s', async (fullName) => {
  const a = assignment('ACCEPTED');
  a.checkpoint.author.fullName = fullName;
  a.submission!.id = 2;
  a.submissions.push({ id: 1, kind: 'LINK', url: 'https://example.com/old', submittedAt: serverNow, returnComment: 'Нет тестов', returnedAt: '2026-11-11T19:31:00Z', withdrawnAt: null });
  serveAssignment(a);
  renderApp('/tasks/7001', student);
  const history = await screen.findByRole('region', { name: 'История сдач' });
  const initials = fullName === 'Петров Павел' ? 'Петров П.' : 'Петров П. П.';
  expect(within(history).getByText(`Замечание преподавателя · ${initials} · 11 нояб., 22:31`)).toBeVisible();
  expect(screen.getAllByText('Нет тестов')).toHaveLength(1);
  expect(within(history).getByText('Нет тестов')).toBeVisible();
  expect(screen.queryByText('Возвращена с замечанием')).not.toBeInTheDocument();
});
it('R10: выбранная работа отозвана — приём 0 из 1 с причиной пропуска', async () => {
  const page = example<Schemas['CheckpointAssignmentPage']>('listCheckpointAssignments');
  const row = { ...page.items[0]!, assignmentId: 7001, state: 'SUBMITTED' as Schemas['AssignmentState'], status: 'SUBMITTED' as Schemas['AssignmentStatus'] };
  page.items = [row]; page.nextCursor = null;
  const a = assignment();
  a.submissions = [{ id: 1, kind: 'LINK', url: 'https://example.com', submittedAt: serverNow, returnComment: null, returnedAt: null, withdrawnAt: serverNow }];
  serveAssignment(a);
  server.use(
    http.get('*/api/v1/checkpoints/:id/assignments', () => HttpResponse.json(page)),
    http.post('*/api/v1/checkpoints/:id/assignments/accept', async ({ request }) => {
      expect(await request.json()).toEqual({ assignmentIds: [7001] });
      row.state = 'OPEN'; row.status = 'IN_PROGRESS'; row.submittedAt = null; row.submissionKind = null;
      return HttpResponse.json({ accepted: [], skipped: [{ assignmentId: 7001, code: 'INVALID_STATE' }] });
    }),
  );
  renderApp('/checkpoints/501', teacher); const user = userEvent.setup();
  await user.click(await screen.findByRole('checkbox', { name: row.student.fullName }));
  await user.click(screen.getByRole('button', { name: 'Принять 1 работу' }));
  expect(await screen.findByText('Принято 0 из 1')).toBeVisible();
  expect(screen.getByText(/пропущен: состояние работы изменилось, принять можно только сданную работу/)).toBeVisible();
  await waitFor(() => expect(screen.getByRole('checkbox', { name: row.student.fullName })).toBeDisabled());
});

it.each([4, 20])('R3: студент видит шаг %s из назначения', async (step) => {
  const a = assignment(); a.penalty.step = step; a.penalty.nextAmount = step;
  serveAssignment(a); renderApp('/tasks/7001', student);
  expect(await screen.findByText(`До дедлайна штрафа нет; после — до −${step} АР за каждый интервал`)).toBeVisible();
});

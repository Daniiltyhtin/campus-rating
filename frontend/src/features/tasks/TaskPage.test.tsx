import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server } from '../../mocks/server';
import { renderApp } from '../../test/render';
import { example, type Schemas } from './fixtures';
beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
const student = { userId: '1001', role: 'STUDENT' as const };
it.each([
  ['BEFORE_DEADLINE', 'До дедлайна штрафа нет; после — до −20 АР за каждый интервал'],
  ['RUNNING', 'Следующее списание −20 АР — в ближайшую минуту'],
  ['ZERO_BALANCE', 'Рейтинг 0: списание возобновится после пополнения'],
  ['CAP_REACHED', 'Списано 40 из 60, больше не спишется'],
  ['STOPPED', 'Штраф остановлен, списано 40'],
] as const)('фаза %s определяет текст', async (phase, text) => {
  const assignment = example<Schemas['Assignment']>('getAssignment');
  assignment.penalty = {
    ...assignment.penalty,
    phase,
    charged: 40,
    cap: 60,
    step: 20,
    nextAmount: 20,
    nextAt: '2020-01-01T00:00:00Z',
  };
  server.use(http.get('*/api/v1/assignments/:id', () => HttpResponse.json(assignment)));
  renderApp('/tasks/7001', student);
  expect(await screen.findByText(text)).toBeInTheDocument();
});
it('415 показана у поля файла', async () => {
  server.use(
    http.post('*/api/v1/assignments/:id/submission', () =>
      HttpResponse.json(
        { type: 'about:blank', title: 'Unsupported Media Type', status: 415, code: 'FILE_TYPE_NOT_ALLOWED' },
        { status: 415, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    ),
  );
  renderApp('/tasks/7001', student);
  const user = userEvent.setup({ applyAccept: false });
  await user.click(await screen.findByRole('tab', { name: 'Файл' }));
  await user.upload(screen.getByLabelText(/Файл работы/, { selector: 'input' }), new File(['x'], 'work.pdf'));
  await user.click(screen.getByRole('button', { name: 'Сдать работу' }));
  expect(await screen.findByText('Можно pdf, docx, zip, png или jpg')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /Файл работы/ })).toHaveAttribute('aria-invalid', 'true');
});
it('история сохраняет порядок версий и адрес скачивания', async () => {
  const assignment = example<Schemas['Assignment']>('getAssignment');
  assignment.submissions = [
    {
      id: 2,
      kind: 'LINK',
      url: 'https://example.org/new',
      submittedAt: '2026-11-10T12:00:00Z',
      returnComment: null,
      returnedAt: null,
      withdrawnAt: null,
    },
    {
      id: 1,
      kind: 'FILE',
      fileName: 'old.pdf',
      contentType: 'application/pdf',
      sizeBytes: 20,
      submittedAt: '2026-11-09T12:00:00Z',
      returnComment: 'Дополните',
      returnedAt: '2026-11-10T00:00:00Z',
      withdrawnAt: null,
    },
  ];
  server.use(http.get('*/api/v1/assignments/:id', () => HttpResponse.json(assignment)));
  renderApp('/tasks/7001', student);
  const history = await screen.findByRole('region', { name: 'История сдач' });
  const links = within(history).getAllByRole('link');
  expect(links[0]).toHaveAttribute('href', 'https://example.org/new');
  expect(links[1]).toHaveAttribute('href', '/api/v1/assignments/7001/submissions/1/file');
  expect(links[1]).toHaveAttribute('download');
  expect(history).toHaveTextContent('Дополните');
});
it('сдаёт multipart ссылку и перечитывает назначение с остановленным штрафом', async () => {
  let submitted = false;
  const initial = example<Schemas['Assignment']>('getAssignment');
  const stopped = example<Schemas['Assignment']>('submitAssignment');
  server.use(
    http.get('*/api/v1/assignments/:id', () => HttpResponse.json(submitted ? stopped : initial)),
    http.post('*/api/v1/assignments/:id/submission', async ({ request }) => {
      const form = await request.formData();
      expect(form.get('link')).toBe('https://example.org/work');
      expect(form.has('file')).toBe(false);
      submitted = true;
      return HttpResponse.json(stopped);
    }),
  );
  renderApp('/tasks/7001', student);
  const user = userEvent.setup();
  await user.type(await screen.findByRole('textbox', { name: 'Ссылка на работу' }), 'https://example.org/work');
  await user.click(screen.getByRole('button', { name: 'Сдать работу' }));
  expect(await screen.findByText(`Штраф остановлен, списано ${stopped.penalty.charged}`)).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Сдать работу' })).not.toBeInTheDocument();
});
it('отсчёт использует nextAt и nextAmount сервера', async () => {
  const assignment = example<Schemas['Assignment']>('getAssignment');
  assignment.penalty = {
    ...assignment.penalty,
    phase: 'RUNNING',
    nextAmount: 7,
    nextAt: new Date(Date.now() + 42000).toISOString(),
  };
  server.use(http.get('*/api/v1/assignments/:id', () => HttpResponse.json(assignment)));
  renderApp('/tasks/7001', student);
  expect(await screen.findByText(/Следующее списание −7 АР через 0:4[12]/)).toBeInTheDocument();
});
it.each([
  [413, { code: 'FILE_TOO_LARGE' }, 'Файл больше 10 МБ'],
  [
    400,
    {
      code: 'VALIDATION_FAILED',
      fieldErrors: [{ field: 'file', code: 'INVALID', message: 'Не удалось прочитать файл' }],
    },
    'Не удалось прочитать файл',
  ],
])('ошибка %s относится к полю файла', async (status, problem, message) => {
  server.use(
    http.post('*/api/v1/assignments/:id/submission', () =>
      HttpResponse.json(
        { type: 'about:blank', title: 'Error', status, ...problem },
        { status, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    ),
  );
  renderApp('/tasks/7001', student);
  const user = userEvent.setup();
  await user.click(await screen.findByRole('tab', { name: 'Файл' }));
  await user.upload(screen.getByLabelText(/Файл работы/, { selector: 'input' }), new File(['x'], 'work.pdf', { type: 'application/pdf' }));
  await user.click(screen.getByRole('button', { name: 'Сдать работу' }));
  expect(await screen.findByText(message)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /Файл работы/ })).toHaveAttribute('aria-invalid', 'true');
});
it('предел версий сдачи показан текстом сервера', async () => {
  const detail = 'Достигнут предел: 20 сдач по одной задаче. Обратитесь к преподавателю.';
  server.use(
    http.post('*/api/v1/assignments/:id/submission', () =>
      HttpResponse.json(
        { type: 'about:blank', title: 'Conflict', status: 409, code: 'SUBMISSION_LIMIT_REACHED', detail },
        { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    ),
  );
  renderApp('/tasks/7001', student);
  const user = userEvent.setup();
  await user.type(await screen.findByRole('textbox', { name: 'Ссылка на работу' }), 'https://example.org/work');
  await user.click(screen.getByRole('button', { name: 'Сдать работу' }));
  expect(await screen.findByRole('alert')).toHaveTextContent(detail);
});

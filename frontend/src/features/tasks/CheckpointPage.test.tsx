import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server } from '../../mocks/server';
import { renderApp } from '../../test/render';
import { example, type Schemas } from './fixtures';
beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
const teacher = { userId: '2001', role: 'TEACHER' as const };

function rows() {
  const page = example<Schemas['CheckpointAssignmentPage']>('listCheckpointAssignments');
  page.items = [1, 2, 3].map((id) => ({
    ...page.items[0]!,
    assignmentId: id,
    student: { id, fullName: `Студент ${id}` },
    state: 'SUBMITTED',
    status: 'SUBMITTED',
  }));
  page.nextCursor = null;
  server.use(http.get('*/api/v1/checkpoints/:id/assignments', () => HttpResponse.json(page)));
}
it('выбор трёх → принято два и причина пропуска', async () => {
  rows();
  server.use(
    http.post('*/api/v1/checkpoints/:id/assignments/accept', async ({ request }) => {
      expect(await request.json()).toEqual({ assignmentIds: [1, 2, 3] });
      return HttpResponse.json({ accepted: [1, 2], skipped: [{ assignmentId: 3, code: 'INVALID_STATE' }] });
    }),
  );
  renderApp('/checkpoints/501', teacher);
  const user = userEvent.setup();
  for (const checkbox of await screen.findAllByRole('checkbox')) await user.click(checkbox);
  await user.click(screen.getByRole('button', { name: 'Принять 3 работы' }));
  expect(await screen.findByText('Принято 2 из 3')).toBeInTheDocument();
  expect(screen.getByText(/Студент 3.*пропущен.*состояние/i)).toBeInTheDocument();
});
it('возврат требует непустой комментарий', async () => {
  rows();
  renderApp('/checkpoints/501', teacher);
  const user = userEvent.setup();
  await user.click((await screen.findAllByRole('button', { name: 'Вернуть…' }))[0]!);
  await user.click(await screen.findByRole('button', { name: 'Вернуть работу' }));
  expect(await screen.findByText('Укажите комментарий')).toBeInTheDocument();
  expect(screen.getByRole('dialog')).toBeInTheDocument();
});
it('возврат отправляет комментарий и перечитывает список', async () => {
  rows();
  let returned = false;
  let reads = 0;
  const page = example<Schemas['CheckpointAssignmentPage']>('listCheckpointAssignments');
  page.nextCursor = null;
  server.use(
    http.get('*/api/v1/checkpoints/:id/assignments', () => {
      reads++;
      return HttpResponse.json(page);
    }),
    http.post('*/api/v1/assignments/:id/return', async ({ request }) => {
      expect(await request.json()).toEqual({ comment: 'Дополните тесты' });
      returned = true;
      return HttpResponse.json(example<Schemas['Assignment']>('returnAssignment'));
    }),
  );
  renderApp('/checkpoints/501', teacher);
  const user = userEvent.setup();
  await user.click((await screen.findAllByRole('button', { name: 'Вернуть…' }))[0]!);
  await user.type(await screen.findByRole('textbox', { name: 'Комментарий возврата' }), 'Дополните тесты');
  await user.click(screen.getByRole('button', { name: 'Вернуть работу' }));
  const { waitFor } = await import('@testing-library/react');
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(returned).toBe(true);
  expect(reads).toBeGreaterThan(1);
});
it('правка отправляет только название и описание и обновляет заголовок', async () => {
  rows();
  const checkpoint = example<Schemas['Checkpoint']>('getCheckpoint');
  server.use(
    http.get('*/api/v1/checkpoints/:id', () => HttpResponse.json(checkpoint)),
    http.patch('*/api/v1/checkpoints/:id', async ({ request }) => {
      const body = await request.json();
      expect(body).toEqual({ title: 'Новое название', description: checkpoint.description });
      checkpoint.title = 'Новое название';
      return HttpResponse.json(checkpoint);
    }),
  );
  renderApp('/checkpoints/501', teacher);
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Редактировать описание' }));
  const title = screen.getByRole('textbox', { name: 'Название' });
  await user.clear(title);
  await user.type(title, 'Новое название');
  await user.click(screen.getByRole('button', { name: 'Сохранить' }));
  expect(await screen.findByRole('heading', { level: 1, name: 'Новое название' })).toBeInTheDocument();
});
it('фильтры передаются серверу, открытие сдачи сохраняет выбор', async () => {
  const page = example<Schemas['CheckpointAssignmentPage']>('listCheckpointAssignments');
  page.nextCursor = null;
  const row = { ...page.items[0]!, state: 'SUBMITTED' as const, status: 'SUBMITTED' as const };
  page.items = [row];
  const requested: URL[] = [];
  server.use(
    http.get('*/api/v1/checkpoints/:id/assignments', ({ request }) => {
      requested.push(new URL(request.url));
      return HttpResponse.json(page);
    }),
  );
  renderApp('/checkpoints/501', teacher);
  const user = userEvent.setup();
  await user.click(await screen.findByRole('tab', { name: /Сдано на проверку/ }));
  await user.selectOptions(screen.getByLabelText('Группа'), '11');
  const checkbox = await screen.findByRole('checkbox', { name: row.student.fullName });
  await user.click(checkbox);
  await user.click(screen.getByRole('button', { name: 'Открыть сдачу' }));
  expect(await screen.findByRole('dialog')).toBeInTheDocument();
  await user.keyboard('{Escape}');
  const { waitFor } = await import('@testing-library/react');
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(screen.getByRole('checkbox', { name: row.student.fullName })).toBeChecked();
  expect(
    requested.some((url) => url.searchParams.get('status') === 'SUBMITTED' && url.searchParams.get('groupId') === '11'),
  ).toBe(true);
});

it('смена фильтра статуса или группы сбрасывает выбор работ', async () => {
  rows();
  renderApp('/checkpoints/501', teacher);
  const user = userEvent.setup();
  await user.click(await screen.findByRole('checkbox', { name: 'Студент 1' }));
  expect(screen.getByText('Выбрано 1')).toBeInTheDocument();
  await user.click(screen.getByRole('tab', { name: /Сдано на проверку/ }));
  expect(await screen.findByRole('checkbox', { name: 'Студент 1' })).not.toBeChecked();
  expect(screen.queryByText('Выбрано 1')).not.toBeInTheDocument();
  await user.click(screen.getByRole('checkbox', { name: 'Студент 2' }));
  await user.selectOptions(screen.getByLabelText('Группа'), '11');
  expect(await screen.findByRole('checkbox', { name: 'Студент 2' })).not.toBeChecked();
});

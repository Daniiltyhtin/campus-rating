import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server } from '../../mocks/server';
import { renderApp } from '../../test/render';
import { example, type Schemas } from './fixtures';
beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
it('создаёт точку с группой и дедлайном по Москве', async () => {
  let received: unknown;
  const checkpoint = example<Schemas['Checkpoint']>('createCheckpoint');
  server.use(
    http.get('*/api/v1/me/accessible-groups', () => HttpResponse.json(example('listAccessibleGroups'))),
    http.post('*/api/v1/checkpoints', async ({ request }) => {
      received = await request.json();
      return HttpResponse.json(checkpoint, { status: 201 });
    }),
  );
  const router = renderApp('/checkpoints/new', { userId: '2001', role: 'TEACHER' });
  const user = userEvent.setup();
  await user.type(await screen.findByRole('textbox', { name: 'Название' }), 'Новая работа');
  await user.click(screen.getByRole('checkbox', { name: 'ИВТ-21' }));
  const { fireEvent } = await import('@testing-library/react');
  fireEvent.change(screen.getByLabelText(/Дедлайн/), { target: { value: '2027-01-01T12:00' } });
  expect(screen.getByText(/Шаг штрафа:/)).toHaveTextContent('20 АР');
  await user.click(screen.getByRole('button', { name: 'Создать точку' }));
  expect(await screen.findByRole('button', { name: 'Редактировать описание' })).toBeInTheDocument();
  expect(received).toEqual({
    title: 'Новая работа',
    description: '',
    groupIds: [11],
    deadline: '2027-01-01T09:00:00.000Z',
    cost: 60,
  });
  expect(router.state.location.pathname).toBe(`/checkpoints/${checkpoint.id}`);
});

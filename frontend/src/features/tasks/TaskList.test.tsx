import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../mocks/server';
import { renderApp } from '../../test/render';
import { setViewportWidth } from '../../test/setup';
import { example, type Schemas } from './fixtures';
beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
const student = { userId: '1001', role: 'STUDENT' as const };
it('показывает OVERDUE первым в порядке сервера на 360 px', async () => {
  setViewportWidth(360);
  renderApp('/tasks', student);
  const tasks = example<Schemas['StudentTaskPage']>('listMyAssignments');
  expect(await screen.findByRole('link', { name: tasks.items[0]!.checkpoint.title })).toBeInTheDocument();
  const articles = screen.getAllByRole('article');
  expect(articles[0]).toHaveTextContent('Просрочено');
  expect(articles).toHaveLength(tasks.items.length);
  for (const element of document.querySelectorAll('*'))
    expect(element.getBoundingClientRect().width).toBeLessThanOrEqual(360);
});
it.each(['PENDING', 'ACTIVE'] as const)('пустое членство %s', async (membershipState) => {
  server.use(
    http.get('*/api/v1/me/assignments', () => HttpResponse.json({ items: [], nextCursor: null, membershipState })),
  );
  renderApp('/tasks', student);
  expect(
    await screen.findByText(
      membershipState === 'PENDING'
        ? 'Членство ждёт подтверждения'
        : 'Новых задач нет. Когда преподаватель создаст точку для вашей группы, она появится здесь',
    ),
  ).toBeInTheDocument();
});
it('завершённые подгружаются по курсору без потери первой страницы', async () => {
  const page = example<Schemas['StudentTaskPage']>('listMyAssignments');
  server.use(
    http.get('*/api/v1/me/assignments', ({ request }) => {
      const url = new URL(request.url);
      if (url.searchParams.get('view') === 'active') return HttpResponse.json(page);
      const second = url.searchParams.get('cursor') === 'next';
      return HttpResponse.json({
        ...page,
        items: [
          {
            ...page.items[0]!,
            assignmentId: second ? 2 : 1,
            checkpoint: { id: 1, title: second ? 'Вторая завершённая' : 'Первая завершённая' },
            state: 'ACCEPTED',
            status: 'ACCEPTED',
          },
        ],
        nextCursor: second ? null : 'next',
      });
    }),
  );
  renderApp('/tasks', student);
  const { default: userEvent } = await import('@testing-library/user-event');
  const user = userEvent.setup();
  await user.click(await screen.findByRole('tab', { name: 'Завершённые' }));
  expect(await screen.findByRole('link', { name: 'Первая завершённая' })).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Загрузить ещё' }));
  expect(await screen.findByRole('link', { name: 'Вторая завершённая' })).toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Первая завершённая' })).toBeInTheDocument();
});
it('ошибка загрузки позволяет повторить запрос', async () => {
  let failed = true;
  server.use(
    http.get('*/api/v1/me/assignments', () =>
      failed ? HttpResponse.error() : HttpResponse.json({ items: [], nextCursor: null, membershipState: 'PENDING' }),
    ),
  );
  renderApp('/tasks', student);
  expect(await screen.findByRole('alert')).toBeInTheDocument();
  failed = false;
  const { default: userEvent } = await import('@testing-library/user-event');
  await userEvent.setup().click(screen.getByRole('button', { name: 'Повторить' }));
  expect(await screen.findByText('Членство ждёт подтверждения')).toBeInTheDocument();
});

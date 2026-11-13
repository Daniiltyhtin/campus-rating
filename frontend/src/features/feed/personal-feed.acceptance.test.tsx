import { cleanup, configure, getConfig, screen, waitFor } from '@testing-library/react';
import { http } from 'msw';
import type { components } from '../../api/schema';
import { server } from '../../mocks/server';
import { authUsers, createFeedScenario, disposeFeedTests, feedJson, mountFeed } from '../../test/feedAcceptance';

type Item = components['schemas']['PersonalFeedItem'];
type Page = components['schemas']['PersonalFeedPage'];

const originalTimeout = getConfig().asyncUtilTimeout;
beforeAll(() => { configure({ asyncUtilTimeout: 3_000 }); server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => {
  cleanup(); disposeFeedTests(); server.resetHandlers(); localStorage.clear();
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/';
  document.cookie = 'campus-mock-user=; Max-Age=0; Path=/';
});
afterAll(() => { server.close(); configure({ asyncUtilTimeout: originalTimeout }); });

const everyKind: Item[] = [
  { id: 7, kind: 'POINTS_AWARDED', createdAt: '2026-11-21T12:00:00Z', activityId: 601, activityTitle: 'Субботник', amount: 5, currency: 'BOA' },
  { id: 6, kind: 'ACTIVITY_CREATED', createdAt: '2026-11-19T08:00:00Z', activityId: 601, activityTitle: 'Субботник' },
  { id: 5, kind: 'WORK_ACCEPTED', createdAt: '2026-11-18T16:00:00Z', assignmentId: 7001, checkpointTitle: 'Эссе' },
  { id: 4, kind: 'WORK_RETURNED', createdAt: '2026-11-18T12:00:00Z', assignmentId: 7001, checkpointTitle: 'Эссе' },
  { id: 3, kind: 'DEADLINE_REMINDER', createdAt: '2026-11-17T09:00:00Z', assignmentId: 7002, checkpointTitle: 'Реферат', deadline: '2026-11-18T06:00:00Z' },
  { id: 2, kind: 'WORK_SUBMITTED', createdAt: '2026-11-16T10:00:00Z', checkpointId: 501, checkpointTitle: 'Эссе', studentFullName: 'Смирнова Анна Олеговна' },
  { id: 1, kind: 'ASSIGNMENT_CREATED', createdAt: '2026-11-13T09:00:00Z', assignmentId: 7001, checkpointTitle: 'Эссе', deadline: '2026-11-20T15:00:00Z' },
];

function personal(pages: Record<string, Page | (() => Response)>) {
  const requests: string[] = [];
  server.use(http.get('*/api/v1/feed/personal', ({ request }) => {
    const cursor = new URL(request.url).searchParams.get('cursor') ?? '';
    requests.push(cursor);
    const page = pages[cursor] ?? { items: [], nextCursor: null };
    return typeof page === 'function' ? page() : feedJson(page);
  }));
  return requests;
}
function rows(count: number, offset = 0): Item[] {
  return Array.from({ length: count }, (_, index) => ({ id: 1000 - offset - index, kind: 'ACTIVITY_CREATED',
    createdAt: new Date(Date.UTC(2026, 10, 18, 9, 0, -(offset + index))).toISOString(), activityId: 600 + offset + index,
    activityTitle: `Активность №${offset + index + 1}` }));
}

describe('U14 персональная лента — R1/R2/R4/R6', { timeout: 12_000 }, () => {
  it('показывает тексты, ссылки и московское время всех семи типов, новые сверху', async () => {
    createFeedScenario(authUsers.student);
    personal({ '': { items: everyKind, nextCursor: null } });
    mountFeed('/');
    expect(await screen.findByRole('heading', { name: 'Главная' })).toBeInTheDocument();
    const expected: [string, string][] = [
      ['Начислено 5 БОА за «Субботник»', `/users/${authUsers.student.id}`],
      ['Новая активность «Субботник» для вашей группы', '/activities'],
      ['Работа «Эссе» принята', '/tasks/7001'],
      ['Работу «Эссе» вернули на доработку', '/tasks/7001'],
      ['До дедлайна «Реферат» меньше суток', '/tasks/7002'],
      ['Сдана работа «Эссе» · Смирнова Анна Олеговна', '/checkpoints/501'],
      ['Новая задача «Эссе» · до 20.11.2026 18:00', '/tasks/7001'],
    ];
    const links = await screen.findAllByRole('link', { name: /Начислено|Новая|Работа|Работу|До дедлайна|Сдана/ });
    expect(links.map((link) => [link.textContent, link.getAttribute('href')])).toEqual(expected);
    expect(screen.getByText('21.11.2026 15:00')).toHaveAttribute('dateTime', '2026-11-21T12:00:00Z');
    expect(screen.getByText('13.11.2026 12:00')).toBeInTheDocument();
  });

  it('догружает по курсору без дублей и прячет кнопку на последней странице', async () => {
    createFeedScenario(authUsers.student);
    const first = rows(20), second = [first[19]!, ...rows(5, 20)];
    const requests = personal({ '': { items: first, nextCursor: 'page-2' }, 'page-2': { items: second, nextCursor: null } });
    const view = mountFeed('/');
    expect(await screen.findByText('Новая активность «Активность №20» для вашей группы')).toBeInTheDocument();
    expect(screen.getAllByRole('article')).toHaveLength(20);
    await view.user.click(screen.getByRole('button', { name: 'Загрузить ещё' }));
    expect(await screen.findByText('Новая активность «Активность №25» для вашей группы')).toBeInTheDocument();
    expect(screen.getAllByRole('article')).toHaveLength(25);
    expect(screen.queryByRole('button', { name: 'Загрузить ещё' })).not.toBeInTheDocument();
    expect(requests).toEqual(['', 'page-2']);
  });

  it('пустая лента — «Событий пока нет»', async () => {
    createFeedScenario(authUsers.teacher);
    personal({ '': { items: [], nextCursor: null } });
    mountFeed('/');
    expect(await screen.findByText('Событий пока нет')).toBeInTheDocument();
  });

  it('неизвестный kind пропускается, известное событие остаётся в ленте', async () => {
    createFeedScenario(authUsers.student);
    personal({ '': () => feedJson({ items: [{ id: 1, kind: 'POLL_OPENED', createdAt: '2026-11-18T09:00:00Z' }, everyKind[2]], nextCursor: null }) });
    mountFeed('/');
    expect(await screen.findByRole('link', { name: 'Работа «Эссе» принята' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Повторить' })).not.toBeInTheDocument();
    expect(screen.getAllByRole('article')).toHaveLength(1);
  });

  it('администратор на / видит прежний экран без запроса ленты', async () => {
    createFeedScenario({ ...authUsers.admin, mustChangePassword: false });
    const requests = personal({ '': { items: everyKind, nextCursor: null } });
    mountFeed('/');
    expect(await screen.findByText('Раздел появится')).toBeInTheDocument();
    await waitFor(() => expect(requests).toHaveLength(0));
    expect(screen.queryByRole('heading', { name: 'Главная' })).not.toBeInTheDocument();
  });

  it('студент с членством PENDING видит пояснение и пустую ленту', async () => {
    createFeedScenario(authUsers.pendingStudent);
    personal({ '': { items: [], nextCursor: null } });
    mountFeed('/');
    expect(await screen.findByText(/ждёт подтверждения старосты или преподавателя/)).toBeInTheDocument();
    expect(await screen.findByText('Событий пока нет')).toBeInTheDocument();
  });
});

import { cleanup, configure, screen } from '@testing-library/react';
import { http } from 'msw';
import { server } from '../../mocks/server';
import { authUsers, awardRows, createFeedScenario, disposeFeedTests, feedJson, mountFeed } from '../../test/feedAcceptance';

beforeAll(() => { configure({ asyncUtilTimeout: 3000 }); server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => { cleanup(); disposeFeedTests(); server.resetHandlers(); });
afterAll(() => server.close());

function scenario(viewer: Parameters<typeof createFeedScenario>[0] = authUsers.student) {
  createFeedScenario(viewer);
  const [all, own] = awardRows(2) as [ReturnType<typeof awardRows>[number], ReturnType<typeof awardRows>[number]];
  const requests: string[] = [];
  server.use(http.get('*/api/v1/feed/awards', ({ request }) => {
    const mine = new URL(request.url).searchParams.get('mine');
    requests.push(mine ?? '');
    return feedJson({ items: mine === 'true' ? [own] : [all, own], nextCursor: null });
  }));
  return requests;
}

describe('лента наград: «Все» и «Мои»', () => {
  it('переключает ленту на свои награды и обратно, сохраняя выбор в адресе', async () => {
    const requests = scenario();
    const { user, router } = mountFeed('/awards');
    expect(await screen.findByText('Награда №1')).toBeInTheDocument();
    await user.click(screen.getByRole('radio', { name: 'Мои' }));
    expect(await screen.findByText('Награда №2')).toBeInTheDocument();
    expect(screen.queryByText('Награда №1')).not.toBeInTheDocument();
    expect(router.state.location.search).toBe('?mine=1');
    expect(requests).toContain('true');
    await user.click(screen.getByRole('radio', { name: 'Все' }));
    expect(await screen.findByText('Награда №1')).toBeInTheDocument();
    expect(router.state.location.search).toBe('');
  });

  it('открывается сразу на своих по ?mine=1', async () => {
    const requests = scenario();
    mountFeed('/awards?mine=1');
    expect(await screen.findByText('Награда №2')).toBeInTheDocument();
    expect(screen.queryByText('Награда №1')).not.toBeInTheDocument();
    expect(requests[0]).toBe('true');
  });

  it('администратору переключатель не показывается', async () => {
    scenario({ ...authUsers.admin, mustChangePassword: false });
    mountFeed('/awards?mine=1');
    expect(await screen.findByText('Награда №1')).toBeInTheDocument();
    expect(screen.queryByRole('radio', { name: 'Мои' })).not.toBeInTheDocument();
  });
});

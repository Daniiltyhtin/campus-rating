import { act, screen, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { authUsers, createAuthScenario, mountAuth, problem } from '../test/authAcceptance';
import { createProfileScenario } from '../test/profileAcceptance';
import { advance, cleanupTiming, controlledRequest, disposeTimingView, fakeLoaderTime, flushNetwork, setupTiming, timingUser, untilNetwork } from '../test/loaderAcceptance';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
beforeEach(setupTiming);
afterEach(cleanupTiming);
afterAll(() => server.close());

const rows = [
  { ...authUsers.admin, id: 3001, mustChangePassword: false, lastName: 'Андреев', firstName: 'Яков', middleName: null, fullName: 'Андреев Яков', email: 'a@campus.test', group: null, department: null, status: null, createdAt: '2026-11-01T00:00:00Z', lastLoginAt: null, passwordSetAt: null },
  { ...authUsers.admin, id: 3002, mustChangePassword: false, lastName: 'Белов', firstName: 'Антон', middleName: null, fullName: 'Белов Антон', email: 'b@campus.test', group: null, department: null, status: null, createdAt: '2026-11-01T00:00:00Z', lastLoginAt: null, passwordSetAt: null },
];
const loader = () => screen.queryByRole('status', { name: 'Загрузка' });

async function openFromHome(path: string) {
  createAuthScenario({ ...authUsers.admin, mustChangePassword: false });
  const view = disposeTimingView(mountAuth('/password/change'));
  await screen.findByRole('button', { name: 'Сменить пароль' });
  fakeLoaderTime();
  await act(async () => { await view.router.navigate(path); });
  return view;
}

it.each([
  [100, false], [320, false], [2000, false], [500, true],
] as const)('Covers AE31, AE32, AE33, AE34: %s ms %s AsyncContent delays only slow results and errors', async (duration, failure) => {
  const request = controlledRequest('get', '*/api/v1/admin/users', () => failure ? problem('INTERNAL_ERROR', 500) : HttpResponse.json({ items: rows, nextCursor: null }));
  await openFromHome('/admin/users');
  await untilNetwork(() => request.calls === 1);
  expect(loader()).not.toBeInTheDocument();
  expect(screen.queryByRole('table', { name: 'Пользователи' })).not.toBeInTheDocument();
  await advance(Math.min(duration, 299));
  expect(loader()).not.toBeInTheDocument();
  if (duration > 300) {
    await advance(1);
    expect(loader()).toBeInTheDocument();
    await advance(duration - 300);
  }
  await request.release();
  const result = () => failure ? screen.queryByRole('alert') : screen.queryByRole('table', { name: 'Пользователи' });
  if (duration > 300 && duration < 700) {
    expect(result()).not.toBeInTheDocument();
    expect(loader()).toBeInTheDocument();
    await advance(699 - duration);
    expect(result()).not.toBeInTheDocument();
    await advance(1);
  }
  expect(result()).toBeInTheDocument();
  expect(loader()).not.toBeInTheDocument();
});

it('Covers AE39a: a 100 ms name sort retains the previous table without a loader', async () => {
  const first = controlledRequest('get', '*/api/v1/admin/users', () => HttpResponse.json({ items: rows, nextCursor: null }));
  await openFromHome('/admin/users');
  await untilNetwork(() => first.calls === 1);
  await first.release();
  const table = screen.getByRole('table', { name: 'Пользователи' });
  const before = within(table).getAllByRole('row')[1]!.textContent;
  const next = controlledRequest('get', '*/api/v1/admin/users', () => HttpResponse.json({ items: [...rows].reverse(), nextCursor: null }));
  await timingUser().click(within(table).getByRole('button', { name: 'Имя' }));
  await untilNetwork(() => next.calls === 1);
  expect(screen.getByRole('table', { name: 'Пользователи' })).toBeVisible();
  expect(within(screen.getByRole('table', { name: 'Пользователи' })).getAllByRole('row')[1]).toHaveTextContent(before!);
  expect(loader()).not.toBeInTheDocument();
  await advance(99);
  expect(screen.getByRole('table', { name: 'Пользователи' })).toBeVisible();
  await advance(1);
  await next.release();
  expect(within(screen.getByRole('table', { name: 'Пользователи' })).getAllByRole('row')[1]).toHaveTextContent('Белов');
  expect(loader()).not.toBeInTheDocument();
});

it.each(['profile', 'session'] as const)('Covers AE34, AE38: %s error at 500 ms stays hidden until 700 ms', async (kind) => {
  if (kind === 'profile') createProfileScenario();
  else createAuthScenario();
  const request = controlledRequest('get', kind === 'profile' ? '*/api/v1/users/1001/profile' : '*/api/v1/auth/me', () => problem('INTERNAL_ERROR', 500));
  fakeLoaderTime();
  disposeTimingView(mountAuth(kind === 'profile' ? '/users/1001' : '/login'));
  await untilNetwork(() => request.calls === 1);
  await advance(300);
  expect(loader()).toBeInTheDocument();
  await advance(200);
  await request.release();
  const retry = () => screen.queryByRole('button', { name: /Повторить/ });
  expect(retry()).not.toBeInTheDocument();
  expect(loader()).toBeInTheDocument();
  await advance(199);
  expect(retry()).not.toBeInTheDocument();
  await advance(1);
  expect(retry()).toBeVisible();
  expect(loader()).not.toBeInTheDocument();
});

it.each([100, 1000])('Covers AE38: session loader obeys the delay for a %i ms session', async (duration) => {
  createAuthScenario();
  const request = controlledRequest('get', '*/api/v1/auth/me', () => problem('UNAUTHENTICATED', 401));
  fakeLoaderTime();
  disposeTimingView(mountAuth('/login'));
  await untilNetwork(() => request.calls === 1);
  expect(loader()).not.toBeInTheDocument();
  await advance(Math.min(duration, 299));
  expect(loader()).not.toBeInTheDocument();
  if (duration > 300) {
    await advance(1);
    expect(loader()).toBeInTheDocument();
    await advance(duration - 300);
  }
  await request.release();
  expect(screen.getByRole('button', { name: 'Войти' })).toBeVisible();
  expect(loader()).not.toBeInTheDocument();
});

it.each([
  ['faculties', 100, 'AE37', 'Загрузка факультетов…'],
  ['faculties', 1000, 'AE37', 'Загрузка факультетов…'],
  ['groups', 1000, 'AE42', 'Загрузка справочника…'],
  ['config', 1000, 'AE42', 'Загрузка настроек стенда…'],
] as const)('Covers AE37, AE42: %s %i ms %s status «%s» follows the common delay', async (kind, duration, _ae, text) => {
  createAuthScenario();
  const view = disposeTimingView(mountAuth('/login'));
  await screen.findByRole('button', { name: 'Войти' });
  if (kind === 'config') view.queryClient.removeQueries({ queryKey: ['auth', 'config'] });
  if (kind === 'groups') {
    await act(async () => { await view.router.navigate('/register'); });
    await screen.findByRole('combobox', { name: 'Факультет' });
  }
  const path = kind === 'config' ? '*/api/v1/auth/config' : kind === 'groups' ? '*/api/v1/directory/faculties/1/groups' : '*/api/v1/directory/faculties';
  const request = controlledRequest('get', path, () => HttpResponse.json(kind === 'config' ? { demo: false, emailVerification: 'OFF' } : { items: [] }));
  fakeLoaderTime();
  if (kind === 'groups') await timingUser().selectOptions(screen.getByRole('combobox', { name: 'Факультет' }), '1');
  else await act(async () => { await view.router.navigate('/register'); });
  await untilNetwork(() => request.calls === 1);
  expect(screen.queryByText(text, { exact: true })).not.toBeInTheDocument();
  await advance(Math.min(duration, 299));
  expect(screen.queryByText(text, { exact: true })).not.toBeInTheDocument();
  if (duration > 300) {
    await advance(1);
    expect(screen.getByText(text, { exact: true })).toHaveAttribute('role', 'status');
    await advance(duration - 301);
    expect(screen.getByText(text, { exact: true })).toBeVisible();
    await advance(1);
  }
  await request.release();
  expect(screen.queryByText(text, { exact: true })).not.toBeInTheDocument();
  await flushNetwork();
});

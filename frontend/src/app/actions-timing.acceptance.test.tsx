import { act, screen } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { authUsers, createAuthScenario, mountAuth, problem } from '../test/authAcceptance';
import { createProfileScenario, ledgerRows } from '../test/profileAcceptance';
import { advance, cleanupTiming, controlledRequest, disposeTimingView, fakeLoaderTime, flushNetwork, setupTiming, timingUser, untilNetwork } from '../test/loaderAcceptance';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
beforeEach(setupTiming);
afterEach(cleanupTiming);
afterAll(() => server.close());

function ordinary(button: HTMLElement) {
  expect(button).toBeEnabled();
  expect(button).not.toHaveAttribute('data-loading');
  expect(button.querySelector('.mantine-Loader-root')).toBeNull();
}
function busy(button: HTMLElement) {
  expect(button).toBeDisabled();
  expect(button).toHaveAttribute('data-loading');
  expect(button.querySelector('.mantine-Loader-root')).not.toBeNull();
}

async function prepareAction(kind: 'login' | 'password' | 'set') {
  const state = createAuthScenario(kind === 'password' ? { ...authUsers.student, mustChangePassword: true } : null);
  const path = kind === 'login' ? '/login' : kind === 'password' ? '/password/change' : '/password/set/timing-token';
  const label = kind === 'login' ? 'Войти' : kind === 'password' ? 'Сменить пароль' : 'Установить пароль';
  const view = disposeTimingView(mountAuth(path));
  const button = await screen.findByRole('button', { name: label });
  if (kind === 'login') {
    await view.user.type(screen.getByLabelText('Email'), 'timing@campus.test');
    await view.user.type(screen.getByLabelText('Пароль', { exact: true }), 'password-1');
  } else {
    if (kind === 'password') await view.user.type(screen.getByLabelText('Текущий пароль'), 'password-1');
    await view.user.type(screen.getByLabelText('Новый пароль', { exact: true }), 'password-2');
    await view.user.type(screen.getByLabelText(kind === 'password' ? 'Повторите новый пароль' : 'Повторите пароль', { exact: true }), 'password-2');
  }
  return { state, view, button };
}

it.each([100, 320, 1000])('Covers AE35, AE36: login at %i ms locks repeats immediately and publishes the session at the result deadline', async (duration) => {
  const { state, view, button } = await prepareAction('login');
  const request = controlledRequest('post', '*/api/v1/auth/login', () => {
    state.currentUser = state.loginUser;
    return HttpResponse.json(state.currentUser);
  });
  fakeLoaderTime();
  const user = timingUser();
  await user.click(button);
  await user.click(button);
  await untilNetwork(() => request.calls > 0);
  expect(request.calls).toBe(1);
  ordinary(button);
  await advance(Math.min(duration, 299));
  ordinary(button);
  expect(view.router.state.location.pathname).toBe('/login');
  if (duration > 300) {
    await advance(1);
    busy(button);
    await advance(duration - 300);
  }
  await request.release();
  if (duration === 320) {
    busy(button);
    expect(view.router.state.location.pathname).toBe('/login');
    await user.click(button);
    await flushNetwork();
    expect(request.calls).toBe(1);
    await advance(379);
    expect(screen.getByRole('heading', { name: 'С возвращением' })).toBeVisible();
    expect(view.router.state.location.pathname).toBe('/login');
    await advance(1);
  }
  expect(view.router.state.location.pathname).toBe('/');
  expect(screen.queryByRole('heading', { name: 'С возвращением' })).not.toBeInTheDocument();
  expect(request.calls).toBe(1);
});

it('Covers AE33, AE36: login response at 320 ms keeps the form and route until 700 ms', async () => {
  const { state, view, button } = await prepareAction('login');
  const request = controlledRequest('post', '*/api/v1/auth/login', () => {
    state.currentUser = state.loginUser;
    return HttpResponse.json(state.currentUser);
  });
  fakeLoaderTime();
  await timingUser().click(button);
  await untilNetwork(() => request.calls === 1);
  await advance(300);
  busy(button);
  await advance(20);
  await request.release();
  expect(view.router.state.location.pathname).toBe('/login');
  expect(screen.getByRole('heading', { name: 'С возвращением' })).toBeVisible();
  await advance(379);
  expect(view.router.state.location.pathname).toBe('/login');
  await advance(1);
  expect(view.router.state.location.pathname).toBe('/');
});

it('Covers AE36: Enter in a pending login form does not send another request', async () => {
  const { button } = await prepareAction('login');
  const request = controlledRequest('post', '*/api/v1/auth/login', () => problem('INVALID_CREDENTIALS', 401));
  fakeLoaderTime();
  const user = timingUser();
  await user.click(button);
  await untilNetwork(() => request.calls === 1);
  await user.click(screen.getByLabelText('Email'));
  await user.keyboard('{Enter}');
  await flushNetwork();
  expect(request.calls).toBe(1);
  await advance(300);
  await user.keyboard('{Enter}');
  await flushNetwork();
  expect(request.calls).toBe(1);
  await request.release();
});

it('Covers AE34, AE36: login mutation error at 500 ms appears at 700 ms', async () => {
  const { button } = await prepareAction('login');
  const request = controlledRequest('post', '*/api/v1/auth/login', () => problem('INVALID_CREDENTIALS', 401));
  fakeLoaderTime();
  await timingUser().click(button);
  await untilNetwork(() => request.calls === 1);
  await advance(300);
  busy(button);
  await advance(200);
  await request.release();
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  await advance(199);
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  await advance(1);
  expect(screen.getByRole('alert')).toBeVisible();
  ordinary(screen.getByRole('button', { name: 'Войти' }));
});

it.each([['password', 100], ['password', 320], ['password', 1000], ['set', 100], ['set', 320], ['set', 1000]] as const)('Covers AE35, AE36: %s %i ms action uses the same 300/400 ms rule and suppresses repeats', async (kind, duration) => {
  const { state, view, button } = await prepareAction(kind);
  const request = controlledRequest('post', kind === 'password' ? '*/api/v1/auth/password' : '*/api/v1/auth/password/set', () => {
    if (kind === 'password') state.currentUser = { ...authUsers.student, mustChangePassword: false };
    return new HttpResponse(null, { status: 204 });
  });
  fakeLoaderTime();
  const user = timingUser();
  await user.click(button);
  await user.click(button);
  await untilNetwork(() => request.calls > 0);
  expect(request.calls).toBe(1);
  ordinary(button);
  await user.click(screen.getByLabelText('Новый пароль', { exact: true }));
  await user.keyboard('{Enter}');
  await flushNetwork();
  expect(request.calls).toBe(1);
  await advance(Math.min(duration, 299));
  ordinary(button);
  if (duration > 300) {
    await advance(1);
    busy(button);
    await advance(duration - 300);
  }
  await request.release();
  const success = () => kind === 'password' ? view.router.state.location.pathname === '/' : Boolean(screen.queryByText('Пароль установлен, email подтверждён'));
  if (duration === 320) {
    expect(success()).toBe(false);
    await advance(379);
    expect(success()).toBe(false);
    await advance(1);
  }
  expect(success()).toBe(true);
});

it('Covers AE42: password session refresh status appears at 300 ms and lasts until the one second response', async () => {
  const { button } = await prepareAction('password');
  const request = controlledRequest('get', '*/api/v1/auth/csrf', () => problem('INTERNAL_ERROR', 500));
  fakeLoaderTime();
  await timingUser().click(button);
  await flushNetwork();
  await untilNetwork(() => request.calls > 0);
  expect(screen.queryByText('Обновление сессии…', { exact: true })).not.toBeInTheDocument();
  await advance(299);
  expect(screen.queryByText('Обновление сессии…', { exact: true })).not.toBeInTheDocument();
  await advance(1);
  expect(screen.getByText('Обновление сессии…', { exact: true })).toHaveAttribute('role', 'status');
  await advance(699);
  expect(screen.getByText('Обновление сессии…', { exact: true })).toBeVisible();
  await advance(1);
  await request.release();
  expect(screen.queryByText('Обновление сессии…', { exact: true })).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Повторить' })).toBeVisible();
});

it.each([320, 1000])('Covers AE39: load more at %i ms retains old rows and delays new rows and hasNextPage', async (duration) => {
  createAuthScenario({ ...authUsers.admin, mustChangePassword: false });
  const row = { id: 3001, role: 'ADMIN', fullName: 'Андреев Яков', lastName: 'Андреев', firstName: 'Яков', middleName: null, email: 'a@campus.test', group: null, department: null, status: null, createdAt: '2026-11-01T00:00:00Z', lastLoginAt: null, passwordSetAt: null };
  const first = controlledRequest('get', '*/api/v1/admin/users', () => HttpResponse.json({ items: [row], nextCursor: 'next-users' }));
  const view = disposeTimingView(mountAuth('/admin/users'));
  await untilNetwork(() => first.calls === 1);
  await first.release();
  const button = await screen.findByRole('button', { name: 'Загрузить ещё' });
  const request = controlledRequest('get', '*/api/v1/admin/users', () => HttpResponse.json({ items: [{ ...row, id: 3002, fullName: 'Белов Антон', lastName: 'Белов', firstName: 'Антон' }], nextCursor: null }));
  fakeLoaderTime();
  const user = timingUser();
  await user.click(button);
  await user.click(button);
  await untilNetwork(() => request.calls > 0);
  expect(request.calls).toBe(1);
  ordinary(button);
  expect(screen.getByRole('table', { name: 'Пользователи' })).toHaveTextContent('Андреев');
  await advance(299);
  ordinary(button);
  await advance(1);
  busy(button);
  await advance(duration - 300);
  await request.release();
  if (duration === 320) {
    expect(screen.queryByText('Белов', { exact: true })).not.toBeInTheDocument();
    expect(button).toBeInTheDocument();
    await advance(379);
    expect(screen.queryByText('Белов', { exact: true })).not.toBeInTheDocument();
    await advance(1);
  }
  expect(screen.getByRole('table', { name: 'Пользователи' })).toHaveTextContent('Андреев');
  expect(screen.getByText('Белов', { exact: true })).toBeVisible();
  expect(screen.queryByRole('button', { name: 'Загрузить ещё' })).not.toBeInTheDocument();
  expect(view.router.state.location.pathname).toBe('/admin/users');
});

it('Covers AE39: automatic profile history at 320 ms publishes the next page at 700 ms', async () => {
  const state = createProfileScenario();
  state.pages.set('', { items: ledgerRows(1), nextCursor: 'next-history' });
  let intersect: (() => void) | undefined;
  vi.stubGlobal('IntersectionObserver', class {
    constructor(callback: IntersectionObserverCallback) {
      intersect = () => callback([{ isIntersecting: true } as IntersectionObserverEntry], this as unknown as IntersectionObserver);
    }
    observe() {}
    disconnect() {}
    unobserve() {}
  });
  disposeTimingView(mountAuth('/users/1001'));
  await screen.findByText('Историческая запись 1');
  await untilNetwork(() => Boolean(intersect));
  const request = controlledRequest('get', '*/api/v1/ledger/1001/entries', () => HttpResponse.json({ items: ledgerRows(2).slice(1), nextCursor: null }));
  fakeLoaderTime();
  act(() => { intersect!(); });
  await untilNetwork(() => request.calls === 1);
  expect(screen.getByText('Историческая запись 1')).toBeVisible();
  await advance(299);
  ordinary(screen.getByRole('button', { name: 'Показать ещё' }));
  await advance(1);
  busy(screen.getByRole('button', { name: 'Показать ещё' }));
  await advance(20);
  await request.release();
  expect(screen.queryByText('Историческая запись 2')).not.toBeInTheDocument();
  await advance(379);
  expect(screen.queryByText('Историческая запись 2')).not.toBeInTheDocument();
  await advance(1);
  expect(screen.getByText('Историческая запись 1')).toBeVisible();
  expect(screen.getByText('Историческая запись 2')).toBeVisible();
  expect(screen.queryByRole('button', { name: 'Показать ещё' })).not.toBeInTheDocument();
});

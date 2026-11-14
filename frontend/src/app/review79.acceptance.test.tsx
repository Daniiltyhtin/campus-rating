import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AssignmentDetails } from '../features/tasks/TaskPage';
import { MantineProvider } from '@mantine/core';
import { act, render, screen } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { AsyncContent, type LoadState } from './layouts/PageLayouts';
import { server } from '../mocks/server';
import { authUsers, createAuthScenario, mountAuth, problem } from '../test/authAcceptance';
import { advance, cleanupTiming, controlledRequest, disposeTimingView, fakeLoaderTime, flushNetwork, setupTiming, timingUser, untilNetwork } from '../test/loaderAcceptance';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
beforeEach(setupTiming);
afterEach(cleanupTiming);
afterAll(() => server.close());

it('R26: publishes the held error before a load restarted at 500 ms', async () => {
  fakeLoaderTime();
  const content = (state: LoadState) => <MantineProvider env="test"><AsyncContent state={state}><button>Готовые данные</button></AsyncContent></MantineProvider>;
  const view = render(content({ status: 'ready' }));
  view.rerender(content({ status: 'loading' }));
  await advance(320);
  view.rerender(content({ status: 'error', message: 'Ошибка первой операции', retry: () => undefined }));
  await advance(180);
  view.rerender(content({ status: 'loading' }));
  await advance(199);
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  await advance(1);
  expect(screen.getByRole('alert')).toHaveTextContent('Ошибка первой операции');
  await advance(299);
  expect(screen.getByRole('alert')).toBeVisible();
  await advance(1);
  expect(screen.getByRole('status', { name: 'Загрузка' })).toBeVisible();
});

it('R29: two name sort clicks before response reverse the current direction', async () => {
  createAuthScenario({ ...authUsers.admin, mustChangePassword: false });
  const row = { id: 3001, role: 'ADMIN', fullName: 'Андреев Яков', lastName: 'Андреев', firstName: 'Яков', middleName: null, email: 'a@campus.test', group: null, department: null, status: null, createdAt: '2026-11-01T00:00:00Z', lastLoginAt: null, passwordSetAt: null };
  const initial = controlledRequest('get', '*/api/v1/admin/users', () => HttpResponse.json({ items: [row], nextCursor: null }));
  const view = disposeTimingView(mountAuth('/admin/users'));
  await untilNetwork(() => initial.calls === 1);
  await initial.release();
  const button = await screen.findByRole('button', { name: 'Имя' });
  const request = controlledRequest('get', '*/api/v1/admin/users', () => HttpResponse.json({ items: [row], nextCursor: null }));
  fakeLoaderTime();
  await timingUser().click(button);
  await untilNetwork(() => request.calls === 1);
  expect(view.router.state.location.search).toBe('?sort=firstName');
  await timingUser().click(button);
  expect(view.router.state.location.search).toBe('?sort=firstName&direction=desc');
  expect(screen.getByRole('table', { name: 'Пользователи' })).toHaveTextContent('Андреев');
});

it.each([false, true])('R26: shifted session refresh holds its own indicator for 400 ms (failure %s)', async (failure) => {
  const state = createAuthScenario(null);
  const view = disposeTimingView(mountAuth('/login'));
  const button = await screen.findByRole('button', { name: 'Войти' });
  await view.user.type(screen.getByLabelText('Email'), 'timing@campus.test');
  await view.user.type(screen.getByLabelText('Пароль', { exact: true }), 'password-1');
  const login = controlledRequest('post', '*/api/v1/auth/login', () => { state.currentUser = state.loginUser; return HttpResponse.json(state.currentUser); });
  const me = controlledRequest('get', '*/api/v1/auth/me', () => failure ? problem('INTERNAL_ERROR', 500) : HttpResponse.json(state.loginUser));
  fakeLoaderTime();
  await timingUser().click(button);
  await untilNetwork(() => login.calls === 1);
  await advance(500);
  await login.release();
  await untilNetwork(() => me.calls === 1);
  await advance(300);
  expect(screen.getByText('Обновление сессии…', { exact: true })).toBeVisible();
  await advance(20);
  await me.release();
  expect(screen.getByText('Обновление сессии…', { exact: true })).toBeVisible();
  expect(view.router.state.location.pathname).toBe('/login');
  await advance(379);
  expect(screen.getByText('Обновление сессии…', { exact: true })).toBeVisible();
  await advance(1);
  expect(screen.queryByText('Обновление сессии…', { exact: true })).not.toBeInTheDocument();
  if (failure) expect(screen.getByRole('button', { name: 'Повторить' })).toBeVisible();
  else expect(view.router.state.location.pathname).toBe('/');
});

it.each([
  ['/tasks/601', '*/api/v1/assignments/601', 'student'],
  ['/checkpoints/501', '*/api/v1/checkpoints/501', 'teacher'],
  ['/groups/101/students', '*/api/v1/groups/101/students', 'teacher'],
  ['/admin/submissions/601', '*/api/v1/assignments/601', 'admin'],
  ['/admin/users/teachers/2001', '*/api/v1/admin/teachers/2001', 'admin'],
] as const)('R26: %s access error at 500 ms keeps the loader until 700 ms', async (path, endpoint, role) => {
  createAuthScenario({ ...authUsers[role], mustChangePassword: false });
  const view = disposeTimingView(mountAuth('/password/change'));
  await screen.findByRole('button', { name: 'Сменить пароль' });
  const request = controlledRequest('get', endpoint, () => problem('FORBIDDEN', 403));
  fakeLoaderTime();
  await act(async () => { await view.router.navigate(path); });
  await untilNetwork(() => request.calls === 1);
  await advance(300);
  expect(screen.getByRole('status', { name: 'Загрузка' })).toBeVisible();
  await advance(200);
  await request.release();
  expect(screen.queryByRole('heading', { name: 'Нет доступа' })).not.toBeInTheDocument();
  expect(screen.getByRole('status', { name: 'Загрузка' })).toBeVisible();
  await advance(199);
  expect(screen.queryByRole('heading', { name: 'Нет доступа' })).not.toBeInTheDocument();
  await advance(1);
  expect(screen.getByRole('heading', { name: 'Нет доступа' })).toBeVisible();
  expect(screen.queryByRole('status', { name: 'Загрузка' })).not.toBeInTheDocument();
});

it('R27: membership approval and rejection keep their appearance and suppress repeats before 300 ms', async () => {
  createAuthScenario({ ...authUsers.teacher, mustChangePassword: false });
  const initial = controlledRequest('get', '*/api/v1/memberships/pending', () => HttpResponse.json({ items: [{ studentId: 1001, fullName: 'Студент Тестовый', email: 'student@campus.test', group: { id: 101, name: 'Тестовая' }, requestedAt: '2026-11-01T00:00:00Z' }] }));
  disposeTimingView(mountAuth('/memberships'));
  await untilNetwork(() => initial.calls === 1);
  await initial.release();
  const approve = await screen.findByRole('button', { name: 'Подтвердить Студент Тестовый' });
  const reject = screen.getByRole('button', { name: 'Отклонить Студент Тестовый' });
  const request = controlledRequest('post', '*/api/v1/memberships/1001/approval', () => problem('INTERNAL_ERROR', 500));
  fakeLoaderTime();
  await timingUser().click(approve);
  await untilNetwork(() => request.calls === 1);
  expect(approve).toBeEnabled();
  expect(reject).toBeEnabled();
  await timingUser().click(approve);
  await timingUser().click(reject);
  await flushNetwork();
  expect(request.calls).toBe(1);
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  await advance(300);
  expect(approve).toHaveAttribute('data-loading');
  expect(approve).toBeDisabled();
  await advance(20);
  await request.release();
  await advance(379);
  expect(approve).toHaveAttribute('data-loading');
  await advance(1);
  expect(screen.getByRole('alert')).toBeVisible();
});

it('R27: menu logout stays visible and ordinary until 300 ms then shows a spinner', async () => {
  createAuthScenario({ ...authUsers.student, mustChangePassword: false });
  const view = disposeTimingView(mountAuth('/'));
  await screen.findByRole('button', { name: /Меню пользователя/ });
  await view.user.click(screen.getByRole('button', { name: /Меню пользователя/ }));
  const button = await screen.findByRole('menuitem', { name: 'Выйти' });
  const request = controlledRequest('post', '*/api/v1/auth/logout', () => problem('INTERNAL_ERROR', 500));
  fakeLoaderTime();
  await timingUser().click(button);
  await untilNetwork(() => request.calls === 1);
  expect(button).toBeVisible();
  expect(button).not.toHaveAttribute('data-disabled');
  await timingUser().click(button);
  expect(request.calls).toBe(1);
  await advance(300);
  expect(screen.getByRole('menuitem', { name: 'Выйти' }).querySelector('.mantine-Loader-root')).not.toBeNull();
});

it('R28: email verification error uses the text indicator deadline', async () => {
  const state = createAuthScenario({ ...authUsers.student, mustChangePassword: false });
  const view = disposeTimingView(mountAuth('/password/change'));
  await screen.findByRole('button', { name: 'Сменить пароль' });
  state.config.emailVerification = 'REQUIRED';
  act(() => { view.queryClient.setQueryData(['auth', 'config'], state.config); });
  const request = controlledRequest('get', '*/api/v1/auth/email/status', () => problem('INTERNAL_ERROR', 500));
  fakeLoaderTime();
  await act(async () => { await view.router.navigate('/'); });
  await untilNetwork(() => request.calls === 1);
  expect(screen.queryByText('Проверка email…')).not.toBeInTheDocument();
  await advance(300);
  expect(screen.getByText('Проверка email…')).toBeVisible();
  await advance(200);
  await request.release();
  expect(screen.getByText('Проверка email…')).toBeVisible();
  expect(screen.queryByText('Не удалось проверить подтверждение email')).not.toBeInTheDocument();
  await advance(199);
  expect(screen.getByText('Проверка email…')).toBeVisible();
  await advance(1);
  expect(screen.getByText('Не удалось проверить подтверждение email')).toBeVisible();
});


it.each(['FORBIDDEN', 'NOT_FOUND'])('R26: embedded assignment details hold %s until 700 ms', async (code) => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const request = controlledRequest('get', '*/api/v1/assignments/601', () => problem(code, code === 'FORBIDDEN' ? 403 : 404));
  fakeLoaderTime();
  const view = render(<MantineProvider env="test"><QueryClientProvider client={client}><AssignmentDetails assignmentId={601} readOnly /></QueryClientProvider></MantineProvider>);
  await untilNetwork(() => request.calls === 1);
  await advance(300);
  expect(screen.getByRole('status', { name: 'Загрузка' })).toBeVisible();
  await advance(200);
  await request.release();
  expect(screen.queryByRole('heading')).not.toBeInTheDocument();
  await advance(199);
  expect(screen.getByRole('status', { name: 'Загрузка' })).toBeVisible();
  await advance(1);
  expect(screen.getByRole('heading', { name: code === 'FORBIDDEN' ? 'Нет доступа' : 'Не найдено' })).toBeVisible();
  view.unmount();
  client.clear();
});

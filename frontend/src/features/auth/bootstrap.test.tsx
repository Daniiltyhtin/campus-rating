import { cleanup, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../mocks/server';
import { authUsers, createAuthScenario, mountAuth, problem } from '../../test/authAcceptance';
import { authKeys } from './authQueries';
import { logoutAction, openUserMenu } from '../../test/authAcceptance';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => { cleanup(); server.resetHandlers(); document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'; });
afterAll(() => server.close());
it('bootstraps csrf and current user', async () => {
  createAuthScenario(authUsers.student);
  const { queryClient } = mountAuth('/tasks');
  await waitFor(() => {
    const errors = queryClient.getQueryCache().getAll().flatMap((query) => query.state.error ? [query.state.error] : []);
    expect(errors).toEqual([]);
    expect(screen.getByRole('navigation', { name: 'Боковое меню' })).toBeInTheDocument();
  });
});

it('renews rejected CSRF without silently resubmitting login', async () => {
  const state = createAuthScenario();
  let attempts = 0;
  server.use(http.post('*/api/v1/auth/login', () => {
    attempts++;
    if (attempts === 1) return problem('CSRF_INVALID', 403);
    state.currentUser = authUsers.student;
    return HttpResponse.json(authUsers.student);
  }));
  const { user } = mountAuth();
  await user.type(await screen.findByLabelText('Email'), 'smirnova@student.campus.test');
  await user.type(screen.getByLabelText('Пароль'), 'old');
  await user.click(screen.getByRole('button', { name: 'Войти' }));
  await user.click(await screen.findByRole('button', { name: 'Обновить защиту формы' }));
  await waitFor(() => expect(state.csrfCount).toBe(2));
  expect(attempts).toBe(1);
  await user.click(screen.getByRole('button', { name: 'Войти' }));
  expect(await screen.findByRole('navigation', { name: 'Боковое меню' })).toBeInTheDocument();
});

it('removes private cached data when me discovers expiration on refetch', async () => {
  const state = createAuthScenario(authUsers.student);
  const { queryClient } = mountAuth('/tasks');
  await screen.findByRole('navigation', { name: 'Боковое меню' });
  queryClient.setQueryData(['private-profile'], { id: 1001 });
  state.currentUser = null;
  await queryClient.invalidateQueries({ queryKey: authKeys.user });
  expect(await screen.findByRole('button', { name: 'Войти' })).toBeInTheDocument();
  expect(queryClient.getQueryData(['private-profile'])).toBeUndefined();
});

it('retries token refresh after logout without submitting logout again', async () => {
  const state = createAuthScenario(authUsers.student);
  let csrfAttempts = 0;
  let fail = true;
  server.use(http.get('*/api/v1/auth/csrf', () => {
    csrfAttempts++;
    if (csrfAttempts > 1 && fail) return problem('INTERNAL_ERROR', 500);
    document.cookie = `XSRF-TOKEN=logout-${csrfAttempts}; Path=/`;
    state.token = `logout-${csrfAttempts}`;
    return new HttpResponse(null, { status: 204 });
  }));
  const { user } = mountAuth('/tasks');
  await screen.findByRole('navigation', { name: 'Боковое меню' });
  await openUserMenu(user, authUsers.student.fullName);
  await user.click(logoutAction());
  const retry = await screen.findByRole('button', { name: 'Повторить' });
  expect(screen.queryByRole('button', { name: 'Войти' })).not.toBeInTheDocument();
  fail = false;
  await user.click(retry);
  expect(await screen.findByRole('button', { name: 'Войти' })).toBeInTheDocument();
  expect(state.events.filter((event) => event.path.endsWith('/logout'))).toHaveLength(1);
});

it('makes password visibility reachable and usable with the keyboard', async () => {
  createAuthScenario();
  const { user } = mountAuth();
  await user.click(await screen.findByLabelText('Email'));
  await user.tab();
  expect(screen.getByLabelText('Пароль')).toHaveFocus();
  await user.tab();
  expect(screen.getByRole('button', { name: 'Показать пароль' })).toHaveFocus();
  await user.keyboard('{Enter}');
  expect(screen.getByLabelText('Пароль')).toHaveAttribute('type', 'text');
  expect(screen.getByRole('button', { name: 'Скрыть пароль' })).toHaveFocus();
});

import { act, cleanup, renderHook, waitFor, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { http, HttpResponse } from 'msw';
import { api } from '../../api/client';
import { server } from '../../mocks/server';
import { authUsers, createAuthScenario, mountAuth, problem } from '../../test/authAcceptance';
import { authKeys, useAuthSession } from './authQueries';
import { useAuthAction } from './useAuthAction';

const gates: { resolve: () => void }[] = [];
const clients: QueryClient[] = [];
beforeEach(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(async () => {
  cleanup();
  gates.splice(0).forEach((gate) => gate.resolve());
  await Promise.all(clients.splice(0).map(async (client) => {
    await waitFor(() => {
      expect(client.isMutating()).toBe(0);
      expect(client.isFetching()).toBe(0);
    });
    await client.cancelQueries();
    client.clear();
  }));
  server.resetHandlers(); server.close();
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/';
});
function deferred() { let resolve!: () => void; const promise = new Promise<void>((done) => { resolve = done; }); const gate = { promise, resolve }; gates.push(gate); return gate; }
async function settle(client: QueryClient) {
  await waitFor(() => expect(client.isFetching()).toBe(0));
}
function harness(client = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  clients.push(client);
  return { client, wrapper: ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> };
}
it('33-1 clears and cancels private queries before publishing a changed user', async () => {
  createAuthScenario(authUsers.teacher);
  const h = harness();
  h.client.setQueryData(authKeys.user, authUsers.student);
  const gate = deferred(); let signal: AbortSignal | undefined;
  const pending = h.client.fetchQuery({ queryKey: ['private', 1001], queryFn: async (context) => { signal = context.signal; await gate.promise; return 'secret'; } }).catch(() => undefined);
  h.client.setQueryData(['private-cache'], 'secret');
  const published: boolean[] = [];
  h.client.getQueryCache().subscribe(() => { if (h.client.getQueryData<{ id: number }>(authKeys.user)?.id === authUsers.teacher.id) published.push(h.client.getQueryData(['private-cache']) === undefined); });
  const hook = renderHook(useAuthSession, h);
  await act(async () => { await hook.result.current.session.refetch(); });
  expect(signal?.aborted).toBe(true);
  expect(h.client.getQueryData(['private-cache'])).toBeUndefined();
  expect(published.length).toBeGreaterThan(0); expect(published.every(Boolean)).toBe(true);
  gate.resolve(); await pending; await settle(h.client); h.client.clear();
});
it.each(['login', 'password', 'logout'] as const)('33-2 %s cancels an older query me response', async (kind) => {
  createAuthScenario(); const gate = deferred(); let started = false; let aborted = false;
  server.use(http.get('*/api/v1/auth/me', async ({ request }) => { started = true; request.signal.addEventListener('abort', () => { aborted = true; }); await gate.promise; return problem('UNAUTHENTICATED', 401); }));
  const h = harness();
  const hook = renderHook(() => ({ session: useAuthSession(), action: useAuthAction(async () => {}, kind) }), h);
  await waitFor(() => expect(started).toBe(true));
  server.use(http.get('*/api/v1/auth/me', () => HttpResponse.json(authUsers.student)));
  await act(async () => { await hook.result.current.action.mutateAsync(undefined); });
  expect(aborted).toBe(true);
  gate.resolve();
  await waitFor(() => expect(h.client.getQueryData(authKeys.user)).toEqual(kind === 'logout' ? null : authUsers.student));
  await settle(h.client); h.client.clear();
});
it.skip('33-2 ignores direct finish me after a newer logout', async () => {
  createAuthScenario(authUsers.student); const gate = deferred(); let started = false;
  server.use(http.get('*/api/v1/auth/me', async () => { started = true; await gate.promise; return HttpResponse.json(authUsers.student); }));
  const h = harness(); h.client.setQueryData(authKeys.user, authUsers.student);
  const hook = renderHook(() => ({ password: useAuthAction(async () => {}, 'password'), logout: useAuthAction(async () => {}, 'logout') }), h);
  let pending!: Promise<unknown>;
  act(() => { pending = hook.result.current.password.mutateAsync(undefined); });
  await waitFor(() => expect(started).toBe(true));
  await act(async () => { await hook.result.current.logout.mutateAsync(undefined); });
  await act(async () => { gate.resolve(); await pending; });
  expect(h.client.getQueryData(authKeys.user)).toBeNull(); await settle(h.client); h.client.clear();
});
it.each(['login', 'password', 'logout'] as const)('33-3 scenario invalidates token after %s', async (kind) => {
  createAuthScenario(kind === 'login' ? null : authUsers.student);
  await api.GET('/api/v1/auth/csrf');
  const old = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))!.slice(11);
  const post = (token: string) => fetch('http://localhost/api/v1/auth/register/student', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token }, body: '{}' });
  if (kind === 'login') await api.POST('/api/v1/auth/login', { body: { email: 'a@b.c', password: 'password' } });
  if (kind === 'password') await api.POST('/api/v1/auth/password', { body: { currentPassword: 'password', newPassword: 'password-2' } });
  if (kind === 'logout') await api.POST('/api/v1/auth/logout');
  expect(await (await post(old)).json()).toMatchObject({ code: 'CSRF_INVALID' });
  await api.GET('/api/v1/auth/csrf');
  const fresh = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))!.slice(11);
  expect((await post(fresh)).status).toBe(201);
});
it.each(['login', 'password'] as const)('33-4 %s shows loading until refresh actually fails and blocks repeated refresh', async (kind) => {
  createAuthScenario(kind === 'login' ? null : { ...authUsers.student, mustChangePassword: true });
  const view = mountAuth(kind === 'login' ? '/login' : '/password/change');
  await screen.findByRole('button', { name: kind === 'login' ? 'Войти' : 'Сменить пароль' });
  const gate = deferred(); let calls = 0;
  server.use(http.get('*/api/v1/auth/csrf', async () => { calls++; await gate.promise; return problem('INTERNAL_ERROR', 500); }));
  if (kind === 'login') { await view.user.type(screen.getByLabelText('Email'), 'a@b.c'); await view.user.type(screen.getByLabelText('Пароль', { exact: true }), 'password'); }
  else { await view.user.type(screen.getByLabelText('Текущий пароль'), 'password'); await view.user.type(screen.getByLabelText('Новый пароль', { exact: true }), 'password-2'); await view.user.type(screen.getByLabelText('Повторите новый пароль', { exact: true }), 'password-2'); }
  await view.user.click(screen.getByRole('button', { name: kind === 'login' ? 'Войти' : 'Сменить пароль' }));
  await waitFor(() => expect(calls).toBe(1));
  expect(screen.queryByText(/Не удалось обновить/)).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Повторить' })).not.toBeInTheDocument();
  expect(await screen.findByRole('status', {}, { timeout: 3000 })).toHaveTextContent(/Обновление/);
  await act(async () => { gate.resolve(); });
  await screen.findByRole('button', { name: 'Повторить' });
  const retryGate = deferred(); server.use(http.get('*/api/v1/auth/csrf', async () => { calls++; await retryGate.promise; return problem('INTERNAL_ERROR', 500); }));
  await view.user.click(screen.getByRole('button', { name: 'Повторить' }));
  expect(screen.queryByRole('button', { name: 'Повторить' })).not.toBeInTheDocument();
  await act(async () => { retryGate.resolve(); });
  await screen.findByRole('button', { name: 'Повторить' });
  view.router.dispose(); view.queryClient.clear();
});
it.each(['login', 'password', 'logout'] as const)('33-2 %s also cancels me started while the auth POST was pending', async (kind) => {
  createAuthScenario(); const h = harness();
  const submitGate = deferred(); const meGate = deferred(); let started = false; let aborted = false;
  server.use(http.get('*/api/v1/auth/me', async ({ request }) => { started = true; request.signal.addEventListener('abort', () => { aborted = true; }); await meGate.promise; return problem('UNAUTHENTICATED', 401); }));
  const hook = renderHook(() => useAuthAction(async () => { await submitGate.promise; }, kind), h);
  let action!: Promise<unknown>;
  act(() => { action = hook.result.current.mutateAsync(undefined); });
  await waitFor(() => expect(hook.result.current.isPending).toBe(true));
  const session = renderHook(useAuthSession, h);
  await waitFor(() => expect(started).toBe(true));
  server.use(http.get('*/api/v1/auth/me', () => HttpResponse.json(authUsers.student)));
  await act(async () => { submitGate.resolve(); await action; });
  expect(aborted).toBe(true);
  await act(async () => { meGate.resolve(); });
  expect(session.result.current.session.data).toEqual(kind === 'logout' ? null : authUsers.student);
});

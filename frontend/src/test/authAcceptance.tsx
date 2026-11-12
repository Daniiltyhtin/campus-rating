import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { http, HttpResponse } from 'msw';
import type { components } from '../api/schema';
import { routes } from '../app/router';
import { CampusThemeProvider } from '../theme/CampusThemeProvider';
import { examples } from '../mocks/examples.generated';
import { server } from '../mocks/server';

type CurrentUser = components['schemas']['CurrentUser'];

function example(operationId: string, index = 0): unknown {
  const response = examples.find((operation) => operation.operationId === operationId)?.responses
    .filter((response) => response.status >= 200 && response.status < 300)[index];
  if (!response) throw new Error(`Missing contract example: ${operationId}/${index}`);
  return structuredClone(response.body);
}

export const authUsers = {
  student: example('getCurrentUser', 0) as components['schemas']['CurrentStudent'],
  pendingStudent: example('getCurrentUser', 1) as components['schemas']['CurrentStudent'],
  teacher: example('getCurrentUser', 2) as components['schemas']['CurrentTeacher'],
  unapprovedTeacher: example('getCurrentUser', 3) as components['schemas']['CurrentTeacher'],
  admin: example('getCurrentUser', 4) as components['schemas']['CurrentAdmin'],
};

export function problem(code: string, status: number, extra: Record<string, unknown> = {}) {
  return HttpResponse.json({ type: 'about:blank', title: 'Request failed', status, code, ...extra }, {
    status, headers: { 'Content-Type': 'application/problem+json' },
  });
}

function json(value: unknown) {
  return new HttpResponse(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } });
}

export function createAuthScenario(initialUser: CurrentUser | null = null) {
  const state = {
    currentUser: initialUser,
    loginUser: structuredClone(authUsers.student) as CurrentUser,
    events: [] as { method: string; path: string; csrf: string | null; body: unknown }[],
    token: '',
    csrfCount: 0,
    config: { demo: true, emailVerification: 'OFF' as 'OFF' | 'REQUIRED' },
    nextStep: 'LOGIN' as 'LOGIN' | 'CONFIRM_EMAIL',
  };
  async function record(request: Request) {
    state.events.push({ method: request.method, path: new URL(request.url).pathname,
      csrf: request.headers.get('X-XSRF-TOKEN'), body: await request.clone().json() });
  }
  server.use(
    http.get('*/api/v1/admin/teachers', () => json({ items: [] })),
    http.get('*/api/v1/auth/csrf', () => {
      state.token = `acceptance-csrf-${++state.csrfCount}`;
      document.cookie = `XSRF-TOKEN=${state.token}; Path=/`;
      return new HttpResponse(null, { status: 204 });
    }),
    http.get('*/api/v1/auth/me', () => state.currentUser
      ? HttpResponse.json(state.currentUser) : problem('UNAUTHENTICATED', 401)),
    http.get('*/api/v1/auth/config', () => HttpResponse.json(state.config)),
    http.post('*/api/v1/auth/login', async ({ request }) => {
      await record(request);
      if (request.headers.get('X-XSRF-TOKEN') !== state.token || !state.token) return problem('CSRF_INVALID', 403);
      state.currentUser = structuredClone(state.loginUser);
      state.token = "";
      return HttpResponse.json(state.currentUser);
    }),
    http.post('*/api/v1/auth/register/:role', async ({ request, params }) => {
      await record(request);
      if (request.headers.get('X-XSRF-TOKEN') !== state.token || !state.token) return problem('CSRF_INVALID', 403);
      return HttpResponse.json({ userId: params.role === 'student' ? 1001 : 2002, nextStep: state.nextStep }, { status: 201 });
    }),
    http.post('*/api/v1/auth/password', async ({ request }) => {
      await record(request);
      if (request.headers.get('X-XSRF-TOKEN') !== state.token || !state.token) return problem('CSRF_INVALID', 403);
      if (!state.currentUser) return problem('UNAUTHENTICATED', 401);
      state.currentUser = { ...state.currentUser, mustChangePassword: false };
      state.token = "";
      return new HttpResponse(null, { status: 204 });
    }),
    http.post('*/api/v1/auth/logout', async ({ request }) => {
      state.events.push({ method: 'POST', path: new URL(request.url).pathname, csrf: request.headers.get('X-XSRF-TOKEN'), body: null });
      if (request.headers.get('X-XSRF-TOKEN') !== state.token || !state.token) return problem('CSRF_INVALID', 403);
      state.currentUser = null;
      state.token = "";
      return new HttpResponse(null, { status: 204 });
    }),
    http.get('*/api/v1/directory/faculties', () => json(example('listFaculties'))),
    http.get('*/api/v1/directory/faculties/:facultyId/groups', ({ params }) => json(
      params.facultyId === '1' ? example('listFacultyGroups') : { items: [] })),
    http.get('*/api/v1/directory/faculties/:facultyId/departments', ({ params }) => json(
      params.facultyId === '1' ? example('listFacultyDepartments') : { items: [] })),
  );
  return state;
}

export function mountAuth(path = '/login') {
  const queryClient = new QueryClient({ defaultOptions: {
    queries: { retry: false }, mutations: { retry: false },
  } });
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  const view = render(<CampusThemeProvider><MantineProvider env="test" withCssVariables={false} withGlobalClasses={false}><QueryClientProvider client={queryClient}>
    <RouterProvider router={router} />
  </QueryClientProvider></MantineProvider></CampusThemeProvider>);
  return { ...view, router, queryClient, user: userEvent.setup() };
}

export async function choose(user: ReturnType<typeof userEvent.setup>, label: string, value: string) {
  const input = await screen.findByRole('combobox', { name: label });
  if (input instanceof HTMLSelectElement) {
    const option = Array.from(input.options).find((item) => item.textContent?.includes(value));
    if (!option) throw new Error(`Missing option ${label}: ${value}`);
    await user.selectOptions(input, option.value);
  } else {
    await user.click(input);
    await user.click(await screen.findByRole('option', { name: new RegExp(value) }));
  }
}

export async function fillRegistration(user: ReturnType<typeof userEvent.setup>, password = 'valid-password-1') {
  await user.type(await screen.findByLabelText('ФИО'), 'Тестов Алексей Иванович');
  await user.type(screen.getByLabelText('Email'), 'acceptance@student.campus.test');
  await user.type(screen.getByLabelText('Пароль', { exact: true }), password);
  await choose(user, 'Факультет', 'Факультет информационных технологий');
  await choose(user, 'Группа', 'ИВТ-21');
}

export async function openUserMenu(user: ReturnType<typeof userEvent.setup>, fullName: string) {
  const direct = screen.queryByRole('button', { name: 'Выйти' });
  if (!direct) {
    await user.click(await screen.findByRole('button', { name: new RegExp(`${fullName}|меню пользователя`, 'i') }));
    await screen.findByRole('menuitem', { name: 'Выйти' });
  }
}

export function logoutAction() {
  return screen.queryByRole('menuitem', { name: 'Выйти' }) ?? screen.getByRole('button', { name: 'Выйти' });
}

import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { http, HttpResponse } from 'msw';
import type { components } from '../api/schema';
import { routes } from '../app/router';
import { examples } from '../mocks/examples.generated';
import { server } from '../mocks/server';
import { CampusThemeProvider } from '../theme/CampusThemeProvider';
import { authUsers, createAuthScenario, problem } from './authAcceptance';

type Schemas = components['schemas'];
type RequestRecord = { method: string; path: string; query: Record<string, string>; body: unknown; csrf: string | null };

function example<T>(operationId: string, index = 0): T {
  const response = examples.find((operation) => operation.operationId === operationId)?.responses
    .filter((response) => response.status >= 200 && response.status < 300)[index];
  if (!response) throw new Error(`Missing contract example: ${operationId}/${index}`);
  return structuredClone(response.body) as T;
}

export { authUsers, problem };
export const activityNames = { social: 'Субботник в кампусе', academic: 'Хакатон факультета' };
export const participantNames = { open: 'Кузнецов Дмитрий Андреевич', confirmed: 'Смирнова Анна Олеговна', second: 'Иванова Мария Сергеевна' };

export function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((done) => { resolve = done; });
  return { promise, resolve };
}

export function json(value: unknown, status = 200) {
  return new HttpResponse(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
}

export function createActivitiesScenario(user: Schemas['CurrentUser'] | null = authUsers.student) {
  createAuthScenario(user);
  const sourceList = example<Schemas['ActivityList']>('listActivities');
  const social = { ...sourceList.items[0], enrolled: false, startsAt: '2020-06-15T07:00:00Z' };
  const academic = { ...example<Schemas['Activity']>('createActivity'), enrolled: null, startsAt: '2100-06-15T11:00:00Z' };
  const activity: Schemas['Activity'] = {
    ...academic, id: 603, confirmationOpen: true, enrollmentOpen: false,
    startsAt: '2000-06-15T11:00:00Z',
  };
  const sourceParticipants = example<Schemas['ParticipantList']>('listActivityParticipants');
  const participants: Schemas['ParticipantList'] = {
    ...sourceParticipants, type: 'ACADEMIC', maxPoints: 75,
    items: sourceParticipants.items.map((item) => ({ ...item, enrolled: null })),
  };
  const budgets = example<Schemas['BudgetList']>('listBudgets');
  budgets.month = '2042-07';
  const summary = example<Schemas['TeacherAwardSummary']>('getMyAwardsSummary');
  summary.month = budgets.month;
  const firstAward = example<Schemas['TeacherAwardPage']>('listMyAwards').items[0];
  if (!firstAward) throw new Error('Missing teacher award contract fixture');
  const state = {
    user,
    list: { items: [social, academic, sourceList.items[1]] } as Schemas['ActivityList'],
    activity,
    participants,
    groups: example<Schemas['StudyGroupList']>('listAccessibleGroups'),
    budgets,
    summary,
    firstPage: { items: [firstAward], nextCursor: 'activities-page-2' } as Schemas['TeacherAwardPage'],
    secondPage: { items: [firstAward, { ...firstAward, id: 9102,
      student: { id: 1003, fullName: participantNames.second }, activity: { ...firstAward.activity, title: 'Вторая награда' } }], nextCursor: null } as Schemas['TeacherAwardPage'],
    requests: [] as RequestRecord[],
    withinBudget: true,
    confirmResult: null as Schemas['ParticipationResult'] | null,
  };
  async function record(request: Request) {
    const url = new URL(request.url);
    const text = request.method === 'GET' ? '' : await request.clone().text();
    const body: unknown = text ? JSON.parse(text) : null;
    state.requests.push({ method: request.method, path: url.pathname,
      query: Object.fromEntries(url.searchParams), body, csrf: request.headers.get('X-XSRF-TOKEN') });
  }
  function csrf(request: Request) {
    const token = document.cookie.split('; ').find((part) => part.startsWith('XSRF-TOKEN='))?.slice('XSRF-TOKEN='.length);
    return token && request.headers.get('X-XSRF-TOKEN') === decodeURIComponent(token);
  }
  function awards(body: Schemas['ParticipationRequest']): Schemas['AwardLine'][] {
    return body.items.map((item) => ({ studentId: item.studentId, groupId: 11, level: item.level,
      amount: ({ 25: 19, 50: 38, 75: 56, 100: 75 })[item.level] }));
  }
  server.use(
    http.get('*/api/v1/activities', async ({ request }) => { await record(request); return json(state.list); }),
    http.post('*/api/v1/activities', async ({ request }) => {
      await record(request);
      if (!csrf(request)) return problem('CSRF_INVALID', 403);
      const body = await request.json() as Schemas['ActivityCreate'];
      const created: Schemas['Activity'] = { ...academic, ...body, id: 605,
        currency: body.type === 'ACADEMIC' ? 'BUA' : 'BOA', groups: state.groups.items.filter((group) => body.groupIds.includes(group.id)) };
      state.list.items = [created, ...state.list.items];
      return json(created, 201);
    }),
    http.get('*/api/v1/activities/:activityId', async ({ request }) => { await record(request); return json(state.activity); }),
    http.put('*/api/v1/activities/:activityId/enrollment', async ({ request, params }) => {
      await record(request);
      if (!csrf(request)) return problem('CSRF_INVALID', 403);
      state.list.items = state.list.items.map((item) => item.id === Number(params.activityId) ? { ...item, enrolled: true } : item);
      return json(state.list.items.find((item) => item.id === Number(params.activityId)));
    }),
    http.delete('*/api/v1/activities/:activityId/enrollment', async ({ request, params }) => {
      await record(request);
      if (!csrf(request)) return problem('CSRF_INVALID', 403);
      state.list.items = state.list.items.map((item) => item.id === Number(params.activityId) ? { ...item, enrolled: false } : item);
      return json(state.list.items.find((item) => item.id === Number(params.activityId)));
    }),
    http.get('*/api/v1/me/accessible-groups', async ({ request }) => { await record(request); return json(state.groups); }),
    http.get('*/api/v1/activities/:activityId/participants', async ({ request }) => { await record(request); return json(state.participants); }),
    http.post('*/api/v1/activities/:activityId/participation/preview', async ({ request }) => {
      await record(request);
      if (!csrf(request)) return problem('CSRF_INVALID', 403);
      const lines = awards(await request.json() as Schemas['ParticipationRequest']);
      const total = lines.reduce((sum, line) => sum + line.amount, 0);
      return json({ currency: state.activity.currency, awards: lines, alreadyConfirmed: [], total,
        budgets: [{ groupId: 11, groupName: 'ИВТ-21', needed: total, remaining: state.withinBudget ? 1000 : 50,
          shortfall: state.withinBudget ? 0 : Math.max(total - 50, 0) }], withinBudget: state.withinBudget });
    }),
    http.post('*/api/v1/activities/:activityId/participation', async ({ request }) => {
      await record(request);
      if (!csrf(request)) return problem('CSRF_INVALID', 403);
      const lines = awards(await request.json() as Schemas['ParticipationRequest']);
      const result = state.confirmResult ?? { currency: state.activity.currency, awards: lines, alreadyConfirmed: [],
        total: lines.reduce((sum, line) => sum + line.amount, 0) };
      for (const line of [...result.awards, ...result.alreadyConfirmed]) {
        state.participants.items = state.participants.items.map((item) => item.student.id === line.studentId
          ? { ...item, confirmed: { level: line.level, amount: line.amount, confirmedAt: '2042-07-15T08:00:00Z' } } : item);
      }
      return json(result);
    }),
    http.get('*/api/v1/budgets', async ({ request }) => {
      await record(request);
      return json({ ...state.budgets, month: new URL(request.url).searchParams.get('month') ?? state.budgets.month });
    }),
    http.get('*/api/v1/me/awards/summary', async ({ request }) => {
      await record(request);
      return json({ ...state.summary, month: new URL(request.url).searchParams.get('month') ?? state.summary.month });
    }),
    http.get('*/api/v1/me/awards', async ({ request }) => {
      await record(request);
      return json(new URL(request.url).searchParams.has('cursor') ? state.secondPage : state.firstPage);
    }),
  );
  return state;
}

export function mountActivities(path: string) {
  // jsdom не предоставляет FontFaceSet, на события которого подписывается autosize Textarea.
  if (!document.fonts) {
    const fonts = Object.assign(new EventTarget(), { status: 'loaded', check: () => true, load: async () => [] });
    Object.defineProperty(document, 'fonts', { configurable: true, value: Object.assign(fonts, { ready: Promise.resolve(fonts) }) });
  }
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  const view = render(<CampusThemeProvider><MantineProvider env="test" withCssVariables={false} withGlobalClasses={false}>
    <QueryClientProvider client={queryClient}><RouterProvider router={router} /></QueryClientProvider>
  </MantineProvider></CampusThemeProvider>);
  return { ...view, router, queryClient, user: userEvent.setup() };
}

type User = ReturnType<typeof userEvent.setup>;
export async function chooseOption(user: User, label: string, text: string) {
  const input = await screen.findByRole('combobox', { name: label });
  if (input instanceof HTMLSelectElement) {
    const option = Array.from(input.options).find((option) => option.textContent?.includes(text));
    if (!option) throw new Error(`Missing ${label} option: ${text}`);
    await user.selectOptions(input, option.value);
  } else {
    await user.click(input);
    await user.click(await screen.findByRole('option', { name: new RegExp(text) }));
  }
}

export async function fillActivity(user: User, points = '75') {
  await user.type(await screen.findByLabelText('Название'), 'Новый хакатон');
  await user.type(screen.getByLabelText('Описание'), 'Командное соревнование');
  await user.click(await screen.findByRole('checkbox', { name: /ИВТ-21/ }));
  // datetime-local принимает календарный ввод, не последовательность отдельных невалидных дат.
  const date = screen.getByLabelText('Дата и время начала (Москва)');
  fireEvent.change(date, { target: { value: '2100-06-15T14:00' } });
  const max = screen.getByLabelText('Максимум баллов');
  await user.clear(max);
  await user.type(max, points);
}

export async function selectParticipant(user: User, name = participantNames.open) {
  await user.click(await screen.findByRole('checkbox', { name: new RegExp(name) }));
}

export async function setLevel(user: User, level: number, name = participantNames.open) {
  const group = await screen.findByRole('radiogroup', { name: new RegExp(name) });
  await user.click(within(group).getByRole('radio', { name: new RegExp(`^${level}\\s*%$`) }));
}

import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { http } from 'msw';
import type { components } from '../api/schema';
import { routes } from '../app/router';
import { examples } from '../mocks/examples.generated';
import { server } from '../mocks/server';
import { CampusThemeProvider } from '../theme/CampusThemeProvider';
import { authUsers, createAuthScenario, problem } from './authAcceptance';

type Schemas = components['schemas'];
export type LedgerEntry = Schemas['LedgerEntryView'];
type FeatureRequest = { path: string; query: Record<string, string> };

function example<T>(operationId: string, index = 0): T {
  const value = examples.find((operation) => operation.operationId === operationId)?.responses
    .filter((response) => response.status >= 200 && response.status < 300)[index];
  if (!value) throw new Error(`Missing ${operationId}/${index} contract example`);
  return structuredClone(value.body) as T;
}

export const profileFixtures = {
  publicStudent: () => example<Schemas['StudentProfile']>('getUserProfile', 0),
  ownStudent: () => example<Schemas['StudentProfile']>('getUserProfile', 1),
  adminStudent: () => example<Schemas['StudentProfile']>('getUserProfile', 2),
  teacher: () => example<Schemas['TeacherProfile']>('getUserProfile', 3),
  admin: () => example<Schemas['AdminProfile']>('getUserProfile', 4),
};
export { authUsers, problem };

export function profileJson(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
}

export function profileDeferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((done) => { resolve = done; });
  return { promise, resolve };
}

export function createProfileScenario(viewer: Schemas['CurrentUser'] | null = authUsers.student) {
  createAuthScenario(viewer);
  const own = profileFixtures.ownStudent();
  const second = { ...profileFixtures.publicStudent(), id: 1002, fullName: 'Кузнецов Дмитрий Андреевич' };
  const profiles = new Map<number, Schemas['UserProfile']>([
    [1001, viewer?.role === 'ADMIN' ? profileFixtures.adminStudent() : viewer?.id === 1001 ? own : profileFixtures.publicStudent()],
    [1002, second], [2001, profileFixtures.teacher()], [3001, profileFixtures.admin()],
  ]);
  const initial = example<Schemas['LedgerEntryPage']>('listLedgerEntries');
  initial.nextCursor = null;
  const state = { profiles, pages: new Map<string, Schemas['LedgerEntryPage']>([['', initial]]), requests: [] as FeatureRequest[] };
  server.use(
    http.get('*/api/v1/users/:userId/profile', ({ request, params }) => {
      const url = new URL(request.url);
      state.requests.push({ path: url.pathname, query: Object.fromEntries(url.searchParams) });
      const profile = state.profiles.get(Number(params.userId));
      return profile ? profileJson(profile) : problem('NOT_FOUND', 404);
    }),
    http.get('*/api/v1/ledger/:userId/entries', ({ request }) => {
      const url = new URL(request.url);
      state.requests.push({ path: url.pathname, query: Object.fromEntries(url.searchParams) });
      const page = state.pages.get(url.searchParams.get('cursor') ?? '') ?? { items: [], nextCursor: null };
      const currency = url.searchParams.get('currency');
      return profileJson({ ...page, items: currency ? page.items.filter((item) => item.currency === currency) : page.items });
    }),
  );
  return state;
}

export function mountProfile(path = '/users/1001') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  const view = render(<CampusThemeProvider><MantineProvider env="test" withCssVariables={false} withGlobalClasses={false}>
    <QueryClientProvider client={queryClient}><RouterProvider router={router} /></QueryClientProvider>
  </MantineProvider></CampusThemeProvider>);
  return { ...view, router, queryClient, user: userEvent.setup() };
}

export function ledgerRows(count: number): LedgerEntry[] {
  return Array.from({ length: count }, (_, index) => ({ id: 50_000 - index,
    createdAt: new Date(Date.UTC(2026, 10, 12, 9, 0, -index)).toISOString(), currency: 'BOA', amount: 1, kind: 'ACTIVITY_AWARD',
    source: { type: 'ACTIVITY', id: 601, title: `Историческая запись ${index + 1}` },
    author: { id: 2001, fullName: 'Преподаватель из снимка' },
  }));
}

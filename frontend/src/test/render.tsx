import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { routes } from '../app/router';
import type { Session } from '../app/session';
import { currentUser } from '../features/auth/contracts';
import { authKeys } from '../features/auth/authQueries';
import { examples } from '../mocks/examples.generated';
import { CampusThemeProvider } from '../theme/CampusThemeProvider';

export function renderApp(path: string, session: Session | null = null) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const user = session ? currentUser(examples.find((operation) => operation.operationId === 'getCurrentUser')?.responses
    .find((response) => response.body && typeof response.body === 'object' && 'role' in response.body && response.body.role === session.role)?.body) : null;
  queryClient.setQueryData(authKeys.csrf, true);
  queryClient.setQueryData(['auth', 'config'], { demo: false, emailVerification: 'OFF' });
  queryClient.setQueryData(authKeys.user, user ? { ...user, id: Number(session?.userId), mustChangePassword: false } : null);
  render(
    <CampusThemeProvider><MantineProvider env="test" withCssVariables={false} withGlobalClasses={false}>
      <QueryClientProvider client={queryClient}>
          <RouterProvider router={router} />
      </QueryClientProvider>
    </MantineProvider></CampusThemeProvider>,
  );
  return router;
}

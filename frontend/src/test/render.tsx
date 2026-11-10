import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { routes } from '../app/router';
import { SessionProvider, type Session } from '../app/session';
import { CampusThemeProvider } from '../theme/CampusThemeProvider';

export function renderApp(path: string, session: Session | null = null) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <CampusThemeProvider>
      <QueryClientProvider client={queryClient}>
        <SessionProvider value={session}>
          <RouterProvider router={router} />
        </SessionProvider>
      </QueryClientProvider>
    </CampusThemeProvider>,
  );
  return router;
}

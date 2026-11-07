import { MantineProvider } from '@mantine/core';
import { render } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { routes } from '../app/router';
import { SessionProvider, type Session } from '../app/session';

export function renderApp(path: string, session: Session | null = null) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(
    <MantineProvider>
      <SessionProvider value={session}>
        <RouterProvider router={router} />
      </SessionProvider>
    </MantineProvider>,
  );
  return router;
}

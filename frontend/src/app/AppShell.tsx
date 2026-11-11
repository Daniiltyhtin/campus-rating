import { Group, AppShell as MantineAppShell, NavLink, Title, useMantineTheme } from '@mantine/core';
import { useMediaQuery } from '@mantine/hooks';
import { Link, Outlet, useLocation } from 'react-router';
import { isActive, navigationFor } from './navigation';
import type { Session } from './session';
import type { CurrentUser } from '../features/auth/contracts';
import { ThemeSelect } from '../features/auth/AuthLayout';
import { UserMenu } from '../features/auth/UserMenu';

const HEADER_HEIGHT = 104;
const FOOTER_HEIGHT = 64;

export function AppShell({ session, user }: { session: Session; user: CurrentUser }) {
  const theme = useMantineTheme();
  const desktop = useMediaQuery(`(min-width: ${theme.breakpoints.sm})`, undefined, {
    getInitialValueInEffect: false,
  });
  const { pathname } = useLocation();
  const items = navigationFor(session);

  return (
    <MantineAppShell
      header={{ height: desktop ? HEADER_HEIGHT : 176 }}
      navbar={desktop ? { width: 240, breakpoint: 0 } : undefined}
      footer={desktop ? undefined : { height: FOOTER_HEIGHT }}
      padding="md"
    >
      <MantineAppShell.Header>
        <Group h="100%" px="md" className="auth-shell-header">
          <Title order={3}>Campus Rating</Title>
          <ThemeSelect />
          <UserMenu fullName={user.fullName} userId={session.userId} />
        </Group>
      </MantineAppShell.Header>

      {desktop ? (
        <MantineAppShell.Navbar p="xs" component="nav" aria-label="Боковое меню">
          {items.map((item) => (
            <NavLink
              key={item.to}
              component={Link}
              to={item.to}
              label={item.label}
              active={isActive(pathname, item.to)}
              aria-current={isActive(pathname, item.to) ? 'page' : undefined}
            />
          ))}
        </MantineAppShell.Navbar>
      ) : (
        <MantineAppShell.Footer component="nav" aria-label="Нижнее меню">
          <Group h="100%" gap={0} grow wrap="nowrap">
            {items.map((item) => (
              <NavLink
                key={item.to}
                component={Link}
                to={item.to}
                label={item.label}
                active={isActive(pathname, item.to)}
                aria-current={isActive(pathname, item.to) ? 'page' : undefined}
                h="100%"
                px={2}
                styles={{ label: { textAlign: 'center', fontSize: 'var(--mantine-font-size-xs)' } }}
              />
            ))}
          </Group>
        </MantineAppShell.Footer>
      )}

      <MantineAppShell.Main>
        <Outlet />
      </MantineAppShell.Main>
    </MantineAppShell>
  );
}

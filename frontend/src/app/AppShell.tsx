import { Group, AppShell as MantineAppShell, NavLink, useMantineTheme } from '@mantine/core';
import { useMediaQuery } from '@mantine/hooks';
import { Link, Outlet, useLocation } from 'react-router';
import { isActive, navigationFor } from './navigation';
import type { Session } from './session';
import type { CurrentUser } from '../features/auth/contracts';
import { BrandMark } from '../theme/Brand';
import { UserMenu } from '../features/auth/UserMenu';

const HEADER_HEIGHT = '3.5rem';
const FOOTER_HEIGHT = '4rem';

export function AppShell({ session, user }: { session: Session; user: CurrentUser }) {
  const theme = useMantineTheme();
  const desktop = useMediaQuery(`(min-width: ${theme.breakpoints.sm})`, undefined, {
    getInitialValueInEffect: false,
  });
  const { pathname } = useLocation();
  const items = navigationFor(session);

  return (
    <MantineAppShell
      header={{ height: HEADER_HEIGHT }}
      navbar={desktop ? { width: '15rem', breakpoint: 0 } : undefined}
      footer={desktop ? undefined : { height: FOOTER_HEIGHT }}
      padding="md"
    >
      <MantineAppShell.Header>
        <Group h="100%" px="md" className="auth-shell-header">
          <Link className="cr-brand" to={items[0]?.to ?? '/'} aria-label="Campus Rating"><BrandMark /><span>Campus Rating</span></Link>
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
                className="cr-bottom-link"
                px="0.125rem"
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

import { Group, AppShell as MantineAppShell, NavLink, Title, useMantineTheme } from '@mantine/core';
import { useMediaQuery } from '@mantine/hooks';
import { Link, Outlet, useLocation } from 'react-router';
import { isActive, navigationFor } from './navigation';
import type { Session } from './session';

const HEADER_HEIGHT = 56;
const FOOTER_HEIGHT = 64;

export function AppShell({ session }: { session: Session }) {
  const theme = useMantineTheme();
  const desktop = useMediaQuery(`(min-width: ${theme.breakpoints.sm})`, undefined, {
    getInitialValueInEffect: false,
  });
  const { pathname } = useLocation();
  const items = navigationFor(session);

  return (
    <MantineAppShell
      header={{ height: HEADER_HEIGHT }}
      navbar={desktop ? { width: 240, breakpoint: 0 } : undefined}
      footer={desktop ? undefined : { height: FOOTER_HEIGHT }}
      padding="md"
    >
      <MantineAppShell.Header>
        <Group h="100%" px="md">
          <Title order={3}>Campus Rating</Title>
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

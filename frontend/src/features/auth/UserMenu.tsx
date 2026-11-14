import { ActionButton } from '../../app/ActionButton';
import { useLoaderTiming } from '../../app/loaderTiming';
import { Loader, Menu, Stack, UnstyledButton } from '@mantine/core';
import { useMediaQuery } from '@mantine/hooks';
import { Link } from 'react-router';
import { api } from '../../api/client';
import { ActionError } from './AuthLayout';
import { requireSuccess, useAuthAction } from './useAuthAction';

function useLogout() {
  return useAuthAction(async () => { await requireSuccess(await api.POST('/api/v1/auth/logout')); }, 'logout');
}
export function LogoutButton() {
  const action = useLogout();
  return <Stack gap="xs"><ActionButton variant="default" onClick={() => action.mutate()} pending={action.isPending}>Выйти</ActionButton>
    {action.error && <ActionError error={action.error} onReset={action.reset} />}</Stack>;
}
export function UserMenu({ fullName, userId }: { fullName: string; userId: string }) {
  const action = useLogout();
  const timing = useLoaderTiming(action.isPending);
  const wide = useMediaQuery('(min-width: 1280px)', false, { getInitialValueInEffect: false });
  const [surname = '', ...names] = fullName.trim().split(/\s+/);
  const initials = `${surname[0] ?? ''}${names[0]?.[0] ?? ''}`;
  const shortName = [surname, ...names.map((name) => `${name[0]}.`)].join(' ');
  return <Stack gap="xs"><Menu position="bottom-end" withinPortal transitionProps={{ duration: 0 }}>
    <Menu.Target><UnstyledButton aria-label={`Меню пользователя: ${fullName}`} className="cr-user-trigger"><span className="cr-user-avatar">{initials}</span>{wide && <span className="cr-user-name">{shortName}</span>}</UnstyledButton></Menu.Target>
    <Menu.Dropdown><Menu.Label className="cr-caption">{fullName}</Menu.Label><Menu.Item className="auth-menu-item" component={Link} to={`/users/${userId}`}>Мой профиль</Menu.Item>
      <Menu.Item className="auth-menu-item" component={Link} to="/password/change">Сменить пароль</Menu.Item>
      <Menu.Divider />
      <Menu.Item className="auth-menu-item" closeMenuOnClick={false} onClick={() => { if (!action.isPending) action.mutate(); }} disabled={timing.indicatorVisible} leftSection={timing.indicatorVisible ? <Loader size="xs" /> : undefined}>Выйти</Menu.Item>
    </Menu.Dropdown></Menu>{action.error && <ActionError error={action.error} onReset={action.reset} />}</Stack>;
}

import { Button, Menu, Stack } from '@mantine/core';
import { Link } from 'react-router';
import { api } from '../../api/client';
import { ActionError } from './AuthLayout';
import { requireSuccess, useAuthAction } from './useAuthAction';

function useLogout() {
  return useAuthAction(async () => { await requireSuccess(await api.POST('/api/v1/auth/logout')); }, 'logout');
}
export function LogoutButton() {
  const action = useLogout();
  return <Stack gap="xs"><Button variant="default" onClick={() => action.mutate()} disabled={action.isPending} loading={action.isPending}>Выйти</Button>
    {action.error && <ActionError error={action.error} onReset={action.reset} />}</Stack>;
}
export function UserMenu({ fullName, userId }: { fullName: string; userId: string }) {
  const action = useLogout();
  return <Stack gap="xs"><Menu position="bottom-end" withinPortal transitionProps={{ duration: 0 }}>
    <Menu.Target><Button variant="subtle" aria-label={`Меню пользователя: ${fullName}`} className="auth-user">Мой аккаунт</Button></Menu.Target>
    <Menu.Dropdown><Menu.Label className="cr-caption">{fullName}</Menu.Label><Menu.Item className="auth-menu-item" component={Link} to={`/users/${userId}`}>Мой профиль</Menu.Item>
      <Menu.Item className="auth-menu-item" component={Link} to="/password/change">Сменить пароль</Menu.Item><Menu.Divider />
      <Menu.Item className="auth-menu-item" onClick={() => action.mutate()} disabled={action.isPending}>Выйти</Menu.Item>
    </Menu.Dropdown></Menu>{action.error && <ActionError error={action.error} onReset={action.reset} />}</Stack>;
}

import { Text } from '@mantine/core';
import { Navigate, Outlet, useLocation } from 'react-router';
import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { AppShell } from './AppShell';
import { authKeys, useAuthSession } from '../features/auth/authQueries';
import { AuthLayout, RetryNotice } from '../features/auth/AuthLayout';
import type { CurrentUser } from '../features/auth/contracts';

export function AuthBoundary() {
  const client = useQueryClient();
  const { csrf, session } = useAuthSession();
  useEffect(() => {
    const expired = () => {
      void client.cancelQueries().then(() => {
        client.removeQueries({ predicate: (query) => query.queryKey[0] !== 'auth' });
        client.setQueryData(authKeys.user, null);
      });
    };
    const passwordRequired = () => {
      client.setQueryData<CurrentUser | null>(authKeys.user, (user) => user ? { ...user, mustChangePassword: true } : user);
      void client.invalidateQueries({ queryKey: authKeys.user });
    };
    window.addEventListener('campus:session-expired', expired);
    window.addEventListener('campus:password-required', passwordRequired);
    return () => { window.removeEventListener('campus:session-expired', expired); window.removeEventListener('campus:password-required', passwordRequired); };
  }, [client]);
  if (csrf.isError || session.isError) return <AuthLayout title="Не удалось открыть приложение" description="Проверьте соединение и повторите загрузку.">
    <RetryNotice message="Не удалось проверить сессию или защиту формы" retry={() => { if (csrf.isError) void csrf.refetch(); if (session.isError) void session.refetch(); }} />
  </AuthLayout>;
  if (csrf.isPending || csrf.data !== true || session.isPending) return <RouteLoading />;
  return <Outlet />;
}
export function GuestGate() {
  const { session } = useAuthSession();
  if (session.data) return <Navigate to={session.data.mustChangePassword ? '/password/change' : '/'} replace />;
  return <Outlet />;
}
export function PasswordGate() {
  const { session } = useAuthSession();
  return session.data ? <Outlet /> : <Navigate to="/login" replace />;
}

export function SessionGate() {
  const { session } = useAuthSession();
  const user = session.data;
  if (!user) return <Navigate to="/login" replace />;
  if (user.mustChangePassword) return <Navigate to="/password/change" replace />;
  return <AppShell session={{ userId: String(user.id), role: user.role }} user={user} />;
}

export function SectionPlaceholder() {
  const { session } = useAuthSession();
  const { pathname } = useLocation();
  const user = session.data;
  if (pathname === '/' && user?.role === 'TEACHER' && !user.teacherApproved) return <Text>Заявка ждёт подтверждения администратора</Text>;
  if ((pathname === '/' || pathname === '/tasks') && user?.role === 'STUDENT' && user.membershipState === 'PENDING') return <Text>Членство в {user.group.name} ждёт подтверждения старосты или преподавателя</Text>;
  return <Text>Раздел появится</Text>;
}

export function RouteLoading() {
  return <Text role="status">Загрузка…</Text>;
}

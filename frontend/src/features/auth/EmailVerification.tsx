import { Button, Stack, Text } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { useParams } from 'react-router';
import { api } from '../../api/client';
import { AuthLayout, LoginLink, RetryNotice } from './AuthLayout';
import { useAuthConfig, useAuthSession } from './authQueries';
import { AdminError } from '../admin/shared';
import { LogoutButton } from './UserMenu';

export function SendVerification({ email }: { email: string }) {
  const mutation = useMutation({ mutationFn: async () => { const result = await api.POST('/api/v1/auth/email/verification', { body: { email } }); if (!result.response.ok) throw new Error(); } });
  return <Stack><Button variant="default" loading={mutation.isPending} onClick={() => mutation.mutate()}>Отправить ссылку подтверждения</Button>
    {mutation.isSuccess && <Text role="status">Если email ожидает подтверждения, ссылка отправлена</Text>}<AdminError error={mutation.error} /></Stack>;
}
export function ConfirmEmailPage() {
  const { token } = useParams(), client = useQueryClient();
  const mutation = useMutation({ mutationFn: async () => { const result = await api.POST('/api/v1/auth/email/confirmation', { body: { token: token ?? '' } }); if (!result.response.ok) throw new Error(); },
    onSuccess: () => client.invalidateQueries({ queryKey: ['email-verification'] }) });
  return <AuthLayout title="Подтверждение email" description="Подтвердите адрес для входа в аккаунт."><Stack>
    {mutation.isSuccess ? <Text role="status">Email подтверждён</Text> : <Button loading={mutation.isPending} onClick={() => mutation.mutate()}>Подтвердить email</Button>}
    <AdminError error={mutation.error} /><LoginLink /></Stack></AuthLayout>;
}
export function EmailGate({ children }: { children: ReactNode }) {
  const config = useAuthConfig(), user = useAuthSession().session.data;
  const enabled = config.data?.emailVerification === 'REQUIRED' && !!user;
  const query = useQuery({ queryKey: ['email-verification', user?.id], enabled, queryFn: async () => {
    const result = await api.GET('/api/v1/auth/email/status'); if (!result.response.ok || !result.data) throw new Error(); return result.data;
  } });
  if (enabled && query.isPending) return <Text role="status">Проверка email…</Text>;
  if (enabled && query.isError) return <RetryNotice message="Не удалось проверить подтверждение email" retry={() => { void query.refetch(); }} />;
  if (enabled && query.data && !query.data.verified) return <AuthLayout title="Подтвердите email" description="Для доступа к приложению подтвердите адрес из письма."><SendVerification email={user!.email} /><LogoutButton /></AuthLayout>;
  return children;
}

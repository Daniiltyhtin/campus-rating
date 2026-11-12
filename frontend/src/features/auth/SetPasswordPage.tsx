import { Button, Stack, Text } from '@mantine/core';
import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { useParams } from 'react-router';
import { api } from '../../api/client';
import { AuthLayout, LoginLink, PasswordField } from './AuthLayout';
import { passwordError } from './formErrors';
import { AdminError } from '../admin/shared';

export function SetPasswordPage() {
  const { token } = useParams(), [password, setPassword] = useState(''), [error, setError] = useState<string>();
  const mutation = useMutation({ mutationFn: async () => { const result = await api.POST('/api/v1/auth/password/set', { body: { token: token ?? '', password } }); if (!result.response.ok) throw new Error(); }, onSuccess: () => setPassword('') });
  return <AuthLayout title="Установка пароля" description="Задайте пароль для входа в аккаунт."><Stack>{mutation.isSuccess ? <Text role="status">Пароль установлен, email подтверждён</Text> : <form onSubmit={(e) => {
    e.preventDefault(); const message = passwordError(password); setError(message); if (!message) mutation.mutate();
  }}><Stack><PasswordField label="Новый пароль" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.currentTarget.value)} error={error} />
    <Button type="submit" loading={mutation.isPending}>Установить пароль</Button><AdminError error={mutation.error} /></Stack></form>}<LoginLink /></Stack></AuthLayout>;
}

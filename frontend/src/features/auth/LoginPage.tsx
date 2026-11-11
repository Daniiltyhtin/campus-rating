import { Anchor, Button, Stack, Text, TextInput } from '@mantine/core';
import { useState } from 'react';
import { Link } from 'react-router';
import { api } from '../../api/client';
import { FormLayout } from '../../app/layouts/PageLayouts';
import { ActionError, AuthLayout, ConfigNotices, PasswordField, RetryNotice } from './AuthLayout';
import { useAuthConfig } from './authQueries';
import { emailError } from './formErrors';
import { requireSuccess, useAuthAction, useFormErrors } from './useAuthAction';

export function LoginPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const config = useAuthConfig();
  const form = useFormErrors();
  const action = useAuthAction(async (body: { email: string; password: string }) => {
    await requireSuccess(await api.POST('/api/v1/auth/login', { body }));
    setPassword('');
  }, 'login');
  return <AuthLayout title="С возвращением" description="Войдите в свой университетский аккаунт.">
    <FormLayout title="Вход" onSubmit={(event) => {
      event.preventDefault();
      const errors = { email: emailError(email.trim()), password: password.length < 1 || password.length > 1024 ? 'Укажите пароль' : undefined };
      form.setErrors(Object.fromEntries(Object.entries(errors).filter((entry): entry is [string, string] => typeof entry[1] === 'string')));
      if (Object.values(errors).some(Boolean) || action.isPending || action.needsRefresh) return;
      action.mutate({ email: email.trim(), password }, { onError: form.fromServer });
    }}><TextInput label="Email" type="email" autoComplete="email" value={email} onChange={(event) => { setEmail(event.currentTarget.value); form.clear('email'); }} error={form.errors.email} />
      <PasswordField label="Пароль" autoComplete="current-password" value={password} onChange={(event) => { setPassword(event.currentTarget.value); form.clear('password'); }} error={form.errors.password} />
      {action.refreshing ? <Text role="status">Обновление сессии…</Text> : action.refreshFailed ? <RetryNotice message="Вход выполнен. Не удалось обновить защиту формы. Повторите обновление." retry={action.refresh} />
        : action.error && <ActionError error={action.error} onReset={action.reset} />}
      <Button type="submit" loading={action.isPending || action.refreshing} disabled={action.isPending || action.needsRefresh}>Войти</Button>
    </FormLayout>
    <Stack gap="xs"><Text className="cr-caption">Первый раз здесь?</Text><Anchor component={Link} to="/register" c="var(--cr-link)">Создать аккаунт</Anchor></Stack>
    <ConfigNotices config={config.data} />
  </AuthLayout>;
}

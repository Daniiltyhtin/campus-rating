import { ActionButton } from '../../app/ActionButton';
import { useTimedMutation } from '../../app/actionTiming';
import { Stack, Text } from '@mantine/core';
import { useState } from 'react';
import { useParams } from 'react-router';
import { api } from '../../api/client';
import { AuthLayout, LoginLink, PasswordField } from './AuthLayout';
import { passwordError } from './formErrors';
import { useFormErrors } from './useAuthAction';
import { AdminError } from '../admin/shared';

export function SetPasswordPage() {
  const { token } = useParams(), [password, setPassword] = useState(''), [passwordRepeat, setPasswordRepeat] = useState('');
  const form = useFormErrors();
  const mutation = useTimedMutation({ mutationFn: async () => { const result = await api.POST('/api/v1/auth/password/set', { body: { token: token ?? '', password } }); if (!result.response.ok) throw new Error(); }, onSuccess: () => { setPassword(''); setPasswordRepeat(''); } });
  return <AuthLayout title="Установка пароля" description="Задайте пароль для входа в аккаунт."><Stack>{mutation.isSuccess ? <Text role="status">Пароль установлен, email подтверждён</Text> : <form onSubmit={(e) => {
    e.preventDefault();
    const errors = { password: passwordError(password), passwordRepeat: passwordRepeat !== password ? 'Пароли не совпадают' : undefined };
    form.setErrors(Object.fromEntries(Object.entries(errors).filter((entry): entry is [string, string] => typeof entry[1] === 'string')));
    if (!Object.values(errors).some(Boolean)) mutation.mutate();
  }}><Stack><PasswordField label="Новый пароль" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.currentTarget.value)} error={form.errors.password} />
    <PasswordField label="Повторите пароль" autoComplete="new-password" value={passwordRepeat} onChange={(e) => { setPasswordRepeat(e.currentTarget.value); form.clear('passwordRepeat'); }} error={form.errors.passwordRepeat} />
    <ActionButton type="submit" pending={mutation.isPending}>Установить пароль</ActionButton><AdminError error={mutation.error} /></Stack></form>}<LoginLink /></Stack></AuthLayout>;
}

import { ActionButton } from '../../app/ActionButton';
import { Text } from '@mantine/core';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import { api } from '../../api/client';
import { FormLayout } from '../../app/layouts/PageLayouts';
import { ActionError, AuthLayout, PasswordField, RetryNotice } from './AuthLayout';
import { passwordError } from './formErrors';
import { requireSuccess, useAuthAction, useFormErrors } from './useAuthAction';
import { LogoutButton } from './UserMenu';

export function ChangePasswordPage() {
  const [oldPassword, setOldPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [passwordRepeat, setPasswordRepeat] = useState('');
  const form = useFormErrors();
  const navigate = useNavigate();
  const action = useAuthAction(async (body: { currentPassword: string; newPassword: string }) => {
    await requireSuccess(await api.POST('/api/v1/auth/password', { body }));
    setOldPassword(''); setNewPassword(''); setPasswordRepeat('');
  }, 'password');
  useEffect(() => { if (action.refreshSuccess) void navigate('/', { replace: true }); }, [action.refreshSuccess, navigate]);
  return <AuthLayout title="Смена пароля" description="Задайте собственный пароль. Если вы вошли с временным паролем, остальные разделы откроются после смены.">
    <FormLayout title="Новый пароль" onSubmit={(event) => {
      event.preventDefault();
      const errors = { currentPassword: oldPassword.length < 1 || oldPassword.length > 1024 ? 'Укажите текущий пароль' : undefined,
        newPassword: passwordError(newPassword), passwordRepeat: passwordRepeat !== newPassword ? 'Пароли не совпадают' : undefined };
      form.setErrors(Object.fromEntries(Object.entries(errors).filter((entry): entry is [string, string] => typeof entry[1] === 'string')));
      if (Object.values(errors).some(Boolean) || action.isPending || action.needsRefresh) return;
      action.mutate({ currentPassword: oldPassword, newPassword }, { onError: form.fromServer, onSuccess: () => { void navigate('/', { replace: true }); } });
    }}><PasswordField label="Текущий пароль" autoComplete="current-password" value={oldPassword} onChange={(event) => { setOldPassword(event.currentTarget.value); form.clear('currentPassword'); }} error={form.errors.currentPassword} />
      <PasswordField label="Новый пароль" autoComplete="new-password" description="От 8 символов, не больше 72 байт UTF-8" value={newPassword} onChange={(event) => { setNewPassword(event.currentTarget.value); form.clear('newPassword'); }} error={form.errors.newPassword} />
      <PasswordField label="Повторите новый пароль" autoComplete="new-password" value={passwordRepeat} onChange={(event) => { setPasswordRepeat(event.currentTarget.value); form.clear('passwordRepeat'); }} error={form.errors.passwordRepeat} />
      {action.refreshing ? <Text role="status">Обновление сессии…</Text> : action.refreshFailed ? <RetryNotice message="Пароль изменён. Не удалось обновить сессию. Повторите обновление." retry={action.refresh} />
        : action.error && <ActionError error={action.error} onReset={action.reset} />}
      <ActionButton type="submit" disabled={action.needsRefresh && !action.isPending} pending={action.isPending || action.refreshing}>Сменить пароль</ActionButton>
    </FormLayout><LogoutButton />
  </AuthLayout>;
}

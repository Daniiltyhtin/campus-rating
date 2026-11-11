import { Anchor, Button, Group, NativeSelect, PasswordInput, Stack, Text } from '@mantine/core';
import type { PasswordInputProps } from '@mantine/core';
import { useId, useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '../../api/client';
import { PageLayout } from '../../app/layouts/PageLayouts';
import { useCampusAppearance } from '../../theme/useCampusAppearance';
import type { AuthConfig } from './contracts';
import { authKeys, csrfToken } from './authQueries';
import { errorMessage } from './formErrors';
import './auth.css';

export function ThemeSelect() {
  const { appearance, setAppearance } = useCampusAppearance();
  return <NativeSelect label="Тема интерфейса" value={appearance} onChange={(event) => {
    const value = event.currentTarget.value;
    if (value === 'light' || value === 'dark' || value === 'contrast') setAppearance(value);
  }} data={[{ value: 'light', label: 'Светлая' }, { value: 'dark', label: 'Тёмная' }, { value: 'contrast', label: 'Высокий контраст' }]} />;
}
export function AuthLayout({ title, description, children }: { title: string; description: string; children: ReactNode }) {
  return <main className="auth-page"><PageLayout title={title} description={description} actions={<ThemeSelect />}>
    <div className="auth-grid"><Stack gap="lg" className="auth-intro">
      <Text className="cr-kicker">Учёба. Участие. Результат.</Text>
      <Text size="lg">Университетская жизнь в одном месте</Text>
      <Text className="cr-caption">Задания и дедлайны, баллы за активность и достижения вашего сообщества.</Text>
      <Group gap="xl" className="cr-balances"><span className="cr-ar">АР · рейтинг</span><span className="cr-bua">БУА · учёба</span><span className="cr-boa">БОА · участие</span></Group>
    </Stack><Stack gap="lg" className="auth-form">{children}</Stack></div>
  </PageLayout></main>;
}
export function RetryNotice({ message, retry }: { message: string; retry: () => void }) {
  return <Stack gap="sm"><Text role="alert">{message}</Text><Button variant="default" onClick={retry}>Повторить</Button></Stack>;
}
export function ConfigNotices({ config }: { config: AuthConfig | undefined }) {
  return <Stack gap="xs">{config?.demo && <Text className="cr-caption">Демо-стенд, данные удаляются</Text>}
    {config?.emailVerification === 'OFF' && <Text className="cr-caption">Подтверждение email на этом стенде выключено</Text>}</Stack>;
}
export function LoginLink() { return <Anchor component={Link} to="/login" c="var(--cr-link)">Войти</Anchor>; }
export function PasswordField(props: PasswordInputProps) {
  const id = useId();
  const [visible, setVisible] = useState(false);
  const descriptions = [props.description ? `${id}-description` : '', props.error ? `${id}-error` : ''].filter(Boolean).join(' ');
  return <PasswordInput {...props} id={id} visible={visible} onVisibilityChange={setVisible}
    aria-invalid={Boolean(props.error)} aria-describedby={descriptions || undefined}
    styles={{ input: { height: 'auto' }, innerInput: { minHeight: 'var(--cr-touch-target)' },
      description: { color: 'var(--cr-muted)' }, error: { color: 'var(--cr-status-overdue)' } }}
    visibilityToggleButtonProps={{ tabIndex: 0, 'aria-label': visible ? 'Скрыть пароль' : 'Показать пароль' }} />;
}
export function ActionError({ error, onReset }: { error: unknown; onReset: () => void }) {
  const client = useQueryClient();
  const refresh = useMutation({ mutationFn: csrfToken, retry: false, onSuccess: (ready) => {
    client.setQueryData(authKeys.csrf, ready); onReset();
  } });
  return <Stack gap="xs"><Text role="alert">{errorMessage(error)}</Text>
    {error instanceof ApiError && error.code === 'CSRF_INVALID' && <Button variant="default" loading={refresh.isPending} disabled={refresh.isPending} onClick={() => refresh.mutate()}>Обновить защиту формы</Button>}
    {refresh.error && <Text role="alert">Не удалось обновить защиту формы. Попробуйте снова.</Text>}</Stack>;
}

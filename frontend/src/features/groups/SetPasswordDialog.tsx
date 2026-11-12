import { Button, Group, Modal, Stack, Text } from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../../api/client';
import { dataOf } from '../tasks/helpers';
import { AdminError } from '../admin/shared';

export function SetPasswordDialog({ user }: { user: { id: number; fullName: string; role: 'STUDENT' | 'TEACHER' } }) {
  const client = useQueryClient(), [opened, setOpened] = useState(false), [copied, setCopied] = useState(false);
  const mutation = useMutation({ gcTime: 0, retry: false,
    mutationFn: () => dataOf(api.POST('/api/v1/users/{userId}/temporary-password', { params: { path: { userId: user.id } } })),
    onSuccess: () => { void client.invalidateQueries({ queryKey: ['admin-users'] }); void client.invalidateQueries({ queryKey: ['group-students'] }); } });
  function close() { if (!mutation.isPending) { setOpened(false); mutation.reset(); setCopied(false); } }
  return <><Button variant="default" onClick={() => { mutation.reset(); setOpened(true); }}>Выставить временный пароль</Button>
    <Modal opened={opened} onClose={close} title="Временный пароль" closeOnEscape={!mutation.isPending} closeOnClickOutside={!mutation.isPending}>
      <Stack>{mutation.data ? <><Text>Пароль выставлен, {user.role === 'STUDENT' ? 'студент' : 'преподаватель'} сменит его при входе</Text>
        <Text component="code" aria-label="Временный пароль">{mutation.data.password}</Text>
        <Button onClick={() => { void navigator.clipboard.writeText(mutation.data!.password).then(() => setCopied(true)); }}>Скопировать</Button>{copied && <Text role="status">Скопировано</Text>}
        <Button onClick={close}>Закрыть</Button></> : <><Text>Выставить временный пароль для {user.fullName}? Пользователь сменит его при входе, все его сессии завершатся.</Text>
        <Group><Button loading={mutation.isPending} onClick={() => mutation.mutate()}>Выставить</Button><Button variant="default" disabled={mutation.isPending} onClick={close}>Отмена</Button></Group></>}
        <AdminError error={mutation.error} /></Stack>
    </Modal></>;
}

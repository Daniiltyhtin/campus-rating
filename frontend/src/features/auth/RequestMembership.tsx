import { Button, NativeSelect, Stack, Text } from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../../api/client';
import { errorText, fieldError } from '../tasks/helpers';
import { authKeys, useFaculties, useFacultyOptions } from './authQueries';
import { currentUser } from './contracts';

export function RequestMembership({ groupName }: { groupName: string }) {
  const client = useQueryClient();
  const [facultyId, setFacultyId] = useState(''), [groupId, setGroupId] = useState('');
  const faculties = useFaculties(), groups = useFacultyOptions(facultyId, 'STUDENT');
  const mutation = useMutation({ retry: false, mutationFn: async () => {
    const result = await api.PUT('/api/v1/me/membership', { body: { facultyId: Number(facultyId), groupId: Number(groupId) } });
    if (result.error) throw result.error;
    return currentUser(result.data);
  }, onSuccess: (user) => { client.setQueryData(authKeys.user, user); } });
  const groupError = fieldError(mutation.error, 'groupId') ?? fieldError(mutation.error, 'facultyId');
  return <Stack gap="sm">
    <Text>Заявка в {groupName} отклонена. Выберите группу и подайте заявку заново.</Text>
    <form onSubmit={(event) => { event.preventDefault(); if (facultyId && groupId && !mutation.isPending) mutation.mutate(); }}><Stack gap="sm">
      <NativeSelect label="Факультет" value={facultyId} disabled={!faculties.isSuccess || mutation.isPending}
        onChange={(event) => { setFacultyId(event.currentTarget.value); setGroupId(''); }}
        data={[{ value: '', label: 'Выберите факультет' }, ...(faculties.data ?? []).map((item) => ({ value: String(item.id), label: item.name }))]} />
      <NativeSelect label="Группа" value={groupId} disabled={!groups.isSuccess || mutation.isPending} error={groupError}
        onChange={(event) => setGroupId(event.currentTarget.value)}
        data={[{ value: '', label: 'Выберите группу' }, ...(groups.data ?? []).map((item) => ({ value: String(item.id), label: item.name }))]} />
      {mutation.error && !groupError && <Text role="alert">{errorText(mutation.error)}</Text>}
      <div><Button type="submit" loading={mutation.isPending} disabled={!facultyId || !groupId}>Подать заявку</Button></div>
    </Stack></form>
  </Stack>;
}

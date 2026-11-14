import { useLoaderTiming } from '../../app/loaderTiming';
import { ActionButton } from '../../app/ActionButton';
import { useTimedMutation } from '../../app/actionTiming';
import { NativeSelect, Stack, Text } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../../api/client';
import { errorText, fieldError } from '../tasks/helpers';
import { authKeys, useFaculties, useFacultyOptions } from './authQueries';
import { RetryNotice } from './AuthLayout';
import { currentUser } from './contracts';

export function RequestMembership({ groupName }: { groupName: string }) {
  const client = useQueryClient();
  const [facultyId, setFacultyId] = useState(''), [groupId, setGroupId] = useState('');
  const faculties = useFaculties(), groups = useFacultyOptions(facultyId, 'STUDENT');
  const facultiesTiming = useLoaderTiming(faculties.isPending);
  const groupsTiming = useLoaderTiming(!!facultyId && groups.isPending);
  const mutation = useTimedMutation({ retry: false, mutationFn: async () => {
    const result = await api.PUT('/api/v1/me/membership', { body: { facultyId: Number(facultyId), groupId: Number(groupId) } });
    if (result.error) throw result.error;
    return currentUser(result.data);
  }, onSuccess: (user) => { client.setQueryData(authKeys.user, user); } });
  const groupError = fieldError(mutation.error, 'groupId') ?? fieldError(mutation.error, 'facultyId');
  return <Stack gap="sm">
    <Text>Заявка в {groupName} отклонена. Выберите группу и подайте заявку заново.</Text>
    <form onSubmit={(event) => { event.preventDefault(); if (facultyId && groupId && !mutation.isPending) mutation.mutate(); }}><Stack gap="sm">
      <NativeSelect label="Факультет" value={facultyId} disabled={!faculties.isSuccess || !facultiesTiming.resultReady || mutation.isPending}
        onChange={(event) => { setFacultyId(event.currentTarget.value); setGroupId(''); }}
        data={[{ value: '', label: 'Выберите факультет' }, ...(faculties.data ?? []).map((item) => ({ value: String(item.id), label: item.name }))]} />
      {facultiesTiming.indicatorVisible && <Text role="status">Загрузка факультетов…</Text>}
      {facultiesTiming.resultReady && faculties.isError && <RetryNotice message="Не удалось загрузить факультеты" retry={() => { void faculties.refetch(); }} />}
      <NativeSelect label="Группа" value={groupId} disabled={!groups.isSuccess || !groupsTiming.resultReady || mutation.isPending} error={groupError}
        onChange={(event) => setGroupId(event.currentTarget.value)}
        data={[{ value: '', label: 'Выберите группу' }, ...(groupsTiming.resultReady ? groups.data ?? [] : []).map((item) => ({ value: String(item.id), label: item.name }))]} />
      {facultyId && groupsTiming.indicatorVisible && <Text role="status">Загрузка справочника…</Text>}
      {groupsTiming.resultReady && groups.isError && <RetryNotice message="Не удалось загрузить справочник" retry={() => { void groups.refetch(); }} />}
      {mutation.error && !groupError && <Text role="alert">{errorText(mutation.error)}</Text>}
      <div><ActionButton type="submit" pending={mutation.isPending} disabled={!facultyId || !groupId}>Подать заявку</ActionButton></div>
    </Stack></form>
  </Stack>;
}

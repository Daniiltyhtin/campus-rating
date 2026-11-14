import { ActionButton } from '../../../app/ActionButton';
import { useTimedMutation } from '../../../app/actionTiming';
import { Button, Group, Modal, NativeSelect, Stack, Text } from '@mantine/core';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../../../api/client';
import { dataOf, errorText, fieldError } from '../../tasks/helpers';
import type { DirectoryEntry } from './contracts';
import { headsKey, useGroupHeads } from './groupHeadsQuery';

type Heads = ReturnType<typeof useGroupHeads>;

function AssignDialog({ group, close }: { group: DirectoryEntry; close: () => void }) {
  const client = useQueryClient();
  const [studentId, setStudentId] = useState('');
  const students = useQuery({ queryKey: ['group-students', group.id], queryFn: () => dataOf(api.GET('/api/v1/groups/{groupId}/students', { params: { path: { groupId: group.id } } })) });
  const assign = useTimedMutation({ mutationFn: async () => {
    const result = await api.PUT('/api/v1/admin/group-heads/{groupId}', { params: { path: { groupId: group.id } }, body: { studentId: Number(studentId) } });
    if (result.error) throw result.error;
    return result.data;
  }, onSuccess: async () => { await client.invalidateQueries({ queryKey: headsKey }); close(); } });
  const active = (students.data?.items ?? []).filter((item) => item.membershipState === 'ACTIVE');
  return <Modal opened onClose={() => { if (!assign.isPending) close(); }} title={`Староста ${group.name}`} centered closeButtonProps={{ 'aria-label': 'Закрыть' }}>
    <form onSubmit={(event) => { event.preventDefault(); if (studentId && !assign.isPending) assign.mutate(); }}><Stack>
      {students.isError ? <Text role="alert">{errorText(students.error)}</Text>
        : <NativeSelect label="Студент" value={studentId} disabled={!students.isSuccess || assign.isPending} error={fieldError(assign.error, 'studentId')}
          description="Только студенты с подтверждённым членством" onChange={(event) => setStudentId(event.currentTarget.value)}
          data={[{ value: '', label: active.length || !students.isSuccess ? 'Выберите студента' : 'Нет студентов с подтверждённым членством' },
            ...active.map((item) => ({ value: String(item.id), label: item.fullName }))]} />}
      {assign.error && !fieldError(assign.error, 'studentId') && <Text role="alert">{errorText(assign.error)}</Text>}
      <Group justify="end"><Button variant="default" disabled={assign.isPending} onClick={close}>Отмена</Button>
        <ActionButton type="submit" pending={assign.isPending} disabled={!studentId}>Назначить</ActionButton></Group>
    </Stack></form>
  </Modal>;
}

export function HeadCell({ group, heads, disabled }: { group: DirectoryEntry; heads: Heads; disabled: boolean }) {
  const client = useQueryClient();
  const [assigning, setAssigning] = useState(false);
  const remove = useTimedMutation({ mutationFn: async () => {
    const result = await api.DELETE('/api/v1/admin/group-heads/{groupId}', { params: { path: { groupId: group.id } } });
    if (result.error) throw result.error;
  }, onSettled: () => client.invalidateQueries({ queryKey: headsKey }) });
  if (heads.isError) return <Text>Не удалось загрузить</Text>;
  if (!heads.data) return null;
  const head = heads.data.items.find((item) => item.groupId === group.id);
  return <Stack gap={4}>
    <Text>{head ? head.student.fullName : '—'}</Text>
    <Group gap="xs">
      <Button size="xs" variant="default" aria-label={`Назначить старосту ${group.name}`} disabled={disabled || remove.isPending} onClick={() => setAssigning(true)}>{head ? 'Сменить' : 'Назначить'}</Button>
      {head && <ActionButton size="xs" variant="default" aria-label={`Снять старосту ${group.name}`} disabled={disabled} pending={remove.isPending} onClick={() => remove.mutate()}>Снять</ActionButton>}
    </Group>
    {remove.error && <Text role="alert">{errorText(remove.error)}</Text>}
    {assigning && <AssignDialog group={group} close={() => setAssigning(false)} />}
  </Stack>;
}

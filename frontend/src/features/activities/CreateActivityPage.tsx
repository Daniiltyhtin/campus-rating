import { ActionButton } from '../../app/ActionButton';
import { useTimedMutation } from '../../app/actionTiming';
import { Checkbox, NativeSelect, Stack, Text, Textarea, TextInput } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import { api } from '../../api/client';
import { AsyncContent, FormLayout } from '../../app/layouts/PageLayouts';
import { useFormErrors } from '../auth/useAuthAction';
import { ActivityAccess, ActivityFailure, ActivityLink, ActivityPage } from './ActivityShared';
import { parseActivity } from './contracts';
import { activityKeys, loadState, read, useAccessibleGroups } from './queries';
import { activityClock, validateCreation } from './time';
import type { CreationFields } from './time';
import type { components } from '../../api/schema';

function CreateForm({ userId }: { userId: number }) {
  const groups = useAccessibleGroups(userId);
  const client = useQueryClient();
  const navigate = useNavigate();
  const form = useFormErrors();
  const [fields, setFields] = useState<CreationFields>({ title: '', description: '', type: 'ACADEMIC', groupIds: [], startsAt: '', maxPoints: '' });
  const change = (field: Exclude<keyof CreationFields, 'groupIds'>, value: string) => { setFields((old) => ({ ...old, [field]: value })); form.clear(field); };
  const create = useTimedMutation({ retry: false, mutationFn: (body: components['schemas']['ActivityCreate']) => read(api.POST('/api/v1/activities', { body }), parseActivity),
    onError: form.fromServer, onSuccess: async () => { await client.invalidateQueries({ queryKey: activityKeys.root }); await navigate('/activities'); } });
  const available = groups.data?.items ?? [];
  return <ActivityPage title="Новая активность" description="Задайте группы, время и максимум баллов. Начало указывается по Москве." actions={<ActivityLink to="/activities">К активностям</ActivityLink>}>
    <div className="activity-form"><FormLayout title="Параметры активности" onSubmit={(event) => {
      event.preventDefault();
      const validated = validateCreation(fields, available, activityClock.now());
      if (!validated.ok) { form.setErrors(validated.errors); return; }
      if (!groups.isSuccess || available.length === 0 || create.isPending) return;
      form.setErrors({}); create.mutate(validated.body);
    }}>
      <TextInput label="Название" value={fields.title} error={form.errors.title} onChange={(event) => change('title', event.currentTarget.value)} />
      <Textarea label="Описание" value={fields.description} error={form.errors.description} autosize minRows={3} onChange={(event) => change('description', event.currentTarget.value)} />
      <NativeSelect label="Тип активности" value={fields.type} error={form.errors.type} onChange={(event) => change('type', event.currentTarget.value)} data={[{ value: 'ACADEMIC', label: 'Учебная · БУА' }, { value: 'SOCIAL', label: 'Общественная · БОА' }]} />
      <AsyncContent state={loadState(groups, available.length === 0, 'Нет доступных групп. Дождитесь подтверждения администратора или выдачи доступа.')}>
        <Checkbox.Group label="Группы" value={fields.groupIds} error={form.errors.groupIds} onChange={(value) => { setFields((old) => ({ ...old, groupIds: value })); form.clear('groupIds'); }}>
          <Stack gap="xs" mt="xs">{available.map((group) => <Checkbox key={group.id} value={String(group.id)} label={`${group.name} · ${group.faculty.code}`} />)}</Stack>
        </Checkbox.Group>
      </AsyncContent>
      <TextInput label="Дата и время начала (Москва)" type="datetime-local" value={fields.startsAt} error={form.errors.startsAt} onChange={(event) => change('startsAt', event.currentTarget.value)} />
      <TextInput label="Максимум баллов" inputMode="numeric" description="Целое число от 2 до 10000. Начисления выполняются при подтверждении участия." value={fields.maxPoints} error={form.errors.maxPoints} onChange={(event) => change('maxPoints', event.currentTarget.value)} />
      {create.error != null && <ActivityFailure error={create.error} />}
      <ActionButton type="submit" disabled={!groups.isSuccess || available.length === 0} pending={create.isPending}>Создать активность</ActionButton>
      <Text className="cr-caption">Учебная активность даёт БУА, общественная — БОА. Для общественной активности студентам нужна предварительная запись.</Text>
    </FormLayout></div>
  </ActivityPage>;
}
export function CreateActivityPage() { return <ActivityAccess teacherOnly>{(user) => <CreateForm userId={user.id} />}</ActivityAccess>; }

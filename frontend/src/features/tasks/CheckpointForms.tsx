import { Button, Checkbox, Input, Stack, Text, Textarea, TextInput } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import { api } from '../../api/client';
import { AsyncContent, FormLayout, PageLayout } from '../../app/layouts/PageLayouts';
import { useAuthSession } from '../auth/authQueries';
import { ErrorMessage, TaskButton, TaskFailure } from './shared';
import { dataOf, fieldError, loadState, moscowDeadline, type Schemas, invalidateTaskData } from './helpers';

export function CheckpointEdit({ checkpoint, onSaved }: { checkpoint: Schemas['Checkpoint']; onSaved: () => void }) {
  const [title, setTitle] = useState(checkpoint.title);
  const [description, setDescription] = useState(checkpoint.description);
  const [titleError, setTitleError] = useState<string>();
  const client = useQueryClient();
  const mutation = useMutation({
    mutationFn: () =>
      dataOf(
        api.PATCH('/api/v1/checkpoints/{checkpointId}', {
          params: { path: { checkpointId: checkpoint.id } },
          body: { title, description },
        }),
      ),
    onSuccess: async () => {
      await invalidateTaskData(client);
      onSaved();
    },
  });
  return (
    <FormLayout
      title="Редактировать описание"
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        if (!title.trim()) { setTitleError('Укажите название'); return; }
        mutation.mutate();
      }}
    >
      <TextInput
        label="Название"
        required
        maxLength={200}
        value={title}
        onChange={(event) => { setTitle(event.currentTarget.value); setTitleError(undefined); }}
        error={titleError ?? fieldError(mutation.error, 'title')}
      />
      <Textarea
        label="Описание"
        maxLength={10000}
        value={description}
        onChange={(event) => setDescription(event.currentTarget.value)}
        error={fieldError(mutation.error, 'description')}
      />
      {!['title', 'description', 'groupIds', 'deadline', 'cost'].some((field) => fieldError(mutation.error, field)) && <ErrorMessage error={mutation.error} />}
      <TaskButton type="submit" loading={mutation.isPending}>
        Сохранить
      </TaskButton>
      <Button variant="default" onClick={onSaved}>
        Отмена
      </Button>
    </FormLayout>
  );
}

export function CheckpointNew() {
  const user = useAuthSession().session.data;
  if (user?.role !== 'TEACHER') return <TaskFailure forbidden />;
  return <CheckpointNewForm />;
}

function CheckpointNewForm() {
  const defaults = useQuery({
    queryKey: ['checkpoint-defaults'],
    queryFn: () => dataOf(api.GET('/api/v1/checkpoints/defaults')),
  });
  const groups = useQuery({
    queryKey: ['accessible-groups'],
    queryFn: () => dataOf(api.GET('/api/v1/me/accessible-groups')),
  });
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [deadline, setDeadline] = useState('');
  const [cost, setCost] = useState('60');
  const [groupIds, setGroupIds] = useState<number[]>([]);
  const [errors, setErrors] = useState<Record<string, string | undefined>>({});
  const navigate = useNavigate();
  const client = useQueryClient();
  const mutation = useMutation({
    mutationFn: () =>
      dataOf(
        api.POST('/api/v1/checkpoints', {
          body: {
            title,
            description,
            deadline: moscowDeadline(deadline),
            cost: Number(cost),
            groupIds,
          },
        }),
      ),
    onSuccess: async (checkpoint) => {
      await invalidateTaskData(client);
      void navigate(`/checkpoints/${checkpoint.id}`);
    },
  });
  return (
    <PageLayout title="Новая точка">
      <AsyncContent state={loadState(defaults)}>
        <AsyncContent
          state={loadState(
            groups,
            groups.data?.items.length === 0,
            'Нет доступных групп. Создание точки станет доступно после получения допуска или доступа к группам.',
          )}
        >
          <FormLayout
            title="Параметры точки"
            noValidate
            maxWidth="44rem"
            onSubmit={(event) => {
              event.preventDefault();
              const nextErrors = {
                title: title.trim() ? undefined : 'Укажите название',
                groupIds: groupIds.length ? undefined : 'Выберите хотя бы одну группу',
                deadline: deadline && Number.isFinite(Date.parse(moscowDeadline(deadline))) ? undefined : 'Укажите дедлайн',
                cost: Number.isInteger(Number(cost)) && Number(cost) >= 1 && Number(cost) <= 1000 ? undefined : 'Укажите стоимость от 1 до 1000 АР',
              };
              setErrors(nextErrors);
              if (Object.values(nextErrors).some(Boolean)) return;
              mutation.mutate();
            }}
          >
            <TextInput
              label="Название"
              value={title}
              required
              maxLength={200}
              onChange={(event) => setTitle(event.currentTarget.value)}
              error={errors.title ?? fieldError(mutation.error, 'title')}
            />
            <Textarea
              label="Описание"
              value={description}
              maxLength={10000}
              onChange={(event) => setDescription(event.currentTarget.value)}
              error={fieldError(mutation.error, 'description')}
            />
            <fieldset aria-required="true" aria-invalid={!!(errors.groupIds ?? fieldError(mutation.error, 'groupIds'))}
              style={{ border: `var(--cr-rule-width) solid var(${errors.groupIds || fieldError(mutation.error, 'groupIds') ? '--cr-status-overdue' : '--cr-field'})`, borderRadius: 'var(--mantine-radius-sm)' }}>
              <legend><Input.Label required>Группы</Input.Label></legend>
              <Stack>
                {groups.data?.items.map((group) => (
                  <Checkbox
                    key={group.id}
                    label={group.name}
                    checked={groupIds.includes(group.id)}
                    onChange={(event) => {
                      setErrors((current) => ({ ...current, groupIds: undefined }));
                      setGroupIds(
                        event.currentTarget.checked
                          ? [...groupIds, group.id]
                          : groupIds.filter((id) => id !== group.id),
                      );
                    }}
                  />
                ))}
              </Stack>
              {(errors.groupIds || fieldError(mutation.error, 'groupIds')) && (
                <Text role="alert" c="var(--cr-status-overdue)">{fieldError(mutation.error, 'groupIds') ?? 'Выберите хотя бы одну группу'}</Text>
              )}
            </fieldset>
            <TextInput
              type="datetime-local"
              label="Дедлайн (МСК)"
              required
              value={deadline}
              onChange={(event) => setDeadline(event.currentTarget.value)}
              error={errors.deadline ?? fieldError(mutation.error, 'deadline')}
            />
            <TextInput
              type="number"
              label="Стоимость, АР"
              required
              min={1}
              max={1000}
              step={1}
              value={cost}
              onChange={(event) => setCost(event.currentTarget.value)}
              error={errors.cost ?? fieldError(mutation.error, 'cost')}
            />
            <Text>
              Шаг штрафа: {defaults.data?.penaltyStep} АР; интервал: {defaults.data?.penaltyIntervalSeconds} с.
              Фиксируются при создании.
            </Text>
            {!['title', 'description', 'groupIds', 'deadline', 'cost'].some((field) => fieldError(mutation.error, field)) && <ErrorMessage error={mutation.error} />}
            <TaskButton type="submit" loading={mutation.isPending}>
              Создать точку
            </TaskButton>
          </FormLayout>
        </AsyncContent>
      </AsyncContent>
    </PageLayout>
  );
}

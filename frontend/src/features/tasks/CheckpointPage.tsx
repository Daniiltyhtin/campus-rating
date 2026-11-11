import { Button, Checkbox, Group, Modal, NativeSelect, Stack, Tabs, Text, Textarea } from '@mantine/core';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useParams } from 'react-router';
import { api } from '../../api/client';
import { AsyncContent, PageLayout, TeacherTableLayout } from '../../app/layouts/PageLayouts';
import { useAuthSession } from '../auth/authQueries';
import { CheckpointEdit } from './CheckpointForms';
import { AssignmentDetails } from './TaskPage';
import { ErrorMessage, More, Status } from './shared';
import { dataOf, dateTime, fieldError, loadState, statuses, type Schemas, invalidateTaskData } from './helpers';

function workCount(n: number) {
  if (n % 10 === 1 && n % 100 !== 11) return `${n} работу`;
  if (n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 12 || n % 100 > 14)) return `${n} работы`;
  return `${n} работ`;
}

export function CheckpointPage() {
  const checkpointId = Number(useParams().id);
  const user = useAuthSession().session.data;
  const session = user ? { userId: String(user.id), role: user.role } : null;
  const query = useQuery({
    queryKey: ['checkpoint', checkpointId],
    queryFn: () => dataOf(api.GET('/api/v1/checkpoints/{checkpointId}', { params: { path: { checkpointId } } })),
  });
  const [status, setStatus] = useState<Schemas['AssignmentStatus']>();
  const [groupId, setGroupId] = useState<string>('');
  const [selected, setSelected] = useState<number[]>([]);
  const [opened, setOpened] = useState<number>();
  const [returnId, setReturnId] = useState<number>();
  const [editing, setEditing] = useState(false);
  const [names, setNames] = useState<Record<number, string>>({});
  const rowsQuery = useInfiniteQuery({
    queryKey: ['checkpoint-assignments', checkpointId, status, groupId],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      dataOf(
        api.GET('/api/v1/checkpoints/{checkpointId}/assignments', {
          params: {
            path: { checkpointId },
            query: { status, groupId: groupId ? Number(groupId) : undefined, cursor: pageParam },
          },
        }),
      ),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const rows = rowsQuery.data?.pages.flatMap((page) => page.items) ?? [];
  const client = useQueryClient();
  const accept = useMutation({
    mutationFn: (ids: number[]) =>
      dataOf(
        api.POST('/api/v1/checkpoints/{checkpointId}/assignments/accept', {
          params: { path: { checkpointId } },
          body: { assignmentIds: ids },
        }),
      ),
    onSuccess: async () => {
      setSelected([]);
      await invalidateTaskData(client);
    },
  });
  const checkpoint = query.data;
  const canEdit = session?.role === 'TEACHER' && session.userId === String(checkpoint?.author.id);
  const counts = rowsQuery.data?.pages[0]?.statusCounts;
  function filterChanged() {
    setSelected([]);
    accept.reset();
  }
  return (
    <PageLayout title={checkpoint?.title ?? 'Точка'}>
      <AsyncContent state={loadState(query)}>
        {checkpoint && (
          <Stack>
            <Text>{checkpoint.description}</Text>
            <Text>
              Дедлайн: {dateTime(checkpoint.deadline)} (МСК) · Стоимость: {checkpoint.cost} АР
            </Text>
            <Text>
              Штраф: {checkpoint.penaltyStep} АР каждые {checkpoint.penaltyIntervalSeconds} с
            </Text>
            {canEdit && (
              <Button variant="default" onClick={() => setEditing(true)}>
                Редактировать описание
              </Button>
            )}
            {editing && <CheckpointEdit checkpoint={checkpoint} onSaved={() => setEditing(false)} />}
            <Tabs
              value={status ?? 'all'}
              onChange={(value) => {
                setStatus(value === 'all' ? undefined : (value as Schemas['AssignmentStatus']));
                filterChanged();
              }}
            >
              <Tabs.List>
                <Tabs.Tab value="all">Все</Tabs.Tab>
                {Object.entries(statuses).map(([value, label]) => (
                  <Tabs.Tab key={value} value={value}>
                    {label} · {counts?.[value as Schemas['AssignmentStatus']] ?? 0}
                  </Tabs.Tab>
                ))}
              </Tabs.List>
            </Tabs>
            <NativeSelect
              label="Группа"
              value={groupId}
              onChange={(event) => {
                setGroupId(event.currentTarget.value);
                filterChanged();
              }}
              data={[
                { value: '', label: 'Все группы' },
                ...checkpoint.groups.map((group) => ({ value: String(group.id), label: group.name })),
              ]}
            />
            {canEdit && (
              <div className="cr-selection">
                <strong>Выбрано {selected.length}</strong>
                <Button variant="subtle" onClick={() => setSelected([])}>
                  Снять выбор
                </Button>
                <Button
                  disabled={!selected.length}
                  loading={accept.isPending}
                  onClick={() => {
                    setNames(Object.fromEntries(rows.map((row) => [row.assignmentId, row.student.fullName])));
                    accept.mutate([...selected]);
                  }}
                >
                  Принять {workCount(selected.length)}
                </Button>
              </div>
            )}
            {accept.data && (
              <div role="status" className="cr-success">
                <strong>
                  Принято {accept.data.accepted.length} из {accept.variables?.length}
                </strong>
                {accept.data.skipped.map((item) => (
                  <Text key={item.assignmentId}>
                    {names[item.assignmentId] ??
                      `Назначение ${item.assignmentId}`}{' '}
                    пропущен: состояние работы изменилось, принять можно только сданную работу.
                  </Text>
                ))}
              </div>
            )}
            <ErrorMessage error={accept.error} />
            <TeacherTableLayout
              caption="Работы студентов"
              rows={rows}
              keyForRow={(row) => row.assignmentId}
              state={loadState(rowsQuery, !rows.length, 'Работ по выбранным фильтрам нет. Измените фильтры.')}
              isSelected={(row) => selected.includes(row.assignmentId)}
              columns={[
                {
                  key: 'student',
                  title: 'Студент',
                  render: (row) =>
                    canEdit ? (
                      <Checkbox
                        label={row.student.fullName}
                        disabled={row.state !== 'SUBMITTED' || accept.isPending}
                        checked={selected.includes(row.assignmentId)}
                        onChange={(event) => {
                          if (event.currentTarget.checked) {
                            setSelected([...selected, row.assignmentId]);
                          } else {
                            setSelected(selected.filter((id) => id !== row.assignmentId));
                          }
                          accept.reset();
                        }}
                      />
                    ) : (
                      row.student.fullName
                    ),
                },
                { key: 'group', title: 'Группа', render: (row) => row.group.name },
                { key: 'status', title: 'Статус', render: (row) => <Status status={row.status} /> },
                {
                  key: 'submitted',
                  title: 'Сдано',
                  render: (row) => (
                    <Stack gap="xs">
                      {row.submittedAt ? dateTime(row.submittedAt) : 'Ещё не сдано'}
                      <Button
                        variant="subtle"
                        disabled={!row.submittedAt}
                        onClick={() => setOpened(row.assignmentId)}
                      >
                        Открыть сдачу
                      </Button>
                    </Stack>
                  ),
                },
                { key: 'charged', title: 'Списано', render: (row) => `${row.charged} АР` },
                {
                  key: 'actions',
                  title: 'Действия',
                  render: (row) =>
                    canEdit &&
                    row.state === 'SUBMITTED' && (
                      <Button variant="default" onClick={() => setReturnId(row.assignmentId)}>
                        Вернуть…
                      </Button>
                    ),
                },
              ]}
            />
            <More {...rowsQuery} />
            {rowsQuery.isFetchNextPageError && (
              <Text role="alert">Не удалось загрузить следующую страницу. Попробуйте ещё раз.</Text>
            )}
            <Modal opened={opened !== undefined} onClose={() => setOpened(undefined)} title="Материалы сдачи">
              {opened !== undefined && <AssignmentDetails assignmentId={opened} />}
            </Modal>
            <Modal opened={returnId !== undefined} onClose={() => setReturnId(undefined)} title="Вернуть на доработку">
              {returnId !== undefined && (
                <ReturnForm
                  key={returnId}
                  assignmentId={returnId}
                  onReturned={() => {
                    setSelected((ids) => ids.filter((id) => id !== returnId));
                    setReturnId(undefined);
                  }}
                />
              )}
            </Modal>
          </Stack>
        )}
      </AsyncContent>
    </PageLayout>
  );
}

function ReturnForm({ assignmentId, onReturned }: { assignmentId: number; onReturned: () => void }) {
  const [comment, setComment] = useState('');
  const [required, setRequired] = useState(false);
  const client = useQueryClient();
  const mutation = useMutation({
    mutationFn: () =>
      dataOf(
        api.POST('/api/v1/assignments/{assignmentId}/return', {
          params: { path: { assignmentId } },
          body: { comment },
        }),
      ),
    onSuccess: async () => {
      await invalidateTaskData(client);
      onReturned();
    },
  });
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        if (!comment.trim()) {
          setRequired(true);
          return;
        }
        mutation.mutate();
      }}
    >
      <Stack>
        <Textarea
          label="Комментарий возврата"
          aria-required="true"
          maxLength={2000}
          value={comment}
          onChange={(event) => {
            setComment(event.currentTarget.value);
            setRequired(false);
          }}
          error={required ? 'Укажите комментарий' : fieldError(mutation.error, 'comment')}
        />
        <Text>После возврата штраф возобновится, если дедлайн прошёл и стоимость ещё не списана.</Text>
        <ErrorMessage error={mutation.error} />
        <Group>
          <Button type="submit" loading={mutation.isPending}>
            Вернуть работу
          </Button>
        </Group>
      </Stack>
    </form>
  );
}

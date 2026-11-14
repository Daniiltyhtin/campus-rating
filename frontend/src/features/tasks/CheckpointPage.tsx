import { ActionButton } from '../../app/ActionButton';
import { useTimedInfiniteQuery } from '../../app/infiniteTiming';
import { useTimedMutation } from '../../app/actionTiming';
import { Button, Checkbox, Menu, Modal, NativeSelect, Stack, Tabs, Text, Textarea } from '@mantine/core';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useMediaQuery } from '@mantine/hooks';
import { useState } from 'react';
import { useParams } from 'react-router';
import { api } from '../../api/client';
import { AsyncContent, PageLayout, TeacherTableLayout } from '../../app/layouts/PageLayouts';
import { useAuthSession } from '../auth/authQueries';
import { CheckpointEdit } from './CheckpointForms';
import { AssignmentDetails } from './TaskPage';
import { ErrorMessage, More, Status, TaskButton, TaskFailure } from './shared';
import { dataOf, dateTime, isAccessFailure, fieldError, loadState, statuses, type Schemas, invalidateTaskData } from './helpers';

function workCount(n: number) {
  if (n % 10 === 1 && n % 100 !== 11) return `${n} работу`;
  if (n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 12 || n % 100 > 14)) return `${n} работы`;
  return `${n} работ`;
}

export function CheckpointPage() {
  const user = useAuthSession().session.data;
  if (user?.role === 'STUDENT') return <TaskFailure forbidden />;
  return <CheckpointContent />;
}

function CheckpointContent() {
  const checkpointId = Number(useParams().id);
  const desktop = useMediaQuery('(min-width: 48em)', true, { getInitialValueInEffect: false });
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
  const rowsQuery = useTimedInfiniteQuery({
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
  const accept = useTimedMutation({
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
  if (isAccessFailure(query.error)) return <TaskFailure error={query.error} />;
  function filterChanged() {
    setSelected([]);
    accept.reset();
  }
  return (
    <PageLayout title={checkpoint?.title ?? 'Точка'}>
      <AsyncContent state={loadState(query)}>
        {checkpoint && (
          <Stack pb={!desktop && selected.length ? 'calc(var(--cr-touch-target) * 3 + var(--mantine-spacing-md) * 2)' : undefined}>
            <Text>{checkpoint.description}</Text>
            <Text>
              Дедлайн: {dateTime(checkpoint.deadline)} (МСК) · Стоимость: {checkpoint.cost} АР
            </Text>
            <Text>
              Штраф: {checkpoint.penaltyStep} АР каждые {checkpoint.penaltyIntervalSeconds} с
            </Text>
            {canEdit && (
              <TaskButton variant="default" onClick={() => setEditing(true)}>
                Редактировать описание
              </TaskButton>
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
                    {label}{counts ? ` · ${counts[value as Schemas['AssignmentStatus']]}` : ''}
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
            {canEdit && (desktop || selected.length > 0) && (
              <section aria-label="Выбор работ" className="cr-selection" style={{ position: desktop ? 'static' : 'fixed', bottom: 'var(--app-shell-footer-height, 0px)', insetInline: desktop ? undefined : 'var(--mantine-spacing-md)', zIndex: 'calc(var(--mantine-z-index-app) + 1)' }}>
                <strong>Выбрано {selected.length}</strong>
                <Button variant="subtle" onClick={() => setSelected([])}>
                  Снять выбор
                </Button>
                <ActionButton
                  disabled={!selected.length}
                  pending={accept.isPending}
                  onClick={() => {
                    setNames(Object.fromEntries(rows.map((row) => [row.assignmentId, row.student.fullName])));
                    accept.mutate([...selected]);
                  }}
                >
                  Принять {workCount(selected.length)}
                </ActionButton>
              </section>
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
                        styles={{ label: { color: 'var(--cr-text)' } }}
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
                  render: (row) => row.submittedAt ? dateTime(row.submittedAt) : 'Ещё не сдано',
                },
                { key: 'charged', title: 'Списано', render: (row) => `${row.charged} АР` },
                {
                  key: 'actions',
                  title: 'Действия',
                  render: (row) => {
                    const hasVersions = row.hasSubmissions;
                    const canReturn = canEdit && row.status === 'SUBMITTED';
                    return hasVersions ? <Menu>
                      <Menu.Target><Button variant="subtle" aria-label={`Действия: ${row.student.fullName}`}>⋯</Button></Menu.Target>
                      <Menu.Dropdown>
                        <Menu.Item onClick={() => setOpened(row.assignmentId)}>Открыть сдачу</Menu.Item>
                        {canReturn && <Menu.Item onClick={() => setReturnId(row.assignmentId)}>Вернуть…</Menu.Item>}
                      </Menu.Dropdown>
                    </Menu> : null;
                  },
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
  const mutation = useTimedMutation({
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
    <form noValidate
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
        {!fieldError(mutation.error, 'comment') && <ErrorMessage error={mutation.error} />}
        <TaskButton type="submit" pending={mutation.isPending}>
          Вернуть работу
        </TaskButton>
      </Stack>
    </form>
  );
}

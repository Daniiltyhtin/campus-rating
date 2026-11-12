import { Button, Group, Modal, Stack, Text, TextInput } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../../../api/client';
import type { components } from '../../../api/schema';
import { AsyncContent, PageLayout, TeacherTableLayout } from '../../../app/layouts/PageLayouts';
import { dataOf, dateTime, fieldError, loadState } from '../../tasks/helpers';
import { AdminError, AdminOnly } from '../shared';

type Semester = components['schemas']['AdminSemester'];
type Command = { kind: 'save'; id?: number; title: string; startsAt: string } | { kind: 'delete'; id: number } | { kind: 'start'; id: number };
function Semesters() {
  const client = useQueryClient();
  const query = useQuery({ queryKey: ['admin-semesters'], queryFn: () => dataOf(api.GET('/api/v1/admin/semesters')) });
  const settings = useQuery({ queryKey: ['admin-settings'], queryFn: () => dataOf(api.GET('/api/v1/admin/settings')) });
  const [editor, setEditor] = useState<{ kind: 'save' | 'delete' | 'start'; semester?: Semester }>();
  const [title, setTitle] = useState(''), [startsAt, setStartsAt] = useState('');
  const [message, setMessage] = useState('');
  const action = useMutation({ mutationFn: async (command: Command) => {
    if (command.kind === 'delete') { const result = await api.DELETE('/api/v1/admin/semesters/{semesterId}', { params: { path: { semesterId: command.id } } }); if (!result.response.ok) throw new Error(); return; }
    if (command.kind === 'start') return dataOf(api.POST('/api/v1/admin/semesters/{semesterId}/start', { params: { path: { semesterId: command.id } } }));
    const body = { title: command.title, startsAt: new Date(`${command.startsAt}:00+03:00`).toISOString() };
    return command.id ? dataOf(api.PUT('/api/v1/admin/semesters/{semesterId}', { params: { path: { semesterId: command.id } }, body })) : dataOf(api.POST('/api/v1/admin/semesters', { body }));
  }, onSuccess: async () => { setEditor(undefined); setMessage('Семестр сохранён'); await client.invalidateQueries({ queryKey: ['admin-semesters'] }); } });
  function open(kind: 'save' | 'delete' | 'start', semester?: Semester) {
    action.reset(); setMessage(''); setTitle(semester?.title ?? '');
    const date = semester ? new Date(Date.parse(semester.startsAt) + 3 * 3600000).toISOString().slice(0, 16) : '';
    setStartsAt(date); setEditor({ kind, semester });
  }
  const items = query.data?.items ?? [], nearest = items.find((s) => !s.started), previous = [...items].reverse().find((s) => s.started);
  return <PageLayout title="Семестры" actions={<Button onClick={() => open('save')}>Добавить семестр</Button>}>
    {message && <Text role="status">{message}</Text>}
    <TeacherTableLayout caption="Семестры" rows={items} keyForRow={(s) => s.id} state={loadState(query, items.length === 0, 'Семестров пока нет')}
      columns={[{ key: 'title', title: 'Название', render: (s) => s.title }, { key: 'start', title: 'Начало (МСК)', render: (s) => dateTime(s.startsAt) },
        { key: 'state', title: 'Состояние', render: (s) => s.started ? 'Начат' : 'Запланирован' }, { key: 'n', title: 'N', render: (s) => s.started ? s.accrual ?? '—' : '—' },
        { key: 'actions', title: 'Действия', render: (s) => !s.started && <Group><Button variant="default" onClick={() => open('save', s)}>Изменить {s.title}</Button>
          <Button variant="default" onClick={() => open('delete', s)}>Удалить {s.title}</Button>{s.id === nearest?.id && <Button onClick={() => open('start', s)}>Начать сейчас</Button>}</Group> }]} />
    <Modal opened={!!editor} onClose={() => { if (!action.isPending) setEditor(undefined); }} title={editor?.kind === 'start' ? 'Начать семестр' : editor?.kind === 'delete' ? 'Удалить семестр' : 'Семестр'}>
      <Stack>{editor?.kind === 'save' ? <form onSubmit={(event) => { event.preventDefault(); action.mutate({ kind: 'save', id: editor.semester?.id, title, startsAt }); }}>
        <Stack><TextInput required label="Название" maxLength={100} value={title} onChange={(e) => setTitle(e.currentTarget.value)} error={fieldError(action.error, 'title')} />
          <TextInput required type="datetime-local" label="Начало (МСК)" value={startsAt} onChange={(e) => setStartsAt(e.currentTarget.value)} error={fieldError(action.error, 'startsAt')} />
          <Button type="submit" loading={action.isPending}>Сохранить</Button></Stack></form> : <>
        {editor?.kind === 'start' ? <AsyncContent state={loadState(settings)}><Text>Начать семестр «{editor.semester?.title}» сейчас? Каждому студенту начислится до {settings.data?.semesterAccrual} АР. Отменить начисление будет нельзя.</Text>
          {previous && <Text>Предыдущий семестр «{previous.title}» начат {dateTime(previous.startsAt)}.</Text>}</AsyncContent> : <Text>Удалить семестр «{editor?.semester?.title}»?</Text>}
        <Button loading={action.isPending} disabled={editor?.kind === 'start' && !settings.data} onClick={() => { if (editor?.semester && editor.kind !== 'save') action.mutate({ kind: editor.kind, id: editor.semester.id }); }}>{editor?.kind === 'start' ? 'Начать' : 'Удалить'}</Button>
      </>}<AdminError error={action.error} /><Button variant="default" disabled={action.isPending} onClick={() => setEditor(undefined)}>Отмена</Button></Stack>
    </Modal>
  </PageLayout>;
}
export function AdminSemestersPage() { return <AdminOnly><Semesters /></AdminOnly>; }

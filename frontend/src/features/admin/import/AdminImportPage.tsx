import { ActionButton } from '../../../app/ActionButton';
import { useTimedMutation } from '../../../app/actionTiming';
import { Button, FileInput, Modal, NativeSelect, Stack, Text } from '@mantine/core';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../../../api/client';
import { PageLayout, TeacherTableLayout } from '../../../app/layouts/PageLayouts';
import { dataOf, loadState } from '../../tasks/helpers';
import { AdminError, AdminOnly } from '../shared';

type Kind = 'GROUPS' | 'STUDENTS' | 'TEACHER_ALLOWLIST';
const headers = { GROUPS: 'faculty_code,group_code,group_name,admission_year', STUDENTS: 'email,last_name,first_name,middle_name,faculty_code,group_code', TEACHER_ALLOWLIST: 'email,department_code' };
function Import() {
  const client = useQueryClient(), [kind, setKind] = useState<Kind>('GROUPS'), [file, setFile] = useState<File | null>(null), [localError, setLocalError] = useState('');
  const [remove, setRemove] = useState<string>();
  const query = useQuery({ queryKey: ['teacher-allowlist'], queryFn: () => dataOf(api.GET('/api/v1/admin/teacher-allowlist')) });
  const upload = useTimedMutation({ mutationFn: () => {
    if (!file) throw new Error('Выберите файл');
    return dataOf(api.POST('/api/v1/admin/import/{kind}', { params: { path: { kind } }, body: { file }, bodySerializer: (body) => { const form = new FormData(); form.append('file', body.file); return form; } }));
  }, onSuccess: () => { void client.invalidateQueries({ queryKey: ['teacher-allowlist'] }); void client.invalidateQueries({ queryKey: ['admin-users'] }); void client.invalidateQueries({ queryKey: ['directory'] }); } });
  const deletion = useTimedMutation({ mutationFn: async () => {
    const response = await api.DELETE('/api/v1/admin/teacher-allowlist', { params: { query: { email: remove! } } }); if (!response.response.ok) throw new Error();
  }, onSuccess: async () => { setRemove(undefined); await client.invalidateQueries({ queryKey: ['teacher-allowlist'] }); } });
  return <PageLayout title="Импорт"><Stack><NativeSelect label="Вид импорта" value={kind} disabled={upload.isPending} data={[{ value: 'GROUPS', label: 'Группы' }, { value: 'STUDENTS', label: 'Студенты' }, { value: 'TEACHER_ALLOWLIST', label: 'Список преподавателей' }]}
    onChange={(e) => { setKind(e.currentTarget.value as Kind); setFile(null); setLocalError(''); upload.reset(); }} />
    <Text>CSV (UTF-8 или Windows-1251, запятая или точка с запятой) или XLSX. До 5 МБ и 5000 строк.</Text><Text style={{ overflowWrap: 'anywhere' }}>Заголовок: {headers[kind]}</Text>
    <FileInput label="Файл импорта" accept=".csv,.xlsx" value={file} disabled={upload.isPending} onChange={(value) => { setFile(value); setLocalError(''); upload.reset(); }} error={localError} />
    <ActionButton pending={upload.isPending} onClick={() => {
      if (!file) { setLocalError('Выберите файл'); return; }
      if (file.size > 5 * 1024 * 1024) { setLocalError('Файл слишком большой: до 5 МБ и 5000 строк'); return; }
      upload.mutate();
    }}>Импортировать</ActionButton><AdminError error={upload.error} />
    {upload.data && <><Text role="status">Создано: {upload.data.created} · Пропущено (уже есть): {upload.data.skipped} · Отклонено: {upload.data.rejected.length}</Text>
      <TeacherTableLayout caption="Отклонённые строки" rows={upload.data.rejected} keyForRow={(r) => r.row} state={{ status: 'ready' }} columns={[
        { key: 'row', title: 'Строка', render: (r) => r.row }, { key: 'key', title: 'Ключ строки', render: (r) => r.key }, { key: 'reason', title: 'Причина', render: (r) => r.reason }]} /></>}
    <TeacherTableLayout caption="Список преподавателей" rows={query.data?.items ?? []} keyForRow={(r) => r.email} state={loadState(query, query.data?.items.length === 0, 'Список преподавателей пуст')}
      columns={[{ key: 'email', title: 'Email', render: (r) => r.email }, { key: 'department', title: 'Кафедра', render: (r) => r.department.name },
        { key: 'registered', title: 'Зарегистрирован', render: (r) => r.registered ? 'да' : 'нет' }, { key: 'actions', title: 'Действия', render: (r) => !r.registered && <Button variant="default" onClick={() => { deletion.reset(); setRemove(r.email); }}>Удалить {r.email}</Button> }]} />
    <Modal opened={!!remove} onClose={() => { if (!deletion.isPending) setRemove(undefined); }} title="Удалить из списка преподавателей"><Stack>
      <Text>Удалить {remove} из списка преподавателей?</Text><ActionButton pending={deletion.isPending} onClick={() => deletion.mutate()}>Удалить</ActionButton>
      <AdminError error={deletion.error} /><Button variant="default" disabled={deletion.isPending} onClick={() => setRemove(undefined)}>Отмена</Button></Stack></Modal>
  </Stack></PageLayout>;
}
export function AdminImportPage() { return <AdminOnly><Import /></AdminOnly>; }

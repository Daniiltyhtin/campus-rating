import { Button, Group, Modal, NativeSelect, Stack, Text, TextInput } from '@mantine/core';
import type { ReactNode } from 'react';
import { useState } from 'react';
import { ApiError } from '../../../api/client';
import { AsyncContent } from '../../../app/layouts/PageLayouts';
import { fieldErrors } from '../../auth/formErrors';
import { useDirectory } from '../directory/queries';
import { grantDescription, grantKinds, grantNames, validateGrant } from './contracts';
import type { Grant, GrantFields, Teacher } from './contracts';
import { teacherState, teacherError, useTeacherAction } from './queries';
import type { TeacherCommand } from './queries';

type Action = ReturnType<typeof useTeacherAction>;
type DialogProps = { teacher: Teacher; close: () => void; finished: (command: TeacherCommand) => void };
function Frame({ title, busy, close, children }: { title: string; busy: boolean; close: () => void; children: ReactNode }) {
  return <Modal opened title={title} centered className="teachers-dialog" returnFocus={false} closeButtonProps={{ 'aria-label': 'Закрыть' }}
    withCloseButton={!busy} closeOnEscape={!busy} closeOnClickOutside={!busy} onClose={() => { if (!busy) close(); }}>{children}</Modal>;
}
function Failure({ action }: { action: Action }) {
  const error = action.refresh.error ?? action.mutation.error;
  return <>{error && <Stack role="alert" gap="sm"><Text>{teacherError(error)}</Text>
    {error instanceof ApiError && error.code === 'CSRF_INVALID' && <Button variant="default" loading={action.refresh.isPending} onClick={() => action.refresh.mutate()}>Обновить защиту</Button>}
    {error instanceof ApiError && (error.code === 'INVALID_STATE' || error.code === 'NOT_FOUND') && <Button variant="default" onClick={action.reload}>Обновить карточку</Button>}
  </Stack>}{action.refresh.isSuccess && <Text role="status">Защита обновлена. Повторите действие.</Text>}</>;
}
export function ApprovalDialog({ teacher, viewerId, close, finished }: DialogProps & { viewerId: number }) {
  const query = useDirectory('departments', viewerId), action = useTeacherAction(teacher.id, finished);
  const [selected, setSelected] = useState(String(teacher.department.id)), [errors, setErrors] = useState<Record<string, string>>({});
  const busy = action.mutation.isPending || action.refresh.isPending, rows = query.data?.items ?? [];
  const unavailable = query.isError || !query.data || rows.length === 0;
  return <Frame title="Допустить преподавателя" busy={busy} close={close}><form noValidate onSubmit={(event) => {
    event.preventDefault(); if (busy || unavailable) return;
    const departmentId = Number(selected);
    if (!rows.some((row) => row.id === departmentId)) { setErrors({ departmentId: 'Выберите существующую кафедру' }); return; }
    setErrors({}); action.mutation.mutate({ kind: 'approve', departmentId }, { onError: (error) => setErrors(fieldErrors(error)) });
  }}><Stack><Text>Проверьте кафедру: её факультет определит доступные группы.</Text>
    <AsyncContent state={teacherState(query, rows.length === 0, 'Кафедр пока нет. Сначала создайте кафедру в справочниках.')}>
      <NativeSelect label="Кафедра" value={selected} disabled={busy} data={[{ value: '', label: 'Выберите кафедру' }, ...rows.map((row) => ({ value: String(row.id), label: row.name }))]}
        error={errors.departmentId} onChange={(event) => { setSelected(event.currentTarget.value); setErrors({}); }} data-autofocus />
    </AsyncContent><Failure action={action} />
    <Group justify="end"><Button variant="default" disabled={busy} onClick={close}>Отмена</Button><Button type="submit" disabled={busy || unavailable} loading={action.mutation.isPending}>Допустить</Button></Group>
  </Stack></form></Frame>;
}
export function GrantDialog({ teacher, viewerId, close, finished }: DialogProps & { viewerId: number }) {
  const groupQuery = useDirectory('groups', viewerId), facultyQuery = useDirectory('faculties', viewerId), action = useTeacherAction(teacher.id, finished);
  const [fields, setFields] = useState<GrantFields>({ kind: 'GROUP', groupId: '', facultyId: '', course: '' }), [errors, setErrors] = useState<Record<string, string>>({});
  const groups = groupQuery.data?.items.flatMap((row) => row.kind === 'groups' ? [{ id: row.id, name: row.name, faculty: row.faculty }] : []) ?? [];
  const faculties = facultyQuery.data?.items ?? [], busy = action.mutation.isPending || action.refresh.isPending;
  const needsFaculty = fields.kind === 'FACULTY' || fields.kind === 'FACULTY_COURSE';
  const unavailable = fields.kind === 'GROUP' ? !groupQuery.data || groupQuery.isError || groups.length === 0
    : needsFaculty && (!facultyQuery.data || facultyQuery.isError || faculties.length === 0);
  const change = (field: keyof GrantFields, value: string) => { setFields((old) => ({ ...old, [field]: value })); setErrors((old) => { const next = { ...old }; delete next[field]; return next; }); };
  return <Frame title="Выдать доступ" busy={busy} close={close}><form noValidate onSubmit={(event) => {
    event.preventDefault(); if (busy || unavailable) return;
    const checked = validateGrant(fields, groups, faculties);
    if (!checked.ok) { setErrors(checked.errors); return; }
    setErrors({}); action.mutation.mutate({ kind: 'grant', body: checked.body }, { onError: (error) => setErrors(fieldErrors(error)) });
  }}><Stack><NativeSelect label="Вид доступа" value={fields.kind} data={grantKinds.map((kind) => ({ value: kind, label: grantNames[kind] }))} disabled={busy} error={errors.kind} data-autofocus
    onChange={(event) => { setFields({ kind: event.currentTarget.value, groupId: '', facultyId: '', course: '' }); setErrors({}); action.mutation.reset(); }} />
    {fields.kind === 'GROUP' && <AsyncContent state={teacherState(groupQuery, groups.length === 0, 'Групп пока нет. Сначала создайте группу.')}><NativeSelect label="Группа" value={fields.groupId} disabled={busy} error={errors.groupId}
      data={[{ value: '', label: 'Выберите группу' }, ...groups.map((group) => ({ value: String(group.id), label: `${group.name} · ${group.faculty.code}` }))]} onChange={(event) => change('groupId', event.currentTarget.value)} /></AsyncContent>}
    {needsFaculty && <AsyncContent state={teacherState(facultyQuery, faculties.length === 0, 'Факультетов пока нет. Сначала создайте факультет.')}><NativeSelect label="Факультет" value={fields.facultyId} disabled={busy} error={errors.facultyId}
      data={[{ value: '', label: 'Выберите факультет' }, ...faculties.map((faculty) => ({ value: String(faculty.id), label: faculty.name }))]} onChange={(event) => change('facultyId', event.currentTarget.value)} /></AsyncContent>}
    {(fields.kind === 'COURSE' || fields.kind === 'FACULTY_COURSE') && <TextInput type="number" label="Курс" value={fields.course} disabled={busy} error={errors.course} onChange={(event) => change('course', event.currentTarget.value)} />}
    <Failure action={action} /><Group justify="end"><Button variant="default" disabled={busy} onClick={close}>Отмена</Button><Button type="submit" disabled={busy || unavailable} loading={action.mutation.isPending}>Выдать доступ</Button></Group>
  </Stack></form></Frame>;
}
export function ConfirmTeacherDialog({ teacher, command, grant, close, finished }: DialogProps & { command: 'reject' | 'remove'; grant?: Grant }) {
  const action = useTeacherAction(teacher.id, finished), busy = action.mutation.isPending || action.refresh.isPending;
  return <Frame title={command === 'reject' ? 'Отклонить заявку' : 'Снять доступ'} busy={busy} close={close}><Stack>
    {command === 'reject' ? <><Text>{teacher.fullName} · {teacher.email}</Text><Text>Аккаунт будет удалён. Пользователь сможет зарегистрироваться заново.</Text></>
      : <><Text>{grant && grantDescription(grant)}</Text><Text>Группа может остаться доступной по кафедре или другой выдаче.</Text></>}
    <Failure action={action} /><Group justify="end"><Button variant="default" disabled={busy} onClick={close}>Отмена</Button>
      <Button disabled={busy} loading={action.mutation.isPending} onClick={() => { if (command === 'reject') action.mutation.mutate({ kind: 'reject' }); else if (grant) action.mutation.mutate({ kind: 'remove', grantId: grant.id }); }}>{command === 'reject' ? 'Отклонить' : 'Снять доступ'}</Button>
    </Group>
  </Stack></Frame>;
}

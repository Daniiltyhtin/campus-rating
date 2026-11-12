import { Button, Group, Modal, NativeSelect, Stack, Text, TextInput } from '@mantine/core';
import { useState } from 'react';
import { ApiError } from '../../../api/client';
import { AsyncContent } from '../../../app/layouts/PageLayouts';
import { fieldErrors } from '../../auth/formErrors';
import { fieldsFor, labels, validateDirectory } from './contracts';
import type { DirectoryEntry, DirectoryKind, Faculty } from './contracts';
import { directoryError, useDirectory, useDirectoryAction } from './queries';

type FacultyQuery = ReturnType<typeof useDirectory>;
function ActionError({ action }: { action: ReturnType<typeof useDirectoryAction> }) {
  const error = action.refresh.error ?? action.mutation.error;
  return <>{error && <Stack role="alert" gap="sm"><Text>{directoryError(error)}</Text>
    {error instanceof ApiError && error.code === 'CSRF_INVALID' && <Button variant="default" loading={action.refresh.isPending} onClick={() => action.refresh.mutate()}>Обновить защиту</Button>}</Stack>}
    {action.refresh.isSuccess && <Text role="status">Защита обновлена. Повторите действие.</Text>}</>;
}
export function EditDirectory({ kind, entry, faculties, close, finished }: { kind: DirectoryKind; entry?: DirectoryEntry; faculties: FacultyQuery; close: () => void; finished: (message: string) => void }) {
  const [fields, setFields] = useState(() => fieldsFor(entry));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const action = useDirectoryAction(kind, finished), busy = action.mutation.isPending || action.refresh.isPending;
  const facultyRows: Faculty[] = faculties.data?.items ?? [];
  const needsFaculty = kind !== 'faculties';
  function change(field: keyof typeof fields, value: string) {
    setFields((old) => ({ ...old, [field]: value })); setErrors((old) => { const next = { ...old }; delete next[field]; return next; });
  }
  const unavailable = needsFaculty && (!faculties.data || faculties.isError || facultyRows.length === 0);
  return <Modal opened onClose={() => { if (!busy) close(); }} title={`${entry ? 'Редактировать' : 'Добавить'} ${labels[kind].noun}`} centered
    className="directory-dialog" returnFocus={false} closeButtonProps={{ 'aria-label': 'Закрыть' }} closeOnClickOutside={!busy} closeOnEscape={!busy} withCloseButton={!busy}>
    <form noValidate onSubmit={(event) => {
      event.preventDefault(); if (busy || unavailable) return;
      const result = validateDirectory(kind, fields, facultyRows);
      if (!result.ok) { setErrors(result.errors); return; }
      setErrors({}); action.mutation.mutate({ action: 'save', id: entry?.id, body: result.body }, { onError: (error) => {
        setErrors(error instanceof ApiError && error.code === 'DIRECTORY_CODE_TAKEN' ? { code: 'Код уже используется' } : fieldErrors(error));
      } });
    }}><Stack>
      <TextInput label="Код" value={fields.code} onChange={(event) => change('code', event.currentTarget.value)} error={errors.code} disabled={busy} data-autofocus />
      <TextInput label="Название" value={fields.name} onChange={(event) => change('name', event.currentTarget.value)} error={errors.name} disabled={busy} />
      {needsFaculty && <><AsyncContent state={faculties.isError ? { status: 'error', message: directoryError(faculties.error), retry: () => { void faculties.refetch(); } }
        : faculties.isPending ? { status: 'loading' } : facultyRows.length === 0 ? { status: 'empty', message: 'Сначала создайте факультет. Факультетов пока нет.' } : { status: 'ready' }}>
        <NativeSelect label="Факультет" value={fields.facultyId} disabled={busy} error={errors.facultyId} onChange={(event) => change('facultyId', event.currentTarget.value)}
          data={[{ value: '', label: 'Выберите факультет' }, ...facultyRows.map((row) => ({ value: String(row.id), label: row.name }))]} />
      </AsyncContent></>}
      {kind === 'groups' && <TextInput type="number" label="Год набора" value={fields.admissionYear} onChange={(event) => change('admissionYear', event.currentTarget.value)} error={errors.admissionYear} disabled={busy} />}
      <ActionError action={action} />
      <Group justify="end"><Button variant="default" onClick={close} disabled={busy}>Отмена</Button><Button type="submit" disabled={busy || unavailable} loading={action.mutation.isPending}>Сохранить</Button></Group>
    </Stack></form>
  </Modal>;
}
export function DeleteDirectory({ entry, close, finished }: { entry: DirectoryEntry; close: () => void; finished: (message: string) => void }) {
  const action = useDirectoryAction(entry.kind, finished), busy = action.mutation.isPending || action.refresh.isPending;
  return <Modal opened onClose={() => { if (!busy) close(); }} title={`Удалить ${labels[entry.kind].noun}`} centered className="directory-dialog" returnFocus={false} closeButtonProps={{ 'aria-label': 'Закрыть' }} closeOnClickOutside={!busy} closeOnEscape={!busy} withCloseButton={!busy}>
    <Stack><Text>Удалить «{entry.name}»? Связанные данные удалены не будут. Сервер проверит, используется ли запись.</Text><ActionError action={action} />
      <Group justify="end"><Button variant="default" disabled={busy} onClick={close}>Отмена</Button><Button disabled={busy} loading={action.mutation.isPending} onClick={() => action.mutation.mutate({ action: 'delete', id: entry.id })}>Удалить</Button></Group>
    </Stack>
  </Modal>;
}

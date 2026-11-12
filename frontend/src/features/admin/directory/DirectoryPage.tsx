import { Button, Group, Stack, Tabs, Text } from '@mantine/core';
import { useRef, useState } from 'react';
import { PageLayout, TeacherTableLayout } from '../../../app/layouts/PageLayouts';
import { useAuthSession } from '../../auth/authQueries';
import { DeleteDirectory, EditDirectory } from './DirectoryDialogs';
import { kinds, labels } from './contracts';
import type { DirectoryEntry, DirectoryKind } from './contracts';
import { directoryError, useDirectory } from './queries';
import './directory.css';

type Editor = { action: 'add'; kind: DirectoryKind } | { action: 'edit' | 'delete'; kind: DirectoryKind; entry: DirectoryEntry };
function Directory({ viewerId }: { viewerId: number }) {
  const [kind, setKind] = useState<DirectoryKind>('faculties'), [editor, setEditor] = useState<Editor | null>(null), [result, setResult] = useState('');
  const opener = useRef<HTMLButtonElement | null>(null), addButton = useRef<HTMLButtonElement>(null);
  const list = useDirectory(kind, viewerId), faculties = useDirectory('faculties', viewerId);
  const restore = (target: HTMLButtonElement | null) => { requestAnimationFrame(() => { if (target?.isConnected) target.focus(); else addButton.current?.focus(); }); };
  const close = () => { setEditor(null); restore(opener.current); };
  const finished = (message: string) => { setEditor(null); setResult(message); restore(editor?.action === 'delete' ? addButton.current : opener.current); };
  const open = (next: Editor, target: HTMLButtonElement) => { opener.current = target; setResult(''); setEditor(next); };
  const busy = editor !== null;
  return <div className="directory-page"><PageLayout title="Справочники" description="Факультеты, учебные группы и кафедры университета.">
    {result && <Text role="status">{result}</Text>}
    <Tabs value={kind} onChange={(value) => { const next = kinds.find((item) => item === value); if (next && !busy) { setKind(next); setResult(''); } }}>
      <Tabs.List>{kinds.map((item) => <Tabs.Tab value={item} key={item} disabled={busy}>{labels[item].title}</Tabs.Tab>)}</Tabs.List>
      {kinds.map((item) => <Tabs.Panel value={item} key={item}><Stack gap="lg" pt="lg">{item === kind && <>
        <Group><Button ref={addButton} disabled={busy} onClick={(event) => open({ action: 'add', kind }, event.currentTarget)}>Добавить {labels[kind].noun}</Button></Group>
        <TeacherTableLayout caption={labels[kind].title} rows={list.data?.items ?? []} keyForRow={(row) => row.id}
          state={list.isError ? { status: 'error', message: directoryError(list.error), retry: () => { void list.refetch(); } } : list.isPending ? { status: 'loading' }
            : !list.data?.items.length ? { status: 'empty', message: `${labels[kind].title}: записей пока нет. Добавьте первую запись.` } : { status: 'ready' }}
          columns={[{ key: 'code', title: 'Код', render: (row) => row.code }, { key: 'name', title: 'Название', render: (row) => row.name },
            ...(kind !== 'faculties' ? [{ key: 'faculty', title: 'Факультет', render: (row: DirectoryEntry) => row.kind !== 'faculties' ? row.faculty.name : '' }] : []),
            ...(kind === 'groups' ? [{ key: 'year', title: 'Год набора', render: (row: DirectoryEntry) => row.kind === 'groups' ? row.admissionYear : '' }] : []),
            { key: 'actions', title: 'Действия', render: (row) => <Group gap="xs"><Button variant="default" aria-label={`Редактировать ${row.name}`} disabled={busy} onClick={(event) => open({ action: 'edit', kind, entry: row }, event.currentTarget)}>Редактировать</Button>
              <Button variant="default" aria-label={`Удалить ${row.name}`} disabled={busy} onClick={(event) => open({ action: 'delete', kind, entry: row }, event.currentTarget)}>Удалить</Button></Group> }]} />
      </>}</Stack></Tabs.Panel>)}
    </Tabs>
    {editor && (editor.action === 'delete' ? <DeleteDirectory entry={editor.entry} close={close} finished={finished} />
      : <EditDirectory key={`${editor.kind}-${editor.action === 'edit' ? editor.entry.id : 'new'}`} kind={editor.kind} entry={editor.action === 'edit' ? editor.entry : undefined} faculties={faculties} close={close} finished={finished} />)}
  </PageLayout></div>;
}
export function DirectoryPage() {
  const { session } = useAuthSession();
  return session.data?.role === 'ADMIN' ? <Directory viewerId={session.data.id} key={session.data.id} /> : <PageLayout title="Нет доступа"><Text>Этот раздел доступен администратору.</Text></PageLayout>;
}

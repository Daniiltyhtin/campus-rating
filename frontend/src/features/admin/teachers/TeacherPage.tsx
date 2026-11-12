import { Button, Group, Stack, Text, Title } from '@mantine/core';
import { useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { ApiError } from '../../../api/client';
import { AsyncContent } from '../../../app/layouts/PageLayouts';
import { useAuthSession } from '../../auth/authQueries';
import { grantDescription } from './contracts';
import type { Grant, Teacher } from './contracts';
import { ApprovalDialog, ConfirmTeacherDialog, GrantDialog } from './TeacherDialogs';
import { TeacherLayout, TeacherLink, TeacherStatus } from './TeacherShared';
import { useTeacher, useTeacherGrants, useTeacherScope, teacherState } from './queries';
import type { TeacherCommand } from './queries';

type Editor = { kind: 'approve' | 'reject' | 'grant' } | { kind: 'remove'; grant: Grant };
function TeacherContent({ viewerId, teacher, result, setResult }: { viewerId: number; teacher: Teacher; result: string; setResult: (message: string) => void }) {
  const grants = useTeacherGrants(viewerId, teacher.id), scope = useTeacherScope(viewerId, teacher.id);
  const [editor, setEditor] = useState<Editor | null>(null);
  const opener = useRef<HTMLButtonElement | null>(null), add = useRef<HTMLButtonElement>(null), navigate = useNavigate();
  const restore = (target: HTMLButtonElement | null) => { requestAnimationFrame(() => { if (target?.isConnected && !target.disabled) target.focus(); else add.current?.focus(); }); };
  const close = () => { setEditor(null); restore(opener.current); };
  const open = (next: Editor, target: HTMLButtonElement) => { opener.current = target; setResult(''); setEditor(next); };
  function finished(command: TeacherCommand) {
    setEditor(null);
    if (command.kind === 'reject') { void navigate('/admin/directory/teachers', { replace: true, state: { focusList: true } }); return; }
    setResult(command.kind === 'approve' ? 'Преподаватель допущен' : command.kind === 'grant' ? 'Доступ выдан' : 'Доступ снят');
    restore(command.kind === 'remove' || command.kind === 'approve' ? add.current : opener.current);
  }
  const busy = editor !== null;
  return <Stack gap="xl"><Stack gap="sm"><Text>{teacher.email}</Text><Text>{teacher.department.name}</Text><Text>{teacher.department.faculty.name}</Text><div><TeacherStatus approved={teacher.teacherApproved} /></div></Stack>
    {result && <Text role="status">{result}</Text>}
    {!teacher.teacherApproved && <Group><Button disabled={busy} onClick={(event) => open({ kind: 'approve' }, event.currentTarget)}>Допустить</Button><Button variant="default" disabled={busy} onClick={(event) => open({ kind: 'reject' }, event.currentTarget)}>Отклонить заявку</Button></Group>}
    <section><Title order={2} mb="md">Выдачи доступа</Title><Button ref={add} autoFocus={result === 'Преподаватель допущен'} mb="md" disabled={busy} onClick={(event) => open({ kind: 'grant' }, event.currentTarget)}>Выдать доступ</Button>
      <AsyncContent state={teacherState(grants, grants.data?.items.length === 0, 'Дополнительных выдач доступа пока нет.')}><Stack gap={0}>{grants.data?.items.map((grant) => <article className="cr-article" key={grant.id}><Group justify="space-between"><Text>{grantDescription(grant)}</Text>
        <Button variant="default" disabled={busy} aria-label={`Снять доступ ${grantDescription(grant)}`} onClick={(event) => open({ kind: 'remove', grant }, event.currentTarget)}>Снять доступ</Button></Group></article>)}</Stack></AsyncContent>
    </section>
    <section><Title order={2} mb="md">Доступные группы</Title><Text className="cr-caption" mb="md">Группы определяет сервер по кафедре, выдачам и текущему учебному году.</Text>
      <AsyncContent state={teacherState(scope, scope.data?.items.length === 0, teacher.teacherApproved ? 'Доступных групп пока нет.' : 'До допуска доступных групп нет.')}>
        <Stack gap="sm">{scope.data?.items.map((group) => <div key={group.id}><Text>{group.name}</Text><Text className="cr-caption">{group.faculty.name}</Text></div>)}</Stack>
      </AsyncContent>
    </section>
    {editor?.kind === 'approve' && <ApprovalDialog teacher={teacher} viewerId={viewerId} close={close} finished={finished} />}
    {editor?.kind === 'grant' && <GrantDialog teacher={teacher} viewerId={viewerId} close={close} finished={finished} />}
    {editor?.kind === 'reject' && <ConfirmTeacherDialog teacher={teacher} command="reject" close={close} finished={finished} />}
    {editor?.kind === 'remove' && <ConfirmTeacherDialog teacher={teacher} command="remove" grant={editor.grant} close={close} finished={finished} />}
  </Stack>;
}
function TeacherRecord({ viewerId, teacherId }: { viewerId: number; teacherId: number }) {
  const query = useTeacher(viewerId, teacherId);
  const [result, setResult] = useState('');
  if (query.error instanceof ApiError && (query.error.code === 'NOT_FOUND' || query.error.code === 'FORBIDDEN')) return <TeacherLayout title={query.error.code === 'NOT_FOUND' ? 'Преподаватель не найден' : 'Нет доступа'}><TeacherLink to="/admin/directory/teachers">Преподаватели</TeacherLink></TeacherLayout>;
  return <TeacherLayout title={query.data?.fullName ?? 'Преподаватель'}><TeacherLink to="/admin/directory/teachers">Преподаватели</TeacherLink>
    <AsyncContent state={teacherState(query)}>{query.data && <TeacherContent key={`${teacherId}-${query.data.teacherApproved}-${query.data.department.id}`} viewerId={viewerId} teacher={query.data} result={result} setResult={setResult} />}</AsyncContent>
  </TeacherLayout>;
}
export function TeacherPage() {
  const { session } = useAuthSession(), { id } = useParams();
  if (session.data?.role !== 'ADMIN') return <TeacherLayout title="Нет доступа"><Text>Раздел доступен администратору.</Text></TeacherLayout>;
  const teacherId = Number(id);
  if (!id || !/^\d+$/.test(id) || !Number.isSafeInteger(teacherId) || teacherId <= 0) return <TeacherLayout title="Преподаватель не найден"><TeacherLink to="/admin/directory/teachers">Преподаватели</TeacherLink></TeacherLayout>;
  return <TeacherRecord key={`${session.data.id}-${teacherId}`} viewerId={session.data.id} teacherId={teacherId} />;
}

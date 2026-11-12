import { Anchor, Button, FileButton, Input, Modal, Stack, Tabs, Text, TextInput } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router';
import { taskClock } from './clock';
import { api, submitAssignment } from '../../api/client';
import { AsyncContent, FormLayout, PageLayout } from '../../app/layouts/PageLayouts';
import { useAuthSession } from '../auth/authQueries';
import { ErrorMessage, Status, TaskButton, TaskFailure } from './shared';
import { dataOf, dateTime, isAccessFailure, fieldError, loadState, fileValidation, fileSize, linkValidation, teacherCommentLabel, type Schemas, invalidateTaskData } from './helpers';

export function Penalty({ penalty }: { penalty: Schemas['Penalty'] }) {
  const [now, setNow] = useState(() => taskClock.now().getTime());
  const ticking = penalty.phase === 'RUNNING' && penalty.nextAt !== null && Date.parse(penalty.nextAt) > now;
  useEffect(() => {
    if (!ticking) return;
    const timer = window.setInterval(() => setNow(taskClock.now().getTime()), 1000);
    return () => window.clearInterval(timer);
  }, [ticking]);
  let text: string;
  switch (penalty.phase) {
    case 'BEFORE_DEADLINE':
      text = `До дедлайна штрафа нет; после — до −${penalty.step} АР за каждый интервал`;
      break;
    case 'ZERO_BALANCE':
      text = 'Рейтинг 0: списание возобновится после пополнения';
      break;
    case 'CAP_REACHED':
      text = `Списано ${penalty.charged} из ${penalty.cap}, больше не спишется`;
      break;
    case 'STOPPED':
      text = `Штраф остановлен, списано ${penalty.charged}`;
      break;
    case 'RUNNING': {
      const seconds = Math.max(0, Math.ceil((Date.parse(penalty.nextAt!) - now) / 1000));
      const nextTime = seconds
        ? `через ${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
        : '— в ближайшую минуту';
      text = `Следующее списание −${penalty.nextAmount} АР ${nextTime}`;
    }
  }
  return <Text>{text}</Text>;
}

export function AssignmentDetails({ assignmentId }: { assignmentId: number }) {
  const query = useQuery({
    queryKey: ['assignment', assignmentId],
    queryFn: () => readAssignment(assignmentId),
  });
  const assignment = query.data;
  if (isAccessFailure(query.error)) return <TaskFailure error={query.error} />;
  const currentReturn = assignment?.state === 'OPEN' && assignment.returnComment ? assignment.submissions.find((version) => version.returnComment === assignment.returnComment && version.returnedAt) : undefined;
  return (
    <AsyncContent state={loadState(query)}>
      {assignment && (
        <Stack style={{ minWidth: 0, overflowWrap: 'anywhere' }}>
          <Text>{assignment.checkpoint.description}</Text>
          <Text>Дедлайн: {dateTime(assignment.checkpoint.deadline)} (МСК)</Text>
          <Status status={assignment.status} />
          <Text className="cr-ar">
            Списано {assignment.penalty.charged} из {assignment.penalty.cap} АР
          </Text>
          <Penalty penalty={assignment.penalty} />
          {currentReturn && <TeacherComment author={assignment.checkpoint.author.fullName} submission={currentReturn} />}
          <Withdrawal assignment={assignment} />
          <section aria-label="История сдач">
            <Text component="h2">История сдач</Text>
            {!assignment.submissions.length && <Text>Сдач пока нет. Отправьте ссылку или файл работы.</Text>}
            {assignment.submissions.map((submission) => (
              <article className="cr-article" key={submission.id}>
                <Stack gap="xs">
                  <Text>{dateTime(submission.submittedAt)} (МСК)</Text>
                  {submission.kind === 'LINK' ? (
                    <Anchor href={submission.url} target="_blank" rel="noopener noreferrer">
                      {submission.url}
                    </Anchor>
                  ) : (
                    <Anchor
                      href={`/api/v1/assignments/${assignmentId}/submissions/${submission.id}/file`}
                      download={submission.fileName}
                    >
                      {submission.fileName}
                    </Anchor>
                  )}
                  {submission.withdrawnAt && <Text>Отозвана студентом · {dateTime(submission.withdrawnAt)} (МСК)</Text>}
                  {submission.returnComment && (currentReturn?.id === submission.id ?
                    <details><summary>Возвращена с замечанием</summary><TeacherComment author={assignment.checkpoint.author.fullName} submission={submission} /></details> :
                    <TeacherComment author={assignment.checkpoint.author.fullName} submission={submission} />)}
                </Stack>
              </article>
            ))}
          </section>
          {assignment.state === 'OPEN' && <SubmissionForm assignment={assignment} />}
        </Stack>
      )}
    </AsyncContent>
  );
}

function SubmissionForm({ assignment }: { assignment: Schemas['Assignment'] }) {
  const user = useAuthSession().session.data;
  const session = user ? { userId: String(user.id), role: user.role } : null;
  const client = useQueryClient();
  const [mode, setMode] = useState('link');
  const [link, setLink] = useState('');
  const [file, setFile] = useState<File>();
  const [localError, setLocalError] = useState<string>();
  const resetFile = useRef<() => void>(null);
  const mutation = useMutation({
    mutationFn: () => dataOf(submitAssignment(api, assignment.id, mode === 'file' && file ? { file } : { link })),
    onSuccess: () => invalidateTaskData(client),
  });
  if (session?.role !== 'STUDENT' || session.userId !== String(assignment.student.id)) return null;

  const validation = mode === 'file' && file ? fileValidation(file) : undefined;
  const inputError = localError ?? validation ?? fieldError(mutation.error, mode === 'file' ? 'file' : 'link');
  return (
    <FormLayout
      title="Сдать работу"
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        const error = mode === 'file' ? (file ? fileValidation(file) : 'Выберите файл') : linkValidation(link);
        if (error) {
          setLocalError(error);
          return;
        }
        mutation.mutate();
      }}
    >
      <Tabs
        value={mode}
        onChange={(value) => {
          setMode(value ?? 'link');
          setLocalError(undefined);
          mutation.reset();
        }}
      >
        <Tabs.List>
          <Tabs.Tab value="link">Ссылка</Tabs.Tab>
          <Tabs.Tab value="file">Файл</Tabs.Tab>
        </Tabs.List>
      </Tabs>
      {mode === 'link' ? (
        <TextInput
          label="Ссылка на работу"
          type="url"
          pattern="https?://.+"
          required
          maxLength={2000}
          value={link}
          onChange={(event) => { setLink(event.currentTarget.value); setLocalError(undefined); mutation.reset(); }}
          error={inputError}
        />
      ) : (
        <Input.Wrapper label="Файл работы" labelElement="label" id={`file-${assignment.id}`}
          labelProps={{ htmlFor: `file-${assignment.id}-button` }} required
          description="До 10 МБ: pdf, docx, zip, png или jpg" error={inputError ? <span role="alert">{inputError}</span> : undefined}
          styles={{ error: { color: 'var(--cr-status-overdue)' } }}>
          <Stack gap="xs" p="sm" style={{ border: `var(--cr-rule-width) solid var(${inputError ? '--cr-status-overdue' : '--cr-field'})`, borderRadius: 'var(--mantine-radius-sm)' }}>
            <FileButton accept=".pdf,.docx,.zip,.png,.jpg" resetRef={resetFile}
              inputProps={{ id: `file-${assignment.id}`, 'aria-labelledby': `file-${assignment.id}-label` }}
              onChange={(value) => { setFile(value ?? undefined); setLocalError(undefined); mutation.reset(); }}>
              {(props) => <Button {...props} id={`file-${assignment.id}-button`}
                aria-labelledby={`file-${assignment.id}-label`} aria-invalid={!!inputError} aria-required="true"
                aria-describedby={[`file-${assignment.id}-description`, file && `file-${assignment.id}-selection`, inputError && `file-${assignment.id}-error`].filter(Boolean).join(' ')}
                variant="default" style={{ alignSelf: 'flex-start' }}>Выбрать файл</Button>}
            </FileButton>
            {file && <><Text id={`file-${assignment.id}-selection`}>{file.name} · {fileSize(file.size)}</Text><Button variant="subtle" style={{ alignSelf: 'flex-start' }} onClick={() => { setFile(undefined); setLocalError(undefined); mutation.reset(); resetFile.current?.(); }}>Убрать</Button></>}
          </Stack>
        </Input.Wrapper>
      )}
      {!fieldError(mutation.error, mode === 'file' ? 'file' : 'link') && <ErrorMessage error={mutation.error} />}
      <TaskButton type="submit" loading={mutation.isPending} disabled={!!validation}>
        Сдать работу
      </TaskButton>
    </FormLayout>
  );
}

export function TaskPage() {
  const assignmentId = Number(useParams().id);
  const query = useQuery({
    queryKey: ['assignment', assignmentId],
    queryFn: () => readAssignment(assignmentId),
  });
  if (isAccessFailure(query.error)) return <TaskFailure error={query.error} />;
  return (
    <PageLayout title={query.data?.checkpoint.title ?? 'Задача'}>
      <AssignmentDetails assignmentId={assignmentId} />
    </PageLayout>
  );
}

async function readAssignment(assignmentId: number) {
  const result = await api.GET('/api/v1/assignments/{assignmentId}', { params: { path: { assignmentId } } });
  const data = await dataOf(Promise.resolve(result));
  const headerTime = Date.parse(result.response.headers.get('Date') ?? '');
  return { ...data, serverTime: Number.isFinite(headerTime) ? headerTime : taskClock.now().getTime(), receivedAt: taskClock.monotonicNow() };
}

function TeacherComment({ author, submission }: { author: string; submission: Schemas['Submission'] }) {
  return <div style={{ padding: 'var(--mantine-spacing-md)', background: 'var(--cr-selection)', fontFamily: 'var(--mantine-font-family)', fontStyle: 'normal' }}>
    {submission.returnedAt && <Text>{teacherCommentLabel(author, submission.returnedAt)}</Text>}
    <Text>{submission.returnComment}</Text>
  </div>;
}

function Withdrawal({ assignment }: { assignment: Awaited<ReturnType<typeof readAssignment>> }) {
  const user = useAuthSession().session.data;
  const client = useQueryClient();
  const [opened, setOpened] = useState(false);
  const [now, setNow] = useState(() => taskClock.monotonicNow());
  const serverNow = assignment.serverTime + Math.max(0, now - assignment.receivedAt);
  const eligible = user?.role === 'STUDENT' && user.id === assignment.student.id && assignment.state === 'SUBMITTED' && serverNow < Date.parse(assignment.checkpoint.deadline);
  useEffect(() => {
    if (!eligible) return;
    const timer = window.setInterval(() => setNow(taskClock.monotonicNow()), 250);
    return () => window.clearInterval(timer);
  }, [eligible]);
  const mutation = useMutation({
    mutationFn: () => dataOf(api.POST('/api/v1/assignments/{assignmentId}/submission/withdrawal', { params: { path: { assignmentId: assignment.id } } })),
    onSuccess: async () => { setOpened(false); await invalidateTaskData(client); },
    onError: () => invalidateTaskData(client),
  });
  return <>
    {eligible && <TaskButton variant="default" onClick={() => { mutation.reset(); setOpened(true); }}>Отозвать сдачу</TaskButton>}
    <Modal opened={opened} onClose={() => setOpened(false)} title="Отозвать работу?">
      <Stack><Text>Вы сможете сдать её заново до дедлайна</Text>
        <TaskButton loading={mutation.isPending} disabled={mutation.isSuccess} onClick={() => mutation.mutate()}>Отозвать</TaskButton>
        <ErrorMessage error={mutation.error} />
        <TaskButton variant="default" onClick={() => setOpened(false)}>Отмена</TaskButton>
      </Stack>
    </Modal>
  </>;
}

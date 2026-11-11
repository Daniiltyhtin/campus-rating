import { Anchor, Button, Stack, Tabs, Text, TextInput } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useParams } from 'react-router';
import { api, submitAssignment } from '../../api/client';
import { AsyncContent, FormLayout, PageLayout } from '../../app/layouts/PageLayouts';
import { useSession } from '../../app/session';
import { ErrorMessage, Status } from './shared';
import { dataOf, dateTime, fieldError, loadState, type Schemas } from './helpers';

export function Penalty({ penalty }: { penalty: Schemas['Penalty'] }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
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
    queryFn: () => dataOf(api.GET('/api/v1/assignments/{assignmentId}', { params: { path: { assignmentId } } })),
  });
  const assignment = query.data;
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
          {assignment.returnComment && (
            <blockquote className="cr-return-comment">{assignment.returnComment}</blockquote>
          )}
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
                  {submission.returnComment && (
                    <blockquote className="cr-return-comment">
                      {submission.returnComment}
                      {submission.returnedAt && <Text>{dateTime(submission.returnedAt)} (МСК)</Text>}
                    </blockquote>
                  )}
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
  const session = useSession();
  const client = useQueryClient();
  const [mode, setMode] = useState('link');
  const [link, setLink] = useState('');
  const [file, setFile] = useState<File>();
  const [required, setRequired] = useState(false);
  const mutation = useMutation({
    mutationFn: () => dataOf(submitAssignment(api, assignment.id, mode === 'file' && file ? { file } : { link })),
    onSuccess: () => client.invalidateQueries(),
  });
  if (session?.role !== 'STUDENT' || session.userId !== String(assignment.student.id)) return null;

  function fileInputError() {
    if (required) return 'Выберите файл';
    return fieldError(mutation.error, 'file');
  }
  return (
    <FormLayout
      title="Сдать работу"
      onSubmit={(event) => {
        event.preventDefault();
        if (mode === 'file' && !file) {
          setRequired(true);
          return;
        }
        mutation.mutate();
      }}
    >
      <Tabs
        value={mode}
        onChange={(value) => {
          setMode(value ?? 'link');
          setRequired(false);
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
          onChange={(event) => setLink(event.currentTarget.value)}
          error={fieldError(mutation.error, 'link')}
        />
      ) : (
        <TextInput
          type="file"
          label="Файл работы"
          accept=".pdf,.docx,.zip,.png,.jpg"
          onChange={(event) => {
            setFile(event.currentTarget.files?.[0]);
            setRequired(false);
          }}
          error={fileInputError()}
          description="До 10 МБ: pdf, docx, zip, png или jpg"
        />
      )}
      {!fieldError(mutation.error, mode === 'file' ? 'file' : 'link') && <ErrorMessage error={mutation.error} />}
      <Button type="submit" loading={mutation.isPending}>
        Сдать работу
      </Button>
    </FormLayout>
  );
}

export function TaskPage() {
  const assignmentId = Number(useParams().id);
  const query = useQuery({
    queryKey: ['assignment', assignmentId],
    queryFn: () => dataOf(api.GET('/api/v1/assignments/{assignmentId}', { params: { path: { assignmentId } } })),
  });
  return (
    <PageLayout title={query.data?.checkpoint.title ?? 'Задача'}>
      <AssignmentDetails assignmentId={assignmentId} />
    </PageLayout>
  );
}

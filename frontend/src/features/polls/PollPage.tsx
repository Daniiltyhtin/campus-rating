import { ActionButton } from '../../app/ActionButton';
import { useTimedMutation } from '../../app/actionTiming';
import { Checkbox, Radio, Stack, Table, Text } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useParams } from 'react-router';
import { AsyncContent } from '../../app/layouts/PageLayouts';
import { ActivityAccess, ActivityLink, ActivityPage } from '../activities/ActivityShared';
import { formatMoscowTime } from '../activities/time';
import type { CurrentUser } from '../auth/contracts';
import { PollResults } from './PollResults';
import { pollError, pollKeys, usePoll, usePollResponses, votePoll, type Poll } from './queries';

type Viewer = Exclude<CurrentUser, { role: 'ADMIN' }>;

function Responses({ userId, poll }: { userId: number; poll: Poll }) {
  const responses = usePollResponses(userId, poll.id, !poll.anonymous);
  if (poll.anonymous) return <Text className="cr-caption">Анонимный опрос: ответы по студентам не сохраняются.</Text>;
  const optionText = new Map(poll.questions.flatMap((question) => question.options.map((option) => [option.id, option.text] as const)));
  const items = responses.data?.items ?? [];
  const state = responses.isError ? { status: 'error' as const, message: pollError(responses.error), retry: () => { void responses.refetch(); } }
    : responses.isPending ? { status: 'loading' as const } : items.length === 0 ? { status: 'empty' as const, message: 'Ответов пока нет' } : { status: 'ready' as const };
  return <section aria-label="Ответы по студентам"><AsyncContent state={state}>
    <Table.ScrollContainer minWidth={480}><Table>
      <Table.Thead><Table.Tr><Table.Th>Студент</Table.Th><Table.Th>Группа</Table.Th>
        {poll.questions.map((question) => <Table.Th key={question.id}>{question.text}</Table.Th>)}</Table.Tr></Table.Thead>
      <Table.Tbody>{items.map((item) => <Table.Tr key={item.student.id}>
        <Table.Td><ActivityLink to={`/users/${item.student.id}`}>{item.student.fullName}</ActivityLink></Table.Td>
        <Table.Td>{item.group.name}</Table.Td>
        {poll.questions.map((question) => <Table.Td key={question.id}>{(item.answers.find((answer) => answer.questionId === question.id)?.optionIds ?? [])
          .map((id) => optionText.get(id)).join(', ')}</Table.Td>)}
      </Table.Tr>)}</Table.Tbody>
    </Table></Table.ScrollContainer>
  </AsyncContent></section>;
}

function Ballot({ poll, onVoted, onFailed }: { poll: Poll; onVoted: () => Promise<unknown>; onFailed: (message: string) => void }) {
  const [answers, setAnswers] = useState<Record<number, number[]>>({});
  const client = useQueryClient();
  const vote = useTimedMutation({ retry: false,
    mutationFn: () => votePoll(poll.id, { answers: poll.questions.map((question) => ({ questionId: question.id, optionIds: answers[question.id] ?? [] })) }),
    onSuccess: async () => { onFailed(''); await client.invalidateQueries({ queryKey: pollKeys.root }); await onVoted(); },
    onError: async (error) => { onFailed(pollError(error)); await client.invalidateQueries({ queryKey: pollKeys.root }); await onVoted(); } });
  const complete = poll.questions.every((question) => (answers[question.id] ?? []).length > 0);
  return <Stack gap="lg">
    {poll.questions.map((question) => question.type === 'SINGLE'
      ? <Radio.Group key={question.id} label={question.text} value={String(answers[question.id]?.[0] ?? '')}
          onChange={(value) => setAnswers((old) => ({ ...old, [question.id]: [Number(value)] }))}>
          <Stack gap="xs" mt="xs">{question.options.map((option) => <Radio key={option.id} value={String(option.id)} label={option.text} />)}</Stack>
        </Radio.Group>
      : <Checkbox.Group key={question.id} label={question.text} value={(answers[question.id] ?? []).map(String)}
          onChange={(value) => setAnswers((old) => ({ ...old, [question.id]: value.map(Number) }))}>
          <Stack gap="xs" mt="xs">{question.options.map((option) => <Checkbox key={option.id} value={String(option.id)} label={option.text} />)}</Stack>
        </Checkbox.Group>)}
    <ActionButton disabled={!complete} pending={vote.isPending} onClick={() => vote.mutate()}>Проголосовать</ActionButton>
  </Stack>;
}

function Voting({ poll, user, onVoted }: { poll: Poll; user: Viewer; onVoted: () => Promise<unknown> }) {
  const [message, setMessage] = useState('');
  const alert = message ? <div role="alert"><Text>{message}</Text></div> : null;
  if (poll.results) return <Stack gap="md">{alert}
    <Text>{poll.eligible != null ? `Проголосовали ${poll.results.voted} из ${poll.eligible}` : `Проголосовали: ${poll.results.voted}`}</Text>
    <PollResults poll={poll} results={poll.results} />
  </Stack>;
  if (!poll.open) return <Stack gap="md">{alert}<Text>Опрос завершён.</Text></Stack>;
  if (user.role === 'STUDENT' && user.membershipState !== 'ACTIVE') return <Text>Голосовать можно после подтверждения членства.</Text>;
  return <Stack gap="md">{alert}<Ballot poll={poll} onVoted={onVoted} onFailed={setMessage} /></Stack>;
}

function PollView({ user, pollId }: { user: Viewer; pollId: number }) {
  const poll = usePoll(user.id, pollId);
  const data = poll.data;
  const state = poll.isError ? { status: 'error' as const, message: pollError(poll.error), retry: () => { void poll.refetch(); } }
    : poll.isPending ? { status: 'loading' as const } : { status: 'ready' as const };
  return <ActivityPage title={data?.title ?? 'Опрос'} description={data ? `${data.open ? 'До' : 'Завершён'} ${formatMoscowTime(data.endsAt)}` : undefined}
    actions={<ActivityLink to="/polls">К опросам</ActivityLink>}>
    <AsyncContent state={state}>{data && <Stack gap="lg">
      {data.description && <Text>{data.description}</Text>}
      <Text className="cr-caption">{data.groups.map((group) => group.name).join(', ')}{data.anonymous ? ' · анонимный' : ''}</Text>
      <Voting poll={data} user={user} onVoted={() => poll.refetch()} />
      {data.eligible != null && <Responses userId={user.id} poll={data} />}
    </Stack>}</AsyncContent>
  </ActivityPage>;
}

export function PollPage() {
  const { id } = useParams();
  return <ActivityAccess>{(user) => <PollView user={user} pollId={Number(id)} key={id} />}</ActivityAccess>;
}

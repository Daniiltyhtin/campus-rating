import { Button, Checkbox, Group, Radio, Stack, Text, Textarea, TextInput } from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import { AsyncContent, FormLayout } from '../../app/layouts/PageLayouts';
import { useFormErrors } from '../auth/useAuthAction';
import { ActivityAccess, ActivityLink, ActivityPage } from '../activities/ActivityShared';
import { loadState, useAccessibleGroups } from '../activities/queries';
import { moscowInputToUtc } from '../activities/time';
import { createPoll, pollError, pollKeys, type PollCreate } from './queries';
import './polls.css';

type QuestionDraft = { key: number; text: string; type: 'SINGLE' | 'MULTIPLE'; options: string[] };
let nextKey = 1;
const blankQuestion = (): QuestionDraft => ({ key: nextKey++, text: '', type: 'SINGLE', options: ['', ''] });

function QuestionEditor({ index, question, error, canRemove, onChange, onRemove }: {
  index: number; question: QuestionDraft; error: (field: string) => string | undefined; canRemove: boolean;
  onChange: (question: QuestionDraft) => void; onRemove: () => void;
}) {
  const path = `questions[${index}]`;
  const setOption = (position: number, value: string) => onChange({ ...question, options: question.options.map((option, at) => at === position ? value : option) });
  return <fieldset className="poll-question"><legend>Вопрос {index + 1}</legend><Stack gap="sm">
    <TextInput label="Текст вопроса" value={question.text} error={error(`${path}.text`)} onChange={(event) => onChange({ ...question, text: event.currentTarget.value })} />
    <Radio.Group label="Тип ответа" value={question.type} onChange={(value) => onChange({ ...question, type: value === 'MULTIPLE' ? 'MULTIPLE' : 'SINGLE' })}>
      <Group gap="md" mt="xs"><Radio value="SINGLE" label="Один вариант" /><Radio value="MULTIPLE" label="Несколько вариантов" /></Group>
    </Radio.Group>
    {question.options.map((option, position) => <Group key={position} gap="xs" align="end" wrap="nowrap">
      <TextInput style={{ flex: 1 }} label={`Вариант ${position + 1}`} value={option} error={error(`${path}.options[${position}]`)}
        onChange={(event) => setOption(position, event.currentTarget.value)} />
      {question.options.length > 2 && <Button variant="default" aria-label={`Удалить вариант ${position + 1}`}
        onClick={() => onChange({ ...question, options: question.options.filter((_, at) => at !== position) })}>Удалить</Button>}
    </Group>)}
    {error(`${path}.options`) && <Text className="activity-danger">{error(`${path}.options`)}</Text>}
    <Group gap="xs">
      {question.options.length < 10 && <Button variant="default" onClick={() => onChange({ ...question, options: [...question.options, ''] })}>Добавить вариант</Button>}
      {canRemove && <Button variant="default" onClick={onRemove}>Удалить вопрос</Button>}
    </Group>
  </Stack></fieldset>;
}

function NewPollForm({ userId }: { userId: number }) {
  const groups = useAccessibleGroups(userId);
  const client = useQueryClient();
  const navigate = useNavigate();
  const form = useFormErrors();
  const [title, setTitle] = useState(''), [description, setDescription] = useState(''), [endsAt, setEndsAt] = useState('');
  const [groupIds, setGroupIds] = useState<string[]>([]), [anonymous, setAnonymous] = useState(false);
  const [questions, setQuestions] = useState<QuestionDraft[]>(() => [blankQuestion()]);
  const create = useMutation({ retry: false, mutationFn: createPoll, onError: form.fromServer,
    onSuccess: async (poll) => { await client.invalidateQueries({ queryKey: pollKeys.root }); await navigate(`/polls/${poll.id}`); } });
  const available = groups.data?.items ?? [];
  const error = (field: string) => form.errors[field];
  const submit = () => {
    const utc = moscowInputToUtc(endsAt);
    if (!utc) { form.setErrors({ endsAt: 'Укажите дату и время окончания' }); return; }
    const body: PollCreate = { title: title.trim(), description: description.trim(), endsAt: utc, groupIds: groupIds.map(Number), anonymous,
      questions: questions.map((question) => ({ text: question.text.trim(), type: question.type, options: question.options.map((option) => option.trim()) })) };
    form.setErrors({}); create.mutate(body);
  };
  return <ActivityPage title="Новый опрос" description="Окончание указывается по Москве. После создания опрос не меняется." actions={<ActivityLink to="/polls">К опросам</ActivityLink>}>
    <div className="activity-form"><FormLayout title="Параметры опроса" noValidate onSubmit={(event) => { event.preventDefault(); if (!create.isPending) submit(); }}>
      <TextInput label="Название" value={title} error={error('title')} onChange={(event) => { setTitle(event.currentTarget.value); form.clear('title'); }} />
      <Textarea label="Описание" value={description} error={error('description')} autosize minRows={2} onChange={(event) => { setDescription(event.currentTarget.value); form.clear('description'); }} />
      <TextInput label="Окончание (Москва)" type="datetime-local" value={endsAt} error={error('endsAt')} onChange={(event) => { setEndsAt(event.currentTarget.value); form.clear('endsAt'); }} />
      <AsyncContent state={loadState(groups, available.length === 0, 'Нет доступных групп. Дождитесь подтверждения администратора или выдачи доступа.')}>
        <Checkbox.Group label="Группы" value={groupIds} error={error('groupIds')} onChange={(value) => { setGroupIds(value); form.clear('groupIds'); }}>
          <Stack gap="xs" mt="xs">{available.map((group) => <Checkbox key={group.id} value={String(group.id)} label={`${group.name} · ${group.faculty.code}`} />)}</Stack>
        </Checkbox.Group>
      </AsyncContent>
      <Checkbox label="Анонимный опрос" description="Ответы по студентам не сохраняются, видны только итоги." checked={anonymous} onChange={(event) => setAnonymous(event.currentTarget.checked)} />
      {questions.map((question, index) => <QuestionEditor key={question.key} index={index} question={question} error={error} canRemove={questions.length > 1}
        onChange={(next) => setQuestions((old) => old.map((item) => item.key === question.key ? next : item))}
        onRemove={() => setQuestions((old) => old.filter((item) => item.key !== question.key))} />)}
      {error('questions') && <Text className="activity-danger">{error('questions')}</Text>}
      {questions.length < 20 && <Button variant="default" onClick={() => setQuestions((old) => [...old, blankQuestion()])}>Добавить вопрос</Button>}
      {create.error != null && <div role="alert"><Text>{pollError(create.error)}</Text></div>}
      <Button type="submit" loading={create.isPending} disabled={!groups.isSuccess || available.length === 0}>Создать опрос</Button>
    </FormLayout></div>
  </ActivityPage>;
}

export function NewPollPage() { return <ActivityAccess teacherOnly>{(user) => <NewPollForm userId={user.id} />}</ActivityAccess>; }

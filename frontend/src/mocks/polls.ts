import { http, HttpResponse } from 'msw';
import type { components } from '../api/schema';
import { readUser, validCsrf } from './auth';
import { examples } from './examples.generated';

type Schemas = components['schemas'];
type Viewer = Schemas['CurrentUser'];
type Group = Schemas['StudyGroupRef'];
type FieldError = { field: string; code: string; message: string };

export type StoredOption = { id: number; text: string; votes: number };
export type StoredQuestion = { id: number; text: string; type: Schemas['PollQuestionType']; options: StoredOption[] };
export type StoredPoll = {
  id: number; title: string; description: string; endsAt: string; anonymous: boolean;
  groups: Group[]; author: Schemas['UserRef']; questions: StoredQuestion[];
  voters: Set<number>; named: Map<number, Schemas['PollAnswer'][]>;
};
export type RosterEntry = { student: Schemas['UserRef']; groupId: number; state: 'ACTIVE' | 'PENDING' };
export type PollMockState = { now: () => Date; polls: StoredPoll[]; roster: RosterEntry[]; nextPollId: number; nextItemId: number };

const DAY = 24 * 3600_000;

function contractGroups(): Group[] {
  const body = examples.find((item) => item.operationId === 'listAccessibleGroups')?.responses.find((item) => item.status === 200)?.body;
  if (!body || typeof body !== 'object' || !('items' in body) || !Array.isArray(body.items)) throw new Error('Missing listAccessibleGroups example');
  return structuredClone(body.items) as Group[];
}

function problem(code: string, status: number, extra: Record<string, unknown> = {}) {
  return HttpResponse.json({ type: 'about:blank', title: 'Ошибка', status, code, ...extra }, {
    status, headers: { 'Content-Type': 'application/problem+json' },
  });
}

function invalid(fieldErrors: FieldError[]) {
  return problem('VALIDATION_FAILED', 400, { detail: 'Проверьте поля запроса', fieldErrors });
}

function at(now: () => Date, offset: number) {
  return new Date(now().getTime() + offset).toISOString().replace(/\.\d{3}Z$/, 'Z');
}

function stored(id: number, title: string, endsAt: string, anonymous: boolean, group: Group, questions: StoredQuestion[]): StoredPoll {
  return { id, title, description: '', endsAt, anonymous, groups: [group], author: { id: 2001, fullName: 'Петров Павел Петрович' },
    questions, voters: new Set(), named: new Map() };
}

function record(poll: StoredPoll, studentId: number, answers: Schemas['PollAnswer'][]) {
  poll.voters.add(studentId);
  for (const answer of answers) {
    const question = poll.questions.find((item) => item.id === answer.questionId);
    for (const option of question?.options ?? []) if (answer.optionIds.includes(option.id)) option.votes++;
  }
  if (!poll.anonymous) poll.named.set(studentId, answers);
}

export function createPollMockState(now: () => Date = () => new Date()): PollMockState {
  const [ivt21, ivt22] = contractGroups() as [Group, Group];
  const roster: RosterEntry[] = [
    { student: { id: 1001, fullName: 'Смирнова Анна Олеговна' }, groupId: ivt21.id, state: 'ACTIVE' },
    { student: { id: 1002, fullName: 'Кузнецов Дмитрий Андреевич' }, groupId: ivt21.id, state: 'ACTIVE' },
    { student: { id: 1003, fullName: 'Иванова Мария Сергеевна' }, groupId: ivt21.id, state: 'ACTIVE' },
    { student: { id: 1005, fullName: 'Алексеев Артём Ильич' }, groupId: ivt21.id, state: 'ACTIVE' },
    { student: { id: 1006, fullName: 'Белова Вера Павловна' }, groupId: ivt21.id, state: 'ACTIVE' },
    { student: { id: 1007, fullName: 'Григорьев Глеб Олегович' }, groupId: ivt21.id, state: 'ACTIVE' },
    { student: { id: 1004, fullName: 'Орлов Никита Сергеевич' }, groupId: ivt21.id, state: 'PENDING' },
    { student: { id: 1101, fullName: 'Захаров Захар Захарович' }, groupId: ivt22.id, state: 'ACTIVE' },
  ];
  const format = stored(801, 'Формат зачёта', at(now, 8 * DAY), true, ivt21, [
    { id: 811, text: 'Когда удобнее?', type: 'SINGLE', options: [{ id: 821, text: 'Утро', votes: 0 }, { id: 822, text: 'Вечер', votes: 0 }] },
  ]);
  format.description = 'Выберите удобное время.';
  const topic = stored(802, 'Тема курсового проекта', at(now, -2 * DAY), false, ivt21, [
    { id: 831, text: 'Какие темы интересны?', type: 'MULTIPLE', options: [
      { id: 841, text: 'Веб-сервисы', votes: 0 }, { id: 842, text: 'Мобильные приложения', votes: 0 }, { id: 843, text: 'Анализ данных', votes: 0 }] },
  ]);
  record(topic, 1001, [{ questionId: 831, optionIds: [841, 843] }]);
  record(topic, 1002, [{ questionId: 831, optionIds: [842] }]);
  const consultation = stored(803, 'Консультации перед зачётом', at(now, 3 * DAY), true, ivt21, [
    { id: 851, text: 'Какой день удобнее?', type: 'SINGLE', options: [{ id: 861, text: 'Понедельник', votes: 0 }, { id: 862, text: 'Среда', votes: 0 }] },
    { id: 852, text: 'Какой формат подходит?', type: 'MULTIPLE', options: [{ id: 863, text: 'Очно', votes: 0 }, { id: 864, text: 'Онлайн', votes: 0 }] },
  ]);
  const semester = stored(804, 'Итоги семестра', at(now, -5 * DAY), true, ivt21, [
    { id: 871, text: 'Оцените курс', type: 'SINGLE', options: [{ id: 881, text: 'Хорошо', votes: 0 }, { id: 882, text: 'Можно лучше', votes: 0 }] },
  ]);
  record(semester, 1002, [{ questionId: 871, optionIds: [881] }]);
  const duty = stored(805, 'Дежурства в лаборатории', at(now, 5 * DAY), true, ivt21, [
    { id: 891, text: 'Когда дежурить?', type: 'SINGLE', options: [{ id: 895, text: 'До пар', votes: 0 }, { id: 896, text: 'После пар', votes: 0 }] },
  ]);
  record(duty, 1001, [{ questionId: 891, optionIds: [896] }]);
  return { now, polls: [format, topic, consultation, semester, duty], roster, nextPollId: 806, nextItemId: 10_000 };
}

export function createPollMockHandlers(state: PollMockState = createPollMockState(), viewer: () => Viewer | null = readUser) {
  const groups = contractGroups();
  const open = (poll: StoredPoll) => Date.parse(poll.endsAt) > state.now().getTime();
  const isStudentOf = (user: Viewer, poll: StoredPoll) => user.role === 'STUDENT' && poll.groups.some((group) => group.id === user.group.id);
  const eligible = (poll: StoredPoll) => state.roster.filter((row) => row.state === 'ACTIVE' && poll.groups.some((group) => group.id === row.groupId)).length;
  const results = (poll: StoredPoll): Schemas['PollResults'] => ({
    voted: poll.voters.size,
    questions: poll.questions.map((question) => ({ questionId: question.id, options: question.options.map((option) => ({
      optionId: option.id, votes: option.votes, share: poll.voters.size ? Math.round(option.votes * 100 / poll.voters.size) : 0,
    })) })),
  });
  const view = (poll: StoredPoll, user: Viewer): Schemas['Poll'] => {
    const author = user.role === 'TEACHER' && poll.author.id === user.id;
    const voted = user.role === 'STUDENT' ? poll.voters.has(user.id) : null;
    return {
      id: poll.id, title: poll.title, description: poll.description, endsAt: poll.endsAt, open: open(poll), anonymous: poll.anonymous,
      groups: structuredClone(poll.groups), author: { ...poll.author },
      questions: poll.questions.map((question) => ({ id: question.id, text: question.text, type: question.type,
        options: question.options.map((option) => ({ id: option.id, text: option.text })) })),
      voted, eligible: author ? eligible(poll) : null, results: author || voted ? results(poll) : null,
    };
  };
  const summary = (poll: StoredPoll, user: Viewer): Schemas['PollSummary'] => ({
    id: poll.id, title: poll.title, endsAt: poll.endsAt, open: open(poll), anonymous: poll.anonymous,
    voted: user.role === 'STUDENT' ? poll.voters.has(user.id) : null, groups: structuredClone(poll.groups),
  });
  const order = (a: StoredPoll, b: StoredPoll) => {
    if (open(a) !== open(b)) return open(a) ? -1 : 1;
    const byEnd = open(a) ? Date.parse(a.endsAt) - Date.parse(b.endsAt) : Date.parse(b.endsAt) - Date.parse(a.endsAt);
    return byEnd || a.id - b.id;
  };
  const find = (id: unknown) => state.polls.find((poll) => poll.id === Number(id));

  function creationErrors(body: Partial<Schemas['PollCreate']>): FieldError[] {
    const errors: FieldError[] = [];
    const add = (field: string, message: string) => errors.push({ field, code: 'INVALID', message });
    const length = (value: unknown) => typeof value === 'string' ? [...value].length : -1;
    if (length(body.title) < 1 || length(body.title) > 200) add('title', 'Название — от 1 до 200 символов.');
    if (length(body.description) < 0 || length(body.description) > 2000) add('description', 'Описание — до 2000 символов.');
    if (typeof body.endsAt !== 'string' || !(Date.parse(body.endsAt) > state.now().getTime())) add('endsAt', 'Окончание должно быть в будущем.');
    if (!Array.isArray(body.groupIds) || body.groupIds.length === 0 || new Set(body.groupIds).size !== body.groupIds.length) add('groupIds', 'Выберите группы без повторов.');
    if (!Array.isArray(body.questions) || body.questions.length < 1 || body.questions.length > 20) add('questions', 'Добавьте от 1 до 20 вопросов.');
    (Array.isArray(body.questions) ? body.questions : []).forEach((question, index) => {
      if (length(question.text) < 1 || length(question.text) > 500) add(`questions[${index}].text`, 'Текст вопроса — от 1 до 500 символов.');
      if (question.type !== 'SINGLE' && question.type !== 'MULTIPLE') add(`questions[${index}].type`, 'Выберите тип вопроса.');
      const options = Array.isArray(question.options) ? question.options : [];
      const valid = options.length >= 2 && options.length <= 10 && options.every((option) => length(option) >= 1 && length(option) <= 200)
        && new Set(options.map((option) => option.trim())).size === options.length;
      if (!valid) add(`questions[${index}].options`, 'Нужно от 2 до 10 разных вариантов длиной до 200 символов.');
    });
    return errors;
  }

  function ballotError(poll: StoredPoll, body: Partial<Schemas['PollVote']>): string | null {
    const answers = Array.isArray(body.answers) ? body.answers : [];
    const seen = new Set<number>();
    for (const [index, answer] of answers.entries()) {
      const question = poll.questions.find((item) => item.id === answer.questionId);
      if (!question || seen.has(question.id)) return `answers[${index}].questionId`;
      seen.add(question.id);
      const ids = Array.isArray(answer.optionIds) ? answer.optionIds : [];
      const own = ids.every((id) => question.options.some((option) => option.id === id));
      const count = question.type === 'SINGLE' ? ids.length === 1 : ids.length >= 1;
      if (!own || !count || new Set(ids).size !== ids.length) return `answers[${index}].optionIds`;
    }
    return seen.size === poll.questions.length ? null : 'answers';
  }

  return [
    http.get('*/api/v1/polls', () => {
      const user = viewer();
      if (!user) return problem('UNAUTHENTICATED', 401);
      if (user.role === 'ADMIN') return problem('FORBIDDEN', 403);
      const items = state.polls.filter((poll) => user.role === 'TEACHER' ? poll.author.id === user.id : isStudentOf(user, poll))
        .sort(order).map((poll) => summary(poll, user));
      return HttpResponse.json({ items });
    }),
    http.post('*/api/v1/polls', async ({ request }) => {
      const user = viewer();
      if (!user) return problem('UNAUTHENTICATED', 401);
      if (!validCsrf(request)) return problem('CSRF_INVALID', 403);
      if (user.role !== 'TEACHER') return problem('FORBIDDEN', 403);
      const body = await request.json() as Partial<Schemas['PollCreate']>;
      const errors = creationErrors(body);
      if (errors.length) return invalid(errors);
      const created = body as Schemas['PollCreate'];
      const targets = created.groupIds.map((id) => groups.find((group) => group.id === id));
      if (!user.teacherApproved || targets.some((group) => !group)) return problem('GROUP_NOT_ACCESSIBLE', 403);
      const poll: StoredPoll = {
        id: state.nextPollId++, title: created.title, description: created.description, endsAt: created.endsAt, anonymous: created.anonymous,
        groups: targets as Group[], author: { id: user.id, fullName: user.fullName }, voters: new Set(), named: new Map(),
        questions: created.questions.map((question) => ({ id: state.nextItemId++, text: question.text, type: question.type,
          options: question.options.map((text) => ({ id: state.nextItemId++, text: text.trim(), votes: 0 })) })),
      };
      state.polls.push(poll);
      return HttpResponse.json(view(poll, user), { status: 201 });
    }),
    http.get('*/api/v1/polls/:pollId', ({ params }) => {
      const user = viewer();
      if (!user) return problem('UNAUTHENTICATED', 401);
      const poll = find(params.pollId);
      if (!poll) return problem('NOT_FOUND', 404);
      const author = user.role === 'TEACHER' && poll.author.id === user.id;
      if (!author && !isStudentOf(user, poll)) return problem('FORBIDDEN', 403);
      return HttpResponse.json(view(poll, user));
    }),
    http.post('*/api/v1/polls/:pollId/vote', async ({ request, params }) => {
      const user = viewer();
      if (!user) return problem('UNAUTHENTICATED', 401);
      if (!validCsrf(request)) return problem('CSRF_INVALID', 403);
      if (user.role !== 'STUDENT') return problem('FORBIDDEN', 403);
      const poll = find(params.pollId);
      if (!poll) return problem('NOT_FOUND', 404);
      if (!isStudentOf(user, poll)) return problem('FORBIDDEN', 403);
      if (!open(poll)) return problem('POLL_CLOSED', 422, { detail: 'Опрос завершён.' });
      if (user.membershipState !== 'ACTIVE') return problem('MEMBERSHIP_NOT_ACTIVE', 422, { detail: 'Голосовать можно после подтверждения членства.' });
      if (poll.voters.has(user.id)) return problem('ALREADY_VOTED', 409, { detail: 'Вы уже проголосовали в этом опросе.' });
      const body = await request.json() as Partial<Schemas['PollVote']>;
      const field = ballotError(poll, body);
      if (field) return invalid([{ field, code: 'INVALID', message: 'Проверьте ответ на вопрос.' }]);
      record(poll, user.id, (body as Schemas['PollVote']).answers);
      return HttpResponse.json(results(poll));
    }),
    http.get('*/api/v1/polls/:pollId/responses', ({ params }) => {
      const user = viewer();
      if (!user) return problem('UNAUTHENTICATED', 401);
      if (user.role !== 'TEACHER') return problem('FORBIDDEN', 403);
      const poll = find(params.pollId);
      if (!poll) return problem('NOT_FOUND', 404);
      if (poll.author.id !== user.id) return problem('FORBIDDEN', 403);
      if (poll.anonymous) return problem('INVALID_STATE', 409, { detail: 'Ответы по студентам в анонимном опросе не сохраняются.' });
      const items: Schemas['PollResponse'][] = [...poll.named.entries()].map(([studentId, answers]) => {
        const row = state.roster.find((item) => item.student.id === studentId);
        const group = poll.groups.find((item) => item.id === row?.groupId) ?? poll.groups[0]!;
        return { student: row?.student ?? { id: studentId, fullName: `Студент ${studentId}` }, group, answers: structuredClone(answers) };
      }).sort((a, b) => a.student.fullName.localeCompare(b.student.fullName, 'ru') || a.student.id - b.student.id);
      return HttpResponse.json({ items });
    }),
  ];
}

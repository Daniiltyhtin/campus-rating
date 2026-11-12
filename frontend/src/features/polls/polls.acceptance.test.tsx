import { cleanup, configure, fireEvent, getConfig, screen, waitFor, within } from '@testing-library/react';
import { http } from 'msw';
import type { components } from '../../api/schema';
import { createPollMockHandlers, createPollMockState, type PollMockState } from '../../mocks/polls';
import { server } from '../../mocks/server';
import { authUsers, createAuthScenario, problem } from '../../test/authAcceptance';
import { json, mountActivities } from '../../test/activitiesAcceptance';

type Schemas = components['schemas'];
type Viewer = Schemas['CurrentUser'];
type Recorded = { method: string; path: string; body: unknown; csrf: string | null };
type User = ReturnType<typeof mountActivities>['user'];

const kuznetsov: Schemas['CurrentStudent'] = { ...authUsers.student, id: 1002, fullName: 'Кузнецов Дмитрий Андреевич',
  email: 'kuznetsov@student.campus.test' };

const originalTimeout = getConfig().asyncUtilTimeout;
beforeAll(() => { configure({ asyncUtilTimeout: 3_000 }); server.listen({ onUnhandledFrame: 'error' }); });
afterEach(() => {
  cleanup();
  server.resetHandlers();
  localStorage.clear();
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/';
  document.cookie = 'campus-mock-user=; Max-Age=0; Path=/';
});
afterAll(() => { server.close(); configure({ asyncUtilTimeout: originalTimeout }); });

function signIn(viewer: Viewer) {
  createAuthScenario(viewer);
  document.cookie = `campus-mock-user=${encodeURIComponent(JSON.stringify(viewer))}; Path=/`;
}

function scenario(viewer: Viewer) {
  signIn(viewer);
  const state = createPollMockState();
  const requests: Recorded[] = [];
  server.use(
    http.all('*/api/v1/polls*', async ({ request }) => {
      const text = request.method === 'GET' ? '' : await request.clone().text();
      requests.push({ method: request.method, path: new URL(request.url).pathname, body: text ? JSON.parse(text) : null,
        csrf: request.headers.get('X-XSRF-TOKEN') });
      return undefined;
    }),
    ...createPollMockHandlers(state),
  );
  return { state, requests };
}

function switchViewer(viewer: Viewer) {
  cleanup();
  signIn(viewer);
}

function poll(state: PollMockState, id: number) {
  const found = state.polls.find((item) => item.id === id);
  if (!found) throw new Error(`Нет опроса ${id} в моках`);
  return found;
}

function smallest(pattern: RegExp) {
  return (_: string, element: Element | null) => !!element && pattern.test(element.textContent ?? '')
    && !Array.from(element.children).some((child) => pattern.test(child.textContent ?? ''));
}

function resultRowPattern(option: string, votes: number, share: number) {
  return new RegExp(`${option}[\\s\\S]*(?<!\\d)${votes}(?!\\d)[\\s\\S]*(?<!\\d)${share}\\s*%`);
}

async function expectResult(option: string, votes: number, share: number) {
  expect((await screen.findAllByText(smallest(resultRowPattern(option, votes, share)))).length).toBeGreaterThan(0);
}

function expectNoResults() {
  expect(screen.queryByText(/\d\s*%/)).not.toBeInTheDocument();
}

function votes(requests: Recorded[]) {
  return requests.filter((request) => request.method === 'POST' && request.path.endsWith('/vote'));
}

function questionGroup(index: number) {
  return screen.getByRole('group', { name: new RegExp(`^Вопрос ${index}(\\D|$)`) });
}

async function fillQuestion(user: User, index: number, text: string, type: 'Один вариант' | 'Несколько вариантов', options: string[]) {
  const group = questionGroup(index);
  await user.type(within(group).getByRole('textbox', { name: 'Текст вопроса' }), text);
  await user.click(within(group).getByRole('radio', { name: type }));
  for (const [position, option] of options.entries()) {
    const name = `Вариант ${position + 1}`;
    if (!within(group).queryByRole('textbox', { name })) await user.click(within(group).getByRole('button', { name: 'Добавить вариант' }));
    await user.type(within(group).getByRole('textbox', { name }), option);
  }
}

async function fillPollHeader(user: User) {
  await user.type(await screen.findByRole('textbox', { name: 'Название' }), 'Формат экзамена');
  await user.type(screen.getByRole('textbox', { name: 'Описание' }), 'Выберите удобный вариант.');
  fireEvent.change(screen.getByLabelText(/Окончание/), { target: { value: '2100-06-15T14:00' } });
  await user.click(await screen.findByRole('checkbox', { name: /ИВТ-21/ }));
}

describe('U16 студент: «Главная», голос и результаты — П4, П5, AE2', { timeout: 15_000 }, () => {
  it('блок «Новые опросы» показывает открытые опросы без голоса и ведёт на страницу опроса', async () => {
    scenario(authUsers.student);
    const { user, router } = mountActivities('/');
    const block = await screen.findByRole('region', { name: 'Новые опросы' });
    expect(await within(block).findByRole('link', { name: /Формат зачёта/ })).toHaveAttribute('href', '/polls/801');
    expect(within(block).getByRole('link', { name: /Консультации перед зачётом/ })).toHaveAttribute('href', '/polls/803');
    expect(within(block).queryByText(/Дежурства в лаборатории/)).not.toBeInTheDocument();
    expect(within(block).queryByText(/Тема курсового проекта/)).not.toBeInTheDocument();
    expect(within(block).queryByText(/Итоги семестра/)).not.toBeInTheDocument();
    await user.click(within(block).getByRole('link', { name: /Формат зачёта/ }));
    expect(await screen.findByRole('heading', { name: 'Формат зачёта' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/polls/801');
  });

  it('AE2: SINGLE — радио, кнопка недоступна без ответа, после голоса полосы с числом и долей и нет повторного голоса', async () => {
    const { requests } = scenario(authUsers.student);
    const { user } = mountActivities('/polls/801');
    expect(await screen.findByRole('heading', { name: 'Формат зачёта' })).toBeInTheDocument();
    expectNoResults();
    const question = screen.getByRole('radiogroup', { name: /Когда удобнее\?/ });
    expect(within(question).getAllByRole('radio')).toHaveLength(2);
    const submit = screen.getByRole('button', { name: 'Проголосовать' });
    expect(submit).toBeDisabled();
    await user.click(within(question).getByRole('radio', { name: 'Вечер' }));
    expect(submit).toBeEnabled();
    await user.click(submit);
    await expectResult('Утро', 0, 0);
    await expectResult('Вечер', 1, 100);
    expect(screen.getByText(/Проголосовал[аио]?\s*:?\s*1(?!\d)/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Проголосовать' })).not.toBeInTheDocument();
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
    expect(votes(requests)).toEqual([expect.objectContaining({ csrf: 'acceptance-csrf-1',
      body: { answers: [{ questionId: 811, optionIds: [822] }] } })]);
  });

  it('AE2: не голосовавший студент не видит статистику', async () => {
    const { state } = scenario(kuznetsov);
    poll(state, 801).voters.add(1001);
    poll(state, 801).questions[0]!.options[1]!.votes = 1;
    mountActivities('/polls/801');
    expect(await screen.findByRole('heading', { name: 'Формат зачёта' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Проголосовать' })).toBeDisabled();
    expectNoResults();
  });

  it('MULTIPLE — флажки; кнопка доступна, только когда отвечены все вопросы', async () => {
    const { requests } = scenario(authUsers.student);
    const { user } = mountActivities('/polls/803');
    const day = await screen.findByRole('radiogroup', { name: /Какой день удобнее\?/ });
    const format = screen.getByRole('group', { name: /Какой формат подходит\?/ });
    expect(within(format).getAllByRole('checkbox')).toHaveLength(2);
    const submit = screen.getByRole('button', { name: 'Проголосовать' });
    await user.click(within(day).getByRole('radio', { name: 'Среда' }));
    expect(submit).toBeDisabled();
    await user.click(within(format).getByRole('checkbox', { name: 'Очно' }));
    await user.click(within(format).getByRole('checkbox', { name: 'Онлайн' }));
    expect(submit).toBeEnabled();
    await user.click(within(format).getByRole('checkbox', { name: 'Онлайн' }));
    expect(submit).toBeEnabled();
    await user.click(within(format).getByRole('checkbox', { name: 'Очно' }));
    expect(submit).toBeDisabled();
    await user.click(within(format).getByRole('checkbox', { name: 'Очно' }));
    await user.click(within(format).getByRole('checkbox', { name: 'Онлайн' }));
    await user.click(submit);
    await expectResult('Онлайн', 1, 100);
    await expectResult('Понедельник', 0, 0);
    expect(votes(requests)).toHaveLength(1);
    expect(votes(requests)[0]!.body).toEqual({ answers: expect.arrayContaining([
      { questionId: 851, optionIds: [862] },
      { questionId: 852, optionIds: expect.arrayContaining([863, 864]) as unknown },
    ]) as unknown });
    expect((votes(requests)[0]!.body as Schemas['PollVote']).answers).toHaveLength(2);
  });

  it('PENDING видит опрос, но вместо кнопки — пояснение про подтверждение членства', async () => {
    const { requests } = scenario(authUsers.pendingStudent);
    mountActivities('/polls/801');
    expect(await screen.findByRole('heading', { name: 'Формат зачёта' })).toBeInTheDocument();
    expect(screen.getByText(/Голосовать можно после подтверждения членства/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Проголосовать' })).not.toBeInTheDocument();
    expectNoResults();
    expect(votes(requests)).toHaveLength(0);
  });

  it('завершённый опрос: голосовавший видит результаты, не голосовавший — «Опрос завершён» без статистики', async () => {
    scenario(authUsers.student);
    mountActivities('/polls/802');
    expect(await screen.findByRole('heading', { name: 'Тема курсового проекта' })).toBeInTheDocument();
    await expectResult('Веб-сервисы', 1, 50);
    await expectResult('Мобильные приложения', 1, 50);
    expect(screen.queryByRole('button', { name: 'Проголосовать' })).not.toBeInTheDocument();

    switchViewer(authUsers.student);
    mountActivities('/polls/804');
    expect(await screen.findByRole('heading', { name: 'Итоги семестра' })).toBeInTheDocument();
    expect(screen.getAllByText(/Опрос завершён/).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: 'Проголосовать' })).not.toBeInTheDocument();
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
    expectNoResults();
  });

  it('список /polls студента — серверный порядок, без кнопки создания', async () => {
    scenario(authUsers.student);
    mountActivities('/polls');
    const titles = ['Консультации перед зачётом', 'Дежурства в лаборатории', 'Формат зачёта', 'Тема курсового проекта', 'Итоги семестра'];
    const nodes = [];
    for (const title of titles) nodes.push(await screen.findByRole('link', { name: new RegExp(title) }));
    for (let index = 1; index < nodes.length; index++) {
      expect(nodes[index - 1]!.compareDocumentPosition(nodes[index]!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    }
    expect(nodes[2]).toHaveAttribute('href', '/polls/801');
    expect(screen.queryByRole('link', { name: 'Новый опрос' })).not.toBeInTheDocument();
  });
});

describe('U16 ошибки голоса и загрузки', { timeout: 15_000 }, () => {
  it('409 ALREADY_VOTED — понятный текст и перечитанная страница с результатами', async () => {
    const { state, requests } = scenario(authUsers.student);
    server.use(http.post('*/api/v1/polls/801/vote', () => {
      poll(state, 801).voters.add(1001);
      poll(state, 801).questions[0]!.options[0]!.votes = 1;
      return problem('ALREADY_VOTED', 409, { detail: 'Вы уже проголосовали в этом опросе.' });
    }));
    const { user } = mountActivities('/polls/801');
    await user.click(await screen.findByRole('radio', { name: 'Вечер' }));
    await user.click(screen.getByRole('button', { name: 'Проголосовать' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/уже проголосовал/i);
    await expectResult('Утро', 1, 100);
    expect(requests.filter((request) => request.method === 'GET' && request.path === '/api/v1/polls/801').length).toBeGreaterThanOrEqual(2);
    expect(screen.queryByRole('button', { name: 'Проголосовать' })).not.toBeInTheDocument();
  });

  it('422 POLL_CLOSED — понятный текст, голосование пропадает после обновления', async () => {
    const { state, requests } = scenario(authUsers.student);
    server.use(http.post('*/api/v1/polls/801/vote', () => {
      poll(state, 801).endsAt = '2000-01-01T00:00:00Z';
      return problem('POLL_CLOSED', 422, { detail: 'Опрос завершён.' });
    }));
    const { user } = mountActivities('/polls/801');
    await user.click(await screen.findByRole('radio', { name: 'Утро' }));
    await user.click(screen.getByRole('button', { name: 'Проголосовать' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/завершён/i);
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Проголосовать' })).not.toBeInTheDocument());
    expect(requests.filter((request) => request.method === 'GET' && request.path === '/api/v1/polls/801').length).toBeGreaterThanOrEqual(2);
    expectNoResults();
  });

  it('ошибка загрузки опроса — «Повторить»', async () => {
    scenario(authUsers.student);
    let fail = true;
    server.use(http.get('*/api/v1/polls/801', () => fail ? problem('INTERNAL_ERROR', 500) : undefined));
    const { user } = mountActivities('/polls/801');
    const retry = await screen.findByRole('button', { name: 'Повторить' });
    expect(screen.queryByRole('heading', { name: 'Формат зачёта' })).not.toBeInTheDocument();
    fail = false;
    await user.click(retry);
    expect(await screen.findByRole('heading', { name: 'Формат зачёта' })).toBeInTheDocument();
  });

  it('ошибка загрузки списка на «Главной» — «Повторить» в блоке опросов', async () => {
    scenario(authUsers.student);
    let fail = true;
    server.use(http.get('*/api/v1/polls', () => fail ? problem('INTERNAL_ERROR', 500) : undefined));
    const { user } = mountActivities('/');
    const block = await screen.findByRole('region', { name: 'Новые опросы' });
    const retry = await within(block).findByRole('button', { name: 'Повторить' });
    fail = false;
    await user.click(retry);
    expect(await within(block).findByRole('link', { name: /Формат зачёта/ })).toBeInTheDocument();
  });
});

describe('U16 преподаватель: создание опроса — П1, П2', { timeout: 30_000 }, () => {
  it('«Новый опрос» из блока «Мои опросы» на «Главной»', async () => {
    scenario(authUsers.teacher);
    const { user, router } = mountActivities('/');
    const block = await screen.findByRole('region', { name: 'Мои опросы' });
    expect(await within(block).findByRole('link', { name: /Формат зачёта/ })).toHaveAttribute('href', '/polls/801');
    expect(within(block).getByRole('link', { name: /Консультации перед зачётом/ })).toBeInTheDocument();
    expect(within(block).queryByText(/Тема курсового проекта/)).not.toBeInTheDocument();
    await user.click(within(block).getByRole('link', { name: 'Новый опрос' }));
    expect(await screen.findByRole('heading', { name: 'Новый опрос' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/polls/new');
  });

  it('форма: вопросы и варианты добавляются и удаляются, окончание по Москве уходит в UTC, после создания — страница опроса', async () => {
    const { requests } = scenario(authUsers.teacher);
    const { user, router } = mountActivities('/polls/new');
    await fillPollHeader(user);
    const anonymous = screen.getByLabelText('Анонимный опрос');
    if (!(anonymous as HTMLInputElement).checked) await user.click(anonymous);
    expect(anonymous).toBeChecked();
    await fillQuestion(user, 1, 'Когда удобнее?', 'Один вариант', ['Утро', 'Вечер']);
    await user.click(screen.getByRole('button', { name: 'Добавить вопрос' }));
    await fillQuestion(user, 2, 'Какой формат?', 'Несколько вариантов', ['Устно', 'Письменно', 'Лишний']);
    await user.click(within(questionGroup(2)).getByRole('button', { name: /Удалить вариант 3/ }));
    expect(within(questionGroup(2)).queryByRole('textbox', { name: 'Вариант 3' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Добавить вопрос' }));
    await user.type(within(questionGroup(3)).getByRole('textbox', { name: 'Текст вопроса' }), 'Удаляемый');
    await user.click(within(questionGroup(3)).getByRole('button', { name: /Удалить вопрос/ }));
    expect(screen.queryByRole('group', { name: /^Вопрос 3(\D|$)/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Создать опрос' }));
    expect(await screen.findByRole('heading', { name: 'Формат экзамена' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/polls/806');
    const posts = requests.filter((request) => request.method === 'POST' && request.path === '/api/v1/polls');
    expect(posts).toEqual([expect.objectContaining({ csrf: 'acceptance-csrf-1', body: {
      title: 'Формат экзамена', description: 'Выберите удобный вариант.',
      endsAt: expect.stringMatching(/^2100-06-15T11:00:00(?:\.000)?Z$/) as unknown, groupIds: [11], anonymous: true,
      questions: [
        { text: 'Когда удобнее?', type: 'SINGLE', options: ['Утро', 'Вечер'] },
        { text: 'Какой формат?', type: 'MULTIPLE', options: ['Устно', 'Письменно'] },
      ],
    } })]);
  });

  it('ошибки 400 показываются под полями и форма остаётся', async () => {
    scenario(authUsers.teacher);
    server.use(http.post('*/api/v1/polls', () => problem('VALIDATION_FAILED', 400, { fieldErrors: [
      { field: 'endsAt', code: 'INVALID', message: 'Окончание должно быть в будущем.' },
      { field: 'questions[0].options', code: 'INVALID', message: 'Варианты не должны повторяться.' },
    ] })));
    const { user, router } = mountActivities('/polls/new');
    await fillPollHeader(user);
    await fillQuestion(user, 1, 'Когда удобнее?', 'Один вариант', ['Утро', 'Вечер']);
    await user.click(screen.getByRole('button', { name: 'Создать опрос' }));
    expect(await screen.findByText('Окончание должно быть в будущем.')).toBeInTheDocument();
    expect(screen.getByLabelText(/Окончание/)).toHaveAttribute('aria-invalid', 'true');
    expect(within(questionGroup(1)).getByText('Варианты не должны повторяться.')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/polls/new');
  });

  it('группы — только из доступных преподавателю', async () => {
    scenario(authUsers.teacher);
    mountActivities('/polls/new');
    expect(await screen.findByRole('checkbox', { name: /ИВТ-21/ })).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: /ИВТ-22/ })).toBeInTheDocument();
    expect(screen.getAllByRole('checkbox', { name: /ИВТ-|ЭК-/ })).toHaveLength(2);
  });
});

describe('U16 автор: результаты и ответы по студентам — П6, AE3', { timeout: 15_000 }, () => {
  it('AE2+AE3: после голоса Смирновой автор анонимного опроса видит «1 из 6», распределение и пометку без таблицы', async () => {
    scenario(authUsers.student);
    const { user } = mountActivities('/polls/801');
    await user.click(await screen.findByRole('radio', { name: 'Вечер' }));
    await user.click(screen.getByRole('button', { name: 'Проголосовать' }));
    await expectResult('Вечер', 1, 100);

    switchViewer(authUsers.teacher);
    mountActivities('/polls/801');
    expect(await screen.findByRole('heading', { name: 'Формат зачёта' })).toBeInTheDocument();
    expect(await screen.findByText(/Проголосовали\s*1\s*из\s*6/i)).toBeInTheDocument();
    await expectResult('Утро', 0, 0);
    await expectResult('Вечер', 1, 100);
    expect(screen.getByText(/Анонимный опрос: ответы по студентам не сохраняются/)).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Проголосовать' })).not.toBeInTheDocument();
  });

  it('AE3: автор неанонимного опроса видит таблицу ответов по студентам в порядке ФИО', async () => {
    scenario(authUsers.teacher);
    mountActivities('/polls/802');
    expect(await screen.findByRole('heading', { name: 'Тема курсового проекта' })).toBeInTheDocument();
    expect(await screen.findByText(/Проголосовали\s*2\s*из\s*6/i)).toBeInTheDocument();
    const table = await screen.findByRole('table');
    const rows = within(table).getAllByRole('row').filter((row) => /Кузнецов|Смирнова/.test(row.textContent ?? ''));
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('Кузнецов Дмитрий Андреевич');
    expect(rows[0]).toHaveTextContent('ИВТ-21');
    expect(rows[0]).toHaveTextContent('Мобильные приложения');
    expect(rows[1]).toHaveTextContent('Смирнова Анна Олеговна');
    expect(rows[1]).toHaveTextContent('ИВТ-21');
    expect(rows[1]).toHaveTextContent(/Веб-сервисы[\s\S]*Анализ данных|Анализ данных[\s\S]*Веб-сервисы/);
    expect(rows[1]).not.toHaveTextContent('Мобильные приложения');
    expect(screen.queryByText(/Анонимный опрос: ответы по студентам не сохраняются/)).not.toBeInTheDocument();
  });

  it('список /polls преподавателя — свои опросы и «Новый опрос»', async () => {
    scenario(authUsers.teacher);
    mountActivities('/polls');
    expect(await screen.findByRole('link', { name: /Формат зачёта/ })).toHaveAttribute('href', '/polls/801');
    expect(screen.getByRole('link', { name: /Тема курсового проекта/ })).toHaveAttribute('href', '/polls/802');
    expect(screen.getByRole('link', { name: 'Новый опрос' })).toHaveAttribute('href', '/polls/new');
  });
});

describe('U16 лента: тексты POLL_CREATED и POLL_CLOSED — П8, AE1, AE5', () => {
  it('показывает оба вида со ссылкой на опрос и московским временем', async () => {
    scenario(authUsers.teacher);
    const items: Schemas['PersonalFeedItem'][] = [
      { id: 2, kind: 'POLL_CLOSED', createdAt: '2026-11-20T15:00:00Z', pollId: 801, pollTitle: 'Формат зачёта', voted: 1, eligible: 6 },
      { id: 1, kind: 'POLL_CREATED', createdAt: '2026-11-18T09:00:00Z', pollId: 801, pollTitle: 'Формат зачёта', endsAt: '2026-11-20T15:00:00Z' },
    ];
    server.use(http.get('*/api/v1/feed/personal', () => json({ items, nextCursor: null })));
    mountActivities('/');
    expect(await screen.findByRole('link', { name: 'Опрос «Формат зачёта» завершён · проголосовали 1 из 6' }))
      .toHaveAttribute('href', '/polls/801');
    expect(screen.getByRole('link', { name: 'Новый опрос «Формат зачёта» · до 20.11.2026 18:00' }))
      .toHaveAttribute('href', '/polls/801');
  });
});

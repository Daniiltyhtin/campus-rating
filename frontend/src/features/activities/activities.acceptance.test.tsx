import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http } from 'msw';
import { server } from '../../mocks/server';
import {
  activityNames, authUsers, chooseOption, createActivitiesScenario, deferred, fillActivity, json,
  mountActivities, participantNames, problem, selectParticipant, setLevel,
} from '../../test/activitiesAcceptance';
import type { components } from '../../api/schema';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => {
  cleanup();
  server.resetHandlers();
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/';
  localStorage.clear();
});
afterAll(() => server.close());

describe('U9 список и запись — R17/R18', () => {
  it('показывает серверный порядок, московское время и фильтрует учебные/общественные', async () => {
    createActivitiesScenario();
    const { user } = mountActivities('/activities');
    const first = await screen.findByText(activityNames.social);
    const second = screen.getByText(activityNames.academic);
    expect(first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getAllByText(/10:00/).length).toBeGreaterThan(0);
    expect(screen.getAllByRole('link', { name: authUsers.teacher.fullName })[0]).toHaveAttribute('href', '/users/2001');
    await user.click(screen.getByRole('radio', { name: /Учебные/ }));
    expect(screen.getByText(activityNames.academic)).toBeInTheDocument();
    expect(screen.queryByText(activityNames.social)).not.toBeInTheDocument();
    await user.click(screen.getByRole('radio', { name: /Общественные/ }));
    expect(screen.getByText(activityNames.social)).toBeInTheDocument();
    expect(screen.queryByText(activityNames.academic)).not.toBeInTheDocument();
  });

  it('записывает и отменяет без тела с CSRF, используя enrollmentOpen вместо часов браузера', async () => {
    const state = createActivitiesScenario();
    const { user } = mountActivities('/activities');
    await user.click(await screen.findByRole('button', { name: new RegExp(`Записаться.*${activityNames.social}`) }));
    await user.click(await screen.findByRole('button', { name: new RegExp(`Отменить запись.*${activityNames.social}`) }));
    expect(await screen.findByRole('button', { name: new RegExp(`Записаться.*${activityNames.social}`) })).toBeEnabled();
    expect(state.requests.filter((request) => request.path.endsWith('/enrollment'))).toEqual([
      expect.objectContaining({ method: 'PUT', body: null, csrf: 'acceptance-csrf-1' }),
      expect.objectContaining({ method: 'DELETE', body: null, csrf: 'acceptance-csrf-1' }),
    ]);
  });

  it('ожидающий PUT блокирует повторную запись, после ответа состояние перечитывается', async () => {
    const state = createActivitiesScenario();
    const pending = deferred();
    let puts = 0;
    server.use(http.put('*/api/v1/activities/603/enrollment', async () => {
      puts++;
      await pending.promise;
      const item = state.list.items.find((activity) => activity.id === 603);
      if (!item) throw new Error('Missing enrollment fixture');
      item.enrolled = true;
      return json(item);
    }));
    const { user } = mountActivities('/activities');
    try {
      const enroll = await screen.findByRole('button', { name: new RegExp(`Записаться.*${activityNames.social}`) });
      await user.dblClick(enroll);
      await waitFor(() => expect(puts).toBe(1));
      expect(enroll).toBeDisabled();
      expect(screen.queryByRole('button', { name: new RegExp(`Отменить запись.*${activityNames.social}`) })).not.toBeInTheDocument();
      await user.click(enroll);
      expect(puts).toBe(1);
      pending.resolve();
      expect(await screen.findByRole('button', { name: new RegExp(`Отменить запись.*${activityNames.social}`) })).toBeEnabled();
      expect(puts).toBe(1);
      await waitFor(() => expect(state.requests.filter((request) => request.method === 'GET' && request.path === '/api/v1/activities').length).toBeGreaterThanOrEqual(2));
    } finally { pending.resolve(); }
  });

  it('PENDING видит список и может отменить прежнюю запись, но не записаться заново', async () => {
    const state = createActivitiesScenario(authUsers.pendingStudent);
    state.list.items[0]!.enrolled = true;
    const { user } = mountActivities('/activities');
    await user.click(await screen.findByRole('button', { name: new RegExp(`Отменить запись.*${activityNames.social}`) }));
    await waitFor(() => expect(state.requests.filter((request) => request.method === 'DELETE')).toHaveLength(1));
    expect(await screen.findByText(/членство.*подтвержден|членство.*подтверждения/i)).toBeInTheDocument();
    const enroll = screen.queryByRole('button', { name: new RegExp(`Записаться.*${activityNames.social}`) });
    if (enroll) expect(enroll).toBeDisabled();
    expect(state.requests.filter((request) => request.method === 'PUT')).toHaveLength(0);
    expect(screen.getByText(activityNames.academic)).toBeInTheDocument();
  });

  it('не предлагает запись на учебную, закрытую или enrolled=null активность', async () => {
    const state = createActivitiesScenario();
    state.list.items[0]!.enrolled = null;
    mountActivities('/activities');
    await screen.findByText(activityNames.academic);
    expect(screen.queryByRole('button', { name: /Записаться/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Отменить запись/ })).not.toBeInTheDocument();
  });

  it('ENROLLMENT_CLOSED после отправки обновляет карточку, не показывает ложную запись', async () => {
    const state = createActivitiesScenario();
    let posts = 0;
    server.use(http.put('*/api/v1/activities/603/enrollment', () => {
      posts++;
      state.list.items[0]!.enrollmentOpen = false;
      return problem('ENROLLMENT_CLOSED', 422);
    }));
    const { user } = mountActivities('/activities');
    await user.click(await screen.findByRole('button', { name: new RegExp(`Записаться.*${activityNames.social}`) }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/запись.*закрыт|время.*записи.*истек|активность.*начал/i);
    await waitFor(() => expect(state.requests.filter((request) => request.method === 'GET' && request.path === '/api/v1/activities').length).toBeGreaterThanOrEqual(2));
    expect(posts).toBe(1);
    expect(screen.queryByRole('button', { name: new RegExp(`Отменить запись.*${activityNames.social}`) })).not.toBeInTheDocument();
  });

  it('ошибка списка имеет повтор, пустой список объяснён', async () => {
    const state = createActivitiesScenario();
    let fail = true;
    server.use(http.get('*/api/v1/activities', () => fail ? problem('INTERNAL_ERROR', 500) : json({ items: [] })));
    const { user } = mountActivities('/activities');
    const retry = await screen.findByRole('button', { name: /Повторить/i });
    expect(screen.queryByText(activityNames.social)).not.toBeInTheDocument();
    fail = false;
    await user.click(retry);
    expect(await screen.findByText(/активност.*нет|нет.*активност|активност.*появятся/i)).toBeInTheDocument();
    expect(state.requests.filter((request) => request.method === 'PUT')).toHaveLength(0);
  });
});

// Несколько полей вводятся настоящими пользовательскими событиями, что в jsdom дольше обычного.
describe('U9 создание — R17/R41', { timeout: 15_000 }, () => {
  it('отправляет проверенные id/числа, преобразует Москву в UTC и обновляет список', async () => {
    const state = createActivitiesScenario(authUsers.teacher);
    const { user, router } = mountActivities('/activities/new');
    await fillActivity(user);
    await user.click(await screen.findByRole('checkbox', { name: /ИВТ-22/ }));
    await user.click(screen.getByRole('button', { name: 'Создать активность' }));
    expect(await screen.findByText('Новый хакатон')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/activities');
    expect(state.requests.filter((request) => request.method === 'POST' && request.path === '/api/v1/activities')).toEqual([
      expect.objectContaining({ csrf: 'acceptance-csrf-1', body: { title: 'Новый хакатон', description: 'Командное соревнование',
        type: 'ACADEMIC', groupIds: [11, 12], startsAt: expect.stringMatching(/^2100-06-15T11:00:00(?:\.000)?Z$/), maxPoints: 75 } }),
    ]);
  });

  it.each(['1', '10001', '2.5'])('не отправляет недопустимый максимум %s', async (points) => {
    const state = createActivitiesScenario(authUsers.teacher);
    const { user } = mountActivities('/activities/new');
    await fillActivity(user, points);
    await user.click(screen.getByRole('button', { name: 'Создать активность' }));
    expect(screen.getByLabelText('Максимум баллов')).toHaveAttribute('aria-invalid', 'true');
    expect(state.requests.filter((request) => request.method === 'POST')).toHaveLength(0);
  });

  it.each(['2', '10000'])('принимает включённую границу максимума %s', async (points) => {
    const state = createActivitiesScenario(authUsers.teacher);
    const { user } = mountActivities('/activities/new');
    await fillActivity(user, points);
    await user.click(screen.getByRole('button', { name: 'Создать активность' }));
    expect(await screen.findByText('Новый хакатон')).toBeInTheDocument();
    expect(state.requests.find((request) => request.method === 'POST')?.body).toEqual(expect.objectContaining({ maxPoints: Number(points) }));
  });

  it('нет доступных групп — объяснение допуска и disabled submit', async () => {
    const state = createActivitiesScenario(authUsers.unapprovedTeacher);
    state.groups.items = [];
    mountActivities('/activities/new');
    expect(await screen.findByText(/нет.*доступных групп|доступных групп.*нет|допуск.*администратор|подтверждения администратора/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Создать активность' })).toBeDisabled();
    expect(state.requests.filter((request) => request.method === 'POST')).toHaveLength(0);
  });

  it('показывает fieldErrors у группы и удаляет ошибку при изменении выбора', async () => {
    createActivitiesScenario(authUsers.teacher);
    server.use(http.post('*/api/v1/activities', () => problem('VALIDATION_FAILED', 400, {
      fieldErrors: [{ field: 'groupIds', code: 'INVALID', message: 'Проверьте выбранные группы.' }],
    })));
    const { user } = mountActivities('/activities/new');
    await fillActivity(user);
    await user.click(screen.getByRole('button', { name: 'Создать активность' }));
    expect(await screen.findByText('Проверьте выбранные группы.')).toBeInTheDocument();
    await user.click(screen.getByRole('checkbox', { name: /ИВТ-22/ }));
    expect(screen.queryByText('Проверьте выбранные группы.')).not.toBeInTheDocument();
  });
});

describe('U9 подтверждение и предпросмотр — R19/R20, AE8/AE9/AE10', () => {
  it('SOCIAL использует возвращённый состав записавшихся, не добавляя участников из других источников', async () => {
    const state = createActivitiesScenario(authUsers.teacher);
    state.activity.type = 'SOCIAL';
    state.activity.currency = 'BOA';
    state.activity.maxPoints = 100;
    state.participants.type = 'SOCIAL';
    state.participants.maxPoints = 100;
    state.participants.items = state.participants.items.map((item) => ({ ...item, enrolled: true }));
    mountActivities('/activities/603/confirm');
    expect(await screen.findByRole('link', { name: participantNames.open })).toHaveAttribute('href', '/users/1002');
    expect(screen.getByRole('link', { name: participantNames.confirmed })).toHaveAttribute('href', '/users/1001');
    expect(screen.queryByRole('link', { name: participantNames.second })).not.toBeInTheDocument();
    expect(screen.getAllByRole('row').filter((row) => /Кузнецов|Смирнова/.test(row.textContent ?? ''))).toHaveLength(2);
  });

  it('показывает только серверных участников; подтверждённая строка остаётся только для чтения', async () => {
    createActivitiesScenario(authUsers.teacher);
    mountActivities('/activities/603/confirm');
    const confirmed = await screen.findByRole('row', { name: new RegExp(participantNames.confirmed) });
    expect(within(confirmed).getByRole('link', { name: participantNames.confirmed })).toHaveAttribute('href', '/users/1001');
    for (const control of within(confirmed).queryAllByRole('checkbox')) expect(control).toBeDisabled();
    for (const control of within(confirmed).queryAllByRole('radio')) expect(control).toBeDisabled();
    expect(within(confirmed).getByText(/75\s*%/)).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: new RegExp(participantNames.open) })).not.toBeChecked();
  });

  it('confirmationOpen=false не отправляет ни preview, ни confirm', async () => {
    const state = createActivitiesScenario(authUsers.teacher);
    state.activity.confirmationOpen = false;
    mountActivities('/activities/603/confirm');
    expect(await screen.findByText(/подтвержден.*после|подтвержден.*недоступ|активност.*начн/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Начислить/ })).not.toBeInTheDocument();
    expect(state.requests.filter((request) => request.method === 'POST')).toHaveLength(0);
  });

  it('уровень50 при max75 показывает серверные38 и отправляет то же тело confirm один раз', async () => {
    const state = createActivitiesScenario(authUsers.teacher);
    const { user } = mountActivities('/activities/603/confirm');
    await selectParticipant(user);
    await setLevel(user, 50);
    const confirm = await screen.findByRole('button', { name: /Начислить\s+38\s+БУА/ });
    await waitFor(() => expect(confirm).toBeEnabled());
    await user.click(confirm);
    expect(await screen.findByText(/Начислено\s+38\s+БУА/)).toBeInTheDocument();
    const preview = state.requests.filter((request) => request.path.endsWith('/preview')).at(-1);
    const posted = state.requests.filter((request) => request.path === '/api/v1/activities/603/participation');
    expect(posted).toHaveLength(1);
    expect(posted[0]).toEqual(expect.objectContaining({ csrf: 'acceptance-csrf-1', body: { items: [{ studentId: 1002, level: 50 }] } }));
    expect(preview?.body).toEqual(posted[0]!.body);
    const row = await screen.findByRole('row', { name: new RegExp(participantNames.open) });
    await waitFor(() => {
      const checkbox = within(row).queryByRole('checkbox');
      if (checkbox) expect(checkbox).toBeDisabled();
      expect(within(row).getByText(/50\s*%/)).toBeInTheDocument();
    });
  });

  it('поздний ответ preview старого уровня не заменяет новый и запрос отменяется', async () => {
    createActivitiesScenario(authUsers.teacher);
    const old = deferred();
    let oldSignal: AbortSignal | undefined;
    let firstReceived = false;
    server.use(http.post('*/api/v1/activities/603/participation/preview', async ({ request }) => {
      const body = await request.json() as components['schemas']['ParticipationRequest'];
      const level = body.items[0]!.level;
      if (level === 100) { oldSignal = request.signal; firstReceived = true; await old.promise; }
      return json({ currency: 'BUA', awards: [{ studentId: 1002, groupId: 11, level, amount: level === 100 ? 75 : 38 }],
        alreadyConfirmed: [], total: level === 100 ? 75 : 38,
        budgets: [{ groupId: 11, groupName: 'ИВТ-21', needed: level === 100 ? 75 : 38, remaining: 1000, shortfall: 0 }], withinBudget: true });
    }));
    const { user } = mountActivities('/activities/603/confirm');
    try {
      await selectParticipant(user);
      await waitFor(() => expect(firstReceived).toBe(true));
      const pending = screen.queryByRole('button', { name: /^Начислить/ });
      if (pending) expect(pending).toBeDisabled();
      await setLevel(user, 50);
      const current = await screen.findByRole('button', { name: /Начислить\s+38\s+БУА/ });
      await waitFor(() => expect(current).toBeEnabled());
      expect(oldSignal?.aborted).toBe(true);
      old.resolve();
      await waitFor(() => expect(screen.queryByRole('button', { name: /Начислить\s+75\s+БУА/ })).not.toBeInTheDocument());
      expect(current).toBeEnabled();
    } finally { old.resolve(); }
  });

  it('недостаток бюджета в preview блокирует confirm целиком', async () => {
    const state = createActivitiesScenario(authUsers.teacher);
    state.withinBudget = false;
    const { user } = mountActivities('/activities/603/confirm');
    await selectParticipant(user);
    const confirm = await screen.findByRole('button', { name: /Начислить\s+75\s+БУА/ });
    expect(confirm).toBeDisabled();
    expect(screen.getByText(/не хватает|недостаточно/i)).toBeInTheDocument();
    expect(screen.getAllByText(/25/).length).toBeGreaterThan(0);
    expect(state.requests.filter((request) => request.path === '/api/v1/activities/603/participation')).toHaveLength(0);
  });

  it('AE8: два участника по50 при остатке50 дают серверный дефицит50 и не отправляются', async () => {
    const state = createActivitiesScenario(authUsers.teacher);
    state.activity.type = 'SOCIAL';
    state.activity.currency = 'BOA';
    state.activity.maxPoints = 100;
    state.participants.type = 'SOCIAL';
    state.participants.maxPoints = 100;
    state.participants.items = state.participants.items.map((item) => ({ ...item, confirmed: null, enrolled: true }));
    server.use(http.post('*/api/v1/activities/603/participation/preview', async ({ request }) => {
      const body = await request.json() as components['schemas']['ParticipationRequest'];
      const awards = body.items.map((item) => ({ studentId: item.studentId, groupId: 11, level: item.level, amount: item.level }));
      const total = awards.reduce((sum, item) => sum + item.amount, 0);
      return json({ currency: 'BOA', awards, alreadyConfirmed: [], total,
        budgets: [{ groupId: 11, groupName: 'ИВТ-21', needed: total, remaining: 50, shortfall: Math.max(total - 50, 0) }], withinBudget: total <= 50 });
    }));
    const { user } = mountActivities('/activities/603/confirm');
    await selectParticipant(user);
    await setLevel(user, 50);
    await selectParticipant(user, participantNames.confirmed);
    await setLevel(user, 50, participantNames.confirmed);
    const confirm = await screen.findByRole('button', { name: /Начислить\s+100\s+БОА/ });
    expect(confirm).toBeDisabled();
    expect(screen.getByText(/не хватает|недостаточно/i)).toBeInTheDocument();
    expect(screen.getAllByText(/50/).length).toBeGreaterThan(0);
    expect(state.requests.filter((request) => request.path === '/api/v1/activities/603/participation')).toHaveLength(0);
  });

  it('гонка BUDGET_EXCEEDED после положительного preview показывает группу/дефицит, а не успех', async () => {
    const state = createActivitiesScenario(authUsers.teacher);
    let posts = 0;
    server.use(http.post('*/api/v1/activities/603/participation', () => {
      posts++;
      return problem('BUDGET_EXCEEDED', 422, { budgets: [{ groupId: 11, groupName: 'ИВТ-21', needed: 75, remaining: 50, shortfall: 25 }] });
    }));
    const { user } = mountActivities('/activities/603/confirm');
    await selectParticipant(user);
    const confirm = await screen.findByRole('button', { name: /Начислить\s+75\s+БУА/ });
    await waitFor(() => expect(confirm).toBeEnabled());
    await user.click(confirm);
    expect(await screen.findByText(/не хватает|недостаточно/i)).toBeInTheDocument();
    expect(screen.getAllByText(/ИВТ-21/).length).toBeGreaterThan(0);
    expect(screen.queryByText(/Начислено\s+/)).not.toBeInTheDocument();
    expect(posts).toBe(1);
    await waitFor(() => expect(state.requests.filter((request) => request.path.endsWith('/preview')).length).toBeGreaterThanOrEqual(2));
  });

  it.each(['NOT_ENROLLED', 'STUDENT_NOT_ELIGIBLE', 'ALREADY_CONFIRMED'])('%s показывает имя из studentIds и перечитывает участников', async (code) => {
    const state = createActivitiesScenario(authUsers.teacher);
    server.use(http.post('*/api/v1/activities/603/participation', () => problem(code, code === 'ALREADY_CONFIRMED' ? 409 : 422, { studentIds: [1002] })));
    const { user } = mountActivities('/activities/603/confirm');
    await selectParticipant(user);
    const confirm = await screen.findByRole('button', { name: /Начислить\s+75\s+БУА/ });
    await waitFor(() => expect(confirm).toBeEnabled());
    await user.click(confirm);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(participantNames.open);
    expect(alert).toHaveTextContent(/не записан|не подход|недоступ|уже подтверж|не может/i);
    expect(screen.queryByText(/Начислено\s+/)).not.toBeInTheDocument();
    await waitFor(() => expect(state.requests.filter((request) => request.path.endsWith('/participants')).length).toBeGreaterThanOrEqual(2));
  });

  it('повтор total0 с alreadyConfirmed не выдаётся за новое начисление', async () => {
    const state = createActivitiesScenario(authUsers.teacher);
    state.confirmResult = { currency: 'BUA', awards: [], alreadyConfirmed: [{ studentId: 1002, groupId: 11, level: 100, amount: 75 }], total: 0 };
    const { user } = mountActivities('/activities/603/confirm');
    await selectParticipant(user);
    const confirm = await screen.findByRole('button', { name: /Начислить\s+75\s+БУА/ });
    await waitFor(() => expect(confirm).toBeEnabled());
    await user.click(confirm);
    expect(await screen.findByText(/уже.*подтвержден|новых начислений нет/i)).toBeInTheDocument();
    expect(screen.queryByText(/Начислено\s+0/)).not.toBeInTheDocument();
  });
});

describe('U9 бюджет и история — R20/R21', () => {
  it('берёт месяц с сервера, показывает прежние группы и server summary вместо суммы страницы', async () => {
    const state = createActivitiesScenario(authUsers.teacher);
    state.groups.items = state.groups.items.filter((group) => group.id === 11);
    mountActivities('/groups');
    await waitFor(() => expect(screen.getByLabelText('Месяц бюджета')).toHaveValue('2042-07'));
    expect(await screen.findByRole('row', { name: /ИВТ-22.*1000/ })).toBeInTheDocument();
    expect(screen.getAllByText(/600/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/350/).length).toBeGreaterThan(0);
    expect(await screen.findByText('Помощь в организации конференции')).toBeInTheDocument();
    await waitFor(() => expect(state.requests.some((request) => request.path === '/api/v1/me/awards' && request.query.month === '2042-07')).toBe(true));
  });

  it('подгружает cursor без дубликатов; смена группы/месяца начинает историю заново', async () => {
    const state = createActivitiesScenario(authUsers.teacher);
    const { user } = mountActivities('/groups');
    await user.click(await screen.findByRole('button', { name: 'Показать ещё' }));
    expect(await screen.findByText('Вторая награда')).toBeInTheDocument();
    expect(screen.getAllByText('Помощь в организации конференции')).toHaveLength(1);
    expect(state.requests.some((request) => request.path === '/api/v1/me/awards' && request.query.cursor === 'activities-page-2')).toBe(true);
    await chooseOption(user, 'Группа', 'ИВТ-22');
    await waitFor(() => expect(state.requests.some((request) => request.path === '/api/v1/me/awards' && request.query.groupId === '12' && !request.query.cursor)).toBe(true));
    fireEvent.change(screen.getByLabelText('Месяц бюджета'), { target: { value: '2042-08' } });
    await waitFor(() => expect(state.requests.some((request) => request.path === '/api/v1/me/awards' && request.query.month === '2042-08' && !request.query.cursor && !request.query.groupId)).toBe(true));
    expect(screen.queryByText('Вторая награда')).not.toBeInTheDocument();
  });

  it('ошибка истории даёт повтор, не скрывая успешно загруженный бюджет', async () => {
    createActivitiesScenario(authUsers.teacher);
    let fail = true;
    server.use(http.get('*/api/v1/me/awards', () => fail ? problem('INTERNAL_ERROR', 500) : json({ items: [], nextCursor: null })));
    const { user } = mountActivities('/groups');
    const retry = await screen.findByRole('button', { name: /Повторить/i });
    expect(screen.getByRole('heading', { name: 'Группы и бюджет' })).toBeInTheDocument();
    fail = false;
    await user.click(retry);
    expect(await screen.findByText(/начислений.*нет|нет.*начислений|история.*пуста/i)).toBeInTheDocument();
  });

  it('поздняя cursor-страница прежнего месяца не смешивается с новой историей', async () => {
    const state = createActivitiesScenario(authUsers.teacher);
    const old = deferred();
    let oldStarted = false;
    let oldSignal: AbortSignal | undefined;
    server.use(http.get('*/api/v1/me/awards', async ({ request }) => {
      const params = new URL(request.url).searchParams;
      if (params.has('cursor')) {
        oldStarted = true;
        oldSignal = request.signal;
        await old.promise;
        return json(state.secondPage);
      }
      if (params.get('month') === '2042-08') {
        return json({ items: state.firstPage.items.map((item) => ({ ...item, id: 9200,
          activity: { ...item.activity, title: 'Августовская награда' } })), nextCursor: null });
      }
      return json(state.firstPage);
    }));
    const { user } = mountActivities('/groups');
    try {
      await user.click(await screen.findByRole('button', { name: 'Показать ещё' }));
      await waitFor(() => expect(oldStarted).toBe(true));
      fireEvent.change(screen.getByLabelText('Месяц бюджета'), { target: { value: '2042-08' } });
      expect(await screen.findByText('Августовская награда')).toBeInTheDocument();
      expect(oldSignal?.aborted).toBe(true);
      old.resolve();
      await waitFor(() => expect(screen.queryByText('Вторая награда')).not.toBeInTheDocument());
    } finally { old.resolve(); }
  });
});

describe('U9 границы доступа и ответов — R3', () => {
  it.each(['/activities/new', '/activities/603/confirm', '/groups'])('студент на %s видит отказ без teacher API', async (path) => {
    const state = createActivitiesScenario();
    mountActivities(path);
    expect(await screen.findByText('Нет доступа')).toBeInTheDocument();
    expect(state.requests).toHaveLength(0);
  });

  it('ADMIN не получает список активностей', async () => {
    const state = createActivitiesScenario({ ...authUsers.admin, mustChangePassword: false });
    mountActivities('/activities');
    expect(await screen.findByText('Нет доступа')).toBeInTheDocument();
    expect(state.requests).toHaveLength(0);
  });

  it('невалидный id не превращается в запрос с NaN', async () => {
    const state = createActivitiesScenario(authUsers.teacher);
    mountActivities('/activities/abc/confirm');
    expect(await screen.findByText('Активность не найдена')).toBeInTheDocument();
    expect(state.requests).toHaveLength(0);
  });

  it('чужой автор не получает управление подтверждением', async () => {
    const state = createActivitiesScenario(authUsers.teacher);
    state.activity.author = { id: 2002, fullName: 'Другой преподаватель' };
    mountActivities('/activities/603/confirm');
    expect(await screen.findByText('Нет доступа')).toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
    expect(state.requests.filter((request) => request.method === 'POST')).toHaveLength(0);
  });

  it('несогласованная валюта Activity превращается в ошибку с повтором', async () => {
    const state = createActivitiesScenario();
    state.list.items[0]!.currency = 'AR';
    mountActivities('/activities');
    expect(await screen.findByRole('button', { name: /Повторить/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Записаться/ })).not.toBeInTheDocument();
  });
});

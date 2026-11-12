import { test, expect, login, logout, evidence, choose, moscowInput, type Schemas } from './support.js';

test('ACT-01 создание активности через UI, студент видит тип и максимум', async ({ page, scenario }) => {
  const teacher = await scenario.registerTeacher(), student = await scenario.registerStudent();
  await login(page, teacher);
  await page.goto('/activities/new');
  const title = `Событие ${scenario.prefix}`;
  await page.getByLabel('Название', { exact: true }).fill(title);
  await page.getByLabel('Описание', { exact: true }).fill('Общественная активность группы');
  await page.getByLabel('Тип активности', { exact: true }).selectOption('SOCIAL');
  await choose(page.getByText(`${scenario.group.name} · ${scenario.faculty.code}`, { exact: true }));
  await page.getByLabel('Дата и время начала (Москва)', { exact: true }).fill(moscowInput(await teacher.api.future(120)));
  await page.getByLabel('Максимум баллов', { exact: true }).fill('75');
  await page.getByRole('button', { name: 'Создать активность', exact: true }).click();
  await expect(page).toHaveURL(/\/activities$/);
  await logout(page);
  await login(page, student);
  await page.goto('/activities');
  const item = page.getByRole('article', { name: title, exact: true });
  await expect(item.getByRole('heading', { name: title, exact: true })).toBeVisible();
  await expect(item).toContainText('Общественная · БОА');
  await expect(item).toContainText('75 БОА');
  await evidence(page, 'ACT-01-created');
});

test('ACT-02 запись и отмена SOCIAL до начала, после начала запись закрыта', async ({ page, scenario }) => {
  const teacher = await scenario.registerTeacher(), student = await scenario.registerStudent();
  await login(page, student);
  const activity = await scenario.activity(teacher, 'SOCIAL', 12);
  await page.goto('/activities');
  const item = page.getByRole('article', { name: activity.title, exact: true });
  await item.getByRole('button', { name: `Записаться: ${activity.title}`, exact: true }).click();
  await expect(item.getByRole('status')).toHaveText('Вы записались на активность');
  await item.getByRole('button', { name: `Отменить запись: ${activity.title}`, exact: true }).click();
  await expect(item.getByRole('status')).toHaveText('Запись отменена');
  await student.api.until(activity.startsAt);
  await page.reload();
  await expect(item).toContainText('Запись закрыта.');
  await expect(item.getByRole('button', { name: /Записаться|Отменить запись/ })).toHaveCount(0);
  await student.api.send(`/api/v1/activities/${activity.id}/enrollment`, undefined, 'PUT', 422);
  const academic = await scenario.activity(teacher);
  await student.api.send(`/api/v1/activities/${academic.id}/enrollment`, undefined, 'PUT', 409);
});

test('ACT-03 предпросмотр 50 % от 75 = 38 БОА, история и безопасный повтор', async ({ page, scenario }) => {
  const teacher = await scenario.registerTeacher(), student = await scenario.registerStudent();
  const notEnrolled = await scenario.registerStudent();
  await login(page, teacher);
  const activity = await scenario.activity(teacher, 'SOCIAL', 5);
  await student.api.send(`/api/v1/activities/${activity.id}/enrollment`, undefined, 'PUT');
  const items = [{ studentId: student.id, level: 50 as const }];
  await teacher.api.send(`/api/v1/activities/${activity.id}/participation`, { items }, 'POST', 409);
  await teacher.api.until(activity.startsAt);
  const rejected = await teacher.api.send(`/api/v1/activities/${activity.id}/participation`, {
    items: [{ studentId: notEnrolled.id, level: 50 }],
  }, 'POST', 422);
  expect(await rejected.json()).toMatchObject({ code: 'NOT_ENROLLED' });
  await page.goto(`/activities/${activity.id}/confirm`);
  await choose(page.getByRole('row').filter({ hasText: student.fullName }).getByText('Выбрать', { exact: true }));
  const row = page.getByRole('row').filter({ hasText: student.fullName });
  await choose(row.getByText('50 %', { exact: true }));
  await expect(page.getByText(/К начислению/)).toContainText('38 БОА');
  await page.getByRole('button', { name: 'Начислить 38 БОА', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('Начислено 38 БОА');
  expect(await teacher.api.confirm(activity.id, items)).toMatchObject({ total: 0, awards: [], alreadyConfirmed: [{ amount: 38 }] });
  await teacher.api.send(`/api/v1/activities/${activity.id}/participation`, {
    items: [{ studentId: student.id, level: 100 }],
  }, 'POST', 409);
  await logout(page);
  await login(page, student);
  await page.goto(`/users/${student.id}`);
  const entry = page.getByRole('article').filter({ hasText: activity.title });
  await expect(entry).toHaveCount(1);
  await expect(entry).toContainText('+38 БОА');
  await expect(entry.getByRole('link', { name: teacher.fullName, exact: true })).toBeVisible();
  await expect(entry.getByRole('time')).toHaveAttribute('datetime', /.+/);
  await expect(page.getByLabel('Балансы').getByText('38', { exact: true })).toBeVisible();
  await evidence(page, 'ACT-03-history');
});

test('BUD-01 общий бюджет: расход, атомарный отказ сверх остатка и новый месяц', async ({ page, scenario }) => {
  const teacher = await scenario.registerTeacher();
  const students = [await scenario.registerStudent(), await scenario.registerStudent()];
  const spent = await scenario.activity(teacher, 'ACADEMIC', 3, 962);
  await teacher.api.until(spent.startsAt);
  await teacher.api.confirm(spent.id, [{ studentId: students[0].id, level: 100 }]);
  const activity = await scenario.activity(teacher, 'ACADEMIC', 3);
  await teacher.api.until(activity.startsAt);
  await login(page, teacher);
  await page.goto('/groups');
  const budget = page.getByRole('table', { name: 'Бюджет групп', exact: true }).getByRole('row').filter({ hasText: scenario.group.name });
  await expect(budget.getByRole('cell').filter({ hasText: /^(Расход )?962$/ })).toBeVisible();
  await expect(budget.getByRole('cell').filter({ hasText: /^(Остаток )?38$/ })).toBeVisible();
  await page.goto(`/activities/${activity.id}/confirm`);
  for (const student of students) {
    await choose(page.getByRole('row').filter({ hasText: student.fullName }).getByText('Выбрать', { exact: true }));
    await choose(page.getByRole('row').filter({ hasText: student.fullName }).getByText('50 %', { exact: true }));
  }
  await expect(page.getByText('Бюджет не позволяет подтвердить участие целиком. Измените уровень или состав участников.', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Начислить 76 БУА', exact: true })).toBeDisabled();
  const response = await teacher.api.send(`/api/v1/activities/${activity.id}/participation`, {
    items: students.map((student) => ({ studentId: student.id, level: 50 })),
  }, 'POST', 422);
  expect(await response.json()).toMatchObject({ code: 'BUDGET_EXCEEDED', budgets: [{ remaining: 38, needed: 76, shortfall: 38 }] });
  const participants = await teacher.api.get<Schemas['ParticipantList']>(`/api/v1/activities/${activity.id}/participants`);
  expect(participants.items.every((item) => item.confirmed === null)).toBe(true);
  await evidence(page, 'BUD-01-insufficient');
  await page.goto('/groups');
  await expect(budget.getByRole('cell').filter({ hasText: /^(Остаток )?38$/ })).toBeVisible();
  const now = new Date(await teacher.api.now() + 3 * 3600_000);
  const nextMonth = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 1)).toISOString().slice(0, 7);
  await page.getByLabel('Месяц бюджета', { exact: true }).fill(nextMonth);
  await expect(budget.getByRole('cell').filter({ hasText: /^(Расход )?0$/ })).toBeVisible();
  await expect(budget.getByRole('cell').filter({ hasText: /^(Лимит |Остаток )?1000$/ })).toHaveCount(2);
});

test('ACT-01 ACT-03 отказы: максимум, группа, роль, уровень участия; начислений нет', async ({ page, scenario }) => {
  const teacher = await scenario.registerTeacher(), student = await scenario.registerStudent();
  const body: Schemas['ActivityCreate'] = {
    title: 'Проверка отказов', description: '', type: 'ACADEMIC', groupIds: [scenario.group.id],
    startsAt: await teacher.api.future(120), maxPoints: 75,
  };
  await teacher.api.send('/api/v1/activities', { ...body, maxPoints: 1 }, 'POST', 400);
  await teacher.api.send('/api/v1/activities', { ...body, groupIds: [11] }, 'POST', 403);
  await student.api.send('/api/v1/activities', body, 'POST', 403);
  const activity = await scenario.activity(teacher, 'ACADEMIC', 3);
  await teacher.api.until(activity.startsAt);
  await teacher.api.send(`/api/v1/activities/${activity.id}/participation`, {
    items: [{ studentId: student.id, level: 30 }],
  }, 'POST', 400);
  await student.api.send(`/api/v1/activities/${activity.id}/participation`, {
    items: [{ studentId: student.id, level: 50 }],
  }, 'POST', 403);
  await login(page, student);
  await page.goto(`/users/${student.id}`);
  await expect(page.getByLabel('Балансы').getByText('0', { exact: true })).toHaveCount(2);
  await expect(page.getByRole('article').filter({ hasText: activity.title })).toHaveCount(0);
});

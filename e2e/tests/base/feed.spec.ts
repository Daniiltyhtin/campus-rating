import { test, expect, login, logout, evidence, choose, type Schemas } from './support.js';
import type { Locator, Page } from '@playwright/test';

test('PRF-01 Covers AE40 свой профиль: балансы, история R6, активные и завершённые задачи; чужой без сдачи', async ({ page, scenario }) => {
  const teacher = await scenario.registerTeacher(), student = await scenario.registerStudent();
  const viewer = await scenario.registerStudent();
  const active = await scenario.checkpoint(teacher), completed = await scenario.checkpoint(teacher);
  const assignment = await scenario.assignment(student, completed);
  await student.api.submit(assignment.id);
  await teacher.api.send(`/api/v1/checkpoints/${completed.id}/assignments/accept`, { assignmentIds: [assignment.id] });
  const activity = await scenario.activity(teacher, 'ACADEMIC', 3);
  await teacher.api.until(activity.startsAt);
  await teacher.api.confirm(activity.id, [{ studentId: student.id, level: 50 }]);
  await login(page, student);
  await page.goto(`/users/${student.id}`);
  await expect(page.getByRole('heading', { name: student.fullName, exact: true })).toBeVisible();
  await expect(page.getByText(scenario.faculty.name, { exact: true })).toBeVisible();
  const balances = page.getByLabel('Балансы');
  await expect(balances.getByText('1000', { exact: true })).toBeVisible();
  await expect(balances.getByText('38', { exact: true })).toBeVisible();
  await expect(balances.getByText('0', { exact: true })).toBeVisible();
  const hitArea = await balances.locator('.cr-currency-mark').first().evaluate((mark) => {
    const area = getComputedStyle(mark, '::before');
    return [parseFloat(area.width), parseFloat(area.height)];
  });
  for (const size of hitArea) expect(size).toBeGreaterThanOrEqual(44);
  const entry = page.getByRole('article').filter({ hasText: activity.title });
  await expect(entry).toContainText('+38 БУА');
  await expect(entry.getByRole('link', { name: teacher.fullName, exact: true })).toBeVisible();
  await expect(entry.getByRole('time')).toHaveAttribute('datetime', /.+/);
  await page.getByRole('tab', { name: 'Задачи', exact: true }).click();
  await expect(page.getByRole('link', { name: active.title, exact: true })).toHaveAttribute('href', /\/tasks\/\d+/);
  await expect(page.getByRole('link', { name: completed.title, exact: true })).toBeVisible();
  await expect(page.getByText('Принято', { exact: true })).toBeVisible();
  await evidence(page, 'PRF-01-own-tasks');
  await logout(page);
  await login(page, viewer);
  await page.goto(`/users/${student.id}`);
  await page.getByRole('tab', { name: 'Задачи', exact: true }).click();
  await expect(page.getByRole('heading', { name: active.title, exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: active.title, exact: true })).toHaveCount(0);
  await expect(page.getByRole('link', { name: completed.title, exact: true })).toHaveCount(0);
  expect((await viewer.api.context.get(`/api/v1/assignments/${assignment.id}`)).status()).toBe(403);
});

test('FEED-01 Covers AE40 новое начисление первым в ленте другого пользователя', async ({ page, scenario }) => {
  const teacher = await scenario.registerTeacher(), student = await scenario.registerStudent();
  const viewer = await scenario.registerStudent();
  const older = await scenario.activity(teacher, 'ACADEMIC', 3);
  const activity = await scenario.activity(teacher, 'ACADEMIC', 3);
  await teacher.api.until(older.startsAt);
  await teacher.api.confirm(older.id, [{ studentId: student.id, level: 25 }]);
  await login(page, viewer);
  await page.goto('/awards');
  await teacher.api.until(activity.startsAt);
  await teacher.api.confirm(activity.id, [{ studentId: student.id, level: 50 }]);
  await page.reload();
  const own = page.getByRole('article').filter({ has: page.getByRole('link', { name: student.fullName, exact: true }) });
  await expect(own).toHaveCount(2);
  const first = own.first();
  await expect(first).toContainText(activity.title);
  await expect(first).toContainText('+38 БУА');
  await expect(first.getByRole('link', { name: student.fullName, exact: true })).toBeVisible();
  await expect(first.getByRole('link', { name: teacher.fullName, exact: true })).toBeVisible();
  await expect(first.getByRole('time')).toHaveAttribute('datetime', /.+/);
  const times = await page.getByRole('article').getByRole('time').evaluateAll((elements) => elements.map((element) => Date.parse(element.getAttribute('datetime')!)));
  expect(times.length).toBeGreaterThan(1);
  expect(times).toEqual([...times].sort((a, b) => b - a));
  await evidence(page, 'FEED-01-newest');
});

test('RAT-01 Covers AE40 преподаватель сортирует рейтинг по АР, БУА и БОА; студенту нет доступа', async ({ page, scenario }) => {
  const teacher = await scenario.registerTeacher();
  const first = await scenario.registerStudent(), second = await scenario.registerStudent();
  const academic = await scenario.activity(teacher, 'ACADEMIC', 3, 100);
  const social = await scenario.activity(teacher, 'SOCIAL', 3, 100);
  await second.api.send(`/api/v1/activities/${social.id}/enrollment`, undefined, 'PUT');
  await teacher.api.until(social.startsAt);
  await teacher.api.confirm(academic.id, [{ studentId: first.id, level: 100 }]);
  await teacher.api.confirm(social.id, [{ studentId: second.id, level: 100 }]);
  const checkpoint = await scenario.checkpoint(teacher, 3, 4);
  const firstWork = await scenario.assignment(first, checkpoint);
  await first.api.submit(firstWork.id);
  const secondWork = await scenario.assignment(second, checkpoint);
  await expect.poll(async () => (await second.api.get<Schemas['Assignment']>(`/api/v1/assignments/${secondWork.id}`)).penalty.charged,
    { timeout: 15_000, intervals: [200, 500] }).toBe(4);
  await login(page, teacher);
  await page.goto(`/groups/${scenario.group.id}`);
  const rows = page.getByRole('table', { name: 'Рейтинг студентов', exact: true }).getByRole('row');
  for (const [currency, leader] of [['АР', first], ['БУА', first], ['БОА', second]] as const) {
    await choose(page.getByRole('radiogroup', { name: 'Сортировка рейтинга', exact: true }).getByText(currency, { exact: true }));
    await expect(rows.nth(1).getByRole('link', { name: leader.fullName, exact: true })).toBeVisible();
  }
  await evidence(page, 'RAT-01-rating');
  await logout(page);
  await login(page, first);
  await page.goto(`/groups/${scenario.group.id}`);
  await expect(page.getByRole('heading', { name: 'Нет доступа', exact: true })).toBeVisible();
  expect((await first.api.context.get(`/api/v1/groups/${scenario.group.id}/ratings`)).status()).toBe(403);
});

async function homeCount(page: Page, link: Locator) {
  await page.goto('/');
  await page.getByRole('article').or(page.getByText('Событий пока нет', { exact: true })).first().waitFor();
  return link.count();
}

test('FEED-02 сдача работы появляется на главной у автора точки, новая задача — у студента', async ({ page, scenario }) => {
  const teacher = await scenario.registerTeacher(), student = await scenario.registerStudent();
  const checkpoint = await scenario.checkpoint(teacher, 600);
  const assignment = await scenario.assignment(student, checkpoint);
  await login(page, student);
  const task = page.getByRole('link', { name: new RegExp(`^Новая задача «${checkpoint.title}» · до \\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}$`) });
  await expect.poll(() => homeCount(page, task), { timeout: 15_000, intervals: [500, 1000] }).toBe(1);
  await expect(task).toHaveAttribute('href', `/tasks/${assignment.id}`);
  await student.api.submit(assignment.id);
  await logout(page);
  await login(page, teacher);
  const submitted = page.getByRole('link', { name: `Сдана работа «${checkpoint.title}» · ${student.fullName}`, exact: true });
  await expect.poll(() => homeCount(page, submitted), { timeout: 15_000, intervals: [500, 1000] }).toBe(1);
  await expect(submitted).toHaveAttribute('href', `/checkpoints/${checkpoint.id}`);
  await expect(page.getByRole('heading', { name: 'Главная', exact: true })).toBeVisible();
  await evidence(page, 'FEED-02-teacher-home');
});

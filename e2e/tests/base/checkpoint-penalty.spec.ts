import { test, expect, login, logout, evidence, choose, moscowInput, type Schemas, type User } from './support.js';
import type { Page } from '@playwright/test';

async function submit(page: Page, link = 'https://example.org/report') {
  await page.getByLabel('Ссылка на работу').fill(link);
  await page.getByRole('button', { name: 'Сдать работу', exact: true }).click();
  await expect(page.getByText('Сдано на проверку', { exact: true })).toBeVisible();
}
async function charged(student: User, id: number, amount: number) {
  await expect.poll(async () => (await student.api.get<Schemas['Assignment']>(`/api/v1/assignments/${id}`)).penalty.charged,
    { timeout: 22_000, intervals: [200, 500] }).toBe(amount);
}

test('CP-01 создание точки через UI назначает её студентам группы и фиксирует шаг 4', async ({ page, scenario }) => {
  const teacher = await scenario.registerTeacher();
  const students = [await scenario.registerStudent(), await scenario.registerStudent()];
  await login(page, teacher);
  await page.goto('/checkpoints/new');
  const title = `Новая точка ${scenario.prefix}`;
  await page.getByLabel('Название').fill(title);
  await page.getByLabel('Описание', { exact: true }).fill('Описание новой точки');
  await choose(page.getByText(scenario.group.name, { exact: true }));
  await page.getByLabel('Дедлайн (МСК)').fill(moscowInput(await teacher.api.future(180)));
  await page.getByLabel('Стоимость, АР').fill('12');
  await expect(page.getByText(/Шаг штрафа: 4 АР; интервал: 4 с/)).toBeVisible();
  await page.getByRole('button', { name: 'Создать точку', exact: true }).click();
  await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible();
  await expect(page.getByText('Штраф: 4 АР каждые 4 с', { exact: true })).toBeVisible();
  await page.goto('/checkpoints');
  await expect(page.getByRole('link', { name: title, exact: true })).toBeVisible();
  await logout(page);
  for (const student of students) {
    await login(page, student);
    await page.goto('/tasks');
    await page.getByRole('link', { name: title, exact: true }).click();
    await expect(page.getByText('В процессе', { exact: true })).toBeVisible();
    await expect(page.getByText('Списано 0 из 12 АР', { exact: true })).toBeVisible();
    await logout(page);
  }
});

test('CP-02 сдача до дедлайна, отзыв и новая версия в истории', async ({ page, scenario }) => {
  const teacher = await scenario.registerTeacher(), student = await scenario.registerStudent();
  const checkpoint = await scenario.checkpoint(teacher);
  const assignment = await scenario.assignment(student, checkpoint);
  await login(page, student);
  await page.goto(`/tasks/${assignment.id}`);
  await submit(page, 'https://example.org/version-1');
  await expect(page.getByText('Списано 0 из 12 АР', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Отозвать сдачу', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Отозвать', exact: true }).click();
  await expect(page.getByText('В процессе', { exact: true })).toBeVisible();
  await expect(page.getByText(/Отозвана студентом/)).toBeVisible();
  await submit(page, 'https://example.org/version-2');
  const history = page.getByRole('region', { name: 'История сдач' });
  await expect(history.getByRole('link', { name: 'https://example.org/version-1', exact: true })).toBeVisible();
  await expect(history.getByRole('link', { name: 'https://example.org/version-2', exact: true })).toBeVisible();
  await evidence(page, 'CP-02-versions');
});

test('PEN-01 три слота по 4 АР, общий потолок 12 и баланс 988', async ({ page, scenario }) => {
  const teacher = await scenario.registerTeacher(), student = await scenario.registerStudent();
  await login(page, student);
  const checkpoint = await scenario.checkpoint(teacher, 5);
  const assignment = await scenario.assignment(student, checkpoint);
  await page.goto(`/tasks/${assignment.id}`);
  await expect(page.getByText('Списано 0 из 12 АР', { exact: true })).toBeVisible();
  for (const amount of [4, 8, 12]) await charged(student, assignment.id, amount);
  await student.api.until(new Date(Date.parse(checkpoint.deadline) + 16_000).toISOString());
  await charged(student, assignment.id, 12);
  await page.reload();
  await expect(page.getByText('Просрочено', { exact: true })).toBeVisible();
  await expect(page.getByText('Списано 12 из 12 АР', { exact: true })).toBeVisible();
  await evidence(page, 'PEN-01-cap');
  await page.goto(`/users/${student.id}`);
  await expect(page.getByLabel('Балансы').getByText('988', { exact: true })).toBeVisible();
});

test('PEN-02 сдача после слота останавливает дальнейшие списания', async ({ page, scenario }) => {
  const teacher = await scenario.registerTeacher(), student = await scenario.registerStudent();
  await login(page, student);
  const checkpoint = await scenario.checkpoint(teacher, 4);
  const assignment = await scenario.assignment(student, checkpoint);
  await page.goto(`/tasks/${assignment.id}`);
  await charged(student, assignment.id, 4);
  await submit(page);
  await expect(page.getByText('Штраф остановлен, списано 4', { exact: true })).toBeVisible();
  await student.api.until(new Date(Date.parse(checkpoint.deadline) + 13_000).toISOString());
  await charged(student, assignment.id, 4);
  await page.reload();
  await expect(page.getByText('Списано 4 из 12 АР', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Отозвать сдачу', exact: true })).toHaveCount(0);
});

test('PEN-03 возврат с комментарием возобновляет штраф до общего потолка', async ({ page, scenario }) => {
  const teacher = await scenario.registerTeacher(), student = await scenario.registerStudent();
  await login(page, teacher);
  const checkpoint = await scenario.checkpoint(teacher, 4);
  const assignment = await scenario.assignment(student, checkpoint);
  await charged(student, assignment.id, 4);
  await student.api.submit(assignment.id);
  await page.goto(`/checkpoints/${checkpoint.id}`);
  await page.getByRole('button', { name: `Действия: ${student.fullName}`, exact: true }).click();
  await page.getByRole('menuitem', { name: 'Вернуть…', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('button', { name: 'Вернуть работу', exact: true }).click();
  await expect(dialog.getByText('Укажите комментарий', { exact: true })).toBeVisible();
  await dialog.getByLabel('Комментарий возврата').fill('Добавьте выводы');
  await dialog.getByRole('button', { name: 'Вернуть работу', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await logout(page);
  await login(page, student);
  await page.goto(`/tasks/${assignment.id}`);
  await expect(page.getByText('Добавьте выводы', { exact: true }).first()).toBeVisible();
  await charged(student, assignment.id, 12);
  await page.reload();
  await expect(page.getByText('Списано 12 из 12 АР', { exact: true })).toBeVisible();
  await evidence(page, 'PEN-03-return');
});

test('CP-03 приём пачкой: сданная принята, несданная пропущена с причиной', async ({ page, scenario }) => {
  const teacher = await scenario.registerTeacher();
  const submitted = await scenario.registerStudent(), open = await scenario.registerStudent();
  const fresh = await scenario.registerStudent();
  const checkpoint = await scenario.checkpoint(teacher);
  const work = await scenario.assignment(submitted, checkpoint), pending = await scenario.assignment(open, checkpoint);
  await submitted.api.submit(work.id);
  const freshWork = await scenario.assignment(fresh, checkpoint);
  await fresh.api.submit(freshWork.id);
  await login(page, teacher);
  await page.goto(`/checkpoints/${checkpoint.id}`);
  await expect(page.getByRole('checkbox', { name: open.fullName, exact: true })).toBeDisabled();
  await choose(page.getByText(submitted.fullName, { exact: true }));
  await page.getByRole('button', { name: /Принять/ }).click();
  await expect(page.getByRole('row').filter({ hasText: submitted.fullName })).toContainText('Принято');
  const result = await teacher.api.post<Schemas['AcceptAssignmentsResult']>(`/api/v1/checkpoints/${checkpoint.id}/assignments/accept`, {
    assignmentIds: [freshWork.id, pending.id],
  });
  expect(result.accepted).toEqual([freshWork.id]);
  expect(result.skipped).toContainEqual({ assignmentId: pending.id, code: 'INVALID_STATE' });
  await logout(page);
  await login(page, submitted);
  await page.goto(`/users/${submitted.id}`);
  await page.getByRole('tab', { name: 'Задачи', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Завершённые задачи', exact: true })).toBeVisible();
  await expect(page.getByText('Принято', { exact: true })).toBeVisible();
});

test('CP-01 CP-02 отказы: поля точки, чужая группа, роль студента и чужая сдача', async ({ page, scenario }) => {
  const teacher = await scenario.registerTeacher();
  const student = await scenario.registerStudent(), other = await scenario.registerStudent();
  const body: Schemas['CheckpointCreate'] = {
    title: 'Проверка отказов', description: '', groupIds: [scenario.group.id],
    deadline: await teacher.api.future(120), cost: 12,
  };
  for (const fields of [{ cost: 0 }, { cost: 1001 }, { groupIds: [] }, { deadline: new Date(await teacher.api.now() - 1000).toISOString() }]) {
    await teacher.api.send('/api/v1/checkpoints', { ...body, ...fields }, 'POST', 400);
  }
  await teacher.api.send('/api/v1/checkpoints', { ...body, groupIds: [11] }, 'POST', 403);
  await student.api.send('/api/v1/checkpoints', body, 'POST', 403);
  const checkpoint = await scenario.checkpoint(teacher);
  const assignment = await scenario.assignment(student, checkpoint);
  const stranger = await scenario.registerTeacher();
  await stranger.api.send(`/api/v1/checkpoints/${checkpoint.id}`, { title: 'Чужая правка' }, 'PATCH', 403);
  expect((await stranger.api.context.get(`/api/v1/checkpoints/${checkpoint.id}/assignments`)).status()).toBe(403);
  await stranger.api.send(`/api/v1/checkpoints/${checkpoint.id}/assignments/accept`, { assignmentIds: [assignment.id] }, 'POST', 403);
  await stranger.api.send(`/api/v1/assignments/${assignment.id}/return`, { comment: 'Чужой возврат' }, 'POST', 403);
  expect((await other.api.context.get(`/api/v1/assignments/${assignment.id}`)).status()).toBe(403);
  const forbidden = await other.api.context.post(`/api/v1/assignments/${assignment.id}/submission`, {
    headers: await other.api.csrf(), multipart: { link: 'https://example.org/stolen' },
  });
  expect(forbidden.status()).toBe(403);
  const uploads: Record<string, string | { name: string; mimeType: string; buffer: Buffer }>[] = [
    {}, { link: 'https://example.org/report', file: { name: 'report.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4') } },
  ];
  for (const multipart of uploads) {
    const response = await student.api.context.post(`/api/v1/assignments/${assignment.id}/submission`, {
      headers: await student.api.csrf(), multipart,
    });
    expect(response.status()).toBe(400);
  }
  await login(page, student);
  await page.goto('/checkpoints/new');
  await expect(page.getByRole('heading', { name: 'Нет доступа', exact: true })).toBeVisible();
  await page.goto(`/tasks/${assignment.id}`);
  await page.getByRole('button', { name: 'Сдать работу', exact: true }).click();
  await expect(page.getByLabel('Ссылка на работу')).toHaveAttribute('aria-invalid', 'true');
  await expect(page.getByText('В процессе', { exact: true })).toBeVisible();
});

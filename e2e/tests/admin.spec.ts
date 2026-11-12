import { test, expect, login, logout, evidence, moscowInput, type Schemas } from './base/support.js';

const administrator = { email: 'admin@campus.test', password: 'demo-admin-1' };
test('ADM-01: семестры, настройки и бюджеты администратора — 004/AE1–4,8,11', async ({ page, scenario }) => {
  await login(page, administrator);
  await page.goto('/admin/semesters');
  await expect(page.getByRole('heading', { name: 'Семестры', exact: true })).toBeVisible();
  const semesters = await scenario.admin.get<Schemas['AdminSemesterList']>('/api/v1/admin/semesters');
  const latest = Math.max(Date.now(), ...semesters.items.map((s) => Date.parse(s.startsAt)));
  const start = new Date(latest + 86400_000).toISOString();
  const name = `Семестр ${scenario.prefix}`;
  await page.getByRole('button', { name: 'Добавить семестр' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('textbox', { name: 'Название', exact: true }).fill(name);
  await dialog.getByLabel('Начало (МСК)').fill(moscowInput(start));
  await dialog.getByRole('button', { name: 'Сохранить', exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(page.getByText(name, { exact: true })).toBeVisible();
  await evidence(page, 'ADM-01-semesters');
  await page.getByRole('button', { name: `Удалить ${name}`, exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Удалить', exact: true }).click();
  await expect(page.getByText(name, { exact: true })).not.toBeVisible();
  await page.goto('/admin/settings');
  await page.getByLabel('Шаг штрафа, АР').fill('0');
  await page.getByRole('button', { name: 'Сохранить', exact: true }).click();
  await expect(page.getByText('Допустимо от 1 до 1000', { exact: true })).toBeVisible();
  await evidence(page, 'ADM-01-settings');
  await page.goto('/admin/budgets');
  await page.getByLabel('Месяц', { exact: true }).fill('2000-01');
  await expect(page.getByText('За январь 2000 начислений не было', { exact: true })).toBeVisible();
  await evidence(page, 'ADM-01-budgets');
});

test('ADM-02: временный пароль, смена и приватный аудит — 006/AE3,7,9', async ({ page, scenario }) => {
  const student = await scenario.registerStudent();
  await login(page, administrator);
  await page.goto('/admin/users');
  await page.getByLabel('Поиск по ФИО или email').fill(student.email);
  const row = page.getByRole('row').filter({ has: page.getByText(student.email, { exact: true }) });
  await row.getByRole('button', { name: 'Выставить временный пароль', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('button', { name: 'Выставить', exact: true }).click();
  await expect(dialog.getByLabel('Временный пароль', { exact: true })).toBeVisible();
  const password = await dialog.getByLabel('Временный пароль', { exact: true }).innerText();
  expect(password).toMatch(/^[A-HJ-NP-Za-km-z2-9]{12}$/);
  await dialog.getByRole('button', { name: 'Закрыть', exact: true }).click();
  await expect(page.getByText(password, { exact: true })).toHaveCount(0);
  expect((await student.api.context.get('/api/v1/auth/me')).status()).toBe(401);
  await evidence(page, 'ADM-02-users');
  await logout(page);
  await login(page, { email: student.email, password });
  await expect(page).toHaveURL(/\/password\/change$/);
  await page.getByLabel('Текущий пароль', { exact: true }).fill(password);
  await page.getByLabel('Новый пароль', { exact: true }).fill('New-e2e-password-32');
  await page.getByRole('button', { name: 'Сменить пароль', exact: true }).click();
  await expect(page).not.toHaveURL(/\/password\/change$/);
  await page.goto(`/users/${student.id}`);
  await expect(page.getByText(/Пароль выставлен: Администратор/)).toBeVisible();
  await evidence(page, 'ADM-02-audit');
});

test('ADM-03: импорт, построчный отчёт и повтор — 005/AE3,6', async ({ page, scenario }) => {
  await login(page, administrator);
  await page.goto('/admin/import');
  const content = 'faculty_code,group_code,group_name,admission_year\n'
    + `${scenario.faculty.code},${scenario.prefix}-import,Новая группа,2026\n`
    + `missing,${scenario.prefix}-bad,Нет факультета,2026\n`;
  const chooser = page.waitForEvent('filechooser');
  await page.getByLabel('Файл импорта').click();
  await (await chooser).setFiles({ name: 'groups.csv', mimeType: 'text/csv', buffer: Buffer.from(content) });
  await page.getByRole('button', { name: 'Импортировать', exact: true }).click();
  await expect(page.getByText('Создано: 1 · Пропущено (уже есть): 0 · Отклонено: 1', { exact: true })).toBeVisible();
  await expect(page.getByText('факультет не найден', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Импортировать', exact: true }).click();
  await expect(page.getByText('Создано: 0 · Пропущено (уже есть): 1 · Отклонено: 1', { exact: true })).toBeVisible();
  await evidence(page, 'ADM-03-import');
});

test('ADM-04: материалы сдачи только для чтения — 007/AE1,3,4', async ({ page, scenario }) => {
  const student = await scenario.registerStudent(), teacher = await scenario.registerTeacher();
  const checkpoint = await scenario.checkpoint(teacher), assignment = await scenario.assignment(student, checkpoint);
  await student.api.submit(assignment.id); await teacher.api.returnWork(assignment.id, 'Добавьте выводы');
  await login(page, administrator);
  await page.goto(`/admin/submissions/${assignment.id}`);
  await expect(page.getByRole('heading', { name: checkpoint.title })).toBeVisible();
  await expect(page.getByText('Добавьте выводы', { exact: true })).toBeVisible();
  for (const name of ['Сдать работу', 'Отозвать сдачу', 'Принять', 'Вернуть…']) await expect(page.getByRole('button', { name, exact: true })).toHaveCount(0);
  await evidence(page, 'ADM-04-submission');
  await logout(page); await login(page, student);
  await page.goto('/admin/submissions/999999');
  await expect(page.getByRole('heading', { name: 'Нет доступа', exact: true })).toBeVisible();
});

import { test, expect, login, logout, evidence, choose, type Schemas, type User } from './support.js';
import { mailToken } from '../../fixtures/greenmail.js';
import type { Page } from '@playwright/test';

async function fill(page: Page, user: Pick<User, 'lastName' | 'firstName' | 'middleName' | 'email' | 'password'>, faculty: number, target: number, teacher = false) {
  await page.goto('/register');
  if (teacher) await choose(page.getByText('Преподаватель', { exact: true }));
  await page.getByLabel('Фамилия', { exact: true }).fill(user.lastName);
  await page.getByLabel('Имя', { exact: true }).fill(user.firstName);
  await page.getByLabel('Отчество', { exact: true }).fill(user.middleName);
  await page.getByLabel('Email', { exact: true }).fill(user.email);
  await page.getByLabel('Пароль', { exact: true }).fill(user.password);
  await page.getByLabel('Факультет', { exact: true }).selectOption(String(faculty));
  await page.getByLabel(teacher ? 'Кафедра' : 'Группа', { exact: true }).selectOption(String(target));
}

test('REG-01 Covers AE1, AE40 студент регистрируется и получает начальные балансы', async ({ page, scenario }) => {
  const user = scenario.identity();
  await fill(page, user, scenario.faculty.id, scenario.group.id);
  await evidence(page, 'U24-registration-name-fields');
  await page.getByRole('button', { name: 'Зарегистрироваться' }).click();
  await expect(page.getByRole('status')).toContainText('Регистрация завершена. Подтвердите email по ссылке из письма.');
  await scenario.confirmEmail(user.email);
  await login(page, user);
  const me = await page.request.get('/api/v1/auth/me');
  const { id } = await me.json() as { id: number };
  await page.goto(`/users/${id}`);
  await expect(page.getByRole('heading', { name: user.fullName, exact: true })).toBeVisible();
  const balances = page.getByLabel('Балансы', { exact: true });
  await expect(balances).toContainText('1000');
  await expect(balances.getByText('0', { exact: true })).toHaveCount(2);
  await evidence(page, 'REG-01-profile');
});

test('REG-01 ошибки полей: короткий пароль, занятый email, чужой факультет', async ({ page, scenario }) => {
  const existing = await scenario.registerStudent();
  await fill(page, { ...existing, password: 'short' }, scenario.faculty.id, scenario.group.id);
  await page.getByRole('button', { name: 'Зарегистрироваться' }).click();
  await expect(page.getByLabel('Пароль', { exact: true })).toHaveAttribute('aria-invalid', 'true');
  await expect(page.getByText('Пароль должен содержать не меньше 8 символов', { exact: true })).toBeVisible();
  await page.getByLabel('Пароль', { exact: true }).fill(existing.password);
  await page.getByRole('button', { name: 'Зарегистрироваться' }).click();
  await expect(page.getByLabel('Email', { exact: true })).toHaveAttribute('aria-invalid', 'true');
  await expect(page.getByText('Этот email уже зарегистрирован', { exact: true })).toBeVisible();
  await page.getByLabel('Факультет', { exact: true }).selectOption('1');
  await expect(page.getByLabel('Группа', { exact: true })).toHaveValue('');
  await expect(page.getByRole('option', { name: scenario.group.name, exact: true })).toHaveCount(0);
  const guest = await scenario.api();
  const response = await guest.send('/api/v1/auth/register/student', {
    lastName: 'Тест', firstName: 'Студент', email: scenario.identity().email, password: 'e2e-password-1', facultyId: 1, groupId: scenario.group.id,
  }, 'POST', 400);
  expect(await response.json()).toMatchObject({ code: 'VALIDATION_FAILED', fieldErrors: [{ field: 'groupId' }] });
  await page.getByLabel('Email', { exact: true }).fill(scenario.identity().email);
  await page.getByLabel('Группа', { exact: true }).selectOption('11');
  await page.route('**/api/v1/auth/register/student', async (route) => {
    const body = route.request().postDataJSON() as Schemas['StudentRegistration'];
    await route.continue({ postData: JSON.stringify({ ...body, groupId: scenario.group.id }) });
  });
  await page.getByRole('button', { name: 'Зарегистрироваться' }).click();
  await expect(page.getByLabel('Группа', { exact: true })).toHaveAttribute('aria-invalid', 'true');
  await expect(page.getByText(/Группа не принадлежит .*факультету/)).toBeVisible();
});

test('REG-02 преподаватель ждёт допуска, после API U13 видит группы', async ({ page, scenario }) => {
  const user = scenario.identity('Преподаватель');
  await fill(page, user, scenario.faculty.id, scenario.department.id, true);
  await page.getByRole('button', { name: 'Зарегистрироваться' }).click();
  await expect(page.getByRole('status')).toContainText('Регистрация завершена');
  await scenario.confirmEmail(user.email);
  await login(page, user);
  await expect(page.getByText('Заявка ждёт подтверждения администратора', { exact: true })).toBeVisible();
  await page.goto('/groups');
  await expect(page.getByText('Доступных групп пока нет.', { exact: false })).toBeVisible();
  const me = await page.request.get('/api/v1/auth/me');
  const { id } = await me.json() as { id: number };
  await scenario.approveTeacher(id);
  await page.reload();
  await expect(page.getByRole('link', { name: `${scenario.group.name} · ${scenario.faculty.code}`, exact: true })).toBeVisible();
  await expect(page.getByText('Заявка ждёт подтверждения администратора', { exact: true })).toHaveCount(0);
  await evidence(page, 'REG-02-approved');
});

test('REG-02 email из списка преподавателей нельзя зарегистрировать студентом', async ({ page, scenario }) => {
  await fill(page, { ...scenario.identity(), email: 'petrov@campus.test' }, scenario.faculty.id, scenario.group.id);
  await page.getByRole('button', { name: 'Зарегистрироваться' }).click();
  await expect(page.getByLabel('Email', { exact: true })).toHaveAttribute('aria-invalid', 'true');
  await expect(page.getByText('Этот email закреплён за преподавателем. Зарегистрируйтесь как преподаватель.', { exact: true })).toBeVisible();
});

test('AUTH-01 одинаковая ошибка входа, защита сессии и CSRF, выход', async ({ page, scenario }) => {
  const student = await scenario.registerStudent();
  await page.goto(`/users/${student.id}`);
  await expect(page).toHaveURL(/\/login$/);
  expect((await page.request.get('/api/v1/auth/me')).status()).toBe(401);
  expect((await page.request.post('/api/v1/auth/login', { data: { email: student.email, password: student.password } })).status()).toBe(403);
  for (const email of [student.email, `${scenario.prefix}-unknown@campus.test`]) {
    await page.getByLabel('Email', { exact: true }).fill(email);
    await page.getByLabel('Пароль', { exact: true }).fill('wrong-password');
    await page.getByRole('button', { name: 'Войти', exact: true }).click();
    await expect(page.getByText('Неверный email или пароль', { exact: true })).toBeVisible();
    await expect(page).toHaveURL(/\/login$/);
  }
  await login(page, student);
  await logout(page);
  expect((await page.request.get('/api/v1/auth/me')).status()).toBe(401);
  await page.goto('/tasks');
  await expect(page).toHaveURL(/\/login$/);
});

test('REG-03 полный путь: письмо, подтверждение email, вход, подтверждение членства старостой, назначение по открытой точке', async ({ page, browser, scenario }) => {
  const teacher = await scenario.registerTeacher();
  const head = await scenario.registerStudent();
  await scenario.admin.send(`/api/v1/admin/group-heads/${scenario.group.id}`, { studentId: head.id }, 'PUT');
  const expired = await scenario.checkpoint(teacher, 3);
  const open = await scenario.checkpoint(teacher, 600);
  await teacher.api.until(new Date(Date.parse(expired.deadline) + 1000).toISOString());

  const user = scenario.identity();
  await fill(page, user, scenario.faculty.id, scenario.group.id);
  await page.getByRole('button', { name: 'Зарегистрироваться' }).click();
  await expect(page.getByRole('status')).toContainText('Подтвердите email по ссылке из письма.');
  await page.goto(`/email/confirm/${await mailToken(user.email, 'email/confirm')}`);
  await page.getByRole('button', { name: 'Подтвердить email', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('Email подтверждён');
  await login(page, user);
  await expect(page.getByText(`Членство в ${scenario.group.name} ждёт подтверждения старосты или преподавателя`, { exact: true })).toBeVisible();

  const headContext = await browser.newContext({ baseURL: test.info().project.use.baseURL, viewport: page.viewportSize() ?? undefined });
  try {
    const headPage = await headContext.newPage();
    await login(headPage, head);
    await headPage.getByRole('link', { name: 'Проверить заявки', exact: true }).click();
    await expect(headPage).toHaveURL(/\/memberships$/);
    await headPage.getByRole('button', { name: `Подтвердить ${user.fullName}`, exact: true }).click();
    await expect(headPage.getByText('Новых заявок нет', { exact: true })).toBeVisible();
    await evidence(headPage, 'REG-03-head-approved');
  } finally { await headContext.close(); }

  await page.goto('/tasks');
  await expect(page.getByRole('link', { name: open.title })).toBeVisible();
  await expect(page.getByText(expired.title)).toHaveCount(0);
  await evidence(page, 'REG-03-student-tasks');
});

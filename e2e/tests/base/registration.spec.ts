import { test, expect, login, logout, evidence, choose, type Schemas, type User } from './support.js';
import type { Page } from '@playwright/test';

async function fill(page: Page, user: Pick<User, 'fullName' | 'email' | 'password'>, faculty: number, target: number, teacher = false) {
  await page.goto('/register');
  if (teacher) await choose(page.getByText('Преподаватель', { exact: true }));
  await page.getByLabel('ФИО', { exact: true }).fill(user.fullName);
  await page.getByLabel('Email', { exact: true }).fill(user.email);
  await page.getByLabel('Пароль', { exact: true }).fill(user.password);
  await page.getByLabel('Факультет', { exact: true }).selectOption(String(faculty));
  await page.getByLabel(teacher ? 'Кафедра' : 'Группа', { exact: true }).selectOption(String(target));
}

test('REG-01 студент регистрируется и получает начальные балансы', async ({ page, scenario }) => {
  const user = scenario.identity();
  await fill(page, user, scenario.faculty.id, scenario.group.id);
  await page.getByRole('button', { name: 'Зарегистрироваться' }).click();
  await expect(page.getByRole('status')).toContainText('Регистрация завершена. Теперь можно войти');
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
    ...scenario.identity(), facultyId: 1, groupId: scenario.group.id,
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

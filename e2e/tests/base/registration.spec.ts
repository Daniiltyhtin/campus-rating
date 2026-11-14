import { test, expect, login, logout, evidence, fillRegistration, type Schemas } from './support.js';
import { mailToken } from '../../fixtures/greenmail.js';

test('REG-01 Covers AE1, AE40 студент регистрируется и получает начальные балансы', async ({ page, scenario }) => {
  const user = scenario.identity();
  await fillRegistration(page, user, scenario.faculty.id, scenario.group.id);
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
  await fillRegistration(page, { ...existing, password: 'short' }, scenario.faculty.id, scenario.group.id);
  await page.getByRole('button', { name: 'Зарегистрироваться' }).click();
  await expect(page.getByLabel('Пароль', { exact: true })).toHaveAttribute('aria-invalid', 'true');
  await expect(page.getByText('Пароль должен содержать не меньше 8 символов', { exact: true })).toBeVisible();
  await page.getByLabel('Пароль', { exact: true }).fill(existing.password);
  await page.getByLabel('Повторите пароль', { exact: true }).fill(existing.password);
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
  await fillRegistration(page, user, scenario.faculty.id, scenario.department.id, true);
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
  await fillRegistration(page, { ...scenario.identity(), email: 'petrov@campus.test' }, scenario.faculty.id, scenario.group.id);
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
  await fillRegistration(page, user, scenario.faculty.id, scenario.group.id);
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

for (const teacher of [false, true]) {
  test(`U24 U8 Covers AE25, AE26: ${teacher ? 'преподаватель' : 'студент'} исправляет повтор пароля и регистрируется`, async ({ page, scenario }) => {
    const user = { ...scenario.identity(), password: 'Secret123' };
    const endpoint = `/api/v1/auth/register/${teacher ? 'teacher' : 'student'}`;
    const requests: unknown[] = [];
    page.on('request', (request) => {
      if (request.method() === 'POST' && new URL(request.url()).pathname === endpoint) requests.push(request.postDataJSON());
    });
    await fillRegistration(page, user, scenario.faculty.id, teacher ? scenario.department.id : scenario.group.id, teacher);
    const password = page.getByLabel('Пароль', { exact: true });
    const repeat = page.getByLabel('Повторите пароль', { exact: true });
    const error = page.getByText('Пароли не совпадают', { exact: true });
    const submit = page.getByRole('button', { name: 'Зарегистрироваться', exact: true });
    await repeat.fill('Secret124');
    await submit.click();
    await expect(error).toBeVisible();
    await expect(repeat).toHaveAttribute('aria-invalid', 'true');
    await expect(repeat).toHaveAccessibleDescription('Пароли не совпадают');
    expect(requests).toHaveLength(0);
    await password.fill('Secret124');
    await expect(error).toBeVisible();
    await password.fill('Secret123');
    await repeat.fill('Secret125');
    await expect(error).toHaveCount(0);
    expect(requests).toHaveLength(0);
    await submit.click();
    await expect(error).toBeVisible();
    expect(requests).toHaveLength(0);
    await evidence(page, 'U24-U8-password-mismatch');
    await repeat.fill('Secret123');
    await expect(error).toHaveCount(0);
    await submit.click();
    await expect(page.getByRole('status')).toContainText('Регистрация завершена');
    expect(requests).toEqual([teacher ? {
      lastName: user.lastName, firstName: user.firstName, email: user.email, password: user.password, departmentId: scenario.department.id,
    } : {
      lastName: user.lastName, firstName: user.firstName, email: user.email, password: user.password, facultyId: scenario.faculty.id, groupId: scenario.group.id,
    }]);
    await evidence(page, 'U24-U8-registration-success');
    await scenario.confirmEmail(user.email);
    await login(page, user);
  });
}

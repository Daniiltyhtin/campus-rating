import { test, expect, login, logout, evidence, moscowInput, type Schemas } from './support.js';

function escape(text: string) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

test('POLL-01 преподаватель создаёт опрос в UI, студент голосует один раз и видит результаты, автор — детализацию', async ({ page, scenario }) => {
  const teacher = await scenario.registerTeacher();
  const student = await scenario.registerStudent();
  await scenario.registerStudent();
  const title = `Опрос ${scenario.prefix}`;

  await login(page, teacher);
  await page.goto('/');
  const mine = page.getByRole('region', { name: 'Мои опросы', exact: true });
  await mine.getByRole('link', { name: 'Новый опрос', exact: true }).click();
  await expect(page).toHaveURL(/\/polls\/new$/);
  await page.getByLabel('Название', { exact: true }).fill(title);
  await page.getByLabel('Описание', { exact: true }).fill('Выберите удобное время.');
  await page.getByLabel(/Окончание/).fill(moscowInput(await teacher.api.future(3600)));
  await page.getByRole('checkbox', { name: new RegExp(escape(scenario.group.name)) }).check({ force: true });
  await page.getByLabel('Анонимный опрос', { exact: true }).setChecked(false);
  const question = page.getByRole('group', { name: /^Вопрос 1(\D|$)/ });
  await question.getByRole('textbox', { name: 'Текст вопроса', exact: true }).fill('Когда удобнее?');
  await question.getByRole('radio', { name: 'Один вариант', exact: true }).check();
  await question.getByRole('textbox', { name: 'Вариант 1', exact: true }).fill('Утро');
  await question.getByRole('textbox', { name: 'Вариант 2', exact: true }).fill('Вечер');
  await evidence(page, 'POLL-01-form');
  await page.getByRole('button', { name: 'Создать опрос', exact: true }).click();
  await expect(page).toHaveURL(/\/polls\/\d+$/);
  await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible();
  const pollId = Number(new URL(page.url()).pathname.split('/').pop());
  await logout(page);

  await login(page, student);
  await page.goto('/');
  const fresh = page.getByRole('region', { name: 'Новые опросы', exact: true });
  await fresh.getByRole('link', { name: new RegExp(escape(title)) }).click();
  await expect(page).toHaveURL(new RegExp(`/polls/${pollId}$`));
  const answer = page.getByRole('radiogroup', { name: /Когда удобнее\?/ });
  const submit = page.getByRole('button', { name: 'Проголосовать', exact: true });
  await expect(submit).toBeDisabled();
  await answer.getByRole('radio', { name: 'Вечер', exact: true }).check();
  await submit.click();
  await expect(page.getByText(/100\s*%/).first()).toBeVisible();
  await expect(page.getByText(/Проголосовал[аио]?\s*:?\s*1(?!\d)/i)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Проголосовать', exact: true })).toHaveCount(0);
  await evidence(page, 'POLL-01-voted');
  await page.reload();
  await expect(page.getByText(/100\s*%/).first()).toBeVisible();
  await expect(page.getByRole('button', { name: 'Проголосовать', exact: true })).toHaveCount(0);
  const poll = await student.api.get<Schemas['Poll']>(`/api/v1/polls/${pollId}`);
  const repeat = await student.api.send(`/api/v1/polls/${pollId}/vote`, {
    answers: [{ questionId: poll.questions[0].id, optionIds: [poll.questions[0].options[0].id] }],
  }, 'POST', 409);
  expect(await repeat.json()).toMatchObject({ code: 'ALREADY_VOTED' });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Главная', exact: true })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Новые опросы', exact: true }).getByRole('link', { name: new RegExp(escape(title)) }))
    .toHaveCount(0);
  await logout(page);

  await login(page, teacher);
  await page.goto(`/polls/${pollId}`);
  await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible();
  await expect(page.getByText(/Проголосовали\s*1\s*из\s*2/i)).toBeVisible();
  const row = page.getByRole('row').filter({ hasText: student.fullName });
  await expect(row).toHaveCount(1);
  await expect(row).toContainText(scenario.group.name);
  await expect(row).toContainText('Вечер');
  await evidence(page, 'POLL-01-author');
});

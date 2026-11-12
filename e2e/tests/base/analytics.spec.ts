import { test, expect, login, evidence } from './support.js';

test('ANL-01 U17/U6: преподаватель открывает аналитику ИВТ-21 и меняет период на 90 дней', async ({ page }) => {
  await login(page, { email: 'petrov@campus.test', password: 'demo-teacher-1' });
  await page.goto('/groups');
  await page.getByRole('link', { name: /^ИВТ-21 ·/ }).click();
  await page.getByRole('tab', { name: 'Аналитика', exact: true }).click();
  await expect(page).toHaveURL(/tab=analytics/);
  await expect(page.getByRole('heading', { name: 'Средний АР группы', exact: true })).toBeVisible();
  const daily = page.getByRole('table', { name: 'Средний АР группы', exact: true });
  const points = page.getByRole('table', { name: 'Баллы за период', exact: true });
  const budget = page.getByRole('table', { name: 'Расход бюджета по месяцам', exact: true });
  await expect(daily.getByRole('row')).toHaveCount(31);
  await expect(points).toBeVisible();
  await expect(budget.getByRole('row')).toHaveCount(7);
  await expect(page.getByRole('application')).toBeVisible();
  const averages: string[] = [];
  for (const row of (await daily.getByRole('row').all()).slice(1)) {
    averages.push(await row.getByRole('cell').nth(1).innerText());
  }
  expect(new Set(averages.filter((average) => average !== '—')).size).toBeGreaterThan(1);
  await evidence(page, 'ANL-01-30-days');
  const budgetBefore = await budget.innerText();
  const period = page.getByRole('combobox', { name: 'Период', exact: true });
  if (await period.evaluate((element) => element.tagName === 'SELECT')) {
    await period.selectOption({ label: '90 дней' });
  } else {
    await period.click();
    await page.getByRole('option', { name: '90 дней', exact: true }).click();
  }
  await expect(daily.getByRole('row')).toHaveCount(91);
  await expect(page).toHaveURL(/period=90/);
  await expect(points).toBeVisible();
  await expect.poll(() => budget.innerText()).toBe(budgetBefore);
  await evidence(page, 'ANL-01-90-days');
});

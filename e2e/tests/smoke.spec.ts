import { expect, test } from '@playwright/test';

test('U2: главная страница открывает форму входа (R6, R40)', async ({ page }) => {
  await page.goto('/');

  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByLabel('Email', { exact: true })).toBeVisible();
  await expect(page.getByLabel('Пароль', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Войти', exact: true })).toBeVisible();
  await expect.poll(() => page.evaluate(() =>
    document.documentElement.scrollWidth <= document.documentElement.clientWidth,
  )).toBe(true);
  await page.screenshot({ path: test.info().outputPath('login.png'), fullPage: true });
});

test('U2: nginx сохраняет API и закрывает actuator (R6, KTD11)', async ({ request }) => {
  const index = await request.get('/');
  expect(index.status()).toBe(200);
  expect(index.headers()['content-type']).toMatch(/^text\/html/);
  const html = await index.text();
  expect(html).toMatch(/<!doctype html>/i);

  const missing = await request.get('/api/v1/does-not-exist');
  expect(missing.status()).toBe(404);
  // Boot error JSON now, application/problem+json after master-U3 ProblemDetail.
  expect(missing.headers()['content-type']).toMatch(/^application\/(problem\+)?json/);
  expect(await missing.json()).toMatchObject({ status: 404 });

  const actuator = await request.get('/actuator/health');
  expect(actuator.status()).toBe(200);
  expect(actuator.headers()['content-type']).toMatch(/^text\/html/);
  expect(await actuator.text()).toBe(html);

  const asset = await request.get('/assets/does-not-exist.js');
  expect(asset.status()).toBe(404);
  expect(asset.headers()['content-type']).toMatch(/^text\/html/);
});

import { test, expect, type Page } from '@playwright/test';
import { login } from './base/support.js';

const teacher = { email: 'petrov@campus.test', password: 'demo-teacher-1' };
const stepSlide = (page: Page, step: number) => page.locator(`[data-demo-step="${step}"]`);
const phone = (page: Page) => page.frameLocator('iframe[title="Живое демо Campus Rating"]');

async function show(page: Page, step: number) {
  const target = stepSlide(page, step);
  for (let index = 0; index < 60 && !await target.evaluate((slide) => slide.classList.contains('active')); index++) {
    const previous = page.locator('section.slide.active');
    const previousIndex = await previous.getAttribute('data-demo-step');
    await page.keyboard.press('ArrowRight');
    if (previousIndex) await expect(stepSlide(page, Number(previousIndex))).not.toHaveClass(/\bactive\b/);
    await expect(page.locator('section.slide.active')).toHaveCount(1);
  }
  await expect(target).toHaveClass(/\bactive\b/);
  return target;
}

async function start(page: Page) {
  page.on('console', (message) => { if (message.text().startsWith('[demo]')) console.log(message.text()); });
  await page.goto('/presentation?live');
  const intro = page.locator('[data-demo-intro]');
  for (let index = 0; index < 60 && !await intro.evaluate((slide) => slide.classList.contains('active')); index++) {
    await page.keyboard.press('ArrowRight');
  }
  await expect(intro).toHaveClass(/\bactive\b/);
  await intro.locator('[data-demo-prefix]').fill('demo');
  await intro.locator('[data-demo-prefix]').blur();
}

async function run(page: Page, step: number) {
  const slide = await show(page, step);
  await expect(slide).toHaveAttribute('data-demo-state', 'idle');
  await page.keyboard.press('ArrowRight');
  await expect(slide).toHaveClass(/\bactive\b/);
  if (step !== 4) await expect(page.locator('.demo-tap').first()).toBeAttached({ timeout: 30_000 });
  await expect(slide).toHaveAttribute('data-demo-state', 'done', { timeout: step === 4 ? 230_000 : 120_000 });
}

test.describe('R25 живое демо защиты', () => {
  test.describe.configure({ mode: 'serial' });
  test.beforeEach(() => {
    test.setTimeout(300_000);
  });

  test('без запуска перелистывание и перезагрузка не создают точку', async ({ page, browser }) => {
    const observer = await browser.newContext({ baseURL: test.info().project.use.baseURL });
    try {
      const account = await observer.newPage();
      await login(account, teacher);
      await account.goto('/checkpoints');
      await expect(account.getByRole('link', { name: 'Лабораторная работа: анализ данных', exact: true })).toBeVisible();
      const titles = account.getByRole('link', { name: /^Курсовая, этап / });
      const before = await titles.allTextContents();
      let siteRequests = 0;
      page.on('request', (request) => { if (new URL(request.url()).pathname.startsWith('/api/')) siteRequests++; });
      await page.goto('/presentation?live');
      await show(page, 1);
      await page.reload();
      await show(page, 1);
      await expect(page.locator('iframe')).not.toHaveAttribute('src', /\/(login|tasks|checkpoints)/);
      await expect(stepSlide(page, 1).locator('[data-demo-frame-slot]')).toHaveText('campus-rating.ru');
      await account.reload();
      await expect(titles).toHaveText(before);
      expect(siteRequests).toBe(0);
    } finally { await observer.close(); }
  });

  test('без префикса клавиша листает дальше, префикс забывается после перезагрузки', async ({ page }) => {
    await page.goto('/presentation?live');
    await show(page, 1);
    await page.keyboard.press('ArrowRight');
    await expect(stepSlide(page, 2)).toHaveClass(/\bactive\b/);
    await expect(stepSlide(page, 1)).toHaveAttribute('data-demo-state', 'idle');
    await start(page);
    await show(page, 1);
    await expect(stepSlide(page, 1)).toHaveAttribute('data-demo-state', 'idle');
    await page.reload();
    await expect(page.locator('[data-demo-prefix]')).toHaveValue('');
    await show(page, 1);
    await page.keyboard.press('ArrowRight');
    await expect(stepSlide(page, 2)).toHaveClass(/\bactive\b/);
    await expect(page.locator('iframe')).not.toHaveAttribute('src', /./);
  });

  test('шаги 1–7: создание, штраф, возврат, приём и 38 БОА в ленте', async ({ page }) => {
    await start(page);
    await run(page, 1);
    const checkpoint = await phone(page).getByRole('heading', { name: /^Курсовая, этап / }).innerText();
    await run(page, 2);
    const activity = `Субботник ${checkpoint.replace('Курсовая, этап ', '')}`;
    await expect(phone(page).getByRole('heading', { name: activity, exact: true })).toBeVisible();
    await run(page, 3);
    await expect(phone(page).getByRole('article', { name: activity, exact: true })).toContainText('Вы записаны');
    await run(page, 4);
    await expect(phone(page).getByText('Просрочено', { exact: true })).toBeVisible();
    await expect(phone(page).getByText(/Списано (4|8|12) из 12 АР/)).toBeVisible();
    await run(page, 5);
    await expect(phone(page).getByRole('row').filter({ hasText: 'Смирнова' })).toContainText('Принято');
    await run(page, 6);
    await expect(phone(page).getByRole('status')).toContainText('Начислено 38 БОА');
    await run(page, 7);
    await expect(phone(page).getByRole('article').filter({ hasText: activity })).toContainText('+38 БОА');
  });

  test('сбой iframe останавливает прогон и блокирует шаги 5–7', async ({ page }) => {
    await start(page);
    for (const step of [1, 2, 3]) await run(page, step);
    const slide = await show(page, 4);
    await page.route('**/*', async (route) => {
      if (route.request().frame().parentFrame()) await route.abort();
      else await route.continue();
    });
    await page.keyboard.press('ArrowRight');
    await expect(slide).toHaveAttribute('data-demo-state', /waiting|running/);
    await expect(slide.locator('[data-demo-status]')).toHaveText('Стенд не ответил', { timeout: 160_000 });
    for (const step of [5, 6, 7]) {
      await page.keyboard.press('ArrowRight');
      await expect(stepSlide(page, step)).toHaveClass(/\bactive\b/);
      await expect(stepSlide(page, step)).toHaveAttribute('data-demo-state', 'idle');
    }
  });

  test('экспорт показывает последний кадр записи и не создаёт iframe', async ({ page }) => {
    await page.goto('/presentation?export');
    await expect(page.locator('iframe')).toHaveCount(0);
    for (let step = 1; step <= 7; step++) {
      await expect(stepSlide(page, step).locator('[data-demo-frame-slot] img')).toHaveAttribute('src', `/presentation/demo/step-${step}.end.jpg`);
    }
  });
});

test.describe('запись демо по умолчанию', () => {
  test('клавиша запускает запись шага, следующая листает; сайт не открывается', async ({ page }) => {
    let siteRequests = 0;
    page.on('request', (request) => { if (new URL(request.url()).pathname.startsWith('/api/')) siteRequests++; });
    await page.goto('/presentation#s11');
    await expect(page.locator('[data-demo-intro]')).toHaveClass(/\bactive\b/);
    await expect(page.locator('[data-demo-prefix]')).toBeHidden();
    for (let step = 1; step <= 7; step++) {
      const response = await page.request.get(`/presentation/demo/step-${step}.mp4`);
      expect(response.status(), `step-${step}.mp4`).toBe(200);
    }
    const slide = await show(page, 1);
    const video = slide.locator('[data-demo-frame-slot] video');
    await expect(video).toHaveAttribute('src', '/presentation/demo/step-1.mp4');
    await page.keyboard.press('ArrowRight');
    await expect(slide).toHaveClass(/\bactive\b/);
    await expect(slide).toHaveAttribute('data-demo-state', 'running');
    await expect.poll(() => video.evaluate((element: HTMLVideoElement) => element.currentTime)).toBeGreaterThan(0.5);
    await page.keyboard.press('ArrowRight');
    await expect(stepSlide(page, 2)).toHaveClass(/\bactive\b/);
    await expect(page.locator('iframe')).toHaveCount(0);
    expect(siteRequests).toBe(0);
  });
});

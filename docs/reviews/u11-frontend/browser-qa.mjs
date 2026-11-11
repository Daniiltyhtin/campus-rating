import { chromium, expect } from '../../../e2e/node_modules/@playwright/test/index.mjs';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const root = new URL('./', import.meta.url);
await mkdir(root, { recursive: true });
const browser = await chromium.launch();
const results = [], errors = [];
const baseURL = process.env.U11_WEB_URL ?? 'http://127.0.0.1:5191';
async function measure(page, name, width, theme) {
  await expect(page.locator('html')).toHaveAttribute('data-theme', theme === 'contrast' ? 'high-contrast' : theme);
  await page.evaluate(() => document.fonts.ready);
  const metrics = await page.evaluate(() => {
    const visible = (element) => { const rect = element.getBoundingClientRect(); return rect.width > 0 && rect.height > 0 && getComputedStyle(element).visibility !== 'hidden'; };
    const target = Number.parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--cr-touch-target'));
    const controls = [...document.querySelectorAll('main a, main button, main select, main .mantine-SegmentedControl-label')].filter(visible);
    const small = controls.filter((element) => { const rect = element.getBoundingClientRect(); return rect.height + 0.5 < target || rect.width + 0.5 < target; }).map((element) => ({ text: element.textContent, height: element.getBoundingClientRect().height, width: element.getBoundingClientRect().width, target }));
    const rgb = (value) => value.match(/[\d.]+/g)?.slice(0, 3).map(Number);
    const luminance = (color) => color.map((part) => { const value = part / 255; return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4; }).reduce((sum, part, index) => sum + part * [0.2126, 0.7152, 0.0722][index], 0);
    const contrasts = [...document.querySelectorAll('.feed-page h1, .feed-page h2, .feed-page p, .feed-page a, .feed-page strong, .feed-page time, .feed-page label, .feed-page td, .feed-page th, .feed-page td span')].filter(visible).filter((element) => element.textContent.trim()).map((element) => {
      const color = rgb(getComputedStyle(element).color);
      let ancestor = element;
      while (ancestor.parentElement && ['rgba(0, 0, 0, 0)', 'transparent'].includes(getComputedStyle(ancestor).backgroundColor)) ancestor = ancestor.parentElement;
      const background = rgb(getComputedStyle(ancestor).backgroundColor);
      if (!color || !background) return { text: element.textContent, ratio: 0 };
      const values = [luminance(color), luminance(background)].sort((a, b) => b - a);
      return { text: element.textContent.trim(), ratio: (values[0] + 0.05) / (values[1] + 0.05) };
    });
    return { overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth, small, target,
      minimumContrast: contrasts.length ? Math.min(...contrasts.map((item) => item.ratio)) : null,
      contrastFailures: contrasts.filter((item) => item.ratio < (document.documentElement.dataset.theme === 'high-contrast' ? 7 : 4.5)),
      theme: document.documentElement.dataset.theme, text: document.querySelector('main')?.innerText };
  });
  expect(metrics.overflow).toBe(false);
  expect(metrics.small).toEqual([]);
  expect(metrics.contrastFailures).toEqual([]);
  const screenshot = `${name}-${width}-${theme}.png`;
  await page.screenshot({ path: fileURLToPath(new URL(screenshot, root)), fullPage: true });
  results.push({ name, width, theme, screenshot, ...metrics });
}
try {
  for (const role of ['teacher', 'student', 'admin']) {
    const context = await browser.newContext({ viewport: { width: 1280, height: 800 }, reducedMotion: 'reduce' });
    const page = await context.newPage();
    page.on('pageerror', (error) => errors.push({ role, error: error.message }));
    await page.goto(`${baseURL}/login`);
    await page.getByLabel('Email', { exact: true }).fill(role === 'teacher' ? 'petrov@campus.test' : role === 'student' ? 'smirnova@student.campus.test' : 'admin@campus.test');
    await page.getByLabel('Пароль', { exact: true }).fill(`demo-${role}-1`);
    await page.getByRole('button', { name: 'Войти', exact: true }).click();
    if (role === 'admin') {
      await page.getByLabel('Текущий пароль', { exact: true }).fill('demo-admin-1');
      await page.getByLabel('Новый пароль', { exact: true }).fill('changed-demo-admin-1');
      await page.getByRole('button', { name: 'Сменить пароль', exact: true }).click();
    }
    await expect(page).toHaveURL(`${baseURL}/`);
    for (const theme of ['light', 'dark', 'contrast']) {
      await page.getByLabel('Тема интерфейса').selectOption(theme);
      for (const width of [360, 1280]) {
        await page.setViewportSize({ width, height: 800 });
        await page.goto(`${baseURL}/awards`);
        await expect(page.getByRole('heading', { name: 'Лента наград', exact: true })).toBeVisible();
        await expect(page.getByText('Помощь в организации конференции', { exact: true })).toBeVisible();
        await measure(page, `${role}-awards`, width, theme);
        const student = page.getByRole('link', { name: 'Смирнова Анна Олеговна', exact: true });
        await student.click();
        await expect(page).toHaveURL(`${baseURL}/users/1001`);
        await expect(page.getByRole('heading', { name: 'Смирнова Анна Олеговна', exact: true })).toBeVisible();
        if (role !== 'teacher') continue;
        await page.goto(`${baseURL}/groups`);
        await expect(page.getByRole('link', { name: 'ИВТ-21 · ФИТ', exact: true })).toBeVisible();
        await measure(page, 'groups', width, theme);
        await page.getByRole('link', { name: 'ИВТ-21 · ФИТ', exact: true }).click();
        await expect(page.getByRole('heading', { name: 'Рейтинг группы ИВТ-21', exact: true })).toBeVisible();
        await expect(page.getByRole('table')).toBeVisible();
        await measure(page, 'rating-ar', width, theme);
        await page.getByText('БУА', { exact: true }).first().click();
        await expect(page.getByRole('radio', { name: 'БУА', exact: true })).toBeChecked();
        await expect(page.getByRole('table').getByRole('link').first()).toHaveText('Смирнова Анна Олеговна');
        await measure(page, 'rating-bua', width, theme);
        await page.keyboard.press('Tab');
        const focused = await page.evaluate(() => {
          const element = document.activeElement;
          return element && { name: element.getAttribute('aria-label') ?? element.textContent, outline: getComputedStyle(element).outlineStyle };
        });
        expect(focused?.outline).not.toBe('none');
      }
    }
    if (role === 'teacher') {
      await page.goto(`${baseURL}/groups`);
      await page.getByRole('link', { name: 'Лента наград', exact: true }).waitFor();
      await page.evaluate(async () => {
        const source = await (await fetch('/src/main.tsx')).text();
        const workerUrl = /import\("([^"]*\/mocks\/browser\.ts[^"]*)"\)/.exec(source)?.[1];
        if (!workerUrl) throw new Error('Vite mock worker import not found');
        const { worker } = await import(workerUrl);
        const { http, HttpResponse } = await import('/node_modules/.vite/deps/msw.js');
        const { examples } = await import('/src/mocks/examples.generated.ts');
        const initial = structuredClone(examples.find((item) => item.operationId === 'listAwardsFeed').responses.find((item) => item.status === 200).body);
        let fresh = false;
        globalThis.__u11Requests = [];
        globalThis.__u11ShowAwards = () => { fresh = true; };
        const latest = initial.items.map((item, index) => ({ ...item, entryId: 99_999 - index, createdAt: '2026-11-11T09:00:00Z',
          activity: { ...item.activity, title: 'Очень длинное название награждённой общественной и учебной активности '.repeat(5) },
          student: { ...item.student, fullName: 'ДлинноеИмяБезПробелов'.repeat(8) } }));
        worker.use(http.get('*/api/v1/feed/awards', ({ request }) => { globalThis.__u11Requests.push({ fresh, url: request.url }); return HttpResponse.json({ items: fresh ? [...latest, ...initial.items] : initial.items, nextCursor: null }); }));
        const rating = structuredClone(examples.find((item) => item.operationId === 'getGroupRating').responses.find((item) => item.status === 200).body);
        rating.group.name = 'Очень длинное имя учебной группы '.repeat(5);
        rating.group.faculty.name = 'ОченьДлинноеНазваниеФакультетаБезПробелов'.repeat(6);
        rating.items[0].student.fullName = 'Очень длинное имя студента '.repeat(8);
        worker.use(http.get('*/api/v1/groups/:id/ratings', ({ request }) => HttpResponse.json({ ...rating, sort: new URL(request.url).searchParams.get('sort') ?? 'AR' })));
      });
      await page.getByRole('link', { name: 'Лента наград', exact: true }).click();
      await expect(page.getByText('Олимпиада по программированию', { exact: true })).toBeVisible();
      await page.evaluate(() => globalThis.__u11ShowAwards());
      const banner = page.getByRole('button', { name: 'Новые награды: 2 — показать', exact: true });
      try { await expect(banner).toBeVisible({ timeout: 35_000 }); }
      catch (error) { console.log(await page.evaluate(() => ({ requests: globalThis.__u11Requests, visibility: document.visibilityState }))); throw error; }
      await expect(page.getByText(/Очень длинное название награждённой/)).toHaveCount(0);
      await banner.click();
      await expect(page.getByText(/Очень длинное название награждённой/).first()).toBeVisible();
      for (const theme of ['light', 'dark', 'contrast']) {
        await page.getByLabel('Тема интерфейса').selectOption(theme);
        for (const width of [360, 1280]) { await page.setViewportSize({ width, height: 800 }); await measure(page, 'long-awards', width, theme); }
      }
      await page.getByRole('link', { name: 'Группы', exact: true }).click();
      await page.getByRole('link', { name: 'ИВТ-21 · ФИТ', exact: true }).click();
      await expect(page.getByRole('heading', { name: /Рейтинг группы Очень длинное/ })).toBeVisible();
      for (const theme of ['light', 'dark', 'contrast']) {
        await page.getByLabel('Тема интерфейса').selectOption(theme);
        for (const width of [360, 1280]) { await page.setViewportSize({ width, height: 800 }); await measure(page, 'long-rating', width, theme); }
      }
    }
    await context.close();
  }
  expect(errors).toEqual([]);
  await writeFile(new URL('browser-result.json', root), JSON.stringify({ base: 'explicit MSW Vite', results, errors }, null, 2));
  console.log(JSON.stringify({ screenshots: results.length, errors: errors.length, overflow: results.filter((item) => item.overflow).length, smallTargets: results.flatMap((item) => item.small).length }));
} finally { await browser.close(); }

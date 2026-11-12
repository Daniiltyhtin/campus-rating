import { chromium, expect } from '../../../e2e/node_modules/@playwright/test/index.mjs';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const root = new URL('./', import.meta.url), baseURL = process.env.U13_WEB_URL ?? 'http://127.0.0.1:5191';
await mkdir(root, { recursive: true });
const browser = await chromium.launch(), results = [], errors = [];
async function capture(page, name, width, theme) {
  await expect(page.locator('html')).toHaveAttribute('data-theme', theme === 'contrast' ? 'high-contrast' : theme);
  await page.evaluate(() => document.fonts.ready);
  const metrics = await page.evaluate(() => {
    const scope = document.querySelector('[role="dialog"]') ?? document.querySelector('.directory-page');
    const visible = (element) => { const box = element.getBoundingClientRect(); return box.width > 0 && box.height > 0 && getComputedStyle(element).visibility !== 'hidden'; };
    const minimum = Number.parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--cr-touch-target'));
    const small = [...scope.querySelectorAll('button, input, select, a')].filter(visible).filter((element) => {
      const box = element.getBoundingClientRect(); return box.width + 0.5 < minimum || box.height + 0.5 < minimum;
    }).map((element) => ({ name: element.getAttribute('aria-label') ?? element.textContent, box: element.getBoundingClientRect().toJSON(), minimum }));
    const rgb = (value) => value.match(/[\d.]+/g)?.slice(0, 3).map(Number);
    const luminance = (color) => color.map((part) => { const value = part / 255; return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4; }).reduce((sum, part, index) => sum + part * [0.2126, 0.7152, 0.0722][index], 0);
    const ratios = [...scope.querySelectorAll('h1, h2, p, th, td, label, input, select, button')].filter(visible).filter((element) => element.textContent?.trim() || element.value).filter((element) => !element.disabled).map((element) => {
      let ancestor = element;
      while (ancestor.parentElement && ['transparent', 'rgba(0, 0, 0, 0)'].includes(getComputedStyle(ancestor).backgroundColor)) ancestor = ancestor.parentElement;
      const text = rgb(getComputedStyle(element).color), background = rgb(getComputedStyle(ancestor).backgroundColor);
      if (!text || !background) return 0;
      const values = [luminance(text), luminance(background)].sort((a, b) => b - a);
      return (values[0] + 0.05) / (values[1] + 0.05);
    });
    return { small, overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
      contrast: Math.min(...ratios), content: scope.textContent };
  });
  expect(metrics.small).toEqual([]); expect(metrics.overflow).toBe(false);
  expect(metrics.contrast).toBeGreaterThanOrEqual(theme === 'contrast' ? 7 : 4.5);
  const screenshot = `${name}-${width}-${theme}.png`;
  await page.screenshot({ path: fileURLToPath(new URL(screenshot, root)), fullPage: (await page.getByRole('dialog').count()) === 0 });
  results.push({ name, width, theme, screenshot, ...metrics });
}
try {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 }, reducedMotion: 'reduce' });
  const page = await context.newPage();
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto(`${baseURL}/login`);
  await page.getByLabel('Email', { exact: true }).fill('admin@campus.test');
  await page.getByLabel('Пароль', { exact: true }).fill('demo-admin-1');
  await page.getByRole('button', { name: 'Войти', exact: true }).click();
  await page.getByLabel('Текущий пароль', { exact: true }).fill('demo-admin-1');
  await page.getByLabel('Новый пароль', { exact: true }).fill('changed-admin-1');
  await page.getByRole('button', { name: 'Сменить пароль', exact: true }).click();
  await expect(page).toHaveURL(`${baseURL}/`);
  await page.getByRole('link', { name: 'Справочники', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Справочники', exact: true })).toBeVisible();
  const kinds = [
    { kind: 'faculties', tab: 'Факультеты', noun: 'факультет' },
    { kind: 'groups', tab: 'Учебные группы', noun: 'группу' },
    { kind: 'departments', tab: 'Кафедры', noun: 'кафедру' },
  ];
  for (const theme of ['light', 'dark', 'contrast']) {
    await page.getByLabel('Тема интерфейса').selectOption(theme);
    for (const width of [360, 1280]) {
      await page.setViewportSize({ width, height: 800 });
      for (const item of kinds) {
        await page.getByRole('tab', { name: item.tab, exact: true }).click();
        await expect(page.getByRole('table', { name: item.tab, exact: true })).toBeVisible();
        await capture(page, item.kind, width, theme);
        const add = page.getByRole('button', { name: `Добавить ${item.noun}`, exact: true });
        await add.click();
        const dialog = page.getByRole('dialog', { name: `Добавить ${item.noun}`, exact: true });
        await expect(dialog).toBeVisible();
        await page.keyboard.press('Tab');
        expect(await dialog.evaluate((element) => element.contains(document.activeElement))).toBe(true);
        const unique = `${item.kind}-${theme}-${width}`, name = 'Длинное название новой записи '.repeat(5).trim();
        await dialog.getByLabel('Код', { exact: true }).fill(unique);
        await dialog.getByLabel('Название', { exact: true }).fill(name);
        if (item.kind !== 'faculties') await dialog.getByLabel('Факультет', { exact: true }).selectOption('1');
        if (item.kind === 'groups') await dialog.getByLabel('Год набора', { exact: true }).fill('2026');
        await capture(page, `${item.kind}-form`, width, theme);
        await dialog.getByRole('button', { name: 'Сохранить', exact: true }).click();
        await expect(dialog).toHaveCount(0);
        await expect(page.getByRole('status')).toHaveText('Запись сохранена');
        await page.getByRole('button', { name: `Редактировать ${name}`, exact: true }).click();
        const edit = page.getByRole('dialog', { name: `Редактировать ${item.noun}`, exact: true });
        await expect(edit.getByLabel('Код', { exact: true })).toHaveValue(unique);
        await edit.getByLabel('Название', { exact: true }).fill('Изменённая запись');
        await edit.getByRole('button', { name: 'Сохранить', exact: true }).click();
        await expect(page.getByRole('button', { name: 'Удалить Изменённая запись', exact: true })).toBeVisible();
        await page.getByRole('button', { name: 'Удалить Изменённая запись', exact: true }).click();
        const removal = page.getByRole('dialog', { name: `Удалить ${item.noun}`, exact: true });
        await removal.getByRole('button', { name: 'Удалить', exact: true }).click();
        await expect(removal).toHaveCount(0);
        await expect(page.getByRole('button', { name: 'Удалить Изменённая запись', exact: true })).toHaveCount(0);
      }
      await page.getByRole('tab', { name: 'Учебные группы', exact: true }).click();
      await page.getByRole('button', { name: 'Удалить ИВТ-21', exact: true }).click();
      const removal = page.getByRole('dialog', { name: 'Удалить группу', exact: true });
      await removal.getByRole('button', { name: 'Удалить', exact: true }).click();
      await expect(removal.getByRole('alert')).toContainText('Запись используется');
      await capture(page, 'occupied-group', width, theme);
      await removal.getByRole('button', { name: 'Отмена', exact: true }).click();
      await page.getByRole('tab', { name: 'Факультеты', exact: true }).click();
      await page.getByRole('button', { name: 'Добавить факультет', exact: true }).click();
      const dialog = page.getByRole('dialog', { name: 'Добавить факультет', exact: true });
      await dialog.getByRole('button', { name: 'Сохранить', exact: true }).click();
      await expect(dialog.getByLabel('Код', { exact: true })).toHaveAttribute('aria-invalid', 'true');
      await capture(page, 'validation', width, theme);
      await page.keyboard.press('Escape');
      await expect(dialog).toHaveCount(0);
      await expect(page.getByRole('button', { name: 'Добавить факультет', exact: true })).toBeFocused();
    }
  }
  expect(errors).toEqual([]);
  await writeFile(new URL('browser-result.json', root), JSON.stringify({ mode: 'explicit MSW', results, errors }, null, 2));
  console.log(JSON.stringify({ screenshots: results.length, errors: errors.length, minContrast: Math.min(...results.map((item) => item.contrast)), overflow: results.filter((item) => item.overflow).length, smallTargets: results.flatMap((item) => item.small).length }));
  await context.close();
} finally { await browser.close(); }

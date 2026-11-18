import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../../..');
const { chromium } = createRequire(path.join(root, 'e2e/package.json'))('@playwright/test');
const deck = pathToFileURL(path.join(root, 'frontend/public/presentation/index.html')).href;
const output = path.join(here, 'campus-rating.pdf');

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
await page.goto(`${deck}?export#s1`, { waitUntil: 'networkidle' });
await page.evaluate(() => document.fonts.ready);
const count = await page.locator('.slide').count();
const shots = [];
for (let i = 1; i <= count; i++) {
  await page.evaluate(n => { location.hash = `#s${n}`; }, i);
  await page.locator('.slide.active').nth(0).waitFor();
  await page.waitForTimeout(200);
  shots.push((await page.screenshot({ type: 'png' })).toString('base64'));
}
const sheet = await browser.newPage();
await sheet.setContent(`<!doctype html><style>@page{size:1920px 1080px;margin:0}body{margin:0}img{display:block;width:1920px;height:1080px;break-after:page}</style>${shots.map(s => `<img src="data:image/png;base64,${s}">`).join('')}`);
await sheet.pdf({ path: output, width: '1920px', height: '1080px', printBackground: true });
await browser.close();
console.log(`PDF: ${path.relative(root, output)}, слайдов: ${count}`);

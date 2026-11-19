import { createRequire } from 'node:module';
import { execFileSync, spawnSync } from 'node:child_process';
import { cp, mkdir, mkdtemp, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../../..');
const { chromium } = createRequire(path.join(root, 'e2e/package.json'))('@playwright/test');
const base = process.env.DEMO_BASE_URL ?? 'http://127.0.0.1:8080';
const prefix = process.env.DEMO_PASSWORD_PREFIX ?? 'demo';
const output = path.join(root, 'frontend/public/presentation/demo');
const work = await mkdtemp(path.join(tmpdir(), 'campus-demo-'));
const staged = path.join(work, 'clips');

async function record() {
  const browser = await chromium.launch();
  try {
    const context = await browser.newContext({ viewport: { width: 1920, height: 1080 }, recordVideo: { dir: work, size: { width: 1920, height: 1080 } } });
    const page = await context.newPage();
    const started = Date.now();
    const seconds = () => (Date.now() - started) / 1000;
    page.on('console', (message) => { if (message.text().startsWith('[demo]')) console.error(message.text()); });
    await page.goto(`${base}/presentation?live#s11`);
    await page.waitForTimeout(1000);
    await page.evaluate(() => {
      const mark = document.createElement('div');
      mark.id = 'record-mark';
      mark.style.cssText = 'position:fixed;inset:0;background:#000;z-index:2000';
      document.body.append(mark);
    });
    const marked = seconds();
    await page.waitForTimeout(800);
    await page.evaluate(() => document.getElementById('record-mark').remove());
    await page.waitForTimeout(1500);
    await page.fill('[data-demo-prefix]', prefix);
    await page.locator('[data-demo-intro]').click({ position: { x: 20, y: 1000 } });
    const cuts = [];
    for (let step = 1; step <= 7; step++) {
      await page.evaluate((n) => { location.hash = `#s${11 + n}`; }, step);
      const slide = page.locator(`[data-demo-step="${step}"]`);
      await slide.and(page.locator('.active')).waitFor();
      await page.keyboard.press('ArrowRight');
      await page.waitForFunction((n) => document.querySelector(`[data-demo-step="${n}"]`).dataset.demoState === 'running', step, { timeout: 200_000, polling: 50 });
      const from = seconds();
      await page.waitForFunction((n) => ['done', 'failed'].includes(document.querySelector(`[data-demo-step="${n}"]`).dataset.demoState), step, { timeout: 200_000, polling: 50 });
      if (await slide.getAttribute('data-demo-state') !== 'done') throw new Error(`Шаг ${step} не выполнен`);
      await page.waitForTimeout(1500);
      cuts.push({ step, from: Math.max(0, from - 0.3), to: seconds() });
      console.log(`шаг ${step}: ${(cuts.at(-1).to - cuts.at(-1).from).toFixed(1)} с`);
    }
    const box = await page.locator('iframe').boundingBox();
    await context.close();
    return { cuts, box, marked };
  } finally {
    await browser.close();
  }
}

function cutClips({ cuts, box, marked }, video) {
  const black = spawnSync('ffmpeg', ['-hide_banner', '-i', video, '-vf', 'blackdetect=d=0.4:pix_th=0.05', '-an', '-f', 'null', '-'], { encoding: 'utf8' });
  const found = black.stderr.match(/black_start:([\d.]+)/);
  if (!found) throw new Error('Метка калибровки не найдена в видео');
  const shift = Number(found[1]) - marked;
  const even = (value) => Math.floor(value / 2) * 2;
  const crop = `crop=${even(box.width)}:${even(box.height)}:${Math.floor(box.x)}:${Math.floor(box.y)}`;
  for (const { step, from, to } of cuts) {
    const clip = path.join(staged, `step-${step}.mp4`);
    execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-ss', (from + shift).toFixed(2), '-to', (to + shift).toFixed(2), '-i', video,
      '-vf', crop, '-an', '-c:v', 'libx264', '-preset', 'slow', '-crf', '26', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', clip]);
    execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-i', clip, '-frames:v', '1', '-q:v', '3', path.join(staged, `step-${step}.start.jpg`)]);
    execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-sseof', '-0.2', '-i', clip, '-frames:v', '1', '-q:v', '3', path.join(staged, `step-${step}.end.jpg`)]);
  }
}

try {
  const result = await record();
  const video = (await readdir(work)).find((file) => file.endsWith('.webm'));
  if (!video) throw new Error('Видео записи не найдено');
  await mkdir(staged);
  cutClips(result, path.join(work, video));
  await rm(output, { recursive: true, force: true });
  await cp(staged, output, { recursive: true });
  console.log(`Записи: ${path.relative(root, output)}`);
} finally {
  await rm(work, { recursive: true, force: true });
}

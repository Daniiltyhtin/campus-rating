import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { main } from '../agent-stats.mjs';

const fixtures = new URL('./fixtures/agent-stats/', import.meta.url);
const claude = await fs.readFile(new URL('claude.json', fixtures), 'utf8');
const codex = await fs.readFile(new URL('codex.json', fixtures), 'utf8');

async function setup(t) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'agent-stats-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  await fs.mkdir(path.join(root, 'docs'), { recursive: true });
  await fs.copyFile(new URL('agent-log.md', fixtures), path.join(root, 'docs/agent-log.md'));
  const calls = [];
  const messages = [];
  const options = {
    root,
    platform: 'linux',
    run(command, args, settings) {
      calls.push({ command, args, settings });
      return { status: 0, stdout: args.includes('claude') ? claude : codex, stderr: '' };
    },
    log: text => messages.push(text),
    error: text => messages.push(text),
  };
  return {
    options, calls, messages,
    exported: async name => JSON.parse(await fs.readFile(path.join(root, `docs/presentation/agent-stats/${name}.json`), 'utf8')),
    report: () => fs.readFile(path.join(root, 'docs/presentation/agent-stats.md'), 'utf8'),
  };
}

test('KTD9 export normalizes both daily formats and invokes pinned ccusage with project and since', async t => {
  const { options, calls, exported } = await setup(t);
  assert.equal(await main(['--export', 'Алиса', '--since', '20261029', '--claude-project', 'campus'], options), 0);
  assert.deepEqual(calls.map(({ command, args, settings }) => [command, args, settings.shell]), [
    ['npx', ['-y', 'ccusage@20.0.46', 'claude', 'daily', '--json', '--since', '20261029', '--project', 'campus'], false],
    ['npx', ['-y', 'ccusage@20.0.46', 'codex', 'daily', '--json', '--since', '20261029'], false],
  ]);
  const result = await exported('Алиса');
  assert.equal(result.participant, 'Алиса');
  assert.deepEqual(result.period, { since: '20261029', through: '2026-10-31' });
  assert.equal(result.sources.claude.coverage, 'проект campus');
  assert.equal(result.sources.codex.coverage, 'вся машина');
  assert.equal(result.sources.claude.status, 'ok');
  assert.deepEqual(result.sources.claude.daily, [
    { date: '2026-10-30', tokens: { input: 12, output: 30, cache: 58, total: 100 }, costUSD: 1.25, models: ['claude-opus-5-5', 'claude-sonnet-5-5'] },
    { date: '2026-10-31', tokens: { input: 2, output: 3, cache: 5, total: 10 }, costUSD: 0.5, models: ['claude-sonnet-5-5'] },
  ]);
  assert.deepEqual(result.sources.codex.daily, [
    { date: '2026-10-30', tokens: { input: 20, output: 10, cache: 170, total: 200 }, costUSD: 2.5, models: ['gpt-5.6-luna', 'gpt-6.1-sol'] },
  ]);
});

test('R3/KTD9 defaults to hackathon start and machine coverage; Windows invokes npx via cmd.exe without shell', async t => {
  const { options, calls, exported } = await setup(t);
  assert.equal(await main(['--export', 'Боб'], { ...options, platform: 'win32' }), 0);
  assert.deepEqual(calls.map(({ command, args, settings }) => [command, args, settings.shell]), [
    ['cmd.exe', ['/d', '/s', '/c', 'npx', '-y', 'ccusage@20.0.46', 'claude', 'daily', '--json', '--since', '20261022'], false],
    ['cmd.exe', ['/d', '/s', '/c', 'npx', '-y', 'ccusage@20.0.46', 'codex', 'daily', '--json', '--since', '20261022'], false],
  ]);
  const result = await exported('Боб');
  assert.equal(result.sources.claude.coverage, 'вся машина');
  assert.equal(result.sources.claude.daily[0].tokens.total, 100);
  assert.equal(result.sources.codex.daily[0].tokens.total, 200);
});

test('KTD9 merge sums two participants, counts journal entries and review rounds, and is repeatable', async t => {
  const { options, report } = await setup(t);
  await main(['--export', 'Алиса', '--claude-project', 'campus'], options);
  await main(['--export', 'Боб'], options);
  assert.equal(await main(['--merge'], options), 0);
  const text = await report();
  assert.match(text, /\| Алиса \| claude \| 14 \| 33 \| 63 \| 110 \| 1\.750000 \| проект campus \|/);
  assert.match(text, /\| Боб \| codex \| 20 \| 10 \| 170 \| 200 \| 2\.500000 \| вся машина \|/);
  assert.match(text, /\| \*\*Итого\*\* \| — \| 68 \| 86 \| 466 \| 620 \| 8\.500000 \|/);
  assert.match(text, /Записей журнала: 5/);
  assert.match(text, /Кругов ревью: 5/);
  await main(['--export', 'Алиса', '--claude-project', 'campus'], options);
  assert.equal(await main(['--merge'], options), 0);
  assert.equal(await report(), text);
});

test('KTD9 merge includes daily rows by date, participant and source alongside totals', async t => {
  const { options, report } = await setup(t);
  await main(['--export', 'Боб'], options);
  await main(['--export', 'Алиса'], options);
  assert.equal(await main(['--merge'], options), 0);
  const text = await report();
  assert.match(text, /\| Дата \| Участник \| Источник \| Всего токенов \| Стоимость, USD \|/);
  assert.deepEqual(text.split('\n').filter(line => /^\| \d{4}-/.test(line)), [
    '| 2026-10-30 | Алиса | claude | 100 | 1.250000 |',
    '| 2026-10-30 | Алиса | codex | 200 | 2.500000 |',
    '| 2026-10-30 | Боб | claude | 100 | 1.250000 |',
    '| 2026-10-30 | Боб | codex | 200 | 2.500000 |',
    '| 2026-10-31 | Алиса | claude | 10 | 0.500000 |',
    '| 2026-10-31 | Боб | claude | 10 | 0.500000 |',
  ]);
  assert.match(text, /\*\*Итого\*\*.*620 \| 8\.500000/);
  assert.doesNotMatch(text, /сесси/i);
});

test('KTD9 journal distinguishes accepted units from research, missing commits and templates', async t => {
  const { options, report } = await setup(t);
  await main(['--export', 'Алиса'], options);
  assert.equal(await main(['--merge'], options), 0);
  const text = await report();
  assert.match(text, /Принятых юнитов: 1\b/);
  assert.match(text, /Записей журнала: 5\b/);
  assert.match(text, /Кругов ревью: 5\b/);
});

for (const [name, failed, reason] of [
  ['unavailable', { error: new Error('npx unavailable'), status: null }, /npx unavailable/],
  ['nonzero exit', { status: 1, stdout: '', stderr: 'ccusage failed' }, /ccusage failed/],
  ['empty output', { status: 0, stdout: '', stderr: '' }, /пустой/i],
  ['invalid JSON', { status: 0, stdout: '{', stderr: '' }, /JSON/i],
  ['invalid shape', { status: 0, stdout: '{}', stderr: '' }, /daily/i],
  ['invalid token data', { status: 0, stdout: '{"daily":[{"date":"2026-10-30","inputTokens":-1,"modelsUsed":[]}]}', stderr: '' }, /inputTokens/],
]) {
  test(`KTD9 ${name} marks only failed source as no data and returns zero`, async t => {
    const { options, exported, report, messages } = await setup(t);
    const run = options.run;
    options.run = (command, args, settings) => args[2] === 'claude' ? failed : run(command, args, settings);
    assert.equal(await main(['--export', 'Алиса'], options), 0);
    const result = await exported('Алиса');
    assert.equal(result.sources.claude.status, 'нет данных');
    assert.match(result.sources.claude.reason, reason);
    assert.equal(result.sources.codex.status, 'ok');
    assert.match(messages.join('\n'), /claude.*нет данных/);
    assert.equal(await main(['--merge'], options), 0);
    assert.match(await report(), /нет данных/);
    assert.match(await report(), /\*\*Итого\*\*.*200 \| 2\.500000/);
  });
}

test('KTD9 empty daily is a successful zero export', async t => {
  const { options, exported } = await setup(t);
  options.run = () => ({ status: 0, stdout: '{"daily":[]}', stderr: '' });
  assert.equal(await main(['--export', 'Алиса'], options), 0);
  const result = await exported('Алиса');
  assert.deepEqual(result.sources.claude.daily, []);
  assert.equal(result.sources.claude.status, 'ok');
  assert.equal(result.period.through, null);
});

test('KTD9 changed export replaces participant data and preserves the other participant file', async t => {
  const { options, exported, report } = await setup(t);
  await main(['--export', 'Алиса'], options);
  await main(['--export', 'Боб'], options);
  const bob = await exported('Боб');
  const run = options.run;
  options.run = (command, args, settings) => args[2] === 'claude'
    ? { status: 0, stdout: '{"daily":[]}', stderr: '' }
    : run(command, args, settings);
  await main(['--export', 'Алиса'], options);
  assert.deepEqual(await exported('Боб'), bob);
  assert.deepEqual((await exported('Алиса')).sources.claude.daily, []);
  assert.equal(await main(['--merge'], options), 0);
  assert.match(await report(), /\*\*Итого\*\*.*510 \| 6\.750000/);
});

test('KTD9 invalid CLI input cannot escape participant directory or launch ccusage', async t => {
  const { options, calls } = await setup(t);
  for (const args of [[], ['--export', '../escape'], ['--export', 'a/b'], ['--export', 'a\\b'], ['--merge', '--export', 'a'], ['--export', 'a', '--since', '20260230'], ['--export', 'a', '--unknown']]) {
    assert.equal(await main(args, options), 1);
  }
  assert.equal(calls.length, 0);
});

test('KTD9 merge without participant exports reports zero and still counts the journal', async t => {
  const { options, report } = await setup(t);
  await fs.mkdir(path.join(options.root, 'docs/presentation/agent-stats'), { recursive: true });
  await fs.writeFile(path.join(options.root, 'docs/presentation/agent-stats/.gitkeep'), '');
  assert.equal(await main(['--merge'], options), 0);
  assert.match(await report(), /\*\*Итого\*\* \| — \| 0 \| 0 \| 0 \| 0 \| 0\.000000/);
  assert.match(await report(), /Записей журнала: 5/);
});

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
  assert.deepEqual(result.period, { since: '2026-10-29', through: '2026-10-31' });
  assert.equal(result.sources.claude.coverage, 'один проект Claude');
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
  assert.match(text, /\| Алиса \| claude \| 14 \| 33 \| 63 \| 110 \| 1\.750000 \| один проект Claude \|/);
  assert.match(text, /\| Боб \| codex \| 20 \| 10 \| 170 \| 200 \| 2\.500000 \| вся машина; не входит в итог для слайда \|/);
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
  assert.match(text, /## Для слайда/);
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

async function jsonl(file, records) {
  await fs.mkdir(path.dirname(file), { recursive: true });
  await fs.writeFile(file, records.map(record => typeof record === 'string' ? record : JSON.stringify(record)).join('\n') + '\n');
}

function rollout(id, cwd, timestamp = '2026-11-10T12:00:00Z', usage = { input_tokens: 711187, cached_input_tokens: 672768, output_tokens: 4463 }) {
  return [
    { type: 'session_meta', timestamp, payload: { id, cwd } },
    { type: 'event_msg', payload: { type: 'token_count', info: { total_token_usage: { input_tokens: 1, cached_input_tokens: 0, output_tokens: 0 } } } },
    '{broken',
    { type: 'event_msg', payload: { type: 'token_count', info: { total_token_usage: usage } } },
  ];
}

test('Codex cwd includes root and descendants, excludes prefix siblings, skips broken and empty sessions, and deduplicates ids', async t => {
  const { options, exported, calls } = await setup(t);
  const home = path.join(options.root, 'codex');
  const cwd = path.join(options.root, 'campus-rating');
  const other = path.join(options.root, 'second-project');
  await jsonl(path.join(home, 'sessions/a/rollout-root.jsonl'), rollout('root', cwd));
  await jsonl(path.join(home, 'sessions/b/rollout-copy.jsonl'), rollout('root', cwd));
  await jsonl(path.join(home, 'sessions/b/rollout-child.jsonl'), rollout('child', path.join(cwd, 'backend')));
  await jsonl(path.join(home, 'sessions/rollout-other.jsonl'), rollout('other', other));
  await jsonl(path.join(home, 'sessions/rollout-training.jsonl'), rollout('training', `${cwd}-training`));
  await jsonl(path.join(home, 'sessions/rollout-old.jsonl'), rollout('old', cwd, '2026-10-21T12:00:00Z'));
  await jsonl(path.join(home, 'sessions/rollout-empty.jsonl'), [rollout('empty', cwd)[0], 'bad json']);
  assert.equal(await main(['--export', 'Даниил', '--codex-home', home, '--codex-cwd', cwd, '--codex-cwd', other], options), 0);
  const result = await exported('Даниил');
  const stats = result.sources.codex;
  assert.equal(stats.ioTokens, 42882 * 3);
  assert.equal(stats.withCacheTokens, 715650 * 3);
  assert.deepEqual(stats.daily[0].tokens, { input: 38419 * 3, output: 4463 * 3, cache: 672768 * 3, total: 715650 * 3 });
  assert.equal(stats.costUSD, undefined);
  assert.equal(stats.daily[0].costUSD, null);
  assert.equal(calls.some(call => call.args.includes('codex')), false);
  assert.doesNotMatch(JSON.stringify(result), new RegExp(options.root));
});

function claudeMessage(sessionId, id, requestId, input = 10, timestamp = '2026-11-09T12:00:00Z') {
  return { type: 'assistant', sessionId, requestId, timestamp, message: { id, model: 'claude-test', usage: { input_tokens: input, output_tokens: 2, cache_creation_input_tokens: 3, cache_read_input_tokens: 4 } } };
}

test('Claude multiple config directories and exact project names deduplicate sessions and message/request pairs', async t => {
  const { options, calls, exported } = await setup(t);
  const configs = [path.join(options.root, 'claude-a'), path.join(options.root, 'claude-b')];
  const records = [claudeMessage('s1', 'm1', 'r1'), claudeMessage('s1', 'm1', 'r1'), claudeMessage('s1', 'm1', 'r2')];
  for (const config of configs) await jsonl(path.join(config, 'projects/campus/session.jsonl'), records);
  await jsonl(path.join(configs[1], 'projects/campus-training/ignored.jsonl'), [claudeMessage('ignored', 'm2', 'r2', 10000)]);
  await jsonl(path.join(configs[1], 'projects/second/session.jsonl'), [claudeMessage('s2', 'm2', 'r1', 20)]);
  await jsonl(path.join(configs[0], 'projects/campus/old.jsonl'), [claudeMessage('old', 'old', 'old', 999, '2026-10-21T12:00:00Z'), 'broken']);
  assert.equal(await main(['--export', 'Алиса', '--claude-config-dir', configs[0], '--claude-config-dir', configs[1], '--claude-project', 'campus', '--claude-project', 'second'], options), 0);
  const stats = (await exported('Алиса')).sources.claude;
  assert.equal(stats.ioTokens, 46);
  assert.equal(stats.withCacheTokens, 67);
  assert.deepEqual(stats.daily[0].tokens, { input: 40, output: 6, cache: 21, total: 67 });
  const claudeCalls = calls.filter(call => call.args.includes('claude'));
  assert.equal(claudeCalls.length, 2);
  assert.ok(claudeCalls.every(call => call.settings.env.CLAUDE_CONFIG_DIR === configs.join(',')));
  assert.deepEqual(claudeCalls.map(call => call.args.at(-1)), ['campus', 'second']);
  assert.doesNotMatch(JSON.stringify(await exported('Алиса')), new RegExp(options.root));
});

for (const [tokens, millions] of [[1499999, 1], [1500000, 2]]) {
  test(`Slide rounds combined ${tokens} to ${millions} million, uses min/max dates, excludes machine-wide Codex`, async t => {
    const { options, report } = await setup(t);
    const dir = path.join(options.root, 'docs/presentation/agent-stats');
    await fs.mkdir(dir, { recursive: true });
    const day = (date, input, cache) => ({ date, tokens: { input, output: 0, cache, total: input + cache }, costUSD: 1, models: [] });
    for (const [participant, date, input] of [['Максим', '2026-11-12', 100], ['Даниил', '2026-10-29', tokens - 100]]) {
      await fs.writeFile(path.join(dir, `${participant}.json`), JSON.stringify({
        participant, period: { since: '2026-10-22', through: date }, sources: {
          claude: { status: 'ok', coverage: 'один проект Claude', daily: [day(date, input, 500000)] },
          codex: { status: 'ok', coverage: 'вся машина', daily: [day('2026-11-19', 999999999, 999999999)] },
        },
      }));
    }
    assert.equal(await main(['--merge'], options), 0);
    const text = await report();
    const slide = text.split('## Для слайда\n')[1]?.split('\n\n|')[0];
    assert.ok(slide);
    assert.match(slide, new RegExp(`Без кэша: ${millions} млн токенов`));
    assert.match(slide, new RegExp(`С учётом кэша: ${millions + 1} млн токенов`));
    assert.match(slide, /Нижняя оценка: сессии в папке проекта у двух участников, 29\.10–12\.11\.2026/);
    assert.doesNotMatch(slide, /USD|\$|стоимость/i);
    assert.match(text, /Максим \| codex .*вся машина; не входит в итог для слайда/);
    assert.match(text, /Стоимость, USD/);
  });
}

test('Codex single session exposes exact slide sums and uses session_meta date', async t => {
  const { options, exported, report } = await setup(t);
  const home = path.join(options.root, 'codex');
  const lines = rollout('single', options.root, '2026-11-01T12:00:00Z');
  lines.at(-1).timestamp = '2026-11-19T12:00:00Z';
  await jsonl(path.join(home, 'sessions/rollout-single.jsonl'), ['null', ...lines]);
  assert.equal(await main(['--export', 'Даниил', '--codex-home', home, '--codex-cwd', options.root], options), 0);
  const stats = (await exported('Даниил')).sources.codex;
  assert.equal(stats.ioTokens, 42882);
  assert.equal(stats.withCacheTokens, 715650);
  assert.equal(stats.daily[0].date, '2026-11-01');
  assert.equal(await main(['--merge'], options), 0);
  assert.match(await report(), /С учётом кэша: 1 млн токенов/);
  assert.match(await report(), /01\.11–01\.11\.2026/);
});

test('Codex token_count without usage after a valid one keeps the last valid total', async t => {
  const { options, exported } = await setup(t);
  const home = path.join(options.root, 'codex');
  await jsonl(path.join(home, 'sessions/rollout-tail.jsonl'), [
    ...rollout('tail', options.root),
    { type: 'event_msg', payload: { type: 'token_count', info: null } },
    { type: 'event_msg', payload: { type: 'token_count', info: { last_token_usage: { input_tokens: 5 } } } },
  ]);
  assert.equal(await main(['--export', 'Даниил', '--codex-home', home, '--codex-cwd', options.root], options), 0);
  const stats = (await exported('Даниил')).sources.codex;
  assert.equal(stats.ioTokens, 42882);
  assert.equal(stats.withCacheTokens, 715650);
});

test('Claude exact single project uses joined config roots and keeps matching ccusage cost', async t => {
  const { options, calls, exported } = await setup(t);
  const config = path.join(options.root, 'claude');
  await jsonl(path.join(config, 'projects/campus/session.jsonl'), [claudeMessage('s', 'm', 'r')]);
  await jsonl(path.join(config, 'projects/campus-training/session.jsonl'), [claudeMessage('x', 'x', 'x', 1000)]);
  const run = options.run;
  options.run = (command, args, settings) => {
    const result = run(command, args, settings);
    if (args.includes('claude')) result.stdout = JSON.stringify({ daily: [{ date: '2026-11-09', inputTokens: 10, outputTokens: 2, cacheCreationTokens: 3, cacheReadTokens: 4, totalTokens: 19, totalCost: 0.25, modelsUsed: ['claude-test'] }] });
    return result;
  };
  assert.equal(await main(['--export', 'Алиса', '--claude-config-dir', config, '--claude-project', 'campus'], options), 0);
  const stats = (await exported('Алиса')).sources.claude;
  assert.equal(stats.ioTokens, 12);
  assert.equal(stats.withCacheTokens, 19);
  assert.equal(stats.daily[0].costUSD, 0.25);
  assert.equal(calls[0].settings.env.CLAUDE_CONFIG_DIR, config);
  assert.deepEqual(calls[0].args.slice(-2), ['--project', 'campus']);
});

test('Claude tokens survive ccusage failure and no filesystem paths enter exports or reports', async t => {
  const { options, exported, report } = await setup(t);
  const config = path.join(options.root, 'claude');
  await jsonl(path.join(config, 'projects/campus/session.jsonl'), [claudeMessage('s', 'm', 'r')]);
  options.run = () => ({ error: new Error(`Cannot open '${config}/private.jsonl'`), status: null });
  assert.equal(await main(['--export', 'Алиса', '--claude-config-dir', config, '--claude-project', 'campus'], options), 0);
  const data = await exported('Алиса');
  assert.equal(data.sources.claude.withCacheTokens, 19);
  assert.equal(data.sources.claude.daily[0].costUSD, null);
  assert.doesNotMatch(JSON.stringify(data), new RegExp(options.root));
  assert.equal(await main(['--merge'], options), 0);
  assert.doesNotMatch(await report(), new RegExp(options.root));
});

test('Claude project folder beginning with a dash is passed as an attached ccusage value', async t => {
  const { options, calls } = await setup(t);
  assert.equal(await main(['--export', 'Алиса', '--claude-project', '-encoded-project'], options), 0);
  assert.ok(calls[0].args.includes('--project=-encoded-project'));
});

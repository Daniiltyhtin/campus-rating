import * as fs from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const ccusage = 'ccusage@20.0.46';
const sources = ['claude', 'codex'];
const usage = 'Usage: --export <имя> [--since YYYYMMDD] [--claude-project <проект>] | --merge';

function repositoryRoot() {
  let directory = path.dirname(fileURLToPath(import.meta.url));
  while (!existsSync(path.join(directory, '.git'))) {
    const parent = path.dirname(directory);
    if (parent === directory) throw new Error('Cannot locate git root');
    directory = parent;
  }
  return directory;
}

function validDate(value) {
  return /^\d{4}-\d{2}-\d{2}$/.test(value)
    && !Number.isNaN(Date.parse(value))
    && new Date(value).toISOString().slice(0, 10) === value;
}

function parseArguments(args) {
  if (args.length === 1 && args[0] === '--merge') return { merge: true };
  const values = new Map();
  for (let index = 0; index < args.length; index += 2) {
    const key = args[index];
    const value = args[index + 1];
    if (!['--export', '--since', '--claude-project'].includes(key)
      || values.has(key) || !value?.trim() || value.startsWith('--')) throw new Error(usage);
    values.set(key, value);
  }
  const participant = values.get('--export');
  if (!participant || /[<>:"/\\|?*\x00-\x1f]/.test(participant)
    || /[. ]$/.test(participant) || /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(participant)) {
    throw new Error('Некорректное имя участника. ' + usage);
  }
  const since = values.get('--since') ?? '20261022';
  const date = since.replace(/^(\d{4})(\d{2})(\d{2})$/, '$1-$2-$3');
  if (!/^\d{8}$/.test(since) || !validDate(date)) throw new Error('Некорректная дата --since: YYYYMMDD');
  return { participant, since, project: values.get('--claude-project') };
}

function number(value, field) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) {
    throw new Error(`Некорректное поле ${field}`);
  }
  return value;
}

function normalizeDay(day, source) {
  if (!day || !validDate(day.date)) throw new Error('Некорректное поле date');
  const models = source === 'claude' ? day.modelsUsed : Object.keys(day.models ?? {});
  if (!Array.isArray(models) || models.some(model => typeof model !== 'string')) {
    throw new Error('Некорректное поле models');
  }
  return {
    date: day.date,
    tokens: {
      input: number(day.inputTokens, 'inputTokens'),
      output: number(day.outputTokens, 'outputTokens'),
      cache: number(day.cacheCreationTokens, 'cacheCreationTokens') + number(day.cacheReadTokens, 'cacheReadTokens'),
      total: number(day.totalTokens, 'totalTokens'),
    },
    costUSD: number(source === 'claude' ? day.totalCost : day.costUSD, 'costUSD'),
    models,
  };
}

function collectSource(source, config, { root, run, platform, log }) {
  const coverage = source === 'claude' && config.project ? `проект ${config.project}` : 'вся машина';
  const args = ['-y', ccusage, source, 'daily', '--json', '--since', config.since];
  if (source === 'claude' && config.project) args.push('--project', config.project);
  try {
    const command = platform === 'win32' ? 'cmd.exe' : 'npx';
    const commandArgs = platform === 'win32' ? ['/d', '/s', '/c', 'npx', ...args] : args;
    const result = run(command, commandArgs, {
      cwd: root, encoding: 'utf8', shell: false, timeout: 120_000, maxBuffer: 32 * 1024 * 1024,
    });
    if (result.error) throw result.error;
    if (result.status !== 0) throw new Error(result.stderr?.trim() || `ccusage: exit ${result.status}`);
    if (!result.stdout?.trim()) throw new Error('Пустой вывод ccusage');
    let parsed;
    try { parsed = JSON.parse(result.stdout); }
    catch { throw new Error('Некорректный JSON ccusage'); }
    if (!Array.isArray(parsed?.daily)) throw new Error('В JSON ccusage отсутствует массив daily');
    const daily = parsed.daily.map(day => normalizeDay(day, source)).sort((a, b) => a.date.localeCompare(b.date));
    return { status: 'ok', coverage, daily };
  } catch (error) {
    const reason = error.message;
    log(`${source}: нет данных — ${reason}`);
    return { status: 'нет данных', coverage, reason };
  }
}

async function exportParticipant(config, options) {
  const collected = Object.fromEntries(sources.map(source => [source, collectSource(source, config, options)]));
  const dates = Object.values(collected).flatMap(source => (source.daily ?? []).map(day => day.date)).sort();
  const data = {
    participant: config.participant,
    period: { since: config.since, through: dates.at(-1) ?? null },
    sources: collected,
  };
  const directory = path.join(options.root, 'docs/presentation/agent-stats');
  await fs.mkdir(directory, { recursive: true });
  await fs.writeFile(path.join(directory, `${config.participant}.json`), JSON.stringify(data, null, 2) + '\n');
  options.log(`Экспорт: docs/presentation/agent-stats/${config.participant}.json`);
}

function journalStats(text) {
  const lines = text.replace(/\r\n/g, '\n').split('\n');
  let fence;
  let entries = 0;
  let accepted = 0;
  let rounds = 0;
  let entry;
  const finishEntry = () => {
    if (entry?.unit && entry.committed) accepted += 1;
  };
  for (const line of lines) {
    const marker = line.match(/^\s*(`{3,}|~{3,})/);
    if (marker) {
      if (!fence) fence = marker[1];
      else if (marker[1][0] === fence[0] && marker[1].length >= fence.length) fence = undefined;
      continue;
    }
    if (fence) continue;
    if (/^#{2,3}\s/.test(line)) {
      finishEntry();
      entry = /^#{2,3}\s+\d{4}-\d{2}-\d{2}\b/.test(line)
        ? { unit: /\bU\d+\b/.test(line), committed: false } : undefined;
      if (entry) entries += 1;
    }
    const match = line.match(/^\s*- \*\*Кругов ревью:\*\*\s+(\d+)(?:\s*\(.*\))?\s*$/);
    if (entry && match) rounds += Number(match[1]);
    const commit = line.match(/^\s*- \*\*Коммит \/ PR:\*\*\s*(.*?)\s*$/);
    if (entry && commit && commit[1] && commit[1] !== '—') entry.committed = true;
  }
  finishEntry();
  return { entries, accepted, rounds };
}

const emptyTotal = () => ({ input: 0, output: 0, cache: 0, total: 0, costUSD: 0 });

function totalDays(daily) {
  if (!Array.isArray(daily)) throw new Error('Некорректный экспорт: daily');
  return daily.reduce((sum, day) => {
    for (const key of ['input', 'output', 'cache', 'total']) sum[key] += number(day.tokens?.[key], key);
    sum.costUSD += number(day.costUSD, 'costUSD');
    return sum;
  }, emptyTotal());
}

const cell = value => String(value).replace(/\|/g, '\\|').replace(/[\r\n]/g, ' ');
const totalCells = total => `${total.input} | ${total.output} | ${total.cache} | ${total.total} | ${total.costUSD.toFixed(6)}`;

async function merge(options) {
  const directory = path.join(options.root, 'docs/presentation/agent-stats');
  const files = (await fs.readdir(directory)).filter(file => file.endsWith('.json')).sort();
  const rows = [
    '# Статистика агентов', '',
    'Стоимость — оценка ccusage в USD. Cache включает создание и чтение кэша; total берётся из ccusage.',
    'Codex охватывает всю машину: фильтра по проекту нет. Итог суммирует доступные данные с указанным охватом.', '',
    '| Участник | Источник | Input | Output | Cache | Всего токенов | Стоимость, USD | Охват |',
    '| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |',
  ];
  const grand = emptyTotal();
  const periods = [];
  const daily = [];
  for (const file of files) {
    const data = JSON.parse(await fs.readFile(path.join(directory, file), 'utf8'));
    if (typeof data.participant !== 'string' || !data.period || !data.sources) throw new Error(`Некорректный экспорт: ${file}`);
    periods.push(`${cell(data.participant)}: с ${cell(data.period.since)} по ${cell(data.period.through ?? 'нет данных')}`);
    for (const source of sources) {
      const stats = data.sources[source];
      if (stats?.status === 'нет данных') {
        rows.push(`| ${cell(data.participant)} | ${source} | — | — | — | нет данных | — | ${cell(stats.coverage)}; ${cell(stats.reason)} |`);
      } else if (stats?.status === 'ok') {
        const total = totalDays(stats.daily);
        for (const key of Object.keys(grand)) grand[key] += total[key];
        rows.push(`| ${cell(data.participant)} | ${source} | ${totalCells(total)} | ${cell(stats.coverage)} |`);
        for (const day of stats.daily) {
          daily.push({ date: day.date, participant: data.participant, source, total: day.tokens.total, costUSD: day.costUSD });
        }
      } else throw new Error(`Некорректный источник ${source}: ${file}`);
    }
  }
  rows.push(`| **Итого** | — | ${totalCells(grand)} | доступные данные |`, '', ...periods, '');
  rows.push('| Дата | Участник | Источник | Всего токенов | Стоимость, USD |', '| --- | --- | --- | ---: | ---: |');
  daily.sort((a, b) => a.date.localeCompare(b.date) || a.participant.localeCompare(b.participant) || a.source.localeCompare(b.source));
  for (const day of daily) {
    rows.push(`| ${cell(day.date)} | ${cell(day.participant)} | ${day.source} | ${day.total} | ${day.costUSD.toFixed(6)} |`);
  }
  rows.push('');
  const journal = journalStats(await fs.readFile(path.join(options.root, 'docs/agent-log.md'), 'utf8'));
  rows.push(`Принятых юнитов: ${journal.accepted}`, '', `Записей журнала: ${journal.entries}`, '', `Кругов ревью: ${journal.rounds}`, '');
  await fs.writeFile(path.join(options.root, 'docs/presentation/agent-stats.md'), rows.join('\n'));
  options.log('Сводка: docs/presentation/agent-stats.md');
}

export async function main(args, options = {}) {
  const settings = { run: spawnSync, platform: process.platform, log: console.log, error: console.error, ...options };
  try {
    if (Number(process.versions.node.split('.')[0]) < 22) throw new Error('Node 22 or newer is required');
    const config = parseArguments(args);
    settings.root ??= repositoryRoot();
    if (config.merge) await merge(settings);
    else await exportParticipant(config, settings);
    return 0;
  } catch (error) {
    settings.error(error.message);
    return 1;
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exitCode = await main(process.argv.slice(2));
}

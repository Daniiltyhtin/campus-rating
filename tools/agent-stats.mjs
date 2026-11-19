import * as fs from 'node:fs/promises';
import { existsSync, createReadStream } from 'node:fs';
import { createInterface } from 'node:readline';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { homedir } from 'node:os';

const ccusage = 'ccusage@20.0.46';
const sources = ['claude', 'codex'];
const usage = 'Usage: --export <имя> [--since YYYYMMDD] [--claude-project <проект>]... [--claude-config-dir <путь>]... [--codex-cwd <путь>]... [--codex-home <путь>] | --merge';

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
  const repeated = new Set(['--claude-project', '--claude-config-dir', '--codex-cwd']);
  for (let index = 0; index < args.length; index += 2) {
    const key = args[index];
    const value = args[index + 1];
    if (!['--export', '--since', '--codex-home', ...repeated].includes(key)
      || (values.has(key) && !repeated.has(key)) || !value?.trim() || value.startsWith('--')) throw new Error(usage);
    values.set(key, repeated.has(key) ? [...(values.get(key) ?? []), value] : value);
  }
  const participant = values.get('--export');
  if (!participant || /[<>:"/\\|?*\x00-\x1f]/.test(participant)
    || /[. ]$/.test(participant) || /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(participant)) {
    throw new Error('Некорректное имя участника. ' + usage);
  }
  const since = values.get('--since') ?? '20261022';
  const date = since.replace(/^(\d{4})(\d{2})(\d{2})$/, '$1-$2-$3');
  if (!/^\d{8}$/.test(since) || !validDate(date)) throw new Error('Некорректная дата --since: YYYYMMDD');
  const projects = [...new Set(values.get('--claude-project') ?? [])];
  if (projects.some(project => project === '.' || project === '..' || /[/\\]/.test(project))) throw new Error('Некорректное имя проекта Claude');
  return {
    participant, since, sinceDate: date, projects,
    claudeDirs: values.get('--claude-config-dir')?.map(resolveDirectory),
    codexRoots: values.get('--codex-cwd')?.map(resolveDirectory),
    codexHome: resolveDirectory(values.get('--codex-home') ?? path.join(homedir(), '.codex')),
  };
}

function resolveDirectory(value) {
  return path.resolve(value === '~' ? homedir() : value.startsWith('~/') ? path.join(homedir(), value.slice(2)) : value);
}

function safeReason(error) {
  return error.message.replace(/(?:[A-Za-z]:[\\/]|~[\\/]|\/)[^\s"'<>|]+/g, '[путь]');
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

function claudeCoverage(projects) {
  return projects.length === 0 ? 'вся машина' : projects.length === 1 ? 'один проект Claude' : 'проекты Claude';
}

function collectSource(source, config, { root, run, platform, log }) {
  const coverage = source === 'claude' ? claudeCoverage(config.projects) : 'вся машина';
  const args = ['-y', ccusage, source, 'daily', '--json', '--since', config.since];
  if (source === 'claude' && config.projects.length === 1) {
    const project = config.projects[0];
    args.push(...(project.startsWith('-') ? [`--project=${project}`] : ['--project', project]));
  }
  try {
    const command = platform === 'win32' ? 'cmd.exe' : 'npx';
    const commandArgs = platform === 'win32' ? ['/d', '/s', '/c', 'npx', ...args] : args;
    const result = run(command, commandArgs, {
      ...(source === 'claude' && config.claudeDirs ? { env: { ...process.env, CLAUDE_CONFIG_DIR: config.claudeDirs.join(',') } } : {}),
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
    const reason = safeReason(error);
    log(`${source}: нет данных — ${reason}`);
    return { status: 'нет данных', coverage, reason };
  }
}

async function* sessionFiles(directory, matches) {
  let entries;
  try { entries = await fs.readdir(directory, { withFileTypes: true }); }
  catch (error) { if (error.code === 'ENOENT') return; throw error; }
  for (const entry of entries.sort((a, b) => a.name.localeCompare(b.name))) {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) yield* sessionFiles(file, matches);
    else if (entry.isFile() && matches(entry.name)) yield file;
  }
}

async function* records(file) {
  const input = createReadStream(file, { encoding: 'utf8' });
  const lines = createInterface({ input, crlfDelay: Infinity });
  try {
    for await (const line of lines) {
      let record;
      try { record = JSON.parse(line); }
      catch { continue; }
      yield record;
    }
  } finally {
    lines.close();
    input.destroy();
  }
}

function sessionDate(timestamp) {
  const date = typeof timestamp === 'string' ? timestamp.slice(0, 10) : '';
  return validDate(date) ? date : undefined;
}

function inside(directory, root) {
  const relative = path.relative(root, path.resolve(directory));
  return relative === '' || (relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative));
}

function addDay(days, date, tokens, model) {
  if (!days.has(date)) days.set(date, { date, tokens: { input: 0, output: 0, cache: 0, total: 0 }, costUSD: null, models: [] });
  const day = days.get(date);
  for (const key of Object.keys(day.tokens)) day.tokens[key] += tokens[key];
  if (typeof model === 'string' && !day.models.includes(model)) day.models.push(model);
}

function dailyResult(days, coverage) {
  return { status: 'ok', coverage, daily: [...days.values()].sort((a, b) => a.date.localeCompare(b.date)) };
}

async function collectCodex(config) {
  const sessions = new Map();
  for await (const file of sessionFiles(path.join(config.codexHome, 'sessions'), name => /^rollout-.*\.jsonl$/.test(name))) {
    let meta;
    let usage;
    for await (const record of records(file)) {
      if (!meta && record?.type === 'session_meta') meta = record;
      if (record?.type === 'event_msg' && record.payload?.type === 'token_count' && record.payload.info?.total_token_usage) usage = record.payload.info.total_token_usage;
    }
    const date = sessionDate(meta?.timestamp);
    if (!date || date < config.sinceDate || typeof meta.payload?.id !== 'string'
      || typeof meta.payload.cwd !== 'string' || !config.codexRoots.some(root => inside(meta.payload.cwd, root))) continue;
    if (!usage) continue;
    const input = number(usage.input_tokens, 'input_tokens');
    const cache = number(usage.cached_input_tokens, 'cached_input_tokens');
    const output = number(usage.output_tokens, 'output_tokens');
    if (cache > input) throw new Error('cached_input_tokens превышает input_tokens');
    const tokens = { input: input - cache, output, cache, total: input + output };
    const previous = sessions.get(meta.payload.id);
    if (!previous || tokens.total > previous.tokens.total) sessions.set(meta.payload.id, { date, tokens });
  }
  const days = new Map();
  for (const { date, tokens } of sessions.values()) addDay(days, date, tokens);
  return dailyResult(days, 'папка проекта Codex');
}

async function collectClaude(config, options) {
  const directories = config.claudeDirs ?? (process.env.CLAUDE_CONFIG_DIR?.split(',').map(resolveDirectory) ?? [path.join(homedir(), '.claude')]);
  const messages = new Set();
  const days = new Map();
  for (const directory of directories) {
    const projectsRoot = path.join(directory, 'projects');
    let projects;
    try { projects = await fs.readdir(projectsRoot, { withFileTypes: true }); }
    catch (error) { if (error.code === 'ENOENT') continue; throw error; }
    for (const project of projects) {
      if (!project.isDirectory() || (config.projects.length && !config.projects.includes(project.name))) continue;
      for await (const file of sessionFiles(path.join(projectsRoot, project.name), name => name.endsWith('.jsonl'))) {
        for await (const record of records(file)) {
          const usage = record?.message?.usage;
          const date = sessionDate(record?.timestamp);
          if (!usage || !date || date < config.sinceDate || typeof record.message.id !== 'string') continue;
          const key = JSON.stringify([record.message.id, record.requestId ?? null]);
          if (messages.has(key)) continue;
          messages.add(key);
          const input = number(usage.input_tokens ?? 0, 'input_tokens');
          const output = number(usage.output_tokens ?? 0, 'output_tokens');
          const cache = number(usage.cache_creation_input_tokens ?? 0, 'cache_creation_input_tokens')
            + number(usage.cache_read_input_tokens ?? 0, 'cache_read_input_tokens');
          addDay(days, date, { input, output, cache, total: input + output + cache }, record.message.model);
        }
      }
    }
  }
  const prices = new Map();
  for (const project of config.projects.length ? config.projects : [undefined]) {
    const stats = collectSource('claude', { ...config, claudeDirs: directories, projects: project ? [project] : [] }, options);
    for (const day of stats.daily ?? []) {
      const sum = prices.get(day.date) ?? { total: 0, costUSD: 0 };
      sum.total += day.tokens.total;
      sum.costUSD += day.costUSD;
      prices.set(day.date, sum);
    }
  }
  for (const day of days.values()) {
    const price = prices.get(day.date);
    if (price?.total === day.tokens.total) day.costUSD = price.costUSD;
  }
  return dailyResult(days, claudeCoverage(config.projects));
}

async function exportParticipant(config, options) {
  const collected = {};
  for (const source of sources) {
    try {
      const stats = source === 'codex' && config.codexRoots ? await collectCodex(config)
        : source === 'claude' && (config.claudeDirs || config.projects.length > 1) ? await collectClaude(config, options)
          : collectSource(source, config, options);
      collected[source] = stats.status === 'ok' ? { ...stats, ...slideTokens(stats.daily) } : stats;
    } catch (error) {
      const reason = safeReason(error);
      options.log(`${source}: нет данных — ${reason}`);
      collected[source] = { status: 'нет данных', coverage: 'папка проекта', reason };
    }
  }
  const dates = Object.values(collected).flatMap(source => (source.daily ?? []).map(day => day.date)).sort();
  const data = {
    participant: config.participant,
    period: { since: config.sinceDate, through: dates.at(-1) ?? null },
    sources: collected,
  };
  const directory = path.join(options.root, 'docs/presentation/agent-stats');
  await fs.mkdir(directory, { recursive: true });
  await fs.writeFile(path.join(directory, `${config.participant}.json`), JSON.stringify(data, null, 2) + '\n');
  options.log(`Экспорт: docs/presentation/agent-stats/${config.participant}.json`);
}

function slideTokens(daily) {
  return daily.reduce((sum, day) => ({
    ioTokens: sum.ioTokens + number(day.tokens?.input, 'input') + number(day.tokens?.output, 'output'),
    withCacheTokens: sum.withCacheTokens + number(day.tokens?.input, 'input') + number(day.tokens?.output, 'output') + number(day.tokens?.cache, 'cache'),
  }), { ioTokens: 0, withCacheTokens: 0 });
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
    sum.costUSD = sum.costUSD === null || day.costUSD === null ? null : sum.costUSD + number(day.costUSD, 'costUSD');
    return sum;
  }, emptyTotal());
}

const cell = value => String(value).replace(/\|/g, '\\|').replace(/[\r\n]/g, ' ');
const costCell = value => value === null ? '—' : value.toFixed(6);
const totalCells = total => `${total.input} | ${total.output} | ${total.cache} | ${total.total} | ${costCell(total.costUSD)}`;

async function merge(options) {
  const directory = path.join(options.root, 'docs/presentation/agent-stats');
  const files = (await fs.readdir(directory)).filter(file => file.endsWith('.json')).sort();
  const rows = [
    '# Статистика агентов', '',
    'Стоимость — оценка ccusage в USD; — означает неизвестную оценку. Input не включает кэш; Cache включает создание и чтение кэша.',
    'Общий итог таблицы суммирует доступные данные с указанным охватом.',
    'Для слайда учитываются только источники с охватом проекта или папки проекта; охват «вся машина» не входит.',
    'ioTokens = Input + Output; withCacheTokens = Input + Output + Cache. Для Codex из входа заранее вычтен кэш.', '',
    '| Участник | Источник | Input | Output | Cache | Всего токенов | Стоимость, USD | Охват |',
    '| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |',
  ];
  const grand = emptyTotal();
  const slide = { ioTokens: 0, withCacheTokens: 0, dates: [], participants: new Set() };
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
        for (const key of Object.keys(grand)) grand[key] = grand[key] === null || total[key] === null ? null : grand[key] + total[key];
        const included = ['один проект Claude', 'проекты Claude', 'папка проекта Codex'].includes(stats.coverage);
        if (included && stats.daily.length) {
          const tokens = slideTokens(stats.daily);
          slide.ioTokens += tokens.ioTokens;
          slide.withCacheTokens += tokens.withCacheTokens;
          slide.dates.push(...stats.daily.map(day => day.date));
          slide.participants.add(data.participant);
        }
        const coverage = `${cell(stats.coverage)}${included ? '' : '; не входит в итог для слайда'}`;
        rows.push(`| ${cell(data.participant)} | ${source} | ${totalCells(total)} | ${coverage} |`);
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
    rows.push(`| ${cell(day.date)} | ${cell(day.participant)} | ${day.source} | ${day.total} | ${costCell(day.costUSD)} |`);
  }
  rows.push('');
  slide.dates.sort();
  const formatDate = date => date.split('-').reverse().join('.');
  const period = slide.dates.length ? `${formatDate(slide.dates[0]).slice(0, 5)}–${formatDate(slide.dates.at(-1))}` : 'нет данных';
  const participants = slide.participants.size === 2 ? 'двух участников'
    : slide.participants.size === 1 ? 'одного участника' : `${slide.participants.size} участников`;
  rows.push('## Сбор данных', '',
    'Повторяйте `--claude-config-dir`, `--claude-project` и `--codex-cwd` для нескольких каталогов и корней. Имя проекта Claude должно точно совпадать с именем папки в `projects`.', '',
    '```sh',
    'node tools/agent-stats.mjs --export <имя> --claude-config-dir <каталог-Claude> --claude-project <имя-папки-проекта> --codex-home <каталог-Codex> --codex-cwd <корень-проекта>',
    'node tools/agent-stats.mjs --merge',
    '```', '',
    'Без `--codex-home` используется стандартный каталог Codex. По умолчанию период начинается 22.10.2026; его можно изменить через `--since YYYYMMDD`. Пути остаются только в аргументах команды.', '',
    'Стоимость Claude из ccusage сохраняется только при совпадении суточного числа токенов с дедуплицированными журналами; иначе оценка неизвестна.', '');
  rows.push('## Для слайда', '', `Без кэша: ${Math.round(slide.ioTokens / 1_000_000)} млн токенов`, '',
    `С учётом кэша: ${Math.round(slide.withCacheTokens / 1_000_000)} млн токенов`, '',
    `Нижняя оценка: сессии в папке проекта у ${participants}, ${period}`, '');
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

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const fileTools = new Set(['Read', 'Edit', 'Write', 'MultiEdit', 'NotebookEdit', 'read_file', 'write_file', 'edit_file']);
const shellTools = new Set(['Bash', 'PowerShell', 'exec_command', 'shell_command', 'shell']);
const readCommands = new Set(['cat', 'type', 'head', 'tail', 'less', 'ls', 'dir', 'grep', 'rg', 'wc', 'diff', 'get-content', 'select-string']);
const readGitCommands = new Set(['diff', 'log', 'status', 'show']);
const skillScripts = new Set(['tools/check-agent-docs.mjs', 'tools/sync-skills.mjs']);
const skillsReason = 'KTD12: правь .agents/skills, затем выполни синк: node tools/sync-skills.mjs.';
const envReason = 'KTD12: чтение и правка .env* запрещены; используй .env.example.';
const pushReason = 'KTD12: git push в main и принудительный push запрещены; отправь ветку юнита без --force и открой PR.';
const slashes = value => value.replaceAll('\\', '/');
const protectedText = (value, { root }) => /^[a-z]:[\\/]/i.test(root) ? slashes(value).toLowerCase() : slashes(value);

function relativePath(file, { root, cwd }) {
  const normalizedRoot = slashes(root);
  const api = /^[a-z]:\//i.test(normalizedRoot) ? path.win32 : path.posix;
  return slashes(api.relative(normalizedRoot, api.resolve(slashes(cwd), slashes(file))));
}

function pathReason(file, writing, context) {
  const relative = protectedText(relativePath(file, context), context);
  if (relative === '..' || relative.startsWith('../') || path.posix.isAbsolute(relative)) return null;
  if (relative.split('/').some(part => part.startsWith('.env') && part !== '.env.example')) return envReason;
  if (writing && (relative === '.claude/skills' || relative.startsWith('.claude/skills/'))) return skillsReason;
  return null;
}

// Как в shell: соседние куски в кавычках и без склеиваются в одно слово.
function tokens(command) {
  return (command.match(/(?:"[^"\n]*"|'[^'\n]*'|[^\s;|&<>"'])+|[\r\n;|&<>]/g) ?? [])
    .map(token => token.replace(/"([^"\n]*)"|'([^'\n]*)'/g, (_, double, single) => double ?? single));
}

function collapseSegments(text) {
  let previous;
  do {
    previous = text;
    text = text.replace(/\/\.(?=\/)/g, '').replace(/\/{2,}/g, '/')
      .replace(/(?:^|(?<=[\s'"`<>|&;(){}\[\],:=/]))(?!\.\.\/)[^\s/'"`<>|&;(){}\[\],:=]+\/\.\.\//g, '');
  } while (text !== previous);
  return text;
}

function pushBlocked(words, branch) {
  for (let i = 0; i < words.length; i++) {
    if (!/(?:^|[/\\])git(?:\.exe)?$/i.test(words[i])) continue;
    const end = words.findIndex((word, index) => index > i && /^[\r\n;|&<>]$/.test(word));
    const command = words.slice(i + 1, end < 0 ? undefined : end);
    let j = 0;
    while (j < command.length && command[j].startsWith('-')) {
      j += ['-C', '-c', '--git-dir', '--work-tree', '--namespace'].includes(command[j]) ? 2 : 1;
    }
    if (command[j] !== 'push') continue;
    const args = command.slice(j + 1).map(arg => arg.replace(/\\(.)/g, '$1'));
    if (args.some(arg => ['--all', '--mirror'].includes(arg))) return true;
    if (args.some(arg => /^--force(?:$|[-=])/.test(arg) || /^-[^-]*f/.test(arg) || arg.startsWith('+'))) return true;
    if (args.some(arg => /^(?:main|refs\/heads\/main)$/.test(arg) || /:(?:refs\/heads\/)?main$/.test(arg))) return true;
    const positional = [];
    for (let index = 0; index < args.length; index++) {
      if (['--repo', '--receive-pack', '--exec', '-o', '--push-option'].includes(args[index])) index++;
      else if (!args[index].startsWith('-')) positional.push(args[index]);
    }
    if (branch === 'main' && positional.length <= 1) return true;
  }
  return false;
}

function readOnly(command) {
  const words = tokens(command);
  const [name, subcommand] = words;
  if (readCommands.has(name?.toLowerCase())) return true;
  if (name === 'git') return readGitCommands.has(subcommand) && !words.some(word => /^--output(?:=|$)/.test(word));
  return name === 'node' && skillScripts.has(subcommand);
}

function shellReason(command, context) {
  const commands = Array.isArray(command) ? command.filter(value => typeof value === 'string') : [command];
  if (commands.some(value => typeof value !== 'string')) return null;
  const shell = commands.some(value => /\s/.test(value)) ? commands.join(';') : commands.join(' ');
  const words = tokens(shell);
  if (pushBlocked(words, context.branch)) return pushReason;
  const text = collapseSegments(protectedText(commands.join(' '), context));
  const components = text.split(/[\s/\\'"`<>|&;(){}\[\],:=]+/);
  if (components.some(part => part.startsWith('.env') && part !== '.env.example')) return envReason;
  if (text.includes('.claude/skills')) {
    if (text.includes('>') || !shell.split(/;|&&|\|\||\||\r?\n/).every(readOnly)) return skillsReason;
  }
  return null;
}

export function guard(input, context) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return null;
  const { tool_name: name, tool_input: args } = input;
  if (name === 'apply_patch') {
    const patch = typeof args === 'string' ? args : args?.command ?? args?.patch ?? args?.input;
    if (typeof patch !== 'string') return null;
    for (const match of patch.matchAll(/^\*\*\* (?:(?:Add|Update|Delete) File|Move to): (.+)\r?$/gm)) {
      const reason = pathReason(match[1].trim(), true, context);
      if (reason) return reason;
    }
    return null;
  }
  if (!args || typeof args !== 'object' || Array.isArray(args)) return null;
  if (shellTools.has(name)) return shellReason(args.command ?? args.cmd, context);
  if (!fileTools.has(name)) return null;
  for (const key of ['file_path', 'path', 'notebook_path']) {
    if (typeof args[key] !== 'string') continue;
    const reason = pathReason(args[key], !['Read', 'read_file'].includes(name), context);
    if (reason) return reason;
  }
  return null;
}

async function main() {
  let input;
  try {
    let raw = '';
    for await (const chunk of process.stdin) raw += chunk;
    input = JSON.parse(raw);
  } catch { return; }
  const root = fileURLToPath(new URL('../../', import.meta.url));
  const cwd = typeof input?.cwd === 'string' ? input.cwd : process.cwd();
  const result = spawnSync('git', ['rev-parse', '--abbrev-ref', 'HEAD'], { cwd: root, encoding: 'utf8' });
  const branch = result.status === 0 ? result.stdout.trim() : undefined;
  const reason = guard(input, { root, cwd, branch });
  if (reason) {
    process.stderr.write(`${reason}\n`);
    process.exitCode = 2;
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();

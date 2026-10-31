import path from 'node:path';
import { fileURLToPath } from 'node:url';

const fileTools = new Set(['Read', 'Edit', 'Write', 'NotebookEdit', 'read_file', 'write_file', 'edit_file']);
const shellTools = new Set(['Bash', 'PowerShell', 'exec_command', 'shell_command', 'shell']);
const skillsReason = 'KTD12: правь .agents/skills, затем выполни синк: node tools/sync-skills.mjs.';
const envReason = 'KTD12: чтение и правка .env* запрещены; используй .env.example.';
const pushReason = 'KTD12: git push в main и принудительный push запрещены; отправь ветку юнита без --force и открой PR.';
const slashes = value => value.replaceAll('\\', '/');

function relativePath(file, { root, cwd }) {
  const normalizedRoot = slashes(root);
  const api = /^[a-z]:\//i.test(normalizedRoot) ? path.win32 : path.posix;
  return slashes(api.relative(normalizedRoot, api.resolve(slashes(cwd), slashes(file))));
}

function pathReason(file, writing, context) {
  const relative = relativePath(file, context);
  if (relative === '..' || relative.startsWith('../') || path.posix.isAbsolute(relative)) return null;
  if (relative.split('/').some(part => part.startsWith('.env') && part !== '.env.example')) return envReason;
  if (writing && (relative === '.claude/skills' || relative.startsWith('.claude/skills/'))) return skillsReason;
  return null;
}

function tokens(command) {
  return (command.match(/"[^"\n]*"|'[^'\n]*'|[^\s;|&<>]+|[;|&<>]/g) ?? [])
    .map(token => token.replace(/^(["'])(.*)\1$/, '$2'));
}

function pushBlocked(words) {
  for (let i = 0; i < words.length; i++) {
    if (!/(?:^|[/\\])git(?:\.exe)?$/i.test(words[i])) continue;
    const end = words.findIndex((word, index) => index > i && /^[;|&<>]$/.test(word));
    const command = words.slice(i + 1, end < 0 ? undefined : end);
    let j = 0;
    while (j < command.length && command[j].startsWith('-')) {
      j += ['-C', '-c', '--git-dir', '--work-tree', '--namespace'].includes(command[j]) ? 2 : 1;
    }
    if (command[j] !== 'push') continue;
    const args = command.slice(j + 1);
    if (args.some(arg => /^--force(?:$|[-=])/.test(arg) || /^-[^-]*f/.test(arg) || arg.startsWith('+'))) return true;
    if (args.some(arg => /^(?:main|refs\/heads\/main)$/.test(arg) || /:(?:refs\/heads\/)?main$/.test(arg))) return true;
  }
  return false;
}

function shellReason(command, context) {
  const commands = Array.isArray(command) ? command.filter(value => typeof value === 'string') : [command];
  if (commands.some(value => typeof value !== 'string')) return null;
  const words = commands.flatMap(tokens);
  if (pushBlocked(words)) return pushReason;
  const writing = words.some(word => /^(?:cp|mv|rm|touch|tee|sed|perl|python|python3|node|Set-Content|Add-Content|Out-File|Copy-Item|Move-Item|Remove-Item)$/i.test(word))
    || words.includes('>');
  for (const word of words) {
    const file = word.replace(/^[^=]+=\s*/, '');
    const reason = pathReason(file, writing, context);
    if (reason) return reason;
  }
  return null;
}

export function guard(input, context) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return null;
  const { tool_name: name, tool_input: args } = input;
  if (name === 'apply_patch') {
    const patch = typeof args === 'string' ? args : args?.patch ?? args?.input;
    if (typeof patch !== 'string') return null;
    for (const match of patch.matchAll(/^\*\*\* (?:Add|Update|Delete) File: (.+)\r?$/gm)) {
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
  const reason = guard(input, { root, cwd });
  if (reason) {
    process.stderr.write(`${reason}\n`);
    process.exitCode = 2;
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();

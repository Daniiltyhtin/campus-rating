import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

export function statusPaths(status) {
  const entries = status.split('\0');
  const paths = [];
  for (let i = 0; i < entries.length; i++) {
    const entry = entries[i];
    if (entry.length < 4) continue;
    paths.push(entry.slice(3));
    if (/[RC]/.test(entry.slice(0, 2)) && entries[i + 1]) paths.push(entries[++i]);
  }
  return paths;
}

export function reminder(input, paths) {
  if (!input || typeof input !== 'object' || Array.isArray(input) || input.stop_hook_active) return null;
  if (paths.includes('docs/agent-log.md')) return null;
  if (!paths.some(file => /^(backend|frontend|e2e|tools|\.agents)\//.test(file))) return null;
  return { systemMessage: 'Изменены код или скиллы: подготовь запись в docs/agent-log.md по скиллу agent-log.' };
}

async function main() {
  try {
    let raw = '';
    for await (const chunk of process.stdin) raw += chunk;
    const input = JSON.parse(raw);
    if (!input || typeof input !== 'object' || Array.isArray(input) || input.stop_hook_active) return;
    const root = fileURLToPath(new URL('../../', import.meta.url));
    const status = execFileSync('git', ['status', '--porcelain=v1', '-z', '--untracked-files=all'],
      { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], timeout: 5000 });
    const output = reminder(input, statusPaths(status));
    if (output) process.stdout.write(`${JSON.stringify(output)}\n`);
  } catch {
    // Напоминание не должно мешать завершению при отсутствии git или повреждённом вводе.
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();

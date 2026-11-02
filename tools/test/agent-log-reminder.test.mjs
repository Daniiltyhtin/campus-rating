import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { reminder, statusPaths } from '../../.claude/hooks/agent-log-reminder.mjs';

const source = new URL('../../.claude/hooks/agent-log-reminder.mjs', import.meta.url);

test('KTD4: reminder uses code paths, log changes and loop flag', () => {
  for (const file of ['backend/x.java', 'frontend/x.ts', 'e2e/a.ts', 'tools/a.mjs', '.agents/skills/a.md']) {
    assert.match(reminder({}, [file]).systemMessage, /agent-log/);
    assert.equal(reminder({}, [file, 'docs/agent-log.md']), null);
  }
  for (const input of [{ stop_hook_active: true }, null, [], 'bad']) {
    assert.equal(reminder(input, ['backend/a.java']), null);
  }
  assert.equal(reminder({}, ['docs/spec/a.md']), null);
  assert.equal(reminder({}, []), null);
});

test('KTD4: porcelain -z preserves spaces, Unicode, renames and deletions', () => {
  assert.deepEqual(statusPaths(' M backend/код с пробелом.java\0R  docs/moved.md\0backend/old.java\0 D tools/x.mjs\0'),
    ['backend/код с пробелом.java', 'docs/moved.md', 'backend/old.java', 'tools/x.mjs']);
});

async function fixture(t, git = true) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'журнал с пробелом-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  await fs.mkdir(path.join(root, '.claude/hooks'), { recursive: true });
  await fs.copyFile(source, path.join(root, '.claude/hooks/agent-log-reminder.mjs'));
  await fs.mkdir(path.join(root, 'backend'));
  if (git) {
    const init = spawnSync('git', ['init', '--quiet', root], { encoding: 'utf8' });
    assert.equal(init.status, 0, init.stderr);
  }
  const run = (input = '{}', cwd = root, env = process.env) => spawnSync(process.execPath,
    [path.join(root, '.claude/hooks/agent-log-reminder.mjs')], { input, cwd, env, encoding: 'utf8' });
  return { root, run };
}

function silent(result) {
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, '');
  assert.equal(result.stderr, '');
}

test('R3/R4: actual git, independent cwd and nonblocking JSON output', async t => {
  const { root, run } = await fixture(t);
  silent(run());
  await fs.writeFile(path.join(root, 'backend/код с пробелом.java'), 'code');
  for (const cwd of [root, path.join(root, 'backend'), os.tmpdir()]) {
    const result = run('{}', cwd);
    assert.equal(result.status, 0);
    assert.equal(result.stderr, '');
    assert.match(JSON.parse(result.stdout).systemMessage, /agent-log/);
  }
  for (const input of ['', '{', 'null', '[]', '{"stop_hook_active":true}']) silent(run(input));
  silent(run('{}', root, { ...process.env, PATH: '' }));
  await fs.mkdir(path.join(root, 'docs'));
  await fs.writeFile(path.join(root, 'docs/agent-log.md'), 'entry');
  silent(run());
  await fs.rm(path.join(root, 'backend/код с пробелом.java'));
  await fs.rm(path.join(root, 'docs/agent-log.md'));
  await fs.mkdir(path.join(root, 'docs/spec'));
  await fs.writeFile(path.join(root, 'docs/spec/plan.md'), 'plan');
  silent(run());
});

test('R4: no git repository is silent', async t => {
  const { run } = await fixture(t, false);
  silent(run());
});

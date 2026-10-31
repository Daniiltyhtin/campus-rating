import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
const script = fileURLToPath(new URL('../sync-skills.mjs', import.meta.url));
test('sync copies five skills, preserves references, is idempotent and removes stale skills', async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'skills-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  for (const name of ['one', 'two', 'three', 'four', 'five']) {
    const dir = path.join(root, '.agents', 'skills', name);
    await fs.mkdir(path.join(dir, 'references'), { recursive: true });
    await fs.writeFile(path.join(dir, 'SKILL.md'), `---\r\nname: ${name}\r\ndescription: Test\r\n---\r\nBody\r\n`);
    await fs.writeFile(path.join(dir, 'references', 'a.md'), 'Reference\n');
  }
  const run = () => {
    const result = spawnSync(process.execPath, [script, '--root', root], { encoding: 'utf8' });
    assert.ifError(result.error);
    return result;
  };
  assert.equal(run().status, 0);
  const copy = path.join(root, '.claude', 'skills', 'one', 'SKILL.md');
  const content = await fs.readFile(copy, 'utf8');
  assert.match(content, /^---\nname: one\ndescription: Test\n---\n/);
  assert.match(content, /сгенерировано из `\.agents\/skills`, правь там/);
  assert.equal(await fs.readFile(path.join(root, '.claude', 'skills', 'one', 'references', 'a.md'), 'utf8'), 'Reference\n');
  const before = (await fs.stat(copy)).mtimeMs;
  assert.equal(run().status, 0);
  assert.equal((await fs.stat(copy)).mtimeMs, before);
  assert.equal((await fs.readdir(path.join(root, '.claude', 'skills'))).length, 5);
  await fs.rm(path.join(root, '.agents', 'skills', 'two'), { recursive: true });
  assert.equal(run().status, 0);
  await assert.rejects(fs.access(path.join(root, '.claude', 'skills', 'two')));
});

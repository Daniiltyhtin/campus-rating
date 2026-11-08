import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
const scripts = fileURLToPath(new URL('../', import.meta.url));
const header = '<!-- сгенерировано из `.agents/skills`, правь там -->\n';
async function fixture(t) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'docs-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  const put = async (file, text) => {
    const dest = path.join(root, ...file.split('/'));
    await fs.mkdir(path.dirname(dest), { recursive: true });
    await fs.writeFile(dest, text);
  };
  await put('AGENTS.md', '# Rules\n');
  await put('CLAUDE.md', '@AGENTS.md\n');
  await put('.agents/skills/demo/SKILL.md', '---\nname: demo\ndescription: Test\n---\nBody\n');
  await put('.claude/skills/demo/SKILL.md', `---\nname: demo\ndescription: Test\n---\n${header}Body\n`);
  const run = () => {
    const result = spawnSync(process.execPath, [path.join(scripts, 'check-agent-docs.mjs'), '--root', root], { encoding: 'utf8' });
    assert.ifError(result.error);
    return result;
  };
  return { root, put, run };
}
test('valid docs and CRLF normalization; templates excluded', async t => {
  const { put, run } = await fixture(t);
  await put('.agents/skills/demo/SKILL.md', '---\r\nname: demo\r\ndescription: Test\r\n---\r\nBody\r\n');
  await put('.agents/templates/skill/SKILL.md', 'placeholder');
  assert.equal(run().status, 0, run().stderr);
});
for (const [label, file, content, diagnostic] of [
  ['drift', '.claude/skills/demo/SKILL.md', 'manual', /\.claude.*SKILL.md/],
  ['budget', 'AGENTS.md', 'x'.repeat(13 * 1024), /budget/i],
  ['import', 'CLAUDE.md', '# Empty', /CLAUDE.md/],
  ['name', '.agents/skills/demo/SKILL.md', '---\nname: wrong\ndescription: Test\n---\n', /name/],
  ['duplicate', '.agents/skills/other/SKILL.md', '---\nname: demo\ndescription: Test\n---\n', /duplicate/i],
  ['description', '.agents/skills/demo/SKILL.md', `---\nname: demo\ndescription: ${'a'.repeat(1025)}\n---\n`, /description/],
  ['link', '.agents/skills/demo/SKILL.md', '---\nname: demo\ndescription: Test\n---\n[x](references/x.md)', /references\/x.md/],
  ['deep instructions', 'backend/src/AGENTS.md', 'Rules', /depth/i],
  ['frontmatter keys', '.agents/skills/demo/SKILL.md', '---\nname: demo\ndescription: Test\nextra: no\n---\n', /frontmatter/i],
]) test(label, async t => {
  const { put, run } = await fixture(t);
  await put(file, content);
  const result = run();
  assert.notEqual(result.status, 0);
  assert.match(result.stdout + result.stderr, diagnostic);
  assert.ok((result.stdout + result.stderr).includes(path.basename(file)));
});
test('secrets are detected without leaking their value', async t => {
  const { put, run } = await fixture(t);
  const token = 'ghp_' + 'a'.repeat(36);
  await put('.agents/skills/demo/SKILL.md', `---\nname: demo\ndescription: Test\n---\n${token}`);
  const result = run();
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /secret/i);
  assert.ok(!result.stderr.includes(token));
});
test('both commands reject Node below 22 clearly', async () => {
  for (const name of ['sync-skills.mjs', 'check-agent-docs.mjs']) {
    const url = new URL(`../${name}`, import.meta.url).href;
    const result = spawnSync(process.execPath, ['--input-type=module', '-e', `Object.defineProperty(process.versions, 'node', {value:'20.0.0'}); await import(${JSON.stringify(url)});`], { encoding: 'utf8' });
    assert.ifError(result.error);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /Node.*22/);
  }
});

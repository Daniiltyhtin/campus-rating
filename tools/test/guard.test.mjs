import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { guard } from '../../.claude/hooks/guard.mjs';

const root = '/project';
const check = (tool_name, tool_input, context = {}) => guard({ tool_name, tool_input }, { root, cwd: root, ...context });

test('KTD12: file keys, normalized paths and generated skill boundary', () => {
  for (const key of ['file_path', 'path', 'notebook_path']) {
    for (const name of ['Edit', 'Write', 'NotebookEdit']) {
      for (const file of ['.claude/skills/ub-mode/SKILL.md', '/project/.claude/skills/x.md', 'x/../.claude\\skills\\x.md']) {
        assert.match(check(name, { [key]: file }), /\.agents\/skills.*синк/);
      }
    }
  }
  assert.equal(check('Edit', { path: '.agents/skills/ub-mode/SKILL.md' }), null);
  assert.equal(check('Read', { path: '.claude/skills/x.md' }), null);
  assert.equal(check('Edit', { path: '/elsewhere/.claude/skills/x.md' }), null);
  assert.match(check('Edit', { path: '../.claude/skills/x.md' }, { cwd: '/project/backend' }), /синк/);
  assert.match(check('Edit', { path: 'C:\\repo\\.claude\\skills\\x.md' }, { root: 'C:\\repo', cwd: 'C:\\repo\\backend' }), /синк/);
});

test('KTD12: env files denied, exact example allowed', () => {
  for (const name of ['Read', 'Edit', 'Write', 'NotebookEdit', 'read_file']) {
    for (const file of ['.env', 'backend/.env.local', '/project/frontend/.env.production']) {
      assert.match(check(name, { path: file }), /\.env/);
    }
    assert.equal(check(name, { path: 'backend/.env.example' }), null);
  }
});

test('KTD12: Codex patches inspect only file headers, including deletes', () => {
  for (const action of ['Add', 'Update', 'Delete']) {
    assert.match(check('apply_patch', `*** Begin Patch\n*** ${action} File: .claude/skills/a.md\n*** End Patch`), /синк/);
    assert.match(check('apply_patch', { patch: `*** ${action} File: backend/.env.local` }), /\.env/);
  }
  assert.equal(check('apply_patch', { input: '*** Update File: tools/a.mjs\n+const x = ".env";' }), null);
  assert.match(check('apply_patch', { command: '*** Begin Patch\n*** Update File: .claude/skills/ub-mode/SKILL.md\n*** End Patch' }), /синк/);
  assert.equal(check('apply_patch', { command: '*** Begin Patch\n*** Update File: .agents/skills/ub-mode/SKILL.md\n*** End Patch' }), null);
});

test('KTD12: shell commands across Claude and Codex', () => {
  for (const tool of ['Bash', 'PowerShell', 'exec_command', 'shell_command']) {
    for (const command of ['git push origin main', 'git push --force', 'git push -f origin u2',
      'git push --force-with-lease origin u2', 'git -C backend push origin HEAD:refs/heads/main',
      'git status && git push origin main', 'git push origin +u2:u2', 'git push origin HEAD:main']) {
      assert.match(check(tool, { command }), /push/);
    }
    assert.equal(check(tool, { command: 'git push origin u2-skeleton' }), null);
    assert.equal(check(tool, { command: 'git diff main' }), null);
    assert.match(check(tool, { command: ['git', 'push', 'origin', 'main'] }), /push/);
    assert.match(check(tool, { command: ['git status', 'git push origin main'] }), /push/);
    assert.match(check(tool, { command: 'cat "backend/.env.local"' }), /\.env/);
    assert.match(check(tool, { command: 'Get-Content .\\backend\\.env' }), /\.env/);
    assert.equal(check(tool, { command: 'cat backend/.env.example' }), null);
    assert.match(check(tool, { command: 'cp x .claude/skills/x.md' }), /синк/);
  }
});

test('KTD12: malformed input and unknown tools fail open', () => {
  for (const input of [null, [], {}, 'bad', { tool_name: 'Read', tool_input: null }]) {
    assert.equal(guard(input, { root, cwd: root }), null);
  }
  assert.equal(check('unknown', { path: '.env', command: 'git push --force' }), null);
});

test('R3/KTD12: real hook protocol and path root from script location', async t => {
  const repo = await fs.mkdtemp(path.join(os.tmpdir(), 'guard кириллица-'));
  t.after(() => fs.rm(repo, { recursive: true, force: true }));
  await fs.mkdir(path.join(repo, '.claude/hooks'), { recursive: true });
  await fs.mkdir(path.join(repo, 'backend'));
  const script = path.join(repo, '.claude/hooks/guard.mjs');
  await fs.copyFile(new URL('../../.claude/hooks/guard.mjs', import.meta.url), script);
  assert.equal(spawnSync('git', ['init', '--quiet', repo]).status, 0);
  for (const cwd of [repo, path.join(repo, 'backend')]) {
    const run = input => spawnSync(process.execPath, [script], { input, cwd, encoding: 'utf8' });
    const denied = run(JSON.stringify({ tool_name: 'Read', tool_input: { file_path: path.join(repo, 'backend/.env') } }));
    assert.equal(denied.status, 2);
    assert.equal(denied.stdout, '');
    assert.match(denied.stderr, /\.env/);
    for (const input of ['', '{', 'null', JSON.stringify({ tool_name: 'Read', tool_input: { path: '.env.example' } })]) {
      const allowed = run(input);
      assert.equal(allowed.status, 0);
      assert.equal(allowed.stdout, '');
      assert.equal(allowed.stderr, '');
    }
  }
});

test('KTD5/KTD6: checked-in role capabilities, narrow permissions and hook registration', async () => {
  const settings = JSON.parse(await fs.readFile(new URL('../../.claude/settings.json', import.meta.url), 'utf8'));
  for (const tool of ['Bash', 'PowerShell']) {
    assert.ok(settings.permissions.allow.includes(`${tool}(node --test *)`));
    assert.ok(settings.permissions.ask.includes(`${tool}(git push *)`));
    assert.ok(!settings.permissions.allow.includes(tool));
    assert.ok(!settings.permissions.allow.includes(`${tool}(*)`));
  }
  for (const tool of ['Read', 'Edit']) {
    const rules = settings.permissions.deny;
    assert.ok(rules.indexOf(`${tool}(**/.env*)`) >= 0);
    assert.ok(rules.indexOf(`${tool}(!**/.env.example)`) > rules.indexOf(`${tool}(**/.env*)`));
  }
  const matched = settings.hooks.PreToolUse[0].matcher.split('|');
  for (const tool of ['Read', 'Edit', 'Write', 'MultiEdit', 'NotebookEdit', 'Bash', 'PowerShell']) assert.ok(matched.includes(tool), tool);
  for (const [event, file] of [['Stop', 'agent-log-reminder'], ['PreToolUse', 'guard']]) {
    assert.equal(settings.hooks[event][0].hooks[0].type, 'command');
    assert.equal(settings.hooks[event][0].hooks[0].command, `node "\${CLAUDE_PROJECT_DIR}/.claude/hooks/${file}.mjs"`);
  }
  for (const [role, skill] of [['reviewer', 'review-unit'], ['tester', 'write-tests']]) {
    const text = await fs.readFile(new URL(`../../.claude/agents/${role}.md`, import.meta.url), 'utf8');
    assert.match(text, new RegExp(`skills:\\n  - ${skill}\\n`));
    const tools = text.match(/^tools: (.+)$/m)[1].split(/,\s*/);
    assert.ok(tools.includes('PowerShell'));
    assert.ok(tools.includes('Bash'));
    if (role === 'reviewer') {
      assert.ok(!tools.includes('Edit'));
      assert.ok(!tools.includes('Write'));
    }
  }
});

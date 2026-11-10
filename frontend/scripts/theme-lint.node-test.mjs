import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ESLint } from 'eslint';

for (const [filePath, source] of [
  ['src/features/probe.tsx', 'export const Probe = () => <div style={{ color: "#fff" }} />;'],
  ['src/features/probe.css', '.probe { color: rgb(10, 20, 30); }'],
  ['src/features/probe.css', '.probe { background: white; }'],
]) {
  test(`rejects a color literal in ${filePath}`, async () => {
    const [result] = await new ESLint().lintText(source, { filePath });
    assert.equal(result.messages.some((message) => message.ruleId === 'theme/no-color-literals'), true);
  });
}

test('allows a token in CSS', async () => {
  const [result] = await new ESLint().lintText('.probe { color: var(--cr-text); }', { filePath: 'src/features/probe.css' });
  assert.equal(result.errorCount, 0);
});

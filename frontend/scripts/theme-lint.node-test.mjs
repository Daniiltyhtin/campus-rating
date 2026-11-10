import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ESLint } from 'eslint';

const lint = async (source, filePath) => (await new ESLint().lintText(source, { filePath }))[0];
const flagged = (result) => result.messages.some((message) => message.ruleId === 'theme/no-color-literals');

for (const [filePath, source] of [
  ['src/features/probe.tsx', 'export const Probe = () => <div style={{ color: "#fff" }} />;'],
  ['src/features/probe.tsx', 'export const Probe = () => <span c="red">x</span>;'],
  ['src/features/probe.tsx', "export const Probe = () => <span bg={'white'}>x</span>;"],
  ['src/features/probe.css', '.probe { color: rgb(10, 20, 30); }'],
  ['src/features/probe.css', '.probe { background: white; }'],
  ['src/features/probe.css', '.probe { border: 1px solid red; }'],
  ['src/features/probe.css', '.probe { box-shadow: 0 0 4px black; }'],
  ['src/features/probe.css', '.probe { color: oklch(0.5 0.1 200); }'],
]) {
  test(`rejects a color literal: ${source}`, async () => {
    assert.equal(flagged(await lint(source, filePath)), true);
  });
}

for (const [filePath, source] of [
  ['src/features/probe.css', '.probe { color: var(--cr-text); border: 1px solid var(--cr-rule); }'],
  ['src/features/probe.tsx', '// Fixes #101 and #feed\nexport const Probe = () => <a href="#feed">Лента</a>;'],
  ['src/features/probe.css', '/* background: white */ .probe { color: var(--cr-link); }'],
]) {
  test(`allows tokens, comments and anchors: ${source.split('\n')[0]}`, async () => {
    assert.equal(flagged(await lint(source, filePath)), false);
  });
}

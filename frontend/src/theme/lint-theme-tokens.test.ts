import { ESLint } from 'eslint';
import { describe, expect, it } from 'vitest';

describe('feature color lint gate', () => {
  it.each([
    ['src/features/probe.tsx', 'export const Probe = () => <div style={{ color: "#fff" }} />;'],
    ['src/features/probe.css', '.probe { color: rgb(10, 20, 30); }'],
    ['src/features/probe.css', '.probe { background: white; }'],
  ])('rejects a literal in %s', async (filePath, source) => {
    const [result] = await new ESLint().lintText(source, { filePath });
    expect(result?.messages.some((message) => message.ruleId === 'theme/no-color-literals')).toBe(true);
  });
  it('allows a token in CSS', async () => {
    const [result] = await new ESLint().lintText('.probe { color: var(--cr-text); }', { filePath: 'src/features/probe.css' });
    expect(result?.errorCount).toBe(0);
  });
});

import type { Linter, Rule } from 'eslint';

function literals(text: string) {
  const expression = /#[\da-f]{3,8}\b|\b(?:rgba?|hsla?)\s*\(|\b(?:color|background(?:Color)?|borderColor)\s*:\s*['"]?(?:white|black|red|blue|green|gray|grey|yellow|orange|purple|pink)\b/gi;
  return [...text.matchAll(expression)].map((match) => {
    const prefix = text.slice(0, match.index);
    const lines = prefix.split('\n');
    return { line: lines.length, column: lines.at(-1)!.length, length: match[0].length };
  });
}

const noColorLiterals: Rule.RuleModule = {
  meta: { type: 'problem', schema: [], messages: { literal: 'Используйте токен темы или CSS-переменную вместо цветового литерала.' } },
  create(context) {
    return { Program(node) {
      for (const item of literals(context.sourceCode.text)) {
        context.report({ node, messageId: 'literal', loc: {
          start: { line: item.line, column: item.column }, end: { line: item.line, column: item.column + item.length },
        } });
      }
    } };
  },
};

const cssSources = new Map<string, string>();
export const themeTokensPlugin = {
  rules: { 'no-color-literals': noColorLiterals },
  processors: { css: {
    preprocess(text: string, filename: string) { cssSources.set(filename, text); return ['']; },
    postprocess(messages: Linter.LintMessage[][], filename: string): Linter.LintMessage[] {
      const source = cssSources.get(filename) ?? '';
      cssSources.delete(filename);
      return [...(messages[0] ?? []), ...literals(source).map((item): Linter.LintMessage => ({
        ruleId: 'theme/no-color-literals', severity: 2, message: 'Используйте токен темы или CSS-переменную вместо цветового литерала.',
        line: item.line, column: item.column + 1,
      }))];
    },
  } },
};

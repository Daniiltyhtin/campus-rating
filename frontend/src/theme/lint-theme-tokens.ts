import type { Linter, Rule } from 'eslint';

const names = 'white|black|red|blue|green|gray|grey|yellow|orange|purple|pink|navy|teal|cyan|magenta|maroon|olive|lime|aqua|fuchsia|silver|gold|coral|crimson|brown|indigo|violet|beige|salmon|tomato';
const property = 'color|c|bg|background(?:-?color)?|border(?:-?(?:top|right|bottom|left))?(?:-?color)?|outline(?:-?color)?|fill|stroke|box-?shadow|text-?shadow|caret-?color|accent-?color';
const expression = new RegExp(
  `(?<![\\w&/])#[\\da-f]{3,8}\\b|\\b(?:rgba?|hsla?|hwb|lab|lch|oklab|oklch)\\s*\\(|\\b(?:${property})\\s*[:=]\\s*\\{?\\s*['"\\x60]?[^;'"\\x60\\n}]*?(?<![-\\w])(?:${names})\\b`,
  'gi',
);

function blank(text: string) {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, (comment) => comment.replace(/[^\n]/g, ' '))
    .replace(/(^|[^:])\/\/[^\n]*/g, (line, lead: string) => lead + ' '.repeat(line.length - lead.length))
    .replace(/\b(href|to|id)=(["'])#[^"']*\2/g, (attribute) => ' '.repeat(attribute.length));
}

function literals(text: string) {
  return [...blank(text).matchAll(expression)].map((match) => {
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

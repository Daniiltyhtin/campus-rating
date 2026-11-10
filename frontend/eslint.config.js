import js from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import { themeTokensPlugin } from './src/theme/lint-theme-tokens.ts';

export default tseslint.config(
  { ignores: ['dist'] },
  { files: ['src/features/**/*.{ts,tsx}'], plugins: { theme: themeTokensPlugin }, rules: { 'theme/no-color-literals': 'error' } },
  { files: ['src/features/**/*.css'], plugins: { theme: themeTokensPlugin }, processor: 'theme/css' },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: {
      ecmaVersion: 2023,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['error', { allowConstantExport: true }],
    },
  },
);

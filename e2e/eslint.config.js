import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['playwright-report/**', 'test-results/**', 'blob-report/**'] },
  {
    files: ['**/*.ts'],
    extends: [...tseslint.configs.recommendedTypeChecked],
    languageOptions: {
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
  },
);

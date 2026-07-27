import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import sonarjs from 'eslint-plugin-sonarjs';
import prettier from 'eslint-config-prettier';

// Flat config. Layers: JS recommended -> typescript-eslint (PMD-like) -> SonarJS
// (bug/complexity smells) -> React Hooks (deps/rules) -> Prettier last (disables any
// formatting rules so Prettier owns layout). `npm run lint` gates on errors.
export default tseslint.config(
  { ignores: ['dist', 'node', 'coverage', 'vite.config.ts'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  sonarjs.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    plugins: { 'react-hooks': reactHooks },
    rules: {
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',
      // Complexity / size gates — the analog of PMD + Checkstyle Method/FileLength.
      // Now errors: App.tsx was split into focused modules and the hot functions trimmed.
      'max-lines-per-function': ['error', { max: 200, skipBlankLines: true, skipComments: true }],
      complexity: ['error', 20],
      'sonarjs/cognitive-complexity': ['error', 15],
      // SonarJS readability smells that are idiomatic here (JSX ternaries, handlers/render
      // helpers defined inside components) — tracked as warnings, not build-breaking.
      'sonarjs/no-nested-conditional': 'warn',
      'sonarjs/no-nested-functions': 'warn',
    },
  },
  prettier,
);

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
      // Warnings for now (App.tsx is a known monolith to split); tighten to error later.
      'max-lines-per-function': ['warn', { max: 200, skipBlankLines: true, skipComments: true }],
      complexity: ['warn', 20],
      // SonarJS readability smells that are idiomatic here (JSX ternaries, handlers/render
      // helpers defined inside components, the big App component) — tracked as warnings,
      // not build-breaking. Genuine bug rules (identical-functions, etc.) stay errors.
      'sonarjs/no-nested-conditional': 'warn',
      'sonarjs/no-nested-functions': 'warn',
      'sonarjs/cognitive-complexity': 'warn',
    },
  },
  prettier,
);

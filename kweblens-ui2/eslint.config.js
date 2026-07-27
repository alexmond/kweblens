import js from '@eslint/js';
import pluginVue from 'eslint-plugin-vue';
import vueParser from 'vue-eslint-parser';
import tseslint from 'typescript-eslint';
import prettier from 'eslint-config-prettier';

// Flat config. Layers: JS recommended -> typescript-eslint -> eslint-plugin-vue
// (essential + strongly-recommended) -> Prettier last (disables formatting rules so
// Prettier owns layout). Complexity/size gates mirror the kweblens-ui (React) module.
export default tseslint.config(
  { ignores: ['dist', 'node', 'coverage', '.node'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    files: ['**/*.vue'],
    languageOptions: {
      parser: vueParser,
      parserOptions: { parser: tseslint.parser },
    },
  },
  {
    files: ['**/*.{ts,vue}'],
    rules: {
      'max-lines-per-function': ['error', { max: 220, skipBlankLines: true, skipComments: true }],
      complexity: ['error', 22],
      // Vue SFC <template> markup inflates the component function; keep it advisory there.
      'vue/multi-word-component-names': 'off',
      // TypeScript already resolves identifiers (incl. browser/DOM globals) via vue-tsc,
      // so core no-undef is redundant here and would false-positive on atob/window/etc.
      'no-undef': 'off',
    },
  },
  prettier,
);

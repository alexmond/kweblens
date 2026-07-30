import js from '@eslint/js';
import pluginVue from 'eslint-plugin-vue';
import vueParser from 'vue-eslint-parser';
import sonarjs from 'eslint-plugin-sonarjs';
import tseslint from 'typescript-eslint';
import prettier from 'eslint-config-prettier';

// Flat config. Layers: JS recommended -> typescript-eslint -> SonarJS (bug/complexity smells)
// -> eslint-plugin-vue (essential + strongly-recommended) -> Prettier last (disables formatting
// rules so Prettier owns layout). Same gate set as kweblens-ui (React), adapted for Vue SFCs.
export default tseslint.config(
  { ignores: ['dist', 'node', 'coverage', '.node'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  sonarjs.configs.recommended,
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
      // Complexity / size gates — the analog of PMD + Checkstyle Method/FileLength (match ui).
      'max-lines-per-function': ['error', { max: 200, skipBlankLines: true, skipComments: true }],
      complexity: ['error', 20],
      'sonarjs/cognitive-complexity': ['error', 15],
      // SonarJS readability smells that are idiomatic here (nested ternaries in templates,
      // handlers/render helpers defined inside components) — tracked as warnings, not gating.
      'sonarjs/no-nested-conditional': 'warn',
      'sonarjs/no-nested-functions': 'warn',
      // Vue SFC <template> markup inflates the component function; keep it advisory there.
      'vue/multi-word-component-names': 'off',
      // TypeScript already resolves identifiers (incl. browser/DOM globals) via vue-tsc,
      // so core no-undef is redundant here and would false-positive on atob/window/etc.
      'no-undef': 'off',
    },
  },
  {
    // Test fixtures legitimately contain literal addresses; they use the RFC 5737
    // documentation ranges (192.0.2.0/24, 198.51.100.0/24), never real or lab addresses.
    //
    // no-clear-text-protocols is off here for the same reason: an `http://…` in a fixture is
    // an EXPECTED RENDERING (a probe target label, built from the probe's own `scheme`), not
    // a request this code makes. The rule cannot tell a displayed string from a connection.
    files: ['**/*.test.ts'],
    rules: { 'sonarjs/no-hardcoded-ip': 'off', 'sonarjs/no-clear-text-protocols': 'off' },
  },
  prettier,
);

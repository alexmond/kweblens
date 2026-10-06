import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [vue()],
  test: {
    globals: true,
    environment: 'jsdom',
    // 'default' FIRST and explicitly: naming any reporter replaces the default set, so
    // omitting it here would silently delete the normal test output. The second one emits
    // the `[progress]` line scripts/progress-tap.py turns into this module's bar during a
    // reactor build; see vitest-progress.ts for why it counts files rather than tests.
    reporters: ['default', './vitest-progress.ts'],
    coverage: {
      provider: 'v8',
      include: ['src/kube.ts', 'src/columns.ts', 'src/navLabel.ts'],
      thresholds: { statements: 70, branches: 70, functions: 70, lines: 70 },
    },
  },
});

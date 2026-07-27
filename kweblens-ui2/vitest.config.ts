import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [vue()],
  test: {
    globals: true,
    environment: 'jsdom',
    coverage: {
      provider: 'v8',
      include: ['src/kube.ts', 'src/columns.ts'],
      thresholds: { statements: 70, branches: 70, functions: 70, lines: 70 },
    },
  },
});

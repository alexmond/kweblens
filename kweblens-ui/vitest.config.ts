import { defineConfig } from 'vitest/config';

// Unit tests + coverage gate (the JaCoCo analog). Coverage is currently scoped to the
// pure-logic modules that have tests (kube, columns); widen `include` and raise the
// thresholds as the suite grows, exactly as the Java JaCoCo gates are meant to.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
    coverage: {
      provider: 'v8',
      include: ['src/kube.ts', 'src/columns.ts'],
      reporter: ['text', 'html'],
      thresholds: { lines: 70, functions: 70, statements: 70, branches: 70 },
    },
  },
});

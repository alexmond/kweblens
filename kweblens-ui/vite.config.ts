import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// Built with base "/ui/" so bundled asset URLs resolve under the path kweblens-web serves
// the SPA at. In dev (`npm run dev`), proxy the JSON API + SSE to the running Spring Boot
// server on :8899, so the two-process dev flow mirrors venice-vr.
export default defineConfig({
  base: '/ui/',
  plugins: [react()],
  build: { outDir: 'dist', emptyOutDir: true },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8899', changeOrigin: true },
    },
  },
});

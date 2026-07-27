import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';

// Built with base "/ui/" so bundled asset URLs resolve under the path kweblens-web serves
// this SPA at. In dev (`npm run dev`), proxy the JSON API + SSE/WS to the running Spring Boot
// server on :8899, mirroring the kweblens-ui (React) dev flow.
export default defineConfig({
  base: '/ui/',
  plugins: [vue()],
  build: { outDir: 'dist', emptyOutDir: true },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8899', changeOrigin: true },
      '/ws': { target: 'http://localhost:8899', changeOrigin: true, ws: true },
      '/actuator': { target: 'http://localhost:8899', changeOrigin: true },
    },
  },
});

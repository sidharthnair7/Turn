import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The landing page builds into .. (frontend/, Spring's web root), next to the
// Floor and Wait pages, so `mvnw spring-boot:run` serves it at /.
// In dev, everything that isn't the landing page is proxied to Spring on :8080.
const spring = 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '..',
    emptyOutDir: false,
    assetsDir: 'assets'
  },
  server: {
    port: 5173,
    proxy: {
      '/api': spring,
      '/floor': spring,
      '/wait': spring,
      '/css': spring,
      '/js': spring
    }
  }
});

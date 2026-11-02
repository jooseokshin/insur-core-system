import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 개발 서버는 운영의 nginx 와 같은 경로 규칙으로 백엔드에 넘긴다.
// /api → 코어 백엔드(8080), /auth → 인증 서버(8081). 브라우저 입장에서는 같은 출처라 CORS 설정이 필요 없다.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: process.env.CORE_URL ?? 'http://localhost:8080', changeOrigin: false },
      '/auth': { target: process.env.AUTH_URL ?? 'http://localhost:8081', changeOrigin: false },
    },
  },
});

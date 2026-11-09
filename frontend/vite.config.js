import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 개발 서버는 운영의 nginx 와 같은 경로 규칙으로 백엔드에 넘긴다.
// /api → 코어 백엔드(8080), /auth 와 /.well-known(OAuth 메타데이터) → 인증 서버(8081). 브라우저 입장에서는 같은 출처라 CORS 설정이 필요 없다.
// 인증 서버가 돌아갈 주소로 localhost 를 받지 않으므로 http://127.0.0.1:5173 으로 연다 (localhost 로 열면 화면이 옮겨 준다).
export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': { target: process.env.CORE_URL ?? 'http://localhost:8080', changeOrigin: false },
      '/auth': { target: process.env.AUTH_URL ?? 'http://localhost:8081', changeOrigin: false },
      '/.well-known': { target: process.env.AUTH_URL ?? 'http://localhost:8081', changeOrigin: false },
    },
  },
});

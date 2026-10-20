import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // 포트 규칙은 docs/dev-env.md 참고
    port: 5173,
    strictPort: true,
    // /api 요청을 Spring 백엔드로 전달한다. 브라우저 기준으로 같은 출처라 CORS가 발생하지 않는다.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})

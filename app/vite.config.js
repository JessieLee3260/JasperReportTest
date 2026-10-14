import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // dev/preview 時把 /api 轉發給 Spring Boot 後端；
  // 正式使用直接 mvn spring-boot:run 開 31415，不需要 dev server
  server: {
    proxy: {
      '/api': { target: 'http://localhost:31415', changeOrigin: true },
    },
  },
  preview: {
    proxy: {
      '/api': { target: 'http://localhost:31415', changeOrigin: true },
    },
  },
})

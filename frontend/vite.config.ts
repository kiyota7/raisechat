import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// ポートは固定(バックエンド8080 / フロントエンド5173)。競合時は別ポートに逃がさず失敗させる
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': 'http://localhost:8080',
      '/uploads': 'http://localhost:8080',
    },
  },
})

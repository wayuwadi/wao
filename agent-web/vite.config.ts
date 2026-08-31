import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  base: '/agent/',
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5174,
    proxy: {
      '/api': { target: process.env.AGENT_API_TARGET ?? 'http://localhost:8081', changeOrigin: true },
      '/actuator': { target: process.env.AGENT_API_TARGET ?? 'http://localhost:8081', changeOrigin: true },
    },
  },
})

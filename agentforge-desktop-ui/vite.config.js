import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue2'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { loadAppMeta } from './scripts/lib/package-config.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const { appName, appTag } = loadAppMeta()

export default defineConfig({
  plugins: [vue()],
  base: './',
  define: {
    __APP_NAME__: JSON.stringify(appName),
    __APP_TAG__: JSON.stringify(appTag)
  },
  resolve: {
    alias: { '@': path.resolve(__dirname, 'src') }
  },
  server: {
    host: '127.0.0.1',
    port: 1420,
    proxy: {
      '/api': {
        target: process.env.VITE_PROXY_TARGET || 'http://127.0.0.1:43179',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true
  }
})

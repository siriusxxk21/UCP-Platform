import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 单独加载已安装的工程依赖，不接入正式路由、认证或开发数据库。
export default defineConfig({
  root: fileURLToPath(new URL('.', import.meta.url)),
  plugins: [vue()],
  cacheDir: '../../node_modules/.vite-page-container-probe',
  server: { host: '127.0.0.1', port: 5187, strictPort: true },
  build: { outDir: '../../node_modules/.page-container-probe-dist', emptyOutDir: true }
})

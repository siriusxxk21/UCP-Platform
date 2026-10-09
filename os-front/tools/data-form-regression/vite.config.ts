import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
export default defineConfig({
  root: fileURLToPath(new URL('.', import.meta.url)),
  plugins: [
    vue(),
    {
      name: 'form-regression-empty-directory',
      configureServer(server) {
        // 独立打开验证页也使用空目录夹具，不依赖浏览器测试器拦截或开发库登录。
        server.middlewares.use((req, res, next) => {
          if (req.method !== 'GET' || req.url?.split('?')[0] !== '/api/nocode/design/page') return next()
          res.setHeader('Content-Type', 'application/json')
          res.end(JSON.stringify({ code: 0, data: { list: [], total: 0 } }))
        })
      }
    }
  ],
  resolve: { alias: { '@': fileURLToPath(new URL('../../src', import.meta.url)) } },
  cacheDir: '../../node_modules/.vite-data-form-regression',
  server: { host: '127.0.0.1', port: 5196, strictPort: true }
})

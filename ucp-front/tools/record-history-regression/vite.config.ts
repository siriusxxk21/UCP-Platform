import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
import { response } from './fixture.mjs'
export default defineConfig({
  root: fileURLToPath(new URL('.', import.meta.url)),
  plugins: [
    vue(),
    {
      name: 'record-history-fixture',
      configureServer(server) {
        server.middlewares.use('/fixture/nocode/record-history', async (req, res) => {
          try {
            let body = ''
            for await (const chunk of req) body += chunk
            res.setHeader('Content-Type', 'application/json')
            res.end(JSON.stringify(response(req.url, JSON.parse(body))))
          } catch {
            res.statusCode = 400
            res.end('Invalid fixture query')
          }
        })
      }
    }
  ],
  resolve: { alias: { '@': fileURLToPath(new URL('../../src', import.meta.url)) } },
  cacheDir: '../../node_modules/.vite-record-history-regression',
  server: { host: '127.0.0.1', port: 5199, strictPort: true }
})

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'node:path'

export default defineConfig({
  cacheDir: 'node_modules/.vite-task-regression',
  optimizeDeps: { entries: ['tools/task-regression/preview.html'] },
  plugins: [vue()],
  resolve: {
    alias: [
      {
        find: '@/utils/request',
        replacement: resolve(process.cwd(), 'tools/task-regression/preview-api.ts')
      },
      ...['task', 'processInstance', 'category', 'definition'].map(name => ({
        find: `@/api/bpm/${name}`,
        replacement: resolve(process.cwd(), 'tools/task-regression/preview-api.ts')
      })),
      { find: '@', replacement: resolve(process.cwd(), 'src') }
    ]
  },
  server: { host: '127.0.0.1', port: 5183, strictPort: true, open: false }
})

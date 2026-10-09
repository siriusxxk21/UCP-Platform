import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'node:path'
export default defineConfig({
  plugins: [vue()],
  cacheDir: 'node_modules/.vite-workflow-workbench-preview',
  optimizeDeps: { entries: ['tools/workflow-workbench-preview/index.html'] },
  resolve: {
    alias: [
      { find: '@/stores/user', replacement: resolve(process.cwd(), 'tools/workflow-workbench-preview/user.ts') },
      { find: '@/utils/request', replacement: resolve(process.cwd(), 'tools/workflow-workbench-preview/request.ts') },
      { find: '@', replacement: resolve(process.cwd(), 'src') }
    ]
  },
  server: { host: '127.0.0.1', port: 5185, strictPort: true, open: false }
})

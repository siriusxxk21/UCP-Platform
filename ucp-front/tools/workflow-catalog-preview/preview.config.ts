import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'node:path'

export default defineConfig({
  cacheDir: 'node_modules/.vite-workflow-catalog-preview',
  optimizeDeps: { entries: ['tools/workflow-catalog-preview/preview.html'] },
  plugins: [vue()],
  resolve: {
    alias: [
      ...[
        '@/api/bpm/model',
        '@/api/bpm/category',
        '@/stores/user',
        '@/api/bpm/form',
        '@/api/bpm/definition',
        '@/api/bpm/processInstance',
        '@/api/system/user',
        '@/api/system/organization',
        '@/api/system/department'
      ].map(find => ({
        find,
        replacement: resolve(process.cwd(), 'tools/workflow-catalog-preview/preview-api.ts')
      })),
      { find: '@', replacement: resolve(process.cwd(), 'src') }
    ]
  },
  server: { host: '127.0.0.1', port: 5184, strictPort: true, open: false }
})

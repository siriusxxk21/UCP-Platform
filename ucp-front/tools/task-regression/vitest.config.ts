import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'node:path'

export default defineConfig({
  plugins: [vue()],
  resolve: { alias: { '@': resolve(process.cwd(), 'src') } },
  test: { environment: 'jsdom', include: ['tools/task-regression/*.test.ts'] }
})

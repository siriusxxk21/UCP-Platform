import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'node:path'

/** 仅运行于隔离工作副本；不启动用户服务，不调用真实业务接口。 */
export default defineConfig({
  plugins: [vue()],
  resolve: { alias: { '@': resolve(process.cwd(), 'src') } },
  test: {
    environment: 'jsdom',
    include: [
      'tools/four-center-regression/*.test.ts',
      'src/nocode/task-entry.test.ts',
      'src/nocode/detail-grid.test.ts',
      'src/nocode/form-behavior.test.ts',
      'src/nocode/form-fill.test.ts'
    ]
  }
})

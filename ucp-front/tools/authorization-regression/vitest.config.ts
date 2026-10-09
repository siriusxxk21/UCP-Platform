import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'node:path'

/** 当前工程的授权组件回归；接口使用替身，不写入业务授权。 */
export default defineConfig({
  plugins: [vue()],
  resolve: { alias: { '@': resolve(process.cwd(), 'src') } },
  test: {
    environment: 'jsdom',
    include: ['src/nocode/authorization-save-state.test.ts', 'src/nocode/object-sharing.test.ts']
  }
})

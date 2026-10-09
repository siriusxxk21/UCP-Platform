import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  test: {
    // 多个真实设计器同时编译和挂载会挤占开发机，限制并发以免正常交互被误判为超时。
    maxWorkers: 2,
    include: ['src/nocode/**/*.test.ts', 'tests/unit/nocode-transport.test.ts']
  }
})

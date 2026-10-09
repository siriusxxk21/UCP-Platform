import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'node:path'

// 用 Vue 自定义 renderer 执行真实组件事件，测试不依赖浏览器 DOM；模板必须按客户端编译。
const plugin = vue()
const transform = plugin.transform as { handler: (...args: any[]) => any }
const handler = transform.handler
transform.handler = function (code, id, options) {
  return handler.call(this, code, id, { ...options, ssr: false })
}
export default defineConfig({
  plugins: [plugin],
  resolve: { alias: { '@': resolve(process.cwd(), 'src') } },
  test: { environment: 'node', include: ['tools/selection-regression/*.test.ts'] }
})

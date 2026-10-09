import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'node:path'

const plugin = vue()
const transform = plugin.transform as { handler: (...args: any[]) => any }
const handler = transform.handler
transform.handler = function (code, id, options) {
  return handler.call(this, code, id, { ...options, ssr: false })
}
export default defineConfig({
  plugins: [plugin],
  define: { __APP_BUILD_INFO__: JSON.stringify({ commit: 'feedback-test' }) },
  resolve: { alias: { '@': resolve(process.cwd(), 'src') } },
  test: {
    environment: 'node',
    include: ['tools/feedback-regression/*.test.ts', 'src/components/system-feedback/*.test.ts']
  }
})

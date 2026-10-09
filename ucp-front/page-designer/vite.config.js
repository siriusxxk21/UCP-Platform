import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'
import { nodePolyfills } from 'vite-plugin-node-polyfills'
import { createSvgIconsPlugin } from 'vite-plugin-svg-icons'

// 与底座隔离依赖版本，产物随主站部署；设计画布只展示物料，不接收令牌或访问业务接口。
const path = value => fileURLToPath(new URL(value, import.meta.url))
export default defineConfig({
  base: '/nocode-designer/',
  plugins: [
    vue(),
    nodePolyfills({
      include: ['buffer', 'process', 'path', 'assert', 'util'],
      globals: { Buffer: true, global: true, process: true }
    }),
    createSvgIconsPlugin({ iconDirs: [path('./node_modules/@opentiny/tiny-engine/assets')], symbolId: 'icon-[name]' })
  ],
  resolve: { dedupe: ['vue', 'vue-router'] },
  define: { __TINY_ENGINE_REMOVED_REGISTRY: {}, 'process.env': {} },
  server: { fs: { allow: [path('../')] } },
  optimizeDeps: {
    include: ['@opentiny/tiny-engine', '@opentiny/tiny-engine-canvas/render', 'ant-design-vue'],
    exclude: ['@vue/repl']
  },
  build: {
    outDir: '../public/nocode-designer',
    emptyOutDir: true,
    // 设计器和画布包含较大的引擎依赖，生产包必须压缩，避免 HTTP 首次打开下载数十 MB。
    minify: 'esbuild',
    rollupOptions: { maxParallelFileOps: 8, input: { editor: path('./index.html'), canvas: path('./canvas.html') } }
  }
})

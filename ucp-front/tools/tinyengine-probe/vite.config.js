import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'
import { realpathSync } from 'node:fs'
import globals from '@esbuild-plugins/node-globals-polyfill'
import modules from '@esbuild-plugins/node-modules-polyfill'
import { createSvgIconsPlugin } from 'vite-plugin-svg-icons'

// 单独的验证入口，避免把尚未选定的引擎带进正式应用构建。
export default defineConfig({
  root: fileURLToPath(new URL('.', import.meta.url)),
  plugins: [
    vue(),
    createSvgIconsPlugin({
      iconDirs: [fileURLToPath(new URL('./node_modules/@opentiny/tiny-engine/assets', import.meta.url))],
      symbolId: 'icon-[name]'
    })
  ],
  resolve: { dedupe: ['vue', 'vue-router'] },
  define: { __TINY_ENGINE_REMOVED_REGISTRY: {}, 'process.env': {} },
  server: {
    host: '127.0.0.1',
    port: 5188,
    strictPort: true,
    fs: {
      allow: [
        fileURLToPath(new URL('../../../', import.meta.url)),
        realpathSync(fileURLToPath(new URL('./node_modules', import.meta.url)))
      ]
    },
    proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true } }
  },
  optimizeDeps: {
    include: [
      '@opentiny/tiny-engine',
      '@opentiny/tiny-engine-canvas/render',
      'ant-design-vue',
      '@form-create/ant-design-vue'
    ],
    exclude: ['@vue/repl'],
    esbuildOptions: { plugins: [globals.default({ process: true, buffer: true }), modules.default()] }
  },
  build: {
    outDir: 'node_modules/.tinyengine-probe-dist',
    emptyOutDir: true,
    rollupOptions: {
      input: {
        editor: fileURLToPath(new URL('./index.html', import.meta.url)),
        canvas: fileURLToPath(new URL('./canvas.html', import.meta.url)),
        preview: fileURLToPath(new URL('./preview.html', import.meta.url))
      }
    }
  }
})

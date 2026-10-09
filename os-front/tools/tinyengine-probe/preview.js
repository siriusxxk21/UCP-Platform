import { reactive, watch, watchEffect } from 'vue'
import { createRender } from '@opentiny/tiny-engine-canvas/render'
import { useMessage } from '@opentiny/tiny-engine-meta-register'
import { components, installComponents } from './components.js'
import { readSchema, materials } from './schema.js'

// 复用同一个引擎渲染器，以独立 HTML 运行保存后的 DSL，不装载编辑器外壳。
const schema = reactive(readSchema())
const messages = useMessage()
window.probeErrors = []
window.TinyGlobalConfig = { enableTailwindCSS: false }
window.host = {
  ...messages,
  watch,
  watchEffect,
  getSchema: () => schema,
  appSchema: { utils: [], globalState: [], dataSource: [] },
  getSchemaDiff: () => undefined,
  patchLatestSchema: () => {},
  schemaUtils: { getSchema: () => schema }
}
const controller = {
  getBaseInfo: () => ({ pageId: 'probe', id: 'probe' }),
  getPageById: async () => ({ page_content: schema }),
  getPageAncestors: async () => [],
  getMaterial: name => materials.components.find(item => item.component === name) || {},
  addHistoryDataChangedCallback: () => () => {},
  updatePreviewId: () => {},
  useMessage: () => messages,
  useNotify: () => {},
  useModal: () => ({}),
  getBlockByName: async () => null
}
document.addEventListener('canvasReady', event => {
  event.detail.setController(controller)
  event.detail.setDesignMode('normal')
})
createRender({
  canvasDependencies: { scripts: [], styles: [] },
  lifeCycles: {
    appCreated: (app, { api }) => {
      Object.assign(window.TinyLowcodeComponent, components)
      installComponents(app)
      const reportError = error => window.probeErrors.push(error.stack || error.message)
      app.config.errorHandler = reportError
      app.mixin({
        mounted() {
          if (this.$root !== this) return
          queueMicrotask(() => {
            // 渲染器在根节点挂载时订阅 schemaImport，之后才导入保存的页面结构。
            app.config.errorHandler = reportError
            document.body.classList.add('runtime-preview')
            api.setDesignMode('normal')
            messages.publish({ topic: 'schemaImport', data: { current: schema } })
          })
        }
      })
    }
  }
})

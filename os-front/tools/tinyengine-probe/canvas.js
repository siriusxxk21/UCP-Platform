import { createRender } from '@opentiny/tiny-engine-canvas/render'
import { components, installComponents } from './components.js'

// 使用引擎公开的画布入口与生命周期注册 OS 组件；不修改依赖源码。
createRender({
  canvasDependencies: { scripts: [], styles: [] },
  lifeCycles: {
    appCreated: app => {
      Object.assign(window.TinyLowcodeComponent, components)
      installComponents(app)
      window.probeErrors = []
      const reportError = error => window.probeErrors.push(error.stack || error.message)
      app.config.errorHandler = reportError
      app.mixin({
        mounted() {
          if (this.$root === this)
            queueMicrotask(() => {
              app.config.errorHandler = reportError
            })
        }
      })
    }
  }
})

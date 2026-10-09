import { createRender } from '@opentiny/tiny-engine-canvas/render'
import { components } from './components.js'
createRender({
  canvasDependencies: { scripts: [], styles: [] },
  lifeCycles: {
    appCreated: app => {
      Object.assign(window.TinyLowcodeComponent, components)
      Object.entries(components).forEach(([name, component]) => app.component(name, component))
    }
  }
})

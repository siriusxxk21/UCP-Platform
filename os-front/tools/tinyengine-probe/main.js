import {
  init,
  Canvas,
  defineService,
  useCanvas,
  useMaterial,
  useLayout,
  useHistory,
  useMessage,
  META_APP,
  META_SERVICE
} from '@opentiny/tiny-engine'
import { watch, nextTick } from 'vue'
import { materials, initialSchema, readSchema, STORAGE_KEY } from './schema.js'
import './probe.css'
import 'virtual:svg-icons-register'

const status = text => {
  document.querySelector('#status').textContent = text
}
const clone = value => JSON.parse(JSON.stringify(value))
const evidence = { ready: false, saved: 0, restored: 0 }
if (!location.search) history.replaceState({}, '', '?type=app&id=probe&pageid=probe')
function updateEvidence() {
  const canvas = useCanvas()
  document.querySelector('#evidence').textContent = JSON.stringify({
    ...evidence,
    selected: canvas.getCurrentSchema()?.id,
    schema: canvas.getSchema(),
    history: useHistory().historyState
  })
}

// 配置原始画布元对象，保留 apis 内响应式状态引用。2.11 的注册表深合并会复制 pageState。
Canvas.options = { ...Canvas.options, canvasSrc: '/canvas.html' }
await init({
  registry: [
    {
      'engine.config': {
        theme: 'light',
        material: [],
        scripts: [],
        styles: [],
        enableTailwindCSS: false,
        canvasDependencies: { scripts: [], styles: [] }
      },
      // 选型工具直接装配一个本地验证页面；正式接入时复用 OS 应用服务和授权。
      [META_SERVICE.GlobalService]: defineService({
        id: META_SERVICE.GlobalService,
        type: 'MetaService',
        init: ({ state }) => {
          state.appInfo = { id: 'probe', name: 'OS 页面验证', platform: {} }
          state.userInfo = { id: 'probe', username: '本地验证' }
        },
        apis: { getUserInfo: () => ({ id: 'probe', username: '本地验证' }) }
      }),
      [META_SERVICE.Resource]: { apis: { fetchResource: async () => {} } },
      [META_SERVICE.Http]: {
        apis: {
          get: async url => {
            if (url.includes('pages/list'))
              return [
                {
                  id: 'probe',
                  parentId: '0',
                  fileName: '订单经营工作台',
                  group: 'staticPages',
                  meta: { route: 'orders', rootElement: 'Page' }
                }
              ]
            if (url.includes('block-groups') || url.includes('extension/list')) return []
            if (url.includes('pages/detail')) return { id: 'probe', name: '订单经营工作台', page_content: readSchema() }
            throw new Error(`验证页未接入 TinyEngine 平台接口：${url}`)
          },
          getHttp: () => ({ get: async () => ({}), post: async () => ({}) })
        }
      },
      [META_APP.Layout]: {
        options: {
          isShowWorkspace: false,
          layoutConfig: {
            plugins: {
              left: { top: [META_APP.Materials, META_APP.OutlineTree], bottom: [] },
              right: { top: [META_APP.Props, META_APP.Styles] }
            },
            toolbars: { left: [], center: [META_APP.Media], right: [[META_APP.RedoUndo]], collapse: [], setting: [] }
          }
        }
      }
    }
  ],
  lifeCycles: {
    appMounted: () => {
      useMaterial().addMaterials(materials)
      const canvas = useCanvas()
      canvas.pageState.loading = false
      useLayout().layoutState.pageStatus = { state: 'occupy', data: {} }
      watch(
        canvas.isCanvasApiReady,
        async ready => {
          if (!ready || evidence.ready) return
          canvas.initData(readSchema(), { id: 'probe', name: '订单经营工作台' })
          await nextTick()
          evidence.ready = true
          status('已就绪 · 独立验证草稿')
          updateEvidence()
        },
        { immediate: true }
      )
      watch(() => [canvas.getSchema(), canvas.getCurrentSchema()], updateEvidence, { deep: true })
    }
  }
})

document.querySelector('#save').onclick = () => {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(useCanvas().getSchema()))
  useCanvas().setSaved(true)
  evidence.saved++
  status('草稿已保存到本机浏览器')
  updateEvidence()
}
document.querySelector('#restore').onclick = () => {
  useCanvas().initData(readSchema(), { id: 'probe', name: '订单经营工作台' })
  evidence.restored++
  status('已恢复草稿')
  updateEvidence()
}
document.querySelector('#reset').onclick = () => {
  useCanvas().initData(initialSchema(), { id: 'probe', name: '订单经营工作台' })
  status('示例已重置，保存后覆盖本地验证草稿')
}
document.querySelector('#preview').onclick = () => {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(useCanvas().getSchema()))
  window.open('/preview.html', '_blank', 'noopener')
}

// 只暴露无凭据的公开引擎 API，供可重复诊断判断选中、嵌套和撤销状态。
window.probe = {
  schema: () => clone(useCanvas().getSchema()),
  canvas: useCanvas,
  history: useHistory,
  layout: useLayout,
  message: useMessage
}

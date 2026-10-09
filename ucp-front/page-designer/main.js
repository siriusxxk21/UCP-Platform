import { v4 as uuidv4 } from 'uuid'
import {
  init,
  Canvas,
  defineService,
  useCanvas,
  useMaterial,
  useHistory,
  useLayout,
  META_APP,
  META_SERVICE
} from '@opentiny/tiny-engine'
import { watch, nextTick } from 'vue'
import { materials } from './materials.js'
import { listModeAttributes } from '../src/nocode/page-list-mode'
import { deferCanvasRect } from '../src/nocode/page-canvas-rect'
import { pickHostProps } from '../src/nocode/page-designer-props'
import { nestingViolation } from '../src/nocode/page-nesting'
import './designer.css'
import 'virtual:svg-icons-register'

const clone = value => JSON.parse(JSON.stringify(value))
const empty = () => ({
  componentName: 'Page',
  fileName: 'OsBusinessPage',
  props: {},
  children: [],
  state: {},
  methods: {},
  css: '',
  lifeCycles: {},
  inputs: [],
  outputs: []
})
let initialized = false,
  pending,
  ready = false
let focused = false,
  pluginBeforeFocus = ''
// 使用公开布局 API 适应可用画布，避免窄窗口将 1200px 页面缩成不可读的缩略图。
function fitCanvas() {
  const wrap = document.querySelector('#canvas-wrap')
  if (!wrap) return
  useLayout().setDimension({
    width: `${Math.max(320, wrap.clientWidth - 24)}px`,
    minWidth: '320px',
    maxWidth: '1920px',
    scale: 1
  })
}
addEventListener('resize', () => {
  if (ready) fitCanvas()
})
const send = (type, data = {}) => parent.postMessage({ channel: 'os-page-designer', type, ...data }, location.origin)
function load() {
  if (!ready || !pending || initialized) return
  initialized = true
  useCanvas().initData(pending.schema, { id: 'os-page', name: pending.name || '业务页面' })
}
// 只接受同站父页面的受控草稿；不传递用户令牌，不建立 TinyEngine 平台服务。
addEventListener('message', event => {
  if (event.source !== parent || event.origin !== location.origin || event.data?.channel !== 'os-page-designer') return
  if (event.data.type === 'INIT') {
    pending = event.data
    load()
  }
  if (event.data.type === 'FOCUS' && focused !== !!event.data.enabled) {
    const layout = useLayout()
    focused = !!event.data.enabled
    if (focused) {
      pluginBeforeFocus = layout.layoutState.plugins.render
      layout.closePlugin(true)
    } else if (pluginBeforeFocus) layout.activePlugin(pluginBeforeFocus)
  }
  if (event.data.type === 'INSERT' && initialized) {
    const canvas = useCanvas()
    const entry = materials.snippets
      .flatMap(group => group.children)
      .find(item => item.snippetName === event.data.componentName)
    const found = event.data.parentId ? canvas.getNodeWithParentById(event.data.parentId) : { node: canvas.getSchema() }
    if (!entry || !found?.node) return
    const parentName = found.node.componentName
    const config = materials.components.find(item => item.component === parentName)?.configure
    const allowed = parentName === 'Page' || config?.isContainer
    const childAllowed =
      !config?.nestingRule?.childWhitelist || config.nestingRule.childWhitelist.includes(entry.snippetName)
    const childConfig = materials.components.find(item => item.component === entry.snippetName)?.configure
    const parentAllowed =
      !childConfig?.nestingRule?.parentWhitelist || childConfig.nestingRule.parentWhitelist.includes(parentName)
    if (!allowed || !childAllowed || !parentAllowed) return
    const identify = node => ({
      ...clone(node),
      id: uuidv4(),
      children: (node.children || []).map(identify)
    })
    // 使用引擎自身的插入、选中及历史记录，快捷添加与拖拽编辑同一份结构。
    canvas.canvasApi.value.insertNode({ ...found, data: identify(entry.schema) }, 'in')
    canvas.setSaved(false)
  }
  if (event.data.type === 'LIST_MODE' && initialized) {
    const canvas = useCanvas()
    const selected = canvas.getCurrentSchema()
    if (selected?.id !== event.data.id || typeof event.data.related !== 'boolean') return
    const value = listModeAttributes(selected, event.data.related)
    if (!value) return
    // 沿用引擎节点更新和历史栈，切换后原有按钮目标与页签位置仍指向同一节点。
    canvas.operateNode({ type: 'updateAttributes', id: selected.id, value })
    useHistory().addHistory()
    canvas.setSaved(false)
  }
  if (event.data.type === 'PROPS' && initialized) {
    const selected = useCanvas().getCurrentSchema()
    if (selected?.id !== event.data.id) return
    // 允许写入的键与 pageSchema() 生成的属性同表维护（见 page-designer-props.ts）
    const props = pickHostProps(event.data.props)
    useCanvas().operateNode({ type: 'changeProps', id: selected.id, value: { props } })
    // 宿主属性面板与引擎拖拽共用历史栈，属性修改也必须能够撤销和重做。
    useHistory().addHistory()
    useCanvas().setSaved(false)
  }
})
Canvas.options = { ...Canvas.options, canvasSrc: '/nocode-designer/canvas.html' }
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
      [META_SERVICE.GlobalService]: defineService({
        id: META_SERVICE.GlobalService,
        type: 'MetaService',
        init: ({ state }) => {
          state.appInfo = { id: 'os', name: '页面设计', platform: {} }
          state.userInfo = { id: 'designer', username: '搭建者' }
        },
        apis: { getUserInfo: () => ({ id: 'designer', username: '搭建者' }) }
      }),
      [META_SERVICE.Resource]: { apis: { fetchResource: async () => {} } },
      [META_SERVICE.Http]: {
        apis: {
          // 全部网络写入及 AI 请求由本地适配层明确拒绝，不继承引擎默认后台接口。
          request: async () => {
            throw new Error('页面编辑器未开放网络服务')
          },
          post: async () => {
            throw new Error('页面编辑器未开放网络服务')
          },
          put: async () => {
            throw new Error('页面编辑器未开放网络服务')
          },
          delete: async () => {
            throw new Error('页面编辑器未开放网络服务')
          },
          get: async url => {
            if (url.includes('pages/list'))
              return [
                {
                  id: 'os-page',
                  parentId: '0',
                  fileName: '业务页面',
                  group: 'staticPages',
                  meta: { route: 'page', rootElement: 'Page' }
                }
              ]
            if (url.includes('pages/detail')) return { id: 'os-page', name: '业务页面', page_content: empty() }
            if (url.includes('block-groups') || url.includes('extension/list')) return []
            throw new Error('此服务由 OS 应用中心管理')
          },
          getHttp: () => ({})
        }
      },
      [META_APP.Materials]: { width: 240 },
      [META_APP.OutlineTree]: { width: 240 },
      [META_APP.Layout]: {
        options: {
          isShowWorkspace: false,
          layoutConfig: {
            plugins: { left: { top: [META_APP.Materials, META_APP.OutlineTree], bottom: [] }, right: { top: [] } },
            toolbars: { left: [], center: [META_APP.Media], right: [[META_APP.RedoUndo]], collapse: [], setting: [] }
          }
        }
      }
    }
  ],
  lifeCycles: {
    appCreated: ({ app }) => {
      const canvas = useCanvas()
      // 在引擎注册尺寸监听前包装公开 API，删除/插入、ResizeObserver 共用安全的选框刷新。
      const stop = watch(
        canvas.canvasApi,
        (api, previous, onCleanup) => {
          if (typeof api.updateRect === 'function')
            onCleanup(deferCanvasRect(api, id => !!canvas.getNodeWithParentById(id)?.node))
        },
        { immediate: true, flush: 'sync' }
      )
      app.onUnmount(stop)
      addEventListener('pagehide', event => {
        if (!event.persisted) stop()
      })
    },
    appMounted: () => {
      useMaterial().addMaterials(materials)
      const canvas = useCanvas()
      canvas.pageState.loading = false
      useLayout().layoutState.pageStatus = { state: 'occupy', data: {} }
      // 高度与引擎缩放比例同步；隐藏地址栏后不再保留它占用的画布留白。
      watch(
        () => useLayout().getDimension().scale,
        scale => document.documentElement.style.setProperty('--os-canvas-scale', String(scale || 1)),
        { immediate: true }
      )
      watch(
        canvas.isCanvasApiReady,
        async value => {
          if (!value) return
          ready = true
          load()
          await nextTick()
          fitCanvas()
          send('READY')
        },
        { immediate: true }
      )
      // 属性侧栏改变可用画布宽度时也重新适配，避免固定宽度缩略图。
      const wrap = document.querySelector('#canvas-wrap')
      if (wrap)
        new ResizeObserver(() => {
          if (ready) fitCanvas()
        }).observe(wrap)
      // 引擎拖放只在「放到容器内部」时看子节点白名单，放到某个节点上方 / 下方时不看父容器（悬停在页签容器空白处会被改指向最后一个页签的下方），
      // 也从不看 parentWhitelist：把页签容器拖进页签容器就成了与页签并排。结构一出现父子不合规就用引擎自己的历史栈撤回这一步，
      // 不把不合规结构交给宿主，并告诉宿主给出提示（业务方 2026-10-04）。只有上一次结构合规时才撤回，存量本就不合规的页面照常编辑。
      let lastValid = null,
        reverting = 0
      watch(
        () => [canvas.getSchema(), canvas.getCurrentSchema()],
        () => {
          if (!initialized) return
          const schema = canvas.getSchema()
          const violation = nestingViolation(schema)
          if (violation && lastValid && reverting < 2) {
            reverting++
            const history = useHistory()
            if (reverting === 1 && history.historyState?.back) history.back()
            else canvas.resetCanvasState({ ...canvas.pageState, pageSchema: clone(lastValid) })
            if (reverting === 1) send('REJECTED', { violation })
            return
          }
          reverting = 0
          if (!violation) lastValid = clone(schema)
          send('CHANGE', { schema: clone(schema), selected: clone(canvas.getCurrentSchema() || null) })
        },
        { deep: true }
      )
    }
  }
})

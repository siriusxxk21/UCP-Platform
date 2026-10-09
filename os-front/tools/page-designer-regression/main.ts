import { createApp, defineComponent, h, ref } from 'vue'
import Antd from 'ant-design-vue'
import TinyPageDesigner from '../../src/views/nocode/application/components/TinyPageDesigner.vue'
import { NodeKind, uiNode } from '../../src/types/nocode/application-ui'
import './style.css'

// 在当前 Vite 开发工程装载真实宿主、静态编辑器与画布，不访问或修改业务数据。
const cards = [
  uiNode(NodeKind.CARD, {
    id: 'card-a',
    text: '卡片甲',
    children: [
      uiNode(NodeKind.TEXT, { id: 'text-a', text: '拖拽文字甲' }),
      uiNode(NodeKind.TEXT, { id: 'text-b', text: '拖拽文字乙' })
    ]
  }),
  uiNode(NodeKind.CARD, {
    id: 'card-b',
    text: '卡片乙',
    children: [uiNode(NodeKind.HEADING, { id: 'heading', text: '目标标题' })]
  })
]

// ?fixture=tabs：两个页签容器 + 一张卡片，验证页签容器只能直接放页签（verify-tabs.mjs，业务方 2026-10-04）。
const tabs = [
  uiNode(NodeKind.TABS, {
    id: 'tabs-a',
    text: '民宿页签',
    children: [
      uiNode(NodeKind.TAB, {
        id: 'tab-1',
        text: '基本信息',
        children: [uiNode(NodeKind.TEXT, { id: 'text-in-tab', text: '页签内文字' })]
      }),
      uiNode(NodeKind.TAB, {
        id: 'tab-2',
        text: '相关业务',
        children: [uiNode(NodeKind.HEADING, { id: 'tab-2-heading', text: '相关业务标题' })]
      })
    ]
  }),
  uiNode(NodeKind.TABS, {
    id: 'tabs-b',
    text: '第二个页签容器',
    children: [uiNode(NodeKind.TAB, { id: 'tab-3', text: '其它' })]
  }),
  uiNode(NodeKind.CARD, {
    id: 'card-a',
    text: '卡片甲',
    children: [uiNode(NodeKind.TEXT, { id: 'loose-text', text: '卡片里的文字' })]
  })
]
const nodes = new URLSearchParams(location.search).get('fixture') === 'tabs' ? tabs : cards

createApp(
  defineComponent({
    setup() {
      const designer = ref<InstanceType<typeof TinyPageDesigner>>()
      Object.assign(window, {
        designerRegression: {
          nodes: () => designer.value?.getNodes(),
          ready: () => designer.value?.isReady(),
          dirty: () => designer.value?.hasChanges(),
          toggleProperties: () => designer.value?.toggleProperties(),
          toggleFocus: () => designer.value?.toggleFocus()
        }
      })
      return () => h(TinyPageDesigner, { ref: designer, nodes, resources: [], objects: {} })
    }
  })
)
  .use(Antd)
  .mount('#app')

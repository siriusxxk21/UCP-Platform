import { createApp, defineComponent, h, onMounted, ref } from 'vue'
import { createRouter, createWebHashHistory, RouterLink, RouterView } from 'vue-router'
import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import '@/style.css'
import './preview.css'
import FlowTask from '@/views/nocode/application/flow-task.vue'
import Review from '@/views/bpm/processInstance/detail/index.vue'
import { failNextMaterial } from './request'
import ProcessViewer from '@/views/bpm/components/bpmn-process-designer/package/designer/ProcessViewer.vue'
import SimpleViewer from '@/views/bpm/components/simple-process-design/components/simple-process-viewer.vue'
import { nocodePlatformKey, type NocodePlatform } from '@/nocode/platform'
import { prepareBpmnView } from '@/views/bpm/components/bpmn-process-designer/package/designer/bpmn-view'
import { legacyXml, simpleNode } from './fixtures'
const tasks = [{ id: 'editing', taskDefinitionKey: 'business_work', status: 1 }]
const diagram = defineComponent({
  setup() {
    const xml = ref('')
    onMounted(async () => (xml.value = (await prepareBpmnView(legacyXml)).xml))
    return () =>
      h('section', [h('h2', 'BPMN 流程（已有图形布局）'), h(ProcessViewer, { xml: xml.value, view: { tasks } })])
  }
})
const routes = [
  { path: '/', name: 'NocodeFlowTask', component: FlowTask },
  { path: '/simple', component: { render: () => h(SimpleViewer, { flowNode: simpleNode, tasks }) } },
  { path: '/bpmn', component: diagram },
  { path: '/legacy', component: { render: () => h(ProcessViewer, { xml: legacyXml, view: { tasks } }) } },
  {
    path: '/process',
    name: 'TaskInstanceDetail',
    component: Review
  },
  { path: '/records', name: 'NocodeApplicationRuntime', component: { render: () => h('div', '模拟业务应用已到达') } }
]
const router = createRouter({ history: createWebHashHistory(), routes })
const links = [
  { to: '/process?id=review-instance&taskId=review-task', label: '审批材料汇总' },
  { to: '/process?id=legacy-review-instance&taskId=task-company', label: '旧流程材料' },
  { to: '/?taskId=editing', label: '长表单办理' },
  { to: '/?taskId=submitted', label: '已提交材料' },
  { to: '/simple', label: '简易流程' },
  { to: '/bpmn', label: 'BPMN流程' },
  { to: '/legacy', label: '历史无布局流程' }
]
const app = createApp({
  render: () =>
    h('div', { class: 'workbench-preview' }, [
      h('header', [
        h('strong', '流程工作台回归 · 模拟数据'),
        ...links.map(link => h(RouterLink, { to: link.to }, () => link.label)),
        h('button', { onClick: failNextMaterial }, '下一次材料读取失败')
      ]),
      h('main', [h(RouterView)])
    ])
})
app.provide(nocodePlatformKey, {
  directory: { users: async () => [], departments: async () => [] },
  runtime: {},
  work: {},
  objects: {},
  dataCenter: {},
  applications: {},
  hasPermission: () => true
} as unknown as NocodePlatform)
app.use(router).use(Antd).mount('#app')
if (!location.hash || location.hash === '#/') router.replace('/?taskId=editing')

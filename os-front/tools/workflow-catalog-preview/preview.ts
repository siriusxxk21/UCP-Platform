import { createApp, defineComponent, h, onMounted, ref } from 'vue'
import { createRouter, createWebHashHistory, RouterLink, RouterView } from 'vue-router'
import Antd, { ConfigProvider } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import 'ant-design-vue/dist/reset.css'
import '@/style.css'
import './preview.css'
import ModelList from '@/views/bpm/model/index.vue'
import ModelForm from '@/views/bpm/model/form/index.vue'
import StartForm from '@/views/bpm/processInstance/create/modules/form.vue'
import { nocodePlatformKey } from '@/nocode/platform'
import { simulateFailure } from './preview-api'

const start = defineComponent({
  setup() {
    const form = ref<any>()
    const definition = { id: 'preview-definition', name: '发起表单可选验收', formType: 0 }
    onMounted(() => form.value.initProcessInfo(definition))
    return () => h(StartForm, { ref: form, selectProcessDefinition: definition as any })
  }
})
const routes = [
  { path: '/', component: ModelList, label: '流程模型' },
  { path: '/designer', component: ModelForm, label: '表单与节点设计', to: '/designer?type=update&id=preview-design' },
  { path: '/start', component: start, label: '无需表单发起' }
]
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    ...routes,
    {
      path: '/:pathMatch(.*)*',
      component: { render: () => h('div', `已跳转至 ${router.currentRoute.value.fullPath}（模拟入口）`) }
    }
  ]
})
const app = createApp({
  render: () =>
    h(
      ConfigProvider,
      { locale: zhCN },
      {
        default: () =>
          h('div', { class: 'workflow-preview' }, [
            h('header', [
              h('strong', '流程优化验收 · 模拟数据'),
              ...routes.map(route => h(RouterLink, { to: route.to || route.path }, () => route.label)),
              h('button', { onClick: simulateFailure }, '模拟下一次请求失败')
            ]),
            h('main', [h(RouterView)])
          ])
      }
    )
})
app.provide(nocodePlatformKey, { runtime: { mine: async () => [] }, hasPermission: () => true } as any)
app.use(router).use(Antd).mount('#app')

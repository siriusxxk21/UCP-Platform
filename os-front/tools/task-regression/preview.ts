import { createApp, h } from 'vue'
import { createRouter, createWebHashHistory, RouterLink, RouterView } from 'vue-router'
import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import '@/style.css'
import './preview.css'
import Todo from '@/views/bpm/task/todo/index.vue'
import Done from '@/views/bpm/task/done/index.vue'
import Manager from '@/views/bpm/task/manager/index.vue'
import Copy from '@/views/bpm/task/copy/index.vue'
import { simulateFailure } from './preview-api'

const routes = [
  { path: '/', component: Todo, label: '待办任务' },
  { path: '/done', component: Done, label: '已办任务' },
  { path: '/manager', component: Manager, label: '流程任务管理' },
  { path: '/copy', component: Copy, label: '抄送任务' }
]
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    ...routes,
    {
      path: '/detail',
      name: 'TaskInstanceDetail',
      component: { render: () => h('div', '详情跳转已到达（模拟页面，不办理业务）') }
    },
    {
      path: '/work-task',
      name: 'NocodeFlowTask',
      component: {
        render: () => h('div', `统一办理入口已到达 · ${router.currentRoute.value.query.taskId}（模拟页面，不办理业务）`)
      }
    }
  ]
})
createApp({
  render: () =>
    h('div', { class: 'task-preview' }, [
      h('header', [
        h('strong', '任务列表视觉回归 · 模拟数据'),
        ...routes.map(r => h(RouterLink, { to: r.path }, () => r.label)),
        h('button', { onClick: simulateFailure }, '模拟下一次请求失败')
      ]),
      h('main', [h(RouterView)])
    ])
})
  .use(router)
  .use(Antd)
  .mount('#app')

import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import '../../src/style.css'
import Runtime from '../../src/views/nocode/application/runtime.vue'
import { nocodePlatformKey, type NocodePlatform } from '../../src/nocode/platform'
import { NodeKind, uiNode } from '../../src/types/nocode/application-ui'
import { ResourceKind } from '../../src/types/nocode/application'
import App from './App.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/nocode-app/runtime?id=multi' },
    { path: '/nocode-app/runtime', component: Runtime }
  ]
})
const platform = {
  runtime: {
    application: async (id: string) => {
      if (id === 'error') throw new Error('验证夹具：应用暂不可用')
      const count = id === 'single' ? 1 : 8
      return {
        application: { id, name: '布局验证应用' },
        versionNo: 7,
        definition: {
          objects: [],
          resources: Array.from({ length: count }, (_, i) => [
            {
              id: `menu-${i + 1}`,
              name: `业务页面 ${i + 1}`,
              kind: ResourceKind.MENU,
              config: { targetId: `page-${i + 1}` }
            },
            {
              id: `page-${i + 1}`,
              name: `内容 ${i + 1}`,
              kind: ResourceKind.PAGE,
              config: {
                nodes: [
                  uiNode(NodeKind.CARD, {
                    text: `自定义分组 ${i + 1}`,
                    children: [uiNode(NodeKind.TEXT, { text: `当前内容：业务页面 ${i + 1}` })]
                  })
                ]
              }
            }
          ]).flat()
        }
      }
    }
  }
} as unknown as NocodePlatform

createApp(App).use(router).use(Antd).provide(nocodePlatformKey, platform).mount('#app')

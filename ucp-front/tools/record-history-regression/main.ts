import { createApp, h } from 'vue'
import Antd, { ConfigProvider } from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import '../../src/style.css'
import { antdTheme } from '../../src/theme/antd-theme'
import zhCN from 'ant-design-vue/locale/zh_CN'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'
import RecordHistory from '../../src/views/nocode/record-history/index.vue'
import { nocodePlatformKey, type NocodePlatform } from '../../src/nocode/platform'
import { createRuntimeApi } from '../../src/api/nocode/runtime'
import type { NocodeHttpClient } from '../../src/api/nocode/object'
const client = {
  async post(path: string, body: unknown) {
    const r = await fetch('/fixture' + path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    })
    if (!r.ok) throw new Error('加载失败，请重试')
    return r.json()
  },
  async get() {
    return [
      { id: 'a1', name: '公司经营管理' },
      { id: 'a2', name: '资料管理' }
    ]
  }
} as unknown as NocodeHttpClient
dayjs.locale('zh-cn')
createApp({
  render: () =>
    h(
      ConfigProvider,
      { theme: antdTheme, locale: zhCN },
      {
        default: () =>
          h('main', [
            h(
              'div',
              { style: 'padding:12px 24px;background:#262451;color:white' },
              '日创 OS 平台 · 工作台 / 表格更新　｜　正式组件 · 模拟接口验证'
            ),
            h(RecordHistory)
          ])
      }
    )
})
  .provide(nocodePlatformKey, { runtime: createRuntimeApi(client) } as NocodePlatform)
  .use(Antd)
  .mount('#app')

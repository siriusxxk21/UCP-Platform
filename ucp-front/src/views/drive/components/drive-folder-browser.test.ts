// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import type { DriveGateway } from '../driver/drive-gateway'

/** VueFinder 的替身：只记下收到的 features */
const finders = vi.hoisted(() => ({ features: [] as Array<Record<string, unknown>> }))
vi.mock('vuefinder', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    ContextMenuIds: {},
    contextMenuItems: [],
    VueFinder: defineComponent({
      name: 'VueFinderStub',
      props: ['id', 'driver', 'config', 'features', 'contextMenuItems', 'locale'],
      setup(props) {
        finders.features.push(props.features as Record<string, unknown>)
        return () => h('div', { class: 'finder-stub' })
      }
    })
  }
})
vi.mock('vuefinder/dist/style.css', () => ({}))
vi.mock('../drive-finder.css', () => ({}))
vi.mock('../vuefinder-runtime', () => ({
  ensureVueFinderInstalled: async () => undefined,
  seedFinderState: () => undefined
}))
vi.mock('./EntryDetailDrawer.vue', async () => {
  const { defineComponent } = await import('vue')
  return { __esModule: true, default: defineComponent({ name: 'EntryDetailDrawerStub', render: () => null }) }
})
import DriveFolderBrowser from './DriveFolderBrowser.vue'

const gateway = {} as DriveGateway
const apps: App[] = []
async function mount(props: Record<string, unknown>) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp({ render: () => h(DriveFolderBrowser, { finderId: 'f', gateway, storageName: 's', ...props }) })
  apps.push(app)
  app.mount(host)
  for (let i = 0; i < 6; i++) {
    await Promise.resolve()
    await nextTick()
  }
  return finders.features[finders.features.length - 1]
}

afterEach(() => {
  apps.splice(0).forEach(app => app.unmount())
  finders.features.length = 0
  document.body.innerHTML = ''
})

describe('DriveFolderBrowser 能不能写', () => {
  it('默认能写：不额外关掉写入口', async () => {
    const features = await mount({})
    for (const key of ['newfolder', 'upload', 'rename', 'delete', 'move', 'copy']) expect(features[key]).toBeUndefined()
    expect(features.search).toBe(true)
  })

  it('allowWrite 为 false：上传、新建、改名、移动、复制、删除全部关掉，搜索照旧', async () => {
    const features = await mount({ allowWrite: false })
    expect(features).toMatchObject({
      newfolder: false,
      upload: false,
      rename: false,
      delete: false,
      move: false,
      copy: false,
      search: true
    })
  })
})

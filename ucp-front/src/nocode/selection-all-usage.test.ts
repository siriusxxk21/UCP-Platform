// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App, type Component } from 'vue'
import ObjectSharingPanel from '@/views/nocode/components/ObjectSharingPanel.vue'
import ApplicationSharing from '@/views/nocode/application/components/ApplicationSharing.vue'
import type { ObjectGrant, ObjectSharingGrant } from '@/types/nocode/authorization'
import type { PublishedObject } from '@/types/nocode/application'

const mocks = vi.hoisted(() => ({
  objectSharing: vi.fn(),
  sharingTargets: vi.fn(),
  sharingDefinition: vi.fn(),
  saveObjectSharing: vi.fn(),
  sharing: vi.fn(),
  objectVersion: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({ applications: mocks, hasPermission: () => false })
}))
vi.mock('@/nocode/data-center', () => ({ errorMessage: (error: Error) => error.message }))
vi.mock('ant-design-vue', () => ({
  Drawer: { render: () => null },
  Modal: { render: () => null, confirm: vi.fn() },
  message: { success: vi.fn() }
}))
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn(), onBeforeRouteUpdate: vi.fn() }))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/components/ObjectGrantFields.vue', () => ({ default: { render: () => null } }))

let app: App | undefined, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const published = {
  objectId: 'object',
  versionNo: 3,
  checksum: 'v3',
  definition: { objectId: 'object', objectName: '客户', fields: [], fieldOptions: {}, details: [], relations: [] }
} as unknown as PublishedObject
const permission = (patch: Partial<ObjectGrant> = {}): ObjectGrant => ({
  objectId: 'object',
  actions: ['READ'],
  scope: 'ALL',
  readFields: ['a', 'b'],
  writeFields: ['a'],
  readDetails: [],
  writeDetails: [],
  ...patch
})
const row = (grant: ObjectGrant | null): ObjectSharingGrant => ({
  objectId: 'object',
  applicationId: 'app',
  applicationName: '客户管理',
  revision: 4,
  permission: grant,
  reason: '',
  updater: '',
  updateTime: ''
})
beforeEach(() => {
  vi.resetAllMocks()
  mocks.sharingTargets.mockResolvedValue([{ id: 'app', name: '客户管理' }])
  mocks.sharingDefinition.mockResolvedValue(published)
  mocks.objectVersion.mockResolvedValue(published)
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
})

describe('数据中心新增应用授权的默认值', () => {
  /** 保留真实 setup，不渲染模板（写法同 relation-autosave.test.ts）。 */
  async function setup(props: Record<string, unknown>) {
    app = createApp({ ...(ObjectSharingPanel as Component), render: () => null }, props)
    host = document.createElement('div')
    document.body.append(host)
    const vm = app.mount(host)
    await flush()
    return (vm.$ as unknown as { setupState: { openEditor: (id?: string) => void; grant: ObjectGrant | undefined } })
      .setupState
  }
  it('还没有授权行：与系统首次引用时自动写入的默认授权相同', async () => {
    mocks.objectSharing.mockResolvedValue([])
    const state = await setup({ objectId: 'object' })
    state.openEditor('app')
    await flush()
    expect(JSON.parse(JSON.stringify(state.grant))).toEqual({
      objectId: 'object',
      actions: ['READ', 'CREATE', 'UPDATE', 'DELETE', 'IMPORT', 'EXPORT'],
      scope: 'ALL',
      readFields: ['*'],
      writeFields: ['*'],
      readDetails: ['*'],
      writeDetails: ['*'],
      readRelations: ['*'],
      writeRelations: ['*']
    })
  })
  it('已撤销的授权重新授权时同样从默认值开始', async () => {
    mocks.objectSharing.mockResolvedValue([row(null)])
    const state = await setup({ objectId: 'object' })
    state.openEditor('app')
    await flush()
    expect(state.grant?.readFields).toEqual(['*'])
    expect(state.grant?.scope).toBe('ALL')
  })
  it('已有授权行：原样打开，不套默认值', async () => {
    mocks.objectSharing.mockResolvedValue([row(permission())])
    const state = await setup({ objectId: 'object' })
    state.openEditor('app')
    await flush()
    expect(JSON.parse(JSON.stringify(state.grant))).toEqual(permission())
  })
})

describe('应用数据权限总览里的「全部」', () => {
  async function mount(grant: ObjectGrant) {
    mocks.sharing.mockResolvedValue([row(grant)])
    app = createApp(() => h(ApplicationSharing, { applicationId: 'app', objects: { object: published } }))
    const plain = defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('div', slots.default?.())
    })
    for (const name of ['ASpace', 'AButton', 'AEmpty', 'ATag', 'ACollapse', 'ACollapsePanel'])
      app.component(name, plain)
    app.component(
      'AAlert',
      defineComponent({ props: ['message'], setup: p => () => h('div', { role: 'alert' }, p.message) })
    )
    host = document.createElement('div')
    document.body.append(host)
    app.mount(host)
    await flush()
  }
  const counts = () => host.querySelector('.permission-counts')?.textContent?.replace(/\s+/g, '')
  it('清单照旧显示个数', async () => {
    await mount(permission())
    expect(counts()).toBe('查看字段2填写字段1全部记录')
  })
  it('全部显示「全部」，不显示成 1 个字段', async () => {
    await mount(permission({ readFields: ['*'], writeFields: ['*'] }))
    expect(counts()).toBe('查看字段全部填写字段全部全部记录')
    expect(host.textContent).not.toContain('尚未选择可查看字段')
  })
  it('可查看是全部、可填写是空清单：各显示各的', async () => {
    await mount(permission({ readFields: ['*'], writeFields: [] }))
    expect(counts()).toBe('查看字段全部填写字段0全部记录')
  })
  it('可查看为空时照旧给出警告（对照组）', async () => {
    await mount(permission({ readFields: [], writeFields: [] }))
    expect(host.textContent).toContain('尚未选择可查看字段，运行列表和表单将无法显示内容。')
  })
})

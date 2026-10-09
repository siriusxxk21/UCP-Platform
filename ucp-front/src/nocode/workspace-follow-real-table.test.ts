// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, reactive, type App } from 'vue'
import Antd from 'ant-design-vue'
import Workspace from '@/views/nocode/application/workspace.vue'
import type { ApplicationDetail, ObjectFollow, PublishedObject } from '@/types/nocode/application'

/**
 * 用真实的表格组件（OsTablePage + 组件库）挂工作台：表格只认挂载时的列、并按浏览器里存过的列设置过滤，
 * 替身表格看不出这两点。这里确认「自动跟随」列真的显示得出来。
 */
const mocks = vi.hoisted(() => ({
  apps: {
    get: vi.fn(),
    objectVersion: vi.fn(),
    objectFollows: vi.fn(),
    readableObjects: vi.fn(),
    releases: vi.fn()
  },
  route: { query: { id: 'app' } } as { query: Record<string, string> }
}))
vi.mock('@/api/auth', () => ({ getUserInfo: vi.fn().mockResolvedValue({}) }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ applyPermissionInfo: vi.fn() }) }))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({
    applications: mocks.apps,
    dataCenter: { categories: vi.fn(), objects: vi.fn() },
    hasPermission: () => true
  })
}))
vi.mock('@/nocode/data-center', () => ({ errorMessage: (error: Error) => error.message }))
vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => ({ push: vi.fn() }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('@/views/nocode/application/components/ResourceManager.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/BusinessConfigManager.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/TaskEntryManager.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/ApplicationMembers.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/ApplicationPublishDialog.vue', () => ({
  default: { render: () => null }
}))
vi.mock('@/views/nocode/application/components/ApplicationObjectPermission.vue', () => ({
  default: { render: () => null }
}))
vi.mock('@/views/nocode/application/components/PlatformMenuEntry.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/components/CategoryInput.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/components/CategoryTreePanel.vue', () => ({ default: { render: () => null } }))
vi.mock('@/components/ucp-modal-form/OsModalForm.vue', () => ({ default: { render: () => null } }))

const published = (objectId: string, objectName: string, versionNo: number): PublishedObject =>
  ({
    objectId,
    versionNo,
    checksum: `${objectId}-v${versionNo}`,
    definition: {
      objectId,
      objectName,
      tableName: `biz_${objectId}`,
      fields: [],
      fieldOptions: {},
      details: [],
      relations: []
    }
  }) as unknown as PublishedObject
const detail = (): ApplicationDetail =>
  ({
    application: {
      id: 'app',
      code: 'fund',
      name: '资金管理',
      category: '',
      description: null,
      icon: null,
      status: 'ACTIVE',
      revision: 7,
      publishedVersion: 61,
      updateTime: ''
    },
    draft: { objects: [{ objectId: '2001', versionNo: 11, checksum: '2001-v11' }], resources: [] },
    issues: []
  }) as unknown as ApplicationDetail
const follow: ObjectFollow = {
  objectId: '2001',
  enabled: true,
  state: 'FOLLOWING',
  pinnedVersion: 11,
  latestVersion: 12,
  pendingVersion: null,
  pendingCode: null,
  pendingReason: null,
  followedAt: null,
  revision: 0
}
let app: App | undefined, host: HTMLDivElement
const errors: unknown[] = []
const flush = async () => {
  for (let i = 0; i < 20; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount() {
  app = createApp(Workspace)
  app.use(Antd)
  app.config.errorHandler = error => errors.push(error)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
}
const headers = () =>
  Array.from(host.querySelectorAll('.workspace-objects-table thead th'))
    .map(th => th.textContent?.trim())
    .filter(Boolean)
let warn: ReturnType<typeof vi.spyOn>
beforeEach(() => {
  vi.resetAllMocks()
  errors.length = 0
  localStorage.clear()
  mocks.route = reactive({ query: { id: 'app' } })
  warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: false,
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn()
    }))
  )
  // jsdom 不支持带伪元素参数的 getComputedStyle（表格量滚动条宽度时会用到），只取元素本身的样式。
  const computed = window.getComputedStyle.bind(window)
  vi.spyOn(window, 'getComputedStyle').mockImplementation(element => computed(element))
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe = vi.fn()
      unobserve = vi.fn()
      disconnect = vi.fn()
    }
  )
  mocks.apps.get.mockResolvedValue(detail())
  mocks.apps.objectVersion.mockImplementation(async (id: string, versionNo?: number) =>
    published(id, '资金流水', versionNo ?? 12)
  )
  mocks.apps.objectFollows.mockResolvedValue([follow])
  mocks.apps.readableObjects.mockResolvedValue([])
  mocks.apps.releases.mockResolvedValue({ list: [], total: 0 })
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
  document.body.innerHTML = ''
  warn.mockRestore()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('真实表格里的「自动跟随」列', () => {
  it('状态读取成功：表头有「自动跟随」，排在「最新发布版本」之后；行里有开关和「立即跟随」', async () => {
    await mount()
    expect(errors).toEqual([])
    expect(headers()).toEqual(['序号', '数据对象', '固定版本', '最新发布版本', '自动跟随', '物理主表', '操作'])
    const row = host.querySelector('.workspace-objects-table tbody tr.ant-table-row')
    expect(row?.querySelector('button.ant-switch')?.getAttribute('aria-checked')).toBe('true')
    expect(row?.textContent).toContain('待跟随')
    expect(row?.textContent?.replace(/\s/g, '')).toContain('立即跟随')
    expect(row?.querySelector('button[aria-label="同步最新版本"]')).toBeNull()
  })
  it('应用被暂停（不是启用状态）：开关开着的行也有可点的「同步最新版本」，没有「立即跟随」——恢复不用先关开关', async () => {
    const suspended = detail()
    suspended.application.status = 'DISABLED'
    mocks.apps.get.mockResolvedValue(suspended)
    await mount()
    expect(errors).toEqual([])
    const row = host.querySelector('.workspace-objects-table tbody tr.ant-table-row')
    expect(row?.querySelector('button.ant-switch')?.getAttribute('aria-checked')).toBe('true')
    const sync = row?.querySelector<HTMLButtonElement>('button[aria-label="同步最新版本"]')
    expect(sync).not.toBeNull()
    expect(sync?.disabled).toBe(false)
    expect(row?.textContent?.replace(/\s/g, '')).not.toContain('立即跟随')
    expect(row?.textContent).toContain('应用停用中，不自动跟随')
  })
  it('状态读取失败：表头没有「自动跟随」，同步按钮照常', async () => {
    mocks.apps.objectFollows.mockRejectedValue(new Error('404'))
    await mount()
    expect(errors).toEqual([])
    expect(headers()).toEqual(['序号', '数据对象', '固定版本', '最新发布版本', '物理主表', '操作'])
    expect(host.querySelector('.workspace-objects-table button.ant-switch')).toBeNull()
    expect(host.querySelector('.workspace-objects-table button[aria-label="同步最新版本"]')).not.toBeNull()
  })
  it('表格下方显示「因关联而可读取」一行，不占表格的行', async () => {
    mocks.apps.readableObjects.mockResolvedValue([
      {
        object: published('2007', '客户', 5),
        via: [{ fromObjectId: '2001', fromObjectName: '资金流水', kind: 'RELATION', name: '所属客户' }],
        closed: false
      }
    ])
    await mount()
    expect(errors).toEqual([])
    expect(host.querySelector('.readable-objects')?.textContent).toBe(
      '因关联而可读取（只读，不能为它们建列表或表单）：客户（资金流水的“所属客户”）'
    )
    expect(host.querySelectorAll('.workspace-objects-table tbody tr.ant-table-row')).toHaveLength(1)
  })
  it('浏览器里存过旧的列设置（当时还没有这一列）也照常显示「自动跟随」', async () => {
    localStorage.setItem('la-col-settings:nocode-app-objects-v2', JSON.stringify(['name', 'version', 'actions']))
    await mount()
    expect(headers()).toEqual(['序号', '数据对象', '固定版本', '最新发布版本', '自动跟随', '物理主表', '操作'])
  })
})

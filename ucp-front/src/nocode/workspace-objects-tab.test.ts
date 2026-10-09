// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, type App } from 'vue'
import Workspace from '@/views/nocode/application/workspace.vue'
import type {
  ApplicationDetail,
  FollowRun,
  ObjectFollow,
  PublishedObject,
  ReadableObject
} from '@/types/nocode/application'

/**
 * 应用工作台「已引用对象」页签的真实模板：自动跟随列、同步按钮的显隐、「因关联而可读取」一行，
 * 以及工作台把哪张对象表交给哪个子组件（隐式可读的对象不能进任何挑选对象的地方）。
 */
const mocks = vi.hoisted(() => ({
  apps: {
    get: vi.fn(),
    save: vi.fn(),
    restore: vi.fn(),
    status: vi.fn(),
    objectVersion: vi.fn(),
    objectFollows: vi.fn(),
    setObjectFollow: vi.fn(),
    runObjectFollow: vi.fn(),
    readableObjects: vi.fn(),
    categories: vi.fn(),
    releases: vi.fn()
  },
  permissions: new Set<string>(),
  route: { query: { id: 'app' } } as { query: Record<string, string> },
  message: { success: vi.fn(), warning: vi.fn(), info: vi.fn(), error: vi.fn() },
  confirm: vi.fn(),
  /** 子组件替身收到的属性（响应式，读到的总是最新值）。 */
  received: {} as Record<string, Record<string, unknown>>
}))
vi.mock('@/api/auth', () => ({ getUserInfo: vi.fn().mockResolvedValue({}) }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ applyPermissionInfo: vi.fn() }) }))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({
    applications: mocks.apps,
    dataCenter: { categories: vi.fn(), objects: vi.fn() },
    hasPermission: (code: string) => mocks.permissions.has(code)
  })
}))
vi.mock('@/nocode/data-center', () => ({ errorMessage: (error: Error) => error.message }))
vi.mock('ant-design-vue', () => ({ message: mocks.message, Modal: { confirm: mocks.confirm } }))
vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => ({ push: vi.fn() }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
async function recorder(name: string) {
  const { defineComponent } = await import('vue')
  return {
    default: defineComponent({
      name,
      inheritAttrs: false,
      setup(_, { attrs }) {
        mocks.received[name] = attrs
        return () => null
      }
    })
  }
}
vi.mock('@/views/nocode/application/components/ResourceManager.vue', () => recorder('ResourceManager'))
vi.mock('@/views/nocode/application/components/BusinessConfigManager.vue', () => recorder('BusinessConfigManager'))
vi.mock('@/views/nocode/application/components/TaskEntryManager.vue', () => recorder('TaskEntryManager'))
vi.mock('@/views/nocode/application/components/ApplicationMembers.vue', () => recorder('ApplicationMembers'))
vi.mock('@/views/nocode/application/components/ApplicationPublishDialog.vue', () =>
  recorder('ApplicationPublishDialog')
)
vi.mock('@/views/nocode/application/components/ApplicationObjectPermission.vue', () => recorder('ObjectPermission'))
vi.mock('@/views/nocode/application/components/PlatformMenuEntry.vue', () => ({ default: { render: () => null } }))
// 合并到主线后工作台多了「自动更新」页签（数据联动第一期）：它有自己的用例，这里不挂，免得它读取失败的提示混进本文件「不弹错」的断言里。
vi.mock('@/views/nocode/application/components/LinkageSyncPanel.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/components/CategoryInput.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/components/CategoryTreePanel.vue', () => ({ default: { render: () => null } }))
vi.mock('@/components/ucp-modal-form/OsModalForm.vue', () => ({ default: { render: () => null } }))
// 表格替身：逐行逐列渲染单元格插槽，列按传入的列定义来。
vi.mock('@/components/ucp-table-page/OsTablePage.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['columns', 'dataSource'],
      setup(props, { slots, attrs }) {
        mocks.received.ObjectsTable ||= attrs
        return () =>
          h('div', { 'data-table': (props.columns || []).map((c: { key: string }) => c.key).join(',') }, [
            slots.actions?.(),
            ...(props.dataSource || []).map((record: { objectId?: string }) =>
              h(
                'div',
                { 'data-row': record.objectId },
                (props.columns || []).map((column: { key: string }) =>
                  h('div', { 'data-col': column.key }, slots.bodyCell?.({ column, record }))
                )
              )
            )
          ])
      }
    })
  }
})

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
const latest: Record<string, number> = { '2001': 12, '2002': 5 }
const names: Record<string, string> = { '2001': '资金流水', '2002': '合同' }
const detail = (revision: number, versions: Record<string, number> = { '2001': 11, '2002': 5 }): ApplicationDetail =>
  ({
    application: {
      id: 'app',
      code: 'fund',
      name: '资金管理',
      category: '',
      description: null,
      icon: null,
      status: 'ACTIVE',
      revision,
      publishedVersion: 61,
      updateTime: ''
    },
    draft: {
      objects: Object.entries(versions).map(([objectId, versionNo]) => ({
        objectId,
        versionNo,
        checksum: `${objectId}-v${versionNo}`
      })),
      resources: []
    },
    issues: []
  }) as unknown as ApplicationDetail
const follow = (patch: Partial<ObjectFollow>): ObjectFollow => ({
  objectId: '2001',
  enabled: true,
  state: 'FOLLOWING',
  pinnedVersion: 11,
  latestVersion: 12,
  pendingVersion: null,
  pendingCode: null,
  pendingReason: null,
  followedAt: null,
  revision: 0,
  ...patch
})
const customer: ReadableObject = {
  object: published('2007', '客户', 5),
  via: [{ fromObjectId: '2001', fromObjectName: '资金流水', kind: 'RELATION', name: '所属客户' }],
  closed: false
}

let app: App | undefined, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function must<T>(value: T | null | undefined, what: string): T {
  if (value == null) throw new Error(`${what} 不存在`)
  return value
}
async function mount() {
  app = createApp(Workspace)
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of ['ATabs', 'ATabPane', 'ASpin', 'ASpace', 'AForm', 'AFormItem', 'AEmpty', 'AInput', 'ATextarea'])
    app.component(name, plain)
  app.component(
    'AAlert',
    defineComponent({
      props: ['message', 'type'],
      setup: p => () => h('div', { role: 'alert', 'data-type': p.type }, p.message)
    })
  )
  app.component(
    'ATooltip',
    defineComponent({
      props: ['title'],
      setup:
        (p, { slots }) =>
        () =>
          h('span', { 'data-tip': p.title || '' }, slots.default?.())
    })
  )
  app.component(
    'APopconfirm',
    defineComponent({
      emits: ['confirm'],
      setup:
        (_, { slots, emit }) =>
        () =>
          h('span', { onClick: () => emit('confirm') }, slots.default?.())
    })
  )
  app.component(
    'ATag',
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('em', slots.default?.())
    })
  )
  app.component(
    'AButton',
    defineComponent({
      props: ['disabled'],
      setup:
        (p, { slots }) =>
        () =>
          h('button', { type: 'button', disabled: !!p.disabled }, slots.default?.())
    })
  )
  app.component(
    'ASwitch',
    defineComponent({
      props: ['checked', 'disabled'],
      emits: ['change'],
      setup:
        (p, { emit }) =>
        () =>
          h('button', {
            role: 'switch',
            'aria-checked': String(!!p.checked),
            disabled: !!p.disabled,
            onClick: () => emit('change', !p.checked)
          })
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
}
const table = () => must(host.querySelector('[data-table]'), '已引用对象表格')
const cell = (objectId: string, column: string) =>
  must(host.querySelector(`[data-row="${objectId}"] [data-col="${column}"]`), `${objectId} 的 ${column} 单元格`)
const syncButton = (objectId: string) =>
  cell(objectId, 'actions').querySelector<HTMLButtonElement>('button[aria-label="同步最新版本"]')
const toggler = (objectId: string) =>
  must(cell(objectId, 'follow').querySelector<HTMLButtonElement>('[role="switch"]'), `${objectId} 的跟随开关`)
const action = (objectId: string, label: string) =>
  must(
    Array.from(cell(objectId, 'follow').querySelectorAll<HTMLButtonElement>('button')).find(
      b => b.textContent?.trim() === label
    ),
    `${objectId} 的「${label}」`
  )
const readableLine = () => host.querySelector('.readable-objects')
/** 替身没有声明属性，模板里的短横线写法原样落在 attrs 上。 */
const received = (name: string, prop: string) => {
  const attrs = must(mocks.received[name], `${name} 替身`)
  return attrs[prop] ?? attrs[prop.replace(/[A-Z]/g, letter => '-' + letter.toLowerCase())]
}
const objectIds = (name: string, prop = 'objects') =>
  Object.keys(must(received(name, prop), `${name} 的 ${prop}`) as Record<string, unknown>).sort()

let warn: ReturnType<typeof vi.spyOn>
beforeEach(() => {
  vi.resetAllMocks()
  for (const key of Object.keys(mocks.received)) delete mocks.received[key]
  mocks.permissions.clear()
  for (const code of ['nocode:app:update', 'nocode:app:manage', 'nocode:app:publish', 'nocode:object:query'])
    mocks.permissions.add(code)
  mocks.route = reactive({ query: { id: 'app' } })
  warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
  mocks.apps.get.mockResolvedValue(detail(7))
  mocks.apps.objectVersion.mockImplementation(async (id: string, versionNo?: number) =>
    published(id, must(names[id], '对象名'), versionNo ?? must(latest[id], '最新版本'))
  )
  mocks.apps.objectFollows.mockResolvedValue([
    follow({}),
    follow({ objectId: '2002', enabled: false, pinnedVersion: 5, latestVersion: 5, revision: 1 })
  ])
  mocks.apps.readableObjects.mockResolvedValue([])
  mocks.apps.releases.mockResolvedValue({ list: [], total: 0 })
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
  warn.mockRestore()
})

describe('工作台的自动跟随列', () => {
  it('状态读取成功：「自动跟随」列排在「最新发布版本」之后', async () => {
    await mount()
    expect(table().getAttribute('data-table')).toBe('name,version,latestVersion,follow,table,actions')
    expect(mocks.apps.objectFollows).toHaveBeenCalledWith('app')
    expect(toggler('2001').getAttribute('aria-checked')).toBe('true')
    expect(toggler('2002').getAttribute('aria-checked')).toBe('false')
    expect(cell('2001', 'follow').textContent).toContain('待跟随')
    expect(cell('2001', 'follow').textContent).toContain('立即跟随')
  })
  it('表格用新的列设置键，旧键下存过的列设置不会把新列藏起来', async () => {
    await mount()
    expect(received('ObjectsTable', 'columnSettingsKey')).toBe('nocode-app-objects-v3')
  })
  it('状态读取失败（含后端还没有这个接口）：整列不显示，不弹错，其余照常', async () => {
    mocks.apps.objectFollows.mockRejectedValue(new Error('404'))
    await mount()
    expect(table().getAttribute('data-table')).toBe('name,version,latestVersion,table,actions')
    expect(host.querySelector('[data-col="follow"]')).toBeNull()
    expect(host.querySelector('[role="alert"][data-type="error"]')).toBeNull()
    expect(mocks.message.error).not.toHaveBeenCalled()
    expect(mocks.message.warning).not.toHaveBeenCalled()
    expect(cell('2001', 'name').textContent).toContain('资金流水')
    // 现有的同步按钮按「跟随关着」处理：照常显示。
    expect(syncButton('2001')).not.toBeNull()
    expect(syncButton('2002')).not.toBeNull()
  })
  it('开着跟随的行隐藏「同步最新版本」按钮，关着的行照常显示', async () => {
    await mount()
    expect(syncButton('2001')).toBeNull()
    expect(syncButton('2002')).not.toBeNull()
    // 其它操作不受影响。
    expect(cell('2001', 'actions').querySelector('button[aria-label="移除引用"]')).not.toBeNull()
  })
  it('有未保存修改时开关和「立即跟随」禁用，悬停「请先保存或放弃当前修改」', async () => {
    await mount()
    expect(toggler('2001').disabled).toBe(false)
    ;(must(mocks.received.ResourceManager, '页面与视图替身').onChange as () => void)()
    await flush()
    expect(toggler('2001').disabled).toBe(true)
    expect(toggler('2002').disabled).toBe(true)
    expect(action('2001', '立即跟随').disabled).toBe(true)
    expect(must(toggler('2001').closest('[data-tip]'), '开关提示').getAttribute('data-tip')).toBe(
      '请先保存或放弃当前修改'
    )
    toggler('2001').click()
    action('2001', '立即跟随').click()
    await flush()
    expect(mocks.confirm).not.toHaveBeenCalled()
    expect(mocks.apps.runObjectFollow).not.toHaveBeenCalled()
  })
  it('没有应用编辑权限时开关只读', async () => {
    mocks.permissions.delete('nocode:app:update')
    await mount()
    expect(toggler('2001').disabled).toBe(true)
    expect(action('2001', '立即跟随').disabled).toBe(true)
  })
  it('立即跟随成功：工作台接收返回的应用详情（修订号、草稿引用），并重新读取跟随状态', async () => {
    await mount()
    const run: FollowRun = {
      outcome: 'FOLLOWED',
      follow: follow({ pinnedVersion: 12 }),
      application: detail(9, { '2001': 12, '2002': 5 }),
      draftSynced: true,
      draftReason: null
    }
    mocks.apps.runObjectFollow.mockResolvedValue(run)
    mocks.apps.objectFollows.mockResolvedValue([
      follow({ pinnedVersion: 12 }),
      follow({ objectId: '2002', enabled: false, pinnedVersion: 5, latestVersion: 5, revision: 1 })
    ])
    action('2001', '立即跟随').click()
    await flush()
    expect(mocks.apps.runObjectFollow).toHaveBeenCalledWith({ applicationId: 'app', objectId: '2001' })
    expect(cell('2001', 'version').textContent).toContain('V12')
    expect(cell('2001', 'follow').textContent).not.toContain('待跟随')
    expect(mocks.apps.objectFollows.mock.calls.length).toBeGreaterThanOrEqual(2)
    expect(host.textContent).not.toContain('有未保存修改')
    // 之后保存用的是新修订号：说明走的是工作台自己的接收路径，没有另记一份修订号。
    mocks.apps.save.mockResolvedValue(detail(10, { '2001': 12, '2002': 5 }))
    must(
      Array.from(host.querySelectorAll('button')).find(b => b.textContent?.includes('保存草稿')),
      '保存草稿'
    ).click()
    await flush()
    expect(mocks.apps.save).toHaveBeenCalledWith(expect.objectContaining({ expectedRevision: 9 }))
    expect(must(mocks.apps.save.mock.calls[0], '保存请求')[0].definition.objects[0]).toEqual({
      objectId: '2001',
      versionNo: 12,
      checksum: '2001-v12'
    })
  })
  it('应用已跟上但草稿没同步：提示逐字，并对该行恢复显示同步按钮', async () => {
    await mount()
    expect(syncButton('2001')).toBeNull()
    mocks.apps.runObjectFollow.mockResolvedValue({
      outcome: 'FOLLOWED',
      follow: follow({ pinnedVersion: 12 }),
      application: detail(9),
      draftSynced: false,
      draftReason: '草稿里的表单用到了已停用的字段'
    } satisfies FollowRun)
    action('2001', '立即跟随').click()
    await flush()
    expect(mocks.message.warning).toHaveBeenCalledWith(
      '应用已跟上，但草稿没能同步：草稿里的表单用到了已停用的字段。请手工同步并修正后保存。'
    )
    expect(syncButton('2001')).not.toBeNull()
  })
  it('拨到关要确认；确认后发请求', async () => {
    await mount()
    toggler('2001').click()
    await flush()
    expect(mocks.apps.setObjectFollow).not.toHaveBeenCalled()
    const dialog = must(mocks.confirm.mock.calls[0], '确认框')[0]
    expect(dialog.title).toBe('关闭自动跟随？')
    mocks.apps.setObjectFollow.mockResolvedValue({
      outcome: null,
      follow: follow({ enabled: false, revision: 1 }),
      application: detail(7),
      draftSynced: true,
      draftReason: null
    } satisfies FollowRun)
    await dialog.onOk()
    await flush()
    expect(mocks.apps.setObjectFollow).toHaveBeenCalledWith({
      applicationId: 'app',
      objectId: '2001',
      enabled: false,
      expectedRevision: 0
    })
  })
})

describe('因关联而可读取', () => {
  it('有隐式可读对象：表格下方一行只读小字（文案逐字）和悬停说明', async () => {
    mocks.apps.readableObjects.mockResolvedValue([
      customer,
      {
        object: published('2009', '币种', 2),
        via: [{ fromObjectId: '2002', fromObjectName: '合同', kind: 'PICK', name: '结算币种' }],
        closed: true
      }
    ])
    await mount()
    expect(mocks.apps.readableObjects).toHaveBeenCalledWith({
      applicationId: 'app',
      objects: [
        { objectId: '2001', versionNo: 11, checksum: '2001-v11' },
        { objectId: '2002', versionNo: 5, checksum: '2002-v5' }
      ]
    })
    const line = must(readableLine(), '因关联而可读取一行')
    expect(line.textContent).toBe(
      '因关联而可读取（只读，不能为它们建列表或表单）：客户（资金流水的“所属客户”）、币种（合同的“结算币种”）（已关闭）'
    )
    expect(must(line.closest('[data-tip]'), '悬停说明').getAttribute('data-tip')).toBe(
      '这些对象没有被本应用引用。系统只读取它们来完成引用选择、名称显示和计算。要为它们建列表或表单，请点“引用对象”。'
    )
    // 这一行不带任何操作。
    expect(line.querySelector('button')).toBeNull()
  })
  it('集合为空：整行不出现', async () => {
    await mount()
    expect(readableLine()).toBeNull()
    expect(host.textContent).not.toContain('因关联而可读取')
  })
  it('读取失败：整行不出现、不弹错，工作台其它功能照常', async () => {
    mocks.apps.readableObjects.mockRejectedValue(new Error('404'))
    await mount()
    expect(readableLine()).toBeNull()
    expect(host.querySelector('[role="alert"][data-type="error"]')).toBeNull()
    expect(mocks.message.error).not.toHaveBeenCalled()
    expect(cell('2001', 'name').textContent).toContain('资金流水')
    expect(toggler('2001')).toBeDefined()
    expect(objectIds('ResourceManager')).toEqual(['2001', '2002'])
  })
  it('隐式可读的对象不进「已引用对象」表，也没有任何操作按钮', async () => {
    mocks.apps.readableObjects.mockResolvedValue([customer])
    await mount()
    expect(host.querySelector('[data-row="2007"]')).toBeNull()
    expect(host.querySelectorAll('[data-row]')).toHaveLength(2)
  })
  it('边界：挑选对象用的对象表只有已引用的对象；隐式对象只经 readableObjects 交给页面与视图', async () => {
    mocks.apps.readableObjects.mockResolvedValue([customer])
    await mount()
    for (const name of ['ResourceManager', 'BusinessConfigManager', 'ApplicationMembers'])
      expect(objectIds(name), name).toEqual(['2001', '2002'])
    expect(objectIds('ApplicationPublishDialog')).toEqual(['2001', '2002'])
    expect(objectIds('ResourceManager', 'readableObjects')).toEqual(['2007'])
    for (const name of ['BusinessConfigManager', 'ApplicationMembers', 'ApplicationPublishDialog'])
      expect(received(name, 'readableObjects'), name).toBeUndefined()
  })
  it('草稿里移除一个对象后重新拉取', async () => {
    mocks.apps.readableObjects.mockResolvedValue([customer])
    await mount()
    expect(mocks.apps.readableObjects).toHaveBeenCalledTimes(1)
    mocks.apps.readableObjects.mockResolvedValue([])
    must(cell('2001', 'actions').querySelector<HTMLButtonElement>('button[aria-label="移除引用"]'), '移除引用').click()
    await flush()
    expect(mocks.apps.readableObjects).toHaveBeenCalledTimes(2)
    expect(mocks.apps.readableObjects).toHaveBeenLastCalledWith({
      applicationId: 'app',
      objects: [{ objectId: '2002', versionNo: 5, checksum: '2002-v5' }]
    })
    expect(readableLine()).toBeNull()
  })
})

import { afterEach, beforeAll, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import Antd from 'ant-design-vue'
import type { ObjectGrant } from '@/types/nocode/authorization'
import type { TaskEntryPolicy } from '@/types/nocode/task-entry'

const mocks = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), sharing: vi.fn() }))
vi.mock('@/utils/request', () => ({ default: { get: mocks.get, post: mocks.post } }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ applications: { sharing: mocks.sharing } }) }))
vi.mock('@/nocode/unsaved', () => ({ confirmDiscard: vi.fn(), useUnsavedNavigation: vi.fn() }))
vi.mock('@/components/UserSelectorTrigger.vue', () => ({
  default: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    setup:
      (_: unknown, { emit }: any) =>
      () =>
        h('button', { onClick: () => emit('update:modelValue', ['10']) }, '选择测试人员')
  }
}))
import ApplicationMembers from '@/views/nocode/application/components/ApplicationMembers.vue'

const apps: App[] = []
let storedPolicy: TaskEntryPolicy
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const grant = (): ObjectGrant => ({
  objectId: 'purchase',
  actions: ['READ', 'CREATE', 'UPDATE'],
  scope: 'ALL',
  readFields: ['name', 'secret'],
  writeFields: ['name', 'secret'],
  readDetails: [],
  writeDetails: [],
  readRelations: [],
  writeRelations: [],
  actionScopes: {}
})
const shared = () => ({ ...grant(), actions: ['READ', 'CREATE'], readFields: ['name'], writeFields: ['name'] })
beforeAll(() => {
  window.matchMedia = vi.fn().mockImplementation(() => ({ matches: false, addListener() {}, removeListener() {} }))
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  )
  Element.prototype.scrollIntoView = vi.fn()
})
beforeEach(() => {
  storedPolicy = { revision: 3, enabled: true, members: [] }
  mocks.get.mockImplementation(async (url: string) => {
    if (url === '/nocode/task-entry/policy') return clone(storedPolicy)
    if (url === '/system/role/list-all-simple') return [{ id: '20', name: '采购角色' }]
    throw new Error(`Unexpected GET ${url}`)
  })
  mocks.sharing.mockResolvedValue([{ objectId: 'purchase', permission: shared() }])
  mocks.post.mockImplementation(async (url: string, payload: any) => {
    expect(url).toBe('/nocode/task-entry/policy')
    expect(payload.expectedRevision).toBe(storedPolicy.revision)
    storedPolicy = clone({ revision: storedPolicy.revision + 1, enabled: payload.enabled, members: payload.members })
    return clone(storedPolicy)
  })
})
afterEach(() => {
  apps.splice(0).forEach(app => app.unmount())
  document.body.innerHTML = ''
  vi.clearAllMocks()
})
function mount() {
  const limits = reactive([grant()])
  const objects = {
    purchase: {
      objectId: 'purchase',
      definition: {
        objectName: '采购单',
        fields: [
          { id: 'name', name: '名称', type: 'TEXT' },
          { id: 'secret', name: '内部字段', type: 'TEXT' }
        ],
        details: [],
        relations: []
      }
    }
  }
  const host = document.createElement('div')
  document.body.append(host)
  const errors: unknown[] = []
  const app = createApp({
    render: () => h(ApplicationMembers, { applicationId: 'app', entryId: 'entry', limits, objects: objects as any })
  })
  app.config.errorHandler = error => errors.push(error)
  app.use(Antd)
  app.mount(host)
  apps.push(app)
  return { app, host, limits, errors }
}
function button(host: Element, name: string) {
  return Array.from(host.querySelectorAll('button')).find(b => b.textContent?.replace(/\s/g, '') === name)!
}
async function loaded(host: Element) {
  await vi.waitFor(() => expect(mocks.get).toHaveBeenCalledWith('/system/role/list-all-simple'))
  await vi.waitFor(() => expect(button(host, '添加成员或角色').disabled).toBe(false))
}
async function selectOption(select: Element, label: string) {
  select.querySelector('.ant-select-selector')!.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
  const option = await vi.waitFor(() => {
    const option = Array.from(document.querySelectorAll<HTMLElement>('.ant-select-item-option')).find(
      o => o.textContent?.trim() === label
    )
    expect(option).toBeTruthy()
    return option!
  })
  option.click()
  await nextTick()
}

it('空入口点击添加成员时，响应式权限上限不会中断渲染', async () => {
  const { host, limits, errors } = mount()
  const original = clone(limits)
  await loaded(host)
  expect(host.textContent).toContain('尚未授权其他成员')
  button(host, '添加成员或角色').click()
  await nextTick()
  expect(errors).toEqual([])
  expect(host.querySelectorAll('.member-card')).toHaveLength(1)
  expect(host.textContent).toContain('可访问对象')
  expect(limits).toEqual(original)
})

it('添加角色、配置范围、保存启停并重新打开后保留授权，且不改入口上限', async () => {
  const { host, limits, errors, app } = mount()
  const original = clone(limits)
  await loaded(host)
  button(host, '添加成员或角色').click()
  await nextTick()
  expect(errors).toEqual([])
  await selectOption(host.querySelector('.member-card .ant-select')!, '系统角色')
  await selectOption(host.querySelectorAll('.member-card .ant-select')[1]!, '采购角色')
  await selectOption(host.querySelector('.member-card .ant-select-multiple')!, '采购单')
  await nextTick()
  expect(host.querySelector('.grant')).not.toBeNull()
  const update = Array.from(host.querySelectorAll<HTMLElement>('.grant .ant-checkbox-wrapper')).find(
    el => el.textContent?.trim() === '修改'
  )!
  expect(update.querySelector<HTMLInputElement>('input')!.disabled).toBe(true)
  const create = Array.from(host.querySelectorAll<HTMLElement>('.grant .ant-checkbox-wrapper')).find(
    el => el.textContent?.trim() === '新增'
  )!
  create.querySelector<HTMLInputElement>('input')!.click()
  host.querySelector<HTMLButtonElement>('.ant-switch')!.click()
  button(host, '保存授权').click()
  await vi.waitFor(() => expect(mocks.post).toHaveBeenCalledTimes(1))
  expect(storedPolicy.enabled).toBe(false)
  expect(storedPolicy.members).toEqual([
    expect.objectContaining({
      principalKind: 'ROLE',
      principalId: '20',
      objects: [expect.objectContaining({ objectId: 'purchase', actions: ['READ', 'CREATE'], readFields: ['name'] })]
    })
  ])
  expect(limits).toEqual(original)
  expect(errors).toEqual([])
  app.unmount()
  apps.splice(apps.indexOf(app), 1)
  host.remove()
  const reopened = mount()
  await loaded(reopened.host)
  expect(reopened.errors).toEqual([])
  expect(reopened.host.querySelectorAll('.member-card')).toHaveLength(1)
  expect(reopened.host.textContent).toContain('采购角色')
  expect(reopened.host.querySelector('.ant-switch')?.getAttribute('aria-checked')).toBe('false')
})

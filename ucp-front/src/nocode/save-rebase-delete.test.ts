// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, provide, type App, type Component, type ComponentPublicInstance } from 'vue'
import { message } from 'ant-design-vue'
import BusinessRecords from '@/views/nocode/application/components/BusinessRecords.vue'
import type { ApplicationResource } from '@/types/nocode/application'
import type { BusinessRow } from '@/types/nocode/runtime'
import { taskEntrySessionKey } from './task-entry-context'

const api = vi.hoisted(() => ({
  model: vi.fn(),
  page: vi.fn(),
  get: vi.fn(),
  delete: vi.fn(),
  action: vi.fn()
}))
const flags = vi.hoisted(() => ({ rebaseDelete: true }))
vi.mock('@/nocode/save-rebase', async original => ({
  ...(await original<typeof import('./save-rebase')>()),
  get REBASE_DELETE() {
    return flags.rebaseDelete
  }
}))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api }) }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'rebase-delete-test' } }) }))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/RecordEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/SelectionField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/RecordQueryField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/BusinessFileField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/nocode/directory-options', () => ({ directoryTypes: [], directoryOptions: async () => [] }))

interface ListState {
  rows: BusinessRow[]
  error: string
  batchResult: string
  remove: (row: BusinessRow) => Promise<void>
  batchDelete: (keys: string[]) => Promise<void>
  executeAction: (action: ApplicationResource, row: BusinessRow) => Promise<void>
}
const mounted: Array<{ app: App; host: HTMLElement }> = []
const flush = async () => {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function setup({ taskEntry = false } = {}) {
  let instance: ComponentPublicInstance | null = null
  const host = document.createElement('div')
  const target = { ...(BusinessRecords as Component), render: () => null }
  const app = createApp({
    setup() {
      if (taskEntry)
        provide(taskEntrySessionKey, {
          key: 'entry',
          saveDraft: async () => ({}) as never,
          loadDraft: async () => null,
          checkDraft: async () => null
        })
      return () =>
        h(target, {
          applicationId: 'app',
          objectId: 'object',
          view: { objectId: 'object', fieldIds: ['name'], list: { batchDelete: true } },
          ref: (value: Element | ComponentPublicInstance | null) => {
            if (value && '$' in value) instance = value
          }
        })
    }
  })
  document.body.append(host)
  app.mount(host)
  mounted.push({ app, host })
  await flush()
  if (!instance) throw new Error('组件未挂载')
  return (instance as ComponentPublicInstance & { $: { setupState: ListState } }).$.setupState
}
const permissions = {
  actions: ['READ', 'CREATE', 'UPDATE', 'DELETE'],
  readFields: ['name'],
  writeFields: ['name'],
  readDetails: [],
  writeDetails: []
}
const row = (id: string, revision = '1'): BusinessRow => ({ id, revision, values: { name: id }, permissions })
const latest = (id: string, revision: string | null) => ({ record: row(id, revision as string), details: {} })
const conflict = () => Object.assign(new Error('记录已被修改，请刷新后重试'), { businessCode: 1_050_000_004 })
const missing = () => Object.assign(new Error('记录不存在或不可访问'), { businessCode: 1_050_000_002 })
const blocked = () => Object.assign(new Error('存在引用，不能删除'), { businessCode: 1_050_000_001 })
const body = (id: string, expectedRevision: string) => ({
  applicationId: 'app',
  objectId: 'object',
  id,
  expectedRevision
})
const success = vi.spyOn(message, 'success').mockImplementation(() => (() => undefined) as never)

beforeEach(() => {
  flags.rebaseDelete = true
  api.model.mockResolvedValue({
    writable: true,
    permissions,
    object: {
      objectId: 'object',
      objectName: '对象',
      titleFieldId: 'name',
      fields: [{ id: 'name', name: '名称', type: 'TEXT' }],
      fieldOptions: {},
      details: [],
      relations: [],
      settings: {}
    },
    details: {}
  })
  api.page.mockImplementation(async () => ({ list: [row('A'), row('B'), row('C')], total: 3 }))
})
afterEach(() => {
  mounted.splice(0).forEach(({ app, host }) => {
    app.unmount()
    host.remove()
  })
  vi.resetAllMocks()
  success.mockImplementation(() => (() => undefined) as never)
})

describe('P9 列表删除遇到「记录已被修改」', () => {
  it('取最新修订号再删一次：成功', async () => {
    const list = await setup()
    api.delete.mockRejectedValueOnce(conflict()).mockResolvedValueOnce(true)
    api.get.mockResolvedValue(latest('A', '4'))
    await list.remove(row('A'))
    expect(api.delete.mock.calls).toEqual([[body('A', '1')], [body('A', '4')]])
    expect(api.get).toHaveBeenCalledTimes(1)
    expect(api.get).toHaveBeenCalledWith('app', 'object', 'A', { quiet: true })
    expect(success).toHaveBeenCalledWith('记录已删除')
    expect(list.error).toBe('')
  })

  it('取最新时发现记录已经不存在（别人先删了）：视为删除成功', async () => {
    const list = await setup()
    api.delete.mockRejectedValueOnce(conflict())
    api.get.mockRejectedValue(missing())
    await list.remove(row('A'))
    expect(api.delete).toHaveBeenCalledTimes(1)
    expect(success).toHaveBeenCalledWith('记录已删除')
    expect(list.error).toBe('')
  })

  it('第二次仍冲突：只重试一次，按原来的方式提示', async () => {
    const list = await setup()
    api.delete.mockRejectedValue(conflict())
    api.get.mockResolvedValue(latest('A', '4'))
    await list.remove(row('A'))
    expect(api.delete).toHaveBeenCalledTimes(2)
    expect(api.get).toHaveBeenCalledTimes(1)
    expect(list.error).toBe('记录已被修改，请刷新后重试')
    expect(success).not.toHaveBeenCalled()
  })

  it('取最新因别的原因失败、或最新记录没有修订号：按原来的冲突提示', async () => {
    const list = await setup()
    api.delete.mockRejectedValue(conflict())
    api.get.mockRejectedValueOnce(new Error('网络错误'))
    await list.remove(row('A'))
    expect(api.delete).toHaveBeenCalledTimes(1)
    expect(list.error).toBe('记录已被修改，请刷新后重试')

    api.get.mockResolvedValueOnce(latest('A', null))
    await list.remove(row('A'))
    expect(api.delete).toHaveBeenCalledTimes(2)
    expect(list.error).toBe('记录已被修改，请刷新后重试')
  })

  it('不是冲突的失败：不重试', async () => {
    const list = await setup()
    api.delete.mockRejectedValue(blocked())
    await list.remove(row('A'))
    expect(api.delete).toHaveBeenCalledTimes(1)
    expect(api.get).not.toHaveBeenCalled()
    expect(list.error).toBe('存在引用，不能删除')
  })

  it('批量删除逐条同样处理，汇总不变', async () => {
    const list = await setup()
    // A 冲突后重试成功；B 冲突后发现已被删；C 重试仍冲突
    api.delete.mockImplementation(async (command: { id: string; expectedRevision: string }) => {
      if (command.expectedRevision === '1') throw conflict()
      if (command.id === 'C') throw conflict()
      return true
    })
    api.get.mockImplementation(async (_app: string, _object: string, id: string) => {
      if (id === 'B') throw missing()
      return latest(id, '2')
    })
    await list.batchDelete(['A', 'B', 'C'])
    expect(api.delete.mock.calls.map(([command]) => [command.id, command.expectedRevision])).toEqual([
      ['A', '1'],
      ['A', '2'],
      ['B', '1'],
      ['C', '1'],
      ['C', '2']
    ])
    expect(list.batchResult).toBe('已删除 2 条，失败 1 条。记录 C：记录已被修改，请刷新后重试')
  })

  it('任务入口场景不重试，保持原行为', async () => {
    const list = await setup({ taskEntry: true })
    api.delete.mockRejectedValue(conflict())
    await list.remove(row('A'))
    expect(api.delete).toHaveBeenCalledTimes(1)
    expect(api.get).not.toHaveBeenCalled()
    expect(list.error).toBe('记录已被修改，请刷新后重试')
  })
})

describe('P10 删除重试的开关', () => {
  it('关掉之后恢复原行为：一次冲突即失败', async () => {
    flags.rebaseDelete = false
    const list = await setup()
    api.delete.mockRejectedValue(conflict())
    await list.remove(row('A'))
    expect(api.delete).toHaveBeenCalledTimes(1)
    expect(api.get).not.toHaveBeenCalled()
    expect(list.error).toBe('记录已被修改，请刷新后重试')
    await list.batchDelete(['A'])
    expect(api.delete).toHaveBeenCalledTimes(2)
    expect(list.batchResult).toBe('已删除 0 条，失败 1 条。记录 A：记录已被修改，请刷新后重试')
  })
})

describe('P11 业务动作', () => {
  it('遇到冲突不重试', async () => {
    const list = await setup()
    api.action.mockRejectedValue(conflict())
    await list.executeAction({ id: 'action' } as ApplicationResource, row('A'))
    expect(api.action).toHaveBeenCalledTimes(1)
    expect(api.get).not.toHaveBeenCalled()
    expect(list.error).toBe('记录已被修改，请刷新后重试')
  })
})

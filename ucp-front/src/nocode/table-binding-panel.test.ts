// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, reactive, type App } from 'vue'
import TableBindingPanel from '@/views/nocode/components/TableBindingPanel.vue'
import type { AdoptionPreflight, TableBinding } from '@/types/nocode/data-center'

const mocks = vi.hoisted(() => ({ preflight: vi.fn(), hasPermission: vi.fn() }))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({ dataCenter: { preflight: mocks.preflight }, hasPermission: mocks.hasPermission })
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ resolve: () => ({ href: '/nocode/table' }) }) }))

const mounted: { app: App; host: HTMLElement }[] = []
async function flush() {
  for (let index = 0; index < 4; index++) {
    await Promise.resolve()
    await nextTick()
  }
}
function deferred() {
  let resolve!: (result: AdoptionPreflight) => void
  const promise = new Promise<AdoptionPreflight>(done => {
    resolve = done
  })
  return { resolve, promise }
}
function result(schema = 'public'): AdoptionPreflight {
  return {
    schemaName: schema,
    tableName: 'existing_table',
    fingerprint: schema,
    allowed: false,
    readOnly: true,
    checks: [{ code: 'CLAIMED', message: '已纳管', blocking: true }],
    titleColumns: ['id'],
    structure: {
      relation: { schema, name: 'existing_table', kind: 'TABLE', comment: null },
      columns: [],
      constraints: [],
      indexes: [],
      triggers: [],
      statistics: {
        estimatedRows: 0,
        tableBytes: 0,
        indexBytes: 0,
        totalBytes: 0,
        rowSecurity: false,
        forceRowSecurity: false,
        canSelect: true,
        canWrite: true
      }
    }
  }
}
async function setup() {
  const binding = reactive<TableBinding>({
    source: 'ADOPTED',
    schemaName: 'public',
    keyColumn: 'id',
    parentColumn: null,
    structureMode: 'RETAIN',
    readOnly: true,
    repairBaseFields: false,
    fingerprint: 'public'
  })
  const app = createApp(
    { ...TableBindingPanel, render: () => null },
    { modelValue: binding, tableName: 'existing_table' }
  )
  const host = document.createElement('div')
  document.body.append(host)
  const vm = app.mount(host)
  mounted.push({ app, host })
  await flush()
  const state = (
    vm.$ as unknown as { setupState: { preflight?: AdoptionPreflight; checking: boolean; checkError: string } }
  ).setupState
  return { binding, state }
}
beforeEach(() => {
  vi.clearAllMocks()
  mocks.hasPermission.mockReturnValue(true)
})
afterEach(() => {
  for (const item of mounted.splice(0)) {
    item.app.unmount()
    item.host.remove()
  }
})

describe('纳管面板预检会话', () => {
  it('缺少数据表查询权限时不调用需要更完整权限的预检接口', async () => {
    mocks.hasPermission.mockImplementation(permission => permission !== 'nocode:table:query')
    const { state } = await setup()
    expect(mocks.preflight).not.toHaveBeenCalled()
    expect(state.preflight).toBeUndefined()
    expect(state.checking).toBe(false)
  })

  it('更换绑定 Schema 后迟到的旧结果不覆盖新表能力，也不改写草稿', async () => {
    const first = deferred(),
      second = deferred()
    mocks.preflight.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const { binding, state } = await setup()
    binding.schemaName = 'new_schema'
    await flush()
    second.resolve(result('new_schema'))
    await flush()
    first.resolve(result())
    await flush()
    expect(state.preflight?.schemaName).toBe('new_schema')
    expect(binding.fingerprint).toBe('public')
    expect(binding.structureMode).toBe('RETAIN')
    expect(binding.readOnly).toBe(true)
    expect(state.checking).toBe(false)
  })

  it('预检失败保留未知状态和可见错误，不误报可以写入', async () => {
    mocks.preflight.mockRejectedValue(new Error('数据库连接暂不可用'))
    const { state, binding } = await setup()
    expect(state.preflight).toBeUndefined()
    expect(state.checkError).toContain('数据库连接暂不可用')
    expect(state.checking).toBe(false)
    expect(binding.readOnly).toBe(true)
  })
})

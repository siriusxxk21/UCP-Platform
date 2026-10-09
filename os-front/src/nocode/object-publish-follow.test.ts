// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, reactive, type App, type Component } from 'vue'
import ObjectEditor from '@/views/nocode/object/editor.vue'
import { newDesign } from './data-center'
import type { ObjectDesign, PublishPlan, StructureCheck } from '@/types/nocode/data-center'
import type { FollowResult } from '@/types/nocode/application'

/**
 * 对象发布成功后的自动跟随提示（契约 10.6），以及发布窗口里四种新提示（契约 5.7）：
 * 它们是普通的不阻断检查项，现有列表原样显示，不需要改模板。
 */
const mocks = vi.hoisted(() => ({
  data: {
    design: vi.fn(),
    history: vi.fn(),
    plan: vi.fn(),
    execute: vi.fn(),
    followResult: vi.fn(),
    objects: vi.fn(),
    version: vi.fn()
  },
  route: {} as { path: string; query: Record<string, string> },
  router: { replace: vi.fn(), push: vi.fn(), resolve: vi.fn(() => ({ href: '' })) },
  message: { success: vi.fn(), warning: vi.fn(), info: vi.fn() },
  modal: { confirm: vi.fn(), success: vi.fn() }
}))
vi.mock('@/nocode/platform', () => ({
  nocodePlatformKey: Symbol('test-platform'),
  useNocodePlatform: () => ({
    dataCenter: mocks.data,
    hasPermission: () => true,
    directory: { users: async () => [], departments: async () => [] }
  })
}))
vi.mock('@/api/nocode/data-center', () => ({ createDataCenterApi: () => mocks.data }))
vi.mock('@/utils/request', () => ({ default: { get: vi.fn(), post: vi.fn() } }))
vi.mock('ant-design-vue', () => ({ message: mocks.message, Modal: mocks.modal, Upload: { LIST_IGNORE: '' } }))
vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => mocks.router,
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn()
}))
vi.mock('@/components/os-table-page/OsTablePage.vue', () => ({ default: {} }))
vi.mock('@/components/os-modal-form/OsModalForm.vue', () => ({ default: {} }))
vi.mock('@/components/UserSelectorTrigger.vue', () => ({ default: {} }))
vi.mock('@/views/nocode/components/FieldValueEditor.vue', () => ({ default: {} }))

const mounted: Array<{ app: App; host: HTMLElement }> = []
async function flush() {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
interface EditorState {
  planPublish: () => Promise<void>
  publish: () => Promise<void>
  reason: string
  planOpen: boolean
  publishError: string
  publishChecks: StructureCheck[]
  publishCheckHelp: (check: StructureCheck) => unknown
}
/** 保留真实 setup 与发布流程，不渲染模板（写法同 relation-autosave.test.ts）。 */
async function setup() {
  const app = createApp({ ...(ObjectEditor as Component), render: () => null })
  const host = document.createElement('div')
  document.body.append(host)
  const vm = app.mount(host)
  mounted.push({ app, host })
  await flush()
  return (vm.$ as unknown as { setupState: EditorState }).setupState
}
function design(): ObjectDesign {
  const input = newDesign()
  const [first] = input.draft.fields
  const name = { ...first, id: 'field', key: 'field', name: '名称', code: 'name' }
  return {
    ...input,
    draft: {
      ...input.draft,
      id: 'object',
      objectCode: 'example',
      objectName: '示例',
      tableName: 'biz_example',
      titleFieldId: name.id,
      fields: [name],
      lockVersion: 1,
      versionNo: 2,
      state: 'DRAFT',
      updatedAt: ''
    },
    fieldOptions: {},
    source: 'GENERATED',
    schemaName: 'public',
    status: 'ACTIVE',
    publishedVersion: 1,
    readOnly: false,
    versions: [],
    dependencies: []
  } as unknown as ObjectDesign
}
const checks: StructureCheck[] = [
  {
    code: 'APPLICATION_FOLLOW',
    message: '发布后将自动跟随并生效的应用（1 个）：资金管理。不需要再到应用里同步、保存、发布。',
    blocking: false
  },
  {
    code: 'APPLICATION_FOLLOW_PENDING',
    message:
      '应用“合同”暂时跟不上：还有 1 条流程审批、0 条办理申请没有完结。它继续按当前版本运行；这些审批完结（通过并生效，或由申请人放弃）后自动跟上。',
    blocking: false
  },
  {
    code: 'FIELD_EXPOSURE',
    message:
      '本次新增字段：备注二。应用跟随后，这些字段会自动对“可查看字段”选了“全部”的成员可见——资金管理：应用创建人、张三。',
    blocking: false
  },
  {
    code: 'HANDLING_IN_FLIGHT',
    message:
      '本对象还有 2 条“新增 / 修改须审批”的申请在审批中。对象发布后，这些申请通过时将无法生效，需要申请人重新提交。',
    blocking: false
  }
]
const plan = (extra: Partial<PublishPlan> = {}): PublishPlan => ({
  id: 'plan-1',
  objectId: 'object',
  revision: 1,
  versionNo: 2,
  state: 'PENDING',
  changes: [],
  checks: [],
  dependencies: [],
  conversions: [],
  applicationUpgrades: [],
  createdAt: '',
  ...extra
})
const followed = (applicationName: string, outcome: FollowResult['outcome']): FollowResult => ({
  applicationId: applicationName,
  applicationName,
  outcome,
  fromVersion: 1,
  toVersion: 2,
  applicationVersion: 5,
  reason: null
})
async function publishWith(results: () => Promise<FollowResult[]>) {
  mocks.data.followResult.mockImplementation(results)
  const state = await setup()
  await state.planPublish()
  state.reason = '加一个备注字段'
  await state.publish()
  await flush()
  return state
}
let warn: ReturnType<typeof vi.spyOn>
beforeEach(() => {
  vi.resetAllMocks()
  warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
  mocks.route = reactive({ path: '/nocode/object/editor', query: { id: 'object' } })
  mocks.router.resolve.mockReturnValue({ href: '' })
  mocks.data.design.mockResolvedValue(design())
  mocks.data.history.mockResolvedValue([])
  mocks.data.objects.mockResolvedValue({ list: [], total: 0 })
  mocks.data.version.mockResolvedValue({ fields: [] })
  mocks.data.plan.mockResolvedValue(plan())
  mocks.data.execute.mockResolvedValue({ id: 'plan-1', objectId: 'object', versionNo: 2, state: 'SUCCEEDED' })
})
afterEach(() => {
  for (const { app, host } of mounted.splice(0)) {
    app.unmount()
    host.remove()
  }
  warn.mockRestore()
})

describe('对象发布成功后的跟随提示', () => {
  it('有应用没跟上：按发布计划查询，出警告（文案逐字），发布结果照常', async () => {
    const state = await publishWith(async () => [followed('资金管理', 'FOLLOWED'), followed('合同', 'PENDING')])
    expect(mocks.data.followResult).toHaveBeenCalledWith('plan-1')
    expect(mocks.message.warning).toHaveBeenCalledWith(
      '对象已发布。1 个应用暂时没跟上：合同。原因见各应用的“已引用对象”。'
    )
    expect(mocks.message.success).toHaveBeenCalledWith('对象结构已发布')
    expect(state.publishError).toBe('')
    expect(state.planOpen).toBe(false)
  })
  it('全部跟上：出成功提示（文案逐字）', async () => {
    await publishWith(async () => [followed('资金管理', 'FOLLOWED'), followed('合同', 'FOLLOWED')])
    expect(mocks.message.success).toHaveBeenCalledWith('对象已发布，2 个应用已自动跟上。')
    expect(mocks.message.success).toHaveBeenCalledWith('对象结构已发布')
    expect(mocks.message.warning).not.toHaveBeenCalled()
  })
  it('没有任何跟随：不出跟随提示', async () => {
    await publishWith(async () => [])
    expect(mocks.message.success.mock.calls).toEqual([['对象结构已发布']])
    expect(mocks.message.warning).not.toHaveBeenCalled()
  })
  it('接口失败（含后端还没有这个接口）：不报错，不影响「已发布」的结果', async () => {
    const state = await publishWith(async () => {
      throw new Error('404')
    })
    expect(mocks.data.followResult).toHaveBeenCalledTimes(1)
    expect(state.publishError).toBe('')
    expect(state.planOpen).toBe(false)
    expect(mocks.message.success.mock.calls).toEqual([['对象结构已发布']])
    expect(mocks.message.warning).not.toHaveBeenCalled()
  })
  it('发布失败时不查询跟随结果', async () => {
    mocks.data.execute.mockResolvedValue({
      id: 'plan-1',
      objectId: 'object',
      versionNo: 2,
      state: 'FAILED',
      error: '结构校验未通过'
    })
    const state = await publishWith(async () => [followed('资金管理', 'FOLLOWED')])
    expect(mocks.data.followResult).not.toHaveBeenCalled()
    expect(state.publishError).toBe('结构校验未通过')
    expect(mocks.message.success).not.toHaveBeenCalled()
  })
})

describe('发布窗口里的四种跟随提示', () => {
  it('作为不阻断的检查项原样进入列表，没有「去看看」链接', async () => {
    mocks.data.plan.mockResolvedValue(plan({ checks }))
    const state = await setup()
    await state.planPublish()
    await flush()
    expect(state.publishChecks.map(check => check.code)).toEqual([
      'APPLICATION_FOLLOW',
      'APPLICATION_FOLLOW_PENDING',
      'FIELD_EXPOSURE',
      'HANDLING_IN_FLIGHT'
    ])
    expect(state.publishChecks.map(check => check.message)).toEqual(checks.map(check => check.message))
    // 模板按 blocking 取样式：不阻断的显示为次要文字。
    expect(state.publishChecks.every(check => check.blocking === false)).toBe(true)
    for (const check of state.publishChecks) expect(state.publishCheckHelp(check)).toBeNull()
  })
})

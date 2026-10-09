// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import OrderedCalculationCalibration from '@/views/nocode/components/OrderedCalculationCalibration.vue'
import { defaultFieldOptions } from './data-center'
import type { ObjectDataModel } from '@/types/nocode/object-data'
import type {
  OrderedCalculationState,
  OrderedCalibrationCommand,
  OrderedCalibrationPreview,
  OrderedCalibrationResult
} from '@/types/nocode/ordered-calculation'

const api = vi.hoisted(() => ({
  model: vi.fn(),
  calculationStatus: vi.fn(),
  calculationPreview: vi.fn(),
  calibrate: vi.fn(),
  resumeCalculation: vi.fn(),
  retryCalculation: vi.fn(),
  pauseCalculation: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ objectData: api }) }))
const calculation = { mode: 'RUNNING_TOTAL', updateMode: 'ON_SAVE', aggregate: 'SUM', conditions: [] }
const model = {
  versionNo: 2,
  checksum: 'v2',
  model: {
    object: {
      fields: ['balance', 'adjacent', 'snapshot', 'live', 'plain'].map(id => ({
        id,
        key: id,
        code: id,
        name: id,
        type: id === 'plain' ? 'DECIMAL' : 'FORMULA'
      })),
      fieldOptions: {
        balance: { ...defaultFieldOptions(), calculation },
        adjacent: { ...defaultFieldOptions(), calculation: { ...calculation, mode: 'SEQUENCE' } },
        snapshot: { ...defaultFieldOptions(), calculation: { ...calculation, mode: 'LOCAL' } },
        live: { ...defaultFieldOptions(), calculation: { ...calculation, updateMode: 'LIVE' } },
        plain: defaultFieldOptions()
      }
    }
  }
} as unknown as ObjectDataModel
const preview: OrderedCalibrationPreview = {
  objectId: 'o',
  versionNo: 3,
  checksum: 'v3',
  fields: [
    {
      fieldId: 'balance',
      signature: 'rule-1',
      state: 'PENDING',
      groups: 10,
      rows: 20,
      nullRows: 4,
      changedRows: 5,
      fillRows: 2,
      incorrectRows: 3,
      validNullRows: 2
    }
  ]
}
const state = (status: OrderedCalculationState['state'], requestId = 'persisted'): OrderedCalculationState => ({
  objectId: 'o',
  fieldId: 'balance',
  signature: 'rule-1',
  state: status,
  cursor: { requestId, fieldIds: ['balance'], versionNo: 3, checksum: 'v3' },
  totalRows: 20,
  updatedRows: status === 'READY' ? 5 : 2,
  completedGroups: status === 'READY' ? 10 : 5,
  error: status === 'FAILED' ? '重算失败' : null,
  revision: 1
})
const result = (status: OrderedCalculationState['state'], requestId = 'persisted'): OrderedCalibrationResult => ({
  requestId,
  complete: status === 'READY',
  states: [state(status, requestId)]
})
interface SetupState {
  choices: { value: string }[]
  fieldIds: string[]
  preview?: OrderedCalibrationPreview
  command?: OrderedCalibrationCommand
  acknowledged: boolean
  canStart: boolean
  advancing: boolean
  failure: string
  runs: { id: string; states: OrderedCalculationState[] }[]
  inspect: () => Promise<void>
  start: () => void
  resume: (run: { id: string; states: OrderedCalculationState[] }) => void
  pauseRun: (run: { id: string; states: OrderedCalculationState[] }) => void
  requestPause: () => void
  close: () => void
  clearPreview: () => void
}
const mounted: App[] = []
async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount() {
  const cancel = vi.fn(),
    changed = vi.fn()
  const app = createApp(
    { ...OrderedCalculationCalibration, render: () => null },
    { objectId: 'o', model, onCancel: cancel, onChanged: changed }
  )
  const vm = app.mount(document.createElement('div'))
  mounted.push(app)
  await flush()
  return { state: (vm.$ as unknown as { setupState: SetupState }).setupState, cancel, changed }
}
beforeEach(() => {
  vi.resetAllMocks()
  api.model.mockResolvedValue({ ...model, versionNo: 3, checksum: 'v3' })
  api.calculationStatus.mockResolvedValue([])
  api.calculationPreview.mockResolvedValue(preview)
  api.calibrate.mockImplementation((command: OrderedCalibrationCommand) =>
    Promise.resolve(result('BACKFILLING', command.requestId))
  )
  api.resumeCalculation.mockImplementation((command: OrderedCalibrationCommand) =>
    Promise.resolve(result('READY', command.requestId))
  )
  api.retryCalculation.mockImplementation((command: OrderedCalibrationCommand) =>
    Promise.resolve(result('READY', command.requestId))
  )
  api.pauseCalculation.mockImplementation((command: OrderedCalibrationCommand) =>
    Promise.resolve(result('FAILED', command.requestId))
  )
})
afterEach(() => mounted.splice(0).forEach(app => app.unmount()))

describe('有序计算校准会话', () => {
  it('仅选择有序ON_SAVE，预览固定最新版本且未经维护确认不执行', async () => {
    const { state } = await mount()
    expect(state.choices.map(choice => choice.value)).toEqual(['balance', 'adjacent'])
    state.fieldIds = ['balance']
    await state.inspect()
    expect(api.calculationPreview).toHaveBeenCalledExactlyOnceWith({
      objectId: 'o',
      versionNo: 3,
      checksum: 'v3',
      fieldIds: ['balance']
    })
    state.start()
    expect(api.calibrate).not.toHaveBeenCalled()
    state.acknowledged = true
    state.start()
    await flush()
    const command = api.calibrate.mock.calls[0]?.[0]
    expect(command).toMatchObject({
      versionNo: 3,
      checksum: 'v3',
      fieldIds: ['balance'],
      signatures: { balance: 'rule-1' },
      maxGroups: 1
    })
    expect(api.resumeCalculation).toHaveBeenCalledExactlyOnceWith(command)
    expect(state.preview).toBeUndefined()
    expect(state.advancing).toBe(false)
  })
  it('刷新或重新打开从持久批次恢复，沿用原版本与操作标识重试失败组', async () => {
    api.model.mockResolvedValue({ ...model, versionNo: 9, checksum: 'v9' })
    api.calculationStatus.mockResolvedValue([state('FAILED')])
    const { state: view } = await mount()
    view.resume(view.runs[0]!)
    await flush()
    expect(api.retryCalculation).toHaveBeenCalledExactlyOnceWith({
      objectId: 'o',
      versionNo: 3,
      checksum: 'v3',
      fieldIds: ['balance'],
      signatures: { balance: 'rule-1' },
      requestId: 'persisted',
      maxGroups: 1
    })
    expect(api.calibrate).not.toHaveBeenCalled()
    expect(view.runs).toEqual([])
  })
  it('关闭页面不再推进下一批，在途请求完成后保留服务端进度', async () => {
    let finish!: (value: OrderedCalibrationResult) => void
    api.calibrate.mockReturnValue(
      new Promise<OrderedCalibrationResult>(resolve => {
        finish = resolve
      })
    )
    const { state: view, cancel } = await mount()
    await view.inspect()
    view.acknowledged = true
    view.start()
    view.close()
    finish(result('BACKFILLING', api.calibrate.mock.calls[0]?.[0].requestId))
    await flush()
    expect(cancel).toHaveBeenCalledOnce()
    expect(api.resumeCalculation).not.toHaveBeenCalled()
    expect(api.pauseCalculation).not.toHaveBeenCalled()
    expect(view.runs).toHaveLength(1)
  })
  it('显式暂停等待在途批次提交，再用相同操作标识保存暂停状态', async () => {
    let finish!: (value: OrderedCalibrationResult) => void
    api.calibrate.mockReturnValue(
      new Promise<OrderedCalibrationResult>(resolve => {
        finish = resolve
      })
    )
    const { state: view } = await mount()
    await view.inspect()
    view.acknowledged = true
    view.start()
    const request = api.calibrate.mock.calls[0]?.[0]
    view.requestPause()
    expect(api.pauseCalculation).not.toHaveBeenCalled()
    finish(result('BACKFILLING', request.requestId))
    await flush()
    expect(api.pauseCalculation).toHaveBeenCalledExactlyOnceWith(request)
    expect(api.resumeCalculation).not.toHaveBeenCalled()
    expect(view.runs[0]?.states[0]?.state).toBe('FAILED')
    expect(view.advancing).toBe(false)
  })
  it('当前批恰好完成全部校准时不会把已就绪状态暂停', async () => {
    let finish!: (value: OrderedCalibrationResult) => void
    api.calibrate.mockReturnValue(
      new Promise<OrderedCalibrationResult>(resolve => {
        finish = resolve
      })
    )
    const { state: view } = await mount()
    await view.inspect()
    view.acknowledged = true
    view.start()
    view.requestPause()
    finish(result('READY', api.calibrate.mock.calls[0]?.[0].requestId))
    await flush()
    expect(api.pauseCalculation).not.toHaveBeenCalled()
    expect(view.runs).toEqual([])
  })
  it('重新打开后可显式暂停未完成批次，沿用批次的固定版本', async () => {
    api.model.mockResolvedValue({ ...model, versionNo: 9, checksum: 'v9' })
    api.calculationStatus.mockResolvedValue([state('BACKFILLING')])
    const { state: view } = await mount()
    view.pauseRun(view.runs[0]!)
    await flush()
    expect(api.pauseCalculation).toHaveBeenCalledExactlyOnceWith({
      objectId: 'o',
      versionNo: 3,
      checksum: 'v3',
      fieldIds: ['balance'],
      signatures: { balance: 'rule-1' },
      requestId: 'persisted',
      maxGroups: 1
    })
    expect(api.calibrate).not.toHaveBeenCalled()
    expect(api.resumeCalculation).not.toHaveBeenCalled()
  })
  it('暂停请求失败不伪造暂停成功，保留原批次供刷新重试', async () => {
    api.calculationStatus.mockResolvedValue([state('BACKFILLING')])
    api.pauseCalculation.mockRejectedValueOnce(new Error('暂停请求中断'))
    const { state: view } = await mount()
    view.pauseRun(view.runs[0]!)
    await flush()
    expect(view.failure).toContain('暂停请求中断')
    expect(view.runs[0]?.states[0]?.state).toBe('BACKFILLING')
    expect(view.advancing).toBe(false)
  })
  it('首次请求超时结果不确定时原样重试，不另建一个校准批次', async () => {
    api.calibrate
      .mockRejectedValueOnce(new Error('timeout of 300000ms exceeded'))
      .mockImplementation((command: OrderedCalibrationCommand) => Promise.resolve(result('READY', command.requestId)))
    const { state: view } = await mount()
    await view.inspect()
    view.acknowledged = true
    view.start()
    await flush()
    expect(view.failure).toContain('timeout of 300000ms exceeded')
    const request = api.calibrate.mock.calls[0]?.[0]
    view.start()
    await flush()
    expect(api.calibrate.mock.calls[1]?.[0]).toEqual(request)
  })
  it('字段选择变化后旧预览不可作为执行依据', async () => {
    let finish!: (value: OrderedCalibrationPreview) => void
    api.calculationPreview.mockReturnValue(
      new Promise<OrderedCalibrationPreview>(resolve => {
        finish = resolve
      })
    )
    const { state: view } = await mount()
    const pending = view.inspect()
    await flush()
    view.fieldIds = ['adjacent']
    view.clearPreview()
    finish(preview)
    await pending
    expect(view.preview).toBeUndefined()
    expect(view.canStart).toBe(false)
  })
})

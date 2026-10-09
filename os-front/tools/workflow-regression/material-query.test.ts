import { afterEach, describe, expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import { useFlowMaterials } from '@/views/bpm/processInstance/detail/use-flow-materials'
import type { FlowMaterialDetail, FlowMaterialItem } from '@/api/nocode/flow-material'
const item = (id: string, extra: Partial<FlowMaterialItem> = {}): FlowMaterialItem => ({
  id,
  taskId: `task-${id}`,
  nodeId: id,
  nodeName: id,
  formName: '流程表单',
  submitterName: '提交人',
  submittedAt: '2026-09-10',
  revision: 1,
  kind: 'FLOW_FORM',
  state: 'CURRENT',
  required: true,
  ...extra
})
const detail = (item: FlowMaterialItem): FlowMaterialDetail => ({
  item,
  flowForm: { conf: '{}', fields: ['{"type":"input","field":"value","title":"内容"}'], values: { value: item.id } }
})
const disposers: (() => void)[] = []
afterEach(() => disposers.splice(0).forEach(fn => fn()))
function setup(
  items = [item('a'), item('b'), item('c'), item('history', { nodeId: 'a', state: 'HISTORY', required: false })]
) {
  const api = {
    list: vi.fn(async () => ({ items, reviewRequired: true, reviewToken: 'token-v1' })),
    detail: vi.fn(async ({ materialId }: any) => detail(items.find(item => item.id === materialId)!))
  }
  const scope = effectScope()
  disposers.push(() => scope.stop())
  const state = scope.run(() => useFlowMaterials(api, () => ({ processInstanceId: 'instance', taskId: 'approval' })))!
  return { state, api, items }
}
describe('审批材料按需读取与审批依据', () => {
  it('默认仅加载第一步骤，切换和历史版本按需读取并缓存', async () => {
    const { state, api } = setup()
    await state.load()
    expect(state.activeId.value).toBe('a')
    expect(api.detail.mock.calls.map(([query]) => query.materialId)).toEqual(['a'])
    expect(api.detail).toHaveBeenCalledWith({ processInstanceId: 'instance', taskId: 'approval', materialId: 'a' })
    await state.selectStep('b')
    expect(state.activeItem.value?.nodeId).toBe('b')
    await state.select('history')
    expect(state.activeItem.value?.state).toBe('HISTORY')
    await state.selectStep('b')
    await state.selectStep('a')
    expect(state.activeId.value).toBe('history')
    expect(api.detail.mock.calls.map(([query]) => query.materialId)).toEqual(['a', 'b', 'history'])
    expect(await state.select('unknown')).toBe(false)
    expect(state.activeId.value).toBe('history')
  })
  it('切换步骤后迟到的读取只更新缓存，不抢回当前页签', async () => {
    const { state, api } = setup()
    await state.load()
    let finish!: (value: any) => void
    api.detail.mockImplementationOnce(() => new Promise(resolve => (finish = resolve)))
    const second = state.selectStep('b')
    await state.selectStep('c')
    finish(detail(item('b')))
    await second
    expect(state.activeId.value).toBe('c')
    expect(state.details.b).toBeTruthy()
  })
  it('仅有历史提交的步骤也能直接打开，刷新后不沿用旧选择', async () => {
    const { state } = setup([item('history', { nodeId: 'old', state: 'HISTORY', required: false })])
    await state.load()
    expect(state.activeId.value).toBe('history')
    expect(state.details.history).toBeTruthy()
    state.reset()
    expect(state.activeItem.value).toBeUndefined()
  })
  it('通过前读取尚未选中的必需材料，失败则阻止通过并支持重试', async () => {
    const { state, api } = setup()
    await state.load()
    await state.selectStep('b')
    api.detail.mockRejectedValueOnce(new Error('无权读取第三份'))
    expect(await state.prepareApproval()).toBe(false)
    expect(state.approvalBlocked.value).toContain('必需材料')
    expect(state.detailErrors.c).toBe('无权读取第三份')
    expect(await state.loadMaterial('c')).toBe(true)
    expect(await state.prepareApproval()).toBe(true)
  })
  it('可选材料失败不扩大为所有审批失败', async () => {
    const { state, api } = setup([item('optional', { required: false })])
    api.detail.mockRejectedValueOnce(new Error('可选材料故障'))
    await state.load()
    expect(state.detailErrors.optional).toBeTruthy()
    expect(state.approvalBlocked.value).toBe('')
  })
  it('目录故障清除旧资料及旧token，并保留明确错误', async () => {
    const { state, api } = setup()
    await state.load()
    api.list.mockRejectedValueOnce(new Error('目录读取失败'))
    await state.load()
    expect(state.items.value).toEqual([])
    expect(state.loaded.value).toBe(false)
    expect(state.error.value).toBe('目录读取失败')
    expect(state.reviewToken.value).toBeUndefined()
    expect(await state.prepareApproval()).toBe(false)
  })
  it('服务端阻断不能伪装成暂无材料', async () => {
    const { state, api } = setup([])
    api.list.mockResolvedValueOnce({
      items: [],
      reviewRequired: true,
      reviewToken: 'x',
      blockedReason: '尚不支持当前复杂路径'
    } as any)
    await state.load()
    expect(state.blockedReason.value).toContain('复杂路径')
    expect(await state.prepareApproval()).toBe(false)
  })
  it.each([{ revision: 2 }, { state: 'HISTORY' }])('材料版本或有效状态变化必须重新核对目录：%j', async changed => {
    const { state, api, items } = setup([item('a')])
    api.detail.mockResolvedValueOnce(detail({ ...items[0]!, ...changed } as FlowMaterialItem))
    await state.load()
    expect(state.details.a).toBeUndefined()
    expect(state.detailErrors.a).toContain('变化')
    expect(await state.prepareApproval()).toBe(false)
  })
  it('畸形流程表单不允许被辅助函数静默渲染为空材料', async () => {
    const { state, api, items } = setup([item('a')])
    api.detail.mockResolvedValueOnce({
      item: items[0]!,
      flowForm: { conf: '{}', fields: ['invalid-json'], values: {} }
    })
    await state.load()
    expect(state.detailErrors.a).toContain('结构不完整')
    expect(state.details.a).toBeUndefined()
  })
  it('刷新后过期目录响应不覆盖新材料', async () => {
    const { state, api } = setup()
    let resolve!: (value: any) => void
    api.list.mockImplementationOnce(() => new Promise(done => (resolve = done)))
    const first = state.load()
    await state.load()
    resolve({ items: [item('old')], reviewRequired: false })
    await first
    expect(state.items.value.some(item => item.id === 'old')).toBe(false)
    expect(state.reviewToken.value).toBe('token-v1')
  })
  it('相同材料并发读取共用请求；切换后旧内容无法回灌', async () => {
    const { state, api } = setup()
    await state.load()
    let resolve!: (value: any) => void
    api.detail.mockImplementationOnce(() => new Promise(done => (resolve = done)))
    const first = state.loadMaterial('c'),
      second = state.loadMaterial('c')
    state.reset()
    resolve(detail(item('c')))
    expect(await first).toBe(false)
    expect(await second).toBe(false)
    expect(state.details.c).toBeUndefined()
    expect(api.detail.mock.calls.filter(([q]) => q.materialId === 'c')).toHaveLength(1)
  })
  it('要求token时缺失凭据与渲染失败均阻止通过', async () => {
    const { state, api } = setup([item('a')])
    api.list.mockResolvedValueOnce({ items: [item('a')], reviewRequired: true } as any)
    await state.load()
    expect(state.approvalBlocked.value).toContain('凭据')
    await state.load()
    state.renderFailed('a', '展示失败')
    expect(await state.prepareApproval()).toBe(false)
  })
})

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick } from 'vue'
import Nodes from '@/views/bpm/processInstance/detail/WorkflowTaskNodes.vue'
import Source from '@/views/nocode/task-center/TaskWorkflowSource.vue'
import type { WorkflowTaskNodeView } from '@/types/nocode/workflow-task-node'

const api = vi.hoisted(() => ({ list: vi.fn(), source: vi.fn(), retry: vi.fn(), push: vi.fn() }))
vi.mock('@/api/nocode/workflow-task-node', () => ({ createWorkflowTaskNodeApi: () => api }))
vi.mock('@/utils/request', () => ({ default: {} }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: api.push }) }))
vi.mock('@/views/nocode/task-center/TaskDetail.vue', () => ({
  default: defineComponent({
    props: ['id'],
    emits: ['close', 'changed'],
    setup:
      (p, { emit }) =>
      () =>
        h('div', { 'data-detail': p.id }, [
          h('button', { onClick: () => emit('changed') }, '完成操作'),
          h('button', { onClick: () => emit('close') }, '关闭任务')
        ])
  })
}))
const disposers: Array<() => void> = []
const item = (extra: Partial<WorkflowTaskNodeView> = {}): WorkflowTaskNodeView => ({
  executionId: 'e',
  processInstanceId: 'p',
  nodeId: 'n',
  nodeName: '装修任务',
  taskId: 'task',
  state: 'WAITING',
  taskState: 'PENDING',
  canViewTask: true,
  canViewProcess: true,
  canRetry: false,
  createdAt: 1,
  ...extra
})
beforeEach(() => {
  api.list.mockResolvedValue([item()])
  api.source.mockResolvedValue(item())
  api.retry.mockResolvedValue(item())
})
afterEach(() => {
  disposers.splice(0).forEach(dispose => dispose())
  vi.clearAllMocks()
})
async function settle() {
  for (let i = 0; i < 4; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount(component: typeof Nodes | typeof Source, props: Record<string, unknown>) {
  const host = document.createElement('div')
  document.body.append(host)
  const app = createApp(() => h(component, props))
  const wrapper = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', [slots.default?.(), slots.extra?.()])
  })
  for (const name of ['a-card', 'a-tag', 'a-space']) app.component(name, wrapper)
  app.component('a-alert', defineComponent({ props: ['message'], setup: p => () => h('div', p.message) }))
  app.component(
    'a-button',
    defineComponent({
      props: ['disabled'],
      setup:
        (p, { slots }) =>
        () =>
          h('button', { disabled: p.disabled }, slots.default?.())
    })
  )
  app.mount(host)
  disposers.push(() => {
    app.unmount()
    host.remove()
  })
  await settle()
  return host
}
function button(host: HTMLElement, text: string) {
  return Array.from(host.querySelectorAll('button')).find(b => b.textContent?.trim() === text)
}
describe('流程和任务详情双向联动', () => {
  it('正常待办入口复用任务详情，任务内部操作后保持抽屉打开', async () => {
    const changed = vi.fn(),
      host = await mount(Nodes, { processInstanceId: 'p', onChanged: changed })
    expect(host.textContent).toContain('未开始')
    button(host, '查看任务')!.click()
    await settle()
    expect(host.querySelector('[data-detail="task"]')).not.toBeNull()
    button(host, '完成操作')!.click()
    await settle()
    expect(api.list).toHaveBeenCalledTimes(2)
    expect(changed).toHaveBeenCalledOnce()
    expect(host.querySelector('[data-detail="task"]')).not.toBeNull()
  })
  it('无任务访问权不显示详情入口，不能用流程可见性扩权', async () => {
    api.list.mockResolvedValue([item({ canViewTask: false, taskId: null, taskState: null })])
    const host = await mount(Nodes, { processInstanceId: 'p' })
    expect(button(host, '查看任务')).toBeUndefined()
    expect(host.textContent).toContain('无任务详情权限')
    expect(host.textContent).toContain('等待任务完成')
    expect(host.textContent).not.toContain('正在创建任务')
    expect(button(host, '重试同步')).toBeUndefined()
  })
  it('连续任务节点共用 execution 时保持两张独立卡片及各自详情入口', async () => {
    const first = item({ nodeId: 'first', nodeName: '现场勘察', taskId: 'task-first', state: 'COMPLETED' })
    const second = item({ nodeId: 'second', nodeName: '装修施工', taskId: 'task-second' })
    api.list.mockResolvedValueOnce([first, second]).mockResolvedValueOnce([second, first])
    const host = await mount(Nodes, { processInstanceId: 'p' })
    const cards = Array.from(host.querySelectorAll<HTMLElement>('.workflow-task-nodes__item'))
    expect(cards).toHaveLength(2)
    button(host, '刷新任务')!.click()
    await settle()
    const refreshed = Array.from(host.querySelectorAll<HTMLElement>('.workflow-task-nodes__item'))
    expect(refreshed).toHaveLength(2)
    // 排序刷新时各节点保留自己的 DOM 身份，不能因 execution 复用互换卡片状态。
    expect(refreshed[0]).toBe(cards[1])
    expect(refreshed[1]).toBe(cards[0])
    button(refreshed[0]!, '查看任务')!.click()
    await settle()
    expect(host.querySelector('[data-detail="task-second"]')).not.toBeNull()
    button(host, '关闭任务')!.click()
    await settle()
    button(refreshed[1]!, '查看任务')!.click()
    await settle()
    expect(host.querySelector('[data-detail="task-first"]')).not.toBeNull()
  })
  it('创建失败保留阻塞原因，有权限才能重试当前execution', async () => {
    api.list.mockResolvedValue([
      item({ state: 'CREATING', taskId: null, taskState: null, error: '人员字段必须为单人', canRetry: true })
    ])
    const host = await mount(Nodes, { processInstanceId: 'p' })
    expect(host.textContent).toContain('创建受阻')
    expect(host.textContent).toContain('人员字段必须为单人')
    button(host, '重试同步')!.click()
    await settle()
    expect(api.retry).toHaveBeenCalledWith('e')
    expect(button(host, '查看任务')).toBeUndefined()
  })
  it('已取消任务明确不自动继续流程，不能显示为节点完成', async () => {
    api.list.mockResolvedValue([item({ taskState: 'CANCELLED' })])
    const host = await mount(Nodes, { processInstanceId: 'p' })
    expect(host.textContent).toContain('流程不会自动继续')
    expect(host.textContent).not.toContain('节点已完成')
  })
  it('任务来源仅在独立流程权限允许时提供跳转', async () => {
    api.source.mockResolvedValueOnce(item({ canViewProcess: false }))
    const denied = await mount(Source, { taskId: 'task' })
    expect(denied.textContent).toContain('来源：流程任务')
    expect(button(denied, '查看来源流程')).toBeUndefined()
    const allowed = await mount(Source, { taskId: 'task' })
    button(allowed, '查看来源流程')!.click()
    await settle()
    expect(api.push).toHaveBeenCalledWith({ name: 'BpmInstanceDetail', query: { id: 'p' } })
  })
  it('普通任务没有来源时不增加空白面板', async () => {
    api.source.mockResolvedValue(null)
    const host = await mount(Source, { taskId: 'ordinary' })
    expect(host.textContent).toBe('')
  })
  it('来源失效或流程挂起时展示只读原因，并把状态交给任务详情锁定写入口', async () => {
    const loaded = vi.fn(),
      loading = vi.fn()
    const suspended = item({ readOnlyReason: '所属流程已挂起，请恢复流程后操作' })
    api.source.mockResolvedValueOnce(suspended)
    const host = await mount(Source, { taskId: 'task', onLoaded: loaded, onLoading: loading })
    expect(host.textContent).toContain('所属流程已挂起')
    expect(loaded).toHaveBeenCalledWith(suspended)
    expect(loading.mock.calls.map(args => args[0])).toEqual([true, false])
    api.source.mockResolvedValueOnce(item({ state: 'INVALIDATED' }))
    const invalidated = await mount(Source, { taskId: 'task' })
    expect(invalidated.textContent).toContain('所属流程已结束，任务仅供查看')
  })
  it('来源读取失败明确报告并保留重试，不误判为普通任务解除只读', async () => {
    const failed = vi.fn(),
      loaded = vi.fn()
    api.source.mockRejectedValueOnce(new Error('来源服务暂不可用'))
    const host = await mount(Source, { taskId: 'task', onLoaded: loaded, onFailed: failed })
    expect(failed).toHaveBeenCalledOnce()
    expect(loaded).not.toHaveBeenCalled()
    button(host, '重试')!.click()
    await settle()
    expect(loaded).toHaveBeenCalledWith(item())
  })
})

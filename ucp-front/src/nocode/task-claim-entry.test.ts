// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, type App } from 'vue'
import TaskClaimEntry from '@/views/nocode/task-center/TaskClaimEntry.vue'
import { canLocateTaskClaim, type TaskClaimLocation } from './task-claim-entry'
import type { TaskClaimableItem, TaskStructureNode } from '@/types/nocode/task-center'

const api = vi.hoisted(() => ({ claimableChildren: vi.fn(), claim: vi.fn(), claimGroup: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ taskCenter: api }) }))
vi.mock('@/components/ucp-modal-form/OsModalForm.vue', () => ({
  default: defineComponent({
    props: ['title'],
    setup:
      (props, { slots }) =>
      () =>
        h('section', [props.title, slots.formItems?.()])
  })
}))
vi.mock('@/views/nocode/task-center/TaskClaimDialog.vue', () => ({
  default: defineComponent({
    props: ['task'],
    emits: ['saved', 'close'],
    setup:
      (props, { emit }) =>
      () =>
        h('aside', { 'data-claim': props.task.id, 'data-revision': props.task.revision }, [
          props.task.title,
          h('button', { onClick: () => emit('saved') }, '模拟保存'),
          h('button', { onClick: () => emit('close') }, '关闭领取')
        ])
  })
}))
let app: App, host: HTMLDivElement
const location = ref<TaskClaimLocation>({ id: 'survey', rootId: 'root', title: '现场勘察' })
const item = (values: Partial<TaskClaimableItem> = {}): TaskClaimableItem => ({
  id: 'survey',
  rootId: 'root',
  parentId: 'root',
  title: '现场勘察',
  status: 'PENDING',
  assigneeId: null,
  assignmentMode: 'OPEN',
  revision: 8,
  urgency: 'NORMAL',
  priority: 'MEDIUM',
  expectedStart: null,
  expectedEnd: null,
  canClaim: true,
  ...values
})
const flush = async () => {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount(onSaved = vi.fn(), onClose = vi.fn()) {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp({ setup: () => () => h(TaskClaimEntry, { target: location.value, onSaved, onClose }) })
  app.component('AAlert', defineComponent({ props: ['message'], setup: props => () => h('p', props.message) }))
  for (const name of ['ASpace', 'ASpin'])
    app.component(
      name,
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h('div', slots.default?.())
      })
    )
  app.component(
    'AButton',
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('button', slots.default?.())
    })
  )
  app.mount(host)
  await flush()
}
function click(label: string) {
  const button = Array.from(host.querySelectorAll('button')).find(item => item.textContent === label)
  if (!button) throw new Error(`缺少按钮 ${label}`)
  button.click()
}
beforeEach(() => {
  vi.clearAllMocks()
  location.value = { id: 'survey', rootId: 'root', title: '现场勘察' }
  api.claimableChildren.mockResolvedValue([item({ id: 'same-name' }), item()])
})
afterEach(() => {
  app?.unmount()
  host?.remove()
})

describe('同组任务就地领取', () => {
  it('按真实组和节点 ID 定位，不按重复名称查找；打开不领取不开始', async () => {
    await mount()
    expect(api.claimableChildren).toHaveBeenCalledWith('root')
    expect(host.querySelector('aside')?.dataset).toMatchObject({ claim: 'survey', revision: '8' })
    expect(api.claim).not.toHaveBeenCalled()
    expect(api.claimGroup).not.toHaveBeenCalled()
  })
  it('服务端不允许领取时不生成确认弹窗', async () => {
    api.claimableChildren.mockResolvedValue([item({ canClaim: false })])
    await mount()
    expect(host.textContent).toContain('不在你的可领取范围')
    expect(host.querySelector('aside')).toBeNull()
  })
  it('并发被同事领取时提示最新负责人', async () => {
    api.claimableChildren.mockResolvedValue([item({ canClaim: false, assigneeId: 'other', assigneeName: '王师傅' })])
    await mount()
    expect(host.textContent).toContain('该任务已由王师傅负责')
    expect(host.querySelector('aside')).toBeNull()
  })
  it('不能从别的组或同名节点拼出领取权限', async () => {
    api.claimableChildren.mockResolvedValue([item({ rootId: 'other-root' }), item({ id: 'other-id' })])
    await mount()
    expect(host.querySelector('aside')).toBeNull()
  })
  it('失败可以原地重试，恢复后使用新版本', async () => {
    api.claimableChildren.mockRejectedValueOnce(new Error('网络暂不可用'))
    await mount()
    expect(host.textContent).toContain('网络暂不可用')
    click('重新查询')
    await flush()
    expect(host.querySelector('aside')?.dataset.revision).toBe('8')
  })
  it('切换任务后旧请求不覆盖新目标', async () => {
    let resolve!: (value: TaskClaimableItem[]) => void
    api.claimableChildren.mockImplementationOnce(
      () =>
        new Promise(done => {
          resolve = done
        })
    )
    await mount()
    location.value = { id: 'second', rootId: 'root', title: '另一个子任务' }
    api.claimableChildren.mockResolvedValue([item({ id: 'second' })])
    await flush()
    resolve([item()])
    await flush()
    expect(host.querySelector('aside')?.dataset.claim).toBe('second')
  })
  it('已领取后刷新父视图不销毁确认弹窗，保留计划失败重试的机会', async () => {
    const onSaved = vi.fn(() => {
      location.value = { ...location.value }
    })
    const onClose = vi.fn()
    await mount(onSaved, onClose)
    click('模拟保存')
    await flush()
    expect(onSaved).toHaveBeenCalledOnce()
    expect(host.querySelector('aside')?.dataset.claim).toBe('survey')
    expect(api.claimableChildren).toHaveBeenCalledTimes(1)
    click('关闭领取')
    expect(onClose).toHaveBeenCalledOnce()
  })
  it('总任务复用原有整组领取预览，不误用子任务列表', async () => {
    location.value = { id: 'root', rootId: 'root', title: '办公室装修' }
    await mount()
    expect(host.querySelector('aside')?.dataset.claim).toBe('root')
    expect(api.claimableChildren).not.toHaveBeenCalled()
  })
  it('概要入口只对待领取的未开始任务展示，不把同事任务和已办记录当成可领', () => {
    const node = {
      ...item(),
      assigneeName: '待领取',
      predecessorIds: [],
      detailVisible: false
    } as unknown as TaskStructureNode
    expect(canLocateTaskClaim(node)).toBe(true)
    expect(canLocateTaskClaim({ ...node, assigneeName: '王师傅', assigneeId: 'other' } as never)).toBe(false)
    expect(canLocateTaskClaim({ ...node, status: 'COMPLETED' })).toBe(false)
  })
})

// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, inject, nextTick, provide, type App } from 'vue'
import TaskPlanDialog from '@/views/nocode/task-center/TaskPlanDialog.vue'
import type { TaskChecklistContext, TaskChecklistItem, TaskPlan } from '@/types/nocode/task-center'

const api = vi.hoisted(() => ({ checklistContext: vi.fn(), checklist: vi.fn(), success: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ taskCenter: api }) }))
vi.mock('ant-design-vue', () => ({ message: { success: api.success } }))

const inheritedPlan = (extra: Partial<TaskPlan> = {}): TaskPlan => ({
  id: null,
  mode: 'CHECKLIST',
  period: 'WEEK',
  date: '2030-09-30',
  endDate: '2030-10-06',
  active: true,
  canCancel: false,
  userId: '9007199254740993',
  userName: '施工员工',
  source: 'SELF',
  inherited: true,
  inheritedFromTaskId: 'parent-task',
  inheritedFromTitle: '办公室装修',
  ...extra
})
const explicitPlan = (extra: Partial<TaskPlan> = {}): TaskPlan => ({
  ...inheritedPlan(),
  id: 'explicit-plan',
  inherited: false,
  inheritedFromTaskId: null,
  inheritedFromTitle: null,
  canCancel: true,
  ...extra
})
const item = (extra: Partial<TaskChecklistItem> = {}): TaskChecklistItem => ({
  taskId: 'child-task',
  title: '安装设备',
  status: 'PENDING',
  assigneeId: '9007199254740993',
  version: 8,
  todayPlans: [],
  weekPlans: [inheritedPlan()],
  nextWeekPlans: [],
  history: [],
  canAdd: true,
  warnings: [],
  ...extra
})
const context = (items: TaskChecklistItem[] = [item()]): TaskChecklistContext => ({
  today: '2030-10-04',
  weekStart: '2030-09-30',
  weekEnd: '2030-10-06',
  nextWeekStart: '2030-10-07',
  nextWeekEnd: '2030-10-13',
  items
})

let app: App | undefined
let host: HTMLDivElement
const flush = async () => {
  for (let index = 0; index < 15; index++) {
    await Promise.resolve()
    await nextTick()
  }
}
function button(label: string, root: ParentNode = host): HTMLButtonElement {
  const found = Array.from(root.querySelectorAll<HTMLButtonElement>('button'))
    .reverse()
    .find(node => node.textContent?.trim() === label)
  if (!found) throw new Error(`缺少按钮：${label}`)
  return found
}
function submitButtons() {
  // 周期选择与真正提交的文案相同，只计清单选择器之外的操作按钮。
  return Array.from(host.querySelectorAll<HTMLButtonElement>('button')).filter(
    node => !node.closest('[data-plan-period]') && /^(加入|移出|重试)/.test(node.textContent?.trim() || '')
  )
}
async function mount(extra: Record<string, unknown> = {}) {
  app = createApp(() => h(TaskPlanDialog, { ids: ['child-task'], ...extra }))
  const surface = defineComponent({
    props: ['open'],
    emits: ['ok'],
    setup:
      (props, { slots, emit }) =>
      () =>
        props.open
          ? h(
              'section',
              { 'data-plan-modal': true, onKeydown: (event: KeyboardEvent) => event.key === 'Enter' && emit('ok') },
              [slots.title?.(), slots.default?.(), slots.footer?.()]
            )
          : null
  })
  app.component('AModal', surface)
  app.component('ADrawer', surface)
  const plain = defineComponent({
    props: ['label', 'message'],
    setup: (props, { slots, expose }) => {
      expose({ validate: async () => true, resetFields: vi.fn() })
      return () => h('div', [props.label, props.message, slots.default?.()])
    }
  })
  for (const name of ['AForm', 'AFormItem', 'ASpin', 'AAlert', 'ATooltip', 'ADropdown', 'AMenu', 'AMenuItem'])
    app.component(name, plain)
  app.component(
    'AButton',
    defineComponent({
      props: ['disabled', 'loading'],
      setup:
        (props, { slots, attrs }) =>
        () =>
          h('button', { ...attrs, disabled: props.disabled || props.loading }, slots.default?.())
    })
  )
  app.component(
    'ARadioGroup',
    defineComponent({
      props: ['value', 'disabled'],
      emits: ['update:value'],
      setup: (props, { slots, emit }) => {
        provide('plan-inheritance-period', {
          disabled: () => props.disabled,
          change: (value: string) => emit('update:value', value)
        })
        return () => h('div', { 'data-plan-period': props.value }, slots.default?.())
      }
    })
  )
  app.component(
    'ARadioButton',
    defineComponent({
      props: { value: { type: String, required: true } },
      setup: (props, { slots }) => {
        const group = inject<{ disabled: () => boolean; change: (value: string) => void }>('plan-inheritance-period')
        return () =>
          h('button', { disabled: group?.disabled(), onClick: () => group?.change(props.value) }, slots.default?.())
      }
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
}
async function submitByKeyboard() {
  host.querySelector('[data-plan-modal]')?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }))
  await flush()
}
beforeEach(() => {
  vi.resetAllMocks()
  api.checklistContext.mockResolvedValue(context())
  api.checklist.mockResolvedValue({ changed: ['child-task'], unchanged: [] })
})
afterEach(() => {
  app?.unmount()
  host?.remove()
})

describe('计划继承的可见来源与操作边界', () => {
  it('展示后台授权可见的上级名称，已继承本周时不再提供加入或移出按钮', async () => {
    await mount()
    expect(api.checklistContext).toHaveBeenCalledWith({ ids: ['child-task'], target: 'SELF' })
    expect(host.textContent).toContain('本周计划 · 随上级「办公室装修」')
    expect(host.textContent).toContain('随上级纳入，请从上级调整')
    expect(host.textContent).not.toContain('parent-task')
    expect(host.querySelector('[data-plan-period]')?.getAttribute('data-plan-period')).toBe('WEEK')
    expect(submitButtons()).toHaveLength(0)
    await submitByKeyboard()
    expect(api.checklist).not.toHaveBeenCalled()
  })
  it('后台隐藏上级来源时只显示通用继承提示，不显示来源标识或猜测名称', async () => {
    api.checklistContext.mockResolvedValue(
      context([item({ weekPlans: [inheritedPlan({ inheritedFromTitle: null })] })])
    )
    await mount()
    expect(host.textContent).toContain('本周计划 · 随上级计划')
    expect(host.textContent).not.toContain('办公室装修')
    expect(host.textContent).not.toContain('parent-task')
    expect(host.querySelector('.task-checklist__line a')).toBeNull()
    expect(submitButtons()).toHaveLength(0)
  })
  it.each([
    { id: null, canCancel: false },
    { id: 'unexpected-parent-plan', canCancel: true },
    { id: 'old-parent-plan', canCancel: true, inherited: undefined }
  ])('继承项 %j 即使误带可移出标识也不提交来源计划身份', async extra => {
    api.checklistContext.mockResolvedValue(context([item({ weekPlans: [inheritedPlan(extra)] })]))
    await mount({ initialAction: 'REMOVE' })
    expect(host.textContent).toContain('随上级纳入，请从上级调整')
    expect(submitButtons()).toHaveLength(0)
    await submitByKeyboard()
    expect(api.checklist).not.toHaveBeenCalled()
  })
  it('管理入口可查看继承与显式计划，但切换周期和表单确认事件均不能修改他人清单', async () => {
    api.checklistContext.mockResolvedValue(
      context([item({ todayPlans: [explicitPlan({ period: 'DAY', date: '2030-10-04' })] })])
    )
    const close = vi.fn()
    await mount({ target: 'ASSIGNEE', onClose: close })
    expect(api.checklistContext).toHaveBeenCalledWith({ ids: ['child-task'], target: 'ASSIGNEE' })
    expect(host.textContent).toContain('查看负责人计划')
    expect(host.textContent).toContain('施工员工 · 本周计划 · 随上级「办公室装修」')
    expect(submitButtons()).toHaveLength(0)
    await submitByKeyboard()
    button('今日计划').click()
    await flush()
    expect(host.textContent).toContain('今日计划 · 自行加入')
    expect(submitButtons()).toHaveLength(0)
    await submitByKeyboard()
    expect(api.checklist).not.toHaveBeenCalled()
    button('关闭').click()
    expect(close).toHaveBeenCalledOnce()
  })
  it('历史主管安排尊重服务端只读状态，不因不是继承项而提供移出', async () => {
    api.checklistContext.mockResolvedValue(
      context([item({ weekPlans: [explicitPlan({ source: 'MANAGER', canCancel: false })] })])
    )
    await mount()
    expect(host.textContent).toContain('本周计划 · 历史主管安排')
    expect(host.textContent).toContain('仅供查看，当前不能移出')
    expect(submitButtons()).toHaveLength(0)
    await submitByKeyboard()
    expect(api.checklist).not.toHaveBeenCalled()
  })
  it('已继承本周仍可单独加入今日，日期和版本均采用服务端上下文', async () => {
    const saved = vi.fn()
    const close = vi.fn()
    await mount({ onSaved: saved, onClose: close })
    expect(submitButtons()).toHaveLength(0)
    button('加入今日计划').click()
    await flush()
    expect(host.textContent).toContain('尚未加入今日计划')
    expect(submitButtons()).toHaveLength(1)
    button('加入今日计划').click()
    await flush()
    expect(api.checklist).toHaveBeenCalledWith({
      ids: ['child-task'],
      target: 'SELF',
      action: 'ADD',
      period: 'DAY',
      date: '2030-10-04',
      expectedVersions: { 'child-task': 8 },
      requestKey: expect.any(String)
    })
    expect(saved).toHaveBeenCalledOnce()
    expect(close).toHaveBeenCalledOnce()
  })
  it('今日同样继承时不重复加入，切回本周也不产生写操作', async () => {
    api.checklistContext.mockResolvedValue(
      context([item({ todayPlans: [inheritedPlan({ period: 'DAY', date: '2030-10-04', endDate: '2030-10-04' })] })])
    )
    await mount()
    button('加入今日计划').click()
    await flush()
    expect(host.textContent).toContain('今日计划 · 随上级「办公室装修」')
    expect(submitButtons()).toHaveLength(0)
    await submitByKeyboard()
    button('加入本周计划').click()
    await flush()
    expect(submitButtons()).toHaveLength(0)
    expect(api.checklist).not.toHaveBeenCalled()
  })
  it('本人显式计划仍可移出，只发送本人的计划 ID 并重新读取继承结果', async () => {
    api.checklistContext
      .mockResolvedValueOnce(context([item({ weekPlans: [explicitPlan()] })]))
      .mockResolvedValueOnce(context())
    const saved = vi.fn()
    const close = vi.fn()
    await mount({ onSaved: saved, onClose: close })
    expect(host.textContent).toContain('本周计划 · 自行加入')
    button('移出此清单').click()
    await flush()
    expect(api.checklist).toHaveBeenCalledWith({
      ids: ['child-task'],
      target: 'SELF',
      action: 'REMOVE',
      period: 'WEEK',
      date: '2030-09-30',
      planIds: ['explicit-plan'],
      expectedVersions: { 'child-task': 8 },
      requestKey: expect.any(String)
    })
    expect(api.checklistContext).toHaveBeenCalledTimes(2)
    expect(host.textContent).toContain('随上级「办公室装修」')
    expect(submitButtons()).toHaveLength(0)
    expect(saved).toHaveBeenCalledOnce()
    expect(close).not.toHaveBeenCalled()
  })
  it('批量移出仅提交显式计划，继承节点不连带移出或提交来源 ID', async () => {
    api.checklistContext.mockResolvedValue(
      context([item(), item({ taskId: 'explicit-task', version: 9, weekPlans: [explicitPlan()] })])
    )
    await mount({ ids: ['child-task', 'explicit-task'], initialAction: 'REMOVE' })
    expect(host.textContent).toContain('1 项当前不能移出，会保留')
    button('移出所选本周计划').click()
    await flush()
    expect(api.checklist).toHaveBeenCalledWith(
      expect.objectContaining({
        ids: ['explicit-task'],
        planIds: ['explicit-plan'],
        expectedVersions: { 'explicit-task': 9 },
        action: 'REMOVE'
      })
    )
  })
  it('上下文读取失败不能加入或移出，刷新成功后才允许按真实状态操作', async () => {
    api.checklistContext
      .mockRejectedValueOnce(new Error('计划读取失败'))
      .mockResolvedValueOnce(context([item({ weekPlans: [] })]))
    await mount()
    expect(host.textContent).toContain('计划读取失败')
    expect(submitButtons()).toHaveLength(0)
    await submitByKeyboard()
    expect(api.checklist).not.toHaveBeenCalled()
    button('刷新清单').click()
    await flush()
    expect(host.textContent).not.toContain('计划读取失败')
    expect(host.textContent).toContain('尚未加入本周计划')
    button('加入本周计划').click()
    await flush()
    expect(api.checklist).toHaveBeenCalledOnce()
    expect(api.checklist).toHaveBeenCalledWith(expect.objectContaining({ ids: ['child-task'], action: 'ADD' }))
  })
  it('加载中与响应缺少所选任务时均不开放提交，不拿部分上下文改动清单', async () => {
    let resolve: ((value: TaskChecklistContext) => void) | undefined
    api.checklistContext.mockImplementationOnce(() => new Promise<TaskChecklistContext>(done => (resolve = done)))
    await mount({ ids: ['child-task', 'unloaded-task'] })
    expect(submitButtons()).toHaveLength(0)
    await submitByKeyboard()
    expect(api.checklist).not.toHaveBeenCalled()
    if (!resolve) throw new Error('尚未请求计划上下文')
    resolve(context([item({ weekPlans: [] })]))
    await flush()
    expect(submitButtons()).toHaveLength(0)
    await submitByKeyboard()
    expect(api.checklist).not.toHaveBeenCalled()
  })
})

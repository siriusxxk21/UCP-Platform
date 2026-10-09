// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, inject, nextTick, provide, reactive, type App } from 'vue'
import TaskPlanDialog from '@/views/nocode/task-center/TaskPlanDialog.vue'
import type { TaskPlan, TaskChecklistContext, TaskChecklistItem } from '@/types/nocode/task-center'

const api = vi.hoisted(() => ({
  checklistContext: vi.fn(),
  checklist: vi.fn(),
  plan: vi.fn(),
  comment: vi.fn(),
  confirm: vi.fn(),
  success: vi.fn(),
  warning: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ taskCenter: api }) }))
vi.mock('ant-design-vue', () => ({
  message: { success: api.success, warning: api.warning },
  Modal: { confirm: api.confirm }
}))

const contextItem = (extra: Partial<TaskChecklistItem> = {}): TaskChecklistItem => ({
  taskId: 'task-a',
  title: '三号楼施工',
  status: 'PENDING',
  assigneeId: 'employee',
  version: 7,
  todayPlans: [],
  weekPlans: [],
  history: [],
  canAdd: true,
  reason: null,
  warnings: [],
  ...extra
})
const plan = (extra: Partial<TaskPlan> = {}): TaskPlan => ({
  id: 'plan-a',
  mode: 'CHECKLIST',
  period: 'WEEK',
  date: '2030-10-04',
  endDate: '2030-10-04',
  active: true,
  userId: 'employee',
  source: 'SELF',
  arrangedById: 'employee',
  arrangedByName: '施工员工',
  canCancel: true,
  ...extra
})
let app: App | undefined
let host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 15; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function button(label: string): HTMLButtonElement {
  const found = Array.from(host.querySelectorAll('button'))
    .reverse()
    .find(item => item.textContent?.trim() === label)
  if (!found) throw new Error(`缺少按钮：${label}`)
  return found
}
function buttons() {
  return Array.from(host.querySelectorAll('button')).map(item => item.textContent?.trim())
}
async function mount(extra: Record<string, unknown> = {}) {
  const props = reactive({ ids: ['task-a'], taskNames: ['三号楼施工'], ...extra })
  app = createApp(() => h(TaskPlanDialog, props))
  const surface = defineComponent({
    props: ['open'],
    setup:
      (p, { slots }) =>
      () =>
        p.open ? h('section', [slots.title?.(), slots.default?.(), slots.footer?.()]) : null
  })
  app.component('AModal', surface)
  app.component('ADrawer', surface)
  const plain = defineComponent({
    props: ['label', 'message', 'description'],
    setup: (p, { slots, expose }) => {
      expose({ validate: async () => true, resetFields: vi.fn() })
      return () =>
        h('div', [p.label, p.message, p.description, slots.default?.(), slots.description?.(), slots.action?.()])
    }
  })
  for (const name of [
    'AForm',
    'AFormItem',
    'ASpin',
    'AAlert',
    'ATag',
    'ASpace',
    'ACollapse',
    'ACollapsePanel',
    'AEmpty',
    'ATooltip',
    'ADropdown',
    'AMenu',
    'AMenuItem'
  ])
    app.component(name, plain)
  app.component(
    'AButton',
    defineComponent({
      props: ['disabled', 'loading'],
      setup:
        (p, { slots, attrs }) =>
        () =>
          h('button', { ...attrs, disabled: p.disabled || p.loading }, slots.default?.())
    })
  )
  app.component(
    'ARadioGroup',
    defineComponent({
      props: ['value', 'disabled'],
      emits: ['update:value', 'change'],
      setup: (p, { emit, slots }) => {
        provide('plan-choice', {
          select: (value: unknown) => {
            emit('update:value', value)
            emit('change', { target: { value } })
          },
          disabled: () => p.disabled
        })
        return () => h('div', slots.default?.())
      }
    })
  )
  const choice = defineComponent({
    props: ['value', 'disabled'],
    setup: (p, { slots }) => {
      const group = inject<{ select: (value: unknown) => void; disabled: () => boolean }>('plan-choice')
      return () =>
        h(
          'button',
          { disabled: p.disabled || group?.disabled(), onClick: () => group?.select(p.value) },
          slots.default?.()
        )
    }
  })
  app.component('ARadio', choice)
  app.component('ARadioButton', choice)
  app.component(
    'ADatePicker',
    defineComponent({
      props: ['value', 'disabled'],
      emits: ['update:value', 'change'],
      setup:
        (p, { attrs, emit }) =>
        () =>
          h('input', {
            ...attrs,
            value: p.value,
            disabled: p.disabled,
            onChange: (event: Event) => {
              const value = (event.target as HTMLInputElement).value
              emit('update:value', value)
              emit('change', value)
            }
          })
    })
  )
  app.component(
    'ATextarea',
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup:
        (p, { attrs, emit }) =>
        () =>
          h('textarea', {
            ...attrs,
            value: p.value,
            onInput: (event: Event) => emit('update:value', (event.target as HTMLTextAreaElement).value)
          })
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  return props
}
const context = (items: TaskChecklistItem[] = [contextItem()]): TaskChecklistContext => ({
  today: '2030-10-04',
  weekStart: '2030-09-30',
  weekEnd: '2030-10-06',
  nextWeekStart: '2030-10-07',
  nextWeekEnd: '2030-10-13',
  items
})
beforeEach(() => {
  vi.clearAllMocks()
  api.checklistContext.mockResolvedValue(context())
  api.checklist.mockResolvedValue({ changed: ['task-a'], unchanged: [] })
  api.comment.mockResolvedValue({})
})
afterEach(async () => {
  app?.unmount()
  await flush()
  host?.remove()
})

describe('今日与本周计划清单', () => {
  it('下周加入展示准确范围并使用服务端日期，保存后通知列表刷新', async () => {
    const saved = vi.fn(),
      close = vi.fn()
    await mount({ initialPeriod: 'NEXT_WEEK', onSaved: saved, onClose: close })
    expect(host.textContent).toContain('2030-10-07 至 2030-10-13')
    button('加入下周计划').click()
    await flush()
    expect(api.checklist).toHaveBeenCalledWith(
      expect.objectContaining({ period: 'WEEK', date: '2030-10-07', ids: ['task-a'] })
    )
    expect(saved).toHaveBeenCalledOnce()
    expect(close).toHaveBeenCalledOnce()
  })
  it('从下周移出只操作下周清单，本周成员仍保留', async () => {
    api.checklistContext.mockResolvedValue(
      context([
        contextItem({
          weekPlans: [plan({ id: 'this-week' })],
          nextWeekPlans: [plan({ id: 'next-week', date: '2030-10-07', endDate: '2030-10-13' })]
        })
      ])
    )
    await mount({ initialPeriod: 'NEXT_WEEK', initialAction: 'REMOVE' })
    button('移出所选下周计划').click()
    await flush()
    expect(api.checklist).toHaveBeenCalledWith(
      expect.objectContaining({ action: 'REMOVE', period: 'WEEK', date: '2030-10-07', planIds: ['next-week'] })
    )
  })
  it('批量加入明确提示不可操作项，仅提交仍可加入的任务', async () => {
    api.checklistContext.mockResolvedValue(
      context([
        contextItem(),
        contextItem({ taskId: 'ended', title: '已结束项', status: 'COMPLETED', canAdd: false, reason: '任务已结束' })
      ])
    )
    await mount({ ids: ['task-a', 'ended'] })
    expect(host.textContent).toContain('1 项当前不能加入；仅处理可加入的 1 项')
    button('加入本周计划').click()
    await flush()
    expect(api.checklist).toHaveBeenCalledWith(
      expect.objectContaining({ ids: ['task-a'], expectedVersions: { 'task-a': 7 } })
    )
  })
  it('默认本周，使用服务端锚点版本，不再提供日期区间或月新增', async () => {
    await mount()
    expect(api.checklistContext).toHaveBeenCalledWith({ ids: ['task-a'], target: 'SELF' })
    expect(host.textContent).toContain('2030-09-30 至 2030-10-06')
    expect(host.querySelector('input')).toBeNull()
    expect(host.textContent).not.toContain('月计划')
    button('加入本周计划').click()
    await flush()
    expect(api.checklist).toHaveBeenCalledWith(
      expect.objectContaining({
        ids: ['task-a'],
        target: 'SELF',
        action: 'ADD',
        period: 'WEEK',
        date: '2030-09-30',
        expectedVersions: { 'task-a': 7 },
        requestKey: expect.any(String)
      })
    )
    expect(api.checklist.mock.calls[0]?.[0]).not.toHaveProperty('endDate')
    expect(api.plan).not.toHaveBeenCalled()
  })
  it('加入今日只发所选真实任务，后台原子补本周，不携带父子其他任务', async () => {
    await mount({ initialPeriod: 'DAY' })
    button('加入今日计划').click()
    await flush()
    expect(api.checklist).toHaveBeenCalledWith(
      expect.objectContaining({ ids: ['task-a'], period: 'DAY', date: '2030-10-04' })
    )
    expect(api.checklist).toHaveBeenCalledOnce()
  })
  it('读取失败保持不可提交，可刷新恢复', async () => {
    api.checklistContext.mockRejectedValueOnce(new Error('上下文不可用'))
    await mount()
    expect(host.textContent).toContain('上下文不可用')
    expect(buttons().filter(x => x === '加入本周计划')).toHaveLength(1)
    button('刷新清单').click()
    await flush()
    button('加入本周计划').click()
    await flush()
    expect(api.checklist).toHaveBeenCalledOnce()
  })
  it('终态只回看，不能加入且不误报主管保护', async () => {
    api.checklistContext.mockResolvedValue(
      context([
        contextItem({
          status: 'COMPLETED',
          canAdd: false,
          reason: '任务已完成',
          weekPlans: [plan({ canCancel: false })]
        })
      ])
    )
    await mount()
    expect(host.textContent).toContain('任务已完成')
    expect(host.textContent).not.toContain('主管加入，不能自行移出')
    expect(buttons()).not.toContain('移出此清单')
    expect(buttons().filter(x => x === '加入本周计划')).toHaveLength(1)
  })
  it('本人可以移出历史主管加入的清单，保留来源展示', async () => {
    api.checklistContext.mockResolvedValue(
      context([contextItem({ weekPlans: [plan({ source: 'MANAGER', canCancel: true })] })])
    )
    await mount()
    expect(host.textContent).toContain('历史主管安排')
    expect(host.textContent).not.toContain('不能自行移出')
    button('移出此清单').click()
    await flush()
    expect(api.checklist).toHaveBeenCalledWith(
      expect.objectContaining({ action: 'REMOVE', target: 'SELF', planIds: ['plan-a'] })
    )
  })
  it('老板查看他人计划只能切换清单查看，没有加入或移出操作', async () => {
    api.checklistContext.mockResolvedValue(
      context([
        contextItem({
          todayPlans: [plan({ id: 'day', period: 'DAY' })],
          weekPlans: [plan({ userName: '李志航', arrangedByName: '历史管理员' })]
        })
      ])
    )
    await mount({ target: 'ASSIGNEE' })
    expect(host.textContent).toContain('查看负责人计划')
    expect(host.textContent).toContain('李志航')
    expect(host.textContent).not.toContain('历史管理员')
    expect(buttons()).not.toContain('加入本周计划')
    expect(buttons()).not.toContain('加入今日计划')
    expect(buttons()).not.toContain('移出此清单')
    button('今日计划').click()
    await flush()
    expect(host.textContent).toContain('今日计划')
    expect(buttons()).not.toContain('移出此清单')
    expect(api.checklist).not.toHaveBeenCalled()
  })
  it('移出今日只提交该日真实planId，不附带周项', async () => {
    api.checklistContext.mockResolvedValue(
      context([contextItem({ todayPlans: [plan({ id: 'today', period: 'DAY' })], weekPlans: [plan({ id: 'week' })] })])
    )
    await mount({ initialPeriod: 'DAY' })
    button('移出此清单').click()
    await flush()
    expect(api.checklist).toHaveBeenCalledWith(
      expect.objectContaining({ action: 'REMOVE', period: 'DAY', planIds: ['today'], date: '2030-10-04' })
    )
  })
  it('批量移出仅提交可移项，保留受保护项和无可移任务', async () => {
    api.checklistContext.mockResolvedValue(
      context([
        contextItem({ weekPlans: [plan({ id: 'own' }), plan({ id: 'manager', source: 'MANAGER', canCancel: false })] }),
        contextItem({ taskId: 'b', weekPlans: [plan({ id: 'b-protected', source: 'MANAGER', canCancel: false })] })
      ])
    )
    await mount({ ids: ['task-a', 'b'], initialAction: 'REMOVE' })
    expect(host.textContent).toContain('2 项当前不能移出')
    expect(buttons()).toContain('本周计划')
    expect(buttons()).toContain('今日计划')
    expect(host.textContent).not.toContain('加入本周计划')
    expect(host.textContent).not.toContain('加入今日会同时加入本周')
    expect(host.textContent).not.toContain('仅移出所选计划项，不删除任务')
    button('移出所选本周计划').click()
    await flush()
    expect(api.checklist).toHaveBeenCalledWith(
      expect.objectContaining({
        ids: ['task-a'],
        action: 'REMOVE',
        planIds: ['own'],
        expectedVersions: { 'task-a': 7 }
      })
    )
  })
  it('未知回执重试原请求且不换日期、版本或请求键', async () => {
    api.checklist.mockRejectedValueOnce(new Error('连接中断'))
    await mount()
    button('加入本周计划').click()
    await flush()
    expect(host.textContent).toContain('结果尚未确认')
    expect(button('加入今日计划').disabled).toBe(true)
    api.checklistContext.mockResolvedValue({ ...context(), today: '2030-10-05' })
    button('重试原请求').click()
    await flush()
    expect(api.checklist.mock.calls[1]?.[0]).toEqual(api.checklist.mock.calls[0]?.[0])
    expect(api.checklistContext).toHaveBeenCalledOnce()
  })
  it('明确拒绝后可刷新读取最新锚点与版本，再发新请求', async () => {
    api.checklist.mockRejectedValueOnce(Object.assign(new Error('日期已变化'), { businessCode: 400 }))
    await mount()
    button('加入本周计划').click()
    await flush()
    api.checklistContext.mockResolvedValue({ ...context([contextItem({ version: 8 })]), weekStart: '2030-10-07' })
    button('刷新清单').click()
    await flush()
    button('加入本周计划').click()
    await flush()
    expect(api.checklist.mock.calls[1]?.[0]).toMatchObject({ date: '2030-10-07', expectedVersions: { 'task-a': 8 } })
    expect(api.checklist.mock.calls[1]?.[0].requestKey).not.toBe(api.checklist.mock.calls[0]?.[0].requestKey)
  })
  it('旧月区间和过往清单保留可见，不提供编辑旧记录', async () => {
    api.checklistContext.mockResolvedValue(
      context([
        contextItem({
          history: [
            plan({ id: 'old', mode: 'SCHEDULE', period: 'MONTH', date: '2030-09-01', endDate: '2030-09-30' }),
            plan({ id: 'past', active: false })
          ]
        })
      ])
    )
    await mount()
    button('查看历史计划（2）').click()
    await flush()
    expect(host.textContent).toContain('旧版区间安排 · 月 · 2030-09-01 至 2030-09-30')
    expect(host.textContent).toContain('过往清单')
    expect(buttons()).not.toContain('移出此清单')
  })
  it('当前节点变化忽略晚到的旧上下文', async () => {
    let resolve!: (c: TaskChecklistContext) => void
    api.checklistContext.mockImplementationOnce(
      () =>
        new Promise<TaskChecklistContext>(r => {
          resolve = r
        })
    )
    const props = await mount()
    api.checklistContext.mockResolvedValue(context([contextItem({ taskId: 'b', title: '新任务', version: 9 })]))
    props.ids = ['b']
    await flush()
    resolve(context())
    await flush()
    expect(host.textContent).toContain('新任务')
    button('加入本周计划').click()
    await flush()
    expect(api.checklist).toHaveBeenCalledWith(expect.objectContaining({ ids: ['b'], expectedVersions: { b: 9 } }))
  })
  it('管理入口明确为查看负责人计划', async () => {
    await mount({ target: 'ASSIGNEE' })
    expect(host.textContent).toContain('查看负责人计划')
    expect(host.textContent).not.toContain('我的工作清单')
  })
})

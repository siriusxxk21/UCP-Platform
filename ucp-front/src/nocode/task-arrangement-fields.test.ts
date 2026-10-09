// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  createApp,
  defineComponent,
  h,
  inject,
  provide,
  nextTick,
  reactive,
  ref,
  type App,
  type Ref,
  type Component
} from 'vue'
import TaskAssignmentFields from '@/views/nocode/task-center/TaskAssignmentFields.vue'
import TaskAcceptanceFields from '@/views/nocode/task-center/TaskAcceptanceFields.vue'
import TaskScheduleFields from '@/views/nocode/task-center/TaskScheduleFields.vue'
import TaskSplitDialog from '@/views/nocode/task-center/TaskSplitDialog.vue'
import TaskNodeFields from '@/views/nocode/task-center/TaskNodeFields.vue'
import { newAutoTaskNode, newTaskNode } from './task-center'
import type { TaskNodeInput, TaskSchedule } from '@/types/nocode/task-center'

const selection = vi.hoisted(() => ({ props: {} as Record<string, unknown> }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ taskCenter: { formPreview: vi.fn() } }) }))
vi.mock('@/components/ucp-modal-form/OsModalForm.vue', () => ({
  default: defineComponent({
    inheritAttrs: false,
    setup:
      (_, { slots }) =>
      () =>
        h('section', [slots.formItems?.(), slots.footer?.()])
  })
}))
vi.mock('@/views/nocode/task-center/TaskBindingPicker.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskEntriesEditor.vue', () => ({
  default: { render: () => h('section', { 'data-business-editor': '' }, '业务办理项') }
}))
vi.mock('@/components/UserSelector/index.vue', () => ({
  default: defineComponent({
    props: ['visible', 'multiple', 'candidateUserIds', 'enabledOnly', 'showMultipleToggle', 'selectedUsers'],
    emits: ['confirm'],
    setup: (props, { emit }) => {
      selection.props = props
      return () =>
        h('div', [
          h(
            'button',
            { onClick: () => emit('confirm', [{ id: '9007199254740993', nickname: '张工', username: 'zhang' }]) },
            '确认人员'
          ),
          h('button', { onClick: () => emit('confirm', []) }, '清空人员')
        ])
    }
  })
}))
let app: App, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const button = (label: string) => {
  const found = Array.from(host.querySelectorAll('button')).find(
    item => item.textContent?.trim() === label || item.getAttribute('aria-label') === label
  )
  if (!found) throw new Error(`缺少按钮：${label}`)
  return found
}
async function selectValue(label: string, value: string) {
  const select = host.querySelector<HTMLSelectElement>(`select[aria-label="${label}"]`)
  if (!select) throw new Error(`缺少下拉框：${label}`)
  select.value = value
  select.dispatchEvent(new Event('change'))
  await flush()
  return select
}
async function selectSpecialTime(value: string) {
  if (!host.querySelector('[aria-label="从什么时候开始"]')) {
    if (host.querySelector('[aria-label="时间规则"]')) await selectValue('时间规则', 'CUSTOM')
    else button('CUSTOM').click()
  }
  await flush()
  return selectValue('从什么时候开始', value)
}
async function mount(component: Component, model: Ref<unknown>, props: Record<string, unknown> = {}) {
  app = createApp(() =>
    h(component, {
      modelValue: model.value,
      'onUpdate:modelValue': (value: unknown) => {
        model.value = value
      },
      ...props
    })
  )
  app.component(
    'ASelect',
    defineComponent({
      props: ['value', 'options', 'disabled'],
      emits: ['update:value', 'change', 'select'],
      setup:
        (p, { emit, attrs }) =>
        () =>
          h(
            'select',
            {
              ...attrs,
              value: p.value,
              disabled: p.disabled,
              onChange: (event: Event) => {
                const value = (event.target as HTMLSelectElement).value
                emit('update:value', value)
                emit('change', value)
                emit('select', value)
              }
            },
            p.options
              .flatMap(
                (option: {
                  value?: string
                  label: string
                  disabled?: boolean
                  options?: Array<{ value: string; label: string; disabled?: boolean }>
                }) => option.options || [option]
              )
              .map((option: { value: string; label: string; disabled?: boolean }) =>
                h('option', { value: option.value, disabled: option.disabled }, option.label)
              )
          )
    })
  )
  for (const name of ['AFormItem', 'ASpace', 'ACollapse', 'ACollapsePanel', 'AAlert', 'ATooltip'])
    app.component(
      name,
      defineComponent({
        props: ['label', 'message'],
        setup:
          (p, { slots }) =>
          () =>
            h('div', [p.label, p.message, slots.default?.()])
      })
    )
  app.component(
    'AButton',
    defineComponent({
      props: ['disabled'],
      setup:
        (p, { slots }) =>
        () =>
          h('button', { disabled: p.disabled }, slots.default?.())
    })
  )
  app.component(
    'ARadioGroup',
    defineComponent({
      props: ['value', 'disabled'],
      emits: ['change'],
      setup: (p, { emit, slots }) => {
        provide('scheduleRadio', {
          disabled: () => p.disabled,
          change: (value: string) => emit('change', { target: { value } })
        })
        return () => h('div', { 'data-radio-value': p.value }, slots.default?.())
      }
    })
  )
  app.component(
    'ARadio',
    defineComponent({
      props: { value: { type: String, required: true }, disabled: Boolean },
      setup: (p, { slots }) => {
        const group = inject<{ disabled: () => boolean; change: (value: string) => void }>('scheduleRadio')
        return () =>
          h(
            'button',
            { 'aria-label': p.value, disabled: p.disabled || group?.disabled(), onClick: () => group?.change(p.value) },
            slots.default?.() || p.value
          )
      }
    })
  )
  for (const name of ['ADatePicker', 'AInputNumber', 'AInput', 'ATextarea'])
    app.component(
      name,
      defineComponent({
        props: ['value', 'disabled'],
        emits: ['update:value'],
        setup: (p, { emit, attrs, expose }) => {
          const field = ref<HTMLInputElement>()
          expose({ focus: () => field.value?.focus() })
          return () =>
            h('input', {
              ...attrs,
              ref: field,
              value: p.value,
              disabled: p.disabled,
              onInput: (event: Event) => emit('update:value', (event.target as HTMLInputElement).value)
            })
        }
      })
    )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
}
afterEach(() => {
  app?.unmount()
  host?.remove()
})
describe('人员与时间安排共用控件', () => {
  it.each([false, true])('先选三种安排意图，单独安排的开始依据直接可见，compact=%s', async compact => {
    const schedule = ref<TaskSchedule>(newAutoTaskNode().schedule)
    await mount(TaskScheduleFields, schedule, { compact, hasPredecessors: true })
    const values = compact
      ? Array.from(host.querySelectorAll<HTMLOptionElement>('[aria-label="时间规则"] option')).map(item => item.value)
      : Array.from(host.querySelectorAll('.task-schedule-fields__modes button')).map(item =>
          item.getAttribute('aria-label')
        )
    expect(values).toEqual(['AUTO', 'CUSTOM', 'UNSCHEDULED'])
    expect(host.querySelector('details')).toBeNull()
    expect(host.querySelector('[aria-label="从什么时候开始"]')).toBeNull()
    const custom = await selectSpecialTime('PLAN_START')
    expect(schedule.value.mode).toBe('PLAN_START')
    expect(Array.from(custom.options).map(option => option.value)).toEqual(['PLAN_START', 'PREDECESSOR', 'FIXED'])
    expect(custom.selectedOptions[0]?.textContent).toBe('按计划开始日期')
    // 模拟旧下拉残留事件，也不能为新配置引入创建时间起点。
    await selectSpecialTime('T0')
    expect(schedule.value.mode).toBe('PLAN_START')
  })
  it.each([false, true])(
    '创建时间仅为已有配置回显，主动更换后不可再新增，templateEditing=%s',
    async templateEditing => {
      const schedule = ref<TaskSchedule>({ mode: 'T0', fixedStart: null, offsetDays: 2, durationDays: 3 })
      const original = JSON.stringify(schedule.value)
      await mount(TaskScheduleFields, schedule, { templateEditing, hasPredecessors: false })
      const select = host.querySelector<HTMLSelectElement>('[aria-label="从什么时候开始"]')
      expect(select?.selectedOptions[0]?.textContent).toBe('任务创建时开始（旧规则）')
      expect(host.querySelector('[aria-label="本次安排"]')?.textContent).toContain('从任务创建时后 2 天开始')
      expect(JSON.stringify(schedule.value)).toBe(original)
      await selectSpecialTime('PLAN_START')
      expect(host.querySelector('option[value="T0"]')).toBeNull()
      if (templateEditing) expect(host.querySelector('[aria-label="从什么时候开始"]')).toBeNull()
      else {
        expect(host.querySelector('option[value="PLAN_START"]')?.textContent).toBe('按计划开始日期')
        await selectSpecialTime('T0')
      }
      expect(schedule.value).toMatchObject({ mode: 'PLAN_START', offsetDays: 2, durationDays: 3 })
    }
  )
  it.each([
    ['AUTO', false],
    ['AUTO', true],
    ['UNSCHEDULED', false],
    ['UNSCHEDULED', true],
    ['FIXED', false],
    ['FIXED', true],
    ['PLAN_START', false],
    ['PLAN_START', true],
    ['PREDECESSOR', false],
    ['PREDECESSOR', true],
    ['T0', false],
    ['T0', true]
  ] as const)('既有 %s 在 compact=%s 始终选中正确意图和依据，不改写旧值', async (mode, compact) => {
    const schedule = ref<TaskSchedule>({ mode, fixedStart: '2030-03-01T08:00:00', offsetDays: 2, durationDays: 3 })
    const original = JSON.stringify(schedule.value)
    await mount(TaskScheduleFields, schedule, { compact, hasPredecessors: true })
    const intent = mode === 'AUTO' || mode === 'UNSCHEDULED' ? mode : 'CUSTOM'
    const basis = host.querySelector<HTMLSelectElement>('[aria-label="从什么时候开始"]')
    if (intent === 'CUSTOM') expect(basis?.value).toBe(mode)
    else expect(basis).toBeNull()
    if (compact) expect(host.querySelector<HTMLSelectElement>('[aria-label="时间规则"]')?.value).toBe(intent)
    else expect(host.querySelector('.task-schedule-fields__modes')?.getAttribute('data-radio-value')).toBe(intent)
    expect(JSON.stringify(schedule.value)).toBe(original)
  })
  it.each([false, true])('已保存的前序基准即使暂缺前序也如实保留并提醒，compact=%s', async compact => {
    const schedule = ref<TaskSchedule>({ mode: 'PREDECESSOR', fixedStart: null, offsetDays: 2, durationDays: 3 })
    const original = JSON.stringify(schedule.value)
    await mount(TaskScheduleFields, schedule, { compact, hasPredecessors: false })
    expect(host.querySelector<HTMLSelectElement>('[aria-label="从什么时候开始"]')?.value).toBe('PREDECESSOR')
    expect(
      host.querySelector<HTMLOptionElement>('[aria-label="从什么时候开始"] option[value="PREDECESSOR"]')?.disabled
    ).toBe(true)
    expect(host.textContent).toContain('当前仍保留原规则；请先设置前序任务')
    expect(JSON.stringify(schedule.value)).toBe(original)
    if (compact) await selectValue('时间规则', 'AUTO')
    else {
      button('AUTO').click()
      await flush()
    }
    expect(schedule.value).toMatchObject({ mode: 'AUTO', offsetDays: 2, durationDays: 3 })
    expect(host.querySelector('option[value="PREDECESSOR"]')).toBeNull()
  })
  it('自动排期叶节点以工期和可选间隔为主，保留零天且不在前端猜具体日期', async () => {
    const schedule = ref<TaskSchedule>(newAutoTaskNode().schedule)
    await mount(TaskScheduleFields, schedule, { templateEditing: true, isRoot: true })
    expect(host.textContent).toContain('计划工期（自然日）')
    expect(host.textContent).toContain('按整体计划开始日期安排')
    expect(host.querySelector('input[aria-label="预计工期天数"]')).not.toBeNull()
    expect(host.querySelector('input[aria-label="延后天数"]')).toBeNull()
    button('DELAY').click()
    await flush()
    expect(schedule.value.offsetDays).toBe(1)
    button('SAME_DAY').click()
    await flush()
    expect(schedule.value.offsetDays).toBe(0)
    expect(host.querySelector('[role="status"]')).toBeNull()
  })
  it.each([false, true])('自动排期汇总父节点隐藏独立工期且不清除旧数值，compact=%s', async compact => {
    const schedule = ref<TaskSchedule>({ ...newAutoTaskNode().schedule, offsetDays: 2, durationDays: 6 })
    const original = JSON.stringify(schedule.value)
    await mount(TaskScheduleFields, schedule, { hasChildren: true, isRoot: true, compact, templateEditing: true })
    expect(host.textContent).toContain('自动汇总下级任务')
    expect(host.querySelector('input[aria-label="预计工期天数"]')).toBeNull()
    expect(host.querySelector('input[aria-label="延后天数"]')).toBeNull()
    expect(JSON.stringify(schedule.value)).toBe(original)
  })
  it.each([false, true])('有下级的节点明确区分汇总与自己安排，总任务=%s', async isRoot => {
    const schedule = ref<TaskSchedule>({ ...newAutoTaskNode().schedule, offsetDays: 2, durationDays: 6 })
    await mount(TaskScheduleFields, schedule, { hasChildren: true, isRoot, templateEditing: true })
    expect(button('AUTO').textContent).toContain('按下级汇总')
    expect(button('AUTO').textContent).toContain('开始取下级最早日期，完成取下级最晚日期')
    expect(button('CUSTOM').textContent).toContain(isRoot ? '单独安排总任务' : '单独安排')
    expect(host.querySelector('[aria-label="本次安排"]')?.textContent).not.toContain('6 天')
    button('CUSTOM').click()
    await flush()
    expect(schedule.value).toMatchObject({ mode: 'PLAN_START', offsetDays: 2, durationDays: 6 })
    expect(host.querySelector('[aria-label="从什么时候开始"]')).toBeNull()
    expect(host.querySelector('[aria-label="本次安排"]')?.textContent).toContain(
      `使用${isRoot ? '总任务' : '当前任务'}自己的工期，不随下级汇总`
    )
    expect(host.querySelector('[aria-label="本次安排"]')?.textContent).toContain('计划 6 天（自然日）')
    expect(host.querySelector('[aria-label="预计工期天数"]')).not.toBeNull()
    button('AUTO').click()
    await flush()
    expect(host.querySelector('[aria-label="预计工期天数"]')).toBeNull()
    expect(schedule.value).toMatchObject({ mode: 'AUTO', offsetDays: 2, durationDays: 6 })
  })
  it.each([false, true])('独立根和子叶节点都随任务顺序，不误显示按下级汇总，总任务=%s', async isRoot => {
    const schedule = ref<TaskSchedule>(newAutoTaskNode().schedule)
    await mount(TaskScheduleFields, schedule, { isRoot, hasChildren: false, hasPredecessors: true })
    expect(button('AUTO').textContent).toContain('随任务顺序')
    expect(button('AUTO').textContent).not.toContain('按下级汇总')
    expect(button('CUSTOM').textContent).not.toContain('总任务')
    expect(host.querySelector('[aria-label="本次安排"]')?.textContent).toContain('前序任务最晚完成时')
    expect(host.querySelector('[aria-label="预计工期天数"]')).not.toBeNull()
  })
  it.each([false, true])('重新点击既有单独安排不重置真实模式、日期和工期，compact=%s', async compact => {
    const schedule = ref<TaskSchedule>({
      mode: 'FIXED',
      fixedStart: '2030-03-01T08:15:30',
      fixedEnd: '2030-03-03T09:25:00',
      offsetDays: 2,
      durationDays: 3
    })
    const original = JSON.stringify(schedule.value)
    await mount(TaskScheduleFields, schedule, { compact })
    if (compact) await selectValue('时间规则', 'CUSTOM')
    else {
      button('CUSTOM').click()
      await flush()
    }
    expect(JSON.stringify(schedule.value)).toBe(original)
    expect(host.querySelector('[aria-label="本次安排"]')?.textContent).toContain('开始 2030-03-01，完成 2030-03-03')
  })
  it('结果句区分零天、未知工期和前序日期来源，不猜具体日期', async () => {
    const schedule = ref<TaskSchedule>({ mode: 'PREDECESSOR', fixedStart: null, offsetDays: 0, durationDays: 0 })
    await mount(TaskScheduleFields, schedule, { hasPredecessors: true, plannedStart: '2030-03-01' })
    expect(host.querySelector('[aria-label="本次安排"]')?.textContent).toContain(
      '从前序任务最晚完成时当天开始，计划 0 天（自然日）'
    )
    expect(host.textContent).toContain('未完成取预计日期，已完成取实际日期')
    expect(host.textContent).not.toContain('完成前不会提前计算日期')
    expect(host.textContent).not.toContain('2030-03-01')
    schedule.value.offsetDays = null as unknown as number
    schedule.value.durationDays = null as unknown as number
    await flush()
    expect(host.querySelector('[aria-label="本次安排"]')?.textContent).toContain('间隔待填写')
    expect(host.querySelector('[aria-label="本次安排"]')?.textContent).toContain('工期待填写')
    expect(host.querySelector('[aria-label="本次安排"]')?.textContent).not.toContain('0 天')
  })
  it.each([false, true])('未知祖先时条件说明，明确上下文后再展示确切依据，compact=%s', async compact => {
    const schedule = ref<TaskSchedule>(newAutoTaskNode().schedule)
    const props = reactive({
      compact,
      hasPredecessors: false,
      hasInheritedPredecessors: undefined as boolean | undefined
    })
    await mount(TaskScheduleFields, schedule, props)
    expect(host.textContent).toContain('有前序时接续完成日期，无前序时跟随整体计划开始')
    expect(host.querySelector('[aria-label="本次安排"]')?.textContent).toContain('按任务顺序确定开始日')
    expect(host.querySelector('[aria-label="本次安排"]')?.textContent).not.toContain('从整项任务计划开始日')
    props.hasInheritedPredecessors = false
    await flush()
    expect(host.textContent).not.toContain('有前序时接续完成日期')
    expect(host.querySelector('[aria-label="本次安排"]')?.textContent).toContain('从整项任务计划开始日')
    props.hasInheritedPredecessors = true
    await flush()
    expect(host.textContent).toContain('含上级的前序任务')
    expect(host.querySelector('[aria-label="本次安排"]')?.textContent).toContain('从前序任务最晚完成时')
  })
  it('真实拆分弹窗不提供自身前序基准，未加载祖先关系时不猜自动起点', async () => {
    const schedule = ref<TaskSchedule>(newAutoTaskNode().schedule)
    await mount(TaskSplitDialog, schedule, {
      schedule: schedule.value,
      title: '安装网络',
      plan: 'LATER',
      path: '办公室装修 › 网络施工',
      busy: false,
      retryBusy: false
    })
    button('设置时间（可选）').click()
    await flush()
    expect(host.textContent).toContain('有前序时接续完成日期，无前序时跟随整体计划开始')
    expect(host.querySelector('[aria-label="本次安排"]')?.textContent).toContain('按任务顺序确定开始日')
    expect(host.querySelector('[aria-label="预计日期"]')).toBeNull()
    button('CUSTOM').click()
    await flush()
    expect(host.querySelector('option[value="PREDECESSOR"]')).toBeNull()
    await selectValue('从什么时候开始', 'PREDECESSOR')
    expect(schedule.value.mode).toBe('PLAN_START')
  })
  it('旧提前偏移准确回显为提前，不改原值也不新增提前编辑选项', async () => {
    const schedule = ref<TaskSchedule>({ mode: 'PLAN_START', fixedStart: null, offsetDays: -2, durationDays: 3 })
    const before = JSON.stringify(schedule.value)
    await mount(TaskScheduleFields, schedule, { templateEditing: true })
    expect(host.querySelector('[aria-label="本次安排"]')?.textContent).toContain('提前 2 天开始')
    expect(host.querySelector('[aria-label="本次安排"]')?.textContent).not.toContain('后 -2 天')
    expect(host.querySelector('[aria-label="延后天数"]')?.getAttribute('min')).toBe('1')
    expect(JSON.stringify(schedule.value)).toBe(before)
  })
  it('旧规则不会在加载时升级，只能由用户明确选择跟随顺序', async () => {
    const schedule = ref<TaskSchedule>({ mode: 'PLAN_START', fixedStart: null, offsetDays: 2, durationDays: 3 })
    const original = JSON.stringify(schedule.value)
    await mount(TaskScheduleFields, schedule, { hasInheritedPredecessors: true, templateEditing: true })
    expect(JSON.stringify(schedule.value)).toBe(original)
    button('AUTO').click()
    await flush()
    expect(schedule.value).toMatchObject({ mode: 'AUTO', offsetDays: 2, durationDays: 3 })
    expect(host.textContent).toContain('含上级的前序任务')
    expect(host.textContent).toContain('已完成取实际日期，未完成取预计日期')
  })
  it('任务属性按实际下级判断汇总，根无下级仍可填写工期', async () => {
    const root = ref({ ...newAutoTaskNode(), id: 'root', title: '装修' })
    const props = reactive({ members: [], isRoot: true, section: 'basic', nodes: [] as TaskNodeInput[] })
    await mount(TaskNodeFields, root, props)
    expect(host.querySelector('input[aria-label="预计工期天数"]')).not.toBeNull()
    props.nodes = [{ ...newAutoTaskNode(), id: 'child', title: '安装' }]
    await flush()
    expect(host.textContent).toContain('自动汇总下级任务')
    expect(host.querySelector('input[aria-label="预计工期天数"]')).toBeNull()
  })
  it('单独安排保留所有开始依据，零天与延后、工期均可编辑', async () => {
    const schedule = ref<TaskSchedule>({ mode: 'UNSCHEDULED', fixedStart: null, offsetDays: 0, durationDays: 3 })
    await mount(TaskScheduleFields, schedule, { templateEditing: true, hasPredecessors: true })
    expect(host.textContent).toContain('排期方式')
    expect(host.textContent).not.toContain('预计时间规则')
    expect(host.textContent).not.toContain('实际开始、实际结束')
    await selectSpecialTime('PLAN_START')
    expect(schedule.value.mode).toBe('PLAN_START')
    expect(host.textContent).toContain('计划工期（自然日）')
    expect(host.querySelector('input[aria-label="延后天数"]')).toBeNull()
    expect(host.textContent).not.toContain('具体起点在使用模板创建任务时填写')
    button('DELAY').click()
    await flush()
    expect(schedule.value.offsetDays).toBe(1)
    expect(host.querySelector('input[aria-label="延后天数"]')).not.toBeNull()
    button('SAME_DAY').click()
    await flush()
    expect(schedule.value).toMatchObject({ mode: 'PLAN_START', offsetDays: 0, durationDays: 3 })
    await selectSpecialTime('PREDECESSOR')
    expect(schedule.value).toMatchObject({ mode: 'PREDECESSOR', offsetDays: 0, durationDays: 3 })
    expect(host.querySelector('[role="status"]')).toBeNull()
  })
  it('没有前序任务时不可选前序完成排期，只读时所有安排保持原值', async () => {
    const schedule = ref<TaskSchedule>({ mode: 'PLAN_START', fixedStart: null, offsetDays: 2, durationDays: 5 })
    const props = reactive({ templateEditing: true, hasPredecessors: false, readonly: false })
    await mount(TaskScheduleFields, schedule, props)
    expect(host.querySelector('option[value="PREDECESSOR"]')).toBeNull()
    expect(host.querySelector('[aria-label="从什么时候开始"]')).toBeNull()
    props.readonly = true
    await flush()
    for (const value of ['AUTO', 'CUSTOM', 'UNSCHEDULED', 'SAME_DAY', 'DELAY'])
      expect(button(value).disabled).toBe(true)
    expect(host.querySelector('[aria-label="从什么时候开始"]')).toBeNull()
    expect(Array.from(host.querySelectorAll('input')).every(input => input.disabled)).toBe(true)
    expect(schedule.value).toMatchObject({ mode: 'PLAN_START', offsetDays: 2, durationDays: 5 })
  })
  it.each([
    [false, false],
    [false, true],
    [true, false],
    [true, true]
  ])('仅一个开始依据时隐藏整块，保留工期和摘要，compact=%s readonly=%s', async (compact, readonly) => {
    const schedule = ref<TaskSchedule>({ mode: 'PLAN_START', fixedStart: null, offsetDays: 2, durationDays: 5 })
    const original = JSON.stringify(schedule.value)
    await mount(TaskScheduleFields, schedule, { compact, readonly, templateEditing: true, hasPredecessors: false })
    expect(host.querySelector('[aria-label="从什么时候开始"]')).toBeNull()
    expect(host.textContent).not.toContain('从什么时候开始')
    expect(host.querySelector('.task-schedule-fields__basis')).toBeNull()
    expect(host.querySelector<HTMLInputElement>('[aria-label="预计工期天数"]')?.value).toBe('5')
    expect(host.querySelector('[aria-label="本次安排"]')?.textContent).toContain('计划开始日后 2 天开始')
    expect(host.querySelector('[aria-label="本次安排"]')?.textContent).toContain('计划 5 天（自然日）')
    expect(JSON.stringify(schedule.value)).toBe(original)
  })
  it('出现可选前序时才展示开始依据，选项数量变化不改写配置', async () => {
    const schedule = ref<TaskSchedule>({ mode: 'PLAN_START', fixedStart: null, offsetDays: 2, durationDays: 5 })
    const original = JSON.stringify(schedule.value)
    const props = reactive({ templateEditing: true, hasPredecessors: false })
    await mount(TaskScheduleFields, schedule, props)
    expect(host.querySelector('[aria-label="从什么时候开始"]')).toBeNull()
    props.hasPredecessors = true
    await flush()
    const select = host.querySelector<HTMLSelectElement>('[aria-label="从什么时候开始"]')
    expect(select?.value).toBe('PLAN_START')
    expect(Array.from(select?.options ?? []).map(option => option.value)).toEqual(['PLAN_START', 'PREDECESSOR'])
    props.hasPredecessors = false
    await flush()
    expect(host.querySelector('[aria-label="从什么时候开始"]')).toBeNull()
    expect(JSON.stringify(schedule.value)).toBe(original)
  })
  it('负责人下拉只选择方式，弹窗确认人员后保持雪花ID，切换方式不遗留旧人员和领取范围', async () => {
    const node = ref<TaskNodeInput>({ ...newTaskNode(), candidateUserIds: ['2'] })
    await mount(TaskAssignmentFields, node, {
      tableEditing: true,
      compact: true,
      allowFollow: true,
      members: [{ id: '9007199254740993', name: '张工' }]
    })
    const select = await selectValue('负责人安排', 'ASSIGNED')
    expect(Array.from(select.options).map(option => option.value)).toContain('ASSIGNED')
    expect(Array.from(select.options).map(option => option.label)).not.toContain('张工')
    expect(node.value.assigneeId).toBeNull()
    button('确认人员').click()
    await flush()
    expect(node.value).toMatchObject({
      assignmentMode: 'ASSIGNED',
      assigneeId: '9007199254740993',
      candidateUserIds: []
    })
    expect(host.textContent).not.toContain('选择负责人')
    expect(host.textContent).not.toContain('确认人员')
    await selectValue('负责人安排', 'FOLLOW_ROOT')
    expect(node.value).toMatchObject({ assignmentMode: 'FOLLOW_ROOT', assigneeId: null })
    await selectValue('负责人安排', 'UNASSIGNED')
    expect(node.value).toMatchObject({ assignmentMode: 'UNASSIGNED', assigneeId: null })
  })

  it('验收下拉只选择指定或无需验收，人员选择器排除负责人', async () => {
    const node = ref<TaskNodeInput>({ ...newTaskNode(), assigneeId: '1', acceptorId: '2' })
    await mount(TaskAcceptanceFields, node, {
      tableEditing: true,
      compact: true,
      members: [
        { id: '1', name: '负责人' },
        { id: '2', name: '原验收人' },
        { id: '9007199254740993', name: '张工' }
      ]
    })
    const select = await selectValue('验收人', 'ASSIGNED')
    expect(Array.from(select.options).map(option => option.value)).toEqual(['NONE', 'ASSIGNED'])
    expect(selection.props.candidateUserIds).toEqual(['2', '9007199254740993'])
    expect(node.value.acceptorId).toBe('2')
    button('确认人员').click()
    await flush()
    expect(node.value.acceptorId).toBe('9007199254740993')
    await selectValue('验收人', 'NONE')
    expect(node.value.acceptorId).toBeNull()
    expect(node.value.assigneeId).toBe('1')
    expect(host.textContent).not.toContain('确认人员')
  })

  it('负责人选择器异常返回验收人时不改变原有分配', async () => {
    const node = ref<TaskNodeInput>({
      ...newTaskNode(),
      assignmentMode: 'ASSIGNED',
      assigneeId: '1',
      acceptorId: '9007199254740993'
    })
    await mount(TaskAssignmentFields, node, {
      tableEditing: true,
      compact: true,
      members: [
        { id: '1', name: '负责人' },
        { id: '9007199254740993', name: '验收人' }
      ]
    })
    const before = JSON.stringify(node.value)
    await selectValue('负责人安排', 'ASSIGNED')
    button('确认人员').click()
    await flush()
    expect(JSON.stringify(node.value)).toBe(before)
    expect(host.textContent).toContain('负责人和验收人不能是同一人')
  })

  it('表格开放领取保留候选范围入口，可编辑并清空范围，不改变负责人安排', async () => {
    const node = ref<TaskNodeInput>({ ...newTaskNode(), candidateUserIds: ['2'] })
    await mount(TaskAssignmentFields, node, {
      tableEditing: true,
      compact: true,
      members: [
        { id: '2', name: '原候选人' },
        { id: '9007199254740993', name: '张工' }
      ]
    })
    button('限定领取人员').click()
    await flush()
    expect(selection.props).toMatchObject({
      multiple: true,
      enabledOnly: true,
      selectedUsers: [{ id: '2', username: '', nickname: '原候选人' }]
    })
    button('确认人员').click()
    await flush()
    expect(node.value).toMatchObject({
      assignmentMode: 'OPEN',
      assigneeId: null,
      candidateUserIds: ['9007199254740993']
    })
    button('限定领取人员').click()
    await flush()
    button('清空人员').click()
    await flush()
    expect(node.value.candidateUserIds).toEqual([])
    expect(button('限定领取人员')).toBeDefined()
  })

  it.each(['负责人安排', '验收人'] as const)('只读%s表格选择器禁用，迟到的选择事件也不能写值', async label => {
    const node = ref<TaskNodeInput>({ ...newTaskNode(), assignmentMode: 'ASSIGNED', assigneeId: '1', acceptorId: '2' })
    await mount(label === '负责人安排' ? TaskAssignmentFields : TaskAcceptanceFields, node, {
      tableEditing: true,
      compact: true,
      readonly: true,
      members: [
        { id: '1', name: '负责人' },
        { id: '2', name: '验收人' },
        { id: '3', name: '其他成员' }
      ]
    })
    const before = JSON.stringify(node.value)
    const select = await selectValue(label, 'ASSIGNED')
    expect(select.disabled).toBe(true)
    await selectValue(label, label === '负责人安排' ? 'OPEN' : 'NONE')
    expect(JSON.stringify(node.value)).toBe(before)
  })

  it('候选范围选择中转为只读后，迟到确认不能覆盖既有范围', async () => {
    const node = ref<TaskNodeInput>({ ...newTaskNode(), candidateUserIds: ['2'] })
    const props = reactive({
      tableEditing: true,
      compact: true,
      readonly: false,
      members: [
        { id: '2', name: '原候选人' },
        { id: '9007199254740993', name: '张工' }
      ]
    })
    await mount(TaskAssignmentFields, node, props)
    button('限定领取人员').click()
    await flush()
    props.readonly = true
    await flush()
    button('确认人员').click()
    await flush()
    expect(node.value.candidateUserIds).toEqual(['2'])
    expect(host.textContent).not.toContain('确认人员')
  })

  it('行内时间规则直接更新同一个排期模型，模板不提供新固定日期', async () => {
    const schedule = ref<TaskSchedule>({ mode: 'UNSCHEDULED', fixedStart: null, offsetDays: 0, durationDays: 1 })
    await mount(TaskScheduleFields, schedule, { compact: true, templateEditing: true, hasPredecessors: true })
    const select = host.querySelector<HTMLSelectElement>('select[aria-label="时间规则"]')!
    expect(Array.from(select.options).map(option => option.value)).toEqual(['AUTO', 'CUSTOM', 'UNSCHEDULED'])
    await selectSpecialTime('PREDECESSOR')
    const duration = host.querySelector<HTMLInputElement>('input[aria-label="预计工期天数"]')!
    duration.value = '3'
    duration.dispatchEvent(new Event('input'))
    await flush()
    expect(schedule.value.mode).toBe('PREDECESSOR')
    expect(Number(schedule.value.durationDays)).toBe(3)
    expect(select.selectedOptions[0]?.label).toBe('单独安排')
    expect(host.querySelector<HTMLSelectElement>('[aria-label="从什么时候开始"]')?.value).toBe('PREDECESSOR')
    expect(host.querySelector('.task-schedule-fields--compact')?.getAttribute('title')).toBeNull()
    expect(host.querySelector('[format="YYYY-MM-DD"]')).toBeNull()
  })
  it.each(['business', 'feedback'] as const)('独立配置页签 %s 不重复展示基本信息和另一分区', async section => {
    const node = ref<TaskNodeInput>({
      ...newTaskNode(),
      title: '总任务',
      dataPolicy: { version: 1, business: 'GROUP', feedback: 'GROUP' }
    })
    await mount(TaskNodeFields, node, { members: [], isRoot: true, templateEditing: true, section })
    expect(host.querySelector('input[placeholder="填写可执行的任务名称"]')).toBeNull()
    expect(host.textContent).not.toContain('任务基本信息')
    expect(host.querySelector('[data-business-editor]')).not.toBeNull()
    expect(host.textContent).not.toContain('配置业务视图、办理表单与标准工时')
    expect(host.textContent).not.toContain('选择执行中需要登记的反馈内容')
  })
  it.each([false, true])('模板节点只配置规则且不要求实际起点（总任务=%s）', async isRoot => {
    const node = ref<TaskNodeInput>({
      ...newTaskNode(),
      predecessorIds: isRoot ? [] : ['previous'],
      schedule: { mode: 'PLAN_START', fixedStart: null, offsetDays: 2, durationDays: 3 }
    })
    await mount(TaskNodeFields, node, { members: [], isRoot, templateEditing: true })
    expect(host.textContent).toContain('排期方式')
    expect(host.querySelector<HTMLInputElement>('[aria-label="延后天数"]')?.value).toBe('2')
    expect(host.querySelector<HTMLInputElement>('[aria-label="预计工期天数"]')?.value).toBe('3')
    expect(host.textContent).not.toContain('使用模板创建任务时填写')
    expect(host.textContent).not.toContain('加入任务池前请补充')
    expect(host.textContent).not.toContain('FIXED')
    expect(host.querySelector('option[value="PREDECESSOR"]') === null).toBe(isRoot)
    if (isRoot) expect(host.querySelector('[aria-label="从什么时候开始"]')).toBeNull()
    else await selectSpecialTime('PREDECESSOR')
    expect(node.value.schedule.mode).toBe(isRoot ? 'PLAN_START' : 'PREDECESSOR')
    expect(host.querySelector('[role="status"]')).toBeNull()
  })
  it.each([false, true])('历史模板的指定日期只读保留，切换规则不自动补写旧日期，compact=%s', async compact => {
    const schedule = ref<TaskSchedule>({
      mode: 'FIXED',
      fixedStart: '2030-03-01T08:15:30',
      offsetDays: 0,
      durationDays: 3
    })
    const original = JSON.stringify(schedule.value)
    await mount(TaskScheduleFields, schedule, { compact, templateEditing: true })
    if (compact) expect(host.querySelector<HTMLSelectElement>('[aria-label="时间规则"]')?.value).toBe('CUSTOM')
    else expect(host.querySelector('.task-schedule-fields__modes')?.getAttribute('data-radio-value')).toBe('CUSTOM')
    expect(host.querySelector<HTMLSelectElement>('[aria-label="从什么时候开始"]')?.value).toBe('FIXED')
    expect(host.querySelector<HTMLOptionElement>('option[value="FIXED"]')?.disabled).toBe(true)
    expect(host.querySelector('[format="YYYY-MM-DD"]')).toBeNull()
    expect(JSON.stringify(schedule.value)).toBe(original)
    await selectSpecialTime('PLAN_START')
    expect(schedule.value).toMatchObject({ mode: 'PLAN_START', fixedStart: '2030-03-01T08:15:30', durationDays: 3 })
    expect(schedule.value.fixedEnd).toBeUndefined()
    expect(host.textContent).not.toContain('FIXED')
  })
  it('配置中不再提供直接上级下拉，编辑名称不改变已有归属与顺序', async () => {
    const root = { ...newTaskNode(), id: 'root', title: '办公室装修' }
    const parent = { ...newTaskNode('root'), id: 'parent', title: '装修施工' }
    const current = ref<TaskNodeInput>({
      ...newTaskNode('parent'),
      id: 'current',
      title: '墙面粉刷',
      predecessorIds: ['other']
    })
    await mount(TaskNodeFields, current, {
      members: [],
      nodes: [root, parent, current.value],
      hierarchyRootId: 'root',
      applicationId: null
    })
    expect(host.textContent).not.toContain('直接上级任务')
    expect(host.querySelector('[placeholder="未指定时属于总任务的一级子任务"]')).toBeNull()
    const input = host.querySelector<HTMLInputElement>('[placeholder="填写可执行的任务名称"]')
    expect(input).not.toBeNull()
    if (!input) throw new Error('缺少任务名称')
    input.value = '墙面粉刷已改名'
    input.dispatchEvent(new Event('input'))
    await flush()
    expect(current.value).toMatchObject({ title: '墙面粉刷已改名', parentId: 'parent', predecessorIds: ['other'] })
  })
  it.each([false, true])('节点配置只读展示顺序，不因 readonly=%s 修改或删去已有前置', async readonly => {
    const first = { ...newTaskNode(), id: 'first', title: '准备' }
    const current = ref<TaskNodeInput>({
      ...newTaskNode(),
      id: 'current',
      title: '施工',
      predecessorIds: ['first', 'missing']
    })
    const before = JSON.stringify(current.value)
    await mount(TaskNodeFields, current, { members: [], nodes: [first, current.value], readonly, applicationId: null })
    const summary = host.querySelector('[aria-label="任务顺序"]')
    expect(summary?.textContent).toContain('准备')
    expect(summary?.textContent).toContain('未显示的前置任务')
    expect(summary?.querySelectorAll('input,button,select').length).toBe(0)
    expect(host.querySelector('.task-dependency-picker')).toBeNull()
    expect(host.querySelector('[aria-label="查找前置任务"]')).toBeNull()
    expect(JSON.stringify(current.value)).toBe(before)
    current.value.title = '施工已改名'
    await flush()
    expect(current.value.predecessorIds).toEqual(['first', 'missing'])
  })
  it('没有单独前置时展示并行，并区分继承的上级顺序而不复制为本节点前置', async () => {
    const first = { ...newTaskNode(), id: 'first', title: '准备' }
    const parent = { ...newTaskNode(), id: 'parent', title: '施工', predecessorIds: ['first'] }
    const current = ref<TaskNodeInput>({ ...newTaskNode('parent'), id: 'current', title: '测量' })
    await mount(TaskNodeFields, current, { members: [], nodes: [first, parent, current.value], applicationId: null })
    const summary = host.querySelector('[aria-label="任务顺序"]')
    expect(summary?.textContent).toContain('无单独前置任务，可与同级并行')
    expect(summary?.textContent).toContain('受上级顺序约束：还需等待')
    expect(summary?.textContent).toContain('准备')
    expect(current.value.predecessorIds).toEqual([])
    expect(parent.predecessorIds).toEqual(['first'])
  })
  it('未定人子项允许显式跟随，手动改为开放领取后保留独立来源', async () => {
    const node = ref<TaskNodeInput>(newTaskNode('root'))
    await mount(TaskAssignmentFields, node, { members: [], allowFollow: true })
    const select = host.querySelector('select')
    if (!select) throw new Error('缺少人员安排选择器')
    expect(select.value).toBe('FOLLOW_ROOT')
    expect(host.querySelector('.task-assignment-fields__main')?.getAttribute('title')).toContain(
      '总任务被领取或分配后承接'
    )
    select.value = 'OPEN'
    select.dispatchEvent(new Event('change'))
    await flush()
    expect(node.value).toMatchObject({ assignmentMode: 'OPEN', assigneeId: null, candidateUserIds: [] })
    expect(host.textContent).toContain('所有人可领取')
  })
  it('受限分工仅提供指定负责人和开放领取，不提供跟随、待分配或验收', async () => {
    const node = ref<TaskNodeInput>({ ...newTaskNode('root'), assignmentMode: 'ASSIGNED', assigneeId: '1' })
    await mount(TaskAssignmentFields, node, { members: [], allowFollow: true, delegateOnly: true })
    expect(Array.from(host.querySelectorAll('option')).map(option => option.value)).toEqual(['ASSIGNED', 'OPEN'])
  })
  it('在办转交只允许指定接收人，不展示或接受其他分工模式', async () => {
    const node = ref<TaskNodeInput>({ ...newTaskNode('root'), assignmentMode: 'ASSIGNED', assigneeId: '1' })
    await mount(TaskAssignmentFields, node, { members: [], allowFollow: true, assignedOnly: true })
    expect(Array.from(host.querySelectorAll('option')).map(option => option.value)).toEqual(['ASSIGNED'])
    await selectValue('负责人安排', 'OPEN')
    expect(node.value.assignmentMode).toBe('ASSIGNED')
    expect(node.value.assigneeId).toBe('1')
  })
  it('默认所有人可领取；指定负责人调用公共选择器单选且限制启用候选，保留大整数ID', async () => {
    const node = ref<TaskNodeInput>(newTaskNode())
    await mount(TaskAssignmentFields, node, { members: [{ id: '9007199254740993', name: '张工' }] })
    expect(host.textContent).toContain('所有人可领取')
    const select = host.querySelector('select')!
    select.value = 'ASSIGNED'
    select.dispatchEvent(new Event('change'))
    await flush()
    expect(selection.props).toMatchObject({
      multiple: false,
      showMultipleToggle: false,
      enabledOnly: true,
      candidateUserIds: ['9007199254740993']
    })
    button('确认人员').click()
    await flush()
    expect(node.value).toMatchObject({ assignmentMode: 'ASSIGNED', assigneeId: '9007199254740993' })
    select.value = 'UNASSIGNED'
    select.dispatchEvent(new Event('change'))
    await flush()
    expect(node.value.assigneeId).toBeNull()
    select.value = 'ASSIGNED'
    select.dispatchEvent(new Event('change'))
    await flush()
    expect(button('确认人员')).toBeDefined()
  })
  it('验收人单选启用成员并排除负责人，保留雪花 ID，取消后恢复直接完成', async () => {
    const node = ref<TaskNodeInput>({ ...newTaskNode(), assigneeId: '1' })
    await mount(TaskAcceptanceFields, node, {
      members: [
        { id: '1', name: '负责人' },
        { id: '9007199254740993', name: '张工' }
      ]
    })
    expect(host.textContent).toContain('不设置验收人，负责人完成总任务后直接结束')
    await selectValue('验收人', 'ASSIGNED')
    expect(selection.props).toMatchObject({
      multiple: false,
      showMultipleToggle: false,
      enabledOnly: true,
      candidateUserIds: ['9007199254740993']
    })
    button('确认人员').click()
    await flush()
    expect(node.value.acceptorId).toBe('9007199254740993')
    expect(host.textContent).toContain('负责人提交后进入待验收')
    await selectValue('验收人', 'NONE')
    expect(node.value.acceptorId).toBeNull()
    expect(node.value.assigneeId).toBe('1')
  })
  it('不能将验收人改为负责人，显示冲突并保留已配置的两人', async () => {
    const node = ref<TaskNodeInput>({
      ...newTaskNode(),
      assignmentMode: 'ASSIGNED',
      assigneeId: '1',
      acceptorId: '9007199254740993'
    })
    await mount(TaskAssignmentFields, node, { members: [{ id: '9007199254740993', name: '张工' }] })
    await selectValue('负责人安排', 'ASSIGNED')
    button('确认人员').click()
    await flush()
    expect(host.textContent).toContain('负责人和验收人不能是同一人')
    expect(node.value).toMatchObject({ assigneeId: '1', acceptorId: '9007199254740993' })
  })
  it('已选负责人从选择器异常回传为验收人时拒绝写入', async () => {
    const node = ref<TaskNodeInput>({ ...newTaskNode(), assigneeId: '9007199254740993', acceptorId: '2' })
    await mount(TaskAcceptanceFields, node, { members: [{ id: '2', name: '验收人' }] })
    await selectValue('验收人', 'ASSIGNED')
    button('确认人员').click()
    await flush()
    expect(host.textContent).toContain('负责人和验收人不能是同一人')
    expect(node.value.acceptorId).toBe('2')
  })
  it.each([false, true])('仅总任务展示可选验收配置（isRoot=%s）', async isRoot => {
    const node = ref<TaskNodeInput>(newTaskNode(isRoot ? null : 'root'))
    await mount(TaskNodeFields, node, { members: [], isRoot, applicationId: null })
    expect(host.textContent?.includes('验收人（可选）')).toBe(isRoot)
    expect(host.querySelector('[aria-label="验收人"]') !== null).toBe(isRoot)
  })
  it('领取范围可选多选候选，清空后回到所有人，不强制候选', async () => {
    const node = ref<TaskNodeInput>(newTaskNode())
    await mount(TaskAssignmentFields, node, { members: [{ id: '9007199254740993', name: '张工' }] })
    button('限定领取人员').click()
    await flush()
    expect(selection.props).toMatchObject({ multiple: true, enabledOnly: true })
    button('确认人员').click()
    await flush()
    expect(node.value).toMatchObject({
      assignmentMode: 'OPEN',
      assigneeId: null,
      candidateUserIds: ['9007199254740993']
    })
    button('限定领取人员').click()
    await flush()
    button('清空人员').click()
    await flush()
    expect(node.value.candidateUserIds).toEqual([])
    expect(host.textContent).toContain('所有人可领取')
  })
  it('只在已有整体起点时展示预计日期，前序完成时间不假造日期或重复解释', async () => {
    const schedule = ref<TaskSchedule>({ mode: 'PLAN_START', fixedStart: null, offsetDays: 2, durationDays: 3 })
    await mount(TaskScheduleFields, schedule)
    expect(host.querySelector('[aria-label="预计日期"]')).toBeNull()
    app.unmount()
    host.remove()
    await mount(TaskScheduleFields, schedule, { plannedStart: '2030-03-01T08:00:00', hasPredecessors: true })
    expect(Array.from(host.querySelectorAll('[aria-label="预计日期"] dd')).map(item => item.textContent)).toEqual([
      '2030-03-03',
      '2030-03-06'
    ])
    expect(host.textContent).not.toContain('实际开始、实际结束由任务执行时自动记录')
    await selectSpecialTime('PREDECESSOR')
    expect(host.querySelector('[aria-label="预计日期"]')).toBeNull()
    expect(host.textContent).not.toContain('2030-03-03')
  })
  it('指定日期显示开始与截止，保留旧工期推导截止；只开始不伪造截止', async () => {
    const schedule = ref<TaskSchedule>({
      mode: 'FIXED',
      fixedStart: '2030-03-01T08:00:00',
      offsetDays: 0,
      durationDays: 3
    })
    await mount(TaskScheduleFields, schedule)
    expect(host.querySelector('[aria-label="预计日期"]')?.textContent).toContain('预计完成2030-03-04')
    await selectSpecialTime('PLAN_START')
    expect(schedule.value.fixedEnd).toBe('2030-03-04T08:00:00')
    await selectSpecialTime('FIXED')
    expect(schedule.value.fixedEnd).toBe('2030-03-04T08:00:00')
    schedule.value.fixedEnd = null
    await flush()
    expect(host.querySelector('[aria-label="预计日期"]')?.textContent).toContain('预计完成—')
    expect(host.querySelectorAll('input')).toHaveLength(2)
  })
  it('未知偏移不当作当天，历史 T0 回显不改写，切换排期保留已填天数', async () => {
    const schedule = ref<TaskSchedule>({ mode: 'T0', fixedStart: null, offsetDays: 2, durationDays: 3 })
    await mount(TaskScheduleFields, schedule, { templateEditing: true })
    expect(host.querySelector<HTMLSelectElement>('[aria-label="从什么时候开始"]')?.value).toBe('T0')
    expect(schedule.value.mode).toBe('T0')
    button('UNSCHEDULED').click()
    await flush()
    button('CUSTOM').click()
    await flush()
    expect(schedule.value).toMatchObject({ mode: 'PLAN_START', offsetDays: 2, durationDays: 3 })
    schedule.value.offsetDays = null as unknown as number
    await flush()
    expect(host.querySelector('[aria-label="延后天数"]')).not.toBeNull()
    expect(schedule.value.offsetDays).toBeNull()
    expect(host.querySelector('[aria-label="预计日期"]')).toBeNull()
  })
})

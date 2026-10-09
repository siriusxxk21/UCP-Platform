// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, inject, nextTick, onMounted, provide, ref, type App, type Component } from 'vue'
import TaskWorkRuleFields from '@/views/nocode/task-center/TaskWorkRuleFields.vue'
import TaskWorkBudgetFields from '@/views/nocode/task-center/TaskWorkBudgetFields.vue'
import type { TaskWorkEntryConfig, TaskWorkRule } from '@/types/nocode/task-work-entries'

vi.mock('@/views/nocode/task-center/TaskBindingPicker.vue', () => ({
  default: defineComponent({
    emits: ['rule-fields', 'field-options'],
    setup(_, { emit }) {
      onMounted(() => {
        emit('rule-fields', [
          { id: 'count', name: '设备数量', type: 'DECIMAL' },
          { id: 'state', name: '施工状态', type: 'SELECT' },
          { id: 'ready', name: '准备完成', type: 'BOOLEAN' }
        ])
        emit('field-options', { state: { options: [{ code: 'DONE', label: '已完成' }] } })
      })
      return () => null
    }
  })
}))
let app: App | undefined, host: HTMLElement
async function flush() {
  await nextTick()
  await nextTick()
}
function mount(render: () => ReturnType<typeof h>) {
  app = createApp(render)
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  app.component('AFormItem', plain)
  app.component('ARadio', plain)
  app.component('ADatePicker', plain)
  const input = defineComponent({
    props: ['value', 'disabled', 'options'],
    emits: ['update:value'],
    setup:
      (props, { emit, attrs }) =>
      () =>
        h('input', {
          ...attrs,
          value: props.value,
          disabled: props.disabled,
          onInput: (event: Event) => {
            const raw = (event.target as HTMLInputElement).value
            emit('update:value', attrs.type === 'number' ? (raw === '' ? null : Number(raw)) : raw)
          }
        })
  })
  app.component('AInput', input)
  app.component(
    'AInputNumber',
    defineComponent({
      props: ['value', 'disabled'],
      emits: ['update:value'],
      setup:
        (props, { emit, attrs }) =>
        () =>
          h(input, {
            ...attrs,
            ...props,
            type: 'number',
            'onUpdate:value': (value: unknown) => emit('update:value', value)
          })
    })
  )
  app.component(
    'ASelect',
    defineComponent({
      props: ['value', 'disabled', 'options'],
      emits: ['update:value'],
      setup:
        (props, { emit, attrs }) =>
        () =>
          h(
            'select',
            {
              ...attrs,
              value: String(props.value ?? ''),
              disabled: props.disabled,
              onChange: (event: Event) => {
                const value = (props.options || []).find(
                  (option: { value: unknown }) => String(option.value) === (event.target as HTMLSelectElement).value
                )?.value
                emit('update:value', value)
              }
            },
            (props.options || []).map((option: { value: unknown; label: string }) =>
              h('option', { value: String(option.value) }, option.label)
            )
          )
    })
  )
  app.component(
    'ARadioGroup',
    defineComponent({
      props: ['value', 'disabled'],
      emits: ['update:value'],
      setup(props, { emit, slots, attrs }) {
        provide('budget-radio', { props, change: (value: string) => emit('update:value', value) })
        return () => h('div', attrs, slots.default?.())
      }
    })
  )
  app.component(
    'ARadioButton',
    defineComponent({
      props: ['value'],
      setup(props, { slots }) {
        const group = inject<{ props: { value: string; disabled: boolean }; change: (value: string) => void }>(
          'budget-radio'
        )
        return () =>
          h(
            'button',
            { disabled: group?.props.disabled, 'data-mode': props.value, onClick: () => group?.change(props.value) },
            slots.default?.()
          )
      }
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
}
async function change(label: string, value: string, event = 'change') {
  const input = host.querySelector<HTMLInputElement | HTMLSelectElement>(`[aria-label="${label}"]`)
  if (!input) throw new Error(`控件不存在：${label}`)
  input.value = value
  input.dispatchEvent(new Event(event))
  await flush()
}
function entry(rule?: TaskWorkRule | null): TaskWorkEntryConfig {
  return {
    key: 'log',
    name: '施工日志',
    binding: { applicationId: 'app', viewId: 'view', formId: 'form', entryId: null },
    workRule: rule,
    dataMode: 'ROOT_SHARED',
    sourceNodeId: null,
    sourceEntryKey: null,
    readableFieldIds: ['safe'],
    writableFieldIds: [],
    required: true,
    allowAll: false
  }
}
async function mountRule(initial: TaskWorkRule | null, readonly = false, compact = true) {
  const rule = ref(initial)
  mount(() =>
    h(TaskWorkRuleFields as Component, {
      modelValue: rule.value,
      binding: entry().binding,
      readonly,
      compact,
      'onUpdate:modelValue': (value: TaskWorkRule | null) => (rule.value = value)
    })
  )
  await flush()
  return rule
}
async function mountBudget(
  initial: TaskWorkEntryConfig[],
  initialMode: 'AUTO' | 'MANUAL' | null = null,
  readonly = false
) {
  const entries = ref(initial),
    minutes = ref<number | null>(180),
    mode = ref(initialMode)
  mount(() =>
    h(TaskWorkBudgetFields, {
      entries: entries.value,
      readonly,
      minutes: minutes.value,
      mode: mode.value,
      'onUpdate:minutes': value => (minutes.value = value),
      'onUpdate:mode': value => (mode.value = value)
    })
  )
  await flush()
  return { entries, minutes, mode }
}
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
})

describe('表格内三种工时规则共用控件', () => {
  it('无需弹窗直接选择模式；换单位时清理不适用字段和旧预计量', async () => {
    const original: TaskWorkRule = {
      mode: 'CONDITION',
      minutes: 15,
      conditionFieldId: 'state',
      conditionValue: 'DONE',
      plannedQuantity: 4
    }
    const rule = await mountRule(original)
    expect(host.querySelector('[aria-label="计工时的条件值"]')).not.toBeNull()
    await change('标准工时计算方式', 'QUANTITY')
    expect(rule.value).toEqual({ mode: 'QUANTITY', minutes: 15 })
    expect(original.conditionValue).toBe('DONE')
    await change('计工时的数量字段', 'count')
    expect(rule.value?.quantityFieldId).toBe('count')
    expect(host.querySelector<HTMLSelectElement>('[aria-label="计工时的数量字段"]')?.value).toBe('count')
    expect(host.querySelector('[aria-label="计工时的条件值"]')).toBeNull()
  })
  it('表内小时分钟输入同步工时，保留原预计量和增减值', async () => {
    const rule = await mountRule({ mode: 'RECORD_ONCE', minutes: 15, plannedQuantity: 4, adjustmentMinutes: 5 })
    await change('标准工时（小时）', '2', 'input')
    await change('标准工时（分钟）', '30', 'input')
    expect(rule.value).toEqual({ mode: 'RECORD_ONCE', minutes: 150, plannedQuantity: 4, adjustmentMinutes: 5 })
  })
  it('条件选项使用中文，布尔false仍是有效条件', async () => {
    const rule = await mountRule(
      { mode: 'CONDITION', minutes: 20, conditionFieldId: 'state', conditionValue: 'DONE' },
      false,
      false
    )
    expect(host.textContent).toContain('「施工状态」首次为「已完成」')
    await change('计工时的条件字段', 'ready')
    expect(rule.value?.conditionValue).toBeNull()
    await change('计工时的条件值', 'false')
    expect(rule.value?.conditionValue).toBe(false)
    expect(host.textContent).toContain('「准备完成」首次为「否」')
  })
  it('只读保护覆盖模式、字段和时长，不因手工派发事件被修改', async () => {
    const original: TaskWorkRule = { mode: 'QUANTITY', minutes: 15, quantityFieldId: 'count', plannedQuantity: 2 }
    const rule = await mountRule(original, true)
    for (const control of Array.from(host.querySelectorAll<HTMLInputElement>('select,input')))
      expect(control.disabled).toBe(true)
    await change('标准工时计算方式', 'CONDITION')
    await change('标准工时（分钟）', '20', 'input')
    expect(rule.value).toEqual(original)
  })
  it.each(['RECORD_ONCE', 'QUANTITY', 'CONDITION'] as const)('表格模式不重复显示计算说明：%s', async mode => {
    await mountRule({ mode, minutes: 15, quantityFieldId: 'count', conditionFieldId: 'ready', conditionValue: true })
    expect(host.querySelector('[aria-label="工时计算预览"]')).toBeNull()
    expect(host.querySelector('.work-rule__duration > span')).toBeNull()
    expect(host.querySelector('[aria-label="标准工时（分钟）"]')).not.toBeNull()
    if (mode === 'QUANTITY') expect(host.querySelector('[aria-label="计工时的数量字段"]')).not.toBeNull()
    if (mode === 'CONDITION') expect(host.querySelector('[aria-label="计工时的条件值"]')).not.toBeNull()
  })
  it('不计工时默认无需填写，三种模式随时可选且可清除配置', async () => {
    const rule = await mountRule(null)
    expect(host.querySelector<HTMLSelectElement>('[aria-label="标准工时计算方式"]')?.value).toBe('NONE')
    expect(host.querySelector('[aria-label="标准工时（分钟）"]')).toBeNull()
    await change('标准工时计算方式', 'QUANTITY')
    expect(rule.value).toEqual({ mode: 'QUANTITY', minutes: 0 })
    await change('计工时的数量字段', 'count')
    await change('标准工时（分钟）', '15', 'input')
    expect(rule.value).toEqual({ mode: 'QUANTITY', minutes: 15, quantityFieldId: 'count' })
    await change('标准工时计算方式', 'NONE')
    expect(rule.value).toBeNull()
    expect(host.querySelector('[aria-label="标准工时（分钟）"]')).toBeNull()
    await change('标准工时计算方式', 'RECORD_ONCE')
    expect(rule.value).toEqual({ mode: 'RECORD_ONCE', minutes: 0 })
  })
  it('只读不计工时选项不能通过派发事件打开配置', async () => {
    const rule = await mountRule(null, true)
    await change('标准工时计算方式', 'RECORD_ONCE')
    expect(rule.value).toBeNull()
  })
})
describe('任务标准总工时预算', () => {
  it('旧模板缺省人工总额，不给未知工作量擅自默认1', async () => {
    const state = await mountBudget([entry({ mode: 'RECORD_ONCE', minutes: 15 })])
    expect(state.minutes.value).toBe(180)
    expect(state.mode.value).toBeNull()
    expect(state.entries.value[0]?.workRule?.plannedQuantity).toBeUndefined()
    host.querySelector<HTMLButtonElement>('[data-mode="AUTO"]')?.click()
    await flush()
    expect(state.minutes.value).toBeNull()
    expect(host.textContent).toContain('未设置')
    expect(host.querySelector('.work-budget__warning')).toBeNull()
  })
  it('旧模板手工修改总额显式标记MANUAL，未实际修改不生成模式变更', async () => {
    const state = await mountBudget([entry({ mode: 'RECORD_ONCE', minutes: 15 })])
    await change('任务标准总工时（小时）', '3', 'input')
    expect(state.minutes.value).toBe(180)
    expect(state.mode.value).toBeNull()
    await change('任务标准总工时（小时）', '2', 'input')
    expect(state.minutes.value).toBe(120)
    expect(state.mode.value).toBe('MANUAL')
  })
  it('三种模式按工作量自动合计，并在总额处统一向上取整', async () => {
    const state = await mountBudget(
      [
        entry({ mode: 'RECORD_ONCE', minutes: 15, plannedQuantity: 2 }),
        entry({ mode: 'QUANTITY', minutes: 5, adjustmentMinutes: 1, quantityFieldId: 'count', plannedQuantity: 0.2 }),
        entry({ mode: 'CONDITION', minutes: 20, conditionFieldId: 'ready', conditionValue: false, plannedQuantity: 2 })
      ],
      'AUTO'
    )
    expect(state.minutes.value).toBe(72)
    expect(host.textContent).toContain('1 小时 12 分钟')
    const firstRule = state.entries.value[0]?.workRule
    if (!firstRule) throw new Error('缺少测试规则')
    firstRule.minutes = 30
    await flush()
    expect(state.minutes.value).toBe(102)
  })
  it('多行小数工作量先合计再进位，不能逐行增加虚拟分钟', async () => {
    const state = await mountBudget(
      [
        entry({ mode: 'QUANTITY', minutes: 1, quantityFieldId: 'count', plannedQuantity: 0.2 }),
        entry({ mode: 'QUANTITY', minutes: 1, quantityFieldId: 'count', plannedQuantity: 0.2 })
      ],
      'AUTO'
    )
    expect(state.minutes.value).toBe(1)
  })
  it('切回人工保留当前合计，之后不随表单单价自动变化', async () => {
    const state = await mountBudget([entry({ mode: 'RECORD_ONCE', minutes: 15, plannedQuantity: 2 })], 'AUTO')
    host.querySelector<HTMLButtonElement>('[data-mode="MANUAL"]')?.click()
    await flush()
    const firstRule = state.entries.value[0]?.workRule
    if (!firstRule) throw new Error('缺少测试规则')
    firstRule.minutes = 30
    await flush()
    expect(state.mode.value).toBe('MANUAL')
    expect(state.minutes.value).toBe(30)
  })
  it.each([-1, 0.5, Number.NaN])('固定记录数无效时不生成总工时：%s', async plannedQuantity => {
    const state = await mountBudget([entry({ mode: 'RECORD_ONCE', minutes: 15, plannedQuantity })], 'AUTO')
    expect(state.minutes.value).toBeNull()
    expect(host.textContent).toContain('未设置')
  })
  it('允许一项预计量为0，全部为0时总额未设置但不显示阻塞提示', async () => {
    const state = await mountBudget(
      [
        entry({ mode: 'RECORD_ONCE', minutes: 15, plannedQuantity: 0 }),
        entry({ mode: 'RECORD_ONCE', minutes: 15, plannedQuantity: 2 })
      ],
      'AUTO'
    )
    expect(state.minutes.value).toBe(30)
    const second = state.entries.value[1]?.workRule
    if (!second) throw new Error('缺少测试规则')
    second.plannedQuantity = 0
    await flush()
    expect(state.minutes.value).toBeNull()
    expect(host.textContent).toContain('未设置')
    expect(host.querySelector('.work-budget__warning')).toBeNull()
    expect(host.textContent).not.toContain('超出可配置范围')
  })
  it('只读自动汇总可以显示参考值但不改写已发布版本', async () => {
    const state = await mountBudget([entry({ mode: 'RECORD_ONCE', minutes: 15, plannedQuantity: 2 })], 'AUTO', true)
    expect(host.textContent).toContain('30 分钟')
    expect(state.minutes.value).toBe(180)
    expect(host.querySelector<HTMLButtonElement>('[data-mode="MANUAL"]')?.disabled).toBe(true)
  })
})

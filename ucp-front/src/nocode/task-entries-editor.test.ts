// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, watch, type App } from 'vue'
import TaskEntriesEditor from '@/views/nocode/task-center/TaskEntriesEditor.vue'
import type { TaskWorkEntryConfig } from '@/types/nocode/task-work-entries'
import type { TaskDataPolicy } from '@/types/nocode/task-center'
import { taskEntryIdentity, type TaskEntryCandidate } from './task-entry-selection'
import { taskWorkRuleSummary } from './task-work-rule'
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['columns', 'dataSource'],
    setup:
      (props, { slots }) =>
      () =>
        h('section', { 'data-work-table': '' }, [
          h('header', props.columns.map((column: { title: string }) => column.title).join(' ')),
          ...props.dataSource.map((record: TaskWorkEntryConfig) =>
            h(
              'div',
              props.columns.map((column: { key: string }) => slots.bodyCell?.({ record, column }))
            )
          )
        ])
  })
}))
vi.mock('@/views/nocode/task-center/TaskWorkRuleFields.vue', () => ({
  default: defineComponent({
    props: ['modelValue'],
    setup: props => () => h('span', taskWorkRuleSummary(props.modelValue))
  })
}))
vi.mock('@/views/nocode/task-center/TaskWorkBudgetFields.vue', () => ({
  default: defineComponent({
    props: ['entries'],
    setup: props => () =>
      h(
        'section',
        { 'data-budget-entries': '' },
        props.entries.map((entry: TaskWorkEntryConfig) => entry.name).join('、')
      )
  })
}))
const confirm = vi.hoisted(() => vi.fn())
vi.mock('ant-design-vue', () => ({ Modal: { confirm } }))
const capture = vi.hoisted(() => ({
  entries: [] as TaskWorkEntryConfig[],
  catalog: [] as TaskEntryCandidate[],
  application: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({ runtime: { application: capture.application } })
}))
vi.mock('@/views/nocode/task-center/TaskEntrySelector.vue', () => ({
  default: defineComponent({
    props: ['open', 'entries'],
    emits: ['confirm', 'catalog'],
    setup(props, { emit }) {
      watch(
        () => props.open,
        open => {
          if (open) emit('catalog', capture.catalog)
        }
      )
      return () =>
        props.open
          ? h('button', { onClick: () => emit('confirm', [...props.entries, ...capture.entries]) }, '批量确认')
          : null
    }
  })
}))
vi.mock('@/views/nocode/task-center/TaskEntryConfigDialog.vue', () => ({
  default: defineComponent({
    props: ['entry', 'sourceInfo', 'readonly'],
    emits: ['save'],
    setup:
      (props, { emit }) =>
      () =>
        props.entry
          ? h('section', { 'data-config-key': props.entry.key, 'data-readonly': !!props.readonly }, [
              h('p', JSON.stringify(props.sourceInfo)),
              h(
                'button',
                { onClick: () => emit('save', { ...props.entry, workRule: { mode: 'RECORD_ONCE', minutes: 30 } }) },
                '保存办理配置'
              )
            ])
          : null
  })
}))
vi.mock('@/views/nocode/task-center/TaskWorkAdjustmentDialog.vue', () => ({
  default: defineComponent({
    props: ['entry'],
    emits: ['save'],
    setup:
      (props, { emit }) =>
      () =>
        props.entry ? h('button', { onClick: () => emit('save', 5) }, '保存本次工时调整') : null
  })
}))
let app: App, host: HTMLElement
beforeEach(() => {
  confirm.mockReset()
  capture.application.mockReset().mockResolvedValue({
    application: { name: '房间管理' },
    definition: {
      resources: [
        { id: 'form', kind: 'FORM', name: '房间信息' },
        { id: 'view', kind: 'VIEW', name: 'Wi-Fi 配置' }
      ]
    }
  })
})
const flush = async () => {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const config = (key: string): TaskWorkEntryConfig => ({
  key,
  name: key,
  binding: { applicationId: 'app', viewId: 'view', formId: 'form', entryId: null },
  dataMode: 'INDEPENDENT',
  sourceNodeId: null,
  sourceEntryKey: null,
  readableFieldIds: ['field'],
  writableFieldIds: [],
  required: true,
  allowAll: false
})
async function mount(
  initial = [config('原办理项')],
  options: {
    readonly?: boolean
    workAdjustment?: boolean
    bindingLocked?: boolean
    legacyPolicy?: TaskDataPolicy
    budgetEntries?: TaskWorkEntryConfig[]
    showWorkTotal?: boolean
  } = {}
) {
  const entries = ref(initial)
  app = createApp(() =>
    h(TaskEntriesEditor, {
      modelValue: entries.value,
      unified: true,
      ...options,
      'onUpdate:modelValue': value => (entries.value = value)
    })
  )
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of ['ATag', 'ATooltip']) app.component(name, plain)
  app.component(
    'AInputNumber',
    defineComponent({
      props: ['value', 'disabled'],
      emits: ['update:value'],
      setup:
        (props, { emit, attrs }) =>
        () =>
          h('input', {
            ...attrs,
            type: 'number',
            value: props.value,
            disabled: props.disabled,
            onInput: (event: Event) =>
              emit(
                'update:value',
                (event.target as HTMLInputElement).value === ''
                  ? null
                  : Number((event.target as HTMLInputElement).value)
              )
          })
    })
  )
  app.component(
    'AButton',
    defineComponent({
      emits: ['click'],
      setup:
        (_, { slots, emit }) =>
        () =>
          h('button', { onClick: (event: MouseEvent) => emit('click', event) }, slots.default?.())
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  return entries
}
async function click(text: string) {
  Array.from(host.querySelectorAll('button'))
    .find(button => button.textContent?.includes(text))!
    .click()
  await flush()
}
async function clickCard(index = 0, selector = 'article') {
  const card = host.querySelectorAll<HTMLElement>(selector)[index]
  if (!card) throw new Error(`未找到卡片入口：${selector}[${index}]`)
  card.click()
  await flush()
}
afterEach(() => {
  app?.unmount()
  host?.remove()
  capture.entries = []
  capture.catalog = []
})
it('发起时锁定资源仍能只调整所选表单，不修改基准和其他表单', async () => {
  const first = { ...config('可调整'), workRule: { mode: 'RECORD_ONCE' as const, minutes: 15 } }
  const second = { ...config('保持不变'), workRule: { mode: 'RECORD_ONCE' as const, minutes: 30 } }
  const entries = await mount([first, second], { readonly: true, workAdjustment: true })
  await clickCard()
  expect(host.querySelector('[data-config-key]')).toBeNull()
  await click('保存本次工时调整')
  expect(entries.value[0]).toEqual({ ...first, workRule: { ...first.workRule, adjustmentMinutes: 5 } })
  expect(entries.value[1]).toEqual(second)
  expect(first.workRule.minutes).toBe(15)
  expect(host.textContent).toContain('20 分钟 / 条')
  expect(host.querySelector('.business-item__remove')).toBeNull()
})
it('整组预算可汇总下级旧关联，工时配置表仍只显示当前节点办理项', async () => {
  const current = config('总任务表单'),
    child = config('子任务表单')
  await mount([current], { showWorkTotal: true, budgetEntries: [current, child] })
  expect(host.querySelector('[data-budget-entries]')?.textContent).toBe('总任务表单、子任务表单')
  expect(host.querySelector('[data-work-table]')?.textContent).toContain('总任务表单')
  expect(host.querySelector('[data-work-table]')?.textContent).not.toContain('子任务表单')
  expect(host.querySelectorAll('article')).toHaveLength(1)
})
it('预计记录数统一放在表头，输入框下不再重复说明', async () => {
  await mount([{ ...config('施工日志'), workRule: { mode: 'RECORD_ONCE', minutes: 15, plannedQuantity: 2 } }])
  const table = host.querySelector('[data-work-table]')
  expect(table?.querySelector('header')?.textContent).toContain('预计记录数')
  expect(table?.textContent?.match(/预计记录数/g)).toHaveLength(1)
  expect(table?.textContent).not.toContain('预计工作量')
  expect(host.querySelector<HTMLInputElement>('[aria-label="施工日志预计记录数"]')?.value).toBe('2')
})
it.each([true, false])('发起时预计量可按本次安排修改，资源和规则始终锁定且不污染模板：readonly=%s', async readonly => {
  const original = {
    ...config('设备登记'),
    workRule: { mode: 'QUANTITY' as const, minutes: 15, quantityFieldId: 'count', plannedQuantity: 2 }
  }
  const other = { ...config('施工日志'), workRule: { mode: 'RECORD_ONCE' as const, minutes: 30, plannedQuantity: 1 } }
  const entries = await mount([original, other], { readonly, workAdjustment: true })
  const input = host.querySelector<HTMLInputElement>('[aria-label="设备登记预计业务数量"]')
  if (!input) throw new Error('预计量输入框不存在')
  expect(input.disabled).toBe(false)
  input.value = '3.5'
  input.dispatchEvent(new Event('input'))
  await flush()
  expect(entries.value[0]).toEqual({ ...original, workRule: { ...original.workRule, plannedQuantity: 3.5 } })
  expect(original.workRule.plannedQuantity).toBe(2)
  expect(entries.value[1]).toEqual(other)
  expect(host.querySelector('[aria-label^="移除关联："]')).toBeNull()
  expect(host.querySelector('[aria-label$="标准工时计算方式"]')).toBeNull()
  expect(Array.from(host.querySelectorAll('button')).some(button => button.textContent?.includes('选择业务视图'))).toBe(
    false
  )
})
it('已发布只读配置仍不能修改预计量', async () => {
  const original = {
    ...config('设备登记'),
    workRule: { mode: 'RECORD_ONCE' as const, minutes: 15, plannedQuantity: 2 }
  }
  const entries = await mount([original], { readonly: true })
  const input = host.querySelector<HTMLInputElement>('[aria-label="设备登记预计记录数"]')
  if (!input) throw new Error('预计量输入框不存在')
  expect(input.disabled).toBe(true)
  input.value = '4'
  input.dispatchEvent(new Event('input'))
  await flush()
  expect(entries.value[0]).toEqual(original)
})
it.each([-1, 0.5, 1000000])('发起时预计记录数也保持校验边界：%s', async quantity => {
  const original = {
    ...config('施工日志'),
    workRule: { mode: 'RECORD_ONCE' as const, minutes: 15, plannedQuantity: 2 }
  }
  const entries = await mount([original], { readonly: true, workAdjustment: true })
  const input = host.querySelector<HTMLInputElement>('[aria-label="施工日志预计记录数"]')
  if (!input) throw new Error('预计量输入框不存在')
  input.value = String(quantity)
  input.dispatchEvent(new Event('input'))
  await flush()
  expect(entries.value[0]).toEqual(original)
})
it('预计量0与未填写区分显示，不把清空转成零', async () => {
  const original = {
    ...config('施工日志'),
    workRule: { mode: 'RECORD_ONCE' as const, minutes: 15, plannedQuantity: 2 }
  }
  const entries = await mount([original], { readonly: true, workAdjustment: true })
  const input = host.querySelector<HTMLInputElement>('[aria-label="施工日志预计记录数"]')
  if (!input) throw new Error('预计量输入框不存在')
  input.value = '0'
  input.dispatchEvent(new Event('input'))
  await flush()
  expect(entries.value[0]?.workRule?.plannedQuantity).toBe(0)
  expect(host.querySelector('[data-work-table]')?.textContent).toContain('0 分钟')
  input.value = ''
  input.dispatchEvent(new Event('input'))
  await flush()
  expect(entries.value[0]?.workRule?.plannedQuantity).toBeNull()
  expect(host.querySelector('[data-work-table]')?.textContent).toContain('未设置')
  expect(original.workRule.plannedQuantity).toBe(2)
})
it.each([0, 0.000001, 2.123456])('数量模式允许0或最多6位小数：%s', async quantity => {
  const original = {
    ...config('设备登记'),
    workRule: { mode: 'QUANTITY' as const, quantityFieldId: 'count', minutes: 1, plannedQuantity: 2 }
  }
  const entries = await mount([original], { readonly: true, workAdjustment: true })
  const input = host.querySelector<HTMLInputElement>('[aria-label="设备登记预计业务数量"]')
  if (!input) throw new Error('预计量输入框不存在')
  input.value = String(quantity)
  input.dispatchEvent(new Event('input'))
  await flush()
  expect(entries.value[0]?.workRule?.plannedQuantity).toBe(quantity)
})
it('数量模式拒绝超过6位小数的注入值，不静默修改原预计量', async () => {
  const original = {
    ...config('设备登记'),
    workRule: { mode: 'QUANTITY' as const, quantityFieldId: 'count', minutes: 1, plannedQuantity: 2 }
  }
  const entries = await mount([original], { readonly: true, workAdjustment: true })
  const input = host.querySelector<HTMLInputElement>('[aria-label="设备登记预计业务数量"]')
  if (!input) throw new Error('预计量输入框不存在')
  input.value = '0.1234567'
  input.dispatchEvent(new Event('input'))
  await flush()
  expect(entries.value[0]).toEqual(original)
})
it('卡片展示视图和填写表单、不展示应用来源，保存后保留原权限和稳定key', async () => {
  const entries = await mount()
  expect(host.querySelectorAll('article')).toHaveLength(1)
  expect(host.querySelector('article')?.textContent).not.toContain('房间管理')
  expect(host.querySelector('article')?.textContent).toContain('Wi-Fi 配置')
  expect(host.querySelector('article')?.textContent).toContain('房间信息')
  expect(host.querySelector('.business-item__source')?.textContent).toContain('业务视图')
  expect(host.querySelector('.business-item__source')?.textContent).toContain('填写表单')
  await clickCard()
  expect(host.textContent).toContain('房间管理')
  expect(host.textContent).toContain('Wi-Fi 配置')
  expect(host.textContent).toContain('房间信息')
  await click('保存办理配置')
  expect(entries.value[0]).toMatchObject({
    key: '原办理项',
    readableFieldIds: ['field'],
    writableFieldIds: [],
    required: true,
    workRule: { mode: 'RECORD_ONCE', minutes: 30 }
  })
})
it.each([
  ['RECORD_ONCE', '条 · 每条固定工时'],
  ['QUANTITY', '单位 · 按数量计算'],
  ['CONDITION', '条 · 满足条件后计入']
] as const)('工时表集中展示计量规则，关联卡片不重复展示：%s', async (mode, summary) => {
  await mount([{ ...config('WiFi'), workRule: { mode, minutes: 15 } }])
  expect(host.querySelector('[data-work-table]')?.textContent).toContain(`15 分钟 / ${summary}`)
  expect(host.querySelector('article')?.textContent).not.toContain('15 分钟')
  expect(host.textContent).not.toContain('每项对应一个视图和办理表单')
})
it.each([true, false])('只有核实的不可用目录项才显示异常，不把待核对当错误：%s', async pendingVerification => {
  const entry = { ...config('WiFi'), workRule: { mode: 'RECORD_ONCE' as const, minutes: 15 } }
  capture.catalog = [
    {
      id: taskEntryIdentity(entry.binding)!,
      name: entry.name,
      binding: entry.binding!,
      applicationId: 'app',
      applicationName: '房间管理',
      source: 'VIEW',
      available: false,
      pendingVerification,
      status: pendingVerification ? '已选办理项；选择所属应用可核对' : '当前无权限或已下架；保留原配置'
    }
  ]
  await mount([entry])
  await click('选择业务视图')
  const warning = host.querySelector('article .business-item__warning')
  if (pendingVerification) expect(warning).toBeNull()
  else expect(warning?.textContent).toContain('当前无权限或已下架')
})
it('多选只给新增项应用默认共享设置，不覆盖原有高级共享字段配置', async () => {
  const entries = await mount()
  capture.entries = [config('新增办理项')]
  await click('选择业务视图')
  await click('批量确认')
  expect(entries.value).toHaveLength(2)
  expect(entries.value[0]?.dataMode).toBe('INDEPENDENT')
  expect(entries.value[0]?.writableFieldIds).toEqual([])
  expect(entries.value[1]?.dataMode).toBe('ROOT_SHARED')
  expect(entries.value[1]?.dataScope).toBe('GROUP')
  expect(entries.value[1]?.workRule).toBeNull()
  expect(entries.value[0]?.workRule).toBeUndefined()
})
it('每张卡片分别展示范围，去掉底部配置按钮，保留卡片标题键盘入口及X移除入口', async () => {
  await mount([
    { ...config('WiFi'), dataScope: 'ALL' },
    { ...config('设备'), dataScope: 'GROUP' }
  ])
  const cards = host.querySelectorAll('article')
  expect(cards[0]?.textContent).toContain('全部业务数据')
  expect(cards[1]?.textContent).toContain('仅本组任务数据')
  for (const card of Array.from(cards)) {
    expect(Array.from(card.querySelectorAll('button')).filter(button => button.textContent === '配置')).toHaveLength(0)
    expect(card.querySelector('button[aria-haspopup="dialog"]')).not.toBeNull()
    expect(card.querySelectorAll('button[aria-label^="移除关联："]')).toHaveLength(1)
    expect(card.textContent).not.toContain('更多')
  }
})
it.each(['article', '.business-item__source', '.business-item__footer', '.business-item__configure'])(
  '点击卡片不同区域只打开对应办理项：%s',
  async selector => {
    const entries = await mount([config('WiFi'), config('设备')])
    await clickCard(1, selector)
    expect(host.querySelector('[data-config-key]')?.getAttribute('data-config-key')).toBe('设备')
    expect(confirm).not.toHaveBeenCalled()
    await click('保存办理配置')
    expect(entries.value[0]?.workRule).toBeUndefined()
    expect(entries.value[1]?.workRule?.minutes).toBe(30)
  }
)
it('X先确认，只移除目标关联不影响其他项', async () => {
  const entries = await mount([config('WiFi'), config('设备')])
  host.querySelector<HTMLButtonElement>('[aria-label="移除关联：WiFi"]')!.click()
  await flush()
  expect(confirm).toHaveBeenCalledOnce()
  expect(host.querySelector('[data-config-key]')).toBeNull()
  expect(entries.value).toHaveLength(2)
  expect(confirm.mock.calls[0]![0].content).toContain('不删除已经存在的业务数据')
  await confirm.mock.calls[0]![0].onOk()
  await flush()
  expect(entries.value.map(entry => entry.key)).toEqual(['设备'])
  expect(host.querySelector('[data-config-key]')).toBeNull()
})
it('只读卡片没有X且仍能查看范围；旧范围读取不改写模型', async () => {
  const entries = await mount([config('__business')], {
    readonly: true,
    legacyPolicy: { version: 1, business: 'ALL', feedback: 'GROUP' }
  })
  expect(host.querySelector('[aria-label^="移除关联："]')).toBeNull()
  expect(host.querySelector('button[aria-label="查看配置：__business"]')).not.toBeNull()
  expect(host.textContent).toContain('全部业务数据')
  expect(entries.value[0]?.dataScope).toBeUndefined()
  await clickCard()
  expect(host.querySelector('[data-readonly="true"]')).not.toBeNull()
  await click('保存办理配置')
  expect(entries.value[0]?.workRule).toBeUndefined()
})
it('来源锁定的旧业务关联不能通过X移除', async () => {
  await mount([config('__business')], { bindingLocked: true })
  const button = host.querySelector<HTMLButtonElement>('[aria-label="移除关联：__business"]')!
  expect(button.disabled).toBe(true)
  button.click()
  await clickCard(0, '.business-item__remove')
  expect(confirm).not.toHaveBeenCalled()
  expect(host.querySelector('[data-config-key]')).toBeNull()
})
it.each(['missing', 'rejected'])('关联应用不可用时保留原卡片及配置并展示可理解提示：%s', async failure => {
  if (failure === 'missing') capture.application.mockResolvedValue({ application: { name: '房间管理' } })
  else capture.application.mockRejectedValue(new Error('应用不可访问'))
  const entries = await mount()
  expect(host.querySelectorAll('article')).toHaveLength(1)
  expect(host.textContent).toContain('关联应用暂不可用，已保留原配置')
  expect(entries.value).toEqual([config('原办理项')])
  await clickCard()
  await click('保存办理配置')
  expect(entries.value[0]?.binding?.viewId).toBe('view')
  expect(entries.value[0]?.writableFieldIds).toEqual([])
})

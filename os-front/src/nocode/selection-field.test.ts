// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, provide, computed, type App } from 'vue'
import type { SelectionOption, SelectionQuery } from '@/types/nocode/selection'
import SelectionField from '@/views/nocode/application/components/SelectionField.vue'
import { selectionValuesKey } from './selection'
import { fieldRuleNamesKey } from './field-rule-runtime'
import selectionFieldSource from '@/views/nocode/application/components/SelectionField.vue?raw'

const api = vi.hoisted(() => ({ selection: vi.fn(), fieldChange: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api, applications: {} }) }))
vi.mock('ant-design-vue', () => ({
  Form: { useInjectFormItemContext: () => ({ id: { value: 'subject' }, onFieldChange: api.fieldChange }) }
}))

const Select = defineComponent({
  props: ['value', 'options'],
  setup: props => () =>
    h(
      'select',
      { value: props.value },
      props.options.map((option: SelectionOption) =>
        h('option', { value: option.value, disabled: option.disabled }, option.label)
      )
    )
})
const hidden = defineComponent({ setup: () => () => null })
const option = (value: string, disabled = false): SelectionOption => ({
  value,
  label: value,
  code: value,
  path: null,
  parentValue: null,
  disabled,
  unavailable: false
})
const values = ref<Record<string, unknown>>({})
const selected = ref<string | string[] | null | undefined>()
const changed = vi.fn((value: string | string[] | null) => {
  selected.value = value
})
let app: App | undefined, host: HTMLDivElement
async function flush() {
  for (let index = 0; index < 12; index++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount(options: { readOnly?: boolean; creating?: boolean; props?: Record<string, unknown> } = {}) {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(() =>
    h(SelectionField, {
      applicationId: 'app',
      objectId: 'voucher',
      fieldId: 'subject',
      formId: 'voucher-form',
      presentation: { linkFieldId: 'subjectType', linkTargetFieldId: 'type' },
      modelValue: selected.value,
      readOnly: options.readOnly,
      creating: options.creating,
      'onUpdate:modelValue': changed,
      ...options.props
    })
  )
  app.provide(selectionValuesKey, values)
  app.provide(
    fieldRuleNamesKey,
    ref({ company: { name: '公司', detailId: null }, category: { name: '贷方科目分类', detailId: 'lines' } })
  )
  app.component('a-select', Select)
  for (const name of [
    'a-tree-select',
    'a-button',
    'a-modal',
    'a-input-search',
    'a-table',
    'a-typography-text',
    'a-alert',
    'a-tooltip'
  ])
    app.component(name, hidden)
  app.mount(host)
  await flush()
}
beforeEach(() => {
  vi.clearAllMocks()
  values.value = { subjectType: 'old-type' }
  selected.value = 'old-subject'
  api.selection.mockImplementation(async (query: SelectionQuery) => {
    const candidate = query.formValues?.subjectType === 'new-type' ? 'new-subject' : 'old-subject'
    return {
      options: [option(candidate)],
      selected: (query.selected || []).map(value => option(value, value !== candidate)),
      total: 1,
      tree: false,
      defaultValue: null
    }
  })
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
})

describe('linked selection reload', () => {
  it.each(['row', 'main'])('内部明细使用 %s 来源时保持本行隔离或向各行传播', async scope => {
    const main = ref<Record<string, unknown>>({ subjectType: 'old-type' })
    const rows = ref([
      { key: 'row-a', subjectType: 'old-type', subject: 'old-subject' as string | string[] | null },
      { key: 'row-b', subjectType: 'old-type', subject: 'old-subject' as string | string[] | null }
    ])
    const Row = defineComponent({
      props: ['row'],
      setup(props) {
        provide(
          selectionValuesKey,
          computed(() => (scope === 'main' ? main.value : props.row))
        )
        return () =>
          h(SelectionField, {
            applicationId: 'app',
            objectId: 'voucher',
            fieldId: 'subject',
            detailId: 'entries',
            detailRecordId: props.row.key,
            formId: 'voucher-form',
            presentation: { linkFieldId: 'subjectType', linkTargetFieldId: 'type' },
            modelValue: props.row.subject,
            'onUpdate:modelValue': value => {
              props.row.subject = value
            }
          })
      }
    })
    host = document.createElement('div')
    document.body.append(host)
    app = createApp(() => rows.value.map(row => h(Row, { key: row.key, row })))
    app.component('a-select', Select)
    for (const name of [
      'a-tree-select',
      'a-button',
      'a-modal',
      'a-input-search',
      'a-table',
      'a-typography-text',
      'a-alert',
      'a-tooltip'
    ])
      app.component(name, hidden)
    app.mount(host)
    await flush()
    api.selection.mockClear()
    if (scope === 'main') main.value.subjectType = 'new-type'
    else rows.value[0]!.subjectType = 'new-type'
    await flush()
    expect(rows.value.map(row => row.subject)).toEqual(scope === 'main' ? [null, null] : [null, 'old-subject'])
    expect(new Set(api.selection.mock.calls.map(([query]) => query.detailRecordId))).toEqual(
      new Set(scope === 'main' ? ['row-a', 'row-b'] : ['row-a'])
    )
    for (const [query] of api.selection.mock.calls)
      expect(query).toMatchObject({ detailId: 'entries', formValues: { subjectType: 'new-type' }, selected: [] })
    // 调整排序保留组件身份与当前输入，不重新清空另一行。
    rows.value.reverse()
    await flush()
    expect(rows.value.find(row => row.key === 'row-b')!.subject).toBe(scope === 'main' ? null : 'old-subject')
  })
  it('excludes the cleared value from requests and candidates when its source changes', async () => {
    await mount()
    expect(api.selection).toHaveBeenLastCalledWith(expect.objectContaining({ selected: ['old-subject'] }))
    api.selection.mockClear()
    values.value.subjectType = 'new-type'
    await flush()
    expect(changed).toHaveBeenCalledWith(null)
    expect(selected.value).toBeNull()
    expect(api.selection).toHaveBeenCalled()
    for (const [query] of api.selection.mock.calls)
      expect(query).toMatchObject({ selected: [], formValues: { subjectType: 'new-type' } })
    expect(Array.from(host.querySelectorAll('option')).map(element => element.value)).toEqual(['new-subject'])
  })

  it('retains the current selection for read-only display when its source changes', async () => {
    await mount({ readOnly: true })
    api.selection.mockClear()
    values.value.subjectType = 'new-type'
    await flush()
    expect(changed).not.toHaveBeenCalled()
    expect(selected.value).toBe('old-subject')
    expect(api.selection).toHaveBeenLastCalledWith(
      expect.objectContaining({ selected: ['old-subject'], formValues: { subjectType: 'new-type' } })
    )
    expect(host.textContent).toContain('old-subject')
  })

  it('keeps an omitted new value eligible for its default after the initial source arrives', async () => {
    values.value = {}
    selected.value = undefined
    api.selection.mockResolvedValue({
      options: [option('new-subject')],
      selected: [],
      total: 1,
      tree: false,
      defaultValue: 'new-subject'
    })
    await mount({ creating: true })
    expect(api.selection).not.toHaveBeenCalled()
    values.value.subjectType = 'new-type'
    await flush()
    expect(changed).not.toHaveBeenCalledWith(null)
    expect(selected.value).toBe('new-subject')
    expect(api.selection.mock.calls[0]?.[0]).toMatchObject({
      selected: [],
      formValues: { subjectType: 'new-type' }
    })
  })
})

describe('对象引用筛选（B36、B61）', () => {
  const ruleResult = (extra: Record<string, unknown> = {}) => ({
    options: [option('in-scope')],
    selected: [],
    total: 1,
    tree: false,
    defaultValue: null,
    ruleState: 'APPLIED',
    ...extra
  })
  const detailRow = (extra: Record<string, unknown> = {}) => ({
    presentation: undefined,
    detailId: 'lines',
    ruleDependsOn: ['category'],
    ruleMasterDependsOn: ['company'],
    ...extra
  })

  it('B36：候选只来自服务端，选择器不调用列表分页接口做本地筛选', () => {
    expect(selectionFieldSource).not.toMatch(/\.page\s*\(/)
    expect(selectionFieldSource).not.toMatch(/runtime\.page|api\.page/)
  })

  it('formValues 携带本行与主表依赖值（全局字段 ID）', async () => {
    values.value = { company: 'C1', category: 'A', unrelated: 'x' }
    api.selection.mockResolvedValue(ruleResult())
    await mount({ props: detailRow() })
    expect(api.selection).toHaveBeenLastCalledWith(
      expect.objectContaining({ detailId: 'lines', formValues: { category: 'A', company: 'C1' } })
    )
  })

  it('detailLazyReload：主表依赖变化只标记过期，获得焦点时才请求', async () => {
    values.value = { company: 'C1', category: 'A' }
    api.selection.mockResolvedValue(ruleResult())
    await mount({ props: detailRow() })
    api.selection.mockClear()
    values.value.company = 'C2'
    await flush()
    expect(api.selection).not.toHaveBeenCalled()
    expect(changed).not.toHaveBeenCalled()
    host.querySelector('select')!.dispatchEvent(new Event('focus'))
    await flush()
    expect(api.selection).toHaveBeenCalledTimes(1)
    expect(api.selection).toHaveBeenLastCalledWith(
      expect.objectContaining({ formValues: { category: 'A', company: 'C2' } })
    )
    host.querySelector('select')!.dispatchEvent(new Event('focus'))
    await flush()
    expect(api.selection).toHaveBeenCalledTimes(1)
  })

  it('本行依赖变化立即重新加载，且不清空已选值', async () => {
    values.value = { company: 'C1', category: 'A' }
    api.selection.mockResolvedValue(ruleResult())
    await mount({ props: detailRow() })
    api.selection.mockClear()
    values.value.category = 'B'
    await flush()
    expect(api.selection).toHaveBeenCalledTimes(1)
    expect(changed).not.toHaveBeenCalled()
  })

  it('主表字段上的引用筛选：依赖变化立即重新加载', async () => {
    values.value = { company: 'C1' }
    api.selection.mockResolvedValue(ruleResult())
    await mount({ props: { presentation: undefined, ruleDependsOn: ['company'], ruleMasterDependsOn: [] } })
    api.selection.mockClear()
    values.value.company = 'C2'
    await flush()
    expect(api.selection).toHaveBeenCalledTimes(1)
  })

  it('PENDING：下拉禁用并提示「请先填写主表「X」」', async () => {
    values.value = { category: 'A' }
    api.selection.mockResolvedValue(
      ruleResult({ options: [], total: 0, ruleState: 'PENDING_ROW_VALUE', pendingFields: ['company'] })
    )
    await mount({ props: detailRow() })
    expect(host.querySelector('select')!.disabled).toBe(true)
    expect(host.textContent).toContain('请先填写主表「公司」')
    expect(host.textContent).not.toContain('候选 0 条')
  })

  // 业务方 2026-10-04：引用筛选里的固定值存了名称，候选静默为空、不知道改哪里。
  it('筛选配置有误：说明原因与去哪里改，不再静默为空', async () => {
    values.value = { company: 'C1', category: 'A' }
    api.selection.mockResolvedValue(
      ruleResult({
        options: [],
        total: 0,
        ruleState: 'CONDITION_UNSUPPORTED',
        ruleMessage: '配置有误：条件「管理状态」的固定值「民宿管理」不是「管理状态」里的记录，请到对象设计里重新选择'
      })
    )
    await mount({ props: detailRow() })
    expect(host.querySelector('[role="alert"]')?.textContent).toBe(
      '引用筛选配置有误：条件「管理状态」的固定值「民宿管理」不是「管理状态」里的记录，请到对象设计里重新选择'
    )
    expect(host.textContent).not.toContain('候选 0 条')
  })

  it('其余求不了值的原因接「无法求值」；PENDING 与 APPLIED 不出这一行', async () => {
    values.value = { company: 'C1', category: 'A' }
    api.selection.mockResolvedValue(
      ruleResult({
        options: [],
        total: 0,
        ruleState: 'SOURCE_NOT_READABLE',
        ruleMessage: '当前用户无权按来源字段「公司」查询'
      })
    )
    await mount({ props: detailRow() })
    expect(host.querySelector('[role="alert"]')?.textContent).toBe(
      '引用筛选无法求值：当前用户无权按来源字段「公司」查询'
    )
    app?.unmount()
    host.remove()
    api.selection.mockResolvedValue(ruleResult({ ruleMessage: '不该显示' }))
    await mount({ props: detailRow() })
    expect(host.querySelector('[role="alert"]')).toBeNull()
  })

  it('APPLIED 且 0 条：显示「筛选已生效 · 符合条件的候选 0 条」', async () => {
    values.value = { company: 'C1', category: 'A' }
    api.selection.mockResolvedValue(ruleResult({ options: [], total: 0 }))
    await mount({ props: detailRow() })
    expect(host.querySelector('select')!.disabled).toBe(false)
    expect(host.textContent).toContain('筛选已生效 · 符合条件的候选 0 条')
    expect(host.textContent).not.toContain('不符合当前筛选')
  })

  it('批量结果 inScope=false：已选值标注「不符合当前筛选」；未给出时不标注', async () => {
    values.value = { company: 'C2', category: 'A' }
    api.selection.mockImplementation(async (query: SelectionQuery) =>
      ruleResult({ selected: (query.selected || []).map(value => option(value)) })
    )
    await mount({ props: detailRow({ ruleOutOfScope: true }) })
    expect(host.textContent).toContain('已选值不符合当前筛选')
    expect(host.textContent).toContain('old-subject（不符合当前筛选）')
  })
})

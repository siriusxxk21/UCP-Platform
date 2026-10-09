// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref, type App, type ComponentPublicInstance } from 'vue'
import Antd from 'ant-design-vue'
import CalculationEditor from '@/views/nocode/components/CalculationEditor.vue'
import { FieldType, RelationType } from '@/types/nocode/enums'
import type { CalculationOptions, ObjectRelation } from '@/types/nocode/data-center'
import type { ObjectField } from '@/types/nocode/object'
import { newField } from './object-draft'

const api = vi.hoisted(() => ({ objects: vi.fn(), design: vi.fn(), version: vi.fn() }))
vi.mock('@/api/nocode/data-center', () => ({ createDataCenterApi: () => api }))
vi.mock('@/utils/request', () => ({ default: {} }))

const fields: ObjectField[] = Object.values(FieldType).map((type, index) => ({
  ...newField(index, type),
  key: type,
  id: type,
  code: type.toLowerCase(),
  type
}))
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))
function required<T>(value: T | null | undefined): T {
  if (value == null) throw new Error('缺少预期的组件或控件')
  return value
}
const legacy = (mode: 'LOCAL' | 'RELATION' | 'LOOKUP'): CalculationOptions => ({
  mode,
  updateMode: 'LIVE',
  targetObjectId: null,
  relationId: null,
  targetField: null,
  aggregate: 'SINGLE',
  logic: 'AND',
  conditions: [],
  excludeCurrent: false
})
const running = (): CalculationOptions & { runningTotal: NonNullable<CalculationOptions['runningTotal']> } => ({
  ...legacy('LOOKUP'),
  mode: 'RUNNING_TOTAL',
  aggregate: 'SUM',
  targetField: 'money',
  groupFields: [],
  runningTotal: {
    orderField: 'datetime',
    tieBreakerField: null,
    subtractField: null,
    initialValue: '0',
    initialField: null
  }
})
interface State {
  category: string
  sequenceVariant: string
  changeCategory: (category: 'FORMULA' | 'LOOKUP' | 'AGGREGATE' | 'SEQUENCE') => void
  changeSource: (mode: string) => void
  changeSequenceVariant: (variant: string) => void
  changeMode: (mode: string) => void
  changeAggregate: (aggregate: CalculationOptions['aggregate']) => void
  changeGroups: (codes: string[]) => void
  changeInitialSource: (source: string) => void
  patchRunning: (value: Partial<NonNullable<CalculationOptions['runningTotal']>>) => void
  patchSequence: (value: Partial<NonNullable<CalculationOptions['sequence']>>) => void
  patch: (value: Partial<CalculationOptions>) => void
  validate: () => string
  numericFields: ObjectField[]
  orderFields: ObjectField[]
  groupFields: ObjectField[]
  valueFields: ObjectField[]
  targets: ObjectField[]
  effectiveTarget: string | null
  groupOptions: { value: string; disabled: boolean }[]
  aggregateOptions: { value: string }[]
}
const mounted: { app: App; host: HTMLElement }[] = []
async function flush() {
  for (let i = 0; i < 6; i++) await nextTick()
}
async function mount(value: CalculationOptions | null = null, disabled = false, relations: ObjectRelation[] = []) {
  const model = ref(value)
  const editor = ref<ComponentPublicInstance>()
  const resultType = vi.fn()
  const updated = vi.fn()
  const app = createApp({
    setup: () => () =>
      h(CalculationEditor, {
        ref: editor,
        modelValue: model.value,
        fields,
        relations,
        disabled,
        'onUpdate:modelValue': (next: CalculationOptions | null | undefined) => {
          updated(next)
          model.value = next ?? null
        },
        onResultType: resultType
      })
  })
  app.use(Antd)
  const host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  mounted.push({ app, host })
  await flush()
  const state = (required(editor.value).$ as unknown as { setupState: State }).setupState
  return { host, state, model, resultType, updated }
}
function item(host: HTMLElement, label: string) {
  const found = Array.from(host.querySelectorAll<HTMLElement>('.ant-form-item')).find(
    node => node.querySelector('.ant-form-item-label')?.textContent === label
  )
  if (!found) throw new Error(`缺少表单项：${label}`)
  return found
}
async function select(host: HTMLElement, label: string, option: string, index = 0) {
  const selector = item(host, label).querySelectorAll('.ant-select-selector')[index]
  if (!selector) throw new Error(`缺少选择控件：${label}`)
  selector.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
  await flush()
  const dropdown = Array.from(document.querySelectorAll('.ant-select-dropdown'))
    .filter(node => !node.classList.contains('ant-select-dropdown-hidden'))
    .at(-1)
  const choice = Array.from(dropdown?.querySelectorAll<HTMLElement>('.ant-select-item-option') ?? []).find(
    node => node.textContent === option
  )
  if (!choice) throw new Error(`缺少选项：${option}`)
  choice.click()
  await flush()
}

beforeEach(() => {
  vi.clearAllMocks()
  api.objects.mockResolvedValue({ list: [], total: 0 })
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      matches: false,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn()
    }))
  )
})
afterEach(() => {
  for (const { app, host } of mounted.splice(0)) {
    app.unmount()
    host.remove()
  }
  document.querySelectorAll('.ant-select-dropdown').forEach(node => node.remove())
  vi.unstubAllGlobals()
})

describe('全表统计与累计余额配置', () => {
  it('选择同一个一级入口、来源或旧相邻子方式不重置旧 JSON', async () => {
    const input: CalculationOptions = {
      ...legacy('LOCAL'),
      mode: 'SEQUENCE',
      aggregate: 'SUM',
      groupFields: ['text'],
      sequence: { orderField: 'datetime', tieBreakerField: 'text', direction: 'NEXT' }
    }
    const { state, model, updated, resultType } = await mount(input)
    state.changeCategory('SEQUENCE')
    state.changeSequenceVariant('ADJACENT')
    state.changeMode('SEQUENCE')
    await flush()
    expect(model.value).toEqual(input)
    expect(updated).not.toHaveBeenCalled()
    expect(resultType).not.toHaveBeenCalled()
    expect(model.value?.sequence).not.toHaveProperty('operation')
  })
  it('查找和汇总仅显式切换聚合方式，来源、匹配条件和保存时更新保持', async () => {
    const input: CalculationOptions = {
      ...legacy('LOOKUP'),
      updateMode: 'ON_SAVE',
      targetField: 'money',
      conditions: [{ targetField: 'text', operator: 'eq', localField: null, value: 'A' }]
    }
    const { state, model, updated } = await mount(input)
    state.changeCategory('LOOKUP')
    state.changeSource('LOOKUP')
    await flush()
    expect(updated).not.toHaveBeenCalled()
    state.changeCategory('AGGREGATE')
    await flush()
    expect(model.value).toEqual({ ...input, aggregate: 'SUM' })
    state.changeCategory('LOOKUP')
    await flush()
    expect(model.value).toEqual(input)
    state.changeSource('RELATION')
    await flush()
    expect(model.value).toEqual({ ...legacy('RELATION'), updateMode: 'ON_SAVE' })
    state.changeCategory('AGGREGATE')
    await flush()
    state.changeSource('STATISTICS')
    await flush()
    expect(model.value).toMatchObject({ mode: 'STATISTICS', aggregate: 'SUM', updateMode: 'ON_SAVE' })
  })
  it('本对象汇总切换查找保留有效条件，旧增减累计显式切换逐笔累计正确写入子方式', async () => {
    const input: CalculationOptions = {
      ...running(),
      mode: 'STATISTICS',
      runningTotal: null,
      conditions: [{ targetField: 'text', operator: 'eq', localField: null, value: 'A' }]
    }
    const { state, model } = await mount(input)
    state.changeCategory('LOOKUP')
    await flush()
    expect(model.value).toMatchObject({
      mode: 'LOOKUP',
      aggregate: 'SINGLE',
      conditions: input.conditions,
      targetField: 'money'
    })
    state.changeMode('RUNNING_TOTAL')
    await flush()
    state.changeSequenceVariant('CUMULATIVE')
    await flush()
    expect(model.value).toMatchObject({
      mode: 'SEQUENCE',
      sequence: { operation: 'CUMULATIVE', direction: 'PREVIOUS' }
    })
  })
  it('顺序模式兼容旧相邻规则，切换累计后期初保留精度并保留旧 LIVE', async () => {
    const { state, model, host } = await mount({
      ...legacy('LOCAL'),
      mode: 'SEQUENCE',
      aggregate: 'SUM',
      sequence: { orderField: 'datetime', tieBreakerField: null, direction: 'PREVIOUS' }
    })
    expect(state.validate()).toBe('')
    expect(item(host, '更新方式').querySelectorAll('input:disabled')).toHaveLength(0)
    expect(model.value?.updateMode).toBe('LIVE')
    expect(host.textContent).toContain('相邻记录取值')
    state.patchSequence({ operation: 'CUMULATIVE', initialValue: '900719925474099312345.12' })
    await flush()
    expect(model.value?.sequence?.initialValue).toBe('900719925474099312345.12')
    expect(host.textContent).toContain('本行基础字段及本行公式')
    expect(host.textContent).not.toContain('组内第一条的上一条')
    expect(state.validate()).toBe('')
    state.patchSequence({ direction: 'NEXT' })
    await flush()
    expect(state.validate()).toContain('首笔累计')
    state.patchSequence({ direction: 'PREVIOUS', initialValue: 'abc' })
    await flush()
    expect(state.validate()).toContain('有效数字')
  })
  it('从公式入口初始化全表统计，默认 SUM、无条件、整表并保留本行条件能力', async () => {
    const { host, model, state, resultType } = await mount()
    await select(host, '计算方式', '汇总统计')
    expect(model.value).toEqual({
      ...legacy('LOOKUP'),
      mode: 'STATISTICS',
      aggregate: 'SUM',
      groupFields: [],
      runningTotal: null
    })
    expect(state.aggregateOptions.map(option => option.value)).toEqual(['COUNT', 'SUM', 'AVG', 'MIN', 'MAX'])
    expect(resultType).toHaveBeenLastCalledWith('DECIMAL')
    expect(host.textContent).not.toContain('来源对象')
    expect(host.textContent).toContain('不设条件即全表统计')
    expect(host.textContent).toContain('无 500 条截断')
    expect(host.textContent).toContain('仍需来源字段的计算取数授权，不改变应用成员权限')
    state.patch({ targetField: 'money' })
    await flush()
    expect(state.validate()).toBe('')
    state.patch({ conditions: [{ targetField: 'money', operator: 'gt', localField: 'decimal', value: null }] })
    await flush()
    expect(state.validate()).toBe('')
    expect(item(host, '匹配条件').querySelectorAll('.ant-select')).toHaveLength(4)
    await select(host, '结果处理', '记录数')
    expect(model.value?.targetField).toBeNull()
    expect(resultType).toHaveBeenLastCalledWith('INTEGER')
    expect(host.textContent).not.toContain('取值字段')
    expect(state.validate()).toBe('')
  })

  it('新建累计默认 ON_SAVE + SUM，允许显式 LIVE，配置实际控件可写入金额与排序字段', async () => {
    const { host, state, model } = await mount()
    await select(host, '计算方式', '顺序计算')
    expect(model.value).toMatchObject({ mode: 'SEQUENCE', updateMode: 'ON_SAVE' })
    await select(host, '顺序计算方式', '增减值累计')
    expect(model.value).toEqual({
      ...running(),
      updateMode: 'ON_SAVE',
      targetField: null,
      runningTotal: { ...running().runningTotal, orderField: '' }
    })
    expect(item(host, '更新方式').querySelectorAll('input:disabled')).toHaveLength(0)
    expect(item(host, '结果处理').querySelector('.ant-select-disabled')).not.toBeNull()
    expect(host.textContent).not.toContain('排除本记录')
    expect(host.textContent).toContain('分页和界面排序不影响累计结果')
    expect(host.textContent).toContain('同组受影响结果在同一事务中更新')
    expect(host.textContent).toContain('每组初始值只加一次')
    expect(host.textContent).toContain('读取时计算的结果不能参与筛选、排序和报表')
    required(item(host, '更新方式').querySelector<HTMLInputElement>('input[value="LIVE"]')).click()
    await flush()
    expect(model.value?.updateMode).toBe('LIVE')
    expect(host.textContent).toContain('不写入结果列')
    expect(state.validate()).toContain('增加值')
    await select(host, '增加值字段', 'MONEY（money）')
    expect(state.validate()).toContain('累计顺序')
    await select(host, '累计顺序字段', 'DATETIME（datetime）')
    await select(host, '减少值字段', 'DECIMAL（decimal）')
    await select(host, '同序字段', 'TEXT（text）')
    expect(model.value?.runningTotal).toMatchObject({
      orderField: 'datetime',
      subtractField: 'decimal',
      tieBreakerField: 'text'
    })
    const input = required(item(host, '固定初始值').querySelector('input'))
    input.value = '900719925474099312345.123456789'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    input.dispatchEvent(new Event('change', { bubbles: true }))
    input.dispatchEvent(new Event('blur', { bubbles: true }))
    await flush()
    expect(model.value?.runningTotal?.initialValue).toBe('900719925474099312345.123456789')
    expect(state.validate()).toBe('')
  })

  it('切换模式完整清理配置，旧三模式 JSON 不追加新属性，GENERATED 仍为 null', async () => {
    const { state, model } = await mount(running())
    for (const next of ['LOCAL', 'RELATION', 'LOOKUP'] as const) {
      state.changeMode('RUNNING_TOTAL')
      await flush()
      state.patch({
        conditions: [{ targetField: 'money', operator: 'eq', localField: null, value: '1' }],
        groupFields: ['text']
      })
      state.patchRunning({ initialValue: '123.45', subtractField: 'decimal' })
      await flush()
      state.changeMode(next)
      await flush()
      expect(model.value).toEqual(legacy(next))
    }
    state.patch({ targetObjectId: 'remote', relationId: 'relation', updateMode: 'ON_SAVE', excludeCurrent: true })
    state.changeMode('STATISTICS')
    await flush()
    expect(model.value).toEqual({
      ...legacy('LOOKUP'),
      mode: 'STATISTICS',
      aggregate: 'SUM',
      groupFields: [],
      runningTotal: null
    })
    state.changeMode('RUNNING_TOTAL')
    await flush()
    expect(model.value?.runningTotal).toEqual({ ...running().runningTotal, orderField: '' })
    state.changeMode('GENERATED')
    await flush()
    expect(model.value).toBeNull()
  })

  it('候选仅允许约定基础类型，分组最多五个且不能重复或包含多值关系', async () => {
    const { state, model } = await mount(running(), false, [
      {
        id: 'many',
        code: 'many',
        name: '多值引用',
        kind: RelationType.MANY_TO_MANY,
        targetObjectId: 'other',
        fieldId: 'REFERENCE',
        targetFieldId: null,
        required: false,
        onDelete: 'RESTRICT'
      }
    ])
    expect(state.numericFields.map(field => field.type)).toEqual(['INTEGER', 'DECIMAL', 'MONEY', 'PERCENT'])
    expect(state.orderFields.map(field => field.type)).toEqual([
      'INTEGER',
      'DECIMAL',
      'DATE',
      'DATETIME',
      'MONEY',
      'PERCENT',
      'TIME'
    ])
    expect(state.groupFields.map(field => field.type)).toEqual([
      'TEXT',
      'TEXTAREA',
      'INTEGER',
      'DECIMAL',
      'BOOLEAN',
      'DATE',
      'DATETIME',
      'MONEY',
      'PERCENT',
      'TIME',
      'SELECT',
      'ORGANIZATION',
      'USER',
      'DEPARTMENT',
      'POST',
      'USER_GROUP',
      'AUTO_NUMBER',
      'UUID'
    ])
    expect(state.valueFields).toEqual(state.numericFields)
    const codes = ['text', 'select', 'boolean', 'user', 'uuid']
    state.changeGroups(codes)
    await flush()
    expect(model.value?.groupFields).toEqual(codes)
    expect(state.groupOptions.find(option => option.value === 'money')?.disabled).toBe(true)
    expect(state.groupOptions.find(option => option.value === 'text')?.disabled).toBe(false)
    for (const invalid of [[...codes, 'money'], ['text', 'text'], ['formula'], ['multi_select'], ['reference']]) {
      state.changeGroups(invalid)
      await flush()
      expect(model.value?.groupFields).toEqual(codes)
    }
    state.changeGroups([])
    await flush()
    expect(model.value?.groupFields).toEqual([])
    expect(state.validate()).toBe('')
  })

  it('期初来源通过真实单选控件切换且互斥，切回固定金额重新设为字符串 0', async () => {
    const { host, state, model } = await mount(running())
    required(item(host, '初始值来源').querySelector<HTMLInputElement>('input[value="FIELD"]')).click()
    await flush()
    expect(model.value?.runningTotal).toMatchObject({ initialField: '', initialValue: null })
    expect(state.validate()).toContain('首条初始值字段')
    await select(host, '首条初始值字段', 'MONEY（money）')
    expect(model.value?.runningTotal).toMatchObject({ initialField: 'money', initialValue: null })
    expect(state.validate()).toBe('')
    required(item(host, '初始值来源').querySelector<HTMLInputElement>('input[value="FIXED"]')).click()
    await flush()
    expect(model.value?.runningTotal).toMatchObject({ initialField: null, initialValue: '0' })
    expect(state.validate()).toBe('')
  })

  it('累计条件只允许固定值，未匹配结果为空；新增布尔条件可保存 false', async () => {
    const { state, host, model } = await mount(running())
    required(
      Array.from(host.querySelectorAll('button')).find(button => button.textContent?.includes('添加条件'))
    ).click()
    await flush()
    expect(model.value?.conditions).toEqual([{ targetField: '', operator: 'eq', localField: null, value: null }])
    expect(item(host, '匹配条件').querySelectorAll('.ant-select')).toHaveLength(3)
    expect(host.textContent).toContain('未匹配条件的记录结果为空')
    state.patch({ conditions: [{ targetField: 'boolean', operator: 'eq', localField: null, value: null }] })
    await flush()
    await select(host, '匹配条件', '否', 3)
    expect(model.value?.conditions[0]?.value).toBe(false)
    expect(state.validate()).toBe('')
    state.patch({ conditions: [{ targetField: 'money', operator: 'gt', localField: 'decimal', value: null }] })
    await flush()
    expect(state.validate()).toContain('仅支持固定值')
  })

  it.each(['STATISTICS', 'RUNNING_TOTAL'] as const)('只读 %s 显示原配置且任何编辑入口不更新模型', async mode => {
    const input: CalculationOptions = { ...running(), mode }
    if (mode === 'STATISTICS') input.runningTotal = null
    const original = clone(input)
    const { state, host, model, updated, resultType } = await mount(input, true)
    expect(updated).not.toHaveBeenCalled()
    expect(host.querySelectorAll('input:not(:disabled), button:not(:disabled)')).toHaveLength(0)
    state.changeMode('LOCAL')
    state.changeGroups(['text'])
    state.changeAggregate('COUNT')
    state.changeInitialSource('FIELD')
    state.patchRunning({ initialValue: '9' })
    state.patch({ targetField: 'decimal' })
    await flush()
    expect(model.value).toEqual(original)
    expect(updated).not.toHaveBeenCalled()
    expect(resultType).not.toHaveBeenCalled()
  })

  it('重新打开保存配置不重置高精度期初及分组，不加载远程来源字段', async () => {
    const value = running()
    value.groupFields = ['reference', 'user']
    value.runningTotal = {
      ...value.runningTotal,
      tieBreakerField: 'auto_number',
      initialValue: '999999999999999999.99'
    }
    const { state, model, updated, host } = await mount(value)
    expect(model.value).toEqual(value)
    expect(updated).not.toHaveBeenCalled()
    expect(state.effectiveTarget).toBeNull()
    expect(state.targets).toEqual(fields)
    expect(api.design).not.toHaveBeenCalled()
    expect(api.version).not.toHaveBeenCalled()
    expect(item(host, '固定初始值').querySelector('input')?.value).toBe('999999999999999999.99')
    expect(state.validate()).toBe('')
  })

  it('保存前拒绝非法来源、类型、条件、顺序和期初配置，但不校验旧模式', async () => {
    const { state, model } = await mount(running())
    const cases: [Partial<CalculationOptions>, string][] = [
      [{ targetObjectId: 'other' }, '仅支持当前对象'],
      [{ relationId: 'other' }, '仅支持当前对象'],
      [{ targetField: 'formula' }, '基本数值'],
      [{ groupFields: ['text', 'text'] }, '不能重复'],
      [{ groupFields: ['multi_select'] }, '基本单值'],
      [{ aggregate: 'SINGLE' }, '不支持唯一取值'],
      [{ excludeCurrent: true }, '包含本记录'],
      [{ runningTotal: null }, '累计顺序'],
      [{ runningTotal: { ...running().runningTotal, orderField: 'text' } }, '累计顺序'],
      [{ runningTotal: { ...running().runningTotal, tieBreakerField: 'formula' } }, '同序字段'],
      [{ runningTotal: { ...running().runningTotal, tieBreakerField: 'boolean' } }, '同序字段'],
      [{ runningTotal: { ...running().runningTotal, tieBreakerField: 'reference' } }, '同序字段'],
      [{ runningTotal: { ...running().runningTotal, subtractField: 'summary' } }, '基本数值'],
      [{ runningTotal: { ...running().runningTotal, initialField: 'money', initialValue: '1' } }, '只能选择一种'],
      [{ runningTotal: { ...running().runningTotal, initialValue: null } }, '有效数字'],
      [{ runningTotal: { ...running().runningTotal, initialValue: 'NaN' } }, '有效数字'],
      [{ conditions: [{ targetField: 'formula', operator: 'eq', localField: null, value: '0' }] }, '来源字段'],
      [{ conditions: [{ targetField: 'money', operator: 'eq', localField: null, value: null }] }, '固定值']
    ]
    for (const [patch, message] of cases) {
      model.value = { ...running(), ...patch }
      await flush()
      expect(state.validate()).toContain(message)
    }
    state.changeMode('LOCAL')
    await flush()
    expect(state.validate()).toBe('')
  })
})

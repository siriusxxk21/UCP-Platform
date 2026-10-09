// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, ref, type App, type Component, type ComponentPublicInstance } from 'vue'
import Antd from 'ant-design-vue'
import FieldDesigner from '@/views/nocode/components/FieldDesigner.vue'
import { FieldType, RelationType } from '@/types/nocode/enums'
import type { FieldOptions, ObjectRelation } from '@/types/nocode/data-center'
import type { FieldRules, RuleCondition } from '@/types/nocode/field-rules'
import type { ObjectField } from '@/types/nocode/object'
import { defaultFieldOptions } from './data-center'
import { conditionsError, fieldRulesError } from './field-rules'
import { newField } from './object-draft'

/**
 * 这次上线的主线（业务方 2026-10-01）：「本身我们做这个修改就是用来让引用筛选里的比较方式可以有不等于，空白和非空白的」。
 * 整条配置链路从对象字段抽屉走：引用字段 → 「选项」区的引用筛选 → 条件的比较方式 等于 / 不等于 / 为空 / 不为空，
 * 条件字段是单选时固定值是选项下拉（显示名称、保存编码）→ 确定后写回 rules.reference.filter。
 * 三种形状的引用字段都要能配：关系生成的整数列（主表）、明细里的关系生成列、「对象引用」类型字段（单选对象引用）。
 * 数据联动弹层的条件行是同一个组件，同样核一遍。
 */
const api = vi.hoisted(() => ({ objects: vi.fn(), design: vi.fn(), version: vi.fn() }))
vi.mock('@/api/nocode/data-center', () => ({ createDataCenterApi: () => api }))
vi.mock('@/utils/request', () => ({ default: { get: vi.fn(async () => []), post: vi.fn() } }))
vi.mock('@/api/system/organization', () => ({ getOrganizationTree: vi.fn(async () => []) }))
vi.mock('@/api/system/department', () => ({ getDepartmentTree: vi.fn(async () => []) }))
vi.mock('@/components/os-table-page/OsTablePage.vue', () => ({ default: { render: () => null } }))
vi.mock('@/components/os-modal-form/OsModalForm.vue', async () => {
  const { defineComponent, h: render } = await import('vue')
  return {
    default: defineComponent({
      props: { open: Boolean },
      setup:
        (props, { slots }) =>
        () =>
          props.open ? render('div', { class: 'drawer-stub' }, slots.formItems?.()) : null
    })
  }
})
vi.mock('@/views/nocode/components/FieldValueEditor.vue', () => ({ default: { render: () => null } }))

const mounted: { app: App; host: HTMLElement }[] = []
async function flush() {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function field(type: string, id: string, name: string, patch: Partial<ObjectField> = {}): ObjectField {
  return { ...newField(0, name), key: id, id, code: id, type: type as ObjectField['type'], ...patch }
}
const options = (patch: Partial<FieldOptions> = {}): FieldOptions => ({ ...defaultFieldOptions(), ...patch })
const relation = (patch: Partial<ObjectRelation> = {}): ObjectRelation => ({
  id: 'rel-1',
  code: 'c_zjls',
  name: '资金流水',
  kind: RelationType.REFERENCE,
  targetObjectId: 'cash',
  fieldId: '24383',
  targetFieldId: null,
  required: false,
  onDelete: 'RESTRICT',
  ...patch
})
const company = () => field(FieldType.TEXT, 'company', '公司')

/** 被引用对象「资金流水」：带一个单选字段「凭证状态」（库里存编码 ylr / wlr）和一个文本字段。 */
const cashDefinition = () => ({
  fields: [field(FieldType.SELECT, 'status', '凭证状态'), field(FieldType.TEXT, 'memo', '备注')],
  fieldOptions: {
    status: options({
      options: [
        { code: 'ylr', label: '已录入', disabled: false },
        { code: 'wlr', label: '未录入', disabled: false }
      ]
    })
  },
  relations: [],
  settings: { titleTemplate: '{流水号}' }
})
/** 数据联动的来源对象「银行回单」：有一个指向资金流水的引用列（可作取值字段）和同样的单选字段。 */
const receiptDefinition = () => ({
  fields: [
    field(FieldType.INTEGER, 'cash_ref', '对应流水'),
    field(FieldType.SELECT, 'status', '核对状态'),
    field(FieldType.TEXT, 'memo', '摘要')
  ],
  fieldOptions: {
    cash_ref: options({ generated: true }),
    status: options({
      options: [
        { code: 'ok', label: '已核对', disabled: false },
        { code: 'no', label: '未核对', disabled: false }
      ]
    })
  },
  relations: [relation({ id: 'rel-r', code: 'cash_ref', name: '对应流水', fieldId: 'cash_ref' })],
  settings: { titleTemplate: null }
})

interface Shape {
  name: string
  target: () => ObjectField
  options: (rules?: FieldRules) => FieldOptions
  props: () => Record<string, unknown>
}
const withRules = (base: Partial<FieldOptions>, rules?: FieldRules) => options({ ...base, ...(rules ? { rules } : {}) })
const shapes: Shape[] = [
  {
    name: '关系生成的整数列（主表）',
    target: () => field(FieldType.INTEGER, '24383', '资金流水', { code: 'c_zjls_id' }),
    options: rules => withRules({ generated: true, columnName: 'c_zjls_id', nativeType: 'bigint' }, rules),
    props: () => ({ relations: [relation()], objectId: 'voucher' })
  },
  {
    name: '明细里的关系生成列',
    target: () => field(FieldType.INTEGER, '24383', '资金流水', { code: 'c_zjls_id' }),
    options: rules => withRules({ generated: true, columnName: 'c_zjls_id', nativeType: 'bigint' }, rules),
    props: () => ({
      relations: [relation({ sourceDetailId: 'd1' })],
      detail: true,
      master: { fields: [field(FieldType.TEXT, 'm1', '凭证公司')], relations: [] },
      objectId: 'voucher'
    })
  },
  {
    name: '「对象引用」类型字段（单选对象引用）',
    target: () => field(FieldType.REFERENCE, '24383', '资金流水', { code: 'c_zjls', length: null }),
    options: rules => withRules({ columnName: 'c_zjls', nativeType: 'bigint' }, rules),
    // 对象编辑器对这类字段会传转换预检等入参：照实际接线挂上。
    props: () => ({
      relations: [relation()],
      objectId: 'voucher',
      previewSwitch: vi.fn(async () => ({ decision: 'COMPATIBLE', deploymentState: 'DEPLOYED', impacts: [] })),
      applyConversion: vi.fn(() => null),
      relationTargets: [{ label: '资金流水', value: 'cash' }],
      loadRelationTargets: vi.fn(async () => {})
    })
  }
]

async function openDrawer(shape: Shape, rules?: FieldRules) {
  const target = shape.target()
  const instance = ref<ComponentPublicInstance>()
  const events = { options: vi.fn(), fields: vi.fn() }
  const props = reactive<Record<string, unknown>>({
    modelValue: [company(), target],
    options: { company: options(), [target.key]: shape.options(rules) },
    ...shape.props()
  })
  const app = createApp({
    setup: () => () =>
      h(FieldDesigner as Component, {
        ref: instance,
        ...props,
        'onUpdate:options': events.options,
        'onUpdate:modelValue': events.fields
      })
  })
  app.use(Antd)
  const host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  mounted.push({ app, host })
  await flush()
  const state = (instance.value!.$ as unknown as { setupState: Record<string, any> }).setupState
  state.show(target)
  await flush()
  const drawer = host.querySelector<HTMLElement>('.drawer-stub')
  if (!drawer) throw new Error('字段抽屉没有打开')
  const saved = () => {
    const call = events.options.mock.calls.at(-1)
    if (!call) throw new Error('确定后没有写回字段配置')
    return (call[0] as Record<string, FieldOptions>)[target.key]!
  }
  return { drawer, state, events, target, saved }
}
function formItem(root: ParentNode, label: string) {
  const found = Array.from(root.querySelectorAll<HTMLElement>('.ant-form-item')).find(
    node => node.querySelector('.ant-form-item-label')?.textContent?.trim() === label
  )
  if (!found) throw new Error(`缺少表单项：${label}`)
  return found
}
function button(root: ParentNode, text: string) {
  // 两个汉字的按钮会被组件库在字间插入空格，比较前去掉空白。
  const found = Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find(
    node => node.textContent?.replace(/\s/g, '') === text
  )
  if (!found) throw new Error(`缺少按钮：${text}`)
  return found
}
/* ── 条件行里的下拉：来源字段、比较方式、值来源、固定值 / 当前字段 ── */
const selects = (root: ParentNode) => Array.from(root.querySelectorAll<HTMLElement>('.rule-row > .ant-select'))
const selectLabels = (root: ParentNode) =>
  selects(root).map(node => node.querySelector('.ant-select-selection-item')?.textContent?.trim() ?? '')
const dropdowns = new WeakMap<Element, Element>()
async function openSelect(root: ParentNode, position: number) {
  const select = selects(root)[position]
  const selector = select?.querySelector('.ant-select-selector')
  if (!select || !selector) throw new Error(`条件行缺少第 ${position + 1} 个下拉`)
  const before = new Set(Array.from(document.querySelectorAll('.ant-select-dropdown')))
  selector.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
  await flush()
  const created = Array.from(document.querySelectorAll('.ant-select-dropdown')).filter(node => !before.has(node))
  if (created.length > 1) throw new Error('一次打开出现了多个下拉弹层')
  const dropdown = created[0] ?? dropdowns.get(select)
  if (!dropdown) throw new Error(`第 ${position + 1} 个下拉未打开`)
  dropdowns.set(select, dropdown)
  return Array.from(dropdown.querySelectorAll<HTMLElement>('.ant-select-item-option'))
}
async function pick(root: ParentNode, position: number, label: string) {
  const option = (await openSelect(root, position)).find(node => node.textContent === label)
  if (!option) throw new Error(`第 ${position + 1} 个下拉里没有「${label}」`)
  option.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await flush()
}
const optionTexts = async (root: ParentNode, position: number) =>
  (await openSelect(root, position)).map(node => node.textContent)

beforeEach(() => {
  vi.clearAllMocks()
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
  api.objects.mockResolvedValue({
    list: [
      { id: 'cash', objectName: '资金流水', objectCode: 'zjls', publishedVersion: 1, category: '财务' },
      { id: 'receipt', objectName: '银行回单', objectCode: 'yhhd', publishedVersion: 1, category: '财务' }
    ],
    total: 2
  })
  api.design.mockImplementation(async (id: string) => ({
    publishedVersion: 1,
    draft:
      id === 'receipt' ? { objectName: '银行回单', objectCode: 'yhhd' } : { objectName: '资金流水', objectCode: 'zjls' }
  }))
  api.version.mockImplementation(async (id: string) => ({
    definition: id === 'receipt' ? receiptDefinition() : cashDefinition()
  }))
})
afterEach(async () => {
  await new Promise(resolve => setTimeout(resolve, 100))
  for (const { app, host } of mounted.splice(0)) {
    app.unmount()
    host.remove()
  }
  document.querySelectorAll('.ant-modal-root, .ant-select-dropdown').forEach(node => node.remove())
  vi.unstubAllGlobals()
})

describe.each(shapes)('引用筛选的比较方式 · $name', shape => {
  it('抽屉里有可操作的「引用筛选」；添加条件后，单选条件字段的比较方式是「等于、不等于、为空、不为空」', async () => {
    const { drawer, state } = await openDrawer(shape)
    const filter = formItem(drawer, '引用筛选')
    expect(filter.textContent).toContain('未设置筛选：候选为目标对象中当前用户可见的全部记录。')
    const add = button(filter, '添加条件')
    expect(add.disabled).toBe(false)
    add.click()
    await flush()
    expect(filter.querySelectorAll('.rule-row')).toHaveLength(1)
    expect(await optionTexts(filter, 0)).toEqual(['凭证状态', '备注'])
    await pick(filter, 0, '凭证状态')
    expect(await optionTexts(filter, 1)).toEqual(['等于', '不等于', '为空', '不为空'])
    expect(state.option.rules?.reference?.filter).toHaveLength(1)
  })

  it('单选条件字段的固定值是选项下拉：显示名称、写出编码；「不等于」同样带编码', async () => {
    const { drawer, state, saved, events } = await openDrawer(shape, {
      reference: { filter: [{ fieldId: 'status', operator: 'eq', valueSource: 'CONSTANT', value: null }] }
    })
    const filter = formItem(drawer, '引用筛选')
    // 固定值不是文本框：是该字段选项的下拉。
    expect(filter.querySelector('.rule-row .ant-input')).toBeNull()
    expect(await optionTexts(filter, 3)).toEqual(['已录入', '未录入'])
    await pick(filter, 3, '已录入')
    expect(selectLabels(filter)).toEqual(['凭证状态', '等于', '固定值', '已录入'])
    expect(state.option.rules.reference.filter).toEqual([
      { fieldId: 'status', operator: 'eq', valueSource: 'CONSTANT', value: 'ylr' }
    ])
    await pick(filter, 1, '不等于')
    await pick(filter, 3, '未录入')
    const expected: RuleCondition[] = [{ fieldId: 'status', operator: 'neq', valueSource: 'CONSTANT', value: 'wlr' }]
    expect(state.option.rules.reference.filter).toEqual(expected)
    state.save()
    await flush()
    expect(state.error).toBe('')
    expect(state.open).toBe(false)
    expect(saved().rules).toEqual({ reference: { labelFieldId: null, filter: expected } })
    // 只维护规则：字段本身（名称、类型、必填）不回写。
    expect(events.fields).not.toHaveBeenCalled()
  })

  it('选「为空 / 不为空」后取值控件消失，写出的条件不带值；确定后的形状与后端校验一致', async () => {
    const { drawer, state, saved } = await openDrawer(shape, {
      reference: {
        labelFieldId: 'memo',
        filter: [{ fieldId: 'status', operator: 'eq', valueSource: 'FORM_FIELD', formFieldId: 'company' }]
      }
    })
    const filter = formItem(drawer, '引用筛选')
    expect(selects(filter)).toHaveLength(4)
    await pick(filter, 1, '为空')
    // 只剩「来源字段」「比较方式」两个下拉：没有值来源、固定值或当前字段。
    expect(selectLabels(filter)).toEqual(['凭证状态', '为空'])
    expect(filter.querySelector('.rule-row .ant-input')).toBeNull()
    const blank = state.option.rules.reference.filter as RuleCondition[]
    expect(blank).toEqual([{ fieldId: 'status', operator: 'isNull', valueSource: 'CONSTANT', value: null }])
    expect(blank[0]).not.toHaveProperty('formFieldId')
    await pick(filter, 1, '不为空')
    expect(selects(filter)).toHaveLength(2)
    state.save()
    await flush()
    expect(state.error).toBe('')
    const rules = saved().rules!
    // 后端 FieldRuleValidator.conditionShape：为空 / 不为空的值来源固定为 CONSTANT，value 与 formFieldId 均为空。
    expect(rules).toEqual({
      reference: {
        labelFieldId: 'memo',
        filter: [{ fieldId: 'status', operator: 'notNull', valueSource: 'CONSTANT', value: null }]
      }
    })
    expect(conditionsError(rules.reference!.filter)).toBeNull()
    expect(fieldRulesError(saved())).toBeNull()
  })

  it('条件没填完整时确定被拦住并说明原因，不写回', async () => {
    const { drawer, state, events } = await openDrawer(shape, {
      reference: { filter: [{ fieldId: 'status', operator: 'isNull', valueSource: 'CONSTANT', value: null }] }
    })
    const filter = formItem(drawer, '引用筛选')
    await pick(filter, 1, '不等于')
    expect(selects(filter)).toHaveLength(4)
    state.save()
    await flush()
    expect(state.error).toContain('引用筛选配置不完整')
    expect(state.open).toBe(true)
    expect(events.options).not.toHaveBeenCalled()
  })
})

describe.each(shapes)('数据联动弹层的条件行 · $name', shape => {
  const linkage = (): NonNullable<FieldRules['linkage']> => ({
    sourceObjectId: 'receipt',
    conditions: [{ fieldId: 'status', operator: 'eq', valueSource: 'CONSTANT', value: 'ok' }],
    valueFieldId: 'cash_ref',
    multiRow: 'FIRST',
    readOnly: true
  })

  it('抽屉里能打开数据联动设置；条件的比较方式同样四项，选「不为空」后确定写回', async () => {
    const { drawer, state, saved } = await openDrawer(shape, { linkage: linkage() })
    expect(drawer.querySelector('.value-source-switch .ant-switch-checked')).not.toBeNull()
    const open = button(drawer, '数据联动设置')
    expect(open.disabled).toBe(false)
    open.click()
    await flush()
    const modal = document.querySelector<HTMLElement>('.ant-modal')
    if (!modal) throw new Error('数据联动弹层没有打开')
    expect(selectLabels(modal)).toEqual(['核对状态', '等于', '固定值', '已核对'])
    expect(await optionTexts(modal, 1)).toEqual(['等于', '不等于', '为空', '不为空'])
    await pick(modal, 1, '不为空')
    expect(selects(modal)).toHaveLength(2)
    button(modal, '确定').click()
    await flush()
    const expected = {
      ...linkage(),
      conditions: [{ fieldId: 'status', operator: 'notNull', valueSource: 'CONSTANT', value: null }]
    }
    expect(state.option.rules.linkage).toEqual(expected)
    state.save()
    await flush()
    expect(state.error).toBe('')
    expect(saved().rules).toEqual({ linkage: expected })
  })
})

// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, ref, type App, type Component, type ComponentPublicInstance } from 'vue'
import Antd from 'ant-design-vue'
import FieldDesigner from '@/views/nocode/components/FieldDesigner.vue'
import { FieldType, RelationType } from '@/types/nocode/enums'
import type { FieldOptions, FieldSwitchPreview, ObjectRelation } from '@/types/nocode/data-center'
import type { FieldRules } from '@/types/nocode/field-rules'
import type { ObjectField } from '@/types/nocode/object'
import { defaultFieldOptions } from './data-center'
import { newField } from './object-draft'

/**
 * 业务方 2026-10-01（合并版上线当晚）：对象「会计凭证录入」的字段「资金流水」（c_zjls_id，类型显示「整数」，
 * 由对象关系生成的引用列）打开字段抽屉后，「选项」区整块变成置灰的「默认值：自定义 / 数据联动 / 公式编辑｜未设置」，
 * 「单选（对象引用）」提示条不见了——「是没有联动数据的筛选功能了。」
 *
 * 引用列不一定是「对象引用」类型：关系生成的列、映射到关系上的已有列，按目标主键落成整数 / 文本 / UUID 列。
 * 抽屉认关系要看「这一列是不是关系的引用列」，不能只看字段类型。以前的用例都拿「对象引用」类型当生成列，没有覆盖到。
 */
const api = vi.hoisted(() => ({ objects: vi.fn(), design: vi.fn(), version: vi.fn() }))
vi.mock('@/api/nocode/data-center', () => ({ createDataCenterApi: () => api }))
vi.mock('@/utils/request', () => ({ default: { get: vi.fn(async () => []), post: vi.fn() } }))
vi.mock('@/api/system/organization', () => ({ getOrganizationTree: vi.fn(async () => []) }))
vi.mock('@/api/system/department', () => ({ getDepartmentTree: vi.fn(async () => []) }))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({ default: { render: () => null } }))
vi.mock('@/components/ucp-modal-form/OsModalForm.vue', async () => {
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
vi.mock('@/views/nocode/components/FieldValueEditor.vue', async () => {
  const { defineComponent, h: render } = await import('vue')
  return {
    default: defineComponent({
      props: { preview: Boolean, displayOnly: Boolean },
      setup: props => () =>
        render('div', { 'data-role': props.preview ? 'preview' : props.displayOnly ? 'display' : 'default-editor' })
    })
  }
})

const PROTECTED_HINT = '此字段由系统或对象关系自动维护，请从对应关系配置入口调整。'
const RELATION_HINT = '候选来自关联对象，显示记录名称。字段类型和目标对象可在上方调整。'
const EMPTY_FILTER = '未设置筛选：候选为目标对象中当前用户可见的全部记录。'

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
/** 关系生成的引用列：线上实际形态是整数列 + generated，不是「对象引用」类型。 */
const generatedColumn = (type: string = FieldType.INTEGER) =>
  field(type, '24383', '资金流水', { code: 'c_zjls_id', length: type === FieldType.TEXT ? 64 : null })
const generatedOptions = (rules?: FieldRules | null) =>
  options({ generated: true, columnName: 'c_zjls_id', nativeType: 'bigint', ...(rules ? { rules } : {}) })
const company = () => field(FieldType.TEXT, 'company', '公司')
const savedRules = (): FieldRules => ({
  reference: {
    labelFieldId: 'bank',
    filter: [{ fieldId: 'bank', operator: 'eq', valueSource: 'FORM_FIELD', formFieldId: 'company' }]
  },
  linkage: {
    sourceObjectId: 'cash',
    conditions: [{ fieldId: 'bank', operator: 'eq', valueSource: 'FORM_FIELD', formFieldId: 'company' }],
    valueFieldId: 'amount',
    multiRow: 'FIRST',
    readOnly: true
  }
})

async function mount(props: Record<string, unknown>) {
  const instance = ref<ComponentPublicInstance>()
  const events = { relation: vi.fn(), focused: vi.fn(), options: vi.fn(), fields: vi.fn() }
  const reactiveProps = reactive({ ...props })
  const app = createApp({
    setup: () => () =>
      h(FieldDesigner as Component, {
        ref: instance,
        ...reactiveProps,
        onRelation: events.relation,
        onRelationFocused: events.focused,
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
  return { host, state, events, props: reactiveProps }
}
const labels = (root: ParentNode) =>
  Array.from(root.querySelectorAll('.ant-form-item-label')).map(node => node.textContent?.trim())
function formItem(root: ParentNode, label: string) {
  const found = Array.from(root.querySelectorAll<HTMLElement>('.ant-form-item')).find(
    node => node.querySelector('.ant-form-item-label')?.textContent?.trim() === label
  )
  if (!found) throw new Error(`缺少表单项：${label}`)
  return found
}
function button(root: ParentNode, text: string) {
  // 两个汉字的按钮会被组件库在字间插入空格（「移 除」），比较前去掉空白。
  const found = Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find(
    node => node.textContent?.replace(/\s/g, '') === text
  )
  if (!found) throw new Error(`缺少按钮：${text}`)
  return found
}
async function openDrawer(
  target: ObjectField,
  props: Record<string, unknown>
): Promise<Awaited<ReturnType<typeof mount>> & { drawer: HTMLElement }> {
  const context = await mount(props)
  context.state.show(target)
  await flush()
  const drawer = context.host.querySelector<HTMLElement>('.drawer-stub')
  if (!drawer) throw new Error('字段抽屉没有打开')
  return { ...context, drawer }
}
/** 业务方给的「应该长这样」：提示条、约束置灰、选项区三块都在且能操作。 */
function expectReferenceDrawer(drawer: HTMLElement) {
  // ② 关系提示条与进入对象关系的入口
  const notice = Array.from(drawer.querySelectorAll('.ant-alert')).find(node =>
    node.textContent?.includes(RELATION_HINT)
  )
  expect(notice?.querySelector('.ant-alert-message')?.textContent).toBe('单选（对象引用）')
  expect(button(notice!, '配置关系详细规则').disabled).toBe(false)
  // ③ 必填 / 唯一由对象关系决定，置灰
  const constraints = Array.from(formItem(drawer, '约束').querySelectorAll<HTMLInputElement>('input[type="checkbox"]'))
  expect(constraints).toHaveLength(2)
  expect(constraints.every(input => input.disabled)).toBe(true)
  // ④ 「选项」区：显示名字段、引用筛选、数据联动（可选），不是默认值块
  expect(labels(drawer)).toEqual(expect.arrayContaining(['选项', '显示名字段', '引用筛选']))
  for (const absent of ['默认值', '最小值', '最大值', '最大长度', '正则校验'])
    expect(labels(drawer)).not.toContain(absent)
  expect(drawer.querySelector('[data-role="default-editor"]')).toBeNull()
  expect(drawer.textContent).not.toContain('公式编辑')
  const label = formItem(drawer, '显示名字段')
  expect(label.querySelector('.ant-select')!.classList.contains('ant-select-disabled')).toBe(false)
  const filter = formItem(drawer, '引用筛选')
  expect(button(filter, '添加条件').disabled).toBe(false)
  expect(filter.textContent).toContain('依赖的当前字段没有值时，候选为空并提示先填写')
  expect(filter.textContent).toContain('条件之间只有「且」')
  const linkage = drawer.querySelector('.value-source-switch')!
  expect(linkage.textContent).toContain('数据联动（可选）')
  expect(linkage.querySelector('.ant-switch')!.classList.contains('ant-switch-disabled')).toBe(false)
}

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
    list: [{ id: 'cash', objectName: '资金流水', objectCode: 'zjls', publishedVersion: 1, category: '财务' }],
    total: 1
  })
  api.design.mockResolvedValue({ publishedVersion: 1, draft: { objectName: '资金流水', objectCode: 'zjls' } })
  api.version.mockResolvedValue({
    definition: {
      fields: [field(FieldType.TEXT, 'bank', '开户行'), field(FieldType.DECIMAL, 'amount', '金额')],
      fieldOptions: {},
      relations: [],
      settings: { titleTemplate: '{流水号}' }
    }
  })
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

describe('关系生成的引用列（整数存储）的字段抽屉', () => {
  it.each([FieldType.INTEGER, FieldType.TEXT, FieldType.UUID])(
    '%s 存储：提示条、约束置灰、「选项」区的显示名字段 / 引用筛选 / 数据联动都在且可操作，不出现默认值块',
    async type => {
      const target = generatedColumn(type)
      const { drawer, state } = await openDrawer(target, {
        modelValue: [company(), target],
        options: { company: options(), [target.key]: generatedOptions() },
        relations: [relation()]
      })
      // ① 字段类型下方的结构保护说明保留
      expect(formItem(drawer, '字段类型').textContent).toContain(PROTECTED_HINT)
      expect(state.currentRelation).toEqual(expect.objectContaining({ code: 'c_zjls' }))
      expectReferenceDrawer(drawer)
      const label = formItem(drawer, '显示名字段')
      expect(label.querySelector('.ant-select-selection-placeholder')?.textContent).toBe(
        '沿用目标对象标题模板：{流水号}'
      )
      expect(formItem(drawer, '引用筛选').textContent).toContain(EMPTY_FILTER)
    }
  )

  it('能操作：加一条引用筛选、打开数据联动后出现「数据联动设置」', async () => {
    const target = generatedColumn()
    const { drawer, state } = await openDrawer(target, {
      modelValue: [company(), target],
      options: { company: options(), [target.key]: generatedOptions() },
      relations: [relation()]
    })
    button(formItem(drawer, '引用筛选'), '添加条件').click()
    await flush()
    expect(state.option.rules?.reference?.filter).toHaveLength(1)
    expect(formItem(drawer, '引用筛选').textContent).not.toContain(EMPTY_FILTER)
    expect(drawer.querySelectorAll('.rule-row')).toHaveLength(1)
    drawer.querySelector<HTMLButtonElement>('.value-source-switch .ant-switch')!.click()
    await flush()
    expect(button(drawer, '数据联动设置').disabled).toBe(false)
  })

  it('「配置关系详细规则」以抽屉来源进入对象关系，抽屉保持打开', async () => {
    const target = generatedColumn()
    const { drawer, state, events } = await openDrawer(target, {
      modelValue: [target],
      options: { [target.key]: generatedOptions() },
      relations: [relation()]
    })
    button(drawer, '配置关系详细规则').click()
    await flush()
    expect(events.relation).toHaveBeenCalledWith(expect.objectContaining({ key: '24383' }), 'drawer')
    expect(state.open).toBe(true)
  })

  it('已配的显示名字段、引用筛选、数据联动照原样回显；不改直接确定，规则原样写回，字段本身不回写', async () => {
    const target = generatedColumn()
    const rules = savedRules()
    const { drawer, state, events } = await openDrawer(target, {
      modelValue: [company(), target],
      options: { company: options(), [target.key]: generatedOptions(rules) },
      relations: [relation()]
    })
    expectReferenceDrawer(drawer)
    expect(formItem(drawer, '显示名字段').querySelector('.ant-select-selection-item')?.textContent).toBe('开户行')
    expect(formItem(drawer, '引用筛选').querySelectorAll('.rule-row')).toHaveLength(1)
    expect(drawer.querySelector('.value-source-switch .ant-switch-checked')).not.toBeNull()
    expect(button(drawer, '数据联动设置').disabled).toBe(false)
    state.save()
    await flush()
    expect(state.error).toBe('')
    expect(state.open).toBe(false)
    expect(events.fields).not.toHaveBeenCalled()
    expect(events.options).toHaveBeenCalledTimes(1)
    const saved = (events.options.mock.calls[0]![0] as Record<string, FieldOptions>)[target.key]!
    expect(saved.rules).toEqual(rules)
    expect(saved).toEqual(expect.objectContaining({ generated: true, columnName: 'c_zjls_id', nativeType: 'bigint' }))
  })

  it('打开后什么都不改直接确定：已配的显示名字段与引用筛选不会被当成普通整数列清掉', async () => {
    const target = generatedColumn()
    const rules = savedRules()
    const { state, events } = await openDrawer(target, {
      modelValue: [company(), target],
      options: { company: options(), [target.key]: generatedOptions(rules) },
      relations: [relation()]
    })
    state.save()
    await flush()
    const saved = (events.options.mock.calls[0]![0] as Record<string, FieldOptions>)[target.key]!
    expect(saved.rules?.reference).toEqual(rules.reference)
    expect(saved.rules?.linkage).toEqual(rules.linkage)
  })

  it('移除引用筛选条件并关掉数据联动后确定：rules.reference 只剩显示名字段，rules.linkage 清掉', async () => {
    const target = generatedColumn()
    const { drawer, state, events } = await openDrawer(target, {
      modelValue: [company(), target],
      options: { company: options(), [target.key]: generatedOptions(savedRules()) },
      relations: [relation()]
    })
    button(formItem(drawer, '引用筛选'), '移除').click()
    await flush()
    drawer.querySelector<HTMLButtonElement>('.value-source-switch .ant-switch')!.click()
    await flush()
    state.save()
    await flush()
    expect(state.error).toBe('')
    const saved = (events.options.mock.calls[0]![0] as Record<string, FieldOptions>)[target.key]!
    expect(saved.rules).toEqual({ reference: { labelFieldId: 'bank', filter: [] } })
  })

  it('对象关系保存后回到抽屉：整数存储的生成列同样打开，显示名字段与引用筛选可配', async () => {
    const pick = field(FieldType.SELECT, 'pick', '资金流水', { id: null })
    const { host, state, events, props } = await mount({
      modelValue: [pick],
      options: { pick: options() },
      relations: []
    })
    // 模拟对象编辑器保存成功后回填：选择字段被替换为关系生成的整数引用列。
    const target = generatedColumn()
    props.modelValue = [target]
    props.options = { [target.key]: generatedOptions() }
    props.relations = [relation()]
    props.focusRelation = { code: 'c_zjls', seq: 1 }
    await flush()
    expect(state.open).toBe(true)
    expect(state.key).toBe('24383')
    expect(events.focused).toHaveBeenCalledTimes(1)
    expectReferenceDrawer(host.querySelector<HTMLElement>('.drawer-stub')!)
  })

  it('只读查看：同样显示关系提示条与引用配置，只是都不可改', async () => {
    const target = generatedColumn()
    const { drawer } = await openDrawer(target, {
      modelValue: [target],
      options: { [target.key]: generatedOptions(savedRules()) },
      relations: [relation()],
      readOnly: true
    })
    expect(drawer.textContent).toContain(RELATION_HINT)
    expect(labels(drawer)).toEqual(expect.arrayContaining(['选项', '显示名字段', '引用筛选']))
    expect(labels(drawer)).not.toContain('默认值')
    expect(formItem(drawer, '显示名字段').querySelector('.ant-select')!.classList.contains('ant-select-disabled')).toBe(
      true
    )
    expect(button(formItem(drawer, '引用筛选'), '添加条件').disabled).toBe(true)
  })

  it('主从关系的父键只开放显示名字段，不出现引用筛选与数据联动', async () => {
    const target = generatedColumn()
    const { drawer } = await openDrawer(target, {
      modelValue: [target],
      options: { [target.key]: generatedOptions() },
      relations: [relation({ kind: RelationType.MASTER_DETAIL, required: true })]
    })
    expect(labels(drawer)).toContain('显示名字段')
    expect(labels(drawer)).not.toContain('引用筛选')
    expect(labels(drawer)).not.toContain('默认值')
    expect(drawer.querySelector('.value-source-switch')).toBeNull()
  })
})

describe('明细里的关系生成列', () => {
  it('抽屉同样显示引用配置，「当前字段」分「本行 / 主表」两组；确定后规则写回', async () => {
    const target = generatedColumn()
    const rules = savedRules()
    const { drawer, state, events } = await openDrawer(target, {
      modelValue: [company(), target],
      options: { company: options(), [target.key]: generatedOptions(rules) },
      relations: [relation({ sourceDetailId: 'd1' })],
      detail: true,
      master: { fields: [field(FieldType.TEXT, 'm1', '凭证公司')], relations: [] }
    })
    expectReferenceDrawer(drawer)
    const groups = state.valueSourceSection.formGroups as { label: string; options: { label: string }[] }[]
    expect(groups.map(group => group.label)).toEqual(['本行', '主表'])
    expect(groups.flatMap(group => group.options.map(option => option.label))).toEqual([
      '本行 · 公司',
      '主表 · 凭证公司'
    ])
    state.save()
    await flush()
    expect(state.error).toBe('')
    expect(events.fields).not.toHaveBeenCalled()
    const saved = (events.options.mock.calls[0]![0] as Record<string, FieldOptions>)[target.key]!
    expect(saved.rules).toEqual(rules)
  })
})

describe('映射到对象关系上的已有整数列（不是关系生成的）', () => {
  it('抽屉按引用列显示配置；确定后引用规则保留，不被当成普通整数列清掉', async () => {
    const target = field(FieldType.INTEGER, '500', '资金流水', { code: 'cash_ref' })
    const rules = savedRules()
    const { drawer, state, events } = await openDrawer(target, {
      modelValue: [company(), target],
      options: { company: options(), [target.key]: options({ columnName: 'cash_ref', rules }) },
      relations: [relation({ fieldId: '500' })]
    })
    expectReferenceDrawer(drawer)
    state.save()
    await flush()
    expect(state.error).toBe('')
    const saved = (events.options.mock.calls[0]![0] as Record<string, FieldOptions>)[target.key]!
    expect(saved.rules).toEqual(rules)
    expect(saved.defaultValue ?? null).toBeNull()
  })
})

describe('显式单值引用字段：字段类型转换流程不受影响', () => {
  const preview: FieldSwitchPreview = {
    decision: 'COMPATIBLE',
    deploymentState: 'DEPLOYED',
    impacts: [],
    totalRows: 0,
    valueRows: 0,
    failedRows: 0
  } as unknown as FieldSwitchPreview
  const explicit = () => field(FieldType.REFERENCE, '600', '客户', { code: 'customer' })
  const mountExplicit = (extra: Record<string, unknown> = {}) =>
    openDrawer(explicit(), {
      modelValue: [explicit()],
      options: { '600': options({ columnName: 'customer' }) },
      relations: [relation({ id: 'rel-9', code: 'customer', name: '客户', fieldId: '600', targetObjectId: 'cash' })],
      previewSwitch: vi.fn(async () => preview),
      relationTargets: [{ label: '资金流水', value: 'cash' }],
      loadRelationTargets: vi.fn(async () => {}),
      ...extra
    })

  it('未改类型时：关系提示条与引用配置在，字段类型可改', async () => {
    const { drawer, state } = await mountExplicit()
    expect(state.currentRelation).toEqual(expect.objectContaining({ code: 'customer' }))
    expect(drawer.textContent).toContain(RELATION_HINT)
    expect(labels(drawer)).toEqual(expect.arrayContaining(['从哪份资料选择', '显示名字段', '引用筛选']))
    expect(formItem(drawer, '字段类型').querySelector('.ant-select')!.classList.contains('ant-select-disabled')).toBe(
      false
    )
    expect(formItem(drawer, '字段类型').textContent).toContain('选择其他类型会解除当前单值对象关系')
  })

  it('在抽屉里改成文本（解除引用）：关系不再适用，换成文本列的配置；取消复核后恢复为引用', async () => {
    const { drawer, state } = await mountExplicit()
    await state.changeType(FieldType.TEXT)
    await flush()
    expect(state.field.type).toBe(FieldType.TEXT)
    expect(state.switchReviewOpen).toBe(true)
    expect(state.currentRelation).toBeUndefined()
    expect(state.protectedField).toBe(false)
    expect(drawer.textContent).not.toContain(RELATION_HINT)
    expect(labels(drawer)).toEqual(expect.arrayContaining(['最大长度', '默认值', '正则校验']))
    expect(labels(drawer)).not.toContain('显示名字段')
    state.cancelSwitchReview()
    await flush()
    expect(state.field.type).toBe(FieldType.REFERENCE)
    expect(state.currentRelation).toEqual(expect.objectContaining({ code: 'customer' }))
    expect(labels(drawer)).toContain('显示名字段')
  })

  it('只改数据分类后确定：分类随规则一起写回，不走转换应用', async () => {
    const applyConversion = vi.fn(() => null)
    const rules: FieldRules = { reference: { labelFieldId: 'bank', filter: [] } }
    const { state, events } = await mountExplicit({
      options: { '600': options({ columnName: 'customer', rules }) },
      applyConversion
    })
    state.option.classification = 'SENSITIVE'
    state.save()
    await flush()
    expect(state.error).toBe('')
    expect(state.open).toBe(false)
    expect(applyConversion).not.toHaveBeenCalled()
    const saved = (events.options.mock.calls[0]![0] as Record<string, FieldOptions>)['600']!
    expect(saved.classification).toBe('SENSITIVE')
    expect(saved.rules).toEqual(rules)
  })

  it('已保存单选在抽屉里原地转为对象引用：进入转换复核，关系要到确定后才建立', async () => {
    const pick = field(FieldType.SELECT, '700', '客户', { code: 'customer' })
    const { drawer, state, events } = await openDrawer(pick, {
      modelValue: [pick],
      options: { '700': options({ columnName: 'customer' }) },
      relations: [],
      previewSwitch: vi.fn(async () => preview),
      relationTargets: [{ label: '资金流水', value: 'cash' }],
      loadRelationTargets: vi.fn(async () => {})
    })
    state.configureRelation()
    await flush()
    expect(state.field.type).toBe(FieldType.REFERENCE)
    expect(state.switchReviewOpen).toBe(true)
    expect(state.currentRelation).toBeUndefined()
    expect(events.relation).not.toHaveBeenCalled()
    expect(labels(drawer)).toContain('从哪份资料选择')
  })
})

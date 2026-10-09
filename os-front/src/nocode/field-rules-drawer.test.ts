// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref, type App, type Component, type ComponentPublicInstance } from 'vue'
import Antd from 'ant-design-vue'
import FieldDesigner from '@/views/nocode/components/FieldDesigner.vue'
import DataLinkageEditor from '@/views/nocode/components/DataLinkageEditor.vue'
import RuleConditionRows from '@/views/nocode/components/RuleConditionRows.vue'
import ReferenceRuleEditor from '@/views/nocode/components/ReferenceRuleEditor.vue'
import SelectionSourceEditor from '@/views/nocode/components/SelectionSourceEditor.vue'
import { FieldType } from '@/types/nocode/enums'
import type { FieldOptions } from '@/types/nocode/data-center'
import type { FieldRules, RuleCondition } from '@/types/nocode/field-rules'
import type { ObjectField } from '@/types/nocode/object'
import { defaultFieldOptions } from './data-center'
import { newField } from './object-draft'
import { conditionsError, formFieldGroups, type PublishedDefinition } from './field-rules'
import editorSource from '@/views/nocode/object/editor.vue?raw'

const api = vi.hoisted(() => ({ objects: vi.fn(), design: vi.fn(), version: vi.fn() }))
vi.mock('@/api/nocode/data-center', () => ({ createDataCenterApi: () => api }))
const http = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('@/utils/request', () => ({ default: http }))
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

const mounted: { app: App; host: HTMLElement }[] = []
async function flush() {
  await vi.advanceTimersByTimeAsync(20)
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function field(type: string, id: string, name = type, code = type.toLowerCase()): ObjectField {
  return { ...newField(0, name), key: id, id, code, type: type as ObjectField['type'] }
}
const options = (patch: Partial<FieldOptions> = {}): FieldOptions => ({ ...defaultFieldOptions(), ...patch })
async function mount(component: Component, props: Record<string, unknown>) {
  const instance = ref<ComponentPublicInstance>()
  const app = createApp({ setup: () => () => h(component, { ref: instance, ...props }) })
  app.use(Antd)
  const host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  mounted.push({ app, host })
  await flush()
  if (!instance.value) throw new Error('组件未挂载')
  return { host, state: (instance.value.$ as unknown as { setupState: Record<string, any> }).setupState }
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
const radios = (root: ParentNode) =>
  Array.from(root.querySelectorAll('.ant-radio-wrapper')).map(node => node.textContent?.trim() ?? '')

beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
  http.get.mockResolvedValue([])
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
    list: [{ id: 'src', objectName: '资金流水', objectCode: 'zjls', publishedVersion: 1, category: '财务' }],
    total: 1
  })
  api.design.mockResolvedValue({ publishedVersion: 1, draft: { objectName: '资金流水', objectCode: 'zjls' } })
  api.version.mockResolvedValue({
    definition: {
      fields: [field(FieldType.TEXT, 'bank', '开户行'), field(FieldType.DECIMAL, 'amount', '金额')],
      fieldOptions: {},
      relations: []
    }
  })
})
afterEach(async () => {
  // 弹层的进场过渡在下一帧回调；等它结束再卸载，避免回调落在测试环境销毁之后。
  await vi.advanceTimersByTimeAsync(100)
  for (const { app, host } of mounted.splice(0)) {
    app.unmount()
    host.remove()
  }
  document.querySelectorAll('.ant-modal-root, .ant-select-dropdown').forEach(node => node.remove())
  // Vue 过渡完成回调可能晚于卸载；在 jsdom 仍存在时排空它们。
  await vi.runAllTimersAsync()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

// 组件测试：真实渲染抽屉内容（OsModalForm 以插槽桩替代），断言界面上出现或不出现的块。
describe('字段抽屉 · 值来源分区', () => {
  it('选项类只有「选项」块，不渲染默认值块，保存前清掉默认值', async () => {
    for (const type of [FieldType.SELECT, FieldType.MULTI_SELECT, FieldType.REGION]) {
      const target = field(type, 'status-' + type, '状态')
      const emitted = vi.fn()
      const value = type === FieldType.SELECT ? 'a' : '["a"]'
      const { host, state } = await mount(FieldDesigner, {
        modelValue: [target],
        options: {
          [target.key]: options({ options: [{ code: 'a', label: '甲', disabled: false }], defaultValue: value })
        },
        'onUpdate:options': emitted
      })
      state.show(target)
      await flush()
      const drawer = host.querySelector('.drawer-stub')!
      expect(labels(drawer)).toContain('选项')
      expect(labels(drawer)).not.toContain('默认值')
      expect(drawer.querySelector('[data-role="default-editor"]')).toBeNull()
      expect(drawer.textContent).toContain('选项类字段没有默认值')
      state.save()
      expect(state.error).toBe('')
      expect(emitted.mock.calls[0]![0][target.key].defaultValue).toBeNull()
    }
  })
  it('其它字段只有「默认值」块：自定义 / 数据联动 / 公式编辑，没有「启用默认值」开关与「字段说明」', async () => {
    const target = field(FieldType.TEXT, 'remark', '备注')
    const { host, state } = await mount(FieldDesigner, {
      modelValue: [target],
      options: { [target.key]: options({ description: '存量说明' }) }
    })
    state.show(target)
    await flush()
    const drawer = host.querySelector('.drawer-stub')!
    expect(labels(drawer)).toContain('默认值')
    expect(labels(drawer)).not.toContain('选项')
    expect(labels(drawer)).not.toContain('字段说明')
    expect(drawer.querySelector('[data-role="default-editor"]')).not.toBeNull()
    expect(
      Array.from(formItem(drawer, '默认值').querySelectorAll('.ant-radio-button-wrapper')).map(n =>
        n.textContent?.trim()
      )
    ).toEqual(['自定义', '数据联动', '公式编辑'])
    expect(drawer.textContent).not.toContain('启用默认值')
    expect(drawer.querySelector('.ant-switch')).toBeNull()
    expect(state.option.description).toBe('存量说明')
  })
  it('金额字段的公式默认值出现取整方式三选一，缺省向下取整；非金额字段不出现', async () => {
    const price = field(FieldType.DECIMAL, 'price', '单价', 'c_dj')
    const qty = field(FieldType.INTEGER, 'qty', '数量', 'c_sl')
    for (const type of [FieldType.MONEY, FieldType.DECIMAL]) {
      const target = field(type, 'total-' + type, '合计')
      const { host, state } = await mount(FieldDesigner, {
        modelValue: [price, qty, target],
        options: {
          [price.key]: options(),
          [qty.key]: options(),
          [target.key]: options({ rules: { defaultFormula: 'c_dj * c_sl' } })
        }
      })
      state.show(target)
      await flush()
      const drawer = host.querySelector('.drawer-stub')!
      const choices = drawer.querySelector('.value-source-choices')
      // 2026-09-29 裁定：公式默认值一律只读，编辑时依赖变化也重算。
      expect(drawer.textContent).toContain('只读，依赖字段变化时自动重算')
      expect(drawer.textContent).not.toContain('编辑已有记录不重算')
      if (type === FieldType.DECIMAL) {
        expect(choices).toBeNull()
        expect(drawer.textContent).not.toContain('取整方式')
        continue
      }
      expect(radios(choices!).map(text => text.split(/\s/)[0])).toEqual(['四舍五入', '向下取整', '去掉小数'])
      expect(choices!.querySelector('.ant-radio-wrapper-checked')?.textContent).toContain('向下取整')
      expect(drawer.textContent).toContain('1.5 → 2；-1.5 → -2')
      expect(drawer.textContent).toContain('公式里不要写 round')
    }
  })
  it('明细字段的「当前字段」分「本行 · / 主表 ·」两组，不列其它明细', async () => {
    const target = field(FieldType.TEXT, 'r1', '贷方科目')
    const row = [target, field(FieldType.TEXT, 'r2', '贷方科目分类')]
    const other = field(FieldType.TEXT, 'o1', '其它明细字段')
    const { state } = await mount(FieldDesigner, {
      modelValue: row,
      options: {
        r1: options({ rules: { linkage: { sourceObjectId: 'src', conditions: [], valueFieldId: 'bank' } } }),
        r2: options()
      },
      detail: true,
      master: { fields: [field(FieldType.TEXT, 'm1', '公司')] },
      details: [
        {
          id: 'd2',
          code: 'd2',
          name: '另一明细',
          tableName: 't',
          state: 'ACTIVE',
          fields: [other],
          fieldOptions: {},
          indexes: []
        }
      ]
    })
    state.show(target)
    await flush()
    const groups = state.valueSourceSection.formGroups as ReturnType<typeof formFieldGroups>
    expect(groups.map(group => group.label)).toEqual(['本行', '主表'])
    const texts = groups.flatMap(group => group.options.map(option => option.label))
    expect(texts).toEqual(['本行 · 贷方科目分类', '主表 · 公司'])
    expect(texts.join()).not.toContain('其它明细字段')
  })
})

describe('明细字段抽屉接入主表字段', () => {
  it('对象编辑器把主表字段与关系传给明细的字段设计器', () => {
    const blocks = editorSource
      .split('<FieldDesigner')
      .slice(1)
      .map(block => block.slice(0, block.indexOf('/>')))
    const detail = blocks.filter(block => /\n\s*detail\s*$/.test(block))
    expect(detail).toHaveLength(1)
    expect(detail[0]).toContain(':master="{ fields: input.draft.fields, relations: sourceRelations() }"')
  })
  it('明细字段数据联动条件的「当前字段」下拉出现「主表 ·」组', async () => {
    const target = field(FieldType.TEXT, 'r1', '贷方科目')
    const conditions: RuleCondition[] = [
      { fieldId: 'bank', operator: 'eq', valueSource: 'FORM_FIELD', formFieldId: null }
    ]
    const { host, state } = await mount(FieldDesigner, {
      modelValue: [target, field(FieldType.TEXT, 'r2', '贷方科目分类')],
      options: {
        r1: options({ rules: { linkage: { sourceObjectId: 'src', conditions, valueFieldId: 'bank' } } }),
        r2: options()
      },
      detail: true,
      master: { fields: [field(FieldType.TEXT, 'm1', '公司')], relations: [] }
    })
    state.show(target)
    await flush()
    const button = Array.from(host.querySelectorAll<HTMLButtonElement>('.drawer-stub button')).find(
      node => node.textContent?.trim() === '数据联动设置'
    )
    if (!button) throw new Error('缺少数据联动设置按钮')
    button.click()
    await flush()
    const selector = document
      .querySelectorAll('.ant-modal .rule-row .ant-select')[3]
      ?.querySelector('.ant-select-selector')
    if (!selector) throw new Error('缺少当前字段选择')
    selector.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    await flush()
    const dropdown = Array.from(document.querySelectorAll('.ant-select-dropdown')).at(-1)!
    expect(Array.from(dropdown.querySelectorAll('.ant-select-item-group')).map(node => node.textContent)).toEqual([
      '本行',
      '主表'
    ])
    expect(Array.from(dropdown.querySelectorAll('.ant-select-item-option')).map(node => node.textContent)).toContain(
      '主表 · 公司'
    )
  })
})

describe('数据联动弹层', () => {
  async function open(target: ObjectField, valueFieldId: string, stored: FieldRules['rounding'] = null) {
    const model = vi.fn(),
      rounding = vi.fn()
    const { state } = await mount(DataLinkageEditor, {
      modelValue: null,
      rounding: stored,
      field: target,
      fieldOptions: options(),
      formGroups: [],
      'onUpdate:modelValue': model,
      'onUpdate:rounding': rounding
    })
    state.show()
    await flush()
    state.changeSource('src')
    await flush()
    state.changeValueField(valueFieldId)
    await flush()
    const modal = document.querySelector('.ant-modal')
    if (!modal) throw new Error('数据联动弹层未打开')
    return { state, modal, model, rounding }
  }
  it('「当前字段只读」默认开启，文案与新口径一致；手动关掉后确定存 false', async () => {
    const { modal, state, model } = await open(field(FieldType.TEXT, 'target', '银行名'), 'bank')
    const item = formItem(modal, '当前字段只读（默认开启）')
    const toggle = item.querySelector<HTMLElement>('.ant-switch')!
    expect(toggle.classList.contains('ant-switch-checked')).toBe(true)
    expect(item.textContent).toContain('值只能由联动带出；没取到值时字段为空且不能填写')
    expect(labels(modal)).not.toContain('命中后当前字段设为只读')
    toggle.click()
    await flush()
    state.confirm()
    await flush()
    expect(model).toHaveBeenLastCalledWith(expect.objectContaining({ readOnly: false }))
  })
  it('存量联动 readOnly 为 null 按开启显示（与后端 null 按 true 一致），显式 false 保持关闭', async () => {
    for (const [stored, checked] of [
      [null, true],
      [false, false]
    ] as const) {
      const { state, host } = await mount(DataLinkageEditor, {
        modelValue: { sourceObjectId: 'src', conditions: [], valueFieldId: 'bank', multiRow: null, readOnly: stored },
        field: field(FieldType.TEXT, 'target', '银行名'),
        fieldOptions: options(),
        formGroups: []
      })
      expect(host.textContent).toContain(`当前字段只读：${checked ? '开' : '关'}`)
      state.show()
      await flush()
      expect(state.draft.readOnly).toBe(checked)
      state.open = false
      await flush()
    }
  })
  it('非数值来源不列「求和」，条件之间显式为「且」', async () => {
    const { modal, state } = await open(field(FieldType.TEXT, 'target', '银行名'), 'bank')
    const modes = radios(formItem(modal, '多行匹配'))
    expect(modes.some(text => text.startsWith('拼接成一行'))).toBe(true)
    expect(modes.some(text => text.startsWith('求和'))).toBe(false)
    expect(modes).toHaveLength(3)
    expect(labels(modal)).not.toContain('取整方式')
    state.draft.conditions = [
      { fieldId: 'bank', operator: 'eq', valueSource: 'CONSTANT', value: 'A' },
      { fieldId: 'bank', operator: 'like', valueSource: 'CONSTANT', value: 'B' }
    ] satisfies RuleCondition[]
    await flush()
    expect(Array.from(modal.querySelectorAll('.rule-and')).map(node => node.textContent)).toEqual(['且'])
  })
  it('金额目标：数值来源列出「求和」，取整方式三选一缺省向下取整，确定时缺省存 null', async () => {
    const { modal, state, model, rounding } = await open(field(FieldType.MONEY, 'target', '入金'), 'amount')
    expect(radios(formItem(modal, '多行匹配')).some(text => text.startsWith('求和'))).toBe(true)
    const item = formItem(modal, '取整方式')
    expect(radios(item).map(text => text.split(/\s/)[0])).toEqual(['四舍五入', '向下取整', '去掉小数'])
    expect(item.querySelector('.ant-radio-wrapper-checked')?.textContent).toContain('向下取整')
    state.confirm()
    await flush()
    // 2026-09-29 裁定：新配联动「当前字段只读」默认开启（旧断言 readOnly: false 改为 true）。
    expect(model).toHaveBeenLastCalledWith({
      sourceObjectId: 'src',
      conditions: [],
      valueFieldId: 'amount',
      multiRow: null,
      readOnly: true
    } satisfies FieldRules['linkage'])
    // 缺省档不写编码：原本就是 null 时不产生非 null 的取整值。
    expect(rounding).not.toHaveBeenCalledWith(expect.anything())
  })
  it('金额目标：从其它档切回「向下取整」时写回 null，选「去掉小数」写编码', async () => {
    for (const [choice, expected] of [
      ['向下取整', null],
      ['去掉小数', 'DOWN']
    ] as const) {
      const { modal, state, rounding } = await open(field(FieldType.MONEY, 'target', '入金'), 'amount', 'HALF_UP')
      const item = formItem(modal, '取整方式')
      expect(item.querySelector('.ant-radio-wrapper-checked')?.textContent).toContain('四舍五入')
      const input = Array.from(item.querySelectorAll<HTMLElement>('.ant-radio-wrapper'))
        .find(node => node.textContent?.trim().startsWith(choice))
        ?.querySelector<HTMLInputElement>('input')
      if (!input) throw new Error('缺少取整选项：' + choice)
      input.click()
      await flush()
      state.confirm()
      await flush()
      expect(rounding).toHaveBeenLastCalledWith(expected)
      await vi.advanceTimersByTimeAsync(100)
      for (const { app, host } of mounted.splice(0)) {
        app.unmount()
        host.remove()
      }
      document.querySelectorAll('.ant-modal-root').forEach(node => node.remove())
    }
  })
})

// 第一期契约 9.2：新建联动默认开自动更新，存量联动不动；满足可开条件才写 autoUpdate: true，关闭时不写这个键。
describe('数据联动弹层 · 来源变化时自动更新', () => {
  type Linkage = NonNullable<FieldRules['linkage']>
  /** 弹层 setup 暴露出来、测试里用到的那几样。 */
  interface EditorState {
    show: () => void
    confirm: () => void
    changeSource: (id: string) => void
    changeValueField: (id: string) => void
    open: boolean
    draft: {
      conditions: RuleCondition[]
      readOnly: boolean
      autoUpdate: boolean
      emptyValue: string
    }
  }
  const anchor: RuleCondition = { fieldId: 'flow', operator: 'eq', valueSource: 'CURRENT_RECORD' }
  const recordKey: RuleCondition = { fieldId: '$record', operator: 'eq', valueSource: 'FORM_FIELD', formFieldId: 'g' }
  const stateOptions = [
    { code: 'ylr', label: '已录入', disabled: false },
    { code: 'wdj', label: '未登记', disabled: false },
    { code: 'old', label: '作废', disabled: true }
  ]
  /** 当前对象「资金流水」（self-obj）上的目标字段。 */
  const targets = {
    text: () => field(FieldType.TEXT, 'memo', '摘要'),
    select: () => field(FieldType.SELECT, 'vstatus', '凭证状态'),
    money: () => ({ ...field(FieldType.MONEY, 'paid', '入金'), precision: 15, scale: 0 }),
    boolean: () => field(FieldType.BOOLEAN, 'done', '是否已做凭证'),
    date: () => field(FieldType.DATE, 'day', '凭证日期'),
    rich: () => field(FieldType.RICH_TEXT, 'note', '说明')
  }
  /** 来源对象「会计凭证录入」（src）的各类型字段；flow 是指向资金流水的单选关联。 */
  const valueFieldFor: Record<keyof typeof targets, string> = {
    text: 'bank',
    select: 'status',
    money: 'amount',
    boolean: 'posted',
    date: 'posted_on',
    rich: 'detail'
  }
  beforeEach(() => {
    api.design.mockResolvedValue({ publishedVersion: 1, draft: { objectName: '会计凭证录入', objectCode: 'kjpz' } })
    api.version.mockResolvedValue({
      definition: {
        fields: [
          field(FieldType.REFERENCE, 'flow', '资金流水'),
          field(FieldType.TEXT, 'bank', '开户行'),
          field(FieldType.FORMULA, 'calc', '拼接摘要'),
          field(FieldType.SELECT, 'status', '凭证状态'),
          field(FieldType.DECIMAL, 'amount', '金额'),
          field(FieldType.BOOLEAN, 'posted', '已过账'),
          field(FieldType.DATE, 'posted_on', '过账日期'),
          field(FieldType.RICH_TEXT, 'detail', '详情')
        ],
        fieldOptions: {
          calc: options({ resultType: FieldType.TEXT }),
          // 凭证状态挑的是资金流水.凭证状态的选项：两边共用一套选项。
          status: options({
            selection: { kind: 'OBJECT_FIELD_OPTIONS', sourceObjectId: 'self-obj', sourceFieldId: 'vstatus' } as never
          })
        },
        relations: [
          { id: 'r', code: 'flow', name: '资金流水', kind: 'REFERENCE', targetObjectId: 'self-obj', fieldId: 'flow' }
        ]
      }
    })
  })
  /** 真实挂载弹层并把确定写回的值回灌给它（与 FieldValueSourceSection 的用法一致）。 */
  async function editor(
    kind: keyof typeof targets,
    stored: Linkage | null,
    props: Record<string, unknown> = {},
    fieldOptions: FieldOptions = options(kind === 'select' ? { options: stateOptions } : {})
  ) {
    const model = ref<FieldRules['linkage']>(stored)
    const emitted = vi.fn()
    const instance = ref<ComponentPublicInstance>()
    const app = createApp({
      setup: () => () =>
        h(DataLinkageEditor as Component, {
          ref: instance,
          modelValue: model.value,
          'onUpdate:modelValue': (value: FieldRules['linkage']) => {
            emitted(value)
            model.value = value
          },
          field: targets[kind](),
          fieldOptions,
          objectId: 'self-obj',
          formGroups: [],
          ...props
        })
    })
    app.use(Antd)
    const host = document.createElement('div')
    document.body.append(host)
    app.mount(host)
    mounted.push({ app, host })
    await flush()
    const state = (instance.value?.$ as unknown as { setupState: EditorState } | undefined)?.setupState
    if (!state) throw new Error('组件未挂载')
    state.show()
    await flush()
    if (!stored) {
      state.changeSource('src')
      await flush()
      state.changeValueField(valueFieldFor[kind])
      await flush()
    }
    return { state, modal: openModal(), host, emitted, model }
  }
  /** 当前打开的弹层：同一条用例里先后挂载多个编辑器时取最后一个。 */
  function openModal(): Element {
    const modal = Array.from(document.querySelectorAll('.ant-modal')).at(-1)
    if (!modal) throw new Error('数据联动弹层未打开')
    return modal
  }
  const stored = (kind: keyof typeof targets, patch: Partial<Linkage> = {}): Linkage => ({
    sourceObjectId: 'src',
    conditions: [],
    valueFieldId: valueFieldFor[kind],
    multiRow: 'FIRST',
    readOnly: true,
    ...patch
  })
  const autoSwitch = (modal: Element) => {
    const toggle = formItem(modal, '来源变化时自动更新').querySelector<HTMLElement>('.ant-switch')
    if (!toggle) throw new Error('缺少自动更新开关')
    return {
      toggle,
      checked: toggle.classList.contains('ant-switch-checked'),
      disabled: toggle.classList.contains('ant-switch-disabled')
    }
  }
  const blocked = (modal: Element) =>
    formItem(modal, '来源变化时自动更新').querySelector('.linkage-blocked')?.textContent
  const lastEmitted = (emitted: ReturnType<typeof vi.fn>) => emitted.mock.calls.at(-1)?.[0] as Linkage
  async function setConditions(state: EditorState, conditions: RuleCondition[]) {
    state.draft = { ...state.draft, conditions }
    await flush()
  }
  async function confirm(state: EditorState) {
    state.confirm()
    await flush()
  }

  it('开关在「当前字段只读」下方，说明文字照契约', async () => {
    const { modal } = await editor('text', null)
    const order = labels(modal)
    expect(order.indexOf('来源变化时自动更新')).toBe(order.indexOf('当前字段只读（默认开启）') + 1)
    expect(formItem(modal, '来源变化时自动更新').textContent).toContain(
      '来源记录新增、修改、删除后，系统自动重算并保存这个字段，可用于筛选、排序和统计。配置在发布数据对象、并到应用里同步对象版本后生效。'
    )
  })

  it('新建且有锚点时：开关默认是开，确定写出 autoUpdate: true，摘要显示「自动更新：开」', async () => {
    const { state, modal, host, emitted } = await editor('text', null)
    await setConditions(state, [anchor])
    expect(autoSwitch(modal)).toMatchObject({ checked: true, disabled: false })
    expect(blocked(modal)).toBeUndefined()
    await confirm(state)
    expect(lastEmitted(emitted)).toEqual({
      sourceObjectId: 'src',
      conditions: [anchor],
      valueFieldId: 'bank',
      multiRow: null,
      readOnly: true,
      autoUpdate: true
    })
    expect(host.querySelector('.linkage-summary')?.textContent).toContain('自动更新：开')
  })

  it('新建但没锚点时：开关是关并说明原因，确定不写这个键，摘要显示「自动更新：关」', async () => {
    const { state, modal, host, emitted } = await editor('text', null)
    expect(state.draft.autoUpdate).toBe(true)
    expect(autoSwitch(modal)).toMatchObject({ checked: false, disabled: true })
    expect(blocked(modal)).toContain(
      '需要一条按记录匹配的条件：「来源对象的关联字段 等于 当前记录」或「按记录匹配 等于 当前字段」'
    )
    await confirm(state)
    expect(lastEmitted(emitted)).toEqual({
      sourceObjectId: 'src',
      conditions: [],
      valueFieldId: 'bank',
      multiRow: null,
      readOnly: true
    })
    expect(lastEmitted(emitted)).not.toHaveProperty('autoUpdate')
    expect(host.querySelector('.linkage-summary')?.textContent).toContain('自动更新：关')
  })

  it('打开存量联动（没有这个键）开关为关；原样确定后不多出任何键', async () => {
    // 存量联动本身满足可开条件（有「按记录匹配 等于 当前字段」），仍然不自动打开。
    const legacy = stored('text', { conditions: [recordKey] })
    const snapshot = JSON.stringify(legacy)
    const { state, modal, host, emitted } = await editor('text', legacy)
    expect(host.querySelector('.linkage-summary')?.textContent).toContain('自动更新：关')
    expect(blocked(modal)).toBeUndefined()
    expect(autoSwitch(modal)).toMatchObject({ checked: false, disabled: false })
    await confirm(state)
    expect(lastEmitted(emitted)).toEqual(legacy)
    expect(lastEmitted(emitted)).not.toHaveProperty('autoUpdate')
    expect(lastEmitted(emitted)).not.toHaveProperty('emptyValue')
    expect(JSON.stringify(lastEmitted(emitted))).toBe(snapshot)
  })

  it('存量联动由业务方手动打开：点开关后确定才写 autoUpdate: true；已开的再次打开仍是开', async () => {
    const { state, modal, emitted } = await editor('text', stored('text', { conditions: [recordKey] }))
    autoSwitch(modal).toggle.click()
    await flush()
    expect(autoSwitch(modal).checked).toBe(true)
    await confirm(state)
    expect(lastEmitted(emitted)).toMatchObject({ autoUpdate: true })

    const again = await editor('text', stored('text', { conditions: [anchor], autoUpdate: true }))
    expect(autoSwitch(again.modal)).toMatchObject({ checked: true, disabled: false })
    autoSwitch(again.modal).toggle.click()
    await flush()
    await setConditions(again.state, [recordKey])
    await confirm(again.state)
    expect(lastEmitted(again.emitted)).not.toHaveProperty('autoUpdate')
  })

  it('每种开不了的情况都置灰并给出原因', async () => {
    const cases: [string, () => ReturnType<typeof editor>][] = [
      ['明细字段暂不支持', () => editor('text', stored('text', { conditions: [recordKey] }), { detail: true })],
      ['这种类型的字段暂不支持', () => editor('rich', stored('rich', { conditions: [recordKey] }))],
      [
        '这种类型的字段暂不支持',
        () => editor('text', stored('text', { conditions: [recordKey] }), { referenceTarget: 'other-obj' })
      ],
      [
        '来源对象是本对象时暂不支持',
        () => editor('text', stored('text', { conditions: [recordKey] }), { objectId: 'src' })
      ],
      [
        '带入的来源字段是计算字段时暂不支持',
        () => editor('text', stored('text', { conditions: [anchor], valueFieldId: 'calc' }))
      ],
      [
        '可手改的字段不会跟随来源变化',
        () => editor('text', stored('text', { conditions: [recordKey], readOnly: false }))
      ]
    ]
    for (const [reason, open] of cases) {
      const { modal, state } = await open()
      expect(autoSwitch(modal)).toMatchObject({ checked: false, disabled: true })
      expect(blocked(modal)).toBe('现在不能开启：' + reason)
      state.open = false
      await flush()
      await vi.advanceTimersByTimeAsync(100)
      for (const { app, host } of mounted.splice(0)) {
        app.unmount()
        host.remove()
      }
      document.querySelectorAll('.ant-modal-root').forEach(node => node.remove())
    }
  })

  it('只读查看的字段设计器：没有别的原因时开关同样置灰、点不动，也不显示「现在不能开启」', async () => {
    const { state, modal } = await editor('text', stored('text', { conditions: [recordKey] }), { disabled: true })
    expect(blocked(modal)).toBeUndefined()
    expect(autoSwitch(modal)).toMatchObject({ checked: false, disabled: true })
    autoSwitch(modal).toggle.click()
    await flush()
    expect(state.draft.autoUpdate).toBe(false)
    expect(autoSwitch(modal).checked).toBe(false)
  })

  it('关掉只读：同时关掉自动更新，清掉「没有匹配记录时填入」和「当前记录」条件，并给出提示', async () => {
    const constant: RuleCondition = { fieldId: 'bank', operator: 'eq', valueSource: 'CONSTANT', value: 'A' }
    const { state, modal, emitted } = await editor(
      'text',
      stored('text', { conditions: [anchor, constant], autoUpdate: true, emptyValue: '未登记' })
    )
    expect(autoSwitch(modal).checked).toBe(true)
    formItem(modal, '当前字段只读（默认开启）').querySelector<HTMLElement>('.ant-switch')?.click()
    await flush()
    expect(autoSwitch(modal)).toMatchObject({ checked: false, disabled: true })
    expect(blocked(modal)).toBe('现在不能开启：可手改的字段不会跟随来源变化')
    expect(state.draft).toMatchObject({ readOnly: false, autoUpdate: false, emptyValue: '', conditions: [constant] })
    expect(modal.textContent).toContain(
      '已同时关闭「来源变化时自动更新」，已清掉「没有匹配记录时填入」，已移除「当前记录」条件：可手改的字段不会跟随来源变化。'
    )
    expect(labels(modal)).not.toContain('没有匹配记录时填入')
    await confirm(state)
    expect(lastEmitted(emitted)).toEqual({
      sourceObjectId: 'src',
      conditions: [constant],
      valueFieldId: 'bank',
      multiRow: 'FIRST',
      readOnly: false
    })
  })

  it('有「当前记录」条件却没开自动更新：确定被拦住并说明怎么办，不写出无效配置', async () => {
    const { state, modal, emitted } = await editor('text', stored('text', { conditions: [recordKey] }))
    await setConditions(state, [recordKey, anchor])
    expect(autoSwitch(modal)).toMatchObject({ checked: false, disabled: false })
    await confirm(state)
    expect(emitted).not.toHaveBeenCalled()
    expect(modal.textContent).toContain('「当前记录」条件只在开启「来源变化时自动更新」后可用')
  })

  it('被「请打开自动更新，或移除这条条件」拦住后：打开开关，这句提示立刻收起，再点确定写出自动更新', async () => {
    const hint = '「当前记录」条件只在开启「来源变化时自动更新」后可用'
    const { state, modal, emitted } = await editor('text', stored('text', { conditions: [recordKey] }))
    await setConditions(state, [anchor])
    await confirm(state)
    expect(modal.textContent).toContain(hint)
    autoSwitch(modal).toggle.click()
    await flush()
    expect(autoSwitch(modal).checked).toBe(true)
    expect(modal.textContent).not.toContain(hint)
    await confirm(state)
    expect(lastEmitted(emitted)).toMatchObject({ conditions: [anchor], readOnly: true, autoUpdate: true })
  })

  it('被同一句提示拦住后：移除「当前记录」条件，提示同样立刻收起；别的拦截提示不受开关影响', async () => {
    const hint = '「当前记录」条件只在开启「来源变化时自动更新」后可用'
    const { state, modal } = await editor('text', stored('text', { conditions: [recordKey] }))
    await setConditions(state, [recordKey, anchor])
    await confirm(state)
    expect(modal.textContent).toContain(hint)
    await setConditions(state, [recordKey])
    expect(modal.textContent).not.toContain(hint)
    // 对照：换成别的拦截提示（没选要带入的来源字段），拨动开关不会把它收起。
    state.changeValueField('')
    await flush()
    await confirm(state)
    expect(modal.textContent).toContain('请选择要带入的来源字段')
    autoSwitch(modal).toggle.click()
    await flush()
    expect(modal.textContent).toContain('请选择要带入的来源字段')
  })

  it('条件行拿到当前对象 ID：可以开自动更新的字段，值来源里有「当前记录」；明细字段没有', async () => {
    const valueSourceOptions = async (modal: Element) => {
      const selector = modal.querySelectorAll('.rule-row > .ant-select')[2]?.querySelector('.ant-select-selector')
      if (!selector) throw new Error('缺少值来源下拉')
      const before = new Set(Array.from(document.querySelectorAll('.ant-select-dropdown')))
      selector.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
      await flush()
      const dropdown = Array.from(document.querySelectorAll('.ant-select-dropdown')).find(node => !before.has(node))
      if (!dropdown) throw new Error('值来源下拉未打开')
      return Array.from(dropdown.querySelectorAll('.ant-select-item-option')).map(node => node.textContent)
    }
    const condition: RuleCondition = { fieldId: 'flow', operator: 'eq', valueSource: 'CONSTANT', value: null }
    const main = await editor('text', stored('text', { conditions: [condition] }))
    expect(await valueSourceOptions(main.modal)).toEqual(['固定值', '当前字段', '当前记录'])
    main.state.open = false
    await flush()
    await vi.advanceTimersByTimeAsync(100)
    for (const { app, host } of mounted.splice(0)) {
      app.unmount()
      host.remove()
    }
    document.querySelectorAll('.ant-modal-root, .ant-select-dropdown').forEach(node => node.remove())
    const detail = await editor('text', stored('text', { conditions: [condition] }), { detail: true })
    expect(await valueSourceOptions(detail.modal)).toEqual(['固定值', '当前字段'])
  })

  describe('没有匹配记录时填入', () => {
    const emptyItem = (modal: Element) => formItem(modal, '没有匹配记录时填入')
    async function type(modal: Element, text: string) {
      const input = emptyItem(modal).querySelector<HTMLInputElement>('input.ant-input')
      if (!input) throw new Error('缺少输入框')
      input.value = text
      input.dispatchEvent(new Event('input', { bubbles: true }))
      await flush()
    }

    it('只在自动更新开着、且字段类型支持时出现', async () => {
      const off = await editor('text', stored('text', { conditions: [recordKey] }))
      expect(labels(off.modal)).not.toContain('没有匹配记录时填入')
      autoSwitch(off.modal).toggle.click()
      await flush()
      expect(labels(off.modal)).toContain('没有匹配记录时填入')
      const date = await editor('date', stored('date', { conditions: [anchor], autoUpdate: true }))
      expect(autoSwitch(date.modal).checked).toBe(true)
      expect(labels(date.modal)).not.toContain('没有匹配记录时填入')
    })

    it('文本给输入框，写出原文；不填不带键', async () => {
      const { state, emitted } = await editor('text', stored('text', { conditions: [anchor], autoUpdate: true }))
      await confirm(state)
      expect(lastEmitted(emitted)).not.toHaveProperty('emptyValue')
      state.show()
      await flush()
      await type(openModal(), '未登记')
      await confirm(state)
      expect(lastEmitted(emitted)).toMatchObject({ autoUpdate: true, emptyValue: '未登记' })
    })

    it('金额只收整数：带小数时确定被拦住；整数原样写出', async () => {
      const { state, modal, emitted } = await editor(
        'money',
        stored('money', { conditions: [anchor], autoUpdate: true })
      )
      await type(modal, '12.5')
      expect(emptyItem(modal).querySelector('[role="alert"]')?.textContent).toBe('金额按日元整数保存，请填写整数')
      await confirm(state)
      expect(emitted).not.toHaveBeenCalled()
      expect(modal.textContent).toContain('没有匹配记录时填入：金额按日元整数保存，请填写整数')
      await type(modal, '0')
      await confirm(state)
      expect(lastEmitted(emitted)).toMatchObject({ emptyValue: '0' })
    })

    it('布尔是「不填 / 是 / 否」三选一，写出 true / false', async () => {
      const { state, modal, emitted } = await editor(
        'boolean',
        stored('boolean', { conditions: [anchor], autoUpdate: true })
      )
      expect(radios(emptyItem(modal))).toEqual(['不填', '是', '否'])
      expect(emptyItem(modal).querySelector('.ant-radio-wrapper-checked')?.textContent).toBe('不填')
      Array.from(emptyItem(modal).querySelectorAll<HTMLElement>('.ant-radio-wrapper'))
        .find(node => node.textContent?.trim() === '否')
        ?.querySelector<HTMLInputElement>('input')
        ?.click()
      await flush()
      await confirm(state)
      expect(lastEmitted(emitted)).toMatchObject({ emptyValue: 'false' })
    })

    it('单选是该字段选项的下拉：显示名称、保存编码，不列停用项，没有文本框；摘要显示名称', async () => {
      const { state, modal, host, emitted } = await editor(
        'select',
        stored('select', { conditions: [anchor], autoUpdate: true })
      )
      const item = emptyItem(modal)
      expect(item.querySelector('input.ant-input')).toBeNull()
      const selector = item.querySelector('.ant-select-selector')
      if (!selector) throw new Error('缺少选项下拉')
      const before = new Set(Array.from(document.querySelectorAll('.ant-select-dropdown')))
      selector.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
      await flush()
      const dropdown = Array.from(document.querySelectorAll('.ant-select-dropdown')).find(node => !before.has(node))
      if (!dropdown) throw new Error('选项下拉未打开')
      const choices = Array.from(dropdown.querySelectorAll<HTMLElement>('.ant-select-item-option'))
      expect(choices.map(node => node.textContent)).toEqual(['已录入', '未登记'])
      choices.find(node => node.textContent === '未登记')?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await flush()
      expect(state.draft.emptyValue).toBe('wdj')
      await confirm(state)
      expect(lastEmitted(emitted)).toMatchObject({ autoUpdate: true, emptyValue: 'wdj' })
      expect(host.querySelector('.linkage-summary')?.textContent).toContain('没有匹配时填：未登记')
    })

    it('单选的选项加载失败或一条都没有：给出说明，仍是下拉，不退回自由文本', async () => {
      http.get.mockRejectedValue(new Error('网络错误'))
      const failed = await editor(
        'select',
        stored('select', { conditions: [anchor], autoUpdate: true }),
        {},
        options({ selection: { kind: 'SYSTEM_DICTIONARY', dictionaryType: 'voucher_state' } as never })
      )
      expect(emptyItem(failed.modal).querySelector('[role="alert"]')?.textContent).toBe(
        '选项加载失败：网络错误。只能从选项中选择，请稍后重试。'
      )
      expect(emptyItem(failed.modal).querySelector('input.ant-input')).toBeNull()
      expect(emptyItem(failed.modal).querySelector('.ant-select')).not.toBeNull()
      const empty = await editor(
        'select',
        stored('select', { conditions: [anchor], autoUpdate: true }),
        {},
        options({ options: [] })
      )
      expect(emptyItem(empty.modal).querySelector('[role="alert"]')?.textContent).toBe(
        '这个字段还没有可选的选项，请先为它配置选项。'
      )
      expect(empty.state.draft.emptyValue).toBe('')
    })

    it('已存的编码不在生效选项里（已停用）：照原样回显并标出，确定时要求重选', async () => {
      const { state, modal, emitted } = await editor(
        'select',
        stored('select', { conditions: [anchor], autoUpdate: true, emptyValue: 'old' })
      )
      expect(emptyItem(modal).querySelector('.ant-select-selection-item')?.textContent).toBe('作废（已停用）')
      await confirm(state)
      expect(emitted).not.toHaveBeenCalled()
      expect(modal.textContent).toContain('没有匹配记录时填入：请从选项中重新选择')
    })
  })

  it('明细字段从字段抽屉进入：弹层知道它在明细里，自动更新置灰并说明', async () => {
    const target = field(FieldType.TEXT, 'r1', '贷方科目')
    const { host, state } = await mount(FieldDesigner, {
      modelValue: [target],
      options: {
        r1: options({ rules: { linkage: { sourceObjectId: 'src', conditions: [recordKey], valueFieldId: 'bank' } } })
      },
      detail: true,
      master: { fields: [field(FieldType.TEXT, 'm1', '公司')], relations: [] },
      objectId: 'self-obj'
    })
    state.show(target)
    await flush()
    const button = Array.from(host.querySelectorAll<HTMLButtonElement>('.drawer-stub button')).find(
      node => node.textContent?.trim() === '数据联动设置'
    )
    if (!button) throw new Error('缺少数据联动设置按钮')
    button.click()
    await flush()
    const modal = openModal()
    expect(blocked(modal)).toBe('现在不能开启：明细字段暂不支持')
    expect(autoSwitch(modal)).toMatchObject({ checked: false, disabled: true })
  })
})

// 2026-10-01 裁定：真实挂载数据联动弹层，打开「来源字段」下拉，断言界面上列出与不列出的字段。
describe('数据联动弹层 · 来源字段下拉按类型相容过滤', () => {
  const picked = (objectId: string, fieldId: string): Partial<FieldOptions> => ({
    selection: {
      kind: 'OBJECT_FIELD_OPTIONS',
      directory: null,
      dictionaryType: null,
      rootIds: [],
      includeDescendants: false,
      organizationTypes: [],
      defaultMode: 'NONE',
      sourceObjectId: objectId,
      sourceFieldId: fieldId
    }
  })
  beforeEach(() => {
    api.version.mockResolvedValue({
      definition: {
        fields: [
          field(FieldType.TEXT, 'bank', '开户行'),
          field(FieldType.AUTO_NUMBER, 'serial', '流水编号'),
          field(FieldType.URL, 'site', '网址'),
          field(FieldType.FORMULA, 'label', '摘要公式'),
          field(FieldType.TEXTAREA, 'memo', '备注'),
          field(FieldType.DECIMAL, 'amount', '金额'),
          field(FieldType.FORMULA, 'total', '小计公式'),
          field(FieldType.SELECT, 'voucher-status', '凭证状态'),
          field(FieldType.SELECT, 'voucher-level', '级别'),
          field(FieldType.SELECT, 'voucher-kind', '类别')
        ],
        fieldOptions: {
          label: options({ resultType: FieldType.TEXT }),
          total: options({ resultType: FieldType.DECIMAL }),
          'voucher-status': options(picked('flow', 'status')),
          'voucher-level': options(picked('flow', 'kind'))
        },
        relations: []
      }
    })
  })
  /** 同一用例里换目标重开弹层前，先卸载上一个弹层及其下拉。 */
  async function reset() {
    await vi.advanceTimersByTimeAsync(100)
    for (const { app, host } of mounted.splice(0)) {
      app.unmount()
      host.remove()
    }
    document.querySelectorAll('.ant-modal-root, .ant-select-dropdown').forEach(node => node.remove())
  }
  async function sourceChoices(target: ObjectField, props: Record<string, unknown> = {}) {
    await reset()
    const { state } = await mount(DataLinkageEditor, {
      modelValue: null,
      field: target,
      fieldOptions: options(),
      formGroups: [],
      ...props
    })
    state.show()
    await flush()
    state.changeSource('src')
    await flush()
    const modal = document.querySelector('.ant-modal')
    if (!modal) throw new Error('数据联动弹层未打开')
    const item = formItem(modal, '触发以下联动')
    const selector = item.querySelector('.linkage-target .ant-select-selector')
    if (!selector) throw new Error('缺少来源字段选择')
    selector.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    await flush()
    const dropdown = Array.from(document.querySelectorAll('.ant-select-dropdown')).at(-1)
    if (!dropdown) throw new Error('来源字段下拉未打开')
    const names = Array.from(dropdown.querySelectorAll('.ant-select-item-option')).map(node => node.textContent)
    return { names, item, dropdown }
  }
  it('目标为单行文本：列出文本、自动编号、链接和结果为文本的公式，不列多行文本与数值', async () => {
    const { names, item } = await sourceChoices(field(FieldType.TEXT, 'target', '流水编号'))
    expect(names).toEqual(['开户行', '流水编号', '网址', '摘要公式'])
    expect(names).not.toContain('备注')
    expect(item.textContent).not.toContain('来源字段须与当前字段类型一致')
  })
  it('目标为多行文本：在单行文本可取的来源之上另列多行文本', async () => {
    const { names } = await sourceChoices(field(FieldType.TEXTAREA, 'target', '说明'))
    expect(names).toEqual(['开户行', '流水编号', '网址', '摘要公式', '备注'])
  })
  it('目标为数值：不列自动编号；没有相符字段时提示写明文本与挑取值的规则', async () => {
    const { names } = await sourceChoices(field(FieldType.DECIMAL, 'target', '合计'))
    expect(names).toEqual(['金额', '小计公式'])
    const { names: none, item, dropdown } = await sourceChoices(field(FieldType.DATE, 'target-date', '日期'))
    expect(none).toEqual([])
    expect(dropdown.textContent).toContain('来源对象里没有类型相符的字段')
    expect(item.textContent).toContain('单行文本可取文本、自动编号、链接和结果为文本的公式，多行文本另可取多行文本')
    expect(item.textContent).toContain('或一方的候选来源为「挑取值」并指向另一方')
  })
  it('选项目标：来源字段的挑取值指向当前字段时列出；挑别的字段或互不相干的局部选项不列', async () => {
    const status = field(FieldType.SELECT, 'status', '凭证状态')
    const { names } = await sourceChoices(status, { objectId: 'flow' })
    expect(names).toEqual(['凭证状态'])
    // 当前对象还没保存（没有对象 ID）时无从认出反方向。
    const { names: unsaved } = await sourceChoices(status)
    expect(unsaved).toEqual([])
  })
  it('对象编辑器把当前对象 ID 一路传到数据联动弹层：从字段抽屉进入也能选到挑取值指向当前字段的来源', async () => {
    const blocks = editorSource
      .split('<FieldDesigner')
      .slice(1)
      .map(block => block.slice(0, block.indexOf('/>')))
    expect(blocks).toHaveLength(2)
    for (const block of blocks) expect(block).toContain(':object-id="input.draft.id"')
    const status = field(FieldType.SELECT, 'status', '凭证状态')
    const { host, state } = await mount(FieldDesigner, {
      modelValue: [status],
      options: {
        status: options({
          options: [{ code: 'REG', label: '已登记', disabled: false }],
          rules: { linkage: { sourceObjectId: 'src', conditions: [], valueFieldId: 'voucher-status' } }
        })
      },
      objectId: 'flow'
    })
    state.show(status)
    await flush()
    const button = Array.from(host.querySelectorAll<HTMLButtonElement>('.drawer-stub button')).find(
      node => node.textContent?.trim() === '数据联动设置'
    )
    if (!button) throw new Error('缺少数据联动设置按钮')
    button.click()
    await flush()
    const selector = document.querySelector('.ant-modal .linkage-target .ant-select-selector')
    if (!selector) throw new Error('缺少来源字段选择')
    selector.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    await flush()
    const dropdown = Array.from(document.querySelectorAll('.ant-select-dropdown')).at(-1)
    if (!dropdown) throw new Error('来源字段下拉未打开')
    const names = Array.from(dropdown.querySelectorAll('.ant-select-item-option')).map(node => node.textContent)
    expect(names).toEqual(['凭证状态'])
  })
})

describe('条件行', () => {
  it('「当前字段」下拉按「本行 · / 主表 ·」分组显示', async () => {
    const definition: PublishedDefinition = {
      objectId: 'src',
      label: '会计科目',
      fields: [field(FieldType.TEXT, 'category', '科目分类')],
      fieldOptions: {},
      relations: [],
      titleTemplate: null
    }
    const formGroups = formFieldGroups({
      fields: [field(FieldType.TEXT, 'self', '贷方科目'), field(FieldType.TEXT, 'r2', '贷方科目分类')],
      selfKey: 'self',
      master: { fields: [field(FieldType.TEXT, 'm1', '公司')] }
    })
    const { host } = await mount(RuleConditionRows, {
      modelValue: [{ fieldId: 'category', operator: 'eq', valueSource: 'FORM_FIELD', formFieldId: null }],
      definition,
      formGroups,
      emptyText: '无'
    })
    const selector = host.querySelectorAll('.rule-row .ant-select')[3]?.querySelector('.ant-select-selector')
    if (!selector) throw new Error('缺少当前字段选择')
    selector.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    await flush()
    const dropdown = Array.from(document.querySelectorAll('.ant-select-dropdown')).at(-1)!
    expect(Array.from(dropdown.querySelectorAll('.ant-select-item-group')).map(node => node.textContent)).toEqual([
      '本行',
      '主表'
    ])
    expect(Array.from(dropdown.querySelectorAll('.ant-select-item-option')).map(node => node.textContent)).toEqual([
      '本行 · 贷方科目分类',
      '主表 · 公司'
    ])
  })
})

// 业务方 2026-10-01：录凭证时只想选「凭证状态不是已录入 / 还没有状态」的资金流水，原先比较方式下拉只有「等于」。
describe('条件行 · 比较方式（不等于 / 为空 / 不为空）', () => {
  const definition: PublishedDefinition = {
    objectId: 'src',
    label: '资金流水',
    fields: [
      field(FieldType.SELECT, 'status', '凭证状态2'),
      field(FieldType.TEXT, 'memo', '备注'),
      field(FieldType.DATE, 'opened', '发生日期'),
      field(FieldType.MULTI_SELECT, 'tags', '标签')
    ],
    fieldOptions: {
      status: options({
        options: [
          { code: 'DONE', label: '已录入', disabled: false },
          { code: 'TODO', label: '未录入', disabled: false }
        ]
      })
    },
    relations: [],
    titleTemplate: null
  }
  const formGroups = formFieldGroups({
    fields: [field(FieldType.SELECT, 'self', '资金流水'), field(FieldType.SELECT, 'state', '状态')],
    selfKey: 'self'
  })
  /** 真实挂载条件行，并把组件发出的新值回灌进去（与 ReferenceRuleEditor、DataLinkageEditor 的用法一致）。 */
  async function rows(initial: RuleCondition[], props: Record<string, unknown> = {}) {
    const model = ref<RuleCondition[]>(initial)
    const app = createApp({
      setup: () => () =>
        h(RuleConditionRows as Component, {
          modelValue: model.value,
          'onUpdate:modelValue': (value: RuleCondition[]) => (model.value = value),
          definition,
          formGroups,
          emptyText: '无',
          ...props
        })
    })
    app.use(Antd)
    const host = document.createElement('div')
    document.body.append(host)
    app.mount(host)
    mounted.push({ app, host })
    await flush()
    return { host, model }
  }
  const selects = (host: ParentNode) => Array.from(host.querySelectorAll<HTMLElement>('.rule-row > .ant-select'))
  /** 第 position 条条件所在的行。 */
  function row(host: ParentNode, position: number): HTMLElement {
    const found = host.querySelectorAll<HTMLElement>('.rule-row')[position]
    if (!found) throw new Error(`缺少第 ${position + 1} 条条件`)
    return found
  }
  function nth<T>(items: T[], position: number): T {
    const found = items[position]
    if (found === undefined) throw new Error(`缺少第 ${position + 1} 项`)
    return found
  }
  const selectLabels = (host: ParentNode) =>
    selects(host).map(node => node.querySelector('.ant-select-selection-item')?.textContent?.trim() ?? '')
  /**
   * 打开条件行里第 position 个下拉，返回它自己的弹层与选项节点。
   * 测试环境里所有下拉的列表 id 相同，不能按 id 找：首次打开时取新出现的弹层并记住，再次打开沿用同一个。
   */
  const dropdowns = new WeakMap<Element, Element>()
  async function openSelect(host: ParentNode, position: number) {
    const select = selects(host)[position]
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
    return {
      dropdown,
      options: Array.from(dropdown.querySelectorAll<HTMLElement>('.ant-select-item-option'))
    }
  }
  async function pick(host: ParentNode, position: number, label: string) {
    const option = (await openSelect(host, position)).options.find(node => node.textContent === label)
    if (!option) throw new Error(`第 ${position + 1} 个下拉里没有「${label}」`)
    option.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flush()
  }
  const openOperators = async (host: ParentNode) => (await openSelect(host, 1)).options
  const pickOperator = (host: ParentNode, label: string) => pick(host, 1, label)
  /** 「固定值」是条件行的第 4 个下拉（来源字段、比较方式、值来源之后）。 */
  const openValues = (host: ParentNode) => openSelect(host, 3)

  it('单选来源字段的比较方式下拉是「等于、不等于、为空、不为空」四项', async () => {
    const { host } = await rows([{ fieldId: 'status', operator: 'eq', valueSource: 'CONSTANT', value: 'DONE' }])
    expect((await openOperators(host)).map(node => node.textContent)).toEqual(['等于', '不等于', '为空', '不为空'])
  })

  it('文本、日期、多选来源字段都能选「为空 / 不为空」，日期与多选没有「不等于」', async () => {
    const text = await rows([{ fieldId: 'memo', operator: 'eq', valueSource: 'CONSTANT', value: 'x' }])
    expect((await openOperators(text.host)).map(node => node.textContent)).toEqual([
      '等于',
      '不等于',
      '包含',
      '不包含',
      '为空',
      '不为空'
    ])
    const date = await rows([{ fieldId: 'opened', operator: 'lt', valueSource: 'CONSTANT', value: '2026-01-01' }])
    expect((await openOperators(date.host)).map(node => node.textContent)).toEqual([
      '早于',
      '晚于',
      '在范围内',
      '为空',
      '不为空'
    ])
    const multi = await rows([{ fieldId: 'tags', operator: 'containsAny', valueSource: 'CONSTANT', value: ['A'] }])
    expect((await openOperators(multi.host)).map(node => node.textContent)).toEqual(['包含任一', '为空', '不为空'])
  })

  it('选「为空」后值来源与取值控件消失，保存出的条件不带值；改回「不等于」后控件回来且必须填值', async () => {
    const { host, model } = await rows([
      { fieldId: 'status', operator: 'eq', valueSource: 'FORM_FIELD', formFieldId: 'state' }
    ])
    expect(selects(host)).toHaveLength(4)
    await pickOperator(host, '为空')
    expect(model.value).toEqual([{ fieldId: 'status', operator: 'isNull', valueSource: 'CONSTANT', value: null }])
    expect(model.value[0]).not.toHaveProperty('formFieldId')
    expect(conditionsError(model.value)).toBeNull()
    // 只剩「来源字段」「比较方式」两个下拉和「移除」按钮，没有值来源、固定值或当前字段控件。
    expect(selectLabels(host)).toEqual(['凭证状态2', '为空'])
    expect(host.querySelector('.rule-row .ant-input')).toBeNull()
    expect(host.querySelector('.rule-row')?.textContent).not.toContain('固定值')
    expect(host.querySelector('.rule-row')?.textContent).not.toContain('当前字段')

    await pickOperator(host, '不为空')
    expect(model.value).toEqual([{ fieldId: 'status', operator: 'notNull', valueSource: 'CONSTANT', value: null }])
    expect(selects(host)).toHaveLength(2)

    await pickOperator(host, '不等于')
    expect(model.value).toEqual([{ fieldId: 'status', operator: 'neq', valueSource: 'CONSTANT', value: null }])
    expect(selects(host)).toHaveLength(4)
    expect(selectLabels(host).slice(0, 3)).toEqual(['凭证状态2', '不等于', '固定值'])
    expect(conditionsError(model.value)).toContain('请填写固定值')
  })

  it('旧配置回显不变：等于 + 固定值 / 当前字段照原样显示，不改动已存的条件', async () => {
    const stored: RuleCondition[] = [
      { fieldId: 'status', operator: 'eq', valueSource: 'CONSTANT', value: 'DONE' },
      { fieldId: 'status', operator: 'eq', valueSource: 'FORM_FIELD', formFieldId: 'state' }
    ]
    const { host, model } = await rows(stored.map(item => ({ ...item })))
    expect(selectLabels(row(host, 0))).toEqual(['凭证状态2', '等于', '固定值', '已录入'])
    expect(selectLabels(row(host, 1))).toEqual(['凭证状态2', '等于', '当前字段', '当前字段 · 状态'])
    expect(model.value).toEqual(stored)
  })

  // 业务方 2026-10-01：固定值原是自由文本框，填了选项名称「已录入」而库里存的是编码 ylr，候选恒为空。
  it('选项来源字段的固定值是该字段选项的下拉：显示名称，保存出的是编码，没有文本框', async () => {
    const { host, model } = await rows([{ fieldId: 'status', operator: 'eq', valueSource: 'CONSTANT', value: null }])
    expect(host.querySelector('.rule-row .ant-input')).toBeNull()
    expect((await openValues(host)).options.map(node => node.textContent)).toEqual(['已录入', '未录入'])
    await pick(host, 3, '已录入')
    expect(model.value).toEqual([{ fieldId: 'status', operator: 'eq', valueSource: 'CONSTANT', value: 'DONE' }])
    expect(selectLabels(host)).toEqual(['凭证状态2', '等于', '固定值', '已录入'])
    expect(host.querySelector('.rule-choice-problem')).toBeNull()
  })

  it('挑取值来源：选项取它指向的来源字段；公共字典取字典项；多选的「包含任一」是多选下拉', async () => {
    api.design.mockResolvedValue({ publishedVersion: 3, draft: { objectName: '会计凭证录入', objectCode: 'kjpzlr' } })
    api.version.mockResolvedValue({
      definition: {
        fields: [field(FieldType.SELECT, 'voucher-status', '凭证状态')],
        fieldOptions: {
          'voucher-status': options({ options: [{ code: 'ylr', label: '已录入', disabled: false }] })
        },
        relations: []
      }
    })
    http.get.mockImplementation(async (url: string) =>
      url === '/system/dict-data/list-all-simple'
        ? [
            { dictType: 'pay_type', value: 'cash', label: '现金' },
            { dictType: 'other', value: 'x', label: '无关' }
          ]
        : []
    )
    const selection = (patch: Record<string, unknown>) => ({
      kind: 'LOCAL_OPTIONS',
      directory: null,
      dictionaryType: null,
      rootIds: [],
      includeDescendants: false,
      organizationTypes: [],
      defaultMode: 'NONE',
      ...patch
    })
    const sourced: PublishedDefinition = {
      ...definition,
      fields: [
        field(FieldType.SELECT, 'picked', '凭证状态2'),
        field(FieldType.SELECT, 'pay', '支付方式'),
        field(FieldType.MULTI_SELECT, 'tags', '标签')
      ],
      fieldOptions: {
        picked: options({
          selection: selection({
            kind: 'OBJECT_FIELD_OPTIONS',
            sourceObjectId: 'voucher',
            sourceFieldId: 'voucher-status'
          }) as FieldOptions['selection']
        }),
        pay: options({
          selection: selection({ kind: 'SYSTEM_DICTIONARY', dictionaryType: 'pay_type' }) as FieldOptions['selection']
        }),
        tags: options({
          options: [
            { code: 'A', label: '甲类', disabled: false },
            { code: 'B', label: '乙类', disabled: false }
          ]
        })
      }
    }
    const { host, model } = await rows(
      [
        // 早先在自由文本框里填的选项名称：照原样回显并标明不是有效选项，改选后保存编码。
        { fieldId: 'picked', operator: 'eq', valueSource: 'CONSTANT', value: '已录入' },
        { fieldId: 'pay', operator: 'neq', valueSource: 'CONSTANT', value: null },
        { fieldId: 'tags', operator: 'containsAny', valueSource: 'CONSTANT', value: [] }
      ],
      { definition: sourced }
    )
    expect(api.design).toHaveBeenCalledWith('voucher')
    expect(api.version).toHaveBeenCalledWith('voucher', 3)
    const picked = row(host, 0),
      pay = row(host, 1),
      tags = row(host, 2)
    expect(host.querySelector('.rule-row .ant-input')).toBeNull()
    expect(selectLabels(picked)[3]).toBe('已录入（不是有效选项）')
    const pickedValues = await openValues(picked)
    expect(pickedValues.options.map(node => node.textContent)).toEqual(['已录入', '已录入（不是有效选项）'])
    expect(nth(pickedValues.options, 1).classList.contains('ant-select-item-option-disabled')).toBe(true)
    await pick(picked, 3, '已录入')
    expect(model.value[0]).toEqual({ fieldId: 'picked', operator: 'eq', valueSource: 'CONSTANT', value: 'ylr' })

    expect((await openValues(pay)).options.map(node => node.textContent)).toEqual(['现金'])
    await pick(pay, 3, '现金')
    expect(model.value[1]).toEqual({ fieldId: 'pay', operator: 'neq', valueSource: 'CONSTANT', value: 'cash' })

    expect(nth(selects(tags), 3).classList.contains('ant-select-multiple')).toBe(true)
    await pick(tags, 3, '甲类')
    await pick(tags, 3, '乙类')
    expect(model.value[2]).toEqual({
      fieldId: 'tags',
      operator: 'containsAny',
      valueSource: 'CONSTANT',
      value: ['A', 'B']
    })
  })

  it('停用的选项不在下拉里；已保存的停用值照常回显并标「已停用」', async () => {
    const retired: PublishedDefinition = {
      ...definition,
      fieldOptions: {
        status: options({
          options: [
            { code: 'DONE', label: '已录入', disabled: false },
            { code: 'VOID', label: '作废', disabled: true }
          ]
        })
      }
    }
    const { host, model } = await rows(
      [{ fieldId: 'status', operator: 'neq', valueSource: 'CONSTANT', value: 'VOID' }],
      { definition: retired }
    )
    expect(selectLabels(host)).toEqual(['凭证状态2', '不等于', '固定值', '作废（已停用）'])
    const values = await openValues(host)
    expect(values.options.map(node => node.textContent)).toEqual(['已录入', '作废（已停用）'])
    expect(nth(values.options, 1).classList.contains('ant-select-item-option-disabled')).toBe(true)
    expect(nth(model.value, 0).value).toBe('VOID')
  })

  it('选项加载失败或一条选项都没有：给出提示，仍是下拉，不退回自由文本', async () => {
    api.design.mockRejectedValue(new Error('来源对象须先发布'))
    const broken: PublishedDefinition = {
      ...definition,
      fields: [field(FieldType.SELECT, 'picked', '凭证状态2'), field(FieldType.SELECT, 'bare', '类别')],
      fieldOptions: {
        picked: options({
          selection: {
            kind: 'OBJECT_FIELD_OPTIONS',
            directory: null,
            dictionaryType: null,
            rootIds: [],
            includeDescendants: false,
            organizationTypes: [],
            defaultMode: 'NONE',
            sourceObjectId: 'voucher',
            sourceFieldId: 'voucher-status'
          }
        })
      }
    }
    const { host } = await rows(
      [
        { fieldId: 'picked', operator: 'eq', valueSource: 'CONSTANT', value: null },
        { fieldId: 'bare', operator: 'eq', valueSource: 'CONSTANT', value: null }
      ],
      { definition: broken }
    )
    const problems = Array.from(host.querySelectorAll('.rule-choice-problem')).map(node => node.textContent)
    expect(problems).toHaveLength(2)
    expect(problems[0]).toContain('「凭证状态2」的选项加载失败：来源对象须先发布')
    expect(problems[1]).toContain('「类别」没有可选的选项')
    expect(host.querySelector('.rule-row .ant-input')).toBeNull()
    const picked = row(host, 0),
      bare = row(host, 1)
    expect(selects(picked)).toHaveLength(4)
    expect(selects(bare)).toHaveLength(4)
    expect((await openValues(bare)).dropdown.textContent).toContain('没有可选的选项')
  })

  it('文本来源字段的固定值仍是文本框', async () => {
    const { host } = await rows([{ fieldId: 'memo', operator: 'eq', valueSource: 'CONSTANT', value: '房租' }])
    expect(selects(host)).toHaveLength(3)
    expect(host.querySelector<HTMLInputElement>('.rule-row .ant-input')?.value).toBe('房租')
  })

  it('「按记录匹配」仍只有「等于」', async () => {
    const { host } = await rows(
      [{ fieldId: '$record', operator: 'eq', valueSource: 'FORM_FIELD', formFieldId: null }],
      { recordKey: true }
    )
    expect((await openOperators(host)).map(node => node.textContent)).toEqual(['等于'])
  })

  // 第一期契约 9.2：值来源第三项「当前记录」——来源那一行的关联字段指向的就是当前这条记录（凭证的「资金流水」= 这条流水）。
  describe('值来源「当前记录」', () => {
    const relation = (fieldId: string, targetObjectId: string, kind = 'REFERENCE') =>
      ({ id: fieldId, code: fieldId, name: fieldId, kind, targetObjectId, fieldId, targetFieldId: null }) as never
    /** 来源对象「会计凭证录入」：flow 指向本对象（资金流水），subject 指向别的对象，flows 是指向本对象的多选关联。 */
    const voucher = (flowTarget = 'self-obj'): PublishedDefinition => ({
      objectId: 'voucher',
      label: '会计凭证录入',
      fields: [
        field(FieldType.REFERENCE, 'flow', '资金流水'),
        field(FieldType.REFERENCE, 'subject', '科目'),
        field(FieldType.REFERENCE, 'flows', '多条流水'),
        field(FieldType.SELECT, 'status', '凭证状态')
      ],
      fieldOptions: {},
      relations: [
        relation('flow', flowTarget),
        relation('subject', 'other-obj'),
        relation('flows', 'self-obj', 'MANY_TO_MANY')
      ],
      titleTemplate: null
    })
    const allowed = { definition: voucher(), allowCurrentRecord: true, currentObjectId: 'self-obj' }
    const constant = (fieldId: string): RuleCondition => ({
      fieldId,
      operator: 'eq',
      valueSource: 'CONSTANT',
      value: null
    })
    /** 「值来源」是条件行的第 3 个下拉。 */
    const valueSources = async (host: ParentNode) => (await openSelect(host, 2)).options.map(node => node.textContent)

    it('只在来源对象上指向本对象的单选关联字段上出现；指向别的对象、多选关联、普通字段都没有', async () => {
      const own = await rows([constant('flow')], allowed)
      expect(await valueSources(own.host)).toEqual(['固定值', '当前字段', '当前记录'])
      for (const fieldId of ['subject', 'flows', 'status']) {
        const other = await rows([constant(fieldId)], allowed)
        expect(await valueSources(other.host)).toEqual(['固定值', '当前字段'])
      }
    })

    it('选中后比较方式固定「等于」、不显示取值控件，写出的条件只有三个键', async () => {
      const { host, model } = await rows([{ ...constant('flow'), operator: 'neq' }], allowed)
      await pick(host, 2, '当前记录')
      expect(model.value).toEqual([{ fieldId: 'flow', operator: 'eq', valueSource: 'CURRENT_RECORD' }])
      expect(Object.keys(nth(model.value, 0)).sort()).toEqual(['fieldId', 'operator', 'valueSource'])
      expect(conditionsError(model.value)).toBeNull()
      expect(selectLabels(host)).toEqual(['资金流水', '等于', '当前记录'])
      expect(nth(selects(host), 1).classList.contains('ant-select-disabled')).toBe(true)
      expect(host.querySelector('.rule-row .ant-input')).toBeNull()
    })

    it('已存的「当前记录」条件照原样回显，不改动', async () => {
      const stored: RuleCondition[] = [{ fieldId: 'flow', operator: 'eq', valueSource: 'CURRENT_RECORD' }]
      const { host, model } = await rows([...stored], allowed)
      expect(selectLabels(host)).toEqual(['资金流水', '等于', '当前记录'])
      expect(model.value).toEqual(stored)
    })

    it('左侧换成别的字段后退回「固定值」并清空，不留下一个无效条件', async () => {
      const { host, model } = await rows([{ fieldId: 'flow', operator: 'eq', valueSource: 'CURRENT_RECORD' }], allowed)
      await pick(host, 0, '科目')
      expect(model.value).toEqual([{ fieldId: 'subject', operator: 'eq', valueSource: 'CONSTANT', value: null }])
      expect(await valueSources(host)).toEqual(['固定值', '当前字段'])
    })

    it('已存的「当前记录」不再适用（关联字段已改指别的对象）：退回「固定值」，并提示要重填', async () => {
      const { model } = await rows([{ fieldId: 'flow', operator: 'eq', valueSource: 'CURRENT_RECORD' }], {
        ...allowed,
        definition: voucher('other-obj')
      })
      expect(model.value).toEqual([{ fieldId: 'flow', operator: 'eq', valueSource: 'CONSTANT', value: null }])
      expect(conditionsError(model.value)).toContain('请填写固定值')
    })

    it('父组件没有允许时不出现（引用筛选不传 allowCurrentRecord）', async () => {
      const { host } = await rows([constant('flow')], { definition: voucher(), currentObjectId: 'self-obj' })
      expect(await valueSources(host)).toEqual(['固定值', '当前字段'])
      expect(host.textContent).not.toContain('当前记录')
    })

    it('数据对象还没保存（没有对象 ID）时不出现，并提示先保存', async () => {
      const { host } = await rows([constant('flow')], { definition: voucher(), allowCurrentRecord: true })
      expect(await valueSources(host)).toEqual(['固定值', '当前字段'])
      expect(host.textContent).toContain('先保存数据对象后才能使用')
    })

    it('只读查看时不改动已存的条件', async () => {
      const stored: RuleCondition[] = [{ fieldId: 'flow', operator: 'eq', valueSource: 'CURRENT_RECORD' }]
      const { model } = await rows([...stored], { ...allowed, definition: voucher('other-obj'), disabled: true })
      expect(model.value).toEqual(stored)
    })
  })
})

// 引用筛选编辑器不传 allowCurrentRecord：真实挂载，值来源下拉只有两项。
describe('引用筛选 · 不出现「当前记录」', () => {
  it('筛选条件选了指向别的对象的关联字段，值来源仍只有「固定值 / 当前字段」', async () => {
    api.design.mockResolvedValue({ publishedVersion: 1, draft: { objectName: '会计凭证录入', objectCode: 'kjpz' } })
    api.version.mockResolvedValue({
      definition: {
        fields: [field(FieldType.REFERENCE, 'flow', '资金流水')],
        fieldOptions: {},
        relations: [
          { id: 'r', code: 'flow', name: '资金流水', kind: 'REFERENCE', targetObjectId: 'self-obj', fieldId: 'flow' }
        ]
      }
    })
    const { host } = await mount(ReferenceRuleEditor, {
      modelValue: {
        labelFieldId: null,
        filter: [{ fieldId: 'flow', operator: 'eq', valueSource: 'CONSTANT', value: null }]
      },
      relation: { id: 'x', code: 'voucher', name: '凭证', kind: 'REFERENCE', targetObjectId: 'voucher', fieldId: 'v' },
      formGroups: [],
      filterable: true
    })
    const select = host.querySelectorAll<HTMLElement>('.rule-row > .ant-select')[2]
    const selector = select?.querySelector('.ant-select-selector')
    if (!selector) throw new Error('缺少值来源下拉')
    const before = new Set(Array.from(document.querySelectorAll('.ant-select-dropdown')))
    selector.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    await flush()
    const dropdown = Array.from(document.querySelectorAll('.ant-select-dropdown')).find(node => !before.has(node))
    if (!dropdown) throw new Error('值来源下拉未打开')
    expect(Array.from(dropdown.querySelectorAll('.ant-select-item-option')).map(node => node.textContent)).toEqual([
      '固定值',
      '当前字段'
    ])
    expect(host.textContent).not.toContain('当前记录')
  })
})

describe('挑取值来源', () => {
  it('来源字段可用公共字典，预览显示字典项，不再提示候选为空', async () => {
    const dictField = field(FieldType.SELECT, 'pay', '支付方式')
    api.version.mockResolvedValue({
      definition: {
        fields: [dictField, field(FieldType.TEXT, 'bank', '开户行')],
        fieldOptions: {
          pay: options({
            selection: {
              kind: 'SYSTEM_DICTIONARY',
              directory: null,
              dictionaryType: 'pay_type',
              rootIds: [],
              includeDescendants: false,
              organizationTypes: [],
              defaultMode: 'NONE'
            }
          })
        },
        relations: []
      }
    })
    http.get.mockImplementation(async (url: string) =>
      url === '/system/dict-data/list-all-simple'
        ? [
            { dictType: 'pay_type', value: 'cash', label: '现金' },
            { dictType: 'other', value: 'x', label: '无关' }
          ]
        : []
    )
    const target = field(FieldType.SELECT, 'target', '付款方式')
    const { host, state } = await mount(SelectionSourceEditor, {
      field: target,
      options: options({
        selection: {
          kind: 'OBJECT_FIELD_OPTIONS',
          directory: null,
          dictionaryType: null,
          rootIds: [],
          includeDescendants: false,
          organizationTypes: [],
          defaultMode: 'NONE',
          sourceObjectId: 'src',
          sourceFieldId: 'pay'
        }
      })
    })
    await flush()
    expect(state.sourceFieldChoices.map((item: { label: string }) => item.label)).toEqual(['支付方式（公共字典）'])
    const preview = host.querySelector('.source-preview')!
    expect(Array.from(preview.querySelectorAll('.ant-tag')).map(node => node.textContent?.trim())).toEqual(['现金'])
    expect(preview.textContent).toContain('公共字典（pay_type）')
    expect(preview.querySelector('.ant-alert')).toBeNull()
  })
})

// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, type App } from 'vue'
import ObjectGrantFields from '@/views/nocode/components/ObjectGrantFields.vue'
import type { ObjectGrant } from '@/types/nocode/authorization'
import type { PublishedDefinition } from '@/types/nocode/application'

const mocks = vi.hoisted(() => ({ confirm: vi.fn() }))
vi.mock('ant-design-vue', () => ({
  Modal: { confirm: mocks.confirm },
  Collapse: { render: () => null },
  CollapsePanel: { render: () => null }
}))

// 底座控件用最小替身；授权表单、「全部 / 清单」选择和读写联动走真实组件。
let app: App | undefined
let host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 6; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function controls(instance: App) {
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of [
    'AForm',
    'ACollapse',
    'ACollapsePanel',
    'ARadioGroup',
    'ARadio',
    'ACheckboxGroup',
    'ASpace',
    'AInput'
  ])
    instance.component(name, plain)
  instance.component(
    'AFormItem',
    defineComponent({
      props: ['label'],
      setup:
        (p, { slots }) =>
        () =>
          h('div', { 'data-item': p.label }, [h('span', { class: 'item-label' }, p.label), slots.default?.()])
    })
  )
  instance.component(
    'AAlert',
    defineComponent({
      props: ['message', 'type'],
      setup: p => () => h('div', { role: 'alert', 'data-type': p.type }, p.message)
    })
  )
  instance.component(
    'AButton',
    defineComponent({
      props: ['disabled'],
      setup:
        (p, { slots }) =>
        () =>
          h('button', { type: 'button', disabled: !!p.disabled }, slots.default?.())
    })
  )
  instance.component(
    'ACheckbox',
    defineComponent({
      props: ['checked', 'disabled'],
      emits: ['change'],
      setup:
        (p, { emit, slots }) =>
        () =>
          h('label', [
            h('input', {
              type: 'checkbox',
              checked: !!p.checked,
              disabled: !!p.disabled,
              onChange: (e: Event) => emit('change', e)
            }),
            slots.default?.()
          ])
    })
  )
  instance.component(
    'ASelect',
    defineComponent({
      props: ['value', 'disabled', 'options', 'mode'],
      emits: ['update:value', 'change'],
      setup:
        (p, { emit }) =>
        () =>
          h(
            'select',
            {
              disabled: !!p.disabled,
              multiple: p.mode === 'multiple',
              onChange: (e: Event) => {
                const value = Array.from((e.target as HTMLSelectElement).selectedOptions).map(o => o.value)
                emit('update:value', value)
                emit('change', value)
              }
            },
            (p.options || []).map((o: { label: string; value: string; disabled?: boolean }) =>
              h(
                'option',
                { value: o.value, disabled: o.disabled, selected: (p.value || []).includes(o.value) },
                o.label
              )
            )
          )
    })
  )
}
const definition = {
  objectName: '客户',
  fields: [
    { id: 'name', name: '名称', type: 'TEXT' },
    { id: 'amount', name: '金额', type: 'NUMBER' },
    { id: 'total', name: '合计', type: 'FORMULA' },
    { id: 'old', name: '旧字段', type: 'TEXT' }
  ],
  fieldOptions: { old: { state: 'INACTIVE' } },
  details: [
    { id: 'lines', name: '明细行', state: 'ACTIVE' },
    { id: 'gone', name: '停用明细', state: 'INACTIVE' }
  ],
  relations: [
    { id: 'tags', name: '标签', kind: 'MANY_TO_MANY' },
    { id: 'owner', name: '负责人', kind: 'REFERENCE' }
  ]
} as unknown as PublishedDefinition
const grant = (patch: Partial<ObjectGrant> = {}): ObjectGrant => ({
  objectId: 'customer',
  actions: ['READ', 'UPDATE'],
  scope: 'ALL',
  readFields: [],
  writeFields: [],
  readDetails: [],
  writeDetails: [],
  readRelations: [],
  writeRelations: [],
  ...patch
})
function mount(initial: ObjectGrant, extra: Record<string, unknown> = {}) {
  const model = ref(initial)
  const onChange = vi.fn()
  app = createApp(() =>
    h(ObjectGrantFields, {
      modelValue: model.value,
      'onUpdate:modelValue': (value: ObjectGrant) => (model.value = value),
      definition,
      onChange,
      ...extra
    })
  )
  controls(app)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  return { model, onChange }
}
function must<T>(value: T | null | undefined, what: string): T {
  if (value == null) throw new Error(`${what} 不存在`)
  return value
}
const list = (label: string) =>
  must(host.querySelector<HTMLSelectElement>(`select[aria-label="${label}"]`), `选择框「${label}」`)
const item = (label: string) => must(host.querySelector(`[data-item="${label}"]`), `表单项「${label}」`)
const optionsOf = (label: string) =>
  Array.from(list(label).options).map(o => ({ value: o.value, text: o.textContent, disabled: o.disabled }))
const allBox = (label: string) =>
  Array.from(item(label).querySelectorAll('label'))
    .find(l => l.textContent?.trim() === '全部')
    ?.querySelector('input') as HTMLInputElement | undefined
async function toggleAll(label: string) {
  const box = must(allBox(label), `「${label}」的全部勾选项`)
  box.checked = !box.checked
  box.dispatchEvent(new Event('change', { bubbles: true }))
  await flush()
}
async function choose(label: string, values: string[]) {
  const select = list(label)
  for (const option of Array.from(select.options)) option.selected = values.includes(option.value)
  select.dispatchEvent(new Event('change', { bubbles: true }))
  await flush()
}
const full = (patch: Partial<ObjectGrant> = {}) =>
  grant({
    readFields: ['*'],
    writeFields: ['*'],
    readDetails: ['*'],
    writeDetails: ['*'],
    readRelations: ['*'],
    writeRelations: ['*'],
    ...patch
  })
beforeEach(() => vi.resetAllMocks())
afterEach(() => {
  app?.unmount()
  host?.remove()
  app = undefined
})

describe('授权表单的六个清单', () => {
  it('六个清单都是「全部」：都置灰，只显示「全部 · 当前 N 项」，N 不含已停用字段与写不了的字段', async () => {
    mount(full())
    await flush()
    const text = (label: string) => optionsOf(label).map(o => o.text)
    expect(text('可查看字段')).toEqual(['全部 · 当前 3 项'])
    expect(text('可填写和修改字段')).toEqual(['全部 · 当前 2 项'])
    expect(text('可查看内部明细')).toEqual(['全部 · 当前 1 项'])
    expect(text('可修改内部明细')).toEqual(['全部 · 当前 1 项'])
    expect(text('可查看多对多关系')).toEqual(['全部 · 当前 1 项'])
    expect(text('可修改多对多关系')).toEqual(['全部 · 当前 1 项'])
    for (const label of ['可查看字段', '可填写和修改字段', '可查看内部明细', '可修改多对多关系'])
      expect(list(label).disabled, label).toBe(true)
    expect(host.textContent).toContain('随数据对象自动同步')
  })
  it('已停用的字段不是选项；清单里残留的已停用字段只提示个数，不显示名称或 id', async () => {
    mount(grant({ readFields: ['name', 'old', '90417'] }))
    await flush()
    expect(optionsOf('可查看字段').map(o => o.value)).toEqual(['name', 'amount', 'total'])
    expect(item('可查看字段').textContent).toContain('另有 2 个已停用的项，保存时自动移除')
    expect(host.innerHTML).not.toContain('90417')
    expect(host.textContent).not.toContain('旧字段')
  })
  it('读是「全部」时，写清单的候选是全部选项', async () => {
    mount(grant({ readFields: ['*'] }))
    await flush()
    expect(optionsOf('可填写和修改字段')).toEqual([
      { value: 'name', text: '名称', disabled: false },
      { value: 'amount', text: '金额', disabled: false },
      { value: 'total', text: '合计', disabled: true }
    ])
  })
  it('读是清单时，写清单的候选只有读清单里的', async () => {
    mount(grant({ readFields: ['amount'] }))
    await flush()
    expect(optionsOf('可填写和修改字段').map(o => o.value)).toEqual(['amount'])
  })
  it('读从「全部」切到清单后，写里超出的项被去掉', async () => {
    const { model, onChange } = mount(grant({ readFields: ['*'], writeFields: ['name', 'amount'] }))
    await flush()
    await toggleAll('可查看字段')
    expect(mocks.confirm).not.toHaveBeenCalled()
    expect(model.value.readFields).toEqual(['name', 'amount', 'total'])
    expect(model.value.writeFields).toEqual(['name', 'amount'])
    await choose('可查看字段', ['name', 'total'])
    expect(model.value.readFields).toEqual(['name', 'total'])
    expect(model.value.writeFields).toEqual(['name'])
    expect(onChange).toHaveBeenCalledTimes(2)
  })
  it('读是「全部」时改别的地方，写清单原样不动', async () => {
    const { model } = mount(grant({ readFields: ['*'], writeFields: ['amount'] }))
    await flush()
    await choose('可填写和修改字段', ['amount', 'name'])
    expect(model.value.readFields).toEqual(['*'])
    expect(model.value.writeFields).toEqual(['name', 'amount'])
  })
  it('读是清单、写是「全部」时，改读清单后写保持「全部」', async () => {
    const { model } = mount(
      grant({ readFields: ['name', 'amount'], writeFields: ['*'], readDetails: ['lines'], writeDetails: ['*'] })
    )
    await flush()
    await choose('可查看字段', ['name'])
    expect(model.value.readFields).toEqual(['name'])
    expect(model.value.writeFields).toEqual(['*'])
    expect(model.value.writeDetails).toEqual(['*'])
    expect(optionsOf('可填写和修改字段').map(o => o.text)).toEqual(['全部 · 当前 1 项'])
  })
  it('明细与多对多关系：读写联动同样成立', async () => {
    const { model } = mount(
      grant({ readDetails: ['lines'], writeDetails: ['lines'], readRelations: ['tags'], writeRelations: ['tags'] })
    )
    await flush()
    await choose('可查看内部明细', [])
    expect(model.value.writeDetails).toEqual([])
    await choose('可查看多对多关系', [])
    expect(model.value.writeRelations).toEqual([])
  })
  it('旧数据缺少关系清单时不报错，联动后落成空清单', async () => {
    const legacy = grant({ readFields: ['name'] })
    delete legacy.readRelations
    delete legacy.writeRelations
    const { model } = mount(legacy)
    await flush()
    await choose('可查看字段', ['name', 'amount'])
    expect(model.value.writeRelations).toEqual([])
  })
})

describe('上一层的范围', () => {
  it('上限的清单是「全部」时所有选项可选', async () => {
    mount(grant({ readFields: ['name'], readDetails: [], readRelations: [] }), { ceiling: full() })
    await flush()
    expect(optionsOf('可查看字段').map(o => o.disabled)).toEqual([false, false, false])
    expect(optionsOf('可查看内部明细').map(o => o.disabled)).toEqual([false])
    expect(optionsOf('可查看多对多关系').map(o => o.disabled)).toEqual([false])
    expect(optionsOf('可填写和修改字段')).toEqual([{ value: 'name', text: '名称', disabled: false }])
    expect(host.textContent).not.toContain('随数据对象自动同步')
  })
  it('上限是清单时不在其中的选项禁用', async () => {
    mount(grant({ readFields: ['name', 'amount'] }), {
      ceiling: grant({ readFields: ['name'], writeFields: ['name'] })
    })
    await flush()
    expect(optionsOf('可查看字段')).toEqual([
      { value: 'name', text: '名称', disabled: false },
      { value: 'amount', text: '金额', disabled: true },
      { value: 'total', text: '合计', disabled: true }
    ])
    expect(optionsOf('可填写和修改字段')).toEqual([
      { value: 'name', text: '名称', disabled: false },
      { value: 'amount', text: '金额', disabled: true }
    ])
  })
  it('上限可修改是「全部」、可查看是清单：可修改只放开上限可查看的那些', async () => {
    mount(grant({ readFields: ['*'] }), { ceiling: grant({ readFields: ['name'], writeFields: ['*'] }) })
    await flush()
    expect(optionsOf('可填写和修改字段')).toEqual([
      { value: 'name', text: '名称', disabled: false },
      { value: 'amount', text: '金额', disabled: true },
      { value: 'total', text: '合计', disabled: true }
    ])
  })
  it('成员选「全部」：N 按上一层允许的数', async () => {
    mount(grant({ readFields: ['*'] }), { ceiling: grant({ readFields: ['name', 'amount'] }) })
    await flush()
    expect(optionsOf('可查看字段').map(o => o.text)).toEqual(['全部 · 当前 2 项'])
    expect(host.textContent).toContain('随应用数据权限自动同步')
  })
  it('upperName 由使用处指定时逐字显示', async () => {
    mount(grant({ readFields: ['*'] }), { ceiling: full(), upperName: '入口允许范围' })
    await flush()
    expect(host.textContent).toContain('随入口允许范围自动同步')
  })
  it('writeAllAllowed=false：三个可修改清单不提供「全部」，可查看清单照常提供', async () => {
    mount(grant({ readFields: ['*'], readDetails: ['*'], readRelations: ['*'] }), {
      ceiling: full(),
      writeAllAllowed: false
    })
    await flush()
    for (const label of ['可填写和修改字段', '可修改内部明细', '可修改多对多关系'])
      expect(allBox(label), label).toBeUndefined()
    for (const label of ['可查看字段', '可查看内部明细', '可查看多对多关系']) expect(allBox(label), label).toBeDefined()
  })
  it('默认（不传 writeAllAllowed）可修改清单提供「全部」', async () => {
    mount(grant({ readFields: ['*'] }))
    await flush()
    expect(allBox('可填写和修改字段')).toBeDefined()
  })
})

describe('计算取数不再让人勾', () => {
  const notice = () => host.querySelector('[role="alert"]')
  const shown = () => must(notice(), '计算取数状态行')
  it('全部记录且查看无条件：普通提示，文案逐字', async () => {
    mount(full())
    await flush()
    expect(shown().textContent).toBe('本应用的公式、联动、自动更新可以按这个对象的全部数据计算。')
    expect(shown().getAttribute('data-type')).toBe('info')
  })
  it('记录范围是本人创建：警告，文案逐字', async () => {
    mount(full({ scope: 'OWN' }))
    await flush()
    expect(shown().textContent).toBe(
      '当前不能用于系统计算：记录范围是“当前操作者创建的记录”。改成“全部记录”后，本应用的公式、联动、自动更新才能读取这个对象。'
    )
    expect(shown().getAttribute('data-type')).toBe('warning')
  })
  it('查看设了记录条件：警告，文案逐字', async () => {
    mount(full({ actionScopes: { READ: { logic: 'AND', conditions: [], groups: [] } } }))
    await flush()
    expect(shown().textContent).toBe('当前不能用于系统计算：“查看”设了记录条件。取消该条件后才能用于系统计算。')
    expect(shown().getAttribute('data-type')).toBe('warning')
  })
  it('只给修改设了记录条件不影响系统计算', async () => {
    mount(full({ actionScopes: { UPDATE: { logic: 'AND', conditions: [], groups: [] } } }))
    await flush()
    expect(shown().getAttribute('data-type')).toBe('info')
  })
  it('成员 / 入口授权里（有上一层）不显示这一行', async () => {
    mount(full(), { ceiling: full() })
    await flush()
    expect(notice()).toBeNull()
  })
  it('不再有「允许本应用计算规则读取的源字段」这个框，折叠标题不再提计算取数', async () => {
    mount(full({ computeFields: ['name'] }))
    await flush()
    expect(host.textContent).not.toContain('允许本应用计算规则读取的源字段')
    expect(host.querySelector('select[aria-label="计算取数字段"]')).toBeNull()
    expect(host.querySelector('[data-item="允许本应用计算规则读取的源字段"]')).toBeNull()
    // 非精简模式下折叠面板是普通 div，标题落在 header 属性上；旧的计算取数不再算作「已配置」。
    expect(must(host.querySelector('[header]'), '高级设置面板').getAttribute('header')).toBe('高级设置 · 记录条件')
  })
  it('带着旧 computeFields 的授权打开再编辑，发出去的对象里它原样带着', async () => {
    const { model, onChange } = mount(grant({ readFields: ['name', 'amount'], computeFields: ['name', 'amount'] }))
    await flush()
    await choose('可查看字段', ['amount'])
    expect(onChange).toHaveBeenCalled()
    expect(model.value.readFields).toEqual(['amount'])
    expect(model.value.computeFields).toEqual(['name', 'amount'])
  })
})

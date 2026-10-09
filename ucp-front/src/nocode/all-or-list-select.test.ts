// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, type App } from 'vue'
import AllOrListSelect from '@/views/nocode/components/AllOrListSelect.vue'
import GrantFieldSelect from '@/views/nocode/components/GrantFieldSelect.vue'

const mocks = vi.hoisted(() => ({ confirm: vi.fn() }))
vi.mock('ant-design-vue', () => ({ Modal: { confirm: mocks.confirm } }))

// 底座控件用最小替身（写法同 authorization-save-state.test.ts）；「全部 / 清单」的逻辑走真实组件。
let app: App | undefined
let host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 6; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function controls(instance: App) {
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
            (p.options || []).map((o: { label: string; value: string; disabled?: boolean; title?: string }) =>
              h(
                'option',
                { value: o.value, disabled: o.disabled, title: o.title, selected: (p.value || []).includes(o.value) },
                o.label
              )
            )
          )
    })
  )
}
const options = [
  { value: 'name', label: '名称' },
  { value: 'amount', label: '金额' },
  { value: 'secret', label: '机密', disabled: true }
]
function mount(initial: string[], extra: Record<string, unknown> = {}) {
  const model = ref(initial)
  const onChange = vi.fn()
  const onUpdate = vi.fn((value: string[]) => (model.value = value))
  app = createApp(() =>
    h(AllOrListSelect, {
      modelValue: model.value,
      'onUpdate:modelValue': onUpdate,
      label: '可查看字段',
      upperName: '应用数据权限',
      options,
      onChange,
      ...extra
    })
  )
  controls(app)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  return { model, onChange, onUpdate }
}
function must<T>(value: T | null | undefined, what: string): T {
  if (value == null) throw new Error(`${what} 不存在`)
  return value
}
const allBox = () =>
  Array.from(host.querySelectorAll('label'))
    .find(l => l.textContent?.trim() === '全部')
    ?.querySelector('input') as HTMLInputElement | undefined
const theSelect = () => must(host.querySelector('select'), '选择框')
const dialog = () => must(mocks.confirm.mock.calls[0], '确认框')[0]
async function toggleAll() {
  const box = must(allBox(), '「全部」勾选项')
  box.checked = !box.checked
  box.dispatchEvent(new Event('change', { bubbles: true }))
  await flush()
}
const click = async (label: string) => {
  const button = Array.from(host.querySelectorAll('button')).find(b => b.textContent?.trim() === label)
  expect(button, label).toBeDefined()
  must(button, label).click()
  await flush()
}
beforeEach(() => vi.resetAllMocks())
afterEach(() => {
  app?.unmount()
  host?.remove()
  app = undefined
})

describe('授权清单的「全部 / 清单」选择', () => {
  it('全部：勾选项勾上，选择框禁用，框里只有「全部 · 当前 N 项」，N 不含禁用选项', async () => {
    mount(['*'])
    await flush()
    expect(must(allBox(), '「全部」勾选项').checked).toBe(true)
    expect(host.textContent).toContain('随应用数据权限自动同步')
    const select = theSelect()
    expect(select.disabled).toBe(true)
    expect(Array.from(select.options).map(o => o.textContent)).toEqual(['全部 · 当前 2 项'])
  })
  it('全部：框里和界面上没有任何单项文字', async () => {
    mount(['*'])
    await flush()
    for (const option of options) expect(host.textContent).not.toContain(option.label)
    expect(host.textContent).not.toContain('已选')
  })
  it('全部：点「查看当前包含哪些」才列出只读清单，只含未禁用的选项', async () => {
    const { onChange, onUpdate } = mount(['*'])
    await flush()
    await click('查看当前包含哪些')
    expect(Array.from(host.querySelectorAll('li')).map(li => li.textContent?.trim())).toEqual(['名称', '金额'])
    expect(host.querySelector('li button')).toBeNull()
    expect(onUpdate).not.toHaveBeenCalled()
    expect(onChange).not.toHaveBeenCalled()
  })
  it('取消勾选：发出未禁用的全部选项（选项顺序），不弹确认', async () => {
    const { model, onChange } = mount(['*'])
    await flush()
    await toggleAll()
    expect(mocks.confirm).not.toHaveBeenCalled()
    expect(model.value).toEqual(['name', 'amount'])
    expect(onChange).toHaveBeenCalledTimes(1)
  })
  it('清单 → 全部，会多出东西：弹确认（文案逐字），确认后才发出全部', async () => {
    const { model, onChange } = mount(['name'])
    await flush()
    await toggleAll()
    expect(mocks.confirm).toHaveBeenCalledTimes(1)
    const shown = dialog()
    expect(shown.title).toBe('改为“全部”？')
    expect(shown.content).toBe('将增加 1 项：金额。以后新增的也会自动包含。')
    expect(shown.okText).toBe('改为全部')
    expect(shown.cancelText).toBe('保持现状')
    expect(model.value).toEqual(['name'])
    expect(onChange).not.toHaveBeenCalled()
    shown.onOk()
    await flush()
    expect(model.value).toEqual(['*'])
    expect(onChange).toHaveBeenCalledTimes(1)
  })
  it('清单 → 全部，取消确认后值不变', async () => {
    const { model, onChange, onUpdate } = mount([])
    await flush()
    await toggleAll()
    const shown = dialog()
    expect(shown.content).toBe('将增加 2 项：名称、金额。以后新增的也会自动包含。')
    shown.onCancel?.()
    await flush()
    expect(model.value).toEqual([])
    expect(onUpdate).not.toHaveBeenCalled()
    expect(onChange).not.toHaveBeenCalled()
  })
  it('清单 → 全部，多出超过 8 项时只列 8 个并写“等”', async () => {
    const many = Array.from({ length: 10 }, (_, i) => ({ value: `f${i}`, label: `字段${i}` }))
    mount([], { options: many })
    await flush()
    await toggleAll()
    expect(dialog().content).toBe(
      '将增加 10 项：字段0、字段1、字段2、字段3、字段4、字段5、字段6、字段7等。以后新增的也会自动包含。'
    )
  })
  it('清单 → 全部，不会多出东西：不弹确认，直接全部', async () => {
    const { model, onChange } = mount(['amount', 'name'])
    await flush()
    await toggleAll()
    expect(mocks.confirm).not.toHaveBeenCalled()
    expect(model.value).toEqual(['*'])
    expect(onChange).toHaveBeenCalledTimes(1)
  })
  it('清单里有已停用项：打开时不发任何事件，不改数据', async () => {
    const { model, onChange, onUpdate } = mount(['name', '90417'])
    await flush()
    expect(onUpdate).not.toHaveBeenCalled()
    expect(onChange).not.toHaveBeenCalled()
    expect(model.value).toEqual(['name', '90417'])
  })
  it('清单里有已停用项：不显示它的 id，只给一行提示', async () => {
    mount(['name', '90417'])
    await flush()
    expect(host.textContent).not.toContain('90417')
    expect(host.innerHTML).not.toContain('90417')
    expect(host.textContent).toContain('另有 1 个已停用的项，保存时自动移除')
    expect(host.textContent).toContain('已选 1 项')
  })
  it('清单里有已停用项：展开已选清单也不出现它', async () => {
    mount(['name', '90417'])
    await flush()
    await click('查看全部')
    expect(Array.from(host.querySelectorAll('li')).map(li => li.textContent?.trim())).toEqual(['名称'])
    expect(host.innerHTML).not.toContain('90417')
  })
  it('清单里有已停用项：用户再选一项后，发出的值不含已停用项', async () => {
    const { model, onChange } = mount(['name', '90417'])
    await flush()
    const select = theSelect()
    for (const option of Array.from(select.options)) option.selected = ['name', 'amount'].includes(option.value)
    select.dispatchEvent(new Event('change', { bubbles: true }))
    await flush()
    expect(model.value).toEqual(['name', 'amount'])
    expect(onChange).toHaveBeenCalled()
    expect(host.textContent).not.toContain('已停用的项')
  })
  it('清单：上一层没允许的选项禁用并提示「上一层未允许」；没有「全选可用项」，保留清空', async () => {
    const { model } = mount(['name'])
    await flush()
    const blocked = must(
      Array.from(theSelect().options).find(o => o.value === 'secret'),
      '被禁用的选项'
    )
    expect(blocked.disabled).toBe(true)
    expect(blocked.title).toBe('上一层未允许')
    expect(Array.from(host.querySelectorAll('button')).some(b => b.textContent?.trim() === '全选可用项')).toBe(false)
    await click('清空')
    expect(model.value).toEqual([])
  })
  it('allowAll=false：没有「全部」勾选项，其余同清单', async () => {
    mount(['name'], { allowAll: false })
    await flush()
    expect(allBox()).toBeUndefined()
    expect(theSelect().disabled).toBe(false)
    expect(host.textContent).toContain('已选 1 项')
  })
  it('只读：全部显示「全部（当前 N 项）」，可展开清单，没有勾选项和选择框', async () => {
    mount(['*'], { readonly: true })
    await flush()
    expect(host.textContent).toContain('全部（当前 2 项）')
    expect(allBox()).toBeUndefined()
    expect(host.querySelector('select')).toBeNull()
    await click('查看当前包含哪些')
    expect(Array.from(host.querySelectorAll('li')).map(li => li.textContent?.trim())).toEqual(['名称', '金额'])
  })
  it('只读：空清单显示「未授权」，清单显示已选项', async () => {
    mount([], { readonly: true })
    await flush()
    expect(host.textContent).toContain('未授权')
    app?.unmount()
    host.remove()
    mount(['amount', '90417'], { readonly: true })
    await flush()
    expect(Array.from(host.querySelectorAll('li')).map(li => li.textContent?.trim())).toEqual(['金额'])
    expect(host.innerHTML).not.toContain('90417')
    expect(host.textContent).not.toContain('已停用的项')
  })
})

describe('GrantFieldSelect 的「全选可用项」', () => {
  function mountSelect(extra: Record<string, unknown>) {
    app = createApp(() => h(GrantFieldSelect, { modelValue: [], label: '可查看字段', options, ...extra }))
    controls(app)
    host = document.createElement('div')
    document.body.append(host)
    app.mount(host)
  }
  const hasSelectAll = () =>
    Array.from(host.querySelectorAll('button')).some(b => b.textContent?.trim() === '全选可用项')
  it('不传 hideSelectAll 时照常显示（对照组）', async () => {
    mountSelect({})
    await flush()
    expect(hasSelectAll()).toBe(true)
  })
  it('传 hideSelectAll 时不显示', async () => {
    mountSelect({ hideSelectAll: true })
    await flush()
    expect(hasSelectAll()).toBe(false)
  })
})

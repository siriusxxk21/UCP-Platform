// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref, type App } from 'vue'
import Antd from 'ant-design-vue'
import ObjectGrantFields from '@/views/nocode/components/ObjectGrantFields.vue'
import ObjectFollowCell from '@/views/nocode/application/components/ObjectFollowCell.vue'
import type { ObjectGrant } from '@/types/nocode/authorization'
import type { ObjectFollow, PublishedDefinition } from '@/types/nocode/application'

/**
 * 用真实组件库（不是替身）挂载：确认「全部」的勾选项、置灰的选择框、跟随开关
 * 与组件库的属性 / 事件形状对得上。其余行为由替身用例覆盖。
 */
let app: App | undefined, host: HTMLDivElement
const errors: unknown[] = []
const flush = async () => {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function must<T>(value: T | null | undefined, what: string): T {
  if (value == null) throw new Error(`${what} 不存在`)
  return value
}
function mount(render: () => ReturnType<typeof h>) {
  app = createApp(render)
  app.use(Antd)
  app.config.errorHandler = error => errors.push(error)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
}
const definition = {
  objectName: '客户',
  fields: [
    { id: 'name', name: '名称', type: 'TEXT' },
    { id: 'amount', name: '金额', type: 'NUMBER' }
  ],
  fieldOptions: {},
  details: [],
  relations: []
} as unknown as PublishedDefinition
const grant = (patch: Partial<ObjectGrant> = {}): ObjectGrant => ({
  objectId: 'customer',
  actions: ['READ', 'UPDATE'],
  scope: 'ALL',
  readFields: ['*'],
  writeFields: ['name'],
  readDetails: [],
  writeDetails: [],
  readRelations: [],
  writeRelations: [],
  ...patch
})
const item = (label: string) =>
  must(
    Array.from(host.querySelectorAll<HTMLElement>('.ant-form-item')).find(
      el => el.querySelector('.ant-form-item-label')?.textContent?.trim() === label
    ),
    `表单项「${label}」`
  )
const allBox = (label: string) => must(item(label).querySelector<HTMLInputElement>('.ant-checkbox-input'), '全部勾选项')
beforeEach(() => {
  errors.length = 0
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({ matches: false, addListener: vi.fn(), removeListener: vi.fn() }))
  )
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

describe('真实组件库下的授权表单', () => {
  function mountFields(initial: ObjectGrant) {
    const model = ref(initial)
    const onChange = vi.fn()
    mount(() =>
      h(ObjectGrantFields, {
        modelValue: model.value,
        'onUpdate:modelValue': (value: ObjectGrant) => (model.value = value),
        definition,
        onChange
      })
    )
    return { model, onChange }
  }
  it('「全部」：勾选项勾上，选择框置灰，框里只有一个「全部 · 当前 N 项」标签', async () => {
    mountFields(grant())
    await flush()
    expect(errors).toEqual([])
    const read = item('可查看字段')
    expect(allBox('可查看字段').checked).toBe(true)
    expect(read.querySelector('.ant-select')?.classList.contains('ant-select-disabled')).toBe(true)
    expect(Array.from(read.querySelectorAll('.ant-select-selection-item')).map(el => el.textContent?.trim())).toEqual([
      '全部 · 当前 2 项'
    ])
    expect(read.textContent).toContain('随数据对象自动同步')
    expect(read.textContent).not.toContain('名称')
  })
  it('清单：选择框可用，勾选项没勾，保留已选个数', async () => {
    mountFields(grant())
    await flush()
    const write = item('可填写和修改字段')
    expect(allBox('可填写和修改字段').checked).toBe(false)
    expect(write.querySelector('.ant-select')?.classList.contains('ant-select-disabled')).toBe(false)
    // 已选标签按宽度自适应折叠，jsdom 没有布局，这里只看个数。
    expect(write.textContent).toContain('已选 1 项')
  })
  it('取消「全部」的勾选：值变成当时可选的全部选项，并发出变更', async () => {
    const { model, onChange } = mountFields(grant())
    await flush()
    allBox('可查看字段').click()
    await flush()
    expect(errors).toEqual([])
    expect(model.value.readFields).toEqual(['name', 'amount'])
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(allBox('可查看字段').checked).toBe(false)
  })
  it('清单 → 全部且不会多出东西：直接变成全部', async () => {
    const { model } = mountFields(grant({ readFields: ['name', 'amount'] }))
    await flush()
    allBox('可查看字段').click()
    await flush()
    expect(errors).toEqual([])
    expect(model.value.readFields).toEqual(['*'])
  })
  it('计算取数状态行是一条普通提示', async () => {
    mountFields(grant())
    await flush()
    const notice = must(host.querySelector('.ant-alert'), '状态行')
    expect(notice.classList.contains('ant-alert-info')).toBe(true)
    expect(notice.textContent).toContain('本应用的公式、联动、自动更新可以按这个对象的全部数据计算。')
  })
})

describe('真实组件库下的跟随单元格', () => {
  const follow = (patch: Partial<ObjectFollow> = {}): ObjectFollow => ({
    objectId: '2001',
    enabled: true,
    state: 'FOLLOWING',
    pinnedVersion: 11,
    latestVersion: 12,
    pendingVersion: null,
    pendingCode: null,
    pendingReason: null,
    followedAt: null,
    revision: 0,
    ...patch
  })
  it('拨开关发出目标状态（布尔值），点「立即跟随」发出 run', async () => {
    const onToggle = vi.fn(),
      onRun = vi.fn()
    mount(() => h(ObjectFollowCell, { follow: follow(), onToggle, onRun }))
    await flush()
    expect(errors).toEqual([])
    const toggler = must(host.querySelector<HTMLButtonElement>('button.ant-switch'), '开关')
    expect(toggler.getAttribute('aria-checked')).toBe('true')
    toggler.click()
    await flush()
    expect(onToggle).toHaveBeenCalledWith(false)
    expect(host.textContent).toContain('待跟随')
    must(
      Array.from(host.querySelectorAll('button')).find(b => b.textContent?.replace(/\s/g, '') === '立即跟随'),
      '立即跟随'
    ).click()
    expect(onRun).toHaveBeenCalledTimes(1)
  })
  it('有未保存修改时开关与按钮是禁用状态', async () => {
    const onToggle = vi.fn()
    mount(() => h(ObjectFollowCell, { follow: follow(), blockedReason: '请先保存或放弃当前修改', onToggle }))
    await flush()
    const toggler = must(host.querySelector<HTMLButtonElement>('button.ant-switch'), '开关')
    expect(toggler.disabled).toBe(true)
    toggler.click()
    await flush()
    expect(onToggle).not.toHaveBeenCalled()
  })
})

// @vitest-environment jsdom
// 运行端数据视图列表的表格与透视表用同一套字体、字号、内边距：两处读的是同一组样式值（style.css 里的 --data-table-*）。
// 蓝色可点的标题链接、操作列按钮（含红色的删除）照常渲染：统一的只是字号与间距，不碰颜色。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App, type Component } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import Antd from 'ant-design-vue'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const source = (path: string) => readFileSync(fileURLToPath(new URL('../' + path, import.meta.url)), 'utf8')
const rootStyle = source('style.css'),
  pivotSource = source('views/nocode/application/components/ReportPivotTable.vue'),
  listSource = source('views/nocode/application/components/BusinessRecords.vue')

const api = vi.hoisted(() => ({
  model: vi.fn(),
  page: vi.fn(),
  get: vi.fn(),
  viewChildren: vi.fn(),
  viewModel: vi.fn(),
  selection: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api }) }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'data-table-typography-test' } }) }))
vi.mock('@/views/nocode/application/components/RecordSurface.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['open'],
      setup:
        (props, { slots }) =>
        () =>
          props.open ? h('aside', { 'data-surface': true }, slots.default?.()) : null
    })
  }
})
vi.mock('@/views/nocode/application/components/RecordEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/RecordQueryField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/BusinessFileField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/DataViewChildren.vue', () => ({ default: { render: () => null } }))
import BusinessRecords from '@/views/nocode/application/components/BusinessRecords.vue'

/** 某条样式规则里某个属性的值（按选择器原文找规则）。 */
function declared(source: string, selector: string, property: string) {
  const start = source.indexOf(selector + ' {')
  if (start < 0) throw new Error('没有这条规则：' + selector)
  const block = source.slice(start, source.indexOf('}', start))
  return block.match(new RegExp('(?:^|[;{\\s])' + property + ':\\s*([^;]+);'))?.[1]?.trim()
}
const variable = (name: string) => declared(rootStyle, ':root', name)
/** 透视表那边写的可以是字面值，也可以已经改成引用这个变量；两种都算「读的是同一个值」。 */
const same = (value: string | undefined, name: string) => value === variable(name) || value === `var(${name})`

describe('列表表格与透视表读同一组样式值', () => {
  it('字号、内边距、表头字重：透视表现在生效的值就是这组变量的值', () => {
    expect(variable('--data-table-font-size')).toBe('13px')
    expect(variable('--data-table-cell-padding')).toBe('6px 10px')
    expect(variable('--data-table-header-font-weight')).toBe('600')
    expect(variable('--data-table-line-height')).toBe('1.5')
    expect(same(declared(pivotSource, '.pivot-table', 'font-size'), '--data-table-font-size')).toBe(true)
    expect(same(declared(pivotSource, '.pivot-table td', 'padding'), '--data-table-cell-padding')).toBe(true)
    expect(same(declared(pivotSource, '.pivot-head', 'font-weight'), '--data-table-header-font-weight')).toBe(true)
  })

  it('列表的表头、单元格都取这组变量，不另写字面值', () => {
    const style = listSource.slice(listSource.indexOf('<style'))
    expect(declared(style, '.business-records', '--table-header-font-size')).toBe('var(--data-table-font-size)')
    expect(declared(style, '.business-records', '--table-body-font-size')).toBe('var(--data-table-font-size)')
    const cells = style.slice(style.indexOf('/* 字体、字号'), style.indexOf('/* 字体、字号结束 */'))
    expect(cells).toContain('.ant-table-thead > tr > th')
    expect(cells).toContain('.ant-table-tbody > tr > td')
    expect(cells).toContain('padding: var(--data-table-cell-padding);')
    expect(cells).toContain('line-height: var(--data-table-line-height);')
    expect(cells).toContain('font-weight: var(--data-table-header-font-weight);')
    // 只统一字号与间距：这一段里不许出现颜色、背景、对齐方式
    expect(cells).not.toMatch(/(^|[\s;{])(color|background|background-color|text-align)\s*:/)
    expect(cells).not.toMatch(/\d+px/)
  })
})

window.matchMedia ||= ((query: string) => ({
  matches: false,
  media: query,
  onchange: null,
  addListener: () => undefined,
  removeListener: () => undefined,
  addEventListener: () => undefined,
  removeEventListener: () => undefined,
  dispatchEvent: () => false
})) as typeof window.matchMedia
vi.stubGlobal(
  'ResizeObserver',
  class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
)
const computedStyle = window.getComputedStyle.bind(window)
window.getComputedStyle = ((element: Element) => computedStyle(element)) as typeof window.getComputedStyle

let app: App | undefined, host: HTMLDivElement
async function flush() {
  for (let index = 0; index < 4; index++) {
    await new Promise(resolve => setTimeout(resolve))
    await nextTick()
  }
}
async function mount(component: Component, props: Record<string, unknown>) {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(() => h(component, props))
  app.use(createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { render: () => null } }] }))
  app.use(Antd)
  app.mount(host)
  await flush()
}
const permissions = {
  actions: ['READ', 'UPDATE', 'CREATE', 'DELETE'],
  readFields: ['name', 'amount'],
  writeFields: ['name', 'amount'],
  readDetails: [],
  writeDetails: []
}
const row = { id: '1', revision: '1', values: { name: '甲公司', amount: '12.50' }, permissions }
beforeEach(() => {
  api.model.mockResolvedValue({
    writable: true,
    permissions,
    object: {
      objectId: 'object',
      objectName: '资金流水',
      titleFieldId: 'name',
      fields: [
        { id: 'name', name: '摘要', type: 'TEXT' },
        { id: 'amount', name: '金额', type: 'DECIMAL' }
      ],
      fieldOptions: {},
      details: [],
      relations: [],
      settings: {}
    },
    details: {}
  })
  api.page.mockResolvedValue({ list: [row], total: 1 })
  api.get.mockResolvedValue({ record: row, details: {}, processes: [] })
})
afterEach(() => {
  app?.unmount()
  app = undefined
  document.body.innerHTML = ''
  vi.resetAllMocks()
})

describe('带颜色、可点击的单元格照常渲染', () => {
  it('标题列仍是可点开详情的链接，操作列的查看 / 编辑 / 删除仍是链接按钮、删除仍是危险色', async () => {
    await mount(BusinessRecords, { applicationId: 'app', objectId: 'object' })
    const cells = host.querySelectorAll<HTMLElement>('.ant-table-tbody > tr.ant-table-row')[0]!.querySelectorAll('td')
    const title = cells[1]!.querySelector('a')!
    expect(title.textContent!.trim()).toBe('甲公司')
    expect(title.getAttribute('title')).toBe('甲公司')
    // 金额列是普通文字，不是链接
    expect(cells[2]!.querySelector('a')).toBeNull()
    const actions = Array.from(host.querySelectorAll<HTMLElement>('.nocode-table-actions .ant-btn'))
    expect(actions.map(button => (button.textContent || '').trim())).toEqual(['查看', '编辑', '删除'])
    for (const button of actions) expect(button.classList.contains('ant-btn-link'), button.textContent || '').toBe(true)
    expect(actions.map(button => button.classList.contains('ant-btn-dangerous'))).toEqual([false, false, true])
    for (const button of actions) expect(button.querySelector('.anticon'), button.textContent || '').toBeTruthy()
    // 点标题：打开这条记录的详情
    title.click()
    await flush()
    expect(api.get).toHaveBeenCalledWith('app', 'object', '1')
    expect(host.querySelector('[data-surface]')).toBeTruthy()
  })
})

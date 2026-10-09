// @vitest-environment jsdom
// 运行页查询栏上移：有页签行时挂到页签行右侧（页签里唯一的列表才挂），没有时进表格卡片标题行（标题之后、按钮之前），
// 不再单独占一张查询卡片；常用查询字段多时行内只放前 2 个，其余收进「更多条件」；查询行为不变。
// OsTablePage 与 ant 的卡片、页签用真实组件（位置关系只有真实结构才看得出来）；宽屏同行 / 窄屏换行的版式在浏览器里量（e2e/pw）。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref, type App, type Component } from 'vue'
import Antd from 'ant-design-vue'
import { NodeKind, type UiNode } from '@/types/nocode/application-ui'
import { INLINE_QUERY_FIELDS, splitQueryFields, tabSearchOwner } from './runtime-search-placement'

const api = vi.hoisted(() => ({ model: vi.fn(), page: vi.fn(), get: vi.fn(), viewModel: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ runtime: api }) }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'search-row-test' } }) }))
vi.mock('@/views/nocode/application/components/RecordSurface.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['open'],
      setup:
        (props, { slots }) =>
        () =>
          props.open ? h('aside', slots.default?.()) : null
    })
  }
})
vi.mock('@/views/nocode/application/components/RecordEditor.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/BusinessFileField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/RecordQueryField.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['field', 'modelValue'],
      emits: ['update:modelValue', 'search'],
      setup:
        (props, { emit }) =>
        () =>
          h('input', {
            'data-query-field': props.field.id,
            value: props.modelValue ?? '',
            onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value)
          })
    })
  }
})
// 高级检索弹窗打桩：一个按钮代替「在弹窗里配好条件点确定」。
vi.mock('@/components/os-table-page/OsDynamicSearch.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      props: ['open', 'fields'],
      emits: ['confirm', 'update:open', 'update:modelValue', 'removeSaved'],
      setup:
        (_props, { emit }) =>
        () =>
          h(
            'button',
            {
              'data-confirm-advanced': '',
              onClick: () =>
                emit(
                  'confirm',
                  { logic: 'AND', items: [{ type: 'condition', field: 'name', operator: 'like', value: '甲' }] },
                  ''
                )
            },
            '确定高级检索'
          )
    })
  }
})
import OsTablePage from '@/components/os-table-page/OsTablePage.vue'
import BusinessRecords from '@/views/nocode/application/components/BusinessRecords.vue'

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
    observe() {
      return undefined
    }
    unobserve() {
      return undefined
    }
    disconnect() {
      return undefined
    }
  }
)
const computedStyle = window.getComputedStyle.bind(window)
window.getComputedStyle = ((element: Element) => computedStyle(element)) as typeof window.getComputedStyle

let app: App | undefined, host: HTMLDivElement
/** 取不到就让用例失败（不用非空断言）。 */
function must<T>(value: T | null | undefined, what = '元素'): T {
  if (value == null) throw new Error('没有找到' + what)
  return value
}
const flush = async () => {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount(component: Component, props: Record<string, unknown>, slots?: Record<string, unknown>) {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(() => h(component, props, slots))
  app.use(Antd)
  app.mount(host)
  await flush()
}
afterEach(() => {
  app?.unmount()
  app = undefined
  document.body.innerHTML = ''
  localStorage.clear()
  vi.clearAllMocks()
})
const button = (text: string, root: ParentNode = host) =>
  Array.from(root.querySelectorAll<HTMLElement>('button')).find(item => (item.textContent || '').trim() === text)
/** a 在 b 之前（文档顺序）。 */
const before = (a: Element, b: Element) => !!(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING)

const node = (id: string, type: string, extra: Partial<UiNode> = {}): UiNode =>
  ({ id, type, text: null, span: 24, children: [], resourceId: null, ...extra }) as unknown as UiNode

describe('which list owns the tab row search', () => {
  it('is the only list placed directly in the tab', () => {
    expect(
      tabSearchOwner(node('t', NodeKind.TAB, { children: [node('v', NodeKind.VIEW, { resourceId: 'view' })] }))
    ).toBe('v')
    // 详情页里的关联列表同样是列表
    expect(
      tabSearchOwner(
        node('t', NodeKind.TAB, {
          children: [node('x', NodeKind.TEXT), node('r', NodeKind.RELATED, { resourceId: 'view' })]
        })
      )
    ).toBe('r')
  })

  it('is nobody when the tab has two lists, a list inside a card, or no list', () => {
    const view = (id: string) => node(id, NodeKind.VIEW, { resourceId: 'view-' + id })
    expect(tabSearchOwner(node('t', NodeKind.TAB, { children: [view('a'), view('b')] }))).toBeUndefined()
    expect(
      tabSearchOwner(node('t', NodeKind.TAB, { children: [node('c', NodeKind.CARD, { children: [view('a')] })] }))
    ).toBeUndefined()
    expect(
      tabSearchOwner(node('t', NodeKind.TAB, { children: [node('m', NodeKind.METRIC, { resourceId: 'v' })] }))
    ).toBe(undefined)
    expect(tabSearchOwner(node('t', NodeKind.TAB, { children: [node('v', NodeKind.VIEW)] }))).toBeUndefined()
    // dev 的任务列表块（OsTasks → TASKS）带 resourceId，但不是列表
    expect(
      tabSearchOwner(node('t', NodeKind.TAB, { children: [node('k', NodeKind.TASKS, { resourceId: 'v' })] }))
    ).toBe(undefined)
    expect(
      tabSearchOwner(
        node('t', NodeKind.TAB, {
          children: [node('k', NodeKind.TASKS, { resourceId: 'v' }), node('v', NodeKind.VIEW, { resourceId: 'view' })]
        })
      )
    ).toBe('v')
  })

  it('keeps the first two query fields inline and the rest for「更多条件」, in order', () => {
    expect(INLINE_QUERY_FIELDS).toBe(2)
    expect(splitQueryFields(['a'])).toEqual({ inline: ['a'], more: [] })
    expect(splitQueryFields(['a', 'b', 'c', 'd'])).toEqual({ inline: ['a', 'b'], more: ['c', 'd'] })
  })
})

describe('OsTablePage search placement', () => {
  const searchSlot = () => h('input', { 'data-keyword': '' })
  const base = { columns: [], dataSource: [], title: '入住记录', showImport: true, showAdvancedSearch: true }

  it('header: no separate search card; the search bar sits in the table title row after the title and before the buttons', async () => {
    await mount(
      OsTablePage,
      { ...base, searchPlacement: 'header', advancedSearchMode: 'dynamic' },
      { search: searchSlot }
    )
    expect(host.querySelector('.os-table-page__search')).toBeNull()
    const head = must(host.querySelector('.os-table-page__table > .ant-card-head'))
    const title = must(head.querySelector('.ant-card-head-title'))
    const bar = must(title.querySelector('[data-search-bar]'))
    expect(bar).toBeTruthy()
    expect(bar.querySelector('[data-keyword]')).toBeTruthy()
    expect(button('高级检索', bar)).toBeTruthy()
    expect(before(must(title.querySelector('.os-table-page__title-text')), bar)).toBe(true)
    expect(before(bar, must(head.querySelector('.ant-card-extra')))).toBe(true)
    expect(must(head.querySelector('.ant-card-extra')).textContent).toContain('导入')
  })

  it('header with a target: the same search bar is mounted there (once), the title row keeps its original layout, and the bar returns when the target goes away', async () => {
    const target = document.createElement('div')
    document.body.append(target)
    const current = ref<HTMLElement | null>(target)
    await mount(
      defineComponent({
        setup: () => () =>
          h(
            OsTablePage,
            { ...base, searchPlacement: 'header', advancedSearchMode: 'dynamic', searchTarget: current.value },
            { search: searchSlot }
          )
      }),
      {}
    )
    expect(target.querySelectorAll('[data-search-bar]')).toHaveLength(1)
    expect(host.querySelectorAll('[data-search-bar]')).toHaveLength(0)
    expect(document.querySelectorAll('[data-keyword]')).toHaveLength(1)
    // 查询栏挂走了：表格标题行的版式与原来一样（不加标题行查询栏的那套样式）
    expect(host.querySelector('.os-table-page__table--search-header')).toBeNull()
    current.value = null
    await flush()
    expect(target.querySelector('[data-search-bar]')).toBeNull()
    expect(host.querySelector('.ant-card-head-title [data-search-bar]')).toBeTruthy()
    expect(host.querySelector('.os-table-page__table--search-header')).toBeTruthy()
  })

  it('header: temporary and saved conditions show at the top of the table card body, and behave as before', async () => {
    localStorage.setItem(
      'la-saved-search:runtime-key',
      JSON.stringify([{ id: 's1', description: '本月入住', conditions: { logic: 'AND', items: [] }, createdAt: 1 }])
    )
    const onSearch = vi.fn(),
      onDynamic = vi.fn()
    await mount(
      OsTablePage,
      {
        ...base,
        searchPlacement: 'header',
        advancedSearchMode: 'dynamic',
        dynamicSearchFields: [{ field: 'name', label: '名称', type: 'input' }],
        searchStorageKey: 'runtime-key',
        onSearch,
        onDynamicSearch: onDynamic
      },
      { search: searchSlot }
    )
    const body = must(host.querySelector('.os-table-page__table > .ant-card-body'))
    const saved = Array.from(body.querySelectorAll('.os-table-page__saved-conditions'))
    expect(saved.map(e => e.textContent)).toEqual([expect.stringContaining('常用查询：')])
    expect(before(saved[0], must(body.querySelector('.ant-table-wrapper')))).toBe(true)
    ;(body.querySelector('.os-table-page__saved-tag') as HTMLElement).click()
    await flush()
    expect(onDynamic).toHaveBeenLastCalledWith({ logic: 'AND', items: [] })
    expect(onSearch).toHaveBeenCalledTimes(1)
    // 再点同一个标签取消；随后从高级检索确认一个不保存的条件 → 「临时查询」摘要出现在正文顶部，点 × 清除并重查
    ;(body.querySelector('.os-table-page__saved-tag') as HTMLElement).click()
    await flush()
    ;(host.querySelector('[data-confirm-advanced]') as HTMLElement).click()
    await flush()
    const temporary = must(
      Array.from(body.querySelectorAll('.os-table-page__saved-conditions')).find(e =>
        e.textContent?.includes('临时查询：')
      )
    )
    expect(temporary.textContent).toContain('名称')
    expect(host.querySelector('.os-table-page__search')).toBeNull()
    const searches = onSearch.mock.calls.length
    ;(temporary.querySelector('[aria-label="清除高级检索条件"]') as HTMLElement).click()
    await flush()
    expect(onDynamic).toHaveBeenLastCalledWith(null)
    expect(onSearch).toHaveBeenCalledTimes(searches + 1)
    expect(body.textContent).not.toContain('临时查询：')
  })

  it('card (the default for every other page): unchanged — a separate search card above the table, title row untouched', async () => {
    await mount(OsTablePage, { ...base, advancedSearchMode: 'dynamic' }, { search: searchSlot })
    const card = must(host.querySelector('.os-table-page__search'))
    expect(card.querySelector('[data-keyword]')).toBeTruthy()
    expect(button('高级检索', card)).toBeTruthy()
    expect(host.querySelector('[data-search-bar]')).toBeNull()
    expect(must(host.querySelector('.os-table-page__table .ant-card-head-title')).textContent?.trim()).toBe('入住记录')
  })
})

describe('the runtime list (BusinessRecords)', () => {
  const permissions = {
    actions: ['READ', 'UPDATE', 'CREATE'],
    readFields: ['name', 'f1', 'f2', 'f3', 'f4'],
    writeFields: ['name'],
    readDetails: [],
    writeDetails: []
  }
  const fields = ['f1', 'f2', 'f3', 'f4']
  beforeEach(() => {
    api.model.mockResolvedValue({
      writable: true,
      permissions,
      object: {
        objectId: 'object',
        objectName: '入住记录',
        titleFieldId: 'name',
        fields: [
          { id: 'name', name: '入住人', type: 'TEXT' },
          ...fields.map(id => ({ id, name: '字段' + id, type: 'TEXT' }))
        ],
        fieldOptions: {},
        details: [],
        relations: [],
        settings: {}
      },
      details: {}
    })
    api.page.mockResolvedValue({ list: [], total: 0 })
  })
  const view = (queryFieldIds: string[]) => ({
    objectId: 'object',
    fieldIds: ['name'],
    list: { queryFieldIds, columnWidths: {}, batchDelete: false },
    interaction: { buttons: ['CREATE', 'VIEW', 'UPDATE', 'DELETE'], actionIds: [] }
  })
  const input = (selector: string, value: string) => {
    const el =
      host.querySelector<HTMLInputElement>(selector) || must(document.querySelector<HTMLInputElement>(selector))
    el.value = value
    el.dispatchEvent(new Event('input'))
  }
  const lastQuery = () => must(api.page.mock.calls.at(-1))[0]

  it('puts the search bar in the title row (no tab row): keyword, two query fields, query / reset, more conditions; no search card', async () => {
    await mount(BusinessRecords, { applicationId: 'app', objectId: 'object', viewId: 'v', view: view(fields) })
    expect(host.querySelector('.os-table-page__search')).toBeNull()
    const bar = must(
      host.querySelector('.os-table-page__table > .ant-card-head .ant-card-head-title [data-search-bar]')
    )
    expect(bar.querySelector('[aria-label="搜索业务记录"]')).toBeTruthy()
    expect(Array.from(bar.querySelectorAll('[data-query-field]')).map(e => e.getAttribute('data-query-field'))).toEqual(
      ['f1', 'f2']
    )
    expect(button('查询', bar)).toBeTruthy()
    expect(button('重置', bar)).toBeTruthy()
    expect(button('更多条件（2）', bar)).toBeTruthy()
    expect(host.querySelector('[data-search-more]')).toBeNull()
  })

  it('keeps the query behaviour: keyword + inline + folded fields all go into the request, reset clears them', async () => {
    await mount(BusinessRecords, { applicationId: 'app', objectId: 'object', viewId: 'v', view: view(fields) })
    must(button('更多条件（2）')).click()
    await flush()
    const more = must(host.querySelector('.os-table-page__table > .ant-card-body > [data-search-more]'))
    expect(
      Array.from(more.querySelectorAll('[data-query-field]')).map(e => e.getAttribute('data-query-field'))
    ).toEqual(['f3', 'f4'])
    input('[aria-label="搜索业务记录"]', '张三')
    input('[data-query-field="f1"]', '甲')
    input('[data-query-field="f4"]', '丁')
    await flush()
    // 收起后已填的照样参与查询，按钮上标出已填个数
    must(button('收起条件')).click()
    await flush()
    expect(host.querySelector('[data-search-more]')).toBeNull()
    expect(button('更多条件（2）· 已填 1')).toBeTruthy()
    must(button('查询')).click()
    await flush()
    expect(lastQuery()).toMatchObject({
      search: '张三',
      pageNo: 1,
      conditions: {
        logic: 'AND',
        items: [
          { type: 'condition', field: 'f1', operator: 'like', value: '甲' },
          { type: 'condition', field: 'f4', operator: 'like', value: '丁' }
        ]
      }
    })
    must(button('重置')).click()
    await flush()
    expect(lastQuery()).toMatchObject({ search: '', conditions: null })
    expect(button('更多条件（2）')).toBeTruthy()
  })

  it('pressing Enter in the keyword box queries, exactly as before', async () => {
    await mount(BusinessRecords, { applicationId: 'app', objectId: 'object', viewId: 'v', view: view(['f1']) })
    const calls = api.page.mock.calls.length
    input('[aria-label="搜索业务记录"]', '李四')
    await flush()
    must(host.querySelector('[aria-label="搜索业务记录"]')).dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', keyCode: 13, bubbles: true })
    )
    await flush()
    expect(api.page.mock.calls.length).toBe(calls + 1)
    expect(lastQuery()).toMatchObject({ search: '李四' })
    // 只有一个常用查询字段：没有「更多条件」
    expect(host.textContent).not.toContain('更多条件')
  })

  it('mounts the search bar on the tab row target when given one, and takes it back while an inline editor covers the list', async () => {
    const target = document.createElement('div')
    document.body.append(target)
    await mount(BusinessRecords, {
      applicationId: 'app',
      objectId: 'object',
      viewId: 'v',
      view: view(['f1']),
      searchTarget: target,
      inlineEditing: true
    })
    expect(target.querySelector('[aria-label="搜索业务记录"]')).toBeTruthy()
    expect(host.querySelector('[data-search-bar]')).toBeNull()
    must(button('新增')).click()
    await flush()
    expect(target.querySelector('[data-search-bar]')).toBeNull()
    expect(host.querySelector('.ant-card-head-title [data-search-bar]')).toBeTruthy()
  })
})

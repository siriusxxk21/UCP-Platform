import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick } from 'vue'
import type { BpmModelApi } from '@/api/bpm/model'
import type { BpmCategoryApi } from '@/api/bpm/category'
import {
  ALL_MODELS,
  UNCLASSIFIED_MODELS,
  categoryKey,
  groupModels,
  useModelCatalog
} from '@/views/bpm/model/model-catalog'
import ModelList from '@/views/bpm/model/index.vue'

const calls = vi.hoisted(() => ({
  models: vi.fn(),
  categories: vi.fn(),
  sortModels: vi.fn(),
  sortCategories: vi.fn(),
  deleteCategory: vi.fn(),
  push: vi.fn()
}))
vi.mock('@/api/bpm/model', () => ({
  getModelList: calls.models,
  updateModelSortBatch: calls.sortModels,
  cleanModel: vi.fn(),
  deleteModel: vi.fn(),
  deployModel: vi.fn(),
  updateModelState: vi.fn()
}))
vi.mock('@/api/bpm/category', () => ({
  getCategorySimpleList: calls.categories,
  updateCategorySortBatch: calls.sortCategories,
  deleteCategory: calls.deleteCategory
}))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 'current' } }) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: calls.push }) }))
vi.mock('ant-design-vue', () => ({ message: { success: vi.fn(), error: vi.fn() }, Modal: { confirm: vi.fn() } }))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['columns', 'dataSource', 'pagination', 'title', 'columnSettingsKey', 'resizable', 'showColumnSettings'],
    emits: ['search', 'change'],
    setup(props, { slots, emit }) {
      return () =>
        h('section', { 'data-table-key': props.columnSettingsKey }, [
          h('h2', props.title),
          slots.search?.({ triggerSearch: () => emit('search') }),
          slots.actions?.(),
          slots.toolbar?.(),
          h(
            'button',
            {
              'data-next-page': true,
              onClick: () =>
                emit('change', { current: props.pagination.current + 1, pageSize: props.pagination.pageSize })
            },
            '下一页'
          ),
          ...(props.dataSource || []).map((record: any) =>
            h(
              'article',
              { 'data-model-id': record.id },
              props.columns.map((column: any) => slots.bodyCell?.({ column, record }))
            )
          ),
          h(
            'div',
            { 'data-pagination': true },
            `${props.pagination.current}/${props.pagination.pageSize}/${props.pagination.total}`
          )
        ])
    }
  })
}))
const categories = [
  { id: 'one', code: 'A', name: '行政', sort: 0, status: 0 },
  { id: 'empty', code: 'B', name: '空分类', sort: 1, status: 0 }
] as BpmCategoryApi.Category[]
function model(id: number | string, overrides: Partial<BpmModelApi.Model> = {}) {
  return {
    id,
    key: `key${id}`,
    name: `流程${id}`,
    category: 'A',
    type: 20,
    formType: 0,
    ...overrides
  } as BpmModelApi.Model
}
const disposals: (() => void)[] = []
async function settle() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function mount(view: any) {
  const host = document.createElement('div')
  document.body.append(host)
  const app = createApp(view)
  const box = defineComponent({
    setup(_, { slots }) {
      return () => h('div', [slots.default?.(), slots.overlay?.()])
    }
  })
  for (const name of ['a-form', 'a-form-item', 'a-space', 'a-tag', 'a-tooltip', 'a-dropdown', 'a-menu'])
    app.component(name, box)
  const button = defineComponent({
    props: ['disabled', 'loading'],
    setup(props, { slots }) {
      return () => h('button', { disabled: props.disabled || props.loading }, slots.default?.())
    }
  })
  app.component('a-button', button)
  app.component('a-menu-item', button)
  app.component(
    'a-input',
    defineComponent({
      props: ['value', 'disabled'],
      emits: ['update:value', 'pressEnter'],
      setup(props, { emit, attrs }) {
        return () =>
          h('input', {
            ...attrs,
            value: props.value,
            disabled: props.disabled,
            onInput: (event: Event) => emit('update:value', (event.target as HTMLInputElement).value),
            onKeydown: (event: KeyboardEvent) => event.key === 'Enter' && emit('pressEnter')
          })
      }
    })
  )
  app.component(
    'a-popconfirm',
    defineComponent({
      props: ['disabled'],
      emits: ['confirm'],
      setup(props, { slots, emit }) {
        return () => h('div', { onClick: () => !props.disabled && emit('confirm') }, slots.default?.())
      }
    })
  )
  app.component(
    'a-alert',
    defineComponent({
      props: ['message'],
      setup(props, { slots }) {
        return () => h('div', { role: 'alert' }, [props.message, slots.action?.()])
      }
    })
  )
  app.mount(host)
  disposals.push(() => {
    app.unmount()
    host.remove()
  })
}
function controller(api = { models: calls.models, categories: calls.categories }) {
  let result!: ReturnType<typeof useModelCatalog>
  mount(
    defineComponent({
      setup() {
        result = useModelCatalog(api)
        return () => null
      }
    })
  )
  return result
}
function findButton(text: string) {
  const result = [...document.querySelectorAll('button')].find(button => button.textContent?.trim() === text)
  if (!result) throw new Error(`Missing button ${text}`)
  return result
}
async function click(text: string) {
  findButton(text).click()
  await settle()
}
function input(placeholder: string, value: string) {
  const element = document.querySelector<HTMLInputElement>(`input[placeholder="${placeholder}"]`)!
  element.value = value
  element.dispatchEvent(new Event('input', { bubbles: true }))
  return element
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(r => {
    resolve = r
  })
  return { promise, resolve }
}

beforeEach(() => {
  vi.clearAllMocks()
  calls.models.mockResolvedValue(Array.from({ length: 25 }, (_, i) => model(i + 1)))
  calls.categories.mockResolvedValue(categories)
  calls.sortModels.mockResolvedValue(true)
  calls.sortCategories.mockResolvedValue(true)
})
afterEach(() => {
  disposals.splice(0).forEach(dispose => dispose())
})

describe('流程模型分类与查询', () => {
  it('优先分类编码，空分类保留，失配模型只进入未归类一次', () => {
    const groups = groupModels(
      [
        model(1, { categoryName: '空分类' }),
        model(2, { category: 'gone', categoryName: '行政' }),
        model(3, { category: '', categoryName: '行政' })
      ],
      categories
    )
    expect(groups.groups.map(group => group.models.map(row => row.id))).toEqual([[1, 3], []])
    expect(groups.unclassified.map(row => row.id)).toEqual([2])
  })
  it('分页默认 10 条，切换每页数量回第一页，类别切换保留空类', async () => {
    const state = controller()
    await state.loadData()
    expect(state.tableData.value).toHaveLength(10)
    state.changePage({ current: 3, pageSize: 10 })
    expect(state.tableData.value.map(row => row.id)).toEqual([21, 22, 23, 24, 25])
    state.changePage({ current: 3, pageSize: 20 })
    expect(state.current.value).toBe(1)
    state.selectCategory(categoryKey('empty'))
    expect(state.tableData.value).toEqual([])
    expect(state.selectedCategory.value?.name).toBe('空分类')
  })
  it('输入不会立即过滤；查询从首页开始且完整分类计数不变', async () => {
    const state = controller()
    await state.loadData()
    state.selectCategory(categoryKey('one'))
    state.changePage({ current: 3 })
    state.queryName.value = '流程1'
    expect(state.pagination.value.total).toBe(25)
    state.search()
    expect(state.current.value).toBe(1)
    expect(state.pagination.value.total).toBe(11)
    expect(state.selectedCategory.value?.models).toHaveLength(25)
    state.queryName.value = '编辑未查询'
    await state.loadData()
    expect(state.appliedName.value).toBe('流程1')
    expect(state.pagination.value.total).toBe(11)
  })
  it('刷新失败清空旧操作数据，重试恢复原分类与已生效条件', async () => {
    const state = controller()
    await state.loadData()
    state.selectCategory(categoryKey('one'))
    state.queryName.value = '流程1'
    state.search()
    calls.models.mockRejectedValueOnce(new Error('网络中断'))
    await state.loadData()
    expect(state.models.value).toEqual([])
    expect(state.error.value).toBe('网络中断')
    await state.loadData()
    expect(state.selectedKey.value).toBe(categoryKey('one'))
    expect(state.error.value).toBe('')
    expect(state.pagination.value.total).toBe(11)
  })
  it('较早请求不能覆盖新请求结果', async () => {
    const first = deferred<BpmModelApi.Model[]>()
    calls.models.mockReturnValueOnce(first.promise).mockResolvedValueOnce([model('new')])
    const state = controller()
    const oldRequest = state.loadData()
    await state.loadData()
    first.resolve([model('old')])
    await oldRequest
    expect(state.models.value.map(row => row.id)).toEqual(['new'])
  })
  it('刷新缩短列表纠正越界页，失效分类恢复全部', async () => {
    const state = controller()
    await state.loadData()
    state.selectCategory(categoryKey('one'))
    state.changePage({ current: 3 })
    calls.models.mockResolvedValueOnce([model(1)])
    calls.categories.mockResolvedValueOnce([])
    await state.loadData()
    await settle()
    expect(state.current.value).toBe(1)
    expect(state.selectedKey.value).toBe(ALL_MODELS)
    expect(state.grouped.value.unclassified).toHaveLength(1)
    state.selectCategory(UNCLASSIFIED_MODELS)
    expect(state.tableData.value).toHaveLength(1)
  })
})

describe('流程模型列表入口与排序', () => {
  it('只显示一个规范表格，可选空分类，兼容无需发起表单', async () => {
    mount(ModelList)
    await settle()
    expect(document.querySelectorAll('[data-table-key="bpm-model-list"]')).toHaveLength(1)
    expect(document.querySelectorAll('article')).toHaveLength(10)
    expect(document.body.textContent).toContain('无需发起表单')
    await click('空分类0')
    expect(document.querySelectorAll('article')).toHaveLength(0)
    expect(findButton('删除分类').disabled).toBe(false)
  })
  it('名称查询无结果仍禁止删除原本非空分类', async () => {
    mount(ModelList)
    await settle()
    await click('行政25')
    input('请输入流程名称', '无匹配')
    await settle()
    await click('查询')
    expect(document.querySelectorAll('article')).toHaveLength(0)
    expect(findButton('删除分类').disabled).toBe(true)
    expect(findButton('流程排序').disabled).toBe(true)
  })
  it('分页排序跨页移动并提交整个分类，失败可重试保留顺序', async () => {
    mount(ModelList)
    await settle()
    await click('行政25')
    await click('流程排序')
    document.querySelector<HTMLButtonElement>('[aria-label="下移流程 流程10"]')!.click()
    await settle()
    expect(document.querySelector('[data-pagination]')?.textContent).toBe('2/10/25')
    expect(document.querySelector('article')?.getAttribute('data-model-id')).toBe('10')
    calls.sortModels.mockRejectedValueOnce(new Error('保存失败'))
    await click('保存排序')
    expect(findButton('保存排序')).toBeTruthy()
    await click('保存排序')
    expect(calls.sortModels.mock.calls[0][0]).toHaveLength(25)
    expect(calls.sortModels.mock.calls[0][0].slice(8, 12)).toEqual([9, 11, 10, 12])
    expect(calls.sortModels.mock.calls[1][0]).toEqual(calls.sortModels.mock.calls[0][0])
  })
  it('分类排序完整提交，取消不会发请求', async () => {
    mount(ModelList)
    await settle()
    await click('分类排序')
    document.querySelector<HTMLButtonElement>('[aria-label="下移分类 行政"]')!.click()
    await settle()
    await click('取消')
    expect(calls.sortCategories).not.toHaveBeenCalled()
    await click('分类排序')
    document.querySelector<HTMLButtonElement>('[aria-label="下移分类 行政"]')!.click()
    await settle()
    await click('保存排序')
    expect(calls.sortCategories).toHaveBeenCalledWith(['empty', 'one'])
  })
  it('保留新建复制历史入口及管理人操作限制', async () => {
    calls.models.mockResolvedValue([model(1, { managerUserIds: ['someone'] })])
    mount(ModelList)
    await settle()
    expect(findButton('修改').disabled).toBe(true)
    expect(findButton('发布').disabled).toBe(true)
    await click('新建模型')
    expect(calls.push).toHaveBeenLastCalledWith({ path: '/bpm/model/form', query: { type: 'create', id: undefined } })
    await click('复制')
    expect(calls.push).toHaveBeenLastCalledWith({ path: '/bpm/model/form', query: { type: 'copy', id: 1 } })
    await click('历史')
    expect(calls.push).toHaveBeenLastCalledWith({ path: '/bpm/model/definition', query: { key: 'key1' } })
  })
})

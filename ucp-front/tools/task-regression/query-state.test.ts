import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, KeepAlive, nextTick, ref } from 'vue'
import dayjs from 'dayjs'
import { useOsTablePage } from '@/composables/useOsTablePage'
import { useTaskList } from '@/views/bpm/task/use-task-list'

const options = vi.hoisted(() => ({ categories: vi.fn(), definitions: vi.fn() }))
vi.mock('@/api/bpm/category', () => ({ getCategorySimpleList: options.categories }))
vi.mock('@/api/bpm/definition', () => ({ getSimpleProcessDefinitionList: options.definitions }))
const dispose: Array<() => void> = []
async function settle() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function mount<T>(setup: () => T, cached = false) {
  let state!: T
  const visible = ref(true)
  const View = defineComponent({
    setup() {
      state = setup()
      return () => h('div')
    }
  })
  const host = document.createElement('div')
  const app = createApp({ render: () => (cached ? h(KeepAlive, () => (visible.value ? h(View) : null)) : h(View)) })
  app.mount(host)
  dispose.push(() => app.unmount())
  return { state, visible, unmount: () => app.unmount() }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
beforeEach(() => {
  options.categories.mockResolvedValue([{ code: 'purchase', name: '采购' }])
  options.definitions.mockResolvedValue({ list: [{ key: 'purchase_v1', name: '采购申请' }] })
  vi.spyOn(console, 'error').mockImplementation(() => {})
})
afterEach(() => {
  dispose.splice(0).forEach(fn => fn())
  vi.restoreAllMocks()
  vi.clearAllMocks()
})

describe('任务查询协议和列表状态', () => {
  it('首次仅请求一页，使用 pageNo 和默认 10 条，兼容分页形状的流程选项', async () => {
    const fetch = vi.fn().mockResolvedValue({ list: [], total: 0 })
    const { state } = mount(() => useTaskList(fetch), true)
    await settle()
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(fetch.mock.calls[0]![0]).toMatchObject({ pageNo: 1, pageSize: 10 })
    expect(fetch.mock.calls[0]![0]).not.toHaveProperty('pageNum')
    expect(state.processDefinitionOptions.value[0]?.key).toBe('purchase_v1')
  })
  it('查询才应用输入，翻页和刷新保持已提交条件，日期和名称转换正确', async () => {
    const fetch = vi.fn().mockResolvedValue({ list: [], total: 100 })
    const { state } = mount(() => useTaskList(fetch))
    await settle()
    state.queryForm.name = '  安装  '
    state.queryForm.category = 'purchase'
    state.queryForm.createTime = [dayjs('2026-09-01 00:00:00'), dayjs('2026-09-10 23:59:59')]
    state.handleSearch()
    await settle()
    state.queryForm.name = '未提交的新条件'
    state.handleTableChange({ current: 2, pageSize: 10 })
    await settle()
    expect(fetch.mock.lastCall?.[0]).toMatchObject({
      name: '安装',
      category: 'purchase',
      pageNo: 2,
      createTime: ['2026-09-01 00:00:00', '2026-09-10 23:59:59']
    })
    await state.loadData()
    expect(fetch.mock.lastCall?.[0].name).toBe('安装')
    state.handleSearch()
    await settle()
    expect(fetch.mock.lastCall?.[0]).toMatchObject({ name: '未提交的新条件', pageNo: 1 })
  })
  it('改变每页条数回第一页，重置同时清空全部过滤条件', async () => {
    const fetch = vi.fn().mockResolvedValue({ list: [], total: 100 })
    const { state } = mount(() => useTaskList(fetch))
    await settle()
    Object.assign(state.queryForm, { name: '任务', status: 2, category: 'x', processDefinitionKey: 'p' })
    state.handleSearch()
    state.handleTableChange({ current: 3, pageSize: 10 })
    await settle()
    state.handleTableChange({ current: 2, pageSize: 20 })
    await settle()
    expect(fetch.mock.lastCall?.[0]).toMatchObject({ pageNo: 1, pageSize: 20 })
    state.handleReset()
    await settle()
    expect(fetch.mock.lastCall?.[0]).toMatchObject({ pageNo: 1, pageSize: 20 })
    expect(fetch.mock.lastCall?.[0].status).toBeUndefined()
    expect(fetch.mock.lastCall?.[0].category).toBeUndefined()
    expect(fetch.mock.lastCall?.[0].processDefinitionKey).toBeUndefined()
    expect(fetch.mock.lastCall?.[0].name).toBeUndefined()
  })
  it('较晚完成的旧请求不会覆盖新结果或提前取消加载状态', async () => {
    const first = deferred<{ list: string[]; total: number }>()
    const second = deferred<{ list: string[]; total: number }>()
    const fetch = vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const { state } = mount(() => useTaskList<string>(fetch))
    state.queryForm.name = '新'
    state.handleSearch()
    first.resolve({ list: ['旧'], total: 100 })
    await settle()
    expect(state.loading.value).toBe(true)
    expect(state.tableData.value).toEqual([])
    second.resolve({ list: ['新'], total: 1 })
    await settle()
    expect(state.tableData.value).toEqual(['新'])
    expect(state.pagination.total).toBe(1)
    expect(state.loading.value).toBe(false)
  })
  it('旧请求失败不会清空新结果或显示失效错误', async () => {
    const old = deferred<{ list: string[]; total: number }>()
    const fetch = vi
      .fn()
      .mockReturnValueOnce(old.promise)
      .mockResolvedValue({ list: ['新'], total: 1 })
    const { state } = mount(() => useTaskList<string>(fetch))
    state.handleSearch()
    await settle()
    old.reject(new Error('过期错误'))
    await settle()
    expect(state.tableData.value).toEqual(['新'])
    expect(state.loadError.value).toBe('')
  })
  it('查询失败清空旧结果并显示错误，重试成功清除错误', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce({ list: ['旧'], total: 1 })
      .mockRejectedValueOnce(new Error('服务不可用'))
      .mockResolvedValue({ list: ['恢复'], total: 1 })
    const { state } = mount(() => useTaskList<string>(fetch))
    await settle()
    await state.loadData()
    expect(state.loadError.value).toBe('服务不可用')
    expect(state.tableData.value).toEqual([])
    expect(state.pagination.total).toBe(0)
    await state.loadData()
    expect(state.loadError.value).toBe('')
    expect(state.tableData.value).toEqual(['恢复'])
  })
  it('完成任务导致末页消失时补查最后有效页', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce({ list: [], total: 11 })
      .mockResolvedValueOnce({ list: [], total: 10 })
      .mockResolvedValue({ list: ['剩余'], total: 10 })
    const { state } = mount(() => useTaskList<string>(fetch))
    await settle()
    state.handleTableChange({ current: 2, pageSize: 10 })
    await settle()
    expect(fetch.mock.calls.map(call => call[0].pageNo)).toEqual([1, 2, 1])
    expect(state.tableData.value).toEqual(['剩余'])
    expect(state.pagination.current).toBe(1)
  })
  it('缓存页面重新激活刷新数据并保留当前页', async () => {
    const fetch = vi.fn().mockResolvedValue({ list: [], total: 30 })
    const { state, visible } = mount(() => useTaskList(fetch), true)
    await settle()
    state.handleTableChange({ current: 2, pageSize: 10 })
    await settle()
    visible.value = false
    await settle()
    visible.value = true
    await settle()
    expect(fetch).toHaveBeenCalledTimes(3)
    expect(fetch.mock.lastCall?.[0].pageNo).toBe(2)
  })
  it('一组选项失败仍保留另一组，并支持独立重试', async () => {
    options.categories.mockRejectedValueOnce(new Error('分类失败'))
    const { state } = mount(() => useTaskList(vi.fn().mockResolvedValue({ list: [], total: 0 })))
    await settle()
    expect(state.processDefinitionOptions.value).toHaveLength(1)
    expect(state.optionsError.value).toContain('流程分类')
    await state.loadOptions()
    expect(state.categoryOptions.value).toHaveLength(1)
    expect(state.optionsError.value).toBe('')
  })
  it('抄送列表不请求无关选项', async () => {
    mount(() => useTaskList(vi.fn().mockResolvedValue({ list: [], total: 0 }), false))
    await settle()
    expect(options.categories).not.toHaveBeenCalled()
    expect(options.definitions).not.toHaveBeenCalled()
  })
})

describe('底座兼容', () => {
  it('默认仍按实时表单查询，兼容 records 和 afterFetch', async () => {
    const fetch = vi.fn().mockResolvedValue({ records: ['a'], total: 1 })
    const { state } = mount(() =>
      useOsTablePage({
        fetchFn: fetch,
        defaultQuery: () => ({ name: '' }),
        afterFetch: rows => rows.map(String).map(s => s.toUpperCase())
      })
    )
    await settle()
    state.queryForm.name = '直接条件'
    await state.fetchData()
    expect(fetch.mock.lastCall?.[0].name).toBe('直接条件')
    expect(state.tableData.value).toEqual(['A'])
    expect(state.getSearchParams().name).toBe('直接条件')
    expect(state.getExportParams().name).toBe('直接条件')
  })
  it('默认失败策略保留旧数据，immediate false 不自动请求', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce({ list: ['已有'], total: 1 })
      .mockRejectedValue(new Error('失败'))
    const { state } = mount(() => useOsTablePage({ fetchFn: fetch, defaultQuery: () => ({}), immediate: false }))
    expect(fetch).not.toHaveBeenCalled()
    await state.fetchData()
    await state.fetchData()
    expect(state.tableData.value).toEqual(['已有'])
  })
  it('卸载后返回的数据不再修改页面状态', async () => {
    const pending = deferred<{ list: string[]; total: number }>()
    const { state, unmount } = mount(() => useOsTablePage({ fetchFn: () => pending.promise, defaultQuery: () => ({}) }))
    unmount()
    pending.resolve({ list: ['已离开'], total: 1 })
    await settle()
    expect(state.tableData.value).toEqual([])
  })
})

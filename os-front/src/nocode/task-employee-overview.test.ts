// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, ref, type App } from 'vue'
import TaskEmployeeOverview from '@/views/nocode/task-center/TaskEmployeeOverview.vue'
import { nocodePlatformKey, type NocodePlatform } from '@/nocode/platform'
import type { TaskEmployeeOverviewRow } from '@/types/nocode/task-management'

vi.mock('@/components/os-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['columns', 'dataSource', 'loading', 'pagination'],
    emits: ['change'],
    setup:
      (props, { slots, emit }) =>
      () =>
        h('section', { 'data-table-loading': props.loading, 'data-current-page': props.pagination.current }, [
          slots.search?.(),
          slots.title?.(),
          slots.actions?.(),
          ...props.dataSource.map((record: TaskEmployeeOverviewRow) =>
            h(
              'div',
              { 'data-row': record.userId },
              props.columns.map((column: { key: string }) =>
                h('div', { 'data-cell': column.key }, slots.bodyCell?.({ column, record }))
              )
            )
          ),
          !props.dataSource.length ? slots.empty?.() : null,
          h('button', { onClick: () => emit('change', { current: 2, pageSize: 10 }, {}, {}) }, '下一页')
        ])
  })
}))

let app: App | undefined, host: HTMLElement
const permission = ref(true)
const props = reactive({ date: '2026-10-07', refreshKey: 0 })
const select = vi.fn(),
  managementEmployees = vi.fn()
const staff: TaskEmployeeOverviewRow = {
  userId: '9007199254740993123',
  userName: '张三',
  pendingCount: 2,
  runningCount: 1,
  overdueCount: 1,
  todayCount: 3,
  weekCount: 5,
  coordinationCount: 1
}
const flush = async () => {
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 0))
  await nextTick()
}
function element<T extends HTMLElement = HTMLElement>(selector: string): T {
  const found = host.querySelector<T>(selector)
  if (!found) throw new Error(`未找到 ${selector}`)
  return found
}
async function click(text: string) {
  const button = Array.from(host.querySelectorAll<HTMLButtonElement>('button')).find(
    item => item.textContent?.trim() === text
  )
  if (!button) throw new Error(`未找到按钮 ${text}`)
  button.click()
  await flush()
}
async function mount() {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(() => h(TaskEmployeeOverview, { ...props, onSelect: select }))
  app.provide(nocodePlatformKey, {
    taskCenter: { managementEmployees },
    hasPermission: () => permission.value
  } as unknown as NocodePlatform)
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of ['AFormItem', 'ASpace', 'ATooltip']) app.component(name, plain)
  app.component(
    'AButton',
    defineComponent({
      props: ['htmlType', 'disabled'],
      setup:
        (p, { attrs, slots }) =>
        () =>
          h('button', { ...attrs, type: p.htmlType || 'button', disabled: p.disabled }, slots.default?.())
    })
  )
  app.component(
    'AForm',
    defineComponent({
      emits: ['finish'],
      setup:
        (_, { slots, emit }) =>
        () =>
          h(
            'form',
            {
              onSubmit: (e: Event) => {
                e.preventDefault()
                emit('finish')
              }
            },
            slots.default?.()
          )
    })
  )
  app.component(
    'AInput',
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup:
        (p, { attrs, emit }) =>
        () =>
          h('input', {
            ...attrs,
            value: p.value,
            onInput: (e: Event) => emit('update:value', (e.target as HTMLInputElement).value)
          })
    })
  )
  app.component(
    'ADatePicker',
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup:
        (p, { attrs, emit }) =>
        () =>
          h('input', {
            ...attrs,
            value: p.value,
            onChange: (e: Event) => emit('update:value', (e.target as HTMLInputElement).value)
          })
    })
  )
  app.component(
    'AAlert',
    defineComponent({
      props: ['message'],
      setup:
        (p, { slots }) =>
        () =>
          h('div', { role: 'alert' }, [p.message, slots.action?.()])
    })
  )
  app.component(
    'AEmpty',
    defineComponent({ props: ['description'], setup: p => () => h('div', { 'data-empty': true }, p.description) })
  )
  app.component('AResult', defineComponent({ props: ['title'], setup: p => () => h('p', p.title) }))
  app.mount(host)
  await flush()
}
beforeEach(() => {
  vi.clearAllMocks()
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
  permission.value = true
  props.date = '2026-10-07'
  props.refreshKey = 0
  managementEmployees.mockResolvedValue({ list: [staff], total: 21 })
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
  vi.restoreAllMocks()
})

describe('按员工汇总任务概况', () => {
  it('首次只查询一次服务端汇总，每页10条，不读取整个任务列表拼计数', async () => {
    await mount()
    expect(managementEmployees).toHaveBeenCalledTimes(1)
    expect(managementEmployees).toHaveBeenCalledWith({ search: undefined, date: '2026-10-07', pageNo: 1, pageSize: 10 })
    expect(host.textContent).toContain('张三')
    expect(element('[data-cell="pendingCount"]').textContent).toBe('2')
  })
  it('姓名与各数量下钻保留人员原始ID、指标和实际查询日期', async () => {
    await mount()
    expect(element('[data-cell="userName"] button').getAttribute('aria-label')).toBe('查看张三的相关任务')
    const keys = {
      userName: 'RELATED',
      pendingCount: 'PENDING',
      runningCount: 'RUNNING',
      overdueCount: 'OVERDUE',
      todayCount: 'TODAY',
      weekCount: 'WEEK',
      coordinationCount: 'COORDINATION',
      actions: 'RELATED'
    }
    for (const [key, metric] of Object.entries(keys)) {
      element<HTMLButtonElement>(`[data-cell="${key}"] button`).click()
      await flush()
      expect(select).toHaveBeenLastCalledWith({ userId: staff.userId, userName: '张三', metric, date: '2026-10-07' })
    }
  })
  it('未提交日期修改不影响原汇总的下钻；查询后分页沿用新日期和员工搜索', async () => {
    await mount()
    const date = element<HTMLInputElement>('[aria-label="查看日期"]')
    date.value = '2026-10-01'
    date.dispatchEvent(new Event('change'))
    const search = element<HTMLInputElement>('[aria-label="搜索员工姓名"]')
    search.value = ' 张 '
    search.dispatchEvent(new Event('input'))
    await flush()
    element<HTMLButtonElement>('[data-cell="todayCount"] button').click()
    await flush()
    expect(select).toHaveBeenLastCalledWith(expect.objectContaining({ date: '2026-10-07' }))
    await click('查询')
    await click('下一页')
    expect(managementEmployees).toHaveBeenLastCalledWith({ search: '张', date: '2026-10-01', pageNo: 2, pageSize: 10 })
    element<HTMLButtonElement>('[data-cell="weekCount"] button').click()
    await flush()
    expect(select).toHaveBeenLastCalledWith(expect.objectContaining({ date: '2026-10-01', metric: 'WEEK' }))
  })
  it('外部刷新保留已提交条件和当前页，重置恢复默认日期与第一页', async () => {
    await mount()
    await click('下一页')
    props.refreshKey++
    await flush()
    expect(managementEmployees).toHaveBeenLastCalledWith(expect.objectContaining({ pageNo: 2 }))
    await click('重置')
    expect(managementEmployees).toHaveBeenLastCalledWith({
      search: undefined,
      date: '2026-10-07',
      pageNo: 1,
      pageSize: 10
    })
  })
  it('失败清除旧数量并可重试，空结果有独立提示', async () => {
    await mount()
    managementEmployees.mockRejectedValueOnce(new Error('概况暂不可用'))
    await click('刷新')
    expect(host.textContent).toContain('概况暂不可用')
    expect(host.querySelector('[data-row]')).toBeNull()
    await click('重新加载')
    expect(host.textContent).toContain('张三')
    managementEmployees.mockResolvedValueOnce({ list: [], total: 0 })
    await click('刷新')
    expect(host.textContent).toContain('当前管理范围内没有匹配的员工任务')
  })
  it('无任务管理权限时不请求汇总也不可触发员工下钻', async () => {
    permission.value = false
    await mount()
    expect(managementEmployees).not.toHaveBeenCalled()
    expect(host.textContent).toContain('暂无任务管理权限')
    expect(select).not.toHaveBeenCalled()
  })
  it('加载中阻止使用旧数量下钻，迟到结果不会覆盖新的汇总', async () => {
    await mount()
    let resolveOld: (value: unknown) => void = () => undefined
    managementEmployees.mockImplementationOnce(() => new Promise(resolve => (resolveOld = resolve)))
    await click('刷新')
    expect(element<HTMLButtonElement>('[data-cell="pendingCount"] button').disabled).toBe(true)
    managementEmployees.mockResolvedValueOnce({ list: [{ ...staff, pendingCount: 9 }], total: 1 })
    props.refreshKey++
    await flush()
    resolveOld({ list: [{ ...staff, pendingCount: 3 }], total: 1 })
    await flush()
    expect(element('[data-cell="pendingCount"]').textContent).toBe('9')
  })
})

// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, ref, type App } from 'vue'
import TaskTemplateInstances from '@/views/nocode/task-center/TaskTemplateInstances.vue'
import { createTaskCenterApi } from '@/api/nocode/task-center'
import { newTaskNode } from './task-center'
import type { TaskRow, TaskTemplateInstance } from '@/types/nocode/task-center'
import type { NocodeHttpClient } from '@/api/nocode/object'

const api = vi.hoisted(() => ({ templateInstances: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ taskCenter: api }) }))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['dataSource', 'columns', 'pagination', 'serverPagination'],
    emits: ['change'],
    setup:
      (props, { slots, emit }) =>
      () =>
        h('section', [
          slots.search?.(),
          slots.actions?.(),
          h('span', { 'data-total': '', 'data-server': props.serverPagination }, String(props.pagination.total)),
          h('button', { onClick: () => emit('change', { current: 2, pageSize: 10 }) }, '下一页'),
          ...props.dataSource.map((record: { key: string }) =>
            h(
              'div',
              { 'data-key': record.key },
              props.columns.map((column: { key: string }) =>
                h('div', { 'data-column': column.key }, slots.bodyCell?.({ column, record }))
              )
            )
          ),
          props.dataSource.length ? null : slots.empty?.()
        ])
  })
}))
let app: App, host: HTMLDivElement
const open = vi.fn()
const instanceList = ref<InstanceType<typeof TaskTemplateInstances> | null>(null)
const props = reactive({
  templateId: 'template-one',
  publishedVersion: 2,
  versionSummaries: [
    { version: 2, name: 'V2', description: '', publishedAt: '', nodeCount: 1, primary: false },
    { version: 1, name: 'V1', description: '', publishedAt: '', nodeCount: 1, primary: true }
  ]
})
const task = (id: string, parentId: string | null = null): TaskRow => ({
  ...newTaskNode(),
  id,
  rootId: 'root',
  parentId,
  title: id,
  status: 'PENDING',
  creatorId: '1',
  creatorName: '管理员',
  assigneeName: '李志航',
  project: null,
  business: null,
  baselineStart: null,
  baselineEnd: null,
  expectedStart: null,
  expectedEnd: null,
  actualStart: null,
  actualEnd: null,
  createdAt: '2026-10-04T08:00:00',
  revision: 0,
  instanceRevision: 0,
  childCount: 0,
  plans: [],
  canStart: false,
  canExecute: false,
  canEdit: false,
  blockedReason: null,
  templateId: 'template-one',
  templateVersion: 1
})
const group = (rootVisible = true): TaskTemplateInstance => ({
  rootId: 'root',
  title: '办公室装修',
  templateVersion: 1,
  status: 'PENDING',
  root: rootVisible ? task('root') : null,
  nodes: [task('child', 'root')]
})
async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function button(label: string, scope: Element = host) {
  const result = Array.from(scope.querySelectorAll('button')).find(
    item => item.textContent?.trim() === label || item.getAttribute('aria-label') === label
  )
  if (!result) throw new Error(`找不到${label}`)
  return result
}
function element<T extends Element = Element>(selector: string) {
  const result = host.querySelector<T>(selector)
  if (!result) throw new Error(`找不到 ${selector}`)
  return result
}
async function mount() {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(
    defineComponent({ setup: () => () => h(TaskTemplateInstances, { ...props, ref: instanceList, onOpen: open }) })
  )
  const plain = defineComponent({
    props: ['description', 'message'],
    setup:
      (p, { slots }) =>
      () =>
        h('div', [p.description, p.message, slots.default?.()])
  })
  for (const name of ['AForm', 'AFormItem', 'ASpace', 'AEmpty', 'AAlert']) app.component(name, plain)
  app.component(
    'AButton',
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('button', slots.default?.())
    })
  )
  app.component(
    'AInput',
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup:
        (p, { emit }) =>
        () =>
          h('input', {
            value: p.value,
            onInput: (e: Event) => emit('update:value', (e.target as HTMLInputElement).value)
          })
    })
  )
  app.component(
    'ASelect',
    defineComponent({
      props: ['value', 'options'],
      emits: ['update:value'],
      setup:
        (p, { emit }) =>
        () =>
          h(
            'select',
            {
              value: p.value,
              onChange: (e: Event) => {
                const value = (e.target as HTMLSelectElement).value
                emit('update:value', value && /^\d+$/.test(value) ? Number(value) : value)
              }
            },
            p.options.map((option: { value: string | number; label: string }) =>
              h('option', { value: option.value }, option.label)
            )
          )
    })
  )
  app.mount(host)
  await flush()
}
beforeEach(() => {
  vi.clearAllMocks()
  props.templateId = 'template-one'
  props.publishedVersion = 2
  api.templateInstances.mockResolvedValue({ list: [group()], total: 12 })
})
afterEach(() => {
  app?.unmount()
  host?.remove()
})

describe('任务模板实例列表', () => {
  it('版本筛选只使用真实目录，不根据最大版本号补出不存在的快照', async () => {
    props.publishedVersion = 9
    await mount()
    expect(Array.from(element<HTMLSelectElement>('select').options).map(item => item.value)).toEqual(['', '2', '1'])
  })
  it('默认查询所有版本，根分页并展开子任务而不改变总数', async () => {
    await mount()
    expect(api.templateInstances).toHaveBeenLastCalledWith({ templateId: 'template-one', pageNo: 1, pageSize: 10 })
    expect(host.querySelectorAll('[data-key]')).toHaveLength(1)
    button('展开子任务').click()
    await flush()
    expect(host.querySelectorAll('[data-key]')).toHaveLength(2)
    expect(host.querySelector('[data-total]')?.textContent).toBe('12')
    button('详情', element('[data-key="child"]')).click()
    expect(open).toHaveBeenCalledWith('child')
  })
  it('没有根详情权限时不显示根详情按钮，仍允许打开可见子任务', async () => {
    api.templateInstances.mockResolvedValue({ list: [group(false)], total: 1 })
    await mount()
    const root = element('[data-key="root"]')
    expect(root.textContent).toContain('仅可查看有权限的子任务')
    expect(root.querySelector('[data-column="actions"]')?.textContent).toBe('—')
    button('展开子任务').click()
    await flush()
    button('详情', element('[data-key="child"]')).click()
    expect(open).toHaveBeenCalledWith('child')
  })
  it('实例按依赖显示先后，同时保留并行子项原编排顺序，不按名称排序', async () => {
    api.templateInstances.mockResolvedValue({
      list: [
        {
          ...group(),
          nodes: [
            { ...task('b', 'root'), title: '最先的名字', predecessorIds: ['a'] },
            { ...task('a', 'root'), title: '最后的名字' },
            { ...task('c', 'root'), predecessorIds: ['b'] },
            task('z-parallel', 'root'),
            task('d-parallel', 'root')
          ]
        }
      ],
      total: 1
    })
    await mount()
    button('展开子任务').click()
    await flush()
    expect(Array.from(host.querySelectorAll('[data-key]')).map(row => row.getAttribute('data-key'))).toEqual([
      'root',
      'a',
      'b',
      'c',
      'z-parallel',
      'd-parallel'
    ])
  })
  it('名称、版本和状态在服务器筛选，翻页保持已提交条件', async () => {
    await mount()
    const input = element<HTMLInputElement>('input')
    input.value = '施工'
    input.dispatchEvent(new Event('input'))
    const selects = Array.from(host.querySelectorAll('select'))
    for (const [index, value] of ['1', 'RUNNING'].entries()) {
      const select = selects[index]
      if (!select) throw new Error(`找不到第 ${index + 1} 个筛选项`)
      select.value = value
      select.dispatchEvent(new Event('change'))
    }
    await flush()
    button('查询').click()
    await flush()
    expect(api.templateInstances).toHaveBeenLastCalledWith({
      templateId: 'template-one',
      pageNo: 1,
      pageSize: 10,
      search: '施工',
      version: 1,
      status: 'RUNNING'
    })
    input.value = '尚未查询'
    input.dispatchEvent(new Event('input'))
    button('下一页').click()
    await flush()
    expect(api.templateInstances).toHaveBeenLastCalledWith({
      templateId: 'template-one',
      pageNo: 2,
      pageSize: 10,
      search: '施工',
      version: 1,
      status: 'RUNNING'
    })
    await instanceList.value?.refresh()
    expect(api.templateInstances).toHaveBeenLastCalledWith({
      templateId: 'template-one',
      pageNo: 2,
      pageSize: 10,
      search: '施工',
      version: 1,
      status: 'RUNNING'
    })
    expect(input.value).toBe('尚未查询')
  })
  it('创建实例后的外部刷新可更新此前空列表', async () => {
    api.templateInstances.mockResolvedValueOnce({ list: [], total: 0 })
    await mount()
    expect(host.querySelectorAll('[data-key]')).toHaveLength(0)
    api.templateInstances.mockResolvedValueOnce({ list: [group()], total: 1 })
    await instanceList.value?.refresh()
    await flush()
    expect(host.querySelector('[data-key="root"]')).not.toBeNull()
    expect(host.querySelector('[data-total]')?.textContent).toBe('1')
  })
  it('切换模板后重置筛选，并忽略旧模板迟到响应', async () => {
    let resolveOld!: (value: unknown) => void
    api.templateInstances.mockImplementationOnce(
      () =>
        new Promise(resolve => {
          resolveOld = resolve
        })
    )
    await mount()
    api.templateInstances.mockResolvedValue({
      list: [{ ...group(), rootId: 'other-root', root: task('other-root') }],
      total: 1
    })
    props.templateId = 'template-two'
    await flush()
    resolveOld({ list: [group()], total: 12 })
    await flush()
    expect(host.querySelector('[data-key="other-root"]')).not.toBeNull()
    expect(host.querySelector('[data-key="root"]')).toBeNull()
    expect(api.templateInstances).toHaveBeenLastCalledWith({ templateId: 'template-two', pageNo: 1, pageSize: 10 })
  })
  it('加载失败清空旧结果，不把异常伪装成无实例', async () => {
    await mount()
    api.templateInstances.mockRejectedValue(new Error('权限已变化'))
    button('刷新').click()
    await flush()
    expect(host.querySelectorAll('[data-key]')).toHaveLength(0)
    expect(host.textContent).toContain('权限已变化')
    expect(host.textContent).toContain('加载失败，请刷新重试')
  })
  it('未保存模板不发出空身份查询', async () => {
    props.templateId = ''
    await mount()
    expect(api.templateInstances).not.toHaveBeenCalled()
    expect(host.textContent).toContain('暂无符合条件且有权限查看的任务实例')
  })
  it('实例传输复用统一日期解码并保留空根权限边界', async () => {
    const post = vi.fn().mockResolvedValue({
      list: [{ ...group(false), nodes: [{ ...task('child', 'root'), plannedStart: 1893456000000 }] }],
      total: 1
    })
    const client = createTaskCenterApi({ post, get: vi.fn() } as unknown as NocodeHttpClient)
    const query = { templateId: 'stable-id', pageNo: 1, pageSize: 10, version: 1 }
    const result = await client.templateInstances(query)
    expect(post).toHaveBeenCalledWith('/nocode/task-templates/instances', query)
    expect(result.list[0]?.root).toBeNull()
    expect(typeof result.list[0]?.nodes[0]?.plannedStart).toBe('string')
  })
})

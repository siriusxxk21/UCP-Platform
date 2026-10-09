// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App, type Component } from 'vue'
import TaskContext from '@/views/nocode/task-center/TaskContext.vue'
import TaskDag from '@/views/nocode/task-center/TaskDag.vue'
import TaskLinkPicker from '@/views/nocode/task-center/TaskLinkPicker.vue'
import type { TaskRow } from '@/types/nocode/task-center'
import { newTaskNode } from './task-center'
import { TASK_DAG_WIDTH } from './task-dag-layout'

const api = vi.hoisted(() => ({ recordLinkCandidates: vi.fn(), recordLink: vi.fn(), detail: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ taskCenter: api }) }))
vi.mock('@/components/ucp-modal-form/OsModalForm.vue', () => ({
  default: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('section', slots.formItems?.())
  })
}))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['dataSource', 'columns'],
    setup:
      (props, { slots }) =>
      () =>
        h('div', [
          slots.search?.(),
          ...props.dataSource.map((record: TaskRow) =>
            h(
              'div',
              { 'data-task': record.id },
              props.columns.map((column: { key: string }) => slots.bodyCell?.({ column, record }))
            )
          )
        ])
  })
}))

const row = (id: string, title: string, parentId: string | null = null): TaskRow => ({
  ...newTaskNode(parentId),
  id,
  rootId: 'root',
  title,
  assigneeId: 1,
  assigneeName: '执行人',
  creatorId: 1,
  creatorName: '发起人',
  status: 'PENDING',
  project: null,
  business: null,
  baselineStart: null,
  baselineEnd: null,
  expectedStart: null,
  expectedEnd: null,
  actualStart: null,
  actualEnd: null,
  createdAt: '',
  revision: 7,
  instanceRevision: 7,
  childCount: 0,
  plans: [],
  canStart: true,
  canExecute: true,
  canEdit: true,
  blockedReason: null,
  templateId: null,
  templateVersion: null
})
let app: App | undefined, host: HTMLDivElement
const flush = async () => {
  for (let index = 0; index < 12; index++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount(component: Component, props: Record<string, unknown>) {
  app = createApp(() => h(component, props))
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of ['ASpace', 'ATag', 'AAlert', 'AInputSearch']) app.component(name, plain)
  app.component(
    'AButton',
    defineComponent({
      props: ['disabled', 'loading'],
      emits: ['click'],
      setup:
        (props, { slots, emit }) =>
        () =>
          h('button', { disabled: props.disabled || props.loading, onClick: () => emit('click') }, slots.default?.())
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
}
function required<T>(value: T | null | undefined): T {
  if (value == null) throw new Error('预期的任务层级元素未呈现')
  return value
}
const button = (label: string) =>
  required(Array.from(host.querySelectorAll('button')).find(element => element.textContent?.trim() === label))
const element = <T extends Element = HTMLElement>(selector: string) => required(host.querySelector<T>(selector))

beforeEach(() => {
  vi.clearAllMocks()
  api.recordLink.mockResolvedValue({})
})
afterEach(() => {
  app?.unmount()
  host?.remove()
})

describe('任务层级在详情、关系图和关联候选中保持一致', () => {
  it('详情树显示多级编号和父名称，折叠后仍可定位当前任务并保留切换行为', async () => {
    const select = vi.fn()
    await mount(TaskContext, {
      nodes: [row('root', '施工总任务'), row('stage', '基础施工', 'root'), row('leaf', '现场测量', 'stage')],
      currentId: 'leaf',
      originId: 'leaf',
      onSelect: select
    })
    const current = element('[role="treeitem"][aria-current="true"]')
    expect(current.getAttribute('aria-level')).toBe('3')
    expect(current.querySelector('.task-hierarchy')?.getAttribute('data-depth')).toBe('2')
    expect(current.querySelector('.task-hierarchy__outline')).toBeNull()
    expect(current.textContent).toContain('上级：基础施工')
    expect(host.querySelectorAll('.task-hierarchy--root')).toHaveLength(1)
    element<HTMLButtonElement>('[aria-label="收起：基础施工"]').click()
    await flush()
    expect(host.querySelector('[role="treeitem"][aria-current="true"]')).toBeNull()
    const stage = required(
      Array.from(host.querySelectorAll('[role="treeitem"]')).find(element => element.textContent?.includes('基础施工'))
    )
    expect(stage.getAttribute('aria-expanded')).toBe('false')
    button('定位当前任务').click()
    await flush()
    expect(host.querySelector('[role="treeitem"][aria-current="true"]')?.textContent).toContain('现场测量')
    button('基础施工').click()
    expect(select).toHaveBeenCalledWith('stage')
  })

  it('详情只拿到子树时不伪造总任务或完整编号', async () => {
    await mount(TaskContext, {
      nodes: [row('stage', '可见分工', 'hidden'), row('leaf', '下级工作', 'stage')],
      currentId: 'leaf',
      originId: 'leaf'
    })
    expect(host.querySelectorAll('.task-hierarchy--root')).toHaveLength(0)
    expect(host.querySelectorAll('[data-hierarchy]')).toHaveLength(0)
    expect(host.textContent).toContain('当前可见任务')
    expect(host.textContent).toContain('上级任务未在当前视图中')
    expect(host.textContent).toContain('上级：可见分工')
    expect(host.textContent).not.toContain('总负责人')
    expect(api.detail).not.toHaveBeenCalled()
  })

  it('本人子任务用只读上级摘要补全位置，不开放他人详情，也不把参考节点计入完成数量', async () => {
    const select = vi.fn()
    const leaf: TaskRow = {
      ...row('leaf', '墙面施工', 'stage'),
      ancestorContext: [
        {
          id: 'root',
          parentId: null,
          title: '施工总任务',
          assigneeName: '项目经理',
          status: 'PENDING',
          detailVisible: false
        },
        {
          id: 'stage',
          parentId: 'root',
          title: '二层施工',
          assigneeName: '施工主管',
          status: 'PENDING',
          detailVisible: false
        }
      ]
    }
    await mount(TaskContext, { nodes: [leaf], currentId: 'leaf', originId: 'leaf', onSelect: select })
    const path = element('[aria-label="任务位置"]')
    expect(path.textContent).toContain('施工总任务')
    expect(path.textContent).toContain('二层施工')
    expect(path.textContent).not.toContain('未在当前视图中')
    const items = Array.from(host.querySelectorAll('[role="treeitem"]'))
    expect(items).toHaveLength(3)
    expect(items[0]?.textContent).toContain('项目经理')
    expect(items[1]?.textContent).toContain('施工主管')
    expect(items[1]?.textContent).toContain('层级参考')
    expect(items[2]?.getAttribute('aria-level')).toBe('3')
    expect(
      Array.from(host.querySelectorAll('button')).some(item =>
        ['施工总任务', '二层施工'].includes(item.textContent?.trim() || '')
      )
    ).toBe(false)
    expect(host.textContent).toMatch(/正常完成\s*0\s*\/\s*1/)
    element<HTMLButtonElement>('[aria-label="收起：二层施工"]').click()
    await flush()
    expect(host.querySelector('[aria-current="true"]')).toBeNull()
    button('定位当前任务').click()
    await flush()
    expect(host.querySelector('[aria-current="true"]')).not.toBeNull()
    expect(select).not.toHaveBeenCalled()
    expect(api.detail).not.toHaveBeenCalled()
  })

  it('已授权的真实节点优先于摘要，保留原详情切换与状态', async () => {
    const select = vi.fn()
    const root = { ...row('root', '施工总任务'), status: 'RUNNING' as const }
    const leaf: TaskRow = {
      ...row('leaf', '我的工作', 'root'),
      ancestorContext: [
        { id: 'root', parentId: null, title: '旧摘要', assigneeName: '旧人员', status: 'PENDING', detailVisible: false }
      ]
    }
    await mount(TaskContext, { nodes: [root, leaf], currentId: 'leaf', originId: 'leaf', onSelect: select })
    expect(host.textContent).not.toContain('旧摘要')
    expect(host.textContent).not.toContain('层级参考')
    expect(host.textContent).toContain('进行中')
    button('施工总任务').click()
    expect(select).toHaveBeenCalledWith('root')
  })

  it('关系图用嵌套容器表达父子，编号不随前置依赖的横向排布变化', async () => {
    const later = { ...row('later', '设备送达', 'root'), predecessorIds: ['leaf'] }
    const select = vi.fn()
    await mount(TaskDag, {
      nodes: [row('root', '施工总任务'), row('stage', '基础施工', 'root'), row('leaf', '测量', 'stage'), later],
      currentId: 'later',
      onSelect: select
    })
    const node = element<SVGGElement>('[data-task-id="later"]')
    expect(node.getAttribute('data-hierarchy')).toBe('1.2')
    const horizontalPosition = (element: Element) =>
      Number(element.getAttribute('transform')?.match(/translate\((\d+)/)?.[1])
    expect(horizontalPosition(node)).toBeGreaterThan(horizontalPosition(element('[data-task-id="leaf"]')))
    expect(node.getAttribute('aria-label')).toContain('上级：施工总任务')
    expect(node.querySelector('rect')?.getAttribute('width')).toBe(String(TASK_DAG_WIDTH))
    expect(host.querySelectorAll('path[data-relation="parent"]')).toHaveLength(0)
    expect(host.querySelectorAll('[data-container-id]')).toHaveLength(2)
    const dependency = element('path[data-relation="predecessor"]')
    expect(dependency.hasAttribute('stroke-dasharray')).toBe(false)
    expect(dependency.getAttribute('marker-end')).toMatch(/^url\(#task-arrow-/)
    expect(host.textContent).toContain('框内是子任务')
    expect(host.textContent).toContain('总任务是整件事的容器，不是第一个步骤')
    expect(host.textContent).not.toContain('父任务开始后子任务才可执行')
    node.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    expect(select).toHaveBeenCalledWith('later')
  })

  it('模板关系图的首层是一级子任务，不称总任务，也不开放配置图节点切换', async () => {
    const select = vi.fn()
    await mount(TaskDag, {
      nodes: [{ ...newTaskNode(), id: 'draft', title: '模板分工' }],
      draft: true,
      interactive: false,
      onSelect: select
    })
    const node = element<SVGGElement>('[data-task-id="draft"]')
    expect(node.getAttribute('aria-label')).toContain('一级子任务 1')
    expect(node.getAttribute('aria-label')).toContain('上级：发起时的总任务')
    expect(host.querySelector('.task-dag__node--root')).toBeNull()
    expect(node.hasAttribute('tabindex')).toBe(false)
    node.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    expect(select).not.toHaveBeenCalled()
  })

  it('关系图父节点缺失时只显示当前可见子任务，不伪造上级或层级编号', async () => {
    await mount(TaskDag, { nodes: [row('orphan', '当前候选', 'outside')] })
    const node = element('[data-task-id="orphan"]')
    expect(node.getAttribute('aria-label')).toContain('子任务 · 当前候选')
    expect(node.getAttribute('aria-label')).toContain('上级任务未在当前视图中')
    expect(node.hasAttribute('data-hierarchy')).toBe(false)
    expect(host.querySelector('.task-dag__node--root')).toBeNull()
    expect(host.querySelectorAll('[data-relation]')).toHaveLength(0)
  })

  it('关联候选保持分页授权范围，缺父级的子项仍可按原身份关联', async () => {
    const candidate = row('candidate', '同名分工', 'outside')
    api.recordLinkCandidates.mockResolvedValue({ list: [candidate], total: 1 })
    const context = { applicationId: 'app', pageId: 'page', nodeId: 'tasks', recordId: 'record' }
    const linked = vi.fn()
    await mount(TaskLinkPicker, { context, onLinked: linked })
    const item = element('[data-task="candidate"]')
    expect(item.querySelector('.task-hierarchy__label')?.textContent).toBe('子任务')
    expect(item.textContent).toContain('上级任务未在当前视图中')
    expect(item.querySelector('[data-hierarchy]')).toBeNull()
    expect(api.recordLinkCandidates).toHaveBeenCalledWith(
      context,
      expect.objectContaining({ scope: 'MANAGE', tab: 'ALL', pageNo: 1, pageSize: 10 })
    )
    expect(api.detail).not.toHaveBeenCalled()
    button('关联').click()
    await flush()
    expect(api.recordLink).toHaveBeenCalledWith({
      context,
      taskId: 'candidate',
      expectedRevision: 7,
      include: true,
      requestKey: expect.any(String)
    })
    expect(linked).toHaveBeenCalledOnce()
  })
})

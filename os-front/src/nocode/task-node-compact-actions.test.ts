// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, ref, type App, type Ref } from 'vue'
import Antd, { Table } from 'ant-design-vue'
import TaskNodeEditor from '@/views/nocode/task-center/TaskNodeEditor.vue'
import TaskAssignmentFields from '@/views/nocode/task-center/TaskAssignmentFields.vue'
import type { TaskNodeInput } from '@/types/nocode/task-center'
import { newTaskNode } from './task-center'

const selection = vi.hoisted(() => ({ props: {} as Record<string, unknown> }))
vi.mock('@/components/UserSelector/index.vue', () => ({
  default: defineComponent({
    props: ['visible', 'title', 'multiple', 'selectedUsers', 'candidateUserIds', 'enabledOnly'],
    emits: ['confirm', 'update:visible'],
    setup: (props, { emit }) => {
      selection.props = props
      return () =>
        h('section', { 'data-user-selector': '' }, [
          h('span', props.title),
          h('button', { onClick: () => emit('update:visible', false) }, '取消人员选择'),
          h(
            'button',
            {
              onClick: () => emit('confirm', [{ id: '9007199254740993', username: 'fixture', nickname: '候选同事' }])
            },
            '确认人员选择'
          )
        ])
    }
  })
}))
vi.mock('@/views/nocode/task-center/TaskNodeFields.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskDag.vue', () => ({ default: { render: () => null } }))
vi.mock('@/components/os-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['columns', 'dataSource', 'expandedRowKeys', 'customRow'],
    emits: ['expand'],
    setup:
      (props, { emit, slots }) =>
      () =>
        h('section', [
          slots.actions?.(),
          h(
            Table,
            {
              rowKey: 'id',
              columns: props.columns,
              dataSource: props.dataSource,
              expandedRowKeys: props.expandedRowKeys,
              customRow: props.customRow,
              pagination: false,
              onExpand: (expanded: boolean, record: TaskNodeInput) => emit('expand', expanded, record)
            },
            { bodyCell: slots.bodyCell }
          )
        ])
  })
}))

let app: App | undefined
let host: HTMLDivElement
let nodes: Ref<TaskNodeInput[]>
const flush = async () => {
  for (let index = 0; index < 15; index++) {
    await Promise.resolve()
    await nextTick()
  }
}
function required<T>(value: T | null | undefined): T {
  if (value == null) throw new Error('缺少紧凑编排测试目标')
  return value
}
const row = (id: string) => required(host.querySelector<HTMLElement>(`tr[data-row-key="${id}"]`))
function button(label: string, root: ParentNode = document) {
  return required(
    Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find(
      item => item.textContent?.replace(/\s/g, '') === label.replace(/\s/g, '')
    )
  )
}
function icon(id: string, prefix: string) {
  return required(row(id).querySelector<HTMLButtonElement>(`button[aria-label^="${prefix}："]`))
}
async function openMenu(id: string) {
  icon(id, '更多操作').click()
  await flush()
}
function menuItem(label: string) {
  return required(
    Array.from(
      document.querySelectorAll<HTMLElement>('.ant-dropdown:not(.ant-dropdown-hidden) [role="menuitem"]')
    ).find(item => item.textContent?.trim() === label)
  )
}
function fixture() {
  return [
    { ...newTaskNode(), id: 'a', title: '准备' },
    { ...newTaskNode(), id: 'b', title: '执行', predecessorIds: ['a'] }
  ]
}
function install() {
  required(app).use(Antd)
  host = document.createElement('div')
  document.body.append(host)
  required(app).mount(host)
}
async function mountEditor(options: Record<string, unknown> = {}, initial = fixture()) {
  const root = reactive<TaskNodeInput>({ ...newTaskNode(), id: 'root', title: '整组任务', assignmentMode: 'OPEN' })
  nodes = ref(initial)
  app = createApp(() =>
    h(TaskNodeEditor, {
      root,
      members: [],
      inlineConfiguration: true,
      templateEditing: true,
      ...options,
      modelValue: nodes.value,
      'onUpdate:modelValue': (value: TaskNodeInput[]) => (nodes.value = value)
    })
  )
  install()
  await flush()
  return root
}
async function mountAssignment(readonly = false, mode: TaskNodeInput['assignmentMode'] = 'OPEN') {
  const node = reactive<TaskNodeInput>({
    ...newTaskNode(),
    assignmentMode: mode,
    candidateUserIds: ['existing-user']
  })
  app = createApp(() =>
    h(TaskAssignmentFields, {
      modelValue: node,
      members: [
        { id: 'existing-user', name: '原候选人员' },
        { id: '9007199254740993', name: '候选同事' }
      ],
      compact: true,
      tableEditing: true,
      inlineCandidates: true,
      readonly
    })
  )
  install()
  await flush()
  return node
}
beforeEach(() => {
  selection.props = {}
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'requestAnimationFrame', 'cancelAnimationFrame'] })
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe = vi.fn()
      unobserve = vi.fn()
      disconnect = vi.fn()
    }
  )
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: (query: string) => ({ matches: false, media: query, addListener: vi.fn(), removeListener: vi.fn() })
  })
  const nativeStyle = window.getComputedStyle.bind(window)
  vi.spyOn(window, 'getComputedStyle').mockImplementation(element => nativeStyle(element))
})
afterEach(async () => {
  app?.unmount()
  app = undefined
  await flush()
  await vi.runAllTimersAsync()
  await flush()
  host?.remove()
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('紧凑编排操作布局与原有新增语义', () => {
  it.each([
    ['模板', { templateEditing: true }],
    ['发起任务', { templateEditing: false }],
    ['实例调整', { templateEditing: false, instanceWorkspace: true, alignedTable: true }]
  ] as const)('%s共用任务内容、优先级和紧凑图标，不显示序号或紧急程度且保留历史数据', async (_label, options) => {
    const initial = fixture()
    required(initial[0]).urgency = 'URGENT'
    const before = JSON.stringify(initial)
    await mountEditor(options, initial)
    const headers = Array.from(host.querySelectorAll('thead th')).map(item => item.textContent?.trim())
    expect(headers).toContain('任务内容')
    expect(headers).toContain('优先级')
    expect(headers).not.toContain('紧急程度')
    expect(host.querySelector('[aria-label="紧急程度"]')).toBeNull()
    expect(host.querySelector('.task-hierarchy__outline')).toBeNull()
    expect(host.querySelector('.task-hierarchy__count')).toBeNull()
    for (const id of ['root', 'a', 'b']) {
      expect(icon(id, '拆分子任务').querySelector('[data-icon="plus"]')).not.toBeNull()
      expect(icon(id, '添加下一步').querySelector('[data-icon="enter"]')).not.toBeNull()
      expect(icon(id, '更多操作').querySelector('[data-icon="more"]')).not.toBeNull()
    }
    expect(row('root').querySelector('[aria-label="限定领取人员"]')).not.toBeNull()
    expect(JSON.stringify(nodes.value)).toBe(before)
  })

  it('左侧两个直接图标与展开箭头并存，右侧只有竖向更多图标且不显示下级数量', async () => {
    await mountEditor()
    for (const id of ['root', 'a', 'b']) {
      const branch = required(row(id).querySelector('.task-node-editor__branch-actions'))
      expect(branch.querySelectorAll('button')).toHaveLength(2)
      expect(icon(id, '拆分子任务').closest('td')).toBe(row(id).querySelector('td'))
      expect(icon(id, '添加下一步').closest('td')).toBe(row(id).querySelector('td'))
      expect(row(id).querySelectorAll('.task-node-editor__row-actions button')).toHaveLength(1)
      const more = icon(id, '更多操作')
      expect(more.textContent?.trim()).toBe('')
      expect(more.querySelector('[data-icon="more"]')).not.toBeNull()
      expect(row(id).querySelector('.task-hierarchy__count')).toBeNull()
    }
    expect(icon('root', '添加下一步').disabled).toBe(true)
    required(row('root').querySelector<HTMLButtonElement>('[aria-label="收起：整组任务"]')).click()
    await flush()
    expect(host.querySelector('tr[data-row-key="a"]')).toBeNull()
    required(row('root').querySelector<HTMLButtonElement>('[aria-label="展开：整组任务"]')).click()
    await flush()
    expect(row('a')).toBeDefined()
  })

  it('更多菜单取消任务配置，保留批量新增、并行、数据配置、移动与移除入口', async () => {
    await mountEditor()
    await openMenu('a')
    expect(
      Array.from(document.querySelectorAll('.ant-dropdown:not(.ant-dropdown-hidden) [role="menuitem"]')).map(item =>
        item.textContent?.trim()
      )
    ).toEqual(['批量拆分', '添加连续下级', '添加并行任务', '业务关联', '移动到其他任务下', '移除任务'])
    expect(document.querySelector('.ant-dropdown:not(.ant-dropdown-hidden)')?.textContent).not.toContain('任务配置')
    expect(document.querySelector('.ant-dropdown:not(.ant-dropdown-hidden)')?.textContent).not.toContain('过程反馈')
  })

  it('拆分图标直接添加下级并聚焦名称，不给新子任务添加前置边', async () => {
    await mountEditor()
    icon('a', '拆分子任务').click()
    await flush()
    const added = required(nodes.value.find(item => !['a', 'b'].includes(item.id)))
    expect(added.parentId).toBe('a')
    expect(added.predecessorIds).toEqual([])
    expect(row(added.id).contains(document.activeElement)).toBe(true)
    expect(document.querySelector('[role="menu"]')).toBeNull()
    expect(nodes.value.find(item => item.id === 'b')?.predecessorIds).toEqual(['a'])
  })

  it('下一步图标保持同级和先后关系，更多菜单取消空新增恢复原有后继', async () => {
    await mountEditor()
    const before = JSON.stringify(nodes.value)
    icon('a', '添加下一步').click()
    await flush()
    const added = required(nodes.value.find(item => !['a', 'b'].includes(item.id)))
    expect(added.parentId).toBeNull()
    expect(added.predecessorIds).toEqual(['a'])
    expect(nodes.value.find(item => item.id === 'b')?.predecessorIds).toEqual([added.id])
    expect(row(added.id).textContent).not.toContain('取消新增')
    await openMenu(added.id)
    menuItem('取消新增').click()
    await flush()
    expect(JSON.stringify(nodes.value)).toBe(before)
  })

  it.each([
    ['批量拆分', false],
    ['添加连续下级', true]
  ] as const)('%s 仍调用原批量新增，不改动其他任务', async (label, sequential) => {
    await mountEditor()
    const before = JSON.stringify(nodes.value)
    await openMenu('a')
    menuItem(label).click()
    await flush()
    const input = required(document.querySelector<HTMLTextAreaElement>('[aria-label="批量任务名称"]'))
    input.value = '检查甲\n检查乙'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    expect(JSON.stringify(nodes.value)).toBe(before)
    button('添加到编排').click()
    await flush()
    const added = nodes.value.filter(item => !['a', 'b'].includes(item.id))
    expect(added.map(item => item.parentId)).toEqual(['a', 'a'])
    expect(added[0]?.predecessorIds).toEqual([])
    expect(added[1]?.predecessorIds).toEqual(sequential ? [added[0]?.id] : [])
    expect(nodes.value.find(item => item.id === 'b')?.predecessorIds).toEqual(['a'])
  })

  it('关闭节点不能拆分，关闭上级不能加下一步，原数据不变', async () => {
    await mountEditor({ closedIds: ['root', 'a'] })
    const before = JSON.stringify(nodes.value)
    expect(icon('root', '拆分子任务').disabled).toBe(true)
    expect(icon('a', '拆分子任务').disabled).toBe(true)
    expect(icon('a', '添加下一步').disabled).toBe(true)
    await openMenu('a')
    for (const label of ['批量拆分', '添加连续下级']) {
      expect(menuItem(label).getAttribute('aria-disabled')).toBe('true')
      menuItem(label).click()
    }
    icon('a', '拆分子任务').click()
    icon('a', '添加下一步').click()
    await flush()
    expect(document.querySelector('[aria-label="批量任务名称"]')).toBeNull()
    expect(JSON.stringify(nodes.value)).toBe(before)
  })

  it('后继已锁定时下一步插入仍原子拒绝，不因新入口绕过校验', async () => {
    await mountEditor({ frozenIds: ['b'] })
    const before = JSON.stringify(nodes.value)
    icon('a', '添加下一步').click()
    await flush()
    expect(JSON.stringify(nodes.value)).toBe(before)
    expect(document.body.textContent).toMatch(/后续步骤|冻结/)
  })

  it('只读实例不出现左侧新增图标，也不能通过批量入口修改', async () => {
    await mountEditor({ readonly: true })
    const before = JSON.stringify(nodes.value)
    expect(host.querySelector('.task-node-editor__branch-actions')).toBeNull()
    await openMenu('a')
    expect(menuItem('批量拆分').getAttribute('aria-disabled')).toBe('true')
    menuItem('批量拆分').click()
    await flush()
    expect(document.querySelector('[aria-label="批量任务名称"]')).toBeNull()
    expect(JSON.stringify(nodes.value)).toBe(before)
  })
})

describe('开放领取的同行设置入口', () => {
  it('齿轮和分配下拉同行，取消选择保持既有领取范围', async () => {
    const node = await mountAssignment()
    const gear = required(host.querySelector<HTMLButtonElement>('[aria-label="限定领取人员"]'))
    const main = required(gear.closest('.task-assignment-fields__main'))
    expect(main.querySelector('[aria-label="负责人安排"]')).not.toBeNull()
    expect(host.querySelector('.task-assignment-fields__supplement')).toBeNull()
    const before = JSON.stringify(node)
    gear.click()
    await flush()
    expect(selection.props.multiple).toBe(true)
    expect(selection.props.selectedUsers).toEqual([{ id: 'existing-user', username: '', nickname: '原候选人员' }])
    expect(selection.props.candidateUserIds).toEqual(['existing-user', '9007199254740993'])
    button('取消人员选择').click()
    await flush()
    expect(host.querySelector('[data-user-selector]')).toBeNull()
    expect(JSON.stringify(node)).toBe(before)
  })

  it('齿轮确认只修改领取范围并保留雪花 ID 精度', async () => {
    const node = await mountAssignment()
    required(host.querySelector<HTMLButtonElement>('[aria-label="限定领取人员"]')).click()
    await flush()
    button('确认人员选择').click()
    await flush()
    expect(node.assignmentMode).toBe('OPEN')
    expect(node.assigneeId).toBeNull()
    expect(node.candidateUserIds).toEqual(['9007199254740993'])
    expect(host.querySelector('.task-assignment-fields__candidates--limited')).not.toBeNull()
  })

  it('只读领取范围按钮禁用，非开放领取不出现齿轮', async () => {
    const node = await mountAssignment(true)
    const gear = required(host.querySelector<HTMLButtonElement>('[aria-label="限定领取人员"]'))
    expect(gear.disabled).toBe(true)
    gear.click()
    await flush()
    expect(host.querySelector('[data-user-selector]')).toBeNull()
    node.assignmentMode = 'UNASSIGNED'
    await flush()
    expect(host.querySelector('[aria-label="限定领取人员"]')).toBeNull()
  })
})

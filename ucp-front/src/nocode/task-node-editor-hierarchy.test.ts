// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, ref, type App, type Ref } from 'vue'
import Antd, { Table } from 'ant-design-vue'
import TaskNodeEditor from '@/views/nocode/task-center/TaskNodeEditor.vue'
import type { TaskNodeInput } from '@/types/nocode/task-center'
import { newTaskNode } from './task-center'
import { taskDagCardSummary, type TaskDagDisplayNode } from './task-dag-summary'

const userSelection = vi.hoisted(() => ({ props: {} as Record<string, unknown>, id: '9007199254740993' }))
vi.mock('@/components/TiptapEditor.vue', () => ({
  __esModule: true,
  default: defineComponent({
    props: ['modelValue', 'disabled'],
    emits: ['update:modelValue'],
    setup:
      (props, { emit }) =>
      () =>
        h('textarea', {
          'aria-label': '任务内容富文本编辑器',
          value: props.modelValue,
          disabled: props.disabled,
          onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLTextAreaElement).value)
        })
  })
}))
vi.mock('@/components/UserSelector/index.vue', () => ({
  default: defineComponent({
    props: ['visible', 'multiple', 'candidateUserIds', 'enabledOnly', 'showMultipleToggle', 'selectedUsers'],
    emits: ['confirm'],
    setup: (props, { emit }) => {
      userSelection.props = props
      return () =>
        h(
          'button',
          { onClick: () => emit('confirm', [{ id: userSelection.id, nickname: '验收同事', username: 'acceptor' }]) },
          '确认验收人员'
        )
    }
  })
}))

vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['columns', 'dataSource', 'expandedRowKeys', 'customRow'],
    emits: ['expand'],
    setup:
      (props, { emit, slots }) =>
      () =>
        h('section', [
          slots.title?.(),
          slots.actions?.(),
          // 使用真实 AntD 树表验证 children 与受控展开；只省去与本题无关的搜索/分页外壳。
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
vi.mock('@/views/nocode/task-center/TaskNodeFields.vue', () => ({
  default: defineComponent({
    props: ['modelValue', 'hierarchyRootId', 'templateEditing', 'dataReadonly'],
    emits: ['update:modelValue'],
    setup:
      (props, { emit }) =>
      () =>
        h(
          'div',
          {
            'data-configured': props.modelValue.id,
            'data-root': props.hierarchyRootId,
            'data-template': String(!!props.templateEditing),
            'data-resource-readonly': String(!!props.dataReadonly)
          },
          [
            h(
              'button',
              { onClick: () => emit('update:modelValue', { ...props.modelValue, title: '配置中改名' }) },
              '配置中改名'
            ),
            h(
              'button',
              { onClick: () => emit('update:modelValue', { ...props.modelValue, parentId: null }) },
              '清空直接上级'
            )
          ]
        )
  })
}))
vi.mock('@/views/nocode/task-center/TaskDag.vue', () => ({
  default: defineComponent({
    props: ['draft', 'rootId', 'currentId', 'nodes', 'editable', 'frozenIds', 'closedIds', 'context', 'members'],
    emits: [
      'select',
      'link',
      'unlink',
      'rename',
      'addChild',
      'addNext',
      'addParallel',
      'configure',
      'insert',
      'move',
      'remove'
    ],
    setup:
      (props, { emit }) =>
      () =>
        h(
          'div',
          { 'data-dag-draft': String(props.draft), 'data-dag-root': props.rootId, 'data-selected': props.currentId },
          [
            h('button', { onClick: () => emit('select', 'b') }, '图上选择 B'),
            h('button', { onClick: () => emit('link', 'a', 'b') }, '连接 A 到 B'),
            h('button', { onClick: () => emit('link', 'b', 'a') }, '连接 B 到 A'),
            h('button', { onClick: () => emit('insert', 'a', 'b') }, '在 A 到 B 之间插入'),
            h('button', { onClick: () => emit('unlink', 'a', 'b') }, '移除 A 到 B'),
            h('button', { onClick: () => emit('rename', 'b', '设备安装已改名') }, '图上改名'),
            h('button', { onClick: () => emit('rename', props.rootId, '不应改动总任务') }, '图上改总任务名'),
            h('button', { onClick: () => emit('addChild', 'a11') }, '图上拆分'),
            h('button', { onClick: () => emit('addNext', 'b') }, '图上添加下一步'),
            h('button', { onClick: () => emit('addParallel', 'b') }, '图上添加并行任务'),
            h('button', { onClick: () => emit('configure', 'b') }, '图上配置'),
            h('button', { onClick: () => emit('configure', props.rootId) }, '图上配置总任务'),
            h('button', { onClick: () => emit('move', 'b') }, '图上移动 B'),
            h('button', { onClick: () => emit('move', props.rootId) }, '图上移动总任务'),
            h('button', { onClick: () => emit('remove', 'a') }, '图上删除 A'),
            h('button', { onClick: () => emit('remove', props.rootId) }, '图上删除总任务'),
            h('span', { 'data-graph-frozen': '' }, props.frozenIds?.join(',')),
            h('span', { 'data-graph-closed': '' }, props.closedIds?.join(',')),
            h('span', { 'data-graph-names': '' }, props.nodes.map((node: TaskNodeInput) => node.title).join('、')),
            ...props.nodes.map((node: TaskDagDisplayNode) =>
              h(
                'span',
                { 'data-graph-owner': node.id },
                taskDagCardSummary(node, {
                  context: props.context || 'draft',
                  nodes: props.nodes,
                  members: props.members
                }).owner
              )
            )
          ]
        )
  })
}))

function fixture(): TaskNodeInput[] {
  return [
    { ...newTaskNode(), id: 'a', title: '基础施工' },
    { ...newTaskNode('a'), id: 'a1', title: '开挖' },
    { ...newTaskNode('a1'), id: 'a11', title: '测量放线' },
    { ...newTaskNode(), id: 'b', title: '设备安装' }
  ]
}
let app: App | undefined, host: HTMLDivElement, nodes: Ref<TaskNodeInput[]>
const flush = async () => {
  for (let i = 0; i < 15; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function required<T>(value: T | null | undefined): T {
  if (!value) throw new Error('应存在当前测试目标')
  return value
}
const row = (id: string) => required(host.querySelector<HTMLElement>(`tr[data-row-key="${id}"]`))
const shownRows = () =>
  Array.from(host.querySelectorAll('tr[data-row-key]')).map(element => element.getAttribute('data-row-key'))
function button(text: string, root: ParentNode = document) {
  const matches = (item: HTMLButtonElement) => {
    const label = item.textContent?.replace(/\s/g, '')
    return (
      label === text.replace(/\s/g, '') ||
      (text === '更多' && item.getAttribute('aria-label')?.startsWith('更多操作：')) ||
      (['拆分子任务', '添加下一步'].includes(text) && item.getAttribute('aria-label')?.startsWith(`${text}：`))
    )
  }
  return required(Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find(matches) || null)
}
function menuItem(text: string) {
  return required(
    Array.from(
      document.querySelectorAll<HTMLElement>('.ant-dropdown:not(.ant-dropdown-hidden) [role="menuitem"]')
    ).find(item => item.textContent?.trim() === text)
  )
}
async function openCell(id: string, label: string) {
  required(row(id).querySelector<HTMLButtonElement>(`[aria-label="编辑${label}"]`)).click()
  await flush()
  await vi.advanceTimersByTimeAsync(100)
  await flush()
}
async function openTableSelect(id: string, label: string) {
  const select = required(row(id).querySelector(`[aria-label="${label}"]`)?.closest('.ant-select'))
  required(select.querySelector<HTMLElement>('.ant-select-selector')).dispatchEvent(
    new MouseEvent('mousedown', { bubbles: true })
  )
  await flush()
  return select
}
const tableDropdowns = new WeakMap<Element, Element>()
async function selectTableValue(id: string, label: string, option: string) {
  const existing = new Set(Array.from(document.querySelectorAll('.ant-select-dropdown')))
  const select = await openTableSelect(id, label)
  // AntD 测试模式复用同一 aria-controls；记录每个下拉的实际弹层，避免点到上一行残留的菜单。
  const dropdown = required(
    tableDropdowns.get(select) ||
      Array.from(document.querySelectorAll('.ant-select-dropdown')).find(item => !existing.has(item))
  )
  tableDropdowns.set(select, dropdown)
  required(
    Array.from(dropdown.querySelectorAll<HTMLElement>('.ant-select-item-option')).find(
      item => item.querySelector('.ant-select-item-option-content')?.textContent === option
    )
  ).click()
  await flush()
  await vi.advanceTimersByTimeAsync(300)
  await flush()
}
async function openMove(id: string) {
  button('更多', row(id)).click()
  await flush()
  menuItem('移动到其他任务下').click()
  await flush()
  return required(document.querySelector<HTMLElement>('.ant-modal-content'))
}
async function chooseMoveTarget(title: string) {
  const select = required(document.querySelector('[aria-label="移入任务"]')?.closest('.ant-select'))
  required(select.querySelector<HTMLElement>('.ant-select-selector')).dispatchEvent(
    new MouseEvent('mousedown', { bubbles: true })
  )
  await flush()
  const option = required(
    Array.from(document.querySelectorAll<HTMLElement>('.ant-select-item-option')).find(
      item => item.querySelector('.ant-select-item-option-content')?.textContent === title
    )
  )
  option.click()
  await flush()
}
async function mount(props: Record<string, unknown> = {}, initial = fixture()) {
  nodes = ref(initial)
  app = createApp(() =>
    h(TaskNodeEditor, {
      modelValue: nodes.value,
      'onUpdate:modelValue': (value: TaskNodeInput[]) => {
        nodes.value = value
      },
      members: [],
      ...props
    })
  )
  app.use(Antd)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
}
beforeEach(() => {
  userSelection.id = '9007199254740993'
  userSelection.props = {}
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
  const nativeComputedStyle = window.getComputedStyle.bind(window)
  vi.spyOn(window, 'getComputedStyle').mockImplementation(element => nativeComputedStyle(element))
})
afterEach(async () => {
  app?.unmount()
  await flush()
  // 真正执行 AntD/Vue 弹层剩余的双帧入场与离场回调，避免环境销毁后再访问 document。
  await vi.runAllTimersAsync()
  await flush()
  expect(vi.getTimerCount()).toBe(0)
  host?.remove()
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('共用任务编辑器层级与原生树表联动', () => {
  it.each([
    [{ templateEditing: true }, 'AUTO'],
    [{ autoSchedule: true }, 'AUTO'],
    [{}, 'UNSCHEDULED']
  ] as const)('新增拆分和下一步默认遵从显式自动排期开关 %j，不升级原节点', async (props, mode) => {
    const root = { ...newTaskNode(), id: 'root', title: '总任务' }
    const existing = { ...newTaskNode(), id: 'b', title: '已有工作' }
    await mount({ root, inlineConfiguration: true, ...props }, [existing])
    button('拆分子任务', row('root')).click()
    await flush()
    button('添加下一步', row('b')).click()
    await flush()
    const added = nodes.value.filter(item => item.id !== 'b')
    expect(added).toHaveLength(2)
    expect(added.every(item => item.schedule.mode === mode && item.schedule.durationDays === 1)).toBe(true)
    expect(added.find(item => item.predecessorIds.includes('b'))).toBeDefined()
    expect(root.schedule.mode).toBe('UNSCHEDULED')
    expect(existing.schedule.mode).toBe('UNSCHEDULED')
  })
  it('自动排期汇总父任务的时间抽屉不出现独立工期，叶节点仍可配置', async () => {
    const root = { ...newTaskNode(), id: 'root', title: '总任务' }
    root.schedule.mode = 'AUTO'
    const existing = { ...newTaskNode(), id: 'b', title: '现场工作' }
    existing.schedule.mode = 'AUTO'
    await mount({ root, templateEditing: true, inlineConfiguration: true }, [existing])
    expect(row('root').textContent).toContain('下级日期自动汇总')
    required(row('root').querySelector<HTMLButtonElement>('[aria-label="编辑时间安排"]')).click()
    await flush()
    let editor = required(document.querySelector<HTMLElement>('.ant-drawer-content'))
    expect(editor.textContent).toContain('自动汇总下级任务')
    expect(editor.querySelector('[aria-label="预计工期天数"]')).toBeNull()
    button('返回编排', editor).click()
    await flush()
    required(row('b').querySelector<HTMLButtonElement>('[aria-label="编辑时间安排"]')).click()
    await flush()
    editor = required(document.querySelector<HTMLElement>('.ant-drawer-content'))
    expect(editor.querySelector('[aria-label="预计工期天数"]')).not.toBeNull()
    expect(editor.textContent).toContain('无前序任务时跟随整体计划开始')
  })
  it('任务内容独立成列并在富文本弹窗确认后修改，数据列左对齐、操作列居中且无重复顶部创建入口', async () => {
    const root = reactive<TaskNodeInput>({ ...newTaskNode(), id: 'root', title: '总任务', description: '原内容' })
    await mount({ root, inlineConfiguration: true, templateEditing: true }, [])
    const headers = Array.from(host.querySelectorAll<HTMLElement>('thead th'))
    expect(headers.map(item => item.textContent?.trim())).toContain('任务内容')
    expect(
      headers.filter(item => item.textContent?.trim() !== '操作').every(item => item.style.textAlign === 'left')
    ).toBe(true)
    expect(headers.find(item => item.textContent?.trim() === '操作')?.style.textAlign).toBe('center')
    const description = required(row('root').querySelector('[aria-label="编辑任务内容 总任务"]'))
    const title = required(row('root').querySelector('[aria-label="编辑任务名称 1"]'))
    expect(description.closest('td')).not.toBe(title.closest('td'))
    await openCell('root', '任务内容 总任务')
    const modal = required(document.querySelector<HTMLElement>('.ant-modal-content'))
    expect(modal.textContent).toContain('任务内容')
    const input = required(modal.querySelector<HTMLTextAreaElement>('textarea[aria-label="任务内容富文本编辑器"]'))
    expect(input.value).toBe('<p>原内容</p>')
    input.value = '<p><strong>更新后的执行内容</strong></p>'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    expect(root.description).toBe('原内容')
    expect(root.title).toBe('总任务')
    expect(document.querySelector('.ant-popover')).toBeNull()
    expect(document.querySelector('.ant-drawer-content')).toBeNull()
    button('确定', modal).click()
    await flush()
    expect(row('root').querySelector('textarea')).toBeNull()
    expect(root.description).toBe('<p><strong>更新后的执行内容</strong></p>')
    expect(row('root').textContent).toContain('更新后的执行内容')
    expect(row('root').textContent).not.toContain('<strong>')
    const toolbarLabels = Array.from(host.querySelectorAll('button')).map(item => item.textContent?.trim())
    expect(toolbarLabels).not.toContain('批量添加')
    expect(toolbarLabels).not.toContain('添加子任务')
    expect(toolbarLabels).not.toContain('表格操作')
    expect(host.textContent).not.toContain('点击单元格编辑')
    button('拆分子任务', row('root')).click()
    await flush()
    expect(button('表格操作', host)).toBeDefined()
  })

  it('任务内容列在已冻结节点仍可只读查看，不能在弹窗修改正文', async () => {
    const root = reactive<TaskNodeInput>({
      ...newTaskNode(),
      id: 'root',
      title: '总任务',
      description: '<p><strong>已执行的内容</strong></p>'
    })
    await mount({ root, inlineConfiguration: true, frozenIds: ['root'] }, [])
    required(row('root').querySelector<HTMLButtonElement>('[aria-label="查看任务内容 总任务"]')).click()
    await flush()
    const modal = required(document.querySelector<HTMLElement>('.ant-modal-content'))
    expect(modal.querySelector('strong')?.textContent).toBe('已执行的内容')
    expect(modal.querySelector('textarea')).toBeNull()
    expect(
      Array.from(modal.querySelectorAll('button')).some(item => item.textContent?.replace(/\s/g, '') === '确定')
    ).toBe(false)
    expect(root.description).toBe('<p><strong>已执行的内容</strong></p>')
  })

  it('负责人下拉仅选择安排模式，指定时打开人员选择器且领取设置同排', async () => {
    const root = reactive<TaskNodeInput>({ ...newTaskNode(), id: 'root', title: '总任务' })
    await mount({ root, inlineConfiguration: true, members: [{ id: '9007199254740993', name: '员工甲' }] }, [
      { ...newTaskNode(), id: 'b', title: '子任务' }
    ])
    expect(row('root').querySelector('[aria-label="编辑负责人"]')).toBeNull()
    await selectTableValue('root', '负责人安排', '指定负责人')
    button('确认验收人员').click()
    await flush()
    expect(root.assignmentMode).toBe('ASSIGNED')
    expect(root.assigneeId).toBe('9007199254740993')
    expect(document.body.textContent).not.toContain('确认验收人员')
    expect(document.querySelector('.ant-popover')).toBeNull()
    await selectTableValue('b', '负责人安排', '随总任务负责人')
    expect(nodes.value[0]?.assignmentMode).toBe('FOLLOW_ROOT')
    await selectTableValue('root', '负责人安排', '暂不分配')
    expect(root.assignmentMode).toBe('UNASSIGNED')
    expect(root.assigneeId).toBeNull()
    await selectTableValue('root', '负责人安排', '开放领取')
    expect(root.assignmentMode).toBe('OPEN')
    expect(row('root').querySelector('[aria-label="限定领取人员"]')).not.toBeNull()
  })

  it('验收人独立成列且仅总任务可编辑，选择和清空均保留其他节点数据', async () => {
    const root = reactive<TaskNodeInput>({
      ...newTaskNode(),
      id: 'root',
      title: '总任务',
      assignmentMode: 'ASSIGNED',
      assigneeId: '1'
    })
    await mount(
      {
        root,
        inlineConfiguration: true,
        templateEditing: true,
        members: [
          { id: '1', name: '负责人' },
          { id: '9007199254740993', name: '验收同事' }
        ]
      },
      [{ ...newTaskNode(), id: 'b', title: '子任务' }]
    )
    const before = JSON.stringify(nodes.value)
    expect(Array.from(host.querySelectorAll('thead th')).map(item => item.textContent?.trim())).toContain('验收人')
    expect(row('root').querySelector('[aria-label="验收人"]')).not.toBeNull()
    expect(row('b').querySelector('[aria-label="验收人"]')).toBeNull()
    await selectTableValue('root', '验收人', '指定验收人')
    button('确认验收人员').click()
    await flush()
    expect(document.querySelector('.ant-drawer-content')).toBeNull()
    expect(document.querySelector('.ant-popover')).toBeNull()
    expect(document.body.textContent).not.toContain('确认验收人员')
    expect(root.acceptorId).toBe('9007199254740993')
    await selectTableValue('root', '验收人', '无需验收')
    expect(root.acceptorId).toBeNull()
    expect(root.assigneeId).toBe('1')
    expect(JSON.stringify(nodes.value)).toBe(before)
  })

  it('验收人员选择器排除负责人，即使异常确认同一人员也不覆盖原验收人', async () => {
    const root = reactive<TaskNodeInput>({
      ...newTaskNode(),
      id: 'root',
      title: '总任务',
      assignmentMode: 'ASSIGNED',
      assigneeId: '9007199254740993',
      acceptorId: '2'
    })
    await mount(
      {
        root,
        inlineConfiguration: true,
        members: [
          { id: '2', name: '原验收人' },
          { id: root.assigneeId, name: '总负责人' }
        ]
      },
      []
    )
    await selectTableValue('root', '验收人', '指定验收人')
    expect(userSelection.props.candidateUserIds).toEqual(['2'])
    button('确认验收人员').click()
    await flush()
    expect(root.acceptorId).toBe('2')
  })

  it('左右常驻入口保持拆分与先后语义，总任务下一步禁用且批量连续步骤只加到当前下级', async () => {
    const root = reactive<TaskNodeInput>({ ...newTaskNode(), id: 'root', title: '总任务' })
    await mount({ root, inlineConfiguration: true, templateEditing: true }, [
      { ...newTaskNode(), id: 'b', title: '现场工作' }
    ])
    const actions = ['拆分子任务', '添加下一步', '更多']
    for (const label of actions) expect(button(label, row('root'))).toBeDefined()
    const before = JSON.stringify(nodes.value)
    button('添加下一步', row('root')).click()
    await flush()
    expect(JSON.stringify(nodes.value)).toBe(before)
    button('拆分子任务', row('b')).click()
    await flush()
    const single = required(nodes.value.find(node => node.parentId === 'b'))
    expect(single.predecessorIds).toEqual([])
    single.title = '单独工作'
    await flush()
    button('更多', row('b')).click()
    await flush()
    menuItem('批量拆分').click()
    await flush()
    let names = required(document.querySelector<HTMLTextAreaElement>('[aria-label="批量任务名称"]'))
    names.value = '检查甲\n检查乙'
    names.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    button('添加到编排').click()
    await flush()
    const split = nodes.value.filter(node => ['检查甲', '检查乙'].includes(node.title))
    expect(split).toHaveLength(2)
    expect(split.every(node => node.parentId === 'b' && node.predecessorIds.length === 0)).toBe(true)
    button('更多', row('b')).click()
    await flush()
    menuItem('添加连续下级').click()
    await flush()
    names = required(document.querySelector<HTMLTextAreaElement>('[aria-label="批量任务名称"]'))
    names.value = '准备\n执行'
    names.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    button('添加到编排').click()
    await flush()
    const sequential = nodes.value.filter(node => ['准备', '执行'].includes(node.title))
    expect(sequential.map(node => node.parentId)).toEqual(['b', 'b'])
    expect(sequential[0]?.predecessorIds).toEqual([])
    expect(sequential[1]?.predecessorIds).toEqual([sequential[0]?.id])
    button('添加下一步', row('b')).click()
    await flush()
    const next = required(nodes.value.find(node => node.predecessorIds.includes('b')))
    expect(next.parentId).toBeNull()
    expect(nodes.value.some(node => node.id === root.id)).toBe(false)
  })

  it.each(['readonly', 'frozenIds', 'closedIds'] as const)(
    '表格编辑在%s边界保留字段与常用操作保护',
    async restriction => {
      const root = reactive<TaskNodeInput>({ ...newTaskNode(), id: 'root', title: '总任务', acceptorId: '2' })
      await mount(
        {
          root,
          inlineConfiguration: true,
          [restriction]: restriction === 'readonly' ? true : ['root', 'b'],
          // 运行态已关闭节点也属于冻结配置，关闭额外禁止继续拆分。
          ...(restriction === 'closedIds' ? { frozenIds: ['root', 'b'] } : {})
        },
        [{ ...newTaskNode(), id: 'b', title: '已有子任务' }]
      )
      expect(row('root').querySelector('[aria-label="验收人"]')?.closest('.ant-select')?.classList).toContain(
        'ant-select-disabled'
      )
      for (const id of ['root', 'b']) {
        expect(row(id).querySelector('[aria-label="编辑时间安排"]')).toBeNull()
        if (restriction === 'readonly') {
          expect(row(id).querySelector('[aria-label^="添加下一步："]')).toBeNull()
          expect(row(id).querySelector('[aria-label^="拆分子任务："]')).toBeNull()
        } else {
          expect(button('添加下一步', row(id)).disabled).toBe(id === 'root' || restriction !== 'frozenIds')
          expect(button('拆分子任务', row(id)).disabled).toBe(restriction !== 'frozenIds')
        }
        button('更多', row(id)).click()
        await flush()
        for (const label of ['批量拆分', '添加连续下级']) {
          expect(menuItem(label).getAttribute('aria-disabled') === 'true').toBe(restriction !== 'frozenIds')
        }
        button('更多', row(id)).click()
        await flush()
      }
      expect(root.acceptorId).toBe('2')
      expect(nodes.value).toHaveLength(1)
    }
  )

  it('旧无根默认配置模板发起时只锁子项资源，根配置和任务基本信息仍可调整', async () => {
    const root = { ...newTaskNode(), id: 'root', title: '旧模板本次总任务' }
    await mount({ root, inlineConfiguration: true, templateInstance: true, dataReadonly: false }, [
      { ...newTaskNode(), id: 'b', title: '本次子任务' }
    ])
    button('图上编排', host).click()
    await flush()
    button('图上配置总任务').click()
    await flush()
    expect(document.querySelector('[data-configured="root"]')?.getAttribute('data-resource-readonly')).toBe('false')
    button('返回编排').click()
    await flush()
    button('图上配置').click()
    await flush()
    expect(document.querySelector('[data-configured="b"]')?.getAttribute('data-resource-readonly')).toBe('true')
    button('配置中改名').click()
    await flush()
    button('返回编排').click()
    await flush()
    expect(nodes.value[0]?.title).toBe('配置中改名')
  })
  it('时间编辑单独打开抽屉，不夹带节点配置，也不把表单插入表格行', async () => {
    const root = reactive<TaskNodeInput>({ ...newTaskNode(), id: 'root', title: '总任务' })
    await mount({ root, inlineConfiguration: true, templateEditing: true }, [])
    const trigger = required(row('root').querySelector<HTMLButtonElement>('[aria-label="编辑时间安排"]'))
    const summary = row('root').textContent
    trigger.click()
    await flush()
    expect(row('root').querySelector('[aria-label="编辑时间安排"]')).toBe(trigger)
    expect(row('root').querySelector('[aria-label="预计工期天数"]')).toBeNull()
    const editor = required(document.querySelector<HTMLElement>('.ant-drawer-content'))
    expect(editor.closest('tr')).toBeNull()
    expect(document.querySelector('[role="dialog"][aria-label="编辑时间安排"]')).toBeNull()
    expect(editor.textContent).not.toContain('负责人安排')
    expect(editor.textContent).not.toContain('紧急程度')
    expect(editor.textContent).not.toContain('优先级')
    expect(editor.querySelector('[data-configured]')).toBeNull()
    expect(row('root').textContent).toBe(summary)
    root.schedule.mode = 'PLAN_START'
    root.schedule.durationDays = 5
    await flush()
    expect(editor.querySelector('[aria-label="预计工期天数"]')).not.toBeNull()
    expect(editor.textContent).toContain('排期方式')
    expect(editor.textContent).not.toContain('整体计划开始日待确定')
    expect(editor.textContent).not.toContain('实际开始、实际结束')
    expect(editor.textContent).not.toContain('具体起点在使用模板创建任务时填写')
    expect(row('root').textContent).toContain('5 天')
    required(editor.querySelector<HTMLButtonElement>('.ant-drawer-close')).click()
    await flush()
    expect(root.schedule.durationDays).toBe(5)
    expect(trigger.getAttribute('aria-expanded')).toBe('false')
    expect(row('root').textContent).toContain('5 天')
  })
  it('模板仅保留优先级，编辑不覆盖历史紧急值且只读时禁用', async () => {
    const root = reactive<TaskNodeInput>({ ...newTaskNode(), id: 'root', title: '办公室装修', urgency: 'URGENT' })
    const props = reactive({ root, templateEditing: true, inlineConfiguration: true, readonly: false })
    await mount(props, [{ ...newTaskNode(), id: 'b', title: '现场勘察' }])
    const headers = Array.from(host.querySelectorAll('thead th')).map(item => item.textContent?.trim())
    expect(headers).not.toContain('紧急程度')
    expect(headers).toContain('优先级')
    expect(headers).not.toContain('紧急 / 优先级')
    for (const id of ['root', 'b']) {
      expect(row(id).querySelector('[aria-label="紧急程度"]')).toBeNull()
      expect(row(id).querySelector('[aria-label="优先级"]')).not.toBeNull()
    }
    await selectTableValue('root', '优先级', '高')
    expect(root.urgency).toBe('URGENT')
    expect(root.priority).toBe('HIGH')
    await selectTableValue('b', '优先级', '高')
    expect(nodes.value[0]?.priority).toBe('HIGH')
    expect(nodes.value[0]?.urgency).toBe('NORMAL')
    props.readonly = true
    await flush()
    expect(host.textContent).not.toContain('点击单元格编辑')
    for (const id of ['root', 'b']) {
      for (const label of ['优先级']) {
        expect(row(id).querySelector(`[aria-label="${label}"]`)?.closest('.ant-select')?.classList).toContain(
          'ant-select-disabled'
        )
        expect(row(id).querySelector(`[aria-label="编辑${label}"]`)).toBeNull()
      }
    }
  })
  it('模板行内编辑总任务名称和工期，与节点抽屉和图共用同一份草稿', async () => {
    const root = reactive<TaskNodeInput>({
      ...newTaskNode(),
      id: 'root',
      title: '办公室装修',
      schedule: { mode: 'PLAN_START', fixedStart: null, offsetDays: 0, durationDays: 5 },
      dataPolicy: { version: 1, business: 'GROUP', feedback: 'GROUP' }
    })
    await mount({ root, templateEditing: true, inlineConfiguration: true }, [
      { ...newTaskNode(), id: 'b', title: '现场勘察' }
    ])
    await openCell('root', '任务名称 1')
    const name = required(document.querySelector<HTMLInputElement>('input[aria-label="总任务名称 1"]'))
    name.value = '装修工作'
    name.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    required(row('root').querySelector<HTMLButtonElement>('[aria-label="编辑时间安排"]')).click()
    await flush()
    const duration = required(
      document.querySelector<HTMLInputElement>('.ant-drawer-content input[aria-label="预计工期天数"]')
    )
    duration.value = '8'
    duration.dispatchEvent(new Event('input', { bubbles: true }))
    duration.dispatchEvent(new Event('change', { bubbles: true }))
    await flush()
    expect(root.title).toBe('装修工作')
    expect(root.schedule.durationDays).toBe(8)
    expect(row('root').textContent).not.toContain('在上方编辑总任务')
    required(document.querySelector<HTMLButtonElement>('.ant-drawer-close')).click()
    await flush()
    button('图上编排', host).click()
    await flush()
    button('图上配置').click()
    await flush()
    button('配置中改名').click()
    await flush()
    button('返回编排').click()
    await flush()
    expect(nodes.value[0]?.title).toBe('配置中改名')
    expect(document.querySelector('[data-graph-names]')?.textContent).toBe('装修工作、配置中改名')
  })
  it('模板初始只显示摘要，常用拆分动作直接展示、不再保留列表配置按钮，新增仍可直接输入', async () => {
    const root = reactive<TaskNodeInput>({ ...newTaskNode(), id: 'root', title: '总任务' })
    await mount({ root, inlineConfiguration: true, templateEditing: true }, [
      { ...newTaskNode(), id: 'b', title: '检查' }
    ])
    expect(host.querySelectorAll('tbody input.ant-input')).toHaveLength(0)
    expect(row('root').querySelector('td')?.classList).toContain('ant-table-cell-fix-left')
    expect(
      Array.from(required(row('b').lastElementChild).querySelectorAll('button')).map(item =>
        item.textContent?.replace(/\s/g, '')
      )
    ).toEqual([''])
    expect(button('更多', row('b')).getAttribute('aria-label')).toBe('更多操作：检查')
    expect(row('b').querySelectorAll('.task-node-editor__branch-actions button')).toHaveLength(2)
    expect(button('添加下一步', row('root')).disabled).toBe(true)
    expect(
      Array.from(host.querySelectorAll('button')).some(item =>
        ['添加子任务', '批量添加'].includes(item.textContent?.trim() || '')
      )
    ).toBe(false)
    button('拆分子任务', row('root')).click()
    await flush()
    const added = required(nodes.value.at(-1))
    expect(row(added.id).querySelector('input.ant-input')).not.toBeNull()
    const addedInput = required(row(added.id).querySelector<HTMLInputElement>('input.ant-input'))
    addedInput.value = '新拆分工作'
    addedInput.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    addedInput.dispatchEvent(new FocusEvent('blur'))
    await flush()
    expect(row(added.id).querySelector('input.ant-input')).toBeNull()
    expect(row(added.id).textContent).toContain('新拆分工作')
    button('添加下一步', row('b')).click()
    await flush()
    expect(nodes.value.some(node => node.predecessorIds.includes('b'))).toBe(true)
  })
  it('收起单元格编辑保留草稿，Escape 返回触发按钮，不额外提交', async () => {
    const root = reactive<TaskNodeInput>({ ...newTaskNode(), id: 'root', title: '总任务' })
    await mount({ root, inlineConfiguration: true }, [])
    await openCell('root', '任务名称 1')
    const input = required(document.querySelector<HTMLInputElement>('input[aria-label="总任务名称 1"]'))
    expect(document.activeElement).toBe(input)
    input.value = '草稿中修改'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flush()
    expect(root.title).toBe('草稿中修改')
    expect(row('root').querySelector('input.ant-input')).toBeNull()
    expect(document.activeElement?.getAttribute('aria-label')).toBe('编辑任务名称 1')
    await openCell('root', '任务名称 1')
    required(document.querySelector('input[aria-label="总任务名称 1"]')).dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', keyCode: 13, bubbles: true })
    )
    await flush()
    expect(row('root').querySelector('input.ant-input')).toBeNull()
    expect(document.activeElement?.getAttribute('aria-label')).toBe('编辑任务名称 1')
  })
  it('开放总任务模板新增跟随子项时图摘要只包装一次来源和待承接', async () => {
    const root = { ...newTaskNode(), id: 'root', title: '整件施工' }
    await mount({ root, templateEditing: true }, [])
    button('拆分子任务', host).click()
    await flush()
    const child = required(nodes.value[0])
    expect(child.assignmentMode).toBe('FOLLOW_ROOT')
    button('图上编排', host).click()
    await flush()
    expect(document.querySelector(`[data-graph-owner="${child.id}"]`)?.textContent).toBe('随总任务负责人（待承接）')
  })
  it.each(['模板', '实例调整', '只读实例'])('%s的图与配置分别打开抽屉，逐层返回不丢数据', async context => {
    const instance = context !== '模板'
    const readonly = context === '只读实例'
    const root = { ...newTaskNode(), id: 'root', title: '施工任务' }
    const initial = fixture()
    await mount(
      {
        root: instance ? undefined : root,
        rootId: instance ? root.id : undefined,
        templateEditing: !instance,
        readonly
      },
      instance ? [root, ...initial.map(node => ({ ...node, parentId: node.parentId || root.id }))] : initial
    )
    const original = JSON.stringify(nodes.value)
    button('图上编排', host).click()
    await flush()
    const drawer = required(document.querySelector<HTMLElement>('.ant-drawer-content-wrapper'))
    expect(host.querySelector('[data-dag-root]')).toBeNull()
    expect(drawer.style.width).toBe(`${window.innerWidth - 48}px`)
    expect(document.querySelectorAll('.ant-drawer-content')).toHaveLength(1)
    expect(drawer.textContent).toContain(readonly ? '任务关系图' : '图上编排')
    button('图上配置', drawer).click()
    await flush()
    const configuration = required(document.querySelector('[aria-label="任务节点属性"]'))
    expect(drawer.querySelector('[aria-label="任务节点属性"]')).toBeNull()
    expect(configuration.closest('.ant-drawer-content')).not.toBeNull()
    expect(document.querySelectorAll('.ant-drawer-content')).toHaveLength(2)
    button('配置中改名', configuration).click()
    await flush()
    button('返回编排', required(configuration.closest('.ant-drawer-content'))).click()
    await flush()
    expect(document.querySelectorAll('.ant-drawer-content')).toHaveLength(1)
    expect(drawer.querySelector('[data-graph-names]')?.textContent).toContain(readonly ? '设备安装' : '配置中改名')
    if (!readonly) button('图上改名', drawer).click()
    await flush()
    // 关闭图只返回同一编排，不走放弃草稿或任务保存。
    required(drawer.querySelector<HTMLButtonElement>('.ant-drawer-close')).click()
    await flush()
    expect(document.querySelector('.ant-drawer-content')).toBeNull()
    if (readonly) expect(row('b').textContent).toContain('设备安装')
    if (readonly) expect(JSON.stringify(nodes.value)).toBe(original)
    else expect(required(row('b').querySelector<HTMLInputElement>('input.ant-input')).value).toBe('设备安装已改名')
    button('图上编排', host).click()
    await flush()
    expect(document.querySelector('[data-graph-names]')?.textContent).toContain(
      readonly ? '设备安装' : '设备安装已改名'
    )
    expect(document.querySelectorAll('.ant-drawer-content')).toHaveLength(1)
    required(document.querySelector<HTMLButtonElement>('.ant-drawer-close')).click()
    await flush()
    expect(document.querySelector('.ant-drawer-content')).toBeNull()
    expect(shownRows()).toContain('b')
  })

  it.each(['新建', '模板', '实例调整'])('%s列表配置独立抽屉，关闭不收起列表且重开保留草稿', async context => {
    await mount({ templateEditing: context === '模板', rootId: context === '实例调整' ? 'a' : undefined })
    const originalRows = shownRows()
    button('配置', row('b')).click()
    await flush()
    expect(host.querySelector('[aria-label="任务节点属性"]')).toBeNull()
    const drawer = required(document.querySelector('.ant-drawer-content'))
    expect(drawer.textContent).toContain('配置任务节点')
    expect(shownRows()).toEqual(originalRows)
    button('配置中改名', drawer).click()
    await flush()
    required(drawer.querySelector<HTMLButtonElement>('.ant-drawer-close')).click()
    await flush()
    expect(document.querySelector('.ant-drawer-content')).toBeNull()
    expect(required(row('b').querySelector<HTMLInputElement>('input.ant-input')).value).toBe('配置中改名')
    button('配置', row('b')).click()
    await flush()
    expect(document.querySelector('[data-configured]')?.getAttribute('data-configured')).toBe('b')
    expect(nodes.value.find(node => node.id === 'b')?.title).toBe('配置中改名')
  })

  it('图上选中和新增只改变选中项，点配置才打开节点抽屉', async () => {
    await mount()
    button('图上编排', host).click()
    await flush()
    button('图上选择 B').click()
    await flush()
    expect(document.querySelector('[aria-label="任务节点属性"]')).toBeNull()
    button('图上添加下一步').click()
    await flush()
    expect(document.querySelector('[aria-label="任务节点属性"]')).toBeNull()
    expect(document.querySelectorAll('.ant-drawer-content')).toHaveLength(1)
    button('图上配置').click()
    await flush()
    expect(document.querySelectorAll('.ant-drawer-content')).toHaveLength(2)
  })

  it('图抽屉跟随视口，窄屏占满宽度，不打开第二份图', async () => {
    const originalWidth = window.innerWidth
    await mount()
    button('图上编排', host).click()
    await flush()
    try {
      Object.defineProperty(window, 'innerWidth', { configurable: true, value: 600 })
      window.dispatchEvent(new Event('resize'))
      await flush()
      expect(document.querySelector<HTMLElement>('.ant-drawer-content-wrapper')?.style.width).toBe('600px')
      Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1440 })
      window.dispatchEvent(new Event('resize'))
      await flush()
      expect(document.querySelector<HTMLElement>('.ant-drawer-content-wrapper')?.style.width).toBe('1392px')
      expect(document.querySelectorAll('[data-dag-draft]')).toHaveLength(1)
    } finally {
      Object.defineProperty(window, 'innerWidth', { configurable: true, value: originalWidth })
      window.dispatchEvent(new Event('resize'))
    }
  })

  it.each(['列表', '关系图'])('模板从%s配置子任务时传递模板时间规则语境', async entry => {
    await mount({ templateEditing: true })
    if (entry === '关系图') {
      button('图上编排', host).click()
      await flush()
      button('图上配置').click()
    } else button('配置', row('b')).click()
    await flush()
    expect(document.querySelector('[data-configured="b"]')?.getAttribute('data-template')).toBe('true')
  })
  it('图上删除整棵未执行子树先确认，取消不变，确认清理入出边但不桥接或改其他分支', async () => {
    const initial = fixture()
    initial.push(
      { ...newTaskNode(), id: 'before', title: '采购' },
      { ...newTaskNode(), id: 'c', title: '其他分支', predecessorIds: ['before'] }
    )
    required(initial.find(node => node.id === 'a')).predecessorIds = ['before']
    required(initial.find(node => node.id === 'b')).predecessorIds = ['a11']
    await mount({ root: { ...newTaskNode(), id: 'root', title: '办公室装修' } }, initial)
    const before = JSON.stringify(nodes.value)
    button('图上编排', host).click()
    await flush()
    button('图上删除 A').click()
    await flush()
    let modal = required(document.querySelector('.ant-modal-content'))
    expect(modal.textContent).toMatch(/3\s*(个|项)/)
    expect(modal.textContent).toMatch(/2\s*条/)
    expect(JSON.stringify(nodes.value)).toBe(before)
    button('取消', modal).click()
    await flush()
    expect(JSON.stringify(nodes.value)).toBe(before)
    button('图上删除 A').click()
    await flush()
    modal = required(document.querySelector('.ant-modal-content'))
    required(modal.querySelector<HTMLButtonElement>('.ant-btn-primary')).click()
    await flush()
    expect(nodes.value.map(node => node.id).sort()).toEqual(['b', 'before', 'c'])
    expect(nodes.value.find(node => node.id === 'b')?.predecessorIds).toEqual([])
    expect(nodes.value.find(node => node.id === 'c')?.predecessorIds).toEqual(['before'])
    expect(nodes.value.some(node => node.id === 'root')).toBe(false)
  })

  it.each(['后代冻结', '冻结后继', '共享业务引用', '反馈来源引用', '相对时间前置'] as const)(
    '图上删除遇%s完整拒绝，不先清数据或引用',
    async restriction => {
      const initial = fixture()
      const b = required(initial.find(node => node.id === 'b'))
      const frozenIds = restriction === '后代冻结' ? ['a11'] : restriction === '冻结后继' ? ['b'] : []
      if (restriction === '冻结后继' || restriction === '相对时间前置') b.predecessorIds = ['a11']
      if (restriction === '相对时间前置') b.schedule.mode = 'PREDECESSOR'
      if (restriction === '共享业务引用') b.sharing = { mode: 'SHARED', sourceNodeId: 'a11', writableFieldIds: [] }
      if (restriction === '反馈来源引用')
        b.entries = [
          {
            key: 'shared-report',
            name: '共享施工日志',
            binding: null,
            dataMode: 'SOURCE_SHARED',
            sourceNodeId: 'a11',
            sourceEntryKey: 'daily-log',
            readableFieldIds: null,
            writableFieldIds: null,
            required: false,
            allowAll: false
          }
        ]
      await mount({ root: { ...newTaskNode(), id: 'root', title: '办公室装修' }, frozenIds }, initial)
      const before = JSON.stringify(nodes.value)
      button('图上编排', host).click()
      await flush()
      button('图上删除 A').click()
      await flush()
      expect(JSON.stringify(nodes.value)).toBe(before)
      expect(document.querySelector('.ant-modal-content')).toBeNull()
      expect(document.body.textContent).toMatch(/冻结|已开始|引用|前置|不能删除/)
    }
  )

  it('删除确认等待期间下级被冻结，最终确认也不能按旧快照执行', async () => {
    const restrictions = reactive({ frozenIds: [] as string[] })
    await mount({ root: { ...newTaskNode(), id: 'root', title: '办公室装修' }, frozenIds: restrictions.frozenIds })
    const before = JSON.stringify(nodes.value)
    button('图上编排', host).click()
    await flush()
    button('图上删除 A').click()
    await flush()
    const modal = required(document.querySelector('.ant-modal-content'))
    restrictions.frozenIds.push('a11')
    await flush()
    button('确认删除', modal).click()
    await flush()
    expect(JSON.stringify(nodes.value)).toBe(before)
    expect(document.body.textContent).toMatch(/冻结|变更|重新/)
  })
  it('调整未开始的总任务不可移除，名称回车只能在真实根下新增子任务', async () => {
    await mount({ rootId: 'root' }, [{ ...newTaskNode(), id: 'root', title: '整件施工' }])
    button('更多', row('root')).click()
    await flush()
    expect(
      Array.from(document.querySelectorAll('[role="menuitem"]')).some(item => item.textContent?.trim() === '移除')
    ).toBe(false)
    const input = required(row('root').querySelector<HTMLInputElement>('input.ant-input'))
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', keyCode: 13, bubbles: true }))
    await flush()
    expect(nodes.value).toHaveLength(2)
    expect(nodes.value.filter(node => !node.parentId).map(node => node.id)).toEqual(['root'])
    expect(nodes.value[1]?.parentId).toBe('root')
    expect(nodes.value[1]?.id).not.toBe('root')
    expect(row(required(nodes.value[1]).id).querySelector('.task-hierarchy')?.getAttribute('data-depth')).toBe('1')
  })

  it('调整实例移动回总任务下保留真实rootId，不产生第二个根', async () => {
    await mount(
      { rootId: 'a' },
      fixture().filter(node => node.id !== 'b')
    )
    await openMove('a11')
    await chooseMoveTarget('基础施工')
    expect(nodes.value.find(node => node.id === 'a11')?.parentId).toBe('a1')
    button('确认移动').click()
    await flush()
    expect(nodes.value.find(node => node.id === 'a11')?.parentId).toBe('a')
    expect(nodes.value.filter(node => !node.parentId).map(node => node.id)).toEqual(['a'])
    expect(row('a11').querySelector('.task-hierarchy')?.getAttribute('data-depth')).toBe('1')
  })

  it('配置顶部显示完整所属路径，不把调整归属混在任务内容字段中', async () => {
    await mount({ root: { ...newTaskNode(), id: 'root', title: '办公室装修' } })
    button('配置', row('a11')).click()
    await flush()
    const location = required(document.querySelector('[aria-label="所属位置"]'))
    expect(location.textContent).toContain('办公室装修 / 基础施工 / 开挖')
    expect(location.querySelector('input,select,button')).toBeNull()
  })

  it('旧配置表单残留的parentId改变不会绕过显式移动校验', async () => {
    await mount({ root: { ...newTaskNode(), id: 'root', title: '办公室装修' } })
    button('配置', row('a11')).click()
    await flush()
    button('清空直接上级').click()
    await flush()
    button('返回编排').click()
    await flush()
    expect(nodes.value.find(node => node.id === 'a11')?.parentId).toBe('a1')
  })

  it('图上移动复用同一弹框，选择新归属后取消不写模型，总任务没有移动入口', async () => {
    await mount({ root: { ...newTaskNode(), id: 'root', title: '办公室装修' } })
    const before = JSON.stringify(nodes.value)
    button('更多', row('root')).click()
    await flush()
    expect(
      Array.from(document.querySelectorAll('[role="menuitem"]')).some(
        item => item.textContent?.trim() === '移动到其他任务下'
      )
    ).toBe(false)
    button('图上编排', host).click()
    await flush()
    button('图上移动总任务').click()
    await flush()
    expect(document.querySelector('[aria-label="移入任务"]')).toBeNull()
    button('图上移动 B').click()
    await flush()
    await chooseMoveTarget('办公室装修 / 基础施工')
    button('取消', required(document.querySelector('.ant-modal-content'))).click()
    await flush()
    expect(JSON.stringify(nodes.value)).toBe(before)
  })

  it('移动携带下级且不清空顺序；外部总任务返回一级时仍保存parentId null', async () => {
    const root = { ...newTaskNode(), id: 'root', title: '办公室装修' }
    const initial = fixture()
    initial.push({ ...newTaskNode(), id: 'before', title: '采购到货' })
    required(initial.find(node => node.id === 'a')).predecessorIds = ['before']
    await mount({ root }, initial)
    const before = JSON.stringify(nodes.value)
    const modal = await openMove('a')
    expect(modal.textContent).toContain('当前所属：办公室装修')
    await chooseMoveTarget('办公室装修 / 设备安装')
    expect(modal.textContent).toContain('办公室装修 / 设备安装')
    expect(JSON.stringify(nodes.value)).toBe(before)
    button('确认移动').click()
    await flush()
    expect(nodes.value.find(node => node.id === 'a')?.parentId).toBe('b')
    expect(nodes.value.find(node => node.id === 'a1')?.parentId).toBe('a')
    expect(nodes.value.find(node => node.id === 'a11')?.parentId).toBe('a1')
    expect(nodes.value.find(node => node.id === 'a')?.predecessorIds).toEqual(['before'])
    expect(nodes.value).toHaveLength(initial.length)
    await openMove('a')
    await chooseMoveTarget('办公室装修')
    button('确认移动').click()
    await flush()
    expect(nodes.value.find(node => node.id === 'a')?.parentId).toBeNull()
    expect(nodes.value.some(node => node.id === root.id)).toBe(false)
    expect(nodes.value.find(node => node.id === 'a')?.predecessorIds).toEqual(['before'])
  })

  it('取消移动不改变任何数据；自身、下级、已结束与顺序死锁目标不可选', async () => {
    const root = { ...newTaskNode(), id: 'root', title: '办公室装修' }
    const initial = fixture()
    initial.push({ ...newTaskNode(), id: 'closed', title: '已结束工作' })
    required(initial.find(node => node.id === 'b')).predecessorIds = ['a']
    await mount({ root, closedIds: ['closed'] }, initial)
    const before = JSON.stringify(nodes.value)
    await openMove('a')
    const select = required(document.querySelector('[aria-label="移入任务"]')?.closest('.ant-select'))
    required(select.querySelector<HTMLElement>('.ant-select-selector')).dispatchEvent(
      new MouseEvent('mousedown', { bubbles: true })
    )
    await flush()
    for (const title of [
      '办公室装修 / 基础施工',
      '办公室装修 / 基础施工 / 开挖',
      '办公室装修 / 设备安装',
      '办公室装修 / 已结束工作'
    ]) {
      const option = required(
        Array.from(document.querySelectorAll<HTMLElement>('.ant-select-item-option')).find(
          item => item.querySelector('.ant-select-item-option-content')?.textContent === title
        )
      )
      expect(option.classList.contains('ant-select-item-option-disabled')).toBe(true)
    }
    button('取消', required(document.querySelector('.ant-modal-content'))).click()
    await flush()
    expect(JSON.stringify(nodes.value)).toBe(before)
  })

  it('打开移动弹框后下级被冻结，确认时重新校验并原子拒绝', async () => {
    const state = reactive({ frozenIds: [] as string[] })
    await mount({ root: { ...newTaskNode(), id: 'root', title: '办公室装修' }, frozenIds: state.frozenIds })
    const before = JSON.stringify(nodes.value)
    await openMove('a')
    await chooseMoveTarget('办公室装修 / 设备安装')
    state.frozenIds.push('a11')
    await flush()
    button('确认移动').click()
    await flush()
    expect(JSON.stringify(nodes.value)).toBe(before)
    expect(document.body.textContent).toMatch(/冻结|已开始|不能移动/)
  })

  it.each(['ASSIGNED', 'OPEN'] as const)(
    '新建与模板一级同级步骤继承总任务的%s负责人安排和优先级，不继承验收人',
    async assignmentMode => {
      const root = {
        ...newTaskNode(),
        id: 'root',
        title: '整件施工',
        assignmentMode,
        assigneeId: assignmentMode === 'ASSIGNED' ? '9007199254740993' : null,
        acceptorId: '9007199254740995',
        candidateUserIds: assignmentMode === 'OPEN' ? ['9007199254740993'] : [],
        urgency: 'URGENT' as const,
        priority: 'HIGH' as const
      }
      await mount({ root }, [])
      button('拆分子任务', host).click()
      await flush()
      const first = required(nodes.value[0])
      first.title = '准备'
      await flush()
      button('添加下一步', row(first.id)).click()
      await flush()
      const next = required(nodes.value[1])
      next.title = '施工'
      await flush()
      button('更多', row(next.id)).click()
      await flush()
      required(
        Array.from(document.querySelectorAll<HTMLElement>('[role="menuitem"]')).find(
          item => item.textContent?.trim() === '添加并行任务'
        ) || null
      ).click()
      await flush()
      required(row(first.id).querySelector<HTMLInputElement>('input.ant-input')).dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Enter', keyCode: 13, bubbles: true })
      )
      await flush()
      expect(nodes.value).toHaveLength(4)
      for (const node of nodes.value) {
        expect(node).toMatchObject({
          parentId: null,
          assignmentMode: assignmentMode === 'ASSIGNED' ? 'ASSIGNED' : 'FOLLOW_ROOT',
          assigneeId: root.assigneeId,
          acceptorId: null,
          candidateUserIds: [],
          urgency: 'URGENT',
          priority: 'HIGH'
        })
        expect(node.candidateUserIds).not.toBe(root.candidateUserIds)
      }
      expect(next.predecessorIds).toEqual([first.id])
      expect(nodes.value[2]?.predecessorIds).toEqual([first.id])
      expect(nodes.value.some(node => node.id === root.id)).toBe(false)
    }
  )

  it('深层节点和图上新增一次性预填总负责人，不复制另行指定的父负责人或联动覆盖已有节点', async () => {
    const root = reactive({
      ...newTaskNode(),
      id: 'root',
      title: '整件施工',
      assignmentMode: 'ASSIGNED' as const,
      assigneeId: '101'
    })
    const initial = fixture()
    initial.forEach(node => {
      node.assignmentMode = 'ASSIGNED'
      node.assigneeId = '202'
    })
    await mount({ root }, initial)
    button('拆分子任务', row('a11')).click()
    await flush()
    const child = required(nodes.value.find(node => node.parentId === 'a11'))
    expect(child).toMatchObject({ assigneeId: '101', assignmentMode: 'ASSIGNED' })
    child.title = '已手动分配'
    child.assigneeId = '303'
    child.assignmentMode = 'ASSIGNED'
    root.assigneeId = '404'
    await flush()
    expect(child.assigneeId).toBe('303')
    expect(nodes.value.find(node => node.id === 'a11')?.assigneeId).toBe('202')
    button('图上编排', host).click()
    await flush()
    button('图上拆分').click()
    await flush()
    expect(nodes.value.filter(node => node.parentId === 'a11').map(node => node.assigneeId)).toEqual(['303', '404'])
    const graphChild = required(nodes.value.filter(node => node.parentId === 'a11').at(-1))
    expect(document.querySelector('[data-selected]')?.getAttribute('data-selected')).toBe(graphChild.id)
    expect(document.querySelector('[data-configured]')).toBeNull()
    button('图上添加下一步').click()
    await flush()
    expect(nodes.value.find(node => node.predecessorIds.includes('b'))).toMatchObject({
      assigneeId: '404',
      assignmentMode: 'ASSIGNED'
    })
  })

  it('进入只读后即使关系图仍发出编辑意图也不改根、节点、前置或层级', async () => {
    const root = { ...newTaskNode(), id: 'root', title: '整件施工' }
    const props = reactive({ root, readonly: false })
    const initial = fixture()
    required(initial.find(node => node.id === 'b')).predecessorIds = ['a']
    await mount(props, initial)
    button('图上编排', host).click()
    await flush()
    const before = JSON.stringify({ root: props.root, nodes: nodes.value })
    props.readonly = true
    await flush()
    expect(document.querySelector('[data-graph-frozen]')?.textContent?.split(',')).toEqual([
      'root',
      'a',
      'a1',
      'a11',
      'b'
    ])
    expect(document.querySelector('[data-graph-closed]')?.textContent?.split(',')).toEqual([
      'root',
      'a',
      'a1',
      'a11',
      'b'
    ])
    for (const label of [
      '图上改总任务名',
      '图上改名',
      '图上拆分',
      '图上添加下一步',
      '图上添加并行任务',
      '连接 A 到 B',
      '移除 A 到 B',
      '在 A 到 B 之间插入',
      '图上配置',
      '图上移动 B',
      '图上移动总任务',
      '图上删除 A',
      '图上删除总任务'
    ])
      button(label).click()
    await flush()
    expect(JSON.stringify({ root: props.root, nodes: nodes.value })).toBe(before)
    expect(document.querySelector('[data-configured]')?.hasAttribute('readonly')).toBe(true)
    required(document.querySelector<HTMLButtonElement>('.ant-drawer-close')).click()
    await flush()
    expect(button('拆分子任务', host).disabled).toBe(true)
    expect(button('整理显示', host).disabled).toBe(true)
  })

  it('指定日期摘要显示开始截止而非零工期，暂不安排也不显示工期，相对规则仍显示工期', async () => {
    const fixed = {
      ...newTaskNode(),
      id: 'fixed',
      title: '日期任务',
      schedule: {
        mode: 'FIXED' as const,
        fixedStart: '2030-01-01T08:00:00',
        fixedEnd: '2030-01-03T17:00:00',
        durationDays: 0,
        offsetDays: 0
      }
    }
    const relative = {
      ...newTaskNode(),
      id: 'relative',
      title: '相对任务',
      schedule: { mode: 'PLAN_START' as const, fixedStart: null, durationDays: 3, offsetDays: 0 }
    }
    await mount({}, [fixed, { ...newTaskNode(), id: 'later', title: '稍后安排' }, relative])
    expect(row('fixed').textContent).toContain('2030-01-01 至 2030-01-03')
    expect(row('fixed').textContent).not.toMatch(/0\s*天/)
    expect(row('later').textContent).not.toMatch(/\d+\s*天/)
    expect(row('relative').textContent).toMatch(/3\s*天/)
  })
  it('新建总任务实时进入拆分表和关系图，根不进入子任务模型且不能移除', async () => {
    const root = ref({ ...newTaskNode(), id: 'root', title: '施工总任务' })
    await mount({ root: root.value }, [])
    expect(shownRows()).toEqual(['root'])
    expect(row('root').textContent).toContain('总任务')
    expect(row('root').textContent).toContain('在上方编辑总任务')
    expect(row('root').querySelector('input.ant-input')).toBeNull()
    expect(row('root').textContent).not.toContain('添加下一步')
    expect(row('root').textContent).not.toContain('并行分工')
    expect(Array.from(row('root').querySelectorAll('button')).some(item => item.textContent?.includes('移除'))).toBe(
      false
    )
    root.value.title = '总任务已改名'
    await flush()
    expect(row('root').textContent).toContain('总任务已改名')
    button('拆分子任务', host).click()
    await flush()
    expect(nodes.value).toHaveLength(1)
    expect(nodes.value[0]!.id).not.toBe('root')
    expect(nodes.value[0]!.parentId).toBeNull()
    expect(row(nodes.value[0]!.id).querySelector('.task-hierarchy')?.getAttribute('data-depth')).toBe('1')
    button('图上编排', host).click()
    await flush()
    expect(document.querySelector('[data-dag-root="root"]')).not.toBeNull()
  })
  it('下一步同级且依赖当前，随后并行分工保留相同前置、不依赖当前；不复制后代', async () => {
    await mount()
    button('添加下一步', row('a1')).click()
    await flush()
    const next = required(nodes.value.find(node => !['a', 'a1', 'a11', 'b'].includes(node.id)) || null)
    expect(next.parentId).toBe('a')
    expect(next.predecessorIds).toEqual(['a1'])
    expect(row(next.id).textContent).toContain('1.1 开挖')
    next.title = '垫层'
    await flush()
    button('更多', row(next.id)).click()
    await flush()
    required(
      Array.from(document.querySelectorAll<HTMLElement>('[role="menuitem"]')).find(
        item => item.textContent?.trim() === '添加并行任务'
      ) || null
    ).click()
    await flush()
    const parallel = required(nodes.value.find(node => !['a', 'a1', 'a11', 'b', next.id].includes(node.id)) || null)
    expect(parallel.parentId).toBe('a')
    expect(parallel.predecessorIds).toEqual(['a1'])
    expect(parallel.predecessorIds).not.toContain(next.id)
    expect(nodes.value).toHaveLength(6)
    expect(nodes.value.filter(node => node.parentId === parallel.id)).toHaveLength(0)
  })

  it('运行总任务只在根下添加步骤，已关闭上级不能再添加同级分工', async () => {
    await mount(
      { rootId: 'a', closedIds: ['a'] },
      fixture().filter(node => node.id !== 'b')
    )
    expect(row('a').textContent).not.toContain('添加下一步')
    expect(button('添加下一步', row('a1')).disabled).toBe(true)
    button('更多', row('a1')).click()
    await flush()
    expect(
      required(
        Array.from(document.querySelectorAll<HTMLElement>('[role="menuitem"]')).find(
          item => item.textContent?.trim() === '添加并行任务'
        ) || null
      ).getAttribute('aria-disabled')
    ).toBe('true')
  })
  it('已有多级草稿默认展开，名称输入属于各自编号、层级及上级提示，不向模型写入展示字段', async () => {
    const initial = fixture()
    const before = JSON.stringify(initial)
    await mount({}, initial)
    expect(shownRows()).toEqual(['a', 'a1', 'a11', 'b'])
    for (const [id, outline, depth] of [
      ['a', '1', '0'],
      ['a1', '1.1', '1'],
      ['a11', '1.1.1', '2'],
      ['b', '2', '0']
    ] as const) {
      const input = required(row(id).querySelector<HTMLInputElement>('input.ant-input'))
      expect(input.getAttribute('aria-label')).toContain(outline)
      expect(input.closest('.task-hierarchy')?.getAttribute('data-hierarchy')).toBeNull()
      expect(input.closest('.task-hierarchy')?.getAttribute('data-depth')).toBe(depth)
    }
    expect(row('a').textContent).toContain('一级子任务')
    expect(row('a1').textContent).toContain('1 级子任务')
    expect(row('a11').textContent).toContain('2 级子任务')
    expect(row('a11').querySelector('.task-hierarchy__parent')).toBeNull()
    expect(JSON.stringify(nodes.value)).toBe(before)
  })

  it('共用层级按钮控制原生树行，折叠不丢下级展开状态，全部展开/收起按真实父节点工作', async () => {
    await mount()
    required(row('a').querySelector<HTMLButtonElement>('[aria-label="收起：基础施工"]')).click()
    await flush()
    expect(shownRows()).toEqual(['a', 'b'])
    required(row('a').querySelector<HTMLButtonElement>('[aria-label="展开：基础施工"]')).click()
    await flush()
    expect(shownRows()).toEqual(['a', 'a1', 'a11', 'b'])
    button('收起全部', host).click()
    await flush()
    expect(shownRows()).toEqual(['a', 'b'])
    button('展开全部', host).click()
    await flush()
    expect(shownRows()).toEqual(['a', 'a1', 'a11', 'b'])
  })

  it('收起父任务后添加子任务会定位输入，Enter添加同级，Esc仅移除空白新增', async () => {
    await mount()
    button('收起全部', host).click()
    await flush()
    button('拆分子任务', row('a')).click()
    await flush()
    const added = required(nodes.value.find(node => !['a', 'a1', 'a11', 'b'].includes(node.id)) || null)
    expect(added.parentId).toBe('a')
    const input = required(row(added.id).querySelector<HTMLInputElement>('input.ant-input'))
    expect(document.activeElement).toBe(input)
    expect(input.closest('.task-hierarchy')?.getAttribute('data-depth')).toBe('1')
    input.value = '垫层施工'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', keyCode: 13, bubbles: true }))
    await flush()
    const sibling = required(
      nodes.value.find(node => node.id !== added.id && !['a', 'a1', 'a11', 'b'].includes(node.id)) || null
    )
    expect(sibling.parentId).toBe('a')
    const siblingInput = required(row(sibling.id).querySelector<HTMLInputElement>('input.ant-input'))
    expect(document.activeElement).toBe(siblingInput)
    expect(siblingInput.closest('.task-hierarchy')?.getAttribute('data-depth')).toBe('1')
    siblingInput.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flush()
    expect(nodes.value.some(node => node.id === sibling.id)).toBe(false)
    expect(nodes.value.find(node => node.id === added.id)?.title).toBe('垫层施工')
  })

  it('运行实例的真实根显示总任务，冻结/关闭限制保留，配置与关系图收到同一根上下文', async () => {
    await mount(
      { rootId: 'a', frozenIds: ['a'], closedIds: ['a'] },
      fixture().filter(node => node.id !== 'b')
    )
    expect(row('a').textContent).toContain('总任务')
    expect(row('a').textContent).toContain('配置已冻结')
    expect(row('a').querySelector('input.ant-input')).toBeNull()
    expect(button('拆分子任务', row('a')).disabled).toBe(true)
    button('查看', row('a')).click()
    await flush()
    expect(document.querySelector('[data-configured="a"]')?.hasAttribute('readonly')).toBe(true)
    button('配置', row('a11')).click()
    await flush()
    expect(document.querySelector('.task-node-editor__location')?.textContent).toContain('子任务 · 测量放线')
    expect(document.querySelector('[aria-label="所属位置"]')?.textContent).toContain('基础施工 / 开挖')
    expect(document.querySelector('[data-configured="a11"]')?.getAttribute('data-root')).toBe('a')
    button('图上编排', host).click()
    await flush()
    expect(document.querySelector('[data-dag-draft="false"]')?.getAttribute('data-dag-root')).toBe('a')
  })

  it('异步回填草稿默认展开，后续改名不覆盖用户收起选择', async () => {
    await mount({}, [])
    nodes.value = fixture()
    await flush()
    expect(shownRows()).toEqual(['a', 'a1', 'a11', 'b'])
    button('收起全部', host).click()
    await flush()
    nodes.value = nodes.value.map(node => ({ ...node, title: `${node.title}已更新` }))
    await flush()
    expect(shownRows()).toEqual(['a', 'b'])
    button('图上编排', host).click()
    await flush()
    expect(document.querySelector('[data-dag-draft="true"]')).not.toBeNull()
  })

  it('列表批量创建连续步骤，同级顺序与父节点分开保存', async () => {
    const root = { ...newTaskNode(), id: 'root', title: '整件施工' }
    await mount({ root }, [])
    button('添加连续步骤', host).click()
    await flush()
    const input = required(document.querySelector<HTMLTextAreaElement>('[aria-label="批量任务名称"]'))
    input.value = '准备\n施工\n验收'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    button('添加到编排').click()
    await flush()
    expect(nodes.value.map(node => node.title)).toEqual(['准备', '施工', '验收'])
    expect(nodes.value.map(node => node.parentId)).toEqual([null, null, null])
    expect(nodes.value[1]?.predecessorIds).toEqual([nodes.value[0]?.id])
    expect(nodes.value[2]?.predecessorIds).toEqual([nodes.value[1]?.id])
    expect(nodes.value.some(node => node.id === 'root')).toBe(false)
  })

  it('图上连线、改名、递归拆分与列表实时同步；反向死锁不写回', async () => {
    await mount()
    button('图上编排', host).click()
    await flush()
    button('连接 A 到 B').click()
    await flush()
    expect(nodes.value.find(node => node.id === 'b')?.predecessorIds).toEqual(['a'])
    button('连接 B 到 A').click()
    await flush()
    expect(document.body.textContent).toContain('循环或父子等待死锁')
    expect(nodes.value.find(node => node.id === 'a')?.predecessorIds).toEqual([])
    button('图上改名').click()
    button('图上拆分').click()
    await flush()
    expect(nodes.value.at(-1)?.parentId).toBe('a11')
    button('移除 A 到 B').click()
    await flush()
    expect(nodes.value.find(node => node.id === 'b')?.predecessorIds).toEqual([])
    required(document.querySelector<HTMLButtonElement>('.ant-drawer-close')).click()
    await flush()
    expect(required(row('b').querySelector<HTMLInputElement>('input.ant-input')).value).toBe('设备安装已改名')
  })

  it('列表顺序是只读摘要，不再提供勾选弹窗；已有跨层前置不因改名丢失', async () => {
    const initial = fixture()
    required(initial.find(node => node.id === 'b')).predecessorIds = ['a11']
    await mount({}, initial)
    expect(row('b').textContent).toContain('等待')
    expect(row('b').textContent).toContain('1.1.1 测量放线')
    expect(row('b').querySelector('.task-node-editor__dependencies button')).toBeNull()
    button('更多', row('b')).click()
    await flush()
    expect(document.body.textContent).not.toContain('设置先后')
    expect(document.querySelector('.task-dependency-picker')).toBeNull()
    const input = required(row('b').querySelector<HTMLInputElement>('input.ant-input'))
    input.value = '设备安装已调整'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    expect(nodes.value.find(node => node.id === 'b')?.predecessorIds).toEqual(['a11'])
  })

  it('添加下一步插入当前与全部直接后继之间，保留后继的其他前置与原有层级', async () => {
    const root = { ...newTaskNode(), id: 'root', title: '整件施工' }
    const initial = ['a', 'b', 'c', 'd'].map(id => ({ ...newTaskNode(), id, title: id }))
    required(initial.find(node => node.id === 'b')).predecessorIds = ['a', 'd']
    required(initial.find(node => node.id === 'c')).predecessorIds = ['a']
    await mount({ root }, initial)
    button('添加下一步', row('a')).click()
    await flush()
    const added = required(nodes.value.find(node => !initial.some(item => item.id === node.id)))
    expect(added).toMatchObject({ parentId: null, predecessorIds: ['a'] })
    expect(nodes.value.find(node => node.id === 'b')?.predecessorIds).toEqual([added.id, 'd'])
    expect(nodes.value.find(node => node.id === 'c')?.predecessorIds).toEqual([added.id])
    expect(nodes.value.find(node => node.id === 'a')?.predecessorIds).toEqual([])
    expect(nodes.value.every(node => node.parentId === null)).toBe(true)
    expect(nodes.value.some(node => node.id === root.id)).toBe(false)
  })

  it('添加并行任务共享当前前置，原直接后继等待两分支汇合，不复制当前后代', async () => {
    const root = { ...newTaskNode(), id: 'root', title: '整件施工' }
    const initial = ['start', 'a', 'b', 'c'].map(id => ({ ...newTaskNode(), id, title: id }))
    required(initial.find(node => node.id === 'a')).predecessorIds = ['start']
    required(initial.find(node => node.id === 'b')).predecessorIds = ['a']
    required(initial.find(node => node.id === 'c')).predecessorIds = ['a', 'start']
    initial.push({ ...newTaskNode('a'), id: 'a1', title: '子任务' })
    await mount({ root }, initial)
    button('更多', row('a')).click()
    await flush()
    required(
      Array.from(document.querySelectorAll<HTMLElement>('[role="menuitem"]')).find(
        item => item.textContent?.trim() === '添加并行任务'
      )
    ).click()
    await flush()
    const added = required(nodes.value.find(node => !initial.some(item => item.id === node.id)))
    expect(added).toMatchObject({ parentId: null, predecessorIds: ['start'] })
    expect(nodes.value.find(node => node.id === 'b')?.predecessorIds).toEqual(['a', added.id])
    expect(nodes.value.find(node => node.id === 'c')?.predecessorIds).toEqual(['a', 'start', added.id])
    expect(nodes.value.filter(node => node.parentId === added.id)).toHaveLength(0)
    expect(nodes.value.find(node => node.id === 'a1')?.parentId).toBe('a')
  })

  it('图上选中真实边插入只改选中后继，不改变当前节点的其他分支', async () => {
    const root = { ...newTaskNode(), id: 'root', title: '整件施工' }
    const initial = ['a', 'b', 'c'].map(id => ({
      ...newTaskNode(),
      id,
      title: id,
      predecessorIds: id === 'a' ? [] : ['a']
    }))
    await mount({ root }, initial)
    button('图上编排', host).click()
    await flush()
    button('在 A 到 B 之间插入').click()
    await flush()
    const added = required(nodes.value.find(node => !initial.some(item => item.id === node.id)))
    expect(added.predecessorIds).toEqual(['a'])
    expect(nodes.value.find(node => node.id === 'b')?.predecessorIds).toEqual([added.id])
    expect(nodes.value.find(node => node.id === 'c')?.predecessorIds).toEqual(['a'])
  })

  it.each(['下一步', '并行任务'] as const)('取消未命名的%s新增时恢复原直接后继，不残留节点或前置边', async action => {
    const root = { ...newTaskNode(), id: 'root', title: '整件施工' }
    const initial = ['a', 'b'].map(id => ({ ...newTaskNode(), id, title: id, predecessorIds: id === 'a' ? [] : ['a'] }))
    await mount({ root }, initial)
    const before = JSON.stringify(nodes.value)
    if (action === '下一步') button('添加下一步', row('a')).click()
    else {
      button('更多', row('a')).click()
      await flush()
      required(
        Array.from(document.querySelectorAll<HTMLElement>('[role="menuitem"]')).find(
          item => item.textContent?.trim() === '添加并行任务'
        )
      ).click()
    }
    await flush()
    const added = required(nodes.value.find(node => !initial.some(item => item.id === node.id)))
    expect(added.title).toBe('')
    expect(nodes.value.find(node => node.id === 'b')?.predecessorIds).toEqual(
      action === '下一步' ? [added.id] : ['a', added.id]
    )
    button('取消新增', row(added.id)).click()
    await flush()
    expect(JSON.stringify(nodes.value)).toBe(before)
    expect(nodes.value.find(node => node.id === 'b')?.predecessorIds).toEqual(['a'])
    expect(host.querySelector(`tr[data-row-key="${added.id}"]`)).toBeNull()
  })

  it.each([
    ['下一步', 'frozenIds'],
    ['下一步', 'closedIds'],
    ['并行任务', 'frozenIds'],
    ['并行任务', 'closedIds']
  ] as const)('添加%s时任一待改后继被%s锁定，整次修改原子拒绝', async (action, restriction) => {
    const root = { ...newTaskNode(), id: 'root', title: '整件施工' }
    const initial = ['a', 'b', 'c'].map(id => ({
      ...newTaskNode(),
      id,
      title: id,
      predecessorIds: id === 'a' ? [] : ['a']
    }))
    await mount({ root, [restriction]: ['c'] }, initial)
    const before = JSON.stringify(nodes.value)
    if (action === '下一步') button('添加下一步', row('a')).click()
    else {
      button('更多', row('a')).click()
      await flush()
      required(
        Array.from(document.querySelectorAll<HTMLElement>('[role="menuitem"]')).find(
          item => item.textContent?.trim() === '添加并行任务'
        )
      ).click()
    }
    await flush()
    expect(JSON.stringify(nodes.value)).toBe(before)
    expect(document.body.textContent).toMatch(/后续步骤|冻结/)
  })

  it('普通拆分与名称回车仅添加层级，不自动形成前置边或改动已有顺序', async () => {
    const root = { ...newTaskNode(), id: 'root', title: '整件施工' }
    const initial = ['a', 'b'].map(id => ({ ...newTaskNode(), id, title: id, predecessorIds: id === 'a' ? [] : ['a'] }))
    await mount({ root }, initial)
    button('拆分子任务', row('a')).click()
    await flush()
    const child = required(nodes.value.find(node => node.parentId === 'a'))
    expect(child.predecessorIds).toEqual([])
    const input = required(row(child.id).querySelector<HTMLInputElement>('input.ant-input'))
    input.value = '内部工作'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', keyCode: 13, bubbles: true }))
    await flush()
    expect(nodes.value.filter(node => node.parentId === 'a')).toHaveLength(2)
    expect(nodes.value.filter(node => node.parentId === 'a').every(node => node.predecessorIds.length === 0)).toBe(true)
    expect(nodes.value.find(node => node.id === 'b')?.predecessorIds).toEqual(['a'])
  })
})

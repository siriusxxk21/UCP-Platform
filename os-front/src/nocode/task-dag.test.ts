// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, type App } from 'vue'
import TaskDag from '@/views/nocode/task-center/TaskDag.vue'
import { newTaskNode } from './task-center'
import type { TaskNodeInput } from '@/types/nocode/task-center'

const node = (id: string, parentId: string | null = 'root', predecessorIds: string[] = []): TaskNodeInput => ({
  ...newTaskNode(parentId),
  id,
  title: id === 'root' ? '完成现场施工' : id,
  predecessorIds
})
let app: App | undefined, host: HTMLDivElement
async function flush() {
  for (let index = 0; index < 6; index++) await nextTick()
}
async function mount(overrides: Record<string, unknown> = {}) {
  const props = reactive({
    nodes: [node('root', null), node('A'), node('B', 'root', ['A'])],
    rootId: 'root',
    currentId: 'A',
    editable: true,
    interactive: false,
    frozenIds: [] as string[],
    closedIds: [] as string[],
    ...overrides
  })
  app = createApp(() => h(TaskDag, props))
  app.component(
    'AButton',
    defineComponent({
      props: ['disabled'],
      emits: ['click'],
      setup:
        (props, { slots, emit }) =>
        () =>
          h(
            'button',
            { type: 'button', disabled: props.disabled, onClick: (event: MouseEvent) => emit('click', event) },
            slots.default?.()
          )
    })
  )
  for (const name of ['ADropdown', 'AMenu', 'ATooltip'])
    app.component(
      name,
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h('div', [slots.default?.(), slots.overlay?.()])
      })
    )
  app.component(
    'AMenuItem',
    defineComponent({
      props: ['disabled'],
      setup:
        (props, { slots }) =>
        () =>
          h('button', { disabled: props.disabled }, slots.default?.())
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  return props
}
function element<T extends Element = HTMLElement>(selector: string): T {
  const found = host.querySelector<T>(selector)
  if (!found) throw new Error(`未呈现元素：${selector}`)
  return found
}
function required<T>(value: T | null | undefined): T {
  if (value == null) throw new Error('预期图形数据不存在')
  return value
}
function button(title: string): HTMLButtonElement {
  const found = Array.from(host.querySelectorAll('button')).find(
    button => button.textContent?.trim() === title || button.getAttribute('aria-label') === title
  )
  if (!found) throw new Error(`未呈现按钮：${title}`)
  return found
}
function click(selector: string) {
  element(selector).dispatchEvent(new MouseEvent('click', { bubbles: true }))
}
function pointer(selector: string, type: string, clientX = 100, clientY = 100) {
  element(selector).dispatchEvent(new MouseEvent(type, { bubbles: true, button: 0, clientX, clientY }))
}
afterEach(() => {
  app?.unmount()
  host?.remove()
})

describe('可操作任务编排图', () => {
  it('自动排期图例与卡片解释任务顺序及下级汇总，不冒用旧前序实际完成规则', async () => {
    const root = node('root', null)
    const child = node('A')
    root.schedule.mode = 'AUTO'
    child.schedule.mode = 'AUTO'
    await mount({ context: 'template', nodes: [root, child] })
    const legend = element('[aria-label="时间规则图例"]')
    expect(legend.textContent).toContain('跟随任务顺序：按整体计划或前序最晚完成日期排期')
    expect(legend.textContent).not.toContain('或前序实际完成日排期')
    expect(element('[data-task-id="root"]').textContent).toContain('下级日期自动汇总')
    expect(element('[data-task-id="A"]').textContent).toContain('工期 1 天')
  })
  it('编排操作统一为左侧拆分与下一步图标、竖向菜单，并保留批量操作和属性编辑', async () => {
    const batchSplit = vi.fn(),
      continuousChildren = vi.fn()
    const props = await mount({ onBatchSplit: batchSplit, onContinuousChildren: continuousChildren })
    expect(button('拆分子任务').querySelector('[data-icon="plus"]')).not.toBeNull()
    expect(button('添加下一步').querySelector('[data-icon="enter"]')).not.toBeNull()
    expect(button('图上更多操作').querySelector('[data-icon="more"]')).not.toBeNull()
    expect(button('图上更多操作').textContent?.trim()).toBe('')
    expect(element('[data-task-id="A"] .task-dag__hierarchy').textContent?.trim()).toBe('子任务')
    button('批量拆分').click()
    button('添加连续下级').click()
    expect(batchSplit).toHaveBeenCalledWith('A')
    expect(continuousChildren).toHaveBeenCalledWith('A')
    expect(button('配置任务')).toBeDefined()
    props.closedIds = ['A']
    await flush()
    expect(button('批量拆分').disabled).toBe(true)
    expect(button('添加连续下级').disabled).toBe(true)
  })
  it('详情点击先请求跳转，成功切换当前节点后保持缩放和平移', async () => {
    const select = vi.fn()
    const props = await mount({ editable: false, interactive: true, preserveView: true, onSelect: select })
    button('＋').click()
    await flush()
    const transform = element('[data-scene]').getAttribute('transform')
    click('[data-task-id="B"]')
    await flush()
    expect(select).toHaveBeenCalledWith('B')
    expect(element('[data-task-id="A"]').getAttribute('aria-current')).toBe('true')
    props.currentId = 'B'
    props.nodes = [...props.nodes]
    await flush()
    expect(element('[data-task-id="B"]').textContent).toContain('当前查看')
    expect(element('[data-scene]').getAttribute('transform')).toBe(transform)
    button('定位当前任务').click()
    await flush()
    expect(element('[data-scene]').getAttribute('transform')).not.toBe(transform)
  })
  it('跟随来源在模板未承接和实例已承接图卡中只显示一次', async () => {
    const props = await mount({ context: 'template', nodes: [node('root', null), node('A')] })
    const card = element('[data-task-id="A"]')
    expect(card.textContent).toContain('模板配置 · 负责人：随总任务负责人（待承接）')
    expect(card.textContent).not.toContain('（待承接）（待承接）')
    props.nodes[1] = { ...node('A'), assigneeId: '9007199254740993' }
    Object.assign(props, {
      context: 'instance',
      runtimeNodes: [{ id: 'A', status: 'RUNNING', assigneeId: '9007199254740993', assigneeName: '李工' }]
    })
    await flush()
    expect(card.textContent).toContain('进行中 · 负责人：随总任务负责人 · 李工')
    expect(card.textContent).not.toContain('待承接')
  })
  it('模板图卡显示负责人和相对时间，不使用实例状态或待加入任务池文案', async () => {
    await mount({
      context: 'template',
      members: [{ id: '9007199254740993', name: '张工' }],
      nodes: [
        node('root', null),
        {
          ...node('A'),
          assignmentMode: 'ASSIGNED',
          assigneeId: '9007199254740993',
          schedule: { mode: 'PLAN_START', fixedStart: null, offsetDays: 2, durationDays: 3 }
        }
      ]
    })
    const card = element('[data-task-id="A"]')
    expect(card.textContent).toContain('模板配置')
    expect(card.textContent).toContain('负责人：张工')
    expect(card.textContent).toContain('整体计划开始后 2 天 · 预计用时 3 天')
    expect(card.textContent).not.toContain('待加入任务池')
    expect(host.textContent).toContain('按整体计划开始日或前序实际完成日排期')
    expect(host.textContent).not.toContain('T0=')
  })
  it('相对时间图例用人话区分基准，前序模式也可见，历史规则不改作整体起点', async () => {
    const props = await mount({
      context: 'template',
      nodes: [
        node('root', null),
        node('A'),
        {
          ...node('B'),
          predecessorIds: ['A'],
          schedule: { mode: 'PREDECESSOR', fixedStart: null, offsetDays: 0, durationDays: 1 }
        }
      ]
    })
    const legend = element('[aria-label="时间规则图例"]')
    expect(legend.textContent).toContain('按整体计划开始日或前序实际完成日排期')
    expect(legend.textContent).toContain('日期以任务计算结果为准；起点未定时不代用今天')
    props.nodes[2] = {
      ...props.nodes[2],
      schedule: { mode: 'T0', fixedStart: null, offsetDays: 0, durationDays: 1 }
    }
    await flush()
    expect(legend.textContent).toContain('历史创建时间规则仍以任务创建日为起点')
    expect(legend.textContent).not.toContain('整体计划开始日')
    expect(legend.textContent).not.toContain('T0')
  })
  it('实例图合并真实状态预计日期，新增节点仍是草稿且不伪造日期', async () => {
    await mount({
      context: 'instance',
      runtimeNodes: [
        {
          id: 'A',
          status: 'RUNNING',
          assigneeId: '7',
          assigneeName: '李工',
          expectedStart: '2030-10-04T08:00:00',
          expectedEnd: '2030-10-06T18:00:00'
        }
      ],
      nodes: [node('root', null), { ...node('A'), assignmentMode: 'ASSIGNED', assigneeId: '7' }, node('new')]
    })
    expect(element('[data-task-id="A"]').textContent).toContain('进行中')
    expect(element('[data-task-id="A"]').textContent).toContain('2030-10-04 至 2030-10-06')
    expect(element('[data-task-id="new"]').textContent).toContain('草稿（新增）')
    expect(element('[data-task-id="new"]').textContent).toContain('暂不安排')
  })
  it('图上删除只发送选中节点意图；总任务与只读图不显示删除', async () => {
    const remove = vi.fn()
    const props = await mount({ onRemove: remove })
    const before = JSON.stringify(props.nodes)
    button('删除任务').click()
    expect(remove).toHaveBeenCalledExactlyOnceWith('A')
    expect(JSON.stringify(props.nodes)).toBe(before)
    props.currentId = 'root'
    await flush()
    expect(host.textContent).not.toContain('删除任务')
    props.currentId = 'A'
    props.editable = false
    await flush()
    expect(host.textContent).not.toContain('删除任务')
  })
  it.each(['frozenIds', 'closedIds'] as const)('图上%s节点禁用删除入口', async restriction => {
    const remove = vi.fn()
    await mount({ onRemove: remove, [restriction]: ['A'] })
    expect(button('删除任务').disabled).toBe(true)
    button('删除任务').click()
    expect(remove).not.toHaveBeenCalled()
  })
  it('更多移动归属只发送当前节点意图，总任务和只读图不提供入口', async () => {
    const move = vi.fn()
    const props = await mount({ onMove: move })
    const before = JSON.stringify(props.nodes)
    button('移动到其他任务下').click()
    expect(move).toHaveBeenCalledExactlyOnceWith('A')
    expect(JSON.stringify(props.nodes)).toBe(before)
    props.currentId = 'root'
    await flush()
    expect(host.textContent).not.toContain('移动到其他任务下')
    props.currentId = 'A'
    props.editable = false
    await flush()
    expect(host.textContent).not.toContain('移动到其他任务下')
    expect(move).toHaveBeenCalledTimes(1)
  })
  it('冻结节点的移动归属入口禁用，点击不会发送移动意图', async () => {
    const move = vi.fn()
    await mount({ onMove: move, frozenIds: ['A'] })
    expect(button('移动到其他任务下').disabled).toBe(true)
    button('移动到其他任务下').click()
    expect(move).not.toHaveBeenCalled()
  })
  it('总任务保留独立标题、拆分和配置，但不是流程步骤也没有连线端口', async () => {
    const addChild = vi.fn(),
      configure = vi.fn()
    await mount({ currentId: 'root', onAddChild: addChild, onConfigure: configure })
    expect(element('[data-task-id="root"]').textContent).toContain('完成现场施工')
    expect(host.querySelector('[data-task-id="root"] [data-port]')).toBeNull()
    expect(host.textContent).not.toContain('添加下一步')
    expect(host.textContent).not.toContain('添加并行任务')
    button('拆分子任务').click()
    button('配置任务').click()
    expect(addChild).toHaveBeenCalledWith('root')
    expect(configure).toHaveBeenCalledWith('root')
    button('改名').click()
    await flush()
    expect(element<HTMLInputElement>('input[aria-label="任务名称"]').value).toBe('完成现场施工')
  })

  it('图上新增、配置与改名只发意图，不独立改写节点数据', async () => {
    const addChild = vi.fn(),
      addNext = vi.fn(),
      addParallel = vi.fn(),
      configure = vi.fn(),
      rename = vi.fn()
    const props = await mount({
      onAddChild: addChild,
      onAddNext: addNext,
      onAddParallel: addParallel,
      onConfigure: configure,
      onRename: rename
    })
    const before = JSON.stringify(props.nodes)
    button('拆分子任务').click()
    button('添加下一步').click()
    button('添加并行任务').click()
    button('配置任务').click()
    for (const callback of [addChild, addNext, addParallel, configure]) expect(callback).toHaveBeenCalledWith('A')
    button('改名').click()
    await flush()
    const input = element<HTMLInputElement>('input[aria-label="任务名称"]')
    expect(input.maxLength).toBe(160)
    input.value = '  A 准备施工  '
    input.dispatchEvent(new Event('input', { bubbles: true }))
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    expect(rename).toHaveBeenCalledWith('A', 'A 准备施工')
    expect(JSON.stringify(props.nodes)).toBe(before)
    expect(host.querySelector('form')).toBeNull()
  })

  it('点选起终点连线，不能连总任务、自身或锁定目标', async () => {
    const link = vi.fn()
    const props = await mount({ onLink: link, frozenIds: ['B'] })
    button('连接后续任务').click()
    await flush()
    click('[data-task-id="root"]')
    click('[data-task-id="A"]')
    click('[data-task-id="B"]')
    expect(link).not.toHaveBeenCalled()
    props.frozenIds = []
    await flush()
    click('[data-task-id="B"]')
    expect(link).toHaveBeenCalledExactlyOnceWith('A', 'B')
    expect(required(props.nodes.find(node => node.id === 'B')).predecessorIds).toEqual(['A'])
  })

  it('拖拽端口可连接目标，取消拖拽不会创建依赖', async () => {
    const link = vi.fn()
    await mount({ onLink: link })
    pointer('[data-task-id="A"] [data-port="output"]', 'pointerdown')
    pointer('svg', 'pointermove', 300, 200)
    await flush()
    expect(host.querySelector('.task-dag__edge--preview')).not.toBeNull()
    pointer('[data-task-id="B"]', 'pointerup', 300, 200)
    expect(link).toHaveBeenCalledExactlyOnceWith('A', 'B')
    pointer('[data-task-id="A"] [data-port="output"]', 'pointerdown')
    pointer('[data-task-id="B"]', 'pointercancel')
    expect(link).toHaveBeenCalledTimes(1)
    await flush()
    expect(host.querySelector('[role="status"]')).toBeNull()
  })

  it('选择依赖再断开，折叠时仍发实际端点而非分组节点', async () => {
    const unlink = vi.fn()
    await mount({
      nodes: [node('root', null), node('A'), node('A1', 'A'), node('B', 'root', ['A1'])],
      onUnlink: unlink
    })
    click('[aria-label="收起：A"]')
    await flush()
    expect(host.querySelector('[data-task-id="A1"]')).toBeNull()
    expect(element('path[data-relation]').getAttribute('data-projected')).toBe('true')
    click('.task-dag__edge-hit')
    await flush()
    button('断开依赖').click()
    expect(unlink).toHaveBeenCalledExactlyOnceWith('A1', 'B')
  })

  it('锁定节点只能查看配置，不能改名或改前置；关闭父级不能添加同级步骤', async () => {
    const unlink = vi.fn(),
      rename = vi.fn(),
      configure = vi.fn()
    await mount({
      currentId: 'B',
      frozenIds: ['B'],
      closedIds: ['root', 'B'],
      onUnlink: unlink,
      onRename: rename,
      onConfigure: configure
    })
    for (const title of ['改名', '拆分子任务', '添加下一步', '添加并行任务']) expect(button(title).disabled).toBe(true)
    expect(button('查看配置').disabled).toBe(false)
    button('查看配置').click()
    button('改名').click()
    expect(configure).toHaveBeenCalledWith('B')
    expect(rename).not.toHaveBeenCalled()
    click('.task-dag__edge-hit')
    await flush()
    expect(button('断开依赖').disabled).toBe(true)
    button('断开依赖').click()
    expect(unlink).not.toHaveBeenCalled()
  })

  it('父组件新增并选中深层任务时自动展开祖先并立即可改名', async () => {
    const props = await mount()
    click('[aria-label="收起：完成现场施工"]')
    await flush()
    expect(host.querySelector('[data-task-id="A"]')).toBeNull()
    props.nodes = [...props.nodes, node('new-child', 'A')]
    props.currentId = 'new-child'
    await flush()
    expect(element('[data-task-id="new-child"]').getAttribute('aria-current')).toBe('true')
    expect(element('.task-dag__selected-title').textContent).toBe('new-child')
    button('改名').click()
    await flush()
    expect(element<HTMLInputElement>('input').value).toBe('new-child')
  })

  it('增加或断开依赖后自动适配，单纯改名不覆盖用户缩放和平移', async () => {
    const nodes = [
      node('root', null),
      ...Array.from({ length: 6 }, (_, index) => node(`step${index}`, 'root', index ? [`step${index - 1}`] : []))
    ]
    const props = await mount({ nodes, currentId: 'step0' })
    click('[aria-label="放大关系图"]')
    pointer('svg', 'pointerdown', 100, 100)
    pointer('svg', 'pointermove', 170, 180)
    pointer('svg', 'pointerup', 170, 180)
    await flush()
    const manualTransform = element('[data-scene]').getAttribute('transform')
    props.nodes = props.nodes.map(node => ({ ...node, title: `${node.title} 新名称` }))
    await flush()
    expect(element('[data-scene]').getAttribute('transform')).toBe(manualTransform)
    props.nodes = props.nodes.map(node => ({ ...node, predecessorIds: [] }))
    await flush()
    const transform = required(element('[data-scene]').getAttribute('transform'))
    expect(transform).not.toBe(manualTransform)
    const zoom = Number(required(transform.match(/scale\(([^)]+)\)/))[1])
    const sceneHeight = Number(element('.task-dag__background').getAttribute('height'))
    expect(sceneHeight * zoom + 16).toBeLessThanOrEqual(520)
    props.nodes = props.nodes.map((node, index) => ({
      ...node,
      predecessorIds: index > 1 ? [required(props.nodes[index - 1]).id] : []
    }))
    await flush()
    expect(element('[data-scene]').getAttribute('transform')).not.toBe(transform)
  })

  it('只读预览保留缩放折叠，不暴露编辑意图或节点选择', async () => {
    const select = vi.fn(),
      link = vi.fn()
    await mount({ editable: false, interactive: false, onSelect: select, onLink: link })
    expect(host.querySelector('.task-dag__actions')).toBeNull()
    expect(host.querySelector('[data-port]')).toBeNull()
    expect(host.querySelector('.task-dag__edge-hit')).toBeNull()
    click('[data-task-id="A"]')
    expect(select).not.toHaveBeenCalled()
    expect(link).not.toHaveBeenCalled()
    const before = element('[data-scene]').getAttribute('transform')
    click('[aria-label="放大关系图"]')
    await flush()
    expect(element('[data-scene]').getAttribute('transform')).not.toBe(before)
  })

  it('唯一开始结束只是展示边界，不参与选择、连线或任务数', async () => {
    const select = vi.fn(),
      link = vi.fn(),
      unlink = vi.fn(),
      insert = vi.fn()
    const props = await mount({ onSelect: select, onLink: link, onUnlink: unlink, onInsert: insert })
    expect(host.querySelectorAll('[data-boundary]')).toHaveLength(2)
    expect(host.querySelectorAll('[data-task-id]')).toHaveLength(props.nodes.length)
    for (const boundary of Array.from(host.querySelectorAll('[data-boundary]'))) {
      expect(boundary.hasAttribute('data-task-id')).toBe(false)
      expect(boundary.hasAttribute('tabindex')).toBe(false)
      expect(boundary.querySelector('[data-port]')).toBeNull()
      boundary.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    }
    click('[data-boundary-edge="START"]')
    await flush()
    for (const callback of [select, link, unlink, insert]) expect(callback).not.toHaveBeenCalled()
    expect(host.querySelectorAll('.task-dag__edge-hit')).toHaveLength(1)
    expect(host.textContent).toContain('开始、结束只是图示边界，不是实际任务')
  })

  it('未拆分根仍显示大框和拆分子任务入口，只读不开放新增', async () => {
    const addChild = vi.fn()
    const props = await mount({ nodes: [node('root', null)], currentId: 'root', onAddChild: addChild })
    expect(host.querySelectorAll('[data-container-id="root"]')).toHaveLength(1)
    expect(host.querySelectorAll('[data-boundary]')).toHaveLength(2)
    expect(element('.task-dag__empty').textContent).toContain('尚未拆分子任务')
    element<HTMLButtonElement>('.task-dag__empty button').click()
    expect(addChild).toHaveBeenCalledExactlyOnceWith('root')
    props.editable = false
    await flush()
    expect(host.querySelector('.task-dag__empty button')).toBeNull()
    expect(host.querySelectorAll('[data-boundary]')).toHaveLength(2)
  })

  it('选中真实边能局部插入，锁定目标或关闭源父级时禁止', async () => {
    const insert = vi.fn()
    const props = await mount({ onInsert: insert })
    click('.task-dag__edge-hit')
    await flush()
    button('插入步骤').click()
    expect(insert).toHaveBeenCalledExactlyOnceWith('A', 'B')
    props.frozenIds = ['B']
    click('.task-dag__edge-hit')
    await flush()
    expect(button('插入步骤').disabled).toBe(true)
    props.frozenIds = []
    props.closedIds = ['root']
    await flush()
    expect(button('插入步骤').disabled).toBe(true)
    button('插入步骤').click()
    expect(insert).toHaveBeenCalledTimes(1)
  })
})

// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import TaskClaimableGroups from '@/views/nocode/task-center/TaskClaimableGroups.vue'
import type { TaskClaimableGroup, TaskClaimableItem } from '@/types/nocode/task-center'

const api = vi.hoisted(() => ({ claimableGroups: vi.fn(), claimableChildren: vi.fn(), detail: vi.fn() }))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ taskCenter: api }) }))
vi.mock('@/views/nocode/task-center/TaskClaimDialog.vue', () => ({
  default: defineComponent({
    props: ['task'],
    emits: ['saved'],
    setup:
      (props, { emit }) =>
      () =>
        h('aside', { 'data-claim': props.task.id }, [
          props.task.title,
          h('button', { onClick: () => emit('saved') }, '模拟领取成功')
        ])
  })
}))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['dataSource', 'columns', 'pagination', 'scroll'],
    emits: ['change'],
    setup:
      (props, { slots, emit }) =>
      () =>
        h('section', [
          slots.search?.(),
          slots.actions?.(),
          h(
            'header',
            {
              'data-column-widths': props.columns.map((column: { width: number }) => column.width).join(','),
              'data-scroll-x': props.scroll.x
            },
            props.columns.map((column: { title: string }) => column.title).join(' / ')
          ),
          h('span', { 'data-total': '' }, String(props.pagination.total)),
          h('button', { onClick: () => emit('change', { current: 2, pageSize: 10 }) }, '下一页'),
          ...props.dataSource.map((record: { key: string }) =>
            h(
              'div',
              { 'data-key': record.key },
              props.columns.map((column: { key: string }) =>
                h('div', { 'data-column': column.key }, slots.bodyCell?.({ column, record }))
              )
            )
          )
        ])
  })
}))
let app: App, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 15; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function button(label: string, scope: Element = host) {
  const found = Array.from(scope.querySelectorAll('button')).find(
    item => item.textContent?.trim() === label || item.getAttribute('aria-label') === label
  )
  if (!found) throw new Error(`缺少${label}`)
  return found
}
function required<T>(value: T | null): T {
  if (value === null) throw new Error('缺少测试目标')
  return value
}
const item = (id: string, parentId: string | null): TaskClaimableItem => ({
  id,
  rootId: 'root',
  parentId,
  title: id,
  status: 'PENDING',
  assigneeId: null,
  assignmentMode: 'OPEN',
  revision: 1,
  urgency: 'NORMAL',
  priority: 'MEDIUM',
  expectedStart: null,
  expectedEnd: null,
  canClaim: true
})
async function mount(onDetail = vi.fn(), onStructure = vi.fn()) {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(TaskClaimableGroups, { onDetail, onStructure })
  const plain = defineComponent({
    props: ['description', 'message'],
    setup:
      (props, { slots }) =>
      () =>
        h('div', [props.description, props.message, slots.default?.()])
  })
  for (const name of ['AForm', 'AFormItem', 'AAlert', 'AEmpty', 'ASpin', 'ATag', 'ATooltip']) app.component(name, plain)
  app.component(
    'AButton',
    defineComponent({
      props: ['disabled'],
      setup:
        (props, { slots }) =>
        () =>
          h('button', { disabled: props.disabled }, slots.default?.())
    })
  )
  app.component('ASelect', plain)
  app.component(
    'AInput',
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h('input', {
            value: props.value,
            onInput: (event: Event) => emit('update:value', (event.target as HTMLInputElement).value)
          })
    })
  )
  app.mount(host)
  await flush()
}
beforeEach(() => {
  vi.clearAllMocks()
  api.claimableGroups.mockResolvedValue({
    list: [
      {
        rootId: 'root',
        title: '可领取部分所属任务',
        rootVisible: false,
        canClaimGroup: false,
        claimableCount: 3,
        followRootCount: 0
      }
    ],
    total: 27
  })
  api.claimableChildren.mockResolvedValue([item('a', null), item('deep', 'a'), item('separate', null)])
})
afterEach(() => {
  app?.unmount()
  host?.remove()
})
describe('按真实总任务归组的安全领取目录', () => {
  it('领取后刷新最新负责人并保留仍存在的展开组，领取弹窗不被销毁', async () => {
    await mount()
    button('选择子任务').click()
    await flush()
    button('只领这一项', required(host.querySelector('[data-key="item:a"]'))).click()
    await flush()
    api.claimableChildren.mockResolvedValue([
      item('deep', 'a'),
      { ...item('a', null), canClaim: false, assigneeId: 'me', assigneeName: '我' }
    ])
    button('模拟领取成功').click()
    await flush()
    expect(api.claimableGroups).toHaveBeenCalledTimes(2)
    const row = required(host.querySelector('[data-key="item:a"]'))
    expect(row.textContent).not.toContain('只领这一项')
    expect(row.textContent).toContain('我')
    expect(host.querySelector('[data-claim="a"]')).not.toBeNull()
  })
  it('原组领完后从目录移除，刷新不继续读取失效组', async () => {
    await mount()
    button('选择子任务').click()
    await flush()
    api.claimableGroups.mockResolvedValue({ list: [], total: 0 })
    button('刷新').click()
    await flush()
    expect(host.querySelector('[data-key="group:root"]')).toBeNull()
    expect(api.claimableChildren).toHaveBeenCalledTimes(1)
  })
  it('展开完整整组任务，只有可领取节点突出，其他节点保留真实状态和负责人', async () => {
    api.claimableGroups.mockResolvedValue({
      list: [
        {
          rootId: 'root',
          title: '办公室装修',
          rootVisible: false,
          canClaimGroup: false,
          claimableCount: 1,
          followRootCount: 0,
          wholeClaimCount: 0,
          ownership: 'ASSIGNED',
          ownerName: '项目负责人',
          status: 'RUNNING',
          expectedStart: '2026-10-01',
          expectedEnd: '2026-10-15',
          priority: 'HIGH',
          childCount: 3,
          completedChildCount: 1,
          anchorTaskId: 'own'
        }
      ],
      total: 1
    })
    api.claimableChildren.mockResolvedValue([
      {
        ...item('survey', 'root'),
        title: '现场勘察',
        canClaim: false,
        assignmentMode: 'ASSIGNED',
        assigneeId: 'other',
        assigneeName: '王师傅',
        status: 'RUNNING',
        detailVisible: false
      },
      { ...item('own', 'root'), title: '装修施工', expectedStart: '2026-10-05', expectedEnd: '2026-10-10' },
      {
        ...item('done', 'root'),
        title: '已办手续',
        canClaim: false,
        assignmentMode: 'ASSIGNED',
        assigneeId: 'other',
        assigneeName: '王师傅',
        status: 'COMPLETED',
        detailVisible: false
      }
    ])
    const onDetail = vi.fn(),
      onStructure = vi.fn()
    await mount(onDetail, onStructure)
    const root = required(host.querySelector('[data-key="group:root"]'))
    expect(root.querySelector('[data-column="progress"]')?.textContent).toBe('1/3')
    expect(root.querySelector('[data-column="status"]')?.textContent).toBe('进行中')
    expect(root.querySelector('[data-column="expectedStart"]')?.textContent?.trim()).toBe('2026-10-01')
    expect(root.querySelector('[data-column="priority"]')?.textContent).toBe('高')
    button('办公室装修', root).click()
    expect(onStructure).toHaveBeenCalledWith('own', 'root')
    button('展开子任务：办公室装修').click()
    await flush()
    const survey = required(host.querySelector('[data-key="item:survey"]'))
    const own = required(host.querySelector('[data-key="item:own"]'))
    expect(survey.querySelector('.task-claimable-groups__context')).not.toBeNull()
    expect(own.querySelector('.task-claimable-groups__matched')).not.toBeNull()
    expect(survey.querySelector('[data-column="assignee"]')?.textContent).toBe('王师傅')
    expect(survey.querySelector('[data-column="status"]')?.textContent).toBe('进行中')
    expect(host.querySelector('[data-key="item:done"] [data-column="status"]')?.textContent).toBe('已完成')
    expect(survey.querySelector('[data-column="actions"]')?.textContent).toBe('查看编排')
    button('查看编排', survey).click()
    expect(onStructure).toHaveBeenLastCalledWith('own', 'survey')
    expect(onDetail).not.toHaveBeenCalled()
    expect(host.querySelector('[data-claim]')).toBeNull()
    button('详情', own).click()
    expect(onDetail).toHaveBeenCalledWith('own')
    expect(host.querySelector('[data-total]')?.textContent).toBe('1')
  })
  it('总任务自身可领而子任务均已分配时，仍能展开协作背景但只领取总任务', async () => {
    api.claimableGroups.mockResolvedValue({
      list: [
        {
          rootId: 'root',
          title: '总任务',
          rootVisible: false,
          canClaimGroup: true,
          claimableCount: 1,
          followRootCount: 0,
          wholeClaimCount: 1,
          claimableChildCount: 0,
          childCount: 1,
          completedChildCount: 0
        }
      ],
      total: 1
    })
    api.claimableChildren.mockResolvedValue([
      {
        ...item('assigned', 'root'),
        canClaim: false,
        assignmentMode: 'ASSIGNED',
        assigneeId: 'other',
        assigneeName: '同事'
      }
    ])
    await mount()
    button('展开子任务：总任务').click()
    await flush()
    expect(host.querySelector('[data-key="item:assigned"]')?.textContent).toContain('同事')
    expect(host.textContent).not.toContain('领取整个任务')
    expect(host.textContent).not.toContain('只领这一项')
    button('领取任务').click()
    await flush()
    expect(host.querySelector('[data-claim]')?.getAttribute('data-claim')).toBe('root')
  })
  it('已分配节点可按原有详情权限打开，无详情且无安全锚点时不产生无权链接', async () => {
    api.claimableChildren.mockResolvedValue([
      { ...item('visible', 'root'), canClaim: false, detailVisible: true },
      { ...item('summary', 'root'), canClaim: false, detailVisible: false }
    ])
    const onDetail = vi.fn(),
      onStructure = vi.fn()
    await mount(onDetail, onStructure)
    button('展开子任务：可领取部分所属任务').click()
    await flush()
    button('详情', required(host.querySelector('[data-key="item:visible"]'))).click()
    expect(onDetail).toHaveBeenCalledWith('visible')
    expect(host.querySelector('[data-key="item:summary"] [data-column="actions"]')?.textContent).toBe('仅概要')
    expect(host.querySelector('[data-key="item:summary"] button')).toBeNull()
    expect(onStructure).not.toHaveBeenCalled()
  })
  it('可折叠中间分支，重新展开保留原顺序和嵌套层级，不增加分页数量', async () => {
    await mount()
    button('展开子任务：可领取部分所属任务').click()
    await flush()
    const order = () => Array.from(host.querySelectorAll('[data-key]')).map(row => row.getAttribute('data-key'))
    expect(order()).toEqual(['group:root', 'item:a', 'item:deep', 'item:separate'])
    button('收起子任务：a').click()
    await flush()
    expect(order()).toEqual(['group:root', 'item:a', 'item:separate'])
    button('展开子任务：a').click()
    await flush()
    expect(order()).toEqual(['group:root', 'item:a', 'item:deep', 'item:separate'])
    expect(api.claimableChildren).toHaveBeenCalledOnce()
    expect(host.querySelector('[data-total]')?.textContent).toBe('27')
  })
  it('显式展开根或中间分支时一次打开全部后代，刷新保留手动收起的分支', async () => {
    api.claimableChildren.mockResolvedValue([
      item('a', 'root'),
      item('deep', 'a'),
      item('leaf', 'deep'),
      item('separate', 'root')
    ])
    await mount()
    button('展开子任务：可领取部分所属任务').click()
    await flush()
    expect(host.querySelector('[data-key="item:leaf"]')).not.toBeNull()
    button('收起子任务：deep').click()
    await flush()
    button('刷新').click()
    await flush()
    expect(host.querySelector('[data-key="item:deep"]')).not.toBeNull()
    expect(host.querySelector('[data-key="item:leaf"]')).toBeNull()
    button('收起子任务：a').click()
    await flush()
    button('展开子任务：a').click()
    await flush()
    expect(host.querySelector('[data-key="item:leaf"]')).not.toBeNull()
    expect(api.claimableChildren).toHaveBeenCalledTimes(2)
    button('收起子任务：deep').click()
    await flush()
    button('收起子任务：可领取部分所属任务').click()
    await flush()
    button('展开子任务：可领取部分所属任务').click()
    await flush()
    expect(host.querySelector('[data-key="item:leaf"]')).not.toBeNull()
    expect(api.claimableChildren).toHaveBeenCalledTimes(3)
    expect(api.detail).not.toHaveBeenCalled()
  })
  it('领取目录中的重复或异常父链保持可读且展开操作不会循环', async () => {
    api.claimableChildren.mockResolvedValue([
      item('a', 'deep'),
      item('deep', 'a'),
      item('deep', 'a'),
      item('leaf', 'deep')
    ])
    await mount()
    button('展开子任务：可领取部分所属任务').click()
    await flush()
    expect(host.querySelectorAll('[data-key="item:deep"]')).toHaveLength(1)
    button('收起子任务：deep').click()
    await flush()
    button('收起子任务：a').click()
    await flush()
    button('展开子任务：a').click()
    await flush()
    expect(host.querySelector('[data-key="item:leaf"]')).not.toBeNull()
    expect(api.claimableChildren).toHaveBeenCalledOnce()
  })
  it('刷新期间翻页后，不用旧展开选择展开新页的同名任务组', async () => {
    await mount()
    button('展开子任务：可领取部分所属任务').click()
    await flush()
    let finish!: (page: { list: TaskClaimableGroup[]; total: number }) => void
    api.claimableGroups.mockImplementationOnce(() => new Promise(resolve => (finish = resolve)))
    button('刷新').click()
    await flush()
    button('下一页').click()
    await flush()
    finish({ list: [], total: 0 })
    await flush()
    expect(host.querySelector('[data-key="item:a"]')).toBeNull()
    expect(api.claimableChildren).toHaveBeenCalledOnce()
  })
  it('缺失的旧服务摘要显示占位，不伪造未开始、零进度和默认优先级', async () => {
    await mount()
    const root = required(host.querySelector('[data-key="group:root"]'))
    for (const column of ['progress', 'status', 'expectedStart', 'expectedEnd', 'priority'])
      expect(root.querySelector(`[data-column="${column}"]`)?.textContent?.trim()).toBe('—')
  })
  it('展开失败后可以原地重试，不丢失分组也不误用旧展开结果', async () => {
    api.claimableChildren.mockRejectedValueOnce(new Error('网络中断'))
    await mount()
    button('展开子任务：可领取部分所属任务').click()
    await flush()
    expect(host.textContent).toContain('网络中断')
    expect(host.querySelector('[data-key="item:a"]')).toBeNull()
    button('展开子任务：可领取部分所属任务').click()
    await flush()
    expect(api.claimableChildren).toHaveBeenCalledTimes(2)
    expect(host.querySelector('[data-key="item:a"]')).not.toBeNull()
    expect(host.textContent).not.toContain('网络中断')
  })
  it('未领取但可整领的总任务可从名称和详情打开统一抽屉，不触发领取', async () => {
    api.claimableGroups.mockResolvedValue({
      list: [
        {
          rootId: 'root',
          title: '办公室装修',
          rootVisible: false,
          canClaimGroup: true,
          wholeClaimCount: 2,
          claimableCount: 2,
          followRootCount: 1
        }
      ],
      total: 1
    })
    const onDetail = vi.fn()
    await mount(onDetail)
    button('办公室装修').click()
    button('详情').click()
    expect(onDetail.mock.calls).toEqual([['root'], ['root']])
    expect(host.querySelector('[data-claim]')).toBeNull()
    expect(api.claimableChildren).not.toHaveBeenCalled()
  })
  it('可领取子任务的名称和详情打开该子任务，受限总任务仍不可点击', async () => {
    const onDetail = vi.fn()
    await mount(onDetail)
    const root = required(host.querySelector('[data-key="group:root"]'))
    expect(root.querySelector('[data-column="title"]')?.textContent).toContain('可领取部分所属任务')
    expect(Array.from(root.querySelectorAll('button')).some(b => b.textContent?.trim() === '详情')).toBe(false)
    button('展开子任务：可领取部分所属任务').click()
    await flush()
    const child = required(host.querySelector('[data-key="item:deep"]'))
    button('deep', child).click()
    button('详情', child).click()
    expect(onDetail.mock.calls).toEqual([['deep'], ['deep']])
    expect(host.querySelector('[data-claim]')).toBeNull()
  })
  it('已有详情权限但不可整领的总任务仍复用同一详情入口', async () => {
    api.claimableGroups.mockResolvedValue({
      list: [
        {
          rootId: 'root',
          title: '已负责的总任务',
          rootVisible: true,
          canClaimGroup: false,
          claimableCount: 1,
          followRootCount: 0,
          wholeClaimCount: 0
        }
      ],
      total: 1
    })
    const onDetail = vi.fn()
    await mount(onDetail)
    button('已负责的总任务').click()
    expect(onDetail).toHaveBeenCalledWith('root')
  })
  it('统一进度、状态和日期列，固定操作列且不再常驻冗长领取说明', async () => {
    await mount()
    const header = required(host.querySelector('header'))
    expect(header.getAttribute('data-column-widths')).toBe('320,100,105,125,125,150,85,205')
    expect(header.getAttribute('data-scroll-x')).toBe('1215')
    expect(header.textContent).toBe('任务 / 子任务 / 进度 / 状态 / 预计开始 / 预计完成 / 负责人 / 优先级 / 操作')
    expect(host.textContent).not.toContain('可一次领取整个任务')
  })
  it('分页按组，展开仅请求安全摘要且按真实父子缩进，不伪造总任务详情', async () => {
    await mount()
    expect(api.claimableGroups).toHaveBeenCalledWith({ pageNo: 1, pageSize: 10 })
    expect(host.querySelector('[data-total]')?.textContent).toBe('27')
    expect(host.querySelector('[data-key="group:root"]')?.textContent).not.toContain('领取整个任务')
    button('展开子任务：可领取部分所属任务').click()
    await flush()
    expect(api.claimableChildren).toHaveBeenCalledWith('root')
    expect(api.detail).not.toHaveBeenCalled()
    expect(host.querySelector('[data-key="item:deep"] [data-depth]')?.getAttribute('data-depth')).toBe('2')
    expect(host.querySelector('[data-key="item:a"]')?.textContent).toContain('子任务')
    button('只领这一项', required(host.querySelector('[data-key="item:deep"]'))).click()
    await flush()
    expect(host.querySelector('[data-claim]')?.getAttribute('data-claim')).toBe('deep')
    button('下一页').click()
    await flush()
    expect(api.claimableGroups).toHaveBeenLastCalledWith({ pageNo: 2, pageSize: 10 })
    expect(host.querySelector('[data-total]')?.textContent).toBe('27')
  })
  it('可领取真实根提供整项入口，不把根再次当作普通子项领取', async () => {
    api.claimableGroups.mockResolvedValue({
      list: [
        {
          rootId: 'root',
          title: '现场施工',
          rootVisible: true,
          canClaimGroup: true,
          claimableCount: 2,
          followRootCount: 1,
          wholeClaimCount: 2
        }
      ],
      total: 1
    })
    api.claimableChildren.mockResolvedValue([
      item('root', null),
      { ...item('a', 'root'), assignmentMode: 'FOLLOW_ROOT' }
    ])
    await mount()
    button('展开子任务：现场施工').click()
    await flush()
    expect(host.querySelector('[data-key="item:root"]')).toBeNull()
    expect(host.textContent).toContain('领取整个任务')
    button('领取整个任务').click()
    await flush()
    expect(host.querySelector('[data-claim]')?.getAttribute('data-claim')).toBe('root')
  })
  it('翻页后迟到的展开响应不串入新页，搜索仅发送可领名称与允许的筛选', async () => {
    let finish!: (items: TaskClaimableItem[]) => void
    api.claimableChildren.mockImplementationOnce(
      () =>
        new Promise(resolve => {
          finish = resolve
        })
    )
    await mount()
    button('展开子任务：可领取部分所属任务').click()
    await flush()
    button('下一页').click()
    await flush()
    finish([item('stale', null)])
    await flush()
    expect(host.textContent).not.toContain('stale')
    const input = required(host.querySelector('input'))
    input.value = '测量'
    input.dispatchEvent(new Event('input'))
    await flush()
    button('查询').click()
    await flush()
    expect(api.claimableGroups).toHaveBeenLastCalledWith({ pageNo: 1, pageSize: 10, search: '测量' })
  })
  it('未分配的开放子任务可随整个任务一次领取，也保留只领一项入口', async () => {
    api.claimableGroups.mockResolvedValue({
      list: [
        {
          rootId: 'root',
          title: '办公室装修',
          rootVisible: false,
          canClaimGroup: true,
          claimableCount: 4,
          followRootCount: 0,
          wholeClaimCount: 4
        }
      ],
      total: 1
    })
    await mount()
    expect(host.textContent).toContain('领取整个任务')
    expect(host.textContent).not.toContain('你可领取 3 项子任务')
    expect(host.textContent).not.toContain('需单独领取')
    expect(host.textContent).not.toContain('承接')
    expect(host.textContent).not.toContain('0 项')
    expect(host.textContent).not.toContain('不开放总任务资料')
    button('展开子任务：办公室装修').click()
    await flush()
    expect(button('只领这一项', required(host.querySelector('[data-key="item:a"]')))).toBeTruthy()
    button('领取整个任务').click()
    await flush()
    expect(host.querySelector('[data-claim]')?.getAttribute('data-claim')).toBe('root')
  })
  it('整项数量由服务端给出，不能用独立可领总数推断或沿用旧的整领资格', async () => {
    api.claimableGroups.mockResolvedValue({
      list: [
        {
          rootId: 'root',
          title: '办公室装修',
          rootVisible: false,
          canClaimGroup: false,
          claimableCount: 4,
          followRootCount: 0,
          wholeClaimCount: 3
        }
      ],
      total: 1
    })
    await mount()
    expect(host.textContent).toContain('领取整个任务')
    button('领取整个任务').click()
    await flush()
    expect(host.querySelector('[data-claim]')?.getAttribute('data-claim')).toBe('root')
  })
  it('单个任务不提供空的展开入口，领取说明不虚构子任务', async () => {
    api.claimableGroups.mockResolvedValue({
      list: [
        {
          rootId: 'root',
          title: '采购电脑',
          rootVisible: false,
          canClaimGroup: true,
          claimableCount: 1,
          followRootCount: 0
        }
      ],
      total: 1
    })
    await mount()
    const group = required(host.querySelector('[data-key="group:root"]'))
    expect(group.textContent).toContain('领取任务')
    expect(group.querySelector('[aria-expanded]')).toBeNull()
    expect(group.textContent).not.toContain('子任务')
    button('领取任务').click()
    await flush()
    expect(api.claimableChildren).not.toHaveBeenCalled()
  })
  it('总任务不可领时用选择子任务入口展开，不透露总任务负责人或改派承诺', async () => {
    await mount()
    const group = required(host.querySelector('[data-key="group:root"]'))
    expect(group.textContent).toContain('选择子任务')
    expect(group.textContent).not.toContain('同时领取')
    button('选择子任务').click()
    await flush()
    expect(api.claimableChildren).toHaveBeenCalledOnce()
    button('选择子任务').click()
    await flush()
    expect(api.claimableChildren).toHaveBeenCalledOnce()
    expect(host.querySelector('[data-key="item:a"]')).not.toBeNull()
  })
  it('后端明确返回零项时不显示整领入口，不被旧资格字段覆盖', async () => {
    api.claimableGroups.mockResolvedValue({
      list: [
        {
          rootId: 'root',
          title: '办公室装修',
          rootVisible: false,
          canClaimGroup: true,
          claimableCount: 2,
          followRootCount: 0,
          wholeClaimCount: 0
        }
      ],
      total: 1
    })
    await mount()
    const group = required(host.querySelector('[data-key="group:root"]'))
    expect(group.textContent).toContain('选择子任务')
    expect(group.textContent).not.toContain('领取整个任务')
    expect(group.textContent).not.toContain('0 项')
    button('选择子任务').click()
    await flush()
    expect(api.claimableChildren).toHaveBeenCalledOnce()
  })
  it.each([
    ['UNCLAIMED', null, '待领取'],
    ['MINE', '当前员工', '当前员工'],
    ['ASSIGNED', '王师傅', '王师傅'],
    ['ASSIGNED', null, '已分配'],
    ['UNAVAILABLE', null, '—'],
    ['RESTRICTED', '同组负责人', '同组负责人']
  ] as const)('负责人列独立展示 %s，兼容缺失摘要并显示服务端公开的协作姓名', async (ownership, ownerName, label) => {
    api.claimableGroups.mockResolvedValue({
      list: [
        {
          rootId: 'root',
          title: '办公室装修',
          rootVisible: ownership !== 'RESTRICTED',
          canClaimGroup: false,
          claimableCount: 2,
          followRootCount: 0,
          wholeClaimCount: 0,
          ownership,
          ownerName,
          claimableChildCount: 2,
          remainingClaimCount: 0
        }
      ],
      total: 1
    })
    await mount()
    expect(host.querySelector('header')?.textContent).toContain('负责人')
    const group = required(host.querySelector('[data-key="group:root"]'))
    expect(group.querySelector('[data-column="assignee"]')?.textContent).toBe(label)
    expect(host.textContent).not.toContain('已领完')
    button('选择子任务').click()
    await flush()
    expect(host.querySelector('[data-key="item:a"] [data-column="assignee"]')?.textContent).toBe('待领取')
  })
  it('本人总任务提供领取剩余项入口，既不重复领取总任务也不隐藏单项入口', async () => {
    api.claimableGroups.mockResolvedValue({
      list: [
        {
          rootId: 'root',
          title: '办公室装修',
          rootVisible: true,
          canClaimGroup: false,
          claimableCount: 2,
          followRootCount: 0,
          wholeClaimCount: 0,
          ownership: 'MINE',
          ownerName: '当前员工',
          claimableChildCount: 2,
          remainingClaimCount: 2
        } satisfies TaskClaimableGroup
      ],
      total: 1
    })
    await mount()
    const group = required(host.querySelector('[data-key="group:root"]'))
    expect(group.textContent).toContain('领取剩余2项')
    expect(group.textContent).not.toContain('领取整个任务')
    button('领取剩余2项').click()
    await flush()
    expect(host.querySelector('[data-claim]')?.getAttribute('data-claim')).toBe('root')
    button('展开子任务：办公室装修').click()
    await flush()
    expect(button('只领这一项', required(host.querySelector('[data-key="item:a"]')))).toBeTruthy()
  })
  it('补领数量不能绕过本人归属，零项不提示且不添加已领完筛选', async () => {
    api.claimableGroups.mockResolvedValue({
      list: [
        {
          rootId: 'root',
          title: '现场施工',
          rootVisible: true,
          canClaimGroup: false,
          claimableCount: 1,
          followRootCount: 0,
          wholeClaimCount: 0,
          ownership: 'ASSIGNED',
          ownerName: '同事',
          claimableChildCount: 0,
          remainingClaimCount: 1
        }
      ],
      total: 1
    })
    await mount()
    expect(host.textContent).not.toContain('领取剩余')
    expect(host.textContent).not.toContain('0 项')
    expect(host.textContent).not.toContain('已领完')
    expect(api.claimableGroups).toHaveBeenCalledWith({ pageNo: 1, pageSize: 10 })
  })
})

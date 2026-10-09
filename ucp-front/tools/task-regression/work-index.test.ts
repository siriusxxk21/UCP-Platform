import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref } from 'vue'
import FlowWorkDrawer from '@/views/bpm/task/FlowWorkDrawer.vue'
import type { FlowWorkItem, FlowWorkPage } from '@/api/nocode/flow-work-index'
import { WorkDraftState } from '@/types/nocode/work'

const calls = vi.hoisted(() => ({ page: vi.fn(), push: vi.fn() }))
vi.mock('@/utils/request', () => ({ default: { post: calls.page } }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: calls.push }) }))
vi.mock('@/views/nocode/application/components/RecordSurface.vue', () => ({
  default: defineComponent({
    props: ['open'],
    emits: ['update:open'],
    setup(props, { slots, emit }) {
      return () =>
        props.open
          ? h('section', [h('button', { onClick: () => emit('update:open', false) }, '关闭抽屉'), slots.default?.()])
          : null
    }
  })
}))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['dataSource'],
    setup(props, { slots }) {
      return () =>
        h('div', [
          slots.toolbar?.(),
          ...(props.dataSource || []).map((record: FlowWorkItem) =>
            h('article', [
              record.formName,
              slots.bodyCell?.({ column: { key: 'state' }, record }),
              slots.bodyCell?.({ column: { key: 'action' }, record })
            ])
          )
        ])
    }
  })
}))
const dispose: Array<() => void> = []
const cursor = { createdAt: '2026-09-10T08:30:00.123456', id: 'cursor-1' }
const item = (id = 'draft-1', state: WorkDraftState = WorkDraftState.DRAFT): FlowWorkItem => ({
  draftId: id,
  taskId: `task-${id}`,
  processInstanceId: `process-${id}`,
  nodeId: 'node-1',
  state,
  formName: `表单-${id}`,
  objectName: '设备',
  applicationId: '17',
  applicationVersion: 2,
  updatedAt: 1789010000000,
  submissionId: state === WorkDraftState.SUBMITTED ? `submission-${id}` : null,
  writable: state === WorkDraftState.DRAFT,
  blockedReason: null
})
async function settle() {
  for (let i = 0; i < 15; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const buttons = (label: string) =>
  Array.from(document.querySelectorAll('button')).filter(b => b.textContent?.includes(label))
async function click(label: string) {
  const b = buttons(label)[0]
  if (!b) throw new Error(`缺少 ${label}`)
  b.click()
  await settle()
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(yes => {
    resolve = yes
  })
  return { promise, resolve }
}
async function mount(initialOpen = true, initialState: WorkDraftState = WorkDraftState.DRAFT) {
  const open = ref(initialOpen)
  const host = document.createElement('div')
  document.body.append(host)
  const app = createApp({
    render: () =>
      h(FlowWorkDrawer, {
        open: open.value,
        initialState,
        'onUpdate:open': value => {
          open.value = value
        }
      })
  })
  const wrapper = defineComponent({
    setup(_, { slots }) {
      return () => h('span', slots.default?.())
    }
  })
  for (const name of ['a-tab-pane', 'a-tag', 'a-tooltip']) app.component(name, wrapper)
  app.component(
    'a-button',
    defineComponent({
      props: ['disabled', 'loading'],
      setup(props, { slots }) {
        return () => h('button', { disabled: props.disabled || props.loading }, slots.default?.())
      }
    })
  )
  app.component(
    'a-tabs',
    defineComponent({
      emits: ['change'],
      setup(_, { emit }) {
        return () =>
          h('nav', [
            h('button', { onClick: () => emit('change', 'DRAFT') }, '切换草稿'),
            h('button', { onClick: () => emit('change', 'SUBMITTED') }, '切换材料')
          ])
      }
    })
  )
  app.component(
    'a-alert',
    defineComponent({
      props: ['message'],
      setup(props, { slots }) {
        return () => h('aside', { role: 'alert' }, [props.message, slots.action?.()])
      }
    })
  )
  app.mount(host)
  dispose.push(() => {
    app.unmount()
    host.remove()
  })
  await settle()
  return { open }
}
beforeEach(() => {
  vi.clearAllMocks()
  calls.page.mockResolvedValue({ items: [item()], before: null })
  calls.push.mockResolvedValue(undefined)
})
afterEach(() => dispose.splice(0).forEach(fn => fn()))

describe('本人流程草稿与材料索引', () => {
  it('关闭时不请求，首次打开只发送状态、游标及10条上限，不发送操作者', async () => {
    const { open } = await mount(false)
    expect(calls.page).not.toHaveBeenCalled()
    open.value = true
    await settle()
    expect(calls.page).toHaveBeenCalledExactlyOnceWith('/nocode/flow-task/work-page', {
      state: 'DRAFT',
      before: null,
      limit: 10
    })
    expect(document.body.textContent).toContain('表单-draft-1')
  })
  it('空的授权过滤页仍可加载下一页，保持游标微秒精度', async () => {
    calls.page
      .mockResolvedValueOnce({ items: [], before: cursor })
      .mockResolvedValueOnce({ items: [item('visible')], before: null })
    await mount()
    expect(document.body.textContent).toContain('本页暂无可显示记录，可继续加载')
    await click('加载更多')
    expect(calls.page.mock.lastCall?.[1]).toEqual({ state: 'DRAFT', before: cursor, limit: 10 })
    expect(document.body.textContent).toContain('表单-visible')
    expect(buttons('加载更多')).toHaveLength(0)
  })
  it('加载下一页失败保留现有摘要，重试继续同一游标而不是从头查询', async () => {
    calls.page
      .mockResolvedValueOnce({ items: [item()], before: cursor })
      .mockRejectedValueOnce(new Error('读取失败'))
      .mockResolvedValueOnce({ items: [item(), item('second')], before: null })
    await mount()
    await click('加载更多')
    expect(document.body.textContent).toContain('读取失败')
    expect(document.body.textContent).toContain('表单-draft-1')
    await click('重试')
    expect(calls.page.mock.lastCall?.[1].before).toEqual(cursor)
    expect(document.querySelectorAll('article')).toHaveLength(2)
    expect(document.body.textContent).not.toContain('读取失败')
  })
  it('刷新重新核验摘要，失败不保留旧权限下的列表', async () => {
    calls.page
      .mockResolvedValueOnce({ items: [item()], before: cursor })
      .mockRejectedValueOnce(new Error('当前无权查看'))
    await mount()
    await click('刷新')
    expect(document.querySelectorAll('article')).toHaveLength(0)
    expect(buttons('加载更多')).toHaveLength(0)
    expect(calls.page.mock.lastCall?.[1].before).toBeNull()
  })
  it('快速切换到材料时，旧草稿请求不能覆盖材料结果', async () => {
    const pending = deferred<FlowWorkPage>()
    calls.page
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValueOnce({ items: [item('material', WorkDraftState.SUBMITTED)], before: null })
    await mount()
    await click('切换材料')
    pending.resolve({ items: [item('late')], before: cursor })
    await settle()
    expect(document.body.textContent).toContain('表单-material')
    expect(document.body.textContent).not.toContain('表单-late')
    expect(buttons('加载更多')).toHaveLength(0)
  })
  it('关闭后迟到请求不污染再次打开的列表', async () => {
    const pending = deferred<FlowWorkPage>()
    calls.page.mockReturnValueOnce(pending.promise).mockResolvedValueOnce({ items: [item('new')], before: null })
    const { open } = await mount()
    await click('关闭抽屉')
    pending.resolve({ items: [item('old')], before: cursor })
    await settle()
    open.value = true
    await settle()
    expect(document.body.textContent).toContain('表单-new')
    expect(document.body.textContent).not.toContain('表单-old')
  })
  it('只读草稿不提供可执行的办理操作', async () => {
    calls.page.mockResolvedValue({
      items: [{ ...item(), writable: false, blockedReason: '当前无新增权限' }],
      before: null
    })
    await mount()
    expect(buttons('暂不可办理')[0]?.disabled).toBe(true)
    expect(document.body.textContent).toContain('只读')
    await click('暂不可办理')
    expect(calls.push).not.toHaveBeenCalled()
  })
  it.each([WorkDraftState.DRAFT, WorkDraftState.SUBMITTED])(
    '%s 按任务身份进入原办理页，不重新创建来源或传递资源',
    async state => {
      calls.page.mockResolvedValue({ items: [item('target', state)], before: null })
      const { open } = await mount(true, state)
      await click(state === WorkDraftState.DRAFT ? '继续填写' : '查看材料')
      expect(calls.push).toHaveBeenCalledWith({ name: 'NocodeFlowTask', query: { taskId: 'task-target' } })
      expect(open.value).toBe(false)
    }
  )
  it('路由被现有离开保护拦截时保留抽屉', async () => {
    calls.push.mockResolvedValue({ type: 4 })
    const { open } = await mount()
    await click('继续填写')
    expect(open.value).toBe(true)
  })
})

import { afterEach, beforeAll, expect, it, vi } from 'vitest'
import { createApp, h, inject, nextTick, type App } from 'vue'
import Antd from 'ant-design-vue'
const mocks = vi.hoisted(() => ({ get: vi.fn(), draft: vi.fn(), submit: vi.fn() }))
vi.mock('@/utils/request', () => ({ default: {} }))
vi.mock('@/api/nocode/task-entry', () => ({ createTaskEntryApi: () => mocks }))
vi.mock('@/nocode/platform', () => ({
  nocodePlatformKey: Symbol.for('task-test-platform'),
  useNocodePlatform: () => ({ runtime: {} })
}))
vi.mock('@/views/nocode/application/components/BusinessRecords.vue', () => ({
  default: { render: () => h('div', '普通业务列表') }
}))
vi.mock('@/views/nocode/application/components/RecordEditor.vue', () => ({
  default: {
    props: ['record', 'readOnly'],
    setup(props: any) {
      const platform: any = inject(Symbol.for('task-test-platform'))
      return () =>
        h('div', { class: 'test-editor', 'data-readonly': String(!!props.readOnly) }, [
          JSON.stringify(props.record),
          !props.readOnly &&
            h(
              'button',
              {
                onClick: () =>
                  platform.runtime.submit({
                    applicationId: 'app',
                    objectId: 'object',
                    values: props.record?.record.values,
                    details: props.record?.details
                  })
              },
              '提交恢复的整单'
            )
        ])
    }
  }
}))
import TaskSession from '@/views/nocode/task-center/TaskSession.vue'
const apps: App[] = []
beforeAll(() => {
  window.matchMedia = vi.fn().mockImplementation(() => ({ matches: false, addListener() {}, removeListener() {} }))
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  )
})
afterEach(() => {
  apps.splice(0).forEach(app => app.unmount())
  document.body.innerHTML = ''
  vi.resetAllMocks()
})
function mount(props: any) {
  const context: any = {
    entry: { applicationId: 'app', entryId: 'entry', version: 2 },
    config: { mode: 'FORM', objectId: 'object' },
    resources: [],
    model: {}
  }
  const host = document.createElement('div')
  document.body.append(host)
  const app = createApp({ render: () => h(TaskSession, { context, ...props }) })
  app.use(Antd)
  app.mount(host)
  apps.push(app)
  return host
}
it('FORM 已办加载原记录并强制只读，不出现空白新建表单', async () => {
  mocks.get.mockResolvedValue({ record: { id: 'record', values: { name: '已提交采购' } }, details: {} })
  const host = mount({ recordId: 'record' })
  await vi.waitFor(() => expect(host.textContent).toContain('已提交采购'))
  expect(host.querySelector('.test-editor')?.getAttribute('data-readonly')).toBe('true')
  expect(host.textContent).not.toContain('保存后继续填写')
  expect(mocks.get).toHaveBeenCalledWith(expect.objectContaining({ version: 2 }), 'record')
})
it('门户继续草稿直接恢复主表与明细，提交携带原草稿修订', async () => {
  const details = { lines: [{ clientRowKey: 'same-line', values: { quantity: '3' } }] }
  mocks.draft.mockResolvedValue({ id: 'draft', revision: 4, values: { name: '草稿采购' }, details })
  mocks.submit.mockResolvedValue({ outcome: 'SUBMITTED', request: { id: 'request' } })
  const host = mount({ draftId: 'draft' })
  await vi.waitFor(() => expect(host.textContent).toContain('same-line'))
  expect(host.textContent).toContain('草稿采购')
  host.querySelector<HTMLButtonElement>('.test-editor button')!.click()
  await vi.waitFor(() =>
    expect(mocks.submit).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ details }), {
      id: 'draft',
      revision: 4
    })
  )
})
it('已失效草稿显示原因，不能降级成空白表单误提交', async () => {
  mocks.draft.mockResolvedValue(null)
  const host = mount({ draftId: 'old-draft' })
  await vi.waitFor(() => expect(host.textContent).toContain('草稿状态已变化'))
  await nextTick()
  expect(host.querySelector('.test-editor')).toBeNull()
  expect(mocks.submit).not.toHaveBeenCalled()
})

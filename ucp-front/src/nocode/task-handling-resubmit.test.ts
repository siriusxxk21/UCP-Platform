// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import HandlingRequestDetail from '@/views/nocode/task-center/HandlingRequestDetail.vue'
import { nocodePlatformKey, type NocodePlatform } from './platform'

const api = vi.hoisted(() => ({
  reopen: vi.fn(),
  detail: vi.fn(),
  handlingTask: vi.fn(),
  entryHandlingLocation: vi.fn()
}))
vi.mock('@/api/nocode/handling', () => ({ createHandlingApi: () => api }))
vi.mock('@/utils/request', () => ({ default: {} }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/views/nocode/task-center/TaskDetail.vue', () => ({
  default: defineComponent({
    props: ['id', 'initialTab', 'initialEntryKey', 'initialContributionId'],
    setup: props => () =>
      h(
        'div',
        {
          'data-task': props.id,
          'data-tab': props.initialTab,
          'data-entry': props.initialEntryKey,
          'data-contribution': props.initialContributionId
        },
        '任务材料'
      )
  })
}))
vi.mock('@/views/nocode/task-center/HandlingResubmit.vue', () => ({
  default: { render: () => h('div', '普通申请重提') }
}))
vi.mock('@/views/nocode/application/components/RecordReadView.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/BusinessFileField.vue', () => ({ default: { render: () => null } }))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({ default: { render: () => null } }))
let app: App, host: HTMLElement
const flush = async () => {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount(status = 'REJECTED', openResubmit = true) {
  api.detail.mockResolvedValue({
    request: { id: 'request', status, name: '原申请' },
    definition: { fields: [], fieldOptions: {}, details: [], relations: [], settings: {} },
    material: { values: {}, details: {}, handling: { before: null } }
  })
  app = createApp(HandlingRequestDetail, { id: 'request' })
  app.provide(nocodePlatformKey, {
    taskCenter: { handlingTask: api.handlingTask, entryHandlingLocation: api.entryHandlingLocation }
  } as unknown as NocodePlatform)
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of ['ASpin', 'ATag', 'AAlert', 'ASpace', 'ARadioGroup', 'ARadioButton', 'ATextarea'])
    app.component(name, plain)
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
    'AModal',
    defineComponent({
      props: ['open'],
      setup:
        (props, { slots }) =>
        () =>
          props.open ? h('section', { 'data-surface': 'modal' }, [slots.default?.(), slots.footer?.()]) : null
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  if (openResubmit) {
    Array.from(host.querySelectorAll('button'))
      .find(button => button.textContent?.trim() === '修改后重新提交')!
      .click()
    await flush()
  }
}
afterEach(() => {
  app?.unmount()
  host?.remove()
  vi.clearAllMocks()
  api.entryHandlingLocation.mockReset()
})
describe('旧申请入口重提交分流', () => {
  it('撤回申请使用轻量弹窗，不叠加操作抽屉', async () => {
    await mount('PENDING', false)
    Array.from(host.querySelectorAll('button'))
      .find(button => button.textContent?.trim() === '撤回申请')!
      .click()
    await flush()
    expect(host.querySelector('[data-surface="modal"]')).not.toBeNull()
    expect(host.querySelector('.ant-drawer')).toBeNull()
  })
  it('任务申请回到对应任务业务材料，不走独立申请重提', async () => {
    api.handlingTask.mockResolvedValue('task')
    await mount()
    expect(api.handlingTask).toHaveBeenCalledWith('request')
    expect(api.reopen).not.toHaveBeenCalled()
    expect(host.querySelector('[data-task="task"]')?.getAttribute('data-tab')).toBe('business')
  })
  it('普通申请继续使用原重提链路', async () => {
    api.handlingTask.mockResolvedValue(null)
    api.reopen.mockResolvedValue({})
    await mount()
    expect(api.reopen).toHaveBeenCalledWith('request')
    expect(host.textContent).toContain('普通申请重提')
  })
  it('多入口申请定位到对应任务、入口和具体反馈，不能进入其他重提链路', async () => {
    api.entryHandlingLocation.mockResolvedValue({
      taskId: 'feedback-task',
      entryKey: 'quality-entry',
      contributionId: 'rejected-line'
    })
    await mount()
    expect(api.entryHandlingLocation).toHaveBeenCalledWith('request')
    expect(api.handlingTask).not.toHaveBeenCalled()
    expect(api.reopen).not.toHaveBeenCalled()
    const target = host.querySelector('[data-task="feedback-task"]')!
    expect(target.getAttribute('data-entry')).toBe('quality-entry')
    expect(target.getAttribute('data-contribution')).toBe('rejected-line')
  })
  it('任务反馈授权拒绝时不降级成普通申请重提', async () => {
    api.entryHandlingLocation.mockRejectedValue(new Error('反馈已撤权'))
    await mount()
    expect(api.handlingTask).not.toHaveBeenCalled()
    expect(api.reopen).not.toHaveBeenCalled()
    expect(host.querySelector('[data-task]')).toBeNull()
  })
})

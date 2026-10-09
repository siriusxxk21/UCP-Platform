// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, type App } from 'vue'
import PageTasks from '@/views/nocode/application/components/PageTasks.vue'
import { nocodePlatformKey } from './platform'
import type { ApplicationResource } from '@/types/nocode/application'
import type { RecordModel } from '@/types/nocode/runtime'

const fixture = vi.hoisted(() => ({
  form: vi.fn(),
  openDetail: vi.fn(),
  links: [] as Array<Record<string, unknown>>,
  launches: [] as Array<Record<string, unknown>>,
  launchCreated: [] as Array<(id: string) => void>,
  instances: [] as Array<{ props: Record<string, unknown>; load: (rows: unknown[]) => void }>
}))
vi.mock('@/views/nocode/task-center/TaskLinkPicker.vue', () => ({
  default: defineComponent({
    props: ['context'],
    setup(props) {
      fixture.links.push(props)
      return () => h('div', '关联任务')
    }
  })
}))
vi.mock('@/views/nocode/task-center/TaskLaunch.vue', () => ({
  default: defineComponent({
    props: ['initialApplicationId', 'initialApplicationName', 'initialBinding', 'initialProject', 'footerTarget'],
    emits: ['created'],
    setup(props, { emit }) {
      fixture.launches.push(props)
      fixture.launchCreated.push(id => emit('created', { task: { id } }))
      return () => h('button', { onClick: () => emit('created', { task: { id: 'created-task' } }) }, '模拟发起成功')
    }
  })
}))
vi.mock('@/components/ucp-modal-form/OsModalForm.vue', () => ({
  default: defineComponent({
    props: ['open', 'width', 'maximizable'],
    setup:
      (props, { slots }) =>
      () =>
        props.open
          ? h('div', { 'data-launch-width': props.width, 'data-launch-maximizable': props.maximizable !== undefined }, [
              slots.formItems?.(),
              slots.footer?.()
            ])
          : null
  })
}))
vi.mock('@/components/ucp-table-page/OsDynamicSearch.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskList.vue', () => ({
  default: defineComponent({
    props: ['context', 'businessValues', 'businessColumns', 'view', 'refreshKey'],
    emits: ['loaded'],
    setup(props, { emit, expose }) {
      expose({ openDetail: fixture.openDetail })
      fixture.instances.push({ props, load: rows => emit('loaded', rows) })
      return () => h('div', { class: 'test-task-list' }, JSON.stringify(props.businessValues))
    }
  })
}))
let app: App, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function resources(context = true): ApplicationResource[] {
  return [
    {
      id: 'page',
      kind: 'PAGE',
      code: 'page',
      name: '配置页',
      config: { ...(context ? { contextObjectId: 'work' } : {}), nodes: [] }
    },
    {
      id: 'form',
      kind: 'FORM',
      code: 'form',
      name: '工作表单',
      config: { objectId: 'work', nodes: [{ id: 'field', type: 'FIELD', fieldId: 'quantity', children: [] }] }
    }
  ]
}
const model = {
  object: { fields: [{ id: 'quantity', name: '数量', type: 'INTEGER' }], fieldOptions: {}, titleFieldId: 'quantity' },
  permissions: { readFields: ['quantity'] }
} as RecordModel
async function mount(
  context = true,
  recordId?: string,
  taskView?: import('@/types/nocode/application-ui').TaskViewConfig,
  resourceId: string | null = 'form',
  pageResources = resources(context)
) {
  const props = reactive({
    applicationId: 'app',
    resources: pageResources,
    pageId: 'page',
    nodeId: 'tasks',
    resourceId,
    recordId,
    taskView
  })
  app = createApp(() => h(PageTasks, props))
  const runtime = {
    model: vi.fn(async () => model),
    mine: vi.fn(async () => [{ id: 'app', name: '施工应用' }]),
    get: vi.fn(async () => ({ record: { values: { quantity: 0 } } }))
  }
  app.provide(nocodePlatformKey, {
    runtime,
    taskCenter: { form: fixture.form },
    hasPermission: () => true
  } as never)
  const wrapper = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('section', [slots.extra?.(), slots.default?.()])
  })
  app.component('ACard', wrapper)
  app.component('AAlert', wrapper)
  app.component('ASpace', wrapper)
  app.component('ATag', wrapper)
  app.component(
    'AButton',
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('button', slots.default?.())
    })
  )
  app.component('AEmpty', defineComponent({ props: ['description'], setup: props => () => h('p', props.description) }))
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  return { ...runtime, setRecordId: (id: string) => (props.recordId = id) }
}
afterEach(() => {
  app?.unmount()
  host?.remove()
  fixture.instances.length = 0
  fixture.launches.length = 0
  fixture.launchCreated.length = 0
  fixture.links.length = 0
  fixture.form.mockReset()
  fixture.openDetail.mockReset()
})

describe('配置页任务范围与业务字段', () => {
  it('旧记录异步创建晚返回不打开旧详情，也不关闭新记录的发起表单', async () => {
    const runtime = await mount(true, 'first-record')
    const openLaunch = async () => {
      host.querySelector<HTMLButtonElement>('.page-tasks__header button:last-child')?.click()
      await flush()
    }
    await openLaunch()
    expect(fixture.launches).toHaveLength(1)
    const oldCreated = fixture.launchCreated[0]
    expect(oldCreated).toBeDefined()
    runtime.setRecordId('second-record')
    await flush()
    oldCreated?.('old-created-task')
    await flush()
    expect(fixture.openDetail).not.toHaveBeenCalled()
    expect(fixture.instances[0]?.props.refreshKey).toBe(0)
    await openLaunch()
    expect(fixture.launches).toHaveLength(2)
    oldCreated?.('old-created-task')
    await flush()
    expect(host.querySelector('[data-launch-width]')).not.toBeNull()
    expect(fixture.launches[1]?.initialProject).toMatchObject({ recordId: 'second-record' })
    fixture.launchCreated[1]?.('current-created-task')
    await flush()
    expect(fixture.openDetail).toHaveBeenCalledExactlyOnceWith('current-created-task')
    expect(fixture.instances[0]?.props.context).toMatchObject({ recordId: 'second-record' })
    expect(fixture.instances[0]?.props.refreshKey).toBe(1)
  })
  it('创建成功事件与切换记录同一轮发生时不把旧详情带到新记录', async () => {
    const runtime = await mount(true, 'first-record')
    host.querySelector<HTMLButtonElement>('.page-tasks__header button:last-child')?.click()
    await flush()
    fixture.launchCreated[0]?.('old-created-task')
    runtime.setRecordId('second-record')
    await flush()
    expect(fixture.openDetail).not.toHaveBeenCalled()
    expect(fixture.instances[0]?.props.context).toMatchObject({ recordId: 'second-record' })
  })
  it('应用里创建完成后刷新原范围并打开同一套任务详情', async () => {
    await mount(false, undefined, undefined, null)
    Array.from(host.querySelectorAll<HTMLButtonElement>('button'))
      .find(button => button.textContent?.includes('新建任务'))!
      .click()
    await flush()
    Array.from(host.querySelectorAll<HTMLButtonElement>('button'))
      .find(button => button.textContent?.includes('模拟发起成功'))!
      .click()
    await flush()
    expect(fixture.openDetail).toHaveBeenCalledWith('created-task')
    expect(fixture.instances[0]!.props.refreshKey).toBe(1)
    expect(fixture.instances[0]!.props.context).toMatchObject({ applicationId: 'app', pageId: 'page', nodeId: 'tasks' })
    expect(host.textContent).not.toContain('模拟发起成功')
  })
  it('应用任务列表无需业务表单，发起时仅自动带入所属应用', async () => {
    await mount(false, undefined, undefined, null)
    expect(fixture.instances).toHaveLength(1)
    expect(fixture.instances[0]!.props.context).toMatchObject({ applicationId: 'app', pageId: 'page', nodeId: 'tasks' })
    expect(fixture.instances[0]!.props.businessColumns).toEqual([])
    host.querySelector<HTMLButtonElement>('button')!.click()
    await flush()
    expect(fixture.launches[0]).toMatchObject({
      initialApplicationId: 'app',
      initialApplicationName: '施工应用',
      initialProject: null,
      initialBinding: undefined
    })
    expect(fixture.launches[0]?.footerTarget).toBeInstanceOf(HTMLElement)
    expect(host.contains(fixture.launches[0]?.footerTarget as HTMLElement)).toBe(true)
    expect(host.querySelector('[data-launch-maximizable="true"]')?.getAttribute('data-launch-width')).toBe(
      String(Math.min(1600, Math.floor(window.innerWidth * 0.96)))
    )
  })
  it('记录页面发起带入当前记录，不强迫将当前记录表单作为任务业务数据', async () => {
    await mount(true, 'record')
    const launchButton = Array.from(host.querySelectorAll<HTMLButtonElement>('button')).find(button =>
      button.textContent?.includes('新建任务')
    )!
    launchButton.click()
    await flush()
    expect(fixture.launches[0]).toMatchObject({
      initialApplicationId: 'app',
      initialProject: { applicationId: 'app', objectId: 'work', recordId: 'record', label: '0' },
      initialBinding: undefined
    })
  })
  it('记录页面没有任何表单时，仍按页面记录查询、关联和发起任务', async () => {
    const runtime = await mount(
      true,
      'record-3',
      undefined,
      null,
      resources().filter(item => item.kind === 'PAGE')
    )
    expect(fixture.instances).toHaveLength(1)
    expect(fixture.instances[0]!.props.context).toEqual({
      applicationId: 'app',
      pageId: 'page',
      nodeId: 'tasks',
      recordId: 'record-3'
    })
    expect(fixture.instances[0]!.props.businessColumns).toEqual([])
    const buttons = Array.from(host.querySelectorAll<HTMLButtonElement>('button'))
    buttons.find(button => button.textContent?.includes('关联已有任务'))!.click()
    await flush()
    expect(fixture.links[0]?.context).toEqual(fixture.instances[0]!.props.context)
    buttons.find(button => button.textContent?.includes('新建任务'))!.click()
    await flush()
    expect(runtime.model).toHaveBeenCalledWith('app', 'work')
    expect(runtime.get).toHaveBeenCalledWith('app', 'work', 'record-3')
    expect(fixture.launches[0]).toMatchObject({
      initialProject: { applicationId: 'app', objectId: 'work', recordId: 'record-3', label: '0' },
      initialBinding: undefined
    })
  })
  it('无表单的记录页缺少当前记录时仍关闭查询和发起入口', async () => {
    const runtime = await mount(true, undefined, undefined, null)
    expect(host.textContent).toContain('请先从列表选择一条记录')
    expect(fixture.instances).toHaveLength(0)
    expect(host.textContent).not.toContain('新建任务')
    expect(host.textContent).not.toContain('关联已有任务')
    expect(runtime.get).not.toHaveBeenCalled()
  })
  it('缺少所属页面时不显示列表或关联入口', async () => {
    await mount(true, 'record', undefined, null, [])
    expect(fixture.instances).toHaveLength(0)
    expect(host.textContent).not.toContain('新建任务')
    expect(host.textContent).not.toContain('关联已有任务')
  })
  it('当前记录读取失败时不打开任务发起表单', async () => {
    const runtime = await mount(true, 'record', undefined, null)
    runtime.get.mockRejectedValueOnce(new Error('无权查看当前记录'))
    Array.from(host.querySelectorAll<HTMLButtonElement>('button'))
      .find(button => button.textContent?.includes('新建任务'))!
      .click()
    await flush()
    expect(fixture.launches).toHaveLength(0)
  })
  it('缺少当前记录时不创建列表，不发起可能变成全量范围的任务读取', async () => {
    await mount(true)
    expect(host.textContent).toContain('请先从列表选择一条记录')
    expect(fixture.instances).toHaveLength(0)
    expect(fixture.form).not.toHaveBeenCalled()
  })
  it('换页时丢弃旧业务响应，只显示新页经权限过滤的内容', async () => {
    await mount(false)
    let resolveOld!: (value: unknown) => void
    fixture.form
      .mockImplementationOnce(
        () =>
          new Promise(resolve => {
            resolveOld = resolve
          })
      )
      .mockResolvedValueOnce({
        model,
        record: { record: { values: { quantity: 9, secret: '不应显示' }, permissions: { readFields: ['quantity'] } } }
      })
    const instance = fixture.instances[0]!
    const row = (id: string) => ({ id, business: { recordId: id, object: { objectId: 'work' } } })
    instance.load([row('old')])
    await flush()
    instance.load([row('new')])
    await flush()
    expect(host.textContent).toContain('"new":{"quantity":"9"}')
    expect(host.textContent).not.toContain('secret')
    resolveOld({ model, record: { record: { values: { quantity: 3 } } } })
    await flush()
    expect(host.textContent).not.toContain('"old"')
    expect(host.textContent).toContain('"new"')
  })
  it('项目上下文同时保留当前记录与任务业务表字段', async () => {
    await mount(true, 'project-record', { businessFormId: 'form', columnKeys: ['title', 'business:quantity'] }, null)
    const instance = fixture.instances[0]!
    expect(instance.props.context).toMatchObject({ recordId: 'project-record' })
    expect(instance.props.businessColumns).toEqual([{ key: 'quantity', title: '数量' }])
    fixture.form.mockResolvedValue({ model, record: { record: { values: { quantity: 8 } } } })
    instance.load([{ id: 'task', business: { recordId: 'business-record', object: { objectId: 'work' } } }])
    await flush()
    expect(host.textContent).toContain('"task":{"quantity":"8"}')
  })
})

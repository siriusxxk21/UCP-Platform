// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import dayjs from 'dayjs'
import {
  createApp,
  defineComponent,
  getCurrentInstance,
  h,
  inject,
  nextTick,
  provide,
  type App,
  type Component
} from 'vue'
import { createMemoryHistory, createRouter, RouterView, type Router } from 'vue-router'
import TaskCenter from '@/views/nocode/task-center/index.vue'
import TaskList from '@/views/nocode/task-center/TaskList.vue'
import TaskLaunch from '@/views/nocode/task-center/TaskLaunch.vue'
import { newAutoTaskNode, newTaskNode } from './task-center'
import { taskCenterRetiredQuery, taskCenterViewKey, taskLaunchLocation } from './task-launch-navigation'
import type { TaskCreate, TaskDetail, TaskDraft, TaskNodeInput, TaskRow } from '@/types/nocode/task-center'

const api = vi.hoisted(() => ({
  page: vi.fn(),
  managementPage: vi.fn(),
  personalTreePage: vi.fn(),
  pageTasks: vi.fn(),
  members: vi.fn(),
  entryOptions: vi.fn(),
  templates: vi.fn(),
  templateVersion: vi.fn(),
  templateVersions: vi.fn(),
  schedulePreview: vi.fn(),
  create: vi.fn(),
  draftSave: vi.fn(),
  draftGet: vi.fn(),
  draftPublish: vi.fn(),
  detail: vi.fn(),
  drafts: vi.fn(),
  draftDelete: vi.fn(),
  businessClose: vi.fn(),
  mine: vi.fn(),
  permissions: ['nocode:task:query', 'nocode:task:create', 'nocode:task:template']
}))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({ taskCenter: api, runtime: { mine: api.mine } })
}))
vi.mock('@/utils/access', () => ({ hasPermission: (permission: string) => api.permissions.includes(permission) }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 1 } }) }))
vi.mock('@/api/nocode/task-entry', () => ({ createTaskEntryApi: () => ({ mine: async () => [] }) }))
vi.mock('@/utils/request', () => ({ default: {} }))
vi.mock('ant-design-vue', () => ({ message: { success: vi.fn() }, Modal: { confirm: vi.fn() } }))
vi.mock('@/views/nocode/task-center/TaskDetail.vue', () => ({
  default: defineComponent({
    props: ['id'],
    emits: ['close'],
    setup:
      (props, { emit }) =>
      () =>
        h('div', { 'data-task-detail': props.id }, [h('button', { onClick: () => emit('close') }, '关闭任务详情')])
  })
}))
vi.mock('@/views/nocode/task-center/TaskQuickAction.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskPlanDialog.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskNodeEditor.vue', () => ({
  default: defineComponent({
    props: [
      'modelValue',
      'root',
      'inlineConfiguration',
      'readonly',
      'templateEditing',
      'templateInstance',
      'dataReadonly'
    ],
    emits: ['update:modelValue', 'update:root'],
    setup:
      (props, { emit }) =>
      () =>
        h(
          'div',
          {
            'data-node-editor': true,
            'data-inline': props.inlineConfiguration !== undefined,
            'data-template-instance': String(!!props.templateInstance),
            'data-resource-readonly': String(!!props.dataReadonly)
          },
          [
            h('input', {
              'aria-label': '发起任务名称',
              value: props.root?.title,
              disabled: props.readonly,
              onInput: (event: Event) =>
                emit('update:root', { ...props.root, title: (event.target as HTMLInputElement).value })
            }),
            ...(props.modelValue || []).map((node: TaskNodeInput, index: number) =>
              h('input', {
                'aria-label': `子任务名称 ${index + 1}`,
                value: node.title,
                disabled: props.readonly,
                onInput: (event: Event) =>
                  emit(
                    'update:modelValue',
                    props.modelValue.map((item: TaskNodeInput) =>
                      item.id === node.id ? { ...item, title: (event.target as HTMLInputElement).value } : item
                    )
                  )
              })
            ),
            h(
              'button',
              {
                disabled: props.readonly,
                onClick: () =>
                  emit('update:modelValue', [
                    ...(props.modelValue || []),
                    { ...newTaskNode(), title: '新增分工', parentId: props.root.id }
                  ])
              },
              '添加子任务'
            )
          ]
        )
  })
}))
vi.mock('@/views/nocode/task-center/TaskRecordPicker.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskNodeFields.vue', () => ({
  default: defineComponent({
    props: ['modelValue', 'section'],
    emits: ['update:modelValue'],
    setup:
      (props, { emit, slots }) =>
      () =>
        h('div', { 'data-node-section': props.section || 'all' }, [
          ...(!props.section || props.section === 'all'
            ? [
                h('input', {
                  'aria-label': '发起任务名称',
                  value: props.modelValue.title,
                  onInput: (event: Event) =>
                    emit('update:modelValue', { ...props.modelValue, title: (event.target as HTMLInputElement).value })
                })
              ]
            : []),
          slots['business-context']?.(),
          slots['business-record']?.()
        ])
  })
}))
vi.mock('@/views/nocode/task-center/TaskBusinessForm.vue', () => ({
  default: defineComponent({
    props: ['create'],
    emits: ['created'],
    setup: (props, { expose, emit }) => {
      expose({ requestClose: api.businessClose })
      return () =>
        h(
          'button',
          {
            onClick: async () => {
              const detail = await api.create(
                props.create({ values: { material: '反馈' }, requestKey: 'business-key' })
              )
              emit('created', detail)
            }
          },
          '保存业务并发起'
        )
    }
  })
}))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['dataSource', 'columns', 'pagination'],
    emits: ['change'],
    setup:
      (props, { slots, emit }) =>
      () =>
        h('div', { 'data-page': props.pagination?.current }, [
          slots.search?.(),
          slots.actions?.(),
          slots['advanced-search']?.(),
          h('button', { onClick: () => emit('change', { current: 3, pageSize: 10 }) }, '翻到第三页'),
          ...props.dataSource.map((record: TaskRow) =>
            h(
              'div',
              { 'data-row': record.id },
              props.columns.map((column: { key: string }) => slots.bodyCell?.({ column, record }))
            )
          )
        ])
  })
}))

const row = (): TaskRow => ({
  ...newTaskNode(),
  id: 'task-new',
  rootId: 'task-new',
  title: '新建任务',
  creatorId: 1,
  assigneeId: 1,
  creatorName: '张伟',
  assigneeName: '张伟',
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
  revision: 1,
  instanceRevision: 1,
  childCount: 0,
  plans: [],
  canStart: true,
  canExecute: true,
  canEdit: true,
  blockedReason: null,
  templateId: null,
  templateVersion: null
})
const detail = (): TaskDetail => ({ task: row(), nodes: [row()], comments: [], events: [] })
const template = () => ({
  id: 'template-one',
  name: '施工模板',
  description: '',
  creatorId: 1,
  revision: 3,
  publishedVersion: 2,
  nodes: [{ ...newTaskNode(), title: '模板节点' }],
  updatedAt: ''
})
let app: App | undefined, host: HTMLDivElement, router: Router
const flush = async () => {
  for (let i = 0; i < 25; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const button = (label: string, root: ParentNode = document) => {
  const result = Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find(
    el => el.textContent?.trim() === label
  )
  if (!result) throw new Error(`找不到按钮：${label}`)
  return result
}
const launchWorkspace = () => document.querySelector<HTMLElement>('.task-launch-workspace')
const publishedTemplateSelect = () =>
  present(document.querySelector<HTMLSelectElement>('select[aria-label="已发布模板"]'))
async function chooseTemplate(id: string) {
  const select = publishedTemplateSelect()
  select.value = id
  select.dispatchEvent(new Event('change'))
  await flush()
}
function present<T>(element: T | null | undefined): T {
  if (!element) throw new Error('当前应显示目标元素')
  return element
}
async function input(selector: string, value: string) {
  const element = document.querySelector<HTMLInputElement>(selector)
  if (!element) throw new Error(`找不到输入：${selector}`)
  element.value = value
  element.dispatchEvent(new Event('input'))
  await flush()
}
/** 走真实发起步骤：填写起点、读取服务端预览，再通过确认弹窗提交。 */
async function confirmScheduledLaunch(root: ParentNode = document) {
  const start = root.querySelector<HTMLInputElement>('[aria-label="计划开始日期"]')
  if (start && !start.value) {
    start.value = '2030-02-03'
    start.dispatchEvent(new Event('input'))
    await flush()
  }
  const previewCalls = api.schedulePreview.mock.calls.length
  const createCalls = api.create.mock.calls.length
  const publishCalls = api.draftPublish.mock.calls.length
  button('加入任务池', root).click()
  await flush()
  expect(api.schedulePreview).toHaveBeenCalledTimes(previewCalls + 1)
  expect(api.create).toHaveBeenCalledTimes(createCalls)
  expect(api.draftPublish).toHaveBeenCalledTimes(publishCalls)
  expect(document.querySelector('[aria-label="整组排期预览"]')).not.toBeNull()
  button('确认并加入任务池').click()
  await flush()
}
async function discardLaunch() {
  button('返回', launchWorkspace() || document).click()
  await flush()
  const discard = Array.from(document.querySelectorAll('button')).find(el => el.textContent?.trim() === '放弃修改')
  discard?.click()
  await flush()
}
async function mount(path: string, component: Component = TaskCenter, props: Record<string, unknown> = {}) {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/nocode-app/task-center/launch', redirect: to => taskLaunchLocation(to.query, to.hash) },
      ...['/nocode-app/task-center', '/nocode-app/task-center/manage', '/nocode-app/task-center/templates'].map(
        path => ({
          path,
          component,
          props
        })
      ),
      { path: '/elsewhere', component: { render: () => h('div', '其他页面') } }
    ]
  })
  await router.push(path)
  await router.isReady()
  app = createApp(() =>
    h(RouterView, null, {
      default: ({ Component }: { Component: Component }) =>
        h(Component, { key: taskCenterViewKey(router.currentRoute.value) })
    })
  )
  app.use(router)
  const plain = defineComponent({
    props: ['label', 'message'],
    setup: (props, { slots, expose }) => {
      expose({ validate: async () => true, resetFields: vi.fn() })
      return () => h('div', { 'data-label': props.label }, [props.label, props.message, slots.default?.()])
    }
  })
  for (const name of [
    'AForm',
    'AFormItem',
    'ATag',
    'ASpace',
    'AEmpty',
    'AAlert',
    'ATooltip',
    'ACollapse',
    'ACollapsePanel',
    'AMenu',
    'AMenuItem',
    'ADropdown'
  ])
    app.component(name, plain)
  app.component(
    'ATabs',
    defineComponent({
      props: ['activeKey'],
      emits: ['change', 'update:activeKey'],
      setup: (props, { emit, slots }) => {
        provide('task-tabs', {
          active: () => props.activeKey,
          change: (value: unknown) => {
            emit('update:activeKey', value)
            emit('change', value)
          }
        })
        return () => h('div', slots.default?.())
      }
    })
  )
  app.component(
    'ATabPane',
    defineComponent({
      props: ['tab'],
      setup: (props, { slots }) => {
        const tabs = inject<{ active: () => unknown; change: (value: unknown) => void }>('task-tabs')
        const key = getCurrentInstance()?.vnode.key
        return () =>
          h('div', [
            h('button', { onClick: () => tabs?.change(key), 'aria-selected': tabs?.active() === key }, props.tab),
            tabs?.active() === key ? slots.default?.() : null
          ])
      }
    })
  )
  app.component(
    'ADrawer',
    defineComponent({
      props: ['open', 'title'],
      emits: ['close'],
      setup:
        (props, { emit, slots }) =>
        () =>
          props.open
            ? h('section', { 'data-drawer': props.title }, [
                h('button', { onClick: () => emit('close') }, `关闭${props.title}`),
                slots.default?.(),
                slots.footer?.()
              ])
            : null
    })
  )
  app.component(
    'AModal',
    defineComponent({
      props: ['open'],
      setup:
        (props, { slots }) =>
        () =>
          props.open
            ? h('section', { 'data-modal': true }, [slots.title?.(), slots.default?.(), slots.footer?.()])
            : null
    })
  )
  app.component(
    'AButton',
    defineComponent({
      props: ['disabled', 'loading'],
      setup:
        (props, { slots }) =>
        () =>
          h('button', { disabled: props.disabled || props.loading }, slots.default?.())
    })
  )
  const radio = Symbol('radio')
  app.component(
    'ARadioGroup',
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup: (_, { emit, slots }) => {
        provide(radio, (value: unknown) => emit('update:value', value))
        return () => h('div', slots.default?.())
      }
    })
  )
  const radioButton = defineComponent({
    props: ['value'],
    setup: (props, { slots }) => {
      const select = inject<(value: unknown) => void>(radio)
      return () => h('button', { onClick: () => select?.(props.value) }, slots.default?.())
    }
  })
  app.component('ARadioButton', radioButton)
  app.component('ARadio', radioButton)
  app.component(
    'ASelect',
    defineComponent({
      props: ['value', 'options'],
      emits: ['update:value', 'change'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'select',
            {
              value: props.value,
              onChange: (event: Event) => {
                const target = event.target as HTMLSelectElement
                const value = target.value
                emit('update:value', value)
                emit('change', value)
                // 模拟受控选择器：父组件未接受变更时仍显示原值。
                void nextTick(() => (target.value = props.value || ''))
              }
            },
            (props.options || []).map((option: { value: string; label: string }) =>
              h('option', { value: option.value }, option.label)
            )
          )
    })
  )
  const inputComponent = defineComponent({
    props: ['value'],
    emits: ['update:value', 'pressEnter'],
    setup: (props, { emit, expose }) => {
      expose({ focus: vi.fn() })
      return () =>
        h('input', {
          value: props.value,
          onInput: (event: Event) => emit('update:value', (event.target as HTMLInputElement).value),
          onKeydown: (event: KeyboardEvent) => {
            if (event.key === 'Enter') emit('pressEnter')
          }
        })
    }
  })
  for (const name of ['AInput', 'ATextarea', 'ADatePicker', 'AInputNumber', 'ACheckbox'])
    app.component(name, inputComponent)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
}
beforeEach(() => {
  api.personalTreePage.mockImplementation((query: unknown) => api.page(query))
  vi.clearAllMocks()
  api.permissions = ['nocode:task:query', 'nocode:task:create', 'nocode:task:template']
  api.page.mockResolvedValue({ list: [], total: 50 })
  api.managementPage.mockResolvedValue({ list: [], total: 50 })
  api.pageTasks.mockResolvedValue({ list: [], total: 0 })
  api.members.mockResolvedValue([])
  api.mine.mockResolvedValue([])
  api.entryOptions.mockResolvedValue([])
  api.templates.mockResolvedValue([template()])
  api.templateVersions.mockResolvedValue([])
  api.templateVersion.mockResolvedValue({ ...template(), version: 2 })
  api.schedulePreview.mockImplementation(async ({ nodes }: { nodes: TaskNodeInput[] }) => ({
    nodes: nodes.map(node => ({
      id: node.id,
      title: node.title,
      expectedStart: null,
      expectedEnd: null,
      partial: false,
      warnings: []
    })),
    warnings: []
  }))
  api.create.mockResolvedValue(detail())
  api.detail.mockResolvedValue(detail())
  api.drafts.mockResolvedValue([])
  api.draftSave.mockImplementation(async (body: { id: string; expectedRevision: number; content: TaskCreate }) => ({
    id: body.id,
    revision: body.expectedRevision + 1,
    content: JSON.parse(JSON.stringify(body.content)),
    title: body.content.task.title,
    updatedAt: '2030-01-01'
  }))
  api.draftPublish.mockResolvedValue(detail())
  api.businessClose.mockResolvedValue(true)
})
afterEach(() => {
  app?.unmount()
  host?.remove()
})

describe('统一发起入口与列表上下文', () => {
  it.each([false, true])('新发起默认今天，直接预览与提交保持同一日期，使用模板=%s', async fromTemplate => {
    api.templateVersion.mockResolvedValue({ ...template(), task: newAutoTaskNode(), version: 2 })
    await mount('/nocode-app/task-center/manage', TaskLaunch, {
      embedded: true,
      ...(fromTemplate ? { initialTemplateId: 'template-one' } : {})
    })
    const today = dayjs().format('YYYY-MM-DD')
    expect(document.querySelector<HTMLInputElement>('[aria-label="计划开始日期"]')?.value).toBe(today)
    await input('[aria-label="发起任务名称"]', '按计划开始')
    await confirmScheduledLaunch()
    expect(api.schedulePreview).toHaveBeenCalledWith(expect.objectContaining({ plannedStart: today }))
    expect(api.create).toHaveBeenCalledWith(expect.objectContaining({ plannedStart: today }))
  })
  it('选择模板不清空已选日期，重新新建时默认今天', async () => {
    api.templateVersion.mockResolvedValue({ ...template(), task: newAutoTaskNode(), version: 2 })
    await mount('/nocode-app/task-center/manage', TaskLaunch, { embedded: true })
    await input('[aria-label="计划开始日期"]', '2030-02-03')
    button('从模板新建').click()
    await flush()
    await chooseTemplate('template-one')
    button('更换模板').click()
    await flush()
    expect(document.querySelector<HTMLInputElement>('[aria-label="计划开始日期"]')?.value).toBe('2030-02-03')
    button('新建任务').click()
    await flush()
    button('重新新建').click()
    await flush()
    expect(document.querySelector<HTMLInputElement>('[aria-label="计划开始日期"]')?.value).toBe(
      dayjs().format('YYYY-MM-DD')
    )
  })
  it.each([null, '2030-02-03T08:15:30'])('恢复草稿不以默认今天覆盖原计划日期 %s', async plannedStart => {
    api.draftGet.mockResolvedValue({
      id: 'date-draft',
      revision: 1,
      content: { task: { ...newAutoTaskNode(), title: '原草稿' }, plannedStart, requestKey: 'date-draft-key' }
    })
    await mount('/nocode-app/task-center/manage', TaskLaunch, { embedded: true, draftId: 'date-draft' })
    expect(document.querySelector<HTMLInputElement>('[aria-label="计划开始日期"]')?.value).toBe(
      plannedStart?.slice(0, 10) || ''
    )
    button('保存草稿').click()
    await flush()
    expect(present(api.draftSave.mock.calls[0])[0].content.plannedStart).toBe(plannedStart)
  })
  it('普通员工不显示管理捷径，有创建或管理权限时仍可从我的任务进入管理', async () => {
    api.permissions = ['nocode:task:query']
    await mount('/nocode-app/task-center')
    expect(Array.from(host.querySelectorAll('button')).some(el => el.textContent?.trim() === '任务管理')).toBe(false)
    expect(host.textContent).not.toContain('任务池与任务草稿')
  })
  it('有创建权限保留名称清晰的任务管理捷径', async () => {
    await mount('/nocode-app/task-center')
    expect(button('任务管理')).toBeDefined()
  })
  it('管理页面我的草稿可继续编辑或确认删除，不混入正式任务列表', async () => {
    api.drafts.mockResolvedValue([{ id: 'draft-mine', revision: 7, title: '本人未发布草稿', updatedAt: '2030-01-01' }])
    api.draftGet.mockResolvedValue({
      id: 'draft-mine',
      revision: 7,
      content: { task: { ...newTaskNode(), title: '本人未发布草稿' }, requestKey: 'draft-request' }
    })
    await mount('/nocode-app/task-center/manage')
    expect(host.textContent).not.toContain('本人未发布草稿')
    button('我的草稿').click()
    await flush()
    expect(host.textContent).toContain('本人未发布草稿')
    button('继续编辑').click()
    await flush()
    expect(api.draftGet).toHaveBeenCalledWith('draft-mine')
    expect(launchWorkspace()).not.toBeNull()
    expect(document.querySelector('[data-drawer="编辑任务草稿"]')).toBeNull()
    button('返回', present(launchWorkspace())).click()
    await flush()
    button('我的草稿').click()
    await flush()
    button('删除').click()
    await flush()
    button('删除草稿').click()
    await flush()
    expect(api.draftDelete).toHaveBeenCalledWith('draft-mine', 7)
    expect(api.create).not.toHaveBeenCalled()
    expect(api.draftPublish).not.toHaveBeenCalled()
  })
  it('新建默认开放领取和跟随任务顺序；首次草稿断网重试复用 ID 和版本，不创建正式任务', async () => {
    await mount('/nocode-app/task-center/manage', TaskLaunch, { embedded: true })
    await input('[aria-label="发起任务名称"]', '可以稍后补充的任务')
    api.draftSave.mockRejectedValueOnce(new Error('网络中断'))
    button('保存草稿').click()
    await flush()
    const first = present(api.draftSave.mock.calls[0])[0]
    expect(first).toMatchObject({
      expectedRevision: 0,
      content: {
        task: { assignmentMode: 'OPEN', assigneeId: null, candidateUserIds: [], schedule: { mode: 'AUTO' } },
        nodes: null
      }
    })
    expect(document.querySelector<HTMLInputElement>('[aria-label="发起任务名称"]')?.value).toBe('可以稍后补充的任务')
    button('保存草稿').click()
    await flush()
    expect(present(api.draftSave.mock.calls[1])[0]).toEqual(first)
    expect(api.schedulePreview).not.toHaveBeenCalled()
    expect(api.create).not.toHaveBeenCalled()
    expect(api.draftPublish).not.toHaveBeenCalled()
  })
  it('完整草稿恢复保留已有业务记录与子任务；业务binding watcher不误清空已选记录', async () => {
    const content: TaskCreate = {
      task: {
        ...newTaskNode(),
        id: 'draft-root',
        title: '项目计划',
        binding: { applicationId: 'app', formId: 'form', entryId: null }
      },
      nodes: [{ ...newTaskNode(), id: 'child', title: '子任务' }],
      applicationId: 'app',
      project: { applicationId: 'app', objectId: 'project', recordId: 'p1', label: '项目甲' },
      existingRecord: { applicationId: 'app', objectId: 'content', recordId: 'r1', label: '施工内容甲' },
      plannedStart: '2030-02-03T08:00:00',
      requestKey: 'stable-publish-key'
    }
    api.draftGet.mockResolvedValue({
      id: 'draft',
      revision: 3,
      content,
      title: '项目计划',
      updatedAt: ''
    } satisfies TaskDraft)
    await mount('/nocode-app/task-center/manage', TaskLaunch, { embedded: true, draftId: 'draft' })
    expect(document.querySelector<HTMLInputElement>('[aria-label="发起任务名称"]')?.value).toBe('项目计划')
    button('保存草稿').click()
    await flush()
    expect(api.draftSave).toHaveBeenLastCalledWith({
      id: 'draft',
      expectedRevision: 3,
      content: expect.objectContaining(content)
    })
    expect(api.create).not.toHaveBeenCalled()
  })
  it('恢复模板草稿使用保存的固定版本，不自动升级到最新发布版', async () => {
    api.draftGet.mockResolvedValue({
      id: 'draft',
      revision: 1,
      title: '旧模板任务',
      updatedAt: '',
      content: {
        task: { ...newTaskNode(), title: '旧模板任务' },
        templateId: 'template-one',
        templateVersion: 1,
        requestKey: 'fixed-version'
      }
    })
    api.templateVersion.mockResolvedValue({ ...template(), version: 1 })
    await mount('/nocode-app/task-center/manage', TaskLaunch, { embedded: true, draftId: 'draft' })
    expect(api.templateVersion).toHaveBeenCalledWith('template-one', 1)
    expect(api.templateVersion).not.toHaveBeenCalledWith('template-one', 2)
    await confirmScheduledLaunch()
    expect(present(api.draftSave.mock.calls[0])[0].content).toMatchObject({ templateVersion: 1 })
    expect(api.draftPublish).toHaveBeenCalledWith({ id: 'draft', expectedRevision: 2, requestKey: 'fixed-version' })
    expect(api.create).not.toHaveBeenCalled()
  })
  it.each([new Error('回包丢失'), Object.assign(new Error('网关502'), { response: { status: 502 } })])(
    '发布结果未知时只重试原版本原键，不保存已发布草稿：%s',
    async failure => {
      api.draftGet.mockResolvedValue({
        id: 'draft',
        revision: 4,
        content: { task: { ...newTaskNode(), title: '已保存' }, requestKey: 'same-key' }
      })
      api.draftPublish.mockRejectedValueOnce(failure).mockResolvedValueOnce(detail())
      await mount('/nocode-app/task-center/manage', TaskLaunch, { embedded: true, draftId: 'draft' })
      await confirmScheduledLaunch()
      expect(api.draftSave).toHaveBeenCalledTimes(1)
      const attempted = present(api.draftPublish.mock.calls[0])[0]
      button('加入任务池').click()
      await flush()
      expect(present(api.draftPublish.mock.calls[1])[0]).toEqual(attempted)
      expect(api.draftSave).toHaveBeenCalledTimes(1)
      expect(api.schedulePreview).toHaveBeenCalledTimes(1)
      expect(api.create).not.toHaveBeenCalled()
    }
  )
  it('重新打开已发布草稿定位原任务，不再保存或创建', async () => {
    const created = vi.fn()
    api.draftGet.mockResolvedValue({
      id: 'draft',
      revision: 5,
      publishedTaskId: 'published',
      content: { task: newTaskNode(), requestKey: 'same-key' }
    })
    await mount('/nocode-app/task-center/manage', TaskLaunch, { embedded: true, draftId: 'draft', onCreated: created })
    expect(api.detail).toHaveBeenCalledWith('published')
    expect(created).toHaveBeenCalledWith(detail())
    expect(api.draftSave).not.toHaveBeenCalled()
    expect(api.create).not.toHaveBeenCalled()
  })
  it.each([
    ['/nocode-app/task-center', {}, TaskCenter],
    ['/nocode-app/task-center/manage', { scope: 'MANAGE', embedded: true }, TaskList]
  ])('我的任务与嵌入任务区块没有常规发起按钮：%s', async (path, props, component) => {
    await mount(path as string, component as Component, props as Record<string, unknown>)
    expect(Array.from(host.querySelectorAll('button')).some(el => el.textContent?.trim() === '新建任务')).toBe(false)
  })

  it('管理列表取消和成功都保留已提交条件、草稿搜索与第三页；不匹配的新任务仍定位详情', async () => {
    await mount('/nocode-app/task-center/manage')
    const status = present(host.querySelector<HTMLSelectElement>('[aria-label="任务状态筛选"]'))
    status.value = 'RUNNING'
    status.dispatchEvent(new Event('change'))
    await flush()
    await input('input[placeholder="搜索总任务或子任务名称"]', '施工')
    button('查询', host).click()
    await flush()
    button('翻到第三页', host).click()
    await flush()
    await input('input[placeholder="搜索总任务或子任务名称"]', '尚未查询的输入')
    const retainedList = present(host.querySelector<HTMLElement>('.task-list__content'))
    const pageCalls = api.managementPage.mock.calls.length
    button('新建任务', host).click()
    await flush()
    expect(retainedList.style.display).toBe('none')
    expect(document.querySelector('[data-drawer="新建任务"]')).toBeNull()
    await input('[aria-label="发起任务名称"]', '未提交工作')
    button('返回', present(launchWorkspace())).click()
    await flush()
    button('继续编辑').click()
    await flush()
    expect(document.querySelector<HTMLInputElement>('[aria-label="发起任务名称"]')?.value).toBe('未提交工作')
    await discardLaunch()
    expect(launchWorkspace()).toBeNull()
    expect(host.querySelector('.task-list__content')).toBe(retainedList)
    expect(retainedList.style.display).not.toBe('none')
    expect(api.managementPage).toHaveBeenCalledTimes(pageCalls)
    expect(host.querySelector<HTMLInputElement>('input[placeholder="搜索总任务或子任务名称"]')?.value).toBe(
      '尚未查询的输入'
    )
    expect(host.querySelector('[data-page="3"]')).not.toBeNull()
    button('新建任务', host).click()
    await flush()
    await input('[aria-label="发起任务名称"]', '不匹配施工筛选的新任务')
    await confirmScheduledLaunch(present(launchWorkspace()))
    expect(api.create).toHaveBeenCalledWith(
      expect.objectContaining({ task: expect.objectContaining({ title: '不匹配施工筛选的新任务' }) })
    )
    expect(api.managementPage.mock.calls.at(-1)?.[0]).toMatchObject({
      focus: 'ALL',
      query: { search: '施工', status: 'RUNNING', pageNo: 3 }
    })
    expect(api.page).not.toHaveBeenCalled()
    expect(host.querySelector('[data-task-detail="task-new"]')).not.toBeNull()
    expect(host.querySelector<HTMLInputElement>('input[placeholder="搜索总任务或子任务名称"]')?.value).toBe(
      '尚未查询的输入'
    )
    expect(router.currentRoute.value.fullPath).toBe('/nocode-app/task-center/manage')
  })

  it('管理工作区可从模板发起，使用发布固定版本及其可编辑节点', async () => {
    await mount('/nocode-app/task-center/manage')
    button('新建任务', host).click()
    await flush()
    button('从模板新建').click()
    await flush()
    const select = publishedTemplateSelect()
    select.value = 'template-one'
    select.dispatchEvent(new Event('change'))
    await flush()
    expect(api.templateVersion).toHaveBeenCalledWith('template-one', undefined)
    await confirmScheduledLaunch(present(launchWorkspace()))
    expect(api.create).toHaveBeenCalledWith(
      expect.objectContaining({
        templateId: 'template-one',
        templateVersion: 2,
        nodes: [expect.objectContaining({ title: '模板节点' })]
      })
    )
    expect(present(api.create.mock.calls[0])[0].task.dataPolicy).toBeUndefined()
  })
  it('新视图办理项未配置工时可保存草稿和发起，自动预算留空不阻塞', async () => {
    const sourceRoot = { ...newTaskNode(), title: '未配置标准工时任务' }
    sourceRoot.workTotalMode = 'AUTO'
    sourceRoot.entries = [
      {
        key: 'room',
        name: '房间办理',
        binding: { applicationId: 'app', formId: 'form', viewId: 'view', entryId: null },
        dataMode: 'ROOT_SHARED',
        required: false,
        allowAll: false,
        readableFieldIds: null,
        writableFieldIds: null,
        sourceNodeId: null,
        sourceEntryKey: null
      }
    ]
    api.templateVersion.mockResolvedValue({ ...template(), task: sourceRoot, nodes: [], version: 2 })
    await mount('/nocode-app/task-center/manage')
    button('新建任务', host).click()
    await flush()
    button('从模板新建').click()
    await flush()
    const select = publishedTemplateSelect()
    select.value = 'template-one'
    select.dispatchEvent(new Event('change'))
    await flush()
    const workspace = present(launchWorkspace())
    button('保存草稿', workspace).click()
    await flush()
    expect(api.draftSave).toHaveBeenCalledOnce()
    expect(present(api.draftSave.mock.calls[0])[0].content.task.entries[0].binding.viewId).toBe('view')
    await confirmScheduledLaunch(workspace)
    expect(api.draftPublish).toHaveBeenCalledOnce()
    expect(present(api.draftSave.mock.calls[1])[0].content.task.entries[0].workRule).toBeUndefined()
    expect(host.textContent).not.toContain('请设置标准工时')
  })
  it('直接新建首先显示可编辑总任务行，业务配置统一一个页签，不重复展示长基本表单', async () => {
    await mount('/nocode-app/task-center/manage')
    button('新建任务', host).click()
    await flush()
    const workspace = present(launchWorkspace())
    expect(workspace.querySelector('[data-node-editor][data-inline="true"]')).not.toBeNull()
    expect(workspace.querySelectorAll('[aria-label="发起任务名称"]')).toHaveLength(1)
    expect(workspace.querySelector('[data-node-section="all"]')).toBeNull()
    expect(Array.from(workspace.querySelectorAll('button')).some(item => item.textContent === '任务实例')).toBe(false)
    await input('[aria-label="发起任务名称"]', '办公室采购')
    button('业务关联', workspace).click()
    await flush()
    expect(workspace.querySelector('[data-node-section="business"]')).not.toBeNull()
    expect(workspace.querySelector('[data-node-editor]')).toBeNull()
    expect(Array.from(workspace.querySelectorAll('button')).some(item => item.textContent === '过程反馈')).toBe(false)
    expect(workspace.querySelector('[data-node-section="feedback"]')).toBeNull()
    button('任务编排', workspace).click()
    await flush()
    expect(workspace.querySelector<HTMLInputElement>('[aria-label="发起任务名称"]')?.value).toBe('办公室采购')
    await confirmScheduledLaunch(workspace)
    expect(api.create).toHaveBeenCalledWith(
      expect.objectContaining({ task: expect.objectContaining({ title: '办公室采购' }), templateId: null })
    )
  })
  it('纯排期预览只提供返回编排，关闭后保留全部输入且不创建或发布任务', async () => {
    await mount('/nocode-app/task-center/manage')
    button('新建任务', host).click()
    await flush()
    const workspace = present(launchWorkspace())
    await input('[aria-label="发起任务名称"]', '只查看采购排期')
    button('添加子任务', workspace).click()
    await flush()
    await input('[aria-label="子任务名称 1"]', '保留采购分工')
    await input('[aria-label="计划开始日期"]', '2030-02-03')
    button('预览排期', workspace).click()
    await flush()
    expect(api.schedulePreview).toHaveBeenCalledOnce()
    expect(api.schedulePreview).toHaveBeenCalledWith({
      nodes: [
        expect.objectContaining({ title: '只查看采购排期', schedule: expect.objectContaining({ mode: 'AUTO' }) }),
        expect.objectContaining({ title: '保留采购分工' })
      ],
      plannedStart: '2030-02-03'
    })
    const preview = present(document.querySelector('[aria-label="整组排期预览"]'))
    const modal = present(preview.closest('[data-modal]'))
    expect(modal.querySelector('.os-modal-form-modal-title')?.textContent).toBe('整组排期预览')
    expect(preview.querySelector('input, textarea, select')).toBeNull()
    expect(preview.textContent).toContain('只查看采购排期')
    expect(preview.textContent).toContain('保留采购分工')
    const footerLabels = Array.from(modal.querySelectorAll('button'))
      .filter(item => !item.closest('.os-modal-form-title-bar') && !preview.contains(item))
      .map(item => item.textContent?.trim())
    expect(footerLabels).toEqual(['返回编排'])
    expect(api.create).not.toHaveBeenCalled()
    expect(api.draftPublish).not.toHaveBeenCalled()
    expect(api.draftSave).not.toHaveBeenCalled()
    button('返回编排', modal).click()
    await flush()
    expect(document.querySelector('[aria-label="整组排期预览"]')).toBeNull()
    expect(launchWorkspace()).toBe(workspace)
    expect(workspace.querySelector<HTMLInputElement>('[aria-label="发起任务名称"]')?.value).toBe('只查看采购排期')
    expect(workspace.querySelector<HTMLInputElement>('[aria-label="子任务名称 1"]')?.value).toBe('保留采购分工')
    expect(workspace.querySelector<HTMLInputElement>('[aria-label="计划开始日期"]')?.value).toBe('2030-02-03')
    expect(api.create).not.toHaveBeenCalled()
    expect(api.draftPublish).not.toHaveBeenCalled()
    expect(api.draftSave).not.toHaveBeenCalled()
  })
  it.each([
    ['/nocode-app/task-center/manage', '新建任务'],
    ['/nocode-app/task-center/templates', '使用模板']
  ])('在 %s 保存草稿后留在工作区，可继续编排并发布同一草稿', async (path, openLabel) => {
    await mount(path)
    button(openLabel).click()
    await flush()
    await input('[aria-label="发起任务名称"]', '保存后继续安排')
    const workspace = present(launchWorkspace())
    button('保存草稿', workspace).click()
    await flush()
    expect(launchWorkspace()).toBe(workspace)
    expect(api.draftSave).toHaveBeenCalledTimes(1)
    expect(api.create).not.toHaveBeenCalled()
    const saved = present(api.draftSave.mock.calls[0])[0]
    button('添加子任务', workspace).click()
    await flush()
    await confirmScheduledLaunch(workspace)
    expect(api.draftSave).toHaveBeenCalledTimes(2)
    expect(present(api.draftSave.mock.calls[1])[0]).toMatchObject({ id: saved.id, expectedRevision: 1 })
    expect(api.draftPublish).toHaveBeenCalledWith({
      id: saved.id,
      expectedRevision: 2,
      requestKey: saved.content.requestKey
    })
    expect(api.create).not.toHaveBeenCalled()
    expect(launchWorkspace()).toBeNull()
    expect(host.querySelector('[data-task-detail="task-new"]')).not.toBeNull()
  })
  it('发起顶部按创建方式和模板选择分组，来源说明独立且保存操作仍在标题栏', async () => {
    await mount('/nocode-app/task-center/manage', TaskLaunch, {
      workspace: true,
      initialTemplateId: 'template-one'
    })
    const source = present(host.querySelector('[aria-label="任务创建设置"]'))
    const controls = present(source.querySelector('[aria-label="创建方式与模板选择"]'))
    const note = present(source.querySelector('[aria-label="模板来源说明"]'))
    expect(controls.querySelector('[aria-label="创建方式"]')).not.toBeNull()
    expect(controls.querySelector('[aria-label="已发布模板"]')).not.toBeNull()
    expect(controls.querySelector('[aria-label="模板版本"]')).not.toBeNull()
    expect(controls.contains(note)).toBe(false)
    expect(note.textContent).toContain('来源：施工模板 · v2')
    expect(note.textContent).toContain('本次调整不会修改模板')
    expect(note.querySelector('[aria-label="模板有效工作时长"]')).toBeNull()
    const header = present(host.querySelector('.task-launch__header'))
    expect(button('保存草稿', header).disabled).toBe(false)
    expect(button('加入任务池', header).disabled).toBe(false)
    expect(api.draftSave).not.toHaveBeenCalled()
    expect(api.create).not.toHaveBeenCalled()
  })
  it('直接新建仍显示创建方式，不保留模板说明或空选择框', async () => {
    await mount('/nocode-app/task-center/manage', TaskLaunch, { embedded: true })
    const source = present(host.querySelector('[aria-label="任务创建设置"]'))
    expect(source.querySelector('[aria-label="创建方式"]')).not.toBeNull()
    expect(source.querySelector('[aria-label="已发布模板"]')).toBeNull()
    expect(source.querySelector('[aria-label="模板来源说明"]')).toBeNull()
  })
  it('模板载入后同样可编辑总任务与子任务；保存仅带固定来源版本和本次实例节点', async () => {
    const version = {
      ...template(),
      version: 2,
      task: { ...newTaskNode(), id: 'template-root', title: '模板总任务' },
      nodes: [{ ...newTaskNode(), id: 'template-child', parentId: 'template-root', title: '模板子任务' }]
    }
    api.templateVersion.mockResolvedValue(version)
    await mount('/nocode-app/task-center/manage', TaskLaunch, { embedded: true, initialTemplateId: 'template-one' })
    await input('[aria-label="发起任务名称"]', '本次施工任务')
    await input('[aria-label="子任务名称 1"]', '本次施工分工')
    button('添加子任务').click()
    await flush()
    button('保存草稿').click()
    await flush()
    const command = present(api.draftSave.mock.calls[0])[0].content
    expect(command).toMatchObject({
      templateId: 'template-one',
      templateVersion: 2,
      task: { title: '本次施工任务' },
      nodes: [
        { title: '本次施工分工', parentId: null },
        { title: '新增分工', parentId: null }
      ]
    })
    expect(version.task.title).toBe('模板总任务')
    expect(present(version.nodes[0]).title).toBe('模板子任务')
    expect(command.nodes.every((item: TaskNodeInput) => item.id !== command.task.id)).toBe(true)
  })
  it('初始指定模板首次读取失败可原位重试，成功后恢复本次可编辑编排', async () => {
    api.templateVersion
      .mockRejectedValueOnce(new Error('模板详情暂时不可用'))
      .mockResolvedValueOnce({ ...template(), version: 2 })
    await mount('/nocode-app/task-center/manage', TaskLaunch, { embedded: true, initialTemplateId: 'template-one' })
    expect(host.textContent).toContain('模板详情暂时不可用')
    expect(present(host.querySelector<HTMLInputElement>('[aria-label="发起任务名称"]')).disabled).toBe(true)
    button('重试加载模板').click()
    await flush()
    expect(api.templateVersion).toHaveBeenCalledTimes(2)
    expect(api.templateVersion).toHaveBeenLastCalledWith('template-one', undefined)
    expect(host.textContent).not.toContain('模板详情暂时不可用')
    expect(host.textContent).not.toContain('重试加载模板')
    expect(present(host.querySelector<HTMLInputElement>('[aria-label="发起任务名称"]')).disabled).toBe(false)
    expect(host.querySelector<HTMLInputElement>('[aria-label="子任务名称 1"]')?.value).toBe('模板节点')
    button('保存草稿').click()
    await flush()
    expect(present(api.draftSave.mock.calls[0])[0].content).toMatchObject({
      templateId: 'template-one',
      templateVersion: 2,
      nodes: [{ title: '模板节点' }]
    })
  })
  it('旧无总任务默认配置的模板仍传递实例模式，子项资源不能随总任务解锁', async () => {
    api.templateVersion.mockResolvedValue({ ...template(), version: 2 })
    await mount('/nocode-app/task-center/manage', TaskLaunch, { embedded: true, initialTemplateId: 'template-one' })
    const editor = present(host.querySelector<HTMLElement>('[data-node-editor]'))
    expect(editor.dataset.templateInstance).toBe('true')
    expect(editor.dataset.resourceReadonly).toBe('false')
    expect(present(editor.querySelector<HTMLInputElement>('[aria-label="发起任务名称"]')).disabled).toBe(false)
    await input('[aria-label="子任务名称 1"]', '本次可调整工作名称')
    button('保存草稿').click()
    await flush()
    expect(present(api.draftSave.mock.calls[0])[0].content.nodes).toEqual([
      expect.objectContaining({ title: '本次可调整工作名称' })
    ])
  })
  it('旧模板草稿本地根 ID 恢复为来源根，直接子项归根且共享反馈引用保持有效', async () => {
    const sourceRoot = { ...newTaskNode(), id: 'source-root', title: '模板总任务' }
    const child: TaskNodeInput = {
      ...newTaskNode(),
      id: 'source-child',
      parentId: 'local-draft-root',
      title: '本次节点',
      sharing: { mode: 'SHARED', sourceNodeId: sourceRoot.id, writableFieldIds: [] },
      entries: [
        {
          key: 'child-log',
          name: '共享总任务日志',
          binding: null,
          dataMode: 'SOURCE_SHARED',
          sourceNodeId: sourceRoot.id,
          sourceEntryKey: 'root-log',
          readableFieldIds: null,
          writableFieldIds: null,
          required: false,
          allowAll: false
        }
      ]
    }
    api.templateVersion.mockResolvedValue({ ...template(), task: sourceRoot, nodes: [], version: 1 })
    api.draftGet.mockResolvedValue({
      id: 'legacy-draft',
      revision: 4,
      content: {
        task: { ...sourceRoot, id: 'local-draft-root', title: '已调整的总任务' },
        nodes: [child],
        templateId: 'template-one',
        templateVersion: 1,
        requestKey: 'root-restore-key'
      }
    })
    await mount('/nocode-app/task-center/manage', TaskLaunch, { embedded: true, draftId: 'legacy-draft' })
    button('保存草稿').click()
    await flush()
    const saved = present(api.draftSave.mock.calls[0])[0].content as TaskCreate
    expect(saved.task).toMatchObject({ id: sourceRoot.id, title: '已调整的总任务' })
    expect(saved.nodes).toEqual([expect.objectContaining({ id: child.id, parentId: null })])
    const savedChild = present(saved.nodes?.[0])
    expect(savedChild.sharing.sourceNodeId).toBe(saved.task.id)
    expect(savedChild.entries?.[0]?.sourceNodeId).toBe(saved.task.id)
    expect(child.parentId).toBe('local-draft-root')
    expect(sourceRoot.title).toBe('模板总任务')
    expect(saved.templateVersion).toBe(1)
  })
  it.each([null, undefined])('历史模板草稿 nodes=%s 时恢复固定版节点，不静默变成空任务', async nodes => {
    api.draftGet.mockResolvedValue({
      id: 'draft',
      revision: 3,
      content: {
        task: { ...newTaskNode(), id: 'draft-root', title: '历史草稿' },
        nodes,
        templateId: 'template-one',
        templateVersion: 1,
        requestKey: 'legacy-fixed-key'
      }
    })
    api.templateVersion.mockResolvedValue({
      ...template(),
      task: { ...newTaskNode(), id: 'old-template-root' },
      nodes: [{ ...newTaskNode(), id: 'old-child', parentId: 'old-template-root', title: '旧版施工步骤' }],
      version: 1
    })
    await mount('/nocode-app/task-center/manage', TaskLaunch, { embedded: true, draftId: 'draft' })
    expect(document.querySelector<HTMLInputElement>('[aria-label="子任务名称 1"]')?.value).toBe('旧版施工步骤')
    button('保存草稿').click()
    await flush()
    expect(present(api.draftSave.mock.calls[0])[0].content).toMatchObject({
      templateVersion: 1,
      nodes: [{ title: '旧版施工步骤', parentId: null }]
    })
  })
  it('模板草稿显式保存 nodes=[] 时保持用户删除结果，不从模板回填节点', async () => {
    api.draftGet.mockResolvedValue({
      id: 'draft',
      revision: 3,
      content: {
        task: { ...newTaskNode(), title: '只保留总任务' },
        nodes: [],
        templateId: 'template-one',
        templateVersion: 1,
        requestKey: 'empty-nodes-key'
      }
    })
    api.templateVersion.mockResolvedValue({ ...template(), version: 1 })
    await mount('/nocode-app/task-center/manage', TaskLaunch, { embedded: true, draftId: 'draft' })
    expect(document.querySelector('[aria-label="子任务名称 1"]')).toBeNull()
    button('保存草稿').click()
    await flush()
    expect(present(api.draftSave.mock.calls[0])[0].content).toMatchObject({ templateVersion: 1, nodes: [] })
  })
  it('更换模板先确认本次编排；取消保留节点，确认才换入新模板固定版本', async () => {
    api.templates.mockResolvedValue([
      template(),
      { ...template(), id: 'template-two', name: '采购模板', publishedVersion: 4 }
    ])
    api.templateVersion.mockImplementation(async (id: string, version?: number) => ({
      ...template(),
      id,
      version: version ?? (id === 'template-one' ? 2 : 4),
      name: id === 'template-one' ? '施工模板' : '采购模板',
      nodes: [{ ...newTaskNode(), title: id === 'template-one' ? '施工步骤' : '采购步骤' }]
    }))
    await mount('/nocode-app/task-center/manage', TaskLaunch, { embedded: true, initialTemplateId: 'template-one' })
    await input('[aria-label="子任务名称 1"]', '不能丢失的修改')
    await chooseTemplate('template-two')
    button('取消').click()
    await flush()
    expect(publishedTemplateSelect().value).toBe('template-one')
    expect(document.querySelector<HTMLInputElement>('[aria-label="子任务名称 1"]')?.value).toBe('不能丢失的修改')
    expect(api.templateVersion).not.toHaveBeenCalledWith('template-two', undefined)
    await chooseTemplate('template-two')
    button('更换模板').click()
    await flush()
    expect(publishedTemplateSelect().value).toBe('template-two')
    expect(document.querySelector<HTMLInputElement>('[aria-label="子任务名称 1"]')?.value).toBe('采购步骤')
    button('保存草稿').click()
    await flush()
    expect(present(api.draftSave.mock.calls[0])[0].content).toMatchObject({
      templateId: 'template-two',
      templateVersion: 4,
      nodes: [{ title: '采购步骤' }]
    })
  })
  it('从模板切换直接新建受未保存保护，取消后不丢失当前节点', async () => {
    await mount('/nocode-app/task-center/manage', TaskLaunch, { embedded: true, initialTemplateId: 'template-one' })
    await input('[aria-label="子任务名称 1"]', '当前模板分工')
    button('新建任务').click()
    await flush()
    button('取消').click()
    await flush()
    expect(publishedTemplateSelect().value).toBe('template-one')
    expect(document.querySelector<HTMLInputElement>('[aria-label="子任务名称 1"]')?.value).toBe('当前模板分工')
    button('保存草稿').click()
    await flush()
    expect(present(api.draftSave.mock.calls[0])[0].content.templateId).toBe('template-one')
  })
  it('新模板加载失败时保留原编排和来源版本，重试成功后才替换', async () => {
    api.templates.mockResolvedValue([
      template(),
      { ...template(), id: 'template-two', name: '采购模板', publishedVersion: 4 }
    ])
    api.templateVersion
      .mockResolvedValueOnce({ ...template(), version: 2 })
      .mockRejectedValueOnce(new Error('模板加载中断'))
      .mockResolvedValueOnce({
        ...template(),
        id: 'template-two',
        name: '采购模板',
        version: 4,
        nodes: [{ ...newTaskNode(), title: '采购步骤' }]
      })
    await mount('/nocode-app/task-center/manage', TaskLaunch, { embedded: true, initialTemplateId: 'template-one' })
    await input('[aria-label="子任务名称 1"]', '尚未替换的施工分工')
    await chooseTemplate('template-two')
    button('更换模板').click()
    await flush()
    expect(host.textContent).toContain('模板加载中断')
    expect(publishedTemplateSelect().value).toBe('template-one')
    expect(document.querySelector<HTMLInputElement>('[aria-label="子任务名称 1"]')?.value).toBe('尚未替换的施工分工')
    button('保存草稿').click()
    await flush()
    expect(present(api.draftSave.mock.calls[0])[0].content).toMatchObject({
      templateId: 'template-one',
      templateVersion: 2,
      nodes: [{ title: '尚未替换的施工分工' }]
    })
    await chooseTemplate('template-two')
    button('更换模板').click()
    await flush()
    expect(publishedTemplateSelect().value).toBe('template-two')
    expect(document.querySelector<HTMLInputElement>('[aria-label="子任务名称 1"]')?.value).toBe('采购步骤')
  })
  it('直接新建失败后保留全部本地编排，原请求键重试不重复创建', async () => {
    api.create.mockRejectedValueOnce(new Error('连接中断')).mockResolvedValueOnce(detail())
    await mount('/nocode-app/task-center/manage', TaskLaunch, { embedded: true })
    await input('[aria-label="发起任务名称"]', '需要重试的任务')
    button('添加子任务').click()
    await flush()
    await confirmScheduledLaunch()
    const first = JSON.parse(JSON.stringify(present(api.create.mock.calls[0])[0]))
    expect(host.textContent).toContain('连接中断')
    expect(document.querySelector<HTMLInputElement>('[aria-label="子任务名称 1"]')?.value).toBe('新增分工')
    await confirmScheduledLaunch()
    expect(present(api.create.mock.calls[1])[0]).toEqual(first)
    expect(api.draftSave).not.toHaveBeenCalled()
  })
  it('新模板保留总任务来源 ID，不把模板根重复加入 nodes，根日期与授权完整保存', async () => {
    const configured = {
      ...newTaskNode(),
      id: 'template-root',
      title: '模板总任务',
      dataPolicy: { version: 1 as const, business: 'ALL' as const, feedback: 'GROUP' as const },
      binding: { applicationId: 'app', formId: 'form', entryId: null },
      schedule: {
        mode: 'FIXED' as const,
        fixedStart: '2030-01-01T08:00:00',
        fixedEnd: '2030-01-05T17:00:00',
        offsetDays: 0,
        durationDays: 0
      }
    }
    api.templateVersion.mockResolvedValue({ ...template(), task: configured, version: 2 })
    await mount('/nocode-app/task-center/manage', TaskLaunch, { embedded: true, initialTemplateId: 'template-one' })
    button('保存草稿').click()
    await flush()
    const draft = present(api.draftSave.mock.calls[0])[0].content
    expect(draft.task).toMatchObject({
      title: configured.title,
      binding: configured.binding,
      schedule: configured.schedule,
      dataPolicy: configured.dataPolicy
    })
    expect(draft.task.id).toBe(configured.id)
    expect(draft.nodes).toEqual([expect.objectContaining({ title: '模板节点' })])
    expect(draft.templateVersion).toBe(2)
    expect(host.querySelector('[data-node-editor]')).not.toBeNull()
    expect(host.textContent).toContain('本次调整不会修改模板')
    expect(api.mine).toHaveBeenCalledOnce()
  })
  it('页面业务资源不兼容模板固定资源时明确阻止创建和草稿保存，不覆盖模板binding', async () => {
    const fixed = { applicationId: 'app', formId: 'template-form', entryId: null }
    api.templateVersion.mockResolvedValue({
      ...template(),
      task: {
        ...newTaskNode(),
        title: '固定业务模板',
        dataPolicy: { version: 1, business: 'GROUP', feedback: 'GROUP' },
        binding: fixed
      },
      version: 2
    })
    await mount('/nocode-app/task-center/manage', TaskLaunch, {
      embedded: true,
      initialTemplateId: 'template-one',
      initialBinding: { ...fixed, formId: 'other-form' }
    })
    expect(host.textContent).toContain('当前页面业务资源与模板固定业务资源不一致')
    button('加入任务池').click()
    await flush()
    button('保存草稿').click()
    await flush()
    expect(api.create).not.toHaveBeenCalled()
    expect(api.draftSave).not.toHaveBeenCalled()
    expect(api.mine).toHaveBeenCalledOnce()
  })

  it('模板页使用模板在主内容工作区办理，取消和成功都留在原页', async () => {
    await mount('/nocode-app/task-center/templates')
    button('使用模板').click()
    await flush()
    expect(api.templateVersion).toHaveBeenCalledWith('template-one', undefined)
    await discardLaunch()
    expect(router.currentRoute.value.path).toBe('/nocode-app/task-center/templates')
    expect(launchWorkspace()).toBeNull()
    button('使用模板').click()
    await flush()
    await confirmScheduledLaunch(present(launchWorkspace()))
    expect(api.create).toHaveBeenCalledWith(expect.objectContaining({ templateId: 'template-one', templateVersion: 2 }))
    expect(router.currentRoute.value.path).toBe('/nocode-app/task-center/templates')
    expect(host.querySelector('[data-task-detail="task-new"]')).not.toBeNull()
  })
  it('从模板编辑工作区发起后返回，保留未保存模板与原页签而不重新加载', async () => {
    api.templates.mockResolvedValue([{ ...template(), task: { ...newTaskNode(), id: 'root', title: '模板总任务' } }])
    await mount('/nocode-app/task-center/templates')
    button('编辑草稿').click()
    await flush()
    await input('[aria-label="发起任务名称"]', '模板尚未保存的名称')
    const retainedEditor = present(document.querySelector<HTMLInputElement>('[aria-label="发起任务名称"]'))
    const queries = api.templates.mock.calls.length
    button('使用已发布模板').click()
    await flush()
    expect(retainedEditor.closest<HTMLElement>('.task-template-workspace')?.style.display).toBe('none')
    await discardLaunch()
    expect(launchWorkspace()).toBeNull()
    expect(document.querySelector('[aria-label="发起任务名称"]')).toBe(retainedEditor)
    expect(retainedEditor.value).toBe('模板尚未保存的名称')
    expect(api.templates).toHaveBeenCalledTimes(queries + 1)
  })

  it('旧URL保留templateId跳管理页，参数只打开一次，消费参数不重建列表', async () => {
    await mount('/nocode-app/task-center/launch?templateId=template-one&source=old-link#keep')
    expect(router.currentRoute.value.path).toBe('/nocode-app/task-center/manage')
    expect(router.currentRoute.value.query).toEqual({ source: 'old-link' })
    expect(router.currentRoute.value.hash).toBe('#keep')
    expect(launchWorkspace()).not.toBeNull()
    expect(api.managementPage).toHaveBeenCalledTimes(1)
    expect(api.templateVersion).toHaveBeenCalledWith('template-one', undefined)
    expect(api.create).not.toHaveBeenCalled()
    await discardLaunch()
    await router.replace({ query: { source: 'updated-link' } })
    await flush()
    expect(launchWorkspace()).toBeNull()
    expect(api.managementPage).toHaveBeenCalledTimes(1)
  })

  it.each([['nocode:task:query'], ['nocode:task:create']])(
    '无完整query/create权限时URL不打开表单或自动发起：%s',
    async permission => {
      api.permissions = [permission]
      await mount('/nocode-app/task-center/launch?templateId=template-one')
      expect(launchWorkspace()).toBeNull()
      expect(host.textContent).toContain('当前没有新建任务权限')
      expect(api.create).not.toHaveBeenCalled()
      expect(api.templateVersion).not.toHaveBeenCalled()
      expect(Array.from(host.querySelectorAll('button')).some(el => el.textContent?.trim() === '新建任务')).toBe(false)
    }
  )

  it('离开或更新当前路由时未保存输入仍受保护，取消导航继续编辑', async () => {
    await mount('/nocode-app/task-center/manage')
    button('新建任务', host).click()
    await flush()
    await input('[aria-label="发起任务名称"]', '还要继续修改')
    const updating = router.push({ query: { taskId: 'other-task' } })
    await flush()
    button('继续编辑').click()
    await updating
    expect(router.currentRoute.value.query).toEqual({})
    expect(document.querySelector<HTMLInputElement>('[aria-label="发起任务名称"]')?.value).toBe('还要继续修改')
    const leaving = router.push('/elsewhere')
    await flush()
    button('继续编辑').click()
    await leaving
    expect(router.currentRoute.value.path).toBe('/nocode-app/task-center/manage')
    const approved = router.push('/elsewhere')
    await flush()
    button('放弃修改').click()
    await approved
    await flush()
    expect(router.currentRoute.value.path).toBe('/elsewhere')
    expect(launchWorkspace()).toBeNull()
  })

  it('批准同页深链导航后关闭发起表单，清除详情参数也会收回旧详情，列表不重建', async () => {
    await mount('/nocode-app/task-center/manage')
    await input('input[placeholder="搜索总任务或子任务名称"]', '保留条件')
    button('新建任务', host).click()
    await flush()
    await input('[aria-label="发起任务名称"]', '可以放弃的输入')
    const updating = router.push({ query: { taskId: 'other-task' } })
    await flush()
    button('放弃修改').click()
    await updating
    await flush()
    expect(launchWorkspace()).toBeNull()
    expect(host.querySelector('[data-task-detail="other-task"]')).not.toBeNull()
    expect(host.querySelector<HTMLInputElement>('input[placeholder="搜索总任务或子任务名称"]')?.value).toBe('保留条件')
    await router.replace({ query: {} })
    await flush()
    expect(host.querySelector('[data-task-detail]')).toBeNull()
    expect(api.managementPage).toHaveBeenCalledTimes(1)
  })

  it('业务表单拒绝关闭后再离开仍须确认任务输入，不会留下已批准丢弃状态', async () => {
    api.draftGet.mockResolvedValue({
      id: 'legacy',
      revision: 1,
      content: {
        task: { ...newTaskNode(), binding: { applicationId: 'app', formId: 'form', entryId: null } },
        requestKey: 'legacy-key'
      }
    })
    await mount('/nocode-app/task-center/manage', TaskLaunch, {
      embedded: true,
      draftId: 'legacy',
      initialBinding: { applicationId: 'app', formId: 'form', entryId: null }
    })
    await input('[aria-label="发起任务名称"]', '仍有业务输入')
    await confirmScheduledLaunch()
    expect(api.create).not.toHaveBeenCalled()
    expect(api.draftPublish).not.toHaveBeenCalled()
    api.businessClose.mockResolvedValueOnce(false)
    const first = router.push('/elsewhere')
    await flush()
    button('放弃修改').click()
    await first
    await flush()
    expect(router.currentRoute.value.path).toBe('/nocode-app/task-center/manage')
    expect(document.querySelector('[data-drawer="填写业务数据并加入任务池"]')).not.toBeNull()
    const second = router.push('/elsewhere')
    await flush()
    button('继续编辑').click()
    await second
    expect(api.businessClose).toHaveBeenCalledTimes(1)
    expect(document.querySelector<HTMLInputElement>('[aria-label="发起任务名称"]')?.value).toBe('仍有业务输入')
  })

  it('应用上下文保留项目与业务资源；新授权不要求发起时提交业务资料', async () => {
    const project = { applicationId: 'app', objectId: 'project', recordId: 'project-one', label: '当前项目' }
    const binding = { applicationId: 'app', formId: 'form', entryId: null }
    await mount('/nocode-app/task-center/manage?templateId=unrelated', TaskLaunch, {
      embedded: true,
      initialProject: project,
      initialBinding: binding
    })
    await input('[aria-label="发起任务名称"]', '当前项目施工')
    await input('[aria-label="计划开始日期"]', '')
    button('加入任务池').click()
    await flush()
    expect(host.textContent).toContain('请选择计划开始日期')
    expect(api.schedulePreview).not.toHaveBeenCalled()
    expect(api.create).not.toHaveBeenCalled()
    await confirmScheduledLaunch()
    expect(api.templateVersion).not.toHaveBeenCalled()
    expect(api.create).toHaveBeenCalledWith(
      expect.objectContaining({
        project,
        task: expect.objectContaining({ binding, dataPolicy: { version: 1, business: 'GROUP', feedback: 'GROUP' } }),
        business: null,
        templateId: null,
        plannedStart: '2030-02-03'
      })
    )
    const command: TaskCreate = present(api.create.mock.calls[0])[0]
    expect(api.schedulePreview).toHaveBeenCalledWith({
      nodes: [
        expect.objectContaining({ id: command.task.id, title: '当前项目施工', schedule: { ...command.task.schedule } })
      ],
      plannedStart: command.plannedStart
    })
  })

  it('页面复用针对统一任务页，旧入口参数不再重建另一个门户', () => {
    for (const path of [
      '/nocode-app/task-center',
      '/nocode-app/task-center/manage',
      '/nocode-app/task-center/templates'
    ])
      expect(taskCenterViewKey({ path, query: { taskId: 'task' }, fullPath: `${path}?taskId=task` })).toBe(path)
    expect(
      taskCenterViewKey({
        path: '/nocode-app/task-center',
        query: { legacy: '1' },
        fullPath: '/nocode-app/task-center?legacy=1'
      })
    ).toBe('/nocode-app/task-center')
    expect(
      taskCenterViewKey({
        path: '/nocode-app/task-center',
        query: { entry: 'one' },
        fullPath: '/nocode-app/task-center?entry=one'
      })
    ).toBe('/nocode-app/task-center')
    expect(taskCenterViewKey({ path: '/other', query: { id: 'one' }, fullPath: '/other?id=one' })).toBe('/other?id=one')
  })

  it('旧门户草稿参数被移除，保留新版任务深链且不改原查询', () => {
    const query = {
      legacy: '1',
      entry: 'old',
      app: 'app',
      version: '2',
      recordId: 'record',
      draftId: 'old-draft',
      taskId: 'task'
    }
    expect(taskCenterRetiredQuery(query)).toEqual({ taskId: 'task' })
    expect(query.draftId).toBe('old-draft')
    expect(taskCenterRetiredQuery({ draftId: 'new-task-draft' })).toBeNull()
  })

  it.each([
    '/nocode-app/task-center?legacy=1',
    '/nocode-app/task-center?app=app&entry=old&version=2&draftId=old-draft&recordId=record'
  ])('旧地址 %s 回到新版，不读取旧草稿或显示旧门户', async path => {
    await mount(path)
    await flush()
    expect(router.currentRoute.value.fullPath).toBe('/nocode-app/task-center')
    expect(host.textContent).toContain('我的计划')
    expect(host.textContent).not.toContain('我的待办')
    expect(host.textContent).not.toContain('业务申请与办理草稿')
    expect(api.draftGet).not.toHaveBeenCalled()
  })
})

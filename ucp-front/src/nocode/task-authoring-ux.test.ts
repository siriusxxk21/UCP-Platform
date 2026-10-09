// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, inject, nextTick, provide, type App, type Component } from 'vue'
import TaskLaunch from '@/views/nocode/task-center/TaskLaunch.vue'
import TaskLaunchDrawer from '@/views/nocode/task-center/TaskLaunchDrawer.vue'
import TaskTemplates from '@/views/nocode/task-center/TaskTemplates.vue'
import TaskDraftList from '@/views/nocode/task-center/TaskDraftList.vue'
import TaskNodeEditor from '@/views/nocode/task-center/TaskNodeEditor.vue'
import { newTaskNode } from './task-center'
import type { TaskDraft, TaskNodeInput, TaskTemplate } from '@/types/nocode/task-center'

const api = vi.hoisted(() => ({
  members: vi.fn(),
  templates: vi.fn(),
  templateVersion: vi.fn(),
  mine: vi.fn(),
  application: vi.fn(),
  drafts: vi.fn(),
  draftGet: vi.fn(),
  draftSave: vi.fn(),
  draftDelete: vi.fn(),
  create: vi.fn(),
  saveTemplate: vi.fn(),
  publishTemplate: vi.fn(),
  confirm: vi.fn(),
  confirmDiscard: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({ taskCenter: api, runtime: { mine: api.mine, application: api.application } })
}))
vi.mock('@/nocode/task-confirmation', () => ({
  useTaskConfirmation: () => ({ confirm: api.confirm, confirmDiscard: api.confirmDiscard })
}))
vi.mock('@/nocode/unsaved', () => ({ useUnsavedNavigation: vi.fn() }))
vi.mock('@/utils/access', () => ({ hasPermission: () => true }))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 1 } }) }))
vi.mock('vue-router', () => ({ useRoute: () => ({ fullPath: '/' }), useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/views/nocode/task-center/TaskDetail.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskTemplateInstances.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskRecordPicker.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskBusinessForm.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskDag.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskAssignmentFields.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskNodeFields.vue', () => ({
  default: defineComponent({
    props: ['modelValue', 'titleLabel', 'readonly'],
    setup:
      (props, { slots }) =>
      () =>
        h('section', [
          h('label', [
            props.titleLabel || '任务名称',
            h('input', {
              'aria-label': props.titleLabel || '任务名称',
              value: props.modelValue.title,
              disabled: props.readonly,
              onInput: (event: Event) => {
                props.modelValue.title = (event.target as HTMLInputElement).value
              }
            })
          ]),
          slots['business-context']?.(),
          slots['business-record']?.()
        ])
  })
}))
vi.mock('@/components/ucp-modal-form/OsModalForm.vue', () => ({
  default: defineComponent({
    props: ['open', 'title', 'showFooter', 'okText', 'loading'],
    emits: ['ok', 'cancel'],
    setup:
      (props, { emit, slots }) =>
      () =>
        props.open
          ? h('section', { 'data-dialog': props.title }, [
              h('h2', props.title),
              h('button', { onClick: () => emit('cancel') }, `关闭${props.title}`),
              slots.formItems?.(),
              slots.footer?.() ||
                (props.showFooter === false ? null : h('button', { onClick: () => emit('ok') }, props.okText || '保存'))
            ])
          : null
  })
}))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['dataSource', 'columns', 'loading'],
    setup: (props, { slots }) => {
      const renderRows = (rows: Array<{ id: string; children?: typeof rows }>): ReturnType<typeof h>[] =>
        rows.flatMap(record => [
          h('div', { 'data-row': record.id }, [
            ...props.columns.map((column: { key: string }) => slots.bodyCell?.({ column, record }))
          ]),
          ...renderRows(record.children || [])
        ])
      return () =>
        h('section', [
          slots.search?.(),
          slots.title?.(),
          slots.actions?.(),
          ...renderRows(props.dataSource || []),
          !props.loading && !props.dataSource?.length ? slots.empty?.() : null
        ])
    }
  })
}))

let app: App | undefined, host: HTMLDivElement
const flush = async () => {
  for (let index = 0; index < 20; index++) {
    await Promise.resolve()
    await nextTick()
  }
}
function required<T>(value: T | null | undefined): T {
  if (!value) throw new Error('应存在测试目标')
  return value
}
function button(label: string, root: ParentNode = host) {
  return required(
    Array.from(root.querySelectorAll('button')).find(
      item => item.textContent?.trim() === label || item.getAttribute('aria-label') === label
    )
  )
}
async function openRootNameEditor() {
  required(host.querySelector<HTMLButtonElement>('[aria-label="编辑任务名称 1"]')).click()
  await flush()
  return required(host.querySelector<HTMLInputElement>('input[aria-label="总任务名称 1"]'))
}
async function input(label: string, value: string) {
  if (label === '任务名称' && !host.querySelector('input[aria-label="任务名称"]')) {
    await openRootNameEditor()
    label = '总任务名称 1'
  }
  const element = required(host.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`))
  element.value = value
  element.dispatchEvent(new Event('input'))
  await flush()
}
async function mount(component: Component, props: Record<string, unknown> = {}) {
  app = createApp(() => h(component, props))
  const plain = defineComponent({
    props: ['label', 'message', 'description'],
    setup:
      (props, { slots }) =>
      () =>
        h('div', [props.label, props.message, props.description, slots.default?.(), slots.action?.()])
  })
  for (const name of [
    'AForm',
    'AFormItem',
    'AAlert',
    'ATag',
    'AEmpty',
    'ASpace',
    'ACollapse',
    'ACollapsePanel',
    'ATooltip',
    'ATabPane',
    'ATabs',
    'AMenu',
    'AMenuItem',
    'ADropdown',
    'ARadio',
    'ACheckbox',
    'AInputNumber'
  ])
    app.component(name, plain)
  app.component(
    'AButton',
    defineComponent({
      props: ['loading', 'disabled'],
      setup:
        (props, { slots }) =>
        () =>
          h('button', { disabled: props.disabled, 'data-loading': String(!!props.loading) }, slots.default?.())
    })
  )
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
  app.component('ATextarea', plain)
  app.component('ADatePicker', plain)
  app.component(
    'ASelect',
    defineComponent({
      props: ['value', 'options', 'disabled'],
      emits: ['update:value', 'change'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'select',
            {
              value: props.value,
              disabled: props.disabled,
              onChange: (event: Event) => {
                const value = (event.target as HTMLSelectElement).value
                emit('update:value', value)
                emit('change', value)
              }
            },
            props.options?.map((option: { value: string; label: string }) =>
              h('option', { value: option.value }, option.label)
            )
          )
    })
  )
  app.component(
    'ARadioGroup',
    defineComponent({
      emits: ['update:value'],
      setup: (_, { emit, slots }) => {
        provide('radio-change', (value: string) => emit('update:value', value))
        return () => h('div', slots.default?.())
      }
    })
  )
  app.component(
    'ARadioButton',
    defineComponent({
      props: ['value'],
      setup: (props, { slots }) => {
        const change = inject<(value: string) => void>('radio-change')
        return () => h('button', { onClick: () => change?.(props.value) }, slots.default?.())
      }
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
}
const template = (): TaskTemplate => ({
  id: 'template-a',
  name: '施工项目模板',
  description: '',
  task: { ...newTaskNode(), title: '施工项目' },
  nodes: [],
  revision: 1,
  publishedVersion: 1,
  creatorId: 1,
  updatedAt: ''
})
const draft = (): TaskDraft => ({
  id: 'draft-a',
  title: '恢复的施工任务',
  revision: 1,
  updatedAt: '',
  content: { task: { ...newTaskNode(), title: '恢复的施工任务' }, requestKey: 'request-a' }
})
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'requestAnimationFrame', 'cancelAnimationFrame'] })
  vi.resetAllMocks()
  api.members.mockResolvedValue([])
  api.templates.mockResolvedValue([])
  api.mine.mockResolvedValue([])
  api.drafts.mockResolvedValue([])
  api.confirm.mockResolvedValue(true)
  api.confirmDiscard.mockResolvedValue(true)
})
afterEach(async () => {
  app?.unmount()
  await flush()
  // 真实控件的双帧进退场回调应在当前 DOM 环境结束前清理。
  await vi.runAllTimersAsync()
  await flush()
  expect(vi.getTimerCount()).toBe(0)
  host?.remove()
  vi.useRealTimers()
})

describe('任务新建、模板与草稿体验', () => {
  it('发起工作区操作位于页头，仍保存同一份任务数据，离开时清理按钮', async () => {
    api.draftSave.mockImplementation(async value => ({ ...draft(), content: value.content }))
    const saved = vi.fn()
    await mount(TaskLaunchDrawer, { onDraftSaved: saved })
    await input('任务名称', '独立底栏草稿')
    const footer = required(host.querySelector('.task-launch__footer'))
    expect(footer.closest('.task-launch__header')).not.toBeNull()
    expect(host.querySelector('[data-dialog="新建任务"]')).toBeNull()
    expect(host.querySelectorAll('.task-launch__footer')).toHaveLength(1)
    button('保存草稿', footer).click()
    await flush()
    expect(api.draftSave).toHaveBeenCalledWith(
      expect.objectContaining({
        content: expect.objectContaining({ task: expect.objectContaining({ title: '独立底栏草稿' }) })
      })
    )
    expect(saved).toHaveBeenCalledOnce()
    expect(api.create).not.toHaveBeenCalled()
    app?.unmount()
    app = undefined
    expect(document.querySelector('.task-launch__footer')).toBeNull()
  })
  it('独立任务页面没有抽屉底栏时操作留在当前页面，不移到 document.body', async () => {
    await mount(TaskLaunch)
    expect(host.querySelector('.task-launch .task-launch__footer')).not.toBeNull()
    expect(host.querySelector('.task-launch__footer--hosted')).toBeNull()
    expect(document.querySelectorAll('.task-launch__footer')).toHaveLength(1)
  })
  it('目录失败分区重试保留已填写内容，不改变默认所有人可领取', async () => {
    api.members.mockRejectedValueOnce(new Error('人员暂不可用')).mockResolvedValue([])
    api.mine.mockRejectedValueOnce(new Error('应用暂不可用')).mockResolvedValue([])
    api.draftSave.mockImplementation(async value => ({ ...draft(), content: value.content }))
    await mount(TaskLaunch, { embedded: true })
    await input('任务名称', '先安排现场测量')
    expect(host.textContent).toContain('人员列表加载失败')
    expect(host.textContent).toContain('应用列表加载失败')
    button('重试人员列表').click()
    button('重试应用列表').click()
    await flush()
    expect(host.textContent).not.toContain('列表加载失败')
    expect(required(host.querySelector<HTMLInputElement>('input[aria-label="任务名称"]')).value).toBe('先安排现场测量')
    button('保存草稿').click()
    await flush()
    expect(api.draftSave).toHaveBeenCalledWith(
      expect.objectContaining({
        content: expect.objectContaining({
          task: expect.objectContaining({ title: '先安排现场测量', assignmentMode: 'OPEN', candidateUserIds: [] })
        })
      })
    )
  })
  it('草稿恢复失败不展示可误编辑的空白任务，重试恢复原内容', async () => {
    api.draftGet.mockRejectedValueOnce(new Error('网络中断')).mockResolvedValueOnce(draft())
    await mount(TaskLaunch, { embedded: true, draftId: 'draft-a' })
    expect(host.textContent).toContain('草稿加载失败')
    expect(host.querySelector('input[aria-label="任务名称"]')).toBeNull()
    expect(button('加入任务池').disabled).toBe(true)
    button('重新加载草稿').click()
    await flush()
    expect(api.draftGet).toHaveBeenCalledTimes(2)
    expect(required(host.querySelector<HTMLInputElement>('input[aria-label="任务名称"]')).value).toBe('恢复的施工任务')
    expect(host.textContent).not.toContain('草稿加载失败')
  })
  it('模板目录失败显示重试，恢复后保留任务名；空目录明确说明尚无可用模板', async () => {
    api.templates.mockRejectedValueOnce(new Error('模板暂不可用')).mockResolvedValueOnce([])
    await mount(TaskLaunch, { embedded: true })
    await input('任务名称', '不丢失名称')
    button('从模板新建').click()
    await flush()
    expect(host.textContent).toContain('模板列表加载失败')
    button('重试模板列表').click()
    await flush()
    expect(host.textContent).toContain('暂无可用的已发布模板')
    expect(required(host.querySelector<HTMLInputElement>('input[aria-label="任务名称"]')).value).toBe('不丢失名称')
  })
  it('保存草稿时仅草稿按钮呈现加载，加入任务池不可误点', async () => {
    let finish!: (value: TaskDraft) => void
    api.draftSave.mockReturnValue(new Promise<TaskDraft>(resolve => (finish = resolve)))
    await mount(TaskLaunch, { embedded: true })
    await input('任务名称', '现场验收')
    button('保存草稿').click()
    await flush()
    expect(button('保存草稿').dataset.loading).toBe('true')
    expect(button('加入任务池').dataset.loading).toBe('false')
    expect(button('加入任务池').disabled).toBe(true)
    expect(api.create).not.toHaveBeenCalled()
    finish(draft())
    await flush()
    expect(button('保存草稿').dataset.loading).toBe('false')
  })
  it('我的草稿刷新成功移除旧错误，空态说明保存入口', async () => {
    api.drafts.mockRejectedValueOnce(new Error('加载失败')).mockResolvedValueOnce([])
    await mount(TaskDraftList)
    expect(host.textContent).toContain('加载失败')
    button('刷新').click()
    await flush()
    expect(host.textContent).not.toContain('加载失败')
    expect(host.textContent).toContain('在新建任务时选择“保存草稿”')
  })
  it('删除草稿等待响应时禁止打开和重复删除，不影响其他正式任务', async () => {
    api.drafts.mockResolvedValue([{ id: 'draft-a', title: '未发布任务', revision: 1, updatedAt: '' }])
    let finish!: (value: boolean) => void
    api.draftDelete.mockReturnValue(new Promise<boolean>(resolve => (finish = resolve)))
    const edit = vi.fn()
    await mount(TaskDraftList, { onEdit: edit })
    button('删除').click()
    await flush()
    expect(button('继续编辑').disabled).toBe(true)
    button('继续编辑').click()
    button('删除').click()
    expect(edit).not.toHaveBeenCalled()
    expect(api.draftDelete).toHaveBeenCalledOnce()
    finish(true)
    await flush()
  })
  it('新建模板明确默认任务名，随模板名同步直到用户单独修改', async () => {
    await mount(TaskTemplates)
    button('新建模板').click()
    await flush()
    expect(host.querySelector('.task-template-workspace')).not.toBeNull()
    expect(host.querySelector('[data-dialog="新建任务模板"]')).toBeNull()
    expect(host.textContent).toContain('跟随任务顺序')
    const name = required(host.querySelector<HTMLInputElement>('input[placeholder="例如：标准施工项目模板"]'))
    name.value = '施工项目模板'
    name.dispatchEvent(new Event('input'))
    await flush()
    const taskName = await openRootNameEditor()
    expect(taskName.value).toBe('施工项目模板')
    await input('总任务名称 1', '新项目施工')
    name.value = '更新后的模板名称'
    name.dispatchEvent(new Event('input'))
    await flush()
    expect(taskName.value).toBe('新项目施工')
  })
  it('模板保存期间不可离开工作区，成功后留在原页且不自动发布', async () => {
    api.templates.mockResolvedValue([template()])
    let finish!: (value: TaskTemplate) => void
    api.saveTemplate.mockReturnValue(new Promise<TaskTemplate>(resolve => (finish = resolve)))
    await mount(TaskTemplates)
    button('编辑草稿').click()
    await flush()
    button('保存草稿').click()
    await flush()
    button('返回模板列表').click()
    await flush()
    expect(host.querySelector('.task-template-workspace')).not.toBeNull()
    expect(api.confirmDiscard).not.toHaveBeenCalled()
    finish(template())
    await flush()
    expect(host.querySelector('.task-template-workspace')).not.toBeNull()
    button('返回模板列表').click()
    await flush()
    expect(host.querySelector('.task-template-workspace')).toBeNull()
    expect(api.publishTemplate).not.toHaveBeenCalled()
  })
  it('统一授权编排显示继承总任务数据，不再误称独立记录', async () => {
    const root: TaskNodeInput = {
      ...newTaskNode(),
      id: 'root',
      title: '总任务',
      dataPolicy: { version: 1, business: 'GROUP', feedback: 'GROUP' }
    }
    await mount(TaskNodeEditor, {
      root,
      modelValue: [{ ...newTaskNode(), id: 'child', title: '子任务' }],
      members: []
    })
    expect(host.textContent).toContain('所有下级任务继承总任务的数据授权')
    expect(host.textContent).not.toContain('独立记录')
  })
})

// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, getCurrentInstance, h, inject, nextTick, provide, type App } from 'vue'
import TaskLaunch from '@/views/nocode/task-center/TaskLaunch.vue'
import TaskWorkBudgetFields from '@/views/nocode/task-center/TaskWorkBudgetFields.vue'
import { newTaskNode } from './task-center'
import type { TaskCreate, TaskTemplateVersion } from '@/types/nocode/task-center'

const api = vi.hoisted(() => ({
  members: vi.fn(),
  templates: vi.fn(),
  templateVersion: vi.fn(),
  templateVersions: vi.fn(),
  confirm: vi.fn(),
  create: vi.fn(),
  schedulePreview: vi.fn(),
  draftGet: vi.fn(),
  draftSave: vi.fn(),
  draftPublish: vi.fn(),
  mine: vi.fn(),
  application: vi.fn()
}))
vi.mock('@/nocode/platform', () => ({
  useNocodePlatform: () => ({ taskCenter: api, runtime: { mine: api.mine, application: api.application } })
}))
vi.mock('@/utils/access', () => ({ hasPermission: () => true }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/nocode/unsaved', () => ({ useUnsavedNavigation: vi.fn() }))
vi.mock('@/nocode/task-confirmation', () => ({
  useTaskConfirmation: () => ({ confirmDiscard: async () => true, confirm: api.confirm })
}))
vi.mock('ant-design-vue', () => ({ message: { success: vi.fn() } }))
vi.mock('@/components/ucp-modal-form/OsModalForm.vue', () => ({
  default: defineComponent({
    props: ['open', 'title', 'okText'],
    emits: ['ok', 'cancel'],
    setup:
      (props, { slots, emit }) =>
      () =>
        props.open
          ? h('section', { 'data-dialog': props.title }, [
              h('h2', props.title),
              slots.formItems?.(),
              h('button', { onClick: () => emit('ok') }, props.okText),
              h('button', { onClick: () => emit('cancel') }, '继续调整')
            ])
          : null
  })
}))
vi.mock('@/components/ucp-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['dataSource', 'columns'],
    setup:
      (props, { slots }) =>
      () =>
        h(
          'div',
          props.dataSource.map((record: unknown) =>
            h(
              'div',
              props.columns.map((column: unknown) => slots.bodyCell?.({ column, record }))
            )
          )
        )
  })
}))
vi.mock('@/views/nocode/task-center/TaskNodeEditor.vue', () => ({
  default: defineComponent({
    props: ['root', 'readonly'],
    emits: ['update:root'],
    setup:
      (props, { emit }) =>
      () =>
        h('input', {
          'aria-label': '任务名称',
          value: props.root.title,
          disabled: props.readonly,
          onInput: (event: Event) =>
            emit('update:root', { ...props.root, title: (event.target as HTMLInputElement).value })
        })
  })
}))
vi.mock('@/views/nocode/task-center/TaskDag.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskBusinessForm.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/task-center/TaskNodeFields.vue', () => ({
  default: defineComponent({
    props: ['modelValue', 'dataReadonly', 'readonly', 'section'],
    setup:
      (props, { slots }) =>
      () =>
        h('div', { 'data-resources-locked': String(props.dataReadonly) }, [
          slots['business-context']?.(),
          slots['business-record']?.(),
          props.section === 'business'
            ? h(TaskWorkBudgetFields, {
                entries: props.modelValue.entries || [],
                minutes: props.modelValue.effectiveWorkMinutes,
                mode: props.modelValue.workTotalMode,
                readonly: props.readonly,
                'onUpdate:minutes': (value: number | null) => (props.modelValue.effectiveWorkMinutes = value),
                'onUpdate:mode': (value: 'AUTO' | 'MANUAL' | null) => (props.modelValue.workTotalMode = value)
              })
            : null
        ])
  })
}))
vi.mock('@/views/nocode/task-center/TaskRecordPicker.vue', () => ({
  default: defineComponent({
    props: ['modelValue', 'applicationId'],
    emits: ['update:modelValue'],
    setup:
      (props, { emit }) =>
      () =>
        h(
          'button',
          {
            'data-record-picker': props.applicationId,
            onClick: () =>
              emit('update:modelValue', {
                applicationId: props.applicationId,
                objectId: 'order',
                recordId: 'r1',
                label: '本次采购单'
              })
          },
          '选择本次采购单'
        )
  })
}))

let app: App | undefined, host: HTMLDivElement, version: TaskTemplateVersion
const flush = async () => {
  for (let i = 0; i < 25; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const record = { applicationId: 'purchase', objectId: 'order', recordId: 'r1', label: '本次采购单' }
function button(label: string) {
  const element = Array.from(host.querySelectorAll('button')).find(item => item.textContent?.trim() === label)
  if (!element) throw new Error(`缺少按钮：${label}`)
  return element
}
async function launchTask() {
  button('加入任务池').click()
  await flush()
  expect(api.create).not.toHaveBeenCalled()
  button('确认并加入任务池').click()
  await flush()
}
function element<T extends Element>(selector: string) {
  const result = host.querySelector<T>(selector)
  if (!result) throw new Error(`缺少元素：${selector}`)
  return result
}
async function selectApplication(value: string) {
  const element = host.querySelector<HTMLSelectElement>('[aria-label="关联应用"]')
  if (!element) throw new Error('应可选择本次任务关联应用')
  element.value = value
  element.dispatchEvent(new Event('change'))
  await flush()
}
async function chooseVersion(value: number) {
  const select = element<HTMLSelectElement>('[aria-label="模板版本"]')
  select.value = String(value)
  select.dispatchEvent(new Event('change'))
  await flush()
}
function expectFixed(command: TaskCreate) {
  expect(command.templateId).toBe(version.id)
  expect(command.templateVersion).toBe(version.version)
  expect(command.task.binding).toEqual(version.task?.binding)
  expect(command.task.entries).toEqual(version.task?.entries)
  expect(command.task.dataPolicy).toEqual(version.task?.dataPolicy)
}
async function mount(props: Record<string, unknown> = {}) {
  app = createApp(() => h(TaskLaunch, { embedded: true, initialTemplateId: version.id, ...props }))
  const plain = defineComponent({
    props: ['label', 'message', 'header'],
    setup:
      (props, { slots }) =>
      () =>
        h('div', [props.label, props.message, props.header, slots.default?.(), slots.action?.()])
  })
  for (const name of [
    'AForm',
    'AFormItem',
    'AAlert',
    'ATag',
    'ACollapse',
    'ACollapsePanel',
    'ADatePicker',
    'ARadio',
    'ARadioGroup',
    'ARadioButton'
  ])
    app.component(name, plain)
  app.component(
    'AInputNumber',
    defineComponent({
      props: ['value', 'disabled'],
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h('input', {
            value: props.value,
            disabled: props.disabled,
            onInput: (event: Event) => emit('update:value', Number((event.target as HTMLInputElement).value))
          })
    })
  )
  app.component(
    'ADatePicker',
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
  app.component(
    'ATabs',
    defineComponent({
      props: ['activeKey'],
      emits: ['update:activeKey'],
      setup: (props, { emit, slots }) => {
        provide('launch-tabs', {
          active: () => props.activeKey,
          change: (value: unknown) => emit('update:activeKey', value)
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
        const tabs = inject<{ active: () => unknown; change: (value: unknown) => void }>('launch-tabs')
        const key = getCurrentInstance()?.vnode.key
        return () =>
          h('div', [
            h('button', { onClick: () => tabs?.change(key) }, props.tab),
            tabs?.active() === key ? slots.default?.() : null
          ])
      }
    })
  )
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
              value: props.value || '',
              onChange: (event: Event) => {
                const target = event.target as HTMLSelectElement
                const value = target.value
                emit('update:value', value || undefined)
                emit('change', value || undefined)
                void nextTick(() => (target.value = String(props.value || '')))
              }
            },
            [
              h('option', { value: '' }, '未选择'),
              ...props.options.map((option: { value: string; label: string }) =>
                h('option', { value: option.value }, option.label)
              )
            ]
          )
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
  button('业务关联').click()
  await flush()
}

beforeEach(() => {
  vi.resetAllMocks()
  api.schedulePreview.mockImplementation(async ({ nodes }) => ({
    nodes: nodes.map((node: { id: string; title: string }) => ({
      id: node.id,
      title: node.title,
      expectedStart: null,
      expectedEnd: null,
      partial: true,
      warnings: []
    })),
    warnings: []
  }))
  version = {
    id: 'template',
    version: 3,
    name: '采购模板',
    description: '',
    publishedAt: '',
    nodes: [],
    task: {
      ...newTaskNode(),
      title: '采购工作',
      binding: { applicationId: 'purchase', formId: 'order_form', entryId: null },
      dataPolicy: { version: 1, business: 'ALL', feedback: 'GROUP' },
      entries: [
        {
          key: 'feedback',
          name: '采购反馈',
          binding: { applicationId: 'purchase', formId: 'feedback_form', entryId: null },
          dataMode: 'ROOT_SHARED',
          sourceNodeId: null,
          sourceEntryKey: null,
          readableFieldIds: ['title'],
          writableFieldIds: ['title'],
          required: true,
          allowAll: false
        }
      ]
    }
  }
  api.members.mockResolvedValue([])
  api.templates.mockResolvedValue([{ ...version, publishedVersion: version.version }])
  api.templateVersions.mockResolvedValue([
    { version: 3, name: '采购模板', description: '', publishedAt: '', nodeCount: 0, primary: false },
    { version: 1, name: '采购模板', description: '', publishedAt: '', nodeCount: 0, primary: true }
  ])
  api.confirm.mockResolvedValue(true)
  api.templateVersion.mockImplementation(async () => structuredClone(version))
  api.mine.mockResolvedValue([
    { id: 'purchase', name: '采购应用' },
    { id: 'construction', name: '施工应用' }
  ])
  api.application.mockResolvedValue({ application: { id: 'purchase', name: '采购应用' } })
  api.create.mockResolvedValue({ task: { id: 'created' } })
  api.draftSave.mockImplementation(async body => ({ ...body, revision: 1 }))
  api.draftPublish.mockResolvedValue({ task: { id: 'created-from-draft' } })
})
afterEach(() => {
  app?.unmount()
  host?.remove()
})

describe('模板固定资源与本次任务归属分离', () => {
  it('AUTO 发起清空默认日期后需补填并预览，取消不写入，确认后才提交相同规则', async () => {
    version.task!.schedule.mode = 'AUTO'
    api.schedulePreview.mockImplementation(async ({ nodes, plannedStart }) => ({
      nodes: nodes.map((node: { id: string; title: string }) => ({
        ...node,
        expectedStart: plannedStart,
        expectedEnd: '2026-10-17',
        partial: false,
        warnings: []
      })),
      warnings: []
    }))
    await mount()
    button('任务编排').click()
    await flush()
    const start = element<HTMLInputElement>('[aria-label="计划开始日期"]')
    start.value = ''
    start.dispatchEvent(new Event('input'))
    await flush()
    button('加入任务池').click()
    await flush()
    expect(host.textContent).toContain('请选择计划开始日期')
    expect(api.schedulePreview).not.toHaveBeenCalled()
    start.value = '2026-10-12'
    start.dispatchEvent(new Event('input'))
    await flush()
    button('加入任务池').click()
    await flush()
    expect(api.schedulePreview).toHaveBeenCalledWith(expect.objectContaining({ plannedStart: '2026-10-12' }))
    expect(host.querySelector('[data-dialog]')?.textContent).toContain('2026-10-17')
    expect(api.create).not.toHaveBeenCalled()
    button('继续调整').click()
    await flush()
    expect(api.create).not.toHaveBeenCalled()
    await launchTask()
    expect(api.create).toHaveBeenCalledWith(
      expect.objectContaining({
        plannedStart: '2026-10-12',
        task: expect.objectContaining({ schedule: expect.objectContaining({ mode: 'AUTO' }) })
      })
    )
  })
  it('预览失败不发起任务，修改配置后迟到响应不打开旧预览', async () => {
    await mount()
    api.schedulePreview.mockRejectedValueOnce(new Error('排期服务暂不可用'))
    button('加入任务池').click()
    await flush()
    expect(host.textContent).toContain('排期服务暂不可用')
    expect(api.create).not.toHaveBeenCalled()
    let resolve!: (value: unknown) => void
    api.schedulePreview.mockImplementationOnce(
      () =>
        new Promise(done => {
          resolve = done
        })
    )
    button('任务编排').click()
    await flush()
    button('加入任务池').click()
    await flush()
    const name = element<HTMLInputElement>('[aria-label="任务名称"]')
    name.value = '迟到的输入'
    name.dispatchEvent(new Event('input'))
    resolve({ nodes: [], warnings: [] })
    await flush()
    expect(host.querySelector('[data-dialog]')).toBeNull()
    expect(api.create).not.toHaveBeenCalled()
  })
  it.each([false, true])('业务页总工时随选定发布版本展示并用于发起，先保存草稿=%s', async saved => {
    api.templateVersion.mockImplementation(async (_id: string, number?: number) => ({
      ...structuredClone(version),
      version: number ?? 3,
      task: { ...structuredClone(version.task), effectiveWorkMinutes: number === 1 ? 45 : 125 }
    }))
    await mount()
    expect(host.querySelector('[aria-label="任务标准总工时"]')).not.toBeNull()
    expect(element<HTMLInputElement>('[aria-label="任务标准总工时（小时）"]').value).toBe('2')
    expect(element<HTMLInputElement>('[aria-label="任务标准总工时（分钟）"]').value).toBe('5')
    await chooseVersion(1)
    expect(element<HTMLInputElement>('[aria-label="任务标准总工时（小时）"]').value).toBe('0')
    expect(element<HTMLInputElement>('[aria-label="任务标准总工时（分钟）"]').value).toBe('45')
    if (saved) {
      button('保存草稿').click()
      await flush()
      expect(api.draftSave.mock.calls[0]?.[0].content.task.effectiveWorkMinutes).toBe(45)
    }
    await launchTask()
    if (saved) {
      expect(api.draftSave.mock.calls.at(-1)?.[0].content).toMatchObject({
        templateVersion: 1,
        task: { effectiveWorkMinutes: 45 }
      })
      expect(api.draftPublish).toHaveBeenCalledOnce()
    } else {
      expect(api.create.mock.calls[0]?.[0]).toMatchObject({
        templateVersion: 1,
        task: { effectiveWorkMinutes: 45 }
      })
    }
  })
  it('模板和版本并排独立选择，初次选择主版本而不是最新版本', async () => {
    // 模板目录已过期，服务端当前主版本已经从 V3 切到 V1。
    api.templates.mockResolvedValue([{ ...version, publishedVersion: 3, primaryVersion: 3 }])
    api.templateVersion.mockImplementation(async (_id: string, number?: number) => ({
      ...structuredClone(version),
      version: number ?? 1
    }))
    await mount()
    expect(api.templateVersion).toHaveBeenCalledWith('template', undefined)
    expect(host.querySelector<HTMLSelectElement>('[aria-label="模板版本"]')?.value).toBe('1')
    expect(host.querySelectorAll('.task-launch__source-field')).toHaveLength(2)
    await launchTask()
    expect(api.create.mock.calls[0]?.[0]).toMatchObject({ templateId: 'template', templateVersion: 1 })
  })
  it('工作区明确传入历史版本时不改为主版本', async () => {
    api.templates.mockResolvedValue([{ ...version, publishedVersion: 3, primaryVersion: 3 }])
    api.templateVersion.mockImplementation(async (_id: string, number: number) => ({
      ...structuredClone(version),
      version: number
    }))
    await mount({ initialTemplateVersion: 1 })
    expect(api.templateVersion).toHaveBeenCalledWith('template', 1)
    expect(host.querySelector<HTMLSelectElement>('[aria-label="模板版本"]')?.value).toBe('1')
  })
  it('初始模板列表失败后重试仍保留入口明确指定的历史版本', async () => {
    api.templates.mockRejectedValueOnce(new Error('模板目录暂不可用'))
    api.templateVersion.mockImplementation(async (_id: string, number?: number) => ({
      ...structuredClone(version),
      version: number ?? 3
    }))
    await mount({ initialTemplateVersion: 1 })
    expect(api.templateVersion).not.toHaveBeenCalled()
    button('重试模板列表').click()
    await flush()
    expect(api.templateVersion).toHaveBeenCalledWith('template', 1)
    expect(host.querySelector<HTMLSelectElement>('[aria-label="模板版本"]')?.value).toBe('1')
  })
  it('更换版本取消保留选择和修改，确认后才替换配置并提交选定来源', async () => {
    await mount()
    button('任务编排').click()
    await flush()
    const title = element<HTMLInputElement>('[aria-label="任务名称"]')
    title.value = '本次不能丢失的修改'
    title.dispatchEvent(new Event('input'))
    await flush()
    api.confirm.mockResolvedValueOnce(false)
    await chooseVersion(1)
    expect(api.templateVersion).toHaveBeenCalledTimes(1)
    expect(title.value).toBe('本次不能丢失的修改')
    expect(host.querySelector<HTMLSelectElement>('[aria-label="模板版本"]')?.value).toBe('3')
    api.templateVersion.mockResolvedValueOnce({
      ...structuredClone(version),
      version: 1,
      task: { ...version.task, title: '历史配置' }
    })
    await chooseVersion(1)
    expect(title.value).toBe('历史配置')
    expect(host.querySelector<HTMLSelectElement>('[aria-label="模板版本"]')?.value).toBe('1')
    button('保存草稿').click()
    await flush()
    expect(api.draftSave.mock.calls[0]?.[0].content).toMatchObject({ templateVersion: 1, task: { title: '历史配置' } })
  })
  it('版本请求进行中锁定旧配置，失败保留原版本与内容', async () => {
    await mount()
    button('任务编排').click()
    await flush()
    let reject!: (error: Error) => void
    api.templateVersion.mockImplementationOnce(
      () =>
        new Promise((_resolve, fail) => {
          reject = fail
        })
    )
    await chooseVersion(1)
    expect(host.querySelector<HTMLInputElement>('[aria-label="任务名称"]')?.disabled).toBe(true)
    expect(host.querySelector<HTMLSelectElement>('[aria-label="模板版本"]')?.disabled).toBe(true)
    reject(new Error('历史快照加载中断'))
    await flush()
    expect(host.textContent).toContain('历史快照加载中断')
    expect(host.querySelector<HTMLInputElement>('[aria-label="任务名称"]')?.value).toBe('采购工作')
    expect(host.querySelector<HTMLInputElement>('[aria-label="任务名称"]')?.disabled).toBe(false)
    expect(host.querySelector<HTMLSelectElement>('[aria-label="模板版本"]')?.value).toBe('3')
    await launchTask()
    expect(api.create.mock.calls[0]?.[0].templateVersion).toBe(3)
  })
  it('恢复任务草稿始终固定旧版本，即使主版本已经改变', async () => {
    api.templates.mockResolvedValue([{ ...version, publishedVersion: 3, primaryVersion: 3 }])
    api.templateVersion.mockImplementation(async (_id: string, number: number) => ({
      ...structuredClone(version),
      version: number
    }))
    api.draftGet.mockResolvedValue({
      id: 'saved-draft',
      revision: 2,
      content: {
        task: structuredClone(version.task),
        nodes: [],
        templateId: version.id,
        templateVersion: 1,
        requestKey: 'draft-request'
      }
    })
    await mount({ draftId: 'saved-draft', initialTemplateVersion: 3 })
    expect(api.templateVersion).toHaveBeenCalledWith('template', 1)
    expect(api.templateVersion).not.toHaveBeenCalledWith('template', 3)
    expect(host.querySelector<HTMLSelectElement>('[aria-label="模板版本"]')?.value).toBe('1')
  })
  it('显式来源模板不在应用允许范围时拒绝加载，不绕过应用限制', async () => {
    await mount({ initialTemplateVersion: 1, templateIds: ['different-template'] })
    expect(api.templateVersion).not.toHaveBeenCalled()
    expect(host.textContent).toContain('该模板尚未发布或当前不可使用')
    button('保存草稿').click()
    await flush()
    expect(api.draftSave).not.toHaveBeenCalled()
  })
  it('旧模板版本目录迟到时不能覆盖新模板版本选项', async () => {
    let resolveOld!: (value: unknown[]) => void
    api.templateVersions.mockImplementationOnce(
      () =>
        new Promise(resolve => {
          resolveOld = resolve
        })
    )
    api.templates.mockResolvedValue([
      { ...version, publishedVersion: 3, primaryVersion: 3 },
      { ...version, id: 'other', name: '其他模板', publishedVersion: 8, primaryVersion: 8 }
    ])
    await mount()
    api.templateVersion.mockResolvedValueOnce({ ...structuredClone(version), id: 'other', version: 8 })
    api.templateVersions.mockResolvedValueOnce([{ version: 8, primary: true }])
    const select = element<HTMLSelectElement>('[aria-label="已发布模板"]')
    select.value = 'other'
    select.dispatchEvent(new Event('change'))
    await flush()
    resolveOld([{ version: 2, primary: true }])
    await flush()
    expect(host.querySelector<HTMLSelectElement>('[aria-label="模板版本"]')?.value).toBe('8')
    expect(Array.from(element<HTMLSelectElement>('[aria-label="模板版本"]').options).map(item => item.value)).toEqual([
      '',
      '8'
    ])
  })
  it('直接使用模板可选择应用和具体记录，提交仍固定原模板资源与版本', async () => {
    const before = structuredClone(version)
    await mount()
    expect(api.mine).toHaveBeenCalledOnce()
    expect(host.querySelector('[data-resources-locked="true"]')).not.toBeNull()
    await selectApplication('purchase')
    button('选择本次采购单').click()
    await flush()
    await launchTask()
    const command = api.create.mock.calls[0]?.[0] as TaskCreate
    expect(command).toMatchObject({ applicationId: 'purchase', project: record, business: null, existingRecord: null })
    expectFixed(command)
    expect(version).toEqual(before)
  })
  it.each(['construction', ''])('切换或清空归属为 %s 只清除旧记录，不清除模板数据配置', async next => {
    await mount()
    await selectApplication('purchase')
    button('选择本次采购单').click()
    await flush()
    await selectApplication(next)
    await launchTask()
    const command = api.create.mock.calls[0]?.[0] as TaskCreate
    expect(command).toMatchObject({ applicationId: next || null, project: null })
    expectFixed(command)
  })
  it('从记录页面使用模板保留锁定的应用与记录，不额外扫描目录', async () => {
    await mount({ initialApplicationId: 'purchase', initialApplicationName: '采购应用', initialProject: record })
    expect(api.mine).not.toHaveBeenCalled()
    expect(host.querySelector('[aria-label="关联应用"]')).toBeNull()
    expect(host.querySelector('[data-record-picker]')).toBeNull()
    expect(host.textContent).toContain('本次采购单')
    await launchTask()
    const command = api.create.mock.calls[0]?.[0] as TaskCreate
    expect(command).toMatchObject({ applicationId: 'purchase', project: record })
    expectFixed(command)
  })
  it('归属保持可选，不从反馈表单强制推导应用或记录', async () => {
    version.task!.binding = null
    await mount()
    await launchTask()
    const command = api.create.mock.calls[0]?.[0] as TaskCreate
    expect(command).toMatchObject({ applicationId: null, project: null })
    expectFixed(command)
  })
  it('应用目录失败可重试，保留任务名称和模板授权', async () => {
    api.mine.mockRejectedValueOnce(new Error('目录暂不可用'))
    await mount()
    expect(host.textContent).toContain('应用列表加载失败：目录暂不可用')
    button('任务编排').click()
    await flush()
    const input = host.querySelector<HTMLInputElement>('[aria-label="任务名称"]')!
    input.value = '本次采购安排'
    input.dispatchEvent(new Event('input'))
    button('业务关联').click()
    await flush()
    button('重试应用列表').click()
    await flush()
    expect(host.textContent).not.toContain('目录暂不可用')
    await selectApplication('purchase')
    await launchTask()
    const command = api.create.mock.calls[0]?.[0] as TaskCreate
    expect(command.task.title).toBe('本次采购安排')
    expectFixed(command)
  })
  it('保存草稿保留本次记录与固定模板版本，恢复后沿用原上下文锁定规则', async () => {
    await mount()
    await selectApplication('purchase')
    button('选择本次采购单').click()
    await flush()
    button('保存草稿').click()
    await flush()
    const saved = api.draftSave.mock.calls[0]?.[0]
    expect(saved.content).toMatchObject({ applicationId: 'purchase', project: record })
    expectFixed(saved.content)
    app?.unmount()
    host.remove()
    api.draftGet.mockResolvedValue({ ...saved, revision: 1 })
    await mount({ initialTemplateId: undefined, draftId: saved.id })
    expect(host.textContent).toContain('本次采购单')
    expect(host.textContent).toContain('采购应用')
    expect(host.querySelector('[aria-label="关联应用"]')).toBeNull()
    expect(host.querySelector('[data-resources-locked="true"]')).not.toBeNull()
  })
})

// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import Antd from 'ant-design-vue'
import TaskTemplates from '@/views/nocode/task-center/TaskTemplates.vue'
import TaskWorkBudgetFields from '@/views/nocode/task-center/TaskWorkBudgetFields.vue'
import { newTaskNode } from './task-center'
import type { SaveTaskTemplate, TaskTemplate } from '@/types/nocode/task-center'

const state = vi.hoisted(() => ({
  api: {
    templates: vi.fn(),
    members: vi.fn(),
    saveTemplate: vi.fn(),
    publishTemplate: vi.fn(),
    templateVersions: vi.fn(),
    templateVersion: vi.fn(),
    setPrimaryTemplateVersion: vi.fn()
  },
  confirm: vi.fn(),
  discard: vi.fn(),
  track: vi.fn(),
  refreshInstances: vi.fn(),
  manage: true
}))
vi.mock('@/nocode/platform', () => ({ useNocodePlatform: () => ({ taskCenter: state.api }) }))
vi.mock('@/utils/access', () => ({
  hasPermission: (key: string) => key !== 'nocode:task:manage-all' && (!key.endsWith(':template') || state.manage)
}))
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ userInfo: { id: 1 } }) }))
vi.mock('@/nocode/unsaved', () => ({ useUnsavedNavigation: (changed: () => boolean) => state.track(changed) }))
vi.mock('@/nocode/task-confirmation', () => ({
  useTaskConfirmation: () => ({ confirm: state.confirm, confirmDiscard: state.discard })
}))
vi.mock('@/components/os-table-page/OsTablePage.vue', () => ({
  default: defineComponent({
    props: ['columns', 'dataSource'],
    setup:
      (props, { slots }) =>
      () =>
        h('section', [
          slots.actions?.(),
          ...props.dataSource.map((record: TaskTemplate) =>
            h(
              'div',
              { 'data-row': record.id },
              props.columns.map((column: { key: string }) => slots.bodyCell?.({ column, record }))
            )
          )
        ])
  })
}))
vi.mock('@/views/nocode/task-center/TaskNodeEditor.vue', () => ({
  default: defineComponent({
    props: ['modelValue', 'root', 'readonly', 'inlineConfiguration'],
    setup: props => () =>
      h('div', { 'data-inline': String(props.inlineConfiguration) }, [
        h('input', {
          'aria-label': '总任务行内名称',
          value: props.root?.title,
          disabled: props.readonly,
          onInput: (event: Event) => {
            props.root.title = (event.target as HTMLInputElement).value
          }
        })
      ])
  })
}))
vi.mock('@/views/nocode/task-center/TaskNodeFields.vue', () => ({
  default: defineComponent({
    props: ['modelValue', 'section', 'readonly'],
    setup: props => () =>
      h('section', { 'data-section': props.section }, [
        h('input', {
          'aria-label': `${props.section}备注`,
          value: props.modelValue.description,
          disabled: props.readonly,
          onInput: (event: Event) => {
            props.modelValue.description = (event.target as HTMLInputElement).value
          }
        }),
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
vi.mock('@/views/nocode/task-center/TaskTemplateInstances.vue', () => ({
  default: defineComponent({
    props: ['templateId', 'publishedVersion'],
    emits: ['open'],
    setup(props, { emit, expose }) {
      expose({ refresh: state.refreshInstances })
      return () =>
        h(
          'button',
          {
            'data-instance-template': props.templateId,
            'data-version': props.publishedVersion,
            onClick: () => emit('open', 'task-instance')
          },
          '查看实例'
        )
    }
  })
}))
vi.mock('@/views/nocode/task-center/TaskDetail.vue', () => ({
  default: defineComponent({ props: ['id'], setup: props => () => h('div', { 'data-detail': props.id }) })
}))
vi.mock('@/views/nocode/task-center/TaskLaunchDrawer.vue', () => ({
  default: defineComponent({
    props: ['initialTemplateId', 'initialTemplateVersion'],
    emits: ['created'],
    setup:
      (props, { emit }) =>
      () =>
        h(
          'button',
          {
            'data-launch-template': props.initialTemplateId,
            'data-launch-version': props.initialTemplateVersion,
            onClick: () => emit('created', { task: { id: 'new-instance' } })
          },
          '确认新建实例'
        )
  })
}))

let app: App | undefined, host: HTMLDivElement
const fixture = (): TaskTemplate => ({
  id: 'template-1',
  name: '办公室装修',
  description: '模板说明',
  task: {
    ...newTaskNode(),
    id: 'root',
    title: '装修任务',
    dataPolicy: { version: 1, business: 'GROUP', feedback: 'GROUP' }
  },
  nodes: [],
  revision: 2,
  publishedVersion: 1,
  primaryVersion: 1,
  creatorId: 1,
  updatedAt: ''
})
const flush = async () => {
  for (let i = 0; i < 15; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function required<T>(value: T | null | undefined): T {
  if (!value) throw new Error('缺少测试目标')
  return value
}
const button = (label: string) =>
  required(
    Array.from(host.querySelectorAll('button')).find(
      item => (item.getAttribute('aria-label') || item.textContent)?.replace(/\s/g, '') === label.replace(/\s/g, '')
    )
  )
async function click(label: string) {
  button(label).click()
  await flush()
}
async function tab(label: string) {
  required(
    Array.from(host.querySelectorAll<HTMLElement>('[role="tab"]')).find(item => item.textContent === label)
  ).click()
  await flush()
}
async function input(selector: string, value: string) {
  const target = required(host.querySelector<HTMLInputElement>(selector))
  target.value = value
  target.dispatchEvent(new Event('input', { bubbles: true }))
  await flush()
}
async function chooseVersion(label: string) {
  required(host.querySelector('#task-template-version')).dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
  await flush()
  required(
    Array.from(document.querySelectorAll<HTMLElement>('.ant-select-item-option')).find(
      item => item.textContent?.trim() === label
    )
  ).click()
  await flush()
}
async function mount() {
  app = createApp(TaskTemplates)
  app.use(Antd)
  host = document.createElement('div')
  document.body.append(host)
  app.mount(host)
  await flush()
}
beforeEach(() => {
  vi.clearAllMocks()
  state.manage = true
  state.api.members.mockResolvedValue([])
  state.api.templates.mockResolvedValue([fixture()])
  state.api.templateVersions.mockResolvedValue([
    { version: 2, name: '新版装修', description: '', nodeCount: 0, publishedAt: '', primary: false },
    { version: 1, name: '办公室装修', description: '', nodeCount: 0, publishedAt: '', primary: true }
  ])
  state.api.templateVersion.mockImplementation(async (_id: string, version: number) => ({
    ...fixture(),
    version,
    name: `历史版本 ${version}`,
    task: { ...newTaskNode(), title: `V${version} 总任务` }
  }))
  state.confirm.mockResolvedValue(true)
  state.discard.mockResolvedValue(true)
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
  const style = window.getComputedStyle.bind(window)
  vi.spyOn(window, 'getComputedStyle').mockImplementation(element => style(element))
})
afterEach(() => {
  app?.unmount()
  host?.remove()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('任务模板完整工作区', () => {
  it('小时分钟编辑保存为整数分钟，切换历史只读且切回草稿保留输入', async () => {
    state.api.saveTemplate.mockImplementation(async (body: SaveTaskTemplate) => ({
      ...fixture(),
      ...body,
      revision: 3
    }))
    state.api.templateVersion.mockImplementation(async (_id: string, version: number) => ({
      ...fixture(),
      version,
      task: { ...newTaskNode(), title: '历史任务', effectiveWorkMinutes: 45 }
    }))
    await mount()
    await click('编辑草稿')
    await tab('业务关联')
    await input('input[aria-label="任务标准总工时（小时）"]', '2')
    await input('input[aria-label="任务标准总工时（分钟）"]', '15')
    await chooseVersion('V2')
    expect(host.querySelector<HTMLInputElement>('input[aria-label="任务标准总工时（分钟）"]')?.value).toBe('45')
    expect(host.querySelector<HTMLInputElement>('input[aria-label="任务标准总工时（小时）"]')?.disabled).toBe(true)
    await chooseVersion('草稿 · 可编辑副本')
    expect(host.querySelector<HTMLInputElement>('input[aria-label="任务标准总工时（小时）"]')?.value).toBe('2')
    expect(host.querySelector<HTMLInputElement>('input[aria-label="任务标准总工时（分钟）"]')?.value).toBe('15')
    await click('保存草稿')
    expect(state.api.saveTemplate.mock.calls[0]?.[0].task.effectiveWorkMinutes).toBe(135)
  })
  it('历史版本只读并可按该版本发起，不提供保存或发布快照的入口', async () => {
    await mount()
    await click('编辑草稿')
    await chooseVersion('V2')
    expect(state.api.templateVersion).toHaveBeenCalledWith('template-1', 2)
    expect(host.querySelector<HTMLInputElement>('[aria-label="总任务行内名称"]')?.value).toBe('V2 总任务')
    expect(host.querySelector<HTMLInputElement>('[aria-label="总任务行内名称"]')?.disabled).toBe(true)
    expect(Array.from(host.querySelectorAll('button')).some(item => item.textContent?.trim() === '保存草稿')).toBe(
      false
    )
    await click('使用此版本发起')
    expect(host.querySelector('[data-launch-template="template-1"][data-launch-version="2"]')).not.toBeNull()
  })
  it('草稿修改在取消切换和查看历史后都保留，设置主版本仅更新修订号', async () => {
    state.api.setPrimaryTemplateVersion.mockResolvedValue({ ...fixture(), revision: 3, primaryVersion: 2 })
    await mount()
    await click('编辑草稿')
    await input('[aria-label="总任务行内名称"]', '草稿本地修改')
    state.confirm.mockResolvedValueOnce(false)
    await chooseVersion('V2')
    expect(state.api.templateVersion).not.toHaveBeenCalled()
    expect(host.querySelector<HTMLInputElement>('[aria-label="总任务行内名称"]')?.value).toBe('草稿本地修改')
    await chooseVersion('V2')
    await click('设为主版本')
    expect(state.api.setPrimaryTemplateVersion).toHaveBeenCalledWith('template-1', 2, 2)
    await chooseVersion('草稿 · 可编辑副本')
    expect(host.querySelector<HTMLInputElement>('[aria-label="总任务行内名称"]')?.value).toBe('草稿本地修改')
    expect(required(state.track.mock.calls[0])[0]()).toBe(true)
    state.api.saveTemplate.mockImplementation(async (body: SaveTaskTemplate) => ({
      ...fixture(),
      ...body,
      revision: 4,
      primaryVersion: 2
    }))
    await click('保存草稿')
    expect(state.api.saveTemplate.mock.calls.at(-1)?.[0].expectedRevision).toBe(3)
    expect(state.api.saveTemplate.mock.calls.at(-1)?.[0].task.title).toBe('草稿本地修改')
  })
  it('切换版本失败保留草稿；主版本切换冲突不假装成功', async () => {
    state.api.templateVersion.mockRejectedValueOnce(new Error('快照读取失败'))
    await mount()
    await click('编辑草稿')
    await chooseVersion('V2')
    expect(host.textContent).toContain('快照读取失败')
    expect(host.querySelector<HTMLInputElement>('[aria-label="总任务行内名称"]')?.disabled).toBe(false)
    await chooseVersion('V2')
    state.api.setPrimaryTemplateVersion.mockRejectedValueOnce(new Error('模板已被其他人修改'))
    await click('设为主版本')
    expect(host.textContent).toContain('模板已被其他人修改')
    expect(button('设为主版本').disabled).toBe(false)
    expect(state.api.saveTemplate).not.toHaveBeenCalled()
  })
  it('新视图未配置标准工时可保存草稿和发布，自动预算留空不阻塞', async () => {
    const row = fixture()
    row.task!.workTotalMode = 'AUTO'
    row.task!.entries = [
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
    state.api.templates.mockResolvedValue([row])
    await mount()
    await click('编辑草稿')
    state.api.saveTemplate.mockImplementation(async (body: SaveTaskTemplate) => ({ ...row, ...body, revision: 3 }))
    await tab('任务编排')
    await input('[aria-label="总任务行内名称"]', '未配置工时的草稿')
    await click('保存草稿')
    expect(state.api.saveTemplate).toHaveBeenCalledOnce()
    await click('发布')
    expect(state.api.publishTemplate).toHaveBeenCalledOnce()
    expect(host.textContent).not.toContain('请设置标准工时')
  })
  it('首次发布自动设主版本', async () => {
    state.api.templates.mockResolvedValue([{ ...fixture(), publishedVersion: null, primaryVersion: null }])
    state.api.publishTemplate.mockResolvedValue({ version: 1 })
    await mount()
    await click('编辑草稿')
    await click('发布')
    expect(state.api.publishTemplate).toHaveBeenCalledWith('template-1', 2, true)
  })
  it('已有主版本时可明确勾选发布后替换主版本', async () => {
    state.api.publishTemplate.mockResolvedValue({ version: 2 })
    await mount()
    await click('编辑草稿')
    required(host.querySelector<HTMLInputElement>('input[type="checkbox"]')).click()
    await flush()
    await click('发布')
    expect(state.api.publishTemplate).toHaveBeenCalledWith('template-1', 2, true)
  })
  it('基本信息字段可独立编辑，多行说明在收起展开后保留且不会自动保存', async () => {
    await mount()
    await click('新建模板')
    const name = required(host.querySelector<HTMLInputElement>('[aria-label="模板名称"]'))
    const description = required(host.querySelector<HTMLTextAreaElement>('[aria-label="模板说明"]'))
    expect(name.maxLength).toBe(160)
    expect(description.placeholder).toContain('选填')
    await input('[aria-label="模板名称"]', '施工标准模板')
    await input('[aria-label="模板说明"]', '适用于办公室装修\n先勘察，再施工，最后验收')
    await click('收起基本信息')
    expect(host.querySelector('[aria-label="模板说明"]')).toBeNull()
    await click('编辑基本信息')
    expect(host.querySelector<HTMLInputElement>('[aria-label="模板名称"]')?.value).toBe('施工标准模板')
    expect(host.querySelector<HTMLTextAreaElement>('[aria-label="模板说明"]')?.value).toBe(
      '适用于办公室装修\n先勘察，再施工，最后验收'
    )
    expect(state.api.saveTemplate).not.toHaveBeenCalled()
  })
  it('主页面合并业务与反馈为三个页签，切换保留同一草稿，总任务名称可独立修改', async () => {
    await mount()
    await click('新建模板')
    expect(host.querySelector('.task-template-workspace')).not.toBeNull()
    expect(document.querySelector('.ant-drawer')).toBeNull()
    expect(Array.from(host.querySelectorAll('[role="tab"]')).map(item => item.textContent)).toEqual([
      '任务编排',
      '业务关联',
      '任务实例'
    ])
    await input('[placeholder="例如：标准施工项目模板"]', '新建装修模板')
    expect(host.querySelector<HTMLInputElement>('[aria-label="总任务行内名称"]')?.value).toBe('新建装修模板')
    await input('[aria-label="总任务行内名称"]', '装修项目')
    await input('[placeholder="例如：标准施工项目模板"]', '更新模板名')
    expect(host.querySelector<HTMLInputElement>('[aria-label="总任务行内名称"]')?.value).toBe('装修项目')
    await tab('业务关联')
    expect(host.querySelector('[data-section="feedback"]')).toBeNull()
    await input('[aria-label="business备注"]', '业务配置草稿')
    await tab('任务编排')
    await tab('业务关联')
    expect(host.querySelector<HTMLInputElement>('[aria-label="business备注"]')?.value).toBe('业务配置草稿')
    await tab('任务编排')
    expect(host.querySelector<HTMLInputElement>('[aria-label="总任务行内名称"]')?.value).toBe('装修项目')
    expect(button('发布').disabled).toBe(true)
    expect(required(state.track.mock.calls[0])[0]()).toBe(true)
    expect(state.api.saveTemplate).not.toHaveBeenCalled()
  })
  it('保存留在当前页签，发布刷新修订号，实例按模板和发布版本打开', async () => {
    let current = fixture()
    state.api.templates.mockImplementation(async () => [current])
    state.api.saveTemplate.mockImplementation(
      async (value: SaveTaskTemplate) =>
        (current = { ...current, ...value, id: current.id, revision: current.revision + 1 })
    )
    state.api.publishTemplate.mockImplementation(async () => {
      current = { ...current, revision: current.revision + 1, publishedVersion: 2 }
      return { ...current, version: 2 }
    })
    await mount()
    await click('编辑草稿')
    await tab('业务关联')
    await input('[aria-label="business备注"]', '新业务配置')
    await click('保存草稿')
    expect(host.querySelector('[role="tab"][aria-selected="true"]')?.textContent).toBe('业务关联')
    expect(required(state.track.mock.calls[0])[0]()).toBe(false)
    expect(state.api.publishTemplate).not.toHaveBeenCalled()
    await click('发布')
    expect(state.api.publishTemplate).toHaveBeenCalledWith('template-1', 3, false)
    expect(host.textContent).toContain('最新发布 V2')
    expect(host.querySelector('[role="tab"][aria-selected="true"]')?.textContent).toBe('业务关联')
    await input('[aria-label="business备注"]', '下一版修改')
    await click('保存草稿')
    expect(state.api.saveTemplate.mock.calls.at(-1)?.[0].expectedRevision).toBe(4)
    await tab('任务实例')
    expect(host.querySelector('[data-instance-template="template-1"][data-version="2"]')).not.toBeNull()
    await click('查看实例')
    expect(host.querySelector('[data-detail="task-instance"]')).not.toBeNull()
  })
  it('返回列表保护未保存内容，取消离开不丢输入', async () => {
    await mount()
    await click('编辑草稿')
    await input('[aria-label="总任务行内名称"]', '不能丢失')
    state.discard.mockResolvedValueOnce(false)
    await click('返回模板列表')
    expect(state.discard).toHaveBeenCalledWith(true)
    expect(host.querySelector<HTMLInputElement>('[aria-label="总任务行内名称"]')?.value).toBe('不能丢失')
    await click('返回模板列表')
    expect(host.querySelector('.task-template-workspace')).toBeNull()
    await click('编辑草稿')
    expect(host.querySelector<HTMLInputElement>('[aria-label="总任务行内名称"]')?.value).toBe('装修任务')
  })
  it('保存冲突保留输入并显示错误，不自动发布或离开', async () => {
    state.api.saveTemplate.mockRejectedValueOnce(new Error('模板已被其他人修改'))
    await mount()
    await click('编辑草稿')
    await input('[aria-label="总任务行内名称"]', '待合并的修改')
    await click('保存草稿')
    expect(host.textContent).toContain('模板已被其他人修改')
    expect(host.querySelector<HTMLInputElement>('[aria-label="总任务行内名称"]')?.value).toBe('待合并的修改')
    expect(required(state.track.mock.calls[0])[0]()).toBe(true)
    expect(state.api.publishTemplate).not.toHaveBeenCalled()
  })
  it('无编辑权限仍可查看模板和实例，所有配置只读', async () => {
    state.manage = false
    await mount()
    await click('查看')
    expect(host.textContent).toContain('只读')
    expect(host.querySelector<HTMLInputElement>('[aria-label="总任务行内名称"]')?.disabled).toBe(true)
    expect(Array.from(host.querySelectorAll('button')).some(item => item.textContent === '保存草稿')).toBe(false)
    await tab('业务关联')
    expect(host.querySelector<HTMLInputElement>('[aria-label="business备注"]')?.disabled).toBe(true)
    await tab('任务实例')
    expect(host.querySelector('[data-instance-template="template-1"]')).not.toBeNull()
    expect(state.api.saveTemplate).not.toHaveBeenCalled()
  })
  it('发布成功但刷新失败时阻止旧修订号继续提交，重试恢复编辑', async () => {
    state.api.publishTemplate.mockResolvedValue({ version: 2 })
    await mount()
    await click('编辑草稿')
    state.api.templates.mockRejectedValueOnce(new Error('连接中断'))
    await click('发布')
    expect(host.textContent).toContain('模板已发布，但最新草稿加载失败')
    expect(button('保存草稿').disabled).toBe(true)
    expect(host.querySelector<HTMLInputElement>('[aria-label="总任务行内名称"]')?.disabled).toBe(true)
    state.api.templates.mockResolvedValue([{ ...fixture(), revision: 3, publishedVersion: 2 }])
    await click('重新加载草稿')
    expect(button('保存草稿').disabled).toBe(false)
    expect(host.textContent).not.toContain('连接中断')
  })
  it('重新加载草稿期间互斥并锁定工作区，不能重复请求后覆盖恢复后的输入', async () => {
    state.api.publishTemplate.mockResolvedValue({ version: 2 })
    await mount()
    await click('编辑草稿')
    state.api.templates.mockRejectedValueOnce(new Error('连接中断'))
    await click('发布')
    let resolveReload: ((value: TaskTemplate[]) => void) | undefined
    state.api.templates.mockImplementationOnce(
      () =>
        new Promise<TaskTemplate[]>(resolve => {
          resolveReload = resolve
        })
    )
    const previousCalls = state.api.templates.mock.calls.length
    await click('重新加载草稿')
    expect(button('重新加载草稿').disabled).toBe(true)
    expect(button('返回模板列表').disabled).toBe(true)
    expect(host.querySelector<HTMLInputElement>('[aria-label="总任务行内名称"]')?.disabled).toBe(true)
    await click('重新加载草稿')
    expect(state.api.templates).toHaveBeenCalledTimes(previousCalls + 1)
    required(resolveReload)([{ ...fixture(), revision: 3, publishedVersion: 2 }])
    await flush()
    expect(button('保存草稿').disabled).toBe(false)
    await input('[aria-label="总任务行内名称"]', '恢复后继续编辑')
    expect(required(state.track.mock.calls[0])[0]()).toBe(true)
    expect(host.querySelector<HTMLInputElement>('[aria-label="总任务行内名称"]')?.value).toBe('恢复后继续编辑')
  })
  it('先查看实例页签再使用模板创建时刷新已有列表，不重建实例页签', async () => {
    await mount()
    await click('编辑草稿')
    await tab('任务实例')
    const instanceTab = host.querySelector('[data-instance-template="template-1"]')
    await click('使用已发布模板')
    await click('确认新建实例')
    expect(state.refreshInstances).toHaveBeenCalledTimes(1)
    expect(host.querySelector('[data-instance-template="template-1"]')).toBe(instanceTab)
    expect(host.querySelector('[role="tab"][aria-selected="true"]')?.textContent).toBe('任务实例')
    expect(host.querySelector('[data-detail="new-instance"]')).not.toBeNull()
  })
})

import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue'
import Designer from '@/views/bpm/components/simple-process-design/components/simple-process-designer.vue'
import { newWorkflowTaskSetting } from '@/nocode/workflow-task-node'
import { BpmNodeTypeEnum } from '@/types/bpm'
import type { SimpleFlowNode } from '@/views/bpm/components/simple-process-design/consts'
import type { FormSource } from '@/views/bpm/model/form/node-form'

vi.mock('@/views/bpm/model/form/NodeFormEditor.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/components/UserSelector/index.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/api/system/user', () => ({ getUsersByIds: vi.fn().mockResolvedValue([]) }))
vi.mock('@/views/bpm/components/simple-process-design/components/process-node-tree.vue', () => ({
  default: {
    props: ['flowNode'],
    emits: ['edit'],
    template:
      '<button data-testid="edit-task" @click="$emit(\'edit\', flowNode.childNode, { parentNode: flowNode })">编辑任务节点</button>'
  }
}))
vi.mock('@/views/bpm/model/form/WorkflowTaskNodeEditor.vue', () => ({
  default: {
    props: ['modelValue', 'formId'],
    emits: ['busy'],
    template: `<div data-testid="task-editor" :data-form-id="formId">
      <button data-testid="set-date" @click="modelValue.task.schedule.mode = 'FIXED'; modelValue.task.schedule.fixedStart = '2026-10-10'; modelValue.task.schedule.fixedEnd = '2026-10-11'">排期</button>
      <button data-testid="set-title" @click="modelValue.task.title = '二次修改后的任务'">标题</button>
      <button data-testid="loading" @click="$emit('busy', true)">加载中</button>
    </div>`
  }
}))
const disposers: Array<() => void> = []
afterEach(() => disposers.splice(0).forEach(dispose => dispose()))
async function settle() {
  for (let i = 0; i < 4; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function flow(): SimpleFlowNode {
  const setting = newWorkflowTaskSetting()
  setting.task.title = '办公室装修'
  setting.source = 'TEMPLATE'
  setting.templateId = 'template-1'
  setting.templateVersion = 1
  setting.task.assignmentMode = 'ASSIGNED'
  setting.people = [{ nodeId: setting.task.id, role: 'ASSIGNEE', source: 'INITIATOR' }]
  return {
    id: 'start',
    name: '发起人',
    type: BpmNodeTypeEnum.START_USER_NODE,
    childNode: {
      id: 'task-node',
      name: '装修任务',
      type: BpmNodeTypeEnum.TASK_CENTER_NODE,
      taskCenterSetting: setting,
      childNode: { id: 'end', name: '结束', type: BpmNodeTypeEnum.END_EVENT_NODE }
    }
  }
}
async function mount(model = flow()) {
  const state = reactive({
    model,
    inheritedForm: undefined as FormSource | undefined,
    modelFormId: undefined as string | undefined,
    modelFormType: undefined as number | undefined
  })
  const designer = ref<{ validate: () => Promise<SimpleFlowNode> }>()
  const host = document.createElement('div')
  document.body.append(host)
  const app = createApp(() =>
    h(Designer, {
      ref: designer,
      modelValue: state.model,
      inheritedForm: state.inheritedForm,
      modelFormId: state.modelFormId,
      modelFormType: state.modelFormType,
      'onUpdate:modelValue': (value: SimpleFlowNode) => (state.model = value)
    })
  )
  const wrapper = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', [slots.default?.(), slots.footer?.()])
  })
  for (const tag of [
    'a-drawer',
    'a-form',
    'a-form-item',
    'a-popconfirm',
    'a-space',
    'a-collapse',
    'a-collapse-panel',
    'a-tag',
    'a-divider',
    'a-alert',
    'a-select',
    'a-input-number',
    'a-switch'
  ])
    app.component(tag, wrapper)
  app.component(
    'a-button',
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('button', slots.default?.())
    })
  )
  for (const tag of ['a-input', 'a-textarea']) app.component(tag, { template: '<input />' })
  app.mount(host)
  disposers.push(() => {
    app.unmount()
    host.remove()
  })
  await settle()
  return { state, host, designer }
}
async function click(host: HTMLElement, id: string) {
  host.querySelector<HTMLButtonElement>(`[data-testid="${id}"]`)!.click()
  await settle()
}

describe('流程设计器保存任务节点', () => {
  it('发起表单切换为无需表单后，任务人员字段不得继续使用旧流程表单', async () => {
    const { state, host } = await mount()
    state.inheritedForm = { kind: 'FLOW_FORM', formId: 'existing-form' }
    state.modelFormId = 'existing-form'
    state.modelFormType = 10
    await click(host, 'edit-task')
    expect(host.querySelector('[data-testid="task-editor"]')?.getAttribute('data-form-id')).toBe('existing-form')

    // 向导只在提交 payload 时清理旧 formId，切换时不能将它当作仍有效的字段来源。
    state.inheritedForm = { kind: 'NONE' }
    state.modelFormType = 0
    await settle()
    expect(host.querySelector('[data-testid="task-editor"]')?.getAttribute('data-form-id')).toBeNull()
  })
  it.each<FormSource>([{ kind: 'NONE' }, { kind: 'SYSTEM_ROUTE', createPath: '/business/create' }])(
    '显式 $kind 来源优先于残留的旧版流程表单配置',
    async source => {
      const { state, host } = await mount()
      state.inheritedForm = source
      state.modelFormId = 'stale-form'
      state.modelFormType = 10
      await click(host, 'edit-task')
      expect(host.querySelector('[data-testid="task-editor"]')?.getAttribute('data-form-id')).toBeNull()
    }
  )
  it('新版流程表单使用当前来源，不回退旧表单 ID', async () => {
    const { state, host } = await mount()
    state.inheritedForm = { kind: 'FLOW_FORM', formId: 'current-form' }
    state.modelFormId = 'stale-form'
    state.modelFormType = 10
    await click(host, 'edit-task')
    expect(host.querySelector('[data-testid="task-editor"]')?.getAttribute('data-form-id')).toBe('current-form')

    state.inheritedForm = { kind: 'FLOW_FORM' }
    await settle()
    expect(host.querySelector('[data-testid="task-editor"]')?.getAttribute('data-form-id')).toBeNull()
  })
  it('没有新版来源的旧版流程表单仍可加载人员字段', async () => {
    const { state, host } = await mount()
    state.modelFormId = 'legacy-form'
    state.modelFormType = 10
    await click(host, 'edit-task')
    expect(host.querySelector('[data-testid="task-editor"]')?.getAttribute('data-form-id')).toBe('legacy-form')
  })
  it.each([0, 20, undefined])('旧版表单类型 %s 不是流程表单时，不加载残留表单 ID', async formType => {
    const { state, host } = await mount()
    state.modelFormId = 'stale-form'
    state.modelFormType = formType
    await click(host, 'edit-task')
    expect(host.querySelector('[data-testid="task-editor"]')?.getAttribute('data-form-id')).toBeNull()
  })
  it('日期回传标准化后仍编辑同一个节点，发布数据保留明确版本及动态人员来源', async () => {
    const { state, host, designer } = await mount()
    await click(host, 'edit-task')
    await click(host, 'set-date')
    await click(host, 'set-title')
    const saved = await designer.value!.validate()
    expect(state.model.childNode!.taskCenterSetting!.task.title).toBe('二次修改后的任务')
    expect(saved.childNode!.taskCenterSetting).toMatchObject({
      source: 'TEMPLATE',
      templateId: 'template-1',
      templateVersion: 1,
      people: [{ role: 'ASSIGNEE', source: 'INITIATOR' }]
    })
    expect(typeof saved.childNode!.taskCenterSetting!.task.schedule.fixedStart).toBe('number')
    expect(typeof saved.childNode!.taskCenterSetting!.task.schedule.fixedEnd).toBe('number')
    expect(saved.childNode!.taskCenterSetting!.task.assigneeId).toBeFalsy()
  })
  it('任务配置仍在加载时不可保存流程', async () => {
    const { host, designer } = await mount()
    await click(host, 'edit-task')
    await click(host, 'loading')
    await expect(designer.value!.validate()).rejects.toThrow('任务配置加载中')
  })
  it('未固定模板版本会明确阻止保存', async () => {
    const model = flow()
    delete model.childNode!.taskCenterSetting!.templateVersion
    const { designer } = await mount(model)
    await expect(designer.value!.validate()).rejects.toThrow('版本')
  })
})

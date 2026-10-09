// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import TinyPageDesigner from '@/views/nocode/application/components/TinyPageDesigner.vue'
import { pageSchema } from './page-schema'
import { NodeKind, uiNode, type UiNode } from '@/types/nocode/application-ui'
import type { ApplicationResource, PublishedObject } from '@/types/nocode/application'

vi.mock('@/components/InfraUpload.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/nocode/application/components/TaskViewEditor.vue', () => ({ default: { render: () => null } }))
const resources: ApplicationResource[] = [
  { id: 'project-form', kind: 'FORM', code: 'project', name: '项目资料', config: { objectId: 'project' } },
  { id: 'project-brief', kind: 'FORM', code: 'brief', name: '项目摘要', config: { objectId: 'project' } },
  { id: 'log-form', kind: 'FORM', code: 'log', name: '施工日志', config: { objectId: 'log' } },
  { id: 'project-view', kind: 'VIEW', code: 'view', name: '项目台账', config: { objectId: 'project' } }
]
const objects = {
  project: { definition: { objectName: '施工项目', relations: [] } },
  log: { definition: { objectName: '施工日志', relations: [] } }
} as unknown as Record<string, PublishedObject>
let app: App, host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount(node: UiNode, contextObjectId?: string) {
  const nodes = [node]
  app = createApp(TinyPageDesigner, { nodes, resources, objects, contextObjectId })
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.())
  })
  for (const name of [
    'AForm',
    'ASpace',
    'ATag',
    'ATextarea',
    'ACollapse',
    'ACollapsePanel',
    'AInputNumber',
    'ACheckbox',
    'AEmpty',
    'ASlider',
    'ARadio',
    'ARadioGroup',
    'AInput'
  ])
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
    'AFormItem',
    defineComponent({
      props: ['label'],
      setup:
        (props, { slots }) =>
        () =>
          h('section', { 'data-label': props.label }, [h('label', props.label), slots.default?.()])
    })
  )
  app.component(
    'ASelect',
    defineComponent({
      props: ['value', 'options'],
      setup: props => () =>
        h(
          'select',
          { value: props.value },
          (props.options || []).map((option: { value: string; label: string }) =>
            h('option', { value: option.value }, option.label)
          )
        )
    })
  )
  app.component(
    'AAlert',
    defineComponent({
      props: ['message', 'description'],
      setup: props => () => h('aside', [props.message, props.description])
    })
  )
  host = document.createElement('div')
  document.body.append(host)
  const exposed = app.mount(host) as unknown as { getNodes: () => UiNode[]; hasChanges: () => boolean }
  await flush()
  const frame = host.querySelector('iframe')
  if (!frame) throw new Error('页面设计器未挂载画布')
  const message = async (type: string, data = {}) => {
    window.dispatchEvent(
      new MessageEvent('message', {
        origin: location.origin,
        source: frame.contentWindow,
        data: { channel: 'os-page-designer', type, ...data }
      })
    )
    await flush()
  }
  await message('READY')
  const schema = pageSchema(nodes)
  await message('CHANGE', { schema, selected: schema.children[0] })
  return { exposed, nodes }
}
afterEach(() => {
  app?.unmount()
  host?.remove()
})
describe('记录页任务列表直接跟随当前页面记录', () => {
  it('旧配置不再显示表单选择，不改变已有绑定或产生草稿', async () => {
    const { exposed, nodes } = await mount(
      uiNode(NodeKind.TASKS, { id: 'tasks', resourceId: 'project-form' }),
      'project'
    )
    expect(host.textContent).toContain('任务范围')
    expect(host.querySelector('[data-label="任务范围"]')?.textContent).toContain('当前页面记录')
    expect(host.querySelector('[data-label="任务范围"] select')).toBeNull()
    expect(host.textContent).not.toContain('任务关联表单')
    expect(host.textContent).not.toContain('当前记录表单')
    expect(host.querySelector('[data-label="业务资源"]')).toBeNull()
    expect(host.textContent).not.toContain('任务关联范围：')
    expect(host.textContent).not.toContain('不是任务填写或反馈表单')
    expect(host.querySelector('.properties aside')).toBeNull()
    expect(exposed.getNodes()[0]?.resourceId).toBe('project-form')
    expect(exposed.getNodes()[0]?.taskView?.businessFormId).toBeUndefined()
    expect(exposed.hasChanges()).toBe(false)
    expect(nodes[0]?.resourceId).toBe('project-form')
  })
  it('新建记录任务区块无需任何表单，不自动注入资源绑定', async () => {
    const { exposed } = await mount(uiNode(NodeKind.TASKS, { id: 'tasks' }), 'project')
    expect(host.querySelector('[data-label="任务范围"]')?.textContent).toContain('当前页面记录')
    expect(host.textContent).not.toContain('业务表单（可选）')
    expect(exposed.getNodes()[0]?.resourceId).toBeFalsy()
    expect(exposed.getNodes()[0]?.taskView?.businessFormId).toBeUndefined()
    expect(exposed.hasChanges()).toBe(false)
  })
  it('不把应用级可选业务表单误称为当前记录的关联表单', async () => {
    await mount(uiNode(NodeKind.TASKS, { id: 'tasks' }))
    expect(host.textContent).toContain('业务表单（可选）')
    expect(host.textContent).not.toContain('任务关联表单')
    expect(host.textContent).toContain('展示当前应用的任务')
  })
  it('不改记录详情组件的名称和候选类型', async () => {
    await mount(uiNode(NodeKind.DETAIL, { id: 'detail', resourceId: 'project-form' }), 'project')
    const options = Array.from(host.querySelectorAll('[data-label="业务资源"] option'))
    expect(options.map(option => option.textContent)).toEqual(['项目资料', '项目摘要'])
    expect(host.textContent).not.toContain('任务关联表单')
  })
})

import { describe, it, expect } from 'vitest'
import { pageNodes, pageSchema } from './page-schema'
import { nodesToRules, rulesToNodes } from './application-ui'
import { uiNode, NodeKind } from '../types/nocode/application-ui'
import { findPageNode } from './page-context'

describe('page and form contracts', () => {
  it('看板节点只往返资源引用，不携带固定版本或运行查询参数', () => {
    const dashboard = uiNode(NodeKind.REPORT_DASHBOARD, {
      id: 'dashboard',
      resourceId: 'sales_dashboard',
      text: '经营看板'
    })
    const schema = pageSchema([dashboard])
    expect(schema.children[0]?.componentName).toBe('OsReportDashboard')
    expect(pageNodes(schema)[0]).toMatchObject({
      type: 'REPORT_DASHBOARD',
      resourceId: 'sales_dashboard',
      id: 'dashboard',
      binding: null
    })
    expect(schema.children[0]?.props).not.toHaveProperty('dashboard')
    expect(schema.children[0]?.props).not.toHaveProperty('parameters')
  })
  it('keeps an application task block without forcing a business form', () => {
    const tasks = uiNode(NodeKind.TASKS, { text: '应用任务' })
    expect(pageNodes(pageSchema([tasks]))[0]).toMatchObject({ type: 'TASKS', resourceId: null })
  })
  it('preserves current-record relationship binding through nested page containers', () => {
    const related = uiNode(NodeKind.RELATED, {
      resourceId: 'accounts',
      binding: { relationId: 'company', direction: 'INCOMING' }
    })
    const nodes = [uiNode(NodeKind.TABS, { children: [uiNode(NodeKind.TAB, { text: '财务', children: [related] })] })]
    const restored = pageNodes(pageSchema(nodes))
    expect(findPageNode(restored, related.id)?.binding).toEqual(related.binding)
    expect(findPageNode(restored, related.id)?.resourceId).toBe('accounts')
    expect(restored[0]!.children[0]!.text).toBe('财务')
  })
  it('rejects scripts, unknown components and duplicate node identities', () => {
    expect(() => pageNodes({ componentName: 'Page', children: [{ componentName: 'script' }] })).toThrow('OS')
    expect(() =>
      pageNodes({
        componentName: 'Page',
        children: [{ componentName: 'OsText', props: { text: { type: 'JSExpression', value: 'fetch()' } } }]
      })
    ).toThrow('表达式')
    const node = { componentName: 'OsCard', id: 'same' }
    expect(() => pageNodes({ componentName: 'Page', children: [node, node] })).toThrow('重复')
  })
  it('keeps form presentation without weakening base read-only and validation', () => {
    const node = uiNode(NodeKind.FIELD, {
      fieldId: 'name',
      presentation: { label: '公司名称', placeholder: '请输入全称', help: '按营业执照填写', readOnly: true }
    })
    const fields = [
      { type: 'input', field: 'name', title: '名称', props: { disabled: true }, validate: [{ required: true }] }
    ]
    const saved = rulesToNodes(nodesToRules([node], fields, true))[0]!
    expect(saved.presentation).toEqual(node.presentation)
    const rules = nodesToRules([saved], fields)
    expect(rules[0]!.title).toBe('公司名称')
    expect(rules[0]!.props?.disabled).toBe(true)
    expect(rules[0]!.validate).toEqual(fields[0]!.validate)
  })
  it('retains native upload handlers and form tabs without storing executable rules', () => {
    const handler = () => undefined
    const field = uiNode(NodeKind.FIELD, { fieldId: 'attachment' })
    const tabs = [uiNode(NodeKind.TABS, { children: [uiNode(NodeKind.TAB, { text: '附件', children: [field] })] })]
    const fields = [{ type: 'nocodeFiles', field: 'attachment', props: { onUploadStatus: handler } }]
    expect(() => nodesToRules(tabs, fields)).not.toThrow()
    const saved = rulesToNodes(nodesToRules(tabs, fields, true))
    expect(saved[0]!.children[0]!.text).toBe('附件')
    expect(JSON.stringify(saved)).not.toContain('onUploadStatus')
    expect(nodesToRules(tabs, fields)[0]!.children?.[0]).toMatchObject({ props: { forceRender: true } })
  })
  it('round trips record attachment and process blocks with stable form bindings', () => {
    const nodes = [
      uiNode(NodeKind.ATTACHMENTS, { resourceId: 'form' }),
      uiNode(NodeKind.PROCESSES, { resourceId: 'form' })
    ]
    expect(pageNodes(pageSchema(nodes)).map(n => [n.type, n.resourceId])).toEqual([
      ['ATTACHMENTS', 'form'],
      ['PROCESSES', 'form']
    ])
  })
  it('keeps task form binding and identity when composed with project finance and files', () => {
    const tasks = uiNode(NodeKind.TASKS, { resourceId: 'project-form', text: '本项目任务' })
    const nodes = [
      uiNode(NodeKind.TABS, {
        children: [
          uiNode(NodeKind.TAB, { text: '执行', children: [tasks] }),
          uiNode(NodeKind.TAB, {
            text: '财务与文件',
            children: [
              uiNode(NodeKind.RELATED, {
                resourceId: 'finance',
                binding: { relationId: 'project', direction: 'INCOMING' }
              }),
              uiNode(NodeKind.ATTACHMENTS, { resourceId: 'project-form' })
            ]
          })
        ]
      })
    ]
    const restored = pageNodes(pageSchema(nodes))
    expect(findPageNode(restored, tasks.id)).toMatchObject({
      type: 'TASKS',
      resourceId: 'project-form',
      text: '本项目任务'
    })
    expect(restored[0]!.children[1]!.children[0]!.binding?.relationId).toBe('project')
  })
  it('任务固定条件、业务表单及列顺序通过设计器往返不丢失', () => {
    const taskView = {
      businessFormId: 'work-form',
      columnKeys: ['title', 'business:quantity', 'status'],
      conditions: {
        logic: 'AND' as const,
        items: [{ type: 'condition' as const, field: 'quantity', operator: 'gt' as const, value: 2 }]
      },
      templateIds: ['template'],
      taskFilter: { statuses: ['RUNNING' as const] },
      sort: { field: 'business:quantity', descending: false }
    }
    const nodes = [uiNode(NodeKind.TASKS, { resourceId: 'project-form', taskView })]
    const schema = pageSchema(nodes)
    expect(typeof schema.children[0]?.props?.taskViewJson).toBe('string')
    expect(pageNodes(schema)[0]?.taskView).toEqual(taskView)
  })
})

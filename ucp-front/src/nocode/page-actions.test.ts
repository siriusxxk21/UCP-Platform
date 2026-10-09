import { describe, it, expect } from 'vitest'
import { NodeKind, PageActionKind, PageDirection, uiNode } from '../types/nocode/application-ui'
import { ResourceKind, type ApplicationResource } from '../types/nocode/application'
import { pageSchema, pageNodes } from './page-schema'
import { pageActionTargets, resolvePageAction } from './page-actions'
import { pageNodeStyle } from './page-appearance'

describe('controlled page components and actions', () => {
  it('restores appearance, uploaded file identity and button targets without storing a canvas URL', () => {
    const nodes = [
      uiNode(NodeKind.FLEX, {
        style: { gap: 16, direction: PageDirection.ROW },
        children: [
          uiNode(NodeKind.IMAGE, { display: { imageFileId: '123', imageAlt: '标识', imageHeight: 120 } }),
          uiNode(NodeKind.BUTTON, {
            text: '新建账户',
            action: { kind: PageActionKind.CREATE, targetNodeId: 'accounts' },
            display: { buttonType: 'PRIMARY' }
          })
        ]
      })
    ]
    const schema = pageSchema(nodes)
    schema.children![0]!.children![0]!.props!.imageUrl = '/api/infra/file/1/get/sample.png'
    const restored = pageNodes(schema)
    expect(restored[0]?.style).toEqual(nodes[0]?.style)
    expect(restored[0]?.children[0]?.display).toEqual(nodes[0]?.children[0]?.display)
    expect(restored[0]?.children[1]?.action).toEqual(nodes[0]?.children[1]?.action)
    expect(JSON.stringify(restored)).not.toContain('sample.png')
    expect(pageNodes(pageSchema(restored))).toEqual(restored)
  })
  it('resolves a related create through the target list, keeping its existing relationship binding', () => {
    const target = uiNode(NodeKind.RELATED, {
      id: 'accounts',
      resourceId: 'account_view',
      binding: { relationId: 'company_fk', direction: 'INCOMING' }
    })
    const detail = uiNode(NodeKind.DETAIL, { id: 'company', resourceId: 'company_form' })
    const button = uiNode(NodeKind.BUTTON, { action: { kind: PageActionKind.CREATE, targetNodeId: target.id } })
    const nodes = [uiNode(NodeKind.TABS, { children: [uiNode(NodeKind.TAB, { children: [target, detail] })] }), button]
    const resources = [
      {
        id: 'account_view',
        code: 'account_view',
        name: '账户列表',
        kind: ResourceKind.VIEW,
        config: { objectId: 'account', formId: 'account_form' }
      },
      {
        id: 'account_form',
        code: 'account_form',
        name: '账户表单',
        kind: ResourceKind.FORM,
        config: { objectId: 'account' }
      }
    ] as ApplicationResource[]
    const result = resolvePageAction(button, { nodes, contextObjectId: 'company' }, resources)
    expect(result.form?.id).toBe('account_form')
    expect(result.target?.binding).toEqual(target.binding)
    expect(pageActionTargets(nodes, PageActionKind.CREATE).map(n => n.id)).toEqual(['accounts'])
    expect(pageActionTargets(nodes, PageActionKind.EDIT).map(n => n.id)).toEqual(['company'])
    expect(resolvePageAction(button, { nodes: [] }, resources).target).toBeUndefined()
  })
  it('limits runtime styles to numeric ranges and plain colors even for an untrusted draft', () => {
    const style = pageNodeStyle({
      type: NodeKind.FLEX,
      style: { padding: 900, gap: -10, background: 'url(https://example.com)', color: '#4c46e6' }
    })
    expect(style.padding).toBe('48px')
    expect(style.gap).toBe('0px')
    expect(style.backgroundColor).toBeUndefined()
    expect(style.color).toBe('#4c46e6')
    expect(style.display).toBe('flex')
    expect(style.flexWrap).toBe('wrap')
  })
  it('uses the target list default form but refuses a broken explicit binding', () => {
    const target = uiNode(NodeKind.VIEW, { id: 'list', resourceId: 'view' })
    const button = uiNode(NodeKind.BUTTON, { action: { kind: PageActionKind.CREATE, targetNodeId: 'list' } })
    const resources: ApplicationResource[] = [
      { id: 'view', code: 'view', name: '列表', kind: ResourceKind.VIEW, config: { objectId: 'object', formId: null } },
      {
        id: 'default',
        code: 'default',
        name: '默认表单',
        kind: ResourceKind.FORM,
        config: { objectId: 'object', options: { defaultForObject: true } }
      }
    ]
    expect(resolvePageAction(button, { nodes: [target] }, resources).form?.id).toBe('default')
    resources[0]!.config.formId = 'deleted'
    const invalid = resolvePageAction(button, { nodes: [target] }, resources)
    expect(invalid.form).toBeUndefined()
    expect(invalid.error).toContain('不存在')
  })
  it('does not accept executable event definitions as action or style props', () => {
    for (const prop of ['style_color', 'action_kind', 'display_imageFileId']) {
      expect(() =>
        pageNodes({
          componentName: 'Page',
          children: [
            { id: 'button', componentName: 'OsButton', props: { [prop]: { type: 'JSExpression', value: 'fetch()' } } }
          ]
        })
      ).toThrow('表达式')
    }
  })
  it('allows refreshing tasks but keeps record-create actions away from task commands', () => {
    const tasks = uiNode(NodeKind.TASKS, { id: 'work', resourceId: 'project-form' })
    expect(pageActionTargets([tasks], PageActionKind.REFRESH)).toEqual([tasks])
    expect(pageActionTargets([tasks], PageActionKind.CREATE)).toEqual([])
    expect(pageActionTargets([tasks], PageActionKind.EDIT)).toEqual([])
  })
  it('看板仅接入区块刷新目标，不提供记录新增与编辑动作', () => {
    const dashboard = uiNode(NodeKind.REPORT_DASHBOARD, { id: 'dashboard', resourceId: 'sales_dashboard' })
    expect(pageActionTargets([dashboard], PageActionKind.REFRESH)).toEqual([dashboard])
    expect(pageActionTargets([dashboard], PageActionKind.CREATE)).toEqual([])
    expect(pageActionTargets([dashboard], PageActionKind.EDIT)).toEqual([])
    expect(pageActionTargets([dashboard], PageActionKind.VIEW)).toEqual([])
  })
})

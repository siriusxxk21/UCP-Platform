import { describe, it, expect } from 'vitest'
import { nodesToRules, rulesToNodes, boundFields, unavailableFieldComponent } from './application-ui'
import { NodeKind, uiNode, type UiNode } from '@/types/nocode/application-ui'

describe('controlled designer adapter', () => {
  it.each([
    undefined,
    null,
    {},
    {
      label: '内容汇总',
      help: '保留旧字段配置',
      fill: { sourceFieldId: 'source', valueFieldId: 'name', mode: 'SOURCE_CHANGE' as const },
      behavior: { clearWhenHidden: true }
    }
  ])('设计态保留嵌套失效字段的身份与原配置，运行态仍拒绝（配置=%j）', presentation => {
    const missing = uiNode(NodeKind.FIELD, {
      id: 'legacy-summary-node',
      fieldId: 'removed-summary',
      ...(presentation === undefined ? {} : { presentation })
    })
    const nodes = [
      uiNode(NodeKind.CARD, {
        text: '',
        children: [uiNode(NodeKind.ROW, { children: [uiNode(NodeKind.COLUMN, { span: 12, children: [missing] })] })]
      })
    ]
    const rules = nodesToRules(nodes, [], true)
    const restored = rulesToNodes(rules)
    expect(restored).toEqual(nodes)
    expect(JSON.stringify(restored)).not.toContain(unavailableFieldComponent)
    expect(() => nodesToRules(nodes)).toThrow(presentation?.label || 'removed-summary')
    expect(nodesToRules([missing], [], true)[0]).toMatchObject({
      type: unavailableFieldComponent,
      field: 'removed-summary',
      name: 'legacy-summary-node',
      props: { label: presentation?.label || 'removed-summary' }
    })
  })
  it('preserves nested layout, stable field binding and business resource references', () => {
    const nodes: UiNode[] = [
      {
        id: 'row1',
        type: 'ROW',
        fieldId: null,
        resourceId: null,
        text: null,
        span: null,
        children: [
          {
            id: 'col1',
            type: 'COLUMN',
            fieldId: null,
            resourceId: null,
            text: null,
            span: 8,
            children: [
              {
                id: 'f1',
                type: 'FIELD',
                fieldId: '9007199254740993',
                resourceId: null,
                text: null,
                span: null,
                children: []
              }
            ]
          },
          {
            id: 'col2',
            type: 'COLUMN',
            fieldId: null,
            resourceId: null,
            text: null,
            span: 16,
            children: [
              { id: 'v1', type: 'VIEW', fieldId: null, resourceId: 'orders', text: null, span: null, children: [] }
            ]
          }
        ]
      }
    ]
    expect(
      rulesToNodes(nodesToRules(nodes, [{ type: 'input', field: '9007199254740993', title: '名称' }], true))
    ).toEqual(nodes)
    expect(boundFields(nodes)).toEqual(['9007199254740993'])
  })
  it('业务附件保存位置提示按字段呈现配置设计与运行往返', () => {
    const fieldRule = { type: 'nocodeBusinessField', field: 'f-file', title: '附件', props: {} }
    const hidden = uiNode(NodeKind.FIELD, {
      id: 'file-hidden',
      fieldId: 'f-file',
      presentation: { label: '附件', showBusinessPath: false }
    })
    const designed = nodesToRules([hidden], [fieldRule], true)
    expect(designed[0].props).toMatchObject({ _osHideBusinessPath: true })
    expect(rulesToNodes(designed)[0].presentation).toMatchObject({ showBusinessPath: false })
    const runtime = nodesToRules([hidden], [fieldRule])
    expect(runtime[0].props?.hideBusinessPath).toBe(true)
    const plain = uiNode(NodeKind.FIELD, { id: 'file-plain', fieldId: 'f-file' })
    expect(nodesToRules([plain], [fieldRule])[0].props?.hideBusinessPath).toBeUndefined()
    expect(rulesToNodes(designed)[0].presentation).toEqual(
      rulesToNodes(nodesToRules(rulesToNodes(designed), [fieldRule], true))[0].presentation
    )
  })
  it('rejects unregistered widgets and drops executable rule properties at the boundary', () => {
    expect(() => rulesToNodes([{ type: 'script', children: [] }])).toThrow('不支持')
    const node = rulesToNodes([
      {
        type: 'nocodeText',
        name: 'text1',
        props: { text: '<img src=x>', innerHTML: 'danger' },
        on: { click: () => 'not executed' },
        children: []
      }
    ])[0]
    expect(node.text).toBe('<img src=x>')
    expect(node).not.toHaveProperty('on')
    expect(node).not.toHaveProperty('props')
  })
})

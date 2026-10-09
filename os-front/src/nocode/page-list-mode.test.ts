import { describe, expect, it } from 'vitest'
import { listModeAttributes } from './page-list-mode'
import { pageNodes, pageSchema } from './page-schema'
import { NodeKind, uiNode } from '../types/nocode/application-ui'

describe('list relationship mode', () => {
  it('keeps nested list identity, resource and style while using the existing RELATED contract', () => {
    const node = uiNode(NodeKind.VIEW, { id: 'accounts-block', text: '财务账号', resourceId: 'accounts-view' })
    const schema = pageSchema([uiNode(NodeKind.TAB, { text: '财务', children: [node] })])
    const list = schema.children[0]!.children![0]!
    list.props!.style_padding = 16
    const before = JSON.stringify(schema)
    const converted = { ...list, ...listModeAttributes(list, true) }
    converted.props = { ...converted.props, relationId: 'company-relation', direction: 'INCOMING' }
    schema.children[0]!.children![0] = converted
    const saved = pageNodes(schema)[0]!.children[0]!
    expect(saved).toMatchObject({
      id: 'accounts-block',
      text: '财务账号',
      type: NodeKind.RELATED,
      resourceId: 'accounts-view',
      binding: { relationId: 'company-relation', direction: 'INCOMING' }
    })
    const reopened = pageSchema([saved]).children[0]!
    expect(reopened.componentName).toBe('OsRelated')
    expect(reopened.props!.style_padding).toBe(16)
    const plain = { ...reopened, ...listModeAttributes(reopened, false) }
    expect(pageNodes({ componentName: 'Page', children: [plain] })[0]).toMatchObject({
      id: node.id,
      type: NodeKind.VIEW,
      resourceId: node.resourceId,
      binding: null
    })
    expect(JSON.stringify(list)).toBe(JSON.stringify(JSON.parse(before).children[0].children[0]))
  })

  it('limits conversion to lists and only changes default labels', () => {
    expect(listModeAttributes({ componentName: 'OsForm' }, true)).toBeNull()
    expect(listModeAttributes({ componentName: 'OsRelated' }, true)).toBeNull()
    expect(listModeAttributes({ componentName: 'OsView', props: { text: '数据列表' } }, true)?.props.text).toBe(
      '相关列表'
    )
    expect(listModeAttributes({ componentName: 'OsRelated', props: { text: '相关列表' } }, false)?.props.text).toBe(
      '数据列表'
    )
  })
})

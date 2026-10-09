import { describe, expect, it } from 'vitest'
import { NodeKind, uiNode } from '../types/nocode/application-ui'
import { isStandaloneRuntimeList, runtimePageNodes } from './runtime-page'

const list = () => uiNode(NodeKind.VIEW, { text: '公司数据列表', resourceId: 'company-view' })
const card = () => uiNode(NodeKind.CARD, { text: '业务工作台', children: [list()] })

describe('runtime page presentation', () => {
  it('unwraps the legacy default card without mutating the published node or its list identity', () => {
    const root = card()
    const before = JSON.stringify(root)
    const result = runtimePageNodes([root])
    expect(result).toBe(root.children)
    expect(result[0]).toBe(root.children[0])
    expect(JSON.stringify(root)).toBe(before)
    expect(isStandaloneRuntimeList(result)).toBe(true)
  })

  it('also unwraps a plain untitled single-list container', () => {
    const root = { ...card(), text: null, style: {} }
    expect(runtimePageNodes([root])).toBe(root.children)
  })

  it.each([
    { text: '重要客户' },
    { style: { padding: 0 } },
    { style: { border: false } },
    { style: { background: '#ffffff' } },
    { resourceId: 'explicit-resource' },
    { binding: { relationId: 'relation', direction: 'INCOMING' as const } }
  ])('preserves an explicitly configured container: %j', override => {
    const nodes = [{ ...card(), ...override }]
    expect(runtimePageNodes(nodes)).toBe(nodes)
    expect(isStandaloneRuntimeList(nodes)).toBe(false)
  })

  it('preserves multiple blocks, sibling content and non-list pages', () => {
    for (const nodes of [
      [uiNode(NodeKind.CARD, { text: '业务工作台', children: [list(), list()] })],
      [card(), uiNode(NodeKind.TEXT, { text: '说明' })],
      ...[NodeKind.FORM, NodeKind.RELATED, NodeKind.REPORT].map(type => [
        uiNode(NodeKind.CARD, { text: '业务工作台', children: [uiNode(type)] })
      ])
    ]) {
      expect(runtimePageNodes(nodes)).toBe(nodes)
      expect(isStandaloneRuntimeList(nodes)).toBe(false)
    }
  })

  it('uses standalone height only for a sole plain view and preserves explicit list appearance', () => {
    const view = list()
    expect(isStandaloneRuntimeList([view])).toBe(true)
    expect(isStandaloneRuntimeList([view, list()])).toBe(false)
    expect(isStandaloneRuntimeList([{ ...view, style: { minHeight: 500 } }])).toBe(false)
    expect(isStandaloneRuntimeList([])).toBe(false)
    expect(runtimePageNodes([])).toEqual([])
  })
})

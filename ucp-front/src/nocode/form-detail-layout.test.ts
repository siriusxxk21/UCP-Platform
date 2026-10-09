import { describe, expect, it } from 'vitest'
import { NodeKind, uiNode } from '@/types/nocode/application-ui'
import { nodesToRules, rulesToNodes, boundFields } from './application-ui'
import { detailColumnsFromRules, formLayoutNodes, internalDetailIds } from './form-detail-layout'
import { arrangeFormColumns, formDesignIssues } from './form-design'
import type { PublishedDefinition } from '@/types/nocode/application'

const main = () => uiNode(NodeKind.FIELD, { id: 'name-node', fieldId: 'name' })
const detail = (id: string) =>
  uiNode(NodeKind.INTERNAL_DETAIL, { id: 'block-' + id, detail: { detailId: id, mode: 'GRID' } })
const definition = {
  fields: [{ id: 'name', name: '名称', type: 'TEXT' }],
  fieldOptions: {},
  relations: [],
  details: [{ id: 'lines', name: '项目', state: 'ACTIVE' }]
} as unknown as PublishedDefinition
describe('内部明细统一画布协议', () => {
  it('旧明细按传入顺序补尾且不改原节点，保存移除后的集合不会重新补回', () => {
    const original = [main()]
    const projected = formLayoutNodes(original, ['fees', 'lines'])
    expect(original).toHaveLength(1)
    expect(internalDetailIds(projected)).toEqual(['fees', 'lines'])
    expect(formLayoutNodes(projected, ['fees', 'lines'])).toEqual(projected)
    const removed = projected.filter(n => n.detail?.detailId !== 'fees')
    expect(formLayoutNodes(removed, internalDetailIds(removed))).toEqual(removed)
  })
  it('嵌套明细往返保留位置与外观，列规则独立保存，不混入主表字段或节点children', () => {
    const nodes = [main(), uiNode(NodeKind.CARD, { text: '分组', children: [detail('lines')] })]
    const columns = {
      lines: [
        uiNode(NodeKind.FIELD, {
          fieldId: 'item',
          presentation: { fill: { sourceFieldId: 'source', valueFieldId: 'title', mode: 'SOURCE_CHANGE' } }
        })
      ]
    }
    const rules = nodesToRules(nodes, [{ type: 'input', field: 'name', title: '名称' }], true, columns)
    const restored = rulesToNodes(rules)
    expect(internalDetailIds(restored)).toEqual(['lines'])
    expect(restored[1]!.children[0]!.detail).toEqual({ detailId: 'lines', mode: 'GRID' })
    expect(detailColumnsFromRules(rules)).toEqual(columns)
    expect(boundFields(restored)).toEqual(['name'])
    expect(JSON.stringify(restored)).not.toContain('_osDetailNodes')
    expect(internalDetailIds(arrangeFormColumns(restored, 2))).toEqual(['lines'])
  })
  it('重复明细、失效明细与明细内嵌套在应用前被阻止', () => {
    expect(formDesignIssues([main(), detail('lines')], definition)).toEqual([])
    expect(
      formDesignIssues([main(), detail('lines'), detail('lines')], definition).some(i => i.message.includes('重复放置'))
    ).toBe(true)
    expect(formDesignIssues([main(), detail('missing')], definition).some(i => i.message.includes('已不可用'))).toBe(
      true
    )
    expect(
      formDesignIssues([main(), detail('lines')], definition, [], {}, { definition, fieldIds: ['name'] }).some(i =>
        i.message.includes('另一个明细内部')
      )
    ).toBe(true)
  })
})

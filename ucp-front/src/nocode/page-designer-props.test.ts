import { describe, expect, it } from 'vitest'
import { NodeKind, uiNode } from '../types/nocode/application-ui'
import { findPageNode } from './page-context'
import { HOST_PROP_KEYS, pickHostProps } from './page-designer-props'
import { type PageSchemaNode, pageNodes, pageSchema } from './page-schema'

/** 模拟设计器里一次「宿主属性面板改属性 → 画布按白名单收下 → 保存时转回页面节点」。 */
function throughDesigner(nodes: ReturnType<typeof uiNode>[]) {
  const schema = pageSchema(nodes)
  const walk = (node: PageSchemaNode): PageSchemaNode => ({
    ...node,
    props: pickHostProps(node.props || {}),
    children: (node.children || []).map(walk)
  })
  return pageNodes({ ...schema, children: schema.children.map(walk) })
}

const engine = {
  engineUrl: null,
  materials: { objectId: '111', fields: { name: '636', unitPrice: '641', methodCode: '642' } },
  components: null,
  bom: { objectId: '112', recordFieldId: '653', keyFieldId: '644', fields: { quantity: '646', material: '655' } }
}

describe('设计器宿主属性白名单', () => {
  it('设计引擎区块的配置（engineJson）经宿主面板修改后不丢', () => {
    const block = uiNode(NodeKind.ENGINE, { text: '设计引擎', engine })
    const tab = uiNode(NodeKind.TAB, { text: '设计', children: [block] })
    const restored = throughDesigner([uiNode(NodeKind.TABS, { children: [tab] })])
    expect(findPageNode(restored, block.id)?.engine).toEqual(engine)
  })
  it('任务列表块的视图配置（taskViewJson）同样不丢', () => {
    const tasks = uiNode(NodeKind.TASKS, { text: '任务', taskView: { scope: 'RECORD' } as never })
    expect(throughDesigner([tasks])[0]?.taskView).toEqual({ scope: 'RECORD' })
  })
  it('pageSchema 给任一节点生成的属性都在白名单里', () => {
    const checked: string[] = []
    for (const type of Object.values(NodeKind)) {
      let props: Record<string, unknown>
      try {
        props = pageSchema([uiNode(type)]).children[0]?.props ?? {}
      } catch {
        continue // 页面里不支持的节点（如字段）不进设计器
      }
      for (const key of Object.keys(props)) expect(HOST_PROP_KEYS, `${type}.${key}`).toContain(key)
      checked.push(type)
    }
    expect(checked).toContain(NodeKind.ENGINE)
    expect(checked.length).toBeGreaterThan(10)
  })
})

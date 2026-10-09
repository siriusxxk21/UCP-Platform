import { describe, expect, it } from 'vitest'
import { nodesToRules, rulesToNodes } from './application-ui'
import { NodeKind, uiNode, type FormFillBinding } from '@/types/nocode/application-ui'
const nodes = (mode: FormFillBinding['mode'] = 'SOURCE_CHANGE') => [
  uiNode(NodeKind.FIELD, {
    fieldId: 'price',
    presentation: { fill: { sourceFieldId: 'material', valueFieldId: 'source-price', mode } }
  })
]
// 运行时关联带入协调器已撤除（实施设计稿 9.4）：表单改由对象·数据联动求值；存量 fill 配置在迁移前仍须原样往返。
describe('存量关联带入配置', () => {
  it('设计器往返保留关联路径和填充模式', () => {
    expect(
      rulesToNodes(nodesToRules(nodes(), [{ field: 'price', type: 'input' }], true))[0]?.presentation?.fill
    ).toEqual(nodes()[0]?.presentation?.fill)
  })
})

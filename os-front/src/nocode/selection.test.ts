import { describe, expect, it } from 'vitest'
import { selectionViewFormNames } from '@/nocode/selection'
import type { ApplicationResource } from '@/types/nocode/application'

function form(id: string, name: string, viewId: string, detailViewId?: string): ApplicationResource {
  return {
    id,
    kind: 'FORM',
    code: id,
    name,
    config: {
      objectId: 'obj',
      nodes: [
        {
          id: 'n1',
          type: 'COLUMN',
          children: [
            { id: 'n2', type: 'FIELD', fieldId: 'customer', presentation: { selection: { viewId } }, children: [] }
          ]
        }
      ],
      detailNodes: detailViewId
        ? {
            d1: [
              {
                id: 'd1n',
                type: 'FIELD',
                fieldId: 'item',
                presentation: { selection: { viewId: detailViewId } },
                children: []
              }
            ]
          }
        : {}
    }
  }
}

describe('表单候选限定视图引用', () => {
  it('递归收集主表与明细中把该视图用作候选范围的表单名称', () => {
    const resources: ApplicationResource[] = [
      form('form_a', '采购单表单', 'view_limit'),
      form('form_b', '销售单表单', 'view_other', 'view_limit'),
      { id: 'view_limit', kind: 'VIEW', code: 'v_limit', name: '限定视图', config: { objectId: 'obj' } },
      form('form_c', '无引用表单', 'view_other')
    ]
    expect(selectionViewFormNames(resources, 'view_limit')).toEqual(['采购单表单', '销售单表单'])
    expect(selectionViewFormNames(resources, 'view_missing')).toEqual([])
  })
})

import { describe, expect, it } from 'vitest'
import { datasetScopeFields, fieldReferenced, newDatasetAnalysis, removeSourceBranch } from './report-dataset-editor'
import type { DatasetSource } from '@/types/nocode/report-center'
const reference = { objectId: '1', versionNo: 1, checksum: 'abc' }
const source: DatasetSource = {
  schemaVersion: 1,
  root: reference,
  relations: [
    { id: 'a', parentPath: [], relationId: 'rel_a', target: reference },
    { id: 'b', parentPath: ['a'], relationId: 'rel_b', target: reference },
    { id: 'sibling', parentPath: [], relationId: 'rel_sibling', target: reference }
  ],
  fields: [
    { id: 'root', path: [], sourceFieldId: 'number', name: '根字段', role: 'MEASURE' },
    { id: 'nested', path: ['a', 'b'], sourceFieldId: 'number', name: '关联字段', role: 'DIMENSION' }
  ]
}
describe('数据集编辑依赖保护', () => {
  it('移除关联包含后代字段且保留兄弟关联，不修改输入', () => {
    const result = removeSourceBranch(source, 'a')
    expect(result.relations.map(r => r.id)).toEqual(['sibling'])
    expect(result.fields.map(f => f.id)).toEqual(['root'])
    expect(source.fields).toHaveLength(2)
  })
  it('嵌套固定条件引用阻止移除关联', () => {
    const analysis = newDatasetAnalysis()
    analysis.fixedConditions = {
      logic: 'AND',
      conditions: [],
      groups: [{ logic: 'OR', conditions: [{ fieldId: 'nested', operator: 'eq', value: '1' }], groups: [] }]
    }
    expect(() => removeSourceBranch(source, 'a', analysis)).toThrow('请先移除')
  })
  it('指标及格式引用均保护字段，普通改名不改变引用标识', () => {
    const analysis = newDatasetAnalysis()
    analysis.metrics = [{ id: 'sum', name: '求和', operation: 'SUM', fieldId: 'root' }]
    analysis.fieldFormats.nested = { decimals: 2 }
    expect(fieldReferenced(analysis, 'root')).toBe(true)
    expect(fieldReferenced(analysis, 'nested')).toBe(true)
    expect(fieldReferenced(analysis, 'unselected')).toBe(false)
  })
  it('仅被指标条件引用的关联字段仍受删除保护', () => {
    const analysis = newDatasetAnalysis()
    analysis.metrics = [
      {
        id: 'count',
        name: '条件计数',
        operation: 'COUNT',
        fieldId: null,
        conditions: {
          logic: 'AND',
          conditions: [{ fieldId: 'nested', operator: 'eq', value: '1' }],
          groups: []
        }
      }
    ]
    expect(fieldReferenced(analysis, 'nested')).toBe(true)
    expect(() => removeSourceBranch(source, 'a', analysis)).toThrow('请先移除')
  })
  it('相同源字段在不同路径保留不同分析标识，使用固定版本字段类型', () => {
    const fields = datasetScopeFields(source, {
      '1:1': {
        reference,
        name: '对象',
        relations: [],
        fields: [{ id: 'number', code: 'number', name: '数量', type: 'DECIMAL', measure: true }]
      }
    })
    expect(fields.map(f => [f.id, f.name, f.type])).toEqual([
      ['root', '根字段', 'DECIMAL'],
      ['nested', '关联字段', 'DECIMAL']
    ])
    expect(datasetScopeFields(source, {})).toEqual([])
  })
})

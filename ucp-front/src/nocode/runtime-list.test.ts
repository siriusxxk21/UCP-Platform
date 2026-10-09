import { describe, expect, it } from 'vitest'
import type { ObjectField } from '@/types/nocode/object'
import type { BusinessRow } from '@/types/nocode/runtime'
import {
  deleteRecordBatch,
  normalizeAdvancedQuery,
  basicQuery,
  combineConditions,
  dynamicQueryField,
  defaultViewList,
  listPreferenceKey,
  pageSizeOptions
} from './runtime-list'
import type { DynamicSearchCondition } from '@/components/ucp-table-page/types'
import { FieldType } from '@/types/nocode/enums'
import { defaultFieldOptions } from './data-center'

const field = (id: string, type: FieldType) => ({ id, name: id, type }) as ObjectField
describe('运行端统一列表查询', () => {
  it('布尔选择键提交为布尔值，收藏条件的未知字段不能静默丢弃', () => {
    const condition: DynamicSearchCondition = {
      logic: 'AND',
      items: [
        {
          type: 'group',
          groupLogic: 'OR',
          groupItems: [{ type: 'condition', field: 'active', operator: 'in', value: ['true', 'false'] }]
        }
      ]
    }
    expect(normalizeAdvancedQuery(condition, [field('active', FieldType.BOOLEAN)])?.items).toEqual([
      {
        type: 'group',
        groupLogic: 'OR',
        groupItems: [{ type: 'condition', field: 'active', operator: 'in', value: [true, false] }]
      }
    ])
    expect(() => normalizeAdvancedQuery(condition, [])).toThrow('字段已不可用')
  })
  it('批量删除携带原记录版本，部分失败不影响后续记录并准确反馈结果', async () => {
    const rows: BusinessRow[] = ['1', '2', '3'].map(id => ({ id, revision: 'rev-' + id, values: {} }))
    const attempted: string[] = []
    const conflict = new Error('记录已被修改')
    const outcome = await deleteRecordBatch(rows, async row => {
      attempted.push(row.id! + ':' + row.revision)
      if (row.id === '2') throw conflict
    })
    expect(attempted).toEqual(['1:rev-1', '2:rev-2', '3:rev-3'])
    expect(outcome).toEqual({ succeeded: 2, failures: [{ id: '2', error: conflict }] })
  })
  it('保留零、false 和高精度数字，清空值不生成过滤条件', () => {
    const fields = [
      field('name', FieldType.TEXT),
      field('amount', FieldType.MONEY),
      field('active', FieldType.BOOLEAN),
      field('unused', FieldType.TEXT)
    ]
    const result = basicQuery(fields, { name: '公司', amount: '9007199254740993.01', active: false, unused: '' })
    expect(result.conditions?.items).toEqual([
      { type: 'condition', field: 'name', operator: 'like', value: '公司' },
      { type: 'condition', field: 'amount', operator: 'eq', value: '9007199254740993.01' },
      { type: 'condition', field: 'active', operator: 'eq', value: false }
    ])
    expect(basicQuery([fields[1]!], { amount: 0 }).conditions?.items[0]).toMatchObject({ value: 0 })
    expect(basicQuery(fields, {}).conditions).toBeNull()
  })
  it('字典按编码等值匹配，多选按包含任意值查询', () => {
    expect(
      basicQuery(
        [field('state', FieldType.TEXT), field('tags', FieldType.MULTI_SELECT)],
        { state: 'open', tags: ['a', 'b'] },
        new Set(['state'])
      )
    ).toEqual({
      equal: {},
      conditions: {
        logic: 'AND',
        items: [
          { type: 'condition', field: 'state', operator: 'eq', value: 'open' },
          { type: 'condition', field: 'tags', operator: 'containsAny', value: ['a', 'b'] }
        ]
      }
    })
  })
  it('常用查询和 OR 分组保持交集，不能展开为顶层 OR', () => {
    const basic = basicQuery([field('name', FieldType.TEXT)], { name: '公司' }).conditions
    const advanced: DynamicSearchCondition = {
      logic: 'OR',
      items: [
        { type: 'condition', field: 'status', operator: 'eq', value: 'a' },
        { type: 'condition', field: 'status', operator: 'eq', value: 'b' }
      ]
    }
    expect(combineConditions(basic, advanced)).toEqual({
      logic: 'AND',
      items: [
        { type: 'group', groupLogic: 'AND', groupItems: basic!.items },
        { type: 'group', groupLogic: 'OR', groupItems: advanced.items }
      ]
    })
  })
  it('适配底座字段时保留数字字符串与带时区日期格式', () => {
    expect(
      dynamicQueryField(field('balance', FieldType.FORMULA), { ...defaultFieldOptions(), resultType: FieldType.MONEY })
    ).toMatchObject({
      field: 'balance',
      type: 'number',
      stringMode: true,
      operators: ['eq', 'neq', 'gt', 'gte', 'lt', 'lte']
    })
    expect(dynamicQueryField(field('amount', FieldType.MONEY))).toMatchObject({ type: 'number', stringMode: true })
    expect(dynamicQueryField(field('date', FieldType.DATE))).toMatchObject({
      type: 'dateRange',
      valueFormat: 'YYYY-MM-DD'
    })
    expect(dynamicQueryField(field('active', FieldType.BOOLEAN)).options).toContainEqual({
      label: '否',
      value: 'false'
    })
  })
  it('旧视图默认不开放批量删除，分页兼容自定义数量，个人偏好按用户和视图区分', () => {
    expect(defaultViewList()).toMatchObject({ batchDelete: false, queryFieldIds: [], advancedFieldIds: null })
    expect(pageSizeOptions(15)).toEqual(['10', '15', '20', '50', '100'])
    expect(
      new Set([
        listPreferenceKey('1', 'a', 'o', 'v'),
        listPreferenceKey('2', 'a', 'o', 'v'),
        listPreferenceKey('1', 'a', 'o', 'w')
      ]).size
    ).toBe(3)
  })
})

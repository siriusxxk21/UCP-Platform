import { describe, it, expect } from 'vitest'
import { exactNumber, reportFilters, reportFieldOptions, reportFields, scalarField, numericField } from './report'
import { defaultFieldOptions } from './data-center'
import { pageNodes, pageSchema } from './page-schema'
import { NodeKind, uiNode } from '@/types/nocode/application-ui'
import { FieldType } from '@/types/nocode/enums'
import type { ObjectField } from '@/types/nocode/object'
import type { PublishedDefinition } from '@/types/nocode/application'
import type { ReportFilter } from '@/types/nocode/report'
describe('统计数值和页面联动边界', () => {
  it('已就绪有序数值与相邻文本按结果类型进入报表，LIVE、未就绪和普通快照保持原边界', () => {
    const calculation = {
      mode: 'RUNNING_TOTAL' as const,
      updateMode: 'ON_SAVE' as const,
      targetObjectId: null,
      relationId: null,
      targetField: 'amount',
      aggregate: 'SUM' as const,
      logic: 'AND' as const,
      conditions: [],
      excludeCurrent: false
    }
    const ids = ['balance', 'pending', 'live', 'snapshot', 'adjacent']
    const fields = ids.map(id => ({ id, key: id, code: id, name: id, type: FieldType.FORMULA })) as ObjectField[]
    const options = { ...defaultFieldOptions(), resultType: 'MONEY', calculation }
    const definition = {
      fields,
      relations: [],
      fieldOptions: {
        balance: options,
        pending: options,
        live: { ...options, calculation: { ...calculation, updateMode: 'LIVE' } },
        snapshot: { ...options, calculation: { ...calculation, mode: 'LOCAL' } },
        adjacent: { ...options, resultType: 'TEXT', calculation: { ...calculation, mode: 'SEQUENCE' } }
      }
    } as unknown as PublishedDefinition
    expect(reportFields(definition)).toEqual([])
    const readiness = { balance: 'READY', pending: 'FAILED', live: 'READY', snapshot: 'READY', adjacent: 'READY' }
    const result = reportFieldOptions('1', { '1': { definition } }, { '1': readiness })
    expect(result.map(entry => [entry.value, entry.field.type])).toEqual([
      ['balance', 'MONEY'],
      ['adjacent', 'TEXT']
    ])
    expect(numericField(result[0]!.field)).toBe(true)
    expect(numericField(result[1]!.field)).toBe(false)
    expect(definition.fields[0]!.type).toBe(FieldType.FORMULA)
  })
  it('单值目录可作分组和筛选，多值目录不能混入维度或指标', () => {
    const types = [FieldType.ORGANIZATION, FieldType.DEPARTMENT, FieldType.USER, FieldType.POST, FieldType.USER_GROUP]
    const fields = types.map((type, i) => ({ id: String(i), code: 'c_' + i, name: type, type })) as ObjectField[]
    const multi = { id: 'multi', code: 'c_multi', name: '多选部门', type: FieldType.MULTI_SELECT } as ObjectField
    const definition = {
      fields: [...fields, multi],
      relations: [],
      fieldOptions: { multi: { selection: { kind: 'DIRECTORY', directory: 'DEPARTMENT' } } }
    } as unknown as PublishedDefinition
    expect(reportFieldOptions('1', { '1': { definition } }).map(f => f.value)).toEqual(fields.map(f => f.id))
    expect(fields.every(scalarField)).toBe(true)
    expect(fields.some(f => numericField(f))).toBe(false)
    expect(scalarField(multi)).toBe(false)
  })
  it('金额不经过浏览器浮点或截断小数', () => {
    expect(exactNumber('9007199254740993.3000')).toBe('9,007,199,254,740,993.30')
    expect(exactNumber('-1234.0500')).toBe('-1,234.05')
    expect(exactNumber('1234.05001')).toBe('1,234.05001')
    expect(exactNumber('0')).toBe('0')
    expect(exactNumber(null)).toBe('—')
  })
  it('筛选只传入明确绑定的报表并保留 false 和零', () => {
    const definitions: ReportFilter[] = [
      { id: 'company', name: '公司', objectId: '1', fieldId: '10', dateRange: false, targets: { a: '10', b: '20' } },
      { id: 'boolean', name: '开关', objectId: '1', fieldId: '11', dateRange: false, targets: { a: '11' } },
      { id: 'zero', name: '数量', objectId: '1', fieldId: '12', dateRange: false, targets: { a: '12' } },
      { id: 'date', name: '日期', objectId: '1', fieldId: '13', dateRange: true, targets: { a: '13', b: '23' } }
    ]
    const values = { company: '999', boolean: false, zero: 0, date: ['2026-09-01', '2026-09-30'] }
    expect(reportFilters('a', definitions, values)).toEqual({
      equal: { '10': '999', '11': false, '12': 0 },
      dateFrom: '2026-09-01',
      dateTo: '2026-09-30'
    })
    expect(reportFilters('b', definitions, values)).toEqual({
      equal: { '20': '999' },
      dateFrom: '2026-09-01',
      dateTo: '2026-09-30'
    })
    expect(reportFilters('c', definitions, values)).toEqual({ equal: {} })
    expect(reportFilters('a', definitions, {})).toEqual({ equal: {} })
  })
  it('统计物料保存恢复，关联绑定可取消且不污染普通报表', () => {
    const node = uiNode(NodeKind.REPORT, {
      resourceId: 'report',
      text: '统计',
      binding: { relationId: 'relation', direction: 'INCOMING' }
    })
    expect(pageNodes(pageSchema([node]))[0]).toMatchObject({
      type: NodeKind.REPORT,
      resourceId: 'report',
      binding: node.binding
    })
    const schema = pageSchema([node])
    schema.children[0].props!.relationId = ''
    expect(pageNodes(schema)[0].binding).toBeNull()
    expect(pageNodes(pageSchema([uiNode(NodeKind.REPORT, { resourceId: 'report' })]))[0].resourceId).toBe('report')
  })
})

import { describe, expect, it } from 'vitest'
import {
  changedKeys,
  historyCounts,
  historyRows,
  highlightedKeys,
  relevantChanges,
  summaryCounts,
  overviewTables,
  employeeOverview,
  changeDescription,
  linkageSourceLabel
} from './record-history'
import type { HistoryRow, HistoryTable } from '@/types/nocode/record-history'

const row: HistoryRow = {
  id: 'r1',
  startValues: { name: '原值' },
  endValues: { name: '原值' },
  values: { name: '原值' },
  changedFields: ['name'],
  deleted: false,
  createdInRange: false,
  restored: true,
  changes: [
    {
      id: 'e1',
      operation: 'UPDATE',
      time: '2026-09-10T09:00:00Z',
      employeeId: '1',
      employeeName: '张三',
      before: { name: '原值' },
      after: { name: '中间值' },
      fields: []
    },
    {
      id: 'e2',
      operation: 'UPDATE',
      time: '2026-09-11T09:00:00Z',
      employeeId: '2',
      employeeName: '李四',
      before: { name: '中间值' },
      after: { name: '原值' },
      fields: []
    }
  ]
}
const table: HistoryTable = {
  objectId: 't1',
  name: '公司档案',
  applicationIds: ['a1', 'a2'],
  applicationNames: ['业务一', '业务二'],
  coveredFrom: '2026-09-01T00:00:00Z',
  complete: true,
  fields: [],
  rows: [row]
}
describe('表格更新统计与下钻', () => {
  it('聚合接口不需要记录值，跨应用共享表去重且人员取并集', () => {
    const summary = {
      ...table,
      counts: historyCounts([table]),
      employees: [
        { id: '1', name: '张三', counts: historyCounts([table], '1') },
        { id: '2', name: '李四', counts: historyCounts([table], '2') }
      ]
    }
    expect(summaryCounts([summary, summary])).toEqual(historyCounts([table]))
    expect(summaryCounts([summary], '2')).toEqual(historyCounts([table], '2'))
    expect(summaryCounts([summary], 'unknown')).toMatchObject({ records: 0, operations: 0, employees: 0 })
    expect(summaryCounts([summary, { ...summary, objectId: 't2' }])).toMatchObject({
      records: 2,
      operations: 4,
      employees: 2
    })
  })
  it('跨天多人修改按事件计数，记录及共享表去重', () => {
    expect(historyCounts([table, table])).toMatchObject({ records: 1, operations: 2, update: 2, employees: 2 })
    expect(historyCounts([table], '1')).toMatchObject({ records: 1, operations: 1, employees: 1 })
  })
  it('员工筛选命中记录后保留全员过程，高亮仅限该员工', () => {
    expect(historyRows(table, 'changes', '1')[0]?.changes).toHaveLength(2)
    expect(relevantChanges(row, '2')).toHaveLength(1)
    expect([...highlightedKeys(row, '1')]).toEqual(['name'])
  })
  it('恢复原值不产生最终差异，但两次修改均被保留', () => {
    expect(changedKeys(row.startValues, row.endValues)).toEqual([])
    expect(historyRows(table, 'changes')).toHaveLength(1)
  })
  it('全表不受员工筛选裁剪，删除记录只进入变更 Sheet', () => {
    const deleted = { ...row, id: 'r2', deleted: true }
    const combined = { ...table, rows: [row, deleted, { ...row, id: 'r3', changes: [] }] }
    expect(historyRows(combined, 'all', 'unknown').map(r => r.id)).toEqual(['r1', 'r3'])
    expect(historyRows(combined, 'changes').map(r => r.id)).toEqual(['r1', 'r2'])
  })
  it('新增后删除分别计算两次操作，同一行只计一次', () => {
    const transient = {
      ...row,
      changes: [
        { ...row.changes[0]!, operation: 'CREATE' as const, before: null },
        { ...row.changes[1]!, operation: 'DELETE' as const, after: null }
      ]
    }
    expect(historyCounts([{ ...table, rows: [transient] }])).toMatchObject({
      records: 1,
      operations: 2,
      create: 1,
      delete: 1
    })
  })
})

describe('老板查看业务与人员动态', () => {
  const shared = {
    ...table,
    counts: historyCounts([table]),
    employees: [
      { id: '1', name: '张三', counts: historyCounts([table], '1') },
      { id: '2', name: '李四', counts: historyCounts([table], '2') }
    ]
  }
  const idle = { ...shared, objectId: 'idle', name: '未变化表', counts: historyCounts([]), employees: [] }
  it('业务优先按唯一表格展示，共享应用不会重复累计', () => {
    const list = overviewTables([shared, shared, idle], { changedOnly: true })
    expect(list.map(t => t.objectId)).toEqual(['t1'])
    expect(summaryCounts(list).records).toBe(1)
    expect(overviewTables([shared], { application: 'a2', changedOnly: true })).toHaveLength(1)
    expect(overviewTables([shared], { application: 'unknown' })).toHaveLength(0)
  })
  it('零变化表可显式查看，缺失人员不被认定为零工作', () => {
    expect(overviewTables([idle], { changedOnly: true })).toEqual([])
    expect(overviewTables([idle], { changedOnly: false })).toEqual([idle])
    expect(employeeOverview([shared, idle]).map(p => p.id)).toEqual(['2', '1'])
    expect(employeeOverview([shared], 'unknown')).toEqual([])
  })
  it('搜索与业务、人员条件相交，删除入口只筛选发生过删除的表格', () => {
    expect(overviewTables([shared], { keyword: '  业务二  ', employee: '1', changedOnly: true })).toHaveLength(1)
    expect(overviewTables([shared], { keyword: '不存在' })).toEqual([])
    expect(overviewTables([shared], { employee: 'unknown', changedOnly: true })).toEqual([])
    expect(overviewTables([shared], { deletedOnly: true })).toEqual([])
    const removed = { ...shared, counts: { ...shared.counts, delete: 1 } }
    expect(overviewTables([removed], { deletedOnly: true })).toHaveLength(1)
    expect(overviewTables([removed], { employee: '1', deletedOnly: true })).toEqual([])
  })
  it('人员涉及记录按表去重，多人协作不是记录归属或绩效排名', () => {
    const people = employeeOverview([shared, shared])
    expect(people).toHaveLength(2)
    expect(people.every(p => p.counts.records === 1 && p.counts.operations === 1)).toBe(true)
    expect(changeDescription(shared.counts)).toBe('修改 2 次')
    expect(changeDescription(idle.counts)).toBe('没有留存变更')
  })
})

// 第一期契约第 8 章：系统按数据联动自动更新写入的变更，来源 kind 为 LINKAGE；回填另带 backfill: true。
describe('变更来源：系统自动更新', () => {
  const source = { kind: 'LINKAGE', applicationId: '3054', version: 61, name: '凭证状态', fieldIds: ['f1'] }
  it('普通触发标「系统自动更新 · 字段名」，回填标「系统自动更新（存量回填） · 字段名」', () => {
    expect(linkageSourceLabel({ ...source, sourceObjectId: '5391', sourceRecordId: '9' })).toBe(
      '系统自动更新 · 凭证状态'
    )
    expect(linkageSourceLabel({ ...source, backfill: true })).toBe('系统自动更新（存量回填） · 凭证状态')
    expect(linkageSourceLabel({ ...source, name: '凭证状态、凭证号' })).toBe('系统自动更新 · 凭证状态、凭证号')
  })
  it('其它来源（任务中心、持续维护、留存、有序计算）与没有来源的变更都不标', () => {
    for (const kind of ['TASK_ENTRY', 'AUTOMATION', 'CAPTURE_VALUES', 'ORDERED_CALCULATION'])
      expect(linkageSourceLabel({ ...source, kind })).toBeNull()
    // 别的来源即使带了 backfill 也不标成回填。
    expect(linkageSourceLabel({ ...source, kind: 'AUTOMATION', backfill: true })).toBeNull()
    expect(linkageSourceLabel(null)).toBeNull()
    expect(linkageSourceLabel(undefined)).toBeNull()
  })
})

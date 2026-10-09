import type {
  HistoryChange,
  HistoryRow,
  HistoryTable,
  HistoryValues,
  HistoryCounts,
  HistoryTableSummary
} from '@/types/nocode/record-history'

/** 首屏合并服务端计数；共享表按 objectId 去重，人员数取并集。 */
export function summaryCounts(tables: HistoryTableSummary[], employee?: string): HistoryCounts {
  const total = { records: 0, operations: 0, create: 0, update: 0, delete: 0, employees: 0 }
  const people = new Set<string>()
  for (const table of new Map(tables.map(t => [t.objectId, t])).values()) {
    const count = employee ? table.employees.find(p => p.id === employee)?.counts : table.counts
    if (!count) continue
    for (const key of ['records', 'operations', 'create', 'update', 'delete'] as const) total[key] += count[key]
    table.employees.forEach(p => {
      if (!employee || p.id === employee) people.add(p.id)
    })
  }
  total.employees = people.size
  return total
}

export const operationLabels = { CREATE: '新增', UPDATE: '修改', DELETE: '删除' } as const
/**
 * 系统按数据联动「来源变化时自动更新」写入的变更（来源 kind 为 LINKAGE，2026-10-01 第一期契约第 8 章）：
 * 普通触发标「系统自动更新 · 字段名」，存量回填另行标出；其它来源返回 null。
 */
export function linkageSourceLabel(source?: HistoryChange['source']): string | null {
  if (source?.kind !== 'LINKAGE') return null
  return `${source.backfill === true ? '系统自动更新（存量回填）' : '系统自动更新'} · ${source.name}`
}
/**
 * 按日期自动执行以系统身份写入的变更（来源 kind 为 DATE_TRIGGER）：标「按日期自动执行 · 规则名 · 业务日」，
 * 手动「立即按今天执行」另行标出；其它来源返回 null。
 */
export function dateTriggerSourceLabel(source?: HistoryChange['source']): string | null {
  if (source?.kind !== 'DATE_TRIGGER') return null
  return `${source.manual === true ? '按日期自动执行（手动）' : '按日期自动执行'} · ${source.name} · ${source.businessDate}`
}
/** 变更人：系统写入（按日期自动执行）显示「系统」，留痕用的操作者不当成办理人。 */
export function changeOperator(change: Pick<HistoryChange, 'source' | 'employeeName'>): string {
  return change.source?.kind === 'DATE_TRIGGER' ? '系统' : change.employeeName
}
export function relevantChanges(row: HistoryRow, employee?: string): HistoryChange[] {
  return employee ? row.changes.filter(change => change.employeeId === employee) : row.changes
}
/** 统计按稳定对象与记录 ID 去重；同一对象复用于多应用也只计算一次。 */
export function historyCounts(tables: HistoryTable[], employee?: string) {
  const records = new Set<string>(),
    events = new Map<string, HistoryChange>()
  for (const table of tables)
    for (const row of table.rows) {
      const changes = relevantChanges(row, employee)
      if (changes.length) records.add(`${table.objectId}:${row.id}`)
      for (const change of changes) events.set(change.id, change)
    }
  const list = [...events.values()]
  return {
    records: records.size,
    operations: list.length,
    create: list.filter(c => c.operation === 'CREATE').length,
    update: list.filter(c => c.operation === 'UPDATE').length,
    delete: list.filter(c => c.operation === 'DELETE').length,
    employees: new Set(list.map(c => c.employeeId)).size
  }
}
export function changedKeys(before: HistoryValues | null, after: HistoryValues | null) {
  return [...new Set([...Object.keys(before || {}), ...Object.keys(after || {})])].filter(
    key => !before || !after || JSON.stringify(before[key]) !== JSON.stringify(after[key])
  )
}
export function highlightedKeys(row: HistoryRow, employee?: string) {
  return new Set(relevantChanges(row, employee).flatMap(change => changedKeys(change.before, change.after)))
}
export function historyRows(table: HistoryTable, sheet: string, employee?: string) {
  return table.rows.filter(row => (sheet === 'all' ? !row.deleted : relevantChanges(row, employee).length > 0))
}
export function historyValue(value: unknown) {
  if (value === undefined || value === null || value === '') return '—'
  return typeof value === 'object' ? JSON.stringify(value) : String(value)
}

export interface HistoryOverviewFilter {
  application?: string
  employee?: string
  keyword?: string
  changedOnly?: boolean
  deletedOnly?: boolean
}

/** 两个视角共用同一批已授权汇总；筛选的是表格，不伪装成逐条操作筛选。 */
export function overviewTables(tables: HistoryTableSummary[], filter: HistoryOverviewFilter = {}) {
  const keyword = (filter.keyword || '').trim().toLocaleLowerCase()
  return [...new Map(tables.map(table => [table.objectId, table])).values()]
    .filter(table => {
      const counts = summaryCounts([table], filter.employee)
      return (
        (!filter.application || table.applicationIds.includes(filter.application)) &&
        (!keyword ||
          [table.name, ...table.applicationNames].some(name => name.toLocaleLowerCase().includes(keyword))) &&
        (!filter.changedOnly || counts.operations > 0) &&
        (!filter.deletedOnly || counts.delete > 0)
      )
    })
    .sort(
      (a, b) =>
        summaryCounts([b], filter.employee).records - summaryCounts([a], filter.employee).records ||
        a.name.localeCompare(b.name, 'zh-CN') ||
        a.objectId.localeCompare(b.objectId)
    )
}

/** 员工只来自可见事件，不把未出现的人标记为未工作，也不默认做次数排行榜。 */
export function employeeOverview(tables: HistoryTableSummary[], employee?: string) {
  tables = [...new Map(tables.map(table => [table.objectId, table])).values()]
  const people = new Map<string, string>()
  for (const table of tables)
    for (const person of table.employees)
      if ((!employee || person.id === employee) && person.counts.operations > 0) people.set(person.id, person.name)
  return [...people]
    .map(([id, name]) => {
      const involved = tables.filter(table =>
        table.employees.some(person => person.id === id && person.counts.operations > 0)
      )
      return { id, name, tables: involved, counts: summaryCounts(involved, id) }
    })
    .sort((a, b) => a.name.localeCompare(b.name, 'zh-CN') || a.id.localeCompare(b.id))
}

export function changeDescription(counts: HistoryCounts) {
  const items = [
    counts.create ? `新增 ${counts.create} 次` : '',
    counts.update ? `修改 ${counts.update} 次` : '',
    counts.delete ? `删除 ${counts.delete} 次` : ''
  ].filter(Boolean)
  return items.length ? items.join(' · ') : '没有留存变更'
}

export const today = new Date()
today.setHours(0, 0, 0, 0)
// 早间运行回归时也让全部事件早于“截至当前”。
const eventScale = Math.min(1, (Date.now() - today.getTime()) / (12 * 3600000))
const stamp = hours => new Date(today.getTime() + hours * 3600000 * (hours < 0 ? 1 : eventScale)).toISOString()
const fields = [
  { id: 'name', name: '公司名称' },
  { id: 'amount', name: '登记金额' },
  { id: 'contact', name: '联系人' }
]
const base = { name: '日创科技', amount: '120000.00', contact: '王敏' }
const change = (id, time, employeeId, before, after, operation = 'UPDATE') => ({
  id,
  time,
  employeeId,
  employeeName: employeeId === '1' ? '张三' : '李四',
  before,
  after,
  operation,
  fields
})
const events = [
  change('e1', stamp(9), '1', base, { ...base, amount: '150000.00' }),
  change('e2', stamp(10), '2', { ...base, amount: '150000.00' }, base)
]
const deleted = {
  id: 'r2',
  startValues: null,
  endValues: null,
  values: { ...base, name: '临时公司' },
  changedFields: ['name'],
  createdInRange: true,
  deleted: true,
  restored: false,
  changes: [
    change('e3', stamp(8), '1', null, { ...base, name: '临时公司' }, 'CREATE'),
    change('e4', stamp(11), '2', { ...base, name: '临时公司' }, null, 'DELETE')
  ]
}
export function fixture(q) {
  q = { ...q, end: q.end || new Date().toISOString() }
  const start = Date.parse(q.start),
    end = Date.parse(q.end)
  const all = [
    {
      id: 'r1',
      startValues: base,
      endValues: base,
      values: base,
      changedFields: ['amount'],
      createdInRange: false,
      deleted: false,
      restored: true,
      changes: events
    },
    deleted,
    ...Array.from({ length: 12 }, (_, i) => ({
      id: 'r' + (i + 3),
      startValues: { ...base, name: '公司档案 ' + (i + 1) },
      endValues: { ...base, name: '公司档案 ' + (i + 1) },
      values: { ...base, name: '公司档案 ' + (i + 1) },
      changedFields: [],
      createdInRange: false,
      deleted: false,
      restored: false,
      changes: []
    }))
  ]
  const rows = all.map(r => ({
    ...r,
    changes: r.changes.filter(e => Date.parse(e.time) >= start && Date.parse(e.time) <= end)
  }))
  return {
    start: q.start,
    end: q.end,
    tables: [
      {
        objectId: 't1',
        name: '公司档案',
        applicationIds: ['a1', 'a2'],
        applicationNames: ['公司经营管理', '资料管理'],
        coveredFrom: stamp(-48),
        complete: true,
        fields,
        rows
      },
      {
        objectId: 't2',
        name: '资产台账',
        applicationIds: ['a1'],
        applicationNames: ['公司经营管理'],
        coveredFrom: stamp(-48),
        complete: true,
        fields,
        rows: [
          {
            id: 'asset1',
            startValues: null,
            endValues: { name: '办公电脑' },
            values: { name: '办公电脑' },
            changedFields: ['name'],
            createdInRange: true,
            deleted: false,
            restored: false,
            changes: [
              { ...change('asset-event', stamp(7), '3', null, { name: '办公电脑' }, 'CREATE'), employeeName: '王敏' }
            ].filter(e => Date.parse(e.time) >= start && Date.parse(e.time) <= end)
          }
        ]
      },
      ...Array.from({ length: 12 }, (_, i) => ({
        objectId: 'idle' + i,
        name: '基础资料 ' + String(i + 1).padStart(2, '0'),
        applicationIds: ['a2'],
        applicationNames: ['资料管理'],
        coveredFrom: stamp(-48),
        complete: i !== 0,
        fields,
        rows: []
      }))
    ].filter(t => !q.applicationId || t.applicationIds.includes(q.applicationId))
  }
}

function counts(rows, employee) {
  const selected = rows.map(row => row.changes.filter(c => !employee || c.employeeId === employee))
  const changes = selected.flat()
  return {
    records: selected.filter(list => list.length).length,
    operations: changes.length,
    create: changes.filter(c => c.operation === 'CREATE').length,
    update: changes.filter(c => c.operation === 'UPDATE').length,
    delete: changes.filter(c => c.operation === 'DELETE').length,
    employees: new Set(changes.map(c => c.employeeId)).size
  }
}

/** 模拟与正式接口相同的三段式加载；分页响应不预载行过程。 */
export function response(path, request) {
  const data = fixture(request.query || request)
  if (path === '/query')
    return {
      start: data.start,
      end: data.end,
      visibility: '1:100:',
      tables: data.tables.map(({ rows, fields, ...table }) => ({
        ...table,
        counts: counts(rows),
        employees: [...new Map(rows.flatMap(r => r.changes).map(c => [c.employeeId, c.employeeName]))].map(
          ([id, name]) => ({ id, name, counts: counts(rows, id) })
        )
      }))
    }
  const table = data.tables.find(t => t.objectId === request.objectId)
  if (!table) throw new Error('Unknown fixture table')
  if (path === '/detail') return { row: table.rows.find(r => r.id === request.recordId), fields: table.fields }
  if (path !== '/page') throw new Error('Unknown fixture endpoint')
  const rows = table.rows.filter(r =>
    request.changesOnly
      ? r.changes.some(c => !request.query.employeeId || c.employeeId === request.query.employeeId)
      : !r.deleted
  )
  const start = (request.pageNo - 1) * request.pageSize
  return {
    table: {
      ...table,
      rows: rows.slice(start, start + request.pageSize).map(r => ({
        ...r,
        startValues: null,
        endValues: null,
        changes: [],
        changeCount: r.changes.filter(c => !request.query.employeeId || c.employeeId === request.query.employeeId)
          .length
      }))
    },
    total: rows.length,
    pageNo: request.pageNo,
    pageSize: request.pageSize
  }
}

import { describe, expect, it } from 'vitest'
import type { Aggregate, BusinessRow, SaveRecord } from '@/types/nocode/runtime'
import { isRecordConflict, isRecordMissing, rebaseSave, REBASE_DELETE, type RebaseResult } from './save-rebase'

const line = (id: string | null, values: Record<string, unknown>, revision: string | null = id ? '1' : null) =>
  ({ id, revision, clientRowKey: id ? `row-${id}` : 'new-' + JSON.stringify(values), values }) as BusinessRow
function aggregate(
  values: Record<string, unknown>,
  revision: string,
  extra: Partial<Pick<Aggregate, 'details' | 'relations'>> = {}
): Aggregate {
  return { record: { id: 'r1', revision, values }, details: {}, ...extra }
}
function command(values: Record<string, unknown>, extra: Partial<SaveRecord> = {}): SaveRecord {
  return {
    requestKey: 'first-request',
    applicationId: 'app',
    objectId: 'object',
    formId: 'form',
    context: { pageId: 'p', nodeId: 'n', recordId: 'parent' },
    actionCode: 'confirm',
    id: 'r1',
    expectedRevision: '1',
    values,
    ...extra
  }
}
function ok(result: RebaseResult) {
  if (result.ok === false) throw new Error('应当合并成功，却被拒：' + result.reason)
  return result
}

describe('rebaseSave 主字段', () => {
  it('N1 我改了「摘要」、对方改了「金额」：摘要用我的、金额用对方的', () => {
    const base = aggregate({ summary: '旧摘要', amount: 100 }, '1')
    const latest = aggregate({ summary: '旧摘要', amount: 250 }, '2')
    const result = ok(rebaseSave({ base, latest, command: command({ summary: '我的摘要', amount: 100 }) }))
    expect(result.command.values).toEqual({ summary: '我的摘要', amount: 250 })
    expect(result.keptTheirs).toEqual(['amount'])
    expect(result.command.expectedRevision).toBe('2')
    expect(result.command.requestKey).toMatch(/^[0-9a-f-]{36}$/)
    expect(result.command.requestKey).not.toBe('first-request')
    // 其它原样
    expect(result.command).toMatchObject({
      id: 'r1',
      applicationId: 'app',
      objectId: 'object',
      formId: 'form',
      context: { pageId: 'p', nodeId: 'n', recordId: 'parent' },
      actionCode: 'confirm'
    })
  })

  it('N2 双方都改了同一个字段：用我的（后保存的生效），不算「保留了对方的」', () => {
    const base = aggregate({ summary: '旧', amount: 100 }, '1')
    const latest = aggregate({ summary: '对方的', amount: 100 }, '2')
    const result = ok(rebaseSave({ base, latest, command: command({ summary: '我的', amount: 100 }) }))
    expect(result.command.values).toEqual({ summary: '我的', amount: 100 })
    expect(result.keptTheirs).toEqual([])
  })

  it('N3 只有修订号变了（如系统算的余额变了）：值全部等于最新，没有保留对方的改动', () => {
    const base = aggregate({ summary: '摘要', amount: 100, balance: 900 }, '1')
    const latest = aggregate({ summary: '摘要', amount: 100, balance: 700 }, '5')
    const result = ok(rebaseSave({ base, latest, command: command({ summary: '摘要', amount: 100 }) }))
    expect(result.command.values).toEqual({ summary: '摘要', amount: 100 })
    expect(result.keptTheirs).toEqual([])
    expect(result.command.expectedRevision).toBe('5')
  })

  it('N4 空串、null、没有这个键互相不算改过；对象键序不同不算改过', () => {
    const base = aggregate({ a: null, b: '', d: { x: 1, y: 2 }, e: '保留' }, '1')
    const latest = aggregate({ a: '对方填了', b: '对方也填了', c: '对方新填', d: { x: 9, y: 9 }, e: '保留' }, '2')
    const mine = command({ a: '', b: null, c: null, d: { y: 2, x: 1 }, e: '保留' })
    const result = ok(rebaseSave({ base, latest, command: mine }))
    expect(result.command.values).toEqual({
      a: '对方填了',
      b: '对方也填了',
      c: '对方新填',
      d: { x: 9, y: 9 },
      e: '保留'
    })
    expect(result.keptTheirs.sort()).toEqual(['a', 'b', 'c', 'd'])
  })

  it('N4 我把一个字段清空：算改过，用我的', () => {
    const base = aggregate({ remark: '原备注' }, '1')
    const latest = aggregate({ remark: '对方改的备注' }, '2')
    const result = ok(rebaseSave({ base, latest, command: command({ remark: null }) }))
    expect(result.command.values).toEqual({ remark: null })
    expect(result.keptTheirs).toEqual([])
  })

  it('N5 提交的键集合与原提交相同，不多不少', () => {
    const base = aggregate({ a: 1, b: 2, c: 3, readonly: 'x' }, '1')
    const latest = aggregate({ a: 1, c: null, readonly: 'y', extra: 'z' }, '2')
    const result = ok(rebaseSave({ base, latest, command: command({ a: 1, b: 2, c: 3 }) }))
    expect(Object.keys(result.command.values).sort()).toEqual(['a', 'b', 'c'])
    // 对方把 c 清空了：照着清空，且这个键仍然提交
    expect(result.command.values.c).toBeNull()
    expect(JSON.parse(JSON.stringify(result.command.values))).toHaveProperty('c')
    // 最新记录里根本没有 b 这个键（读不到）：不敢当成被清空，沿用原提交的值
    expect(result.command.values.b).toBe(2)
  })
})

describe('rebaseSave 多对多', () => {
  it('N6 我没动：用最新的；我动了：用我的', () => {
    const base = aggregate({}, '1', { relations: { tags: ['1', '2'], owners: ['9'] } })
    const latest = aggregate({}, '2', { relations: { tags: ['1', '2', '3'], owners: ['8'] } })
    const mine = command({}, { relations: { tags: ['1', '2'], owners: ['9', '10'] } })
    const result = ok(rebaseSave({ base, latest, command: mine }))
    expect(result.command.relations).toEqual({ tags: ['1', '2', '3'], owners: ['9', '10'] })
    expect(result.keptTheirs).toEqual(['tags'])
  })

  it('N6 记录上没有这个关系（缺省按空）：我没动就用最新的', () => {
    const base = aggregate({}, '1')
    const latest = aggregate({}, '2', { relations: { tags: ['3'] } })
    const result = ok(rebaseSave({ base, latest, command: command({}, { relations: { tags: [] } }) }))
    expect(result.command.relations).toEqual({ tags: ['3'] })
  })

  it('N6 只是顺序不同不算改过', () => {
    const base = aggregate({}, '1', { relations: { tags: ['1', '2'] } })
    const latest = aggregate({}, '2', { relations: { tags: ['2', '1'] } })
    const result = ok(rebaseSave({ base, latest, command: command({}, { relations: { tags: ['2', '1'] } }) }))
    expect(result.keptTheirs).toEqual([])
  })
})

describe('rebaseSave 明细', () => {
  const baseRows = () => [
    line('d1', { item: '甲', qty: 1, locked: '系统算的' }),
    line('d2', { item: '乙', qty: 2, locked: '系统算的' })
  ]

  it('N7 我没动过的明细分组：从提交里去掉', () => {
    const base = aggregate({ a: 1 }, '1', { details: { lines: baseRows(), notes: [] } })
    const latest = aggregate({ a: 1 }, '2', {
      details: { lines: [line('d1', { item: '甲', qty: 5, locked: 'x' }, '2'), ...baseRows().slice(1)], notes: [] }
    })
    // 提交里只带可写字段（没有 locked）；值与打开时相同
    const mine = command(
      { a: 2 },
      { details: { lines: [line('d1', { item: '甲', qty: 1 }), line('d2', { item: '乙', qty: 2 })], notes: [] } }
    )
    const result = ok(rebaseSave({ base, latest, command: mine }))
    expect(result.command.details).toEqual({})
    expect(result.command.values).toEqual({ a: 2 })
  })

  it('N7 只调了行序：算动过，仍然提交这个分组', () => {
    const base = aggregate({}, '1', { details: { lines: baseRows() } })
    const latest = aggregate({}, '2', { details: { lines: baseRows() } })
    const mine = command(
      {},
      { details: { lines: [line('d2', { item: '乙', qty: 2 }), line('d1', { item: '甲', qty: 1 })] } }
    )
    const result = ok(rebaseSave({ base, latest, command: mine }))
    expect(result.command.details!.lines.map(row => row.id)).toEqual(['d2', 'd1'])
  })

  it('N8 同一行我改了一格、对方改了另一格：两格各取各的，行修订号取最新', () => {
    const base = aggregate({}, '1', { details: { lines: baseRows() } })
    const latest = aggregate({}, '2', {
      details: { lines: [line('d1', { item: '对方改的品名', qty: 1, locked: 'x' }, '7'), baseRows()[1]] }
    })
    const mine = command(
      {},
      { details: { lines: [line('d1', { item: '甲', qty: 99 }), line('d2', { item: '乙', qty: 2 })] } }
    )
    const result = ok(rebaseSave({ base, latest, command: mine }))
    expect(result.command.details!.lines).toEqual([
      { id: 'd1', revision: '7', clientRowKey: 'row-d1', values: { item: '对方改的品名', qty: 99 } },
      { id: 'd2', revision: '1', clientRowKey: 'row-d2', values: { item: '乙', qty: 2 } }
    ])
  })

  it('N9 我改过的行已被对方删除：拒绝，提示原文', () => {
    const base = aggregate({}, '1', { details: { lines: baseRows() } })
    const latest = aggregate({}, '2', { details: { lines: [baseRows()[1]] } })
    const mine = command(
      {},
      { details: { lines: [line('d1', { item: '甲', qty: 99 }), line('d2', { item: '乙', qty: 2 })] } }
    )
    expect(rebaseSave({ base, latest, command: mine })).toEqual({
      ok: false,
      reason: 'DETAIL_ROW_DELETED',
      message: '你修改的明细行已被别人删除，请刷新后重新填写明细'
    })
  })

  it('N10 我没改过的行被对方删除：结果里没有这行', () => {
    const base = aggregate({}, '1', { details: { lines: baseRows() } })
    const latest = aggregate({}, '2', { details: { lines: [baseRows()[1]] } })
    const mine = command(
      {},
      { details: { lines: [line('d1', { item: '甲', qty: 1 }), line('d2', { item: '乙', qty: 20 })] } }
    )
    const result = ok(rebaseSave({ base, latest, command: mine }))
    expect(result.command.details!.lines.map(row => row.id)).toEqual(['d2'])
    expect(result.command.details!.lines[0].values).toEqual({ item: '乙', qty: 20 })
  })

  it('N11 对方新增的行追加在末尾；我新增的行保留；我删的行不出现', () => {
    const base = aggregate({}, '1', { details: { lines: baseRows() } })
    const latest = aggregate({}, '2', {
      details: { lines: [...baseRows(), line('d3', { item: '对方新增', qty: 3, locked: '系统算的' }, '4')] }
    })
    const added = line(null, { item: '我新增', qty: 8 })
    const mine = command({}, { details: { lines: [line('d1', { item: '甲', qty: 1 }), added] } })
    const result = ok(rebaseSave({ base, latest, command: mine }))
    expect(result.command.details!.lines).toEqual([
      { id: 'd1', revision: '1', clientRowKey: 'row-d1', values: { item: '甲', qty: 1 } },
      added,
      // 只带本表单会写的字段，否则服务端会以「包含只读字段」拒绝
      { id: 'd3', revision: '4', clientRowKey: 'row-d3', values: { item: '对方新增', qty: 3 } }
    ])
  })

  it('N11 我把原有的行全删了、对方新增了一行：对方的行原样保留（不带任何字段值）', () => {
    const base = aggregate({}, '1', { details: { lines: baseRows() } })
    const latest = aggregate({}, '2', {
      details: { lines: [...baseRows(), line('d3', { item: '对方新增', qty: 3 }, '4')] }
    })
    const result = ok(rebaseSave({ base, latest, command: command({}, { details: { lines: [] } }) }))
    expect(result.command.details!.lines).toEqual([{ id: 'd3', revision: '4', clientRowKey: 'row-d3', values: {} }])
  })
})

describe('rebaseSave 其它', () => {
  it('N12 带关联区域的联合保存：不自动合并', () => {
    const base = aggregate({ a: 1 }, '1')
    const latest = aggregate({ a: 1 }, '2')
    const mine = command({ a: 2 }, { relatedRecords: { binding: [] } })
    expect(rebaseSave({ base, latest, command: mine })).toEqual({
      ok: false,
      reason: 'RELATED_RECORDS',
      message: '记录已被修改，请刷新后重试'
    })
    // 空的不算
    expect(rebaseSave({ base, latest, command: command({ a: 2 }, { relatedRecords: {} }) }).ok).toBe(true)
  })

  it('N13 不修改传入的对象', () => {
    const base = aggregate({ a: 1, list: ['x'] }, '1', {
      details: { lines: [line('d1', { item: '甲', qty: 1 })] },
      relations: { tags: ['1'] }
    })
    const latest = aggregate({ a: 5, list: ['y'] }, '2', {
      details: { lines: [line('d1', { item: '甲改', qty: 1 }, '2'), line('d9', { item: '新', qty: 9 }, '1')] },
      relations: { tags: ['1', '2'] }
    })
    const mine = command(
      { a: 1, list: ['x'] },
      { details: { lines: [line('d1', { item: '甲', qty: 3 })] }, relations: { tags: ['1'] } }
    )
    const before = JSON.stringify([base, latest, mine])
    const result = ok(rebaseSave({ base, latest, command: mine }))
    expect(JSON.stringify([base, latest, mine])).toBe(before)
    // 结果里的对象也不与最新记录共用：之后改结果不会改到最新记录
    ;(result.command.values.list as string[]).push('z')
    result.command.details!.lines[0].values.item = '动一下'
    expect(JSON.stringify([base, latest, mine])).toBe(before)
  })

  it('业务码判定与删除开关', () => {
    expect(isRecordConflict(Object.assign(new Error('x'), { businessCode: 1_050_000_004 }))).toBe(true)
    expect(isRecordConflict(Object.assign(new Error('x'), { businessCode: 1_050_000_001 }))).toBe(false)
    expect(isRecordConflict(new Error('网络错误'))).toBe(false)
    expect(isRecordMissing(Object.assign(new Error('x'), { businessCode: 1_050_000_002 }))).toBe(true)
    expect(isRecordMissing(undefined)).toBe(false)
    expect(REBASE_DELETE).toBe(true)
  })
})

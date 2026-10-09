import { describe, it, expect } from 'vitest'
import { detailRowChanges } from './history-details'
import type { HistoryDetailChange } from '@/types/nocode/record-history'

const group = (patch: Partial<HistoryDetailChange>): HistoryDetailChange => ({
  id: 'items',
  name: '采购明细',
  fields: [{ id: 'qty', name: '数量' }],
  before: {},
  after: {},
  beforeOrder: [],
  afterOrder: [],
  beforeKnown: true,
  afterKnown: true,
  ...patch
})
describe('整单明细历史', () => {
  it('使用稳定行 ID 区分新增、修改和删除，不把插入后的位移视为修改', () => {
    const changes = detailRowChanges(
      group({
        before: { a: { qty: '2' }, b: { qty: '3' } },
        after: { c: { qty: '1' }, a: { qty: '5' } },
        beforeOrder: ['a', 'b'],
        afterOrder: ['c', 'a']
      })
    )
    expect(changes.map(c => [c.id, c.kind, c.moved])).toEqual([
      ['c', 'CREATE', false],
      ['a', 'UPDATE', false],
      ['b', 'DELETE', false]
    ])
    expect(changes[1]?.fields).toEqual(['qty'])
  })
  it('只调整明细顺序时也呈现变化', () => {
    const rows = { a: { qty: '2' }, b: { qty: '3' } }
    expect(
      detailRowChanges(group({ before: rows, after: rows, beforeOrder: ['a', 'b'], afterOrder: ['b', 'a'] })).map(
        c => c.kind
      )
    ).toEqual(['MOVE', 'MOVE'])
  })
  it('没有历史快照时不把未知状态误算为新增或删除', () => {
    expect(detailRowChanges(group({ beforeKnown: false, after: { a: { qty: '3' } }, afterOrder: ['a'] }))).toEqual([])
  })
  it('同值明细不制造工作变化', () => {
    const rows = { a: { qty: '2' } }
    expect(detailRowChanges(group({ before: rows, after: rows, beforeOrder: ['a'], afterOrder: ['a'] }))).toEqual([])
  })
})

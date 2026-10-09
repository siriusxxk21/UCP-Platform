import { describe, expect, it } from 'vitest'
import { ALL, includesSelection, intersectSelection, isAll, resolveSelection, staleItems } from './selection-all'

describe('授权清单的「全部」', () => {
  it('只有恰好一个 * 才是全部', () => {
    expect(ALL).toBe('*')
    expect(isAll(undefined)).toBe(false)
    expect(isAll(null)).toBe(false)
    expect(isAll([])).toBe(false)
    expect(isAll(['*'])).toBe(true)
    expect(isAll(['1'])).toBe(false)
  })
  it('* 与具体项混用不是全部', () => {
    expect(isAll(['*', '1'])).toBe(false)
    expect(isAll(['1', '*'])).toBe(false)
  })
  it('展开：全部按全集顺序，清单按清单顺序并滤掉不在全集的项', () => {
    expect(resolveSelection(['*'], ['a', 'b', 'c'])).toEqual(['a', 'b', 'c'])
    expect(resolveSelection(['c', 'gone', 'a'], ['a', 'b', 'c'])).toEqual(['c', 'a'])
    expect(resolveSelection(undefined, ['a'])).toEqual([])
    expect(resolveSelection(null, ['a'])).toEqual([])
    expect(resolveSelection([], ['a'])).toEqual([])
  })
  it('展开结果是新数组，不与全集共用引用', () => {
    const universe = ['a', 'b']
    expect(resolveSelection(['*'], universe)).not.toBe(universe)
  })
  it('是否包含：全部包含任何项', () => {
    expect(includesSelection(['*'], 'anything')).toBe(true)
    expect(includesSelection(['a'], 'a')).toBe(true)
    expect(includesSelection(['a'], 'b')).toBe(false)
  })
  it('没填和空清单不包含任何项（不能把没填当成全部）', () => {
    expect(includesSelection(undefined, 'a')).toBe(false)
    expect(includesSelection(null, 'a')).toBe(false)
    expect(includesSelection([], 'a')).toBe(false)
  })
  it('已停用的项：清单里不在全集的；全部没有已停用项', () => {
    expect(staleItems(['a', 'gone', 'b', 'dead'], ['a', 'b'])).toEqual(['gone', 'dead'])
    expect(staleItems(['*'], ['a'])).toEqual([])
    expect(staleItems(undefined, ['a'])).toEqual([])
    expect(staleItems(['a'], ['a'])).toEqual([])
  })
  it('全部 ∩ 清单 = 清单（不是全部）', () => {
    expect(intersectSelection(['*'], ['a', 'b'])).toEqual(['a', 'b'])
    expect(intersectSelection(['*'], [])).toEqual([])
    expect(intersectSelection(['*'], undefined)).toEqual([])
  })
  it('清单 ∩ 全部 = 清单', () => {
    expect(intersectSelection(['b', 'a'], ['*'])).toEqual(['b', 'a'])
    expect(intersectSelection(undefined, ['*'])).toEqual([])
  })
  it('全部 ∩ 全部 = 全部', () => {
    expect(intersectSelection(['*'], ['*'])).toEqual(['*'])
  })
  it('清单 ∩ 清单 = 普通交集，保持前者顺序', () => {
    expect(intersectSelection(['c', 'a', 'x'], ['a', 'b', 'c'])).toEqual(['c', 'a'])
    expect(intersectSelection(undefined, ['a'])).toEqual([])
    expect(intersectSelection(['a'], undefined)).toEqual([])
  })
  it('交集结果是新数组，不改输入', () => {
    const a = ['a', 'b'],
      b = ['*']
    const result = intersectSelection(a, b)
    expect(result).not.toBe(a)
    expect(a).toEqual(['a', 'b'])
    expect(b).toEqual(['*'])
  })
})

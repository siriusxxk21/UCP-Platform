import { describe, expect, it, vi } from 'vitest'
import {
  mergeTaskEntrySelection,
  selectedTaskEntryIds,
  taskEntryIdentity,
  type TaskEntryCandidate
} from './task-entry-selection'
import type { TaskWorkEntryConfig } from '@/types/nocode/task-work-entries'
const candidate = (id: string, app = 'app'): TaskEntryCandidate => {
  const binding = { applicationId: app, formId: id, entryId: null }
  return {
    id: taskEntryIdentity(binding)!,
    name: id,
    applicationId: app,
    applicationName: app,
    source: 'FORM',
    binding,
    available: true
  }
}
const config = (id: string, extra: Partial<TaskWorkEntryConfig> = {}): TaskWorkEntryConfig => ({
  key: id,
  name: `原名称${id}`,
  binding: candidate(id).binding,
  dataMode: 'INDEPENDENT',
  sourceNodeId: null,
  sourceEntryKey: null,
  readableFieldIds: ['read'],
  writableFieldIds: [],
  required: true,
  allowAll: false,
  ...extra
})
describe('批量选择任务反馈入口', () => {
  it('多个视图可复用同一表单，分别配置规则而不合并', () => {
    const base = candidate('form')
    const a = { ...base, source: 'VIEW' as const, binding: { ...base.binding, viewId: 'wifi' } }
    const b = { ...base, source: 'VIEW' as const, binding: { ...base.binding, viewId: 'checkin' } }
    a.id = taskEntryIdentity(a.binding)!
    b.id = taskEntryIdentity(b.binding)!
    const result = mergeTaskEntrySelection(
      [],
      [a, b],
      [a.id, b.id],
      true,
      vi.fn().mockReturnValueOnce('a').mockReturnValueOnce('b')
    )
    expect(result).toHaveLength(2)
    expect(result.map(entry => entry.binding?.viewId)).toEqual(['wifi', 'checkin'])
  })
  it('重新确认选择不重置标准工时规则和旧业务特殊key', () => {
    const original = config('a', {
      key: '__business',
      dataScope: 'ALL',
      workRule: { mode: 'RECORD_ONCE', minutes: 15 }
    })
    expect(mergeTaskEntrySelection([original], [candidate('a')], [candidate('a').id], true, vi.fn())[0]).toBe(original)
  })
  it('一次合并多个候选，跨应用和表单／入口身份互不混淆', () => {
    const a = candidate('a'),
      b = candidate('a', 'second'),
      entry = {
        ...candidate('a'),
        source: 'ENTRY' as const,
        binding: { applicationId: 'app', entryId: 'a', formId: 'a' }
      }
    entry.id = taskEntryIdentity(entry.binding)!
    const result = mergeTaskEntrySelection(
      [],
      [a, b, entry],
      [a.id, b.id, entry.id],
      true,
      vi.fn().mockReturnValueOnce('1').mockReturnValueOnce('2').mockReturnValueOnce('3')
    )
    expect(result).toHaveLength(3)
    expect(result.every(c => c.dataMode === 'ROOT_SHARED')).toBe(true)
    expect(result.every(c => c.dataScope === 'GROUP')).toBe(true)
    expect(new Set(result.map(c => c.key)).size).toBe(3)
  })
  it('已有 key、顺序和高级配置保持原对象，重复确认不新增', () => {
    const existing = [config('b'), config('a')],
      a = candidate('a'),
      b = candidate('b')
    const key = vi.fn()
    const result = mergeTaskEntrySelection(existing, [a, b], [a.id, b.id, a.id], false, key)
    expect(result).toEqual(existing)
    expect(result[0]).toBe(existing[0])
    expect(result[1]).toBe(existing[1])
    expect(key).not.toHaveBeenCalled()
  })
  it('明确取消有效候选只移除对应配置，未加载／失权和指定来源共享继续保留', () => {
    const normal = config('a'),
      unavailable = config('lost'),
      unloaded = config('old'),
      shared = config('shared', {
        dataMode: 'SOURCE_SHARED',
        sourceNodeId: 'before',
        sourceEntryKey: 'work',
        binding: normal.binding
      })
    const result = mergeTaskEntrySelection(
      [normal, unavailable, unloaded, shared],
      [candidate('a'), { ...candidate('lost'), available: false }],
      [],
      false,
      vi.fn()
    )
    expect(result).toEqual([unavailable, unloaded, shared])
  })
  it('同资源历史多组不合并、不重写共享来源，新增资源仅追加', () => {
    const original = [config('a'), config('alias', { binding: candidate('a').binding })],
      b = candidate('b')
    const result = mergeTaskEntrySelection(original, [candidate('a'), b], [candidate('a').id, b.id], false, () => 'new')
    expect(result.slice(0, 2)).toEqual(original)
    expect(result[2]?.key).toBe('new')
    expect(selectedTaskEntryIds(result)).toHaveLength(2)
  })
  it('超过服务端入口上限时不修改传入配置', () => {
    const original = Array.from({ length: 20 }, (_, i) => config(`old${i}`)),
      extra = candidate('extra')
    expect(() => mergeTaskEntrySelection(original, [extra], [extra.id], false, () => 'extra')).toThrow('20')
    expect(original).toHaveLength(20)
  })
})

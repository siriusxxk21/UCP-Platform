import { describe, expect, it } from 'vitest'
import { taskEntryScope, taskEntryScopeLabel } from './task-entry-scope'
import type { TaskWorkEntryConfig } from '@/types/nocode/task-work-entries'

const config = (key: string, dataScope?: 'GROUP' | 'ALL' | null, allowAll = false): TaskWorkEntryConfig => ({
  key,
  name: key,
  dataScope,
  allowAll,
  binding: null,
  dataMode: 'ROOT_SHARED',
  sourceNodeId: null,
  sourceEntryKey: null,
  readableFieldIds: null,
  writableFieldIds: null,
  required: false
})
const policy = { version: 1 as const, business: 'ALL' as const, feedback: 'GROUP' as const }

describe('业务办理项独立数据范围', () => {
  it('每项明确范围优先，改一项不影响另一项或总任务历史策略', () => {
    const first = config('wifi', 'ALL'),
      second = config('devices', 'GROUP')
    expect(taskEntryScope(first, policy)).toBe('ALL')
    expect(taskEntryScope(second, policy)).toBe('GROUP')
    expect(taskEntryScope(config('__business', 'GROUP'), policy)).toBe('GROUP')
    expect(policy.business).toBe('ALL')
  })
  it('历史业务和反馈按原策略分别兼容，读取不写入配置', () => {
    const old = config('__business')
    expect(taskEntryScope(old, policy)).toBe('ALL')
    expect(taskEntryScope(config('feedback', null), policy)).toBe('GROUP')
    expect(old.dataScope).toBeUndefined()
  })
  it('旧逐节点模式尊重allowAll，新显式GROUP不得被旧allowAll覆盖', () => {
    expect(taskEntryScope(config('old', null, true))).toBe('ALL')
    expect(taskEntryScope(config('old'))).toBe('GROUP')
    expect(taskEntryScope(config('old', 'GROUP', true))).toBe('GROUP')
  })
  it('摘要明确当前项的记录范围，不与数据共享方式混淆', () => {
    expect(taskEntryScopeLabel('GROUP')).toBe('仅本组任务数据')
    expect(taskEntryScopeLabel('ALL')).toBe('全部业务数据')
  })
})

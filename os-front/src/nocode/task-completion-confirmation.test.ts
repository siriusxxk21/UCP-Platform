import { describe, expect, it } from 'vitest'
import type { TaskReadiness } from '@/types/nocode/task-center'
import { cancellationConfirmationRequired, taskCompletionReady } from './task-completion-confirmation'

const readiness = (): TaskReadiness => ({
  taskId: 'root',
  revision: 1,
  canComplete: false,
  canCancel: true,
  cancelBlockedReason: null,
  cancellationImpacts: [],
  checks: [
    {
      code: 'CHILDREN_CANCELLED',
      label: '确认取消后的交付范围',
      passed: false,
      reason: '补漆已取消，请确认剩余范围',
      entryKey: null
    }
  ]
})

describe('取消工作后的收尾确认', () => {
  it('取消范围需要主动确认和有效交付说明，不能按全部成功完成', () => {
    const value = readiness()
    expect(cancellationConfirmationRequired(value)).toBe(true)
    expect(taskCompletionReady(value, false, '剩余范围已交付')).toBe(false)
    expect(taskCompletionReady(value, true, '  ')).toBe(false)
    expect(taskCompletionReady(value, true, '剩余范围已交付')).toBe(true)
  })
  it('确认取消范围也不能绕过其他失败或缺失预检', () => {
    for (const code of ['STATE', 'ASSIGNEE', 'CHILDREN', 'BUSINESS', 'FEEDBACK'] as const) {
      const value = readiness()
      value.checks.push({ code, label: '其他必要条件', passed: false, reason: '未满足', entryKey: null })
      expect(taskCompletionReady(value, true, '已确认')).toBe(false)
    }
    expect(taskCompletionReady(null, true, '已确认')).toBe(false)
    expect(taskCompletionReady({ ...readiness(), checks: [] }, true, '已确认')).toBe(false)
    expect(taskCompletionReady({ ...readiness(), checks: [], canComplete: true }, false, '')).toBe(true)
  })
})

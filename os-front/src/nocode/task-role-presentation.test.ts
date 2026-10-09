import { describe, expect, it } from 'vitest'
import dayjs from 'dayjs'
import { taskDueHint, taskExecutionHint, taskPersonalProgress } from './task-role-presentation'

describe('任务角色视图只读摘要', () => {
  it('本人已办与整项状态分开，收尾阻塞采用后端实际原因', () => {
    expect(taskPersonalProgress({})).toBe('')
    expect(taskPersonalProgress({ myPendingCount: 2, myCompletedCount: 1 })).toBe('我待处理 2 项 · 已办 1 项')
    expect(taskPersonalProgress({ myPendingCount: 0, myCompletedCount: 3 }, true)).toBe('我的部分已处理')
    expect(taskPersonalProgress({ myPendingCount: 1, myCompletedCount: 2 }, true)).toBe('我已办 2 项，另有 1 项待处理')
    expect(
      taskExecutionHint({
        status: 'RUNNING',
        canStart: false,
        blockedReason: null,
        completionReason: '待补交付资料：施工照片'
      })
    ).toBe('待补交付资料：施工照片')
  })
  it('依照服务端资格显示可开始，压缩已知阻塞原因并保留未知原因', () => {
    const pending = { status: 'PENDING' as const, canStart: false, blockedReason: null }
    expect(taskExecutionHint({ ...pending, canStart: true })).toBe('可开始')
    expect(taskExecutionHint({ ...pending, blockedReason: '前置任务「A」（负责人：张伟）尚未完成' })).toBe(
      '等待 A 完成'
    )
    expect(taskExecutionHint({ ...pending, blockedReason: '等待上级任务「施工」（负责人：李工）开始' })).toBe(
      '等待上级开始'
    )
    expect(taskExecutionHint({ ...pending, blockedReason: '尚未到预计开始时间：2030-10-10 10:00' })).toBe(
      '等待计划开始'
    )
    expect(taskExecutionHint({ ...pending, blockedReason: '缺少必要材料' })).toBe('缺少必要材料')
    expect(taskExecutionHint({ ...pending, status: 'RUNNING', blockedReason: '任务已开始' })).toBe('')
  })
  it('截止提醒按日计算，不把今天或已关闭的任务误报为逾期', () => {
    const today = dayjs('2030-10-05T15:00:00')
    const task = { status: 'PENDING' as const, expectedEnd: '2030-10-05T00:00:00' }
    expect(taskDueHint(task, today)?.text).toBe('今天到期')
    expect(taskDueHint({ ...task, expectedEnd: '2030-10-06' }, today)?.text).toBe('明天到期')
    expect(taskDueHint({ ...task, expectedEnd: '2030-10-03' }, today)).toEqual({ text: '已逾期 2 天', tone: 'danger' })
    for (const expectedEnd of [null, 'invalid', '2030-10-12'])
      expect(taskDueHint({ ...task, expectedEnd }, today)).toBeNull()
    for (const status of ['COMPLETED', 'CANCELLED'] as const)
      expect(taskDueHint({ status, expectedEnd: '2030-10-01' }, today)).toBeNull()
  })
})

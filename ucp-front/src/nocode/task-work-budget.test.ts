import { describe, expect, it } from 'vitest'
import { taskWorkBudgetMinutes, taskWorkBudgetIssues } from './task-work-budget'
import {
  taskBusinessConfigError,
  taskWorkBudgetError,
  taskWorkRuleError,
  taskWorkBudgetEntries,
  formatTaskWorkQuantity
} from './task-work-rule'
import { newTaskNode, taskNodeInput } from './task-center'
import type { TaskRow } from '@/types/nocode/task-center'
import type { TaskWorkEntryConfig, TaskWorkRule } from '@/types/nocode/task-work-entries'

function entry(rule: TaskWorkRule): TaskWorkEntryConfig {
  return {
    key: 'form',
    name: '表单',
    binding: null,
    dataMode: 'ROOT_SHARED',
    sourceNodeId: null,
    sourceEntryKey: null,
    readableFieldIds: null,
    writableFieldIds: null,
    required: false,
    allowAll: false,
    workRule: rule
  }
}
describe('工时预算与旧模板兼容', () => {
  it('三模式按预计量合计，条件否值不是缺失', () => {
    const entries = [
      entry({ mode: 'RECORD_ONCE', minutes: 10, plannedQuantity: 2 }),
      entry({ mode: 'QUANTITY', minutes: 20, quantityFieldId: 'qty', plannedQuantity: 1.5 }),
      entry({ mode: 'CONDITION', minutes: 5, conditionFieldId: 'ready', conditionValue: false, plannedQuantity: 3 })
    ]
    expect(taskWorkBudgetMinutes(entries)).toBe(65)
  })
  it('小数先求和再取整，采用实例差额后的工时', () => {
    const rule: TaskWorkRule = {
      mode: 'QUANTITY',
      minutes: 10,
      adjustmentMinutes: -9,
      quantityFieldId: 'q',
      plannedQuantity: 0.4
    }
    expect(taskWorkBudgetMinutes([entry(rule), entry(rule)])).toBe(1)
  })
  it('旧预计量不推定1，手工旧总额不受强制填量限制', () => {
    const old = entry({ mode: 'RECORD_ONCE', minutes: 10 })
    expect(taskWorkBudgetMinutes([old])).toBeNull()
    expect(taskWorkBudgetIssues([old])).toHaveLength(1)
    const node = { ...newTaskNode(), entries: [old], effectiveWorkMinutes: 75 }
    expect(taskBusinessConfigError([node])).toBe('')
    expect(taskBusinessConfigError([{ ...node, workTotalMode: 'AUTO' }])).toBe('')
    expect(node.effectiveWorkMinutes).toBe(75)
  })
  it('预计量范围和条数整数校验与三模式统一', () => {
    expect(taskWorkRuleError({ mode: 'RECORD_ONCE', minutes: 10, plannedQuantity: 1.5 })).toContain('整数')
    for (const quantity of [-1, Infinity, Number.NaN, 1000000])
      expect(taskWorkRuleError({ mode: 'RECORD_ONCE', minutes: 10, plannedQuantity: quantity })).toContain('预计工作量')
  })
  it('预计量可为零，数量最多六位小数，合计避免浮点进位', () => {
    const rule: TaskWorkRule = { mode: 'QUANTITY', minutes: 50, quantityFieldId: 'q', plannedQuantity: 0.14 }
    expect(taskWorkBudgetMinutes([entry(rule)])).toBe(7)
    expect(taskWorkRuleError({ ...rule, plannedQuantity: 0.1234567 })).toContain('6 位')
    expect(taskWorkRuleError({ ...rule, plannedQuantity: 0.123456 })).toBe('')
    const zero = entry({ mode: 'RECORD_ONCE', minutes: 10, plannedQuantity: 0 })
    expect(taskWorkRuleError(zero.workRule)).toBe('')
    expect(taskWorkBudgetMinutes([zero])).toBeNull()
    expect(taskWorkBudgetMinutes([zero, entry(rule)])).toBe(7)
  })
  it('旧独立办理项合计整组，新统一授权只计根声明项', () => {
    const root = {
      ...newTaskNode(),
      id: 'root',
      workTotalMode: 'AUTO' as const,
      entries: [entry({ mode: 'RECORD_ONCE', minutes: 10, plannedQuantity: 1 })]
    }
    const child = {
      ...newTaskNode(),
      id: 'child',
      parentId: 'root',
      entries: [entry({ mode: 'RECORD_ONCE', minutes: 20, plannedQuantity: 2 })]
    }
    expect(taskWorkBudgetMinutes(taskWorkBudgetEntries(root, [root, child]))).toBe(50)
    expect(taskBusinessConfigError([root, child])).toBe('')
    expect(
      taskWorkBudgetMinutes(
        taskWorkBudgetEntries({ ...root, dataPolicy: { version: 1, business: 'GROUP', feedback: 'GROUP' } }, [
          root,
          child
        ])
      )
    ).toBe(10)
  })
  it('空规则和超额不展示虚假合计', () => {
    expect(taskWorkBudgetMinutes([])).toBeNull()
    expect(taskWorkBudgetMinutes([entry({ mode: 'RECORD_ONCE', minutes: 599999, plannedQuantity: 2 })])).toBeNull()
    expect(
      taskBusinessConfigError([
        {
          ...newTaskNode(),
          workTotalMode: 'AUTO',
          entries: [entry({ mode: 'RECORD_ONCE', minutes: 599999, plannedQuantity: 2 })]
        }
      ])
    ).toContain('预计合计超出')
  })
  it('部分不计工时不假造完整总额，也不妨碍其他项计时和任务发布', () => {
    const entries = [
      entry({ mode: 'RECORD_ONCE', minutes: 15, plannedQuantity: 2 }),
      { ...entry({ mode: 'RECORD_ONCE', minutes: 0 }), workRule: null }
    ]
    expect(taskWorkBudgetMinutes(entries)).toBeNull()
    expect(taskBusinessConfigError([{ ...newTaskNode(), entries, workTotalMode: 'AUTO' }])).toBe('')
    expect(entries[0]?.workRule?.minutes).toBe(15)
  })
  it('部分项未填写时，已填写项目的小计超限仍需修正', () => {
    const entries = [
      entry({ mode: 'RECORD_ONCE', minutes: 599999, plannedQuantity: 2 }),
      entry({ mode: 'RECORD_ONCE', minutes: 15 })
    ]
    expect(taskWorkBudgetMinutes(entries)).toBeNull()
    expect(taskWorkBudgetError(entries)).toContain('预计合计超出')
    expect(taskBusinessConfigError([{ ...newTaskNode(), entries, workTotalMode: 'AUTO' }])).toContain('预计合计超出')
  })
  it('预计量整数不补尾零，输入中和清空仍可正常编辑', () => {
    expect(formatTaskWorkQuantity('4.000000', { userTyping: false, input: '' })).toBe('4')
    expect(formatTaskWorkQuantity(4, { userTyping: true, input: '4.' })).toBe('4.')
    expect(formatTaskWorkQuantity(null, { userTyping: false, input: '' })).toBe('')
    expect(formatTaskWorkQuantity('0.123456', { userTyping: false, input: '' })).toBe('0.123456')
  })
  it('实例编排转换保留总工时模式，子任务不重复预算', () => {
    const row = {
      ...newTaskNode(),
      id: 'root',
      rootId: 'root',
      workTotalMode: 'AUTO',
      effectiveWorkMinutes: 20
    } as TaskRow
    expect(taskNodeInput(row).workTotalMode).toBe('AUTO')
    expect(taskNodeInput({ ...row, id: 'child', parentId: 'root' }).workTotalMode).toBeUndefined()
  })
})

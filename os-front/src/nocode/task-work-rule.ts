import type { TaskWorkRule, TaskWorkRuleMode, TaskWorkEntryConfig } from '@/types/nocode/task-work-entries'
import type { ObjectField } from '@/types/nocode/object'
import type { TaskNodeInput } from '@/types/nocode/task-center'
import { formatEffectiveWorkMinutes, validEffectiveWorkMinutes } from './task-work-duration'

export const taskWorkRuleOptions: Array<{ value: TaskWorkRuleMode; label: string; description: string }> = [
  { value: 'RECORD_ONCE', label: '每条固定工时', description: '如：每条日志计 3 小时' },
  { value: 'QUANTITY', label: '按数量计算', description: '如：每台设备计 15 分钟' },
  { value: 'CONDITION', label: '满足条件后计入', description: '如：状态变为已完成时计入' }
]
export function taskWorkRuleSummary(rule?: TaskWorkRule | null) {
  if (!rule || taskWorkRuleMinutes(rule) === 0) return '未设置标准工时'
  const unit = rule.mode === 'QUANTITY' ? '单位' : '条'
  return `${formatEffectiveWorkMinutes(taskWorkRuleMinutes(rule))} / ${unit} · ${taskWorkRuleOptions.find(o => o.value === rule.mode)?.label || '每条固定工时'}`
}
/** 配置预览只解释既有规则；不把标准工时当作实际耗时，也不改变历史计量口径。 */
export function taskWorkRulePreview(
  rule: TaskWorkRule | null | undefined,
  fields: ObjectField[],
  options: Array<{ value: unknown; label: string }> = []
) {
  if (
    !rule ||
    !validEffectiveWorkMinutes(rule.minutes) ||
    rule.minutes < 1 ||
    !validEffectiveWorkMinutes(taskWorkRuleMinutes(rule)) ||
    taskWorkRuleMinutes(rule) < 1
  )
    return '填写标准工时后，查看计算方式'
  const duration = formatEffectiveWorkMinutes(taskWorkRuleMinutes(rule))
  if (rule.mode === 'RECORD_ONCE') return `每条记录计 ${duration}`
  if (rule.mode === 'QUANTITY') {
    const field = fields.find(item => item.id === rule.quantityFieldId)
    return field ? `工时 = ${field.name} × ${duration}` : '选择数量字段后，查看计算方式'
  }
  const field = fields.find(item => item.id === rule.conditionFieldId)
  if (!field || rule.conditionValue == null || rule.conditionValue === '') return '设置条件后，查看计算方式'
  const value =
    options.find(option => option.value === rule.conditionValue)?.label ||
    (typeof rule.conditionValue === 'boolean' ? (rule.conditionValue ? '是' : '否') : String(rule.conditionValue))
  return `「${field.name}」首次为「${value}」时，每条计 ${duration}`
}
export function taskWorkRuleMinutes(rule: TaskWorkRule) {
  return rule.minutes + (rule.adjustmentMinutes || 0)
}
export function taskWorkAdjustmentSummary(rule?: TaskWorkRule | null) {
  if (!rule?.adjustmentMinutes) return ''
  return `模板 ${formatEffectiveWorkMinutes(rule.minutes)} · 本次${rule.adjustmentMinutes > 0 ? '增加' : '减少'} ${formatEffectiveWorkMinutes(Math.abs(rule.adjustmentMinutes))}`
}
export function taskWorkRuleFields(fields: ObjectField[], mode: TaskWorkRuleMode) {
  const types: string[] =
    mode === 'QUANTITY'
      ? ['INTEGER', 'DECIMAL']
      : ['TEXT', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'SELECT', 'DATE', 'DATETIME']
  return fields.filter(field => field.id && types.includes(field.type))
}
export function taskWorkRuleError(rule?: TaskWorkRule | null) {
  if (!rule) return ''
  if (!Number.isInteger(rule.minutes) || rule.minutes < 0 || rule.minutes > 599999)
    return '标准工时须为 0 至 9999 小时 59 分钟。'
  if (
    !Number.isInteger(rule.adjustmentMinutes ?? 0) ||
    taskWorkRuleMinutes(rule) < 0 ||
    taskWorkRuleMinutes(rule) > 599999
  )
    return '调整后的标准工时须为 0 至 9999 小时 59 分钟。'
  // 工时独立于业务办理；兼容旧版创建的零分钟占位，停计时仍校验已输入的预计量。
  if (rule.minutes === 0 && taskWorkRuleMinutes(rule) === 0) return taskWorkPlannedQuantityError(rule)
  if (rule.mode === 'QUANTITY' && !rule.quantityFieldId) return '请选择用于计量的数量字段。'
  if (
    rule.mode === 'CONDITION' &&
    (!rule.conditionFieldId || rule.conditionValue == null || rule.conditionValue === '')
  )
    return '请选择条件字段并填写达到条件时的值。'
  return taskWorkPlannedQuantityError(rule)
}

/** 预计工作量只参与预算；旧配置缺值仍为空，不推定为一条。 */
export function taskWorkPlannedQuantityError(rule: TaskWorkRule) {
  const quantity = rule.plannedQuantity
  if (quantity == null) return ''
  if (!Number.isFinite(quantity) || quantity < 0 || quantity > 999999) return '预计工作量须为 0 至 999999。'
  if (rule.mode !== 'QUANTITY' && !Number.isInteger(quantity)) return '预计记录条数须为整数。'
  if (rule.mode === 'QUANTITY' && Number(quantity.toFixed(6)) !== quantity) return '预计工作量最多保留 6 位小数。'
  return ''
}
/** 保留输入中的小数点，失焦后不把整数显示成六位小数。 */
export function formatTaskWorkQuantity(
  value: string | number | null | undefined,
  info: { userTyping: boolean; input: string }
) {
  if (info.userTyping) return info.input
  if (value == null || value === '') return ''
  return Number.isFinite(Number(value)) ? String(Number(value)) : String(value)
}
export function taskWorkBudgetIssues(entries: TaskWorkEntryConfig[]) {
  return entries.filter(entry => {
    const rule = entry.workRule
    return !rule || taskWorkRuleMinutes(rule) === 0 || !!taskWorkRuleError(rule) || rule.plannedQuantity == null
  })
}
/** 缺省工时和预计量不报错；已填写项目的小计越界仍阻止提交。 */
export function taskWorkBudgetError(entries: TaskWorkEntryConfig[]) {
  const total = entries.reduce((sum, entry) => {
    const rule = entry.workRule
    return sum + (rule && !taskWorkRuleError(rule) ? taskWorkRuleMinutes(rule) * (rule.plannedQuantity ?? 0) : 0)
  }, 0)
  return total > 599999 ? '预计合计超出 9999 小时 59 分钟，请调整预计量或工时。' : ''
}
export function taskWorkBudgetMinutes(entries: TaskWorkEntryConfig[]): number | null {
  if (taskWorkBudgetIssues(entries).length || !entries.some(entry => entry.workRule)) return null
  // 六位定点数求和后再取整，避免 0.14 × 50 的浮点尾差把 7 分钟变成 8 分钟。
  const scale = 1000000n
  const scaled = entries.reduce((sum, entry) => {
    const rule = entry.workRule
    return (
      sum +
      (rule ? BigInt(taskWorkRuleMinutes(rule)) * BigInt(Math.round((rule.plannedQuantity ?? 0) * Number(scale))) : 0n)
    )
  }, 0n)
  const total = Number((scaled + scale - 1n) / scale)
  return total > 0 && total <= 599999 ? total : null
}

/** 统一授权只在根配置；历史独立办理项按整组显式声明合计，继承项不重复计算。 */
export function taskWorkBudgetEntries(root: TaskNodeInput, nodes: TaskNodeInput[]) {
  return (root.dataPolicy ? [root] : [root, ...nodes.filter(node => node.id !== root.id)]).flatMap(
    node => node.entries || []
  )
}

/** 工时是可选统计配置；不设置或不填写预计量，不影响任务发布、发起和办理。 */
export function taskBusinessConfigError(nodes: TaskNodeInput[]) {
  for (const node of nodes) {
    for (const entry of node.entries || []) {
      const error = taskWorkRuleError(entry.workRule)
      if (error) return `${node.title || '未命名任务'} / ${entry.name || '业务办理项'}：${error}`
    }
    const budgetError = node.workTotalMode === 'AUTO' ? taskWorkBudgetError(taskWorkBudgetEntries(node, nodes)) : ''
    if (budgetError) return `${node.title || '未命名任务'}：${budgetError}`
  }
  return ''
}

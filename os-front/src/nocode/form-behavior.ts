import type { FieldBehavior, UiNode } from '@/types/nocode/application-ui'
import type { DocumentExpression } from '@/types/nocode/document-policy'
import { compareRecordNumbers } from './record-number'

export const emptyFormValue = (value: unknown) =>
  value == null || (typeof value === 'string' && !value.trim()) || (Array.isArray(value) && !value.length)

/** 与整单条件使用同一组有界算子；字段类型由已发布对象提供，保留数值精度。 */
export function formCondition(
  expression: DocumentExpression,
  values: Record<string, unknown>,
  numericFields: Set<string> = new Set()
): unknown {
  const evaluate = (e: DocumentExpression, depth: number): unknown => {
    if (depth > 16) throw new Error('表单条件嵌套过深')
    const args = e.args || []
    const run = (a: DocumentExpression) => evaluate(a, depth + 1)
    switch (e.op) {
      case 'VALUE':
        return e.value ?? null
      case 'FIELD':
        return values[e.fieldId!] ?? null
      case 'AND':
        return args.every(a => run(a) === true)
      case 'OR':
        return args.some(a => run(a) === true)
      case 'NOT':
        return run(args[0]!) !== true
      case 'EMPTY':
        return emptyFormValue(run(args[0]!))
      case 'SUM':
      case 'COUNT':
      case 'UNIQUE':
        throw new Error('表单条件不支持跨明细取数')
      default: {
        const a = run(args[0]!),
          b = run(args[1]!)
        if (a == null || b == null) return e.op === 'EQ' ? a === b : e.op === 'NE' && a !== b
        const numeric =
          typeof a === 'number' ||
          typeof b === 'number' ||
          args.some(v => v.op === 'FIELD' && numericFields.has(v.fieldId!))
        const compared = numeric
          ? compareRecordNumbers(a, b)
          : String(a) === String(b)
            ? 0
            : String(a) < String(b)
              ? -1
              : 1
        if (compared == null) return false
        switch (e.op) {
          case 'EQ':
            return compared === 0
          case 'NE':
            return compared !== 0
          case 'GT':
            return compared > 0
          case 'GE':
            return compared >= 0
          case 'LT':
            return compared < 0
          case 'LE':
            return compared <= 0
          default:
            throw new Error('表单条件无效')
        }
      }
    }
  }
  return evaluate(expression, 0)
}

export function behaviorState(
  behavior: FieldBehavior | null | undefined,
  values: Record<string, unknown>,
  numericFields?: Set<string>
) {
  const matches = (condition: DocumentExpression | null | undefined) =>
    !!condition && formCondition(condition, values, numericFields) === true
  return {
    visible: !behavior?.showWhen || matches(behavior.showWhen),
    required: matches(behavior?.requiredWhen),
    readOnly: matches(behavior?.readOnlyWhen)
  }
}

export function behaviorNodes(nodes: UiNode[]): UiNode[] {
  return nodes.flatMap(n => [...(n.fieldId && n.presentation?.behavior ? [n] : []), ...behaviorNodes(n.children)])
}

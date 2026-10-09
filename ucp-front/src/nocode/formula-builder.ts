import type { ObjectField } from '@/types/nocode/object'
import type { FieldOptions } from '@/types/nocode/data-center'
import {
  dateFormulaArguments,
  dateFormulaArityError,
  dateFormulaOperation,
  dateFormulaOperations,
  describeDateFormula
} from './formula-dates'

/** 只负责现有公式语法与选择式配置的转换，不在浏览器执行表达式或业务数据计算。 */
export type FormulaNode =
  | { kind: 'field'; value: string }
  | { kind: 'number'; value: string }
  | { kind: 'text'; value: string }
  | { kind: 'boolean'; value: string }
  | { kind: 'null'; value: string }
  | { kind: 'operation'; operation: string; args: FormulaNode[] }

export const formulaOperations = [
  { value: '+', label: '相加', symbol: '+', count: 2 },
  { value: '-', label: '相减', symbol: '−', count: 2 },
  { value: '*', label: '相乘', symbol: '×', count: 2 },
  { value: '/', label: '相除', symbol: '÷', count: 2 },
  { value: 'round', label: '四舍五入', count: 2 },
  { value: 'abs', label: '取绝对值', count: 1 },
  { value: '||', label: '拼接文字', symbol: '拼接', count: 2 },
  { value: 'coalesce', label: '空值时使用备用值', count: 2 },
  { value: 'upper', label: '字母转大写', count: 1 },
  { value: 'lower', label: '字母转小写', count: 1 },
  { value: 'if', label: '如果…那么…否则…', count: 3 },
  { value: '=', label: '等于', symbol: '=', count: 2 },
  { value: '!=', label: '不等于', symbol: '≠', count: 2 },
  { value: '>', label: '大于', symbol: '>', count: 2 },
  { value: '>=', label: '大于等于', symbol: '≥', count: 2 },
  { value: '<', label: '小于', symbol: '<', count: 2 },
  { value: '<=', label: '小于等于', symbol: '≤', count: 2 },
  { value: 'and', label: '全部条件满足', count: 2 },
  { value: 'or', label: '任一条件满足', count: 2 },
  { value: 'not', label: '条件取反', count: 1 },
  { value: 'isblank', label: '是否为空', count: 1 },
  // 日期函数（清单、参数提示与类型检查在 formula-dates.ts）。
  ...dateFormulaOperations.map(({ value, label, count }) => ({ value, label, count }))
]

const comparisonOperators = ['=', '==', '!=', '<>', '>', '>=', '<', '<=']
const binaryOperators = [...comparisonOperators, '+', '-', '*', '/', '||']

export const emptyFormula = (): FormulaNode => ({ kind: 'field', value: '' })
export function operationFormula(operation: string, first = emptyFormula()): FormulaNode {
  const count = formulaOperations.find(item => item.value === operation)?.count ?? 2
  const dateArguments = dateFormulaArguments(operation, first)
  if (dateArguments) return { kind: 'operation', operation, args: dateArguments }
  return {
    kind: 'operation',
    operation,
    args: [
      first,
      ...Array.from({ length: count - 1 }, () =>
        operation === 'round' ? { kind: 'number' as const, value: '2' } : emptyFormula()
      )
    ]
  }
}

export function formulaFields(
  fields: ObjectField[],
  currentKey: string,
  local: boolean,
  options: Record<string, FieldOptions>
) {
  return fields.filter(
    field =>
      field.key !== currentKey &&
      field.code &&
      options[field.key]?.state !== 'INACTIVE' &&
      !['URL', 'IMAGE', 'ATTACHMENT', 'MULTI_SELECT', 'REGION', 'CASCADE', 'RICH_TEXT'].includes(field.type) &&
      (local || !['FORMULA', 'SUMMARY'].includes(field.type))
  )
}

/** 顺序计算只能依赖本行确定性结果；汇总、跨记录取值和循环依赖不能间接混入。 */
export function sequenceFormulaFields(
  fields: ObjectField[],
  currentKey: string,
  options: Record<string, FieldOptions>
) {
  const byCode = new Map(fields.map(field => [field.code, field]))
  function references(node: FormulaNode): string[] {
    return node.kind === 'field' ? [node.value] : node.kind === 'operation' ? node.args.flatMap(references) : []
  }
  function permitted(field: ObjectField, visited: Set<string>): boolean {
    if (field.key === currentKey || options[field.key]?.state === 'INACTIVE' || field.type === 'SUMMARY') return false
    if (field.type !== 'FORMULA') return true
    const setting = options[field.key]
    if ((setting?.calculation && setting.calculation.mode !== 'LOCAL') || visited.has(field.code)) return false
    try {
      const ancestors = new Set([...visited, field.code])
      return references(parseFormula(setting?.expression || '')).every(code => {
        const dependency = byCode.get(code)
        return !!dependency && permitted(dependency, ancestors)
      })
    } catch {
      return false
    }
  }
  return formulaFields(fields, currentKey, true, options).filter(field => permitted(field, new Set()))
}

/** 括号遵循服务端优先级；保留右侧同级运算分组，避免减法、除法和舍入顺序改变。 */
export function serializeFormula(node: FormulaNode): string {
  if (node.kind === 'field') return node.value
  if (node.kind === 'text') return `'${node.value.replaceAll("'", "''")}'`
  if (node.kind === 'number') return node.value
  if (node.kind === 'boolean' || node.kind === 'null') return node.kind === 'null' ? 'null' : node.value
  const priority = (item: FormulaNode): number =>
    item.kind === 'operation' && binaryOperators.includes(item.operation)
      ? comparisonOperators.includes(item.operation)
        ? 0
        : ['*', '/'].includes(item.operation)
          ? 2
          : 1
      : 3
  if (binaryOperators.includes(node.operation)) {
    return node.args
      .map((arg, index) => {
        const value = serializeFormula(arg)
        return priority(arg) < priority(node) ||
          (priority(arg) === priority(node) && (index > 0 || comparisonOperators.includes(node.operation)))
          ? `(${value})`
          : value
      })
      .join(` ${node.operation} `)
  }
  return `${node.operation}(${node.args.map(serializeFormula).join(', ')})`
}

export function describeFormula(node: FormulaNode, fields: ObjectField[]): string {
  if (node.kind === 'field')
    return `【${fields.find(field => field.code === node.value)?.name || node.value || '选择字段'}】`
  if (node.kind === 'number') return node.value || '填写数字'
  if (node.kind === 'text') return `“${node.value}”`
  if (node.kind === 'boolean') return node.value === 'true' ? '是' : '否'
  if (node.kind === 'null') return '空值'
  const op = formulaOperations.find(item => item.value === node.operation)
  const args = node.args.map(arg => describeFormula(arg, fields))
  const dated = describeDateFormula(node.operation, args)
  if (dated) return dated
  if (op?.symbol) return `(${args.join(` ${op.symbol} `)})`
  if (node.operation === 'round') return `${args[0]}，四舍五入保留 ${args[1] ?? '0'} 位`
  if (node.operation === 'coalesce') return `依次取首个非空值：${args.join(' → ')}`
  if (node.operation === 'if') return `如果 ${args[0]}，那么 ${args[1]}，否则 ${args[2]}`
  return `${op?.label ?? node.operation}（${args.join('，')}）`
}

export function formulaNodeError(node: FormulaNode, fields: ObjectField[], conditional = false): string | null {
  if (node.kind === 'field')
    return fields.some(field => field.code === node.value)
      ? null
      : node.value
        ? `字段“${node.value}”不可用，请重新选择`
        : '请选择参与计算的字段'
  if (node.kind === 'number') return /^-?(?:\d+(?:\.\d*)?|\.\d+)$/.test(node.value) ? null : '请填写有效数字'
  if (node.kind === 'text' || node.kind === 'null') return null
  if (node.kind === 'boolean') return ['true', 'false'].includes(node.value) ? null : '请选择是或否'
  for (const [index, arg] of node.args.entries()) {
    const error = formulaNodeError(
      arg,
      fields,
      conditional || (node.operation === 'if' && index > 0) || ['and', 'or'].includes(node.operation)
    )
    if (error) return error
  }
  if (!conditional && node.operation === '/' && node.args[1]?.kind === 'number' && Number(node.args[1].value) === 0)
    return '除数不能为 0'
  if (!conditional && node.operation === 'round' && node.args[1]?.kind === 'number') {
    const scale = Number(node.args[1].value)
    if (!Number.isInteger(scale) || Math.abs(scale) > 10) return '保留位数须为 -10 到 10 的整数'
  }
  return null
}

/** 与 FieldExpressions 的白名单、结合顺序和转义规则保持一致；不支持的旧内容保留在表达式模式。 */
export function parseFormula(source: string): FormulaNode {
  if (!source.trim() || source.length > 1000) throw new Error('请完成计算配置，公式最多 1000 个字符')
  let position = 0,
    nodes = 0
  const space = () => {
    while (/\s/.test(source[position] || '') && position < source.length) position++
  }
  const take = (text: string) => {
    if (!source.startsWith(text, position)) return false
    position += text.length
    return true
  }
  const binary = (operation: string, left: FormulaNode, right: FormulaNode): FormulaNode => ({
    kind: 'operation',
    operation,
    args: [left, right]
  })
  function comparison(depth: number): FormulaNode {
    let result = sum(depth)
    space()
    const op = ['>=', '<=', '!=', '<>', '==', '=', '>', '<'].find(take)
    if (op) result = binary(op === '==' ? '=' : op === '<>' ? '!=' : op, result, sum(depth))
    return result
  }
  function sum(depth: number): FormulaNode {
    let result = product(depth + 1)
    while (true) {
      space()
      const op = ['||', '+', '-'].find(take)
      if (!op) return result
      result = binary(op, result, product(depth + 1))
    }
  }
  function product(depth: number): FormulaNode {
    let result = atom(depth + 1)
    while (true) {
      space()
      const op = ['*', '/'].find(take)
      if (!op) return result
      result = binary(op, result, atom(depth + 1))
    }
  }
  function atom(depth: number): FormulaNode {
    if (depth > 20 || ++nodes > 100) throw new Error('公式嵌套过深或计算项过多，请拆成多个计算字段')
    space()
    if (take('(')) {
      const node = comparison(depth + 1)
      space()
      if (!take(')')) throw new Error('缺少右括号“)”')
      return node
    }
    if (take('-')) {
      const node = atom(depth + 1)
      return node.kind === 'number' && !node.value.startsWith('-')
        ? { kind: 'number', value: '-' + node.value }
        : binary('-', { kind: 'number', value: '0' }, node)
    }
    if (take("'")) {
      let value = ''
      while (position < source.length) {
        const char = source[position++]!
        if (char === "'") {
          if (take("'")) value += "'"
          else return { kind: 'text', value }
        } else value += char
      }
      throw new Error('文字内容缺少结束引号')
    }
    // 文字也可以用双引号（钉钉 / Excel 的写法）；与服务端相同，两个连写的双引号表示一个双引号。
    if (take('"')) {
      let value = ''
      while (position < source.length) {
        const char = source[position++]!
        if (char === '"') {
          if (take('"')) value += '"'
          else return { kind: 'text', value }
        } else value += char
      }
      throw new Error('文字内容缺少结束引号')
    }
    const numeric = source.slice(position).match(/^[\d.]+/)
    if (numeric) {
      position += numeric[0].length
      if (!/^(?:\d+(?:\.\d*)?|\.\d+)$/.test(numeric[0])) throw new Error('数字格式不正确')
      return { kind: 'number', value: numeric[0] }
    }
    const name = source.slice(position).match(/^[a-zA-Z_][a-zA-Z0-9_]*/)?.[0]
    if (!name) throw new Error(`第 ${position + 1} 个字符附近缺少字段或数值`)
    position += name.length
    space()
    if (!take('(')) {
      if (['true', 'false'].includes(name.toLowerCase())) return { kind: 'boolean', value: name.toLowerCase() }
      if (name.toLowerCase() === 'null') return { kind: 'null', value: '' }
      return { kind: 'field', value: name }
    }
    const functionName = name.toLowerCase()
    const op = formulaOperations.find(item => item.value === functionName && !item.symbol)
    if (!op) throw new Error(`暂不支持函数“${name}”`)
    const dateOperation = dateFormulaOperation(functionName)
    // TODAY() / NOW() 不带参数。
    if (dateOperation?.range[1] === 0) {
      space()
      if (!take(')')) throw new Error(`${name.toUpperCase()}() 不带参数`)
      return { kind: 'operation', operation: functionName, args: [] }
    }
    const args = [comparison(depth + 1)]
    space()
    while (take(',')) {
      args.push(comparison(depth + 1))
      space()
    }
    if (!take(')')) throw new Error('函数参数缺少右括号')
    if (dateOperation) {
      const dateArity = dateFormulaArityError(functionName, args.length)
      if (dateArity) throw new Error(dateArity)
      return { kind: 'operation', operation: functionName, args }
    }
    if (
      args.length > 8 ||
      (['coalesce', 'and', 'or'].includes(functionName)
        ? args.length < 2
        : functionName === 'round'
          ? args.length > 2
          : args.length !== op.count)
    )
      throw new Error(`${op.label}的参数数量不正确`)
    return { kind: 'operation', operation: functionName, args }
  }
  const result = comparison(0)
  space()
  if (position !== source.length) throw new Error(`第 ${position + 1} 个字符附近有不支持的内容`)
  return result
}

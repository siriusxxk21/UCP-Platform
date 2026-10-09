import type { ObjectField } from '@/types/nocode/object'
import type { CalculationOptions } from '@/types/nocode/data-center'
import type { FormulaNode } from './formula-builder'

/**
 * 公式里的日期函数：函数清单与参数提示、钉钉 / Excel 写法的兼容（[字段名]、全角标点）、与服务端 FormulaDates 相同的类型检查。
 * 这里不计算任何结果，试算仍走服务端。
 */
export interface DateFormulaOperation {
  value: string
  label: string
  /** 选择式配置里默认带几个参数。 */
  count: number
  /** 允许的参数个数范围。 */
  range: [number, number]
  /** 函数列表里展示的写法。 */
  signature: string
  /** 点击插入到表达式里的文字。 */
  insert: string
  help: string
  /** 选择式配置里每个参数的名称。 */
  args: string[]
}

export const dateFormulaOperations: DateFormulaOperation[] = [
  {
    value: 'year',
    label: '取年份',
    count: 1,
    range: [1, 1],
    signature: 'YEAR(日期)',
    insert: 'YEAR()',
    help: '日期的年份，例如 2026',
    args: ['日期']
  },
  {
    value: 'month',
    label: '取月份',
    count: 1,
    range: [1, 1],
    signature: 'MONTH(日期)',
    insert: 'MONTH()',
    help: '日期的月份，1 到 12',
    args: ['日期']
  },
  {
    value: 'day',
    label: '取几号',
    count: 1,
    range: [1, 1],
    signature: 'DAY(日期)',
    insert: 'DAY()',
    help: '日期是当月的几号，1 到 31',
    args: ['日期']
  },
  {
    value: 'weekday',
    label: '取星期几',
    count: 1,
    range: [1, 2],
    signature: 'WEEKDAY(日期, [类型])',
    insert: 'WEEKDAY()',
    help: '默认周日=1…周六=7；类型写 2 时周一=1…周日=7；写 3 时周一=0…周日=6',
    args: ['日期', '类型（1、2 或 3）']
  },
  {
    value: 'days',
    label: '两个日期相差天数',
    count: 2,
    range: [2, 2],
    signature: 'DAYS(结束日期, 开始日期)',
    insert: 'DAYS(, )',
    help: '结束日期 − 开始日期；结束早于开始时是负数。也可以直接写 结束日期 - 开始日期',
    args: ['结束日期', '开始日期']
  },
  {
    value: 'datedif',
    label: '两个日期相差的天/月/年',
    count: 3,
    range: [3, 3],
    signature: 'DATEDIF(开始日期, 结束日期, "D")',
    insert: 'DATEDIF(, , "D")',
    help: '第三个参数："D" 天数、"M" 整月数、"Y" 整年数',
    args: ['开始日期', '结束日期', '单位（D、M 或 Y）']
  },
  {
    value: 'eomonth',
    label: '月末日期',
    count: 2,
    range: [1, 2],
    signature: 'EOMONTH(日期, 月数)',
    insert: 'EOMONTH(, 0)',
    help: '往后数几个月的月末那一天；0 是当月月末，-1 是上月月末',
    args: ['日期', '往后几个月（0 = 当月）']
  },
  {
    value: 'edate',
    label: '加减月份',
    count: 2,
    range: [2, 2],
    signature: 'EDATE(日期, 月数)',
    insert: 'EDATE(, 1)',
    help: '往后（负数往前）几个月的同一天；那个月没有这一天时取月末',
    args: ['日期', '加几个月（负数为减）']
  },
  {
    value: 'date',
    label: '用年月日拼日期',
    count: 3,
    range: [3, 3],
    signature: 'DATE(年, 月, 日)',
    insert: 'DATE(, , )',
    help: '例如 DATE(2026, 7, 30)；月、日超出范围时自动顺延',
    args: ['年', '月', '日']
  },
  {
    value: 'today',
    label: '今天',
    count: 0,
    range: [0, 0],
    signature: 'TODAY()',
    insert: 'TODAY()',
    help: '今天的日期。只能用在「读取时计算」的公式和公式默认值里',
    args: []
  },
  {
    value: 'now',
    label: '现在',
    count: 0,
    range: [0, 0],
    signature: 'NOW()',
    insert: 'NOW()',
    help: '现在的日期时间。只能用在「读取时计算」的公式和公式默认值里',
    args: []
  }
]

const operations = new Map(dateFormulaOperations.map(item => [item.value, item]))
const upper = (name: string) => name.toUpperCase()

export function dateFormulaOperation(name: string): DateFormulaOperation | undefined {
  return operations.get(name)
}

/** 选择式配置里新建一个日期函数时的默认参数；不是日期函数返回 null。 */
export function dateFormulaArguments(operation: string, first: FormulaNode): FormulaNode[] | null {
  const op = operations.get(operation)
  if (!op) return null
  if (op.count === 0) return []
  const empty = (): FormulaNode => ({ kind: 'field', value: '' })
  const rest: FormulaNode[] = Array.from({ length: op.count - 1 }, empty)
  if (operation === 'datedif') rest[1] = { kind: 'text', value: 'D' }
  if (operation === 'eomonth') rest[0] = { kind: 'number', value: '0' }
  if (operation === 'edate') rest[0] = { kind: 'number', value: '1' }
  return [first, ...rest]
}

export function dateArgumentLabel(operation: string, index: number): string | null {
  return operations.get(operation)?.args[index] ?? null
}

/** 中文预览；不是日期函数返回 null。 */
export function describeDateFormula(operation: string, args: string[]): string | null {
  const op = operations.get(operation)
  if (!op) return null
  switch (operation) {
    case 'today':
      return '今天'
    case 'now':
      return '现在'
    case 'year':
      return `${args[0]}的年份`
    case 'month':
      return `${args[0]}的月份`
    case 'day':
      return `${args[0]}是几号`
    case 'weekday':
      return `${args[0]}是星期几`
    case 'days':
      return `从${args[1]}到${args[0]}的天数`
    case 'datedif':
      return `从${args[0]}到${args[1]}相差（单位 ${args[2]}）`
    case 'eomonth':
      return `${args[0]}往后 ${args[1] ?? '0'} 个月的月末`
    case 'edate':
      return `${args[0]}加 ${args[1]} 个月`
    default:
      return `${args[0]}年${args[1]}月${args[2]}日`
  }
}

/** 解析器用：参数个数不对时的说明；不是日期函数或个数正确返回 null。 */
export function dateFormulaArityError(operation: string, count: number): string | null {
  const op = operations.get(operation)
  if (!op || (count >= op.range[0] && count <= op.range[1])) return null
  const need = op.range[0] === op.range[1] ? `${op.range[0]}` : `${op.range[0]} 到 ${op.range[1]}`
  return `${upper(operation)} 的参数个数不对：需要 ${need} 个，写了 ${count} 个`
}

/* ── 钉钉 / Excel 写法兼容 ── */

export interface NormalizedFormula {
  text: string
  /** 已换成字段编码的 [字段名]。 */
  replaced: { name: string; code: string }[]
  /** 找不到的字段名。 */
  unknown: string[]
  /** 有多个同名字段，无法确定是哪个。 */
  ambiguous: string[]
  /** 每处改动：原文起止位置与换上的文字，用来把光标挪到对应位置。 */
  edits: { start: number; end: number; text: string }[]
}

const FULL_WIDTH: Record<string, string> = { '（': '(', '）': ')', '，': ',', ' ': ' ' }
const CLOSING_QUOTE: Record<string, string> = { '“': '”', '‘': '’' }

/**
 * 把钉钉 / Excel 的写法换成本系统的写法，含义不变：
 * [字段名]（或 [字段编码]）→ 字段编码；引号外的全角括号、逗号 → 半角；中文引号 → 英文引号。
 * 英文引号里的文字原样保留。找不到或重名的字段名留在原处，由调用方提示。
 */
export function normalizeFormulaSource(source: string, fields: ObjectField[]): NormalizedFormula {
  const result: NormalizedFormula = { text: '', replaced: [], unknown: [], ambiguous: [], edits: [] }
  const names = [...fields].sort((a, b) => b.name.length - a.name.length)
  const put = (start: number, end: number, text: string) => {
    if (source.slice(start, end) !== text) result.edits.push({ start, end, text })
    result.text += text
  }
  let position = 0
  while (position < source.length) {
    const char = source[position]!
    if (char === "'" || char === '"') {
      // 英文引号里的内容原样保留（两个连写的引号是转义）。
      let end = position + 1
      while (end < source.length) {
        if (source[end] === char) {
          if (source[end + 1] === char) end += 2
          else break
        } else end++
      }
      end = Math.min(end + 1, source.length)
      result.text += source.slice(position, end)
      position = end
    } else if (CLOSING_QUOTE[char]) {
      const close = source.indexOf(CLOSING_QUOTE[char]!, position + 1)
      const quote = char === '“' ? '"' : "'"
      if (close < 0 || source.slice(position + 1, close).includes(quote)) {
        result.text += char
        position++
      } else {
        put(position, close + 1, quote + source.slice(position + 1, close) + quote)
        position = close + 1
      }
    } else if (char === '[') {
      const byCode = fields.find(field => field.code && source.startsWith(field.code + ']', position + 1))
      const matched = names.find(field => field.name && source.startsWith(field.name + ']', position + 1))
      const close = source.indexOf(']', position + 1)
      if (byCode) {
        put(position, position + byCode.code.length + 2, byCode.code)
        position += byCode.code.length + 2
      } else if (matched) {
        const same = fields.filter(field => field.name === matched.name)
        const end = position + matched.name.length + 2
        if (same.length > 1) {
          result.ambiguous.push(matched.name)
          result.text += source.slice(position, end)
        } else {
          result.replaced.push({ name: matched.name, code: matched.code })
          put(position, end, matched.code)
        }
        position = end
      } else {
        if (close > position) result.unknown.push(source.slice(position + 1, close).trim())
        const end = close > position ? close + 1 : position + 1
        result.text += source.slice(position, end)
        position = end
      }
    } else if (FULL_WIDTH[char]) {
      put(position, position + 1, FULL_WIDTH[char]!)
      position++
    } else {
      result.text += char
      position++
    }
  }
  return result
}

/** 改写之后光标应该落在哪里：光标之前的改动整体平移，光标落在被改动的片段里时放到片段末尾。 */
export function caretAfterNormalize(caret: number, edits: NormalizedFormula['edits']): number {
  let shift = 0
  for (const edit of edits) {
    if (edit.end <= caret) shift += edit.text.length - (edit.end - edit.start)
    else if (edit.start < caret) return edit.start + shift + edit.text.length
  }
  return caret + shift
}

export function normalizeFormulaMessage(result: NormalizedFormula): string {
  if (!result.replaced.length) return ''
  const seen = new Set<string>()
  const pairs = result.replaced.filter(item => !seen.has(item.name) && seen.add(item.name))
  return `已把 ${pairs.map(item => `[${item.name}]`).join('、')} 换成字段编码 ${pairs.map(item => item.code).join('、')}，含义不变。`
}

export function normalizeFormulaError(result: NormalizedFormula): string {
  if (result.ambiguous.length)
    return `有多个字段都叫「${result.ambiguous[0]}」，无法确定是哪一个；请点下面的字段按钮插入要用的那个`
  if (result.unknown.length) return `找不到名为「${result.unknown[0]}」的字段；请核对字段名，或点下面的字段按钮插入`
  return ''
}

/* ── 类型检查（与服务端 FormulaDates 相同的规则与文案） ── */

type Kind = 'NUMBER' | 'TEXT' | 'BOOLEAN' | 'DATE' | 'DATETIME' | 'OTHER' | 'NULL' | 'UNKNOWN'
interface Typed {
  kind: Kind
  /** 日期函数或日期加减算出来的日期（不是字段本身）。 */
  derived: boolean
  /** 写成日期样子的文字常量，例如 '2026-07-30'。 */
  literal: boolean
}

export interface DateFormulaContext {
  /** 能不能用 TODAY() / NOW()：落库的值会过期，只有读取时计算与公式默认值可以。 */
  volatileAllowed: boolean
  /** 本行公式（数据库生成列）。 */
  generated: boolean
  /** 公式默认值的目标字段类型；公式字段不传。 */
  defaultTarget?: string | null
}

/** 公式字段按计算方式决定能不能用 TODAY() / NOW()：没有计算配置的是本行公式（落库），其余只有读取时计算可以。 */
export function dateContextOfCalculation(calculation?: CalculationOptions | null): DateFormulaContext {
  return { volatileAllowed: calculation?.updateMode === 'LIVE', generated: !calculation }
}

const NUMERIC = ['INTEGER', 'DECIMAL', 'MONEY', 'PERCENT']
const TEXTUAL = ['TEXT', 'TEXTAREA', 'RICH_TEXT', 'SELECT', 'AUTO_NUMBER', 'UUID', 'TIME']
function kindOfType(type: string | undefined): Kind {
  if (!type) return 'UNKNOWN'
  if (type === 'DATE' || type === 'DATETIME' || type === 'BOOLEAN') return type
  if (NUMERIC.includes(type)) return 'NUMBER'
  if (TEXTUAL.includes(type)) return 'TEXT'
  return ['FORMULA', 'SUMMARY'].includes(type) ? 'UNKNOWN' : 'OTHER'
}
const of = (kind: Kind): Typed => ({ kind, derived: false, literal: false })
const isDate = (typed: Typed) => typed.kind === 'DATE' || typed.kind === 'DATETIME'
const dateLike = (typed: Typed) => isDate(typed) || typed.literal
const known = (typed: Typed) => typed.kind !== 'UNKNOWN' && typed.kind !== 'NULL'
const NOUN: Record<Kind, string> = {
  NUMBER: '数字',
  TEXT: '文字',
  BOOLEAN: '是/否',
  DATE: '日期',
  DATETIME: '日期时间',
  OTHER: '既不是日期也不是数字的字段',
  NULL: '空值',
  UNKNOWN: '空值'
}

function dateLiteral(value: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})(?:[T ]\d{2}:\d{2}.*)?$/.exec(value)
  if (!match) return false
  const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])]
  const date = new Date(Date.UTC(year, month - 1, day))
  date.setUTCFullYear(year)
  return date.getUTCMonth() === month - 1 && date.getUTCDate() === day
}

class DateFormulaError extends Error {}

/**
 * 与日期有关的配置错误（参数不是日期、结果是日期、落库的公式用了 TODAY() 等），文案点名出错的函数；没有问题返回 null。
 * 只报与日期有关的错，其余仍由 formulaNodeError 与服务端负责。
 */
export function formulaDateError(node: FormulaNode, fields: ObjectField[], context: DateFormulaContext): string | null {
  const byCode = new Map(fields.map(field => [field.code, field]))
  const label = (item: FormulaNode): string => {
    if (item.kind === 'field') return `「${byCode.get(item.value)?.name ?? item.value}」`
    if (item.kind === 'number') return `数字 ${item.value}`
    if (item.kind === 'text') return `文字「${item.value}」`
    if (item.kind === 'operation' && operations.has(item.operation)) return `${upper(item.operation)} 的结果`
    return '这一项'
  }
  const fail = (message: string): never => {
    throw new DateFormulaError(message)
  }
  const dateArgument = (call: { operation: string; args: FormulaNode[] }, index: number) => {
    const item = call.args[index]!
    const typed = type(item)
    if (dateLike(typed) || !known(typed)) return
    fail(
      `${upper(call.operation)} 的第 ${index + 1} 个参数要填日期或日期时间字段（或其它日期函数的结果），${label(item)}是${NOUN[typed.kind]}` +
        (typed.kind === 'TEXT' && item.kind === 'text' ? "，日期请写成 '2026-07-30' 这样" : '')
    )
  }
  const numberArgument = (call: { operation: string; args: FormulaNode[] }, index: number) => {
    const item = call.args[index]!
    const typed = type(item)
    if (!known(typed) || typed.kind === 'NUMBER') return
    fail(`${upper(call.operation)} 的第 ${index + 1} 个参数要填数字，${label(item)}是${NOUN[typed.kind]}`)
  }
  const unify = (name: string, a: Typed, b: Typed): Typed => {
    if (!known(a)) return known(b) || a.kind === 'NULL' ? b : a
    if (!known(b)) return a
    if (isDate(a) && isDate(b))
      return {
        kind: a.kind === 'DATE' && b.kind === 'DATE' ? 'DATE' : 'DATETIME',
        derived: a.derived || b.derived,
        literal: false
      }
    // 只管新写法：算出来的日期与数字混在一起才报；字段本身是日期的存量写法照旧。
    if ((isDate(a) && a.derived && b.kind === 'NUMBER') || (isDate(b) && b.derived && a.kind === 'NUMBER'))
      fail(`${name} 的几个结果里有的是日期、有的是数字，要统一成一种`)
    return a.kind === b.kind ? { kind: a.kind, derived: false, literal: a.literal && b.literal } : of('UNKNOWN')
  }
  function type(item: FormulaNode): Typed {
    if (item.kind === 'number') return of('NUMBER')
    if (item.kind === 'boolean') return of('BOOLEAN')
    if (item.kind === 'null') return of('NULL')
    if (item.kind === 'text') return { kind: 'TEXT', derived: false, literal: dateLiteral(item.value) }
    if (item.kind === 'field') return of(kindOfType(byCode.get(item.value)?.type))
    const name = item.operation
    const args = item.args
    const derivedDate: Typed = { kind: 'DATE', derived: true, literal: false }
    if (name === '||') {
      for (const side of args.map(type))
        if (context.generated && isDate(side) && side.derived)
          fail('本行公式里算出来的日期不能直接拼接文字；请用 YEAR / MONTH / DAY 取出数字后再拼')
      return of('TEXT')
    }
    if (['=', '!=', '>', '>=', '<', '<='].includes(name)) {
      const [left, right] = [type(args[0]!), type(args[1]!)]
      if ((isDate(left) && right.kind === 'NUMBER') || (isDate(right) && left.kind === 'NUMBER')) {
        const leftDate = isDate(left)
        fail(
          `${label(leftDate ? args[0]! : args[1]!)}是日期，不能直接和数字比较（另一边是${label(leftDate ? args[1]! : args[0]!)}）；请先用 YEAR / MONTH / DAY 取出数字，或者和另一个日期比`
        )
      }
      return of('BOOLEAN')
    }
    if (['+', '-', '*', '/'].includes(name)) {
      const [left, right] = [type(args[0]!), type(args[1]!)]
      const dates = isDate(left) || isDate(right)
      if (name === '*' || name === '/') {
        if (dates)
          fail(
            `${label(isDate(left) ? args[0]! : args[1]!)}是日期，不能做乘除；请先用 YEAR / MONTH / DAY 取出数字，或用两个日期相减得到天数`
          )
        return of('NUMBER')
      }
      // 两边都不是日期：都知道类型时是数字；有一边没有类型信息时不下结论（它可能是日期）。
      if (!dates) return of(known(left) && known(right) ? 'NUMBER' : 'UNKNOWN')
      if (dateLike(left) && dateLike(right)) {
        if (name === '+') fail('两个日期不能相加；要算相差天数请用减号或 DAYS')
        return of('NUMBER')
      }
      const other = isDate(left) ? right : left
      const otherNode = isDate(left) ? args[1]! : args[0]!
      if (known(other) && other.kind !== 'NUMBER')
        fail(`日期只能加减天数（数字）或减另一个日期，${label(otherNode)}是${NOUN[other.kind]}`)
      // 另一边没有类型信息：它是日期就得天数、是数字就得日期，这里不下结论。
      if (!known(other)) return of('UNKNOWN')
      if (isDate(right) && name === '-') fail('数字不能减日期；要算相差天数请用「日期 − 日期」或 DAYS')
      return derivedDate
    }
    switch (name) {
      case 'today':
      case 'now':
        if (!context.volatileAllowed)
          fail(
            `${upper(name)}() 每天的结果都不一样，而这个字段的值是保存下来的，存进去之后不会自己更新。请把计算方式改成「包含计算结果」并选择读取时计算，或者改用公式默认值`
          )
        return { kind: name === 'today' ? 'DATE' : 'DATETIME', derived: true, literal: false }
      case 'year':
      case 'month':
      case 'day':
        dateArgument(item, 0)
        return of('NUMBER')
      case 'weekday': {
        dateArgument(item, 0)
        const mode = args[1]
        if (mode && !(mode.kind === 'number' && ['1', '2', '3'].includes(String(Number(mode.value)))))
          fail('WEEKDAY 的第二个参数请直接写 1、2 或 3（1：周日=1…周六=7；2：周一=1…周日=7；3：周一=0…周日=6）')
        return of('NUMBER')
      }
      case 'days':
        dateArgument(item, 0)
        dateArgument(item, 1)
        return of('NUMBER')
      case 'datedif': {
        dateArgument(item, 0)
        dateArgument(item, 1)
        const unit = args[2]
        if (!unit || unit.kind !== 'text' || !['D', 'M', 'Y'].includes(unit.value.trim().toUpperCase()))
          fail('DATEDIF 的第三个参数请直接写 "D"（天）、"M"（整月）或 "Y"（整年）')
        return of('NUMBER')
      }
      case 'eomonth':
      case 'edate':
        dateArgument(item, 0)
        if (args.length === 2) numberArgument(item, 1)
        return derivedDate
      case 'date':
        for (let index = 0; index < 3; index++) numberArgument(item, index)
        return derivedDate
      case 'abs':
      case 'round': {
        const first = type(args[0]!)
        if (isDate(first))
          fail(
            `${upper(name)} 只能用在数字上，${label(args[0]!)}是日期；请先用 YEAR / MONTH / DAY 取出数字，或用两个日期相减得到天数`
          )
        args.slice(1).forEach(type)
        return of('NUMBER')
      }
      case 'if':
      case 'coalesce': {
        if (name === 'if') type(args[0]!)
        return (name === 'if' ? args.slice(1) : args).reduce<Typed>(
          (result, branch) => unify(upper(name), result, type(branch)),
          of('NULL')
        )
      }
      case 'and':
      case 'or':
      case 'not':
      case 'isblank':
        args.forEach(type)
        return of('BOOLEAN')
      default:
        args.forEach(type)
        return of('TEXT')
    }
  }
  try {
    const root = type(node)
    if (isDate(root) && root.derived) {
      if (context.defaultTarget === undefined || context.defaultTarget === null)
        return '这条公式最后算出来的是一个日期，而公式字段的结果只能是文本、整数、小数或金额。日期只能当中间值用：再套一层 YEAR / MONTH / DAY 取出数字，或者两个日期相减得天数，或者拿来比较'
      if (NUMERIC.includes(context.defaultTarget))
        return '公式算出来的是一个日期，不能填进数值字段；要天数请用两个日期相减或 DAYS'
    }
    return null
  } catch (error) {
    if (error instanceof DateFormulaError) return error.message
    throw error
  }
}

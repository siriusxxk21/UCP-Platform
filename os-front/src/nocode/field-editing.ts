import * as NC from '@/types/nocode/enums'
import type { FieldType, ObjectField } from '@/types/nocode/object'
import type { FieldOptions, SaveDesign } from '@/types/nocode/data-center'
import { baseFieldNames, setFieldType } from './object-draft'
import { defaultFieldOptions } from './data-center'
import type { SelectionSource } from '@/types/nocode/selection'
import { fieldCode } from './resource-code'
import { selectionDirectories, selectionSource } from './selection'
import { SelectionKind } from '@/types/nocode/selection'
import { decodeFieldDefault, encodeFieldDefault, fieldDefaultError } from './field-defaults'
import { autoNumberError, defaultAutoNumber } from './auto-number'
import { compareRecordNumbers, recordNumberError } from './record-number'

/** 新字段的空编码或自动建议随名称更新；保存后的身份、手填编码及受保护映射保持稳定。 */
export function fieldNamePatch(
  field: ObjectField,
  name: string,
  options?: FieldOptions,
  adopted = false
): Partial<ObjectField> {
  const automatic =
    !field.id && !fieldStructureLocked(field, options, adopted) && (!field.code || field.code === fieldCode(field.name))
  return automatic ? { name, code: fieldCode(name) } : { name }
}

/** 来源身份改变时独立清理旧默认值与映射，即使底层类型没有变化。 */
export function resetSelectionSource(options: FieldOptions, source: SelectionSource): void {
  options.defaultValue = null
  options.selection = {
    ...source,
    rootIds: [],
    includeDescendants: false,
    organizationTypes: [],
    defaultMode: 'NONE',
    migrationMap: null
  }
}

/** 数量改变不改变来源范围；兼容的默认值转换形状，多值不能静默取第一项。 */
export function changeSelectionCount(field: ObjectField, options: FieldOptions, multiple: boolean): boolean {
  if (multiple === (field.type === NC.FieldType.MULTI_SELECT)) return false
  const source = JSON.parse(JSON.stringify(selectionSource(field, options))) as SelectionSource
  const local = options.options
  const previous = decodeFieldDefault(field, options.defaultValue)
  const incompatible = !multiple && Array.isArray(previous) && previous.length > 1
  const value = multiple
    ? previous == null
      ? null
      : [previous]
    : Array.isArray(previous)
      ? previous.length === 1
        ? previous[0]
        : null
      : previous
  changeFieldType(
    field,
    options,
    multiple
      ? NC.FieldType.MULTI_SELECT
      : source.kind === SelectionKind.DIRECTORY
        ? (source.directory as FieldType)
        : NC.FieldType.SELECT
  )
  options.selection = source
  options.options = local
  options.defaultValue = encodeFieldDefault(field, value)
  if (incompatible && source.defaultMode === 'FIXED') source.defaultMode = 'NONE'
  return incompatible
}

export function fieldStructureLocked(field: ObjectField, options?: FieldOptions, adopted = false): boolean {
  return (
    adopted ||
    !!options?.generated ||
    !!options?.primaryKey ||
    (!!field.id && !!options?.columnName && baseFieldNames.has(options.columnName))
  )
}

/** 行编辑与配置弹窗使用同一类型切换规则，保留说明与分级，清除旧类型专属配置。 */
export function changeFieldType(field: ObjectField, options: FieldOptions, type: FieldType): void {
  if (field.type === type) return
  setFieldType(field, type)
  Object.assign(options, {
    defaultValue: null,
    pattern: null,
    minimum: null,
    maximum: null,
    expression: null,
    calculation: null,
    autoNumber: type === NC.FieldType.AUTO_NUMBER ? defaultAutoNumber() : null,
    resultType:
      type === NC.FieldType.SUMMARY
        ? NC.FieldType.INTEGER
        : type === NC.FieldType.FORMULA
          ? NC.FieldType.DECIMAL
          : null,
    options: [],
    selection: null,
    resolver: NC.DisplayResolver.NONE
  })
  if (type === NC.FieldType.SUMMARY) {
    field.required = false
    field.unique = false
  }
}

/** 多行文本不再提供正则配置；清理旧草稿中不可见的规则，避免保存后继续生效。 */
export function clearMultilinePatterns(fields: ObjectField[], options: Record<string, FieldOptions>): void {
  for (const field of fields)
    if (field.type === NC.FieldType.TEXTAREA && options[field.key]) options[field.key].pattern = null
}

export function fieldBasicsError(field: ObjectField, fields: ObjectField[], adopted = false): string | null {
  if (!field.name.trim()) return '请填写字段名称'
  if (!/^[a-z][a-z0-9_]{0,62}$/.test(field.code))
    return '编码须以小写字母开头，仅含小写字母、数字、下划线，最多 63 字符'
  if (!adopted && baseFieldNames.has(field.code)) return '该编码由系统字段保留'
  if (fields.some(f => f.key !== field.key && f.code === field.code)) return '字段编码重复'
  return null
}

/** 输入时检查常见正则语法错误；对象保存仍由服务端校验最终规则。 */
export function fieldPatternError(field: ObjectField, options?: FieldOptions): string | null {
  if (field.type === NC.FieldType.TEXTAREA) return null
  const pattern = options?.pattern
  if (!pattern?.trim()) return null
  if (field.type !== NC.FieldType.TEXT) return '正则校验仅支持单行文本'
  if (pattern.length > 200) return '正则表达式最多 200 个字符'
  try {
    // 浏览器不识别 Java 的内联标志，转换为等价的分组结构后继续检查其余语法。
    const syntaxPattern = pattern.replace(/\(\?[idmsuxU-]+\)/g, '').replace(/\(\?[idmsuxU-]+:/g, '(?:')
    RegExp(syntaxPattern)
  } catch {
    return '正则表达式无效，请检查括号、方括号或转义符'
  }
  return null
}

interface NumericConstraintIssue {
  input: 'defaultValue' | 'minimum' | 'maximum'
  message: string
}

/** 字段编辑与整张对象保存共用无损十进制校验，避免大金额经 Number 舍入后误判。 */
export function fieldNumericConstraintError(field: ObjectField, options?: FieldOptions): NumericConstraintIssue | null {
  if (
    !options ||
    !(
      [NC.FieldType.INTEGER, NC.FieldType.DECIMAL, NC.FieldType.MONEY, NC.FieldType.PERCENT] as readonly string[]
    ).includes(field.type)
  )
    return null
  const minimum = options.minimum?.trim(),
    maximum = options.maximum?.trim(),
    defaultValue = options.defaultValue?.trim()
  if (minimum && compareRecordNumbers(minimum, '0') === null) return { input: 'minimum', message: '最小值须为有效数字' }
  if (maximum && compareRecordNumbers(maximum, '0') === null) return { input: 'maximum', message: '最大值须为有效数字' }
  if (minimum && maximum && compareRecordNumbers(minimum, maximum) === 1)
    return { input: 'maximum', message: '最大值不能小于最小值' }
  if (!defaultValue) return null
  if (minimum && compareRecordNumbers(defaultValue, minimum) === -1)
    return { input: 'defaultValue', message: `默认值不能小于最小值 ${minimum}` }
  if (maximum && compareRecordNumbers(defaultValue, maximum) === 1)
    return { input: 'defaultValue', message: `默认值不能大于最大值 ${maximum}` }
  const numberError = recordNumberError(field, { minimum: null, maximum: null }, defaultValue)
  return numberError ? { input: 'defaultValue', message: `默认值：${numberError}` } : null
}

export function fieldConfigurationError(field: ObjectField, options?: FieldOptions): string | null {
  if (field.type === NC.FieldType.AUTO_NUMBER) {
    const error = autoNumberError(options?.autoNumber)
    if (error) return error
  }
  const patternError = fieldPatternError(field, options)
  if (patternError) return patternError
  const defaultError = options && fieldDefaultError(field, options)
  if (defaultError) return defaultError
  const numericError = fieldNumericConstraintError(field, options)
  if (numericError) return numericError.message
  const source = options?.selection
  if (source?.kind === SelectionKind.SYSTEM_DICTIONARY && !source.dictionaryType?.trim())
    return '请在配置中选择平台公共字典'
  if (source?.kind === SelectionKind.DIRECTORY && !selectionDirectories.some(type => type === source.directory))
    return '请在配置中选择系统目录'
  if (source?.kind === SelectionKind.OBJECT_RELATION) return '业务对象来源请通过对象关系配置'
  if (
    (
      [NC.FieldType.SELECT, NC.FieldType.MULTI_SELECT, NC.FieldType.REGION, NC.FieldType.CASCADE] as readonly string[]
    ).includes(field.type) &&
    (!options?.selection || options.selection.kind === 'LOCAL_OPTIONS')
  ) {
    if (!options?.options?.length) return '请添加选项（至少一项），并填写编码和名称'
    const codes = new Set<string>()
    for (const option of options.options) {
      if (!option.code.trim() || !option.label.trim()) return '请完善选项编码和名称'
      if (codes.has(option.code)) return '选项编码重复'
      codes.add(option.code)
    }
  }
  if (
    ([NC.FieldType.FORMULA, NC.FieldType.SUMMARY] as readonly string[]).includes(field.type) &&
    (!options?.calculation || options.calculation.mode === 'LOCAL' || options.calculation.mode === 'SEQUENCE') &&
    !options?.expression?.trim()
  )
    return '请在配置中完成计算规则'
  if (
    field.type === NC.FieldType.TEXT &&
    (!Number.isInteger(field.length) || field.length! < 1 || field.length! > 4000)
  )
    return '文本长度须为 1–4000 的整数'
  if (([NC.FieldType.DECIMAL, NC.FieldType.MONEY, NC.FieldType.PERCENT] as readonly string[]).includes(field.type)) {
    if (!Number.isInteger(field.precision) || field.precision! < 1 || field.precision! > 38)
      return '总位数须为 1–38 的整数'
    if (!Number.isInteger(field.scale) || field.scale! < 0 || field.scale! > field.precision!)
      return '小数位数须为 0 至总位数的整数'
  }
  return null
}

/** 行内输入即时进入对象草稿；统一保存时同时校验主表和明细，不能绕过弹窗校验。 */
export function designFieldsError(design: SaveDesign): { tab: string; message: string } | null {
  const tables = [
    {
      name: '主表',
      tab: 'fields',
      fields: design.draft.fields,
      options: design.fieldOptions,
      binding: design.mainBinding
    },
    ...design.details
      .filter(d => d.state === NC.MemberState.ACTIVE)
      .map(d => ({ name: d.name, tab: 'details', fields: d.fields, options: d.fieldOptions, binding: d.binding }))
  ]
  for (const table of tables) {
    const adopted = table.binding?.source === NC.ObjectSource.ADOPTED
    for (const field of table.fields) {
      const error =
        fieldBasicsError(field, table.fields, adopted) ||
        (!adopted && fieldConfigurationError(field, table.options[field.key]))
      if (error)
        return { tab: table.tab, message: `${table.name} · ${field.name || field.code || '未命名字段'}：${error}` }
    }
  }
  return null
}

export function copyFieldOptions(options?: FieldOptions): FieldOptions {
  return { ...defaultFieldOptions(), ...JSON.parse(JSON.stringify(options ?? {})) }
}

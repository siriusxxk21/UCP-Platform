import type { CalculationOptions, FieldOptions } from '@/types/nocode/data-center'
import type { ObjectField } from '@/types/nocode/object'
import { FieldType } from '@/types/nocode/enums'

export function orderedCalculation(value?: CalculationOptions | null): boolean {
  return !!value && ['RUNNING_TOTAL', 'SEQUENCE'].includes(value.mode)
}

export function storedOrderedCalculation(options?: FieldOptions): boolean {
  return orderedCalculation(options?.calculation) && options?.calculation?.updateMode === 'ON_SAVE'
}

/** 查询控件沿用字段身份，把既有结果类型投影为数值或文本。 */
export function calculationValueField<T extends ObjectField>(field: T, options?: FieldOptions): T {
  const result = options?.resultType
  return field.type === FieldType.FORMULA &&
    [FieldType.TEXT, FieldType.INTEGER, FieldType.DECIMAL, FieldType.MONEY].some(type => result === type)
    ? { ...field, type: result as FieldType }
    : field
}

/** 缺失就绪状态时不把新有序物理列当成可查询的完整结果。 */
export function calculationQueryReady(options?: FieldOptions, readiness?: string): boolean {
  if (options?.calculation?.updateMode === 'LIVE') return false
  return !storedOrderedCalculation(options) || readiness === 'READY'
}

/** 相同 ON_SAVE 编码依照计算类型说明含义，避免把普通快照误说成组内联动。 */
export function calculationUpdateDescription(value?: CalculationOptions | null): string {
  if (!value) return '保存时自动计算'
  if (value.updateMode === 'LIVE') return '读取时计算'
  return orderedCalculation(value) ? '保存/删除后，同组联动更新' : '本记录保存时重算'
}

/** 数值统计和累计不接受文本结果；查找、相邻取值等仍保留文本能力。 */
export function calculationResultTypeOptions(value?: CalculationOptions | null) {
  const numericOnly =
    value?.mode === 'STATISTICS' ||
    value?.mode === 'RUNNING_TOTAL' ||
    (value?.mode === 'SEQUENCE' && value.sequence?.operation === 'CUMULATIVE')
  return [
    ...(!numericOnly ? [{ value: 'TEXT', label: '文本' }] : []),
    { value: 'INTEGER', label: '整数' },
    { value: 'DECIMAL', label: '小数' },
    { value: 'MONEY', label: '金额' }
  ]
}

/** 应用配置时复用候选范围校验，不能仅靠下拉选项拦截存量非法值。 */
export function calculationResultTypeError(resultType: string, value?: CalculationOptions | null): string {
  return calculationResultTypeOptions(value).some(option => option.value === resultType)
    ? ''
    : '请选择当前计算方式支持的结果类型'
}

/** 四个入口只投影已有协议；读取旧配置时不写入默认值或迁移模式。 */
export type CalculationCategory = 'FORMULA' | 'LOOKUP' | 'AGGREGATE' | 'SEQUENCE'
export const calculationCategories: { value: CalculationCategory; label: string }[] = [
  { value: 'FORMULA', label: '公式运算' },
  { value: 'LOOKUP', label: '查找取值' },
  { value: 'AGGREGATE', label: '汇总统计' },
  { value: 'SEQUENCE', label: '顺序计算' }
]
export function calculationCategory(value?: CalculationOptions | null): CalculationCategory {
  if (!value || value.mode === 'LOCAL') return 'FORMULA'
  if (value.mode === 'SEQUENCE' || value.mode === 'RUNNING_TOTAL') return 'SEQUENCE'
  if (value.mode === 'STATISTICS' || value.aggregate !== 'SINGLE') return 'AGGREGATE'
  return 'LOOKUP'
}
export function calculationDescription(value?: CalculationOptions | null): string {
  const category = calculationCategories.find(item => item.value === calculationCategory(value))?.label || ''
  if (!value) return `${category} · 仅基础字段`
  const detail =
    value.mode === 'LOCAL'
      ? '包含计算结果'
      : value.mode === 'RELATION'
        ? '关联记录'
        : value.mode === 'LOOKUP'
          ? '条件查询'
          : value.mode === 'STATISTICS'
            ? '本对象记录'
            : value.mode === 'RUNNING_TOTAL'
              ? '增减值累计'
              : value.sequence?.operation === 'CUMULATIVE'
                ? '逐笔计算后累计'
                : '相邻记录取值'
  return `${category} · ${detail}`
}

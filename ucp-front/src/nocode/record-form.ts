import type { Rule } from '@form-create/ant-design-vue'
import type { ObjectField } from '@/types/nocode/object'
import type { FieldOptions } from '@/types/nocode/data-center'
import type { TableModel } from '@/types/nocode/runtime'
import { FieldType, MemberState } from '@/types/nocode/enums'
import { auditFieldNames } from './object-draft'
import { selectionSource } from './selection'
const generatedTypes = new Set<FieldType>([FieldType.FORMULA, FieldType.SUMMARY, FieldType.AUTO_NUMBER])

export const arrayFieldTypes = new Set<FieldType>([
  FieldType.MULTI_SELECT,
  FieldType.IMAGE,
  FieldType.ATTACHMENT,
  FieldType.REGION,
  FieldType.CASCADE
])

export function systemManagedField(field: ObjectField, options?: FieldOptions): boolean {
  return auditFieldNames.has(options?.columnName || field.code)
}

/** 保留带时区时间的偏移量，避免向 OffsetDateTime 接口提交无时区文本。 */
export function dateTimeValueFormat(options?: FieldOptions): string {
  return /with time zone|timestamptz/i.test(options?.nativeType || '') ? 'YYYY-MM-DDTHH:mm:ssZ' : 'YYYY-MM-DDTHH:mm:ss'
}

/** 筛选与写入共用值类型；清空筛选不把 false、0 当作空值。 */
export function recordFilterValue(field: ObjectField, value: unknown): unknown {
  if (value == null || value === '' || (Array.isArray(value) && !value.length)) return undefined
  if (field.type === FieldType.BOOLEAN) {
    if (value === true || value === 'true') return true
    if (value === false || value === 'false') return false
    throw new Error('请选择是或否')
  }
  if (arrayFieldTypes.has(field.type) && !Array.isArray(value)) throw new Error('请选择筛选值')
  return value
}

/** 表单引擎负责交互；字段身份、精度和写入边界使用数据中心的固定版本。 */
export function writableField(
  field: ObjectField,
  options: FieldOptions | undefined,
  model: TableModel,
  creating: boolean
) {
  if (!model.writable || options?.state === MemberState.INACTIVE) return false
  if (systemManagedField(field, options)) return false
  if (model.managedFieldIds?.includes(field.id!)) return false
  if (model.writeFields && !model.writeFields.includes(field.id!)) return false
  if (generatedTypes.has(field.type) || options?.generated) return false
  if (field.id === model.keyFieldId && (!creating || model.generatedKey)) return false
  return true
}
export function recordRules(
  fields: ObjectField[],
  options: Record<string, FieldOptions>,
  model: TableModel,
  creating: boolean
): Rule[] {
  return fields
    .filter(
      f => options[f.id!]?.state !== MemberState.INACTIVE && (!creating || !systemManagedField(f, options[f.id!]))
    )
    .map(f => {
      const o = options[f.id!],
        disabled = !writableField(f, o, model, creating)
      const rule: Rule = {
        field: f.id!,
        title: f.name,
        type: 'input',
        props: {
          disabled,
          placeholder: model.managedFieldIds?.includes(f.id!)
            ? '由业务规则维护'
            : systemManagedField(f, o)
              ? '由系统维护'
              : disabled
                ? '只读'
                : '请输入' + f.name
        },
        col: { span: 12 }
      }
      if (f.required && !disabled)
        rule.validate = [
          {
            required: true,
            type: f.type === FieldType.BOOLEAN ? 'boolean' : arrayFieldTypes.has(f.type) ? 'array' : 'string',
            message: '请填写' + f.name
          }
        ]
      switch (f.type) {
        case FieldType.TEXT:
          rule.props = { ...rule.props, maxlength: f.length || 4000 }
          break
        case FieldType.TEXTAREA:
        case FieldType.RICH_TEXT:
          rule.type = 'textarea'
          rule.col = { span: 24 }
          break
        // 输入框保留字符串，避免大整数、金额经过 Number 转换丢失精度。
        case FieldType.INTEGER:
        case FieldType.DECIMAL:
        case FieldType.MONEY:
        case FieldType.PERCENT:
          rule.props = { ...rule.props, inputmode: 'decimal' }
          break
        case FieldType.BOOLEAN:
          rule.type = 'switch'
          delete rule.props!.placeholder
          break
        case FieldType.SELECT:
        case FieldType.MULTI_SELECT:
        case FieldType.REGION:
        case FieldType.CASCADE:
          rule.type = 'select'
          rule.options = (o?.options || []).map(x => ({ value: x.code, label: x.label, disabled: x.disabled }))
          rule.props = {
            ...rule.props,
            allowClear: !f.required,
            showSearch: true,
            optionFilterProp: 'label',
            mode: arrayFieldTypes.has(f.type) ? 'multiple' : undefined
          }
          break
        case FieldType.DATE:
          rule.type = 'datePicker'
          rule.props = { ...rule.props, valueFormat: 'YYYY-MM-DD', style: { width: '100%' } }
          break
        case FieldType.DATETIME:
          rule.type = 'datePicker'
          rule.props = { ...rule.props, showTime: true, valueFormat: dateTimeValueFormat(o), style: { width: '100%' } }
          break
        case FieldType.TIME:
          rule.type = 'timePicker'
          rule.props = { ...rule.props, valueFormat: 'HH:mm:ss', style: { width: '100%' } }
          break
      }
      return rule
    })
}
/** 默认值只用于新建，保留数值字符串精度；编辑时不覆盖既有空值。 */
export function recordDefaults(
  fields: ObjectField[],
  options: Record<string, FieldOptions>,
  model: TableModel,
  localSelections = true
) {
  const result: Record<string, unknown> = {}
  for (const field of fields) {
    if (!localSelections && selectionSource(field, options[field.id!])) continue
    const raw = options[field.id!]?.defaultValue
    if (!writableField(field, options[field.id!], model, true)) continue
    // 必填开关初始显示“否”，必须同时初始化布尔值；可选字段仍保留数据库默认值的机会。
    if (raw == null) {
      if (field.type === FieldType.BOOLEAN && field.required) result[field.id!] = false
      continue
    }
    if (field.type === FieldType.BOOLEAN) result[field.id!] = raw === 'true'
    else if (
      [FieldType.MULTI_SELECT, FieldType.IMAGE, FieldType.ATTACHMENT, FieldType.REGION, FieldType.CASCADE].some(
        t => t === field.type
      )
    ) {
      try {
        const value = JSON.parse(raw)
        if (Array.isArray(value)) result[field.id!] = value
      } catch {
        /* 非法旧配置交由服务器校验。 */
      }
    } else if (field.type === FieldType.URL) {
      try {
        result[field.id!] = JSON.parse(raw)
      } catch {
        result[field.id!] = raw
      }
    } else result[field.id!] = raw
  }
  return result
}
export function recordPayload(
  fields: ObjectField[],
  options: Record<string, FieldOptions>,
  model: TableModel,
  creating: boolean,
  values: Record<string, unknown>
) {
  const result: Record<string, unknown> = {}
  for (const field of fields)
    if (field.id && writableField(field, options[field.id], model, creating)) {
      const value = values[field.id]
      // 新增记录未填写的可选字段让数据库默认值生效；修改时允许显式清空。
      const selection = !!selectionSource(field, options[field.id]) || field.type === FieldType.REFERENCE
      if (creating && (value === undefined || (!selection && (value === null || value === '')))) continue
      result[field.id] = value === undefined || (selection && value === '') ? null : value
    }
  return result
}

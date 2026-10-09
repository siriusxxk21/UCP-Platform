import * as NC from '@/types/nocode/enums'
import type { DatabaseColumn, FieldOptions, TableBinding } from '@/types/nocode/data-center'
import type { ObjectField } from '@/types/nocode/object'
import { auditFieldNames } from './object-draft'

export interface SystemField {
  code: string
  name: string
  type: string
  rule: string
  purpose: string
}

/** 对应底座 BaseDOColumns 和 createBusinessTable，只读展示，不写入业务字段数组。 */
const standardFields: SystemField[] = [
  { code: 'id', name: '记录 ID', type: 'bigint', rule: '系统生成，唯一且不可修改', purpose: '记录的稳定主键' },
  {
    code: 'deleted',
    name: '删除标识',
    type: 'smallint',
    rule: '默认 0；删除后为 1',
    purpose: '逻辑删除，保留历史数据'
  },
  { code: 'create_time', name: '创建时间', type: 'timestamp', rule: '创建时自动写入', purpose: '记录首次创建时间' },
  { code: 'creator', name: '创建人', type: 'varchar(64)', rule: '创建时写入操作人 ID', purpose: '关联底座用户身份' },
  {
    code: 'update_time',
    name: '修改时间',
    type: 'timestamp',
    rule: '创建及修改时自动写入',
    purpose: '记录最近一次修改时间'
  },
  {
    code: 'updater',
    name: '修改人',
    type: 'varchar(64)',
    rule: '创建及修改时写入操作人 ID',
    purpose: '记录最近一次操作人'
  }
]

export function systemFields(
  binding: TableBinding | undefined,
  fields: ObjectField[],
  options: Record<string, FieldOptions>,
  actualColumns?: DatabaseColumn[]
): SystemField[] {
  if (binding?.source === NC.ObjectSource.ADOPTED) {
    if (actualColumns)
      return actualColumns.flatMap(column => {
        const key = column.name === binding.keyColumn || column.primaryKeyPosition > 0
        const parent = column.name === binding.parentColumn
        const standard = standardFields.find(item => item.code === column.name)
        if (!key && !parent && !auditFieldNames.has(column.name)) return []
        return [
          {
            code: column.name,
            name: key ? '记录 ID' : parent ? '父记录 ID' : standard?.name || column.comment || column.name,
            type: column.nativeType,
            rule: '沿用实际字段定义',
            purpose: key ? '已有表主键' : parent ? '关联所属主记录' : standard?.purpose || '已有系统字段'
          }
        ]
      })
    // 已有表只展示已保存的真实映射，不补齐缺失字段，不假设自定义主键由平台生成。
    return fields.flatMap(field => {
      const option = options[field.key]
      const code = option?.columnName || field.code
      const key = code === binding.keyColumn || option?.primaryKey
      const parent = code === binding.parentColumn
      if (!key && !parent && !auditFieldNames.has(code)) return []
      const standard = standardFields.find(item => item.code === code)
      return [
        {
          code,
          name: key ? '记录 ID' : parent ? '父记录 ID' : standard?.name || field.name,
          type: option?.nativeType || field.type,
          rule: '沿用已有字段定义',
          purpose: key ? '已有表主键' : parent ? '关联所属主记录' : standard?.purpose || '已有系统字段'
        }
      ]
    })
  }
  const result = standardFields.map(field => ({ ...field }))
  if (binding?.parentColumn)
    result.push({
      code: binding.parentColumn,
      name: '父记录 ID',
      type: '与主表主键一致',
      rule: '保存明细时自动关联主记录',
      purpose: '确定内部明细的归属'
    })
  return result
}

import { decodeFieldDefault, encodeFieldDefault } from './field-defaults'
import { FieldType, MemberState } from '@/types/nocode/enums'
import type { ObjectField } from '@/types/nocode/object'
import type { ObjectDataClearColumn, ObjectDataModel } from '@/types/nocode/object-data'

export interface ObjectDataColumnChoice {
  value: string
  label: string
  detailId?: string
}

/** 主表和明细使用同一字段标识；只读/必填是否允许清空由后端给出真实原因。 */
export function objectDataColumnChoices(model: ObjectDataModel): ObjectDataColumnChoice[] {
  return [
    ...model.model.object.fields.map(field => ({
      value: field.id || field.key,
      label: `主表 · ${field.name}`
    })),
    ...(model.model.object.details || [])
      .filter(detail => detail.state === MemberState.ACTIVE)
      .flatMap(detail =>
        detail.fields.map(field => ({
          value: field.id || field.key,
          label: `${detail.name} · ${field.name}`,
          detailId: detail.id || undefined
        }))
      )
  ]
}

/** 清空使用完整列范围，不把网格的筛选、分页或定位记录带入请求。 */
export function objectDataColumnRequest(
  objectId: string,
  model: ObjectDataModel,
  fieldId: string
): ObjectDataClearColumn {
  const column = objectDataColumnChoices(model).find(item => item.value === fieldId)
  if (!column) throw new Error('当前已发布结构中已找不到此列，请重新选择。')
  return {
    objectId,
    versionNo: model.versionNo,
    checksum: model.checksum,
    fieldId,
    ...(column.detailId ? { detailId: column.detailId } : {})
  }
}

/** 网格沿用字段控件的字符串协议；大整数和小数绝不先转为 JavaScript number。 */
export function objectDataInput(field: ObjectField, value: unknown): string | null {
  return encodeFieldDefault(field, value)
}

export function objectDataValue(field: ObjectField, value: string | null): unknown {
  if (value == null || value === '') return null
  if (field.type === FieldType.BOOLEAN) return value === 'true'
  return decodeFieldDefault(field, value)
}

/** 只提交用户修改过的字段，自动字段、隐藏列和内部明细由服务端保留。 */
export function changedObjectData(
  fields: ObjectField[],
  original: Record<string, unknown>,
  editing: Record<string, string | null>,
  creating: boolean
): Record<string, unknown> {
  return Object.fromEntries(
    fields.flatMap(field => {
      const id = field.id || field.key
      if (!(id in editing)) return []
      const value = editing[id] ?? null
      if (!creating && objectDataInput(field, original[id]) === value) return []
      return [[id, objectDataValue(field, value)]]
    })
  )
}

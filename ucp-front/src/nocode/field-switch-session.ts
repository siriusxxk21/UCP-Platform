import type { ObjectField } from '@/types/nocode/object'
import type { FieldOptions } from '@/types/nocode/data-center'
import { copyFieldOptions } from './field-editing'

export interface FieldSwitchSnapshot {
  field: ObjectField
  options: FieldOptions
  targetObjectId: string | null
}

export function fieldSwitchSnapshot(
  field: ObjectField,
  options: FieldOptions,
  targetObjectId: string | null
): FieldSwitchSnapshot {
  return { field: { ...field }, options: copyFieldOptions(options), targetObjectId }
}

/** 取消转换仅撤回类型及其参数，保留用户已编辑的名称、编码、说明和数据分类。 */
export function restoreFieldSwitch(field: ObjectField, options: FieldOptions, snapshot: FieldSwitchSnapshot): void {
  for (const key of ['type', 'length', 'precision', 'scale', 'required', 'unique'] as const) {
    Object.assign(field, { [key]: snapshot.field[key] })
  }
  const retained = { description: options.description, classification: options.classification }
  for (const key of Object.keys(options)) delete (options as unknown as Record<string, unknown>)[key]
  Object.assign(options, copyFieldOptions(snapshot.options), retained)
}

/** 确认绑定完整候选参数，任何后续改动必须重新检查，名称等非转换属性不使确认失效。 */
export function fieldSwitchFingerprint(
  field: ObjectField,
  options: FieldOptions,
  targetObjectId: string | null
): string {
  return JSON.stringify({
    type: field.type,
    length: field.length,
    precision: field.precision,
    scale: field.scale,
    required: field.required,
    unique: field.unique,
    selection: options.selection,
    options: options.options,
    minimum: options.minimum,
    maximum: options.maximum,
    pattern: options.pattern,
    defaultValue: options.defaultValue,
    targetObjectId
  })
}

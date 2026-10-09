import type { InactiveField } from '@/types/nocode/data-center'
import type { ObjectField } from '@/types/nocode/object'
import { MemberState } from '@/types/nocode/enums'

export interface InactiveFieldCandidate extends InactiveField {
  persisted: boolean
}

/** 只按当前表比较编码；同名不同编码仍是独立字段。 */
export function inactiveFieldCodeError(field: ObjectField, inactive: InactiveField[]): string | null {
  const occupied = inactive.find(
    item => item.field.id !== field.id && (item.field.code === field.code || item.options.columnName === field.code)
  )
  return occupied
    ? `编码“${field.code}”已被停用字段“${occupied.field.name}”占用，请从“已停用字段”恢复原字段或更换编码`
    : null
}

export function fieldRestoreError(candidate: InactiveFieldCandidate, fields: ObjectField[]): string | null {
  if (!candidate.restorable) return candidate.blockedReason || '此字段不能直接恢复'
  if (fields.some(field => field.id === candidate.field.id)) return '此字段已在当前草稿中'
  if (fields.length >= 200) return '当前表已达到 200 个字段上限，请先调整字段'
  const conflict = fields.find(field => field.code === candidate.field.code)
  if (conflict) return `当前草稿的“${conflict.name}”已使用编码“${conflict.code}”，请先修改或移除该字段`
  return null
}

/** 返回独立副本，编辑恢复字段不会改写停用列表中的原配置。 */
export function restoredFieldValue(candidate: InactiveFieldCandidate) {
  const value = JSON.parse(JSON.stringify(candidate)) as InactiveFieldCandidate
  value.options.state = MemberState.ACTIVE
  return { field: value.field, options: value.options }
}

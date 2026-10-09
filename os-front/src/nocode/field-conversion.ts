import type { FieldConversion } from '@/types/nocode/data-center'
import { fieldTypes } from './object-draft'

/** 同一数据库类型也可能代表不同业务含义，例如整数与对象引用。 */
export function conversionTypeLabel(fieldType: string | undefined, nativeType: string): string {
  if (fieldType === 'REFERENCE') return '单选（对象引用）'
  const known = fieldTypes.find(item => item.value === fieldType)
  if (known) return known.label
  const type = nativeType.toLowerCase()
  if (/^(bigint|smallint|integer|int[248])$/.test(type)) return '整数'
  if (/^(numeric|decimal)/.test(type)) return '小数'
  if (/^(varchar|character varying)/.test(type)) return '单行文本'
  if (type === 'text') return '文本'
  if (type === 'boolean') return '开关'
  if (type === 'date') return '日期'
  if (type.startsWith('timestamp')) return '日期时间'
  return nativeType
}

/** 点击明确标注清空后果的发布按钮时，仅授权当前计划列出的非空清空列。 */
export function conversionClearFieldIds(conversions: FieldConversion[]): string[] {
  return conversions.filter(item => conversionClearsColumn(item) && item.affectedRows > 0).map(item => item.fieldId)
}

/** 发布按钮完整说明数据清空与应用暂停，不能只显示其中一种后果。 */
export function conversionPublishLabel(conversions: FieldConversion[], applicationCount = 0): string {
  const clear = conversions.filter(item => conversionClearsColumn(item) && item.affectedRows > 0)
  const count = clear.reduce((total, item) => total + item.affectedRows, 0)
  const actions: string[] = []
  if (clear.length)
    actions.push(clear.length === 1 ? `清空本列 ${count} 个值` : `清空 ${clear.length} 列共 ${count} 个值`)
  if (applicationCount) actions.push(`暂停 ${applicationCount} 个应用`)
  return actions.length ? `${actions.join('、')}并发布` : '确认发布'
}

/** 服务端仍核对精确列清单；保留值转换不得混入清空授权。 */
export function conversionConfirmationError(
  conversions: FieldConversion[],
  confirmedIds: string[],
  canManage: boolean
): string | null {
  const affected = conversions.filter(item => conversionClearsColumn(item) && item.affectedRows > 0)
  if (conversions.some(item => !conversionClearsColumn(item) && item.failedRows && item.failedRows > 0))
    return '部分旧值无法按目标规则转换，请修正数据或调整目标类型后重新检查'
  if (affected.some(item => !item.clearAllowed || item.impacts.some(impact => impact.blocking)))
    return '存在尚未处理的转换影响，请先处理后重新检查'
  if (affected.length && !canManage) return '清空历史字段值需要对象管理权限及对象修改权限，请联系有权限的管理员处理'
  if (affected.some(item => !confirmedIds.includes(item.fieldId))) return '清空范围与当前发布计划不一致，请重新检查'
  if (confirmedIds.some(id => !affected.some(item => item.fieldId === id)))
    return '确认内容与当前发布计划不一致，请重新检查'
  return null
}

/** 兼容旧发布计划的空 action；保留列值的约束修改不能被当作清空操作。 */
export function conversionClearsColumn(item: Pick<FieldConversion, 'action'>): boolean {
  return item.action == null || item.action === 'CLEAR_COLUMN'
}

/** 仅信任站内无代码管理入口，避免把接口里的外部地址作为跳转目标。 */
export function conversionImpactRoute(route: string | null): string | null {
  return route && (route.startsWith('/nocode/') || route.startsWith('/nocode-app/')) ? route : null
}

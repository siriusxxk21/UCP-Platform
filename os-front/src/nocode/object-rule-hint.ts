import type { PublishedDefinition } from '@/types/nocode/application'
import { linkageReadOnly } from './field-rules'

/** 数据对象编辑器的入口；新窗口打开，不打断当前表单设计。 */
export const objectEditorHref = (objectId: string) => `/nocode/object/editor?id=${encodeURIComponent(objectId)}`

/**
 * 表单设计器里对「对象上配了数据联动或引用筛选」的字段只读提示一行（实施设计稿 10.2）。
 * 规则只在数据对象上维护，表单不再有「关联带入」；没有规则时返回 null。
 */
export function objectRuleHint(definition: PublishedDefinition | undefined, fieldId: string): string | null {
  const rules = definition?.fieldOptions?.[fieldId]?.rules
  if (!rules) return null
  const parts: string[] = []
  if (rules.linkage) parts.push(`数据联动（只读：${linkageReadOnly(rules.linkage) ? '是' : '否'}）`)
  if (rules.reference?.filter?.length) parts.push('引用筛选')
  if (!parts.length) return null
  return `此字段在数据对象上配置了${parts.join('、')}，修改请到数据对象`
}

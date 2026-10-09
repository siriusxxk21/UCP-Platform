import type { PublishedDefinition } from '@/types/nocode/application'
import type { ObjectField } from '@/types/nocode/object'
import type { ObjectRelation } from '@/types/nocode/data-center'
import { FieldType, RelationType } from '@/types/nocode/enums'

/** 关系字段只用于应用呈现，保存仍提交 relations，不进入主表物理字段。 */
export const relationFieldId = (id: string) => `relation_${id}`
export const isRelationFieldId = (id: string) => id.startsWith('relation_')
export const fieldRelation = (relations: ObjectRelation[] = [], id?: string | null) =>
  id
    ? relations.find(r => r.fieldId === id || (r.kind === RelationType.MANY_TO_MANY && relationFieldId(r.id!) === id))
    : undefined
export function businessFields(definition: Pick<PublishedDefinition, 'fields' | 'relations'>): ObjectField[] {
  return [
    ...definition.fields,
    ...definition.relations
      .filter(r => r.kind === RelationType.MANY_TO_MANY)
      .map((r, index) => ({
        id: relationFieldId(r.id!),
        key: relationFieldId(r.id!),
        code: r.code,
        name: r.name,
        type: FieldType.MULTI_SELECT,
        length: null,
        precision: null,
        scale: null,
        required: r.required,
        unique: false,
        sort: definition.fields.length + index
      }))
  ]
}
export const linkableField = (field: ObjectField) =>
  ![
    FieldType.MULTI_SELECT,
    FieldType.IMAGE,
    FieldType.ATTACHMENT,
    FieldType.REGION,
    FieldType.CASCADE,
    FieldType.SUMMARY,
    FieldType.RICH_TEXT
  ].some(type => type === field.type)

/** 只从已经裁剪的可见字段中解析标题。 */
export function recordTitle(definition: PublishedDefinition, values: Record<string, unknown>): string {
  const template = definition.settings?.titleTemplate
  if (!template?.trim()) return String(values[definition.titleFieldId] ?? '未提供可见标题')
  let unavailable = false
  const result = template.replace(/\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*\}\}/g, (_, code: string) => {
    const field = definition.fields.find(f => f.code === code)
    if (!field || !Object.hasOwn(values, field.id!)) unavailable = true
    return String(field ? (values[field.id!] ?? '') : '')
  })
  return unavailable || !result.trim() ? '未提供可见标题' : result
}

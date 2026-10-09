import type { DatasetAnalysis, DatasetSource, ReportObjectVersion } from '@/types/nocode/report-center'
import type { ObjectField } from '@/types/nocode/object'
import type { FieldType } from '@/types/nocode/enums'
import type { DataScope } from '@/types/nocode/data-scope'

/** 编辑器始终使用稳定字段标识；改名不改变指标和条件的引用。 */
export function datasetKey(prefix: string) {
  return `${prefix}_${crypto.randomUUID().replaceAll('-', '')}`
}
export function newDatasetAnalysis(): DatasetAnalysis {
  return { schemaVersion: 1, metrics: [], fixedConditions: null, fieldFormats: {}, timeZone: 'Asia/Shanghai' }
}
export function fieldReferenced(analysis: DatasetAnalysis | null | undefined, id: string): boolean {
  const contains = (scope: DataScope | null | undefined): boolean =>
    !!scope && (scope.conditions.some(c => c.fieldId === id) || scope.groups.some(contains))
  return (
    !!analysis &&
    (analysis.metrics.some(m => m.fieldId === id || contains(m.conditions)) ||
      contains(analysis.fixedConditions) ||
      !!analysis.fieldFormats[id])
  )
}
export function sourceNodes(source: DatasetSource) {
  return [
    { path: [] as string[], reference: source.root },
    ...source.relations.map(r => ({ path: [...r.parentPath, r.id], reference: r.target }))
  ]
}
export function referenceKey(reference: { objectId: string; versionNo: number }) {
  return `${reference.objectId}:${reference.versionNo}`
}
export function datasetScopeFields(
  source: DatasetSource | null,
  catalog: Record<string, ReportObjectVersion>
): ObjectField[] {
  if (!source) return []
  const nodes = sourceNodes(source)
  return source.fields.flatMap((field, sort) => {
    const node = nodes.find(n => JSON.stringify(n.path) === JSON.stringify(field.path))
    const original = node && catalog[referenceKey(node.reference)]?.fields.find(f => f.id === field.sourceFieldId)
    return original
      ? [
          {
            key: field.id,
            id: field.id,
            code: field.id,
            name: field.name,
            type: original.type as FieldType,
            length: null,
            precision: null,
            scale: null,
            required: false,
            unique: false,
            sort
          }
        ]
      : []
  })
}

/** 删除关联时连同后代移除；已被分析引用的字段必须先由用户显式解除引用。 */
export function removeSourceBranch(
  source: DatasetSource,
  alias: string,
  analysis?: DatasetAnalysis | null
): DatasetSource {
  const fields = source.fields.filter(field => field.path.includes(alias))
  if (fields.some(field => fieldReferenced(analysis, field.id)))
    throw new Error('请先移除该关联字段的指标、固定条件和格式引用')
  return {
    ...source,
    relations: source.relations.filter(relation => relation.id !== alias && !relation.parentPath.includes(alias)),
    fields: source.fields.filter(field => !field.path.includes(alias))
  }
}

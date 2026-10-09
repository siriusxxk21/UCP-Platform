import type { FormConfig, RelatedFormBinding } from '@/types/nocode/application-ui'
import type { PublishedObject } from '@/types/nocode/application'
import { RelationType } from '@/types/nocode/enums'

export function relatedFormOptions(objectId: string, objects: Record<string, PublishedObject>) {
  return Object.values(objects).flatMap(({ definition: source }) =>
    source.relations.flatMap(relation => {
      if (relation.sourceDetailId || relation.kind === RelationType.MANY_TO_MANY) return []
      const incoming = relation.targetObjectId === objectId && source.objectId !== objectId
      if (!incoming && source.objectId !== objectId) return []
      const targetId = incoming ? source.objectId : relation.targetObjectId
      const target = objects[targetId]?.definition
      if (!target || targetId === objectId) return []
      return [
        {
          value: `${source.objectId}:${relation.id}:${incoming ? 'INCOMING' : 'OUTGOING'}`,
          label: `${target.objectName} · ${incoming && relation.kind !== RelationType.ONE_TO_ONE ? '多条记录' : '单条记录'}`,
          targetId,
          sourceObjectId: source.objectId,
          relationId: relation.id!,
          direction: incoming ? ('INCOMING' as const) : ('OUTGOING' as const)
        }
      ]
    })
  )
}
export function getRelatedWriteObjectIds(
  form: FormConfig | undefined,
  objects: Record<string, PublishedObject>
): string[] {
  return [...new Set((form?.relatedForms || []).map(binding => relatedTarget(binding, objects)).filter(Boolean))]
}
export function relatedTarget(binding: RelatedFormBinding, objects: Record<string, PublishedObject>): string {
  if (binding.direction === 'INCOMING') return binding.sourceObjectId
  return (
    objects[binding.sourceObjectId]?.definition.relations.find(r => r.id === binding.relationId)?.targetObjectId || ''
  )
}

import type { ApplicationApi } from '@/api/nocode/application'
import type { PublishedObject } from '@/types/nocode/application'
import { BusinessAction, RecordScope, type ApplicationMember, type ObjectGrant } from '@/types/nocode/authorization'
import { ALL, intersectSelection, isAll } from './selection-all'

/** 共享上限实时生效，字段名称跟随最新发布结构；此结果仅供授权展示，不修改应用的固定版本。 */
export async function loadApplicationSharing(
  api: Pick<ApplicationApi, 'sharing' | 'objectVersion'>,
  applicationId: string,
  references: Record<string, PublishedObject>
) {
  const referenced = Object.values(references)
  const [grants, results] = await Promise.all([
    api.sharing(applicationId),
    Promise.allSettled(referenced.map(object => api.objectVersion(object.objectId)))
  ])
  const objects: Record<string, PublishedObject> = {}
  const unavailable: string[] = []
  results.forEach((result, index) => {
    const previous = referenced[index]!
    if (result.status === 'rejected') {
      unavailable.push(previous.objectId)
      return
    }
    const latest = result.value
    // 历史授权仍可能引用旧成员；保留旧版名称，以最新版覆盖同一稳定 ID 的名称。
    const merge = <T extends { id: string | null }>(old: T[], current: T[]) => [
      ...new Map([...old, ...current].map(item => [item.id, item])).values()
    ]
    objects[previous.objectId] = {
      ...latest,
      definition: {
        ...latest.definition,
        fields: merge(previous.definition.fields, latest.definition.fields),
        details: merge(previous.definition.details, latest.definition.details),
        relations: merge(previous.definition.relations, latest.definition.relations)
      }
    }
  })
  return { grants, objects, unavailable }
}

/** 仅用于编辑器收紧旧配置；安全边界由服务端重新计算，不能依赖前端裁剪。 */
export function restrictObjectGrant(grant: ObjectGrant, ceiling: ObjectGrant): ObjectGrant {
  const both = <T extends string>(a: T[] = [], b: T[] = []) => a.filter(value => b.includes(value))
  return {
    objectId: grant.objectId,
    actions: both(grant.actions, ceiling.actions),
    scope: grant.scope === RecordScope.OWN || ceiling.scope === RecordScope.OWN ? RecordScope.OWN : RecordScope.ALL,
    readFields: intersectSelection(grant.readFields, ceiling.readFields),
    writeFields: intersectSelection(grant.writeFields, ceiling.writeFields),
    readDetails: intersectSelection(grant.readDetails, ceiling.readDetails),
    writeDetails: intersectSelection(grant.writeDetails, ceiling.writeDetails),
    readRelations: intersectSelection(grant.readRelations, ceiling.readRelations),
    writeRelations: intersectSelection(grant.writeRelations, ceiling.writeRelations),
    // 成员自定义条件不能在“收紧”操作中丢失；上限条件由服务端独立取交集。
    ...(grant.actionScopes
      ? {
          actionScopes: JSON.parse(
            JSON.stringify(
              Object.fromEntries(
                Object.entries(grant.actionScopes).filter(([action]) =>
                  both(grant.actions, ceiling.actions).includes(action as ObjectGrant['actions'][number])
                )
              )
            )
          )
        }
      : {})
  }
}

/**
 * 新建授权的默认值：可查看的三个清单默认「全部」；可修改的三个清单——默认操作里有新增或修改 ⇒「全部」，只有查看 ⇒ 空。
 * 操作与记录范围由使用处给，沿用各处原有口径。
 */
export function defaultObjectGrant(
  objectId: string,
  actions: ObjectGrant['actions'],
  scope: ObjectGrant['scope']
): ObjectGrant {
  const write = () =>
    actions.some(action => action === BusinessAction.CREATE || action === BusinessAction.UPDATE) ? [ALL] : []
  return {
    objectId,
    actions: [...actions],
    scope,
    readFields: [ALL],
    writeFields: write(),
    readDetails: [ALL],
    writeDetails: write(),
    readRelations: [ALL],
    writeRelations: write()
  }
}

const selectionKeys = [
  'readFields',
  'writeFields',
  'readDetails',
  'writeDetails',
  'readRelations',
  'writeRelations'
] as const

/** 保存后服务端从六个清单里去掉了几项（按上一层收紧，或清掉已停用的项）；「全部」不逐项计数。 */
export function countTightened(submitted: ApplicationMember[], saved: ApplicationMember[]): number {
  let count = 0
  for (const member of submitted) {
    const kept = saved.find(m => m.principalKind === member.principalKind && m.principalId === member.principalId)
    for (const grant of member.objects) {
      const result = kept?.objects.find(g => g.objectId === grant.objectId)
      if (!result) continue
      for (const key of selectionKeys) {
        const before = grant[key] || [],
          after = result[key] || []
        if (isAll(before) || isAll(after)) continue
        count += before.filter(id => !after.includes(id)).length
      }
    }
  }
  return count
}

import type { ApplicationDefinition, PublishedObject } from '@/types/nocode/application'
import { ResourceKind } from '@/types/nocode/application'
import { BusinessAction, type ObjectSharingGrant } from '@/types/nocode/authorization'
import { includesSelection } from './selection-all'

export interface PublishSharingIssue {
  key: string
  objectId: string
  objectName: string
  problem: string
  fix: string
}

/** 发布前展示实时授权缺项；实际发布仍由服务端在事务内重新校验，不能以此结果代替授权。 */
export function collectPublishSharingIssues(
  applicationId: string,
  applicationName: string,
  definition: ApplicationDefinition,
  objects: Record<string, PublishedObject>,
  grants: ObjectSharingGrant[]
): PublishSharingIssue[] {
  const issues: PublishSharingIssue[] = []
  const current = new Map(
    grants.filter(grant => grant.applicationId === applicationId).map(grant => [grant.objectId, grant])
  )
  const name = (id: string) => objects[id]?.definition.objectName || `名称暂无法读取（对象编号 ${id}）`
  for (const reference of definition.objects) {
    const sharing = current.get(reference.objectId)
    if (sharing?.permission) continue
    issues.push({
      key: `sharing:${reference.objectId}`,
      objectId: reference.objectId,
      objectName: name(reference.objectId),
      problem: sharing
        ? `授予应用“${applicationName}”的共享授权已被撤销。`
        : `尚未配置授予应用“${applicationName}”的共享授权。`,
      fix: `${sharing ? '重新授权' : '新增应用授权'}，选择“${applicationName}”，配置允许的操作、记录范围和可查看字段，填写变更说明并保存。`
    })
  }
  for (const resource of definition.resources) {
    if (resource.kind !== ResourceKind.NUMBER_RULE) continue
    const objectId = String(resource.config.objectId || '')
    const fieldId = String(resource.config.fieldId || '')
    const permission = current.get(objectId)?.permission
    // 未授权对象已单独列出，避免同一原因在每条自动编号规则中重复出现。
    if (!permission) continue
    const fieldName =
      objects[objectId]?.definition.fields.find(field => field.id === fieldId)?.name || `编号字段（${fieldId}）`
    const missing: string[] = []
    if (!permission.actions.includes(BusinessAction.CREATE)) missing.push('“新增”操作权限')
    if (!includesSelection(permission.writeFields, fieldId)) missing.push(`“${fieldName}”字段的填写和修改权限`)
    if (!missing.length) continue
    issues.push({
      key: `number-rule:${resource.id}`,
      objectId,
      objectName: name(objectId),
      problem: `自动编号“${resource.name}”缺少${missing.join('、')}。`,
      fix: `编辑授予应用“${applicationName}”的共享授权，补齐${missing.join('、')}，填写变更说明并保存。`
    })
  }
  return issues
}

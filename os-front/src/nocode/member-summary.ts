import { businessActionOptions, PrincipalKind, RecordScope } from '@/types/nocode/authorization'
import type { ApplicationMember, ObjectGrant } from '@/types/nocode/authorization'

/**
 * 应用「成员与数据权限」卡片折叠时的标题行（业务方 2026-10-04：「权限设置看起来太乱了。要能折叠起来。默认折叠。然后展开编辑。」）：
 * 一眼看出是谁、能访问哪些对象、每个对象的记录范围与允许操作。
 */
export interface GrantSummary {
  objectId: string
  objectName: string
  /** 全部记录 / 当前操作者创建的；设了记录条件时另加「有记录条件」。 */
  scope: string
  /** 允许操作，顿号连接；一个都没有时为「未允许任何操作」。 */
  actions: string
}
export interface MemberSummary {
  principal: string
  /** 可访问对象名，顿号连接；空串表示还没选。 */
  objects: string
  grants: GrantSummary[]
}

export function grantSummary(grant: ObjectGrant, objectName: string): GrantSummary {
  const conditioned = Object.keys(grant.actionScopes || {}).length > 0
  const scope =
    (grant.scope === RecordScope.ALL ? '全部记录' : '当前操作者创建的') + (conditioned ? ' · 有记录条件' : '')
  const actions = businessActionOptions
    .filter(action => grant.actions.includes(action.value))
    .map(action => action.label)
    .join('、')
  return { objectId: grant.objectId, objectName, scope, actions: actions || '未允许任何操作' }
}

export function memberSummary(
  member: ApplicationMember,
  objectName: (objectId: string) => string,
  principalName: string
): MemberSummary {
  const grants = member.objects.map(grant => grantSummary(grant, objectName(grant.objectId)))
  return { principal: principalName, objects: grants.map(g => g.objectName).join('、'), grants }
}

/** 标题行里的成员名称：还没选时说清楚要选什么。 */
export function principalLabel(
  member: ApplicationMember,
  users: Record<string, string>,
  roles: Array<{ label: string; value: string }>
): string {
  if (!member.principalId) return member.principalKind === PrincipalKind.ROLE ? '未选择角色' : '未选择成员'
  if (member.principalKind === PrincipalKind.ROLE)
    return '角色 · ' + (roles.find(role => role.value === member.principalId)?.label || member.principalId)
  return users[member.principalId] || `用户 ${member.principalId}`
}

/** 保存前在前端就能指出是哪一张卡片的问题：没选成员或角色、同一个成员或角色出现两次。 */
export function memberProblems(members: ApplicationMember[]): Array<{ index: number; message: string }> {
  const problems: Array<{ index: number; message: string }> = []
  const seen = new Map<string, number>()
  members.forEach((member, index) => {
    if (!member.principalId) {
      problems.push({
        index,
        message: `第 ${index + 1} 张授权还没有选择${member.principalKind === PrincipalKind.ROLE ? '角色' : '成员'}`
      })
      return
    }
    const key = member.principalKind + ':' + member.principalId
    const first = seen.get(key)
    if (first !== undefined)
      problems.push({
        index,
        message: `第 ${index + 1} 张授权与第 ${first + 1} 张是同一个${member.principalKind === PrincipalKind.ROLE ? '角色' : '成员'}，请合并`
      })
    else seen.set(key, index)
  })
  return problems
}

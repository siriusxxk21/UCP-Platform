import type { ApplicationUpgradeImpact } from '@/types/nocode/data-center'

/** 暂停应用与清空列分别确认，旧客户端或旧确认不能隐式停用应用。 */
export function applicationUpgradeConfirmationError(
  impacts: ApplicationUpgradeImpact[],
  confirmed: boolean,
  canManage: boolean
): string | null {
  if (!impacts.length) return null
  const blocked = impacts.find(app => app.blockers.length)
  if (blocked) return `${blocked.applicationName}：${blocked.blockers[0]}`
  if (!canManage) return '暂停应用需要应用管理权限，请联系应用管理员处理'
  return confirmed ? null : '请确认暂停以上受影响应用，再发布对象变更'
}

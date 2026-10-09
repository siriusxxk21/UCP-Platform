import { ApplicationStatus, type ApplicationRow } from '@/types/nocode/application'

type PublishApplication = Pick<ApplicationRow, 'status' | 'recoveryPending' | 'recoveryNeedsEdit'>

/** 普通停用和回收恢复都经发布并启用，只有回收恢复仍要求先人工保存草稿。 */
export function applicationRequiresPublishAndEnable(application?: PublishApplication): boolean {
  return !!application && (!!application.recoveryPending || application.status === ApplicationStatus.DISABLED)
}

export function applicationPublishBlockReason(
  application: PublishApplication | undefined,
  permissions: { publish: boolean; manage: boolean }
): string | null {
  if (!permissions.publish) return '发布需要应用发布权限，请联系应用管理员。'
  if (application?.recoveryPending && application.recoveryNeedsEdit) return '请先人工编辑并保存应用草稿'
  if (applicationRequiresPublishAndEnable(application) && !permissions.manage)
    return '发布并启用需要应用管理权限，请联系应用管理员。'
  return null
}

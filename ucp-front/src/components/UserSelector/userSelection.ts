/** 用户选择可用性判定；启用筛选时，状态缺失也不允许提交。 */
export function isSelectableUser(user: { status?: number }, enabledOnly: boolean): boolean {
  return !enabledOnly || user.status === 0
}

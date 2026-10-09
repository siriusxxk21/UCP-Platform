import { useUserStore } from '@/stores/user'

/** 权限指令绑定值，字符串和字符串数组均采用"任一命中"规则。 */
export type AccessBindingValue = string | string[]

/**
 * 判断当前授权集合是否包含任一目标值。
 * 空绑定值按无权限处理，避免权限标识配置错误时误展示操作入口。
 */
export function hasAnyAccess(required: AccessBindingValue, granted: string[], bypass = false): boolean {
  const values = (Array.isArray(required) ? required : [required]).map(value => value?.trim()).filter(Boolean)

  if (values.length === 0) return false
  if (bypass) return true
  const grantedSet = new Set(granted)
  return values.some(value => grantedSet.has(value))
}

export function hasPermission(permission: AccessBindingValue): boolean {
  const userStore = useUserStore()
  return hasAnyAccess(permission, [...userStore.permissions], userStore.roles.includes('super_admin'))
}

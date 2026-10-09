import type { LoginResult, PasswordChangeRequiredResult } from '@/types/auth'

interface PostLoginMenu {
  parentId?: number | string
  path?: string
}

/** 判断登录响应是否只允许进入强制改密流程。 */
export function isPasswordChangeRequired(
  result: LoginResult,
): result is PasswordChangeRequiredResult {
  return result.loginStatus === 'PASSWORD_CHANGE_REQUIRED'
}

/** 登录完成后统一进入基础平台首页。 */
export function resolvePostLoginPath(_menus: PostLoginMenu[]) {
  return '/dashboard'
}

/** 判断 BFCache 中的认证快照是否与当前浏览器存储一致。 */
export function isAuthSnapshotConsistent(
  memory: { token: string, passwordChangeToken: string },
  persisted: { token: string, passwordChangeToken: string },
) {
  return memory.token === persisted.token
    && memory.passwordChangeToken === persisted.passwordChangeToken
}

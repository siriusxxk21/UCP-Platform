import request from '@/utils/request'
import { useUserStore } from '@/stores/user'
import type { AuthPermissionInfo, LoginParams, LoginResult, LoginSuccessResult } from '@/types/auth'

// 重新导出类型，保持使用方的 import 兼容
export type { AuthPermissionInfo, LoginParams, LoginResult }

export function login(params: LoginParams) {
  return request.post<LoginResult>('/system/auth/login', params)
}

export function getUserInfo() {
  return request.get<AuthPermissionInfo>('/system/auth/get-permission-info')
}

export function refreshToken(refreshToken: string) {
  return request.post<LoginResult>('/system/auth/refresh-token', undefined, {
    params: { refreshToken },
    skipAuthRefresh: true
  } as any)
}

export function logout() {
  const credential = useUserStore().refreshToken || useUserStore().token
  return request.post<boolean>('/system/auth/logout', undefined, {
    headers: { Authorization: `Bearer ${credential}` },
    skipAuthRefresh: true
  } as any)
}

/** 使用登录预认证阶段签发的一次性凭证完成强制改密。 */
export function changeRequiredPassword(passwordChangeToken: string, newPassword: string) {
  return request.put<LoginSuccessResult>(
    '/system/auth/change-required-password',
    {
      passwordChangeToken,
      newPassword
    },
    {
      skipAuthRefresh: true
    }
  )
}

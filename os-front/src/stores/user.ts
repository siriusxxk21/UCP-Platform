import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { Menu, TenantInfo, UserInfo } from '@/types'
import { registerDynamicRoutes, resetDynamicRoutes } from '@/router'
import type { AuthPermissionInfo, AuthPermissionMenu } from '@/types/auth'
import { realtimeRuntime } from '@/realtime/runtime'
import { clearNavigationTabsSession, useNavigationTabsStore } from './navigationTabs'
import { resolveFixedNavigationTab } from '@/router/navigationTabs'
import { childMenus } from '@/utils/menuNavigation'

// ==================== localStorage 工具函数 ====================
const STORAGE_KEYS = {
  TOKEN: 'token',
  REFRESH_TOKEN: 'refreshToken',
  LAST_ACTIVITY_AT: 'lastActivityAt',
  EXPIRES_TIME: 'expiresTime',
  USER_INFO: 'userInfo',
  MENUS: 'menus',
  ROLES: 'roles',
  PERMISSIONS: 'permissions',
  TENANT_INFO: 'tenantInfo',
  PASSWORD_CHANGE_TOKEN: 'passwordChangeToken'
} as const

/** 从 localStorage 读取 JSON 数据 */
function loadStorage<T>(key: string, defaultValue: T): T {
  try {
    const stored = localStorage.getItem(key)
    return stored ? JSON.parse(stored) : defaultValue
  } catch {
    return defaultValue
  }
}

/** 保存数据到 localStorage */
function saveStorage<T>(key: string, value: T) {
  localStorage.setItem(key, JSON.stringify(value))
}

/** 清除多个 localStorage 项 */
function clearStorage(...keys: string[]) {
  keys.forEach(key => localStorage.removeItem(key))
}

function normalizeMenus(menuList: AuthPermissionMenu[], parentId: number | string = 0): Menu[] {
  return menuList.flatMap((menu, index) => {
    if (menu.visible === false) {
      return []
    }

    const normalized = {
      id: menu.id,
      name: menu.name,
      path: menu.path || '',
      component: menu.component,
      icon: menu.icon,
      parentId: menu.parentId ?? parentId,
      sort: menu.sort ?? index
    } as Menu

    return [normalized, ...normalizeMenus(menu.children || [], menu.id)]
  })
}

/** 报表来源权限暂时简化，兼容服务端菜单及本地缓存中的旧授权入口。 */
function currentMenus(menuList: Menu[]): Menu[] {
  return menuList.filter(menu => menu.path?.split(/[?#]/)[0] !== '/nocode/report-center/data-authorization')
}

// ==================== Store 定义 ====================
export const useUserStore = defineStore('user', () => {
  // State
  const token = ref<string>(localStorage.getItem(STORAGE_KEYS.TOKEN) || '')
  // 迁移旧版本当前会话的刷新凭证，关闭浏览器后仍能自动续期。
  const persistedRefreshToken =
    localStorage.getItem(STORAGE_KEYS.REFRESH_TOKEN) ||
    (token.value ? sessionStorage.getItem(STORAGE_KEYS.REFRESH_TOKEN) : '') ||
    ''
  if (persistedRefreshToken) localStorage.setItem(STORAGE_KEYS.REFRESH_TOKEN, persistedRefreshToken)
  sessionStorage.removeItem(STORAGE_KEYS.REFRESH_TOKEN)
  sessionStorage.removeItem(STORAGE_KEYS.LAST_ACTIVITY_AT)
  const refreshToken = ref<string>(persistedRefreshToken)
  const expiresTime = ref<string>(localStorage.getItem(STORAGE_KEYS.EXPIRES_TIME) || '')
  const userInfo = ref<UserInfo | null>(loadStorage(STORAGE_KEYS.USER_INFO, null))
  const menus = ref<Menu[]>(currentMenus(loadStorage(STORAGE_KEYS.MENUS, [])))
  const roles = ref<string[]>(loadStorage(STORAGE_KEYS.ROLES, []))
  const permissions = ref<string[]>(loadStorage(STORAGE_KEYS.PERMISSIONS, []))
  const accessInitialized = ref(
    localStorage.getItem(STORAGE_KEYS.ROLES) !== null && localStorage.getItem(STORAGE_KEYS.PERMISSIONS) !== null
  )
  const tenantInfo = ref<TenantInfo | null>(loadStorage(STORAGE_KEYS.TENANT_INFO, null))
  const passwordChangeToken = ref<string>(sessionStorage.getItem(STORAGE_KEYS.PASSWORD_CHANGE_TOKEN) || '')
  // 密码状态：不持久化到 localStorage，每次登录/刷新以 get-permission-info 接口为准
  const mustChangePassword = ref(false)
  const passwordRemainDays = ref<number | null>(null)
  const passwordRemindDays = ref<number>(7)
  const topMenus = computed(() => menus.value.filter(m => Number(m.parentId) === 0))

  // Actions
  const setToken = (newToken: string) => {
    token.value = newToken
    localStorage.setItem(STORAGE_KEYS.TOKEN, newToken)
  }

  const setAuthTokens = (accessToken: string, newRefreshToken?: string, newExpiresTime?: string) => {
    setToken(accessToken)
    if (newRefreshToken) {
      refreshToken.value = newRefreshToken
      localStorage.setItem(STORAGE_KEYS.REFRESH_TOKEN, newRefreshToken)
    }
    if (newExpiresTime) {
      expiresTime.value = newExpiresTime
      localStorage.setItem(STORAGE_KEYS.EXPIRES_TIME, newExpiresTime)
    }
  }

  const setUserInfo = (info: UserInfo) => {
    userInfo.value = info
    saveStorage(STORAGE_KEYS.USER_INFO, info)
  }

  const setTenantInfo = (info: TenantInfo | null) => {
    tenantInfo.value = info
    info ? saveStorage(STORAGE_KEYS.TENANT_INFO, info) : localStorage.removeItem(STORAGE_KEYS.TENANT_INFO)
  }

  const setMenus = (menuList: Menu[], needRegisterRoutes: boolean = false) => {
    menuList = currentMenus(menuList)
    menus.value = menuList
    saveStorage(STORAGE_KEYS.MENUS, menuList)
    if (userInfo.value?.id) {
      const fixedTab = resolveFixedNavigationTab(menuList)
      const navigationTabsStore = useNavigationTabsStore()
      navigationTabsStore.initialize(
        String(userInfo.value.id),
        String(tenantInfo.value?.id || userInfo.value.tenantId || ''),
        fixedTab
      )
      navigationTabsStore.syncSystemMenus(menuList, fixedTab)
    }
    if (needRegisterRoutes) {
      resetDynamicRoutes()
    }
    if (needRegisterRoutes && menuList.length > 0) {
      registerDynamicRoutes(menuList)
    }
  }

  const setAccess = (newRoles: string[] = [], newPermissions: string[] = []) => {
    roles.value = [...new Set(newRoles.filter(Boolean))]
    permissions.value = [...new Set(newPermissions.filter(Boolean))]
    accessInitialized.value = true
    saveStorage(STORAGE_KEYS.ROLES, roles.value)
    saveStorage(STORAGE_KEYS.PERMISSIONS, permissions.value)
  }

  const applyPermissionInfo = (permissionInfo: AuthPermissionInfo) => {
    const normalizedMenus = normalizeMenus(permissionInfo.menus || [])
    // 密码状态从 userInfo 剥离，单独存放且不持久化
    const {
      mustChangePassword: mcp,
      passwordRemainDays: prd,
      passwordRemindDays: prdRemind,
      ...userInfo
    } = permissionInfo.user as any
    mustChangePassword.value = mcp === true
    passwordRemainDays.value = prd ?? null
    passwordRemindDays.value = prdRemind ?? 7
    setUserInfo(userInfo)
    setAccess(permissionInfo.roles || [], permissionInfo.permissions || [])
    setMenus(normalizedMenus, true)
    return normalizedMenus
  }

  const getSideMenus = (topMenuId: string | number) => childMenus(menus.value, topMenuId)

  const initDynamicRoutes = (): boolean => {
    if (menus.value.length > 0) {
      registerDynamicRoutes(menus.value)
      return true
    }
    return false
  }

  const logout = () => {
    // 先隔离实时会话，避免旧连接的迟到消息进入下一位登录用户的页面。
    realtimeRuntime.resetSession()
    token.value = ''
    refreshToken.value = ''
    expiresTime.value = ''
    userInfo.value = null
    menus.value = []
    roles.value = []
    permissions.value = []
    accessInitialized.value = false
    tenantInfo.value = null
    passwordChangeToken.value = ''
    mustChangePassword.value = false
    passwordRemainDays.value = null
    passwordRemindDays.value = 7
    resetDynamicRoutes()
    useNavigationTabsStore().reset()
    clearNavigationTabsSession()
    clearStorage(
      STORAGE_KEYS.TOKEN,
      STORAGE_KEYS.REFRESH_TOKEN,
      STORAGE_KEYS.EXPIRES_TIME,
      STORAGE_KEYS.USER_INFO,
      STORAGE_KEYS.MENUS,
      STORAGE_KEYS.ROLES,
      STORAGE_KEYS.PERMISSIONS,
      STORAGE_KEYS.TENANT_INFO
    )
    sessionStorage.removeItem(STORAGE_KEYS.REFRESH_TOKEN)
    sessionStorage.removeItem(STORAGE_KEYS.LAST_ACTIVITY_AT)
    sessionStorage.removeItem(STORAGE_KEYS.PASSWORD_CHANGE_TOKEN)
  }

  /** 进入强制改密预认证态；该状态与正式登录态互斥。 */
  const beginPasswordChange = (ticket: string) => {
    logout()
    passwordChangeToken.value = ticket
    sessionStorage.setItem(STORAGE_KEYS.PASSWORD_CHANGE_TOKEN, ticket)
  }

  const clearPasswordChange = () => {
    passwordChangeToken.value = ''
    sessionStorage.removeItem(STORAGE_KEYS.PASSWORD_CHANGE_TOKEN)
  }

  const updateToken = (newToken: string) => setToken(newToken)

  return {
    token,
    refreshToken,
    expiresTime,
    userInfo,
    menus,
    roles,
    permissions,
    accessInitialized,
    tenantInfo,
    passwordChangeToken,
    mustChangePassword,
    passwordRemainDays,
    passwordRemindDays,
    topMenus,
    normalizeMenus,
    setToken,
    setUserInfo,
    setTenantInfo,
    setMenus,
    setAccess,
    applyPermissionInfo,
    getSideMenus,
    initDynamicRoutes,
    logout,
    beginPasswordChange,
    clearPasswordChange,
    setAuthTokens,
    updateToken
  }
})

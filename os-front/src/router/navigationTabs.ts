import type { RouteLocationNormalized } from 'vue-router'
import type { Menu } from '@/types'
import { menuQueryMatches, menuQuerySpecificity } from '@/utils/menuNavigation'

/** 全局访问标签的数据域。 */
export type NavigationTabScope = 'system'

/** 全局访问标签。标签身份由菜单决定，fullPath 仅表示再次进入时的恢复位置。 */
export interface NavigationTab {
  key: string
  scope: NavigationTabScope
  title: string
  menuId: string
  menuPath: string
  fullPath: string
  closable: boolean
  lastActiveAt: number
}

type RouteLike = Pick<RouteLocationNormalized, 'path' | 'fullPath' | 'query' | 'matched'>

function menuPathname(path?: string): string {
  return path?.split('?')[0].replace(/\/$/, '') || ''
}

function configuredQueryMatches(menuPath: string, route: RouteLike): boolean {
  const queryText = menuPath.split('?')[1]
  if (!queryText)
    return false
  return menuQueryMatches(menuPath, route.fullPath)
}

function isPathMatch(configuredPath: string, currentPath: string): boolean {
  const pathname = menuPathname(configuredPath)
  if (!pathname)
    return false
  if (pathname === currentPath)
    return true
  const parameterIndex = pathname.indexOf('/:')
  const basePath = parameterIndex >= 0 ? pathname.slice(0, parameterIndex) : pathname
  return basePath !== '/' && currentPath.startsWith(`${basePath}/`)
}

function findSystemMenu(menus: Menu[], route: RouteLike, parentPath?: string): Menu | undefined {
  const candidates = menus.filter(menu => !!menu.component && !!menu.path)
  const queryMatch = candidates
    .filter(menu => menuPathname(menu.path) === route.path && configuredQueryMatches(menu.path, route))
    .sort((left, right) => menuQuerySpecificity(right.path) - menuQuerySpecificity(left.path))[0]
  if (queryMatch)
    return queryMatch
  // 独立报表可直接挂菜单；只有没有当前页面入口时，才回落到设计管理列表。
  if (parentPath)
    return candidates.find(menu => menuPathname(menu.path) === menuPathname(parentPath))

  const exactMatch = candidates.find(menu => menuPathname(menu.path) === route.path && !menu.path.includes('?'))
  if (exactMatch)
    return exactMatch

  return candidates
    .filter(
      menu =>
        isPathMatch(menu.path, route.path) && (!menu.path.includes('?') || configuredQueryMatches(menu.path, route)),
    )
    .sort((left, right) => menuPathname(right.path).length - menuPathname(left.path).length)[0]
}

function routeMeta(route: RouteLike): Record<string, unknown> {
  return route.matched.reduce<Record<string, unknown>>((result, record) => ({ ...result, ...record.meta }), {})
}

/** 根据当前可访问菜单确定不可关闭的登录落地标签。 */
export function resolveFixedNavigationTab(menus: Menu[], now = Date.now()): NavigationTab | null {
  const clickableMenus = menus
    .filter(menu => !!menu.component && !!menu.path)
    .slice()
    .sort((left, right) => left.sort - right.sort)
  const menu = clickableMenus.find(item => menuPathname(item.path) === '/dashboard')
  if (!menu) {
    return {
      key: 'system:dashboard',
      scope: 'system',
      title: '首页',
      menuId: 'dashboard',
      menuPath: '/dashboard',
      fullPath: '/dashboard',
      closable: false,
      lastActiveAt: now,
    }
  }
  return {
    key: `system:${menu.id}`,
    scope: 'system',
    title: menu.name,
    menuId: String(menu.id),
    menuPath: menu.path,
    fullPath: menu.path,
    closable: false,
    lastActiveAt: now,
  }
}

/**
 * 将一次成功路由解析为菜单标签。
 * 隐藏路由只有显式声明 tabParentPath 时才归属于菜单，避免通知和跨入口详情页污染标签。
 */
export function resolveNavigationTab(route: RouteLike, systemMenus: Menu[], now = Date.now()): NavigationTab | null {
  const meta = routeMeta(route)
  const parentPath = typeof meta.tabParentPath === 'string' ? meta.tabParentPath : undefined
  if (meta.hidden === true && !parentPath)
    return null

  const menu = findSystemMenu(systemMenus, route, parentPath)
  if (!menu)
    return null
  return {
    key: `system:${menu.id}`,
    scope: 'system',
    title: menu.name,
    menuId: String(menu.id),
    menuPath: menu.path,
    fullPath: route.fullPath,
    closable: true,
    lastActiveAt: now,
  }
}

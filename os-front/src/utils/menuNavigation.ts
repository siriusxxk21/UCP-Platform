import type { Menu } from '@/types'

function getMenuPathname(path?: string): string {
  return path?.split(/[?#]/)[0] || ''
}

/** 同一运行页面可以挂载多个应用，固定查询参数也是菜单身份的一部分。 */
export function menuQueryMatches(menuPath: string, currentPath: string): boolean {
  const configured = new URLSearchParams(menuPath.split('?')[1]?.split('#')[0] || '')
  const current = new URLSearchParams(currentPath.split('?')[1]?.split('#')[0] || '')
  return Array.from(configured.entries()).every(
    ([key, value]) => current.getAll(key).length === 1 && current.get(key) === value,
  )
}

/** 同一路径下，页面入口比只带应用编号的旧入口更具体。 */
export function menuQuerySpecificity(path: string): number {
  return new URLSearchParams(path.split('?')[1]?.split('#')[0] || '').size
}

/** 菜单主键可能超过 JS 安全整数范围，父子关系必须保持字符串比较。 */
export function childMenus(menus: Menu[], parentId: string | number): Menu[] {
  return menus.filter(menu => String(menu.parentId) === String(parentId))
}

/** 从后端菜单 path 解析实际导航 URL（剥离路径占位符如 :id?、:id 等） */
export function resolveMenuNavPath(rawPath: string): string {
  if (!rawPath)
    return '/'
  let path = rawPath.startsWith('/') ? rawPath : `/${rawPath}`
  // 菜单 query 用于表达项目列表的范围（如 ?tab=managed）或挂载的应用（?id=41），导航时必须保留。
  path = path.replace(/\/:\w+\??/g, '')
  return path || '/'
}

/** 二级面板里点得动的菜单：分组标题（menuType 0）和没有地址的不算。 */
export function isClickableMenu(menu: Menu): boolean {
  return menu.menuType !== 0 && !!menu.path
}

/**
 * 点一级菜单该去哪：
 * 1. 有点得动的子菜单 ⇒ 排序后的第一个；
 * 2. 没有，但它自己就是页面（配了组件和地址）⇒ 它自己的地址，查询参数原样保留；
 * 3. 两样都没有 ⇒ undefined，调用方不跳转。
 */
export function resolveTopMenuNavPath(menus: Menu[], topMenuId: string | number): string | undefined {
  const firstChild = childMenus(menus, topMenuId)
    .filter(isClickableMenu)
    .sort((left, right) => left.sort - right.sort)[0]
  if (firstChild)
    return firstChild.path

  const topMenu = menus.find(menu => String(menu.id) === String(topMenuId))
  if (topMenu?.component && topMenu.path)
    return resolveMenuNavPath(topMenu.path)
  return undefined
}

/** 判断路由是否属于菜单页面，仅用于没有精确菜单配置时的兼容匹配。 */
export function isMenuPathMatch(menuPath: string | undefined, currentPath: string): boolean {
  const pathname = getMenuPathname(menuPath)
  if (!pathname || !menuQueryMatches(menuPath!, currentPath))
    return false
  const current = getMenuPathname(currentPath)
  if (pathname === current)
    return true

  if (pathname.includes('/:')) {
    const basePath = pathname.split('/:')[0]
    return current === basePath || current.startsWith(`${basePath}/`)
  }

  return pathname !== '/' && current.startsWith(`${pathname}/`)
}

/** 优先精确匹配菜单；详情等非菜单路由再选择路径最长的所属菜单。 */
export function findBestMatchingMenu(menus: Menu[], currentPath: string): Menu | undefined {
  return menus
    .filter(menu => isMenuPathMatch(menu.path, currentPath))
    .sort(
      (left, right) =>
        getMenuPathname(right.path).length - getMenuPathname(left.path).length
        || menuQuerySpecificity(right.path) - menuQuerySpecificity(left.path),
    )[0]
}

/**
 * 从缓存菜单的 parentId 关系解析当前页面所属的顶级菜单。
 * 菜单归属不依赖 URL 前缀，因为页面路径与菜单层级允许不同。
 */
export function findTopMenuFromCache(menus: Menu[], currentPath: string): Menu | undefined {
  let currentMenu = findBestMatchingMenu(
    menus.filter(menu => getMenuPathname(menu.path) === getMenuPathname(currentPath)),
    currentPath,
  )
  if (!currentMenu)
    return undefined

  const menuById = new Map(menus.map(menu => [String(menu.id), menu]))
  const visitedIds = new Set<string>()

  while (Number(currentMenu.parentId) !== 0) {
    const currentId = String(currentMenu.id)
    if (visitedIds.has(currentId))
      return undefined
    visitedIds.add(currentId)

    const parentMenu = menuById.get(String(currentMenu.parentId))
    if (!parentMenu)
      return undefined
    currentMenu = parentMenu
  }

  return currentMenu
}

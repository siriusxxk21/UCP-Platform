/**
 * 路由组件映射
 *
 * 使用 import.meta.glob 自动扫描 views/ 目录下所有 .vue 文件，
 * 新增页面无需在此注册，自动生效。
 *
 * 映射规则：后端返回的 component 字段（如 'system/user/index'）按以下顺序查找：
 *   1. ../views/{path}.vue     直接匹配
 *   2. ../views/{path}/index.vue  自动补 /index
 *   3. ../views/{path 去掉/index}.vue  移除 /index 后尝试
 *   4. aliasMap 中的别名映射
 *   5. 降级至 404 页面
 */
import type { RouteRecordRaw } from 'vue-router'

// 自动扫描 views/ 目录下所有 .vue 文件（懒加载）
const modules = import.meta.glob(['../views/**/*.vue', '!../views/bpm/_vben-src/**/*.vue'])

/**
 * 别名映射（仅用于后端 component 路径与实际文件路径不一致的特殊情况）
 * 正常页面按标准目录结构存放时无需在此添加
 */
const aliasMap: Record<string, string> = {}

/** Pages already registered as static routes. */
const STATIC_ROUTE_PATHS = new Set([
  '/dashboard',
  '/nocode-app/record-history',
  '/nocode-app/task-center',
  '/nocode-app/task-center/manage',
  '/nocode-app/task-center/launch',
  '/nocode-app/task-center/efficiency',
  '/nocode-app/task-center/templates'
])

/**
 * 根据组件路径获取组件导入函数
 * @param componentPath 后端返回的组件路径，如 'system/user/index'
 * @returns 组件导入函数（懒加载）
 */
export function getComponent(componentPath: string): () => Promise<any> {
  // 1. 优先检查别名映射
  if (aliasMap[componentPath] && modules[aliasMap[componentPath]]) {
    return modules[aliasMap[componentPath]] as () => Promise<any>
  }

  // 2. 直接匹配: ../views/{path}.vue
  const directKey = `../views/${componentPath}.vue`
  if (modules[directKey]) {
    return modules[directKey] as () => Promise<any>
  }

  // 3. 自动补充 /index.vue: ../views/{path}/index.vue
  const indexKey = `../views/${componentPath}/index.vue`
  if (modules[indexKey]) {
    return modules[indexKey] as () => Promise<any>
  }

  // 4. 移除 /index 后尝试: ../views/{path去掉/index}.vue
  const withoutIndexKey = `../views/${componentPath.replace(/\/index$/, '')}.vue`
  if (modules[withoutIndexKey]) {
    return modules[withoutIndexKey] as () => Promise<any>
  }

  // 5. 通用目录扫描降级：查找 ../views/{cleanPath}/*.vue （如 detail.vue / main.vue / 任意 .vue 文件）
  const cleanPath = componentPath.replace(/\/index$/, '')
  const dirPrefix = `../views/${cleanPath}/`
  const matchingKeys = Object.keys(modules).filter(k => k.startsWith(dirPrefix))
  if (matchingKeys.length > 0) {
    const preferredKey =
      matchingKeys.find(k => k.endsWith('/detail.vue') || k.endsWith('/main.vue') || k.endsWith('/index.vue')) ||
      matchingKeys[0]
    return modules[preferredKey] as () => Promise<any>
  }

  // 6. 降级：返回 404 页面
  console.warn(`[动态路由] 未找到组件: ${componentPath}`)
  return () => import('../views/login/404.vue')
}

/**
 * 生成动态路由配置
 * @param menu 后端返回的菜单项
 * @returns Vue Router 路由配置
 */
export function generateRouteFromMenu(menu: {
  id: number | string
  name: string
  path: string
  component?: string
  icon?: string
  parentId: number | string
  sort: number
}): RouteRecordRaw | null {
  // 没有 component 的菜单项不生成路由（可能是父级菜单或外链）
  if (!menu.component) {
    return null
  }

  const componentLoader = getComponent(menu.component)
  if (!componentLoader) {
    console.warn(`[动态路由] 未找到组件映射: ${menu.component}`)
    return null
  }

  // 处理路径（确保以 / 开头，并剥离 query string，注意保留可选路径参数如 :id?）
  let path = menu.path.startsWith('/') ? menu.path : `/${menu.path}`
  if (path.includes('?')) {
    const queryMatch = path.match(/^([^?]*?(?::[a-zA-Z0-9_]+\?)?[^?]*?)\?([a-zA-Z0-9_]+=.+)$/)
    if (queryMatch) {
      path = queryMatch[1]
    } else if (!/:\w+\?/.test(path)) {
      path = path.slice(0, path.indexOf('?'))
    }
  }

  if (STATIC_ROUTE_PATHS.has(path)) {
    return null
  }

  // 通用策略：若路径中未包含动态参数占位符，自动为其追加 (/:id?) 可选路径参数
  // 使所有通用视图天然兼顾根节点列表（如 /repository/images）与特定实体详情（如 /repository/images/123）
  if (!/\/:[a-zA-Z0-9_]+/.test(path)) {
    path = `${path}/:id?`
  }

  return {
    path,
    name: `${menu.name}_${menu.id}`, // 确保名称唯一
    component: componentLoader,
    props: true,
    meta: {
      title: menu.name,
      icon: menu.icon,
      menuId: menu.id,
      requiresAuth: true
    }
  }
}

/**
 * 批量生成动态路由配置
 * @param menus 后端返回的菜单列表
 * @returns 过滤后的路由配置数组
 */
export function generateRoutesFromMenus(
  menus: Array<{
    id: number | string
    name: string
    path: string
    component?: string
    icon?: string
    parentId: number | string
    sort: number
  }>
): RouteRecordRaw[] {
  const routes: RouteRecordRaw[] = []
  const registeredComponentsByPath = new Set<string>()

  menus.forEach(menu => {
    // 有组件的菜单都要有路由：一级菜单自己就是页面（parentId 为 0）时也一样，否则它没有自己的路由记录。
    if (menu.component) {
      const route = generateRouteFromMenu(menu)
      if (route) {
        // Query string describes a view of the same component, not a distinct router record.
        const routeKey = `${String(route.path)}::${menu.component}`
        if (registeredComponentsByPath.has(routeKey)) return
        registeredComponentsByPath.add(routeKey)
        routes.push(route)
      }
    }
  })

  return routes
}

import type { RouteRecordRaw } from 'vue-router'
import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'
import { message } from 'ant-design-vue'
import { generateRoutesFromMenus, getComponent } from './routeMap'
import { refreshAccessToken, isAuthRejection } from '@/utils/request'
import { resolveFixedNavigationTab, resolveNavigationTab } from './navigationTabs'
import { useNavigationTabsStore } from '@/stores/navigationTabs'
import { taskLaunchLocation } from '@/nocode/task-launch-navigation'

// 标记动态路由是否已注册
let dynamicRoutesRegistered = false
const dynamicRouteNames = new Set<string>()

function isTokenExpired(token: string, expiresTime?: string): boolean {
  if (expiresTime) {
    const expiresAt = new Date(expiresTime).getTime()
    if (!Number.isNaN(expiresAt)) {
      return expiresAt <= Date.now()
    }
  }
  try {
    const payload = token.split('.')[1]
    if (!payload) return false
    const normalized = payload.replace(/-/g, '+').replace(/_/g, '/')
    const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=')
    const decoded = JSON.parse(window.atob(padded))
    if (!decoded.exp) return false
    return decoded.exp * 1000 <= Date.now()
  } catch {
    return true
  }
}

/**
 * 常量路由 - 所有用户都可以访问
 */
export const constantRoutes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/login/index.vue'),
    meta: { public: true }
  },
  {
    // 强制改密页：全屏独立页（不嵌入 BasicLayout），需要登录但无菜单权限要求
    path: '/change-password',
    name: 'ChangePassword',
    component: () => import('../views/profile/ChangePassword.vue'),
    meta: { title: '修改密码', requiresAuth: true }
  }
]

/**
 * 隐藏路由 - 不在菜单中显示，但需要登录访问
 */
export const hiddenRoutes: RouteRecordRaw[] = [
  {
    path: '/nocode-app/flow-task',
    name: 'NocodeFlowTask',
    component: getComponent('nocode/application/flow-task'),
    meta: {
      title: '流程任务办理',
      requiresAuth: true,
      hidden: true,
      activeMenu: '/bpm/task/todo',
      tabParentPath: '/bpm/task/todo'
    }
  },
  {
    path: '/nocode-app/mine',
    name: 'NocodeMyApplications',
    component: getComponent('nocode/application/mine'),
    meta: { title: '我的应用', requiresAuth: true, hidden: true }
  },
  {
    path: '/nocode-app/process-record',
    name: 'NocodeProcessRecord',
    component: getComponent('nocode/application/process-record'),
    meta: { title: '流程业务记录', hidden: true, requiresAuth: true }
  },
  {
    path: '/nocode-app/runtime',
    name: 'NocodeApplicationRuntime',
    component: getComponent('nocode/application/runtime'),
    meta: { title: '应用运行', activeMenu: '/nocode-app/application', requiresAuth: true }
  },
  {
    path: '/nocode-app/workspace',
    name: 'NocodeApplicationWorkspace',
    component: getComponent('nocode/application/workspace'),
    meta: { title: '应用设计', hidden: true, requiresAuth: true, tabParentPath: '/nocode-app/application' }
  },
  {
    path: '/nocode/report-center/dashboard-editor',
    name: 'NocodeReportDashboardEditor',
    component: getComponent('nocode/report-center/dashboard-editor'),
    meta: { title: '仪表板设计', hidden: true, requiresAuth: true, tabParentPath: '/nocode/report-center/dashboards' }
  },
  {
    path: '/nocode/report-center/dashboard-view',
    name: 'NocodeReportDashboardView',
    component: getComponent('nocode/report-center/dashboard-view'),
    meta: { title: '仪表板查看', hidden: true, requiresAuth: true, tabParentPath: '/nocode/report-center/dashboards' }
  },
  {
    // 兼容已打开的旧授权地址；菜单入口已移除，此页仅展示无需配置的说明。
    path: '/nocode/report-center/data-authorization',
    name: 'NocodeReportDataAuthorizationNotice',
    component: getComponent('nocode/report-center/data-authorization'),
    meta: { title: '数据集权限说明', hidden: true, requiresAuth: true, tabParentPath: '/nocode/report-center/datasets' },
  },
  {
    path: '/nocode/report-center/dataset-editor',
    name: 'NocodeReportDatasetEditor',
    component: getComponent('nocode/report-center/dataset-editor'),
    meta: { title: '数据集设计', hidden: true, requiresAuth: true, tabParentPath: '/nocode/report-center/datasets' }
  },
  {
    path: '/nocode/object/editor',
    name: 'NocodeObjectEditor',
    component: getComponent('nocode/object/editor'),
    meta: { title: '对象设计', hidden: true, requiresAuth: true, tabParentPath: '/nocode/object' }
  },
  {
    path: '/profile',
    name: 'Profile',
    component: () => import('../views/system/Profile.vue'),
    meta: { title: '个人中心', hidden: true, requiresAuth: true }
  },
  {
    path: '/bpm/form/designer',
    name: 'BpmFormEditor',
    component: getComponent('bpm/form/designer/index'),
    meta: { title: '流程表单设计器', hidden: true, requiresAuth: true, tabParentPath: '/bpm/form' }
  },
  {
    path: '/bpm/process-instance/create',
    name: 'BpmProcessInstanceCreate',
    component: getComponent('bpm/processInstance/create/index'),
    meta: { title: '发起流程', hidden: true, requiresAuth: true }
  },

  {
    path: '/task/instance/detail',
    name: 'TaskInstanceDetail',
    component: getComponent('bpm/processInstance/detail/index'),
    meta: { title: '流程实例详情', hidden: true, requiresAuth: true }
  },
  {
    path: '/bpm/instance/detail',
    name: 'BpmInstanceDetail',
    component: getComponent('bpm/processInstance/detail/index'),
    meta: { title: '流程实例详情', hidden: true, requiresAuth: true }
  },
  {
    path: '/system/message',
    name: 'MessageCenter',
    component: getComponent('system/message/index'),
    meta: { title: '消息中心', hidden: true, requiresAuth: true }
  }
]

/**
 * Layout 路由 - 动态路由的父级容器
 */
export const layoutRoute: RouteRecordRaw = {
  path: '/',
  name: 'Layout',
  component: () => import('../layouts/BasicLayout.vue'),
  redirect: '/dashboard',
  children: [
    // Dashboard 首页作为静态路由，确保始终可访问
    {
      path: '/dashboard',
      name: 'Dashboard',
      component: () => import('../views/system/Dashboard.vue'),
      meta: { title: '首页', icon: 'HomeOutlined', requiresAuth: true }
    },
    {
      path: '/nocode-app/record-history',
      name: 'NocodeRecordHistory',
      component: getComponent('nocode/record-history/index'),
      meta: { title: '表格更新', icon: 'HistoryOutlined', requiresAuth: true }
    },
    // 隐藏路由（非菜单路由）
    {
      path: '/nocode-app/task-center',
      name: 'NocodeTaskCenter',
      component: () => import('../views/nocode/task-center/index.vue'),
      meta: { title: '我的任务', icon: 'AppstoreOutlined', requiresAuth: true }
    },
    ...[
      { path: 'manage', name: 'NocodeTaskManage', title: '任务管理' },
      { path: 'templates', name: 'NocodeTaskTemplates', title: '任务模板' }
    ].map(item => ({
      path: `/nocode-app/task-center/${item.path}`,
      name: item.name,
      component: () => import('../views/nocode/task-center/index.vue'),
      meta: { title: item.title, icon: 'AppstoreOutlined', requiresAuth: true }
    })),
    {
      path: '/nocode-app/task-center/efficiency',
      name: 'NocodeTaskEfficiency',
      component: () => import('../views/nocode/task-center/TaskEfficiency.vue'),
      meta: { title: '能效统计', icon: 'BarChartOutlined', requiresAuth: true }
    },
    {
      path: '/nocode-app/task-center/launch',
      name: 'NocodeTaskLaunch',
      redirect: to => taskLaunchLocation(to.query, to.hash),
      meta: { title: '任务管理', requiresAuth: true }
    },
    ...hiddenRoutes
    // 其他动态路由将在登录后添加到这里
  ]
}

/**
 * 404 路由 - 放在最后匹配
 */
export const notFoundRoute: RouteRecordRaw = {
  path: '/:pathMatch(.*)*',
  name: 'NotFound',
  component: () => import('../views/login/404.vue')
}

// 创建路由实例
const router = createRouter({
  history: createWebHistory(),
  routes: [...constantRoutes, layoutRoute, notFoundRoute]
})

/**
 * 注册动态路由
 * 根据用户菜单权限生成并添加路由
 * @param menus 用户菜单列表
 */
export function registerDynamicRoutes(
  menus: Array<{
    id: number | string
    name: string
    path: string
    component?: string
    icon?: string
    parentId: number | string
    sort: number
  }>
): void {
  if (dynamicRoutesRegistered) {
    resetDynamicRoutes()
  }

  // 生成动态路由配置
  const dynamicRoutes = generateRoutesFromMenus(menus)

  // 添加到 Layout 路由的子路由中
  dynamicRoutes.forEach(route => {
    router.addRoute('Layout', route)
    if (route.name) dynamicRouteNames.add(String(route.name))
  })

  dynamicRoutesRegistered = true
  console.log(`[动态路由] 成功注册 ${dynamicRoutes.length} 个路由`)
}

/**
 * 重置动态路由
 * 用于退出登录时清除已注册的路由
 */
export function resetDynamicRoutes(): void {
  dynamicRouteNames.forEach(name => {
    if (router.hasRoute(name)) router.removeRoute(name)
  })
  dynamicRouteNames.clear()
  dynamicRoutesRegistered = false
  // 注意：Vue Router 4 没有 removeRoute 的批量删除功能
  // 页面刷新时会重新创建 router 实例
}

/**
 * 检查路由是否已注册
 */
export function isDynamicRoutesRegistered(): boolean {
  return dynamicRoutesRegistered
}

// 路由守卫
router.beforeEach(async (to, _from, next) => {
  const userStore = useUserStore()

  // 强制改密预认证态与正式登录态互斥，只允许停留在改密页。
  if (to.path === '/change-password') {
    if (userStore.passwordChangeToken) {
      next()
    } else {
      next({ path: '/login', replace: true })
    }
    return
  }
  if (userStore.passwordChangeToken) {
    next({ path: '/change-password', replace: true })
    return
  }

  // 1. 公开路由直接放行
  if (to.meta.public) {
    next()
    return
  }

  // 2. 检查登录状态
  if (!userStore.token) {
    message.warning('请先登录')
    next('/login')
    return
  }
  if (isTokenExpired(userStore.token, userStore.expiresTime)) {
    // Token 已过期，先尝试刷新；刷新失败才登出
    try {
      await refreshAccessToken()
    } catch (error) {
      if (!isAuthRejection(error)) {
        message.warning('暂时无法连接服务器，请稍后重试')
        next()
        return
      }
      message.warning('登录已过期，请重新登录')
      userStore.logout()
      next('/login')
      return
    }
  }

  // 2.5 强制改密：初始密码未修改或密码已过期，仅允许访问改密页
  if (userStore.mustChangePassword && to.path !== '/change-password') {
    next({ path: '/change-password', replace: true })
    return
  }

  // 3. 已登录但未注册动态路由，需要注册
  if (!dynamicRoutesRegistered && userStore.menus.length > 0) {
    console.log('[路由守卫] 检测到未注册动态路由，开始注册...')
    registerDynamicRoutes(userStore.menus)

    // 注册后重新导航到目标路由
    // 使用 replace: true 避免历史记录问题
    next({ ...to, replace: true })
    return
  }
  // 4. 动态路由已注册但路由不存在（可能是直接访问某个路由）
  // 检查路由是否存在
  if (dynamicRoutesRegistered && to.matched.length === 0) {
    console.warn(`[路由守卫] 路由未找到: ${to.path}`)
    // 如果路由不存在，重定向到基础平台首页
    next('/dashboard')
    return
  }

  // 5. 正常放行
  next()
})

router.afterEach((to, _from, failure) => {
  if (!failure) {
    const userStore = useUserStore()
    const navigationTabsStore = useNavigationTabsStore()
    if (userStore.userInfo?.id) {
      const fixedTab = resolveFixedNavigationTab(userStore.menus)
      navigationTabsStore.initialize(
        String(userStore.userInfo.id),
        String(userStore.tenantInfo?.id || userStore.userInfo.tenantId || ''),
        fixedTab
      )
      navigationTabsStore.syncSystemMenus(userStore.menus, fixedTab)
      const tab = resolveNavigationTab(to, userStore.menus)
      if (tab) navigationTabsStore.register(tab)
    }
  }
})

export default router

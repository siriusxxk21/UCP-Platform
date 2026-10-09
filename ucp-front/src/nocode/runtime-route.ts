import type { RouteLocationNormalizedLoaded } from 'vue-router'
import type { InjectionKey } from 'vue'
import { APPLICATION_RUNTIME_PATH } from './application-entry'
import { taskCenterViewKey } from './task-launch-navigation'
import type { RouteLike as ActivityRoute } from './page-activity'

type RouteLike = Pick<RouteLocationNormalizedLoaded, 'path' | 'fullPath' | 'query'>

/** 外层路由出口判断普通页面是否仍在原宿主；切入平台页签时即使应用相同也会销毁普通宿主。 */
export const plainRouteSurvivalKey: InjectionKey<(to: ActivityRoute) => boolean> = Symbol.for(
  'richuang.route.plain-survival'
)

/** 这个地址是不是应用运行页（动态路由给它补了可选的路径参数）。 */
export const isApplicationRuntimeRoute = (route: RouteLike) =>
  route.path === APPLICATION_RUNTIME_PATH || route.path.startsWith(APPLICATION_RUNTIME_PATH + '/')

/**
 * 不保活的页面用什么 key 决定重建。
 * 应用运行页按「路径 + 应用」：应用内切菜单、对象、视图只是地址参数在变，页面不重建、应用定义不重取。
 * 正式任务页自行响应深链，其余页面按完整地址重建。
 */
export function plainRouteKey(route: RouteLike): string {
  return isApplicationRuntimeRoute(route)
    ? `${route.path}?id=${String(route.query.id || '')}`
    : taskCenterViewKey(route)
}

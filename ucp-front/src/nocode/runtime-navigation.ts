import type { InjectionKey, Ref } from 'vue'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import { APPLICATION_RUNTIME_PATH } from './application-entry'
import type { Menu } from '@/types/system/menu'

/** 服务端已裁剪的资源才参与导航；记录详情仍由带上下文的业务操作打开。 */
export function isStandaloneApplicationPage(resource: ApplicationResource | undefined): boolean {
  return (
    !!resource &&
    [ResourceKind.PAGE, ResourceKind.VIEW, ResourceKind.REPORT_DASHBOARD].some(kind => kind === resource.kind) &&
    !resource.config.contextObjectId
  )
}

export function applicationPagePath(applicationId: string, resourceId: string): string {
  return `${APPLICATION_RUNTIME_PATH}?${new URLSearchParams({ id: applicationId, page: resourceId })}`
}

export function publishedNavigation(resources: ApplicationResource[]): ApplicationResource[] {
  const managedTargets = new Set(
    resources
      .filter(resource => resource.kind === ResourceKind.MENU && resource.config.navigationVersion === 2)
      .map(resource => resource.config.targetId)
  )
  return resources
    .filter(
      resource =>
        resource.kind === ResourceKind.MENU &&
        (resource.config.navigationVersion === 2 || !managedTargets.has(resource.config.targetId)) &&
        (resource.config.navigationVersion !== 2 || resource.config.showInMenu === true) &&
        isStandaloneApplicationPage(resources.find(target => target.id === resource.config.targetId))
    )
    .map((resource, index) => ({ resource, index }))
    .sort(
      (left, right) =>
        Number(left.resource.config.sort ?? left.index * 10) - Number(right.resource.config.sort ?? right.index * 10)
    )
    .map(item => item.resource)
}

/** 平台侧栏只采用当前账号真正获授的页面入口，不补写角色授权。 */
export function mountedApplicationPages(menus: Menu[], applicationId: string): Menu[] {
  return menus.filter(menu => {
    const [path, query] = menu.path.split('?')
    const params = new URLSearchParams(query)
    return (
      path === APPLICATION_RUNTIME_PATH &&
      params.getAll('id').length === 1 &&
      params.get('id') === applicationId &&
      params.getAll('page').length === 1
    )
  })
}

export function applicationHome(resources: ApplicationResource[]): ApplicationResource | undefined {
  const home = resources.find(
    resource =>
      resource.kind === ResourceKind.MENU &&
      resource.config.navigationVersion === 2 &&
      resource.config.defaultHome === true &&
      isStandaloneApplicationPage(resources.find(target => target.id === resource.config.targetId))
  )
  const first = home || publishedNavigation(resources)[0]
  return resources.find(resource => resource.id === first?.config.targetId)
}

export function pageNavigationName(menu: ApplicationResource, resources: ApplicationResource[]): string {
  const target = resources.find(resource => resource.id === menu.config.targetId)
  return menu.config.navigationVersion === 2 ? String(menu.config.menuName || target?.name || menu.name) : menu.name
}

export interface RuntimeNavigationItem {
  id: string
  name: string
  path: string
  icon?: string
}

/** 仅当前运行页向公共布局提供上下文；不写入系统角色菜单或登录缓存。 */
export interface RuntimeNavigationContext {
  owner: symbol
  applicationId: string
  name: string
  items: RuntimeNavigationItem[]
}

export const runtimeNavigationKey: InjectionKey<Ref<RuntimeNavigationContext | undefined>> = Symbol(
  'application-runtime-navigation'
)

import { v4 as uuidv4 } from 'uuid'
import { ResourceKind, type ApplicationResource } from '@/types/nocode/application'
import type { MenuConfig } from '@/types/nocode/application-ui'
import type { Menu } from '@/types/system/menu'
import { resourceSnapshot } from './application-resource'

/** 页面保留原资源身份，MENU 仅作为发布入口配置及旧按钮地址的兼容载体。 */
export interface PageNavigationSettings {
  resourceName: string
  showInMenu: boolean
  platformParentId: string
  menuName: string
  icon: string
  sort: number
  defaultHome: boolean
}

export function supportsPageNavigation(resource: ApplicationResource): boolean {
  return ([ResourceKind.PAGE, ResourceKind.VIEW, ResourceKind.REPORT_DASHBOARD] as ResourceKind[]).includes(
    resource.kind
  )
}

export function pageNavigationUnavailableReason(resource: ApplicationResource): string {
  if (!supportsPageNavigation(resource)) return '此资源用于页面组合，不支持独立菜单入口'
  if (resource.config.contextObjectId) return '此页面需要一条业务记录，请通过列表详情或页面按钮打开'
  return ''
}

export function pageNavigation(resources: ApplicationResource[], resourceId: string): ApplicationResource | undefined {
  const entries = resources.filter(
    resource => resource.kind === ResourceKind.MENU && resource.config.targetId === resourceId
  )
  return entries.find(resource => resource.config.navigationVersion === 2) || entries[0]
}

export function pageNavigationSettings(
  resources: ApplicationResource[],
  resource: ApplicationResource
): PageNavigationSettings {
  const entry = pageNavigation(resources, resource.id)
  const config = entry?.config as unknown as MenuConfig | undefined
  return {
    resourceName: resource.name,
    showInMenu: config?.navigationVersion === 2 && config.showInMenu === true,
    platformParentId: config?.platformParentId || '',
    menuName: (config?.menuName || entry?.name || resource.name).slice(0, 50),
    icon: config?.icon || (resource.kind === ResourceKind.VIEW ? 'TableOutlined' : 'FileTextOutlined'),
    sort: config?.sort ?? resources.filter(item => item.kind === ResourceKind.MENU).length * 10 + 10,
    defaultHome: config?.defaultHome === true
  }
}

/** 仅生成新的草稿数组；不调用系统菜单写接口，也不修改传入的已保存配置。 */
export function applyPageNavigation(
  resources: ApplicationResource[],
  resourceId: string,
  settings: PageNavigationSettings,
  directories: Pick<Menu, 'id' | 'name'>[]
): ApplicationResource[] {
  const resource = resources.find(item => item.id === resourceId)
  if (!resource || !supportsPageNavigation(resource)) throw new Error('页面已不存在，请刷新后重试')
  const resourceName = settings.resourceName.trim()
  if (!resourceName || resourceName.length > 160) throw new Error('页面名称须为 1 至 160 个字符')
  const unavailable = pageNavigationUnavailableReason(resource)
  if ((settings.showInMenu || settings.defaultHome) && unavailable) throw new Error(unavailable)
  if (settings.showInMenu && !directories.some(directory => directory.id === settings.platformParentId))
    throw new Error('请选择已启用且可见的平台一级目录')
  const menuName = settings.menuName.trim() || resourceName
  if (menuName.length > 50) throw new Error('菜单名称不能超过 50 个字符')
  if (!Number.isInteger(settings.sort) || settings.sort < 0 || settings.sort > 9999)
    throw new Error('排序须为 0 至 9999 的整数')
  const existing = pageNavigation(resources, resourceId)
  const config: MenuConfig = {
    targetId: resourceId,
    navigationVersion: 2,
    showInMenu: settings.showInMenu,
    platformParentId: settings.platformParentId || undefined,
    menuName,
    icon: settings.icon,
    sort: settings.sort,
    defaultHome: settings.defaultHome
  }
  const id = existing?.id || uuidv4()
  // 旧 MENU 的 id/code 可能已被页面按钮引用，设置或隐藏入口均复用原身份。
  const entry: ApplicationResource = {
    ...(existing
      ? resourceSnapshot(existing)
      : { id, kind: ResourceKind.MENU, code: `menu_${id.replaceAll('-', '')}` }),
    name: menuName,
    config: config as unknown as Record<string, unknown>
  }
  const updated = resources.map(item => {
    if (item.id === resourceId) return { ...resourceSnapshot(item), name: resourceName }
    if (item.id === existing?.id) return entry
    if (settings.defaultHome && item.kind === ResourceKind.MENU && item.config.defaultHome === true)
      return { ...resourceSnapshot(item), config: { ...item.config, defaultHome: false } }
    return item
  })
  if (!existing) updated.push(entry)
  return updated
}

const resourceReferenceKeys = new Set(['resourceId', 'viewId', 'formId', 'detailPageId', 'targetId'])

function referencesResource(value: unknown, ids: Set<string>): boolean {
  if (Array.isArray(value)) return value.some(child => referencesResource(child, ids))
  if (!value || typeof value !== 'object') return false
  return Object.entries(value).some(
    ([key, child]) =>
      (resourceReferenceKeys.has(key) && typeof child === 'string' && ids.has(child)) || referencesResource(child, ids)
  )
}

/** 删除页面时一并移除其入口；仍引用页面或旧入口地址的按钮、详情页和组合视图须先改绑。 */
export function pageRemovalReferences(resources: ApplicationResource[], resourceId: string): ApplicationResource[] {
  const ids = new Set([
    resourceId,
    ...resources
      .filter(item => item.kind === ResourceKind.MENU && item.config.targetId === resourceId)
      .map(item => item.id)
  ])
  return resources.filter(item => !ids.has(item.id) && referencesResource(item.config, ids))
}

export function removePageAndNavigation(resources: ApplicationResource[], resourceId: string): ApplicationResource[] {
  const references = pageRemovalReferences(resources, resourceId)
  if (references.length) throw new Error(`请先调整使用此页面的资源：${references.map(item => item.name).join('、')}`)
  return resources.filter(
    item => item.id !== resourceId && !(item.kind === ResourceKind.MENU && item.config.targetId === resourceId)
  )
}

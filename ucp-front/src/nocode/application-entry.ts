import type { CreateMenuParams, Menu } from '@/types/system/menu'
import { MenuStatus, MenuType } from '@/types/system/menu'

export const APPLICATION_RUNTIME_PATH = '/nocode-app/runtime'
export const APPLICATION_RUNTIME_COMPONENT = 'nocode/application/runtime'

/** 平台菜单只保存运行入口，应用内导航仍由发布快照管理。 */
export function applicationEntryPath(applicationId: string): string {
  if (!/^[1-9]\d*$/.test(applicationId)) throw new Error('应用编号无效')
  return `${APPLICATION_RUNTIME_PATH}?id=${applicationId}`
}

export function flattenMenus(menus: Menu[]): Menu[] {
  return menus.flatMap(menu => [menu, ...flattenMenus(menu.children || [])])
}

/** 只识别完整应用入口，不接管指向某条记录或应用内页面的手工菜单。 */
export function applicationEntries(menus: Menu[], applicationId: string): Menu[] {
  return flattenMenus(menus).filter(menu => {
    const [path, query = ''] = (menu.path || '').split('?')
    const params = new URLSearchParams(query)
    return (
      menu.menuType === MenuType.MENU &&
      menu.component === APPLICATION_RUNTIME_COMPONENT &&
      path === APPLICATION_RUNTIME_PATH &&
      params.size === 1 &&
      params.get('id') === applicationId
    )
  })
}

/** 首版适配底座左侧两级导航：一级目录下挂应用入口。 */
export function platformEntryDirectories(menus: Menu[]): Menu[] {
  return menus.filter(
    menu =>
      String(menu.parentId) === '0' &&
      menu.menuType === MenuType.DIRECTORY &&
      menu.status === MenuStatus.ENABLED &&
      menu.visible !== false
  )
}

export interface PlatformEntryForm {
  parentId: string
  name: string
  icon: string
  sort: number
  status: MenuStatus
  visible: boolean
}

export function applicationEntryPayload(
  applicationId: string,
  form: PlatformEntryForm,
  existing?: Menu
): CreateMenuParams {
  return {
    ...form,
    name: form.name.trim(),
    path: applicationEntryPath(applicationId),
    menuType: MenuType.MENU,
    component: APPLICATION_RUNTIME_COMPONENT,
    componentName: existing?.componentName || `NocodeApplication_${applicationId}`,
    // 已有入口若在底座手工设置权限标识，编辑挂载位置时不将其抹掉。
    permission: existing?.permission || '',
    keepAlive: false,
    alwaysShow: true
  }
}

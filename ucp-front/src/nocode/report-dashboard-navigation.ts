import type { DashboardContent } from '@/types/nocode/report-dashboard'
import type { PlatformNavigationSettings } from '@/types/nocode/platform-navigation'
import type { Menu } from '@/types/system/menu'
import { platformEntryDirectories } from './application-entry'

/** 打开设置只建立编辑副本；旧看板在用户应用设置前不产生导航字段。 */
export function dashboardNavigationSettings(content: DashboardContent): PlatformNavigationSettings {
  return {
    showInMenu: content.navigation?.showInMenu ?? false,
    platformParentId: content.navigation?.platformParentId || '',
    menuName: content.navigation?.menuName ?? content.name.slice(0, 50),
    icon: content.navigation?.icon ?? 'BarChartOutlined',
    sort: content.navigation?.sort ?? 10
  }
}

export function validateDashboardNavigation(
  settings: PlatformNavigationSettings,
  directories: Menu[]
): PlatformNavigationSettings {
  const value = { ...settings, menuName: settings.menuName.trim() }
  if (value.showInMenu && !platformEntryDirectories(directories).some(item => item.id === value.platformParentId))
    throw new Error('请选择已启用且可见的平台一级目录')
  if (value.showInMenu && !value.menuName) throw new Error('请填写菜单名称')
  if (value.menuName.length > 50) throw new Error('菜单名称不能超过 50 个字符')
  if (!Number.isInteger(value.sort) || value.sort < 0 || value.sort > 9999)
    throw new Error('菜单排序须为 0 至 9999 的整数')
  return value
}

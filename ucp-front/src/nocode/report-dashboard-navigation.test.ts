import { describe, expect, it } from 'vitest'
import { dashboardNavigationSettings, validateDashboardNavigation } from './report-dashboard-navigation'
import { copyDashboard } from './report-dashboard'
import type { DashboardContent } from '@/types/nocode/report-dashboard'
import { MenuStatus, MenuType, type Menu } from '@/types/system/menu'
const board: DashboardContent = { schemaVersion: 1, name: '经营分析', description: '', charts: [] }
const parent: Menu = {
  id: '7',
  parentId: '0',
  name: '经营',
  path: '/business',
  sort: 1,
  menuType: MenuType.DIRECTORY,
  status: MenuStatus.ENABLED,
  visible: true
}
describe('独立仪表板菜单配置', () => {
  it('旧配置无导航字段，编辑副本默认关闭且不改写原内容', () => {
    const settings = dashboardNavigationSettings(board)
    expect(settings).toMatchObject({ showInMenu: false, menuName: '经营分析' })
    settings.showInMenu = true
    expect(board.navigation).toBeUndefined()
  })
  it('只允许有效平台一级目录，拒绝子菜单、停用和隐藏目录', () => {
    const settings = { ...dashboardNavigationSettings(board), showInMenu: true, platformParentId: '7' }
    expect(validateDashboardNavigation(settings, [parent])).toEqual(settings)
    for (const patch of [{ parentId: '8' }, { status: 1 }, { visible: false }, { menuType: MenuType.MENU }])
      expect(() => validateDashboardNavigation(settings, [{ ...parent, ...patch }])).toThrow('一级目录')
  })
  it('隐藏可保留已失效目录配置；复制历史快照时不共享引用', () => {
    const navigation = { ...dashboardNavigationSettings(board), platformParentId: 'removed' }
    expect(validateDashboardNavigation(navigation, [])).toEqual(navigation)
    const source = { ...board, navigation }
    const copied = copyDashboard(source)
    copied.navigation!.menuName = '另一个标题'
    expect(source.navigation.menuName).toBe('经营分析')
  })
  it('拒绝空名称、过长名称和非法排序', () => {
    const settings = { ...dashboardNavigationSettings(board), showInMenu: true, platformParentId: '7' }
    expect(() => validateDashboardNavigation({ ...settings, menuName: ' ' }, [parent])).toThrow('名称')
    expect(() => validateDashboardNavigation({ ...settings, menuName: '字'.repeat(51) }, [parent])).toThrow('50')
    for (const sort of [-1, 1.5, 10000, Number.NaN])
      expect(() => validateDashboardNavigation({ ...settings, sort }, [parent])).toThrow('排序')
  })
})
